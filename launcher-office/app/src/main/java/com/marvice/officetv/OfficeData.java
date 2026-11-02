package com.marvice.officetv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the TV keeps from calendars and mail, in app-private storage, and what it derives: change alerts and
 * follow-ups. Meetings: title (trimmed), start, end, cancelled, online. Mail: sender name, sender domain,
 * subject (trimmed), date, thread id, flags. Never bodies, attachments, full addresses or attendees.
 */
final class OfficeData {
    static final TimeZone OFFICE_TZ = TimeZone.getTimeZone("Asia/Kolkata");
    static final long MAIL_KEEP_MS = 14L * 24 * 3600_000L;
    private static final long REPLY_AFTER_MS = 4L * 3600_000L;        // their mail unanswered for 4 h
    private static final long CHASE_AFTER_MS = 3L * 24 * 3600_000L;   // our mail unanswered for 3 days

    static final class Ev {
        String acct, owner, uid, title;
        long start, end;
        boolean cancelled, online;
    }

    static final class Msg {
        String acct, owner, tid, name, domain, subj;
        long at;
        boolean out, read, flagged;
    }

    static final class Alert {
        long at;
        String owner, text;
    }

    static final class FollowUp {
        String owner, line;
        long since;
        boolean theirTurn;     // true: waiting on them; false: waiting on us
    }

    private static OfficeData instance;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private OfficeData(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences("office_data", Context.MODE_PRIVATE);
    }

    static synchronized OfficeData get(Context ctx) {
        if (instance == null) instance = new OfficeData(ctx);
        return instance;
    }

    void listen(Runnable r) { listeners.add(r); }
    void unlisten(Runnable r) { listeners.remove(r); }
    private void changed() { for (Runnable r : listeners) main.post(r); }

    // ---- Meetings ---------------------------------------------------------------------------------

    /** Replaces one account's meetings and returns the alerts the change produced (none on the first sync). */
    synchronized List<Alert> putEvents(String acct, String owner, List<Ev> fresh) {
        String key = "ev." + acct;
        boolean first = !prefs.contains(key);
        List<Ev> old = loadEvents(acct);
        List<Alert> made = new ArrayList<>();
        if (!first) {
            long now = System.currentTimeMillis();
            Map<String, Ev> byId = new HashMap<>();
            for (Ev e : fresh) byId.put(e.uid, e);
            Map<String, Ev> oldById = new HashMap<>();
            for (Ev e : old) oldById.put(e.uid, e);
            for (Ev o : old) {
                if (o.cancelled || o.end <= now) continue;
                Ev n = byId.get(o.uid);
                if (n == null) made.add(alert(owner, "Removed: " + o.title + " (was " + time(o.start) + ")"));
                else if (n.cancelled) made.add(alert(owner, "Cancelled: " + o.title + " at " + time(o.start)));
                else if (n.start != o.start) made.add(alert(owner, "Moved: " + n.title + " to " + dayTime(n.start)
                        + " (was " + time(o.start) + ")"));
            }
            for (Ev n : fresh) {
                if (!n.cancelled && n.start > now && isToday(n.start) && !oldById.containsKey(n.uid)) {
                    made.add(alert(owner, "New today: " + n.title + " at " + time(n.start)));
                }
            }
        }
        JSONArray a = new JSONArray();
        try {
            for (Ev e : fresh) {
                a.put(new JSONObject().put("u", e.uid).put("t", e.title).put("s", e.start).put("e", e.end)
                        .put("c", e.cancelled).put("o", e.online).put("w", owner));
            }
        } catch (JSONException ignored) { }
        SharedPreferences.Editor ed = prefs.edit().putString(key, a.toString());
        ed.apply();
        if (!made.isEmpty()) addAlerts(made);
        changed();
        return made;
    }

