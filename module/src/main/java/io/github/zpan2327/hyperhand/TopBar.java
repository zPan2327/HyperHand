package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

/**
 * 折叠式大标题栏 —— 逐条对齐 miuix 的 {@code TopAppBar}：
 *
 * <ul>
 *   <li>收起的标题：title3 = 20sp / Medium，位于 {@code CollapsedHeight = 52dp} 的条里；</li>
 *   <li>展开的大标题：title1 = 32sp / Normal，左边距 {@code TitlePadding = 26dp}；</li>
 *   <li>副标题：body2 = 14sp，颜色 onSurfaceVariantSummary；</li>
 *   <li>背景：<b>渐进式模糊</b>（miuix {@code ProgressiveBlur.Top}）——
 *       顶部全额模糊、向下衰减到 0，而不是一块实色底板；收起后叠一层 surface 蒙版
 *       并把模糊区收窄到 52dp。</li>
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
    private final Paint largePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Theme.Colors pal;
    private final float density;

    private String title = "";
    private String subtitle = "";
    private String large = "";
    private int actionIcon = -1;
    private OnAction action;

    private float collapse;
    private float headerBlock;
    private int statusInset;

    private final android.graphics.RectF actionRect = new android.graphics.RectF();

    // 渐进式模糊用的内容采样
    private View backdropSource;
    private Bitmap sample;
    private final Rect sampleSrc = new Rect();
    private final RectF sampleDst = new RectF();
    private int sampleX;
    private int sampleY;
    private float sampleScale;
    private long lastSampleAt;

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
        largePaint.setTypeface(Theme.regular());
        largePaint.setTextSize(Theme.dpf(c, Theme.T_DISPLAY));
        largePaint.setColor(pal.title);
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

    void setBackdropSource(View v) {
        backdropSource = v;
        sample = null;
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

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), expandedHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float barBottom = statusInset + Theme.dpf(getContext(), Theme.BAR_H);
        float c = collapse;

        // ---------------- 渐进式模糊（ProgressiveBlur.Top）
        // 模糊区 = 标题条 + 一段渐隐带；收起后带宽收窄、再叠 surface 蒙版。
        float fade = Theme.dpf(getContext(), 18f + 26f * (1f - c));
        float blurBottom = barBottom + fade;
        drawProgressiveBackdrop(canvas, blurBottom, barBottom, c);

        if (c > 0.55f) {
            float a = Theme.clamp01((c - 0.55f) / 0.45f);
            // 不能用 pal.barBg：演示模式下它是 0x00000000，
            // setColor 之后又被 setAlpha 覆盖，会画出一条黑带。
            bgPaint.setColor(pal.night ? 0xFF17171B : 0xFFF7F7FA);
            // 0.86：留一点透明度，收起后仍能感觉到下面的渐进式模糊
            bgPaint.setAlpha(Math.round(219 * a));
            canvas.drawRect(0, 0, w, barBottom, bgPaint);
            linePaint.setColor(pal.night ? 0x33FFFFFF : 0x14000000);
            linePaint.setAlpha(Math.round(255 * a));
            canvas.drawLine(0, barBottom - linePaint.getStrokeWidth() / 2f, w,
                    barBottom - linePaint.getStrokeWidth() / 2f, linePaint);
        }

        float pad = Theme.dpf(getContext(), Theme.TITLE_PAD);
        float smallAlpha = Math.max(0f, (c - 0.35f) / 0.65f);
        float largeAlpha = 1f - c;

        if (largeAlpha > 0.01f && large != null && large.length() > 0) {
            float top = statusInset + Theme.dpf(getContext(), Theme.BAR_H);
            canvas.save();
            canvas.translate(0, -c * headerBlockHeight() * 0.35f);
            largePaint.setAlpha(Math.round(255 * Theme.clamp01(largeAlpha)));
            canvas.drawText(large, pad, top + Theme.dpf(getContext(), 32), largePaint);
            subPaint.setAlpha(Math.round(255 * Theme.clamp01(largeAlpha)));
            canvas.drawText(subtitle, pad, top + Theme.dpf(getContext(), 54), subPaint);
            canvas.restore();
        }

        if (smallAlpha > 0.01f) {
            titlePaint.setAlpha(Math.round(255 * Theme.clamp01(smallAlpha)));
            float baseline = statusInset + Theme.dpf(getContext(), Theme.BAR_H) / 2f
                    - (titlePaint.ascent() + titlePaint.descent()) / 2f;
            canvas.drawText(title, pad, baseline, titlePaint);
            titlePaint.setAlpha(255);
        }

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

    /**
     * 采样标题栏底下那一条内容，做渐进式模糊（miuix {@code ProgressiveBlur.Top}）：
     * 顶边全额模糊、到 blurBottom 衰减为零。
     */
    private void drawProgressiveBackdrop(Canvas canvas, float blurBottom, float barBottom,
                                         float collapseAmount) {
        if (backdropSource == null || blurBottom <= statusInset) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastSampleAt >= 16 || sample == null) {
            capture(blurBottom, collapseAmount);
            lastSampleAt = now;
        }
        if (sample == null || sample.isRecycled()) {
            return;
        }
        RectF dst = new RectF(sampleX, sampleY,
                sampleX + sample.getWidth() * sampleScale,
                sampleY + sample.getHeight() * sampleScale);

        // 模糊已经写进采样位图，这里只负责画出来 + 竖直遮罩
        int save = canvas.saveLayer(dst, null);
        LiquidGlass.drawBlurredRect(canvas, sample, sampleX, sampleY, sampleScale, dst);

        float y0 = dst.top;
        float y1 = Math.min(dst.bottom, blurBottom);
        Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
        mask.setShader(new android.graphics.LinearGradient(0, y0, 0, y1,
                new int[] {0x00000000, 0xFF000000}, null,
                android.graphics.Shader.TileMode.CLAMP));
        mask.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.DST_OUT));
        canvas.drawRect(dst.left, y0, dst.right, dst.bottom, mask);
        canvas.restoreToCount(save);

        // 顶部再压一层极淡的面色，保证标题在彩色背景上也读得清。
        // 底色不能用 pal.bg —— 演示模式下它是全透明，混出来会是一条黑带。
        int base = pal.night ? 0xFF101014 : 0xFFFFFFFF;
        int tint = Theme.alpha(base, 0.40f + 0.34f * collapseAmount);
        Paint p = new Paint();
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, blurBottom,
                new int[] {tint, Theme.alpha(base, 0f)}, null,
                android.graphics.Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), blurBottom, p);
    }

    private void capture(float bottomPx, float collapseAmount) {
        try {
            int pad = Math.round(Theme.dpf(getContext(), 8f));
            int[] barLoc = new int[2];
            getLocationInWindow(barLoc);
            int[] srcLoc = new int[2];
            backdropSource.getLocationInWindow(srcLoc);

            int winW = getResources().getDisplayMetrics().widthPixels;
            int winH = getResources().getDisplayMetrics().heightPixels;
            int left = barLoc[0] - pad;
            int top = barLoc[1] - pad;
            int w = Math.round(getWidth()) + pad * 2;
            int h = Math.round(bottomPx - statusInset) + pad * 2 + Math.round(statusInset);

            left = Math.max(0, Math.min(left, Math.max(0, winW - w)));
            top = Math.max(0, Math.min(top, Math.max(0, winH - h)));
            w = Math.min(w, winW);
            h = Math.min(h, winH);

            final float scale = 0.5f;
            int ow = Math.max(1, Math.round(w * scale));
            int oh = Math.max(1, Math.round(h * scale));
            if (sample == null || sample.getWidth() != ow || sample.getHeight() != oh) {
                if (sample != null) {
                    sample.recycle();
                }
                sample = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            }
            sample.eraseColor(0);
            Canvas c = new Canvas(sample);
            c.scale(scale, scale);
            c.translate(srcLoc[0] - left, srcLoc[1] - top);
            c.clipRect(left - srcLoc[0], top - srcLoc[1],
                    left - srcLoc[0] + w, top - srcLoc[1] + h);
            backdropSource.draw(c);

            sampleX = left - barLoc[0];
            sampleY = top - barLoc[1];
            sampleScale = scale;

            // 先模糊：半径随折叠略减（展开时更"雾"，收起后贴近系统顶栏）
            LiquidGlass.blur(sample, Math.max(1,
                    Math.round(Theme.dpf(getContext(), 7f + 5f * (1f - collapseAmount)) * scale)));
        } catch (Throwable t) {
            sample = null;
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
                Haptics.get(getContext()).click();
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
