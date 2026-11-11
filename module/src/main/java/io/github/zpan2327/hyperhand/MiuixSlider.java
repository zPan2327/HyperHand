package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * miuix 的 {@code Slider} —— 逐条移植 {@code basic/Slider.kt} 的 SliderTrack。
 *
 * <pre>
 * SliderDefaults.MinHeight        = 28dp   （整个控件的<b>触摸区</b>高度，不是轨道高度）
 * SliderDefaults.TrackHeight      = 4dp    （轨道视觉厚度，圆头）
 * SliderDefaults.ThumbSize        = 20dp   （圆钮直径，白色 + 2dp 阴影）
 * 轨道背景                      = sliderBackground（亮 0x0F000000 / 暗 0x26FFFFFF）
 * 轨道前景                      = primary
 * 圆钮                          = onPrimary
 * 圆钮按下缩放                  = 1.127（ThumbScaleAnimationSpec）
 * 拖动时轨道叠一层 black 4.4%   （backgroundAlpha = 0.044）
 * </pre>
 *
 * <p><b>规格修正</b>：旧实现把 {@code MinHeight = 28dp} 当成了轨道厚度，于是轨道被画成
 * 一条 28dp 粗的实心圆角条、圆钮（半径 = 轨道半径 × 0.72）也被撑到接近轨道高，
 * 比例完全失真（真机截图里像 iOS 的粗 SeekBar，而不是 miuix 的细轨 + 大钮）。
 * 现在轨道固定 4dp、圆钮固定 20dp，28dp 只作为触摸区高度。
 *
 * <p>数值换算是"轨道两端各留一个圆钮半径"的可用区间，与源码的
 * {@code availableWidth = barWidth - 2 * thumbRadius} 一致，所以两端也能拖到。
 */
final class MiuixSlider extends View {

    interface OnValue {
        void onValue(float value);
    }

    private static final float THUMB_SCALE = 1.127f;
    /** SliderDefaults.TrackHeight —— 轨道视觉厚度。 */
    private static final float TRACK_H_DP = 4f;
    /** SliderDefaults.ThumbSize / 2 —— 圆钮半径。 */
    private static final float THUMB_R_DP = 10f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final Theme.Colors pal;

    private float min;
    private float max;
    private float step;
    private float value;

