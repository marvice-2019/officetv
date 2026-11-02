package com.marvice.officetv;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * One-time phone entry page (spec "Phone QR entry"), served by the launcher on the office LAN: random port,
 * random 128-bit path, a 6-digit code shown only on the TV, closed after one accepted submit, five wrong
 * codes or 5 minutes. What the phone sends goes to the callback and nowhere else: never a file, never the log.
 * Plain HTTP on the office Wi-Fi (the TV has no certificate a phone would trust); the path and code are
 * one-time, so a capture is worthless after the submit.
 */
final class PhoneEntry {
    interface Done { void onSubmit(Map<String, String> values); }
    interface Closed { void onClosed(String why); }

    static final class Field {
        final String key, label, type, hint;
        Field(String key, String label, String type, String hint) {
            this.key = key; this.label = label; this.type = type; this.hint = hint;
        }
    }

    private static final String TAG = "PhoneEntry";
    private static final long LIFETIME_MS = 5 * 60_000L;
    private static final int MAX_TRIES = 5, MAX_HEAD = 8192, MAX_BODY = 8192;

    final String url;
    final String code;
    final long expiresAt;
    private final ServerSocket server;
    private final String path;
    private final String title, note;
    private final Field[] fields;
    private final Done done;
    private final Closed closed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean open = true;
    private int tries;

    private PhoneEntry(ServerSocket server, String host, String title, String note, Field[] fields,
                       Done done, Closed closed) {
        SecureRandom r = new SecureRandom();
        byte[] p = new byte[16];
        r.nextBytes(p);
        StringBuilder hex = new StringBuilder();
        for (byte b : p) hex.append(String.format(Locale.ROOT, "%02x", b));
        this.path = "/" + hex;
        this.code = String.format(Locale.ROOT, "%06d", r.nextInt(1_000_000));
        this.server = server;
        this.url = "http://" + host + ":" + server.getLocalPort() + path;
        this.expiresAt = System.currentTimeMillis() + LIFETIME_MS;
        this.title = title;
        this.note = note;
        this.fields = fields;
        this.done = done;
        this.closed = closed;
    }

    /** Opens the page; null when the TV has no LAN address (Wi-Fi down). */
    static PhoneEntry open(String title, String note, Field[] fields, Done done, Closed closed) {
        String host = lanAddress();
        if (host == null) return null;
        try {
            ServerSocket s = new ServerSocket(0, 4);
            s.setSoTimeout(1000);
            PhoneEntry e = new PhoneEntry(s, host, title, note, fields, done, closed);
            Thread t = new Thread(e::loop, "phone-entry");
            t.setDaemon(true);
            t.start();
            return e;
        } catch (IOException ex) {
            Log.w(TAG, "could not open the entry page: " + ex.getClass().getSimpleName());
            return null;
        }
    }

    void close() {
        open = false;
        try { server.close(); } catch (IOException ignored) { }
    }

    private void loop() {
        String why = "expired";
        while (open && System.currentTimeMillis() < expiresAt) {
            try (Socket s = server.accept()) {
                s.setSoTimeout(10_000);
                String result = handle(s);
                if (result != null) { why = result; break; }
            } catch (SocketTimeoutException ignored) {
                // poll the deadline
            } catch (IOException e) {
                if (!open) { why = "closed"; break; }
            }
        }
        close();
        final String reason = why;
        main.post(() -> { if (closed != null) closed.onClosed(reason); });
    }

