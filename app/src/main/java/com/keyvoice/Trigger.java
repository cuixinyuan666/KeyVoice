package com.keyvoice;

import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.widget.Toast;

/** 统一的"触发"入口：决定是弹输入法窗口，还是灭屏直接听写 */
public class Trigger {

    public static void fire(Context c) {
        Config cfg = new Config(c);
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean screenOn = pm != null && pm.isInteractive();

        // 灭屏 -> 输入法用不了，走无界面的识别服务
        if (!screenOn) {
            VoiceService.startListening(c);
            return;
        }

        if ("sr".equals(cfg.mode())) VoiceService.startListening(c);
        else openInputUi(c);
    }

    public static void openInputUi(Context c) {
        Intent i = new Intent(c, VoiceInputActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        c.startActivity(i);
    }

    /**
     * 统一提示入口。
     *
     * 优先投递到主页面 overlay（属于 reversePortrait 的 Activity，随 Activity 旋转，
     * 文字方向与位置都正确）；只有主页面不在前台时才回退成 Toast。
     *
     * 背景：直接用 {@code Toast.makeText(getApplicationContext(), ...)} 弹的是
     * 【系统窗口】，按设备物理方向渲染。主页面是 reversePortrait（旋转 180°），
     * 倒持手机时 Toast 会表现为文字方向错误、位置漂到侧边，因此 App 内提示
     * 一律走 Activity 的 overlay。
     */
    public static void toast(Context c, String s) {
        if (MainActivity.postTip(s)) return;
        try {
            Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }
}
