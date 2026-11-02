package com.marvice.officetv;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Microsoft 365 through Microsoft Graph, read-only. Sign-in is the OAuth device-code flow (the person types
 * a short code on their own phone), so the TV never sees a password. Scopes: Calendars.Read (meetings),
 * Mail.ReadBasic (mail without bodies or attachments), User.Read (name and domain), offline_access (stay
 * signed in). Nothing here can send, delete, move or mark mail: no write scope is ever requested.
 */
final class Graph {
    static final String SCOPES = "offline_access User.Read Calendars.Read Mail.ReadBasic";
    static final String READS = "Your calendar (meeting titles and times) and mail headers (sender name and "
            + "domain, subject, date, read and flag state). No mail bodies, attachments or addresses. Read only.";
    private static final String TAG = "Graph";
    private static final String LOGIN = "https://login.microsoftonline.com/";
    private static final String API = "https://graph.microsoft.com/v1.0";

    static final class DeviceCode {
        String deviceCode, userCode, verifyUrl;
        long expiresAt;
        int interval;
    }

    /** Sign-in failure the person must act on; message is safe to show and to log. */
    static final class AuthException extends Exception {
        AuthException(String m) { super(m); }
    }

    private Graph() {}

    // ---- Device-code sign-in ---------------------------------------------------------------------

