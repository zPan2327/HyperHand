package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 折叠式大标题栏 —— 逐条对齐 miuix 的 {@code TopAppBar}：
 *
 * <ul>
 *   <li>收起的标题：title3 = 20sp / Medium，位于 {@code CollapsedHeight = 52dp} 的条里；</li>
 *   <li>展开的大标题：title1 = 32sp / Normal，左边距 {@code TitlePadding = 26dp}；</li>
 *   <li>副标题：body2 = 14sp，颜色 onSurfaceVariantSummary；</li>
 *   <li>背景：miuix {@code progressiveTextureBlur(gradient = ProgressiveBlur.Top)} ——
 *       顶部全额模糊、中段中等模糊、底边像素级清晰。<b>不再叠实色黑底</b>
 *       （旧实现收起后画 {@code #17171B @ 86%}，就是滚动到底时"一团黑"的来源）。</li>
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

    private final Theme.Colors pal;
    private final ProgressiveBlur progressive;

    private String title = "";
    private String subtitle = "";
    private String large = "";
    private int actionIcon = -1;
    private OnAction action;

    /** 全部页面的文案，索引与 {@code PagePager} 一致。横滑时几页的标题一起画。 */
    private String[] pageTitles = new String[0];
    private String[] pageSubs = new String[0];
    private boolean[] pageLarge = new boolean[0];
    /**
     * 该页顶栏不用渐进模糊，而是「内容从底下滚过、滚到底才化成页面底色」。
     * 关于页是这种：它自带整屏渐变，顶栏一旦压上模糊带就会把渐变切出一条边。
     */
    private boolean[] pagePlain = new boolean[0];
    /** 每页各自的折叠进度。横滑时两页可以处在不同折叠状态。 */
    private float[] pageCollapse = new float[0];
    /** 浮点页位置（0..N-1），由横滑手势直接驱动。 */
    private float pagePos;
    /** 当前吸附页：决定右上角按钮归属与高度。 */
    private int currentPage;

    private float collapse;
    private int statusInset;

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
        progressive = new ProgressiveBlur(c, p, this);
        // 与 SonyPods BarBlur.kt 的 TOP_BAR_PROGRESSIVE_BLUR 一致：
        //   ProgressiveBlur.Top.copy(startFraction = 0.12f, endFraction = 1f, curve = 1.25f)
        //   blurRadius = 16f, BlendColorEntry(surfaceColor @ alpha)
        // 顶部 12% 保持全额模糊，之后按 1.25 次幂衰减到 band 底边完全清晰。
        progressive.setGradient(ProgressiveBlur.Gradient.TOP);
        setClickable(true);
    }

    /**
     * 注册所有页面的顶栏文案。索引与 {@code PagePager} 的子 View 顺序一致。
     *
     * <p>必须一次性给全：横滑过程中需要同时绘制「正在滑出的页」和「正在滑入的页」，
     * 不能像旧实现那样在 {@code onPageSelected} 里把标题整个换掉 —— 那一下就是用户
     * 看到的「突然跳变」。
     */
    void setPages(String[] titles, String[] subtitles, boolean[] showLarge, boolean[] plainBar) {
        int n = titles == null ? 0 : titles.length;
        pageTitles = new String[n];
        pageSubs = new String[n];
        pageLarge = new boolean[n];
        pagePlain = new boolean[n];
        pageCollapse = new float[n];
        for (int i = 0; i < n; i++) {
            pageTitles[i] = titles[i] == null ? "" : titles[i];
            pageSubs[i] = subtitles != null && i < subtitles.length && subtitles[i] != null
                    ? subtitles[i] : "";
            pageLarge[i] = showLarge == null || i >= showLarge.length || showLarge[i];
            pagePlain[i] = plainBar != null && i < plainBar.length && plainBar[i];
            pageCollapse[i] = 0f;
        }
        requestLayout();
        invalidate();
    }

    /** 横滑位置（浮点页索引）。两页标题按该位置整体平移，跟手滑动。 */
    void setPagePosition(float pos) {
        if (Math.abs(pos - pagePos) < 0.0005f) {
            return;
        }
        pagePos = pos;
        invalidate();
    }

    /** 吸附到某一页：只切右上角动作与归属，标题由 {@link #setPagePosition} 连续驱动。 */
    void setCurrentPage(int index, int actionIcon, OnAction action) {
        currentPage = index;
        this.actionIcon = actionIcon;
        this.action = action;
        invalidate();
    }

    /** 单独更新某一页的折叠进度（滚动回调里按页索引写）。 */
    void setCollapseAt(int index, float t) {
        if (index < 0 || index >= pageCollapse.length) {
            return;
        }
        if (Math.abs(t - pageCollapse[index]) < 0.001f) {
            return;
        }
        pageCollapse[index] = t;
        // 只有这一页正处在可见范围里才需要重绘
        if (Math.abs(index - pagePos) < 0.999f) {
            invalidate();
        }
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
        progressive.setBackdropSource(v);
    }

    /** 折叠条的模糊强度：miuix BlurDefaults 默认 20dp。 */
    void setBlurRadius(float dp) {
        progressive.setBlurRadius(dp);
    }

    void setStatusInset(int inset) {
        if (statusInset == inset) {
            return;
        }
        statusInset = inset;
        requestLayout();
    }

    /**
     * 展开状态下的总高度。
     *
     * <p>固定为「状态栏 + 52dp 标题条 + 大标题块」，不再看当前页是否有大标题 ——
     * 否则从概览滑到关于页时高度会突然矮一截，内容整体跳一下。关于页本身
     * {@code padTop = 0}（渐变英雄区铺到状态栏下），不依赖这个高度，多出来的
     * 透明区域既不绘制也不拦截触摸。
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
        float t = y / headerBlockHeight();
        setCollapse(t < 0f ? 0f : (t > 1f ? 1f : t));
    }

    void setCollapse(float t) {
        if (Math.abs(t - collapse) < 0.001f) {
            return;
        }
        collapse = t;
        if (currentPage >= 0 && currentPage < pageCollapse.length) {
            pageCollapse[currentPage] = t;
        }
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
        // 本次重绘一定对应 backdrop 的一次变化（滚动 / 折叠 / 横滑 / 橡皮筋）。
        // 声明脏，让 ProgressiveBlur 在下面 draw() 时同步重采一次，二者严格同帧。
        // 放在 onDraw 而不是各 setter 里，才能覆盖所有 invalidate 来源。
        progressive.invalidateCapture();
        int n = pageTitles.length;
        if (n == 0) {
            return;
        }
        float w = getWidth();
        float barH = Theme.dpf(getContext(), Theme.BAR_H);
        float barBottom = statusInset + barH;

        int base = (int) Math.floor(pagePos);
        if (base < 0) {
            base = 0;
        }
        if (base > n - 1) {
            base = n - 1;
        }
        float frac = pagePos - base;
        if (frac < 0.001f || base + 1 >= n) {
            frac = 0f;
        }

        // 模糊带的强度取两页折叠状态的插值：旧页可能是折叠的、新页是展开的，
        // 插值后带高与模糊厚度连续变化，不会在切页瞬间跳一下。
        float cA = pageCollapse[base];
        float cB = base + 1 < n ? pageCollapse[base + 1] : cA;
        float c = cA + (cB - cA) * frac;

        float fade = Theme.dpf(getContext(), 18f + 26f * (1f - c));
        // 关于页这类「自带整屏渐变」的页面不走模糊：横滑时按页插值 plainness，
        // 到了一半以上就直接用底色条，避免两套绘制叠在一起闪一下。
        float plain = 0f;
        if (n > 0) {
            float pa = pagePlain[base] ? 1f : 0f;
            float pb = base + 1 < n && pagePlain[base + 1] ? 1f : 0f;
            plain = pa + (pb - pa) * frac;
        }
        if (plain < 0.5f) {
            progressive.draw(canvas, 0f, barBottom + fade, c);
        } else {
            // 参考版式：顶栏在滚动过程中保持透明（内容从底下经过），
            // 直到快滚到底才化成页面底色 —— 视觉上就是「渐变 → 纯白」。
            float solid = Theme.clamp01((c - 0.86f) / 0.14f) * plain;
            if (solid > 0.003f) {
                Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
                fill.setColor(Theme.alpha(pal.bg, solid));
                canvas.drawRect(0f, 0f, w, barBottom + fade * (1f - solid), fill);
            }
        }

        // 不画任何分隔线：之前那条 0.75dp 的线在浅色主题下就是明显的黑线。
        // 两页标题按横滑比例整体平移 —— 这就是「顶栏跟着页面一起滑」而不是突变。
        // 例外是关于页：它没有大标题，参考版式里顶部也不跟着横滑，所以那一页的
        // 标题改成「原地淡入淡出」（见 drawPage 里的 vis）。
        drawPage(canvas, base, -frac * w, 1f - frac);
        if (frac > 0f) {
            drawPage(canvas, base + 1, (1f - frac) * w, frac);
        }
    }

    /** 画某一页的顶栏文案，整体平移 {@code dx}；{@code vis} 是该页在横滑中的可见度。 */
    private void drawPage(Canvas canvas, int index, float dx, float vis) {
        if (index < 0 || index >= pageTitles.length) {
            return;
        }
        String t = pageTitles[index];
        String s = pageSubs[index];
        float c = pageCollapse[index];
        float w = getWidth();
        float barH = Theme.dpf(getContext(), Theme.BAR_H);
        float pad = Theme.dpf(getContext(), Theme.TITLE_PAD);

        // 没有大标题的页面（关于页）顶部不横滑：留在原地，按可见度交叉淡入淡出。
        boolean plainPage = !pageLarge[index];
        canvas.save();
        canvas.translate(plainPage ? 0f : dx, 0f);

        float largeAlpha = 1f - c;
        if (pageLarge[index] && largeAlpha > 0.01f && t.length() > 0) {
            float top = statusInset + barH;
            canvas.save();
            canvas.translate(0, -c * headerBlockHeight() * 0.35f);
            largePaint.setAlpha(Math.round(255 * Theme.clamp01(largeAlpha)));
            canvas.drawText(t, pad, top + Theme.dpf(getContext(), 32), largePaint);
            subPaint.setAlpha(Math.round(255 * Theme.clamp01(largeAlpha)));
            canvas.drawText(s, pad, top + Theme.dpf(getContext(), 54), subPaint);
            canvas.restore();
        }

        float smallAlpha = Math.max(0f, (c - 0.35f) / 0.65f);
        if (plainPage) {
            smallAlpha *= Theme.clamp01(vis);
        }
        if (smallAlpha > 0.01f) {
            titlePaint.setAlpha(Math.round(255 * Theme.clamp01(smallAlpha)));
            float baseline = statusInset + barH / 2f
                    - (titlePaint.ascent() + titlePaint.descent()) / 2f;
            // 没有大标题的页面（关于页）走 SonyPods 的 SmallTopAppBar 版式：标题居中。
            // 有大标题的页面保持左对齐——那里折叠后标题要和展开态的大标题左边缘对齐。
            Paint.Align oldAlign = titlePaint.getTextAlign();
            if (!pageLarge[index]) {
                titlePaint.setTextAlign(Paint.Align.CENTER);
            }
            canvas.drawText(t, pageLarge[index] ? pad : w / 2f, baseline, titlePaint);
            titlePaint.setTextAlign(oldAlign);
        }

        canvas.restore();

        // 右上角动作只属于当前页；横滑时跟着一起平移并淡出，吸附回位才恢复点击热区。
        if (index == currentPage && actionIcon >= 0) {
            float visible = 1f - Math.min(1f, Math.abs(dx) / Math.max(1f, w) * 1.6f);
            if (visible > 0.04f) {
                float size = Theme.dpf(getContext(), 22);
                float right = w - Theme.dpf(getContext(), 20);
                float cy = statusInset + barH / 2f;
                float left = right - size;
                Icons.draw(canvas, actionIcon, left + dx, cy - size / 2f, size, pal.title, 0f);
                if (Math.abs(pagePos - currentPage) < 0.02f) {
                    actionRect.set(left - Theme.dpf(getContext(), 12), cy - Theme.dpf(getContext(), 20),
                            right + Theme.dpf(getContext(), 12), cy + Theme.dpf(getContext(), 20));
                } else {
                    actionRect.setEmpty();
                }
            } else {
                actionRect.setEmpty();
            }
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
