package com.marvice.officetv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * Meeting room (spec screen 4): a hub with room status and cards for Mirror laptop, Cast, Meeting timer,
 * Stand-up and the laptop's HDMI port, plus one full-screen card per mode. Back returns to the card that
 * opened a mode, and from the hub to Home.
 */
public class MeetingRoomActivity extends Activity {
    static final String EXTRA_MODE = "mode";
    static final String EXTRA_NO_CABLE = "no_cable";
    static final String MODE_HUB = "hub", MODE_MIRROR = "mirror", MODE_CAST = "cast",
            MODE_TIMER = "timer", MODE_STANDUP = "standup";

    private final Handler main = new Handler(Looper.getMainLooper());
    private String mode;
    private boolean noCable;
    private String lastHubCard;

    // Timer state
    private long timerEnd;
    private boolean warned;
    private TextView bigText, subText, bannerText;

    // Stand-up state
    private String[] team;
    private int speaker;
    private long standupStart, slotStart;
    private long slotMs;
    private boolean standupDone;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            onTick();
            main.postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        noCable = getIntent().getBooleanExtra(EXTRA_NO_CABLE, false);
        String m = getIntent().getStringExtra(EXTRA_MODE);
        show(m == null ? MODE_HUB : m);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    @Override
    public void onBackPressed() {
        if (!MODE_HUB.equals(mode)) {
            show(MODE_HUB);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (MODE_STANDUP.equals(mode) && !standupDone && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            nextSpeaker();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- Modes ------------------------------------------------------------------------------------

    private void show(String m) {
        if (!MODE_HUB.equals(m)) lastHubCard = m;
        mode = m;
        bigText = subText = bannerText = null;
        switch (m) {
            case MODE_MIRROR: showMirror(); break;
            case MODE_CAST: showCast(); break;
            case MODE_TIMER: showTimer(); break;
            case MODE_STANDUP: showStandup(); break;
            default: showHub(); break;
        }
        onTick();
    }

    private void showHub() {
        LinearLayout root = screen();
        TextView status = text(roomStatus(), 44, R.color.office_text, R.font.outfit_light);
        status.setTag("roomStatus");
        root.addView(status);
        TextView sub = text(noCable ? getString(R.string.room_no_cable) : getString(R.string.room_hub_sub),
                18, R.color.office_text_2, R.font.inter_regular);
        sub.setPadding(0, dp(6), 0, dp(28));
        root.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        root.setClipChildren(false);
        root.addView(row);
        View first = hubCard(row, MODE_MIRROR, R.drawable.ic_office_present, getString(R.string.room_mirror),
                getString(R.string.room_mirror_sub));
        hubCard(row, MODE_CAST, R.drawable.ic_office_cast, getString(R.string.room_cast), castName());
        hubCard(row, MODE_TIMER, R.drawable.ic_office_timer, getString(R.string.room_timer),
                getString(R.string.room_timer_sub));
        hubCard(row, MODE_STANDUP, R.drawable.ic_office_standup, getString(R.string.room_standup),
                getString(R.string.room_standup_sub));
        boolean connected = MeetingRoom.laptopConnected(this);
        hubCard(row, "laptop", R.drawable.ic_input, getString(R.string.room_laptop),
                connected ? getString(R.string.room_laptop_connected)
                        : MeetingRoom.hdmiStateMeaningless(this) ? getString(R.string.room_laptop_unknown)
                        : getString(R.string.room_laptop_empty));
        setContentView(root);
        View focus = lastHubCard != null ? row.findViewWithTag(lastHubCard) : null;
        (focus != null ? focus : first).requestFocus();
    }

    private View hubCard(LinearLayout row, String target, int icon, String title, String sub) {
        LinearLayout card = new LinearLayout(this);
        card.setTag(target);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setFocusable(true);
        card.setPadding(dp(16), dp(18), dp(16), dp(16));
        card.setBackground(cardBg(false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(144), dp(168));
        lp.setMargins(dp(8), 0, dp(8), 0);
        card.setLayoutParams(lp);
        // Every symbol at one size, whatever its intrinsic size.
        android.widget.ImageView ic = new android.widget.ImageView(this);
        ic.setImageResource(icon);
        ic.setImageTintList(getColorStateList(R.color.office_text));
        ic.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        card.addView(ic);
        TextView t = text(title, 18, R.color.office_text, R.font.inter_medium);
        t.setPadding(0, dp(10), 0, 0);
        card.addView(t);
        TextView s = text(sub, 16, R.color.office_text_2, R.font.inter_regular);
        s.setSingleLine(false);
        s.setMaxLines(2);
        card.addView(s);
        card.setOnFocusChangeListener((v, has) -> {
            v.setBackground(cardBg(has));
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(140).start();
        });
        card.setOnClickListener(v -> {
            if ("laptop".equals(target)) {
                if (!MeetingRoom.openLaptop(this)) show(MODE_MIRROR);
                return;
            }
            show(target);
        });
        row.addView(card);
        return card;
    }

    private void showMirror() {
        LinearLayout root = screen();
        root.addView(text(getString(R.string.room_mirror), 40, R.color.office_text, R.font.outfit_light));
        TextView name = text(getString(R.string.room_mirror_status_waiting), 18, R.color.office_text_2, R.font.inter_regular);
        name.setPadding(0, dp(4), 0, dp(18));
        root.addView(name);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);
        way(row, "1", getString(R.string.room_way_cable), getString(R.string.room_way_cable_steps),
                getString(R.string.room_way_cable_lag));
        View windows = way(row, "2", getString(R.string.room_way_windows), getString(R.string.room_way_windows_steps,
                castName()), getString(R.string.room_way_windows_lag));
        // Tested on this TV: Win + K only connects while HaiCast is open, so this card opens it.
        windows.setFocusable(true);
        windows.setOnFocusChangeListener((v, has) -> {
            v.setBackground(cardBg(has));
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(140).start();
        });
        windows.setOnClickListener(v -> MeetingRoom.openHaiCast(this));
        way(row, "3", getString(R.string.room_way_chrome), getString(R.string.room_way_chrome_steps, castName()),
                getString(R.string.room_way_chrome_lag));
        View apple = way(row, "4", getString(R.string.room_way_airplay), getString(R.string.room_way_airplay_steps, castName()),
                getString(R.string.room_way_airplay_lag));
        // One press makes sure the AirPlay receiver is running (it starts at boot, but can be stopped).
        apple.setFocusable(true);
        apple.setOnFocusChangeListener((v, has) -> {
            v.setBackground(cardBg(has));
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(140).start();
        });
        apple.setOnClickListener(v -> MeetingRoom.openAirPlay(this));
        TextView foot = text(getString(R.string.room_mirror_foot), 16, R.color.office_text_2, R.font.inter_regular);
        foot.setPadding(0, dp(18), 0, 0);
        root.addView(foot);
        setContentView(root);
        windows.requestFocus();
    }

    private View way(LinearLayout row, String n, String title, String steps, String lag) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg(false));
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(8), 0, dp(8), 0);
        card.setLayoutParams(lp);
        TextView h = text(n + "   " + title, 20, R.color.office_text, R.font.inter_medium);
        card.addView(h);
        TextView s = text(steps, 16, R.color.office_text, R.font.inter_regular);
        s.setSingleLine(false);
        s.setPadding(0, dp(8), 0, dp(8));
        card.addView(s);
        TextView l = text(lag, 16, R.color.office_text_2, R.font.inter_regular);
        l.setSingleLine(false);
        card.addView(l);
        row.addView(card);
        return card;
    }

