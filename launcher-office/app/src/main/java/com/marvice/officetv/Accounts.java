package com.marvice.officetv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The connections list behind Privacy & accounts: who connected what, when, and what it can read. Holds no
 * secret: tokens and passwords live in the Vault under "acct.&lt;id&gt;.", and Disconnect removes both.
 */
final class Accounts {
    static final String M365 = "m365", IMAP = "imap";
    static final String MARVICE = "marvice", INFYST = "infyst";
    private static final String PREFS = "accounts";

    static final class Account {
        String id, owner, kind, person, ownDomain, reads, scopes, lastError;
        String tenant, clientId, host;     // non-secret settings per kind
        long connectedAt, lastSync;
        boolean paused;

        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("owner", owner).put("kind", kind).put("person", person)
                    .put("ownDomain", ownDomain).put("reads", reads).put("scopes", scopes)
                    .put("lastError", lastError).put("tenant", tenant).put("clientId", clientId).put("host", host)
                    .put("connectedAt", connectedAt).put("lastSync", lastSync).put("paused", paused);
        }

        static Account from(JSONObject o) {
            Account a = new Account();
            a.id = o.optString("id");
            a.owner = o.optString("owner", MARVICE);
            a.kind = o.optString("kind");
            a.person = o.optString("person", "");
            a.ownDomain = o.optString("ownDomain", "");
            a.reads = o.optString("reads", "");
            a.scopes = o.optString("scopes", "");
            a.lastError = o.has("lastError") && !o.isNull("lastError") ? o.optString("lastError") : null;
            a.tenant = o.optString("tenant", null);
            a.clientId = o.optString("clientId", null);
            a.host = o.optString("host", null);
            a.connectedAt = o.optLong("connectedAt");
            a.lastSync = o.optLong("lastSync");
            a.paused = o.optBoolean("paused");
            return a;
        }

        String vaultPrefix() { return "acct." + id + "."; }

        String kindLabel() { return M365.equals(kind) ? "Microsoft 365" : "Mailbox (IMAP)"; }
    }

    private final SharedPreferences prefs;

    Accounts(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized List<Account> all() {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Account.from(a.getJSONObject(i)));
        } catch (JSONException ignored) { }
        return out;
    }

    synchronized List<Account> forOwner(String owner) {
        List<Account> out = new ArrayList<>();
        for (Account a : all()) if (a.owner.equals(owner)) out.add(a);
        return out;
    }

    synchronized Account get(String id) {
        for (Account a : all()) if (a.id.equals(id)) return a;
        return null;
    }

    /** Adds or replaces by id. */
    synchronized void save(Account acct) {
        List<Account> list = all();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(acct.id)) { list.set(i, acct); replaced = true; }
        }
        if (!replaced) list.add(acct);
        write(list);
    }

    /** Disconnect: forgets the account, its secrets and its cached calendar and mail. */
    synchronized void remove(Context ctx, String id) {
        List<Account> list = all();
        list.removeIf(a -> a.id.equals(id));
        write(list);
        new Vault(ctx).removePrefix("acct." + id + ".");
        OfficeData.get(ctx).forget(id);
    }

    private void write(List<Account> list) {
        JSONArray a = new JSONArray();
        try {
            for (Account x : list) a.put(x.json());
        } catch (JSONException ignored) { }
        prefs.edit().putString("list", a.toString()).commit();
    }

    static String newId() {
        byte[] b = new byte[4];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    static String leaderName(Context ctx, String owner) {
        return ctx.getString(INFYST.equals(owner) ? R.string.leader_infyst : R.string.leader_marvice);
    }
}
