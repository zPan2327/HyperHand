package io.github.zpan2327.hyperhand;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/**
 * 组件层：全部按 compose-miuix-ui/miuix 的规格手写原生 View，
 * 没有 AndroidX / Compose，构建链只要 javac + aapt2。
 *
 * 对照的默认值：
 * <pre>
 * Card            cornerRadius 16dp，surfaceContainer 底（白 / #242424）
 * BasicComponent  minHeight 56dp，insideMargin 16dp，
 *                 标题 headline1 17sp Medium onSurface，
 *                 摘要 body2 14sp onSurfaceVariantSummary
 *                 行与行之间**不画分隔线**（miuix 与 HyperOS 设置页都没有）
 * SmallTitle      subtitle 14sp Bold onBackgroundVariant，缩进 26dp（页面 12 + 14）
 * 行尾值          body2 14sp onSurfaceVariantActions（0x66 黑）
 * Switch          49 × 28dp，圆钮 20dp（关 4dp / 开 25dp）
 * TextButton      cornerRadius 16dp，minHeight 40dp
 * WindowDialog    cornerRadius 32dp，inside 24dp，maxWidth 420dp
 * </pre>
 *
 * 页面节奏（12dp 页边距 + 12dp 卡片间距）来自 SonyPods 的 LazyColumn。
 */
final class Ui {

    private Ui() {
    }

    interface OnChoice {
        void onChoice(int index);
    }

    interface OnNumber {
        void onNumber(float value);
    }

    interface OnConfirm {
        void onConfirm();
    }

    // ---------------------------------------------------------------- 文本

    static TextView text(Context c, CharSequence s, float sp, int color, boolean medium) {
        return text(c, s, sp, color, medium ? Theme.medium() : Theme.regular());
    }

