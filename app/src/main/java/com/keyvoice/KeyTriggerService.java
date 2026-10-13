package com.keyvoice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * 捕获实体按键（默认音量减键，可用「学习按键」改绑）。
 * 需要在系统「无障碍」里开启本服务。
 *
 * 讯飞输入法两步式流程（ime 模式，默认）：
 *   第1次按 -> 在空格键位置模拟“按住不松” → 讯飞开始语音，直接说话
 *              （输入窗口未开时同步打开；找到键盘后延时 1 秒再按住）
 *   第2次按 -> 模拟“松开空格” → 等讯飞出字后自动发送到电脑
 *   即：最后一次点击之前，虚拟手指一直按在空格上。
 */
public class KeyTriggerService extends AccessibilityService {

    private long downAt = 0;

    private static final int S_IDLE = 0;      // 等第1次按
    private static final int S_PENDING = 1;   // 已计划长按，在等 1 秒延时
    private static final int S_HOLDING = 2;   // 正在“按住”空格（录音中）

    /** 找到键盘位置后先等 1 秒（键盘动画/布局稳定）再按住 */
    private static final long PRESS_DELAY_MS = 1000;
    /** 最长按住 55 秒，防止虚拟指针卡死 */
    private static final long HOLD_TIMEOUT_MS = 55_000;

    private int holdState = S_IDLE;
    private Rect pendingRect = null;

    private GestureDescription.StrokeDescription holdStroke;
    private boolean holdReady = false;   // 第一段“按下”手势是否已被系统接受
    private float holdX, holdY;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable pendingPressTask = this::pressPendingSpace;
    private final Runnable holdTimeoutTask = this::holdTimeout;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        Config cfg = new Config(this);
        int code = event.getKeyCode();

        // 学习模式：下一次按下的物理键被绑定为触发键
        if (cfg.learning()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                cfg.triggerKey(code);
                cfg.learning(false);
                Trigger.toast(this, "已绑定按键：" + KeyEvent.keyCodeToString(code));
            }
            return true;
        }

        int trigger = cfg.triggerKey();
        if (trigger != 0 && code == trigger) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                downAt = System.currentTimeMillis();
                if ("ime".equals(cfg.mode()) && cfg.imeHold()) {
                    pressStep();
                    return cfg.blockKey();
                }
                if (!cfg.longPress()) fire(); // 识别服务模式：按下即触发
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (cfg.longPress() && System.currentTimeMillis() - downAt >= 500) fire();
            }
            // 吞掉该键原功能（音量键不再调音量等）；blockKey 关掉则放行原功能只触发
            return cfg.blockKey();
        }
        return super.onKeyEvent(event);
    }

    /** 窗口已开着时再按 = 立即发送（识别服务模式 / 手动流程用） */
    private void fire() {
        if (VoiceInputActivity.instance != null) {
            sendBroadcast(new Intent("com.keyvoice.COMMIT").setPackage(getPackageName()));
        } else {
            Trigger.fire(this);
        }
    }

    // ---------- 两步式：①按住空格说话 ②松开并发送 ----------

    private void pressStep() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean screenOn = pm != null && pm.isInteractive();

        switch (holdState) {
            case S_IDLE:
                if (!screenOn) {
                    Trigger.fire(this);  // 灭屏：输入法用不了，走识别服务
                    return;
                }
                if (VoiceInputActivity.instance == null) Trigger.openInputUi(this);
                // 计划 1 秒后“按住空格”
                holdState = S_PENDING;
                Rect r = findImeBounds();
                if (r == null) r = fallbackKeyboardRect();
                pendingRect = r;
                main.postDelayed(pendingPressTask, PRESS_DELAY_MS);
                break;

            case S_PENDING:
                // 延时期间又按了一次：不等了，立即按住
                main.removeCallbacks(pendingPressTask);
                pressPendingSpace();
                break;

            case S_HOLDING:
                // 最后一次点击：松开空格 + 发送
                releaseAndSend();
                break;
        }
    }

    private void pressPendingSpace() {
        if (holdState != S_PENDING || pendingRect == null) return;
        Rect r = pendingRect;
        pendingRect = null;
        pressSpaceHold(r);
    }

    /** 找输入法窗口在屏幕上的位置；找不到返回 null */
    private Rect findImeBounds() {
        try {
            for (AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    if (!r.isEmpty()) return r;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 拿不到键盘窗口时的兜底：屏幕底部中心（空格键通常在这里） */
    private Rect fallbackKeyboardRect() {
        try {
            android.graphics.Point size = new android.graphics.Point();
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealSize(size);
            float d = getResources().getDisplayMetrics().density;
            // 底部一排键：跳过导航栏(~48dp)，键高约 56dp
            int bottom = (int) (size.y - 48 * d);
            int top = (int) (bottom - 56 * d);
            return new Rect(0, top, size.x, bottom);
        } catch (Exception e) {
            return new Rect(0, 0, 1080, 2200); // 极端情况：通用竖屏尺寸的底部
        }
    }

    /** 在空格键位置开始“按住不松”（录音中），直到下一次按键才松开 */
    private void pressSpaceHold(Rect ime) {
        holdX = ime.centerX();
        holdY = ime.bottom - Math.max(40, ime.height() / 8); // 底排中心 = 空格
        Path p = new Path();
        p.moveTo(holdX, holdY);
        holdStroke = new GestureDescription.StrokeDescription(p, 0, 120, true); // willContinue=按住不放
        holdReady = false;
        boolean ok = dispatchGesture(
                new GestureDescription.Builder().addStroke(holdStroke).build(),
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) { holdReady = true; }
                    @Override public void onCancelled(GestureDescription g) { holdReady = false; }
                }, main);

        if (ok) {
            holdState = S_HOLDING;
            Trigger.toast(this, "已按住空格：请说话；说完再按一次 = 松开并发送到电脑");
            main.postDelayed(holdTimeoutTask, HOLD_TIMEOUT_MS);
        } else {
            holdStroke = null;
            holdState = S_IDLE;
            Trigger.toast(this, "模拟手势失败，请手动长按空格说话，再按触发键发送");
        }
    }

    private void holdTimeout() {
        if (holdState == S_HOLDING) releaseAndSend();
    }

    /** 松开空格 -> 等讯飞把文字写进输入框 -> 自动发送 */
    private void releaseAndSend() {
        main.removeCallbacks(holdTimeoutTask);
        if (holdState == S_HOLDING && holdStroke != null) {
            try {
                if (!holdReady) {
                    // 第一段还没被系统确认完，稍等再松开
                    main.postDelayed(this::releaseAndSend, 150);
                    return;
                }
                Path p = new Path();
                p.moveTo(holdX, holdY);
                p.lineTo(holdX, holdY + 1); // 极小位移的收尾段，等价于“抬手”
                GestureDescription.StrokeDescription release =
                        holdStroke.continueStroke(p, 0, 60, false);
                dispatchGesture(new GestureDescription.Builder().addStroke(release).build(), null, main);
            } catch (Exception ignored) {
            }
        }
        holdStroke = null;
        holdState = S_IDLE;
        Trigger.toast(this, "已松开，等识别出字后自动发送…");
        // 给讯飞留出把文字写进输入框的时间；输入窗口里若一直没字会提示
        main.postDelayed(() -> {
            if (VoiceInputActivity.instance != null) {
                sendBroadcast(new Intent("com.keyvoice.COMMIT").setPackage(getPackageName()));
            }
        }, 1600);
    }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
