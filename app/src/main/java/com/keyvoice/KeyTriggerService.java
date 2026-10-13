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
 * 捕获实体按键（自定义键 / 音量键 / 侧键 / 扫描键 / 耳机键 …）
 * 需要在系统「无障碍」里开启本服务。开启后用 App 里的"学习按键"绑定你想用的那个键。
 *
 * ime 模式新增“模拟空格”流程（需要讯飞输入法空格长按=语音）：
 *   第一次按物理键 -> 打开输入窗口，找到输入法窗口，在空格键位置模拟“按住不松”；
 *   第二次按物理键 -> 模拟“松开空格”，等讯飞把文字写进输入框后自动发送到电脑。
 */
public class KeyTriggerService extends AccessibilityService {

    private long downAt = 0;

    // 模拟按住空格的状态机
    private static final int S_IDLE = 0;      // 空闲
    private static final int S_WAIT_IME = 1;  // 已开输入窗口，正在等输入法弹出
    private static final int S_HOLDING = 2;   // 正在“按住”空格（录音中）

    private int holdState = S_IDLE;
    private GestureDescription.StrokeDescription holdStroke;
    private boolean holdReady = false;   // 第一段手势是否已被系统接受完成
    private float holdX, holdY;
    private int pollCount = 0;

    /** 输入法窗口找到后，先等 1 秒（等键盘动画/布局稳定）再按住空格 */
    private static final long PRESS_DELAY_MS = 1000;
    private Rect pendingRect = null;
    private final Runnable pendingPressTask = this::pressPendingSpace;

    private final Handler main = new Handler(Looper.getMainLooper());

    private final Runnable holdTimeoutTask = this::holdTimeout;
    private final Runnable pollTask = this::pollIme;

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
                    pressIme(cfg);           // 模拟按住/松开空格流程
                    return cfg.blockKey();
                }
                if (!cfg.longPress()) fire(); // 短按：按下即触发
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (cfg.longPress() && System.currentTimeMillis() - downAt >= 500) fire();
            }
            // 吞掉该键原功能（音量键不再调音量等）；blockKey 关掉则放行原功能只触发
            return cfg.blockKey();
        }
        return super.onKeyEvent(event);
    }

    /** 窗口已开着时，再按一次 = 立即发送（非 ime 模拟流程用） */
    private void fire() {
        if (VoiceInputActivity.instance != null) {
            sendBroadcast(new Intent("com.keyvoice.COMMIT").setPackage(getPackageName()));
        } else {
            Trigger.fire(this);
        }
    }

    // ---------- 模拟“按住空格” ----------

    /** 输入窗口已经开着：直接等输入法弹出并按住空格 */
    private void startHold() {
        holdState = S_WAIT_IME;
        pollCount = 0;
        main.postDelayed(pollTask, 300);
    }

    private void pressIme(Config cfg) {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean screenOn = pm != null && pm.isInteractive();

        switch (holdState) {
            case S_IDLE:
                if (!screenOn) {
                    Trigger.fire(this);  // 灭屏：走识别服务
                    return;
                }
                if (VoiceInputActivity.instance != null) {
                    // 窗口已经开着（比如上次没发完）：直接补“按住空格”
                    startHold();
                } else {
                    holdState = S_WAIT_IME;
                    pollCount = 0;
                    Trigger.fire(this);              // 打开输入窗口并弹输入法
                    Trigger.toast(this, "正在调出输入法…");
                    main.postDelayed(pollTask, 400);
                }
                break;

            case S_WAIT_IME:
                if (pendingRect != null) {
                    // 正在等 1 秒后按住空格：这次按键忽略，避免把即将开始的按住打断
                    Trigger.toast(this, "即将按住空格，请说话；说完再按一次触发键发送");
                    break;
                }
                // 输入法还没等到就又按了一次：放弃模拟，交给手动
                holdState = S_IDLE;
                main.removeCallbacks(pollTask);
                Trigger.toast(this, "输入法未就绪，请手动长按空格说话，再按触发键发送");
                break;

            case S_HOLDING:
                releaseHoldAndCommit();
                break;
        }
    }

    private void pollIme() {
        if (holdState != S_WAIT_IME) return;
        Rect ime = findImeBounds();
        if (ime == null) {
            if (++pollCount >= 30) { // 约 6 秒还没弹出
                holdState = S_IDLE;
                Trigger.toast(this, "没等到输入法弹出，请手动长按空格说话");
                return;
            }
            main.postDelayed(pollTask, 200);
            return;
        }
        // 输入法已弹出：等 1 秒让键盘完全展开、布局稳定，再按住空格
        pendingRect = ime;
        main.postDelayed(pendingPressTask, PRESS_DELAY_MS);
    }

    private void pressPendingSpace() {
        if (holdState != S_WAIT_IME || pendingRect == null) return;
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

    /** 在输入法底部中心（空格键）开始“按住不松” */
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
            Trigger.toast(this, "已按住空格：开始说话；说完再按一次触发键 = 松开并发送");
            main.postDelayed(holdTimeoutTask, 55_000); // 最长按住 55 秒，防指针卡死
        } else {
            holdStroke = null;
            holdState = S_IDLE;
            Trigger.toast(this, "模拟手势失败，请手动长按空格说话");
        }
    }

    private void holdTimeout() {
        if (holdState == S_HOLDING) releaseHoldAndCommit();
    }

    /** 松开空格 -> 等讯飞把文字写进输入框 -> 自动发送 */
    private void releaseHoldAndCommit() {
        main.removeCallbacks(holdTimeoutTask);
        if (holdState == S_HOLDING && holdStroke != null) {
            try {
                if (!holdReady) {
                    // 第一段还没被系统确认完，稍等再松开
                    main.postDelayed(this::releaseHoldAndCommit, 150);
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
