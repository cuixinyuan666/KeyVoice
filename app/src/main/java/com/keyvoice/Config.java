package com.keyvoice;

import android.content.Context;
import android.content.SharedPreferences;

/** 所有设置的读写集中在这里 */
public class Config {
    private static final String P = "keyvoice";
    private static final int DEFAULTS_VER = 2;   // v2：局域网默认 / 讯飞输入法默认 / 音量减默认触发键
    private final SharedPreferences sp;

    public Config(Context c) {
        sp = c.getSharedPreferences(P, Context.MODE_PRIVATE);
        // 升级迁移：旧版本装过的设备也强制套用新默认值（只跑一次）
        if (sp.getInt("defaults_ver", 0) < DEFAULTS_VER) {
            sp.edit()
                    .putBoolean("adb_mode", false)      // 默认局域网 WiFi
                    .putString("mode", "ime")           // 默认讯飞输入法语音
                    .putInt("trigger_key", 25)          // 默认音量减键
                    .putBoolean("block_key", true)      // 屏蔽触发键原功能
                    .putBoolean("ime_hold", true)       // 开启三步式模拟空格
                    .putInt("defaults_ver", DEFAULTS_VER)
                    .apply();
        }
    }

    /** adb 模式：走 adb reverse 隧道。默认关闭 → 默认走局域网 WiFi 并自动扫描 */
    public boolean adbMode() { return sp.getBoolean("adb_mode", false); }
    public void adbMode(boolean v) { sp.edit().putBoolean("adb_mode", v).apply(); }

    public String host() { return sp.getString("host", ""); }
    public void host(String v) { sp.edit().putString("host", v).apply(); }

    public int port() { return sp.getInt("port", 17890); }
    public void port(int v) { sp.edit().putInt("port", v).apply(); }

    /** paste=有光标就上屏（默认） copy=只放剪贴板 */
    public String action() { return sp.getString("action", "paste"); }
    public void action(String v) { sp.edit().putString("action", v).apply(); }

    /** ime=用手机上装的输入法（讯飞）语音输入（默认）；sr=直接调用系统/讯飞识别服务（可灭屏） */
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

    /** 触发用的物理按键 keyCode，默认音量减键（KEYCODE_VOLUME_DOWN=25），0=未设置 */
    public int triggerKey() { return sp.getInt("trigger_key", 25); }
    public void triggerKey(int v) { sp.edit().putInt("trigger_key", v).apply(); }