    static DeviceCode startDeviceCode(String tenant, String clientId) throws IOException, AuthException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", clientId);
        f.put("scope", SCOPES);
        Net.Resp r = Net.postForm(LOGIN + enc(tenant) + "/oauth2/v2.0/devicecode", f);
        JSONObject o = json(r);
        if (r.code != 200) throw new AuthException(describe(o));
        DeviceCode d = new DeviceCode();
        d.deviceCode = o.optString("device_code");
        d.userCode = o.optString("user_code");
        d.verifyUrl = o.optString("verification_uri", "https://microsoft.com/devicelogin");
        d.expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 900) * 1000L;
        d.interval = Math.max(5, o.optInt("interval", 5));
        return d;
    }

    /**
     * One poll of the token endpoint. Returns null while the person is still signing in; on success stores
     * the tokens in the Vault and returns the scopes Microsoft granted. Throws when the code expired or
     * the person declined. "slow_down" adds 5 s to d.interval.
     */
    static String pollDeviceCode(Context ctx, Accounts.Account a, DeviceCode d) throws IOException, AuthException {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        f.put("client_id", a.clientId);
        f.put("device_code", d.deviceCode);
        Net.Resp r = Net.postForm(LOGIN + enc(a.tenant) + "/oauth2/v2.0/token", f);
        JSONObject o = json(r);
        if (r.code == 200) {
            store(ctx, a, o);
            return o.optString("scope", SCOPES);
        }
        String err = o.optString("error");
        if ("authorization_pending".equals(err)) return null;
        if ("slow_down".equals(err)) { d.interval += 5; return null; }
        if ("expired_token".equals(err)) throw new AuthException("The code expired. Start again.");
        if ("authorization_declined".equals(err) || "access_denied".equals(err)) {
            throw new AuthException("Sign-in was declined on the phone.");
        }
        throw new AuthException(describe(o));
    }

    private static void store(Context ctx, Accounts.Account a, JSONObject o) {
        Vault v = new Vault(ctx);
        v.put(a.vaultPrefix() + "at", o.optString("access_token"));
        v.put(a.vaultPrefix() + "exp", Long.toString(System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000L));
        String rt = o.optString("refresh_token", "");
        if (!rt.isEmpty()) v.put(a.vaultPrefix() + "rt", rt);   // Microsoft rotates it: always keep the newest
    }

    /** A valid access token, refreshed when it has under 5 minutes left. */
    private static String token(Context ctx, Accounts.Account a) throws IOException, AuthException {
        Vault v = new Vault(ctx);
        String at = v.get(a.vaultPrefix() + "at");
        String exp = v.get(a.vaultPrefix() + "exp");
        long expAt = 0;
        try { expAt = exp == null ? 0 : Long.parseLong(exp); } catch (NumberFormatException ignored) { }
        if (at != null && expAt - System.currentTimeMillis() > 5 * 60_000L) return at;
        String rt = v.get(a.vaultPrefix() + "rt");
        if (rt == null) throw new AuthException("Not signed in. Reconnect on Privacy & accounts.");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("grant_type", "refresh_token");
        f.put("client_id", a.clientId);
        f.put("refresh_token", rt);
        f.put("scope", SCOPES);
        Net.Resp r = Net.postForm(LOGIN + enc(a.tenant) + "/oauth2/v2.0/token", f);
        JSONObject o = json(r);
        if (r.code != 200) {
            if ("invalid_grant".equals(o.optString("error"))) {
                throw new AuthException("Sign-in expired or was revoked. Reconnect on Privacy & accounts.");
            }
            throw new IOException("token refresh HTTP " + r.code);
        }
        store(ctx, a, o);
        return o.optString("access_token");
    }

    // ---- Reads -----------------------------------------------------------------------------------

    /** Signed-in person's display name and mail domain: [name, domain]. */
    static String[] me(Context ctx, Accounts.Account a) throws IOException, AuthException {
        JSONObject o = getJson(ctx, a, API + "/me?$select=displayName,mail,userPrincipalName");
        String mail = o.optString("mail", "");
        if (mail.isEmpty() || "null".equals(mail)) mail = o.optString("userPrincipalName", "");
        return new String[]{o.optString("displayName", ""), OfficeData.domainOf(mail)};
    }

    /** Meetings from 12 hours ago to 7 days ahead. */
    static List<OfficeData.Ev> calendar(Context ctx, Accounts.Account a) throws IOException, AuthException {
        long now = System.currentTimeMillis();
        String url = API + "/me/calendarView?startDateTime=" + iso(now - 12 * 3600_000L)
                + "&endDateTime=" + iso(now + 7L * 24 * 3600_000L)
                + "&$select=id,subject,start,end,isCancelled,isOnlineMeeting,showAs,sensitivity"
                + "&$orderby=start/dateTime&$top=200";
        List<OfficeData.Ev> out = new ArrayList<>();
        for (JSONObject o : pages(ctx, a, url, 4)) {
            if ("free".equals(o.optString("showAs"))) continue;           // holds and all-day reminders
            OfficeData.Ev e = new OfficeData.Ev();
            e.acct = a.id;
            e.owner = a.owner;
            e.uid = o.optString("id");
            boolean secret = "private".equals(o.optString("sensitivity")) || "confidential".equals(o.optString("sensitivity"));
            e.title = secret ? "Private meeting" : OfficeData.trim(o.optString("subject", "(no title)"), 80);
            e.start = utc(o.optJSONObject("start"));
            e.end = utc(o.optJSONObject("end"));
            e.cancelled = o.optBoolean("isCancelled");
            e.online = o.optBoolean("isOnlineMeeting");
            if (e.start > 0 && e.end > 0) out.add(e);
        }
        return out;
    }

    /** Mail headers from the last 14 days: Focused inbox and Sent. */
    static List<OfficeData.Msg> mail(Context ctx, Accounts.Account a) throws IOException, AuthException {
        String since = iso(System.currentTimeMillis() - OfficeData.MAIL_KEEP_MS);
        List<OfficeData.Msg> out = new ArrayList<>();
        String in = API + "/me/mailFolders/inbox/messages?$select=subject,from,receivedDateTime,conversationId,"
                + "isRead,flag,inferenceClassification&$filter=receivedDateTime%20ge%20" + since
                + "&$orderby=receivedDateTime%20desc&$top=100";
        for (JSONObject o : pages(ctx, a, in, 3)) {
            if (!"focused".equals(o.optString("inferenceClassification", "focused"))) continue;
            JSONObject from = o.optJSONObject("from");
            JSONObject addr = from == null ? null : from.optJSONObject("emailAddress");
            OfficeData.Msg m = msg(a, o, addr, o.optString("receivedDateTime"));
            m.out = false;
            m.read = o.optBoolean("isRead");
            JSONObject flag = o.optJSONObject("flag");
            m.flagged = flag != null && "flagged".equals(flag.optString("flagStatus"));
            out.add(m);
        }
        String sent = API + "/me/mailFolders/sentitems/messages?$select=subject,toRecipients,sentDateTime,"
                + "conversationId&$filter=sentDateTime%20ge%20" + since + "&$orderby=sentDateTime%20desc&$top=100";
        for (JSONObject o : pages(ctx, a, sent, 3)) {
            JSONArray to = o.optJSONArray("toRecipients");
            JSONObject first = to != null && to.length() > 0 ? to.optJSONObject(0) : null;
            OfficeData.Msg m = msg(a, o, first == null ? null : first.optJSONObject("emailAddress"),
                    o.optString("sentDateTime"));
            m.out = true;
            m.read = true;
            out.add(m);
        }
        return out;
    }

    private static OfficeData.Msg msg(Accounts.Account a, JSONObject o, JSONObject addr, String when) {
        OfficeData.Msg m = new OfficeData.Msg();
        m.acct = a.id;
        m.owner = a.owner;
        m.tid = o.optString("conversationId");
        m.subj = OfficeData.trim(o.optString("subject", ""), 70);
        String address = addr == null ? "" : addr.optString("address", "");
        String name = addr == null ? "" : addr.optString("name", "");
        m.domain = OfficeData.domainOf(address);
        m.name = name.contains("@") ? "" : OfficeData.trim(name, 40);     // a bare address as name is dropped
        m.at = parseUtc(when.length() >= 19 ? when.substring(0, 19) : when);
        return m;
    }

    // ---- HTTP plumbing ---------------------------------------------------------------------------

    private static List<JSONObject> pages(Context ctx, Accounts.Account a, String url, int maxPages)
            throws IOException, AuthException {
        List<JSONObject> out = new ArrayList<>();
        String next = url;
        for (int p = 0; p < maxPages && next != null; p++) {
            JSONObject o = getJson(ctx, a, next);
            JSONArray v = o.optJSONArray("value");
            if (v != null) for (int i = 0; i < v.length(); i++) out.add(v.optJSONObject(i));
            next = o.has("@odata.nextLink") ? o.optString("@odata.nextLink") : null;
        }
        return out;
    }

    private static JSONObject getJson(Context ctx, Accounts.Account a, String url) throws IOException, AuthException {
        Net.Resp r = Net.get(url, token(ctx, a));
        if (r.code == 401) {
            new Vault(ctx).remove(a.vaultPrefix() + "exp");      // force one refresh, then retry once
            r = Net.get(url, token(ctx, a));
        }
        if (r.code == 403) throw new AuthException("Microsoft 365 refused access (403). An admin may need to approve the app.");
        if (r.code != 200) {
            Log.w(TAG, "Graph HTTP " + r.code);
            throw new IOException("Graph HTTP " + r.code);
        }
        return json(r);
    }

    private static JSONObject json(Net.Resp r) {
        try {
            return new JSONObject(r.body);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** The provider's error code and the first line of its description (never contains a token). */
    private static String describe(JSONObject o) {
        String e = o.optString("error", "error");
        String d = o.optString("error_description", "");
        if (d.contains("AADSTS700016") || d.contains("AADSTS700038")) {
            return "That Application (client) ID isn't an app in this directory. Check it in Entra, App registrations.";
        }
        if (d.contains("AADSTS90002")) return "That tenant (directory) wasn't found. Check the tenant ID or domain.";
        if (d.contains("AADSTS7000218")) {
            return "The app needs 'Allow public client flows' set to Yes (Entra, App registrations, Authentication).";
        }
        if (d.contains("AADSTS65001") || d.contains("AADSTS90094")) {
            return "Your Microsoft 365 admin must approve the Office TV app first (consent required).";
        }
        int trace = d.indexOf(" Trace ID");
        if (trace > 0) d = d.substring(0, trace);
        int nl = d.indexOf('\n');
        if (nl > 0) d = d.substring(0, nl);
        if (d.length() > 160) d = d.substring(0, 160);
        return d.isEmpty() ? e : e + ": " + d.trim();
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String iso(long t) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(t);
    }

    private static long utc(JSONObject dt) {
        if (dt == null) return 0;
        String s = dt.optString("dateTime", "");
        return parseUtc(s.length() >= 19 ? s.substring(0, 19) : s);
    }

    private static long parseUtc(String s) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try { return f.parse(s).getTime(); } catch (ParseException | NullPointerException e) { return 0; }
    }
}
