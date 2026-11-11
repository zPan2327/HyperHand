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

        ValueRow(LinearLayout row, TextView label, Theme.Colors pal, String unit, int decimals,
                 Context c, float min, float max, float step, Runnable onChange) {
            this.row = row;
            this.label = label;
            this.pal = pal;
            this.unit = unit;
            this.decimals = decimals;
        }

        void set(float v) {
            value = v;
            label.setText(valueSpan(pal, fmt(v, decimals), unit));
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
        addChevron(c, row, p);

        ValueRow vr = new ValueRow(row, label, p, unit, decimals,
                c, min, max, step, onChange);
        vr.value = value;
        addRow(c, card, p, row);
        return vr;
    }

    static final class ChoiceRow {
        final LinearLayout row;
        final TextView label;
        int value;

        ChoiceRow(LinearLayout row, TextView label) {
            this.row = row;
            this.label = label;
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
        addChevron(c, row, p);

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

    // ---------------------------------------------------------------- 对话框
    //
    // 全应用的弹窗只走 MiuixDialog（miuix Dialog / WindowDialog 组件）。
    // 旧的 dialogShell / dialogTitle / dialogButtons / sliderTrack / dialogTextButton
    // 各自 new Dialog，popupMenu 用 PopupWindow，隐藏调试页用 root overlay ——
    // 四条不同的子窗口路径全部收敛到同一个组件，规格见 MiuixDialog。

    /** 概览右上角的锚点菜单已改为 MiuixDialog + MiuixCheckbox（见 MainActivity）。 */

    /**
     * 参数详情子页：点一级设置页的数值行之后才打开，miuix Slider 只在这一层出现。
     *
     * <p>一级页面现在只有「标题 + 当前值 + 箭头」，不再把 SliderPreference 的
     * bottomAction 内联滑杆挂在行下面。子页本身由 MiuixDialog 承载 ——
     * 所有弹出式子窗口统一用 Dialog 组件。
     */
    static void sliderPageDialog(final Activity a, final Theme.Colors p,
                                 String title, String summary, final String unit,
                                 final float min, final float max, final float step,
                                 final int decimals, float current, final OnNumber cb) {
        final MiuixDialog d = new MiuixDialog(a, p);
        d.title(title);
        d.summary(summary);

        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);

        final int inset = MiuixDialog.pad(a);
        final TextView big = text(a, "", 30, p.title, Theme.medium());
        big.setGravity(Gravity.CENTER);
        big.setPadding(inset, Theme.dp(a, 2), inset, 0);
        body.addView(big);

        TextView u = text(a, unit == null || unit.length() == 0 ? " " : unit,
                13, p.summary, Theme.regular());
        u.setGravity(Gravity.CENTER);
        u.setPadding(inset, Theme.dp(a, 2), inset, Theme.dp(a, 6));
        body.addView(u);

        final float[] result = {current};
        // 子页里用 miuix Slider，和一级页面彻底解耦
        final MiuixSlider sb = new MiuixSlider(a, p, min, max, step, current);
        sb.setOnValue(new MiuixSlider.OnValue() {
            @Override
            public void onValue(float v) {
                result[0] = v;
                big.setText(fmt(v, decimals));
                Haptics.get(a).step();
            }
        });
        sb.setPadding(inset, Theme.dp(a, 12), inset, Theme.dp(a, 6));
        // 高度 = 轨道 28dp + 上下 padding，轨道才是完整的 28dp
        body.addView(sb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(a, 28 + 18)));
        result[0] = sb.value();
        big.setText(fmt(result[0], decimals));
        d.content(body);

        d.buttons("确定", new MiuixDialog.OnClick() {
            @Override
            public void onClick(MiuixDialog dlg) {
                cb.onNumber(result[0]);
                dlg.dismiss();
            }
        }, "取消", new MiuixDialog.OnClick() {
            @Override
            public void onClick(MiuixDialog dlg) {
                dlg.dismiss();
            }
        });
        d.show();
    }

    /** 单选对话框（MiuixDialog）：标题 + 选项列表，选中项高亮 + 勾。 */
    static void singleChoiceDialog(final Activity a, final Theme.Colors p, String title,
                                   String summary, final String[] options, int selected,
                                   final OnChoice cb) {
        final MiuixDialog d = new MiuixDialog(a, p);
        d.title(title);
        d.summary(summary);

        LinearLayout list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int inset = MiuixDialog.pad(a);
            row.setMinimumHeight(Theme.dp(a, 46));
            row.setPadding(inset, Theme.dp(a, 7), inset, Theme.dp(a, 7));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(
                    p.night ? 0x14FFFFFF : 0x0D000000), null, new ColorDrawable(Color.TRANSPARENT)));
            row.setClickable(true);
            TextView label = text(a, options[i], 15,
                    i == selected ? p.primary : p.title, Theme.regular());
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i == selected) {
                Icons.IconView check = Icons.view(a, Icons.CHECK, 18, p.primary, 0f);
                row.addView(check, new LinearLayout.LayoutParams(
                        Theme.dp(a, 18), Theme.dp(a, 18)));
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cb.onChoice(idx);
                    d.dismiss();
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        d.content(list);
        d.button("取消", false, new MiuixDialog.OnClick() {
            @Override
            public void onClick(MiuixDialog dlg) {
                dlg.dismiss();
            }
        });
        d.show();
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

    /** 长文本对话框（MiuixDialog）：引用 / 许可 / 使用说明 / 运行状态，内容可滚动。 */
    static void infoDialog(final Activity a, final Theme.Colors p, String title, String body) {
        final MiuixDialog d = new MiuixDialog(a, p);
        d.title(title);

        int maxH = Math.min(Theme.dp(a, 340),
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.50f));
        ScrollView sc = new MaxScrollView(a, maxH);
        sc.setVerticalScrollBarEnabled(false);
        TextView bodyView = text(a, body, 13, p.summary, Theme.regular());
        bodyView.setLineSpacing(Theme.dp(a, 3), 1f);
        int inset = MiuixDialog.pad(a);
        bodyView.setPadding(inset, 0, inset, Theme.dp(a, 12));
        sc.addView(bodyView);
        d.content(sc);

        d.button("知道了", true, new MiuixDialog.OnClick() {
            @Override
            public void onClick(MiuixDialog dlg) {
                dlg.dismiss();
            }
        });
        d.show();
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
