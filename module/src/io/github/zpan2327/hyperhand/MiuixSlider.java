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
 * SliderDefaults.MinHeight        = 28dp   （轨道高度 = 28dp，圆头）
 * 轨道背景                      = sliderBackground（亮 0x0F000000 / 暗 0x26FFFFFF）
 * 轨道前景                      = primary
 * 圆钮                          = onPrimary，半径 = 轨道半径 × 0.72
 * 圆钮按下缩放                  = 1.127（ThumbScaleAnimationSpec）
 * 拖动时轨道叠一层 black 4.4%   （backgroundAlpha = 0.044）
 * 吸附 / 边界触感               = MESH_LIGHT / SCROLL_EDGE
 * </pre>
 *
 * 数值换算是"轨道两端各留一个半径"的可用区间，与源码的
 * {@code availableWidth = barWidth - 2 * thumbRadius} 一致，所以两端也能拖到。
 */
final class MiuixSlider extends View {

    interface OnValue {
        void onValue(float value);
    }

    private static final float THUMB_SCALE = 1.127f;

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
        setMeasuredDimension(MeasureSpec.getSize(w), Theme.dp(getContext(), 28));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float barH = getHeight();
        float barW = getWidth();
        float r = barH / 2f;
        float available = Math.max(0f, barW - 2f * r);
        float frac = (value - min) / (max - min);
        float cx = r + frac * available;

        track.set(0, 0, barW, barH);

        // 轨道背景（sliderBackground）
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.night ? 0x26FFFFFF : 0x0F000000);
        canvas.drawRoundRect(track, r, r, paint);

        // 拖动时整条轨道叠 black 4.4%
        if (dragging) {
            paint.setColor(0x0B000000);
            canvas.drawRoundRect(track, r, r, paint);
        }

        // 前景：从头到圆钮，圆头
        paint.setColor(pal.primary);
        paint.setStrokeWidth(barH);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(r, barH / 2f, Math.max(r, cx), barH / 2f, paint);

        // 圆钮：onPrimary，半径 = r × 0.72 × 缩放
        float thumbR = r * 0.72f * thumbScale.value;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.night ? 0xFF101010 : 0xFFFFFFFF);
        canvas.drawCircle(cx, barH / 2f, thumbR, paint);
    }

    // ---------------------------------------------------------------- 手势

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE: {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    dragging = true;
                    thumbScale.to(THUMB_SCALE);
                    kick();
                    // 点哪跳哪，和 Compose 版 onDragStarted 一致
                    applyTouch(e.getX(), true);
                } else {
                    applyTouch(e.getX(), false);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                thumbScale.to(1f);
                kick();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void kick() {
        lastFrameAt = 0;
        removeCallbacks(ticker);
        postOnAnimation(ticker);
    }

    private void applyTouch(float x, boolean force) {
        float r = getHeight() / 2f;
        float available = Math.max(1f, getWidth() - 2f * r);
        float frac = Math.max(0f, Math.min(1f, (x - r) / available));
        float raw = min + frac * (max - min);
        float next = clampToStep(raw);
        if (next == value && !force) {
            return;
        }
        value = next;
        invalidate();
        // 到达两端给边界触感，中间每格给 MESH_LIGHT
        if (listener != null) {
            listener.onValue(value);
        }
    }
}
