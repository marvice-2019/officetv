package com.marvice.officetv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The TV's own sync, straight from the providers (no n8n): calendars every minute, mail headers every five,
 * meeting reminders at 10 and 2 minutes, and alerts when a meeting moves or is cancelled. Woken by an alarm,
 * so it keeps running while another app is on screen; reminders then show as a small card over that app.
 */
public final class OfficeSync extends BroadcastReceiver {
    private static final String TAG = "OfficeSync";
    private static final String ACTION = "com.marvice.officetv.SYNC";
    private static final long CAL_EVERY_MS = 60_000L, MAIL_EVERY_MS = 5 * 60_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    @Override
    public void onReceive(Context ctx, Intent intent) {
        PendingResult pr = goAsync();
        run(ctx.getApplicationContext(), pr::finish);
    }

    /** Sync now (Home resumed, an account connected). */
    static void kick(Context ctx) {
        run(ctx.getApplicationContext(), null);
    }

    private static void run(Context app, Runnable done) {
        if (!RUNNING.compareAndSet(false, true)) {
            if (done != null) done.run();
            return;
        }
        IO.execute(() -> {
            try {
                syncAll(app);
                remind(app);
            } catch (Exception e) {
                Log.w(TAG, "sync pass failed: " + e.getClass().getSimpleName());
            } finally {
                RUNNING.set(false);
                schedule(app);
                if (done != null) done.run();
            }
        });
    }

    // ---- Sync ------------------------------------------------------------------------------------

    private static void syncAll(Context ctx) {
        Accounts accounts = new Accounts(ctx);
        SharedPreferences st = ctx.getSharedPreferences("office_sync", Context.MODE_PRIVATE);
        OfficeData data = OfficeData.get(ctx);
        long now = System.currentTimeMillis();
        for (Accounts.Account a : accounts.all()) {
            if (a.paused) continue;
            if (a.lastError != null && a.lastError.startsWith("!")) continue;   // needs a reconnect, not retries
            try {
                List<OfficeData.Alert> alerts = new ArrayList<>();
                boolean mailDue = now - st.getLong("mail." + a.id, 0) >= MAIL_EVERY_MS;
                if (Accounts.M365.equals(a.kind)) {
                    alerts = data.putEvents(a.id, a.owner, Graph.calendar(ctx, a));
                    if (mailDue) data.putMail(a.id, Graph.mail(ctx, a));
                } else if (Accounts.IMAP.equals(a.kind) && mailDue) {
                    data.putMail(a.id, imapMail(ctx, a));
                }
                if (mailDue) st.edit().putLong("mail." + a.id, now).apply();
                a.lastSync = System.currentTimeMillis();
                a.lastError = null;
                accounts.save(a);
                for (OfficeData.Alert al : alerts) show(ctx, al.text, a.owner, 30_000L);
            } catch (Graph.AuthException e) {
                a.lastError = "!" + e.getMessage();
                accounts.save(a);
                Log.w(TAG, a.kind + " needs a reconnect");
            } catch (Exception e) {
                a.lastError = "Last try failed (" + e.getClass().getSimpleName() + "); retrying.";
                accounts.save(a);
                Log.w(TAG, a.kind + " sync failed: " + e.getClass().getSimpleName());
            }
        }
    }

    private static List<OfficeData.Msg> imapMail(Context ctx, Accounts.Account a) throws Exception {
        Vault v = new Vault(ctx);
        String user = v.get(a.vaultPrefix() + "user"), pass = v.get(a.vaultPrefix() + "pass");
        if (user == null || pass == null) throw new Graph.AuthException("Mailbox password missing. Reconnect.");
        long since = System.currentTimeMillis() - OfficeData.MAIL_KEEP_MS;
        List<OfficeData.Msg> out = new ArrayList<>();
        Imap c;
        try {
            c = Imap.login(a.host, 993, user, pass);
        } catch (java.io.IOException e) {
            if ("login".equals(e.getMessage())) throw new Graph.AuthException("The mailbox refused the password. Reconnect.");
            throw e;
        }
        try (Imap imap = c) {
            out.addAll(imap.headers("INBOX", false, since, a.id, a.owner, 150));
            out.addAll(imap.headers(null, true, since, a.id, a.owner, 150));
        }
        return out;
    }

