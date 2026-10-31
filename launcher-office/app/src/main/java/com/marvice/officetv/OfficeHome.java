package com.marvice.officetv;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Office Home, batch 1: both companies' brand row, the Company pill (Both / Marvice / Infyst), the
 * world-clock line, Open-Meteo weather (the only live data in this batch) and two "Today" cards filled
 * with sample meetings. Nothing here signs in to anything.
 */
final class OfficeHome {
    static final int BOTH = 0, MARVICE = 1, INFYST = 2;
    private static final String TAG = "OfficeHome";
    private static final String PREFS = "office_home";
    private static final long WEATHER_MAX_AGE_MS = 30 * 60 * 1000L;
    private static final TimeZone OFFICE_TZ = TimeZone.getTimeZone("Asia/Kolkata");

    private final Activity activity;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private int company;
    private boolean fetching;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            renderWorldLine();
            renderCards();
            main.postDelayed(this, 30_000L - (System.currentTimeMillis() % 30_000L));
        }
    };

    OfficeHome(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int saved = prefs.getInt("company", BOTH);
        this.company = saved >= BOTH && saved <= INFYST ? saved : BOTH;
    }

    /** Wires the office pills once, after setContentView. */
    void bind() {
        View reorder = activity.findViewById(R.id.btnReorder);
        if (reorder != null) reorder.setVisibility(View.GONE);   // grid editing moves to long-press (PIN) later
        TextView companyPill = activity.findViewById(R.id.btnCompany);
        companyPill.setOnClickListener(v -> {
            company = (company + 1) % 3;
            prefs.edit().putInt("company", company).apply();
            renderCompany();
        });
        activity.findViewById(R.id.btnWeather).setOnClickListener(v -> fetchWeather(true));
        activity.findViewById(R.id.btnAlerts).setOnClickListener(v ->
                Toast.makeText(activity, "No alerts. Alerts arrive in a later batch.", Toast.LENGTH_SHORT).show());
        setUpCard(R.id.todayMarvice, R.drawable.logo_marvice, R.string.company_marvice_short,
                R.string.leader_marvice, R.color.marvice_accent_2);
        setUpCard(R.id.todayInfyst, R.drawable.logo_infyst, R.string.company_infyst_short,
                R.string.leader_infyst, R.color.infyst_accent);
        renderCompany();
        renderWeather();
    }

    void onResume() {
        main.removeCallbacks(tick);
        tick.run();
        fetchWeather(false);
    }

    void onPause() {
        main.removeCallbacks(tick);
    }

    void onDestroy() {
        main.removeCallbacks(tick);
        io.shutdownNow();
    }

    // ---- Company -------------------------------------------------------------------------------

    private void renderCompany() {
        TextView pill = activity.findViewById(R.id.btnCompany);
        pill.setText(company == MARVICE ? "Marvice" : company == INFYST ? "Infyst" : "Both");
        activity.findViewById(R.id.todayMarvice).setVisibility(company == INFYST ? View.GONE : View.VISIBLE);
        activity.findViewById(R.id.todayInfyst).setVisibility(company == MARVICE ? View.GONE : View.VISIBLE);
    }

    // ---- Today cards -----------------------------------------------------------------------------

    private void setUpCard(int cardId, int logo, int companyName, int leader, int accent) {
        View card = activity.findViewById(cardId);
        ((ImageView) card.findViewById(R.id.cardLogo)).setImageResource(logo);
        ((TextView) card.findViewById(R.id.cardCompany)).setText(companyName);
        ((TextView) card.findViewById(R.id.cardLeader)).setText(leader);
        card.findViewById(R.id.cardAccent).setBackgroundColor(activity.getColor(accent));
    }

    private void renderCards() {
        fillMeetings(activity.findViewById(R.id.todayMarvice), R.array.sample_meetings_marvice, R.color.marvice_accent_2);
        fillMeetings(activity.findViewById(R.id.todayInfyst), R.array.sample_meetings_infyst, R.color.infyst_accent);
    }

    /** Sample meetings ("HH:mm|minutes|title") for today, next three not yet over, countdown on the next. */
    private void fillMeetings(View card, int arrayId, int accent) {
        LinearLayout list = card.findViewById(R.id.cardMeetings);
        list.removeAllViews();
        long now = System.currentTimeMillis();
        List<long[]> times = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        for (String row : activity.getResources().getStringArray(arrayId)) {
            String[] p = row.split("\\|", 3);
            if (p.length < 3) continue;
            try {
                String[] hm = p[0].split(":");
                Calendar c = Calendar.getInstance(OFFICE_TZ);
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                c.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                long start = c.getTimeInMillis();
                long end = start + Integer.parseInt(p[1]) * 60_000L;
                if (end <= now) continue;
                times.add(new long[]{start, end});
                titles.add(p[2]);
            } catch (NumberFormatException e) {
                Log.w(TAG, "skipping a malformed sample meeting");
            }
        }
        SimpleDateFormat fmt = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        fmt.setTimeZone(OFFICE_TZ);
        int shown = 0;
        for (int i = 0; i < times.size() && shown < 3; i++, shown++) {
            long start = times.get(i)[0], end = times.get(i)[1];
            String when = fmt.format(start);
            String badge = null;
            if (start <= now && now < end) badge = activity.getString(R.string.office_now);
            else if (shown == 0) badge = activity.getString(R.string.office_next_in, countdown(start - now));
            list.addView(meetingRow(when, titles.get(i), badge, accent));
        }
        if (shown == 0) list.addView(meetingRow("", "No more meetings today", null, accent));
    }

    /** One meeting: time (and Now / countdown badge) on the first line, the title on its own line below. */
    private View meetingRow(String when, String title, String badge, int accent) {
        LinearLayout block = new LinearLayout(activity);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(6), 0, 0);
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        block.addView(row);
        TextView time = text(when, R.color.office_text_2, R.font.inter_regular);
        row.addView(time);
        TextView name = text(title, R.color.office_text, R.font.inter_regular);
        block.addView(name);
        if (badge != null) {
            TextView b = text(badge, R.color.office_text, R.font.inter_medium);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            bg.setColor((activity.getColor(accent) & 0x00FFFFFF) | 0x66000000);
            b.setBackground(bg);
            b.setPadding(dp(8), 0, dp(8), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(dp(6));
            b.setLayoutParams(lp);
            row.addView(b);
        }
        return block;
    }

    private static String countdown(long ms) {
        long min = Math.max(1, (ms + 59_999L) / 60_000L);
        return min < 60 ? min + " min" : (min / 60) + " h " + (min % 60) + " min";
    }

    // ---- World clock -----------------------------------------------------------------------------

    private void renderWorldLine() {
        TextView line = activity.findViewById(R.id.worldLine);
        SimpleDateFormat fmt = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        fmt.setTimeZone(TimeZone.getTimeZone(activity.getString(R.string.world_zone)));
        line.setText(activity.getString(R.string.office_city) + " · "
                + activity.getString(R.string.world_city) + " " + fmt.format(System.currentTimeMillis()));
    }

    // ---- Weather (Open-Meteo, no key) -------------------------------------------------------------

    private void renderWeather() {
        TextView pill = activity.findViewById(R.id.btnWeather);
        long at = prefs.getLong("weather_at", 0);
        if (at == 0) {
            pill.setText(R.string.office_weather_none);
            return;
        }
        pill.setText(Math.round(prefs.getFloat("weather_temp", 0f)) + "°  "
                + condition(prefs.getInt("weather_code", -1)));
    }

    private void fetchWeather(boolean force) {
        long age = System.currentTimeMillis() - prefs.getLong("weather_at", 0);
        if (fetching || (!force && age < WEATHER_MAX_AGE_MS)) return;
        fetching = true;
        String lat = activity.getString(R.string.weather_lat), lon = activity.getString(R.string.weather_lon);
        io.execute(() -> {
            HttpURLConnection c = null;
            try {
                URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code&timezone=Asia%2FKolkata");
                c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                JSONObject cur = new JSONObject(read(c.getInputStream())).getJSONObject("current");
                float temp = (float) cur.getDouble("temperature_2m");
                int code = cur.getInt("weather_code");
                prefs.edit().putFloat("weather_temp", temp).putInt("weather_code", code)
                        .putLong("weather_at", System.currentTimeMillis()).apply();
            } catch (Exception e) {
                // Offline keeps the last value on the pill; never a popup on Home.
                Log.w(TAG, "weather refresh failed: " + e.getClass().getSimpleName());
            } finally {
                if (c != null) c.disconnect();
                main.post(() -> {
                    fetching = false;
                    if (!activity.isDestroyed()) renderWeather();
                });
            }
        });
    }

    private static String read(InputStream in) throws java.io.IOException {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = s.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    /** WMO weather code to one short word. */
    static String condition(int code) {
        if (code == 0) return "Clear";
        if (code <= 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 95) return "Storm";
        return "";
    }

    private TextView text(String s, int color, int font) {
        TextView t = new TextView(activity);
        t.setText(s);
        t.setTextColor(activity.getColor(color));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTypeface(activity.getResources().getFont(font));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
