package com.keyvoice;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.RelativeLayout;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 主页面按钮容器：长按任一按钮进入“编辑/框选”态（虚线边框），
 * 单指拖动移动、双指捏合缩放，右上角 √ 保存位置与缩放比例（按容器宽高归一化坐标）到 Config。
 * 非编辑态时点击按钮执行原功能（交给各自 OnClickListener）。
 */
public class DragLayout extends RelativeLayout {

    private static final String TAG = "KeyVoiceLayout";

    private final Set<String> KEYS = new HashSet<>();
    {
        KEYS.add("s"); KEYS.add("h"); KEYS.add("e");
        KEYS.add("c"); KEYS.add("p");
    }

    private boolean editMode = false;
    private View selected = null;
    private final Map<View, Drawable> origBg = new HashMap<>();
    private ImageButton checkBtn;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Config cfg;

    // 缩放
    private ScaleGestureDetector scaleDetector;
    // 拖动
    private float dragDX = 0, dragDY = 0;
    // 长按检测
    private View downView = null;
    private float downX = 0, downY = 0, lastX = 0, lastY = 0;
    private long downTime = 0;
    private static final long LONG_PRESS_MS = 420;
    private static final float SLOP = 12f;
    private final Runnable longPressTask = new Runnable() {
        @Override public void run() {
            if (!editMode && downView != null && downView.getTag() != null
                    && KEYS.contains(downView.getTag().toString())) {
                enterEdit(downView);
                selected = downView;
                dragDX = lastX - selected.getX();
                dragDY = lastY - selected.getY();
            }
        }
    };

    public DragLayout(Context c) { super(c); init(c); }
    public DragLayout(Context c, AttributeSet a) { super(c, a); init(c); }
    public DragLayout(Context c, AttributeSet a, int s) { super(c, a, s); init(c); }