    /** 触发键列表（逗号分隔）。默认 [音量减]；可学习追加（如 vivo 侧边 AI 键），多键同时生效、同时屏蔽原功能 */
    public java.util.List<Integer> triggerKeys() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        String s = sp.getString("trigger_keys", "");
        if (!s.isEmpty()) {
            for (String p : s.split(",")) {
                try {
                    int v = Integer.parseInt(p.trim());
                    if (v != 0 && !out.contains(v)) out.add(v);
                } catch (Exception ignored) {
                }
            }
        }
        if (out.isEmpty()) out.add(triggerKey() != 0 ? triggerKey() : 25);
        return out;
    }

    public void setTriggerKeys(java.util.List<Integer> keys) {
        StringBuilder sb = new StringBuilder();
        for (int k : keys) {
            if (sb.length() > 0) sb.append(',');
            sb.append(k);
        }
        sp.edit().putString("trigger_keys", sb.toString())
                .putInt("trigger_key", keys.isEmpty() ? 25 : keys.get(0))
                .apply();
    }

    /** 追加一个触发键（已存在则不变） */
    public void addTriggerKey(int code) {
        java.util.List<Integer> ks = triggerKeys();
        if (!ks.contains(code)) ks.add(code);
        setTriggerKeys(ks);
    }

    /** 重置为默认：仅音量减键 */
    public void resetTriggerKeys() {
        setTriggerKeys(new java.util.ArrayList<>(java.util.Collections.singletonList(25)));
    }

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

    /** 屏蔽触发键的原功能（如音量键不再调音量），只在 App 运行时对绑定键生效 */
    public boolean blockKey() { return sp.getBoolean("block_key", true); }
    public void blockKey(boolean v) { sp.edit().putBoolean("block_key", v).apply(); }

    /** 听写时点亮屏幕（dim），让息屏触发也能正常识别；停止后自动灭屏 */
    public boolean keepScreen() { return sp.getBoolean("keep_screen", true); }
    public void keepScreen(boolean v) { sp.edit().putBoolean("keep_screen", v).apply(); }

    /** ime 模式下模拟“按住空格”：第一次按物理键=按住讯飞空格开始语音，再按一次=松开并自动发送 */
    public boolean imeHold() { return sp.getBoolean("ime_hold", true); }
    public void imeHold(boolean v) { sp.edit().putBoolean("ime_hold", v).apply(); }

    /** 首次启动的权限引导是否已经完成（完成一次后不再自动弹） */
    public boolean onboarded() { return sp.getBoolean("onboarded", false); }
    public void onboarded(boolean v) { sp.edit().putBoolean("onboarded", v).apply(); }

    /** 主页面三个按钮的布局：JSON {"s":[fx,fy,scale],"h":[...],"e":[...]}（归一化坐标） */
    public String layoutJson() { return sp.getString("layout", ""); }
    public void layoutJson(String v) { sp.edit().putString("layout", v).apply(); }

    // ---------------------------------------------------------------- 输入框外观
    // 均写入 SharedPreferences，App 关闭后依然保留（apply() 为异步落盘，进程被杀也不丢）

    /** 输入框文字颜色（ARGB），0 表示「跟随主题」；默认跟随主题 */
    public int inputTextColor() { return sp.getInt("text_color", 0); }
    public void textColor(int v) { sp.edit().putInt("text_color", v).apply(); }

    /** 输入框字号（sp），默认 15 */
    public int fontSize() { return sp.getInt("font_size", 15); }
    public void fontSize(int v) { sp.edit().putInt("font_size", v).apply(); }

    /** 输入框字体：sans / serif / monospace */
    public String fontFamily() { return sp.getString("font_family", "sans"); }
    public void fontFamily(String v) { sp.edit().putString("font_family", v).apply(); }

    // ---------------------------------------------------------------- 主题
    /** dark（默认）/ light */
    public String theme() { return sp.getString("theme", "dark"); }
    public void theme(String v) { sp.edit().putString("theme", v).apply(); }

    // ---------------------------------------------------------------- 设备标识
    /** 设备名默认值：出厂型号（如 "V1938CT"）；取不到时兜底 "Android" */
    public static String defaultDeviceName() {
        String m = android.os.Build.MODEL;
        if (m == null) m = "";
        m = m.trim();
        return m.isEmpty() ? "Android" : m;
    }

    /**
     * 用户自定义的设备名；空串表示「用默认名」。
     * <p>
     * 这个名字会出现在两个地方：① 局域网发现应答（别的设备看到的名字）；
     * ② 主页面 / 设置页顶部的「本机名称」。
     */
    public String deviceName() { return sp.getString("device_name", ""); }
    public void deviceName(String v) {
        sp.edit().putString("device_name", v == null ? "" : v.trim()).apply();
    }

    /** 对外统一入口：没自定义就用默认名，保证永不为空 */
    public String effDeviceName() {
        String s = deviceName();
        if (s == null || s.trim().isEmpty()) return defaultDeviceName();
        return s.trim();
    }

    // ---------------------------------------------------------------- 输入框布局
    // 输入框也可长按调节：位移（translationX/Y，像素）+ 缩放 + 上下颠倒。
    // 一并写入 SharedPreferences，App 关闭后依然保留。

    /**
     * 是否强制「倒置显示」（reversePortrait）。
     * <p>
     * 默认 false：App 跟随系统方向，倒置手机时系统返回键/Home 等基础控件
     * 保持在原位，不会被搬到屏幕顶部。
     * 设为 true 时 App 内容会旋转 180°，需要把手机倒过来拿才是正的。
     */
    public boolean invertMode() { return sp.getBoolean("invert_mode", false); }
    public void invertMode(boolean v) { sp.edit().putBoolean("invert_mode", v).apply(); }

    public float inputTx() { return sp.getFloat("input_tx", 0f); }
    public void inputTx(float v) { sp.edit().putFloat("input_tx", v).apply(); }

    public float inputTy() { return sp.getFloat("input_ty", 0f); }
    public void inputTy(float v) { sp.edit().putFloat("input_ty", v).apply(); }

    public float inputScale() { return sp.getFloat("input_scale", 1f); }
    public void inputScale(float v) { sp.edit().putFloat("input_scale", v).apply(); }

    /** 输入框是否上下颠倒（旋转 180°） */
    public boolean inputFlip() { return sp.getBoolean("input_flip", false); }
    public void inputFlip(boolean v) { sp.edit().putBoolean("input_flip", v).apply(); }

    // ---------------------------------------------------------------- 聊天面板布局
    // PC 发来的消息显示在聊天面板里；面板可拖动/缩放/翻转，布局持久化。

    public float chatTx() { return sp.getFloat("chat_tx", 0f); }
    public void chatTx(float v) { sp.edit().putFloat("chat_tx", v).apply(); }

    public float chatTy() { return sp.getFloat("chat_ty", 0f); }
    public void chatTy(float v) { sp.edit().putFloat("chat_ty", v).apply(); }

    public float chatScale() { return sp.getFloat("chat_scale", 1f); }
    public void chatScale(float v) { sp.edit().putFloat("chat_scale", v).apply(); }

    /** 聊天面板是否上下颠倒（旋转 180°） */
    public boolean chatFlip() { return sp.getBoolean("chat_flip", false); }
    public void chatFlip(boolean v) { sp.edit().putBoolean("chat_flip", v).apply(); }
    public boolean chatCollapsed() { return sp.getBoolean("chat_collapsed", false); }
    public void chatCollapsed(boolean v) { sp.edit().putBoolean("chat_collapsed", v).apply(); }

    /** 跨页面「恢复默认布局」请求标记：设置页点按钮后置位，主页面 onResume 时消费 */
    public boolean layoutResetRequested() { return sp.getBoolean("layout_reset_req", false); }
    public void layoutResetRequested(boolean v) { sp.edit().putBoolean("layout_reset_req", v).apply(); }
}
