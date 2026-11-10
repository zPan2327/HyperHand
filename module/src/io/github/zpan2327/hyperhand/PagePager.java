package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.OverScroller;

/**
 * 三页平级的横向容器 —— 自写的轻量 "ViewPager"，不引 AndroidX。
 *
 * 行为：
 * <ul>
 *   <li>手指横向拖动时三页一起平移，松手按速度 / 位移吸附到最近一页；</li>
 *   <li>纵向手势一律不拦截，交给页面里的 ScrollView，避免抢滚动；</li>
 *   <li>横向判定要求 {@code |dx| > touchSlop && |dx| > |dy| * 1.4}，
 *       斜向滑动明显偏竖直时仍然算滚动；</li>
 *   <li>只有第一页向左、最后一页向右才用阻尼回弹；</li>
 *   <li>切页过程中回调连续位置，底栏指示器据此跟手。</li>
 * </ul>
 */
final class PagePager extends ViewGroup {

    interface OnPageChange {
        /** 停留在第 position 页（吸附完成）。 */
        void onPageSelected(int position);

        /** 拖动/回弹中的连续位置，0..count-1。 */
        void onPageScrolled(float position);
    }

    private final int touchSlop;
    private final int minFlingVelocity;
    private final int maxFlingVelocity;
    private final OverScroller scroller;
    private VelocityTracker tracker;

    private OnPageChange listener;

    private int pageCount;
    private int current;
    private float offset;          // 以"页"为单位，0..count-1
    private boolean dragging;
    private boolean settling;
    private float downX;
    private float downY;
    private float lastX;
    private int scrollAtDown;

    PagePager(Context c) {
        super(c);
        ViewConfiguration vc = ViewConfiguration.get(c);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        scroller = new OverScroller(c);
        setClipChildren(true);
    }

    void setOnPageChange(OnPageChange l) {
        listener = l;
    }

    void setPageCount(int n) {
        pageCount = Math.max(1, n);
        offset = current;
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

    /** 直接跳页（点底栏 / 代码调用）。 */
    void setCurrentPage(int index, boolean animate) {
        int target = clampPage(index);
        if (target == current && !settling) {
            // 位置可能被拖动过，回正
            if (Math.abs(offset - target) < 0.01f) {
                return;
            }
        }
        current = target;
        if (!animate || getWidth() == 0) {
            offset = target;
            scroller.forceFinished(true);
            settling = false;
            applyScroll();
            notifyScrolled();
            return;
        }
        settling = true;
        scroller.forceFinished(true);
        int w = Math.max(1, getWidth());
        scroller.startScroll(Math.round(offset * w), 0, Math.round((target - offset) * w), 0, 280);
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
                dragging = false;
                settling = false;
                scroller.forceFinished(true);
                track(e);
                scrollAtDown = scrollYOfCurrent();
                return false;
            case MotionEvent.ACTION_MOVE: {
                track(e);
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
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
                track(e);
                return true;
            case MotionEvent.ACTION_MOVE: {
                track(e);
                if (!dragging) {
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
                    return true;
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
        float next = offset - dxPx / w;
        // 边界阻尼：第一页往右、最后一页往左，只走 35% 的距离
        if (next < 0f) {
            float over = -next;
            next = -(float) (1.0 - 1.0 / (over * 2.2f + 1.0)) * 0.35f;
        } else if (next > pageCount - 1) {
            float over = next - (pageCount - 1);
            next = (pageCount - 1) + (float) (1.0 - 1.0 / (over * 2.2f + 1.0)) * 0.35f;
        }
        offset = next;
        applyScroll();
        notifyScrolled();
    }

    private void settle(int velocityPx, boolean cancel) {
        int w = Math.max(1, getWidth());
        int target = Math.round(offset);
        if (!cancel && Math.abs(velocityPx) > minFlingVelocity) {
            target = velocityPx < 0 ? (int) Math.ceil(offset) : (int) Math.floor(offset);
        }
        target = clampPage(target);
        if (cancel) {
            target = current;
        }
        final int finalTarget = target;
        settling = true;
        scroller.forceFinished(true);
        scroller.startScroll(Math.round(offset * w), 0,
                Math.round((finalTarget - offset) * w), 0, 260);
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
            listener.onPageScrolled(offset);
        }
    }

    @Override
    public void computeScroll() {
        if (settling) {
            if (scroller.computeScrollOffset()) {
                int w = Math.max(1, getWidth());
                float next = (float) scroller.getCurrX() / w;
                if (next < 0f) {
                    next = 0f;
                } else if (next > pageCount - 1) {
                    next = pageCount - 1f;
                }
                offset = next;
                applyScroll();
                notifyScrolled();
                postInvalidateOnAnimation();
            } else {
                settling = false;
                int landed = Math.round(offset);
                offset = clampPage(landed);
                applyScroll();
                notifyScrolled();
                if (offset != current) {
                    current = (int) offset;
                    Haptics.get(getContext()).tick();
                    if (listener != null) {
                        listener.onPageSelected(current);
                    }
                } else if (listener != null) {
                    listener.onPageSelected(current);
                }
            }
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