    private void init(Context c) {
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                if (editMode && selected != null) {
                    float s = clamp(selected.getScaleX() * d.getScaleFactor(), 0.6f, 2.4f);
                    selected.setScaleX(s); selected.setScaleY(s);
                }
                return true;
            }
        });

        checkBtn = new ImageButton(c);
        checkBtn.setImageResource(R.drawable.ic_check);
        checkBtn.setColorFilter(0xFFFFFFFF);
        checkBtn.setBackground(Ui.buttonBg(Ui.success(c), Ui.shade(Ui.success(c), -0.28f),
                (float) Ui.dp(c, 22) / 2f));
        int cs = Ui.dp(c, 44);
        RelativeLayout.LayoutParams cp = new RelativeLayout.LayoutParams(cs, cs);
        cp.addRule(ALIGN_PARENT_RIGHT);
        cp.addRule(ALIGN_PARENT_TOP);
        cp.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), 0);
        checkBtn.setLayoutParams(cp);
        checkBtn.setVisibility(View.GONE);
        checkBtn.setContentDescription("完成布局");
        checkBtn.setOnClickListener(v -> exitEdit());
        addView(checkBtn);
    }

    public void setConfig(Config cfg) { this.cfg = cfg; }

    // ---- 触摸分发：长按进入编辑态；编辑态拦截所有触摸做拖动/缩放 ----
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        lastX = x; lastY = y;
        if (editMode) {
            // 例外：√ 按钮不被拦截，保证可点击退出
            if (pointInView(checkBtn, x, y)) return false;
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x; downY = y; downTime = System.currentTimeMillis();
                downView = childAtPoint(x, y);
                h.removeCallbacks(longPressTask);
                h.postDelayed(longPressTask, LONG_PRESS_MS);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (downView != null && Math.abs(x - downX) > SLOP || Math.abs(y - downY) > SLOP) {
                    h.removeCallbacks(longPressTask);   // 滑动而非长按，取消
                }
                return false;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(longPressTask);
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!editMode) return false;
        float x = e.getX(), y = e.getY();
        lastX = x; lastY = y;

        scaleDetector.onTouchEvent(e);
        if (scaleDetector.isInProgress()) return true;

        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            View hit = childAtPoint(x, y);
            if (hit != null && hit.getTag() != null && KEYS.contains(hit.getTag().toString())) {
                selected = hit;
                selected.bringToFront();
                checkBtn.bringToFront();
            }
            if (selected != null) {
                dragDX = x - selected.getX();
                dragDY = y - selected.getY();
            }
            return true;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (e.getPointerCount() >= 2) return true; // 双指交给缩放
            if (selected != null) {
                float nx = x - dragDX, ny = y - dragDY;
                nx = clamp(nx, 0, getWidth() - selected.getWidth());
                ny = clamp(ny, 0, getHeight() - selected.getHeight());
                selected.setX(nx); selected.setY(ny);
            }
            return true;
        }
        return true;
    }

    // ---- 编辑态 ----
    public void enterEdit(View v) {
        editMode = true;
        selected = v;
        if (v != null) { v.bringToFront(); }
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch instanceof ImageButton && ch != checkBtn && ch.getTag() != null
                    && KEYS.contains(ch.getTag().toString())) {
                origBg.put(ch, ch.getBackground());
                ch.setBackgroundResource(R.drawable.kv_btn_select);
                ch.setClickable(false);
            }
        }
        checkBtn.setVisibility(View.VISIBLE);
        checkBtn.bringToFront();
    }

    public void exitEdit() {
        editMode = false;
        for (Map.Entry<View, Drawable> e : origBg.entrySet()) {
            e.getKey().setBackground(e.getValue());
            e.getKey().setClickable(true);
        }
        origBg.clear();
        selected = null;
        checkBtn.setVisibility(View.GONE);
        savePositions();
    }

    // ---- 命中测试（按下实际显示尺寸，含缩放）----
    private View childAtPoint(float x, float y) {
        for (int i = getChildCount() - 1; i >= 0; i--) {
            View ch = getChildAt(i);
            if (ch == checkBtn || ch.getVisibility() != View.VISIBLE) continue;
            if (ch.getTag() == null || !KEYS.contains(ch.getTag().toString())) continue;
            float w = ch.getWidth() * ch.getScaleX();
            float h = ch.getHeight() * ch.getScaleY();
            float left = ch.getX(), top = ch.getY();
            if (x >= left && x <= left + w && y >= top && y <= top + h) return ch;
        }
        return null;
    }

    private boolean pointInView(View v, float x, float y) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        return x >= v.getX() && x <= v.getX() + v.getWidth() && y >= v.getY() && y <= v.getY() + v.getHeight();
    }

    private float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }

    // ---- 持久化 ----
    private void savePositions() {
        if (cfg == null || getWidth() == 0 || getHeight() == 0) return;
        int W = getWidth(), H = getHeight();
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch.getTag() == null || !KEYS.contains(ch.getTag().toString())) continue;
            if (!first) sb.append(",");
            first = false;
            float fx = clamp((ch.getX() + ch.getWidth() / 2f) / W, 0, 1);
            float fy = clamp((ch.getY() + ch.getHeight() / 2f) / H, 0, 1);
            sb.append("\"").append(ch.getTag()).append("\":[")
              .append(round(fx)).append(",").append(round(fy)).append(",")
              .append(round(ch.getScaleX())).append("]");
        }
        sb.append("}");
        cfg.layoutJson(sb.toString());
    }

    private float round(float v) { return Math.round(v * 1000f) / 1000f; }

    /** 应用已保存的位置；无存档则保持 MainActivity 里设的默认规则布局（设置右中、两个动作按钮依次在下）。 */
    public void restorePositions(String json) {
        if (getWidth() == 0 || getHeight() == 0) { post(() -> restorePositions(json)); return; }
        Map<String, float[]> pos = parse(json);
        if (pos.isEmpty()) return;   // 无存档：保持默认规则布局，不做任何位移
        for (Map.Entry<String, float[]> e : pos.entrySet()) {
            float[] p = e.getValue();
            apply(e.getKey(), p[0], p[1], p[2]);
        }
        dumpGeometry("restore");
    }

    /**
     * 把归一化存档坐标（中心点 fx,fy ∈ [0,1] + 缩放）还原成绝对像素布局。
     *
     * 关键点：
     *  1) 必须用 margin 做绝对定位（规则被移除后 RelativeLayout 按 left/top margin 摆放子 View）。
     *     不能用 setX/setY —— setX 写的是 translationX = x - 当前 left，而随后的 setLayoutParams
     *     会触发 relayout 把 left 改成 0，两者叠加导致按钮跑偏。
     *  2) 每个按钮按容器【当前】宽高重投影并 clamp 回容器内（用缩放后的实际尺寸），
     *     这样即使存档来自别的屏幕尺寸/方向，也不会整体跑到可视区外（按钮“丢失”）。
     */
    private void apply(String key, float fx, float fy, float scale) {
        View v = findByTag(key);
        if (v == null) return;
        int w = v.getWidth();
        if (w <= 0) w = Ui.dp(getContext(), 38);   // 尚未测量时退回按钮默认直径
        if (Float.isNaN(fx) || Float.isNaN(fy) || Float.isNaN(scale)) return;
        if (scale < 0.5f || scale > 3f) scale = 1f; // 存档异常时丢弃缩放

        float sw = w * scale;                      // 缩放后的实际占位
        float cx = clamp(fx, 0f, 1f) * getWidth();
        float cy = clamp(fy, 0f, 1f) * getHeight();

        // 以中心点对齐，再夹回容器边界，保证按钮完整可见
        int left = (int) clamp(cx - sw / 2f, 0f, Math.max(0f, getWidth() - sw));
        int top  = (int) clamp(cy - sw / 2f, 0f, Math.max(0f, getHeight() - sw));

        RelativeLayout.LayoutParams lp = asRelativeParams(v);
        clearRules(lp);
        lp.leftMargin = left;
        lp.topMargin = top;
        v.setLayoutParams(lp);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setScaleX(scale); v.setScaleY(scale);
    }

    /**
     * 自愈兜底：检查三个按钮是否真的落在容器可视区内。
     * 只要有一个按钮不可见（宽高为 0、或完全在容器外、或被判定为不可见），
     * 就清空异常存档并复位为默认规则布局，避免用户看到“按钮全没了”却无从恢复。
     *
     * @param notifier 复位发生时用于提示用户（可为 null）
     */
    public void ensureVisible(final java.util.function.Consumer<String> notifier) {
        if (getWidth() == 0 || getHeight() == 0) return;
        boolean broken = false;
        for (String key : KEYS) {
            View v = findByTag(key);
            if (v == null) { broken = true; break; }
            if (v.getVisibility() != View.VISIBLE || v.getWidth() <= 0 || v.getHeight() <= 0) {
                broken = true; break;
            }
            float sw = v.getWidth() * v.getScaleX();
            float sh = v.getHeight() * v.getScaleY();
            float left = v.getX(), top = v.getY();
            // 与容器可视区至少要有 8dp 的重叠，否则用户根本看不到/点不到
            float minOverlap = Ui.dp(getContext(), 8);
            boolean visibleInContainer =
                    left + sw > minOverlap && left < getWidth() - minOverlap
                            && top + sh > minOverlap && top < getHeight() - minOverlap;
            if (!visibleInContainer) { broken = true; break; }
        }
        dumpGeometry(broken ? "ensureVisible:MISSING" : "ensureVisible:OK");
        if (broken) {
            resetLayout();
            if (notifier != null) notifier.accept("按钮位置异常，已自动复位");
        }
    }

    /**
     * 救砖入口：清空已保存的布局存档并恢复默认规则布局。
     * 当按钮因历史存档异常（坐标落在容器外）不可见时，作为兜底恢复手段。
     */
    public void resetLayout() {
        if (cfg != null) cfg.layoutJson("");
        if (checkBtn != null) checkBtn.setVisibility(View.GONE);
        editMode = false;
        if (origBg != null && !origBg.isEmpty()) {
            for (Map.Entry<View, Drawable> e : origBg.entrySet()) {
                e.getKey().setBackground(e.getValue());
                e.getKey().setClickable(true);
            }
            origBg.clear();
        }
        selected = null;

        int bs = Ui.dp(getContext(), 38);
        int gap = Ui.dp(getContext(), 16);
        // 五个按钮默认布局：s 右中，其余依次向下排列
        String[] chain = {"s", "h", "e", "c", "p"};
        String prev = null;
        for (String key : chain) {
            boolean first = prev == null;
            resetView(key, ALIGN_PARENT_RIGHT,
                    first ? CENTER_VERTICAL : BELOW,
                    0, first ? 0 : gap, bs, bs, prev);
            prev = key;
        }
        dumpGeometry("reset");
    }

    /** 把某个 tag 的按钮恢复成规则布局（右侧对齐 + 依次向下），并清零缩放/位移。 */
    private void resetView(String key, int rule1, int rule2, int m1, int m2, int bw, int bh, String prevKey) {
        View v = findByTag(key);
        if (v == null) return;
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(bw, bh);
        clearRules(lp);
        if (rule1 != 0) lp.addRule(rule1);
        if (rule2 == CENTER_VERTICAL) {
            lp.addRule(CENTER_VERTICAL);
        } else if (rule2 == BELOW) {
            // prevKey 为空说明是链首，退化为仅右对齐
            if (prevKey != null) {
                View prev = findByTag(prevKey);
                if (prev != null) lp.addRule(BELOW, prev.getId());
            }
        }
        lp.leftMargin = m1;
        lp.topMargin = m2;
        v.setLayoutParams(lp);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
    }

    private void clearRules(RelativeLayout.LayoutParams lp) {
        lp.removeRule(ALIGN_PARENT_RIGHT); lp.removeRule(ALIGN_PARENT_LEFT);
        lp.removeRule(ALIGN_PARENT_TOP); lp.removeRule(ALIGN_PARENT_BOTTOM);
        lp.removeRule(CENTER_VERTICAL); lp.removeRule(CENTER_HORIZONTAL);
        lp.removeRule(BELOW); lp.removeRule(ABOVE);
        lp.removeRule(RIGHT_OF); lp.removeRule(LEFT_OF);
    }

    /**
     * 取到 RelativeLayout.LayoutParams。若当前是别的类型（例如 Ui.iconButton 里设的
     * 纯 ViewGroup.LayoutParams），就地转换，绝不抛 ClassCastException。
     */
    private RelativeLayout.LayoutParams asRelativeParams(View v) {
        ViewGroup.LayoutParams cur = v.getLayoutParams();
        if (cur instanceof RelativeLayout.LayoutParams) return (RelativeLayout.LayoutParams) cur;
        int w = cur != null && cur.width > 0 ? cur.width : Ui.dp(getContext(), 38);
        int hgt = cur != null && cur.height > 0 ? cur.height : Ui.dp(getContext(), 38);
        return new RelativeLayout.LayoutParams(w, hgt);
    }

    /** 诊断：把容器与三个按钮的实际几何打到 logcat（tag: KeyVoiceLayout）。 */
    public void dumpGeometry(String stage) {
        try {
            Log.i(TAG, "---- " + stage + " ---- container w=" + getWidth() + " h=" + getHeight()
                    + " visibility=" + getVisibility());
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                Log.i(TAG, "  child[" + i + "] tag=" + ch.getTag()
                        + " class=" + ch.getClass().getSimpleName()
                        + " vis=" + ch.getVisibility()
                        + " w=" + ch.getWidth() + " h=" + ch.getHeight()
                        + " x=" + ch.getX() + " y=" + ch.getY()
                        + " sx=" + ch.getScaleX() + " sy=" + ch.getScaleY()
                        + " lm=" + ((RelativeLayout.LayoutParams) asRelativeParams(ch)).leftMargin
                        + " tm=" + ((RelativeLayout.LayoutParams) asRelativeParams(ch)).topMargin);
            }
        } catch (Exception e) {
            Log.w(TAG, "dumpGeometry failed", e);
        }
    }

    /** 容器与三个按钮的几何报告（供界面直接展示，不依赖 adb / logcat）。 */
    public String reportGeometry() {
        StringBuilder sb = new StringBuilder();
        sb.append("container ").append(getWidth()).append("x").append(getHeight())
          .append(" vis=").append(getVisibility())
          .append(" editMode=").append(editMode);
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch == checkBtn || ch.getTag() == null) continue;
            RelativeLayout.LayoutParams lp = asRelativeParams(ch);
            sb.append('\n').append(ch.getTag())
              .append(": w=").append(ch.getWidth())
              .append(" h=").append(ch.getHeight())
              .append(" x=").append(Math.round(ch.getX()))
              .append(" y=").append(Math.round(ch.getY()))
              .append(" scale=").append(ch.getScaleX())
              .append(" lm=").append(lp.leftMargin)
              .append(" tm=").append(lp.topMargin);
        }
        return sb.toString();
    }

    private View findByTag(String key) {
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (key.equals(String.valueOf(ch.getTag()))) return ch;
        }
        return null;
    }

    private Map<String, float[]> parse(String json) {
        Map<String, float[]> out = new HashMap<>();
        if (json == null || json.isEmpty()) return out;
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                org.json.JSONArray a = o.getJSONArray(k);
                out.put(k, new float[]{(float) a.getDouble(0), (float) a.getDouble(1), (float) a.getDouble(2)});
            }
        } catch (Exception ignored) { }
        return out;
    }
}
