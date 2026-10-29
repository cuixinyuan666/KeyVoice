package com.keyvoice;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 把文字通过局域网 HTTP POST 发给 Windows 接收端 */
public class Sender {

    public interface Callback {
        void onResult(boolean ok, String msg);
    }

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 一个群发目标：接收端 IP + 端口 */
    private static final class Target {
        final String host;
        final int port;
        Target(String host, int port) { this.host = host; this.port = port; }
    }

    /**
     * 最近一次局域网扫描发现的所有 PC 接收端（按 IP 去重）。
     * <p>
     * 由 UI 层（MainActivity 等）在 {@link Discover#scan} 的 onDone 里调用
     * {@link #rememberPcs} 写入。手机发消息时用它把文字群发到【所有】电脑，
     * 而不只是 cfg.host 里那单独一台 —— 这就是「所有电脑都显示同一份消息」的关键。
     */
    private static final Map<String, Discover.Device> KNOWN_PCS = new LinkedHashMap<>();

    /** 记录最近一次扫描发现的 PC 接收端列表（后台线程安全）。 */
    public static synchronized void rememberPcs(List<Discover.Device> all) {
        if (all == null) return;
        KNOWN_PCS.clear();
        for (Discover.Device d : all) {
            if (d != null && d.isPc() && !d.ip.isEmpty() && !"127.0.0.1".equals(d.ip)) {
                KNOWN_PCS.put(d.ip, d);
            }
        }
    }

    /**
     * 群发目标（去重）：adb 隧道(127.0.0.1) + 用户配置的 host + 最近扫描到的所有 PC。
     * <p>
     * 目标是「所有电脑都收到」：只要手机和它们在同一个局域网、且都开过接收端，
     * 消息就会同时到达每一台，而不再只投递到单一一台。
     */
    private static List<Target> resolveTargets(Config cfg) {
        LinkedHashMap<String, Target> set = new LinkedHashMap<>();
        int port = cfg.port();
        // adb 隧道始终作为候选之一（USB 连着时 reverse 到那台电脑的 17890）
        set.put("127.0.0.1", new Target("127.0.0.1", port));
        // 用户手动配置的单一 host
        String lan = cfg.host() == null ? "" : cfg.host().trim();
        if (!lan.isEmpty()) set.put(lan, new Target(lan, port));
        // 最近扫描发现的所有 PC 接收端（各自带自己的端口）
        synchronized (KNOWN_PCS) {
            for (Discover.Device d : KNOWN_PCS.values()) {
                if (!set.containsKey(d.ip)) set.put(d.ip, new Target(d.ip, d.port));
            }
        }
        return new ArrayList<>(set.values());
    }

    /**
     * 探测用候选目标，按优先级排列（供 ping / 文件发送等仍用单目标逻辑的路径）。
     * <p>
     * adb 模式原本只发 127.0.0.1（靠 adb reverse 隧道）。但 USB 一拔、或 adb reverse
     * 没建起来，隧道就没了，而手机往往还连着同一个 WiFi —— 这时死磕 127.0.0.1
     * 只会得到「连接被拒绝」，用户看到的就是「发送失败」。
     * 所以 adb 模式失败后自动回落到局域网 IP 再试一次，反之亦然。
     */
    /** 供文件发送等复用：当前应投递的所有接收端 (host, port)，与文字群发同一份名单。 */
    public static List<String[]> pcTargets(Context ctx) {
        Config cfg = new Config(ctx);
        List<Target> ts = resolveTargets(cfg);
        List<String[]> out = new ArrayList<>();
        for (Target t : ts) out.add(new String[]{t.host, String.valueOf(t.port)});
        return out;
    }

    private static String[] hosts(Config cfg) {
        String lan = cfg.host() == null ? "" : cfg.host().trim();
        String adb = "127.0.0.1";
        if (cfg.adbMode()) return lan.isEmpty() ? new String[]{adb} : new String[]{adb, lan};
        return lan.isEmpty() ? new String[]{adb} : new String[]{lan, adb};
    }

    public static void send(final Context ctx, final String text, final Callback cb) {
        final Config cfg = new Config(ctx);
        final String action = cfg.action();
        // 带上本机设备名：电脑端的消息列表就能显示「来自 张三的手机」而不是一串 IP
        final String from = cfg.effDeviceName();
        final List<Target> targets = resolveTargets(cfg);

        POOL.execute(() -> {
            int okCount = 0;
            final int total = targets.size();
            StringBuilder tried = new StringBuilder();
            for (Target t : targets) {
                HttpURLConnection conn = null;
                try {
                    URL url = new URL("http://" + t.host + ":" + t.port + "/text");
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
                    if (code >= 200 && code < 300) {
                        okCount++;
                    } else {
                        tried.append(tried.length() == 0 ? "" : " / ")
                                .append(t.host).append(": HTTP ").append(code);
                    }
                } catch (Exception e) {
                    String em = e.getMessage() == null ? e.toString() : e.getMessage();
                    tried.append(tried.length() == 0 ? "" : " / ")
                            .append(t.host).append(": ").append(em);
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            final boolean fOk = okCount > 0;
            final String fMsg;
            if (okCount == total) {
                fMsg = "已同步到 " + okCount + " 台电脑";
            } else if (okCount > 0) {
                fMsg = "已同步 " + okCount + "/" + total + " 台电脑；失败：" + tried;
            } else {
                fMsg = "发送失败：" + tried;
            }
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
