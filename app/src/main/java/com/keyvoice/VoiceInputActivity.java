package com.keyvoice;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * 亮屏时的输入窗口：底部一条，自动聚焦并弹出输入法。
 * 用手机装的讯飞输入法按语音按钮说话，文字进来后按设定自动发到电脑。
 */
public class VoiceInputActivity extends Activity {

    static VoiceInputActivity instance;

    private EditText et;
    private TextView tvStatus;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Config cfg;
    private boolean sending = false;

    private final BroadcastReceiver commit = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // 自动发送已取消：松开长按后文字留在框内，由用户点「发送」手动发
        }
    };

    private final BroadcastReceiver result = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String t = i.getStringExtra("text");
            if (t != null) tvStatus.setText("已发送：" + t);
            h.postDelayed(() -> finish(), 700);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        cfg = new Config(this);
        // 方向：只允许竖屏/翻转竖屏（与主页面一致），永不横屏
        setRequestedOrientation(cfg.invertMode()
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        setContentView(R.layout.activity_voice);

        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.gravity = Gravity.TOP;   // 窗口固定在顶部（键盘在底部不受影响）
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        w.setAttributes(lp);

        tvStatus = findViewById(R.id.tvStatus);
        et = findViewById(R.id.etText);
        Button btnSend = findViewById(R.id.btnSend);
        Button btnClear = findViewById(R.id.btnClear);
        Button btnClose = findViewById(R.id.btnClose);

        btnSend.setOnClickListener(v -> doSend(true));
        btnClear.setOnClickListener(v -> et.setText(""));
        btnClose.setOnClickListener(v -> finish());

        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                // 自动发送已取消：文字只落在输入框，由用户点「发送」手动发到电脑
            }
        });

        // Android 13+ 必须显式声明导出性，否则 SecurityException 崩溃（同 MainActivity）
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commit, new IntentFilter("com.keyvoice.COMMIT"),
                    android.content.Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(result, new IntentFilter(VoiceService.ACTION_RESULT),
                    android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(commit, new IntentFilter("com.keyvoice.COMMIT"));
            registerReceiver(result, new IntentFilter(VoiceService.ACTION_RESULT));
        }

        if ("sr".equals(cfg.mode())) {
            // 交给识别服务，自己只做展示（结果通过广播回来）
            tvStatus.setText("正在调用识别服务（可灭屏）…");
            VoiceService.startListening(this);
            et.post(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
            });
        } else {
            tvStatus.setText("输入窗口已就绪：语音出字后点「发送」发到电脑");
            et.requestFocus();
            et.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
            }, 150);
        }
    }

    private void doSend(boolean force) {
        if (sending && !force) return;
        String text = et.getText() == null ? "" : et.getText().toString();
        if (text.trim().isEmpty()) {
            tvStatus.setText("没有内容");
            return;
        }
        if (cfg.addNewline()) text += "\n";

        sending = true;
        tvStatus.setText("发送中…");
        String finalText = text;
        Sender.send(this, text, (ok, msg) -> {
            sending = false;
            tvStatus.setText(ok ? "已发送：" + finalText : "失败：" + msg);
            if (ok && cfg.autoClose()) h.postDelayed(() -> finish(), 500);
        });
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(commit);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(result);
        } catch (Exception ignored) {
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public void finish() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && getCurrentFocus() != null) {
                imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
            }
        } catch (Exception ignored) {
        }
        super.finish();
        overridePendingTransition(0, 0);
    }
}
