package com.keyvoice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 文件互传页：手机 ↔ 电脑，任意类型文件。
 *
 * <p>发送：ACTION_OPEN_DOCUMENT 多选（type=*&#47;*）→ FileSender 流式上传到 PC 端 /file；
 * 接收：电脑端点「发文件」→ 本机 LanServer 收下并落盘 Download/KeyVoice（见 FileStore），
 * 本页列出最近收到的文件，点击即可用系统应用打开（经 MiniFileProvider 出 content://）。
 */
public class FileActivity extends Activity {

    private static final int REQ_PICK = 301;
    private static final int REQ_WRITE = 302;

    private Config cfg;
    private TextView tvTarget, tvStatus;
    private ProgressBar bar;
    private LinearLayout filesCard;
    private boolean sending = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new Config(this);
        setTheme(Ui.themeRes("light".equalsIgnoreCase(cfg.theme())));
        Ui.setTheme(cfg.theme());
        setRequestedOrientation(cfg.invertMode()
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = Ui.rootScroll(this);
        sv.addView(root);
        setContentView(sv);

        root.addView(Ui.appBar(this, "文件互传"));

        // ---- 发送卡片 ----
        LinearLayout c1 = Ui.sectionCard(this);
        c1.addView(Ui.sectionTitle(this, "发送文件到电脑"));
        tvTarget = Ui.body(this, "");
        tvTarget.setTextColor(Ui.text2(this));
        c1.addView(tvTarget);
        Button bPick = Ui.primaryButton(this, "选择文件发送（可多选）");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        bPick.setLayoutParams(blp);
        bPick.setOnClickListener(v -> pickFiles());
        c1.addView(bPick);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Ui.dp(this, 8);
        bar.setLayoutParams(plp);
        c1.addView(bar);
        tvStatus = Ui.caption(this, "");
        c1.addView(tvStatus);
        c1.addView(Ui.caption(this, "电脑端需运行 KeyVoice 接收端；adb 模式请先执行 adb reverse tcp:17890 tcp:17890。"));
        root.addView(c1);

        // ---- 接收卡片 ----
        LinearLayout c2 = Ui.sectionCard(this);
        c2.addView(Ui.sectionTitle(this, "接收文件（电脑 → 手机）"));
        c2.addView(Ui.body(this, "在电脑端 KeyVoice 窗口点「发文件」并选择文件，即会发送到本机。\n保存位置：" + FileStore.dirLabel(this)));
        Button bRefresh = Ui.ghostButton(this, "刷新已收文件列表");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(this, 8);
        bRefresh.setLayoutParams(rlp);
        bRefresh.setOnClickListener(v -> renderFiles());
        c2.addView(bRefresh);
        root.addView(c2);

        // ---- 已收文件列表 ----
        filesCard = Ui.sectionCard(this);
        filesCard.addView(Ui.sectionTitle(this, "最近的已收文件"));
        root.addView(filesCard);
        renderFiles();

        // Android 9 等旧系统：写公共 Download 需要存储权限；无权限时接收会自动退回 App 目录
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE);
        }

        refreshTarget();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 主页面/电脑可能刚发来文件：回来时刷新列表
        renderFiles();
        refreshTarget();
    }

    private void refreshTarget() {
        String host = cfg.adbMode() ? "127.0.0.1（adb）" : cfg.host();
        if (host == null || host.isEmpty()) host = "未设置（请先在设置里填写或搜索设备）";
        if (tvTarget != null) tvTarget.setText("当前目标：" + host + "：" + cfg.port());
    }

    // ------------------------------------------------------------ 发送

    private void pickFiles() {
        if (sending) return;
        if (cfg.host() == null || cfg.host().isEmpty()) {
            if (!cfg.adbMode()) {
                new AlertDialog.Builder(this)
                        .setTitle("还没设置电脑地址")
                        .setMessage("请先在设置页填写电脑 IP 或点「搜索设备」自动发现；\n"
                                + "如果是 USB（adb）模式则无需地址。")
                        .setPositiveButton("去设置", (d, w) ->
                                startActivity(new Intent(this, SettingsActivity.class)))
                        .setNegativeButton("取消", null)
                        .show();
                return;
            }
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) {
            for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        startSend(uris);
    }

    private void startSend(List<Uri> uris) {
        sending = true;
        bar.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        tvStatus.setText("准备发送 " + uris.size() + " 个文件…");
        FileSender.send(this, uris, (index, total, name, sent, size) -> {
            final int pct = size > 0 ? (int) (sent * 100 / size) : 0;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                bar.setProgress(pct);
                tvStatus.setText(String.format("(%d/%d) %s  %s", index + 1, total, name,
                        size > 0 ? (pct + "%") : (sent / 1024 + " KB")));
            });
        }, (ok, msg) -> {
            sending = false;
            if (isFinishing()) return;
            bar.setVisibility(View.GONE);
            tvStatus.setText(msg);
            tvStatus.setTextColor(ok ? Ui.success(this) : Ui.error(this));
            renderFiles();
        });
    }

    // ------------------------------------------------------------ 已收列表

    private void renderFiles() {
        if (filesCard == null) return;
        // 清掉旧行（保留标题，即第 0 个子 View）
        while (filesCard.getChildCount() > 1) {
            filesCard.removeViewAt(filesCard.getChildCount() - 1);
        }
        List<FileStore.Item> items = FileStore.listReceived(this);
        if (items.isEmpty()) {
            filesCard.addView(Ui.caption(this, "还没有收到文件。电脑端点「发文件」即可推送到这里。"));
            return;
        }
        int n = Math.min(items.size(), 30);
        for (int i = 0; i < n; i++) {
            filesCard.addView(buildRow(items.get(i)));
        }
        if (items.size() > n) {
            filesCard.addView(Ui.caption(this, "…共 " + items.size() + " 个文件，仅显示最近 " + n + " 个"));
        }
    }

    private View buildRow(final FileStore.Item item) {
        LinearLayout row = Ui.row(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(item.name);
        name.setTextSize(14);
        name.setTextColor(Ui.text(this));
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(name);
        TextView sub = new TextView(this);
        sub.setText(fmtSize(item.size));
        sub.setTextSize(11);
        sub.setTextColor(Ui.text3(this));
        col.addView(sub);
        row.addView(col);

        Button bOpen = Ui.ghostButton(this, "打开");
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bOpen.setLayoutParams(olp);
        bOpen.setOnClickListener(v -> openItem(this, item));
        row.addView(bOpen);

        row.setOnClickListener(v -> openItem(this, item));
        return row;
    }

    /** 用系统应用打开收到的文件（主页面「文件」消息行也复用这里）。 */
    public static void openItem(Activity activity, FileStore.Item item) {
        try {
            Uri uri = item.uri;
            if (uri == null && item.file != null) {
                uri = MiniFileProvider.getUriForFile(activity, item.file);
            }
            if (uri == null) throw new Exception("无法定位文件");
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, activity.getContentResolver().getType(uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (Exception e) {
            // 没有可打开该类型的应用 → 退回分享面板
            try {
                Uri uri = item.uri != null ? item.uri
                        : MiniFileProvider.getUriForFile(activity, item.file);
                Intent s = new Intent(Intent.ACTION_SEND);
                s.setType(activity.getContentResolver().getType(uri));
                s.putExtra(Intent.EXTRA_STREAM, uri);
                s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(Intent.createChooser(s, "分享文件"));
            } catch (Exception e2) {
                new AlertDialog.Builder(activity)
                        .setTitle("打不开这个文件")
                        .setMessage("系统里没有能打开它的应用。文件位置：\n"
                                + (item.file != null ? item.file.getAbsolutePath()
                                : FileStore.dirLabel(activity)))
                        .setPositiveButton("知道了", null)
                        .show();
            }
        }
    }

    private static String fmtSize(long n) {
        if (n < 0) return "大小未知";
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        if (n < 1024L * 1024 * 1024) return String.format("%.1f MB", n / 1048576.0);
        return String.format("%.2f GB", n / 1073741824.0);
    }
}
