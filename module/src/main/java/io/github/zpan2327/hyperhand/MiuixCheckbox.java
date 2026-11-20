package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * miuix {@code Checkbox} 的原生 View 移植。
 *
 * <p>三态对应 {@code androidx.compose.ui.state.ToggleableState}：
 * {@code On / Off / Indeterminate}。半选中显示一条横线而不是对勾，且与 On
 * 共用同一套颜色（miuix 文档明确要求），典型用法是"全选"父项：
 * 子项全选 → On、全不选 → Off、部分选中 → Indeterminate。
 *
 * <pre>
 * Checkbox(state = state, onClick = { ... })
 * CheckboxDefaults.checkboxColors()  →  checkedBackground = primary
 *                                      checkedForeground = onPrimary
 *                                      uncheckedBackground = secondary（实心灰，非描边）
 * </pre>
 *
 * 形状严格对齐 miuix Checkbox.kt：{@code capsuleShape = CircleShape}、组件
 * {@code requiredSize(26.dp)}、勾路径取 viewport(23f) 的三点坐标按比例换算、
 * 描边宽 {@code size.width * 0.09f}。选中/未选中用物理弹簧（987 / 0.6）在
 * secondary 与 primary 之间过渡，和 miuix Switch/Slider 的 ThumbScale 同族。
 */
final class MiuixCheckbox extends View {

    /** {@code ToggleableState} 的三态。 */
    enum State {
        ON, OFF, INDETERMINATE
    }

    interface OnStateChange {
        void onChanged(State state);
    }

    /** 一行 checkbox + 两行文字，供 Dialog 里的多选列表使用。 */
    static final class Row {
        final LinearLayout view;
        final MiuixCheckbox checkbox;

        Row(LinearLayout view, MiuixCheckbox checkbox) {
            this.view = view;
            this.checkbox = checkbox;
        }

        State state() {
            return checkbox.state();
        }
    }

    private static final int BOX_DP = 26;   // miuix Checkbox requiredSize = 26dp
    private static final int HIT_DP = 40;   // 触摸命中区，视觉仍是 26dp 的圆
    private static final float VIEWPORT = 23f; // miuix 勾路径的 viewport 边长

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path check = new Path();
    private final Theme.Colors pal;
    private final Ui.Spring fill = new Ui.Spring(0f);

