package com.keyvoice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.RecognitionService;
import android.text.TextUtils;
import android.view.ViewGroup;
import android.content.res.ColorStateList;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页（v0.3.0）：沿用 v0.2.0 的全部配置，改用统一深色设计系统（Ui）。
 * 物理按键触发与遮蔽模式已移除；「模拟长按」手势依赖无障碍服务，在这里开启。
 */
public class SettingsActivity extends Activity {

    private Config cfg;
    private EditText etHost, etPort;
    private EditText etDeviceName;
    private TextView tvInfo, tvAdbHint, tvLanInfo;
    private Spinner spEngine, spAction;
    private Spinner spTheme, spTextColor, spFont, spFontSize;
    private RadioGroup rgMode, rgTrans;
    private Button btnScan;
    private CheckBox cbAutoClose, cbNewline;
    private CheckBox cbResident, cbContinuous, cbMedia, cbVibrate, cbAutoStart;
    private CheckBox cbKeepScreen;
    private CheckBox cbInvert;

    private final List<String> engineLabels = new ArrayList<>();
    private final List<String> engineComps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new Config(this);
        // 主题：必须在构建任何视图之前设置（理由同 MainActivity）。
        // 本页有大量系统控件（Spinner 下拉、AlertDialog、CheckBox/RadioButton 着色），
        // 少了 setTheme() 就只有深色一套，切换到浅色主题时这些控件仍然是深色。
        setTheme(Ui.themeRes("light".equalsIgnoreCase(cfg.theme())));
        Ui.setTheme(cfg.theme());
        // 方向：只允许竖屏/翻转竖屏，永不横屏（与主页面一致）
        setRequestedOrientation(cfg.invertMode()
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = Ui.rootScroll(this);
        sv.addView(root);
        setContentView(sv);

        root.addView(Ui.appBar(this, "KeyVoice · 设置"));

        root.addView(buildDeviceCard());

        // ---- 传输方式 ----
        LinearLayout c1 = Ui.sectionCard(this);
        c1.addView(Ui.sectionTitle(this, "传到 Windows 10 的方式"));
        rgTrans = new RadioGroup(this);
        RadioButton rbAdb = new RadioButton(this); rbAdb.setId(10); rbAdb.setText("adb（USB 线，不需要局域网）");
        RadioButton rbLan = new RadioButton(this); rbLan.setId(11); rbLan.setText("局域网 WiFi");
        styleRadio(rbAdb); styleRadio(rbLan);
        rgTrans.addView(rbAdb); rgTrans.addView(rbLan);
        rgTrans.check(cfg.adbMode() ? 10 : 11);
        c1.addView(rgTrans);
        tvAdbHint = Ui.body(this, "");
        tvAdbHint.setTextColor(Ui.warning(this));
        c1.addView(tvAdbHint);
        rgTrans.setOnCheckedChangeListener((g, id) -> applyTransport());
        root.addView(c1);

        // ---- 设备地址 ----
        LinearLayout c2 = Ui.sectionCard(this);
        c2.addView(Ui.sectionTitle(this, "设备地址"));
        etHost = Ui.inputField(this, "设备局域网 IP，如 192.168.1.5（仅局域网模式用）");
        etHost.setText(cfg.host());
        etHost.setSingleLine(true);
        c2.addView(etHost);
        etPort = Ui.inputField(this, "端口");
        etPort.setText(String.valueOf(cfg.port()));
        etPort.setSingleLine(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = Ui.dp(this, 8);
        etPort.setLayoutParams(plp);
        c2.addView(etPort);
        LinearLayout row1 = Ui.row(this);
        Button bTest = Ui.ghostButton(this, "测试发送"); bTest.setOnClickListener(v -> testSend());
        addWeighted(row1, bTest);
        c2.addView(row1);
        root.addView(c2);

        // ---- 语音来源 ----
        LinearLayout c3 = Ui.sectionCard(this);
        c3.addView(Ui.sectionTitle(this, "语音来源"));
        rgMode = new RadioGroup(this);
        RadioButton rbIme = new RadioButton(this); rbIme.setId(1);
        rbIme.setText("用手机装的输入法（讯飞）语音输入：主页面「模拟长按」=按住说话，「电脑上屏」=松开并发送");
        RadioButton rbSr = new RadioButton(this); rbSr.setId(2);
        rbSr.setText("直接调用识别服务（通知栏/媒体键触发，可灭屏）");
        styleRadio(rbIme); styleRadio(rbSr);
        rgMode.addView(rbIme); rgMode.addView(rbSr);
        rgMode.check("sr".equals(cfg.mode()) ? 2 : 1);
        c3.addView(rgMode);
        spEngine = new Spinner(this);
        styleSpinner(spEngine);
        c3.addView(spEngine);
        c3.addView(Ui.caption(this, "识别引擎默认自动选「讯飞（讯飞）」；若手机没暴露讯飞识别服务，则回退系统默认。"));
        loadEngines();
        root.addView(c3);

        // ---- 电脑端动作 ----
        LinearLayout c4 = Ui.sectionCard(this);
        c4.addView(Ui.sectionTitle(this, "电脑端收到后"));
        spAction = new Spinner(this);
        styleSpinner(spAction);
        String[] acts = {"有输入光标就上屏，没光标就存剪贴板", "只显示在悬浮框/存入剪贴板，不上屏"};
        spAction.setAdapter(Ui.spinnerAdapter(this, acts));
        spAction.setSelection("copy".equals(cfg.action()) ? 1 : 0);
        c4.addView(spAction);
        root.addView(c4);

        // ---- 权限 ----
        LinearLayout c5 = Ui.sectionCard(this);
        c5.addView(Ui.sectionTitle(this, "权限（「模拟长按」依赖无障碍服务）"));
        Button bA11y = Ui.ghostButton(this, "打开系统无障碍设置（手势注入必开）");
        bA11y.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        c5.addView(bA11y);
        Button bWiz = Ui.ghostButton(this, "一键开启所需权限（自动逐项跳转）");
        bWiz.setOnClickListener(v -> wizardNext());
        c5.addView(bWiz);
        root.addView(c5);

        // ---- 常驻 ----
        LinearLayout c6 = Ui.sectionCard(this);
        c6.addView(Ui.sectionTitle(this, "常驻（灭屏听写需要）"));
        cbResident = check("常驻前台服务（通知栏常驻）", cfg.resident()); c6.addView(cbResident);
        cbContinuous = check("连续听写（识别完自动再听，再按一次停止）", cfg.continuous()); c6.addView(cbContinuous);
        cbMedia = check("耳机/蓝牙媒体键也能触发（灭屏可用）", cfg.mediaKey()); c6.addView(cbMedia);
        cbVibrate = check("震动反馈", cfg.vibrate()); c6.addView(cbVibrate);
        cbAutoStart = check("开机自动启动", cfg.autoStart()); c6.addView(cbAutoStart);
        cbKeepScreen = check("听写时点亮屏幕（锁屏触发也能识别）", cfg.keepScreen()); c6.addView(cbKeepScreen);
        c6.addView(Ui.caption(this, "锁屏下请用这两种方式触发：① 下拉通知栏点「听写/停止」按钮；② 耳机/蓝牙媒体键。触发后会点亮屏幕完成听写，停了自动灭屏。"));
        LinearLayout row3 = Ui.row(this);
        Button bStart = Ui.ghostButton(this, "启动常驻服务"); bStart.setOnClickListener(v -> { save(); VoiceService.keep(this); refresh(); });
        Button bStop = Ui.ghostButton(this, "停止服务"); bStop.setOnClickListener(v -> { startService(new Intent(this, VoiceService.class).setAction(VoiceService.ACTION_EXIT)); refresh(); });
        addWeighted(row3, bStart, bStop);
        c6.addView(row3);
        root.addView(c6);

        // ---- 其它 ----
        LinearLayout c7 = Ui.sectionCard(this);
        c7.addView(Ui.sectionTitle(this, "其它"));
        c7.addView(Ui.body(this, "发送方式：识别出的文字只落在主页面输入框，由你点「电脑上屏」手动发送。"));
        cbAutoClose = check("发送成功后自动关闭听写窗口", cfg.autoClose()); c7.addView(cbAutoClose);
        cbNewline = check("文字末尾追加回车", cfg.addNewline()); c7.addView(cbNewline);
        Button bMic = Ui.ghostButton(this, "授予麦克风权限（识别服务模式需要）");
        bMic.setOnClickListener(v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 100));
        c7.addView(bMic);
        Button bSave = Ui.primaryButton(this, "保存设置");
        // 不用 Toast：Toast 是系统窗口，在 reversePortrait 页面里文字方向会错。
        // 直接用页内 tvInfo 提示，随 Activity 一起旋转，方向正确。
        bSave.setOnClickListener(v -> {
            save();
            tvInfo.setText("已保存");
            tvInfo.postDelayed(() -> {
                if (tvInfo != null) tvInfo.setText("");
            }, 1200);
        });
        c7.addView(bSave);
        root.addView(c7);

        // ---- 外观（主题 / 输入框文字颜色 / 字体 / 字号）----
        LinearLayout c8 = Ui.sectionCard(this);
        c8.addView(Ui.sectionTitle(this, "外观"));

        // 主题（切换后才立即持久化并重建本页以套用新色板）
        // 注意：Spinner.onItemSelected 在【onLayout 阶段】才触发，晚于 onCreate 返回，
        // 所以不能靠 boolean 标志栏——必须【与当前已保存值比较】，相同则不 recreate，
        // 否则初始化回调会反复触发 recreate 造成无限重建循环。
        c8.addView(Ui.body(this, "主题"));
        spTheme = spinner(Ui.THEME_LABELS, indexOf(Ui.THEME_VALUES, cfg.theme()));
        spTheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= Ui.THEME_VALUES.length) return;
                String next = Ui.THEME_VALUES[pos];
                if (next.equals(cfg.theme())) return;   // 值的没变（含初始化回调），不重建
                cfg.theme(next);
                Ui.setTheme(next);
                recreate();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        c8.addView(spTheme);

