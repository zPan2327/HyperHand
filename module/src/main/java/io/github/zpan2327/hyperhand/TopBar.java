package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * 折叠式大标题栏 —— **一页一个实例**，逐条对齐 LSPosed（Vector 2.2 / Miuix）的 TopAppBar：
 *
 * <ul>
 *   <li>展开：大标题 title1 = 32sp / Normal，左边距 {@code TitlePadding = 26dp}，
 *       下面 14sp 副标题；</li>
 *   <li>折叠：小标题 title3 = 20sp / Medium，**水平居中**（LSPosed 的折叠条就是居中标题）；</li>
 *   <li>背景：**近乎不透光的纯色 + 很淡的高斯模糊**，内容从条底硬切穿过，不做渐隐、
 *       不画分隔线。</li>
 * </ul>
 *
 * ## 模糊到底要多「淡」
 *
 * 与 LSPosed 2.2.1 逐帧比对（Repository 长列表，内容确实从条底穿过）：它的折叠条
 * 看上去就是一片干净的底色，只在极近距离下能察觉到背后内容的一点点色晕 ——
 * 也就是「底色几乎不透光，模糊只在最上面留一层很淡的质感」。
 *
 * 所以这里底色取 {@code pal.bg × 0.90}（不是之前的 0.32，那会明显看到卡片色块，
 * 也不是 1.0，那就完全没有模糊了），模糊层在下面承担那 10%。
 *
 * ## 为什么是「一页一条」而不是「一条共享 + 按位移画两页」
 *
 * 旧实现只有一条 TopBar，横滑时按浮点页位置把两页的标题一起平移、并按两页的折叠进度
 * 插值底色。结果是：只要两页的折叠状态不同（一页在顶部、一页已经滚下去），横滑过程中
 * 顶栏的底色/标题位置就会出现一段「谁都不是」的中间态，看起来就是标题乱飘。
 *
 * 现在顶栏是页面的一部分（挂在 {@link PageHost} 里，跟页面一起被 pager 平移），
 * 每页各自维护自己的折叠进度，互不干扰 —— 横滑时就是两条独立的顶栏一起滑过去，
 * 与 LSPosed 的 HorizontalPager + 每页自带 TopAppBar 的结构一致。
 */
final class TopBar extends View {

    interface OnAction {
        void onAction();
    }

    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint largePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Theme.Colors pal;
    /** 条体底色；每帧复用，不新分配。 */
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** 右上角按钮的按下态圆底。 */
    private final Paint hitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private String title = "";
    private String subtitle = "";
    private String large = "";
    private boolean showLarge = true;
    /**
     * 该页顶栏不用「快速实色」曲线：内容从底下滚过、滚到底才化成页面底色。
     * 关于页是这种——它自带整屏渐变，顶栏太早压成实色会把渐变切出一条边。
     */
    private boolean plain;
    private int actionIcon = -1;
    private OnAction action;

    private float collapse;
    private int statusInset;

    // ---------------------------------------------------------------- 模糊

    /** 模糊的取材来源：本页的内容 View（不是整棵 contentHost，避免把顶栏自己录进去）。 */
    private View blurTarget;
    /**
     * RenderNode 录制 + RenderEffect 模糊。
     *
     * <p>不能用「画进 Bitmap 再跑盒式模糊」：页面里有 Compose 子树（Miuix squircle 用
     * RuntimeShader 画圆角），软件画布会抛
     * {@code Software rendering doesn't support RuntimeShader}。
     */
    private RenderNode blurNode;
    private RenderEffect blurEffect;
    private int blurRadiusPx = -1;
    private boolean blurDirty = true;
    /** 当前滚动位置（真实像素），由 {@link #setScroll(int)} 维护。 */
    private int scrollY;
    /** 上一帧录制时刻：120Hz 下没必要每帧重录，16ms 一次足够跟手（最多落后一帧）。 */
    private long lastRecordAt;

