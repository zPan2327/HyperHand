package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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
 *                                      uncheckedBackground = 透明 + outline 描边
 * </pre>
 *
 * 状态切换用物理弹簧（987 / 0.6，和 miuix Switch/Slider 的 ThumbScale 同族），
 * 不是固定时长的补间。
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

    private static final int BOX_DP = 22;
    private static final int HIT_DP = 40;
    private static final float CORNER_DP = 6f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
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
        float half = Theme.dpf(getContext(), BOX_DP) / 2f;
        float radius = Theme.dpf(getContext(), CORNER_DP);
        float t = Theme.clamp01(fill.value);
        box.set(cx - half, cy - half, cx + half, cy + half);

        // 未选中：outline 描边；选中：primary 底。两者按弹簧进度交叉淡入淡出。
        stroke.setColor(pal.outline);
        stroke.setAlpha(Math.round(255 * (1f - t)));
        if (stroke.getAlpha() > 0) {
            canvas.drawRoundRect(box, radius, radius, stroke);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pal.primary);
        paint.setAlpha(Math.round(255 * t));
        if (paint.getAlpha() > 0) {
            canvas.drawRoundRect(box, radius, radius, paint);
        }

        if (t <= 0.01f) {
            return;
        }
        stroke.setColor(pal.onPrimary);
        stroke.setAlpha(Math.round(255 * t));
        float d = Theme.dpf(getContext(), 1f);
        if (state == State.INDETERMINATE) {
            // 半选中：一条横线（与 On 共用颜色）
            canvas.drawLine(cx - 5f * d, cy, cx + 5f * d, cy, stroke);
        } else if (state == State.ON) {
            check.reset();
            check.moveTo(cx - 5f * d, cy - 0.5f * d);
            check.lineTo(cx - 1.5f * d, cy + 3.5f * d);
            check.lineTo(cx + 5.5f * d, cy - 4f * d);
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

    /** miuix 风格的 checkbox 行：整行可点，点击即切换。 */
    static Row row(Context c, Theme.Colors p, String title, String summary,
                   State initial, final OnStateChange l) {
        LinearLayout row = Ui.rowShell(c);
        // 窄对话框里收紧行高与左右内边距，避免三行 checkbox 把 Dialog 撑得过高
        int inset = MiuixDialog.pad(c);
        row.setMinimumHeight(Theme.dp(c, 48));
        row.setPadding(inset, Theme.dp(c, 6), inset, Theme.dp(c, 6));
        Ui.rowRipple(row, p);
        final MiuixCheckbox cb = new MiuixCheckbox(c, p);
        cb.setState(initial, false);
        cb.setOnStateChange(l);
        row.addView(cb, new LinearLayout.LayoutParams(
                Theme.dp(c, HIT_DP), Theme.dp(c, HIT_DP)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(c, title, 15, p.title, Theme.medium()));
        if (summary != null && summary.length() > 0) {
            TextView s = Ui.text(c, summary, 12, p.summary, Theme.regular());
            s.setPadding(0, Theme.dp(c, 2), 0, 0);
            col.addView(s);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = Theme.dp(c, 10);
        row.addView(col, lp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.toggle();
            }
        });
        return new Row(row, cb);
    }
}