        // 输入框文字颜色（选择后立即持久化）
        c8.addView(Ui.body(this, "输入框文字颜色"));
        spTextColor = spinner(COLOR_LABELS, colorIndex(cfg.inputTextColor()));
        spTextColor.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos <= 0 || pos >= COLOR_VALUES.length || pos >= COLOR_LABELS.length) return;
                cfg.textColor(getResources().getColor(COLOR_VALUES[pos]));
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        c8.addView(spTextColor);

        // 字体（选择后立即持久化）
        c8.addView(Ui.body(this, "输入框字体"));
        spFont = spinner(Ui.FONT_LABELS, indexOf(Ui.FONT_VALUES, cfg.fontFamily()));
        spFont.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= Ui.FONT_VALUES.length) return;
                cfg.fontFamily(Ui.FONT_VALUES[pos]);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        c8.addView(spFont);

        // 字号（选择后立即持久化）
        c8.addView(Ui.body(this, "输入框字号"));
        spFontSize = spinner(Ui.FONT_SIZE_TXT, sizeIndex(cfg.fontSize()));
        spFontSize.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= Ui.FONT_SIZES.length) return;
                cfg.fontSize(Ui.FONT_SIZES[pos]);
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        c8.addView(spFontSize);

        c8.addView(Ui.caption(this,
                "外观设置选择后立即保存在本机，关闭 App 后依然生效。"
                + "注意：切换主题会重建本页，本页其它分区未保存的改动会被丢弃。"
                + "输入框外观改动需回到主页面后生效。"));
        root.addView(c8);

        // ---- 屏幕方向 ----
        LinearLayout c9 = Ui.sectionCard(this);
        c9.addView(Ui.sectionTitle(this, "屏幕方向"));
        cbInvert = check("「倒置显示」：App 内容旋转 180°，需把手机倒过来拿才是正的。\n"
                + "（默认关闭：跟随系统方向，倒置手机时返回/Home 等系统控件不会被搬到顶部）",
                cfg.invertMode());
        cbInvert.setOnClickListener(v -> {
            cfg.invertMode(cbInvert.isChecked());
            tvInfo.setText(cbInvert.isChecked()
                    ? "已开启倒置显示，重启 App 后生效"
                    : "已关闭倒置显示，重启 App 后生效");
            tvInfo.postDelayed(() -> { if (tvInfo != null) tvInfo.setText(""); }, 2000);
        });
        c9.addView(cbInvert);
        root.addView(c9);

        tvInfo = Ui.caption(this, "");
        root.addView(tvInfo);

        applyTransport();
        refresh();
    }

    // ------------------------------------------------------- 设备（名称 / 发现）

    /** 最近一次扫描到的设备列表（主线程访问） */
    private final java.util.List<Discover.Device> lanDevices = new java.util.ArrayList<>();

    /**
     * 「设备」卡片：本机名称可改（会广播给局域网其它终端），下方显示发现到的设备数量与列表。
     * <p>
     * 放在最上面：设备身份是所有连接行为的前提，先确认「我是谁、对面有几台」再配地址。
     */
    private LinearLayout buildDeviceCard() {
        LinearLayout c = Ui.sectionCard(this);
        c.addView(Ui.sectionTitle(this, "设备"));

        c.addView(Ui.body(this, "本机名称（同一局域网内的其它设备会看到这个名字）"));
        etDeviceName = Ui.inputField(this, Config.defaultDeviceName());
        etDeviceName.setText(cfg.effDeviceName());
        etDeviceName.setSingleLine(true);
        c.addView(etDeviceName);

        LinearLayout row = Ui.row(this);
        btnScan = Ui.ghostButton(this, "搜索设备");
        btnScan.setOnClickListener(v -> scanPc());
        addWeighted(row, btnScan);
        c.addView(row);

        // 设备清单：数量 + 每台的名称与 IP。点电脑即连接，点手机仅提示（手机不做发送目标）。
        // 搜不到设备时的排查提示也写在这里（refreshLanInfo），保证用户一眼能看到。
        tvLanInfo = Ui.body(this, "");
        tvLanInfo.setOnClickListener(v -> scanPc());
        c.addView(tvLanInfo);

        c.addView(Ui.caption(this, "搜索会向局域网广播发现请求，所有运行中的 KeyVoice 终端（电脑和手机）都会应答。点击上方清单可重新搜索。"));

        refreshLanInfo();   // 立即给出初始提示，避免这块在首帧是空白
        return c;
    }

    /**
     * 刷新「发现 N 台设备」这一行（设备清单：名称 + 类型 + IP）。
     * <p>
     * 一台都没搜到时【就地】给出排查提示：设置页很长，把提示放在页尾（tvInfo）
     * 用户根本看不到，必须显示在他正在看的地方。
     */
    private void refreshLanInfo() {
        if (tvLanInfo == null) return;
        if (lanDevices.isEmpty()) {
            tvLanInfo.setText("未发现设备\n"
                    + "若「电脑能搜到你、你却搜不到电脑」，多半是电脑防火墙没放行：\n"
                    + "在电脑上双击 pc/allow_firewall.bat，弹窗点「是」即可。");
            return;
        }
        StringBuilder sb = new StringBuilder("发现 ").append(lanDevices.size()).append(" 台设备：\n");
        for (int i = 0; i < lanDevices.size(); i++) {
            Discover.Device d = lanDevices.get(i);
            sb.append("· ").append(d.label())
              .append("（").append(d.typeLabel()).append(" · ").append(d.ip).append("）");
            if (i < lanDevices.size() - 1) sb.append('\n');
        }
        tvLanInfo.setText(sb.toString());
    }

    // ------------------------------------------------------- 权限自动跳转

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean accEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.toLowerCase().contains(getPackageName().toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean batteryIgnored() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean needWizard() {
        return !micGranted() || !accEnabled() || !batteryIgnored();
    }

    /** 逐项自动跳转：麦克风 → 无障碍 → 电池优化；每次回到本页自动进入下一项 */
    private void wizardNext() {
        if (!micGranted()) {
            tvInfo.setText("权限引导 1/3：请求麦克风权限…");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 100);
            return;
        }
        if (!accEnabled()) {
            tvInfo.setText("权限引导 2/3：请打开名为「a」的无障碍开关后返回，会自动继续…");
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) { }
            return;
        }
        if (!batteryIgnored()) {
            tvInfo.setText("权限引导 3/3：请允许忽略电池优化（保证后台常驻不被杀）…");
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception ignored) { }
            }
            return;
        }
        tvInfo.setText("✓ 权限全部就绪。回到主页面即可「模拟长按」听写。");
    }

    // ------------------------------------------------------- 控件样式

    private void styleRadio(RadioButton rb) {
        rb.setTextSize(13);
        rb.setTextColor(Ui.text2(this));
        try { rb.setButtonTintList(ColorStateList.valueOf(Ui.primary(this))); } catch (Exception ignored) { }
    }

    /** 统一走设计系统：下拉项的底色/文字色跟随当前主题（系统默认样式是白底，深色下会突兀） */
    private void styleSpinner(Spinner sp) {
        Ui.styleSpinner(this, sp);
    }

    private CheckBox check(String t, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(t);
        c.setChecked(v);
        c.setTextSize(13);
        c.setTextColor(Ui.text2(this));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 4);
        c.setLayoutParams(lp);
        try { c.setButtonTintList(ColorStateList.valueOf(Ui.primary(this))); } catch (Exception ignored) { }
        return c;
    }

    // ------------------------------------------------------- 外观设置辅助

    /** 输入框文字颜色色板：第一项「跟随主题」用 0 表示 */
    private static final int[] COLOR_VALUES = {
            Ui.COLOR_FOLLOW_THEME,
            R.color.sw_white, R.color.sw_yellow, R.color.sw_green, R.color.sw_cyan,
            R.color.sw_pink, R.color.sw_orange, R.color.sw_lavender, R.color.sw_lime,
    };
    private static final String[] COLOR_LABELS = {
            "跟随主题", "白", "黄", "绿", "青", "粉", "橙", "紫", "黄绿",
    };

    /** 统一风格的 Spinner（下拉选中项用于外观设置） */
    private Spinner spinner(String[] labels, int sel) {
        Spinner s = new Spinner(this);
        s.setAdapter(Ui.spinnerAdapter(this, labels));
        if (sel >= 0 && sel < labels.length) s.setSelection(sel);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 4);
        s.setLayoutParams(lp);
        return s;
    }

    private int indexOf(String[] arr, String v) {
        if (v == null) return 0;
        for (int i = 0; i < arr.length; i++) if (v.equals(arr[i])) return i;
        return 0;
    }

    private int sizeIndex(int sp) {
        for (int i = 0; i < Ui.FONT_SIZES.length; i++) if (Ui.FONT_SIZES[i] == sp) return i;
        return 2; // 默认 15
    }

    private int colorIndex(int argb) {
        if (argb == Ui.COLOR_FOLLOW_THEME) return 0;
        for (int i = 0; i < COLOR_VALUES.length; i++) {
            if (COLOR_VALUES[i] == Ui.COLOR_FOLLOW_THEME) continue;
            if (argb == getResources().getColor(COLOR_VALUES[i])) return i;
        }
        return 0;
    }

    private void addWeighted(LinearLayout row, Button... btns) {
        for (Button b : btns) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = Ui.dp(this, 8);
            b.setLayoutParams(lp);
            row.addView(b);
        }
    }

    // ------------------------------------------------------------- 逻辑

    private void applyTransport() {
        boolean adb = rgTrans.getCheckedRadioButtonId() == 10;
        etHost.setEnabled(!adb);
        btnScan.setEnabled(!adb);
        String port = etPort.getText().toString().trim();
        if (port.isEmpty()) port = String.valueOf(cfg.port());
        tvAdbHint.setText(adb
                ? "电脑端先执行一次（手机插着 USB）：\n  adb reverse tcp:" + port + " tcp:" + port
                  + "\n然后手机就能发到电脑，全程不走局域网。"
                : "局域网模式：手机与电脑要在同一个 WiFi。");
        // 选了局域网且还没连上：自动搜索并连接设备
        if (!adb && cfg.host().isEmpty()) {
            tvInfo.setText("局域网模式：正在自动搜索设备…");
            etPort.postDelayed(this::scanPc, 600);
        }
    }

    private void loadEngines() {
        engineLabels.clear();
        engineComps.clear();
        engineLabels.add("系统默认");
        engineComps.add("");

        PackageManager pm = getPackageManager();
        List<ResolveInfo> list = pm.queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        int iflytekIndex = -1;
        if (list != null) {
            for (ResolveInfo ri : list) {
                if (ri.serviceInfo == null) continue;
                ComponentName cn = new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name);
                CharSequence l = null;
                try { l = ri.loadLabel(pm); } catch (Exception ignored) { }
                String label = l != null ? l.toString() : cn.getClassName();
                if (cn.getPackageName().toLowerCase().contains("iflytek")) {
                    label = label + "（讯飞）";
                    if (iflytekIndex < 0) iflytekIndex = engineLabels.size();
                }
                engineLabels.add(label);
                engineComps.add(cn.flattenToString());
            }
        }
        spEngine.setAdapter(Ui.spinnerAdapter(this,
                engineLabels.toArray(new String[0])));

        int sel = 0;
        String saved = cfg.srComponent();
        if (!TextUtils.isEmpty(saved)) sel = Math.max(0, engineComps.indexOf(saved));
        else if (iflytekIndex > 0) sel = iflytekIndex;
        spEngine.setSelection(Math.min(sel, engineLabels.size() - 1));
    }

    /** 用户点「搜索设备」：扫描后弹窗让选一台（只有一台就直接连） */
    private void scanPc() {
        runScan(true);
    }

    /** 静默刷新：只更新「发现 N 台设备」这一行，不弹窗、不自动改连接目标 */
    private void refreshDeviceList() {
        runScan(false);
    }

    private void runScan(final boolean interactive) {
        if (interactive) {
            // 静默刷新（进页面自动扫）不动 tvInfo：那一行常驻显示服务状态，别被搜索提示盖掉
            boolean vpn = Discover.vpnActive();
            tvInfo.setText(vpn
                    ? "⚠ 检测到 VPN 已开启，可能拦截局域网发现。正在搜索设备（4 秒）…"
                    : "正在搜索设备（4 秒）…");
            btnScan.setEnabled(false);
            btnScan.setText("搜索中…");
        }
        lanDevices.clear();
        if (tvLanInfo != null) tvLanInfo.setText("正在搜索设备…");

        Discover.scan(this, 4000, new Discover.Sink() {
            @Override public void onFound(final Discover.Device d) {
                runOnUiThread(() -> {
                    lanDevices.add(d);
                    refreshLanInfo();
                });
            }

            @Override public void onDone(final List<Discover.Device> all) {
                runOnUiThread(() -> {
                    if (interactive) {
                        btnScan.setEnabled(!cfg.adbMode());
                        btnScan.setText("搜索设备");
                    }
                    lanDevices.clear();
                    lanDevices.addAll(all);
                    refreshLanInfo();
                    if (!interactive) return;      // 静默刷新到此为止
                    if (all.isEmpty()) {
                        // 最容易踩、也最容易被误判成 bug 的坑：电脑防火墙没放行。
                        // 症状是单向不通 —— 电脑能搜到手机（出去的广播+收回的应答都放行），
                        // 手机却搜不到电脑（手机发的广播被防火墙丢掉）。所以这里直接把病根说清楚。
                        tvInfo.setText("没搜到设备。若「电脑能搜到你、你却搜不到电脑」，"
                                + "基本就是电脑防火墙没放行：在电脑上双击 pc/allow_firewall.bat，"
                                + "在弹窗点「是」即可。另请确认手机与电脑在同一 WiFi"
                                + (Discover.vpnActive() ? "；检测到 VPN 已开启，它会拦截广播，建议临时关闭再试。" : "。"));
                        return;
                    }
                    if (all.size() == 1) {
                        connectTo(all.get(0));
                        return;
                    }
                    String[] items = new String[all.size()];
                    for (int i = 0; i < all.size(); i++) items[i] = all.get(i).toString();
                    new AlertDialog.Builder(SettingsActivity.this)
                            .setTitle("发现 " + all.size() + " 台设备")
                            .setItems(items, (d, which) -> connectTo(all.get(which)))
                            .show();
                });
            }
        });
    }

    /** 选中一台设备后：填入地址 → 切局域网模式 → 保存 → 探测连通性 */
    private void connectTo(Discover.Device d) {
        if (!d.isPc()) {
            // 手机之间暂不互发，只展示；后续若要手机→手机，可在此接上 17892 的 /text
            tvInfo.setText(d.label() + " 是手机，暂不支持直接发送（仅列表展示）");
            return;
        }
        etHost.setText(d.ip);
        etPort.setText(String.valueOf(d.port));
        rgTrans.check(11);          // 切到局域网模式
        applyTransport();
        save();
        String target = d.label() + " " + d.ip + ":" + d.port;
        tvInfo.setText("已锁定 " + target + "，正在测试连接…");
        Sender.ping(this, (ok, msg) -> tvInfo.setText(ok
                ? "已自动连接：" + target + "（收到文字将直接上屏到电脑光标处）"
                : "已填 " + target + " 但连不通：" + msg
                + (Discover.vpnActive() ? " —— 手机开着 VPN 时 HTTP 也会走隧道，建议临时关闭 VPN 再试"
                : " —— 检查电脑防火墙是否放行 17890（可用 pc/allow_firewall.bat 一键放行）")));
    }

    private void testSend() {
        save();
        Sender.send(this, "KeyVoice 测试 " + System.currentTimeMillis() % 10000, (ok, msg) ->
                tvInfo.setText(ok ? "发送成功，看电脑悬浮框" : "发送失败：" + msg));
    }

    private void save() {
        cfg.adbMode(rgTrans.getCheckedRadioButtonId() == 10);
        cfg.host(etHost.getText().toString().trim());
        try {
            cfg.port(Integer.parseInt(etPort.getText().toString().trim()));
        } catch (Exception ignored) { }
        cfg.mode(rgMode.getCheckedRadioButtonId() == 2 ? "sr" : "ime");
        int ei = spEngine.getSelectedItemPosition();
        cfg.srComponent(ei >= 0 && ei < engineComps.size() ? engineComps.get(ei) : "");
        cfg.action(spAction.getSelectedItemPosition() == 1 ? "copy" : "paste");
        cfg.mediaKey(cbMedia.isChecked());
        cfg.resident(cbResident.isChecked());
        cfg.continuous(cbContinuous.isChecked());
        cfg.vibrate(cbVibrate.isChecked());
        cfg.autoStart(cbAutoStart.isChecked());
        cfg.keepScreen(cbKeepScreen.isChecked());
        cfg.autoClose(cbAutoClose.isChecked());
        cfg.addNewline(cbNewline.isChecked());
        // ---- 设备名（广播给局域网其它终端，立即生效）----
        if (etDeviceName != null) {
            cfg.deviceName(etDeviceName.getText().toString().trim());
            LanServer.setDeviceName(cfg.effDeviceName());
            if (etDeviceName.length() == 0) etDeviceName.setHint(Config.defaultDeviceName());
        }
        // ---- 外观（均写入 SharedPreferences，App 关闭后依然保留）----
        cfg.theme(Ui.THEME_VALUES[safePos(spTheme, 0)]);
        int ci = safePos(spTextColor, 0);
        cfg.textColor(ci > 0 ? getResources().getColor(COLOR_VALUES[ci]) : Ui.COLOR_FOLLOW_THEME);
        cfg.fontFamily(Ui.FONT_VALUES[safePos(spFont, 0)]);
        int si = safePos(spFontSize, 2);
        cfg.fontSize(Ui.FONT_SIZES[si]);
        // 屏幕方向（倒置显示）
        if (cbInvert != null) cfg.invertMode(cbInvert.isChecked());
    }

    private int safePos(Spinner s, int def) {
        if (s == null) return def;
        int p = s.getSelectedItemPosition();
        return p < 0 ? def : p;
    }

    private void refresh() {
        tvInfo.setText(VoiceService.running
                ? "服务状态：运行中（灭屏可通知栏/媒体键听写）"
                : "服务状态：未运行。灭屏听写请先点「启动常驻服务」");
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // 设置页也要显示实时的设备数量：进页面静默扫一次（只更新清单，不弹窗）
        refreshDeviceList();
    }
}
