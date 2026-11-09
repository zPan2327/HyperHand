package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * 折叠式大标题栏 —— 逐条对齐 miuix 的 {@code TopAppBar}：
 *
 * <ul>
 *   <li>收起的标题：title3 = 20sp / Medium，位于 {@code CollapsedHeight = 52dp} 的条里；</li>
 *   <li>展开的大标题：title1 = 32sp / Normal，左边距 {@code TitlePadding = 26dp}；</li>
 *   <li>副标题：body2 = 14sp，颜色 onSurfaceVariantSummary，
 *       与标题之间 {@code LargeTitleBottomPadding = 4dp}，
 *       底部 {@code SubtitleBottomPadding = 8dp}；</li>
 *   <li>背景：展开时完全透明（让页面底色透上来），收起后渐显 surface 并画分隔线。</li>
 * </ul>
 *
 * 与 miuix 的差异只有一处：这里是原生 View，滚动由外部把 ScrollView 的 y 传进来，
 * 用 {@link #setScroll(int)} 驱动折叠，不依赖 NestedScrollConnection。
 */
final class TopBar extends View {

    interface OnAction {
        void onAction();
    }

    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Theme.Colors pal;
    private final float density;

    private String title = "";
    private String subtitle = "";
    private String large = "";
    private int actionIcon = -1;
    private OnAction action;

    private float collapse;          // 0 = 展开，1 = 收起
    private float headerBlock;       // 大标题块的高度（px）
    private int statusInset;

    /** 右侧图标按钮的命中区（px）。 */
    private final android.graphics.RectF actionRect = new android.graphics.RectF();

    TopBar(Context c, Theme.Colors p) {
        super(c);
        this.pal = p;
        this.density = c.getResources().getDisplayMetrics().density;
        titlePaint.setTypeface(Theme.medium());
        titlePaint.setTextSize(Theme.dpf(c, Theme.T_HERO));
        titlePaint.setColor(pal.title);
        subPaint.setTypeface(Theme.regular());
        subPaint.setTextSize(Theme.dpf(c, Theme.T_SUM));
        subPaint.setColor(pal.summary);
        smallPaint.setTypeface(Theme.regular());
        smallPaint.setTextSize(Theme.dpf(c, Theme.T_DISPLAY));
        smallPaint.setColor(pal.title);
        bgPaint.setColor(pal.barBg);
        linePaint.setColor(pal.barLine);
        linePaint.setStrokeWidth(Math.max(1f, density * 0.75f));
        setClickable(true);
    }

    void setPage(String title, String subtitle, boolean showLarge, int actionIcon, OnAction action) {
        this.title = title == null ? "" : title;
        this.large = showLarge ? this.title : "";
        this.subtitle = subtitle == null ? "" : subtitle;
        this.actionIcon = actionIcon;
        this.action = action;
        collapse = 0f;
        requestLayout();
        invalidate();
    }

    void setPage(String title, String subtitle, int actionIcon, OnAction action) {
        setPage(title, subtitle, true, actionIcon, action);
    }

    void setStatusInset(int inset) {
        if (statusInset == inset) {
            return;
        }
        statusInset = inset;
        requestLayout();
    }

    /** 展开状态下的总高度：状态栏 + 52dp 标题条 + 大标题块。 */
    int expandedHeight() {
        int h = statusInset + Theme.dp(getContext(), Theme.BAR_H);
        return large == null || large.length() == 0
                ? h : h + Theme.dp(getContext(), Theme.HEADER_BLOCK);
    }

    int collapsedHeight() {
        return statusInset + Theme.dp(getContext(), Theme.BAR_H);
    }

    float headerBlockHeight() {
        if (large == null || large.length() == 0) {
            // 没有大标题的页（关于）：给小标题一个渐显区间，别滚 1px 就卡住
            return Math.max(1f, Theme.dpf(getContext(), 120));
        }
        return Math.max(1f, Theme.dpf(getContext(), Theme.HEADER_BLOCK));
    }

    void setScroll(int y) {
        float t = y / headerBlockHeight();
        setCollapse(t < 0f ? 0f : (t > 1f ? 1f : t));
    }

    void setCollapse(float t) {
        if (Math.abs(t - collapse) < 0.001f) {
            return;
        }
        collapse = t;
        invalidate();
    }

    float collapse() {
        return collapse;
    }

    /** 固定占位：不参与布局折叠，由页面自己留出 padding，标题栏浮在上面。 */
    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), expandedHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float barBottom = statusInset + Theme.dpf(getContext(), Theme.BAR_H);
        float c = collapse;

        // 背景：收起后渐显（miuix 的 color 随 scrollProgress 切换）
        float bgAlpha = Math.max(0f, (c - 0.55f) / 0.45f);
        if (bgAlpha > 0.01f) {
            bgPaint.setColor(pal.barBg);
            bgPaint.setAlpha(Math.round(255 * Theme.clamp01(bgAlpha)));
            canvas.drawRect(0, 0, w, barBottom, bgPaint);
            linePaint.setAlpha(Math.round(255 * Theme.clamp01(bgAlpha)));
            canvas.drawLine(0, barBottom - linePaint.getStrokeWidth() / 2f, w,
                    barBottom - linePaint.getStrokeWidth() / 2f, linePaint);
        }

        float pad = Theme.dpf(getContext(), Theme.TITLE_PAD);
        float smallAlpha = Math.max(0f, (c - 0.35f) / 0.65f);
        float largeAlpha = 1f - c;

        // 大标题 + 副标题：随折叠整体上移，alpha 线性淡出
        if (largeAlpha > 0.01f && large != null && large.length() > 0) {
            float top = statusInset + Theme.dpf(getContext(), Theme.BAR_H);
            canvas.save();
            canvas.translate(0, -c * headerBlockHeight() * 0.35f);

            smallPaint.setAlpha(Math.round(255 * Theme.clamp01(largeAlpha)));
            canvas.drawText(large, pad, top + Theme.dpf(getContext(), 32), smallPaint);

            subPaint.setAlpha(Math.round(255 * Theme.clamp01(largeAlpha)));
            canvas.drawText(subtitle, pad, top + Theme.dpf(getContext(), 32 + 22), subPaint);
            canvas.restore();
        }

        // 收起后的小标题：垂直居中在 52dp 条里（title3 / Medium）
        if (smallAlpha > 0.01f) {
            titlePaint.setAlpha(Math.round(255 * Theme.clamp01(smallAlpha)));
            float baseline = statusInset + Theme.dpf(getContext(), Theme.BAR_H) / 2f
                    - (titlePaint.ascent() + titlePaint.descent()) / 2f;
            canvas.drawText(title, pad, baseline, titlePaint);
            titlePaint.setAlpha(255);
        }

        // 右上角操作图标：始终可用
        if (actionIcon >= 0) {
            float size = Theme.dpf(getContext(), 22);
            float right = w - Theme.dpf(getContext(), 20);
            float cy = statusInset + Theme.dpf(getContext(), Theme.BAR_H) / 2f;
            float left = right - size;
            Icons.draw(canvas, actionIcon, left, cy - size / 2f, size, pal.title, 0f);
            actionRect.set(left - Theme.dpf(getContext(), 12), cy - Theme.dpf(getContext(), 20),
                    right + Theme.dpf(getContext(), 12), cy + Theme.dpf(getContext(), 20));
        }
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        if (action == null || actionIcon < 0) {
            return false;
        }
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            if (actionRect.contains(e.getX(), e.getY())) {
                setPressed(true);
                return true;
            }
            return false;
        }
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
            boolean inside = actionRect.contains(e.getX(), e.getY());
            setPressed(false);
            if (inside && action != null) {
                performClick();
                action.onAction();
            }
            return inside;
        }
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
            setPressed(false);
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
