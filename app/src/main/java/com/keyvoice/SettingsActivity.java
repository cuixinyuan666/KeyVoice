package com.keyvoice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.speech.RecognitionService;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
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
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置页（v0.2.0）：承接原主界面的全部配置。
 * 物理按键触发与遮蔽模式已移除；「模拟长按」手势依赖无障碍服务，在这里开启。
 */
public class SettingsActivity extends Activity {

    private Config cfg;
    private EditText etHost, etPort;
    private TextView tvInfo, tvAdbHint;
    private Spinner spEngine, spAction;
    private RadioGroup rgMode, rgTrans;
    private Button btnScan;
    private CheckBox cbAutoSend, cbAutoClose, cbNewline;
    private CheckBox cbResident, cbContinuous, cbMedia, cbVibrate, cbAutoStart;
    private CheckBox cbKeepScreen;

    private final List<String> engineLabels = new ArrayList<>();
    private final List<String> engineComps = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = new Config(this);

        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);
        sv.addView(root);
        setContentView(sv);

        // ---- 传输方式
        root.addView(title("传到 Windows 10 的方式"));

        rgTrans = new RadioGroup(this);
        RadioButton rbAdb = new RadioButton(this);
        rbAdb.setId(10);
        rbAdb.setText("adb（USB 线，不需要局域网）");
        RadioButton rbLan = new RadioButton(this);
        rbLan.setId(11);
        rbLan.setText("局域网 WiFi");
        rgTrans.addView(rbAdb);
        rgTrans.addView(rbLan);
        rgTrans.check(cfg.adbMode() ? 10 : 11);
        root.addView(rgTrans);

        tvAdbHint = label("");
        tvAdbHint.setTextColor(Color.parseColor("#E65100"));
        root.addView(tvAdbHint);
        rgTrans.setOnCheckedChangeListener((g, id) -> applyTransport());

        // ---- 电脑地址
        root.addView(title("电脑地址"));
        etHost = new EditText(this);
        etHost.setHint("电脑局域网 IP，如 192.168.1.5（仅局域网模式用）");
        etHost.setText(cfg.host());
        etHost.setSingleLine(true);
        root.addView(etHost);

        etPort = new EditText(this);
        etPort.setHint("端口");
        etPort.setText(String.valueOf(cfg.port()));
        etPort.setSingleLine(true);
        root.addView(etPort);

        LinearLayout row1 = row();
        btnScan = button("搜索电脑", v -> scanPc());
        row1.addView(btnScan);
        row1.addView(button("测试发送", v -> testSend()));
        root.addView(row1);

        // ---- 语音来源
        root.addView(title("语音来源"));
        rgMode = new RadioGroup(this);
        RadioButton rbIme = new RadioButton(this);
        rbIme.setId(1);
        rbIme.setText("用手机装的输入法（讯飞）语音输入（主页面「模拟长按」用）");
        RadioButton rbSr = new RadioButton(this);
        rbSr.setId(2);
        rbSr.setText("直接调用识别服务（通知栏/媒体键触发，可灭屏）");
        rgMode.addView(rbIme);
        rgMode.addView(rbSr);
        rgMode.check("sr".equals(cfg.mode()) ? 2 : 1);
        root.addView(rgMode);

        spEngine = new Spinner(this);
        root.addView(spEngine);
        root.addView(label("识别引擎默认自动选「讯飞（讯飞）」；若手机没暴露讯飞识别服务，则回退系统默认。"));
        loadEngines();

        // ---- 电脑端动作
        root.addView(title("电脑端收到后"));
        spAction = new Spinner(this);
        String[] acts = {"有输入光标就上屏，没光标就存剪贴板", "只显示在悬浮框/存入剪贴板，不上屏"};
        spAction.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, acts));
        spAction.setSelection("copy".equals(cfg.action()) ? 1 : 0);
        root.addView(spAction);

        // ---- 权限（模拟长按手势依赖无障碍）
        root.addView(title("权限（「模拟长按」依赖无障碍服务）"));
        root.addView(button("打开系统无障碍设置（手势注入必开）", v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))));
        root.addView(button("一键开启所需权限（自动逐项跳转）", v -> wizardNext()));

        // ---- 常驻
        root.addView(title("常驻（灭屏听写需要）"));
        cbResident = check("常驻前台服务（通知栏常驻）", cfg.resident());
        root.addView(cbResident);
        cbContinuous = check("连续听写（识别完自动再听，再按一次停止）", cfg.continuous());
        root.addView(cbContinuous);
        cbMedia = check("耳机/蓝牙媒体键也能触发（灭屏可用）", cfg.mediaKey());
        root.addView(cbMedia);
        cbVibrate = check("震动反馈", cfg.vibrate());
        root.addView(cbVibrate);
        cbAutoStart = check("开机自动启动", cfg.autoStart());
        root.addView(cbAutoStart);
        cbKeepScreen = check("听写时点亮屏幕（锁屏触发也能识别）", cfg.keepScreen());
        root.addView(cbKeepScreen);

        root.addView(label("锁屏下请用这两种方式触发：① 下拉通知栏点「听写/停止」按钮；② 耳机/蓝牙媒体键。触发后会点亮屏幕完成听写，停了自动灭屏。"));

        LinearLayout row3 = row();
        row3.addView(button("启动常驻服务", v -> {
            save();
            VoiceService.keep(this);
            refresh();
        }));
        row3.addView(button("停止服务", v -> {
            startService(new Intent(this, VoiceService.class).setAction(VoiceService.ACTION_EXIT));
            refresh();
        }));
        root.addView(row3);

        // ---- 其他
        root.addView(title("其它"));
        cbAutoSend = check("识别出字后自动发送到电脑（关掉则手动点「电脑上屏」）", cfg.autoSend());
        root.addView(cbAutoSend);
        cbAutoClose = check("发送成功后自动关闭听写窗口", cfg.autoClose());
        root.addView(cbAutoClose);
        cbNewline = check("文字末尾追加回车", cfg.addNewline());
        root.addView(cbNewline);

        root.addView(button("授予麦克风权限（识别服务模式需要）", v ->
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 100)));

        root.addView(button("保存设置", v -> {
            save();
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        }));

        tvInfo = label("");
        tvInfo.setTextColor(Color.parseColor("#666666"));
        root.addView(tvInfo);

        applyTransport();
        refresh();

        // 默认局域网：没填过电脑 IP 就打开即自动扫描
        if (!cfg.adbMode() && cfg.host().isEmpty()) {
            tvInfo.setText("局域网模式：正在自动扫描电脑…");
            etPort.postDelayed(this::scanPc, 600);
        }
    }

    // ------------------------------------------------------- 权限自动跳转

    private boolean micGranted() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean accEnabled() {
        try {
            String s = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
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
            tvInfo.setText("权限引导 2/3：请打开「按键语音 · 按键监听」开关后返回，会自动继续…");
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Exception ignored) {
            }
            return;
        }
        if (!batteryIgnored()) {
            tvInfo.setText("权限引导 3/3：请允许忽略电池优化（保证后台常驻不被杀）…");
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception ignored) {
                }
            }
            return;
        }
        tvInfo.setText("✓ 权限全部就绪。回到主页面即可「模拟长按」听写。");
    }

    // ------------------------------------------------------------- 逻辑

    private void applyTransport() {
        boolean adb = rgTrans.getCheckedRadioButtonId() == 10;
        etHost.setEnabled(!adb);
        btnScan.setEnabled(!adb);
        String port = etPort.getText().toString().trim();
        if (port.isEmpty()) port = String.valueOf(cfg.port());
        tvAdbHint.setText(adb
                ? "电脑端先执行一次（手机插着 USB）：\n  adb reverse tcp:" + port + " tcp:" + port + "\n然后手机就能发到电脑，全程不走局域网。"
                : "局域网模式：手机与电脑要在同一个 WiFi。");
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
                CharSequence l = ri.loadLabel(pm);
                String label = l != null ? l.toString() : cn.getClassName();
                if (cn.getPackageName().toLowerCase().contains("iflytek")) {
                    label = label + "（讯飞）";
                    if (iflytekIndex < 0) iflytekIndex = engineLabels.size();
                }
                engineLabels.add(label);
                engineComps.add(cn.flattenToString());
            }
        }
        spEngine.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, engineLabels));

        int sel = 0;
        String saved = cfg.srComponent();
        if (!TextUtils.isEmpty(saved)) sel = Math.max(0, engineComps.indexOf(saved));
        else if (iflytekIndex > 0) sel = iflytekIndex;
        spEngine.setSelection(Math.min(sel, engineLabels.size() - 1));
    }

    private void scanPc() {
        boolean vpn = Discover.vpnActive();
        tvInfo.setText(vpn
                ? "⚠ 检测到 VPN 已开启，可能拦截局域网发现。正在搜索（4 秒）…"
                : "正在搜索电脑（4 秒）…");
        final List<String> found = new ArrayList<>();
        Discover.scan(this, 4000, new Discover.Sink() {
            @Override public void onFound(final String ip, final int port) {
                runOnUiThread(() -> found.add(ip + ":" + port));
            }

            @Override public void onDone(final int count) {
                runOnUiThread(() -> {
                    if (count == 0) {
                        tvInfo.setText("没搜到。确认：①电脑已运行接收端；②同一 WiFi；③电脑防火墙放行 17890/17891"
                                + (Discover.vpnActive() ? "；④临时关闭 VPN 再试（VPN 会拦截广播）" : "。"));
                        return;
                    }
                    String[] items = found.toArray(new String[0]);
                    if (items.length == 1) {
                        connectTo(items[0]);
                    } else {
                        new AlertDialog.Builder(SettingsActivity.this)
                                .setTitle("选择电脑")
                                .setItems(items, (d, which) -> connectTo(items[which]))
                                .show();
                    }
                });
            }
        });
    }

    private void connectTo(String s) {
        String[] sp = s.split(":");
        etHost.setText(sp[0]);
        if (sp.length > 1) etPort.setText(sp[1]);
        rgTrans.check(11);          // 切到局域网模式
        applyTransport();
        save();
        tvInfo.setText("已锁定 " + s + "，正在测试连接…");
        final String target = s;
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
        } catch (Exception ignored) {
        }
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
        cfg.autoSend(cbAutoSend.isChecked());
        cfg.autoClose(cbAutoClose.isChecked());
        cfg.addNewline(cbNewline.isChecked());
    }

    private void refresh() {
        tvInfo.setText(VoiceService.running
                ? "服务状态：运行中（灭屏可通知栏/媒体键听写）"
                : "服务状态：未运行。灭屏听写请先点「启动常驻服务」");
    }

    // --------------------------------------------------------- UI 小工具

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView title(String t) {
        TextView tv = label(t);
        tv.setTextSize(15);
        tv.setTextColor(Color.parseColor("#1976D2"));
        tv.setPadding(0, dp(12), 0, dp(4));
        return tv;
    }

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        return tv;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private Button button(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private CheckBox check(String t, boolean v) {
        CheckBox c = new CheckBox(this);
        c.setText(t);
        c.setChecked(v);
        c.setTextSize(13);
        return c;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        if (needWizard()) wizardNext();  // 从设置页返回后自动进入下一项
    }
}
