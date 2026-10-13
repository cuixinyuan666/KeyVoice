package com.keyvoice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * 只负责一件事：在讯飞键盘的空格键位置模拟“按住不松 / 松开”。
 * 主页面「模拟长按」按钮通过 beginHoldFromUi() 调用（需开启无障碍服务以注入手势）。
 *
 * 时序：
 *   beginHoldFromUi() -> 等 1 秒让输入法弹出（每 500ms 重试最多 6 次）
 *                     -> 稳定 300ms -> 在空格位置“按住不松”（持续续段，全程压住）
 *   endHold()  -> 松开空格 -> 等讯飞出字 -> 发 COMMIT 广播（主页面/输入窗口收到后发送到电脑）
 *
 * 物理按键触发与遮蔽模式已按需求移除（v0.2.0）。
 */
public class KeyTriggerService extends AccessibilityService {

    private static final int S_IDLE = 0;      // 空闲
    private static final int S_PENDING = 1;   // 在等输入法弹出/延时
    private static final int S_HOLDING = 2;   // 正在“按住”空格（录音中）

    /** 开始前先等 1 秒，让输入法弹出 */
    private static final long WAIT_IME_MS = 1000;
    /** 输入法没弹出时的重试间隔与次数 */
    private static final long RETRY_MS = 500;
    private static final int MAX_RETRY = 6;
    /** 最长按住 55 秒，防止虚拟指针卡死 */
    private static final long HOLD_TIMEOUT_MS = 55_000;
    /** 松开时若手势还没被系统确认，最多重试次数（约 3 秒），超时就强制复位，避免 holdState 卡死导致「下次长按失效」 */
    private static final int MAX_RELEASE_TRIES = 20;

    /** 供 UI 查询：是否正在“按住空格”录音中 */
    public static volatile boolean holding = false;

    public static KeyTriggerService instance;

    private int holdState = S_IDLE;
    private Rect pendingRect = null;
    private int imeRetries = 0;
    private int releaseTries = 0;

    private GestureDescription.StrokeDescription holdStroke;
    private boolean holdReady = false;
    private float holdX, holdY;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable waitTask = this::waitImeAndPress;
    private final Runnable pendingPressTask = this::pressPendingSpace;
    private final Runnable holdTimeoutTask = this::holdTimeout;

    @Override
    public void onServiceConnected() {
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        holding = false;
        instance = null;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------- 主页面入口 ----------

    /**
     * 主页面「模拟长按」按下：等 1 秒让输入法弹出，然后在空格位置按住不松。
     * 已在按住中时返回 false。
     */
    public boolean beginHoldFromUi() {
        if (holdState != S_IDLE) return false;
        holdState = S_PENDING;
        holdReady = false;      // 新一轮：先置为未就绪，等新手势确认
        releaseTries = 0;
        imeRetries = 0;
        Trigger.toast(this, "等待输入法弹出…");
        main.postDelayed(waitTask, WAIT_IME_MS);
        return true;
    }

    /** 正在按住中则立即松开；仍在等待输入法弹出则直接取消（UI 二次点击 / 超时兜底） */
    public void endHold() {
        if (holdState == S_HOLDING) {
            releaseAndSend();
        } else if (holdState == S_PENDING) {
            main.removeCallbacks(waitTask);
            main.removeCallbacks(pendingPressTask);
            holdState = S_IDLE;
            holdReady = false;
            Trigger.toast(this, "已取消本次长按");
        }
    }

    // ---------- 按住 / 松开 ----------

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
            Trigger.toast(this, "已按住空格：请说话；点「模拟长按」松开");
            main.postDelayed(holdTimeoutTask, HOLD_TIMEOUT_MS);
        } else {
            holdStroke = null;
            holdState = S_IDLE;
            Trigger.toast(this, "模拟手势失败，请确认无障碍服务已开启、讯飞键盘已弹出");
        }
    }

    private void holdTimeout() {
        if (holdState == S_HOLDING) releaseAndSend();
    }

    /** 松开空格 -> 等讯飞把文字写进输入框 -> 发 COMMIT 广播（由 UI 决定怎么发送） */
    private void releaseAndSend() {
        main.removeCallbacks(holdTimeoutTask);
        holding = false;
        if (holdState == S_HOLDING && holdStroke != null) {
            try {
                if (!holdReady && releaseTries < MAX_RELEASE_TRIES) {
                    // 当前段还没被系统确认完，稍等再松开（重试次数封顶，超时就强制复位）
                    releaseTries++;
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
        // 无论上面是否成功，都强制复位状态，防止 holdState 卡在 S_HOLDING 导致下次长按失效
        holdStroke = null;
        holdState = S_IDLE;
        releaseTries = 0;
        Trigger.toast(this, "已松开，等识别出字后自动发送…");
        // 给讯飞留出把文字写进输入框的时间；UI 收到 COMMIT 后自行发送
        main.postDelayed(() -> sendBroadcast(
                new Intent("com.keyvoice.COMMIT").setPackage(getPackageName())), 1600);
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