    /** Serves one request. Returns non-null when the page must close ("submitted" or "too many tries"). */
    private String handle(Socket s) throws IOException {
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        String head = readHead(in);
        if (head == null) return null;
        String[] lines = head.split("\r\n");
        String[] req = lines[0].split(" ");
        if (req.length < 2 || !req[1].equals(path)) {
            reply(out, 404, "<p>Not found.</p>");
            return null;
        }
        if (req[0].equals("GET")) {
            reply(out, 200, form(null));
            return null;
        }
        if (!req[0].equals("POST")) {
            reply(out, 405, "<p>Not allowed.</p>");
            return null;
        }
        int len = 0;
        for (String l : lines) {
            if (l.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                try { len = Integer.parseInt(l.substring(15).trim()); } catch (NumberFormatException ignored) { }
            }
        }
        if (len <= 0 || len > MAX_BODY) {
            reply(out, 413, "<p>Too long.</p>");
            return null;
        }
        byte[] body = new byte[len];
        int got = 0;
        while (got < len) {
            int n = in.read(body, got, len - got);
            if (n < 0) break;
            got += n;
        }
        Map<String, String> form = parse(new String(body, 0, got, StandardCharsets.UTF_8));
        String sent = form.remove("_code");
        if (sent == null || !MessageDigest.isEqual(sent.trim().getBytes(StandardCharsets.UTF_8),
                code.getBytes(StandardCharsets.UTF_8))) {
            tries++;
            if (tries >= MAX_TRIES) {
                reply(out, 403, page("<p>Too many wrong codes. This page is closed; start again on the TV.</p>"));
                return "too many tries";
            }
            reply(out, 200, form("That code doesn't match the TV. " + (MAX_TRIES - tries) + " tries left."));
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (Field f : fields) {
            String v = form.get(f.key);
            values.put(f.key, v == null ? "" : v.trim());
        }
        reply(out, 200, page("<p><b>Sent to the TV.</b> You can close this page. It no longer works.</p>"));
        open = false;
        main.post(() -> done.onSubmit(Collections.unmodifiableMap(values)));
        return "submitted";
    }

    private String form(String error) {
        StringBuilder b = new StringBuilder();
        b.append("<h1>").append(esc(title)).append("</h1>");
        if (note != null) b.append("<p class=n>").append(esc(note)).append("</p>");
        if (error != null) b.append("<p class=e>").append(esc(error)).append("</p>");
        b.append("<form method=post action=\"").append(path).append("\" autocomplete=off>");
        b.append("<label>Code shown on the TV<input name=_code inputmode=numeric pattern=\"[0-9]{6}\" maxlength=6 required></label>");
        for (Field f : fields) {
            b.append("<label>").append(esc(f.label)).append("<input name=\"").append(esc(f.key))
                    .append("\" type=\"").append(esc(f.type)).append("\"");
            if (f.hint != null) b.append(" placeholder=\"").append(esc(f.hint)).append("\"");
            b.append(" autocapitalize=off spellcheck=false required></label>");
        }
        b.append("<button>Send to the TV</button></form>");
        b.append("<p class=n>One use only. Closes after sending, or in 5 minutes.</p>");
        return page(b.toString());
    }

    private static String page(String inner) {
        return "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<meta name=referrer content=no-referrer><title>Office TV</title><style>"
                + "body{font:16px system-ui,sans-serif;background:#0B0D0C;color:#F2F1EC;margin:0;padding:24px 16px;max-width:480px}"
                + "h1{font-size:22px;font-weight:600}label{display:block;margin:16px 0;color:#9AA39E}"
                + "input{display:block;width:100%;box-sizing:border-box;margin-top:6px;padding:12px;font-size:17px;"
                + "border-radius:10px;border:1px solid #2a302d;background:#151917;color:#F2F1EC}"
                + "button{width:100%;padding:14px;font-size:17px;border:0;border-radius:10px;background:#B08D57;color:#0B0D0C;font-weight:600}"
                + ".n{color:#9AA39E;font-size:14px}.e{color:#E05A4F}</style></head><body>" + inner + "</body></html>";
    }

    private static void reply(OutputStream out, int status, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        String reason = status == 200 ? "OK" : status == 404 ? "Not Found" : status == 403 ? "Forbidden"
                : status == 405 ? "Method Not Allowed" : "Payload Too Large";
        String h = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\nX-Frame-Options: DENY\r\nReferrer-Policy: no-referrer\r\n"
                + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) >= 0) {
            buf.write(c);
            if (buf.size() > MAX_HEAD) return null;
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1
                    : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return buf.toString("ISO-8859-1");
        }
        return null;
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            try {
                m.put(URLDecoder.decode(pair.substring(0, i), "UTF-8"), URLDecoder.decode(pair.substring(i + 1), "UTF-8"));
            } catch (Exception ignored) { }
        }
        return m;
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** The TV's address on the office Wi-Fi or Ethernet (private range), never the Tailscale one. */
    static String lanAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
