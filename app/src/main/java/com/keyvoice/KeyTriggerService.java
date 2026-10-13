package com.keyvoice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * 捕获实体按键（默认音量减键，可用「学习按键」改绑）。
 * 需要在系统「无障碍」里开启本服务。
 *
 * 讯飞输入法三步式流程（ime 模式，默认）：
 *   第1次按 -> 启动：打开输入窗口，讯飞输入法自动弹出
 *   第2次按 -> 模拟长按空格键（0.7 秒按压）→ 讯飞开始语音，直接说话
 *   第3次按 -> 发送：把识别出的文字发送到电脑（文字未就绪会自动等待）
 */
public class KeyTriggerService extends AccessibilityService {

    private long downAt = 0;

    private static final int S_IDLE = 0;      // 等第1步
    private static final int S_OPENED = 1;    // 第1步完成：窗口已打开，等第2步
    private static final int S_WAIT_IME = 2;  // 第2步已按：在等输入法弹出/延时
    private static final int S_SPOKEN = 3;    // 第2步完成：长按已发出，等第3步发送

    /** 找到输入法窗口后先等 1 秒（键盘动画/布局稳定）再长按 */
    private static final long PRESS_DELAY_MS = 1000;
    /** 模拟长按时长 */
    private static final long LONG_PRESS_MS = 700;

    private int holdState = S_IDLE;
    private int pollCount = 0;
    private Rect pendingRect = null;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable pollTask = this::pollIme;
    private final Runnable pendingPressTask = this::pressPendingSpace;

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

    // ---------- 三步式：①启动 ②长按空格说话 ③发送 ----------

    private void pressStep() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean screenOn = pm != null && pm.isInteractive();

        switch (holdState) {
            case S_IDLE:
                if (!screenOn) {
                    Trigger.fire(this);  // 灭屏：输入法用不了，走识别服务
                    return;
                }
                holdState = S_OPENED;
                if (VoiceInputActivity.instance == null) Trigger.openInputUi(this);
                Trigger.toast(this, "第1步/3：已启动；再按一次 = 长按空格开始说话");
                break;

            case S_OPENED:
                // 第2步：模拟长按空格
                holdState = S_WAIT_IME;
                pollCount = 0;
                Rect r = findImeBounds();
                if (r != null) {
                    pendingRect = r;
                    main.postDelayed(pendingPressTask, PRESS_DELAY_MS);
                } else {
                    Trigger.toast(this, "第2步/3：正在等输入法弹出…");
                    main.postDelayed(pollTask, 300);
                }
                break;

            case S_WAIT_IME:
                // 还在等输入法/延时中又按了一次：此刻能找到输入法就立即长按
                Rect r2 = findImeBounds();
                if (r2 != null) {
                    main.removeCallbacks(pendingPressTask);
                    pendingRect = r2;
                    pressPendingSpace();
                } else {
                    Trigger.toast(this, "输入法还没弹出，稍等片刻再按");
                }
                break;

            case S_SPOKEN:
                // 第3步：发送到电脑
                holdState = S_IDLE;
                if (VoiceInputActivity.instance != null) {
                    Trigger.toast(this, "第3步/3：发送到电脑…");
                    sendBroadcast(new Intent("com.keyvoice.COMMIT").setPackage(getPackageName()));
                } else {
                    Trigger.toast(this, "输入窗口已关闭，请重新从第1步开始");
                }
                break;
        }
    }

    private void pollIme() {
        if (holdState != S_WAIT_IME) return;
        Rect ime = findImeBounds();
        if (ime == null) {
            if (++pollCount >= 30) { // 约 9 秒还没弹出
                holdState = S_IDLE;
                Trigger.toast(this, "没等到输入法弹出，请手动长按空格说话，再按触发键发送");
                return;
            }
            main.postDelayed(pollTask, 300);
            return;
        }
        // 输入法已弹出：等 1 秒让键盘完全展开、布局稳定，再模拟长按空格
        pendingRect = ime;
        main.postDelayed(pendingPressTask, PRESS_DELAY_MS);
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

    private void pressPendingSpace() {
        if (holdState != S_WAIT_IME || pendingRect == null) return;
        Rect r = pendingRect;
        pendingRect = null;
        pressSpace(r);
    }

    /** 在输入法底部中心（空格键位置）模拟一次标准长按：按下 0.7 秒后抬起 */
    private void pressSpace(Rect ime) {
        float x = ime.centerX();
        float y = ime.bottom - Math.max(40, ime.height() / 8); // 底排中心 = 空格
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription sd =
                new GestureDescription.StrokeDescription(p, 0, LONG_PRESS_MS, false);
        boolean ok = dispatchGesture(
                new GestureDescription.Builder().addStroke(sd).build(), null, main);
        if (ok) {
            holdState = S_SPOKEN;
            Trigger.toast(this, "第2步/3：已长按空格，请说话；说完再按一次 = 发送到电脑");
        } else {
            holdState = S_IDLE;
            Trigger.toast(this, "模拟长按失败，请手动长按空格说话，再按触发键发送");
        }
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