    // ---- Reminders -------------------------------------------------------------------------------

    private static void remind(Context ctx) {
        OfficeData data = OfficeData.get(ctx);
        long now = System.currentTimeMillis();
        for (String owner : new String[]{Accounts.MARVICE, Accounts.INFYST}) {
            for (OfficeData.Ev e : data.today(ctx, owner)) {
                if (e.cancelled || e.start <= now) continue;
                long left = e.start - now;
                String who = Accounts.leaderName(ctx, owner);
                if (left <= 150_000L && data.firstTime(e.uid + "|" + e.start + "|2")) {
                    data.firstTime(e.uid + "|" + e.start + "|10");
                    show(ctx, "In 2 min: " + e.title + " · " + who, owner, 60_000L);
                } else if (left <= 630_000L && left > 150_000L && data.firstTime(e.uid + "|" + e.start + "|10")) {
                    show(ctx, "In 10 min: " + e.title + " at " + OfficeData.time(e.start) + " · " + who, owner, 30_000L);
                }
            }
        }
    }

    /** Next wake: the next calendar poll, or sooner when a reminder falls due. */
    private static void schedule(Context ctx) {
        long now = System.currentTimeMillis();
        long next = now + CAL_EVERY_MS;
        if (new Accounts(ctx).all().isEmpty()) next = now + 15 * 60_000L;
        OfficeData data = OfficeData.get(ctx);
        for (String owner : new String[]{Accounts.MARVICE, Accounts.INFYST}) {
            for (OfficeData.Ev e : data.today(ctx, owner)) {
                for (long at : new long[]{e.start - 600_000L, e.start - 120_000L}) {
                    if (at > now + 1000 && at < next) next = at;
                }
            }
        }
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 1,
                new Intent(ctx, OfficeSync.class).setAction(ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi);
        else am.setWindow(AlarmManager.RTC_WAKEUP, next, 30_000L, pi);
    }

    // ---- On-screen card --------------------------------------------------------------------------

    private static View shown;

    /** A small card at the top right, over whatever is on screen; a toast where overlays are not allowed. */
    static void show(Context ctx, String text, String owner, long forMs) {
        MAIN.post(() -> {
            if (!Settings.canDrawOverlays(ctx)) {
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
                return;
            }
            WindowManager wm = ctx.getSystemService(WindowManager.class);
            if (shown != null) {
                try { wm.removeView(shown); } catch (Exception ignored) { }
            }
            float d = ctx.getResources().getDisplayMetrics().density;
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF0151917);
            bg.setCornerRadius(14 * d);
            bg.setStroke(Math.round(d), 0x33F2F1EC);
            card.setBackground(bg);
            card.setPadding(Math.round(14 * d), Math.round(10 * d), Math.round(18 * d), Math.round(10 * d));
            View bar = new View(ctx);
            bar.setBackgroundColor(ctx.getColor(Accounts.INFYST.equals(owner) ? R.color.infyst_accent : R.color.marvice_accent_2));
            card.addView(bar, new LinearLayout.LayoutParams(Math.round(4 * d), Math.round(28 * d)));
            TextView t = new TextView(ctx);
            t.setText(text);
            t.setTextColor(ctx.getColor(R.color.office_text));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            t.setTypeface(ctx.getResources().getFont(R.font.inter_medium));
            t.setMaxWidth(Math.round(520 * d));
            t.setMaxLines(2);
            t.setPadding(Math.round(10 * d), 0, 0, 0);
            card.addView(t);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.x = Math.round(28 * d);
            lp.y = Math.round(24 * d);
            try {
                wm.addView(card, lp);
                shown = card;
                MAIN.postDelayed(() -> {
                    if (shown == card) {
                        try { wm.removeView(card); } catch (Exception ignored) { }
                        shown = null;
                    }
                }, forMs);
            } catch (Exception e) {
                Toast.makeText(ctx, text, Toast.LENGTH_LONG).show();
            }
        });
    }
}
