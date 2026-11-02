package com.marvice.officetv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

/**
 * Meetings (spec screen 10): two columns, Yuvaraj / Marvice on the left and Vishal / Infyst on the right.
 * Today's meetings (titles and times only), what is on now, the countdown to the next, recent changes, and
 * each leader's follow-ups. Refreshes itself when the sync brings new data.
 */
public class MeetingsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout left, right, changes;
    private final Runnable refresh = this::render;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            render();
            main.postDelayed(this, 30_000L);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.office_bg));
        root.setPadding(dp(40), dp(24), dp(40), dp(20));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("Meetings", 30, R.color.office_text, R.font.inter_medium);
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        SimpleDateFormat f = new SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH);
        f.setTimeZone(OfficeData.OFFICE_TZ);
        head.addView(text(f.format(System.currentTimeMillis()), 18, R.color.office_text_2, R.font.inter_regular));
        TextView acc = text("Privacy & accounts", 16, R.color.office_text, R.font.inter_regular);
        acc.setFocusable(true);
        acc.setClickable(true);
        acc.setPadding(dp(14), dp(6), dp(14), dp(6));
        acc.setBackground(pill(false));
        acc.setOnFocusChangeListener((v, has) -> v.setBackground(pill(has)));
        acc.setOnClickListener(v -> startActivity(new Intent(this, AccountsActivity.class)));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMarginStart(dp(20));
        head.addView(acc, alp);
        root.addView(head);

        LinearLayout cols = new LinearLayout(this);
        cols.setPadding(0, dp(16), 0, 0);
        left = column();
        right = column();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginEnd(dp(16));
        cols.addView(left, lp);
        cols.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(cols, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        changes = new LinearLayout(this);
        changes.setOrientation(LinearLayout.VERTICAL);
        changes.setPadding(0, dp(10), 0, 0);
        root.addView(changes);
        setContentView(root);
        acc.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        OfficeData.get(this).listen(refresh);
        OfficeSync.kick(this);
        main.removeCallbacks(tick);
        tick.run();
    }

    @Override
    protected void onPause() {
        OfficeData.get(this).unlisten(refresh);
        main.removeCallbacks(tick);
        super.onPause();
    }

    private void render() {
        fill(left, Accounts.MARVICE);
        fill(right, Accounts.INFYST);
        changes.removeAllViews();
        List<OfficeData.Alert> alerts = OfficeData.get(this).alerts();
        int n = 0;
        for (OfficeData.Alert a : alerts) {
            if (n++ >= 3) break;
            changes.addView(text(OfficeData.time(a.at) + "  " + a.text + "  · " + Accounts.leaderName(this, a.owner),
                    15, R.color.office_text_2, R.font.inter_regular));
        }
    }

    private void fill(LinearLayout col, String owner) {
        col.removeAllViews();
        boolean infyst = Accounts.INFYST.equals(owner);
        int accent = getColor(infyst ? R.color.infyst_accent : R.color.marvice_accent_2);
        LinearLayout h = new LinearLayout(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        View bar = new View(this);
        bar.setBackgroundColor(accent);
        h.addView(bar, new LinearLayout.LayoutParams(dp(4), dp(22)));
        TextView who = text(Accounts.leaderName(this, owner) + "  ·  "
                + getString(infyst ? R.string.company_infyst_short : R.string.company_marvice_short), 20,
                R.color.office_text, R.font.inter_medium);
        who.setPadding(dp(10), 0, 0, 0);
        h.addView(who);
        col.addView(h);

        OfficeData data = OfficeData.get(this);
        if (!data.hasCalendar(this, owner)) {
            TextView t = text(new Accounts(this).forOwner(owner).isEmpty()
                    ? "No calendar connected. Connect it on Privacy & accounts."
                    : "Connected. Waiting for the first sync…", 16, R.color.office_text_2, R.font.inter_regular);
            t.setSingleLine(false);
            t.setPadding(0, dp(12), 0, 0);
            col.addView(t);
        } else {
            long now = System.currentTimeMillis();
            boolean nextMarked = false;
            int rows = 0;
            for (OfficeData.Ev e : data.today(this, owner)) {
                if (rows++ >= 8) break;
                boolean over = e.end <= now, live = e.start <= now && now < e.end && !e.cancelled;
                String badge = null;
                if (e.cancelled) badge = "Cancelled";
                else if (live) badge = "Now";
                else if (!over && !nextMarked) {
                    badge = "in " + countdown(e.start - now);
                    nextMarked = true;
                }
                col.addView(meeting(e, badge, over || e.cancelled, accent));
            }
            if (rows == 0) {
                TextView t = text("No meetings today.", 16, R.color.office_text_2, R.font.inter_regular);
                t.setPadding(0, dp(12), 0, 0);
                col.addView(t);
            }
        }

        List<OfficeData.FollowUp> fu = data.followUps(this, owner);
        TextView fh = text("Follow-ups" + (fu.isEmpty() ? "" : " (" + fu.size() + ")"), 17, R.color.office_text, R.font.inter_medium);
        fh.setPadding(0, dp(16), 0, dp(2));
        col.addView(fh);
        if (fu.isEmpty()) {
            boolean mail = false;
            for (Accounts.Account a : new Accounts(this).forOwner(owner)) mail |= !a.paused;
            col.addView(text(mail ? "Nothing waiting." : "No mailbox connected.", 15, R.color.office_text_2, R.font.inter_regular));
        }
        for (int i = 0; i < fu.size() && i < 4; i++) {
            OfficeData.FollowUp f = fu.get(i);
            col.addView(text(f.line + "  · " + age(System.currentTimeMillis() - f.since), 15,
                    R.color.office_text_2, R.font.inter_regular));
        }
    }

    private View meeting(OfficeData.Ev e, String badge, boolean dim, int accent) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(9), 0, 0);
        TextView t = text(OfficeData.time(e.start), 16, R.color.office_text_2, R.font.inter_regular);
        t.setMinWidth(dp(86));
        r.addView(t);
        TextView name = text(e.title + (e.online ? "  · Teams" : ""), 17, dim ? R.color.office_text_2 : R.color.office_text,
                R.font.inter_regular);
        if (e.cancelled) name.setPaintFlags(name.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (badge != null) {
            TextView b = text(badge, 14, R.color.office_text, R.font.inter_medium);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor((accent & 0x00FFFFFF) | 0x66000000);
            b.setBackground(bg);
            b.setPadding(dp(8), dp(1), dp(8), dp(1));
            r.addView(b);
        }
        return r;
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(getColor(R.color.office_surface));
        bg.setStroke(dp(1), getColor(R.color.office_stroke));
        c.setBackground(bg);
        c.setPadding(dp(20), dp(16), dp(20), dp(16));
        return c;
    }

    private GradientDrawable pill(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(getColor(focused ? R.color.office_surface_hi : R.color.office_surface));
        g.setStroke(dp(focused ? 2 : 1), focused ? getColor(R.color.marvice_accent_2) : getColor(R.color.office_stroke));
        return g;
    }

    static String countdown(long ms) {
        long min = Math.max(1, (ms + 59_999L) / 60_000L);
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }

    static String age(long ms) {
        long h = ms / 3600_000L;
        return h < 24 ? Math.max(1, h) + " h" : (h / 24) + " d";
    }

    private TextView text(String s, int sp, int color, int font) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(color));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(getResources().getFont(font));
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
