package com.keyvoice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 捕获实体按键（默认音量减键，可用「学习按键」改绑）。
 * 需要在系统「无障碍」里开启本服务。
 *
 * 讯飞输入法两步式流程（ime 模式，默认）：
 *   第1次按 -> 等 1 秒让输入法弹出，然后在空格键位置模拟“按住不松”，直接说话
 *   第2次按 -> 模拟“松开空格” -> 等讯飞出字后自动发送到电脑
 *
 * 关键细节：
 *   - 忽略按键自动重复（按住触发键不放时系统连发 ACTION_DOWN，会误触发“松开”）
 *   - “按住”用持续续段手势：第一段 120ms 完成后立刻续上 60s 长段，
 *     保证虚拟手指全程有效压在空格上，语音不会中途被打断
 */
public class KeyTriggerService extends AccessibilityService {

    private long downAt = 0;

    private static final int S_IDLE = 0;      // 等第1次按
    private static final int S_PENDING = 1;   // 在等输入法弹出/延时
    private static final int S_HOLDING = 2;   // 正在“按住”空格（录音中）

    /** 第1次按下后先等 1 秒，让输入法弹出 */
    private static final long WAIT_IME_MS = 1000;
    /** 输入法没弹出时的重试间隔与次数 */
    private static final long RETRY_MS = 500;
    private static final int MAX_RETRY = 6;
    /** 最长按住 55 秒，防止虚拟指针卡死 */
    private static final long HOLD_TIMEOUT_MS = 55_000;

    /** 供输入窗口查询：是否正在“按住空格”录音中（期间禁止自动发送打断） */
    public static volatile boolean holding = false;

    public static KeyTriggerService instance;
    private View shieldView;
    private float twoFingerStartY = 0f;

