package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * miuix / SonyPods 的 {@code Modifier.overScrollVertical()}（Rubber Band 越界回弹）的
 * 原生 View 移植。
 *
 * <pre>
 * overScrollVertical(
 *     scrollEasing = { distance, range -> DefaultParabolaScrollEasing(distance, range) },
 *     springStiff = 280f, springDamp = 1f
 * )
 * overscrollEffect = null         // 关掉系统 EdgeEffect，避免和橡皮筋打架
 * </pre>
 *
 * <p><b>设计要点（这一版重写的三个原因）</b>
 *
 * <p>1. <b>滚动源永远由系统处理，橡皮筋只做叠加位移</b>。旧实现进入 band 后就不再把
 * MOVE 交给 {@code super}，ScrollView 的 {@code mLastMotionY} / {@code mIsBeingDragged} /
 * {@code VelocityTracker} 全部停在旧的帧上，退出 band 的第一帧位移量按「当前 y − 过期 y」
 * 算，内容会猛跳一下；再次按下时也要重新过一遍 touchSlop，手感就是「卡一下」。
 * 现在每个 MOVE 都原样转发给系统（等价于 NestedScroll 的
 * {@code onPreScroll} 先让滚动源消费），再用 {@code getScrollY()} 的前后差值求出
 * 「没被吃下的余量」，只把这份余量转成 {@code translationY}。状态机始终同步，切换零跳变。
 *
 * <p>2. <b>越界距离必须累加</b>。旧实现每帧 {@code rawDistance = -available}（覆盖），
 * 每次只反映单帧增量，手指匀速拖动时位移反而一顿一顿的。现在累加，并且反向拖动时先把
 * 形变收回 0，不让它穿过 0 变成另一侧的拉伸。
 *
 * <p>3. <b>惯性（fling）也要能撞出橡皮筋</b>。快速滑动后在手指抬起之后才到达边界，
 * 此时滚动完全由 {@link #computeScroll()} 里的 {@code Scroller} 驱动，根本不经过
 * {@code onTouchEvent} —— 这就是「滚动到底部要再滚一次才出现橡皮筋」的真正原因。
 * 现在在 {@code computeScroll()} 里监测「scroller 还在跑但 scrollY 已不再变化」，
 * 把上一帧的滚动增量当作冲量转成越界位移，再交给弹簧带着初速度弹回，惯性是连续的。
 *
 * <p>不含任何触感反馈（页面切换 / 滚动边界的震动已按要求移除）。
 */
class MiuixScrollView extends ScrollView {

    interface OnOverScroll {
        void onOverScroll(float offset);
    }

    /**
     * SonyPods / miuix 的 {@code overScrollVertical(springStiff = 280f, springDamp = 1f)}
     * 是临界阻尼，ω = √280 ≈ 16.7 rad/s，从 300px 收回只要 0.24s —— 手感偏硬。
     * 这里按用户要求「稍微软一点」：刚度降到 190（ω ≈ 13.8，收回约 0.3s），
     * 阻尼比 0.9 留一点点回弹，不再是死板的临界阻尼。
     */
    private static final float SPRING_STIFF = 190f;
    private static final float SPRING_DAMP_RATIO = 0.9f;
    /** 橡皮筋饱和上限（占视高比例），miuix 的 parabola 会自然收敛到这里。 */
    private static final float MAX_OVERSCROLL_RATIO = 0.20f;
    /** 惯性撞边界：把单帧滚动增量换成 spring 的初速度（px/帧 → px/秒，再按撞墙损耗衰减）。 */
    private static final float FLING_VELOCITY_SCALE = 12f;
    /**
     * 回弹初速度上限。spring 的 ω = √280 ≈ 16.7 rad/s，临界阻尼下过冲量约
     * {@code v / ω / e}，2600 px/s 对应约 57px 的继续外冲 —— 有惯性但不夸张。
     * 再大就会从 10% 屏高的位移一路冲到 25% 以上，看着像失控。
     */
    private static final float MAX_VELOCITY = 2600f;

    private final Ui.Spring spring = new Ui.Spring(0f);

    private OnOverScroll listener;

    /** 上一帧的触摸 y，用于算逐帧位移。 */
    private float lastY;
    /** 手指越界的累计距离（正 = 顶部下拉，负 = 底部上推）。 */
    private float rawDistance;
    /** 映射之后真正施加在内容上的位移。 */
    private float offset;
    /** 手指速度（px/秒，正 = 手指上移），抬手时喂给弹簧做惯性。 */
    private float fingerVelocity;
    private long lastMoveAt;

    /** 上一帧 fling 的 scrollY 增量，用来检测「惯性撞边界」。 */
    private int lastFlingDelta;
    private long lastFrameAt;

    /**
     * 是否处于 fling（惯性）期间。{@code computeScroll()} 是每帧都被框架调用的，
     * 非 fling 状态下 {@code delta} 同样为 0，如果不显式跟踪生命周期，就会把上一次
     * 滚动留下的旧增量误判成「撞边界」，在正常滚动到边界时提前弹一下。
     */
    private boolean flinging;
    private int flingIdleFrames;
    /** 本次 fling 的初始速度（px/s），用于「一帧都没滚过」时折算冲量。 */
    private int flingVelocityY;

    /** 惯性冲出的当前速度（px/帧），逐帧衰减到 0 后交给弹簧。 */
    private float impulseV;
    /** 两个动画循环的帧计数，只作死循环兜底。 */
    private int reboundFrames;
    private int impulseFrames;

    private final Runnable rebound = new Runnable() {
        @Override
        public void run() {
            // 硬上限兜底：弹簧在极端数值下若不收敛，这里也绝不会变成常驻循环。
            if (++reboundFrames > 180) {
                finishRebound();
                return;
            }
            long now = SystemClock.uptimeMillis();
            float dt = lastFrameAt == 0 ? 0.016f : Math.min(0.048f, (now - lastFrameAt) / 1000f);
            lastFrameAt = now;
            spring.step(dt);
            apply(spring.value);
            // 这里不用 Spring#settled()：它的阈值是 0.0008px，对像素位移过严，
            // 会在已经看不出来的位置上多跑十几帧。
            boolean done = Math.abs(spring.value) < 0.4f && Math.abs(spring.velocity) < 26f;
            if (done) {
                finishRebound();
            } else {
                postOnAnimation(this);
            }
        }
    };

    private void finishRebound() {
        spring.value = 0f;
        spring.velocity = 0f;
        lastFrameAt = 0;
        reboundFrames = 0;
        apply(0f);
    }

    MiuixScrollView(Context c) {
        super(c);
        spring.set(SPRING_STIFF, SPRING_DAMP_RATIO);
        setOverScrollMode(View.OVER_SCROLL_NEVER);   // 等价于 overscrollEffect = null
        setVerticalScrollBarEnabled(false);
        setClipToPadding(false);
        setClipChildren(false);
    }

    void setOnOverScroll(OnOverScroll l) {
        listener = l;
    }

    float overScrollOffset() {
        return offset;
    }

    /** miuix {@code DefaultParabolaScrollEasing}：起步跟手、末端饱和在 20% 视高。 */
    static float defaultParabolaScrollEasing(float distance, float range) {
        float d = Math.abs(distance);
        float r = Math.max(1f, range);
        float t = d / (d + r * 0.35f);
        float eased = r * MAX_OVERSCROLL_RATIO * t * (2f - t);
        return distance < 0f ? -eased : eased;
    }

    /** 上式的反解：从已有位移倒推「手指越界距离」，让新按下的手势能从当前形变接着拉。 */
    private float inverseEasing(float target) {
        float want = Math.abs(target);
        float lo = 0f;
        float hi = Math.max(1f, getHeight()) * 3f;
        for (int i = 0; i < 24; i++) {
            float mid = (lo + hi) * 0.5f;
            if (defaultParabolaScrollEasing(mid, getHeight()) < want) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return Math.copySign(lo, target);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                removeCallbacks(rebound);
                removeCallbacks(impulseTick);   // 冲出过程中按下：立刻接管
                impulseV = 0f;
                flinging = false;      // 手指按下会中止系统 fling
                flingIdleFrames = 0;
                lastY = e.getY();
                lastMoveAt = SystemClock.uptimeMillis();
                fingerVelocity = 0f;
                lastFlingDelta = 0;
                // 上一次回弹还没走完就重新按下：接管当前位移，手指可以从这里继续，
                // 不把形变瞬移到 0（那样会闪一下）。
                if (Math.abs(offset) > 0.5f) {
                    rawDistance = inverseEasing(offset);
                    spring.value = offset;
                    spring.velocity = 0f;
                } else {
                    rawDistance = 0f;
                    apply(0f);
                }
                return super.onTouchEvent(e);
            }

            case MotionEvent.ACTION_MOVE: {
                float dy = lastY - e.getY();   // >0 手指上移（内容应下滚）
                lastY = e.getY();

                long now = SystemClock.uptimeMillis();
                float dt = Math.max(1f / 240f, (now - lastMoveAt) / 1000f);
                lastMoveAt = now;
                float instant = dy / dt;
                // 轻微低通，避免最后一帧的抖动被当成抬手速度
                fingerVelocity = fingerVelocity * 0.35f + instant * 0.65f;

                // 滚动源永远先消费：状态机（mLastMotionY / mIsBeingDragged）保持同步。
                int before = getScrollY();
                boolean handled = super.onTouchEvent(e);
                int consumed = getScrollY() - before;
                float available = dy - consumed;

                boolean atTop = !canScrollVertically(-1);
                boolean atBottom = !canScrollVertically(1);

                // 「继续往外推」：顶部还在往下拉 / 底部还在往上推。
                boolean pullingOut = (atTop && available < -0.01f)
                        || (atBottom && available > 0.01f);
                boolean hasBand = Math.abs(rawDistance) > 0.01f;

                // 形变回收优先于内容滚动：手指往回收（dy 与 rawDistance 同号）时，
                // 这一帧的位移必须全部用来收形变。但系统在上面已经把它滚成内容位移了
                // （进入内容区间后 consumed == dy，旧公式只剩像素取整残差，形变收敛不动），
                // 于是形变挂着跟内容滚进中段，抬手时 wasOver 判成「越界松手」，
                // 在中段炸出一次回弹 —— 这就是「没到顶/底却出现阻尼回弹」的根因。
                // 这里把系统滚掉的量退回（stolen），dy 全额记到形变上：形变先归零、
                // 多出来的位移才滚内容，与 iOS 橡皮筋「先回弹、后跟手」一致。
                boolean collapsing = hasBand && Math.abs(dy) > 0.001f
                        && Math.signum(dy) == Math.signum(rawDistance);
                if (collapsing) {
                    int stolen = getScrollY() - before;
                    if (stolen != 0) {
                        scrollBy(0, -stolen);
                    }
                    float used = Math.copySign(
                            Math.min(Math.abs(dy), Math.abs(rawDistance)), dy);
                    float leftover = dy - used;
                    float next = rawDistance - used;
                    if (Math.signum(next) != Math.signum(rawDistance)) {
                        next = 0f;
                    }
                    rawDistance = next;
                    apply(defaultParabolaScrollEasing(rawDistance, getHeight()));
                    if (next == 0f) {
                        startRebound(0f);
                        if (Math.abs(leftover) > 0.5f) {
                            // 形变已收完，这一帧没用掉的位移还给内容滚动。
                            scrollBy(0, Math.round(leftover));
                        }
                    }
                    return true;
                }

                // 统一成一个式子：Δ形变 = -available。
                // 轴向约定 —— 底部外推 available > 0 → 形变变负（内容上移）；
                // 顶部外推 available < 0 → 形变变正（内容下移）。
                // 反向拖动时 available 与 rawDistance 同号，绝对值自然减小，形变跟着手指收回，
                // 不会像旧实现那样「两个分支都不命中所以卡住不动」。
                if (pullingOut || (hasBand && Math.abs(available) > 0.01f)) {
                    float next = rawDistance - available;
                    if (hasBand && Math.signum(next) != Math.signum(rawDistance)) {
                        next = 0f;   // 收回穿过 0：说明手指已回到可滚动区间，剩余量交给系统
                    }
                    rawDistance = next;
                    apply(defaultParabolaScrollEasing(rawDistance, getHeight()));
                    if (next == 0f) {
                        startRebound(0f);
                    }
                    // 越界手势不落到子 View 的点击上
                    return true;
                }
                return handled;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                // 只有贴在边界上的形变才算「越界松手」；中段残余的形变（若有）
                // 交给已在跑的弹簧，不要在中段再起一次回弹。
                boolean atEdge = !canScrollVertically(-1) || !canScrollVertically(1);
                boolean wasOver = atEdge && (Math.abs(offset) > 0.5f || rawDistance != 0f);
                if (wasOver) {
                    // 越界状态下抬手：给 super 一个 CANCEL 收尾，避免它拿抬手的 UP
                    // 去启动一段必然被 clamp 掉的 fling（那会和弹簧回弹抢动画）。
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        MotionEvent cancel = MotionEvent.obtain(e);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.onTouchEvent(cancel);
                        cancel.recycle();
                    } else {
                        super.onTouchEvent(e);
                    }
                    rawDistance = 0f;
                    // 手指速度方向 = 形变继续扩张的方向，回弹因此先带惯性冲一下再收。
                    startRebound(-fingerVelocity);
                    return true;   // 吞掉，避免误触发子控件点击
                }
                fingerVelocity = 0f;
                return super.onTouchEvent(e);
            }

            default:
                return super.onTouchEvent(e);
        }
    }

    /**
     * 惯性撞边界：scroller 仍在跑但 {@code scrollY} 已经不再变化，说明本帧应该滚动的
     * 距离被边界 clamp 掉了。把这份余量当冲量转成越界位移，再带初速度弹回 ——
     * 这样「快速滑到底」和「按住拖到底」的手感是一致的，不会出现「再滑一次才弹」。
     */
    @Override
    public void computeScroll() {
        int before = getScrollY();
        super.computeScroll();
        int delta = getScrollY() - before;

        if (!flinging) {
            return;
        }
        if (delta != 0) {
            lastFlingDelta = delta;
            flingIdleFrames = 0;
            return;
        }

        // 这一帧 scrollY 没动：撞边界，或者 scroller 根本没有可滚的余量。
        flingIdleFrames++;
        // 关键补丁：一帧都没滚出去时 lastFlingDelta 仍是 0，旧实现会直接跳过判定，
        // 于是「惯性正好停在临界位置」永远弹不出来，必须再滚一次才走拖动路径 ——
        // 这就是用户报的「卡在临界位置」。这里用初始速度折算单帧增量兜底。
        int impulse = lastFlingDelta != 0 ? lastFlingDelta : Math.round(flingVelocityY / 60f);
        if (impulse == 0) {
            flinging = false;
            flingIdleFrames = 0;
            return;
        }
        int dir = impulse > 0 ? 1 : -1;
        boolean blocked = dir > 0 ? !canScrollVertically(1) : !canScrollVertically(-1);
        if (blocked) {
            flinging = false;
            flingIdleFrames = 0;
            lastFlingDelta = 0;
            applyImpulse(impulse);
            return;
        }
        if (flingIdleFrames > 2) {
            // 没在边界却连续几帧没有位移：scroller 已自然结束，收尾即可。
            flinging = false;
            flingIdleFrames = 0;
            lastFlingDelta = 0;
        }
    }

    /** 记录一次真正的惯性启动；ScrollView 在抬手时会自己调到这里。 */
    @Override
    public void fling(int velocityY) {
        flingVelocityY = velocityY;
        flinging = true;
        flingIdleFrames = 0;
        lastFlingDelta = 0;

        // 抬手瞬间就已经贴在边界上：scroller 没有任何可滚余量，某些情况下它甚至会
        // 立刻结束，连一帧 computeScroll 的机会都没有。这里直接用速度生成冲量，
        // 保证「甩到底」和「拖到底」一样立刻出橡皮筋。
        boolean blockedNow = velocityY > 0
                ? !canScrollVertically(1) : !canScrollVertically(-1);
        if (blockedNow && Math.abs(velocityY) >= 120) {
            flinging = false;
            applyImpulse(Math.round(velocityY / 60f));
            return;
        }
        super.fling(velocityY);
    }

    private void applyImpulse(int impulse) {
        if (Math.abs(offset) > 0.5f) {
            return;   // 已经在形变中，别叠第二次
        }
        // 不在这里一次性把位移顶到饱和 —— 那等于内容瞬移，看着是「猛地一顿」。
        // 改成按惯性逐帧衰减累积：和真实 overscroll 一样，冲出过程本身就是连续的。
        impulseV = impulse;
        impulseFrames = 0;
        rawDistance = 0f;
        removeCallbacks(impulseTick);
        postOnAnimation(impulseTick);
    }

    /** 惯性冲出的分帧推进：每帧衰减，直到动能耗尽再交给弹簧收回。 */
    private final Runnable impulseTick = new Runnable() {
        @Override
        public void run() {
            if (++impulseFrames > 150) {
                rawDistance = 0f;
                impulseV = 0f;
                startRebound(0f);
                return;
            }
            impulseV *= 0.72f;
            rawDistance += -impulseV * 0.55f;
            apply(defaultParabolaScrollEasing(rawDistance, getHeight()));
            if (Math.abs(impulseV) > 2f) {
                postOnAnimation(this);
                return;
            }
            rawDistance = 0f;
            startRebound(impulseV * FLING_VELOCITY_SCALE);
        }
    };

    private void startRebound(float initialVelocity) {
        rawDistance = 0f;
        reboundFrames = 0;
        removeCallbacks(rebound);
        removeCallbacks(impulseTick);
        if (Math.abs(offset) < 0.4f) {
            // 已经没有形变了：别用残余的手速把弹簧从 0 再弹出去一次。
            spring.value = 0f;
            spring.velocity = 0f;
            apply(0f);
            return;
        }
        spring.value = offset;
        spring.target = 0f;
        spring.velocity = Math.max(-MAX_VELOCITY, Math.min(MAX_VELOCITY, initialVelocity));
        lastFrameAt = 0;
        if (Math.abs(offset) < 0.4f && Math.abs(spring.velocity) < 26f) {
            spring.value = 0f;
            spring.velocity = 0f;
            apply(0f);
            return;
        }
        postOnAnimation(rebound);
    }

    private void apply(float v) {
        if (Math.abs(v) < 0.01f) {
            v = 0f;
        }
        offset = v;
        View child = getChildAt(0);
        if (child != null) {
            child.setTranslationY(v);
        }
        if (listener != null) {
            listener.onOverScroll(v);
        }
        invalidate();
    }


}
