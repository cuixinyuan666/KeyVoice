package com.keyvoice;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JsPromptResult;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 手机控电脑（网页远控）。
 *
 * <p>PC 端点「远控」后会在 17894 端口起一个远控服务器（见 pc/kv_remote.py，
 * 架构参考 noVNC/Guacamole「浏览器即客户端」）。本页用 WebView 直接打开它：
 * 看到电脑屏幕（MJPEG 流），触摸即鼠标、页面自带键盘按钮输入中文。
 *
 * <p>地址规则与消息通道一致：
 * · 局域网模式：http://&lt;电脑IP&gt;:17894/remote
 * · adb 模式：http://127.0.0.1:17894/remote（电脑端先执行 adb reverse tcp:17894 tcp:17894）
 *
 * <p>PIN：电脑端远控窗口上会显示 4 位 PIN，在本页输入一次即记住（存 Config）。
 */
public class RemoteActivity extends Activity {

    private Config cfg;
    private EditText etPin;
    private TextView tvStatus;
    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new Config(this);
        setTheme(Ui.themeRes("light".equalsIgnoreCase(cfg.theme())));
        Ui.setTheme(cfg.theme());
        // 看电脑画面横屏最舒服：本页允许跟随传感器（主页面仍是竖屏锁定，互不影响）
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.bg(this));
        setContentView(root);

        // ---- 工具条：标题 + PIN + 连接 + 断开 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Ui.round(Ui.surface(this), Ui.border(this), 1, 0f));
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button bBack = Ui.ghostButton(this, "返回");
        bBack.setMinHeight(Ui.dp(this, 36));
        bBack.setOnClickListener(v -> finish());
        bar.addView(bBack);

        tvStatus = new TextView(this);
        tvStatus.setTextSize(12);
        tvStatus.setTextColor(Ui.text2(this));
        tvStatus.setSingleLine(true);
        tvStatus.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = Ui.dp(this, 8);
        tvStatus.setLayoutParams(slp);
        bar.addView(tvStatus);

        etPin = Ui.inputField(this, "PIN");
        etPin.setInputType(InputType.TYPE_CLASS_NUMBER);
        etPin.setSingleLine(true);
        etPin.setText(cfg.remotePin());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                Ui.dp(this, 84), ViewGroup.LayoutParams.WRAP_CONTENT);
        etPin.setLayoutParams(plp);
        etPin.setMinHeight(Ui.dp(this, 36));
        bar.addView(etPin);

        Button bGo = Ui.primaryButton(this, "连接");
        bGo.setMinHeight(Ui.dp(this, 36));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = Ui.dp(this, 6);
        bGo.setLayoutParams(glp);
        bGo.setOnClickListener(v -> connect());
        bar.addView(bGo);

        // ---- WebView ----
        web = new WebView(this);
        web.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        web.setBackgroundColor(0xFF000000);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setSupportZoom(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView v, String url) {
                if (tvStatus != null) tvStatus.setText("已加载，等待画面…（黑屏点「画质→流畅」）");
            }

            @Override
            public void onReceivedError(WebView v, android.webkit.WebResourceRequest req,
                                        android.webkit.WebResourceError err) {
                if (tvStatus != null && req != null && req.isForMainFrame()) {
                    tvStatus.setText("连不上电脑远控：先在电脑端点「远控」；"
                            + "adb 模式需执行 adb reverse tcp:17894 tcp:17894");
                }
            }
        });
        // 页面在没拿到 pin 时会用 prompt 询问：用原生弹窗接管，保证能输入
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsPrompt(WebView v, String url, String message,
                                      String defaultValue, final JsPromptResult result) {
                final EditText e = Ui.inputField(RemoteActivity.this, "PIN");
                e.setInputType(InputType.TYPE_CLASS_NUMBER);
                if (defaultValue != null) e.setText(defaultValue);
                new AlertDialog.Builder(RemoteActivity.this)
                        .setTitle(message == null ? "输入 PIN" : message)
                        .setView(e)
                        .setPositiveButton("确定", (d, w) -> {
                            String pin = e.getText().toString().trim();
                            cfg.remotePin(pin);
                            result.confirm(pin);
                        })
                        .setNegativeButton("取消", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });
        root.addView(web);

        connect();
    }

    private void connect() {
        // 允许通过启动参数覆盖（便于 adb 自动化 / 快捷方式）：
        //   am start -n com.keyvoice/.RemoteActivity --es host 192.168.1.5 --es pin 1234
        String host = getIntent().getStringExtra("host");
        if (host == null || host.isEmpty()) {
            host = cfg.adbMode() ? "127.0.0.1" : cfg.host();
        }
        String pin = getIntent().getStringExtra("pin");
        if (pin == null) pin = etPin.getText().toString().trim();
        if (host == null || host.isEmpty()) {
            tvStatus.setText("未设置电脑地址");
            new AlertDialog.Builder(this)
                    .setTitle("还没设置电脑地址")
                    .setMessage("局域网模式需要电脑 IP（可在设置页「搜索设备」自动发现）；\n"
                            + "USB（adb）模式无需地址，但要先在电脑执行：\n"
                            + "adb reverse tcp:17894 tcp:17894")
                    .setPositiveButton("去设置", (d, w) ->
                            startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        cfg.remotePin(pin);
        etPin.setText(pin);
        String url = "http://" + host + ":17894/remote" + (pin.isEmpty() ? "" : ("?pin=" + pin));
        tvStatus.setText("连接 " + host + ":17894（电脑端需已点「远控」）");
        web.loadUrl(url);
    }

    /**
     * 返回键必须在【分发之前】拦下：WebView 是焦点视图，它会先消费 BACK
     * （页面有历史时 onKeyDown 收不到），导致整页退不出去。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        try {
            if (web != null) {
                web.loadUrl("about:blank");   // 断开 MJPEG 流，让 PC 端停止截屏
                web.destroy();
            }
        } catch (Exception ignored) { }
        super.onDestroy();
    }
}
