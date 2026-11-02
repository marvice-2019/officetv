package com.marvice.officetv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Read-only IMAP over TLS (port 993) for mail headers. Folders are opened with EXAMINE (the server's read-only
 * mode) and headers fetched with BODY.PEEK, so nothing is marked read. This client has no command that
 * writes: no STORE, COPY, MOVE, APPEND, EXPUNGE or DELETE exists in it. Bodies and attachments are never
 * requested.
 */
final class Imap implements AutoCloseable {
    private final SSLSocket sock;
    private final InputStream in;
    private final OutputStream out;
    private int tag;

    private Imap(String host, int port) throws IOException {
        sock = (SSLSocket) SSLSocketFactory.getDefault().createSocket(host, port);
        sock.setSoTimeout(30_000);
        sock.startHandshake();
        // SSLSocket checks the chain but not the name: check it, or any valid certificate would do.
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, sock.getSession())) {
            sock.close();
            throw new IOException("certificate does not match " + host);
        }
        in = sock.getInputStream();
        out = sock.getOutputStream();
        String greet = line();
        if (!greet.startsWith("* OK")) throw new IOException("unexpected greeting");
    }

    /** Signs in; the password goes only to the server, over TLS. Throws IOException("login") on a wrong password. */
    static Imap login(String host, int port, String user, String password) throws IOException {
        Imap c = new Imap(host, port);
        List<String> r = c.cmd("LOGIN " + quote(user) + " " + quote(password), false);
        if (!ok(r)) {
            c.close();
            throw new IOException("login");
        }
        return c;
    }

    /** Recent headers from one folder (null name = the server's Sent folder; returns empty if none). */
    List<OfficeData.Msg> headers(String folder, boolean sent, long since, String acct, String owner, int max) throws IOException {
        List<OfficeData.Msg> outList = new ArrayList<>();
        if (folder == null) folder = sentFolder();
        if (folder == null) return outList;
        if (!ok(cmd("EXAMINE " + quote(folder), false))) return outList;
        SimpleDateFormat d = new SimpleDateFormat("dd-MMM-yyyy", Locale.ENGLISH);
        d.setTimeZone(OfficeData.OFFICE_TZ);
        List<String> found = cmd("UID SEARCH SINCE " + d.format(since), false);
        List<String> uids = new ArrayList<>();
        for (String l : found) {
            if (l.startsWith("* SEARCH")) for (String u : l.substring(8).trim().split(" ")) if (!u.isEmpty()) uids.add(u);
        }
        if (uids.isEmpty()) return outList;
        List<String> pick = uids.subList(Math.max(0, uids.size() - max), uids.size());
        String set = String.join(",", pick);
        List<String> rows = cmd("UID FETCH " + set + " (UID FLAGS INTERNALDATE BODY.PEEK[HEADER.FIELDS "
                + "(FROM TO SUBJECT MESSAGE-ID IN-REPLY-TO REFERENCES)])", true);
        for (String row : rows) {
            if (!row.startsWith("* ") || !row.contains("FETCH")) continue;
            OfficeData.Msg m = new OfficeData.Msg();
            m.acct = acct;
            m.owner = owner;
            m.out = sent;
            String flags = group(row, "FLAGS \\(([^)]*)\\)");
            m.read = sent || flags.contains("\\Seen");
            m.flagged = !sent && flags.contains("\\Flagged");
            m.at = internalDate(group(row, "INTERNALDATE \"([^\"]+)\""));
            String head = row.substring(Math.max(0, row.indexOf('\n') + 1));
            String who = header(head, sent ? "To" : "From");
            m.subj = OfficeData.trim(decode(header(head, "Subject")), 70);
            String[] nameAddr = nameAddress(who);
            m.name = OfficeData.trim(decode(nameAddr[0]), 40);
            m.domain = OfficeData.domainOf(nameAddr[1]);
            String refs = header(head, "References");
            String root = refs.isEmpty() ? header(head, "In-Reply-To") : refs.trim().split("\\s+")[0];
            if (root.isEmpty()) root = header(head, "Message-ID");
            m.tid = hash(root.isEmpty() ? m.subj : root);
            outList.add(m);
        }
        return outList;
    }

    private String sentFolder() throws IOException {
        for (String l : cmd("LIST \"\" \"*\"", false)) {
            if (l.startsWith("* LIST") && l.contains("\\Sent")) {
                String name = l.substring(l.lastIndexOf(' ') + 1);
                if (l.endsWith("\"")) name = l.substring(l.lastIndexOf('"', l.length() - 2) + 1, l.length() - 1);
                return name;
            }
        }
        return null;
    }

    @Override public void close() {
        try { cmd("LOGOUT", false); } catch (IOException ignored) { }
        try { sock.close(); } catch (IOException ignored) { }
    }

    // ---- Protocol --------------------------------------------------------------------------------

    /** Sends one command and collects responses until its tagged reply. Literals are inlined after a newline. */
    private List<String> cmd(String command, boolean literals) throws IOException {
        String t = "a" + (++tag);
        out.write((t + " " + command + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        List<String> lines = new ArrayList<>();
        while (true) {
            String l = line();
            StringBuilder full = new StringBuilder(l);
            Matcher lit = Pattern.compile("\\{(\\d+)\\}$").matcher(l);
            while (lit.find()) {
                int n = Integer.parseInt(lit.group(1));
                if (n > 64 * 1024) throw new IOException("literal too large");
                byte[] b = new byte[n];
                int got = 0;
                while (got < n) {
                    int k = in.read(b, got, n - got);
                    if (k < 0) throw new IOException("closed");
                    got += k;
                }
                String rest = line();
                full.append('\n').append(new String(b, StandardCharsets.ISO_8859_1)).append(rest);
                lit = Pattern.compile("\\{(\\d+)\\}$").matcher(rest);
            }
            lines.add(full.toString());
            if (l.startsWith(t + " ")) return lines;
        }
    }

    private static boolean ok(List<String> r) {
        String last = r.get(r.size() - 1);
        return last.matches("^a\\d+ OK.*");
    }

    private String line() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, prev = -1;
        while ((c = in.read()) >= 0) {
            if (prev == '\r' && c == '\n') {
                byte[] a = b.toByteArray();
                return new String(a, 0, a.length - 1, StandardCharsets.ISO_8859_1);
            }
            b.write(c);
            if (b.size() > 256 * 1024) throw new IOException("line too long");
            prev = c;
        }
        throw new IOException("connection closed");
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---- Header parsing --------------------------------------------------------------------------

    private static String header(String head, String name) {
        String unfolded = head.replaceAll("\r?\n[ \t]+", " ");
        for (String l : unfolded.split("\r?\n")) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase(name)) return l.substring(i + 1).trim();
        }
        return "";
    }

    /** "Name <a@b>" or "a@b": [name, address], first recipient only. */
    private static String[] nameAddress(String v) {
        String first = v.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)")[0].trim();
        int lt = first.indexOf('<'), gt = first.indexOf('>');
        if (lt >= 0 && gt > lt) {
            String name = first.substring(0, lt).trim().replaceAll("^\"|\"$", "");
            return new String[]{name, first.substring(lt + 1, gt)};
        }
        return new String[]{"", first};
    }

    /** RFC 2047 encoded words (=?utf-8?B?..?= and ?Q?). */
    static String decode(String s) {
        Matcher m = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=").matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String text;
            try {
                Charset cs = Charset.forName(m.group(1));
                byte[] raw;
                if (m.group(2).equalsIgnoreCase("B")) {
                    raw = Base64.getMimeDecoder().decode(m.group(3));
                } else {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    String q = m.group(3).replace('_', ' ');
                    for (int i = 0; i < q.length(); i++) {
                        char c = q.charAt(i);
                        if (c == '=' && i + 2 < q.length()) {
                            o.write(Integer.parseInt(q.substring(i + 1, i + 3), 16));
                            i += 2;
                        } else o.write(c);
                    }
                    raw = o.toByteArray();
                }
                text = new String(raw, cs);
            } catch (Exception e) {
                text = m.group(3);
            }
            m.appendReplacement(b, Matcher.quoteReplacement(text));
        }
        m.appendTail(b);
        return b.toString().replaceAll("\\?=\\s+=\\?", "");
    }

    private static String group(String s, String re) {
        Matcher m = Pattern.compile(re).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    private static long internalDate(String s) {
        try {
            return new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", Locale.ENGLISH).parse(s.trim()).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    /** Thread ids are hashed: a Message-ID can carry a hostname or an address. */
    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder h = new StringBuilder();
            for (int i = 0; i < 8; i++) h.append(String.format(Locale.ROOT, "%02x", d[i]));
            return h.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
