package com.keyvoice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 把文字通过局域网 HTTP POST 发给 Windows 接收端 */
public class Sender {

    public interface Callback {
        void onResult(boolean ok, String msg);
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static void send(final Context ctx, final String text, final Callback cb) {
        final Config cfg = new Config(ctx);
        // adb 模式：发到手机自己的 127.0.0.1，由 adb reverse 隧道转给电脑（USB 线，不用局域网）
        final String host = cfg.adbMode() ? "127.0.0.1" : cfg.host();
        final int port = cfg.port();
        final String action = cfg.action();

        POOL.execute(() -> {
            boolean ok = false;
            String msg;
            HttpURLConnection conn = null;
            try {
                if (host == null || host.isEmpty()) throw new Exception("未设置电脑 IP（局域网模式）");
                URL url = new URL("http://" + host + ":" + port + "/text");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                conn.setDoOutput(true);

                String body = "{\"text\":" + json(text)
                        + ",\"action\":\"" + action + "\""
                        + ",\"from\":" + json(cfg.deviceName())
                        + ",\"ts\":" + System.currentTimeMillis() + "}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(bytes.length);
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                ok = code >= 200 && code < 300;
                msg = "HTTP " + code;
            } catch (Exception e) {
                msg = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }

            final boolean fOk = ok;
            final String fMsg = msg;
            MAIN.post(() -> {
                if (cb != null) cb.onResult(fOk, fMsg);
            });
        });
    }

    /** 探测电脑接收端是否在线（GET /ping） */
    public static void ping(final Context ctx, final Callback cb) {
        final Config cfg = new Config(ctx);
        final String host = cfg.adbMode() ? "127.0.0.1" : cfg.host();
        final int port = cfg.port();
        POOL.execute(() -> {
            boolean ok = false;
            String msg = "";
            HttpURLConnection conn = null;
            try {
                if (host == null || host.isEmpty()) throw new Exception("未设置电脑 IP");
                URL url = new URL("http://" + host + ":" + port + "/ping");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(2500);
                conn.setReadTimeout(2500);
                int code = conn.getResponseCode();
                ok = code >= 200 && code < 300;
                msg = "HTTP " + code;
            } catch (Exception e) {
                msg = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            MAIN.post(() -> { if (cb != null) cb.onResult(fOk, fMsg); });
        });
    }

    private static String json(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
