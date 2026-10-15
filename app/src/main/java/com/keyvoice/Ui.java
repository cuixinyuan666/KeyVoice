package com.keyvoice;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * KeyVoice 设计系统（design tokens + 组件工厂）。
 * 所有界面统一走这里，保证颜色 / 圆角 / 间距 / 触摸目标一致。
 * 配色与 Windows 接收端（pc/keyvoice_box.py）共用同一套深色 token。
 */
public final class Ui {

    /**
     * 当前生效的主题。默认 dark。
     * 用 Ui.setTheme() 设置后，下面的 bg()/surface()/text() 等 token 会取对应色板。
     */
    private static boolean light = false;

    /** 切换主题：true=light，false=dark */
    public static void setTheme(boolean lightTheme) { light = lightTheme; }

    /** 便捷：按 Config 里的主题字符串设置 */
    public static void setTheme(String name) { light = "light".equalsIgnoreCase(name); }

    public static boolean isLight() { return light; }

    private static int col(Context c, int darkRes, int lightRes) {
        return c.getResources().getColor(light ? lightRes : darkRes);
    }

    /**
     * Activity 使用的 android:theme 资源 id。
     * <p>
     * 必须在 {@code super.onCreate()} 之后、构建任何视图之前调用
     * {@code setTheme(themeRes(light))}。Ui 里的 light 标志只作用于代码创建的视图，
     * 系统弹层（Spinner 下拉 / AlertDialog / 输入法）只认 Activity 主题，两边必须一起切。
     */
    public static int themeRes(boolean lightTheme) {
        return lightTheme ? R.style.AppTheme_Light : R.style.AppTheme;
    }

    /** 按当前已设置的主题取 Activity 主题资源 id */
    public static int themeRes() { return themeRes(light); }

    // ---- 颜色 token（对应 res/values/colors.xml）----
    public static int bg(Context c)          { return col(c, R.color.kv_bg, R.color.kvl_bg); }
    public static int surface(Context c)     { return col(c, R.color.kv_surface, R.color.kvl_surface); }
    public static int surfaceHi(Context c)   { return col(c, R.color.kv_surface_hi, R.color.kvl_surface_hi); }
    public static int border(Context c)      { return col(c, R.color.kv_border, R.color.kvl_border); }
    public static int text(Context c)        { return col(c, R.color.kv_text, R.color.kvl_text); }
    public static int text2(Context c)       { return col(c, R.color.kv_text2, R.color.kvl_text2); }
    public static int text3(Context c)       { return col(c, R.color.kv_text3, R.color.kvl_text3); }
    // 主色不随主题变化，保证品牌一致
    public static int primary(Context c)      { return c.getResources().getColor(R.color.kv_primary); }
    public static int primaryPress(Context c) { return c.getResources().getColor(R.color.kv_primary_press); }
    public static int accent(Context c)       { return c.getResources().getColor(R.color.kv_accent); }

    /**
     * 主色的「低强度」版本：用作按钮按下态 / 次要高亮底色。
     * 必须随主题切换 —— 深色下是深蓝，浅色下是浅蓝，否则白底上会糊成一块深色。
     */
    public static int primaryDim(Context c)   { return col(c, R.color.kv_primary_dim, R.color.kvl_primary_dim); }

    /** 主色底上的前景色（图标 / 文字）。主色恒为品牌蓝，两主题下都用白色最清晰。 */
    public static int onPrimary(Context c)    { return Color.WHITE; }
    public static int success(Context c)      { return c.getResources().getColor(R.color.kv_success); }
    public static int warning(Context c)      { return c.getResources().getColor(R.color.kv_warning); }
    public static int error(Context c)        { return c.getResources().getColor(R.color.kv_error); }

    public static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---- 形状 ----
    public static GradientDrawable round(int fill, int stroke, int strokeW, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (strokeW > 0) d.setStroke(strokeW, stroke);
        d.setCornerRadius(radiusPx);
        return d;
    }

