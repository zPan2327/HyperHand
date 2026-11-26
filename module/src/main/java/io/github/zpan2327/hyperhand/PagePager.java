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
 *   <li>第一页右拖、最后一页左拖时**硬止**（不做 miuix 的抛物线橡皮筋）——用户要求
 *       关闭横向回弹，边界处页面完全不动；</li>
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
     * 按下这一下是否发生在切页/吸附动画进行中。
     *
     * 旧实现在 ACTION_DOWN 直接把 {@code animating/settling} 置 false 掐断动画，
     * 若这次按下随后被判定为纵向滚动，事件被 ScrollView 消费，本类收不到 ACTION_UP，
     * 原本挂在 UP 分支的「续上吸附」永远不会执行 —— 页面就永远卡在两页之间。
     * 现在 DOWN 不掐断动画：只有确认是横向拖动时才接管，纵向滚动时让 tween 自己跑完。
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
    // 松手 / 点底栏 / 代码切页统一走**同一条临界阻尼弹簧**：
    //   甩动松手 → 手指速度作为弹簧初速，目标取「朝甩动方向的那个邻页」；
    //   慢拖松手 → 初速很小，就近吸附；
    //   点底栏   → 初速 0（对应 animateScrollToPage）；
    //   一次手势只翻一页 → Compose `PagerDefaults.flingBehavior` 的默认
    //                    `pagerSnapDistance = PagerSnapDistance.atMost(1)`，
    //                    LSPosed 用的就是这个默认值：甩多快都只前进/后退一页。
    //
    // 【2026-11-26 回退记录】曾按 Compose flingBehavior 的形状试过
    // 「decay 惯性滑行 + spring 吸附」两段（先 OverScroller 自由滑行、越过目标页再切弹簧），
    // 真机上明显更难用：decay 不设边界会一路滑过目标页，越过那一帧再把位置直接赋成页位，
    // 表现为「滑一截 → 啪地跳到位」；若把 min/max 设成目标页，OverScroller 又会在一帧内
    // 直接跳到边界（更硬）。两条路都不如单段弹簧，已回退。
    // 想真正复刻 LSPosed 的手感只能上 Compose HorizontalPager，而三页是 Android View
    // （MiuixScrollView 在 DOWN 就消费触摸），塞进 AndroidView 会让 pager 收不到横滑手势。
    private boolean animating;
    private float animTarget;
    private float animVelocity;       // 弹簧初速，单位「页/秒」
    private long animLastFrameAt;     // 弹簧积分上一帧

    private static final float SPRING_K = 400f;          // Compose StiffnessMediumLow
    /** ζ = 1.0：临界阻尼，不过冲（Compose spring 的默认 dampingRatio）。 */
    private static final float SPRING_DAMPING = 2f * (float) Math.sqrt(SPRING_K);
    /** 松手吸附的初速上限（页/秒）：极快甩动时不至于一帧就冲过半页。 */
    private static final float SETTLE_MAX_VELOCITY = 2.4f;

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
            applyScroll();
            notifyScrolled();
            return;
        }
        settling = true;
        // 与 Compose animateScrollToPage 一致：初速 0 的临界阻尼弹簧。
        // 距离越远则整体速度越快，落定时间仍约 250ms —— 不是固定时长的匀速 tween。
        animTarget = target;
        animVelocity = 0f;
        animLastFrameAt = SystemClock.uptimeMillis();
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
                // 只记录「动画进行中」，此处绝不掐断 animating/settling：
                // 若这次按下随后被判为纵向滚动，事件被 ScrollView 消费，本类收不到 UP，
                // 挂在 UP 上的续上逻辑永远不执行 —— offset 就永久停在两页之间。
                // 让 tween 自己跑完；只有确认横向拖动时才在 takeOverAnimation() 里接管。
                settleInterrupted = animating;
                dragging = false;
                verticalGesture = false;
                if (!animating) {
                    // 形变中段被按下：从当前形变接着拉（动画在跑时 rawOffset 已被逐帧同步，
                    // 这里再覆盖会破坏 tween 的中间态）。
                    rawOffset = rawFromVisual(offset);
                }
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
                        // 确认要横向拖动了，此刻才掐断正在跑的切页动画并从当前 offset 接管。
                        takeOverAnimation();
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
                // 与 onInterceptTouchEvent 保持一致：动画进行中不碰 rawOffset/edgeRebound，
                // 否则正在跑的切页 tween 或边界回弹会被这一按改掉参数、卡在中间。
                if (!animating) {
                    rawOffset = rawFromVisual(offset);
                }
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
                        takeOverAnimation();
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
                    // 这一按没转成横向拖动：tween 全程没被掐断，早就自己跑完了，
                    // 这里只清标志，绝不能再 setCurrentPage —— 那会把已落定的动画
                    // 从中途重启，表现为点一下页面突然又飞回去。
                    settleInterrupted = false;
                }
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    /**
     * 确认是横向拖动时，把正在跑的切页/吸附动画从当前可见位置交给手指。
     *
     * <p>与旧实现在 ACTION_DOWN 就掐断不同：这里只在横向手势确凿时才置位，
     * 纵向滚动的那一按不会中断 tween，也就不会出现「卡在两页之间」。
     */
    private void takeOverAnimation() {
        if (!settleInterrupted) {
            return;
        }
        settleInterrupted = false;
        animating = false;
        settling = false;
        // 手指从当前 offset 接管，不跳位；形变段用反解回推 rawOffset。
        rawOffset = rawFromVisual(offset);
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
        // 【不再越界回弹】首/末页继续往外拖时，位移直接夹在 [0, count-1]：
        // 页面纹丝不动，不会出现「拉出一段再弹回去」的橡皮筋。
        // 这是用户明确要求的效果——横滑只做页与页之间的切换，边界硬止。
        float last = pageCount - 1;
        rawOffset = Math.max(0f, Math.min(last, rawOffset - dxPx / w));
        offset = rawOffset;
        applyScroll();
        notifyScrolled();
    }

    /**
     * miuix {@code DefaultParabolaScrollEasing} 的横向版本，直接复用
     * {@link MiuixScrollView#defaultParabolaScrollEasing(float, float)}（range = 页宽）：
     * 起步近似 1:1 跟手，越拉越沉，自然饱和在 20% 屏宽。
     */
    private float visualFromRaw(int w) {
        // 边界不再有阻尼形变：可见位置恒等于手指位置（越界已被夹住）。
        return Math.max(0f, Math.min(pageCount - 1, rawOffset));
    }

    /**
     * 上式的反解：回弹动画被手指中途接管时，从当前可见形变倒推出「手指位置」，
     * 避免按下瞬间形变跳回 0 或突然弹开（同 MiuixScrollView#inverseEasing）。
     */
    private float rawFromVisual(float visual) {
        // 同 visualFromRaw：没有形变了，反解就是夹住原值。
        return Math.max(0f, Math.min(pageCount - 1, visual));
    }

    private void settle(int velocityPx, boolean cancel) {
        float last = pageCount - 1;
        // 手指位置已被夹在 [0, last]，不存在越界松手，只做页吸附。
        int target = Math.round(offset);
        if (!cancel && Math.abs(velocityPx) > minFlingVelocity) {
            // 一次手势只翻一页：Compose `PagerDefaults.flingBehavior` 的默认
            // `pagerSnapDistance = PagerSnapDistance.atMost(1)`，LSPosed 也是这个默认值。
            // 甩得再快也只是「当前页 → 相邻页」，不存在一甩跨两页。
            // 目标取「朝甩动方向的那个邻页」的整页位：offset 恰为整数时也能前进一页。
            boolean forward = velocityPx < 0;
            target = forward ? (int) Math.floor(offset) + 1 : (int) Math.ceil(offset) - 1;
        }
        target = clampPage(target);
        if (cancel) {
            target = current;
        }
        // 带初速的**临界阻尼**弹簧：甩得快就走得快、慢拖就缓缓收拢，到位既不过冲也不回弹。
        // 速度换算成「页/秒」灌进弹簧，限幅避免极快甩动一帧冲过半页。
        float v = cancel ? 0f : -velocityPx / Math.max(1, getWidth());
        // 甩到首/末页时把「继续朝界外」的分量砍掉：目标已经在边界上，
        // 弹簧若带着全速冲出去再拉回，就是「撞墙式」的硬回弹。
        if (target == 0 && v < 0f) {
            v *= 0.25f;
        }
        if (target == (int) last && v > 0f) {
            v *= 0.25f;
        }
        animVelocity = Math.max(-SETTLE_MAX_VELOCITY, Math.min(SETTLE_MAX_VELOCITY, v));
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

        // 阻尼弹簧：x'' = -k(x - target) - c·x'。半隐式欧拉，步长取真实帧间隔。
        // k=400 时半隐式欧拉要求 ω·dt < 2（ω=20 → dt < 0.1），夹到 0.032 留足余量：
        // 掉帧到 30fps 也不会数值发散（发散的表现是页面自己抖起来）。
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.032f, Math.max(1f / 240f, (now - animLastFrameAt) / 1000f));
        animLastFrameAt = now;
        float k = SPRING_K;
        float c = SPRING_DAMPING;
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
        // 用当前「可见」的页，而不是 current：tween 期间 current 已是目标页、
        // 但屏幕上还是旧页，拿 current 判「内容在滚」会读错页。
        int visible = Math.max(0, Math.min(pageCount - 1, Math.round(offset)));
        // 统一走 MainActivity.scrollOf，兼容关于页的 AboutPage.Root（不是裸 ScrollView）。
        android.widget.ScrollView sv = MainActivity.scrollOf(getChildAt(visible));
        return sv != null ? sv.getScrollY() : 0;
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