    private void showCast() {
        LinearLayout root = screen();
        root.addView(text(getString(R.string.room_cast), 40, R.color.office_text, R.font.outfit_light));
        TextView big = text(castName(), 72, R.color.office_text, R.font.outfit_light);
        big.setPadding(0, dp(10), 0, dp(10));
        root.addView(big);
        TextView how = text(getString(R.string.room_cast_how), 18, R.color.office_text, R.font.inter_regular);
        how.setSingleLine(false);
        how.setGravity(Gravity.CENTER);
        root.addView(how);
        TextView note = text(getString(R.string.room_cast_note), 16, R.color.office_text_2, R.font.inter_regular);
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);
        setContentView(root);
    }

    private void showTimer() {
        LinearLayout root = screen();
        MeetingRoom.Slot cur = MeetingRoom.current(this, System.currentTimeMillis());
        if (timerEnd <= System.currentTimeMillis() && cur != null) {
            timerEnd = cur.end;
            warned = false;
        }
        TextView title = text(cur != null ? cur.title : getString(R.string.room_timer), 24, R.color.office_text_2,
                R.font.inter_medium);
        root.addView(title);
        bigText = text("", 128, R.color.office_text, R.font.outfit_extralight);
        root.addView(bigText);
        subText = text("", 20, R.color.office_text_2, R.font.inter_regular);
        root.addView(subText);
        bannerText = text("", 20, R.color.office_text, R.font.inter_medium);
        bannerText.setPadding(dp(18), dp(6), dp(18), dp(6));
        bannerText.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        bannerText.setLayoutParams(blp);
        root.addView(bannerText);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(26), 0, 0);
        root.addView(row);
        View first = null;
        for (int min : new int[]{15, 30, 45, 60}) {
            TextView b = text(min + " min", 18, R.color.office_text, R.font.inter_medium);
            b.setFocusable(true);
            b.setPadding(dp(22), dp(10), dp(22), dp(10));
            b.setBackground(cardBg(false));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(dp(8), 0, dp(8), 0);
            b.setLayoutParams(lp);
            b.setOnFocusChangeListener((v, has) -> v.setBackground(cardBg(has)));
            b.setOnClickListener(v -> {
                timerEnd = System.currentTimeMillis() + min * 60_000L;
                warned = false;
                onTick();
            });
            row.addView(b);
            if (first == null) first = b;
        }
        setContentView(root);
        first.requestFocus();
    }

    private void showStandup() {
        LinearLayout root = screen();
        team = getResources().getStringArray(R.array.standup_team);
        long total = getResources().getInteger(R.integer.standup_minutes) * 60_000L;
        slotMs = team.length > 0 ? total / team.length : total;
        speaker = 0;
        standupDone = false;
        standupStart = slotStart = System.currentTimeMillis();
        root.addView(text(getString(R.string.room_standup), 24, R.color.office_text_2, R.font.inter_medium));
        bigText = text("", 72, R.color.office_text, R.font.outfit_light);
        root.addView(bigText);
        subText = text("", 20, R.color.office_text_2, R.font.inter_regular);
        subText.setSingleLine(false);
        subText.setGravity(Gravity.CENTER);
        root.addView(subText);
        bannerText = text(getString(R.string.room_standup_hint), 16, R.color.office_text_2, R.font.inter_regular);
        bannerText.setPadding(0, dp(22), 0, 0);
        root.addView(bannerText);
        setContentView(root);
    }

    private void nextSpeaker() {
        speaker++;
        slotStart = System.currentTimeMillis();
        if (speaker >= team.length) {
            standupDone = true;
            bigText.setText(R.string.room_standup_done);
            StringBuilder sb = new StringBuilder();
            for (String t : getResources().getStringArray(R.array.sample_due_today)) sb.append(t).append('\n');
            subText.setText(sb.toString().trim());
            bannerText.setText(R.string.room_standup_done_hint);
        }
        onTick();
    }

    // ---- Clock tick ---------------------------------------------------------------------------------

    private void onTick() {
        long now = System.currentTimeMillis();
        if (MODE_HUB.equals(mode)) {
            View v = getWindow().getDecorView().findViewWithTag("roomStatus");
            if (v instanceof TextView) ((TextView) v).setText(roomStatus());
        } else if (MODE_TIMER.equals(mode) && bigText != null) {
            SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
            f.setTimeZone(MeetingRoom.OFFICE_TZ);
            if (timerEnd > now) {
                bigText.setText(mmss(timerEnd - now));
                subText.setText(getString(R.string.room_timer_ends, f.format(timerEnd)));
                if (timerEnd - now <= 5 * 60_000L) {
                    bannerText.setText(R.string.room_timer_five);
                    bannerText.setBackground(pill(R.color.status_at_risk));
                    bannerText.setVisibility(View.VISIBLE);
                    warned = true;
                } else {
                    bannerText.setVisibility(View.GONE);
                }
            } else {
                bigText.setText(timerEnd > 0 ? "0:00" : "--:--");
                subText.setText(timerEnd > 0 ? getString(R.string.room_timer_over) : getString(R.string.room_timer_pick));
                bannerText.setVisibility(View.GONE);
            }
        } else if (MODE_STANDUP.equals(mode) && bigText != null && !standupDone && team.length > 0) {
            long slotLeft = slotMs - (now - slotStart);
            long totalLeft = team.length * slotMs - (now - standupStart);
            bigText.setText(team[speaker]);
            String nextName = speaker + 1 < team.length ? team[speaker + 1] : getString(R.string.room_standup_last);
            subText.setText(getString(R.string.room_standup_line, mmss(Math.max(0, slotLeft)),
                    mmss(Math.max(0, totalLeft)), nextName));
            subText.setTextColor(getColor(slotLeft < 0 ? R.color.status_off_track : R.color.office_text_2));
        }
    }

    /** "Free until 3:00 PM" / "In use until 4:30 PM", readable from the door. */
    private String roomStatus() {
        long now = System.currentTimeMillis();
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        f.setTimeZone(MeetingRoom.OFFICE_TZ);
        MeetingRoom.Slot cur = MeetingRoom.current(this, now);
        if (cur != null) return getString(R.string.room_in_use_until, f.format(cur.end));
        MeetingRoom.Slot nxt = MeetingRoom.next(this, now);
        return nxt != null ? getString(R.string.room_free_until, f.format(nxt.start)) : getString(R.string.room_free_today);
    }

    // ---- Helpers ------------------------------------------------------------------------------------

    private String castName() {
        return MeetingRoom.castName(this);
    }

    private static String mmss(long ms) {
        long s = (ms + 999) / 1000;
        return (s / 60) + ":" + String.format(Locale.ENGLISH, "%02d", s % 60);
    }

    private LinearLayout screen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(getColor(R.color.office_bg));
        root.setPadding(dp(64), dp(32), dp(64), dp(32));
        return root;
    }

    private TextView text(String s, int sp, int color, int font) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(color));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Typeface tf = getResources().getFont(font);
        t.setTypeface(tf);
        return t;
    }

    /** Pre-rendered glass: translucent fill, 1 px light stroke; brighter when focused. */
    private GradientDrawable cardBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        g.setColor(focused ? 0xFF2A302D : 0xCC151917);
        g.setStroke(Math.max(1, dp(focused ? 1 : 0) + 1), focused ? 0x99F2F1EC : 0x24F2F1EC);
        return g;
    }

    private GradientDrawable pill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor((getColor(color) & 0x00FFFFFF) | 0xCC000000);
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
