package com.keyvoice;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.animation.ValueAnimator;
import android.view.inputmethod.InputMethodManager;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * 主页面（v0.3.0 倒握布局）：
 *   - 顶部是输入框（讯飞语音出的字落在这里）
 *   - 输入框右下角一个“动态旋转”的状态点：正常绿、异常（无障碍未开/未连）红，
 *     异常时下方出现一个指向「设置」的动态箭头（点击直接跳无障碍设置）
 *   - 下半部分由 DragLayout 承载三个圆形图标按钮：设置（右中）、模拟长按、电脑上屏；
 *     长按进入编辑态可拖动 / 双指缩放，右上角 √ 固定并保存布局
 */
public class MainActivity extends Activity {

    private EditText et;
    private ImageView statusDot, arrow;
    private ImageButton btnHold, btnSend, btnSettings, btnClear, btnPause;
    private DragLayout side;
    private FrameLayout overlay;
    private FrameLayout rootView;          // 全屏根容器：作为「全局移动」的坐标参照
    private TextView tip;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Config cfg;
    private boolean sending = false;
    /** 电脑发来的消息正在回填输入框：期间抑制自动发送，避免原样回发形成死循环 */
    private boolean fromPc = false;

    /**
     * 输入区高度（dp）。刻意写死而不依赖 WRAP_CONTENT：
     * inputWrap 内含 ALIGN_PARENT_BOTTOM 的状态点，WRAP_CONTENT 高度在该组合下无法
     * 收敛，会膨胀到占满父容器，把下方 weight=1 的按钮区挤成 0 高度。
     * 取值 200dp：输入框约占屏幕 1/3（远大于早期的 120dp），同时仍给五个按钮留足空间
     * （5 个按钮 + 4 个间距 ≈ 5*38 + 4*16 = 254dp）。
     */
    private static final int INPUT_WRAP_DP = 200;
    // 全局移动现在要求元素【完全留在屏幕内】（见 clampGlobalMove），不再保留越界可视区。

    /**
     * 竖排提示条尺寸（dp）。
     *   TIP_BAR_*  = overlay 自身占位（按【旋转后】的窄高条给定，避免旋转后超出被裁剪）
     *   TIP_TEXT_W = TextView 旋转前的宽度（旋转后成为竖向长度）
     */
    private static final int TIP_BAR_W_DP = 64;
    private static final int TIP_BAR_H_DP = 300;
    private static final int TIP_TEXT_W_DP = 280;
    /** 局域网模式未连接时，最多自动搜索连接一次 */
    private boolean lanAutoScanned = false;
    /** 暂停态：已暂时松开长按，等再次点击恢复 */
    private boolean paused = false;
    /** 最近一次成功发送的内容：用于兜底清空（讯飞延迟修正会把已发送内容写回输入框） */
    private String lastSent = "";

    // ---------------------------------------------------------------- 输入框调节
    /** 输入框是否处于调节模式（长按 5 秒进入） */
    private boolean inputAdjustActive = false;
    private LinearLayout tuner;
    /** 输入区容器（RelativeLayout）：调节模式直接对它做位移/缩放/颠倒 */
    private android.widget.RelativeLayout inputWrap;
    private long inputDownTime = 0;
    private float inputDownX = 0, inputDownY = 0, inputLastX = 0, inputLastY = 0;
    /** 输入框长按阈值：系统文字选择约 500ms 就触发，这里要求 5 秒以上才进入调节 */
    private static final long INPUT_LONG_PRESS_MS = 5000L;
    private static final float ADJUST_SLOP = 8f;
    private static final float INPUT_SCALE_MIN = 0.6f;
    private static final float INPUT_SCALE_MAX = 2.2f;
    /** 进入调节态后第一次移动只做锚点校准，避免输入框瞬移“消失” */
    private boolean inputReanchor = false;

    // ---------------------------------------------------------------- 聊天面板
    /** PC 发来的消息显示在这里（仿聊天布局），边缘把手可进入调节 */
    private LinearLayout chatWrap;         // 面板容器（顶栏 + 主体）
    private LinearLayout chatBody;         // 面板主体（头部 + 消息）：翻转时只翻它，顶栏常驻
    private LinearLayout chatRows;         // 消息行容器
    private android.widget.ScrollView chatScroll;
    private View chatShield;               // 调节态触摸拦截层
    private LinearLayout chatTuner;        // 聊天调节工具条
    private TextView chatHeader;           // 面板头部：本机名 + 同局域网设备数量
    private Runnable peerListener;         // 设备数变化回调（退出时摘除）
    private boolean chatAdjustActive = false;
    private boolean chatReanchor = false;
    private float chatLastX = 0, chatLastY = 0;
    private static final float CHAT_SCALE_MIN = 0.6f;
    private static final float CHAT_SCALE_MAX = 1.6f;
    private ImageButton chatFab;            // 面板收起后的悬浮按钮（点此展开）
    private boolean chatCollapsed = false;  // 聊天面板是否收起为悬浮按钮
    private boolean chatHiddenByInput = false; // 输入框获焦时临时隐藏消息框
    private boolean pendingSendAfterRelease = false; // 松开后整句修正提交即自动上屏

    // ---- 发送后「清空输入框」保护窗口 ----
    // 讯飞在松开长按后 1~3 秒内才提交整句修正，会把刚发送的内容又写回输入框，
    // 看起来像“发送后没清空”。保护窗口内凡是被写回的文本一律再清一次；
    // 一旦用户主动点了输入框（想自己打字）就立刻结束保护，不干扰正常输入。
    private long clearGuardUntil = 0L;
    private static final long CLEAR_GUARD_MS = 6000L;
    private ImageButton btnMsg;             // 实体按钮：点一下直接显示消息框
    private boolean downOutsideInput = false; // 本次手势按下的位置是否在输入框之外
    private String lastSentPlain = "";        // 上一条已发送内容（防重复发送用）
    private long lastSentAt = 0L;             // 上一条发送时间

    // ---- 首启权限引导（保留，仅在设置页手动触发；不再自动弹）----
    private boolean onboardingActive = false;
    private boolean onboardWaitingReturn = false;
    private static final int REQ_ONBOARD_MIC = 200;

    /**
     * 当前处于 resumed（前台可见）状态的 MainActivity。
     * 用于把后台服务（无障碍服务 / VoiceService）的提示改道到主页面的 overlay：
     * Toast 是【系统窗口】，按设备物理方向渲染；而本页是 reversePortrait（旋转 180°），
     * 倒持手机时 Toast 的文字方向会错、位置也会漂到边栏。overlay 属于本 Activity，
     * 随 Activity 一起旋转，才是正确的位置与方向。
     */
    private static MainActivity resumed = null;

    private final Runnable hideTip = () -> { if (overlay != null) overlay.setVisibility(View.GONE); };

