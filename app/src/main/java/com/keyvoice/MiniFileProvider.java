package com.keyvoice;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 ContentProvider：给「打开收到的文件」提供 content:// uri。
 *
 * <p>为什么不直接用 androidx.core 的 FileProvider：本项目不使用 AndroidX
 * （app/build.gradle 里 android.useAndroidX=false），而 file:// uri 在
 * targetSdk ≥ 24 下会触发 FileUriExposedException。这里按 FileProvider 的
 * 最小实现自己写一份：
 * <ul>
 *   <li>uri 形如 {@code content://com.keyvoice.files/f/<urlencoded 绝对路径>}；</li>
 *   <li>只允许访问「公共 Download/KeyVoice」「本 App 外部私有目录」「App files 目录」
 *       三类路径（防止被其它应用借道读任意文件）；</li>
 *   <li>配合 Intent.FLAG_GRANT_READ_URI_PERMISSION 把读权限临时授予目标应用
 *       （provider 本身 exported=false，不需要对外开放）。</li>
 * </ul>
 */
public class MiniFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.keyvoice.files";

    /** 把一个本地文件包装成可外发的 content:// uri */
    public static Uri getUriForFile(Context ctx, File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath("f")
                .appendPath(Uri.encode(f.getAbsolutePath()))
                .build();
    }

    private File resolve(Uri uri) {
        String enc = uri.getLastPathSegment();
        if (enc == null) return null;
        File f = new File(Uri.decode(enc));
        String abs = f.getAbsolutePath();
        if (!allowed(abs)) return null;
        return f;
    }

    private boolean allowed(String abs) {
        try {
            String pub = new File(Environment.getExternalStorageDirectory(),
                    FileStore.REL_DIR).getAbsolutePath();
            if (abs.startsWith(pub + File.separator)) return true;
            File ext = getContext().getExternalFilesDir(null);
            if (ext != null && abs.startsWith(ext.getAbsolutePath() + File.separator)) return true;
            File files = getContext().getFilesDir();
            if (files != null && abs.startsWith(files.getAbsolutePath() + File.separator)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.exists()) throw new FileNotFoundException("不允许或不存在: " + uri);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        File f = resolve(uri);
        if (f == null) return "application/octet-stream";
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) {
            String mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(n.substring(dot + 1).toLowerCase());
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null) return null;
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("只读 provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读 provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("只读 provider");
    }
}
