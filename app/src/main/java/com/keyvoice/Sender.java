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

    /**
     * 候选目标地址，按优先级排列。
     * <p>
     * adb 模式原本只发 127.0.0.1（靠 adb reverse 隧道）。但 USB 一拔、或 adb reverse
     * 没建起来，隧道就没了，而手机往往还连着同一个 WiFi —— 这时死磕 127.0.0.1
     * 只会得到「连接被拒绝」，用户看到的就是「发送失败」。
     * 所以 adb 模式失败后自动回落到局域网 IP 再试一次，反之亦然。
     */
    private static String[] hosts(Config cfg) {
        String lan = cfg.host() == null ? "" : cfg.host().trim();
        String adb = "127.0.0.1";
        if (cfg.adbMode()) return lan.isEmpty() ? new String[]{adb} : new String[]{adb, lan};
        return lan.isEmpty() ? new String[]{adb} : new String[]{lan, adb};
    }

    public static void send(final Context ctx, final String text, final Callback cb) {
        final Config cfg = new Config(ctx);
        final int port = cfg.port();
        final String action = cfg.action();
        // 带上本机设备名：电脑端的消息列表就能显示「来自 张三的手机」而不是一串 IP
        final String from = cfg.effDeviceName();
        final String[] candidates = hosts(cfg);

        POOL.execute(() -> {
            boolean ok = false;
            String msg = "";
            StringBuilder tried = new StringBuilder();
            for (String host : candidates) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("http://" + host + ":" + port + "/text");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2500);
                    conn.setDoOutput(true);

                    String body = "{\"text\":" + json(text)
                            + ",\"action\":\"" + action + "\""
                            + ",\"from\":" + json(from)
                            + ",\"ts\":" + System.currentTimeMillis() + "}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(bytes.length);
                    OutputStream os = conn.getOutputStream();
                    os.write(bytes);
                    os.flush();
                    os.close();

                    int code = conn.getResponseCode();
                    ok = code >= 200 && code < 300;
                    msg = ok ? ("HTTP " + code) : ("HTTP " + code + "（" + host + "）");
                    if (ok) {
                        if (!host.equals(candidates[0])) msg = "已改用 " + host + " 发送（" + msg + "）";
                        break;
                    }
                } catch (Exception e) {
                    String em = e.getMessage() == null ? e.toString() : e.getMessage();
                    msg = em;
                    tried.append(tried.length() == 0 ? "" : " / ").append(host).append(": ").append(em);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            if (!ok && tried.length() > 0) msg = tried.toString();
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
        final int port = cfg.port();
        final String[] candidates = hosts(cfg);
        POOL.execute(() -> {
            boolean ok = false;
            String msg = "";
            StringBuilder tried = new StringBuilder();
            for (String host : candidates) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("http://" + host + ":" + port + "/ping");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setConnectTimeout(2000);
                    conn.setReadTimeout(2000);
                    int code = conn.getResponseCode();
                    ok = code >= 200 && code < 300;
                    msg = "HTTP " + code;
                    if (ok) break;
                } catch (Exception e) {
                    String em = e.getMessage() == null ? e.toString() : e.getMessage();
                    msg = em;
                    tried.append(tried.length() == 0 ? "" : " / ").append(host).append(": ").append(em);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            if (!ok && tried.length() > 0) msg = tried.toString();
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
