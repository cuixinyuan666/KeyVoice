package com.keyvoice;

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
        void onMessage(String text);
    }

    public static final int PORT = 17892;
    private static final String TAG = "KeyVoiceMsg";
    private static final byte[] CRLFCRLF = {'\r', '\n', '\r', '\n'};

    private static volatile Listener listener;
    private static volatile boolean started = false;

    private LanServer() { }

    /** 启动服务器（幂等：重复调用只更新监听器，不会重复绑定端口）。
     *  Activity 重建时再次调用即可把新页面的输入框接进来。 */
    public static void start(Listener l) {
        listener = l;
        if (started) return;
        started = true;
        Thread t = new Thread(LanServer::run, "KeyVoiceLanServer");
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
                String text = extractText(body);
                Log.i(TAG, "extracted text=" + text);
                if (!text.isEmpty()) {
                    Listener l = listener;
                    if (l != null) l.onMessage(text);
                }
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

    private static String extractText(String body) {
        try {
            return new org.json.JSONObject(body).optString("text", "");
        } catch (Exception e) {
            Log.w(TAG, "json parse failed: " + e);
            return "";
        }
    }
}
