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
 * 悬浮式液态玻璃底栏 —— 移植 Kyant0/AndroidLiquidGlass 的
 * {@code catalog/components/LiquidBottomTabs.kt}（SonyPods 的
 * {@code LiquidGlassNavigationBar.kt} 是它的同一套实现）。
 *
 * 结构完全按源码的三层：
 * <ol>
 *   <li><b>玻璃体</b>：Capsule，{@code vibrancy() → blur(8dp) → lens(24dp, 24dp)}，
 *       onDrawSurface 铺 {@code #FAFAFA @ 40%}（暗色 {@code #121212 @ 40%}），
 *       highlight 用 BloomStroke（白 0.12、双光源）；</li>
 *   <li><b>折射层</b>（源码里 alpha=0 的那排）：只把"强调色图标"画进独立层，
 *       供指示器的透镜一起折射 —— 按下时胶囊里会浮出被放大的图标；</li>
 *   <li><b>指示器</b>：56dp 高的胶囊，effects 只有
 *       {@code lens(10dp × p, 14dp × p, 色散)}，没有 blur；
 *       onDrawSurface = 黑 10% × (1-p) + 黑 3% × p；
 *       layerBlock 里做 {@code scaleX/Y} 与速度形变；innerShadow × p。</li>
 * </ol>
 *
 * 手势照搬 DampedDragAnimation：按下 press→1（scale 78/56），拖动让指示器按
 * 1/tabWidth 跟手，整条底栏还有 {@code 4dp × easeOut} 的横向橡皮筋位移。
 */
final class GlassBar extends View {

    interface OnTabSelected {
        void onSelected(int index);
    }

    private static final int[] ICONS = {Icons.HOME, Icons.GEAR, Icons.INFO};
    private static final String[] LABELS = {"概览", "设置", "关于"};

    /** 源码：Row 高 64dp、内边距 4dp；指示器高 56dp。 */
    private static final float BAR_H = 64f;
    private static final float PILL_H = 56f;
    private static final float PAD = 4f;
    /** pressedScale = 78f / 56f。 */
    private static final float PRESSED_SCALE = 78f / 56f;
    /** drawBackdrop 的 padding = max(padding, 40.dp)。 */
    private static final float SAMPLE_PAD = 40f;
    /** 指示器放大后上下溢出 (78-64)/2，canvas 要留出这段。 */
    private static final float OVERFLOW = 7f;
    /** 拖动时整条底栏的橡皮筋幅度。 */
    private static final float PANEL_SHIFT = 4f;
    /** 图标 22dp、文字 11sp（miuix footnote2）。 */
    private static final float ICON_DP = 22f;

    private final Theme.Colors pal;
    private final int touchSlop;
    /** 源码：亮色强调 #0088FF，暗色 #0091FF。 */
    private final int accent;
    /** 源码：亮色 #FAFAFA @ 40%，暗色 #121212 @ 40%。 */
    private final int containerColor;

    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillSurfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final RectF pillRect = new RectF();
    private final Rect sampleSrc = new Rect();
    private final Path clipPath = new Path();
    private final Path pillClip = new Path();

    private float barW;
    private float tabW;
    private float radius;
    private float density;

    private int selected;
    private final Ui.Spring pos = new Ui.Spring(0f);
    private final Ui.Spring press = new Ui.Spring(0f);
    private final Ui.Spring scaleX = new Ui.Spring(1f);
    private final Ui.Spring scaleY = new Ui.Spring(1f);
    private final Ui.Spring panel = new Ui.Spring(0f);
    private float panelTargetPx;

    private boolean dragging;
    private float downX;
    private float lastX;

    private OnTabSelected listener;

    private View backdropSource;
    private Bitmap sample;
    /** 指示器专用的更糊版本：胶囊里那层透镜不该看见文字形状。 */
    private Bitmap sampleSoft;
    private int sampleX;
    private int sampleY;
    private float sampleScale;
    private long lastSampleAt;
    private long lastFrameAt;
    private boolean samplingFailed;

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
            if (!panel.settled()) {
                panel.step(dt);
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
        // 源码是 surface @ 40%（#FAFAFA / #121212）。本机没有 GPU 高斯模糊，
        // 采样靠降采样 + 盒式模糊近似，40% 压不住文字的残影，会读成"脏玻璃"；
        // 62% 既能挡住残影，又仍能看出下面有内容在动。
        this.containerColor = p.night ? Theme.alpha(0xFF121212, 0.62f) : Theme.alpha(0xFFFAFAFA, 0.62f);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(Theme.dpf(c, Theme.T_TAB));
        labelPaint.setTypeface(Theme.regular());
        labelPaint.setColor(p.title);
        // DampedDragAnimation 的弹簧参数：value spring(1, 1000)、press spring(1, 1000)、
        // scaleX spring(0.6, 250)、scaleY spring(0.7, 250)、panel spring(0.5, 300)
        pos.set(1000f, 1.0f);
        press.set(1000f, 1.0f);
        scaleX.set(250f, 0.6f);
        scaleY.set(250f, 0.7f);
        panel.set(300f, 0.5f);
        setClickable(true);
        setFocusable(true);
    }

    void setOnTabSelected(OnTabSelected l) {
        listener = l;
    }

    void setBackdropSource(View v) {
        backdropSource = v;
        sample = null;
        samplingFailed = false;
        invalidate();
    }

    void setSelectedImmediate(int index) {
        selected = clampIndex(index);
        pos.snap(selected);
        press.snap(0f);
        scaleX.snap(1f);
        scaleY.snap(1f);
        panel.snap(0f);
        invalidate();
    }

    void select(int index, boolean animate) {
        int target = clampIndex(index);
        selected = target;
        if (!animate) {
            pos.snap(target);
            invalidate();
            return;
        }
        pos.to(target);
        kick();
    }

    private void kick() {
        lastFrameAt = 0;
        removeCallbacks(ticker);
        postOnAnimation(ticker);
    }

    private static int clampIndex(int i) {
        return i < 0 ? 0 : (i >= ICONS.length ? ICONS.length - 1 : i);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int screenW = MeasureSpec.getSize(w);
        setMeasuredDimension(screenW, Math.round(Theme.dpf(getContext(), BAR_H + OVERFLOW * 2f)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float overflow = Theme.dpf(getContext(), OVERFLOW);
        barW = w;
        tabW = (barW - Theme.dpf(getContext(), 8)) / ICONS.length;
        barRect.set(0f, overflow, barW, overflow + Theme.dpf(getContext(), BAR_H));
        radius = barRect.height() / 2f;
        clipPath.reset();
        Squircle.rect(clipPath, barRect, radius);
        sample = null;
    }

    // ---------------------------------------------------------------- 手势

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX();
                lastX = downX;
                dragging = false;
                press.to(1f);
                scaleX.to(PRESSED_SCALE);
                scaleY.to(PRESSED_SCALE);
                kick();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float x = e.getX();
                float dx = x - lastX;
                lastX = x;
                if (!dragging && Math.abs(x - downX) > touchSlop) {
                    dragging = true;
                }
                if (dragging) {
                    pos.to(clamp(pos.target + dx / tabW, 0f, ICONS.length - 1));
                    panelTargetPx = clamp(panelTargetPx + dx, -barW * 3f, barW * 3f);
                    panel.to(panelTargetPx);
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
                panelTargetPx = 0f;
                panel.to(0f);
                if (target != selected) {
                    selected = target;
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
        float local = x - Theme.dpf(getContext(), PAD) - panelOffsetPx();
        return clampIndex((int) Math.floor(local / tabW));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** panelOffset：4dp × sign(fraction) × EaseOut(|fraction|)。 */
    private float panelOffsetPx() {
        float fraction = clamp(panel.value / Math.max(1f, barW), -1f, 1f);
        if (fraction == 0f) {
            return 0f;
        }
        float eased = 1f - (float) Math.pow(1f - Math.abs(fraction), 3f);
        return Math.signum(fraction) * Theme.dpf(getContext(), PANEL_SHIFT) * eased;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        if (now - lastSampleAt >= 16 || sample == null) {
            captureSample();
            lastSampleAt = now;
        }

        float shift = panelOffsetPx();
        canvas.save();
        canvas.translate(shift, 0f);

        // 1) Shadow(radius = 10dp)：亮 0.1 / 暗 0.2
        RectF shadowRect = new RectF(barRect);
        LiquidGlass.drawSoftShadow(canvas, shadowRect, radius,
                Theme.dpf(getContext(), 10f), 0xFF000000, pal.night ? 0.20f : 0.10f);

        // 2) 玻璃体：vibrancy + blur(8dp) + lens(24dp, 24dp)
        canvas.save();
        canvas.clipPath(clipPath);
        drawGlassBand(canvas, barRect, radius, Theme.dpf(getContext(), 8f),
                Theme.dpf(getContext(), 24f), Theme.dpf(getContext(), 24f));
        // onDrawSurface：containerColor
        tintPaint.setColor(containerColor);
        canvas.drawRect(barRect, tintPaint);

        // highlight：iosIndicatorSpecular.copy(alpha = 0.75f)
        LiquidGlass.drawSdfHighlight(canvas, barRect, radius,
                Math.max(1f, 1f * density), 0.75f, 45f);
        canvas.restore();

        // 3) 未选中的图标 + 文字
        drawTabs(canvas);

        // 4) 指示器
        drawPill(canvas);

        // 5) InteractiveHighlight
        float cx = Theme.dpf(getContext(), PAD) + (pos.value + 0.5f) * tabW;
        LiquidGlass.drawInteractiveHighlight(canvas, barRect,
                clamp(cx, barRect.left, barRect.right), barRect.centerY(),
                clamp(press.value, 0f, 1f));

        canvas.restore();
    }

    /** 玻璃带：src/dst 外扩 SAMPLE_PAD，给 lens 的边缘折射留真实内容。 */
    private void drawGlassBand(Canvas canvas, RectF dstShape, float cornerPx,
                               float blurPx, float lensHPx, float lensAPx) {
        drawGlassBand(canvas, dstShape, cornerPx, blurPx, lensHPx, lensAPx, sample);
    }

    private void drawGlassBand(Canvas canvas, RectF dstShape, float cornerPx,
                               float blurPx, float lensHPx, float lensAPx, Bitmap src) {
        if (src == null || src.isRecycled()) {
            canvas.drawColor(pal.night ? 0xF21C1C1E : 0xF2FFFFFF);
            return;
        }
        float pad = Theme.dpf(getContext(), SAMPLE_PAD);
        RectF want = new RectF(dstShape.left - pad, dstShape.top - pad,
                dstShape.right + pad, dstShape.bottom + pad);
        int l = Math.round((want.left - sampleX) * sampleScale);
        int t = Math.round((want.top - sampleY) * sampleScale);
        int r = Math.round((want.right - sampleX) * sampleScale);
        int b = Math.round((want.bottom - sampleY) * sampleScale);
        int cl = Math.max(0, Math.min(l, src.getWidth() - 1));
        int ct = Math.max(0, Math.min(t, src.getHeight() - 1));
        int cr = Math.max(cl + 1, Math.min(r, src.getWidth()));
        int cb = Math.max(ct + 1, Math.min(b, src.getHeight()));
        sampleSrc.set(cl, ct, cr, cb);
        RectF dst = new RectF(
                sampleX + cl / sampleScale, sampleY + ct / sampleScale,
                sampleX + cr / sampleScale, sampleY + cb / sampleScale);
        LiquidGlass.drawGlass(canvas, src, sampleSrc, dst,
                blurPx, lensHPx, lensAPx, cornerPx, false, pad);
    }

    private void drawPill(Canvas canvas) {
        if (tabW <= 0) {
            return;
        }
        float p = clamp(press.value, 0f, 1.2f);
        float inset = Theme.dpf(getContext(), PAD);
        float pillH = Theme.dpf(getContext(), PILL_H);
        float px = barRect.left + inset + pos.value * tabW;
        float pillTop = barRect.top + (barRect.height() - pillH) / 2f;
        pillRect.set(px, pillTop, px + tabW, pillTop + pillH);
        float pillRadius = pillH / 2f;

        float cx = pillRect.centerX();
        float cy = pillRect.centerY();

        // layerBlock：scaleX/Y 弹簧，再按速度做拉伸 / 压扁（velocity / 10）
        float vel = pos.velocity / 10f;
        float sx = scaleX.value / (1f - clamp(vel * 0.75f, -0.2f, 0.2f));
        float sy = scaleY.value * (1f - clamp(vel * 0.25f, -0.2f, 0.2f));

        canvas.save();
        canvas.scale(sx, sy, cx, cy);

        pillClip.reset();
        Squircle.rect(pillClip, pillRect, pillRadius);
        canvas.save();
        canvas.clipPath(pillClip);
        // 源码里指示器的 effects 只有 lens，没有 blur —— 它靠 RuntimeShader 的折射
        // 把底下的内容"推"开，看起来才像一块玻璃片。这台机器的硬件画布不执行
        // RenderEffect / RuntimeShader，只剩 CPU 网格折射，静止时会把底下的文字
        // 拖成一片脏痕。所以静止时走 miuix GlassTabRow / SonyPods 无模糊时的回退：
        // 一块纯色磨砂胶囊；按下（p > 0）再叠上折射层，保留"液体"的手感。
        if (p > 0.05f) {
            drawGlassBand(canvas, pillRect, pillRadius, 0f,
                    Theme.dpf(getContext(), 10f) * p, Theme.dpf(getContext(), 14f) * p,
                    sampleSoft != null ? sampleSoft : sample);
        }
        // 磨砂底色：亮色压一层黑（源码 black 10%），暗色压一层白（white 10%），
        // 让胶囊从底栏里"浮"出来；按下时叠折射层再加一点反向蒙版
        float base = 1f - p;
        pillSurfacePaint.setColor(pal.night
                ? Theme.alpha(0xFFFFFFFF, 0.10f * base + 0.14f * p)
                : Theme.alpha(0xFF000000, 0.075f * base + 0.05f * p));
        canvas.drawRect(pillRect, pillSurfacePaint);
        pillSurfacePaint.setColor(Theme.alpha(0xFF000000, 0.03f * p));
        canvas.drawRect(pillRect, pillSurfacePaint);
        canvas.restore();

        // highlight：BloomStroke，alpha = progress
        LiquidGlass.drawSdfHighlight(canvas, pillRect, pillRadius,
                Math.max(1f, 1f * density), 0.55f + 0.45f * p, 45f);
        // innerShadow：radius 8dp × p、alpha 0.15 × p
        if (p > 0.02f) {
            LiquidGlass.drawInnerShadow(canvas, pillRect, pillRadius,
                    Theme.dpf(getContext(), 8f) * p, Theme.dpf(getContext(), 8f) * p,
                    0xFF000000, 0.15f * p);
        }
        canvas.restore();

        // 选中项：图标 + 文字压在胶囊玻璃之上（位置跟胶囊走，但吃 1.2 的放大）
        int nearest = clampIndex(Math.round(pos.value));
        float near = 1f - Math.min(1f, Math.abs(pos.value - nearest));
        if (near > 0.02f) {
            drawTab(canvas, nearest, pillRect.centerX(), 1f + 0.2f * clamp(press.value, 0f, 1f),
                    Theme.alpha(accent, near), true);
        }
    }

    private void drawTabs(Canvas canvas) {
        float pad = Theme.dpf(getContext(), PAD);
        float iconScale = 1f + 0.2f * clamp(press.value, 0f, 1f);

        for (int i = 0; i < ICONS.length; i++) {
            float near = 1f - Math.min(1f, Math.abs(pos.value - i));
            // 选中的那一项交给指示器画，这里按 near 平滑淡出
            float vis = clamp(1f - near * 2f, 0f, 1f);
            if (vis <= 0.01f) {
                continue;
            }
            float cx = barRect.left + pad + (i + 0.5f) * tabW;
            drawTab(canvas, i, cx, iconScale, Theme.alpha(pal.title, vis), false);
        }
    }

    /**
     * 一个 tab 的内容：图标 22dp 在上、文字 11sp 在下，两者作为一组垂直居中
     * （源码是 Column + Arrangement.spacedBy(1dp, CenterVertically)）。
     */
    private void drawTab(Canvas canvas, int index, float cx, float scale, int color,
                         boolean withLabel) {
        float icon = Theme.dpf(getContext(), ICON_DP) * scale;
        float labelSize = labelPaint.getTextSize();
        float gap = Theme.dpf(getContext(), 1f);
        float total = icon + gap + labelSize * 0.95f;
        float top = barRect.centerY() - total / 2f;

        Icons.draw(canvas, ICONS[index], cx - icon / 2f, top, icon, color, 0f);
        if (withLabel || true) {
            labelPaint.setColor(color);
            float baseline = top + icon + gap - labelPaint.ascent();
            canvas.drawText(LABELS[index], cx, baseline, labelPaint);
        }
    }

    // ---------------------------------------------------------------- 采样

    /**
     * 采样页面内容。源码走 LayerBackdrop + RenderEffect；这台机器的硬件画布
     * 会忽略 Paint/RenderNode 上的 RenderEffect（实测 40dp 也无变化），
     * 所以这里用"低分辨率采样 → 双线性放大"做等效模糊，再叠一层盒式模糊压掉
     * 采样时的锯齿，观感接近 8dp 高斯。
     */
    private void captureSample() {
        if (backdropSource == null || samplingFailed) {
            return;
        }
        try {
            int pad = Math.round(Theme.dpf(getContext(), SAMPLE_PAD));
            int[] barLoc = new int[2];
            getLocationInWindow(barLoc);
            int[] srcLoc = new int[2];
            backdropSource.getLocationInWindow(srcLoc);

            float shift = panelOffsetPx();
            int winW = getResources().getDisplayMetrics().widthPixels;
            int winH = getResources().getDisplayMetrics().heightPixels;

            int left = Math.round(barRect.left + shift) + barLoc[0] - pad;
            int top = Math.round(barRect.top) + barLoc[1] - pad;
            int w = Math.round(barRect.width()) + pad * 2;
            int h = Math.round(barRect.height()) + pad * 2;

            left = Math.max(0, Math.min(left, Math.max(0, winW - w)));
            top = Math.max(0, Math.min(top, Math.max(0, winH - h)));
            w = Math.min(w, winW);
            h = Math.min(h, winH);

            // 0.28 采样 → 放大回原尺寸自带一次柔和模糊，再叠 5px 盒式模糊，
            // 等效半径接近源码的 blur(8dp)
            final float scale = 0.28f;
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

            boxBlur(sample, 6);

            // 指示器那一层再糊一次（在副本上做，主样本保持清晰给玻璃体用）
            if (sampleSoft == null || sampleSoft.getWidth() != ow || sampleSoft.getHeight() != oh) {
                if (sampleSoft != null) {
                    sampleSoft.recycle();
                }
                sampleSoft = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            }
            new Canvas(sampleSoft).drawBitmap(sample, 0f, 0f, null);
            boxBlur(sampleSoft, 6);
            boxBlur(sampleSoft, 6);

            sampleX = left - barLoc[0];
            sampleY = top - barLoc[1];
            sampleScale = scale;
        } catch (Throwable t) {
            samplingFailed = true;
            sample = null;
            sampleSoft = null;
        }
    }

    /**
     * 小半径盒式模糊（可分离两趟）。小图上做，代价可以忽略；
     * 目的是把文字边缘彻底化开，否则玻璃里会看到"脏斑"。
     */
    private static void boxBlur(Bitmap bmp, int radius) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w < 3 || h < 3 || radius <= 0) {
            return;
        }
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[w * h];
        int div = radius * 2 + 1;
        // 横向
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int r = 0;
            int g = 0;
            int b = 0;
            int a = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = px[row + Math.max(0, Math.min(w - 1, i))];
                a += (p >>> 24);
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = ((a / div) << 24) | ((r / div) << 16) | ((g / div) << 8) | (b / div);
                int out = px[row + Math.min(w - 1, x + radius + 1)];
                int in = px[row + Math.max(0, x - radius)];
                a += ((out >>> 24) - (in >>> 24));
                r += (((out >> 16) & 0xFF) - ((in >> 16) & 0xFF));
                g += (((out >> 8) & 0xFF) - ((in >> 8) & 0xFF));
                b += ((out & 0xFF) - (in & 0xFF));
            }
        }
        // 纵向
        for (int x = 0; x < w; x++) {
            int r = 0;
            int g = 0;
            int b = 0;
            int a = 0;
            for (int i = -radius; i <= radius; i++) {
                int p = tmp[Math.max(0, Math.min(h - 1, i)) * w + x];
                a += (p >>> 24);
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                px[y * w + x] = ((a / div) << 24) | ((r / div) << 16) | ((g / div) << 8) | (b / div);
                int out = tmp[Math.min(h - 1, y + radius + 1) * w + x];
                int in = tmp[Math.max(0, y - radius) * w + x];
                a += ((out >>> 24) - (in >>> 24));
                r += (((out >> 16) & 0xFF) - ((in >> 16) & 0xFF));
                g += (((out >> 8) & 0xFF) - ((in >> 8) & 0xFF));
                b += ((out & 0xFF) - (in & 0xFF));
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }
}