    static TextView text(Context c, CharSequence s, float sizeSp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView title(Context c, Theme.Colors p, CharSequence s) {
        return text(c, s, Theme.T_ROW, p.title, Theme.medium());
    }

    /**
     * SmallTitle：miuix subtitle（14sp Bold）+ SonyPods 的 insideMargin。
     * 页面已有 12dp 边距，这里补 14dp，左边距落在 26dp —— 与 miuix 的 28dp 视觉一致。
     */
    static TextView section(Context c, Theme.Colors p, String s) {
        TextView t = text(c, s, Theme.T_SECTION, p.section, Theme.bold());
        t.setPadding(Theme.dp(c, 14), Theme.dp(c, 14), Theme.dp(c, 14), Theme.dp(c, 6));
        return t;
    }

    // ---------------------------------------------------------------- 卡片

    /** miuix Card：16dp squircle 底，无内边距（行自己带 16dp）。 */
    static LinearLayout card(Context c, Theme.Colors p) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.round(p.card, Theme.dp(c, Theme.CARD_RADIUS)));
        card.setClipToOutline(true);
        return card;
    }

    /** 卡片之间的间距：SonyPods 用 12dp。 */
    static LinearLayout.LayoutParams cardLp(Context c, int topGapDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(c, topGapDp);
        return lp;
    }

    /** 可点击卡片：圆角底 + 按压蒙版 + 轻微缩放反馈。 */
    static void clickableCard(View card, int radiusDp, int baseColor, Theme.Colors p,
                              View.OnClickListener l) {
        Context c = card.getContext();
        Drawable bg = Theme.round(baseColor, Theme.dp(c, radiusDp));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(p.night ? 0x14FFFFFF : 0x0D000000),
                bg, Theme.round(Color.WHITE, Theme.dp(c, radiusDp))));
        card.setClickable(true);
        card.setOnClickListener(l);
        pressScale(card);
    }

    /** 往卡片里塞一行。miuix 的 BasicComponent 之间不画分隔线，这里也不画。 */
    static void addRow(Context c, LinearLayout card, Theme.Colors p, View row) {
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 行外壳：minHeight 56dp、左右 16dp、上下 10dp，内容垂直居中。 */
    static LinearLayout rowShell(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Theme.dp(c, Theme.ROW_MIN_H));
        row.setPadding(Theme.dp(c, Theme.ROW_PAD_H), Theme.dp(c, 10),
                Theme.dp(c, Theme.ROW_PAD_H), Theme.dp(c, 10));
        return row;
    }

    static void rowRipple(View row, Theme.Colors p) {
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(p.night ? 0x14FFFFFF : 0x0D000000),
                null, new ColorDrawable(Color.WHITE)));
        row.setClickable(true);
        tapFeedback(row, true);
    }

    /**
     * 给可点控件挂一层"轻触"触觉反馈。
     *
     * 用 OnTouchListener 而不是 OnClickListener：行/按钮的业务回调是各自
     * {@code setOnClickListener} 设置的，包一层会互相覆盖。这里返回 false，
     * 完全不参与事件分发，只在 ACTION_UP 且确实落在控件内时震一下。
     *
     * @param light true = 最轻的一档（列表行、tab），false = 实一点的按钮触感
     */
    static void tapFeedback(final View v, final boolean light) {
        v.setOnTouchListener(new View.OnTouchListener() {
            private boolean inside;

            @Override
            public boolean onTouch(View view, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        inside = true;
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        inside = e.getX() >= 0 && e.getY() >= 0
                                && e.getX() <= view.getWidth() && e.getY() <= view.getHeight();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                        if (inside) {
                            ctxHaptic(view, light);
                        }
                        inside = false;
                        break;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        inside = false;
                        break;
                    default:
                        break;
                }
                // 不消费：点击、长按、滚动都不受影响
                return false;
            }
        });
    }

    private static void ctxHaptic(View v, boolean light) {
        Haptics h = Haptics.get(v.getContext());
        if (light) {
            h.tick();
        } else {
            h.click();
        }
    }

    /** BasicComponent 的两行内容：标题 17sp Medium + 摘要 14sp。 */
    static LinearLayout twoLine(Context c, Theme.Colors p, String title, String summary) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(c, title, Theme.T_ROW, p.title, Theme.medium()));
        if (summary != null && summary.length() > 0) {
            TextView s = text(c, summary, Theme.T_SUM, p.summary, Theme.regular());
            s.setPadding(0, Theme.dp(c, 3), 0, 0);
            s.setLineSpacing(Theme.dp(c, 2), 1f);
            col.addView(s);
        }
        return col;
    }

    /** 行尾数值：值 14sp + 单位 14sp，都用 onSurfaceVariantActions。 */
    static CharSequence valueSpan(Theme.Colors p, String value, String unit) {
        SpannableStringBuilder sb = new SpannableStringBuilder(value);
        if (unit != null && unit.length() > 0) {
            int start = sb.length();
            sb.append(" ").append(unit);
            sb.setSpan(new AbsoluteSizeSpan(Math.round(Theme.T_SUM), true), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(p.chevron), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    static TextView valueText(Context c, Theme.Colors p, String value, String unit) {
        TextView t = text(c, valueSpan(p, value, unit), Theme.T_SUM, p.chevron, Theme.regular());
        t.setGravity(Gravity.END);
        return t;
    }

    /** miuix ArrowRight：10×16dp 的细箭头，颜色 onSurfaceVariantActions。 */
    static Icons.IconView chevronView(Context c, Theme.Colors p) {
        Icons.IconView v = Icons.view(c, Icons.CHEVRON, 14, p.chevron, 0f);
        return v;
    }

    static void addChevron(Context c, LinearLayout row, Theme.Colors p) {
        Icons.IconView cv = chevronView(c, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Theme.dp(c, 14), Theme.dp(c, 14));
        lp.leftMargin = Theme.dp(c, 6);
        row.addView(cv, lp);
    }

    /**
     * MIUIX「选择类」设置行右侧的上下双箭头（ArrowUpDown，10×16dp）。
     * 规范的语义区分：选择 / 弹层类行用这个双箭头；真正跳转到新页面的行（关于页链接等）
     * 才用 {@link #addChevron} 的 ArrowRight。
     */
    static void addDropdownArrow(Context c, LinearLayout row, Theme.Colors p) {
        android.view.View v = Icons.arrowUpDownView(c, p.chevron);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Theme.dp(c, 10), Theme.dp(c, 16));
        lp.leftMargin = Theme.dp(c, 8);
        row.addView(v, lp);
    }

    // ---------------------------------------------------------------- 行

    /**
     * 开关行：只有右侧的 {@link SwitchView} 本身可点击 / 拖动。
     * 行的标题、摘要、空白区域一律不触发切换（之前整行可点，点哪儿都会改开关）。
     */
    static SwitchView switchRow(Context c, LinearLayout card, Theme.Colors p,
                                String title, String summary, boolean checked) {
        LinearLayout row = rowShell(c);
        row.addView(twoLine(c, p, title, summary),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final SwitchView sw = new SwitchView(c, p);
        sw.setCheckedNoAnim(checked);
        // 给开关自身一点触摸扩展，但事件命中区仍严格限制在这个小 View 内
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Theme.dp(c, 49), Theme.dp(c, 28));
        slp.leftMargin = Theme.dp(c, 12);
        row.addView(sw, slp);
        addRow(c, card, p, row);
        return sw;
    }

    /**
     * 一级设置页只显示「标题 + 当前值 + 箭头」。
     * miuix Slider 不再挂在行下面（旧的 bottomAction 内联滑杆），
     * 点行打开对应的参数子页（MiuixDialog），滑杆只在子页里出现。
     */
    static final class ValueRow {
        final LinearLayout row;
        final TextView label;
        final Theme.Colors pal;
        final String unit;
        final int decimals;
        float value;
        private float min;
        private float max;
        private float step;
        private float[] options;

        ValueRow(LinearLayout row, TextView label, Theme.Colors pal, String unit, int decimals,
                 Context c, float min, float max, float step, Runnable onChange) {
            this.row = row;
            this.label = label;
            this.pal = pal;
            this.unit = unit;
            this.decimals = decimals;
            configure(min, max, step);
        }

        /** 数值行不再弹 Slider 子页：min/max/step 决定 Dropdown 的有限选项集。 */
        void configure(float minValue, float maxValue, float stepValue) {
            min = minValue;
            max = maxValue;
            step = stepValue;
            options = null;
        }

        float[] options() {
            if (options == null) {
                if (step <= 0f || max < min) {
                    options = new float[] { value };
                } else {
                    int count = Math.min(512, Math.round((max - min) / step) + 1);
                    options = new float[count];
                    for (int i = 0; i < count; i++) {
                        options[i] = Math.min(max, min + i * step);
                    }
                }
            }
            return options;
        }

        /** 预设值 / 存档值可能不在 step 网格上：找最近的一项，保证当前值始终可见。 */
        int indexOf(float v) {
            float[] all = options();
            int best = 0;
            float diff = Math.abs(all[0] - v);
            for (int i = 1; i < all.length; i++) {
                float d = Math.abs(all[i] - v);
                if (d < diff) {
                    diff = d;
                    best = i;
                }
            }
            return best;
        }

        void set(float v) {
            value = v;
            label.setText(valueSpan(pal, fmt(v, decimals), unit));
        }

        void setEnabled(boolean enabled) {
            rowEnabled(row, enabled);
        }
    }

    /** 数值行：右侧显示当前值，点行打开参数子页（Slider 只在子页里）。 */
    static ValueRow valueRow(Context c, LinearLayout card, Theme.Colors p,
                             String title, String summary, String unit,
                             float value, int decimals) {
        return valueRow(c, card, p, title, summary, unit, value, decimals, 0);
    }

    /** 兼容旧签名的数值行（范围 / 步长现在只由参数子页使用）。 */
    static ValueRow valueRow(Context c, LinearLayout card, Theme.Colors p,
                             String title, String summary, String unit,
                             float value, int decimals, final int sliderInset) {
        return valueRow(c, card, p, title, summary, unit, value, decimals,
                value, value, 1f, sliderInset, null);
    }

    static ValueRow valueRow(Context c, LinearLayout card, Theme.Colors p,
                             String title, String summary, String unit,
                             float value, int decimals,
                             float min, float max, float step,
                             final int sliderInset, Runnable onChange) {
        LinearLayout row = rowShell(c);
        rowRipple(row, p);
        row.addView(twoLine(c, p, title, summary),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView label = valueText(c, p, fmt(value, decimals), unit);
        label.setMaxWidth(Math.round(Theme.screenWidth(c) * 0.42f));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = Theme.dp(c, 12);
        row.addView(label, llp);
        addDropdownArrow(c, row, p);

        ValueRow vr = new ValueRow(row, label, p, unit, decimals,
                c, min, max, step, onChange);
        vr.value = value;
        addRow(c, card, p, row);
        return vr;
    }

    /** 数值行 + 明确的取值范围：Dropdown 的选项就由 min/max/step 生成。 */
    static ValueRow valueRow(Context c, LinearLayout card, Theme.Colors p,
                             String title, String summary, String unit,
                             float value, int decimals, float min, float max, float step) {
        return valueRow(c, card, p, title, summary, unit, value, decimals,
                min, max, step, 0, null);
    }

    static final class ChoiceRow {
        final LinearLayout row;
        final TextView label;
        int value;

        ChoiceRow(LinearLayout row, TextView label) {
            this.row = row;
            this.label = label;
        }

        void setEnabled(boolean enabled) {
            rowEnabled(row, enabled);
        }
    }

    /** MIUIX 禁用态：整行降到 50% 透明度且不再可点，选项里的同值行也同步禁用。 */
    static void rowEnabled(View row, boolean enabled) {
        row.setEnabled(enabled);
        if (row instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) row;
            for (int i = 0; i < g.getChildCount(); i++) {
                View child = g.getChildAt(i);
                child.setEnabled(enabled);
                child.setAlpha(enabled ? 1f : 0.5f);
                if (child instanceof ViewGroup) {
                    ViewGroup cg = (ViewGroup) child;
                    for (int j = 0; j < cg.getChildCount(); j++) {
                        View grand = cg.getChildAt(j);
                        grand.setEnabled(enabled);
                        grand.setAlpha(enabled ? 1f : 0.5f);
                    }
                }
            }
        }
    }

    /** 单选行：右侧显示当前选项文字。 */
    static ChoiceRow choiceRow(Context c, LinearLayout card, Theme.Colors p,
                               String title, String summary, String[] options, int selected,
                               final OnChoice open) {
        LinearLayout row = rowShell(c);
        rowRipple(row, p);
        row.addView(twoLine(c, p, title, summary),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int idx = Math.max(0, Math.min(options.length - 1, selected));
        final TextView label = text(c, options[idx], Theme.T_SUM, p.chevron, Theme.regular());
        label.setGravity(Gravity.END);
        label.setMaxWidth(Math.round(Theme.screenWidth(c) * 0.42f));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = Theme.dp(c, 12);
        row.addView(label, llp);
        addDropdownArrow(c, row, p);

        final ChoiceRow cr = new ChoiceRow(row, label);
        cr.value = idx;
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open.onChoice(cr.value);
            }
        });
        addRow(c, card, p, row);
        return cr;
    }

    /** 纯操作行：标题（+摘要）+ 箭头。 */
    static LinearLayout actionRow(Context c, LinearLayout card, Theme.Colors p,
                                  String title, String summary, final Runnable action) {
        return navRow(c, card, p, -1, title, summary, null, action);
    }

    /** 带可选图标方块 / 行尾文字的操作行，用于"关于"页的源码 / 引用 / 许可。 */
    static LinearLayout navRow(Context c, LinearLayout card, Theme.Colors p, int iconKind,
                               String title, String summary, String endText, final Runnable action) {
        LinearLayout row = rowShell(c);
        rowRipple(row, p);
        if (iconKind >= 0) {
            row.addView(iconTile(c, p, iconKind, p.primary),
                    new LinearLayout.LayoutParams(Theme.dp(c, 32), Theme.dp(c, 32)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = Theme.dp(c, 12);
            row.addView(twoLine(c, p, title, summary), lp);
        } else {
            row.addView(twoLine(c, p, title, summary),
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        if (endText != null && endText.length() > 0) {
            TextView end = text(c, endText, Theme.T_SUM, p.chevron, Theme.regular());
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.leftMargin = Theme.dp(c, 8);
            row.addView(end, elp);
        }
        addChevron(c, row, p);
        if (action != null) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    action.run();
                }
            });
        }
        addRow(c, card, p, row);
        return row;
    }

    /**
     * SonyPods InfoText：标签 headline1 17sp Medium 在上、
     * 数值 body2 14sp 在下，条目间距 24dp、无分隔线。
     * 返回数值 TextView 以便就地刷新。
     */
    static TextView infoPair(Context c, LinearLayout card, Theme.Colors p, String label) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, Theme.dp(c, 24));
        col.addView(text(c, label, Theme.T_ROW, p.title, Theme.medium()));
        TextView v = text(c, "", Theme.T_SUM, p.summary, Theme.regular());
        v.setPadding(0, Theme.dp(c, 2), 0, 0);
        v.setLineSpacing(Theme.dp(c, 2), 1f);
        col.addView(v);
        card.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    /** SonyPods InfoCard 的一次性条目（末条 bottomPadding 传 0）。 */
    static void infoText(Context c, LinearLayout card, Theme.Colors p, String label, String value) {
        infoText(c, card, p, label, value, true);
    }

    static void infoText(Context c, LinearLayout card, Theme.Colors p, String label, String value,
                         boolean gapAfter) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, Theme.dp(c, gapAfter ? 24 : 0));
        col.addView(text(c, label, Theme.T_ROW, p.title, Theme.medium()));
        TextView v = text(c, value == null ? "" : value, Theme.T_SUM, p.summary, Theme.regular());
        v.setPadding(0, Theme.dp(c, 2), 0, 0);
        v.setLineSpacing(Theme.dp(c, 2), 1f);
        col.addView(v);
        card.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 圆角图标底 + 线性图标。 */
    static FrameLayout iconTile(Context c, Theme.Colors p, int kind, int tint) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(Theme.round(Theme.alpha(tint, p.night ? 0.20f : 0.10f), Theme.dp(c, 10)));
        Icons.IconView icon = Icons.view(c, kind, 18, tint, 0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Theme.dp(c, 18), Theme.dp(c, 18));
        lp.gravity = Gravity.CENTER;
        box.addView(icon, lp);
        return box;
    }

    /** 多行说明块。 */
    static TextView block(Context c, Theme.Colors p, String s) {
        TextView t = text(c, s, Theme.T_SUM, p.summary, Theme.regular());
        t.setLineSpacing(Theme.dp(c, 4), 1f);
        t.setPadding(Theme.dp(c, 16), Theme.dp(c, 10), Theme.dp(c, 16), Theme.dp(c, 14));
        return t;
    }

    /** 卡片内的强调块（浅色底），用于校准提示这类信息。 */
    static TextView hintBlock(Context c, Theme.Colors p, String s, int accent) {
        TextView t = text(c, s, Theme.T_SUM, p.summary, Theme.regular());
        t.setLineSpacing(Theme.dp(c, 3), 1f);
        t.setPadding(Theme.dp(c, 12), Theme.dp(c, 10), Theme.dp(c, 12), Theme.dp(c, 10));
        t.setBackground(Theme.round(Theme.alpha(accent, p.night ? 0.16f : 0.07f), Theme.dp(c, 10)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Theme.dp(c, 16), Theme.dp(c, 2), Theme.dp(c, 16), Theme.dp(c, 14));
        t.setLayoutParams(lp);
        return t;
    }

    // ---------------------------------------------------------------- 按钮

    /** miuix TextButton（primary）：primary 底 + onPrimary 字，16dp 圆角。 */
    static TextView primaryButton(Context c, Theme.Colors p, String s) {
        TextView b = text(c, s, Theme.T_ROW, p.onPrimary, Theme.medium());
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                Theme.round(p.primary, Theme.dp(c, Theme.CARD_RADIUS)), null));
        tapFeedback(b, false);
        return b;
    }

    /** miuix TextButton（普通）：secondaryVariant 底 + onSecondaryVariant 字。 */
    static TextView secondaryButton(Context c, Theme.Colors p, String s) {
        TextView b = text(c, s, Theme.T_ROW, p.onFill, Theme.medium());
        b.setGravity(Gravity.CENTER);
        Drawable bg = Theme.round(p.fill, Theme.dp(c, Theme.CARD_RADIUS));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(p.night ? 0x14FFFFFF : 0x0D000000),
                bg, null));
        b.setClickable(true);
        b.setFocusable(true);
        tapFeedback(b, false);
        return b;
    }

    /** 无底色的图标按钮：标题栏右上角那种裸图标。 */
    static FrameLayout ghostIconButton(Context c, Theme.Colors p, int kind, final Runnable action) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(new RippleDrawable(ColorStateList.valueOf(p.night ? 0x14FFFFFF : 0x0D000000),
                null, Theme.round(Color.WHITE, Theme.dp(c, 999))));
        box.setClickable(true);
        Icons.IconView icon = Icons.view(c, kind, 22, p.title, 0f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Theme.dp(c, 22), Theme.dp(c, 22));
        lp.gravity = Gravity.CENTER;
        box.addView(icon, lp);
        int size = Theme.dp(c, 40);
        box.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        if (action != null) {
            box.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    action.run();
                }
            });
        }
        pressScale(box);
        return box;
    }

    /** 按下时缩到 0.965 再弹回，替代 Miuix 的 tilt 反馈；同时给一次按钮级触感。 */
    static void pressScale(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            private boolean inside;

            @Override
            public boolean onTouch(View view, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        inside = true;
                        animateScale(view, 0.965f, 90);
                        break;
                    case android.view.MotionEvent.ACTION_MOVE:
                        inside = e.getX() >= 0 && e.getY() >= 0
                                && e.getX() <= view.getWidth() && e.getY() <= view.getHeight();
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                        if (inside) {
                            Haptics.get(view.getContext()).clickPrimary();
                        }
                        inside = false;
                        animateScale(view, 1f, 170);
                        break;
                    case android.view.MotionEvent.ACTION_CANCEL:
                        inside = false;
                        animateScale(view, 1f, 170);
                        break;
                    default:
                        break;
                }
                return false;
            }
        });
    }

    static void animateScale(View v, float target, long duration) {
        v.animate().scaleX(target).scaleY(target).setDuration(duration)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
    }

    // ---------------------------------------------------------------- 页面容器

    static int pagePad(Context c) {
        float w = Theme.screenWidth(c) / c.getResources().getDisplayMetrics().density;
        int extra = (int) Math.max(0, (w - 560) / 2f);
        return Theme.dp(c, Theme.PAGE + extra);
    }

    /** 一页的骨架：ScrollView + 垂直内容列。 */
    static final class Page {
        final ScrollView scroll;
        final LinearLayout column;

        Page(ScrollView s, LinearLayout c) {
            scroll = s;
            column = c;
        }

        void add(View v) {
            column.addView(v);
        }

        void add(View v, LinearLayout.LayoutParams lp) {
            column.addView(v, lp);
        }

        /** 顶部留给折叠标题栏，底部留给悬浮底栏。 */
        void setInsets(int top, int bottom) {
            column.setPadding(column.getPaddingLeft(), top, column.getPaddingRight(), bottom);
        }
    }

    static Page page(Context c, Theme.Colors p) {
        // miuix overScrollVertical()：所有页面统一带 Rubber Band 越界回弹，
        // 系统自带的 EdgeEffect 由 MiuixScrollView 关掉（overscrollEffect = null）。
        MiuixScrollView scroll = new MiuixScrollView(c);
        scroll.setFillViewport(true);
        scroll.setBackground(Theme.pageBackground(p));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = pagePad(c);
        col.setPadding(pad, 0, pad, 0);
        scroll.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return new Page(scroll, col);
    }

    // ---------------------------------------------------------------- 数值格式

    static String fmt(float v, int decimals) {
        if (decimals <= 0) {
            return String.valueOf(Math.round(v));
        }
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    // ---------------------------------------------------------------- 开关

    /**
     * Miuix Switch：49×28dp 轨道、20dp 圆钮（关 4dp / 开 25dp）。
     * 动效照搬 miuix：轨道色 spring(438.6, 0.99)、圆钮位移 spring(987, 0.7)、
     * 按压时圆钮放大 1.127（spring 987, 0.6）—— 全部是物理弹簧，没有固定时长。
     */
    static final class SwitchView extends View {
        interface OnCheckedChange {
            void onCheckedChanged(boolean checked);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF rect = new android.graphics.RectF();
        private final int colorOn;
        private final int colorOff;
        private final int thumbColor = Color.WHITE;
        private final int shadow = 0x1F000000;
        /** 0..1 表示开关进度（位置与轨道色都用它）。 */
        private final Spring track = new Spring(0f);
        private final Spring press = new Spring(0f);
        private boolean checked;
        private OnCheckedChange listener;
        private long lastFrameAt;

        private final Runnable ticker = new Runnable() {
            @Override
            public void run() {
                long now = SystemClock.uptimeMillis();
                float dt = lastFrameAt == 0 ? 0.016f : Math.min(0.05f, (now - lastFrameAt) / 1000f);
                lastFrameAt = now;
                boolean active = false;
                if (!track.settled()) {
                    track.step(dt);
                    active = true;
                }
                if (!press.settled()) {
                    press.step(dt);
                    active = true;
                }
                invalidate();
                if (active) {
                    postOnAnimation(this);
                } else {
                    lastFrameAt = 0;
                }
            }
        };

        SwitchView(Context c, Theme.Colors p) {
            super(c);
            this.colorOn = p.primary;
            this.colorOff = p.night ? 0xFF505050 : 0xFFE6E6E6;
            // miuix Switch 的三组 spring 参数
            track.set(438.6f, 0.99f);
            press.set(987f, 0.6f);
            setClickable(true);
            setFocusable(true);
            super.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle();
                }
            });
        }

        private void kick() {
            lastFrameAt = 0;
            removeCallbacks(ticker);
            postOnAnimation(ticker);
        }

        void setCheckedNoAnim(boolean v) {
            checked = v;
            track.value = v ? 1f : 0f;
            track.target = track.value;
            track.velocity = 0f;
            invalidate();
        }

        boolean isChecked() {
            return checked;
        }

        void setOnCheckedChange(OnCheckedChange l) {
            listener = l;
        }

        void setChecked(boolean v, boolean animate) {
            if (checked == v) {
                return;
            }
            checked = v;
            if (animate) {
                track.to(v ? 1f : 0f);
                kick();
                // 澎湃 OS 的 SWITCH 效果：线马达上是一记短促的"嗒"，不是转子嗡鸣
                Haptics.get(getContext()).toggle();
            } else {
                track.value = v ? 1f : 0f;
                track.target = track.value;
                track.velocity = 0f;
                invalidate();
            }
            if (listener != null) {
                listener.onCheckedChanged(v);
            }
        }

        void toggle() {
            setChecked(!checked, true);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    press.to(1f);
                    kick();
                    return true;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    press.to(0f);
                    kick();
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
                        performClick();
                    }
                    return true;
                default:
                    return super.onTouchEvent(e);
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Theme.dp(getContext(), 49), Theme.dp(getContext(), 28));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float t = clamp01(track.value);
            int trackColor = Theme.blend(colorOff, colorOn, t);
            rect.set(0, 0, w, h);
            paint.setColor(trackColor);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);

            float d = Theme.dp(getContext(), 20) * (1f + 0.127f * clamp01(press.value));
            float left = Theme.dp(getContext(), 4) + t * (w - Theme.dp(getContext(), 8) - d);
            float cy = h / 2f;
            paint.setColor(shadow);
            canvas.drawCircle(left + d / 2f, cy + Theme.dp(getContext(), 1.2f), d / 2f, paint);
            paint.setColor(thumbColor);
            canvas.drawCircle(left + d / 2f, cy, d / 2f, paint);
        }

        private static float clamp01(float v) {
            return v < 0f ? 0f : (v > 1f ? 1f : v);
        }
    }

    // ---------------------------------------------------------------- 对话框

    // ---------------------------------------------------------------- 选择 / Dropdown
    //
    // 单选、数值范围与其它有限选项配置统一使用 MIUIX OverlayDropdownPopup：
    // Java 行只显示当前值并把锚点矩形交给 MainActivity 的 Compose 宿主，
    // 选项行、勾选态、禁用态、震动、定位与退场动画全部由 MIUIX 组件负责。
    // Dialog 只保留两类真正需要它的场景：破坏性操作确认、长文本阅读。

    /** 单选下拉：右侧显示当前值，点击弹出 MIUIX Dropdown，选中即回写。 */
    static void choiceDropdown(final Activity a, final ChoiceRow row,
                               final String[] optionLabels, final String[] summaries,
                               final OnChoice cb) {
        ((MainActivity) a).showDropdown(row.row, optionLabels, summaries, row.value, cb);
    }

    /**
     * 数值项：MIUIX 单列 NumberPicker（SonyPods 电量岛时长同款，只是单列）。
     * min/max/step 生成有限选项，索引即值，天然满足范围与步长校验；索引回写仍走
     * values[index]，配置键 / 广播链路与旧 Dropdown 完全一致。
     */
    static void numericPicker(final Activity a, final ValueRow row, final String title,
                              final String unit, final int decimals, final OnNumber cb) {
        final float[] values = row.options();
        final String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            String s = fmt(values[i], decimals);
            labels[i] = unit == null || unit.length() == 0 ? s : s + " " + unit;
        }
        ((MainActivity) a).showNumberPicker(title, labels, row.indexOf(row.value),
                new OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        if (index >= 0 && index < values.length) {
                            cb.onNumber(values[index]);
                        }
                    }
                });
    }

    /** 确认对话框（MiuixDialog）：用于重启作用域这类破坏性操作。 */
    static void confirmDialog(final Activity a, final Theme.Colors p, String title, String summary,
                              String okText, final OnConfirm cb) {
        final MiuixDialog d = new MiuixDialog(a, p);
        d.title(title);
        d.summary(summary);
        d.buttons(okText, new MiuixDialog.OnClick() {
            @Override
            public void onClick(MiuixDialog dlg) {
                dlg.dismiss();
                cb.onConfirm();
            }
        }, "取消", new MiuixDialog.OnClick() {
            @Override
            public void onClick(MiuixDialog dlg) {
                dlg.dismiss();
            }
        });
        d.show();
    }

    /** 高度上限可设的 ScrollView：内容短时按内容收缩，长时才滚动。 */
    static final class MaxScrollView extends MiuixScrollView {
        private int maxHeight;

        MaxScrollView(android.content.Context c, int maxHeight) {
            super(c);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int w, int h) {
            int capped = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            super.onMeasure(w, capped);
        }
    }

    /**
     * 长文本信息对话框：引用 / 许可 / 使用说明 / 运行状态，内容可滚动。
     *
     * <p>界面严格走 MIUIX 原生 OverlayDialog——委托给 MainActivity 的
     * {@link MainActivity#showInfoDialog(String, String)}，它把
     * {@code InfoDialogHost}（Compose 桥）叠到 Activity 根容器渲染。
     * 不再用 Java 手写的 MiuixDialog 仿制品。
     */
    static void infoDialog(final Activity a, final Theme.Colors p, String title, String body) {
        if (a instanceof MainActivity) {
            ((MainActivity) a).showInfoDialog(title, body);
        }
    }

    /** 通用弹簧：半隐式欧拉 + 子步长，避免大 stiffness 下数值爆炸。 */
    static final class Spring {
        float value;
        float target;
        float velocity;
        float stiffness = 900f;
        float damping = 49f;

        Spring(float v) {
            value = v;
            target = v;
        }

        void set(float stiffness, float ratio) {
            this.stiffness = stiffness;
            this.damping = 2f * ratio * (float) Math.sqrt(stiffness);
        }

        void to(float t) {
            target = t;
        }

        void snap(float v) {
            value = v;
            target = v;
            velocity = 0f;
        }

        boolean settled() {
            return Math.abs(target - value) < 0.0008f && Math.abs(velocity) < 0.008f;
        }

        void step(float dt) {
            int n = Math.max(1, (int) Math.ceil(dt / (1f / 240f)));
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                float a = -stiffness * (value - target) - damping * velocity;
                velocity += a * h;
                value += velocity * h;
            }
            if (Math.abs(target - value) < 0.0008f && Math.abs(velocity) < 0.008f) {
                value = target;
                velocity = 0f;
            }
        }
    }
}
