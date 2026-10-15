package com.keyvoice;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件存储层：手机 ↔ 电脑互传的落盘与列举。
 *
 * <p>分水岭是 API 29（Android 10）的 Scoped Storage：
 * <ul>
 *   <li>API ≥ 29：走 MediaStore.Downloads 写入 {@code Download/KeyVoice/}，无需任何存储权限；</li>
 *   <li>API ≤ 28（含目标机 Android 9）：直接写公共目录 {@code Download/KeyVoice/}，
 *       需要 WRITE_EXTERNAL_STORAGE；没授权或写失败时回退到 App 私有外部目录
 *       （无权限也能收文件，只是位置在 Android/data 下）。</li>
 * </ul>
 * 文件名一律做清洗：去路径分隔与非法字符，防目录穿越。
 */
public final class FileStore {

    /** 接收目录在公共存储里的相对路径（不含存储根） */
    public static final String REL_DIR = "Download/KeyVoice";

    private FileStore() { }

    /** 保存结果 */
    public static final class Saved {
        public final String displayPath;   // 给用户看的路径（Download/KeyVoice/x.zip 或绝对路径）
        public final Uri uri;              // API≥29 的 MediaStore uri；旧路径为 null
        public final File file;            // 旧路径的文件对象；MediaStore 为 null
        public final long size;            // 实际写入字节数
        Saved(String displayPath, Uri uri, File file, long size) {
            this.displayPath = displayPath;
            this.uri = uri;
            this.file = file;
            this.size = size;
        }
    }

    // ------------------------------------------------------------ 文件名工具

