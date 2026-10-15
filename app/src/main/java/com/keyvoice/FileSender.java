package com.keyvoice;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;

/**
 * 手机 → 电脑：把本地文件（任意类型）流式上传到 PC 端 /file 端点。
 *
 * <p>通道与文字发送完全一致（复用「传输方式」设置）：
 * <ul>
 *   <li>adb 模式：POST http://127.0.0.1:17890/file（需 adb reverse tcp:17890 tcp:17890）；</li>
 *   <li>局域网模式：POST http://&lt;电脑IP&gt;:17890/file。</li>
 * </ul>
 * 文件名/来源走 query，body 是文件原始字节；已知大小用固定长度（更快），
 * 未知大小（个别 ContentProvider 不给 SIZE）自动退化为 chunked。
 */
public final class FileSender {

    public interface Progress {
        /** 在后台线程回调；index 从 0 开始 */
        void onProgress(int index, int total, String name, long sent, long size);
    }

    public interface Done {
        void onDone(boolean ok, String msg);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private FileSender() { }

    public static void send(final Context ctx, final List<Uri> uris,
                            final Progress pg, final Done done) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Config cfg = new Config(app);
            String host = cfg.adbMode() ? "127.0.0.1" : cfg.host();
            int port = cfg.port();
            if (host == null || host.isEmpty()) {
                MAIN.post(() -> done.onDone(false, "未设置电脑地址：请先在设置里填写或搜索设备"));
                return;
            }
            int okN = 0;
            String lastErr = "";
            for (int i = 0; i < uris.size(); i++) {
                Uri u = uris.get(i);
                String name = queryName(app, u);
                long size = querySize(app, u);
                try {
                    uploadOne(app, cfg, u, name, size, host, port, i, uris.size(), pg);
                    okN++;
                } catch (Exception e) {
                    lastErr = name + "：" + (e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
            final int fOk = okN;
            final String fErr = lastErr;
            final int total = uris.size();
            MAIN.post(() -> {
                if (fOk == total) done.onDone(true, "已发送 " + fOk + " 个文件到电脑");
                else if (fOk > 0) done.onDone(true, "成功 " + fOk + "/" + total + "，失败：" + fErr);
                else done.onDone(false, "发送失败：" + fErr);
            });
        }, "KeyVoiceFileSender").start();
    }

    private static void uploadOne(Context app, Config cfg, Uri uri, String name, long size,
                                  String host, int port, int idx, int total, Progress pg)
            throws Exception {
        HttpURLConnection conn = null;
        InputStream in = null;
        try {
            String url = "http://" + host + ":" + port + "/file?name="
                    + URLEncoder.encode(name, "UTF-8")
                    + "&from=" + URLEncoder.encode(cfg.effDeviceName(), "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            if (size > 0) conn.setFixedLengthStreamingMode(size);
            else conn.setChunkedStreamingMode(65536);

            in = app.getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("无法读取所选文件");
            OutputStream os = conn.getOutputStream();
            byte[] buf = new byte[65536];
            long sent = 0;
            int r;
            while ((r = in.read(buf)) > 0) {
                os.write(buf, 0, r);
                sent += r;
                if (pg != null) {
                    try { pg.onProgress(idx, total, name, sent, size); }
                    catch (Exception ignored) { }
                }
            }
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) { }
            if (conn != null) conn.disconnect();
        }
    }

    public static String queryName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null && !n.trim().isEmpty()) return FileStore.safeName(n);
            }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment();
        return FileStore.safeName(last == null ? "file" : last);
    }

    public static long querySize(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return cur.getLong(0);
        } catch (Exception ignored) { }
        return -1;
    }
}
