package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 悬浮式液态玻璃底栏。
 *
 * <p>版式照 SonyPods/AndroidLiquidGlass：{@code 64dp} 高的胶囊，最多
 * {@code 每格 76dp × 3 + 8dp} 宽、居中悬浮；指示器是 {@code 56dp} 的胶囊。
 *
 * <p>玻璃本身照手机上 {@code io.github.hyperisland} 的
 * {@code LiquidGlassDrawable}：先抓画面 → <b>先模糊</b> → 再用折射 AGSL 采样。
 * 先模糊后折射是关键：位移采样到的都是模糊块，不会把文字撕成彩色条纹。
 *
 * <pre>
 * 玻璃体   blur 9dp · uRefractionHeight = 0.16 × min(w,h) × 1.25
 *          uRefractionAmount   = -0.16 × min(w,h) × 2
 *          uDispersion         = 0.18 × 0.12
 * 指示器   blur 4dp · 折射带与位移按按下程度放大 → 拖动时像推着一颗水珠
 * </pre>
 */
final class GlassBar extends View {

    interface OnTabSelected {
        void onSelected(int index);
    }

    private static final int[] ICONS = {Icons.HOME, Icons.GEAR, Icons.INFO};
    private static final String[] LABELS = {"概览", "设置", "关于"};

    private static final float BAR_H = 64f;
    private static final float PILL_H = 56f;
    private static final float PAD = 4f;
    private static final float TAB_MAX_W = 76f;
    private static final float PRESSED_SCALE = 78f / 56f;
    private static final float SAMPLE_PAD = 24f;
    private static final float OVERFLOW = 7f;
    private static final float ICON_DP = 22f;
    /** 采样缩放：0.5 = 半分辨率，够看清内容又便宜。 */
    private static final float SAMPLE_SCALE = 0.5f;

    /** HyperIsland 的默认光照方向（左上）。 */
    private static final float LIGHT_X = -0.45f;
    private static final float LIGHT_Y = -0.89f;

    private final Theme.Colors pal;
    private final float density;
    private final int touchSlop;
    private final int accent;
    private final int blend;

    private final RectF barRect = new RectF();
    private final RectF pillRect = new RectF();
    private final RectF shadowRect = new RectF();
    private final Rect sampleSrc = new Rect();
    private final RectF sampleDst = new RectF();
    private final Path clipPath = new Path();
    private final Path pillClip = new Path();
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float barW;
    private float tabW;
    private float radius;

    private int selected;
    private final Ui.Spring pos = new Ui.Spring(0f);
    private final Ui.Spring press = new Ui.Spring(0f);
    private final Ui.Spring scaleX = new Ui.Spring(1f);
    private final Ui.Spring scaleY = new Ui.Spring(1f);

    private boolean dragging;
    private float downX;
    private float lastX;

    private OnTabSelected listener;

