package com.keyvoice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.animation.ValueAnimator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * 主页面（v0.3.0 倒握布局）：
 *   - 顶部是输入框（讯飞语音出的字落在这里）
 *   - 输入框右下角一个“动态旋转”的状态点：正常绿、异常（无障碍未开/未连）红，
 *     异常时下方出现一个指向「设置」的动态箭头（点击直接跳无障碍设置）
 *   - 下半部分由 DragLayout 承载三个圆形图标按钮：设置（右中）、模拟长按、电脑上屏；
 *     长按进入编辑态可拖动 / 双指缩放，右上角 √ 固定并保存布局
 */
public class MainActivity extends Activity {

    private EditText et;
    private ImageView statusDot, arrow;
    private ImageButton btnHold, btnSend, btnSettings;
    private DragLayout side;
    private FrameLayout overlay;
    private TextView tip;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Config cfg;
    private boolean sending = false;
    /** 局域网模式未连接时，最多自动搜索连接一次 */
    private boolean lanAutoScanned = false;
    /** 模拟空格“松开”后置位：等讯飞出字后立即发送（优先于普通自动发送） */
    private boolean commitWait = false;

    // ---- 首启权限引导（保留，仅在设置页手动触发；不再自动弹）----
    private boolean onboardingActive = false;
    private boolean onboardWaitingReturn = false;
    private static final int REQ_ONBOARD_MIC = 200;

    private final Runnable autoSendTask = () -> doSend(false);
    private final Runnable hideTip = () -> { if (overlay != null) overlay.setVisibility(View.GONE); };