    @Override
    public void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hideShield();
        instance = null;
        return super.onUnbind(intent);
    }

    private int holdState = S_IDLE;
    private Rect pendingRect = null;
    private int imeRetries = 0;

    private GestureDescription.StrokeDescription holdStroke;
    private boolean holdReady = false;
    private float holdX, holdY;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable waitTask = this::waitImeAndPress;
    private final Runnable pendingPressTask = this::pressPendingSpace;
    private final Runnable holdTimeoutTask = this::holdTimeout;

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        Config cfg = new Config(this);
        int code = event.getKeyCode();

        // 学习模式：按下的物理键追加为触发键（可绑定多个：音量减 + vivo 侧边键同时生效）
        if (cfg.learning()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                cfg.addTriggerKey(code);
                Trigger.toast(this, "已绑定按键：" + KeyEvent.keyCodeToString(code)
                        + "（追加模式，可与其它触发键同时生效）");
            }
            return true;   // 学习期间也吞掉，防止误触
        }

        // 忽略按键自动重复（长按触发键不松时系统会连发，曾导致“按住”被误松开）
        if (event.getRepeatCount() > 0) {
            return cfg.blockKey();
        }

        // 命中任一已绑定触发键（音量减 / vivo 侧边键等，多键同时有效）
        if (cfg.triggerKeys().contains(code)) {
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
                // 第1次按：先等 1 秒让输入法弹出
                holdState = S_PENDING;
                imeRetries = 0;
                Trigger.toast(this, "等待输入法弹出…");
                main.postDelayed(waitTask, WAIT_IME_MS);
                break;

            case S_PENDING:
                // 还在等输入法就又按了一次：立即用当前能拿到的位置按住
                main.removeCallbacks(waitTask);
                main.removeCallbacks(pendingPressTask);
                Rect r = (pendingRect != null) ? pendingRect : findImeBounds();
                if (r == null) r = fallbackKeyboardRect();
                pendingRect = null;
                pressSpaceHold(r);
                break;

            case S_HOLDING:
                // 最后一次点击：松开空格 + 发送
                releaseAndSend();
                break;
        }
    }

    /** 等待输入法弹出：每 500ms 查一次键盘窗口，最多 6 次，找到后稍作稳定即按住 */
    private void waitImeAndPress() {
        if (holdState != S_PENDING) return;
        Rect r = findImeBounds();
        if (r == null) {
            if (++imeRetries <= MAX_RETRY) {
                main.postDelayed(waitTask, RETRY_MS);
                return;
            }
            r = fallbackKeyboardRect(); // 实在等不到就用屏幕底部中心兜底
        }
        pendingRect = r;
        main.postDelayed(pendingPressTask, 300); // 稳定 300ms 再按
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

    /** 在空格键位置开始“按住不松”：第一段 120ms 完成后立刻续 60s 长段，全程压住 */
    private void pressSpaceHold(Rect ime) {
        holdX = ime.centerX();
        holdY = ime.bottom - Math.max(24, ime.height() / 14); // 底排中心偏下 = 空格
        Path p = new Path();
        p.moveTo(holdX, holdY);

        // 第一段：按下
        GestureDescription.StrokeDescription seg1 =
                new GestureDescription.StrokeDescription(p, 0, 120, true);
        boolean ok = dispatchGesture(
                new GestureDescription.Builder().addStroke(seg1).build(),
                new GestureResultCallback() {
                    @Override public void onCompleted(GestureDescription g) {
                        // 立刻续上长段，保证手指一直“按着”（否则悬空状态可能被系统取消，语音被打断）
                        try {
                            Path p2 = new Path();
                            p2.moveTo(holdX, holdY);
                            GestureDescription.StrokeDescription seg2 =
                                    seg1.continueStroke(p2, 0, HOLD_TIMEOUT_MS + 5000, true);
                            boolean ok2 = dispatchGesture(
                                    new GestureDescription.Builder().addStroke(seg2).build(),
                                    new GestureResultCallback() {
                                        @Override public void onCompleted(GestureDescription gg) {
                                            holdReady = true;
                                            holdStroke = seg2;
                                        }
                                        @Override public void onCancelled(GestureDescription gg) {
                                            holdReady = false;
                                        }
                                    }, main);
                            if (ok2) holdStroke = seg2;
                        } catch (Exception ignored) {
                        }
                    }
                    @Override public void onCancelled(GestureDescription g) {
                        holdReady = false;
                    }
                }, main);

        if (ok) {
            holdStroke = seg1;
            holdState = S_HOLDING;
            holding = true;
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
        holding = false;
        if (holdState == S_HOLDING && holdStroke != null) {
            try {
                if (!holdReady) {
                    // 当前段还没被系统确认完，稍等再松开
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
        holding = false;
        hideShield();
        instance = null;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------- 遮蔽听写模式：盖住键盘以上区域防误碰，双指下滑退出 ----------

    private int shieldWaitTries = 0;
    private final Runnable shieldWaitTask = this::shieldWait;

    /**
     * App 主界面按钮入口：打开输入窗口 -> 强制聚焦输入框并弹输入法 ->
     * **确认输入法真正弹出后**才盖遮罩（保证遮蔽前输入栏已就绪可听写）
     */
    void startShieldMode() {
        Trigger.openInputUi(this, true);   // force_ime：聚焦输入框并触发输入法
        hideShield();
        shieldWaitTries = 0;
        Trigger.toast(this, "正在打开听写窗口，等待输入法就绪…");
        main.postDelayed(shieldWaitTask, 700);
    }

    /** 每 400ms 查一次输入法窗口；弹出后稳定 500ms 再盖遮罩；6 秒等不到则按默认位置盖 */
    private void shieldWait() {
        if (shieldView != null || VoiceInputActivity.instance == null) return;
        Rect ime = findImeBounds();
        if (ime != null) {
            main.postDelayed(this::showShield, 500);   // 输入法已就绪，稳定一下再盖
            return;
        }
        if (++shieldWaitTries <= 15) {
            main.postDelayed(shieldWaitTask, 400);
            return;
        }
        Trigger.toast(this, "没等到输入法弹出，遮罩按默认位置盖上（键盘区域仍留空）");
        showShield();
    }

    private void showShield() {
        if (shieldView != null) return;
        try {
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            android.graphics.Point size = new android.graphics.Point();
            wm.getDefaultDisplay().getRealSize(size);
            // 键盘区域必须留出来：模拟长按空格的手势要落在键盘上
            Rect ime = findImeBounds();
            int coverBottom;
            if (ime != null && ime.top > size.y / 4) {
                coverBottom = ime.top;
            } else {
                coverBottom = (int) (size.y * 0.55f); // 没找到键盘就盖上面 55%
            }

            FrameLayout wrap = new FrameLayout(this);
            TextView hint = new TextView(this);
            hint.setText("已遮蔽防误碰（键盘区域除外）\n物理键听写照常可用\n\n双指下滑退出");
            hint.setTextColor(0xFFFFFFFF);
            hint.setTextSize(15);
            hint.setGravity(Gravity.CENTER);
            wrap.addView(hint, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            wrap.setBackgroundColor(0x88000000);
            wrap.setOnTouchListener(this::onShieldTouch);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, coverBottom,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP;
            wm.addView(wrap, lp);
            shieldView = wrap;
            Trigger.toast(this, "已进入遮蔽模式：双指下滑退出");
        } catch (Exception e) {
            Trigger.toast(this, "遮罩启动失败：" + e.getMessage());
        }
    }

    /** 遮罩触摸处理：吞掉一切触摸防误碰；识别到双指下滑 140px 则退出 */
    private boolean onShieldTouch(View v, MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_POINTER_DOWN:
                if (ev.getPointerCount() >= 2) {
                    twoFingerStartY = (ev.getY(0) + ev.getY(1)) / 2f;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() >= 2 && twoFingerStartY > 0) {
                    float dy = (ev.getY(0) + ev.getY(1)) / 2f - twoFingerStartY;
                    if (dy > 140) {
                        hideShield();
                        Trigger.toast(this, "已退出遮蔽模式");
                        return true;
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (ev.getPointerCount() < 2) twoFingerStartY = 0f;
                break;
        }
        return true; // 吞掉所有触摸
    }

    void hideShield() {
        if (shieldView != null) {
            try {
                WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                wm.removeView(shieldView);
            } catch (Exception ignored) {
            }
            shieldView = null;
        }
        twoFingerStartY = 0f;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
