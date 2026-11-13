package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

/**
 * 三页平级的横向容器 —— 自写的轻量 "ViewPager"，不引 AndroidX。
 *
 * 行为：
 * <ul>
 *   <li>手指横向拖动时三页一起平移，松手按速度 / 位移吸附到最近一页；</li>
 *   <li>纵向手势一律不拦截，交给页面里的 ScrollView，避免抢滚动；</li>
 *   <li>横向判定要求 {@code |dx| > touchSlop && |dx| > |dy| * 1.4}，
 *       斜向滑动明显偏竖直时仍然算滚动；</li>
 *   <li>第一页右拖、最后一页左拖时按 miuix 的抛物线橡皮筋阻尼，跟手且可原路撤回；</li>
 *   <li>切页过程中回调连续位置，底栏指示器据此跟手。</li>
 * </ul>
 */
final class PagePager extends ViewGroup {

    interface OnPageChange {
        /** 停留在第 position 页（吸附完成）。 */
        void onPageSelected(int position);

        /**
         * 拖动/回弹中的连续位置，0..count-1。
         *
         * @param following true 表示这次位移来自用户手势（含松手后的吸附），底栏指示器应当
         *                  逐帧跟手；false 表示是代码触发的切页，底栏用它自己的弹簧走过去，
         *                  不要再被这里的粗粒度步长拽着走。
         */
        void onPageScrolled(float position, boolean following);
    }

    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private VelocityTracker tracker;

    private OnPageChange listener;

    private int pageCount;
    private int current;
    private float offset;          // 可见位置，以"页"为单位，越界时含橡皮筋形变
    /**
     * 未经阻尼压缩的手指位置（页单位，允许 &lt;0 或 &gt;count-1）。
     *
     * <p>miuix 的 {@code MiuixOverscrollEffect} 用 {@code rawTouchAccumulationX}
     * 累加原始触摸位移，再一次性经 {@code DefaultParabolaScrollEasing} 映射到
     * {@code offsetX}。旧实现把「已经压缩过的 offset」每帧再套一次阻尼公式，等于
     * 复合压缩：越拉越拉不动，往回拖也不沿原路返回 —— 这就是「不跟手」的根因。
     */
    private float rawOffset;
    /** 当前弹簧动画是否为边界橡皮筋回弹（软弹簧、只回边界、不做页吸附）。 */
    private boolean edgeRebound;
    private boolean dragging;
    private boolean settling;
    /**
     * 当前这轮位移是否来自用户手势。
     *
     * 只有手势拖动（含松手后的吸附）才让底栏指示器跟手；点底栏切页走的是
     * {@link #setCurrentPage}，那是 280ms 的整段滚动，每帧位移可达半格，让指示器跟它
     * 就会出现「一帧飞过去、再卡三帧、再跳一下」。
     */
    private boolean following;
    /**
     * 按下这一下是否把一段吸附动画掐断了。
     *
     * 掐断后若只是按住没横滑就松手，页面会永远停在两页中间 —— 必须在抬手时把吸附续上。
     */
    private boolean settleInterrupted;
    private float downX;
    private float downY;
    private float lastX;
    private int scrollAtDown;
    /**
     * 本次手势是否已是纵向滚动。
     *
     * 一旦认定是纵向滚动（或子页面已经在滚），整段手势都不许再转给横滑 ——
     * 中途拦截会给子 ScrollView 发 CANCEL，表现为「滚动到一半突然被限制住、顿一下」。
     */
    private boolean verticalGesture;

    // ---------------------------------------------------------------- 切页动画
    //
    // 与 SonyPods 对齐的两套手感（其 MainTabsPagerState.animateToPage + HorizontalPager 默认 fling）：
    //   点底栏/代码切页 → 300ms EaseInOut（100 × max(2, 距离) + 100，相邻页恒 300ms）；
    //   松手吸附       → 带初速的阻尼弹簧（略欠阻尼 ζ≈0.8，能感到弹性但不来回晃）；
    //   边界橡皮筋回弹 → 与 MiuixScrollView 同一组软弹簧（k=190、ζ=0.9）+ 甩速限幅。
    // 旧实现统一用 OverScroller 匀减速 260/280ms：没有弹性，且甩动速度不参与动画，
    // 快甩和慢拖走完的时间一样 —— 就是「切换速度不对」的来源。
    private boolean animating;
    private boolean tweenMode;        // true = 300ms EaseInOut；false = 阻尼弹簧
    private float animFrom;
    private float animTarget;
    private float animVelocity;       // 弹簧初速，单位「页/秒」
    private long animStartAt;         // tween 起点
    private long animLastFrameAt;     // 弹簧积分上一帧