    private State state = State.OFF;
    private OnStateChange listener;
    private long lastFrameAt;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = lastFrameAt == 0 ? 0.016f : Math.min(0.05f, (now - lastFrameAt) / 1000f);
            lastFrameAt = now;
            fill.step(dt);
            invalidate();
            if (!fill.settled()) {
                postOnAnimation(this);
            } else {
                lastFrameAt = 0;
            }
        }
    };

    MiuixCheckbox(Context c, Theme.Colors p) {
        super(c);
        this.pal = p;
        fill.set(987f, 0.6f);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Math.max(1f, Theme.dpf(c, 2f)));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
        setFocusable(true);
    }

    State state() {
        return state;
    }

    void setOnStateChange(OnStateChange l) {
        listener = l;
    }

    void setState(State s, boolean animate) {
        if (s == null || s == state) {
            return;
        }
        state = s;
        boolean on = s != State.OFF;
        if (animate) {
            fill.to(on ? 1f : 0f);
            kick();
        } else {
            fill.snap(on ? 1f : 0f);
            invalidate();
        }
        if (listener != null) {
            listener.onChanged(state);
        }
    }

    /** Off → On → Off；半选中态点一下变成全选（miuix 父子示例的行为）。 */
    void toggle() {
        setState(state == State.ON ? State.OFF : State.ON, true);
        Haptics.get(getContext()).toggle();
    }

    private void kick() {
        lastFrameAt = 0;
        removeCallbacks(ticker);
        postOnAnimation(ticker);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int size = Theme.dp(getContext(), HIT_DP);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float size = Theme.dpf(getContext(), BOX_DP);      // 26dp 圆直径
        float radius = size / 2f;
        float t = Theme.clamp01(fill.value);

        // miuix Checkbox：CircleShape，未选中 secondary 实心、选中 primary 实心，
        // 按弹簧进度在两色之间过渡（不是描边，也不是圆形缩成一点）。
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Theme.blend(pal.secondary, pal.primary, t));
        canvas.drawCircle(cx, cy, radius, paint);

        // 勾：viewport(23f) 三点坐标按比例换算到 26dp 圆内，描边宽 = size * 0.09。
        stroke.setStrokeWidth(size * 0.09f);
        stroke.setColor(pal.onPrimary);
        stroke.setAlpha(Math.round(255 * t));
        if (t <= 0.01f) {
            return;
        }
        float scale = size / VIEWPORT;
        float vpC = VIEWPORT / 2f;                          // 11.5
        if (state == State.INDETERMINATE) {
            // 半选中：一条横线（与 On 共用颜色）
            float half = 6f * Theme.dpf(getContext(), 1f);
            canvas.drawLine(cx - half, cy, cx + half, cy, stroke);
        } else if (state == State.ON) {
            // miuix Checkbox.kt 的 start / middle / end：
            //   (5, 9.4) → (10.3, 14.9) → (17.9, 5.1)
            check.reset();
            check.moveTo(cx + (5f - vpC) * scale, cy + (9.4f - vpC) * scale);
            check.lineTo(cx + (10.3f - vpC) * scale, cy + (14.9f - vpC) * scale);
            check.lineTo(cx + (17.9f - vpC) * scale, cy + (5.1f - vpC) * scale);
            canvas.drawPath(check, stroke);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            boolean inside = e.getX() >= 0 && e.getY() >= 0
                    && e.getX() <= getWidth() && e.getY() <= getHeight();
            if (inside) {
                performClick();
                toggle();
            }
            return true;
        }
        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            return true;
        }
        return super.onTouchEvent(e);
    }

    /**
     * miuix {@code RestartScopeDialog.RestartScopeRow} 的原生 View 移植，1:1 对齐：
     * <pre>
     * Row(fillMaxWidth, clip(RoundedCornerShape(12.dp)),
     *     clickable, padding(horizontal=12.dp, vertical=12.dp), CenterVertically) {
     *   Column(weight=1f) { Text(label, onSurface, headline1) }   // 文字在左，单行
     *   Spacer(12.dp)
     *   Checkbox(state)                                            // 圆勾在右
     * }
     * </pre>
     * 整行可点，点击即切换——与 SonyPods 的 {@code .clickable(role=Checkbox)} 一致。
     */
    static Row row(Context c, Theme.Colors p, String title,
                   State initial, final OnStateChange l) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        // miuix: Row 无常态背景（只有 clickable 的 ripple），clip(RoundedCornerShape(12.dp))，
        // padding(horizontal=12.dp, vertical=12.dp)。SonyPods 截图里行是透明的。
        android.content.res.ColorStateList ripple = android.content.res.ColorStateList.valueOf(
                p.night ? 0x14FFFFFF : 0x0D000000);
        row.setBackground(new android.graphics.drawable.RippleDrawable(ripple, null,
                Theme.round(0xFFFFFFFF, Theme.dp(c, 12))));
        row.setPadding(Theme.dp(c, 12), Theme.dp(c, 12),
                Theme.dp(c, 12), Theme.dp(c, 12));
        row.setClickable(true);
        row.setFocusable(true);

        // 文字在左，weight(1f)，headline1 = 17sp Medium，onSurface
        TextView label = Ui.text(c, title, Theme.T_ROW, p.title, Theme.medium());
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Spacer(12.dp)
        View spacer = new View(c);
        row.addView(spacer, new LinearLayout.LayoutParams(Theme.dp(c, 12), 1));

        // 圆勾在右
        final MiuixCheckbox cb = new MiuixCheckbox(c, p);
        cb.setState(initial, false);
        cb.setOnStateChange(l);
        row.addView(cb, new LinearLayout.LayoutParams(
                Theme.dp(c, HIT_DP), Theme.dp(c, HIT_DP)));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.toggle();
            }
        });
        return new Row(row, cb);
    }
}