    private final ViewTreeObserver.OnPreDrawListener preDraw =
            new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    if (!blurDirty) {
                        return true;
                    }
                    long now = android.os.SystemClock.uptimeMillis();
                    if (now - lastRecordAt < 16L) {
                        invalidate();
                        return true;
                    }
                    lastRecordAt = now;
                    blurDirty = false;
                    updateBlur();
                    return true;
                }
            };

    private final RectF actionRect = new RectF();

    TopBar(Context c, Theme.Colors p) {
        super(c);
        this.pal = p;
        titlePaint.setTypeface(Theme.medium());
        titlePaint.setTextSize(Theme.dpf(c, Theme.T_HERO));
        titlePaint.setColor(pal.title);
        subPaint.setTypeface(Theme.regular());
        subPaint.setTextSize(Theme.dpf(c, Theme.T_SUM));
        subPaint.setColor(pal.summary);
        largePaint.setTypeface(Theme.regular());
        largePaint.setTextSize(Theme.dpf(c, Theme.T_DISPLAY));
        largePaint.setColor(pal.title);
        setClickable(true);
    }

    /** 注册本页顶栏文案与右上角动作（只调用一次，构建时）。 */
    void setPage(String title, String subtitle, boolean showLarge, boolean plain,
                 int actionIcon, OnAction action) {
        this.title = title == null ? "" : title;
        this.large = showLarge ? this.title : "";
        this.subtitle = subtitle == null ? "" : subtitle;
        this.showLarge = showLarge;
        this.plain = plain;
        this.actionIcon = actionIcon;
        this.action = action;
        collapse = 0f;
        blurDirty = true;
        requestLayout();
        invalidate();
    }

    /** 模糊的取材 View（本页内容）。 */
    void setBlurTarget(View target) {
        blurTarget = target;
        blurDirty = true;
        invalidate();
    }

    void setStatusInset(int inset) {
        if (statusInset == inset) {
            return;
        }
        statusInset = inset;
        blurDirty = true;
        requestLayout();
        invalidate();
    }

    /**
     * 展开状态下的总高度。
     *
     * <p>固定为「状态栏 + 52dp 标题条 + 大标题块」：不随当前页是否有大标题变化，
     * 否则切页时内容会整体跳一下。关于页自己 {@code padTop = 0}，不用这个高度。
     */
    int expandedHeight() {
        return statusInset + Theme.dp(getContext(), Theme.BAR_H)
                + Theme.dp(getContext(), Theme.HEADER_BLOCK);
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
        scrollY = y;
        float t = y / headerBlockHeight();
        blurDirty = true;   // 内容在动，模糊层必须重录
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

    /**
     * 内容动过（滚动 / 越界形变 / 横滑）：模糊层必须重新录制。
     *
     * <p>折叠进度不变时 {@link #setCollapse} 会提前返回，但条体下面的内容还在动，
     * 不主动标脏的话模糊会僵在上一帧 —— 表现就是「顶栏像贴了张静止的糊图」。
     */
    void onContentScrolled() {
        blurDirty = true;
        invalidate();
    }

    // ---------------------------------------------------------------- 模糊实现

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(preDraw);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(preDraw);
        super.onDetachedFromWindow();
    }

    private void updateBlur() {
        if (plain || blurTarget == null) {
            return;
        }
        int w = getWidth();
        int h = statusInset + Theme.dp(getContext(), Theme.BAR_H);
        if (w <= 0 || h <= 0 || blurTarget.getWidth() <= 0 || blurTarget.getHeight() <= 0) {
            return;
        }
        if (blurNode == null) {
            blurNode = new RenderNode("hh-topbar-blur");
        }
        if (blurNode.getWidth() != w || blurNode.getHeight() != h) {
            blurNode.setPosition(0, 0, w, h);
        }
        int radius = Math.max(1, Theme.dp(getContext(), 18));
        if (radius != blurRadiusPx) {
            blurRadiusPx = radius;
            blurEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP);
            blurNode.setRenderEffect(blurEffect);
        }
        try {
            android.graphics.RecordingCanvas rc = blurNode.beginRecording();
            // 关键：View#draw 从**视图原点**开始画，而不是「当前可见区域」。
            // 必须按 scrollY 反向平移，否则录到的是内容顶部的空白 ——
            // 模糊层会整块透明，看起来就像根本没做模糊。
            rc.translate(0, -scrollY);
            blurTarget.draw(rc);
            blurNode.endRecording();
        } catch (Throwable t) {
            // 录制失败（例如 View 正在被绘制）：保留上一帧结果，不让顶栏消失。
            android.util.Log.w("HHTopBar", "blur capture failed", t);
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(MeasureSpec.getSize(w), expandedHeight());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float barH = Theme.dpf(getContext(), Theme.BAR_H);
        float barBottom = statusInset + barH;

        // 条体的不透明度：展开态完全透明（这块本来就是页面底色，盖不盖都一样），
        // 滚起来后快速升到 1。solid=1 时是**几乎不透光**的实色：背后内容只剩 10% 的
        // 模糊色晕，这就是 LSPosed 那条「很淡」的模糊。
        float solid = Theme.clamp01(collapse / 0.12f);
        if (plain) {
            // 关于页：不吃 8% 的快速斜坡，滚到底才化底色（它自带整屏渐变）。
            solid = Theme.clamp01((collapse - 0.86f) / 0.14f);
        }
        if (solid > 0.003f) {
            if (!plain && blurNode != null) {
                // 1) 模糊层：不透明的本页内容（条体那一条），给底色提供质感
                int layer = canvas.saveLayerAlpha(0f, 0f, w, barBottom,
                        Math.round(255 * solid));
                canvas.drawRenderNode(blurNode);
                canvas.restoreToCount(layer);
            }
            // 2) 底色：压在模糊层上，90% —— 只让一点点色晕透上来。100% 就没有模糊了，
            //    再低（之前 32%）就会明显看到卡片色块，那正是用户说的「半透明」。
            float base = plain ? solid : solid * 0.90f;
            bgPaint.setColor(Theme.alpha(pal.bg, base));
            canvas.drawRect(0f, 0f, w, barBottom, bgPaint);
        }

        // ---------------------------------------------------------- 标题
        float pad = Theme.dpf(getContext(), Theme.TITLE_PAD);

        // 展开态大标题：跟着折叠进度上移淡出。
        float largeAlpha = 1f - collapse;
        if (showLarge && largeAlpha > 0.01f && title.length() > 0) {
            float top = statusInset + barH;
            canvas.save();
            canvas.translate(0, -collapse * headerBlockHeight() * 0.35f);
            largePaint.setAlpha(Math.round(255 * Theme.clamp01(largeAlpha)));
            canvas.drawText(title, pad, top + Theme.dpf(getContext(), 32), largePaint);
            subPaint.setAlpha(Math.round(255 * Theme.clamp01(largeAlpha)));
            canvas.drawText(subtitle, pad, top + Theme.dpf(getContext(), 54), subPaint);
            canvas.restore();
        }

        // 折叠态小标题：**居中**（LSPosed / Miuix TopAppBar 的折叠标题就是居中）。
        float smallAlpha = Math.max(0f, (collapse - 0.35f) / 0.65f);
        if (smallAlpha > 0.01f && title.length() > 0) {
            titlePaint.setAlpha(Math.round(255 * Theme.clamp01(smallAlpha)));
            float baseline = statusInset + barH / 2f
                    - (titlePaint.ascent() + titlePaint.descent()) / 2f;
            Paint.Align oldAlign = titlePaint.getTextAlign();
            titlePaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(title, w / 2f, baseline, titlePaint);
            titlePaint.setTextAlign(oldAlign);
        }

        // ---------------------------------------------------------- 右上角动作
        if (actionIcon >= 0) {
            float size = Theme.dpf(getContext(), 22);
            float right = w - Theme.dpf(getContext(), 20);
            float cy = statusInset + barH / 2f;
            float left = right - size;
            // 动作图标**常显**，不跟着大标题的淡出一起消失。
            if (isPressed()) {
                hitPaint.setColor(Theme.alpha(pal.title, 0.10f));
                canvas.drawCircle((left + right) / 2f, cy, Theme.dpf(getContext(), 20), hitPaint);
            }
            Icons.draw(canvas, actionIcon, left, cy - size / 2f, size, pal.title, 0f);
            actionRect.set(left - Theme.dpf(getContext(), 12), cy - Theme.dpf(getContext(), 20),
                    right + Theme.dpf(getContext(), 12), cy + Theme.dpf(getContext(), 20));
        } else {
            actionRect.setEmpty();
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
                invalidate();   // 按下态圆底要立刻出来
                return true;
            }
            return false;
        }
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
            boolean inside = actionRect.contains(e.getX(), e.getY());
            setPressed(false);
            invalidate();
            if (inside && action != null) {
                performClick();
                Haptics.get(getContext()).click();
                action.onAction();
            }
            return inside;
        }
        if (e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) {
            setPressed(false);
            invalidate();
        }
        return true;
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
