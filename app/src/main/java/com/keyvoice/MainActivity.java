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
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 主页面（v0.3.0）：沿用 v0.2.0 的三件套，但改用统一深色设计系统（Ui）。
 *   ① 输入框（讯飞语音出的字落在这里）
 *   ② 模拟长按（在讯飞空格键位置模拟“按住不松”开始说话，再点一次松开）
 *   ③ 电脑上屏（把输入框里的文字发到电脑光标处）
 * 其余设置全部在「设置」页。
 */
public class MainActivity extends Activity {

    private EditText et;
    private TextView tvStatus;
    private View statusDot;
    private Button btnHold, btnSend, btnSettings;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Config cfg;
    private boolean sending = false;
    /** 模拟空格“松开”后置位：等讯飞出字后立即发送（优先于普通自动发送） */
    private boolean commitWait = false;

    private final Runnable autoSendTask = () -> doSend(false);

    private final BroadcastReceiver commit = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String t = et.getText() == null ? "" : et.getText().toString();
            if (t.trim().isEmpty()) {
                commitWait = true;   // 松开了但讯飞还没出字，等文字一到就发
                tvStatus.setText("已松开，等识别出字后自动发送…");
            } else {
                doSend(true);
            }
        }
    };

    private final BroadcastReceiver result = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // 识别服务模式（通知栏/媒体键触发）的结果直接落进输入框
            String t = i.getStringExtra("text");
            if (t != null && et != null) {
                et.append(t);
                tvStatus.setText("已收到识别结果，点「电脑上屏」发送");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new Config(this);

        LinearLayout root = Ui.root(this);

        root.addView(Ui.appBar(this, "KeyVoice · 按键语音"));

        // 连接状态卡片
        LinearLayout statusCard = Ui.sectionCard(this);
        LinearLayout sr = Ui.row(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = Ui.stateDot(this, Ui.text3(this));
        LinearLayout.LayoutParams dlp = (LinearLayout.LayoutParams) statusDot.getLayoutParams();
        dlp.rightMargin = Ui.dp(this, 8);
        sr.addView(statusDot);
        tvStatus = Ui.body(this, "连接状态：初始化中…");
        sr.addView(tvStatus);
        statusCard.addView(sr);
        root.addView(statusCard);

        // 主操作卡片
        LinearLayout main = Ui.sectionCard(this);
        main.addView(Ui.sectionTitle(this, "语音输入"));

        et = Ui.inputField(this, "语音识别出的文字会落在这里；也可直接手动输入");
        et.setMinLines(4);
        et.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.bottomMargin = Ui.dp(this, 12);
        et.setLayoutParams(elp);
        main.addView(et);

        LinearLayout.LayoutParams full = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        full.bottomMargin = Ui.dp(this, 10);

        btnHold = Ui.primaryButton(this, "🎤  模拟长按空格（开始说话）");
        btnHold.setOnClickListener(v -> onHoldClick());
        btnHold.setLayoutParams(full);
        main.addView(btnHold);

        btnSend = Ui.accentButton(this, "电脑上屏");
        btnSend.setOnClickListener(v -> doSend(true));
        btnSend.setLayoutParams(full);
        main.addView(btnSend);

        root.addView(main);

        // 设置入口
        btnSettings = Ui.ghostButton(this, "设置（电脑地址 / 语音引擎 / 常驻 / 权限）");
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        root.addView(btnSettings);

        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                if (KeyTriggerService.holding) return; // 正在“按住空格”录音，禁止自动发送打断
                if (commitWait && s != null && !s.toString().trim().isEmpty()) {
                    // 模拟空格松开后，讯飞出字 -> 立即发送
                    commitWait = false;
                    h.removeCallbacks(autoSendTask);
                    h.postDelayed(() -> doSend(false), 700);
                    return;
                }
                if (!cfg.autoSend()) return;
                if (s == null || s.toString().trim().isEmpty()) return;
                h.removeCallbacks(autoSendTask);
                h.postDelayed(autoSendTask, cfg.idleMs());
            }
        });

        registerReceiver(commit, new IntentFilter("com.keyvoice.COMMIT"));
        registerReceiver(result, new IntentFilter(VoiceService.ACTION_RESULT));
    }

    /** 模拟长按按钮：第一次点 = 按住空格开始说话；第二次点 = 松开并等字发送 */
    private void onHoldClick() {
        if (KeyTriggerService.holding) {
            btnHold.setText("🎤  已松开，识别中…");
            KeyTriggerService.instance.endHold();
            return;
        }
        if (KeyTriggerService.instance == null) {
            tvStatus.setText("需要无障碍服务（注入手势用）。请到「设置」里开启。");
            return;
        }
        // 聚焦输入框并确保输入法弹出，然后由服务等 1 秒后按住空格
        et.requestFocus();
        h.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        }, 100);
        if (KeyTriggerService.instance.beginHoldFromUi()) {
            btnHold.setText("松开（说话中…再点一次）");
        }
    }

    private void doSend(boolean force) {
        if (sending && !force) return;
        String text = et.getText() == null ? "" : et.getText().toString();
        if (text.trim().isEmpty()) {
            tvStatus.setText("没有内容可发送");
            return;
        }
        if (cfg.addNewline()) text += "\n";

        sending = true;
        tvStatus.setText("发送中…");
        btnHold.setText("🎤  模拟长按空格（开始说话）");
        String finalText = text;
        Sender.send(this, text, (ok, msg) -> {
            sending = false;
            tvStatus.setText(ok ? "已发送：" + finalText : "失败：" + msg + "（检查设置里的电脑地址）");
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        String where = cfg.adbMode()
                ? "adb 模式（需 adb reverse 隧道）"
                : (cfg.host().isEmpty() ? "局域网模式（未填电脑 IP，去设置里搜索）" : "局域网 → " + cfg.host() + ":" + cfg.port());
        boolean accOk = KeyTriggerService.instance != null;
        tvStatus.setText(where + "\n无障碍服务：" + (accOk ? "已开启（手势可用）" : "未开启（「模拟长按」需要它）"));
        int color = accOk ? Ui.success(this) : Ui.warning(this);
        statusDot.setBackground(Ui.round(color, 0, 0, (float) Ui.dp(this, 10) / 2f));
        if (!KeyTriggerService.holding) {
            btnHold.setText("🎤  模拟长按空格（开始说话）");
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(autoSendTask);
        try {
            unregisterReceiver(commit);
        } catch (Exception ignored) {
        }
        try {
            unregisterReceiver(result);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }
}
