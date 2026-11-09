package io.github.zpan2327.hyperhand;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * HyperHand 的组件层。
 *
 * 全部是原生 View，没有 AndroidX / Compose：卡片、行、开关、按钮、对话框
 * 都按 Miuix 的规格手写（16dp 圆角、56dp 行高、16dp 内边距、0.75dp 分隔线、
 * 17sp Medium 行标题 / 13sp 摘要），这样在只有 javac + aapt2 的构建链里
 * 也能得到系统的 HyperOS 观感。
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
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(medium ? Theme.medium() : Theme.regular());
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 显式字重版：SemiBold 数值 / Bold 段落标题用（Miuix 的字重分层）。 */
    static TextView text(Context c, CharSequence s, float sizeSp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView title(Context c, Theme.Colors p, CharSequence s) {
        return text(c, s, Theme.T_ROW, p.title, true);
    }

    /**
     * SmallTitle：miuix subtitle（14sp Bold）+ SmallTitleDefaults.InsideMargin(28dp, 8dp)。
     * 页面本身已有 12dp 边距，这里补 16dp，视觉上正好与 miuix 的 28dp 缩进一致。
     */
    static TextView section(Context c, Theme.Colors p, String s, boolean first) {
        TextView t = text(c, s, Theme.T_SECTION, p.section, Theme.bold());
        t.setLetterSpacing(0.02f);
        t.setPadding(Theme.dp(c, 16), Theme.dp(c, first ? 16 : 8),
                Theme.dp(c, 16), Theme.dp(c, 8));
        return t;
    }

    /** 页面大标题 + 副标题，可选右侧操作。 */
    static LinearLayout pageHeader(Context c, Theme.Colors p, String head, String subtitle, View endAction) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int padH = Theme.dp(c, 12);
        box.setPadding(padH, Theme.dp(c, 10), padH, Theme.dp(c, 2));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = text(c, head, Theme.T_DISPLAY, p.title, true);
        t.setLetterSpacing(-0.01f);
        col.addView(t);
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = text(c, subtitle, Theme.T_SUM, p.summary, false);
            s.setPadding(0, Theme.dp(c, 5), 0, 0);
            col.addView(s);
        }
        box.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (endAction != null) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Theme.dp(c, 8);
            box.addView(endAction, lp);
        }
        return box;
    }

    // ---------------------------------------------------------------- 卡片

    static LinearLayout card(Context c, Theme.Colors p) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Theme.round(p.card, Theme.dp(c, Theme.CARD_RADIUS)));
        card.setClipToOutline(true);
        return card;
    }

    /** 可点击卡片：圆角底 + 按压蒙版 + 轻微缩放反馈。 */
    static void clickableCard(View card, int radiusDp, int baseColor, Theme.Colors p, View.OnClickListener l) {
        Context c = card.getContext();
        android.graphics.drawable.Drawable bg = Theme.round(baseColor, Theme.dp(c, radiusDp));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), bg,
                Theme.round(Color.WHITE, Theme.dp(c, radiusDp))));
        card.setClickable(true);
        card.setOnClickListener(l);
        pressScale(card);
    }

    /** 卡片之间的间距。 */
    static LinearLayout.LayoutParams cardLp(Context c, int topGapDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Theme.dp(c, topGapDp);
        return lp;
    }

    /** 往卡片里塞一行，自动在行与行之间画 0.75dp 分隔线（左侧内缩 16dp）。 */
    static void addRow(Context c, LinearLayout card, Theme.Colors p, View row) {
        if (card.getChildCount() > 0) {
            View d = new View(c);
            d.setBackgroundColor(p.divider);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Theme.dp(c, 0.75f)));
            dlp.leftMargin = Theme.dp(c, 16);
            card.addView(d, dlp);
        }
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

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
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), null,
                new ColorDrawable(Color.WHITE)));
        row.setClickable(true);
    }

    /** BasicComponent 的两行内容：标题 headline1 17sp Medium，摘要 body2 14sp。 */
    static LinearLayout twoLine(Context c, Theme.Colors p, String title, String summary) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(c, title, Theme.T_ROW, p.title, Theme.medium()));
        if (summary != null && summary.length() > 0) {
            TextView s = text(c, summary, Theme.T_SUM, p.summary, false);
            s.setPadding(0, Theme.dp(c, 3), 0, 0);
            s.setLineSpacing(Theme.dp(c, 2), 1f);
            col.addView(s);
        }
        return col;
    }

    /** 行尾数值：数字 17sp Medium，单位 13sp 弱化。 */
    static CharSequence valueSpan(Theme.Colors p, String value, String unit) {
        SpannableStringBuilder sb = new SpannableStringBuilder(value);
        if (unit != null && unit.length() > 0) {
            int start = sb.length();
            sb.append(" ").append(unit);
            sb.setSpan(new AbsoluteSizeSpan(Math.round(Theme.T_SUM), true), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(p.summary), start, sb.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    static TextView valueText(Context c, Theme.Colors p, String value, String unit) {
        TextView t = text(c, valueSpan(p, value, unit), Theme.T_ROW, p.title, Theme.semibold());
        t.setGravity(Gravity.END);
        return t;
    }

    static Icons.IconView chevronView(Context c, Theme.Colors p) {
        return Icons.view(c, Icons.CHEVRON, 17, p.chevron, 1.9f);
    }

    /** 行右侧的箭头，带一点左边距。 */
    static void addChevron(Context c, LinearLayout row, Theme.Colors p) {
        Icons.IconView cv = chevronView(c, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Theme.dp(c, 6);
        row.addView(cv, lp);
    }

    // ---------------------------------------------------------------- 行

    /** 开关行：整行可点，点击即切换。 */
    static SwitchView switchRow(Context c, LinearLayout card, Theme.Colors p,
                                String title, String summary, boolean checked) {
        LinearLayout row = rowShell(c);
        rowRipple(row, p);
        row.addView(twoLine(c, p, title, summary),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final SwitchView sw = new SwitchView(c, p);
        sw.setCheckedNoAnim(checked);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = Theme.dp(c, 12);
        row.addView(sw, slp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        addRow(c, card, p, row);
        return sw;
    }

    static final class ValueRow {
        final LinearLayout row;
        final TextView label;
        final Theme.Colors pal;
        final String unit;
        final int decimals;
        float value;

        ValueRow(LinearLayout row, TextView label, Theme.Colors pal, String unit, int decimals) {
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

    /** 数值行：右侧显示当前值，点击弹出滑杆对话框。 */
    static ValueRow valueRow(Context c, LinearLayout card, Theme.Colors p,
                             String title, String summary, String unit,
                             float value, int decimals) {
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

        ValueRow vr = new ValueRow(row, label, p, unit, decimals);
        vr.value = value;
        addRow(c, card, p, row);
        return vr;
    }

    /** 单选行：右侧显示当前选项文字。 */
    static final class ChoiceRow {
        final LinearLayout row;
        final TextView label;
        int value;

        ChoiceRow(LinearLayout row, TextView label) {
            this.row = row;
            this.label = label;
        }
    }

    static ChoiceRow choiceRow(Context c, LinearLayout card, Theme.Colors p,
                               String title, String summary, String[] options, int selected,
                               final OnChoice open) {
        LinearLayout row = rowShell(c);
        rowRipple(row, p);
        row.addView(twoLine(c, p, title, summary),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int idx = Math.max(0, Math.min(options.length - 1, selected));
        final TextView label = text(c, options[idx], Theme.T_ROW, p.summary, false);
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
        LinearLayout row = rowShell(c);
        rowRipple(row, p);
        row.addView(twoLine(c, p, title, summary),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addChevron(c, row, p);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        addRow(c, card, p, row);
        return row;
    }

    /** 带图标方块的操作行，用于"关于"页的源码 / 引用 / 许可。 */
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
            TextView end = text(c, endText, Theme.T_SUM, p.summary, false);
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

    /** 只读状态行：左侧标题，右侧结论。 */
    static LinearLayout statusRow(Context c, LinearLayout card, Theme.Colors p,
                                  String title, String value, int valueColor) {
        LinearLayout row = rowShell(c);
        row.setMinimumHeight(Theme.dp(c, 52));
        row.addView(title(c, p, title),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(c, value, Theme.T_BODY, valueColor, false);
        v.setGravity(Gravity.END);
        v.setLineSpacing(Theme.dp(c, 2), 1f);
        v.setMaxLines(2);
        v.setMaxWidth(Math.round(Theme.screenWidth(c) * 0.52f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Theme.dp(c, 12);
        row.addView(v, lp);
        addRow(c, card, p, row);
        return row;
    }

    /**
     * miuix BasicComponent：单行、minHeight 56dp、insideMargin 16dp，
     * 标题 17sp Medium 在左、值 14sp 摘要色在右（行间带分隔线）。
     * 长值（如作用域）会右对齐换行，始终保持"标签—值"的单行结构。
     */
    static LinearLayout infoRow(Context c, LinearLayout card, Theme.Colors p, String label, String value) {
        // 值过长（或本身多行）时改成「标题在上、值在下」，避免右对齐换行后错乱
        if (value != null && (value.indexOf('\n') >= 0 || value.length() > 24)) {
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setPadding(Theme.dp(c, 16), Theme.dp(c, 12), Theme.dp(c, 16), Theme.dp(c, 12));
            col.addView(text(c, label, Theme.T_ROW, p.title, Theme.medium()));
            TextView v = text(c, value, Theme.T_SUM, p.summary, false);
            v.setPadding(0, Theme.dp(c, 3), 0, 0);
            v.setLineSpacing(Theme.dp(c, 2), 1f);
            col.addView(v);
            addRow(c, card, p, col);
            return col;
        }
        LinearLayout row = rowShell(c);
        row.addView(text(c, label, Theme.T_ROW, p.title, Theme.medium()));
        TextView v = text(c, value, Theme.T_SUM, p.summary, false);
        v.setGravity(Gravity.END);
        v.setLineSpacing(Theme.dp(c, 2), 1f);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vlp.leftMargin = Theme.dp(c, 12);
        row.addView(v, vlp);
        addRow(c, card, p, row);
        return row;
    }

    /**
     * SonyPods InfoText：标签 17sp Medium（onSurface）在上、
     * 数值 14sp（onSurfaceVariantSummary）在下，条目间距 24dp、无分隔线。
     * 返回数值 TextView 以便后续就地刷新。
     */
    static TextView infoPair(Context c, LinearLayout card, Theme.Colors p, String label) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Theme.dp(c, 16), Theme.dp(c, 8), Theme.dp(c, 16), Theme.dp(c, 16));
        col.addView(text(c, label, Theme.T_ROW, p.title, true));
        TextView v = text(c, "", 14, p.summary, false);
        v.setPadding(0, Theme.dp(c, 2), 0, 0);
        v.setLineSpacing(Theme.dp(c, 2), 1f);
        col.addView(v);
        v.setTag(col);
        card.addView(col, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    /** 圆角图标底 + 线性图标。 */
    static FrameLayout iconTile(Context c, Theme.Colors p, int kind, int tint) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(Theme.round(Theme.alpha(tint, p.night ? 0.20f : 0.10f), Theme.dp(c, 10)));
        Icons.IconView icon = Icons.view(c, kind, 18, tint, 1.8f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        box.addView(icon, lp);
        return box;
    }

    /** 多行说明块。 */
    static TextView block(Context c, Theme.Colors p, String s) {
        TextView t = text(c, s, Theme.T_SUM, p.summary, false);
        t.setLineSpacing(Theme.dp(c, 4), 1f);
        t.setPadding(Theme.dp(c, 16), Theme.dp(c, 10), Theme.dp(c, 16), Theme.dp(c, 14));
        return t;
    }

    /** 卡片内的强调块（浅色底），用于校准提示这类信息。 */
    static TextView hintBlock(Context c, Theme.Colors p, String s, int accent) {
        TextView t = text(c, s, Theme.T_SUM, p.summary, false);
        t.setLineSpacing(Theme.dp(c, 3), 1f);
        t.setPadding(Theme.dp(c, 12), Theme.dp(c, 10), Theme.dp(c, 12), Theme.dp(c, 10));
        t.setBackground(Theme.round(Theme.alpha(accent, p.night ? 0.16f : 0.07f), Theme.dp(c, 10)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Theme.dp(c, 16), Theme.dp(c, 4), Theme.dp(c, 16), Theme.dp(c, 14));
        t.setLayoutParams(lp);
        return t;
    }

    // ---------------------------------------------------------------- 按钮

    /** 主按钮：primary 底 + onPrimary 文字，圆角 16dp、高 48dp。 */
    static TextView primaryButton(Context c, Theme.Colors p, String s) {
        TextView b = text(c, s, Theme.T_ROW, p.onPrimary, true);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                Theme.round(p.primary, Theme.dp(c, Theme.CARD_RADIUS)), null));
        return b;
    }

    /** 次按钮：surfaceContainerHighest 底。 */
    static TextView secondaryButton(Context c, Theme.Colors p, String s) {
        TextView b = text(c, s, Theme.T_ROW, p.onFill, true);
        b.setGravity(Gravity.CENTER);
        Drawable bg = Theme.round(p.fill, Theme.dp(c, Theme.CARD_RADIUS));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), bg, null));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    /** 胶囊按钮：页面标题右侧的小操作（刷新 / 保存）。 */
    static TextView capsuleButton(Context c, Theme.Colors p, String s, boolean primary) {
        TextView b = text(c, s, Theme.T_SUM, primary ? p.primary : p.onFill, true);
        b.setGravity(Gravity.CENTER);
        int padH = Theme.dp(c, 14);
        b.setPadding(padH, Theme.dp(c, 8), padH, Theme.dp(c, 8));
        Drawable bg = Theme.round(primary ? Theme.alpha(p.primary, p.night ? 0.22f : 0.10f) : p.fill,
                Theme.dp(c, 999));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), bg, null));
        b.setClickable(true);
        return b;
    }

    /** 图标按钮（圆形底，38dp 命中区）。 */
    static FrameLayout iconButton(Context c, Theme.Colors p, int kind, final Runnable action) {
        FrameLayout box = new FrameLayout(c);
        Drawable bg = Theme.round(p.fill, Theme.dp(c, 999));
        box.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), bg,
                Theme.round(Color.WHITE, Theme.dp(c, 999))));
        box.setClickable(true);
        Icons.IconView icon = Icons.view(c, kind, 19, p.title, 1.9f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        box.addView(icon, lp);
        int size = Theme.dp(c, 38);
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

    /** 无底色的图标按钮：SonyPods / KernelSU 标题栏右上角那种裸图标。 */
    static FrameLayout ghostIconButton(Context c, Theme.Colors p, int kind, final Runnable action) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), null,
                Theme.round(Color.WHITE, Theme.dp(c, 999))));
        box.setClickable(true);
        Icons.IconView icon = Icons.view(c, kind, 22, p.title, 1.9f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
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

    /** 按下时缩到 0.965 再弹回，替代 Miuix 的 tilt 反馈。 */
    static void pressScale(final View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        animateScale(view, 0.965f, 90);
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
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

        void setBottomInset(int px) {
            column.setPadding(column.getPaddingLeft(), 0, column.getPaddingRight(), px);
        }
    }

    static Page page(Context c, Theme.Colors p) {
        ScrollView scroll = new ScrollView(c);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
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
        private final int shadow = 0x26000000;
        /** 0..1 表示开关进度（位置与轨道色都用它）。 */
        private final GlassBar.Spring track = new GlassBar.Spring(0f);
        private final GlassBar.Spring press = new GlassBar.Spring(0f);
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

    /** 对话框外壳：圆角 24、去掉标题栏、透明窗口、居中限宽。 */
    /** miuix WindowDialog 规格：CornerRadius 32dp、insideMargin 24dp、outsideMargin 12dp、MaxWidth 420dp。 */
    private static LinearLayout dialogShell(Activity a, Dialog d, Theme.Colors p) {
        FrameLayout root = new FrameLayout(a);
        int pad = Theme.dp(a, 12);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.round(p.card, Theme.dp(a, 32)));
        box.setClipToOutline(true);
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                Math.min(screenW - 2 * pad, Theme.dp(a, 420)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.CENTER;
        root.addView(box, boxLp);

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int maxW = Theme.dp(a, 420) + 2 * pad;
            w.setLayout(Math.min(screenW, maxW), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.32f);
        }
        return box;
    }

    /**
     * miuix 按钮排布：竖向堆叠的全宽按钮（TextButton 规格
     * cornerRadius 16dp / minHeight 40dp / insideMargin 16×13），间距 10dp。
     * 取消 = secondaryVariant 灰底，确定 = primary 强调底。
     */
    private static void dialogButtons(Activity a, LinearLayout box, Theme.Colors p,
                                      TextView left, TextView right) {
        LinearLayout stack = new LinearLayout(a);
        stack.setOrientation(LinearLayout.VERTICAL);
        int m = Theme.dp(a, 24);
        int gap = Theme.dp(a, 10);
        stack.setPadding(m, Theme.dp(a, 8), m, m);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(a, 44));
        stack.addView(right, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(a, 44));
        lp2.topMargin = gap;
        stack.addView(left, lp2);
        box.addView(stack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 滑杆轨道：6dp 圆头、未选中用淡色，已选中用强调色。 */
    private static Drawable sliderTrack(Context c, Theme.Colors p) {
        int h = Theme.dp(c, 6);
        Squircle.D bg = Theme.round(p.night ? 0x33FFFFFF : 0x1A000000, h / 2f);
        bg.setSize(1, h);
        Squircle.D fg = Theme.round(p.primary, h / 2f);
        ClipDrawable clip = new ClipDrawable(fg, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable layer = new LayerDrawable(new Drawable[] {bg, clip});
        layer.setId(0, android.R.id.background);
        layer.setId(1, android.R.id.progress);
        layer.setLayerHeight(0, h);
        layer.setLayerHeight(1, h);
        return layer;
    }

    /** miuix 风格锚点弹出菜单（二级菜单）：20dp 圆角卡片 + 48dp 行。 */
    static void popupMenu(Context c, Theme.Colors p, View anchor, String[] items, final OnChoice cb) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackground(Theme.roundStroke(p.card, Theme.dp(c, 20),
                Theme.alpha(p.night ? 0xFFFFFFFF : 0xFF0B1220, 0.08f), Math.max(1, Theme.dp(c, 1))));
        int pad = Theme.dp(c, 6);
        col.setPadding(pad, pad, pad, pad);
        final android.widget.PopupWindow[] win = new android.widget.PopupWindow[1];
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView t = text(c, items[i], 15, p.title, false);
            t.setPadding(Theme.dp(c, 18), Theme.dp(c, 13), Theme.dp(c, 18), Theme.dp(c, 13));
            t.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), null,
                    new ColorDrawable(Color.TRANSPARENT)));
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (win[0] != null) {
                        win[0].dismiss();
                    }
                    cb.onChoice(idx);
                }
            });
            col.addView(t, new LinearLayout.LayoutParams(
                    Theme.dp(c, 216), ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        android.widget.PopupWindow w = new android.widget.PopupWindow(
                col, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        w.setElevation(Theme.dp(c, 12));
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setOutsideTouchable(true);
        win[0] = w;
        // 位置夹在屏幕内：之前固定 -168dp 偏移会让菜单在靠右的按钮下面溢出屏幕
        int menuW = Theme.dp(c, 228);
        int screenW = c.getResources().getDisplayMetrics().widthPixels;
        int margin = Theme.dp(c, 12);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int x = Math.min(loc[0] + anchor.getWidth() - menuW, screenW - menuW - margin);
        x = Math.max(margin, x);
        int y = loc[1] + anchor.getHeight() + Theme.dp(c, 6);
        w.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y);
    }

    /** miuix TextButton：16dp 圆角实底。color=p.primary 时为强调按钮（primary 底 + 白字）。 */
    private static TextView dialogTextButton(Activity a, Theme.Colors p, String s, int color, boolean bold) {
        boolean primary = color == p.primary;
        TextView t = text(a, s, Theme.T_ROW,
                primary ? (p.night ? 0xFF000000 : 0xFFFFFFFF) : color, bold);
        t.setGravity(Gravity.CENTER);
        int bg = primary ? p.primary : (p.night ? 0x14FFFFFF : 0x0F000000);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple),
                Theme.round(bg, Theme.dp(a, 16)), null));
        t.setClickable(true);
        return t;
    }

    /** Miuix 风格数字对话框：标题 / 大号数值 + 单位 / 滑杆 / 底部两个按钮。 */
    static void numberDialog(final Activity a, final Theme.Colors p, String title, final String unit,
                             final float min, final float max, final float step,
                             final int decimals, float current, final OnNumber cb) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = dialogShell(a, d, p);

        TextView t = text(a, title, 18, p.title, Theme.medium());
        t.setGravity(Gravity.CENTER);
        t.setPadding(Theme.dp(a, 24), Theme.dp(a, 24), Theme.dp(a, 24), Theme.dp(a, 12));
        box.addView(t);

        final TextView big = text(a, "", 30, p.title, true);
        big.setGravity(Gravity.CENTER);
        big.setPadding(Theme.dp(a, 16), Theme.dp(a, 8), Theme.dp(a, 16), 0);
        box.addView(big);

        TextView u = text(a, unit == null || unit.length() == 0 ? " " : unit, Theme.T_SUM, p.summary, false);
        u.setGravity(Gravity.CENTER);
        u.setPadding(Theme.dp(a, 16), Theme.dp(a, 2), Theme.dp(a, 16), Theme.dp(a, 6));
        box.addView(u);

        final int steps = Math.max(1, Math.round((max - min) / step));
        SeekBar sb = new SeekBar(a);
        sb.setMax(steps);
        sb.setProgress(Math.round((current - min) / step));
        sb.setSplitTrack(false);
        sb.setProgressDrawable(sliderTrack(a, p));
        // 圆钮：白色外圈 + 强调色内芯，放在白色卡片上也有轮廓
        int outer = Theme.dp(a, 24);
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.OVAL);
        ring.setColor(p.card);
        ring.setSize(outer, outer);
        GradientDrawable core = new GradientDrawable();
        core.setShape(GradientDrawable.OVAL);
        core.setColor(p.primary);
        core.setSize(outer, outer);
        LayerDrawable thumb = new LayerDrawable(new Drawable[] {ring, core});
        thumb.setLayerInset(1, Theme.dp(a, 4), Theme.dp(a, 4), Theme.dp(a, 4), Theme.dp(a, 4));
        sb.setThumb(thumb);
        sb.setThumbOffset(0);
        sb.setPadding(Theme.dp(a, 22), Theme.dp(a, 14), Theme.dp(a, 22), Theme.dp(a, 14));
        box.addView(sb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(a, 48)));

        final float[] result = {current};
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                result[0] = Math.min(max, min + progress * step);
                big.setText(fmt(result[0], decimals));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        result[0] = Math.min(max, min + sb.getProgress() * step);
        big.setText(fmt(result[0], decimals));

        TextView cancel = dialogTextButton(a, p, "取消", p.summary, false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        TextView ok = dialogTextButton(a, p, "确定", p.primary, true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onNumber(result[0]);
                d.dismiss();
            }
        });
        dialogButtons(a, box, p, cancel, ok);
        d.show();
    }

    /** 单选对话框：标题 + 选项列表（选中项高亮 + 勾）。 */
    static void singleChoiceDialog(final Activity a, final Theme.Colors p, String title, String summary,
                                   final String[] options, int selected, final OnChoice cb) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = dialogShell(a, d, p);

        TextView t = text(a, title, 18, p.title, Theme.medium());
        t.setGravity(Gravity.CENTER);
        t.setPadding(Theme.dp(a, 24), Theme.dp(a, 24), Theme.dp(a, 24), Theme.dp(a, 12));
        box.addView(t);

        if (summary != null && summary.length() > 0) {
            TextView s = text(a, summary, 14, p.summary, false);
            s.setGravity(Gravity.CENTER);
            s.setLineSpacing(Theme.dp(a, 2), 1f);
            s.setPadding(Theme.dp(a, 24), 0, Theme.dp(a, 24), Theme.dp(a, 12));
            box.addView(s);
        }

        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            LinearLayout row = rowShell(a);
            row.setMinimumHeight(Theme.dp(a, 52));
            row.setBackground(new RippleDrawable(ColorStateList.valueOf(p.ripple), null,
                    new ColorDrawable(Color.TRANSPARENT)));
            row.setClickable(true);
            TextView label = text(a, options[i], Theme.T_ROW,
                    i == selected ? p.primary : p.title, i == selected);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (i == selected) {
                row.addView(Icons.view(a, Icons.CHECK, 17, p.primary, 2f));
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cb.onChoice(idx);
                    d.dismiss();
                }
            });
            if (i > 0) {
                View div = new View(a);
                div.setBackgroundColor(p.divider);
                LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Theme.dp(a, 0.75f)));
                dlp.leftMargin = Theme.dp(a, 16);
                box.addView(div, dlp);
            }
            box.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView cancel = dialogTextButton(a, p, "取消", p.summary, false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        LinearLayout stack = new LinearLayout(a);
        stack.setOrientation(LinearLayout.VERTICAL);
        int m = Theme.dp(a, 24);
        stack.setPadding(m, Theme.dp(a, 8), m, m);
        stack.addView(cancel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(a, 44)));
        box.addView(stack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.show();
    }

    /** 确认对话框：用于重启作用域这类破坏性操作。 */
    static void confirmDialog(final Activity a, final Theme.Colors p, String title, String summary,
                              String okText, final OnConfirm cb) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = dialogShell(a, d, p);

        TextView t = text(a, title, 18, p.title, Theme.medium());
        t.setGravity(Gravity.CENTER);
        t.setPadding(Theme.dp(a, 24), Theme.dp(a, 24), Theme.dp(a, 24), Theme.dp(a, 12));
        box.addView(t);

        TextView s = text(a, summary, 14, p.summary, false);
        s.setGravity(Gravity.CENTER);
        s.setLineSpacing(Theme.dp(a, 3), 1f);
        s.setPadding(Theme.dp(a, 24), 0, Theme.dp(a, 24), Theme.dp(a, 16));
        box.addView(s);

        TextView cancel = dialogTextButton(a, p, "取消", p.summary, false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        TextView ok = dialogTextButton(a, p, okText, p.primary, true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
                cb.onConfirm();
            }
        });
        dialogButtons(a, box, p, cancel, ok);
        d.show();
    }

    /** 长文本对话框（引用 / 许可 / 使用说明），内容可滚动。 */
    static void infoDialog(final Activity a, final Theme.Colors p, String title, String body) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = dialogShell(a, d, p);

        TextView t = text(a, title, 18, p.title, Theme.medium());
        t.setGravity(Gravity.CENTER);
        t.setPadding(Theme.dp(a, 24), Theme.dp(a, 24), Theme.dp(a, 24), Theme.dp(a, 12));
        box.addView(t);

        ScrollView sc = new ScrollView(a);
        sc.setVerticalScrollBarEnabled(false);
        TextView bodyView = text(a, body, Theme.T_SUM, p.summary, false);
        bodyView.setLineSpacing(Theme.dp(a, 4), 1f);
        bodyView.setPadding(Theme.dp(a, 20), 0, Theme.dp(a, 20), Theme.dp(a, 16));
        sc.addView(bodyView);
        LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(Theme.dp(a, 340),
                (int) (a.getResources().getDisplayMetrics().heightPixels * 0.5f)));
        box.addView(sc, scLp);

        TextView ok = dialogTextButton(a, p, "知道了", p.primary, true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        LinearLayout stack = new LinearLayout(a);
        stack.setOrientation(LinearLayout.VERTICAL);
        int m = Theme.dp(a, 24);
        stack.setPadding(m, Theme.dp(a, 8), m, m);
        stack.addView(ok, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(a, 44)));
        box.addView(stack, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.show();
    }
}
