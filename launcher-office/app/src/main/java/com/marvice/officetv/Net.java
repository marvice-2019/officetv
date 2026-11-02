package com.marvice.officetv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Minimal HTTPS for the office connections. Callers never log a request or response body: bodies carry
 * tokens. Only the HTTP status reaches the log.
 */
final class Net {
    static final class Resp {
        final int code;
        final String body;
        Resp(int code, String body) { this.code = code; this.body = body; }
    }

    private static final int MAX_BODY = 2 * 1024 * 1024;

    private Net() {}

    static Resp postForm(String url, Map<String, String> form) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8")).append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        HttpURLConnection c = open(url);
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            byte[] b = sb.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream o = c.getOutputStream()) { o.write(b); }
            return finish(c);
        } finally {
            c.disconnect();
        }
    }

    static Resp get(String url, String bearer) throws IOException {
        HttpURLConnection c = open(url);
        try {
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            c.setRequestProperty("Accept", "application/json");
            // Graph returns times in this zone, so the TV never converts.
            c.setRequestProperty("Prefer", "outlook.timezone=\"UTC\"");
            return finish(c);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setUseCaches(false);
        return c;
    }

    private static Resp finish(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        return new Resp(code, in == null ? "" : read(in));
    }

    static String read(InputStream in) throws IOException {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) throw new IOException("response too large");
            }
            return out.toString("UTF-8");
        }
    }
}