    private static final int TWEEN_MS = 300;
    private static final float SPRING_K = 300f;
    private static final float SPRING_DAMPING =
            2f * (float) Math.sqrt(300f) * 0.8f;
    /**
     * 边界橡皮筋回弹用 {@link MiuixScrollView} 的同一组软弹簧。miuix 原本是
     * {@code springStiff=280f, springDamp=1f}（临界阻尼，ω≈16.7），项目里按用户
     * 「稍微软一点」的要求已降为 190/0.9；横滑与纵向必须保持一致。k=300 的切页
     * 弹簧用来吸附合适，但拿它收越界形变会「啪」地绷回去 —— 这是「太硬」的来源。
     */
    private static final float EDGE_SPRING_K = 190f;
    private static final float EDGE_SPRING_DAMPING =
            2f * (float) Math.sqrt(EDGE_SPRING_K) * 0.9f;
    /** 边界回弹的甩速上限（页/秒）：不把整段 fling 速度灌进弹簧，否则像撞墙。 */
    private static final float EDGE_MAX_VELOCITY = 0.9f;
    /** 页内吸附的甩速上限（页/秒），避免极快甩动把页面甩出边界再猛拉回来。 */
    private static final float SETTLE_MAX_VELOCITY = 2.2f;

    PagePager(Context c) {
        super(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        setClipChildren(true);
    }

    void setOnPageChange(OnPageChange l) {
        listener = l;
    }

    void setPageCount(int n) {
        pageCount = Math.max(1, n);
        offset = current;
        rawOffset = current;
        requestLayout();
    }

    int pageCount() {
        return pageCount;
    }

    int currentPage() {
        return current;
    }

    /** 手指正在横向拖动（区别于代码触发的吸附动画）。 */
    boolean isUserDragging() {
        return dragging;
    }

    /** 直接跳页（点底栏 / 代码调用）：300ms EaseInOut，与 SonyPods 的 animateToPage 一致。 */
    void setCurrentPage(int index, boolean animate) {
        int target = clampPage(index);
        if (target == current && !settling) {
            // 位置可能被拖动过，回正
            if (Math.abs(offset - target) < 0.01f) {
                return;
            }
        }
        // 代码触发的整段滚动，不归手势，底栏不要跟。
        following = false;
        current = target;
        if (!animate || getWidth() == 0) {
            offset = target;
            rawOffset = target;
            animating = false;
            settling = false;
            edgeRebound = false;
            applyScroll();
            notifyScrolled();
            return;
        }
        settling = true;
        tweenMode = true;
        edgeRebound = false;
        animFrom = offset;
        animTarget = target;
        animStartAt = SystemClock.uptimeMillis();
        animating = true;
        postInvalidateOnAnimation();
    }

    private int clampPage(int i) {
        return i < 0 ? 0 : (i >= pageCount ? pageCount - 1 : i);
    }

    // ---------------------------------------------------------------- 手势

    /**
     * 只做"要不要接管"的判断，不消费事件本身：
     * 横向占优才 beginDrag 并返回 true，纵向一律放行给子 View。
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastX = downX;
                settleInterrupted = settling;
                dragging = false;
                settling = false;
                verticalGesture = false;
                animating = false;   // 手指从当前 offset 接管，不跳位
                edgeRebound = false;
                rawOffset = rawFromVisual(offset);  // 形变中段被按下：从当前形变接着拉
                track(e);
                scrollAtDown = scrollYOfCurrent();
                return false;
            case MotionEvent.ACTION_MOVE: {
                track(e);
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                // 纵向一旦确立（或内容已经滚起来了），本次手势就归滚动，绝不拦截。
                if (Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                    verticalGesture = true;
                }
                if (!verticalGesture && scrollYOfCurrent() != scrollAtDown) {
                    verticalGesture = true;
                }
                if (verticalGesture) {
                    return false;
                }
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    // 页面正在纵向滚动时不要抢：等它停（拖得很斜时也归滚动）
                    if (Math.abs(dy) < touchSlop * 2 || Math.abs(dy) < Math.abs(dx) * 0.4f) {
                        dragging = true;
                        lastX = e.getX();
                        return true;
                    }
                }
                return false;
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                releaseTracker();
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                lastX = downX;
                edgeRebound = false;
                rawOffset = rawFromVisual(offset);
                track(e);
                return true;
            case MotionEvent.ACTION_MOVE: {
                track(e);
                if (!dragging) {
                    if (verticalGesture) {
                        return true;
                    }
                    float dx = e.getX() - downX;
                    float dy = e.getY() - downY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                        dragging = true;
                        lastX = e.getX();
                    } else {
                        return true;
                    }
                }
                float dx = e.getX() - lastX;
                lastX = e.getX();
                moveBy(dx);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                boolean wasDragging = dragging;
                dragging = false;
                int vel = 0;
                if (tracker != null) {
                    tracker.computeCurrentVelocity(1000, maxFlingVelocity);
                    vel = Math.round(tracker.getXVelocity());
                }
                releaseTracker();
                if (wasDragging) {
                    settle(vel, e.getActionMasked() == MotionEvent.ACTION_CANCEL);
                    settleInterrupted = false;
                    return true;
                }
                if (settleInterrupted) {
                    // 按住把吸附掐断了但没滑动：续上，别停在两页中间。
                    settleInterrupted = false;
                    if (Math.abs(offset - current) > 0.01f) {
                        setCurrentPage(current, true);
                    }
                }
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private void track(MotionEvent e) {
        if (tracker == null) {
            tracker = VelocityTracker.obtain();
        }
        tracker.addMovement(e);
    }

    private void releaseTracker() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    private void moveBy(float dxPx) {
        int w = getWidth();
        if (w <= 0) {
            return;
        }
        following = true;
        // 手指位移 1:1 记在 rawOffset 上，只有「超出页面的那一段」才过阻尼曲线。
        // 这样往回拖严格沿原路收回，且退出越界时视觉位置连续、零跳变。
        rawOffset -= dxPx / w;
        offset = visualFromRaw(w);
        applyScroll();
        notifyScrolled();
    }

    /**
     * miuix {@code DefaultParabolaScrollEasing} 的横向版本，直接复用
     * {@link MiuixScrollView#defaultParabolaScrollEasing(float, float)}（range = 页宽）：
     * 起步近似 1:1 跟手，越拉越沉，自然饱和在 20% 屏宽。
     */
    private float visualFromRaw(int w) {
        float last = pageCount - 1;
        float clamped = Math.max(0f, Math.min(last, rawOffset));
        float over = rawOffset - clamped;
        if (over == 0f) {
            return clamped;
        }
        return clamped + MiuixScrollView.defaultParabolaScrollEasing(over * w, w) / w;
    }

    /**
     * 上式的反解：回弹动画被手指中途接管时，从当前可见形变倒推出「手指位置」，
     * 避免按下瞬间形变跳回 0 或突然弹开（同 MiuixScrollView#inverseEasing）。
     */
    private float rawFromVisual(float visual) {
        float last = pageCount - 1;
        if (visual >= 0f && visual <= last) {
            return visual;
        }
        int w = Math.max(1, getWidth());
        float base = visual < 0f ? 0f : last;
        float easedPx = (visual - base) * w;
        float want = Math.abs(easedPx);
        float lo = 0f;
        float hi = w * 3f;
        for (int i = 0; i < 24; i++) {
            float mid = (lo + hi) * 0.5f;
            if (MiuixScrollView.defaultParabolaScrollEasing(mid, w) < want) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return base + Math.copySign(lo / w, easedPx);
    }

    private void settle(int velocityPx, boolean cancel) {
        float last = pageCount - 1;
        // 越界松手：只走橡皮筋回弹，不参与页吸附，更不能被 fling 甩到别的页。
        if (rawOffset < 0f || rawOffset > last) {
            startEdgeRebound(velocityPx, rawOffset < 0f ? 0f : last, cancel);
            return;
        }
        int target = Math.round(offset);
        if (!cancel && Math.abs(velocityPx) > minFlingVelocity) {
            target = velocityPx < 0 ? (int) Math.ceil(offset) : (int) Math.floor(offset);
        }
        target = clampPage(target);
        if (cancel) {
            target = current;
        }
        // 带初速的阻尼弹簧：甩得快就冲过去（甚至过冲一点再回），慢拖就缓缓收拢 —— 这就是
        // SonyPods HorizontalPager 松手后的手感。速度换算成「页/秒」进弹簧。
        float v = cancel ? 0f : -velocityPx / Math.max(1, getWidth());
        // 甩到首/末页时把「继续朝界外」的分量砍掉：Compose Pager 的 fling 在边界会被
        // clamp，弹簧若带着全速冲出去再硬拉回，就是「撞墙式」的硬回弹。
        if (target == 0 && v < 0f) {
            v *= 0.25f;
        }
        if (target == (int) last && v > 0f) {
            v *= 0.25f;
        }
        animVelocity = Math.max(-SETTLE_MAX_VELOCITY, Math.min(SETTLE_MAX_VELOCITY, v));
        tweenMode = false;
        edgeRebound = false;
        animFrom = offset;
        animTarget = target;
        rawOffset = offset;
        animLastFrameAt = SystemClock.uptimeMillis();
        settling = true;
        animating = true;
        postInvalidateOnAnimation();
    }

    /**
     * 边界橡皮筋回弹：软弹簧（k=190、ζ=0.9，与纵向 MiuixScrollView 一致），
     * 目标是最近的可视边界，初速限幅后只留一点点惯性。旧实现把整段 fling 速度
     * 灌进 k=300 的切页弹簧，越界时会先大幅外冲再猛绷回 —— 又硬又不跟手。
     */
    private void startEdgeRebound(int velocityPx, float target, boolean cancel) {
        float w = Math.max(1, getWidth());
        float v = cancel ? 0f : -velocityPx / w;
        boolean outward = (target == 0f && v < 0f) || (target > 0f && v > 0f);
        v *= outward ? 0.45f : 0.65f;
        animVelocity = Math.max(-EDGE_MAX_VELOCITY, Math.min(EDGE_MAX_VELOCITY, v));
        tweenMode = false;
        edgeRebound = true;
        animFrom = offset;
        animTarget = target;
        animLastFrameAt = SystemClock.uptimeMillis();
        settling = true;
        animating = true;
        postInvalidateOnAnimation();
    }

    private void applyScroll() {
        int w = getWidth();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.setTranslationX(-offset * w + i * w);
        }
        invalidate();
    }

    private void notifyScrolled() {
        if (listener != null) {
            listener.onPageScrolled(offset, following);
        }
    }

    @Override
    public void computeScroll() {
        if (!animating) {
            return;
        }
        if (tweenMode) {
            // 300ms EaseInOut —— 与 SonyPods animateToPage 的 tween(EaseInOut, 300) 逐点一致。
            long t = SystemClock.uptimeMillis() - animStartAt;
            if (t >= TWEEN_MS) {
                offset = animTarget;
                rawOffset = animTarget;
                finishSettle();
                return;
            }
            float u = t / (float) TWEEN_MS;
            float e = u < 0.5f ? 4f * u * u * u : 1f - (float) Math.pow(-2f * u + 2f, 3) / 2f;
            offset = animFrom + (animTarget - animFrom) * e;
            rawOffset = offset;
            applyScroll();
            notifyScrolled();
            postInvalidateOnAnimation();
            return;
        }

        // 阻尼弹簧：x'' = -k(x - target) - c·x'。半隐式欧拉，步长取真实帧间隔。
        // 边界回弹换成软弹簧；页内吸附仍是 k=300 / ζ≈0.8 的切页手感。
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.05f, Math.max(1f / 240f, (now - animLastFrameAt) / 1000f));
        animLastFrameAt = now;
        float k = edgeRebound ? EDGE_SPRING_K : SPRING_K;
        float c = edgeRebound ? EDGE_SPRING_DAMPING : SPRING_DAMPING;
        float x = offset - animTarget;
        float a = -k * x - c * animVelocity;
        animVelocity += a * dt;
        offset += animVelocity * dt;
        rawOffset = offset;
        if (Math.abs(offset - animTarget) < 0.002f && Math.abs(animVelocity) < 0.02f) {
            offset = animTarget;
            rawOffset = animTarget;
            finishSettle();
            return;
        }
        applyScroll();
        notifyScrolled();
        postInvalidateOnAnimation();
    }

    private void finishSettle() {
        animating = false;
        settling = false;
        edgeRebound = false;
        int landed = Math.round(offset);
        offset = clampPage(landed);
        rawOffset = offset;
        applyScroll();
        notifyScrolled();
        // 吸附落定之后这轮位移才算结束，底栏从下一帧起交回自己的弹簧。
        following = false;
        if (offset != current) {
            current = (int) offset;
            // 切页不再震动：视觉上的吸附动画已经足够表达页面落定。
            if (listener != null) {
                listener.onPageSelected(current);
            }
        } else if (listener != null) {
            listener.onPageSelected(current);
        }
    }

    private int scrollYOfCurrent() {
        View v = getChildAt(current);
        if (v instanceof android.widget.ScrollView) {
            return ((android.widget.ScrollView) v).getScrollY();
        }
        return 0;
    }

    // ---------------------------------------------------------------- 布局

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.layout(0, 0, w, b - t);
            child.setTranslationX(-offset * w + i * w);
        }
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        if (getChildCount() > pageCount) {
            pageCount = getChildCount();
        }
    }
}
