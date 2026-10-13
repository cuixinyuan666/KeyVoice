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
        openInputUi(c, false);
    }

    /** forceIme=true：即使识别服务模式也聚焦输入框并弹输入法（遮蔽听写模式用） */
    public static void openInputUi(Context c, boolean forceIme) {
        Intent i = new Intent(c, VoiceInputActivity.class);
        i.putExtra("force_ime", forceIme);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        c.startActivity(i);
    }

    public static void toast(Context c, String s) {
        try {
            Toast.makeText(c.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }
}
