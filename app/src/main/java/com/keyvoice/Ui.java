package com.keyvoice;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * KeyVoice 设计系统（design tokens + 组件工厂）。
 * 所有界面统一走这里，保证颜色 / 圆角 / 间距 / 触摸目标一致。
 * 配色与 Windows 接收端（pc/keyvoice_box.py）共用同一套深色 token。
 */
public final class Ui {

    // ---- 颜色 token（对应 res/values/colors.xml）----
    public static int bg(Context c)          { return c.getResources().getColor(R.color.kv_bg); }
    public static int surface(Context c)      { return c.getResources().getColor(R.color.kv_surface); }
    public static int surfaceHi(Context c)    { return c.getResources().getColor(R.color.kv_surface_hi); }
    public static int border(Context c)       { return c.getResources().getColor(R.color.kv_border); }
    public static int text(Context c)         { return c.getResources().getColor(R.color.kv_text); }
    public static int text2(Context c)        { return c.getResources().getColor(R.color.kv_text2); }
    public static int text3(Context c)        { return c.getResources().getColor(R.color.kv_text3); }
    public static int primary(Context c)      { return c.getResources().getColor(R.color.kv_primary); }
    public static int primaryPress(Context c) { return c.getResources().getColor(R.color.kv_primary_press); }
    public static int primaryDim(Context c)   { return c.getResources().getColor(R.color.kv_primary_dim); }
    public static int accent(Context c)       { return c.getResources().getColor(R.color.kv_accent); }
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

    public static Button primaryButton(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
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
        b.setTextColor(Color.WHITE);
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
}