    synchronized List<Ev> loadEvents(String acct) {
        List<Ev> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("ev." + acct, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Ev e = new Ev();
                e.acct = acct;
                e.uid = o.getString("u");
                e.title = o.optString("t");
                e.start = o.getLong("s");
                e.end = o.getLong("e");
                e.cancelled = o.optBoolean("c");
                e.online = o.optBoolean("o");
                e.owner = o.optString("w");
                out.add(e);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    /** Today's meetings for one leader across their connected accounts, by start time. */
    List<Ev> today(Context ctx, String owner) {
        List<Ev> out = new ArrayList<>();
        for (Accounts.Account a : new Accounts(ctx).forOwner(owner)) {
            if (a.paused) continue;
            for (Ev e : loadEvents(a.id)) if (isToday(e.start) || (e.start < now() && e.end > now())) out.add(e);
        }
        Collections.sort(out, (x, y) -> Long.compare(x.start, y.start));
        return out;
    }

    /** True once any account of this leader has synced its calendar, so the card stops showing samples. */
    boolean hasCalendar(Context ctx, String owner) {
        for (Accounts.Account a : new Accounts(ctx).forOwner(owner)) {
            if (!a.paused && prefs.contains("ev." + a.id)) return true;
        }
        return false;
    }

    // ---- Mail and follow-ups ----------------------------------------------------------------------

    synchronized void putMail(String acct, List<Msg> msgs) {
        long cutoff = System.currentTimeMillis() - MAIL_KEEP_MS;
        JSONArray a = new JSONArray();
        try {
            for (Msg m : msgs) {
                if (m.at < cutoff) continue;
                a.put(new JSONObject().put("t", m.tid).put("n", m.name).put("d", m.domain).put("s", m.subj)
                        .put("a", m.at).put("o", m.out).put("r", m.read).put("f", m.flagged).put("w", m.owner));
            }
        } catch (JSONException ignored) { }
        prefs.edit().putString("mail." + acct, a.toString()).apply();
        changed();
    }

    synchronized List<Msg> loadMail(String acct) {
        List<Msg> out = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - MAIL_KEEP_MS;
        try {
            JSONArray a = new JSONArray(prefs.getString("mail." + acct, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Msg m = new Msg();
                m.acct = acct;
                m.tid = o.optString("t");
                m.name = o.optString("n");
                m.domain = o.optString("d");
                m.subj = o.optString("s");
                m.at = o.optLong("a");
                m.out = o.optBoolean("o");
                m.read = o.optBoolean("r");
                m.flagged = o.optBoolean("f");
                m.owner = o.optString("w");
                if (m.at >= cutoff) out.add(m);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    /**
     * Follow-ups for one leader: threads whose newest message is theirs to a person outside the company and
     * older than 4 hours (reply owed), or ours to an outside person older than 3 days (chase). Flagged mail
     * always counts. Automated senders never count.
     */
    List<FollowUp> followUps(Context ctx, String owner) {
        List<FollowUp> out = new ArrayList<>();
        long now = now();
        for (Accounts.Account acct : new Accounts(ctx).forOwner(owner)) {
            if (acct.paused) continue;
            Map<String, Msg> newest = new HashMap<>();
            Map<String, Boolean> flagged = new HashMap<>();
            for (Msg m : loadMail(acct.id)) {
                Msg cur = newest.get(m.tid);
                if (cur == null || m.at > cur.at) newest.put(m.tid, m);
                if (m.flagged && !m.out) flagged.put(m.tid, true);
            }
            for (Msg m : newest.values()) {
                if (automated(m) || m.domain.equalsIgnoreCase(acct.ownDomain)) continue;
                long age = now - m.at;
                FollowUp f = null;
                if (!m.out && (age >= REPLY_AFTER_MS || flagged.containsKey(m.tid))) {
                    f = new FollowUp();
                    f.theirTurn = false;
                    f.line = "Reply: " + who(m) + " · " + m.subj;
                } else if (m.out && age >= CHASE_AFTER_MS) {
                    f = new FollowUp();
                    f.theirTurn = true;
                    f.line = "Chase: " + who(m) + " · " + m.subj;
                }
                if (f != null) {
                    f.owner = owner;
                    f.since = m.at;
                    out.add(f);
                }
            }
        }
        Collections.sort(out, (x, y) -> Long.compare(x.since, y.since));   // most overdue first
        return out;
    }

    private static String who(Msg m) {
        String n = m.name == null || m.name.isEmpty() ? m.domain : m.name;
        return m.name == null || m.name.isEmpty() || m.domain.isEmpty() ? n : n + " (" + m.domain + ")";
    }

    private static boolean automated(Msg m) {
        String s = (m.name + " " + m.domain).toLowerCase(Locale.ROOT);
        return s.contains("noreply") || s.contains("no-reply") || s.contains("donotreply") || s.contains("notification")
                || s.contains("mailer") || s.contains("newsletter") || s.contains("alerts") || m.domain.isEmpty();
    }

    // ---- Alerts -----------------------------------------------------------------------------------

    private Alert alert(String owner, String text) {
        Alert a = new Alert();
        a.at = System.currentTimeMillis();
        a.owner = owner;
        a.text = text;
        return a;
    }

    synchronized void addAlerts(List<Alert> fresh) {
        List<Alert> all = alerts();
        all.addAll(0, fresh);
        JSONArray a = new JSONArray();
        long cutoff = System.currentTimeMillis() - 24 * 3600_000L;
        try {
            for (Alert x : all) {
                if (x.at < cutoff || a.length() >= 20) continue;
                a.put(new JSONObject().put("a", x.at).put("w", x.owner).put("t", x.text));
            }
        } catch (JSONException ignored) { }
        prefs.edit().putString("alerts", a.toString()).apply();
    }

    synchronized List<Alert> alerts() {
        List<Alert> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("alerts", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Alert x = new Alert();
                x.at = o.getLong("a");
                x.owner = o.optString("w");
                x.text = o.optString("t");
                out.add(x);
            }
        } catch (JSONException ignored) { }
        return out;
    }

    /** Remembers which reminders already showed, so a re-sync never repeats one. */
    synchronized boolean firstTime(String key) {
        String all = prefs.getString("reminded", "");
        if (all.contains("\n" + key + "\n")) return false;
        String[] keep = all.split("\n");
        StringBuilder b = new StringBuilder("\n");
        for (int i = Math.max(0, keep.length - 60); i < keep.length; i++) if (!keep[i].isEmpty()) b.append(keep[i]).append('\n');
        b.append(key).append('\n');
        prefs.edit().putString("reminded", b.toString()).apply();
        return true;
    }

    synchronized void forget(String acct) {
        prefs.edit().remove("ev." + acct).remove("mail." + acct).apply();
        changed();
    }

    // ---- Time helpers -----------------------------------------------------------------------------

    static long now() { return System.currentTimeMillis(); }

    static boolean isToday(long t) {
        SimpleDateFormat d = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        d.setTimeZone(OFFICE_TZ);
        return d.format(t).equals(d.format(now()));
    }

    static String time(long t) {
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.ENGLISH);
        f.setTimeZone(OFFICE_TZ);
        return f.format(t);
    }

    static String dayTime(long t) {
        if (isToday(t)) return time(t);
        SimpleDateFormat f = new SimpleDateFormat("EEE h:mm a", Locale.ENGLISH);
        f.setTimeZone(OFFICE_TZ);
        return f.format(t);
    }

    static String trim(String s, int max) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max - 1).trim() + "…";
    }

    /** The part after @ only; the person's mailbox name is dropped before anything is stored. */
    static String domainOf(String address) {
        if (address == null) return "";
        int at = address.lastIndexOf('@');
        return at < 0 ? "" : address.substring(at + 1).toLowerCase(Locale.ROOT).trim();
    }
}