    public static String safeName(String name) {
        if (name == null) name = "";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0) sb.append('_');
            else sb.append(c);
        }
        String out = sb.toString().trim();
        // 去掉结尾的点与空格（Windows/部分文件系统不允许）
        while (out.endsWith(".") || out.endsWith(" ")) out = out.substring(0, out.length() - 1);
        if (out.isEmpty()) out = "file";
        return out.length() > 180 ? out.substring(0, 180) : out;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + ext);
    }

    // ------------------------------------------------------------ 写入

    public static long copyBody(InputStream in, OutputStream out, long len, boolean chunked)
            throws IOException {
        byte[] buf = new byte[65536];
        long total = 0;
        if (!chunked) {
            long remain = len;
            while (remain > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, remain));
                if (r < 0) break;
                out.write(buf, 0, r);
                remain -= r;
                total += r;
            }
            return total;
        }
        // HTTP/1.1 chunked：逐块解出
        while (true) {
            String line = readLine(in);
            if (line == null) break;
            int sc = line.indexOf(';');
            if (sc >= 0) line = line.substring(0, sc);
            line = line.trim();
            if (line.isEmpty()) continue;
            long size;
            try {
                size = Long.parseLong(line, 16);
            } catch (Exception e) {
                break;
            }
            if (size == 0) {
                while (true) {                      // 吃掉 trailer
                    String t = readLine(in);
                    if (t == null || t.isEmpty()) break;
                }
                break;
            }
            long remain = size;
            while (remain > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, remain));
                if (r < 0) return total;
                out.write(buf, 0, r);
                remain -= r;
                total += r;
            }
            readLine(in);                            // 块尾 CRLF
        }
        return total;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(16);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return sb.toString();
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 256) break;
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * 把字节流保存为文件。优先公共 Download/KeyVoice；失败自动回退 App 私有目录。
     * @return 保存结果；抛出 IOException 代表彻底失败
     */
    public static Saved saveStream(Context ctx, String rawName,
                                   InputStream in, long len, boolean chunked) throws IOException {
        String name = safeName(rawName);
        Context app = ctx.getApplicationContext();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return saveViaMediaStore(app, name, in, len, chunked);
            } catch (Exception e) {
                // MediaStore 不可用（个别 ROM）→ 私有目录兜底
                return savePrivate(app, name, in, len, chunked, "MediaStore 失败: " + e);
            }
        }
        try {
            return saveLegacy(app, name, in, len, chunked);
        } catch (Exception e) {
            return savePrivate(app, name, in, len, chunked, "公共目录失败: " + e);
        }
    }

    private static Saved saveViaMediaStore(Context app, String name, InputStream in,
                                           long len, boolean chunked) throws IOException {
        ContentResolver cr = app.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, name);
        v.put(MediaStore.Downloads.RELATIVE_PATH, REL_DIR);
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("MediaStore insert 返回 null");
        long written = 0;
        OutputStream os = null;
        try {
            os = cr.openOutputStream(uri);
            if (os == null) throw new IOException("openOutputStream 返回 null");
            written = copyBody(in, os, len, chunked);
            os.flush();
        } finally {
            if (os != null) try { os.close(); } catch (Exception ignored) { }
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(uri, done, null, null);
        return new Saved(REL_DIR + "/" + name, uri, null, written);
    }

    private static Saved saveLegacy(Context app, String name, InputStream in,
                                    long len, boolean chunked) throws IOException {
        File dir = new File(Environment.getExternalStorageDirectory(), REL_DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 " + dir);
        File f = uniqueFile(dir, name);
        long written;
        FileOutputStream fos = new FileOutputStream(f);
        try {
            written = copyBody(in, fos, len, chunked);
            fos.flush();
        } finally {
            try { fos.close(); } catch (Exception ignored) { }
        }
        return new Saved(f.getAbsolutePath(), null, f, written);
    }

    private static Saved savePrivate(Context app, String name, InputStream in,
                                     long len, boolean chunked, String why) throws IOException {
        File dir = new File(app.getExternalFilesDir(null), "KeyVoice");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建 " + dir);
        File f = uniqueFile(dir, name);
        long written;
        FileOutputStream fos = new FileOutputStream(f);
        try {
            written = copyBody(in, fos, len, chunked);
            fos.flush();
        } finally {
            try { fos.close(); } catch (Exception ignored) { }
        }
        return new Saved(f.getAbsolutePath() + "（" + why + "，已改存 App 目录）", null, f, written);
    }

    // ------------------------------------------------------------ 列举

    public static final class Item {
        public final String name;
        public final long size;
        public final Uri uri;       // 可直接给播放器/查看器
        public final File file;
        Item(String name, long size, Uri uri, File file) {
            this.name = name; this.size = size; this.uri = uri; this.file = file;
        }
    }

    /** 最近收到的文件（新→旧）。API≥29 查 MediaStore，旧版列目录。 */
    public static List<Item> listReceived(Context ctx) {
        Context app = ctx.getApplicationContext();
        List<Item> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Cursor c = app.getContentResolver().query(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME,
                                     MediaStore.Downloads.SIZE, MediaStore.Downloads.DATE_ADDED},
                        MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                        new String[]{REL_DIR + "%"},
                        MediaStore.Downloads.DATE_ADDED + " DESC");
                if (c != null) {
                    while (c.moveToNext()) {
                        long id = c.getLong(0);
                        out.add(new Item(c.getString(1), c.getLong(2),
                                ContentUris.withAppendedId(
                                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null));
                    }
                    c.close();
                }
                if (!out.isEmpty()) return out;
            } catch (Exception ignored) { }
        }
        File[] roots = {
                new File(Environment.getExternalStorageDirectory(), REL_DIR),
                new File(app.getExternalFilesDir(null), "KeyVoice"),
        };
        for (File dir : roots) {
            File[] fs = dir.listFiles();
            if (fs == null) continue;
            java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File f : fs) {
                if (f.isFile()) out.add(new Item(f.getName(), f.length(), null, f));
            }
            if (!out.isEmpty()) break;
        }
        return out;
    }

    /** 接收目录的用户可读描述（用于界面展示） */
    public static String dirLabel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 29) return REL_DIR;
        File dir = new File(Environment.getExternalStorageDirectory(), REL_DIR);
        if (dir.exists()) return REL_DIR;
        return "Android/data/com.keyvoice/files/KeyVoice";
    }
}