    private final Ui.Spring thumbScale = new Ui.Spring(1f);
    private boolean dragging;
    private long lastFrameAt;
    private OnValue listener;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = android.os.SystemClock.uptimeMillis();
            float dt = lastFrameAt == 0 ? 0.016f : Math.min(0.05f, (now - lastFrameAt) / 1000f);
            lastFrameAt = now;
            if (!thumbScale.settled()) {
                thumbScale.step(dt);
                invalidate();
                postOnAnimation(this);
            } else {
                lastFrameAt = 0;
            }
        }
    };

    MiuixSlider(Context c, Theme.Colors p, float min, float max, float step, float value) {
        super(c);
        this.pal = p;
        this.min = min;
        this.max = max;
        this.step = step <= 0 ? 1f : step;
        this.value = clampToStep(value);
        this.thumbScale.set(987f, 0.55f);
        setClickable(true);
    }

    void setOnValue(OnValue l) {
        listener = l;
    }

    void setValue(float v) {
        value = clampToStep(v);
        invalidate();
    }

    float value() {
        return value;
    }

    private float clampToStep(float v) {
        float c = Math.max(min, Math.min(max, v));
        if (step > 0) {
            float k = Math.round((c - min) / step);
            c = Math.min(max, min + k * step);
        }
        return c;
    }

    @Override
    protected void onMeasure(int w, int h) {
        // 高度交给布局：调用方给的是「轨道 28dp + 上下 padding」，
        // 这里再写死 28dp 会把轨道压成一条线。
        int height = MeasureSpec.getMode(h) == MeasureSpec.UNSPECIFIED
                ? Theme.dp(getContext(), 28)
                : MeasureSpec.getSize(h);
        setMeasuredDimension(MeasureSpec.getSize(w), Math.max(height, Theme.dp(getContext(), 28)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // 左右留出 padding，轨道才不会贴着卡片边
        float left = getPaddingLeft();
        float top = getPaddingTop();
        float barW = Math.max(1f, getWidth() - getPaddingLeft() - getPaddingRight());
        float barH = Math.max(1f, getHeight() - getPaddingTop() - getPaddingBottom());

        // 28dp 是触摸区，不是轨道厚度：轨道固定 4dp 并垂直居中。
        float trackH = Math.min(Theme.dpf(getContext(), TRACK_H_DP), barH);
        float tr = trackH / 2f;
        float cy = top + barH / 2f;

        float thumbR = Theme.dpf(getContext(), THUMB_R_DP) * thumbScale.value;
        // 两端各留一个圆钮半径，圆钮中心才能走到最左 / 最右
        float available = Math.max(0f, barW - 2f * thumbR);
        float frac = max <= min ? 0f : (value - min) / (max - min);
        float cx = left + thumbR + frac * available;

        // 轨道背景（sliderBackground）：细圆头
        track.set(left, cy - tr, left + barW, cy + tr);
        paint.setStyle(Paint.Style.FILL);
        paint.setShadowLayer(0f, 0f, 0f, 0);
        paint.setColor(pal.night ? 0x26FFFFFF : 0x0F000000);
        canvas.drawRoundRect(track, tr, tr, paint);

        // 拖动时整条轨道叠 black 4.4%
        if (dragging) {
            paint.setColor(0x0B000000);
            canvas.drawRoundRect(track, tr, tr, paint);
        }

        // 前景：从头到圆钮中心，圆头
        paint.setColor(pal.primary);
        paint.setStrokeWidth(trackH);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(left + tr, cy, Math.max(left + tr, cx), cy, paint);

        // 圆钮：20dp 白色，带 2dp 柔和阴影（miuix thumb elevation）
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setShadowLayer(Theme.dpf(getContext(), 2f), 0f, Theme.dpf(getContext(), 1f),
                0x33000000);
        canvas.drawCircle(cx, cy, thumbR, paint);
        paint.setShadowLayer(0f, 0f, 0f, 0);
    }

    /** 圆钮中心的绘制坐标（与 onDraw / applyTouch 共用）。 */
    private float trackCenterY() {
        return getPaddingTop() + Math.max(1f,
                getHeight() - getPaddingTop() - getPaddingBottom()) / 2f;
    }

    // ---------------------------------------------------------------- 手势

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                // 只有按在轨道（含圆钮）上才接管。View 的 padding 空白区、
                // 对话框里的大数值 / 说明文字 / 按钮一律不触发，也不跳值。
                if (!hitTrack(e.getX(), e.getY())) {
                    return false;
                }
                dragging = true;
                thumbScale.to(THUMB_SCALE);
                kick();
                applyTouch(e.getX(), true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) {
                    return false;
                }
                applyTouch(e.getX(), false);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) {
                    return false;
                }
                dragging = false;
                thumbScale.to(1f);
                kick();
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    /**
     * 命中区 = 圆钮中心上下各 14dp 的一条带（轨道 4dp 本身太细，按像素点不现实），
     * 仍严格小于 28dp 的控件高度，所以 View 上下 padding 的空白区点了不会跳值。
     */
    private boolean hitTrack(float x, float y) {
        float hit = Theme.dpf(getContext(), 14f);
        float left = getPaddingLeft();
        float right = getWidth() - getPaddingRight();
        return x >= left - hit && x <= right + hit
                && Math.abs(y - trackCenterY()) <= hit;
    }

    private void kick() {
        lastFrameAt = 0;
        removeCallbacks(ticker);
        postOnAnimation(ticker);
    }

    private void applyTouch(float x, boolean force) {
        // 与 onDraw 用同一套几何：两端各留一个圆钮半径
        float left = getPaddingLeft();
        float barW = Math.max(1f, getWidth() - getPaddingLeft() - getPaddingRight());
        float thumbR = Theme.dpf(getContext(), THUMB_R_DP);
        float available = Math.max(1f, barW - 2f * thumbR);
        float frac = Math.max(0f, Math.min(1f, (x - left - thumbR) / available));
        float raw = min + frac * (max - min);
        float next = clampToStep(raw);
        if (next == value && !force) {
            return;
        }
        value = next;
        invalidate();
        if (listener != null) {
            listener.onValue(value);
        }
    }
}