    private View backdropSource;
    private Bitmap sample;
    private int sampleX;
    private int sampleY;
    private long lastSampleAt;
    private long lastFrameAt;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrameAt == 0 ? 0.016f : Math.min(0.05f, (now - lastFrameAt) / 1000f);
            lastFrameAt = now;
            boolean active = false;
            if (!pos.settled()) {
                pos.step(dt);
                active = true;
            }
            if (!press.settled()) {
                press.step(dt);
                active = true;
            }
            if (!scaleX.settled()) {
                scaleX.step(dt);
                active = true;
            }
            if (!scaleY.settled()) {
                scaleY.step(dt);
                active = true;
            }
            invalidate();
            if (active) {
                postOnAnimation(this);
            } else {
                lastFrameAt = 0;
            }
        }
    };

    GlassBar(Context c, Theme.Colors p) {
        super(c);
        this.pal = p;
        this.density = c.getResources().getDisplayMetrics().density;
        this.touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        this.accent = p.night ? 0xFF0091FF : 0xFF0088FF;
        this.blend = p.night ? 0x1FFFFFFF : 0x14FFFFFF;
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(Theme.dpf(c, Theme.T_TAB));
        labelPaint.setTypeface(Theme.regular());
        pos.set(1000f, 1.0f);
        press.set(1000f, 1.0f);
        scaleX.set(250f, 0.6f);
        scaleY.set(250f, 0.7f);
        setClickable(true);
        setFocusable(true);
    }

    void setOnTabSelected(OnTabSelected l) {
        listener = l;
    }

    void setBackdropSource(View v) {
        backdropSource = v;
        sample = null;
        invalidate();
    }

    void setSelectedImmediate(int index) {
        selected = clampIndex(index);
        pos.snap(selected);
        press.snap(0f);
        scaleX.snap(1f);
        scaleY.snap(1f);
        invalidate();
    }

    void select(int index, boolean animate) {
        selected = clampIndex(index);
        if (!animate) {
            pos.snap(selected);
            invalidate();
            return;
        }
        pos.to(selected);
        kick();
    }

    /** 页面被横向拖动时的连续位置：指示器直接跟手。 */
    void setPagePosition(float position) {
        pos.snap(clamp(position, 0f, ICONS.length - 1f));
        selected = clampIndex(Math.round(position));
        invalidate();
    }

    private void kick() {
        lastFrameAt = 0;
        removeCallbacks(ticker);
        postOnAnimation(ticker);
    }

    private static int clampIndex(int i) {
        return i < 0 ? 0 : (i >= ICONS.length ? ICONS.length - 1 : i);
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    protected void onMeasure(int w, int h) {
        float viewW = MeasureSpec.getSize(w);
        float maxW = Theme.dpf(getContext(), TAB_MAX_W * ICONS.length + 8f);
        barW = Math.min(viewW, maxW);
        setMeasuredDimension(Math.round(viewW),
                Math.round(Theme.dpf(getContext(), BAR_H + OVERFLOW * 2f)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float overflow = Theme.dpf(getContext(), OVERFLOW);
        float maxW = Theme.dpf(getContext(), TAB_MAX_W * ICONS.length + 8f);
        barW = Math.min(w, maxW);
        tabW = (barW - Theme.dpf(getContext(), 8)) / ICONS.length;
        float left = (w - barW) / 2f;
        barRect.set(left, overflow, left + barW, overflow + Theme.dpf(getContext(), BAR_H));
        radius = barRect.height() / 2f;
        clipPath.reset();
        Squircle.rect(clipPath, barRect, radius);
        shadowRect.set(barRect);
        sample = null;
    }

    // ---------------------------------------------------------------- 手势

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                lastX = downX;
                dragging = false;
                press.to(1f);
                scaleX.to(PRESSED_SCALE);
                scaleY.to(PRESSED_SCALE);
                kick();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = e.getX();
                float dx = x - lastX;
                lastX = x;
                if (!dragging && Math.abs(x - downX) > touchSlop) {
                    dragging = true;
                }
                if (dragging) {
                    pos.to(clamp(pos.target + dx / tabW, 0f, ICONS.length - 1));
                    kick();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean cancel = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
                int target;
                if (dragging && !cancel) {
                    target = clampIndex(Math.round(pos.value));
                } else if (dragging) {
                    target = selected;
                } else {
                    target = indexAt(e.getX());
                }
                dragging = false;
                press.to(0f);
                scaleX.to(1f);
                scaleY.to(1f);
                pos.to(target);
                if (target != selected) {
                    selected = target;
                    Haptics.get(getContext()).flip();
                    if (listener != null) {
                        listener.onSelected(target);
                    }
                }
                kick();
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private int indexAt(float x) {
        if (barW <= 0) {
            return 0;
        }
        float local = x - barRect.left - Theme.dpf(getContext(), PAD);
        return clampIndex((int) Math.floor(local / tabW));
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        if (now - lastSampleAt >= 16 || sample == null) {
            captureSample();
            lastSampleAt = now;
        }

        LiquidGlass.drawShadow(canvas, shadowRect, radius,
                Theme.dpf(getContext(), 10f), 0xFF000000, pal.night ? 0.24f : 0.12f);

        // ---- 玻璃体：折射带 uRefractionHeight = edgeWidth × min(w,h) × 1.25
        canvas.save();
        canvas.clipPath(clipPath);
        float minSide = Math.min(barRect.width(), barRect.height());
        float edge = Math.max(1f, Theme.dpf(getContext(), 20f));
        float amount = -0.16f * minSide * 2f;
        drawGlassAt(canvas, barRect, radius, Theme.dpf(getContext(), 9f),
                edge, amount, 0.18f, blend);
        LiquidGlass.drawTopGloss(canvas, barRect, 0.42f, 0xFFFFFFFF,
                pal.night ? 0.08f : 0.20f);
        LiquidGlass.drawEdgeHighlight(canvas, barRect, radius, LIGHT_X, LIGHT_Y,
                Math.max(1f, Theme.dpf(getContext(), 12f)), 0.85f, 0.55f, 0.30f, 0.30f);
        canvas.restore();

        drawTabs(canvas);
        drawPill(canvas);
    }

    private void drawPill(Canvas canvas) {
        if (tabW <= 0) {
            return;
        }
        float p = clamp(press.value, 0f, 1.2f);
        float pillH = Theme.dpf(getContext(), PILL_H);
        float px = barRect.left + Theme.dpf(getContext(), PAD) + pos.value * tabW;
        float pillTop = barRect.top + (barRect.height() - pillH) / 2f;
        pillRect.set(px, pillTop, px + tabW, pillTop + pillH);
        float pillRadius = pillH / 2f;
        float cx = pillRect.centerX();
        float cy = pillRect.centerY();

        float vel = pos.velocity / 10f;
        float sx = scaleX.value / (1f - clamp(vel * 0.75f, -0.2f, 0.2f));
        float sy = scaleY.value * (1f - clamp(vel * 0.25f, -0.2f, 0.2f));

        // 缩放直接作用在形状上（不套 canvas 变换）：
        // AGSL 的 fragCoord 是设备坐标，套 canvas 变换会让它和 uOrigin 错位。
        float halfW = pillRect.width() / 2f * sx;
        float halfH = pillRect.height() / 2f * sy;
        RectF scaled = new RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        float scaledRadius = Math.min(halfW, halfH);

        pillClip.reset();
        Squircle.rect(pillClip, scaled, scaledRadius);
        canvas.save();
        canvas.clipPath(pillClip);

        // 指示器：拖动/按下时折射带变宽、位移变大 —— 像一颗被推动的水珠
        float minSide = Math.min(scaled.width(), scaled.height());
        float edge = scaled.height() * (0.30f + 0.16f * p);
        float amount = -(0.20f + 0.10f * p) * minSide * 2f;
        drawGlassAt(canvas, scaled, scaledRadius,
                Theme.dpf(getContext(), 3f), edge, amount,
                0.30f + 0.30f * p, pal.night ? 0x14FFFFFF : 0x10FFFFFF);
        canvas.restore();

        float speed = clamp(Math.abs(vel) * 2f, 0f, 1f);
        LiquidGlass.drawEdgeHighlight(canvas, scaled, scaledRadius,
                LIGHT_X + clamp(pos.velocity * 0.015f, -0.4f, 0.4f), LIGHT_Y,
                Math.max(1f, Theme.dpf(getContext(), 10f)),
                0.45f + 0.30f * p + 0.30f * speed,
                0.30f,
                0.25f + 0.40f * p + 0.45f * speed,
                0.30f + 0.45f * p);

        int nearest = clampIndex(Math.round(pos.value));
        float near = 1f - Math.min(1f, Math.abs(pos.value - nearest));
        if (near > 0.02f) {
            drawTab(canvas, nearest, pillRect.centerX(),
                    1f + 0.16f * clamp(p, 0f, 1f), Theme.alpha(accent, near));
        }
    }

    private void drawTabs(Canvas canvas) {
        for (int i = 0; i < ICONS.length; i++) {
            float near = 1f - Math.min(1f, Math.abs(pos.value - i));
            float vis = clamp(1f - near * 2f, 0f, 1f);
            if (vis <= 0.01f) {
                continue;
            }
            float cx = barRect.left + Theme.dpf(getContext(), PAD) + (i + 0.5f) * tabW;
            drawTab(canvas, i, cx, 1f, Theme.alpha(pal.title, 0.85f * vis));
        }
    }

    private void drawTab(Canvas canvas, int index, float cx, float scale, int color) {
        labelPaint.setColor(color);
        float icon = Theme.dpf(getContext(), ICON_DP) * scale;
        float labelSize = labelPaint.getTextSize();
        float gap = Theme.dpf(getContext(), 1f);
        float total = icon + gap + labelSize;
        float top = barRect.centerY() - total / 2f;

        Icons.draw(canvas, ICONS[index], cx - icon / 2f, top, icon, color, 0f);
        canvas.drawText(LABELS[index], cx, top + icon + gap - labelPaint.ascent(), labelPaint);
    }

    // ---------------------------------------------------------------- 玻璃内核

    /**
     * 在 rect 上画一块玻璃。采样位图里这块区域已经模糊过，
     * shader 用 uCaptureOrigin/uCaptureScale 把画布坐标映射回位图坐标。
     */
    private void drawGlassAt(Canvas canvas, RectF rect, float cornerPx, float blurDp,
                             float edgePx, float amountPx, float dispersion, int tint) {
        if (sample == null || sample.isRecycled()) {
            return;
        }
        LiquidGlass.drawGlass(canvas, sample, sampleX, sampleY, SAMPLE_SCALE,
                rect, cornerPx, edgePx, amountPx, dispersion, tint, false);
    }

    // ---------------------------------------------------------------- 采样

    /**
     * 抓一次底下的内容 —— 只抓底栏这一条，0.5 分辨率，然后<b>当场模糊</b>。
     * 与 HyperIsland 的 RefractiveScreenCapture 顺序一致：先模糊，再折射。
     */
    private void captureSample() {
        if (backdropSource == null) {
            return;
        }
        try {
            int pad = Math.round(Theme.dpf(getContext(), SAMPLE_PAD));
            int[] barLoc = new int[2];
            getLocationInWindow(barLoc);
            int[] srcLoc = new int[2];
            backdropSource.getLocationInWindow(srcLoc);

            int winW = getResources().getDisplayMetrics().widthPixels;
            int winH = getResources().getDisplayMetrics().heightPixels;

            int left = Math.round(barRect.left) + barLoc[0] - pad;
            int top = Math.round(barRect.top) + barLoc[1] - pad;
            int w = Math.round(barRect.width()) + pad * 2;
            int h = Math.round(barRect.height()) + pad * 2;

            left = Math.max(0, Math.min(left, Math.max(0, winW - w)));
            top = Math.max(0, Math.min(top, Math.max(0, winH - h)));
            w = Math.min(w, winW);
            h = Math.min(h, winH);

            int ow = Math.max(2, Math.round(w * SAMPLE_SCALE));
            int oh = Math.max(2, Math.round(h * SAMPLE_SCALE));
            if (sample == null || sample.getWidth() != ow || sample.getHeight() != oh) {
                if (sample != null) {
                    sample.recycle();
                }
                sample = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            }
            sample.eraseColor(0);
            Canvas c = new Canvas(sample);
            c.scale(SAMPLE_SCALE, SAMPLE_SCALE);
            c.translate(srcLoc[0] - left, srcLoc[1] - top);
            c.clipRect(left - srcLoc[0], top - srcLoc[1],
                    left - srcLoc[0] + w, top - srcLoc[1] + h);
            backdropSource.draw(c);

            sampleX = left - barLoc[0];
            sampleY = top - barLoc[1];

            // 先模糊（9dp 屏幕 ≈ 9 × density × 0.5 位图 px），再交给折射
            LiquidGlass.blur(sample, Math.max(1,
                    Math.round(Theme.dpf(getContext(), 9f) * SAMPLE_SCALE)));
        } catch (Throwable t) {
            sample = null;
        }
    }
}