    public static StateListDrawable buttonBg(int normal, int pressed, float radiusPx) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable p = new GradientDrawable(); p.setColor(pressed);  p.setCornerRadius(radiusPx);
        GradientDrawable n = new GradientDrawable(); n.setColor(normal);   n.setCornerRadius(radiusPx);
        s.addState(new int[]{android.R.attr.state_pressed}, p);
        s.addState(new int[]{}, n);
        return s;
    }

    // ---- 布局 primitives ----
    public static LinearLayout root(Context c) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg(c));
        root.setPadding(dp(c, 16), dp(c, 16), dp(c, 16), dp(c, 16));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    public static LinearLayout rootScroll(Context c) {
        LinearLayout root = root(c);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    public static LinearLayout appBar(Context c, String title) {
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(round(surface(c), border(c), 1, dp(c, 12)));
        bar.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams bplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bplp.bottomMargin = dp(c, 12);
        bar.setLayoutParams(bplp);

        int sw = dp(c, 4), sh = dp(c, 22);
        View strip = new View(c);
        strip.setLayoutParams(new LinearLayout.LayoutParams(sw, sh));
        strip.setBackground(round(primary(c), 0, 0, (float) sw / 2f));
        bar.addView(strip);

        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(18);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(text(c));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.leftMargin = dp(c, 10);
        t.setLayoutParams(tp);
        bar.addView(t);
        return bar;
    }

    public static LinearLayout sectionCard(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(surface(c), border(c), 1, dp(c, 12)));
        card.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        card.setLayoutParams(lp);
        return card;
    }

    public static TextView sectionTitle(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(13);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextColor(primary(c));
        t.setPadding(0, 0, 0, dp(c, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 4);
        t.setLayoutParams(lp);
        return t;
    }

    public static TextView body(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(text2(c));
        return t;
    }

    public static TextView caption(Context c, String s) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(text3(c));
        return t;
    }

    // ---------------------------------------------------------------- 下拉框
    //
    // 系统自带的 simple_spinner_dropdown_item 使用系统主题色（通常是白底黑字），
    // 在深色卡片里弹出会非常刺眼。这里统一给出「跟随当前主题」的适配器与边框，
    // 让设置页的下拉与卡片、输入框保持同一套配色。

    /** 主题化的下拉适配器：收起态与展开态都用当前主题的底色与文字色 */
    public static ArrayAdapter<String> spinnerAdapter(Context c, String[] items) {
        return new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, cv, parent);
                t.setTextColor(text(c));
                t.setBackgroundColor(surfaceHi(c));
                return t;
            }

            @Override
            public View getDropDownView(int pos, View cv, ViewGroup parent) {
                TextView t = (TextView) super.getDropDownView(pos, cv, parent);
                t.setTextColor(text(c));
                t.setBackgroundColor(surfaceHi(c));
                int p = dp(c, 12);
                t.setPadding(p, p, p, p);
                return t;
            }
        };
    }

    /** 给 Spinner 套上与输入框一致的圆角边框（需在 setAdapter 之后调用） */
    public static void styleSpinner(Context c, Spinner sp) {
        sp.setBackground(round(surfaceHi(c), border(c), 1, dp(c, 10)));
        sp.setPadding(dp(c, 8), dp(c, 6), dp(c, 8), dp(c, 6));
    }

    public static EditText inputField(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(text3(c));
        e.setTextColor(text(c));
        e.setBackground(round(surfaceHi(c), border(c), 1, dp(c, 10)));
        e.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        e.setMinHeight(dp(c, 44));
        return e;
    }

    /**
     * 应用输入框外观（颜色 / 字体 / 字号）。设置改动后调用，全部来自 Config 持久化值。
     *
     * @param cfg        Config 实例
     * @param themeText  当前主题下「默认文字色」——用户选「跟随主题」时用它
     */
    public static void applyInputStyle(EditText e, Config cfg, int themeText) {
        if (e == null || cfg == null) return;
        int color = cfg.inputTextColor();
        e.setTextColor(color == 0 ? themeText : color);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, cfg.fontSize());
        e.setTypeface(typeface(cfg.fontFamily()));
    }

    /** 字体族 -> Typeface */
    public static android.graphics.Typeface typeface(String family) {
        if (family == null) return android.graphics.Typeface.DEFAULT;
        switch (family) {
            case "serif":     return android.graphics.Typeface.SERIF;
            case "monospace": return android.graphics.Typeface.MONOSPACE;
            case "sans":      return android.graphics.Typeface.SANS_SERIF;
            default:          return android.graphics.Typeface.DEFAULT;
        }
    }

    /** 设置页用的字体可选项（顺序与 stored value 对应） */
    public static final String[] FONT_LABELS   = {"无衬线（默认）", "衬线 Serif", "等宽 Monospace"};
    public static final String[] FONT_VALUES   = {"sans", "serif", "monospace"};

    /** 设置页字号可选项（sp） */
    public static final int[]    FONT_SIZES    = {12, 14, 15, 16, 18, 20, 24, 28};
    public static final String[] FONT_SIZE_TXT = {"极小 12", "小 14", "默认 15", "中大 16",
                                                  "大 18", "特大 20", "巨大 24", "超大 28"};

    /** 设置页主题可选项 */
    public static final String[] THEME_LABELS  = {"深色（默认）", "浅色"};
    public static final String[] THEME_VALUES  = {"dark", "light"};

    /** 「跟随主题」在色板里用 0 表示 */
    public static final int COLOR_FOLLOW_THEME = 0;

    public static Button primaryButton(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(onPrimary(c));
        b.setBackground(buttonBg(primary(c), primaryPress(c), dp(c, 10)));
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        return b;
    }

    public static Button accentButton(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(onPrimary(c));
        b.setBackground(buttonBg(accent(c), Color.parseColor("#0FBBD6"), dp(c, 10)));
        b.setMinHeight(dp(c, 48));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        return b;
    }

    public static Button ghostButton(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(14);
        b.setTextColor(text2(c));
        b.setBackground(buttonBg(surfaceHi(c), primaryDim(c), dp(c, 10)));
        b.setMinHeight(dp(c, 44));
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        return b;
    }

    public static View stateDot(Context c, int color) {
        View v = new View(c);
        int d = dp(c, 10);
        v.setLayoutParams(new LinearLayout.LayoutParams(d, d));
        v.setBackground(round(color, 0, 0, (float) d / 2f));
        return v;
    }

    /** 把颜色按 f 比例调亮(f>0)/调暗(f<0)，用于按钮按下态。f ∈ (-1,1) */
    public static int shade(int color, float f) {
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        if (f < 0) { float k = 1 + f; r = (int) (r * k); g = (int) (g * k); b = (int) (b * k); }
        else { r += (int) ((255 - r) * f); g += (int) ((255 - g) * f); b += (int) ((255 - b) * f); }
        return Color.argb(Color.alpha(color), clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }

    /** 圆形图标按钮：固定直径 diameterDp（0.3cm 半径≈38dp），背景 bgColor，图标白色居中 */
    public static ImageButton iconButton(Context c, int iconRes, int bgColor, int diameterDp) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(iconRes);
        b.setColorFilter(onPrimary(c));
        b.setScaleType(ImageButton.ScaleType.CENTER_INSIDE);
        b.setBackground(buttonBg(bgColor, shade(bgColor, -0.28f), (float) dp(c, diameterDp) / 2f));
        int pad = dp(c, Math.max(8, diameterDp / 4));
        b.setPadding(pad, pad, pad, pad);
        int s = dp(c, diameterDp);
        b.setLayoutParams(new ViewGroup.LayoutParams(s, s));
        b.setContentDescription("");
        return b;
    }
}