    private final BroadcastReceiver commit = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // 用户点「电脑上屏」且当时正在按住：松开后讯飞整句修正提交(COMMIT)即自动上屏，
            // 实现「一键松开+发送」（不必再点第二次）。
            if (pendingSendAfterRelease) {
                pendingSendAfterRelease = false;
                doSend(true);
            }
        }
    };

    private final BroadcastReceiver result = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String t = i.getStringExtra("text");
            if (t != null && et != null) {
                et.append(t);
                showCenterTip("已收到识别结果，点「电脑上屏」发送");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appCtx = getApplicationContext();
        cfg = new Config(this);
        // 方向：默认跟随系统，只有用户显式开启「倒置显示」时才锁定 reversePortrait
        applyOrientation();
        // 主题：来自 Config 持久化，必须在构建任何视图之前设置，颜色 token 才会取对色板
        Ui.setTheme(cfg.theme());

        // 根用 FrameLayout：内容垂直排列 + 一个左侧竖排提示层（不挡按钮）
        FrameLayout root = new FrameLayout(this);
        this.rootView = root;
        root.setBackgroundColor(Ui.bg(this));
        int pad = Ui.dp(this, 16);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 全局移动：输入框可被拖出 content 边界（横向越界），这里不能裁剪子 View
        content.setClipChildren(false);
        content.setClipToPadding(false);

        // ---- 输入框 + 右下角状态点 + 异常箭头 ----
        // 注意：这里【不能】用 WRAP_CONTENT。inputWrap 内含 ALIGN_PARENT_BOTTOM 的
        // statusDot，而 RelativeLayout 在 WRAP_CONTENT 高度下无法确定自身尺寸，
        // 会把 ALIGN_PARENT_BOTTOM 子项按父容器全高摆放，导致 inputWrap 膨胀到占满
        // 整个可用高度（在 vivo Funtouch OS 等 ROM 上尤其明显）。一旦膨胀，
        // 下面 weight=1 的 side 就只能分到 0 高度，三个按钮被压成一条线、完全不可见。
        // 因此这里给 inputWrap 一个【明确的高度】，从根上切断这条传导链。
        RelativeLayout inputWrap = new RelativeLayout(this);
        this.inputWrap = inputWrap;
        LinearLayout.LayoutParams iwlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, INPUT_WRAP_DP));
        inputWrap.setLayoutParams(iwlp);

        et = Ui.inputField(this, "语音识别出的文字会落这里；也可手动输入");
        et.setMinLines(3);
        et.setGravity(Gravity.TOP);
        // 填满 inputWrap 的固定高度（MATCH_PARENT + MATCH_PARENT），
        // 不再用 WRAP_CONTENT，避免与 statusDot 的 ALIGN_PARENT_BOTTOM 互相牵制。
        RelativeLayout.LayoutParams etp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        etp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        et.setLayoutParams(etp);
        // 应用持久化的输入框外观（颜色 / 字体 / 字号）
        Ui.applyInputStyle(et, cfg, Ui.text(this));
        inputWrap.addView(et);

        statusDot = new ImageView(this);
        int ds = Ui.dp(this, 14);
        RelativeLayout.LayoutParams dotp = new RelativeLayout.LayoutParams(ds, ds);
        dotp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        dotp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        dotp.setMargins(0, 0, Ui.dp(this, 14), Ui.dp(this, 14));
        statusDot.setLayoutParams(dotp);
        statusDot.setId(View.generateViewId());
        inputWrap.addView(statusDot);

        arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow);
        arrow.setColorFilter(Color.WHITE);
        arrow.setRotation(45f); // 指向右下（设置齿轮方向）
        arrow.setContentDescription("无障碍未开启，点此去设置");
        arrow.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
        });
        RelativeLayout.LayoutParams arp = new RelativeLayout.LayoutParams(
                Ui.dp(this, 22), Ui.dp(this, 22));
        arp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        arp.addRule(RelativeLayout.BELOW, statusDot.getId());
        arp.setMargins(0, Ui.dp(this, 4), Ui.dp(this, 14), 0);
        arrow.setLayoutParams(arp);
        arrow.setVisibility(View.GONE);
        inputWrap.addView(arrow);

        // 输入框调节把手：左下角、高对比（accent 底 + 白色把手条），点击即进入调节模式（拖动输入框）。
        // 体积缩小为 40×26dp，避免遮挡输入区。
        ImageButton inputHandle = new ImageButton(this);
        int ihs = Ui.dp(this, 26);
        RelativeLayout.LayoutParams ihp = new RelativeLayout.LayoutParams(
                Ui.dp(this, 40), ihs);
        ihp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        ihp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        ihp.setMargins(Ui.dp(this, 6), 0, 0, Ui.dp(this, 5));
        inputHandle.setLayoutParams(ihp);
        inputHandle.setImageResource(R.drawable.kv_handle);
        inputHandle.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        inputHandle.setBackground(Ui.buttonBg(Ui.accent(this), Ui.primaryDim(this), ihs / 2f));
        inputHandle.setElevation(Ui.dp(this, 6));
        inputHandle.setContentDescription("调节输入框（点此进入拖动）");
        inputHandle.setOnClickListener(v -> enterInputAdjust());
        inputWrap.addView(inputHandle);
        inputHandle.bringToFront();

        // 救砖入口：长按输入框右下角状态点 → 报告三个按钮的真实几何（不依赖 adb），并提供一键复位。
        // 这是为无法使用 adb 的场景准备的：几何值直接显示在屏幕上，可截屏反馈。
        statusDot.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("布局复位")
                    .setMessage("长按状态点触发的诊断报告（可直接截屏反馈）：\n\n"
                            + side.reportGeometry())
                    .setPositiveButton("恢复全部布局", (d, w) -> resetAllLayout())
                    .setNeutralButton("关闭", null)
                    .show();
            return true;
        });

        // 箭头动态：轻微上下浮动
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(700);
        va.setRepeatCount(ValueAnimator.INFINITE);
        va.setRepeatMode(ValueAnimator.REVERSE);
        va.addUpdateListener(a -> arrow.setTranslationY((Float) a.getAnimatedValue() * Ui.dp(this, 8)));
        va.start();

        content.addView(inputWrap);

        // ---- 可拖动按钮容器：覆盖整个屏幕的全屏层 ----
        // 让五个按钮可以移动到【任意位置】，包括原本输入框所在的上半屏区域。
        // 普通态不拦截空白处的触摸，触摸会穿透到下层输入框（输入 / 长按调节都不受影响）。
        side = new DragLayout(this);
        side.setConfig(cfg);
        FrameLayout.LayoutParams sideLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        side.setLayoutParams(sideLp);

        int bs = Ui.dp(this, 38);
        btnSettings = Ui.iconButton(this, R.drawable.ic_gear, Ui.text2(this), 38);
        btnSettings.setId(View.generateViewId());
        btnSettings.setTag("s");
        btnSettings.setContentDescription("设置");
        RelativeLayout.LayoutParams glp = new RelativeLayout.LayoutParams(bs, bs);
        glp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        glp.addRule(RelativeLayout.CENTER_VERTICAL);
        btnSettings.setLayoutParams(glp);
        btnSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        btnHold = Ui.iconButton(this, R.drawable.ic_mic, Ui.primary(this), 38);
        btnHold.setId(View.generateViewId());
        btnHold.setTag("h");
        btnHold.setContentDescription("模拟长按空格（按住说话）");
        RelativeLayout.LayoutParams hlp = new RelativeLayout.LayoutParams(bs, bs);
        hlp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        hlp.addRule(RelativeLayout.BELOW, btnSettings.getId());
        hlp.topMargin = Ui.dp(this, 16);
        btnHold.setLayoutParams(hlp);
        btnHold.setOnClickListener(v -> onHoldClick());

        btnSend = Ui.iconButton(this, R.drawable.ic_send, Ui.success(this), 38);
        btnSend.setId(View.generateViewId());
        btnSend.setTag("e");
        btnSend.setContentDescription("电脑上屏（松开并发送）");
        RelativeLayout.LayoutParams slp = new RelativeLayout.LayoutParams(bs, bs);
        slp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        slp.addRule(RelativeLayout.BELOW, btnHold.getId());
        slp.topMargin = Ui.dp(this, 16);
        btnSend.setLayoutParams(slp);
        btnSend.setOnClickListener(v -> onSendClick());

        // ---- 清除输入框 ----
        btnClear = Ui.iconButton(this, R.drawable.ic_clear, Ui.text2(this), 38);
        btnClear.setId(View.generateViewId());
        btnClear.setTag("c");
        btnClear.setContentDescription("清除输入框内容");
        RelativeLayout.LayoutParams clp = new RelativeLayout.LayoutParams(bs, bs);
        clp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        clp.addRule(RelativeLayout.BELOW, btnSend.getId());
        clp.topMargin = Ui.dp(this, 16);
        btnClear.setLayoutParams(clp);
        btnClear.setOnClickListener(v -> {
            et.setText("");
            showCenterTip("已清空输入框");
        });

        // ---- 暂停 / 恢复长按（单键切换）----
        btnPause = Ui.iconButton(this, R.drawable.ic_pause, Ui.warning(this), 38);
        btnPause.setId(View.generateViewId());
        btnPause.setTag("p");
        btnPause.setContentDescription("暂停 / 恢复长按");
        RelativeLayout.LayoutParams plp = new RelativeLayout.LayoutParams(bs, bs);
        plp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        plp.addRule(RelativeLayout.BELOW, btnClear.getId());
        plp.topMargin = Ui.dp(this, 16);
        btnPause.setLayoutParams(plp);
        btnPause.setOnClickListener(v -> onPauseClick());
        updatePauseIcon();

        // ---- 消息框开关（实体按钮）：点一下直接把消息框显示出来 ----
        // 场景：消息框被「收起」或被输入框临时隐藏后，用这个按钮一键叫回来。
        btnMsg = Ui.iconButton(this, R.drawable.ic_chat, Ui.accent(this), 38);
        btnMsg.setId(View.generateViewId());
        btnMsg.setTag("m");
        btnMsg.setContentDescription("显示消息框");
        RelativeLayout.LayoutParams mlp = new RelativeLayout.LayoutParams(bs, bs);
        mlp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        mlp.addRule(RelativeLayout.BELOW, btnPause.getId());
        mlp.topMargin = Ui.dp(this, 16);
        btnMsg.setLayoutParams(mlp);
        btnMsg.setOnClickListener(v -> showChatPanel());
        btnMsg.setOnLongClickListener(v -> {
            // 长按 = 收起（与顶栏「−」等价），方便一键来回切换
            setChatCollapsed(true);
            showCenterTip("已收起消息框（点按钮再叫回来）");
            return true;
        });

        side.addView(btnSettings);
        side.addView(btnHold);
        side.addView(btnSend);
        side.addView(btnClear);
        side.addView(btnPause);
        side.addView(btnMsg);

        root.addView(content);
        // 注：按钮层 side 故意放在【聊天面板之后】添加（见下面 root.addView(side)），
        //     这样它的 z 序在面板之上——面板万一被拖到按钮上，也仍是按钮优先（点得到）。

        // ---- 聊天面板：PC 发来的消息显示在这里（仿聊天布局），把手可进入调节 ----
        chatWrap = new LinearLayout(this);
        chatWrap.setOrientation(LinearLayout.VERTICAL);
        chatWrap.setBackground(Ui.round(0xE6161A21, Ui.border(this), 1, Ui.dp(this, 12)));
        FrameLayout.LayoutParams chatLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 320),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        chatLp.leftMargin = Ui.dp(this, 8);
        chatLp.rightMargin = Ui.dp(this, 8);
        chatLp.bottomMargin = Ui.dp(this, 8);
        chatWrap.setLayoutParams(chatLp);

        LinearLayout chatTop = new LinearLayout(this);
        chatTop.setOrientation(LinearLayout.HORIZONTAL);
        chatTop.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        chatTop.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ImageButton chatHandle = new ImageButton(this);
        int chs = Ui.dp(this, 30);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(chs, chs);
        chp.rightMargin = Ui.dp(this, 6);
        chp.topMargin = Ui.dp(this, 4);
        chatHandle.setLayoutParams(chp);
        chatHandle.setImageResource(R.drawable.kv_handle);
        chatHandle.setBackground(Ui.buttonBg(Ui.surfaceHi(this), Ui.primaryDim(this), chs / 2f));
        chatHandle.setContentDescription("调节聊天面板");
        chatHandle.setOnClickListener(v -> toggleChatAdjust());
        chatTop.addView(chatHandle);

        // 一键恢复原始布局按钮（始终可见）：把输入框 + 聊天面板 + 五个按钮全部复位到默认位置/大小/方向
        ImageButton chatReset = new ImageButton(this);
        int crs = Ui.dp(this, 30);
        LinearLayout.LayoutParams crlp = new LinearLayout.LayoutParams(crs, crs);
        crlp.rightMargin = Ui.dp(this, 6);
        crlp.topMargin = Ui.dp(this, 4);
        chatReset.setLayoutParams(crlp);
        chatReset.setImageResource(R.drawable.ic_reset);
        chatReset.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        chatReset.setBackground(Ui.buttonBg(Ui.surfaceHi(this), Ui.primaryDim(this), crs / 2f));
        chatReset.setContentDescription("恢复原始布局（输入框/面板/按钮）");
        chatReset.setOnClickListener(v -> resetAllLayout());
        chatTop.addView(chatReset, 0);   // 放在调节把手前面（更靠左）

        // 收起按钮：把整个消息框收成悬浮按钮（点悬浮按钮再展开）
        ImageButton chatCollapse = new ImageButton(this);
        int ccs = Ui.dp(this, 30);
        LinearLayout.LayoutParams cclp = new LinearLayout.LayoutParams(ccs, ccs);
        cclp.rightMargin = Ui.dp(this, 6);
        cclp.topMargin = Ui.dp(this, 4);
        chatCollapse.setLayoutParams(cclp);
        chatCollapse.setImageResource(R.drawable.ic_minus);
        chatCollapse.setColorFilter(Ui.text2(this));
        chatCollapse.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        chatCollapse.setBackground(Ui.buttonBg(Ui.surfaceHi(this), Ui.primaryDim(this), ccs / 2f));
        chatCollapse.setContentDescription("收起消息框");
        chatCollapse.setOnClickListener(v -> setChatCollapsed(true));
        chatTop.addView(chatCollapse, 0);   // 最左侧
        chatWrap.addView(chatTop);

        // 面板主体（头部 + 消息列表）：翻转时只旋转它，顶栏 chatTop 保持常驻，
        // 这样即使聊天面板“上下颠倒”，调节把手与“恢复原始布局”按钮也始终在屏内、可点击。
        chatBody = new LinearLayout(this);
        chatBody.setOrientation(LinearLayout.VERTICAL);
        chatBody.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        chatWrap.addView(chatBody);

        // 面板头部：显示本机名称 + 同局域网在线设备数（含本机），随发现结果实时刷新
        chatHeader = new TextView(this);
        chatHeader.setTextSize(12);
        chatHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        chatHeader.setTextColor(Ui.text2(this));
        chatHeader.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 4));
        chatHeader.setBackground(Ui.round(Ui.surface(this), Ui.border(this), 1, 0f));
        chatBody.addView(chatHeader);

        chatScroll = new android.widget.ScrollView(this);
        chatScroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        chatRows = new LinearLayout(this);
        chatRows.setOrientation(LinearLayout.VERTICAL);
        chatScroll.addView(chatRows);
        chatBody.addView(chatScroll);

        // 调节态触摸拦截层：盖住整个面板接管拖动手势
        chatShield = new View(this);
        chatShield.setVisibility(View.GONE);
        chatShield.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        chatShield.setOnTouchListener(this::onChatTouch);
        chatWrap.addView(chatShield);

        root.addView(chatWrap);   // 先加面板
        // 再加按钮层：z 序在面板之上（普通态触摸穿透，右侧按钮优先——
        // 面板被拖到按钮位置时也不会把按钮的点击吃掉）
        root.addView(side);

        // 收起态悬浮按钮：消息框收起后用一个圆形按钮占位；点击展开回面板
        chatFab = new ImageButton(this);
        int fabS = Ui.dp(this, 52);
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(fabS, fabS,
                Gravity.BOTTOM | Gravity.END);
        fabLp.rightMargin = Ui.dp(this, 14);
        fabLp.bottomMargin = Ui.dp(this, 14);
        chatFab.setLayoutParams(fabLp);
        chatFab.setImageResource(R.drawable.ic_chat);
        chatFab.setColorFilter(Color.WHITE);
        chatFab.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        chatFab.setBackground(Ui.buttonBg(Ui.primary(this), Ui.primaryPress(this), (float) fabS / 2f));
        chatFab.setElevation(Ui.dp(this, 8));
        chatFab.setContentDescription("展开消息框");
        chatFab.setOnClickListener(v -> setChatCollapsed(false));
        chatFab.setVisibility(View.GONE);
        root.addView(chatFab);

        // 聊天调节工具条（放大/缩小/翻转/复位/完成）
        chatTuner = new LinearLayout(this);
        chatTuner.setOrientation(LinearLayout.VERTICAL);
        chatTuner.setBackground(Ui.round(Ui.surface(this), Ui.border(this), 1, Ui.dp(this, 12)));
        chatTuner.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        chatTuner.setVisibility(View.GONE);
        LinearLayout cr1 = new LinearLayout(this);
        cr1.setOrientation(LinearLayout.HORIZONTAL);
        cr1.addView(tunerBtn(this, R.drawable.ic_plus, "放大面板", v -> scaleChat(1.12f)));
        cr1.addView(tunerBtn(this, R.drawable.ic_minus, "缩小面板", v -> scaleChat(1f / 1.12f)));
        LinearLayout cr2 = new LinearLayout(this);
        cr2.setOrientation(LinearLayout.HORIZONTAL);
        cr2.addView(tunerBtn(this, R.drawable.ic_flip, "上下颠倒", v -> toggleChatFlip()));
        cr2.addView(tunerBtn(this, R.drawable.ic_reset, "复位", v -> resetChatLayout()));
        cr2.addView(tunerBtn(this, R.drawable.ic_check, "完成", v -> exitChatAdjust()));
        chatTuner.addView(cr1);
        chatTuner.addView(cr2);
        FrameLayout.LayoutParams ctlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        ctlp.rightMargin = Ui.dp(this, 4);
        chatTuner.setLayoutParams(ctlp);
        root.addView(chatTuner);

        // 恢复聊天面板布局（等首帧布局完成）
        chatWrap.post(this::applyChatLayout);

        // ---- 左侧竖排短提示层 ----
        // 位置：本页是 reversePortrait，Activity 内容在屏幕上被旋转 180°，
        //   视图坐标系的左边缘正好映射到【倒持后用户视野的左侧】，所以用 Gravity.LEFT。
        // 方向：TextView 加 -90° 旋转，使文字竖向排列（叠加 Activity 的 180° 后，
        //   用户看到的是自上而下阅读）。
        // 布局：overlay 按【旋转后的占位】给定宽高（窄高条），避免旋转后被裁剪。
        overlay = new FrameLayout(this);
        FrameLayout.LayoutParams ovLp = new FrameLayout.LayoutParams(
                Ui.dp(this, TIP_BAR_W_DP), Ui.dp(this, TIP_BAR_H_DP),
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
        overlay.setLayoutParams(ovLp);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(false);

        tip = new TextView(this);
        tip.setTextSize(15);
        tip.setTypeface(null, android.graphics.Typeface.BOLD);
        // 深色主题下提示底是深色 -> 用白字；浅色主题提示底是白色 -> 跟随主题文字色
        tip.setTextColor(Ui.isLight() ? Ui.text(this) : Color.WHITE);
        tip.setGravity(Gravity.CENTER);
        tip.setMaxWidth(Ui.dp(this, 72));
        tip.setBackground(Ui.round(Ui.surfaceHi(this), Ui.border(this), 1, Ui.dp(this, 12)));
        tip.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        FrameLayout.LayoutParams tipLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        tip.setLayoutParams(tipLp);
        overlay.addView(tip);
        // 不裁剪子 View，保证旋转后的 TextView 完整可见
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);
        root.setClipChildren(false);
        root.setClipToPadding(false);   // 全局移动：子元素可越过 16dp 内边距绘制
        root.addView(overlay);

        // ---- 输入框调节面板（长按输入框 5 秒后出现）----
        // 放在左侧，与提示层同侧；不使用 WRAP_CONTENT 定位，避免长文本时被推走。
        tuner = new LinearLayout(this);
        tuner.setOrientation(LinearLayout.VERTICAL);
        tuner.setBackground(Ui.round(Ui.surface(this), Ui.border(this), 1, Ui.dp(this, 12)));
        tuner.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        tuner.setVisibility(View.GONE);
        tuner.setClipChildren(false);
        FrameLayout.LayoutParams tunerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.LEFT | Gravity.CENTER_VERTICAL);
        tunerLp.leftMargin = Ui.dp(this, 4);
        tuner.setLayoutParams(tunerLp);

        TextView tunerTitle = new TextView(this);
        tunerTitle.setText("调节输入框");
        tunerTitle.setTextSize(12);
        tunerTitle.setTypeface(null, android.graphics.Typeface.BOLD);
        tunerTitle.setTextColor(Ui.text2(this));
        tunerTitle.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), Ui.dp(this, 6));
        tuner.addView(tunerTitle);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        View tunerFlip = tunerBtn(this, R.drawable.ic_flip, "上下颠倒", v -> toggleInputFlip());
        View tunerBig = tunerBtn(this, R.drawable.ic_plus, "放大", v -> scaleInput(1.12f));
        row1.addView(tunerFlip); row1.addView(tunerBig);
        tuner.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        View tunerSmall = tunerBtn(this, R.drawable.ic_minus, "缩小", v -> scaleInput(1f / 1.12f));
        View tunerReset = tunerBtn(this, R.drawable.ic_reset, "复位", v -> resetInputLayout());
        View tunerOk = tunerBtn(this, R.drawable.ic_check, "完成", v -> exitInputAdjust());
        row2.addView(tunerSmall); row2.addView(tunerReset); row2.addView(tunerOk);
        tuner.addView(row2);
        root.addView(tuner);

        setContentView(root);

        // 在首次布局完成后再还原存档位置：此时通过布局规则算出的按钮 left/top 已确定，
        // 才能按容器当前宽高正确重投影，避免按钮落到容器外。
        side.post(() -> {
            side.restorePositions(cfg.layoutJson());
            // 兜底：如果还原后按钮仍不在可视区内，直接复位为默认规则布局。
            side.ensureVisible(this::showCenterTip);
            // 还原输入框的位移/缩放/颠倒（必须等 inputWrap 测量完成后再做，否则 translation 会被后续布局冲掉）
            inputWrap.post(this::applyInputLayout);
        });

        // 输入框：5 秒长按进入调节模式（普通态不消费事件，正常输入不受影响）
        et.setOnTouchListener((vv, ev) -> onInputTouch(vv, ev));
        // 输入框获焦（被点击/开始输入）时，临时隐藏消息框：输入场景下不需要消息框占用空间
        et.setOnFocusChangeListener((v, hasFocus) -> {
            chatHiddenByInput = hasFocus;
            updateChatVisibility();
        });
        // 发送后「清空输入框」保护窗口：刚发送完的几秒内，输入法（讯飞整句修正）若把内容
        // 写回输入框，这里立刻再清一次，避免出现“发送后没清空”。
        // 用户一旦主动点输入框（想自己打字），dispatchTouchEvent 会把窗口清零，不会误删。
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                if (System.currentTimeMillis() > clearGuardUntil) return;
                if (s == null || s.length() == 0) return;
                et.setText("");
            }
        });

        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable s) {
                // 自动发送已取消：识别出的文字只落在输入框，
                // 由用户点「电脑上屏」按钮手动发送
            }
        });

        // Android 13+ 强制要求：运行时注册非系统广播接收器必须显式声明导出性，
        // 否则直接 SecurityException 崩溃（Android 15 实测）。两个 action 都是自有广播，
        // 且发送端 setPackage(getPackageName()) 为显式 intent，NOT_EXPORTED 可正常收到。
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(commit, new IntentFilter("com.keyvoice.COMMIT"),
                    android.content.Context.RECEIVER_NOT_EXPORTED);
            registerReceiver(result, new IntentFilter(VoiceService.ACTION_RESULT),
                    android.content.Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(commit, new IntentFilter("com.keyvoice.COMMIT"));
            registerReceiver(result, new IntentFilter(VoiceService.ACTION_RESULT));
        }

        // PC → 手机：启动局域网接收服务（端口 17892）。
        // 消息进聊天面板（来源名 + 头像），不再写入输入框。
        LanServer.start((text, from) -> postPcMessage(text, from));

        // 手机间互发现 + 群发：启动发现（含本机名），挂设备数刷新回调
        PeerManager.get().setName(cfg.deviceName());
        PeerManager.get().start(this);
        peerListener = this::updatePeerHeader;
        PeerManager.get().addListener(peerListener);
        updatePeerHeader();
    }

    /** 刷新聊天面板头部的「本机名 + 同局域网设备数量」 */
    private void updatePeerHeader() {
        if (chatHeader == null) return;
        int total = PeerManager.get().totalCount();
        String name = PeerManager.get().name();
        chatHeader.setText("本机 " + name + " ｜ 局域网在线 " + total + " 台（含本机）");
    }

    /** PC → 手机：把电脑发来的文字插入输入框光标处 + 存剪贴板 + 提示 */
    /** PC → 手机：消息进聊天面板（仿聊天布局）+ 存剪贴板；不再写入输入框 */
    private void handlePcMessage(String text, String from) {
        if (text == null || text.isEmpty()) return;
        Log.i("KeyVoiceMsg", "收到消息 from=" + from + " text=" + text);
        renderChatMessage(from, text);
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("keyvoice", text));
        } catch (Exception ignored) { }
    }

    /** 页面不在前台时暂存的电脑消息：回到主页面立即入聊天面板 */
    private static volatile String[] pendingPcMessage = null;
    /** 应用级 Context */
    private static android.content.Context appCtx;

    /** 供 LanServer 投递：前台直接处理；不在前台则暂存（回前台自动显示） */
    static boolean postPcMessage(String text, String from) {
        MainActivity a = resumed;
        if (a != null) {
            a.runOnUiThread(() -> a.handlePcMessage(text, from));
            return true;
        }
        pendingPcMessage = new String[]{text, from};
        return false;
    }

    /**
     * 居中短提示 —— 已按需求【取消所有弹窗提示】（含“等待输入法弹出…”等）。
     * 保留方法签名以兼容全部调用点，方法体静默：不再显示任何提示条。
     */
    private void showCenterTip(String msg) {
        // 弹窗提示已全部取消：静默处理
    }

    /**
     * 把文字改成「上下排列」：每个字符单独一行，自上而下堆叠，**字形保持正立**。
     * <p>
     * 之所以不用「整体旋转 90°」的方案：那种做法字符是躺倒的，阅读困难；
     * 中文竖排的正确形态是逐字正立、纵向排列。保留原有换行，避免把分段打乱。
     */
    private static String verticalize(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() * 2);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c);
            if (c == '\n') continue;              // 原有换行保留
            if (i < s.length() - 1) sb.append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------- 输入框调节（长按 5 秒）

    /**
     * 输入框触摸拦截。
     * <p>
     * 普通态：只做「长按 5 秒」检测，不消费事件，保留正常输入与系统粘贴菜单；
     * 调节态：全消费，由自己接管拖动（位移输入框）。
     * <p>
     * 之所以要 5 秒：EditText 的长按约 500ms 就会触发文字选择/粘贴菜单，
     * 与调节手势冲突，所以把调节阈值提到 5 秒以上来错开。
     */
    private boolean onInputTouch(android.view.View v, MotionEvent ev) {
        if (inputAdjustActive) {
            inputAdjustMove(ev);
            return true;      // 调节态全消费，别让 EditText 抢走
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                inputDownTime = System.currentTimeMillis();
                inputDownX = inputLastX = ev.getX();
                inputDownY = inputLastY = ev.getY();
                h.removeCallbacks(inputLongPressTask);
                h.postDelayed(inputLongPressTask, INPUT_LONG_PRESS_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(ev.getX() - inputDownX) > ADJUST_SLOP
                        || Math.abs(ev.getY() - inputDownY) > ADJUST_SLOP) {
                    h.removeCallbacks(inputLongPressTask);   // 手指滑动了，判定为选中文字而非长按调节
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(inputLongPressTask);
                break;
        }
        return false;         // 普通态不消费，EditText 照常工作
    }

    private final Runnable inputLongPressTask = this::enterInputAdjust;

    private void inputAdjustMove(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                inputLastX = ev.getRawX();
                inputLastY = ev.getRawY();
                inputReanchor = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (ev.getPointerCount() >= 2) return;   // 双指留给（将来）缩放
                // 由“长按 5 秒”进入调节态时，手指仍按在原地，inputLastX/Y 还是进入调节前
                // 的【局部坐标】，与这里的 getRawX/Y（屏幕坐标）量级不一致，直接相减会让
                // inputWrap 瞬间位移一 huge 值 → 飞出屏幕（表现为“移动时消失”）。
                // 故第一次移动先只做锚点校准，不真正位移。
                if (inputReanchor) {
                    inputLastX = ev.getRawX();
                    inputLastY = ev.getRawY();
                    inputReanchor = false;
                    return;
                }
                inputWrap.setTranslationX(inputWrap.getTranslationX() + (ev.getRawX() - inputLastX));
                inputWrap.setTranslationY(inputWrap.getTranslationY() + (ev.getRawY() - inputLastY));
                inputLastX = ev.getRawX();
                inputLastY = ev.getRawY();
                clampInputTranslation();   // 拖动中也钳制，保证永远拖不出屏幕
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                saveInputLayout();
                break;
        }
    }

    /** 进入输入框调节模式：显示面板 + 虚化边框 */
    private void enterInputAdjust() {
        h.removeCallbacks(inputLongPressTask);
        inputAdjustActive = true;
        inputReanchor = true;   // 进入调节态后第一次移动先做锚点校准，避免输入框瞬移
        tuner.setVisibility(View.VISIBLE);
        tuner.bringToFront();
        // 3dp 亮青色描边 + 微弱青色底，调节态下清晰可见（原来 2dp 蓝色太淡）
        inputWrap.setBackground(Ui.round(0x3322D3EE, Ui.accent(this), 3, Ui.dp(this, 12)));
        overlay.setVisibility(View.GONE);
        showCenterTip("调节模式：拖动移动 · 面板可颠倒/缩放/复位");
    }

    /** 退出调节模式并保存布局 */
    private void exitInputAdjust() {
        if (!inputAdjustActive) return;
        inputAdjustActive = false;
        tuner.setVisibility(View.GONE);
        inputWrap.setBackground(null);
        saveInputLayout();
        showCenterTip("输入框布局已保存");
    }

    private void toggleInputFlip() {
        boolean f = !cfg.inputFlip();
        cfg.inputFlip(f);
        inputWrap.setRotation(f ? 180f : 0f);
        saveInputLayout();
        if (f) syncInvertOnFlip();
    }

    /**
     * 输入框允许的最大缩放：缩放后必须完整落在父容器（即屏幕可用区）内。
     * 输入框宽度 = 父容器宽度，所以宽度方向的上限就是 1.0（再大必然超出屏幕），
     * 纵向则由父容器高度与输入框高度之比决定。
     */
    private float maxInputScale() {
        if (inputWrap == null) return INPUT_SCALE_MAX;
        ViewGroup p = (ViewGroup) inputWrap.getParent();
        if (p == null || p.getWidth() <= 0 || p.getHeight() <= 0) return INPUT_SCALE_MAX;
        int w = inputWrap.getWidth(), hgt = inputWrap.getHeight();
        if (w <= 0 || hgt <= 0) return INPUT_SCALE_MAX;
        float byW = p.getWidth() / (float) w;
        float byH = p.getHeight() / (float) hgt;
        return clamp(Math.min(INPUT_SCALE_MAX, Math.min(byW, byH)), INPUT_SCALE_MIN, INPUT_SCALE_MAX);
    }

    private void scaleInput(float factor) {
        float cap = maxInputScale();
        float cur = inputWrap.getScaleX();
        float s = clamp(cur * factor, INPUT_SCALE_MIN, cap);
        if (factor > 1f && cur >= cap - 0.001f) {
            showCenterTip("已放大到屏幕上限");
        }
        inputWrap.setScaleX(s);
        inputWrap.setScaleY(s);
        clampInputTranslation();   // 缩放后同步钳制位移，保证不出屏
        saveInputLayout();
    }

    private void resetInputLayout() {
        cfg.inputTx(0f); cfg.inputTy(0f); cfg.inputScale(1f); cfg.inputFlip(false);
        applyInputLayout();
        showCenterTip("输入框布局已复位");
    }

    private void saveInputLayout() {
        if (cfg == null) return;
        cfg.inputTx(inputWrap.getTranslationX());
        cfg.inputTy(inputWrap.getTranslationY());
        cfg.inputScale(inputWrap.getScaleX());
    }

    /** 恢复输入框的位移/缩放/颠倒（来自持久化配置） */
    private void applyInputLayout() {
        if (inputWrap == null || cfg == null) return;
        float tx = cfg.inputTx(), ty = cfg.inputTy();
        // 存档合法性交给底部的 clampInputTranslation()（全局移动范围，允许负位移），
        // 不能再把负值/越界值直接归零——那会把用户「向上/向左移过」的合法位置抹掉。
        inputWrap.setTranslationX(tx);
        inputWrap.setTranslationY(ty);
        float s = cfg.inputScale();
        if (s < INPUT_SCALE_MIN || s > INPUT_SCALE_MAX) s = 1f;
        s = clamp(s, INPUT_SCALE_MIN, maxInputScale());   // 历史存档里过大的缩放也压回屏幕内
        inputWrap.setScaleX(s);
        inputWrap.setScaleY(s);
        inputWrap.setRotation(cfg.inputFlip() ? 180f : 0f);
        clampInputTranslation();
    }

    /**
     * 全局移动钳制（元素完全留在屏幕内）：
     * 可拖到屏幕上的任意位置，但【任何一边都不允许越出屏幕边界】——
     * 既不能整块飞出去，也不能只露一条边。
     * <p>
     * 坐标推导：元素某边缘在屏幕上的坐标 = 布局基位(base) + translation，
     * 要落在 [0, 屏幕宽-元素宽] 内 → translation ∈ [-base, 屏幕宽-元素宽-base]。
     * <p>
     * 注意：输入框宽度≈屏幕宽，因此横向可移量极小（基本贴边）；竖向可全屏移动。
     * 若想要输入框也能横滑，需要把它改窄（如 90% 宽），可另行调整。
     */
    private void clampGlobalMove(View v) {
        if (v == null || rootView == null) return;
        int cw = rootView.getWidth(), ch = rootView.getHeight();
        if (cw <= 0 || ch <= 0) return;
        int w = v.getWidth(), hgt = v.getHeight();
        if (w <= 0 || hgt <= 0) return;
        float sx = v.getScaleX(), sy = v.getScaleY();
        if (sx > 0f) w = Math.round(w * sx);      // 按缩放后的实际占位计算
        if (sy > 0f) hgt = Math.round(hgt * sy);
        int baseLeft = layoutOffsetIn(v, rootView, true);    // 元素相对 root 的布局基位
        int baseTop = layoutOffsetIn(v, rootView, false);
        v.setTranslationX(clamp(v.getTranslationX(), -baseLeft, cw - w - baseLeft));
        v.setTranslationY(clamp(v.getTranslationY(), -baseTop, ch - hgt - baseTop));
    }

    /** 元素相对 ancestor 的布局基位（累加 getLeft/getTop；不含 translation） */
    private static int layoutOffsetIn(View v, View ancestor, boolean horizontal) {
        int d = 0;
        View cur = v;
        while (cur != null && cur != ancestor) {
            d += horizontal ? cur.getLeft() : cur.getTop();
            android.view.ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            cur = (View) p;
        }
        return d;
    }

    private void clampInputTranslation() {
        clampGlobalMove(inputWrap);
    }

    // ------------------------------------------------------- 聊天面板调节与渲染

    /** 聊天面板触摸（仅调节态）：拖动移动面板 */
    private boolean onChatTouch(View v, MotionEvent ev) {
        if (!chatAdjustActive) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                chatLastX = ev.getRawX();
                chatLastY = ev.getRawY();
                chatReanchor = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (chatReanchor) {   // 进入调节态后的第一次移动只校准锚点
                    chatLastX = ev.getRawX();
                    chatLastY = ev.getRawY();
                    chatReanchor = false;
                    return true;
                }
                chatWrap.setTranslationX(chatWrap.getTranslationX() + (ev.getRawX() - chatLastX));
                chatWrap.setTranslationY(chatWrap.getTranslationY() + (ev.getRawY() - chatLastY));
                chatLastX = ev.getRawX();
                chatLastY = ev.getRawY();
                clampChatTranslation();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                saveChatLayout();
                break;
        }
        return true;
    }

    private void toggleChatAdjust() {
        if (chatAdjustActive) exitChatAdjust();
        else enterChatAdjust();
    }

    private void enterChatAdjust() {
        chatAdjustActive = true;
        chatReanchor = true;
        chatShield.setVisibility(View.VISIBLE);
        chatShield.bringToFront();
        chatTuner.setVisibility(View.VISIBLE);
        chatTuner.bringToFront();
    }

    private void exitChatAdjust() {
        chatAdjustActive = false;
        chatTuner.setVisibility(View.GONE);
        chatShield.setVisibility(View.GONE);
        saveChatLayout();
    }

    private void scaleChat(float factor) {
        float cur = chatWrap.getScaleX();
        float s = clamp(cur * factor, CHAT_SCALE_MIN, CHAT_SCALE_MAX);
        if (factor > 1f && cur >= CHAT_SCALE_MAX - 0.001f) return;
        chatWrap.setScaleX(s);
        chatWrap.setScaleY(s);
        clampChatTranslation();
        saveChatLayout();
    }

    private void toggleChatFlip() {
        boolean f = !cfg.chatFlip();
        cfg.chatFlip(f);
        chatBody.setRotation(f ? 180f : 0f);
        saveChatLayout();
        if (f) syncInvertOnFlip();
    }

    /**
     * 主页用到「翻转」（输入框/消息框颠倒）时，联动在设置里也开启「倒置显示」(反转)：
     * 很多用户开了元素翻转后就一直想让整屏也倒过来拿，这里一次性把设置项也置位并即时生效。
     */
    private void syncInvertOnFlip() {
        if (!cfg.invertMode()) {
            cfg.invertMode(true);
            applyOrientation();
            showCenterTip("已联动开启倒置显示（设置里同样生效）");
        }
    }

    private void resetChatLayout() {
        cfg.chatTx(0f);
        cfg.chatTy(0f);
        cfg.chatScale(1f);
        cfg.chatFlip(false);
        cfg.chatCollapsed(false);
        applyChatLayout();
    }

    /** 一键恢复原始 UI 布局：输入框 + 聊天面板 + 五个按钮全部回到默认位置/大小/方向 */
    private void resetAllLayout() {
        resetInputLayout();
        resetChatLayout();
        if (side != null) side.resetLayout();
        showCenterTip("已恢复原始布局");
    }

    /** 供设置页等外部入口调用（需主页面在前台）；跨页面时由 cfg 标记位在 onResume 消费 */
    static void requestResetLayout() {
        if (resumed != null) resumed.resetAllLayout();
    }

    private void saveChatLayout() {
        if (cfg == null || chatWrap == null) return;
        cfg.chatTx(chatWrap.getTranslationX());
        cfg.chatTy(chatWrap.getTranslationY());
        cfg.chatScale(chatWrap.getScaleX());
    }

    private void clampChatTranslation() {
        clampGlobalMove(chatWrap);   // 与输入框同一套「全局移动」规则（可拖到屏幕任意位置）
    }

    /** 恢复聊天面板的位移/缩放/颠倒（来自持久化配置） */
    private void applyChatLayout() {
        if (chatWrap == null || cfg == null) return;
        chatWrap.setTranslationX(cfg.chatTx());
        chatWrap.setTranslationY(cfg.chatTy());
        float s = cfg.chatScale();
        if (s < CHAT_SCALE_MIN || s > CHAT_SCALE_MAX) s = 1f;
        chatWrap.setScaleX(s);
        chatWrap.setScaleY(s);
        chatBody.setRotation(cfg.chatFlip() ? 180f : 0f);
        chatCollapsed = cfg.chatCollapsed();
        updateChatVisibility();
        clampChatTranslation();
    }

    /** 设置聊天面板是否收起为悬浮按钮（持久化） */
    private void setChatCollapsed(boolean collapsed) {
        chatCollapsed = collapsed;
        if (cfg != null) cfg.chatCollapsed(collapsed);
        updateChatVisibility();
    }

    /** 根据 收起态 + 输入框焦点态 决定面板/悬浮按钮的可见性 */
    private void updateChatVisibility() {
        boolean showPanel = !chatCollapsed && !chatHiddenByInput;
        if (chatWrap != null)
            chatWrap.setVisibility(showPanel ? View.VISIBLE : View.GONE);
        if (chatFab != null)
            chatFab.setVisibility((chatCollapsed && !chatHiddenByInput) ? View.VISIBLE : View.GONE);
    }

    /** 实体按钮：直接显示消息框（收起态展开 + 解除输入框的临时隐藏） */
    private void showChatPanel() {
        chatHiddenByInput = false;
        setChatCollapsed(false);          // 内部会调 updateChatVisibility 并持久化
        showCenterTip("已显示消息框");
    }

    // ------------------------------------------------ 点击非输入框区域 → 输入框失焦 + 消息框回来
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            // 只在 DOWN 记录“这次点的是不是输入框”，不做任何视图改动
            downOutsideInput = !hitInputArea(ev);
            if (!downOutsideInput) {
                // 用户主动点输入框（想自己打字）→ 结束“发送后清空”保护，不干扰输入
                clearGuardUntil = 0L;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (downOutsideInput) {
                // ⚠ 必须等手势结束（UP/CANCEL）后再改可见性：
                //    如果在 DOWN 的同一帧就把消息框弹出来，视图树会重排，
                //    这次手势的命中目标会变（消息框 z 序高于按钮层）——
                //    实测后果是「发送」等按钮这次点不动、点了没反应。
                h.post(this::releaseInputFocus);
            }
            downOutsideInput = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 触摸点是否落在输入区（输入框本体或其外框）内 —— 屏幕坐标比对，兼容倒置/位移 */
    private boolean hitInputArea(MotionEvent ev) {
        return hitView(et, ev) || hitView(inputWrap, ev);
    }

    private boolean hitView(View v, MotionEvent ev) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        android.graphics.Rect r = new android.graphics.Rect();
        if (!v.getGlobalVisibleRect(r)) return false;
        return r.contains((int) ev.getRawX(), (int) ev.getRawY());
    }

    /** 输入框失焦：光标消失 + 收起输入法 + 消息框重新显示 */
    private void releaseInputFocus() {
        if (et != null && et.hasFocus()) {
            et.clearFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
        }
        // 焦点回调通常已经把 chatHiddenByInput 置回 false，这里再兜底同步一次
        if (chatHiddenByInput) {
            chatHiddenByInput = false;
            updateChatVisibility();
        }
    }

    /** 聊天面板加一条消息（仿微信布局：圆头像 + 名称 + 气泡） */
    private void renderChatMessage(String from, String text) {
        if (chatRows == null || text == null) return;
        String who = (from == null || from.trim().isEmpty()) ? "电脑" : from.trim();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4));

        TextView avatar = new TextView(this);
        int as = Ui.dp(this, 34);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(as, as);
        alp.gravity = Gravity.TOP;
        avatar.setLayoutParams(alp);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTextSize(14);
        avatar.setTypeface(null, android.graphics.Typeface.BOLD);
        avatar.setTextColor(Color.WHITE);
        avatar.setText(who.substring(0, 1));
        avatar.setBackground(Ui.buttonBg(Ui.primary(this), Ui.primaryPress(this), as / 2f));
        row.addView(avatar);

        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setBackground(Ui.round(Ui.surfaceHi(this), Ui.border(this), 1, Ui.dp(this, 10)));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = Ui.dp(this, 8);
        blp.topMargin = Ui.dp(this, 2);
        bubble.setLayoutParams(blp);
        bubble.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        TextView tvName = new TextView(this);
        tvName.setText(who);
        tvName.setTextSize(11);
        tvName.setTextColor(Ui.text3(this));
        bubble.addView(tvName);
        TextView tvMsg = new TextView(this);
        tvMsg.setText(text);
        tvMsg.setTextSize(14);
        tvMsg.setTextColor(Ui.text(this));
        tvMsg.setMaxWidth(Ui.dp(this, 240));
        bubble.addView(tvMsg);
        row.addView(bubble);

        // 「复制」按钮：把这一条消息直接写进手机剪贴板（复制即用，不用先选中文本）
        TextView btnCopy = new TextView(this);
        btnCopy.setText("复制");
        btnCopy.setTextSize(11);
        btnCopy.setTextColor(Ui.primary(this));
        btnCopy.setPadding(Ui.dp(this, 9), Ui.dp(this, 5), Ui.dp(this, 9), Ui.dp(this, 5));
        btnCopy.setBackground(Ui.round(Ui.surfaceHi(this), Ui.border(this), 1, Ui.dp(this, 8)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = Ui.dp(this, 6);
        clp.topMargin = Ui.dp(this, 2);
        clp.gravity = Gravity.TOP;
        btnCopy.setLayoutParams(clp);
        btnCopy.setContentDescription("复制这条消息");
        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) return;
            cm.setPrimaryClip(ClipData.newPlainText("keyvoice", text));
            showCenterTip("已复制到剪贴板");
        });
        row.addView(btnCopy);

        chatRows.addView(row);
        chatRows.post(() -> {
            if (chatScroll != null) chatScroll.fullScroll(View.FOCUS_DOWN);
        });
    }

    /** 调节面板上的小按钮，外观与主页面圆形按钮体系一致 */
    private View tunerBtn(android.content.Context c, int iconRes, String desc,
                          android.view.View.OnClickListener cmd) {
        ImageButton b = new ImageButton(c);
        int sz = Ui.dp(c, 36);
        b.setLayoutParams(new LinearLayout.LayoutParams(sz, sz));
        b.setImageResource(iconRes);
        b.setColorFilter(Ui.text2(c));
        b.setBackground(Ui.buttonBg(Ui.surfaceHi(c), Ui.primaryDim(c),
                (float) Ui.dp(c, 36) / 2f));
        b.setContentDescription(desc);
        b.setOnClickListener(cmd);
        return b;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 按配置决定屏幕方向。
     * <p>
     * 本应用只支持【竖屏】与【翻转竖屏】，永不横屏：
     * 默认锁定 {@code SCREEN_ORIENTATION_PORTRAIT}（旋转手机也不会横过来）；
     * 设置里开启「倒置显示」后锁定 {@code SCREEN_ORIENTATION_REVERSE_PORTRAIT}（180° 反向竖屏）。
     */
    private void applyOrientation() {
        if (cfg == null) return;
        setRequestedOrientation(cfg.invertMode()
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    @Override
    public void onBackPressed() {
        // 调节态下返回键优先退出调节，而不是直接退出 App
        if (inputAdjustActive) { exitInputAdjust(); return; }
        super.onBackPressed();
    }

    /**
     * 供后台服务（KeyTriggerService / VoiceService）投递提示。
     * 只有本页正处于前台时才显示到 overlay，否则交给 Trigger 回退成 Toast
     * （锁屏/后台触发时仍要有可见反馈）。
     */
    static boolean postTip(String msg) {
        MainActivity a = resumed;
        if (a == null) return false;
        a.runOnUiThread(() -> a.showCenterTip(msg));
        return true;
    }

    /**
     * 模拟长按按钮（话筒）：一次点击连续执行三步——
     *   ① 清空输入框内容（每次听写都从干净状态开始）
     *   ② 把光标落到输入框上（讯飞识别结果才会写进来）
     *   ③ 长按空格，开始「按住说话」
     * 松开动作交给「电脑上屏」按钮（见 onSendClick）。
     */
    private void onHoldClick() {
        if (KeyTriggerService.holding) {
            showCenterTip("正在说话中；完成后点「电脑上屏」松开并上屏");
            return;
        }
        if (KeyTriggerService.instance == null) {
            showCenterTip("需要无障碍服务（注入手势用）。点右下角箭头去开启");
            return;
        }
        // ① 清空输入框：新一次听写不残留上一次的内容
        clearGuardUntil = 0L;               // 手动清空后不再需要“发送后清空”保护
        if (et != null) et.setText("");
        // ② 点击输入框：请求焦点 + 拉起输入法（连续动作，故用 post 让焦点先落地再拉键盘）
        if (et != null) {
            et.requestFocus();
            et.setSelection(et.getText() == null ? 0 : et.getText().length());
        }
        chatHiddenByInput = true;           // 输入场景：消息框先让位
        updateChatVisibility();
        h.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT);
        }, 100);
        // ③ 长按空格（开始注入按住）
        if (!KeyTriggerService.instance.beginHoldFromUi()) {
            showCenterTip("上一次长按正在复位中，稍等 1 秒再点");
            return;
        }
        setHoldActive(true);
    }

    /** 用按钮底色反映「是否正在按住空格」：按住=黄，松开=蓝 */
    private void setHoldActive(boolean active) {
        int base = active ? Ui.warning(this) : Ui.primary(this);
        btnHold.setBackground(Ui.buttonBg(base, Ui.shade(base, -0.28f), (float) Ui.dp(this, 38) / 2f));
    }

    /**
     * 暂停 / 恢复长按（单键切换）。
     *   - 正在按住中 → 暂时松开（走与「电脑上屏」一致的 endHold，讯飞停止识别），进入暂停态
     *   - 暂停态     → 重新开始按住，恢复听写
     * 图标随之在 暂停(∮)/播放(▶) 之间切换。
     */
    private void onPauseClick() {
        if (paused) {
            // 恢复长按
            if (KeyTriggerService.instance == null) {
                showCenterTip("需要无障碍服务（注入手势用）。点右下角箭头去开启");
                return;
            }
            if (KeyTriggerService.holding) {          // 已被别处恢复，同步状态即可
                paused = false;
                updatePauseIcon();
                return;
            }
            if (KeyTriggerService.instance.beginHoldFromUi()) {
                paused = false;
                updatePauseIcon();
                showCenterTip("已恢复长按，请继续说话");
            } else {
                showCenterTip("上一次长按正在复位中，稍等 1 秒再点");
            }
            return;
        }

        // 暂停：松开长按
        if (KeyTriggerService.holding) {
            setHoldActive(false);
            if (KeyTriggerService.instance != null) KeyTriggerService.instance.endHold();
            paused = true;
            updatePauseIcon();
            showCenterTip("已暂停长按；再点一次恢复");
        } else {
            showCenterTip("当前没有正在按住的长按");
        }
    }

    /** 暂停态显示播放键（提示"点我恢复"），否则显示暂停键 */
    private void updatePauseIcon() {
        if (btnPause == null) return;
        btnPause.setImageResource(paused ? R.drawable.ic_play : R.drawable.ic_pause);
        btnPause.setContentDescription(paused ? "恢复长按" : "暂停长按");
    }

    /**
     * 电脑上屏按钮：若正在“按住空格”则先松开（讯飞停止并出字，COMMIT 广播到达后自动发送），
     * 否则直接把框内现有文字发到电脑。
     */
    private void onSendClick() {
        if (KeyTriggerService.holding) {
            setHoldActive(false);
            if (KeyTriggerService.instance != null) KeyTriggerService.instance.endHold();
            // 标记：松开后讯飞整句修正提交(COMMIT)时自动上屏，实现「一键松开+发送」
            pendingSendAfterRelease = true;
            showCenterTip("已松开，出字后自动上屏…");
            // 兜底：3 秒内未收到 COMMIT（如未识别到内容），直接用当前框内文字上屏
            h.postDelayed(() -> {
                if (pendingSendAfterRelease) {
                    pendingSendAfterRelease = false;
                    doSend(true);
                }
            }, 3000);
        } else if (pendingSendAfterRelease) {
            // ⚠ 关键：上一次「松开并发送」还在等识别出字（COMMIT 尚未到）时又点了发送。
            //    这时若直接 doSend，就会把同一条内容【再发一次】——电脑端表现为
            //    “一条消息收到两次、输入框里粘贴两次”。这里直接吞掉这次点击。
            showCenterTip("正在等识别出字，稍后会自动上屏…");
        } else {
            doSend(true);
        }
    }

    private void doSend(boolean force) {
        if (sending && !force) return;
        String text = et.getText() == null ? "" : et.getText().toString();
        if (text.trim().isEmpty()) {
            showCenterTip("没有内容可发送");
            return;
        }
        if (cfg.addNewline()) text += "\n";

        // 防重复发送（第二道保险）：同一条内容在 2.5 秒内只发一次。
        // 用户连点两次发送、或 COMMIT 广播与 3 秒兜底定时器撞车，都会重复走到这里。
        long nowMs = System.currentTimeMillis();
        String plain = text.trim();
        if (!plain.isEmpty() && plain.equals(lastSentPlain) && (nowMs - lastSentAt) < 2500) {
            showCenterTip("与刚发送的内容相同，已跳过重复发送");
            return;
        }
        lastSentPlain = plain;
        lastSentAt = nowMs;

        sending = true;
        setHoldActive(false);
        paused = false;
        updatePauseIcon();
        lastSent = text;
        String finalText = text;

        // 手机互发：把同一条消息广播给同局域网所有对端，并在本机聊天面板也显示（便于查看对话）
        final String myName = cfg.deviceName();
        PeerManager.get().broadcast(finalText, myName);
        runOnUiThread(() -> renderChatMessage(myName, finalText));

        // 发送后「清空输入框」保护窗口：讯飞整句修正常在松开后 1~3 秒才把整句写回框里，
        // 看起来就像“发送后没清空”。窗口期内被写回的文本由 TextWatcher 立刻再清一次。
        clearGuardUntil = System.currentTimeMillis() + CLEAR_GUARD_MS;

        Sender.send(this, text, (ok, msg) -> {
            sending = false;
            if (ok) {
                clearInputNow();       // 发送成功 → 立即清空输入框（光标 + 键盘一起收）
                showCenterTip("已发送：" + finalText);
            } else {
                // 发送失败也要执行松开长按的逻辑，否则长按残留会让讯飞一直处于录音态。
                // 内容不丢：框里若已被清空，就把刚发的内容放回去。
                releaseHold();
                clearGuardUntil = 0L;
                if (et != null && et.getText() != null && et.getText().toString().trim().isEmpty()) {
                    String back = finalText.endsWith("\n")
                            ? finalText.substring(0, finalText.length() - 1) : finalText;
                    et.setText(back);
                    et.setSelection(back.length());
                }
                showCenterTip("发送失败：" + msg);
            }
        });
    }

    /** 发送成功后清空输入框：内容清零 + 光标收起 + 键盘收起 + 消息框重新显示 */
    private void clearInputNow() {
        if (et == null) return;
        et.setText("");
        et.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
        chatHiddenByInput = false;
        updateChatVisibility();
    }

    /** 统一执行「松开长按」：复位按住态、结束无障碍注入、清除暂停标记 */
    private void releaseHold() {
        setHoldActive(false);
        if (KeyTriggerService.instance != null) KeyTriggerService.instance.endHold();
        paused = false;
        updatePauseIcon();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (onboardingActive && onboardWaitingReturn) {
            onboardWaitingReturn = false;
            advanceOnboarding();
        }
        if (!onboardingActive && cfg != null && !cfg.adbMode() && cfg.host().isEmpty() && !lanAutoScanned) {
            lanAutoScanned = true;
            autoLanConnect();
        }
        resumed = this;
        // 从设置页等发起的「恢复默认布局」请求，回到前台时统一执行
        if (cfg.layoutResetRequested()) {
            cfg.layoutResetRequested(false);
            resetAllLayout();
        }
        // 回到主页面：补投暂存的电脑消息
        if (pendingPcMessage != null) {
            String[] p = pendingPcMessage;
            pendingPcMessage = null;
            handlePcMessage(p[0], p[1]);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (resumed == this) resumed = null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_ONBOARD_MIC && onboardingActive) advanceOnboarding();
    }

    private void refreshStatus() {
        boolean accOk = KeyTriggerService.instance != null;
        boolean connected = cfg.adbMode() || !cfg.host().isEmpty();
        boolean abnormal = !accOk || !connected;
        statusDot.setBackgroundResource(abnormal ? R.drawable.dot_red : R.drawable.dot_green);
        android.graphics.drawable.Drawable d = statusDot.getBackground();
        if (d instanceof android.graphics.drawable.Animatable) ((android.graphics.drawable.Animatable) d).start();
        arrow.setVisibility(abnormal ? View.VISIBLE : View.GONE);
        setHoldActive(!KeyTriggerService.holding);
        // 已开启无障碍则记一次“已完成引导”，后续不再提示验证
        if (accOk) cfg.onboarded(true);
    }

    /** 局域网模式未连接时，自动搜索并连接第一台发现的电脑 */
    private void autoLanConnect() {
        final boolean[] done = {false};
        Discover.scan(this, 4000, new Discover.Sink() {
            @Override
            public void onFound(String ip, int port) {
                if (done[0]) return;
                done[0] = true;
                cfg.host(ip);
                cfg.port(port);
                runOnUiThread(() -> showCenterTip("已自动连接到 " + ip + ":" + port));
            }

            @Override
            public void onDone(int count) {
                if (!done[0]) runOnUiThread(() ->
                        showCenterTip("局域网未找到电脑；去「设置」手动搜索"));
            }
        });
    }

    // ------------------------------------------------- 首启权限引导

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

    private void advanceOnboarding() {
        if (!micGranted()) { showOnboardMic(); return; }
        if (!accEnabled()) { showOnboardAcc(); return; }
        if (!batteryIgnored()) { showOnboardBattery(); return; }
        finishOnboarding(true);
    }

    private void showOnboardMic() {
        new AlertDialog.Builder(this)
                .setTitle("权限引导 1/3 · 麦克风")
                .setMessage("「模拟长按」听写需要麦克风权限来录音。是否现在授权？")
                .setCancelable(false)
                .setPositiveButton("去授权", (d, w) ->
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_ONBOARD_MIC))
                .setNegativeButton("暂时跳过", (d, w) -> finishOnboarding(false))
                .show();
    }

    private void showOnboardAcc() {
        new AlertDialog.Builder(this)
                .setTitle("权限引导 2/3 · 无障碍服务")
                .setMessage("「模拟长按」通过无障碍服务向讯飞输入框注入手势，必须在系统设置里手动打开名为「a」的开关（KeyVoice 的无障碍服务）。\n\n点「去开启」后会跳到无障碍设置，找到 a 并打开开关，然后返回即可自动继续。")
                .setCancelable(false)
                .setPositiveButton("去开启", (d, w) -> {
                    onboardWaitingReturn = true;
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
                })
                .setNegativeButton("暂时跳过", (d, w) -> finishOnboarding(false))
                .show();
    }

    private void showOnboardBattery() {
        new AlertDialog.Builder(this)
                .setTitle("权限引导 3/3 · 电池优化")
                .setMessage("为保证后台常驻不被系统杀掉，建议允许「忽略电池优化」。\n\n点「去设置」后允许本应用忽略电池优化，然后返回即可。")
                .setCancelable(false)
                .setPositiveButton("去设置", (d, w) -> {
                    onboardWaitingReturn = true;
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        } catch (Exception ignored) { }
                    }
                })
                .setNegativeButton("暂时跳过", (d, w) -> finishOnboarding(false))
                .show();
    }

    private void finishOnboarding(boolean allGranted) {
        onboardingActive = false;
        cfg.onboarded(true);
        if (allGranted) {
            new AlertDialog.Builder(this)
                    .setTitle("权限已就绪")
                    .setMessage("麦克风 / 无障碍服务 / 电池优化 均已授权。回到主页面点「模拟长按空格」即可开始听写。")
                    .setPositiveButton("开始使用", null)
                    .show();
        } else {
            showCenterTip("已跳过引导；需要时可在「设置」里补开权限");
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacks(hideTip);
        if (peerListener != null) PeerManager.get().removeListener(peerListener);
        try { unregisterReceiver(commit); } catch (Exception ignored) { }
        try { unregisterReceiver(result); } catch (Exception ignored) { }
        super.onDestroy();
    }
}
