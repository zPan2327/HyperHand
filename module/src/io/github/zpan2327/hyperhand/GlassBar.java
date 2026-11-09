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
 * 悬浮式液态玻璃底栏 —— 逐行移植 Kyant0/AndroidLiquidGlass 的
 * <pre>catalog/components/LiquidBottomTabs.kt</pre>
 *
 * 结构与源码一致（三个叠加层）：
 * <ol>
 *   <li><b>底栏</b> 64dp 胶囊：vibrancy + blur(8dp) + lens(24dp, 24dp)，
 *       表面 containerColor = 0xFFFAFAFA / 0xFF121212 @ 40%，常驻 highlight；</li>
 *   <li><b>折射层</b>（源码里 alpha=0 的 tabsBackdrop）：只参与指示器的折射，
 *       内容是"强调色图标"，所以按下时胶囊里会出现被吸出来的图标；</li>
 *   <li><b>指示器</b> 56dp 胶囊：backdrop = 页面内容 + 折射层，
 *       effects 只有 lens(10dp×p, 14dp×p, 色散)（注意：没有 blur），
 *       surface = black 10%×(1-p) + black 3%×p，highlight / innerShadow 全部 ×p。</li>
 * </ol>
 *
 * 手势（DampedDragAnimation）：按下即 press，横向拖动让指示器跟手并让整条底栏
 * 弹性位移（4dp × easeOut），松手按最近的一格吸附并切换页面；速度会带来
 * 横向拉伸 / 纵向压扁的形变。
 *
 * 弹簧参数逐个对源码：value spring(1.0, 1000)、press spring(1.0, 1000)、
 * scaleX spring(0.6, 250)、scaleY spring(0.7, 250)、panel spring(0.5, 300)。
 */
final class GlassBar extends View {

    interface OnTabSelected {
        void onSelected(int index);
    }

    private static final int[] ICONS = {Icons.HOME, Icons.GEAR, Icons.INFO};
    private static final String[] LABELS = {"概览", "设置", "关于"};

    private static final float BAR_H = 64f;
    private static final float PILL_H = 56f;
    /** pressedScale = 78f / 56f。 */
    private static final float PRESSED_SCALE = 64f / 56f;
    /** drawBackdrop 的 padding = max(padding, 40.dp)。 */
    private static final float SAMPLE_PAD = 40f;
    /** 指示器放大后上下溢出 (78-64)/2，canvas 要留出这段，否则被裁。 */
    private static final float OVERFLOW = 7f;
    /** 拖动时整条底栏的弹性位移上限。 */
    private static final float PANEL_SHIFT = 4f;

    /** 弹簧：半隐式欧拉 + 子步长，避免大 stiffness 下数值爆炸。 */
    static final class Spring {
        float value;
        float target;
        float velocity;
        float stiffness = 900f;
        float damping = 49f;

        Spring(float v) {
            value = v;
            target = v;
        }

        void set(float stiffness, float ratio) {
            this.stiffness = stiffness;
            this.damping = 2f * ratio * (float) Math.sqrt(stiffness);
        }

        void to(float t) {
            target = t;
        }

        void snap(float v) {
            value = v;
            target = v;
            velocity = 0f;
        }

        boolean settled() {
            return Math.abs(target - value) < 0.0008f && Math.abs(velocity) < 0.008f;
        }

