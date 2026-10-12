package com.keyvoice;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有设置的读写集中在这里 */
public class Config {
    private static final String P = "keyvoice";
    private final SharedPreferences sp;

    public Config(Context c) {
        sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
    }

    /** adb 模式：走 adb reverse 隧道，手机发往自己的 127.0.0.1，不需要局域网 */
    public boolean adbMode() { return sp.getBoolean("adb_mode", true); }
    public void adbMode(boolean v) { sp.edit().putBoolean("adb_mode", v).apply(); }

    public String host() { return sp.getString("host", ""); }
    public void host(String v) { sp.edit().putString("host", v).apply(); }

    public int port() { return sp.getInt("port", 17890); }
    public void port(int v) { sp.edit().putInt("port", v).apply(); }

    /** paste=有光标就上屏（默认） copy=只放剪贴板 */
    public String action() { return sp.getString("action", "paste"); }
    public void action(String v) { sp.edit().putString("action", v).apply(); }

    /** ime=用手机上装的输入法（讯飞）语音输入；sr=直接调用系统/讯飞识别服务（可灭屏） */
    public String mode() { return sp.getString("mode", "ime"); }
    public void mode(String v) { sp.edit().putString("mode", v).apply(); }

    public String srComponent() { return sp.getString("sr_component", ""); }
    public void srComponent(String v) { sp.edit().putString("sr_component", v).apply(); }

    public boolean autoSend() { return sp.getBoolean("auto_send", true); }
    public void autoSend(boolean v) { sp.edit().putBoolean("auto_send", v).apply(); }

    public int idleMs() { return sp.getInt("idle_ms", 1200); }
    public void idleMs(int v) { sp.edit().putInt("idle_ms", v).apply(); }

    public boolean autoClose() { return sp.getBoolean("auto_close", true); }
    public void autoClose(boolean v) { sp.edit().putBoolean("auto_close", v).apply(); }

    /** 触发用的物理按键 keyCode，0=未设置 */
    public int triggerKey() { return sp.getInt("trigger_key", 0); }
    public void triggerKey(int v) { sp.edit().putInt("trigger_key", v).apply(); }

    public boolean longPress() { return sp.getBoolean("long_press", false); }
    public void longPress(boolean v) { sp.edit().putBoolean("long_press", v).apply(); }

    public boolean learning() { return sp.getBoolean("learning", false); }
    public void learning(boolean v) { sp.edit().putBoolean("learning", v).apply(); }

    public boolean addNewline() { return sp.getBoolean("add_newline", false); }
    public void addNewline(boolean v) { sp.edit().putBoolean("add_newline", v).apply(); }

    /** 常驻前台服务：灭屏也能按键唤起听写 */
    public boolean resident() { return sp.getBoolean("resident", true); }
    public void resident(boolean v) { sp.edit().putBoolean("resident", v).apply(); }

    /** 连续听写：识别完自动再听，直到再次按键停止 */
    public boolean continuous() { return sp.getBoolean("continuous", false); }
    public void continuous(boolean v) { sp.edit().putBoolean("continuous", v).apply(); }

    /** 耳机/蓝牙媒体键也能触发（灭屏可用） */
    public boolean mediaKey() { return sp.getBoolean("media_key", true); }
    public void mediaKey(boolean v) { sp.edit().putBoolean("media_key", v).apply(); }

    public boolean vibrate() { return sp.getBoolean("vibrate", true); }
    public void vibrate(boolean v) { sp.edit().putBoolean("vibrate", v).apply(); }

    public boolean autoStart() { return sp.getBoolean("auto_start", false); }
    public void autoStart(boolean v) { sp.edit().putBoolean("auto_start", v).apply(); }
}
