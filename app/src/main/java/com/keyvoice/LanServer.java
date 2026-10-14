package com.keyvoice;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
 * 只用标准 java.net，不引第三方库。
 */
public final class LanServer {

    public interface Listener {
        void onMessage(String text);
    }

    public static final int PORT = 17892;
    private static final String TAG = "KeyVoiceMsg";

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
            s.setSoTimeout(4000);
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            boolean isPost = line != null && line.startsWith("POST");
            boolean isPing = line != null && line.contains("/ping");
            int contentLength = 0;
            while ((line = in.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    } catch (Exception ignored) { }
                }
            }
            String body = "";
            if (isPost && contentLength > 0) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = in.read(buf, read, contentLength - read);
                    if (r < 0) break;
                    read += r;
                }
                body = new String(buf, 0, read);
            }

            String resp = "{\"ok\":true}";
            if (isPost) {
                String text = extractText(body);
                if (!text.isEmpty()) {
                    Log.i(TAG, "received from pc: " + text);
                    Listener l = listener;
                    if (l != null) l.onMessage(text);
                }
            } else if (isPing) {
                resp = "{\"ok\":true,\"service\":\"keyvoice-phone\"}";
            }
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            OutputStream os = s.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + out.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(out);
            os.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static String extractText(String body) {
        try {
            return new org.json.JSONObject(body).optString("text", "");
        } catch (Exception e) {
            return "";
        }
    }
}