        void step(float dt) {
            int n = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                float a = -stiffness * (value - target) - damping * velocity;
                velocity += a * h;
                value += velocity * h;
            }
            if (Math.abs(target - value) < 0.0008f && Math.abs(velocity) < 0.008f) {
                value = target;
                velocity = 0f;
            }
        }
    }

    private final Theme.Colors pal;
    private final int touchSlop;

    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pillSurfacePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF barRect = new RectF();
    private final RectF pillRect = new RectF();
    private final Rect sampleSrc = new Rect();
    private final Path edgePath = new Path();
    private final Path edgeInner = new Path();
    private final Rect edgeSrc = new Rect();
    private final RectF edgeDst = new RectF();
    private final RectF edgeInnerRect = new RectF();
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clipPath = new Path();
    private final Path pillClip = new Path();

    private float barW;
    private float barH;
    private float tabW;
    private float radius;
    private float density;

    private int selected;
    /** 指示器位置（0..n-1 连续值）。 */
    private final Spring pos = new Spring(0f);
    private final Spring press = new Spring(0f);
    private final Spring scaleX = new Spring(1f);
    private final Spring scaleY = new Spring(1f);
    /** 整条底栏的弹性位移（像素，拖动时累积，松手回 0）。 */
    private final Spring panel = new Spring(0f);
    private float panelTargetPx;

    private boolean dragging;
    private float downX;
    private float lastX;

    private OnTabSelected listener;

    private View backdropSource;
    private Bitmap sample;
    private Bitmap tabsLayer;
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
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(Theme.dp(c, Theme.T_TAB));
        labelPaint.setTypeface(Theme.medium());
        // 与 DampedDragAnimation 完全一致的弹簧参数
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
        tabsLayer = null;
        samplingFailed = false;
        invalidate();
    }

    int getSelected() {
        return selected;
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

    /** 外部切页（点按 / 代码调用）：指示器弹过去。 */
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

    void onContentScrolled() {
        postInvalidateOnAnimation();
    }

    @Override
    protected void onMeasure(int w, int h) {
        int screenW = MeasureSpec.getSize(w);
        barH = Theme.dp(getContext(), BAR_H);
        float overflow = Theme.dp(getContext(), OVERFLOW);
        // 源码是 fillMaxWidth：撑满可用宽度，视觉边距由 MainActivity 的外层 margin 控制
        barW = screenW;
        tabW = (barW - Theme.dp(getContext(), 8)) / ICONS.length;
        radius = barH / 2f;
        setMeasuredDimension(screenW, Math.round(barH + overflow * 2f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float overflow = Theme.dp(getContext(), OVERFLOW);
        // 以实际 View 宽度为准（外层 margin 决定浮动边距），避免和屏幕宽不一致
        barW = w;
        tabW = (barW - Theme.dp(getContext(), 8)) / ICONS.length;
        float left = 0f;
        barRect.set(left, overflow, left + barW, overflow + barH);
        clipPath.reset();
        Squircle.rect(clipPath, barRect, radius);
        pillRect.setEmpty();
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
                // DampedDragAnimation.press()：按下即 1，scaleX/Y 到 78/56
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
                    // onDrag：指示器按 1/tabWidth 跟手，整条底栏同步弹性位移
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
                    // onDragStopped：按最近格吸附
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
        float local = x - barRect.left - Theme.dp(getContext(), 4) - panelOffsetPx();
        return clampIndex((int) Math.floor(local / tabW));
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * panelOffset：4dp × sign(fraction) × EaseOut(|fraction|)，
     * fraction = 累积位移 / 整条宽度（源码里就是 offset / maxWidth）。
     */
    private float panelOffsetPx() {
        float fraction = clamp(panel.value / Math.max(1f, barW), -1f, 1f);
        if (fraction == 0f) {
            return 0f;
        }
        float eased = 1f - (float) Math.pow(1f - Math.abs(fraction), 3f);   // EaseOut(cubic)
        return Math.signum(fraction) * Theme.dp(getContext(), PANEL_SHIFT) * eased;
    }

    // ---------------------------------------------------------------- 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        // 每帧采样：玻璃里的背景必须与内容同步
        if (now - lastSampleAt >= 16 || sample == null) {
            captureSample();
            lastSampleAt = now;
        }

        float shift = panelOffsetPx();
        canvas.save();
        canvas.translate(shift, 0f);

        // 1) Shadow(radius = 10dp)：亮 0.1 / 暗 0.2
        RectF shadowRect = new RectF(barRect);
        shadowRect.offset(0, Theme.dp(getContext(), 1.5f));
        // 投影加重：白底上要让玻璃"离开"页面，否则看不出是独立一层
        LiquidGlass.drawSoftShadow(canvas, shadowRect, radius,
                14f * density, 0xFF0B1220, pal.night ? 0.34f : 0.16f);

        // 2) 底栏玻璃：vibrancy + blur(8dp) + lens(24dp, 24dp)
        canvas.save();
        canvas.clipPath(clipPath);
        // 底栏：磨砂 + 网格折射（位移压到 3dp，大面积才不会拖影）
        drawGlassBand(canvas, barRect, radius, 0f,
                9f * density, 3.2f * density, false, null);
        // onDrawSurface：containerColor = surface @ 0.4
        tintPaint.setColor(pal.night
                ? Theme.alpha(0xFF14161C, 0.30f)
                : Theme.alpha(0xFFF7FAFF, 0.26f));
        canvas.drawRect(barRect, tintPaint);

        // 边缘：先一圈极细暗边把玻璃和页面分开（白底上白高光是看不见的），
        // 再叠一道从上到下由亮到暗的白色高光，形成玻璃厚度。
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1f, density));
        rimPaint.setColor(pal.night
                ? Theme.alpha(0xFFFFFFFF, 0.10f)
                : Theme.alpha(0xFF0B1220, 0.06f));
        canvas.drawRoundRect(barRect, radius, radius, rimPaint);

        LiquidGlass.drawSdfHighlight(canvas, barRect, radius,
                Math.max(1.6f, 0.9f * density), 1f, 45f);

        // 底部内阴影：玻璃下缘的厚度
        LiquidGlass.drawInnerShadow(canvas, barRect, radius,
                2f * density, 7f * density, 0xFF0B1220, pal.night ? 0.30f : 0.09f);
        canvas.restore();

        // 3) 图标（底层，对应源码第一个 Row 的 content）
        drawTabs(canvas);

        // 4) 指示器（源码最上层 Box）：backdrop 含折射层，只有 lens 无 blur
        drawPill(canvas);

        // 5) InteractiveHighlight：整体 +8% 白（ADD）+ 以指示器为圆心的径向 +15%
        float cx = barRect.left + Theme.dp(getContext(), 4) + (pos.value + 0.5f) * tabW;
        LiquidGlass.drawInteractiveHighlight(canvas, barRect,
                clamp(cx, barRect.left, barRect.right), barRect.centerY(),
                clamp(press.value, 0f, 1f));

        canvas.restore();
    }

    /** 玻璃带：src/dst 外扩 SAMPLE_PAD，给 lens 的边缘折射留真实内容。 */
    private void drawGlassBand(Canvas canvas, RectF dstShape, float cornerPx,
                               float blurPx, float lensHPx, float lensAPx,
                               boolean dispersion, Bitmap overlay) {
        if (sample == null || sample.isRecycled()) {
            canvas.drawColor(pal.night ? 0xF01C1C1E : 0xF2FFFFFF);
            return;
        }
        float pad = Theme.dp(getContext(), SAMPLE_PAD);
        RectF want = new RectF(dstShape.left - pad, dstShape.top - pad,
                dstShape.right + pad, dstShape.bottom + pad);
        int l = Math.round((want.left - sampleX) * sampleScale);
        int t = Math.round((want.top - sampleY) * sampleScale);
        int r = Math.round((want.right - sampleX) * sampleScale);
        int b = Math.round((want.bottom - sampleY) * sampleScale);
        int cl = Math.max(0, Math.min(l, sample.getWidth() - 1));
        int ct = Math.max(0, Math.min(t, sample.getHeight() - 1));
        int cr = Math.max(cl + 1, Math.min(r, sample.getWidth()));
        int cb = Math.max(ct + 1, Math.min(b, sample.getHeight()));
        sampleSrc.set(cl, ct, cr, cb);
        // dst 必须跟着 src 一起裁：只裁 src 不裁 dst 会把内容纵向拉伸、错位
        RectF dst = new RectF(
                sampleX + cl / sampleScale, sampleY + ct / sampleScale,
                sampleX + cr / sampleScale, sampleY + cb / sampleScale);
        LiquidGlass.drawGlass(canvas, sample, sampleSrc, overlay, dst,
                blurPx, lensHPx, lensAPx, cornerPx, dispersion, pad);
    }

    private void drawPill(Canvas canvas) {
        if (tabW <= 0) {
            return;
        }
        float p = clamp(press.value, 0f, 1.2f);
        float inset = Theme.dp(getContext(), 4);
        float pillH = Theme.dp(getContext(), PILL_H);
        float px = barRect.left + inset + pos.value * tabW;
        float pillTop = barRect.top + (barH - pillH) / 2f;
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

        // 胶囊自身投影：从底栏里"浮"出来
        LiquidGlass.drawSoftShadow(canvas, pillRect, pillRadius,
                5f * density, 0xFF0B1220, pal.night ? 0.30f : 0.11f);

        pillClip.reset();
        Squircle.rect(pillClip, pillRect, pillRadius);
        canvas.save();
        canvas.clipPath(pillClip);

        // effects：只有 lens(10dp×p, 14dp×p, 色散)，无 blur；
        // backdrop = 页面内容 + 强调色图标层
        drawGlassBand(canvas, pillRect, pillRadius, 0f,
                8f * density * p, 5f * density * p,
                p > 0.02f && LiquidGlass.shaders(), null);

        // onDrawSurface：black 10%×(1-p)，再 black 3%×p
        // Kyant0：亮色 black 10%、暗色 white 10% —— 压出一块独立玻璃片
        pillSurfacePaint.setColor(pal.night
                ? Theme.alpha(0xFFFFFFFF, 0.12f * (1f - p))
                : Theme.alpha(0xFF0B1220, 0.075f * (1f - p)));
        canvas.drawRect(pillRect, pillSurfacePaint);
        pillSurfacePaint.setColor(Theme.alpha(0xFF000000, 0.03f * p));
        canvas.drawRect(pillRect, pillSurfacePaint);
        canvas.restore();

        // 边缘：暗边 + 白高光（同一套做法，白底上才看得出是玻璃片）
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1f, density));
        rimPaint.setColor(pal.night
                ? Theme.alpha(0xFFFFFFFF, 0.12f)
                : Theme.alpha(0xFF0B1220, 0.05f));
        canvas.drawRoundRect(pillRect, pillRadius, pillRadius, rimPaint);
        LiquidGlass.drawSdfHighlight(canvas, pillRect, pillRadius,
                Math.max(1f, 0.6f * density), 0.55f + 0.45f * p, 45f);
        if (p > 0.02f) {
            LiquidGlass.drawInnerShadow(canvas, pillRect, pillRadius,
                    8f * density * p, 8f * density * p, 0xFF000000, 0.15f * p);
        }
        canvas.restore();

        // 选中项的图标与文字：压在胶囊玻璃之上（位置跟胶囊走，但不吃胶囊的缩放）。
        // 这样胶囊的 10% 压暗不会把图标吃掉，按下时图标独立放大到 1.2。
        int nearest = clampIndex(Math.round(pos.value));
        float near = 1f - Math.min(1f, Math.abs(pos.value - nearest));
        if (near > 0.02f) {
            float ls = 22f * density * (1f + 0.2f * clamp(press.value, 0f, 1f));
            float lcx = pillRect.centerX();
            float lcy = barRect.top + barH * 0.40f;
            Icons.draw(canvas, ICONS[nearest], lcx - ls / 2f, lcy - ls / 2f, ls,
                    Theme.alpha(pal.primary, near), 1.75f * density);
            labelPaint.setColor(pal.primary);
            labelPaint.setAlpha((int) (255 * near));
            labelPaint.setTextSize(Theme.dp(getContext(), Theme.T_TAB));
            canvas.drawText(LABELS[nearest], lcx, barRect.top + barH * 0.775f, labelPaint);
        }
    }

    private void drawTabs(Canvas canvas) {
        float baseIcon = 22f * density;
        float iconCy = barRect.top + barH * 0.40f;
        float labelBase = barRect.top + barH * 0.775f;
        float pad = Theme.dp(getContext(), 4);
        labelPaint.setTextSize(Theme.dp(getContext(), Theme.T_TAB));

        // LocalLiquidBottomTabScale：lerp(1f, 1.2f, pressProgress)
        float iconScale = 1f + 0.2f * clamp(press.value, 0f, 1f);

        for (int i = 0; i < ICONS.length; i++) {
            float near = 1f - Math.min(1f, Math.abs(pos.value - i));
            // 选中的那一项交给指示器画（要压在胶囊玻璃上），这里按 near 平滑淡出，
            // 拖动到两格之间时两边都有内容，交接不会闪。
            float vis = clamp(1f - near * 2f, 0f, 1f);
            if (vis <= 0.01f) {
                continue;
            }
            int color = Theme.alpha(pal.title, 0.55f * vis);
            float s = baseIcon * iconScale;
            float cx = barRect.left + pad + (i + 0.5f) * tabW;

            Icons.draw(canvas, ICONS[i], cx - s / 2f, iconCy - s / 2f, s, color,
                    1.75f * density);
            labelPaint.setColor(pal.title);
            labelPaint.setAlpha((int) (255 * (0.55f + 0.30f * near) * vis));
            canvas.drawText(LABELS[i], cx, labelBase, labelPaint);
        }
    }

    // ---------------------------------------------------------------- 采样

    /**
     * 采两样东西：页面内容（sample）和"只含强调色图标"的折射层（tabsLayer）。
     * 两者同坐标系，指示器的透镜把后者一起折射，才会出现图标被吸进玻璃的效果。
     */
    private void captureSample() {
        if (backdropSource == null || samplingFailed) {
            return;
        }
        try {
            int pad = Math.round(Theme.dp(getContext(), SAMPLE_PAD));
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

            // 低分辨率采样：放大回原尺寸时本身就是一次柔和模糊，
            // 不依赖 RenderEffect（那套在这台机器上不生效，实测过 40dp 也不糊）
            float scale = 0.18f;
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
        } catch (Throwable t) {
            samplingFailed = true;
            sample = null;
            tabsLayer = null;
        }
    }
}
