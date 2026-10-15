package com.keyvoice;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 极简 HTTP 服务器：接收电脑端发来的文字（PC → 手机）。
 * <p>
 * 协议与 PC 端一致：POST /text，body 为 JSON {"text":"..."}；另提供 GET /ping 供探测。
 * 端口 17892。电脑端两种方式可达：
 *   1. 局域网直连：http://&lt;手机IP&gt;:17892/text
 *   2. adb 模式：电脑端先执行 {@code adb forward tcp:17892 tcp:17892}，再发 http://127.0.0.1:17892/text
 * <p>
 * 手机收到后的处理逻辑仿照 PC 端「收到消息」：插入输入框光标处（等同上屏）+ 存剪贴板 + 提示。
 * 实现注意：HTTP 头与 body 全部按【字节】读取（Content-Length 是字节数），
 * 用字符流读中文会因「字节数≠字符数」错位/阻塞——这是首版收不到消息的根因。
 * 只用标准 java.net，不引第三方库。
 */
public final class LanServer {

    public interface Listener {
        /** @return true=已投递到前台主页面；false=页面不在前台已暂存 */
        boolean onMessage(String text, String from);
    }

    public static final int PORT = 17892;
    /** 设备发现端口：电脑广播 KV_DISCOVER，本机应答 KV_PHONE_HERE:<型号> */
    public static final int DISCOVER_PORT = 17893;
    private static final String TAG = "KeyVoiceMsg";
    private static final byte[] CRLFCRLF = {'\r', '\n', '\r', '\n'};

    private static volatile Listener listener;
    private static volatile boolean started = false;
    /** 本机对外广播的设备名（用户可在设置里改） */
    private static volatile String deviceName = Config.defaultDeviceName();
    /** 发现应答线程是否已起来，避免 Activity 重建时重复绑定 17893 */
    private static volatile boolean discoveryStarted = false;

    private LanServer() { }

    /** 启动服务器（幂等：重复调用只更新监听器，不会重复绑定端口）。
     *  Activity 重建时再次调用即可把新页面的输入框接进来。 */
    public static void start(Context ctx, Listener l) {
        listener = l;
        // 每次启动都重读一次：用户在设置里改过名字后回到主页面即可生效
        if (ctx != null) deviceName = new Config(ctx.getApplicationContext()).effDeviceName();
        if (started) return;
        started = true;
        Thread t = new Thread(LanServer::run, "KeyVoiceLanServer");
        t.setDaemon(true);
        t.start();
        startDiscovery();
    }

    /** 设置里改了设备名后调用：立即生效，无需重启 App */
    public static void setDeviceName(String name) {
        if (name == null) return;
        name = name.trim();
        if (!name.isEmpty()) deviceName = name;
    }

    /** 当前对外广播的设备名 */
    public static String getDeviceName() { return deviceName; }

    /**
     * 应答局域网里的发现广播（KV_DISCOVER，端口 17893）。
     * <p>
     * 应答里带上【端口 + 用户设置的设备名】，这样电脑端和其它手机能直接列出
     * 「DESKTOP-ABC」「Pixel 7」这样的名字，而不是一串 IP。
     */
    private static void startDiscovery() {
        if (discoveryStarted) return;
        discoveryStarted = true;
        Thread t = new Thread(() -> {
            try {
                java.net.DatagramSocket ds = new java.net.DatagramSocket(DISCOVER_PORT);
                ds.setBroadcast(true);
                byte[] buf = new byte[128];
                java.net.DatagramPacket p = new java.net.DatagramPacket(buf, buf.length);
                while (true) {
                    ds.receive(p);
                    String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (s.startsWith("KV_DISCOVER")) {
                        byte[] out = ("KV_PHONE_HERE:" + PORT + ":" + deviceName)
                                .getBytes(StandardCharsets.UTF_8);
                        ds.send(new java.net.DatagramPacket(out, out.length,
                                p.getAddress(), p.getPort()));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "discovery responder stopped: " + e);
            }
        }, "KeyVoiceDiscover");
        t.setDaemon(true);
        t.start();
    }

    private static void run() {
        try {
            ServerSocket ss = new ServerSocket(PORT);
            Log.i(TAG, "listening on port " + PORT);
            while (true) {
                final Socket s = ss.accept();
                handle(s);
            }
        } catch (Exception e) {
            Log.w(TAG, "server stopped: " + e);
            started = false;
        }
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(6000);
            InputStream is = s.getInputStream();

            // 1) 按【字节】读到 \r\n\r\n，拿完整请求头（不能用字符流，会提前吞掉 body）
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int match = 0, b;
            while ((b = is.read()) != -1) {
                head.write(b);
                if (b == CRLFCRLF[match]) {
                    if (++match == 4) break;
                } else {
                    match = (b == '\r') ? 1 : 0;
                }
            }
            String headerBlock = head.toString("UTF-8");
            String requestLine = headerBlock.isEmpty() ? "" :
                    headerBlock.split("\r\n", 2)[0];
            boolean isPost = requestLine.startsWith("POST");
            boolean isPing = requestLine.contains("/ping");
            int len = parseContentLength(headerBlock);
            Log.i(TAG, "req=\"" + requestLine + "\" isPost=" + isPost + " contentLength=" + len);

            // 2) 按【字节】精确读 body（Content-Length 是字节数，中文每字 3 字节）
            String body = "";
            if (isPost && len > 0) {
                byte[] buf = new byte[len];
                int read = 0;
                while (read < len) {
                    int r = is.read(buf, read, len - read);
                    if (r < 0) break;
                    read += r;
                }
                body = new String(buf, 0, read, StandardCharsets.UTF_8);
            }
            Log.i(TAG, "body=" + body);

            String resp = "{\"ok\":true}";
            if (isPost) {
                String text = extractField(body, "text");
                String from = extractField(body, "from");
                Log.i(TAG, "extracted text=" + text + " from=" + from);
                boolean delivered = false;
                if (!text.isEmpty()) {
                    Listener l = listener;
                    if (l != null) delivered = l.onMessage(text, from);
                }
                // 投递结果直接写进响应：ROM 吞应用日志时，电脑端也能知道送达情况
                resp = "{\"ok\":true,\"delivered\":" + delivered + "}";
            } else if (isPing) {
                resp = "{\"ok\":true,\"service\":\"keyvoice-phone\"}";
            }

            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            OutputStream os = s.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + out.length + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.write(out);
            os.flush();
            s.close();
        } catch (Exception e) {
            Log.w(TAG, "handle failed: " + e);
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** 从请求头里取 Content-Length（大小写不敏感），解析失败返回 0 */
    private static int parseContentLength(String headerBlock) {
        for (String line : headerBlock.split("\r\n")) {
            int i = line.indexOf(':');
            if (i <= 0) continue;
            if (line.substring(0, i).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    return Integer.parseInt(line.substring(i + 1).trim());
                } catch (Exception ignored) { }
            }
        }
        return 0;
    }

    private static String extractField(String body, String field) {
        try {
            return new org.json.JSONObject(body).optString(field, "");
        } catch (Exception e) {
            Log.w(TAG, "json parse failed: " + e);
            return "";
        }
    }
}
