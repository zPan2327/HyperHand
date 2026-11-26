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
 *     springStiff = 190f, springDamp = 0.9f     // miuix 原值 280 / 1.0，按手感调软
 * )
 * overscrollEffect = null                        // 关掉系统 EdgeEffect，避免和橡皮筋打架
 * </pre>
 *
 * <h3>只有一套逻辑（2026-11 重写）</h3>
 *
 * <p>旧实现里有四条路径同时改同一个 {@code offset}：手指拖动、形变回收、惯性冲量
 * （{@code impulseTick} 自己一套逐帧衰减）、弹簧回弹；而且拖动到「形变归零」时会
 * 就地 {@code startRebound()}，于是弹簧和手指在同一段时间里各写一次位移 —— 这就是
 * 「下拉回弹不干净、像有多套逻辑」的来源。
 *
 * <p>现在只有两个量和一个弹簧：
 * <ul>
 *   <li>{@link #rawDistance}：手指空间里的越界距离（正 = 底部方向，负 = 顶部方向）；</li>
 *   <li>{@link #offset}：实际施加在内容上的 {@code translationY}，恒等于
 *       {@code -easing(rawDistance)}；</li>
 *   <li>{@link #spring}：只在手指抬起后运行，唯一目标是把 {@code offset} 收回 0。</li>
 * </ul>
 *
 * <p>每帧 MOVE 只做一件事：把这帧的手指位移按「先收形变、再滚内容」的顺序分配。
 * 系统滚动照常先跑（保持 {@code mLastMotionY} / {@code mIsBeingDragged} /
 * {@code VelocityTracker} 的状态机同步，切换零跳变），但一旦处在越界形变中，
 * 就把系统这一帧滚掉的量原样撤销，位移全部按上面的口径重新分配。
 *
 * <p>惯性撞边界也只喂给同一个弹簧：不再单独跑一条「冲出」动画，而是给弹簧一个朝外的
 * 初速度，让它自己冲出去、再收回来 —— 一次积分、一个曲线，没有接缝。
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
    /** 抬手时喂给弹簧的手指速度上限：再大就会从 10% 屏高一路冲到 25% 以上，像失控。 */
    private static final float MAX_RELEASE_VELOCITY = 2600f;
    /**
     * 惯性撞边界的初始速度上限。ζ=0.9 / ω=13.8 时峰值位移 ≈ 0.0286 × v，
     * 5600 px/s 对应约 160px（约 6% 屏高），有惯性但不夸张。
     */
    private static final float MAX_FLING_VELOCITY = 5600f;
    /** 惯性速度 → 弹簧初速度的折算系数（实测倍率，配合上面的峰值关系定标）。 */
    private static final float FLING_VELOCITY_SCALE = 0.7f;
    /** 调试开关：打印每一次非零形变与来源（定位「中段回弹」用，发布置 false）。 */
    private static final boolean DEBUG_OVERSCROLL = false;

    private final Ui.Spring spring = new Ui.Spring(0f);

    private OnOverScroll listener;

    /** 上一次 MOVE 的触摸 y。 */
    private float lastY;
    /** 按下点 y 与「是否已越过 touchSlop」。没过 slop 的微小位移不能起步橡皮筋。 */
    private float downY;
    /** 本次手势是否已经拿到起点（DOWN 可能被子 View 吃掉，见 onInterceptTouchEvent）。 */
    private boolean gestureStarted;
    /** 本次手势是否已经接管形变（停了弹簧、从当前 offset 接着拉）。 */
    private boolean tookOver;
    /** 仅调试：最近一次位移来源。 */
    private String why = "";
    private boolean dragging;
    private final int touchSlop = android.view.ViewConfiguration.get(
            getContext()).getScaledTouchSlop();
    /** 手指空间里的越界累计距离：负 = 顶部下拉，正 = 底部上推。 */
    private float rawDistance;
    /** 实际施加的 translationY。 */
    private float offset;
    /** 手指速度（px/s，正 = 手指上移），抬手时喂给弹簧。 */
    private float fingerVelocity;
    private long lastMoveAt;

    /** 弹簧是否在跑（手指抬起后的唯一动画）。 */
    private boolean settling;
    private long lastFrameAt;
    private int settleFrames;

    /** 上一次 fling 的 scrollY 增量，用来检测「惯性撞边界」。 */
    private int lastFlingDelta;
    /** watchdog 上一帧看到的 scrollY。 */
    private int watchScrollY;
    /**
     * 是否处于 fling（惯性）期间。{@code computeScroll()} 每帧都会被调，
     * 非 fling 时增量同样为 0，不显式跟踪就会把上一次滚动留下的旧增量误判成撞边界。
     */
    private boolean flinging;
    private int flingIdleFrames;
    /** 本次 fling 的初始速度（px/s），用于「一帧都没滚过」时折算冲量。 */
    private int flingVelocityY;

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

    // ------------------------------------------------------------------ 触摸

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        boolean intercept = super.onInterceptTouchEvent(e);
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // **必须在这里也记录起点**：手指按在可点击的子 View（设置页的卡片行、
            // 开关、下拉项……）上时，DOWN 会被子 View 消费，根本进不到 onTouchEvent，
            // 我们第一次拿到的是「已被父类判定为滚动」的那个 MOVE。若不在这里记录起点，
            // lastY / downY 还是上一次手势的残留值，第一帧 dy 会是一个上千 px 的假跳变：
            // 父类照它自己的 mLastMotionY 正常滚动（moved 很小），这个假余量一旦方向
            // 撞上边界就被当成越界起点，直接拉到抛物线饱和值（约 20% 屏高）——
            // 表现就是「还没到边界、方向也不对，却猛地弹一下」。
            onGestureDown(e.getY());
        } else if (intercept) {
            // 父类这一帧刚判定开始滚动（已越过 touchSlop），后面的 MOVE 都会交给我们。
            dragging = true;
            lastMoveAt = SystemClock.uptimeMillis();
        }
        return intercept;
    }

    /** 记录一次手势的起点。不碰形变/弹簧 —— 手指可能只是点在卡片上。 */
    private void onGestureDown(float y) {
        flinging = false;      // 手指按下会中止系统 fling
        removeCallbacks(flingWatch);
        flingIdleFrames = 0;
        lastFlingDelta = 0;
        lastY = y;
        downY = y;
        dragging = false;
        gestureStarted = true;
        tookOver = false;
        lastMoveAt = SystemClock.uptimeMillis();
        fingerVelocity = 0f;
    }

    /**
     * 真正轮到我们处理拖动时，才停弹簧并接管当前形变。
     *
     * <p>不能在 DOWN 就做：DOWN 可能被子 View 吃掉（纯点击），那样会把正在回弹的
     * 形变冻在半路（弹簧被 cancel，又没人接着跑）。这里从当前 {@code offset} 反解出
     * 手指空间距离，手指接着往下拖不会跳。
     */
    private void takeOverDeformation() {
        stopSpring();
        if (Math.abs(offset) > 0.5f) {
            rawDistance = inverseEasing(-offset);
        } else {
            rawDistance = 0f;
            apply(0f);
        }
        tookOver = true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                onGestureDown(e.getY());
                return super.onTouchEvent(e);
            }

            case MotionEvent.ACTION_MOVE: {
                if (!gestureStarted) {
                    // 理论上不会走到：DOWN 至少会在 onInterceptTouchEvent 里被看到。
                    // 万一真丢了起点，宁可这一帧只当普通滚动，也不要拿残留坐标算出假余量。
                    onGestureDown(e.getY());
                    return super.onTouchEvent(e);
                }
                if (!tookOver) {
                    takeOverDeformation();
                }
                float dy = lastY - e.getY();   // > 0 手指上移（内容应向上滚）
                lastY = e.getY();

                long now = SystemClock.uptimeMillis();
                float dt = Math.max(1f / 240f, (now - lastMoveAt) / 1000f);
                lastMoveAt = now;
                // 轻微低通，避免最后一帧的抖动被当成抬手速度
                fingerVelocity = fingerVelocity * 0.35f + (dy / dt) * 0.65f;

                // 系统滚动先跑：保持它内部状态机同步。返回它这一帧实际滚掉的量。
                int before = getScrollY();
                boolean handled = super.onTouchEvent(e);
                int moved = getScrollY() - before;

                // 没过 touchSlop 的微小位移只交给系统（点击抖动不该拉起橡皮筋）。
                if (!dragging) {
                    if (rawDistance == 0f && Math.abs(e.getY() - downY) <= touchSlop) {
                        return handled;
                    }
                    dragging = true;
                }

                if (rawDistance == 0f) {
                    // 没有形变：系统已经按普通滚动处理，它没吃下的余量才可能是越界起点。
                    //
                    // **必须再判一次边界**：ScrollView 在「刚越过 touchSlop」的那一帧会先把
                    // slop 从位移里扣掉再滚，于是那一帧 moved 比 dy 少了整整一个 slop，
                    // leftover 恒为非 0。不判边界的话，页面正中间随便一划都会被当成越界，
                    // 起手就拉出一段形变，抬手时弹簧再把它弹回去 —— 表现就是
                    // 「每滑一次都触发边界回弹」。只有该方向已经滚不动，余量才是真越界。
                    float leftover = dy - moved;
                    boolean blocked = leftover > 0f
                            ? !canScrollVertically(1) : !canScrollVertically(-1);
                    if (leftover != 0f && blocked) {
                        why = "MOVE dy=" + Math.round(dy) + " moved=" + moved;
                        rawDistance = leftover;
                        apply(-defaultParabolaScrollEasing(rawDistance, getHeight()));
                        return true;   // 越界手势不落到子 View 的点击上
                    }
                    apply(0f);
                    return handled;
                }

                // 已越界：撤销系统这一帧的滚动，位移统一按「先收形变、再滚内容」分配。
                if (moved != 0) {
                    scrollBy(0, -moved);
                }
                float next = rawDistance + dy;
                if (Math.signum(next) != Math.signum(rawDistance)) {
                    // 穿过 0：形变刚好收完，剩下的还给内容滚动。
                    rawDistance = 0f;
                    feedToScroll(next);
                    if (rawDistance != 0f) {
                        // 刚收完又在另一侧撞到边界：继续当形变，不要一帧内来回翻。
                        apply(-defaultParabolaScrollEasing(rawDistance, getHeight()));
                        return true;
                    }
                    apply(0f);
                    return handled;
                }
                rawDistance = next;
                apply(-defaultParabolaScrollEasing(rawDistance, getHeight()));
                return true;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                gestureStarted = false;
                tookOver = false;
                boolean wasOver = rawDistance != 0f || Math.abs(offset) > 0.5f;
                if (!wasOver) {
                    fingerVelocity = 0f;
                    return super.onTouchEvent(e);
                }
                // 越界状态下抬手：给 super 一个 CANCEL 收尾，避免它拿 UP 去启动一段
                // 必然被 clamp 掉的 fling（那会和弹簧抢动画）。
                if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                    MotionEvent cancel = MotionEvent.obtain(e);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.onTouchEvent(cancel);
                    cancel.recycle();
                } else {
                    super.onTouchEvent(e);
                }
                float v0 = Math.max(-MAX_RELEASE_VELOCITY,
                        Math.min(MAX_RELEASE_VELOCITY, -fingerVelocity));
                fingerVelocity = 0f;
                // 只在形变已建立时带惯性；形变已被手指收回 0 就不要再冲出去一次。
                startSpring(Math.abs(offset) > 0.5f ? v0 : 0f);
                return true;
            }

            default:
                return super.onTouchEvent(e);
        }
    }

    /**
     * 把一段手指位移交给内容滚动；滚不动（撞边界）的余量转成新的形变。
     *
     * <p>这是唯一一处「位移 → 形变」的转换，拖动路径与形变回收路径都走它。
     */
    private void feedToScroll(float d) {
        if (d == 0f) {
            return;
        }
        int before = getScrollY();
        scrollBy(0, Math.round(d));
        float used = getScrollY() - before;
        float leftover = d - used;
        if (leftover != 0f) {
            rawDistance = leftover;
        }
    }

    // ------------------------------------------------------------------ 惯性

    /**
     * 惯性撞边界：每帧比较 {@code scrollY} 有没有变化，一变都没有、而且该方向已经
     * 滚不动了，就把上一帧的增量折成弹簧初速度交给同一个弹簧 —— 不再单独跑一条
     * 「冲出」动画，冲出与收回是同一次积分。
     *
     * <p>为什么不用 {@code computeScroll()} 来监测：它只在 scroller 还在跑的时候被
     * 框架调用。惯性正好在最后一帧到达边界时，scroller 同时结束，之后再也没有回调，
     * 「甩到底」就永远不出橡皮筋（旧实现就有这个洞）。watchdog 自己逐帧 post，直到
     * 判定结束为止，时机不依赖框架。
     */
    private final Runnable flingWatch = new Runnable() {
        @Override
        public void run() {
            if (!flinging) {
                return;
            }
            int y = getScrollY();
            int delta = y - watchScrollY;
            watchScrollY = y;
            if (delta != 0) {
                lastFlingDelta = delta;
                flingIdleFrames = 0;
                postOnAnimation(this);
                return;
            }
            flingIdleFrames++;
            int dir = lastFlingDelta != 0 ? Integer.signum(lastFlingDelta)
                    : Integer.signum(flingVelocityY);
            boolean blocked = dir != 0
                    && (dir > 0 ? !canScrollVertically(1) : !canScrollVertically(-1));
            if (blocked) {
                int impulse = lastFlingDelta != 0 ? lastFlingDelta
                        : Math.round(flingVelocityY / 60f);
                flinging = false;
                flingIdleFrames = 0;
                lastFlingDelta = 0;
                kickOutward(impulse);
                return;
            }
            if (flingIdleFrames > 3) {
                // 没在边界却连续几帧没有位移：scroller 已自然结束，收尾即可。
                flinging = false;
                flingIdleFrames = 0;
                lastFlingDelta = 0;
                return;
            }
            postOnAnimation(this);
        }
    };

    /** 记录一次真正的惯性启动；ScrollView 在抬手时会自己调到这里。 */
    @Override
    public void fling(int velocityY) {
        flingVelocityY = velocityY;
        flinging = true;
        flingIdleFrames = 0;
        lastFlingDelta = 0;
        watchScrollY = getScrollY();

        // 抬手瞬间就已经贴在边界上：scroller 没有任何可滚余量，某些情况下它甚至会
        // 立刻结束。这里直接用速度生成初速度，保证「甩到底」和「拖到底」一样立刻出橡皮筋。
        boolean blockedNow = velocityY > 0
                ? !canScrollVertically(1) : !canScrollVertically(-1);
        if (blockedNow && Math.abs(velocityY) >= 120) {
            flinging = false;
            kickOutward(Math.round(velocityY / 60f));
            return;
        }
        super.fling(velocityY);
        removeCallbacks(flingWatch);
        postOnAnimation(flingWatch);
    }

    /** 惯性撞边：给弹簧一个朝外的初速度，冲出 + 收回由同一次积分完成。 */
    private void kickOutward(int impulse) {
        why = "KICK imp=" + impulse;
        if (Math.abs(offset) > 0.5f || settling) {
            return;   // 已经在形变/回弹中，别叠第二次
        }
        float v = -Math.signum(impulse)
                * Math.min(MAX_FLING_VELOCITY, Math.abs(impulse) * 60f * FLING_VELOCITY_SCALE);
        rawDistance = 0f;
        startSpring(v);
    }

    // ------------------------------------------------------------------ 弹簧

    private final Runnable springTick = new Runnable() {
        @Override
        public void run() {
            // 硬上限兜底：极端数值下若弹簧不收敛，也绝不会变成常驻循环。
            if (++settleFrames > 240) {
                finishSpring();
                return;
            }
            long now = SystemClock.uptimeMillis();
            float dt = lastFrameAt == 0 ? 0.016f : Math.min(0.048f, (now - lastFrameAt) / 1000f);
            lastFrameAt = now;
            spring.step(dt);
            apply(spring.value);
            // 不用 Spring#settled()：它的阈值是 0.0008px，对像素位移过严，
            // 会在已经看不出来的位置上多跑十几帧。
            if (Math.abs(spring.value) < 0.4f && Math.abs(spring.velocity) < 26f) {
                finishSpring();
            } else {
                postOnAnimation(this);
            }
        }
    };

    /**
     * 启动唯一的那条回弹动画。
     *
     * @param initialVelocity 朝外的初速度（px/s）。0 表示纯收回；非 0 时先冲出去再收回。
     */
    private void startSpring(float initialVelocity) {
        if (Math.abs(initialVelocity) > 0.5f) why = "SPRING v0=" + Math.round(initialVelocity);
        removeCallbacks(springTick);
        spring.value = offset;
        spring.target = 0f;
        spring.velocity = initialVelocity;
        lastFrameAt = 0;
        settleFrames = 0;
        if (Math.abs(spring.value) < 0.4f && Math.abs(spring.velocity) < 26f) {
            finishSpring();
            return;
        }
        settling = true;
        postOnAnimation(springTick);
    }

    private void finishSpring() {
        removeCallbacks(springTick);
        settling = false;
        spring.value = 0f;
        spring.velocity = 0f;
        lastFrameAt = 0;
        settleFrames = 0;
        rawDistance = 0f;
        apply(0f);
    }

    private void stopSpring() {
        removeCallbacks(springTick);
        settling = false;
        lastFrameAt = 0;
        settleFrames = 0;
    }

    /**
     * 形变方向对应的边界是否真的到了。
     *
     * <p>{@code v < 0} = 内容被推向上方 = 需要「已到底」；{@code v > 0} = 顶部。
     * 用 {@code canScrollVertically} 而不是自己算 scrollY，短页面（range=0）两个方向
     * 都会返回 false，正好允许两边都弹。
     */
    private boolean atOverScrollEdge(float v) {
        return v < 0f ? !canScrollVertically(1) : !canScrollVertically(-1);
    }

    private void apply(float v) {
        if (Math.abs(v) < 0.01f) {
            v = 0f;
        }
        // **硬约束：不在边界上就不许有形变**。
        //
        // 位移只该由「已经顶到边、手指还在继续推」产生。前面几道判断（余量必须撞边界、
        // 惯性 watchdog 必须滚不动）都依赖「这一帧的事件序列符合预期」，而真实手指的
        // 事件流（子 View 吃掉 DOWN、事件批量、抬手那一帧的残余速度……）总会有没覆盖到
        // 的组合，之前几版都是修掉一处又冒一处。所以这里再加一道与事件序列无关的兜底：
        // 只要此刻这一侧还能滚，就把形变清零 —— 中段「莫名其妙弹一下」在结构上不可能发生。
        if (v != 0f && !atOverScrollEdge(v)) {
            if (DEBUG_OVERSCROLL) {
                android.util.Log.d("HHOVS", "guard-block v=" + Math.round(v)
                        + " scrollY=" + getScrollY() + " why=" + why);
            }
            v = 0f;
            rawDistance = 0f;
        }
        if (v == offset) {
            return;   // 位移没变就不重绘、不回调：普通滚动时这里每帧都会被调到
        }
        offset = v;
        if (DEBUG_OVERSCROLL && v != 0f) {
            android.util.Log.d("HHOVS", "apply v=" + Math.round(v) + " scrollY=" + getScrollY()
                    + " max=" + (getChildAt(0) == null ? -1 : getChildAt(0).getHeight() - getHeight())
                    + " why=" + why);
        }
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
