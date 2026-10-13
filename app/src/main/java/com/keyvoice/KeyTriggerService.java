package com.keyvoice;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 捕获实体按键（自定义键 / 音量键 / 侧键 / 扫描键 / 耳机键 …）
 * 需要在系统「无障碍」里开启本服务。开启后用 App 里的"学习按键"绑定你想用的那个键。
 */
public class KeyTriggerService extends AccessibilityService {

    private long downAt = 0;

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
                if (!cfg.longPress()) fire();     // 短按：按下即触发
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                if (cfg.longPress() && System.currentTimeMillis() - downAt >= 500) fire();
            }
            // 吞掉该键原功能（音量键不再调音量等）；blockKey 关掉则放行原功能只触发
            return cfg.blockKey();
        }
        return super.onKeyEvent(event);
    }

    /** 窗口已开着时，再按一次 = 立即发送 */
    private void fire() {
        if (VoiceInputActivity.instance != null) {
            sendBroadcast(new Intent("com.keyvoice.COMMIT").setPackage(getPackageName()));
        } else {
            Trigger.fire(this);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
