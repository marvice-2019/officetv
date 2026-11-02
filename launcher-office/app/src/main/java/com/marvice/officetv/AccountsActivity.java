package com.marvice.officetv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Privacy &amp; accounts (spec "Connections"): every connection with who connected it, when, what it can read,
 * Pause and Disconnect; and the connect flows. Sign-in happens on a phone: the TV shows a QR code (a one-time
 * page for settings and mailbox passwords, Microsoft's device-code page for Microsoft 365).
 */
public class AccountsActivity extends Activity {
    private static final String TAG = "Accounts";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Accounts accounts;
    private SharedPreferences appRegs;
    private LinearLayout list;
    private FrameLayout root;
    private View panel;
    private PhoneEntry entry;
    private volatile boolean cancelled;
    private String armedDisconnect;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        accounts = new Accounts(this);
        appRegs = getSharedPreferences("m365_app", MODE_PRIVATE);
        root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.office_bg));
        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(48), dp(28), dp(48), dp(28));
        scroll.addView(list);
        root.addView(scroll);
        setContentView(root);
        render();
    }

    @Override
    protected void onDestroy() {
        cancelFlow();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && panel != null) {
            cancelFlow();
            render();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---- List ------------------------------------------------------------------------------------

    private void render() {
        list.removeAllViews();
        list.addView(text("Privacy & accounts", 30, R.color.office_text, R.font.inter_medium));
        TextView sub = text("What this TV reads, who connected it and when. Every connection is read-only: nothing "
                + "sends, deletes, moves or marks mail, and no mail body is stored.", 16, R.color.office_text_2, R.font.inter_regular);
        sub.setSingleLine(false);
        sub.setPadding(0, dp(4), 0, dp(16));
        list.addView(sub);

        View firstFocus = null;
        for (Accounts.Account a : accounts.all()) {
            View card = accountCard(a);
            list.addView(card);
            if (firstFocus == null) firstFocus = card.findViewWithTag("first");
        }
        if (accounts.all().isEmpty()) {
            list.addView(text("Nothing connected yet. The Today cards show sample meetings until a calendar connects.",
                    16, R.color.office_text_2, R.font.inter_regular));
        }

        list.addView(spacer(20));
        list.addView(text("Connect", 20, R.color.office_text, R.font.inter_medium));
        for (String owner : new String[]{Accounts.MARVICE, Accounts.INFYST}) {
            String who = Accounts.leaderName(this, owner) + " (" + getString(Accounts.INFYST.equals(owner)
                    ? R.string.company_infyst_short : R.string.company_marvice_short) + ")";
            LinearLayout row = row();
            View m = button("Microsoft 365 for " + who, v -> connectM365(owner));
            row.addView(m);
            row.addView(button("Mailbox (IMAP) for " + Accounts.leaderName(this, owner), v -> connectImap(owner)));
            list.addView(row);
            if (firstFocus == null) firstFocus = m;
        }
        TextView foot = text("Network ADB: open on the office LAN and Tailscale (left open for setup at the owner's request).",
                14, R.color.office_text_2, R.font.inter_regular);
        foot.setSingleLine(false);
        foot.setPadding(0, dp(18), 0, 0);
        list.addView(foot);
        if (firstFocus != null) firstFocus.requestFocus();
    }

    private View accountCard(Accounts.Account a) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(cardBg(false));
        c.setPadding(dp(18), dp(12), dp(18), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        String company = getString(Accounts.INFYST.equals(a.owner) ? R.string.company_infyst_short : R.string.company_marvice_short);
        c.addView(text(a.kindLabel() + " · " + Accounts.leaderName(this, a.owner) + " · " + company
                + (a.paused ? "  (paused)" : ""), 18, R.color.office_text, R.font.inter_medium));
        SimpleDateFormat f = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.ENGLISH);
        f.setTimeZone(OfficeData.OFFICE_TZ);
        c.addView(line("Connected by " + a.person + " on " + f.format(a.connectedAt)
                + (a.ownDomain.isEmpty() ? "" : " · " + a.ownDomain)));
        c.addView(line("Reads: " + a.reads));
        c.addView(line("Permissions: " + a.scopes));
        String status = a.lastError != null ? a.lastError.replaceFirst("^!", "")
                : a.lastSync > 0 ? "Synced " + DateUtils.getRelativeTimeSpanString(a.lastSync, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS) : "Waiting for the first sync";
        TextView st = line(status);
        if (a.lastError != null) st.setTextColor(getColor(R.color.status_at_risk));
        c.addView(st);
        LinearLayout r = row();
        View pause = button(a.paused ? "Resume" : "Pause", v -> {
            a.paused = !a.paused;
            accounts.save(a);
            OfficeSync.kick(this);
            render();
        });
        pause.setTag("first");
        r.addView(pause);
        r.addView(button(a.id.equals(armedDisconnect) ? "Press again to disconnect" : "Disconnect", v -> {
            if (!a.id.equals(armedDisconnect)) {
                armedDisconnect = a.id;
                render();
                return;
            }
            armedDisconnect = null;
            accounts.remove(this, a.id);
            Log.i(TAG, "disconnected one " + a.kind + " account");
            render();
        }));
        c.addView(r);
        return c;
    }

    // ---- Microsoft 365 ---------------------------------------------------------------------------

    private void connectM365(String owner) {
        String tenant = appRegs.getString(owner + ".tenant", null);
        String client = appRegs.getString(owner + ".client", null);
        if (tenant != null && client != null) {
            deviceCode(owner, tenant, client);
            return;
        }
        PhoneEntry.Field[] fields = {
                new PhoneEntry.Field("tenant", "Directory (tenant) ID, or the company domain", "text", "marvice.in"),
                new PhoneEntry.Field("client", "Application (client) ID of the Office TV app", "text",
                        "00000000-0000-0000-0000-000000000000"),
        };
        phonePage(owner, "Microsoft 365 app registration for " + Accounts.leaderName(this, owner),
                "From Microsoft Entra admin center, App registrations, Office TV, Overview. Not secrets.", fields,
                values -> {
                    String t = values.get("tenant"), c = values.get("client");
                    if (t.isEmpty() || !c.matches("[0-9a-fA-F-]{36}")) {
                        showPanel("That client ID doesn't look right", "It is a 36-character ID like "
                                + "1a2b3c4d-.... Press Back and try again.", null, null);
                        return;
                    }
                    appRegs.edit().putString(owner + ".tenant", t).putString(owner + ".client", c).apply();
                    deviceCode(owner, t, c);
                });
    }

    private void deviceCode(String owner, String tenant, String client) {
        cancelled = false;
        showPanel("Connecting Microsoft 365", "Asking Microsoft for a sign-in code…", null, null);
        Accounts.Account a = new Accounts.Account();
        a.id = Accounts.newId();
        a.owner = owner;
        a.kind = Accounts.M365;
        a.tenant = tenant;
        a.clientId = client;
        android.content.Context app = getApplicationContext();
        // Its own thread, not io: Back or leaving the screen must not stop a sign-in already made on the phone.
        Thread t = new Thread(() -> signIn(app, owner, tenant, client, a), "m365-signin");
        t.setDaemon(true);
        t.start();
    }

    private void post(Runnable r) {
        main.post(() -> { if (!isDestroyed() && !isFinishing()) r.run(); });
    }

    private void signIn(android.content.Context app, String owner, String tenant, String client, Accounts.Account a) {
            try {
                Graph.DeviceCode d = Graph.startDeviceCode(tenant, client);
                Log.i(TAG, "sign-in code issued; waiting for the phone");
                post(() -> showPanel("Sign in on your phone",
                        "1. Scan the code, or open " + d.verifyUrl.replace("https://", "") + "\n2. Enter the code below\n"
                                + "3. Sign in as " + Accounts.leaderName(this, owner)
                                + ". The TV never sees the password.\n\nAllows: read calendar, read mail headers (no bodies), "
                                + "your name. Nothing can send or delete.", d.verifyUrl, d.userCode));
                String granted = null;
                while (System.currentTimeMillis() < d.expiresAt) {
                    Thread.sleep(d.interval * 1000L);
                    granted = Graph.pollDeviceCode(app, a, d);
                    if (granted != null) break;
                }
                if (granted == null) throw new Graph.AuthException("The code expired. Start again.");
                Log.i(TAG, "Microsoft 365 tokens received; reading the profile");
                String[] me = Graph.me(app, a);
                a.person = me[0].isEmpty() ? Accounts.leaderName(app, owner) : me[0];
                a.ownDomain = me[1];
                a.reads = Graph.READS;
                a.scopes = granted.replace("https://graph.microsoft.com/", "").trim();
                a.connectedAt = System.currentTimeMillis();
                new Accounts(app).save(a);
                Log.i(TAG, "Microsoft 365 connected");
                OfficeSync.kick(app);
                OfficeSync.show(app, "Microsoft 365 connected: " + a.person, owner, 15_000L);
                post(() -> showPanel("Connected", a.person + "'s calendar and mail headers are connected, read-only. "
                        + "Press Back.", null, null));
            } catch (Graph.AuthException e) {
                Log.w(TAG, "Microsoft 365 sign-in ended: " + e.getMessage());
                new Vault(app).removePrefix(a.vaultPrefix());
                        if (e.getMessage() != null && (e.getMessage().contains("client) ID") || e.getMessage().contains("tenant")
                        )) {
                    appRegs.edit().remove(owner + ".tenant").remove(owner + ".client").apply();
                }
                post(() -> showPanel("Not connected", e.getMessage() + "\n\nPress Back.", null, null));
            } catch (InterruptedException e) {
                new Vault(app).removePrefix(a.vaultPrefix());
            } catch (Exception e) {
                new Vault(app).removePrefix(a.vaultPrefix());
                Log.w(TAG, "connect failed: " + e.getClass().getSimpleName());
                post(() -> showPanel("Not connected", "Network problem (" + e.getClass().getSimpleName()
                        + "). Check the Wi-Fi and press Back to try again.", null, null));
            }
    }

    // ---- IMAP ------------------------------------------------------------------------------------

    private void connectImap(String owner) {
        PhoneEntry.Field[] fields = {
                new PhoneEntry.Field("host", "IMAP server", "text", "imap.hostinger.com"),
                new PhoneEntry.Field("user", "Mailbox address", "email", "name@company.com"),
                new PhoneEntry.Field("pass", "Mailbox password (an app password if the provider offers one)", "password", null),
        };
        phonePage(owner, "Mailbox for " + Accounts.leaderName(this, owner),
                "The TV opens the mailbox in read-only mode and reads headers only. The password goes straight into "
                        + "the TV's encrypted store; it is never shown or logged.", fields, values -> {
                    String host = values.get("host").toLowerCase(Locale.ROOT);
                    if (!host.matches("[a-z0-9.-]+\\.[a-z]{2,}")) {
                        showPanel("That server name doesn't look right", "Press Back and try again.", null, null);
                        return;
                    }
                    showPanel("Checking the mailbox", "Signing in read-only…", null, null);
                    String user = values.get("user"), pass = values.get("pass");
                    io.execute(() -> {
                        try (Imap c = Imap.login(host, 993, user, pass)) {
                            Accounts.Account a = new Accounts.Account();
                            a.id = Accounts.newId();
                            a.owner = owner;
                            a.kind = Accounts.IMAP;
                            a.host = host;
                            a.person = Accounts.leaderName(this, owner) + " (at the TV)";
                            a.ownDomain = OfficeData.domainOf(user);
                            a.reads = "Mail headers from Inbox and Sent (sender name and domain, subject, date, read and "
                                    + "flag state). No bodies or attachments.";
                            a.scopes = "IMAP read-only mode (EXAMINE, BODY.PEEK) on " + host
                                    + ". The mailbox password itself is not limited by the provider.";
                            a.connectedAt = System.currentTimeMillis();
                            Vault v = new Vault(this);
                            v.put(a.vaultPrefix() + "user", user);
                            v.put(a.vaultPrefix() + "pass", pass);
                            accounts.save(a);
                            Log.i(TAG, "IMAP mailbox connected");
                            OfficeSync.kick(this);
                            main.post(() -> showPanel("Connected", "Mailbox headers connected, read-only. Press Back.", null, null));
                        } catch (Exception e) {
                            boolean wrong = "login".equals(e.getMessage());
                            Log.w(TAG, "IMAP connect failed: " + (wrong ? "login refused" : e.getClass().getSimpleName()));
                            main.post(() -> showPanel("Not connected", wrong ? "The server refused that address or password."
                                    : "Couldn't reach " + host + " on port 993.", null, null));
                        }
                    });
                });
    }

    // ---- Phone page and panel --------------------------------------------------------------------

    private void phonePage(String owner, String title, String note, PhoneEntry.Field[] fields, PhoneEntry.Done done) {
        cancelFlow();
        entry = PhoneEntry.open(title, note, fields, v -> {
            entry = null;
            done.onSubmit(v);
        }, why -> {
            if (entry != null && !"submitted".equals(why)) {
                entry = null;
                showPanel("Phone page closed", ("too many tries".equals(why) ? "Too many wrong codes." : "It timed out.")
                        + " Press Back and start again.", null, null);
            }
        });
        if (entry == null) {
            showPanel("No network", "The TV has no Wi-Fi address right now. Press Back.", null, null);
            return;
        }
        showPanel(title, "Scan with a phone on the office Wi-Fi, then enter this code on the page. Works once, for "
                + "5 minutes. Address: " + entry.url.replaceFirst("/[0-9a-f]{32}$", "/…"), entry.url, entry.code);
    }

    private void showPanel(String title, String body, String qr, String bigCode) {
        if (panel != null) root.removeView(panel);
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER);
        p.setBackgroundColor(getColor(R.color.office_bg));
        p.setFocusable(true);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 26, R.color.office_text, R.font.inter_medium));
        TextView b = text(body, 17, R.color.office_text_2, R.font.inter_regular);
        b.setSingleLine(false);
        b.setMaxWidth(dp(420));
        b.setPadding(0, dp(10), 0, 0);
        words.addView(b);
        if (bigCode != null) {
            TextView code = text(bigCode, 44, R.color.office_text, R.font.inter_medium);
            code.setLetterSpacing(0.12f);
            code.setPadding(0, dp(12), 0, 0);
            words.addView(code);
        }
        if (qr != null) {
            Bitmap bmp = Qr.of(qr, dp(220));
            if (bmp != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bmp);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(220));
                lp.setMarginEnd(dp(36));
                p.addView(iv, lp);
            }
        }
        p.addView(words);
        panel = p;
        root.addView(p, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        p.requestFocus();
    }

    private void cancelFlow() {
        cancelled = true;
        if (entry != null) {
            PhoneEntry e = entry;
            entry = null;
            e.close();
        }
        if (panel != null) {
            root.removeView(panel);
            panel = null;
        }
    }

    // ---- Small views -----------------------------------------------------------------------------

    private TextView text(String s, int sp, int color, int font) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(color));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(getResources().getFont(font));
        return t;
    }

    private TextView line(String s) {
        TextView t = text(s, 15, R.color.office_text_2, R.font.inter_regular);
        t.setSingleLine(false);
        t.setPadding(0, dp(3), 0, 0);
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, dp(8), 0, 0);
        return r;
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private View button(String label, View.OnClickListener click) {
        TextView t = text(label, 16, R.color.office_text, R.font.inter_regular);
        t.setFocusable(true);
        t.setClickable(true);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        t.setBackground(cardBg(false));
        t.setOnFocusChangeListener((v, has) -> v.setBackground(cardBg(has)));
        t.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(10));
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable cardBg(boolean focused) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(getColor(focused ? R.color.office_surface_hi : R.color.office_surface));
        g.setStroke(dp(focused ? 2 : 1), focused ? getColor(R.color.marvice_accent_2) : getColor(R.color.office_stroke));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