    private final BroadcastReceiver commit = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String t = et.getText() == null ? "" : et.getText().toString();
            if (t.trim().isEmpty()) {
                commitWait = true;
                showCenterTip("已松开，等识别出字后自动发送…");
            } else {
                doSend(true);
            }
        }
    };

    private final BroadcastReceiver result = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String t = i.getStringExtra("text");
            if (t != null && et != null) {
                et.append(t);
                showCenterTip("已收到识别结果，点「电脑上屏」发送");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new Config(this);

        // 根用 FrameLayout：内容垂直排列 + 一个居中、仅覆盖自身范围的小提示层（不挡按钮）
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.bg(this));
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 输入框 + 右下角状态点 + 异常箭头 ----
        RelativeLayout inputWrap = new RelativeLayout(this);
        inputWrap.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        et = Ui.inputField(this, "语音识别出的文字会落这里；也可手动输入");
        et.setMinLines(3);
        et.setGravity(Gravity.TOP);
        RelativeLayout.LayoutParams etp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        et.setLayoutParams(etp);
        inputWrap.addView(et);

        statusDot = new ImageView(this);
        int ds = Ui.dp(this, 14);
        RelativeLayout.LayoutParams dotp = new RelativeLayout.LayoutParams(ds, ds);
        dotp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        dotp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        dotp.setMargins(0, 0, Ui.dp(this, 14), Ui.dp(this, 14));
        statusDot.setLayoutParams(dotp);
        statusDot.setId(View.generateViewId());
        inputWrap.addView(statusDot);

        arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow);
        arrow.setColorFilter(Color.WHITE);
        arrow.setRotation(45f); // 指向右下（设置齿轮方向）
        arrow.setContentDescription("无障碍未开启，点此去设置");
        arrow.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
        });
        RelativeLayout.LayoutParams arp = new RelativeLayout.LayoutParams(
                Ui.dp(this, 22), Ui.dp(this, 22));
        arp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        arp.addRule(RelativeLayout.BELOW, statusDot.getId());
        arp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 14), 0);
        arrow.setLayoutParams(arp);
        arrow.setVisibility(View.GONE);
        inputWrap.addView(arrow);

        // 救砖入口：长按输入框右下角状态点 → 清空异常布局存档，把三个按钮复位。
        // 仅当按钮因历史存档坐标越界而整体不可见时使用。
        statusDot.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("复位按钮布局")
                    .setMessage("把「设置 / 模拟长按 / 电脑上屏」三个按钮恢复到默认位置？\n\n（用于按钮被拖到看不见的地方时的应急恢复）")
                    .setPositiveButton("复位", (d, w) -> {
                        side.resetLayout();
                        showCenterTip("按钮布局已复位");
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return true;
        });

        // 箭头动态：轻微上下浮动
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(700);
        va.setRepeatCount(ValueAnimator.INFINITE);
        va.setRepeatMode(ValueAnimator.REVERSE);
        va.addUpdateListener(a -> arrow.setTranslationY((Float) a.getAnimatedValue() * Ui.dp(this, 8)));
        va.start();

        content.addView(inputWrap);

        // ---- 右侧可拖动按钮容器 ----
        side = new DragLayout(this);
        side.setConfig(cfg);
        side.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        int bs = Ui.dp(this, 38);
        btnSettings = Ui.iconButton(this, R.drawable.ic_gear, Ui.text2(this), 38);
        btnSettings.setId(View.generateViewId());
        btnSettings.setTag("s");
        btnSettings.setContentDescription("设置");
        RelativeLayout.LayoutParams glp = new RelativeLayout.LayoutParams(bs, bs);
        glp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        glp.addRule(RelativeLayout.CENTER_VERTICAL);
        btnSettings.setLayoutParams(glp);
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        btnHold = Ui.iconButton(this, R.drawable.ic_mic, Ui.primary(this), 38);
        btnHold.setId(View.generateViewId());
        btnHold.setTag("h");
        btnHold.setContentDescription("模拟长按空格（按住说话）");
        RelativeLayout.LayoutParams hlp = new RelativeLayout.LayoutParams(bs, bs);
        hlp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        hlp.addRule(RelativeLayout.BELOW, btnSettings.getId());
        hlp.topMargin = Ui.dp(this, 16);
        btnHold.setLayoutParams(hlp);
        btnHold.setOnClickListener(v -> onHoldClick());

        btnSend = Ui.iconButton(this, R.drawable.ic_monitor, Ui.success(this), 38);
        btnSend.setId(View.generateViewId());
        btnSend.setTag("e");
        btnSend.setContentDescription("电脑上屏（松开并发送）");
        RelativeLayout.LayoutParams slp = new RelativeLayout.LayoutParams(bs, bs);
        slp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        slp.addRule(RelativeLayout.BELOW, btnHold.getId());
        slp.topMargin = Ui.dp(this, 16);
        btnSend.setLayoutParams(slp);
        btnSend.setOnClickListener(v -> onSendClick());

        side.addView(btnSettings);
        side.addView(btnHold);
        side.addView(btnSend);
        content.addView(side);

        root.addView(content);

        // ---- 居中短提示层（仅覆盖自身范围，不挡按钮）----
        overlay = new FrameLayout(this);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        overlay.setVisibility(View.GONE);
        overlay.setClickable(false);
        tip = new TextView(this);
        tip.setTextSize(15);
        tip.setTypeface(null, android.graphics.Typeface.BOLD);
        tip.setTextColor(Color.WHITE);
        tip.setGravity(Gravity.CENTER);
        tip.setMaxWidth(Ui.dp(this, 280));
        tip.setBackground(Ui.round(Ui.surfaceHi(this), Ui.border(this), 1, Ui.dp(this, 12)));
        tip.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        overlay.addView(tip);
        root.addView(overlay);

        setContentView(root);

        // 在首次布局完成后再还原存档位置：此时通过布局规则算出的按钮 left/top 已确定，
        // 才能按容器当前宽高正确重投影，避免按钮落到容器外。
        side.post(() -> side.restorePositions(cfg.layoutJson()));

        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                if (KeyTriggerService.holding) return;
                if (commitWait && s != null && !s.toString().trim().isEmpty()) {
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

    /** 居中短提示（约 800ms 后消失） */
    private void showCenterTip(String msg) {
        tip.setText(msg);
        overlay.setVisibility(View.VISIBLE);
        h.removeCallbacks(hideTip);
        h.postDelayed(hideTip, 800);
    }

    /**
     * 模拟长按按钮：只负责“按住空格开始说话”。
     * 松开动作已交给「电脑上屏」按钮（见 onSendClick）。
     */
    private void onHoldClick() {
        if (KeyTriggerService.holding) {
            showCenterTip("正在说话中；完成后点「电脑上屏」松开并上屏");
            return;
        }
        if (KeyTriggerService.instance == null) {
            showCenterTip("需要无障碍服务（注入手势用）。点右下角箭头去开启");
            return;
        }
        if (!KeyTriggerService.instance.beginHoldFromUi()) {
            showCenterTip("上一次长按正在复位中，稍等 1 秒再点");
            return;
        }
        et.requestFocus();
        h.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        }, 100);
        setHoldActive(true);
    }

    /** 用按钮底色反映「是否正在按住空格」：按住=黄，松开=蓝 */
    private void setHoldActive(boolean active) {
        int base = active ? Ui.warning(this) : Ui.primary(this);
        btnHold.setBackground(Ui.buttonBg(base, Ui.shade(base, -0.28f), (float) Ui.dp(this, 38) / 2f));
    }

    /**
     * 电脑上屏按钮：若正在“按住空格”则先松开（讯飞停止并出字，COMMIT 广播到达后自动发送），
     * 否则直接把框内现有文字发到电脑。
     */
    private void onSendClick() {
        if (KeyTriggerService.holding) {
            setHoldActive(false);
            if (KeyTriggerService.instance != null) KeyTriggerService.instance.endHold();
            showCenterTip("已松开，等出字后自动上屏…");
        } else {
            doSend(true);
        }
    }

    private void doSend(boolean force) {
        if (sending && !force) return;
        String text = et.getText() == null ? "" : et.getText().toString();
        if (text.trim().isEmpty()) {
            showCenterTip("没有内容可发送");
            return;
        }
        if (cfg.addNewline()) text += "\n";

        sending = true;
        setHoldActive(false);
        commitWait = false;
        h.removeCallbacks(autoSendTask);
        String finalText = text;
        Sender.send(this, text, (ok, msg) -> {
            sending = false;
            if (ok) {
                et.setText("");
                showCenterTip("已发送：" + finalText);
            } else {
                showCenterTip("发送失败：" + msg);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (onboardingActive && onboardWaitingReturn) {
            onboardWaitingReturn = false;
            advanceOnboarding();
        }
        if (!onboardingActive && cfg != null && !cfg.adbMode() && cfg.host().isEmpty() && !lanAutoScanned) {
            lanAutoScanned = true;
            autoLanConnect();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_ONBOARD_MIC && onboardingActive) advanceOnboarding();
    }

    private void refreshStatus() {
        boolean accOk = KeyTriggerService.instance != null;
        boolean connected = cfg.adbMode() || !cfg.host().isEmpty();
        boolean abnormal = !accOk || !connected;
        statusDot.setBackgroundResource(abnormal ? R.drawable.dot_red : R.drawable.dot_green);
        android.graphics.drawable.Drawable d = statusDot.getBackground();
        if (d instanceof android.graphics.drawable.Animatable) ((android.graphics.drawable.Animatable) d).start();
        arrow.setVisibility(abnormal ? View.VISIBLE : View.GONE);
        setHoldActive(!KeyTriggerService.holding);
        // 已开启无障碍则记一次“已完成引导”，后续不再提示验证
        if (accOk) cfg.onboarded(true);
    }

    /** 局域网模式未连接时，自动搜索并连接第一台发现的电脑 */
    private void autoLanConnect() {
        final boolean[] done = {false};
        Discover.scan(this, 4000, new Discover.Sink() {
            @Override
            public void onFound(String ip, int port) {
                if (done[0]) return;
                done[0] = true;
                cfg.host(ip);
                cfg.port(port);
                runOnUiThread(() -> showCenterTip("已自动连接到 " + ip + ":" + port));
            }

            @Override
            public void onDone(int count) {
                if (!done[0]) runOnUiThread(() ->
                        showCenterTip("局域网未找到电脑；去「设置」手动搜索"));
            }
        });
    }

    // ------------------------------------------------- 首启权限引导

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean accEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.toLowerCase().contains(getPackageName().toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean batteryIgnored() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void advanceOnboarding() {
        if (!micGranted()) { showOnboardMic(); return; }
        if (!accEnabled()) { showOnboardAcc(); return; }
        if (!batteryIgnored()) { showOnboardBattery(); return; }
        finishOnboarding(true);
    }

    private void showOnboardMic() {
        new AlertDialog.Builder(this)
                .setTitle("权限引导 1/3 · 麦克风")
                .setMessage("「模拟长按」听写需要麦克风权限来录音。是否现在授权？")
                .setCancelable(false)
                .setPositiveButton("去授权", (d, w) ->
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_ONBOARD_MIC))
                .setNegativeButton("暂时跳过", (d, w) -> finishOnboarding(false))
                .show();
    }

    private void showOnboardAcc() {
        new AlertDialog.Builder(this)
                .setTitle("权限引导 2/3 · 无障碍服务")
                .setMessage("「模拟长按」通过无障碍服务向讯飞输入框注入手势，必须在系统设置里手动打开名为「a」的开关（KeyVoice 的无障碍服务）。\n\n点「去开启」后会跳到无障碍设置，找到 a 并打开开关，然后返回即可自动继续。")
                .setCancelable(false)
                .setPositiveButton("去开启", (d, w) -> {
                    onboardWaitingReturn = true;
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
                })
                .setNegativeButton("暂时跳过", (d, w) -> finishOnboarding(false))
                .show();
    }

    private void showOnboardBattery() {
        new AlertDialog.Builder(this)
                .setTitle("权限引导 3/3 · 电池优化")
                .setMessage("为保证后台常驻不被系统杀掉，建议允许「忽略电池优化」。\n\n点「去设置」后允许本应用忽略电池优化，然后返回即可。")
                .setCancelable(false)
                .setPositiveButton("去设置", (d, w) -> {
                    onboardWaitingReturn = true;
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        } catch (Exception ignored) { }
                    }
                })
                .setNegativeButton("暂时跳过", (d, w) -> finishOnboarding(false))
                .show();
    }

    private void finishOnboarding(boolean allGranted) {
        onboardingActive = false;
        cfg.onboarded(true);
        if (allGranted) {
            new AlertDialog.Builder(this)
                    .setTitle("权限已就绪")
                    .setMessage("麦克风 / 无障碍服务 / 电池优化 均已授权。回到主页面点「模拟长按空格」即可开始听写。")
                    .setPositiveButton("开始使用", null)
                    .show();
        } else {
            showCenterTip("已跳过引导；需要时可在「设置」里补开权限");
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(autoSendTask);
        h.removeCallbacks(hideTip);
        try { unregisterReceiver(commit); } catch (Exception ignored) { }
        try { unregisterReceiver(result); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
