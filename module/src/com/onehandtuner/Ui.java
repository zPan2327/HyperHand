package com.onehandtuner;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * 一套按 Miuix（HyperOS 设计语言，top.yukonga.miuix.kmp）取值手写的原生 View 组件。
 *
 * 取色与尺寸来源：miuix-ui 的 Colors.kt / TextStyles.kt / Component.kt / SmallTitle.kt /
 * Card.kt / Button.kt / Switch.kt / Divider.kt，见 docs/设计稿对照.md。
 */
final class Ui {

    private Ui() {
    }

    /** Miuix 的浅色 / 深色 token 子集。 */
    static final class Pal {
        final int bg;          // colorScheme.surface：页面底色
        final int card;        // colorScheme.surfaceContainer：卡片
        final int title;       // onSurfaceSecondary  #CC000000
        final int summary;     // onSurfaceVariantSummary #99000000
        final int section;     // onBackgroundVariant #8C93B0
        final int divider;     // dividerLine #E0E0E0
        final int primary;     // #3482FF
        final int onPrimary;
        final int fill;        // secondaryVariant #F0F0F0
        final int onFill;      // onSecondaryVariant #303030
        final int trackOff;    // 开关关闭态
        final int chevron;

        Pal(int bg, int card, int title, int summary, int section, int divider,
            int primary, int onPrimary, int fill, int onFill, int trackOff, int chevron) {
            this.bg = bg;
            this.card = card;
            this.title = title;
            this.summary = summary;
            this.section = section;
            this.divider = divider;
            this.primary = primary;
            this.onPrimary = onPrimary;
            this.fill = fill;
            this.onFill = onFill;
            this.trackOff = trackOff;
            this.chevron = chevron;
        }
    }

    static boolean isNight(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    static Pal pal(Context c) {
        if (isNight(c)) {
            return new Pal(0xFF000000, 0xFF242424, 0xCCFFFFFF, 0x80FFFFFF, 0xFF787E96,
                    0xFF393939, 0xFF277AF7, 0xFFFFFFFF, 0xFF434343, 0xFFD9D9D9,
                    0xFF505050, 0x66FFFFFF);
        }
        return new Pal(0xFFF7F7F7, 0xFFFFFFFF, 0xCC000000, 0x99000000, 0xFF8C93B0,
                0xFFE0E0E0, 0xFF3482FF, 0xFFFFFFFF, 0xFFF0F0F0, 0xFF303030,
                0xFFE6E6E6, 0x66000000);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static void ripple(View v, int color) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(color), null, mask));
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(medium
                ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.create("sans-serif", Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 段落标题：Miuix SmallTitle，subtitle 14sp Bold / onBackgroundVariant。 */
    static TextView sectionTitle(Context c, Pal pal, String s) {
        TextView t = text(c, s, 14, pal.section, false);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(c, 14), dp(c, 14), dp(c, 28), dp(c, 6));
        return t;
    }

    /** 卡片：Card，cornerRadius 16dp，颜色 surfaceContainer，无阴影。 */
    static LinearLayout card(Context c, Pal pal) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(pal.card, dp(c, 16)));
        card.setClipToOutline(true);
        return card;
    }

    /** 往卡片里塞一行，自动在行与行之间画 0.75dp 分隔线（左侧内缩 16dp）。 */
    static void addRow(Context c, LinearLayout card, Pal pal, View row) {
        if (card.getChildCount() > 0) {
            View d = new View(c);
            d.setBackgroundColor(pal.divider);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.75f)));
            dlp.leftMargin = dp(c, 16);
            card.addView(d, dlp);
        }
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private static LinearLayout rowShell(Context c) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 56));
        row.setPadding(dp(c, 16), dp(c, 9), dp(c, 16), dp(c, 9));
        return row;
    }

    private static LinearLayout twoLine(Context c, Pal pal, String title, String summary) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(text(c, title, 17, pal.title, true));
        if (summary != null && summary.length() > 0) {
            TextView s = text(c, summary, 14, pal.summary, false);
            s.setPadding(0, dp(c, 2), 0, 0);
            col.addView(s);
        }
        return col;
    }

    /** 行尾的值文本，尺寸对齐正文 17sp。 */
    static TextView valueLabel(Context c, Pal pal, String s) {
        TextView t = text(c, s, 17, pal.summary, false);
        t.setGravity(Gravity.END);
        return t;
    }

    /** 开关行：整行可点，点击即切换（对齐 SwitchPreference 的交互）。 */
    static SwitchView switchRow(Context c, LinearLayout card, Pal pal,
                                String title, String summary, boolean checked) {
        LinearLayout row = rowShell(c);
        ripple(row, isNight(c) ? 0x14FFFFFF : 0x0D000000);
        row.addView(twoLine(c, pal, title, summary),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final SwitchView sw = new SwitchView(c, pal);
        sw.setCheckedNoAnim(checked);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = dp(c, 12);
        row.addView(sw, slp);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        addRow(c, card, pal, row);
        return sw;
    }

    /** 数值行：右侧显示当前值，点击弹出滑杆对话框。 */
    static final class ValueRow {
        final LinearLayout row;
        final TextView label;
        float value;

        ValueRow(LinearLayout row, TextView label) {
            this.row = row;
            this.label = label;
        }
    }

    static ValueRow valueRow(Context c, LinearLayout card, Pal pal, String title, String summary,
                             float value, int decimals) {
        final LinearLayout row = rowShell(c);
        ripple(row, isNight(c) ? 0x14FFFFFF : 0x0D000000);
        row.addView(twoLine(c, pal, title, summary),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView label = valueLabel(c, pal, fmt(value, decimals));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = dp(c, 12);
        row.addView(label, llp);
        final ValueRow vr = new ValueRow(row, label);
        vr.value = value;
        addRow(c, card, pal, row);
        return vr;
    }

    static String fmt(float v, int decimals) {
        if (decimals <= 0) {
            return String.valueOf(Math.round(v));
        }
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    /** 主按钮：primary 底 + onPrimary 文字，圆角 16dp、高 48dp。 */
    static TextView primaryButton(Context c, Pal pal, String s) {
        TextView b = text(c, s, 17, pal.onPrimary, true);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        b.setFocusable(true);
        int rippleColor = 0x33FFFFFF;
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor),
                round(pal.primary, dp(c, 16)), null));
        return b;
    }

    /** 次按钮：secondaryVariant 底 + onSecondaryVariant 文字。 */
    static TextView secondaryButton(Context c, Pal pal, String s) {
        TextView b = text(c, s, 17, pal.onFill, true);
        b.setGravity(Gravity.CENTER);
        int rippleColor = isNight(c) ? 0x1FFFFFFF : 0x14000000;
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor),
                round(pal.fill, dp(c, 16)), null));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    // ---------------------------------------------------------------- switch

    /**
     * Miuix Switch：49x28dp 轨道，20dp 圆钮，关闭时左侧内缩 4dp、打开时 25dp。
     */
    static final class SwitchView extends View {
        interface OnCheckedChange {
            void onCheckedChanged(boolean checked);
        }

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final int colorOn;
        private final int colorOff;
        private final int thumbColor = Color.WHITE;
        private final int shadow = 0x1F000000;
        private float anim;
        private boolean checked;
        private OnCheckedChange listener;
        private android.animation.ValueAnimator animator;

        SwitchView(Context c, Pal pal) {
            super(c);
            this.colorOn = pal.primary;
            this.colorOff = pal.trackOff;
            setClickable(true);
            setFocusable(true);
            super.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggle();
                }
            });
        }

        void setCheckedNoAnim(boolean v) {
            checked = v;
            anim = v ? 1f : 0f;
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
                if (animator != null) {
                    animator.cancel();
                }
                animator = android.animation.ValueAnimator.ofFloat(anim, v ? 1f : 0f);
                animator.setDuration(180);
                animator.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    @Override
                    public void onAnimationUpdate(android.animation.ValueAnimator a) {
                        anim = (float) a.getAnimatedValue();
                        invalidate();
                    }
                });
                animator.start();
            } else {
                anim = v ? 1f : 0f;
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
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(getContext(), 49), dp(getContext(), 28));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            int track = blend(colorOff, colorOn, anim);
            rect.set(0, 0, w, h);
            paint.setColor(track);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);

            float d = dp(getContext(), 20);
            float left = dp(getContext(), 4) + anim * (w - dp(getContext(), 8) - d);
            float cy = h / 2f;
            paint.setColor(shadow);
            canvas.drawCircle(left + d / 2f, cy + dp(getContext(), 1), d / 2f, paint);
            paint.setColor(thumbColor);
            canvas.drawCircle(left + d / 2f, cy, d / 2f, paint);
        }
    }

    private static int blend(int from, int to, float t) {
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    // ---------------------------------------------------------------- dialog

    interface OnNumber {
        void onNumber(float v);
    }

    /**
     * Miuix 风格数字对话框：标题 / 大号数值 + 单位 / 滑杆 / 底部两个按钮。
     */
    static void numberDialog(final Activity a, final Pal pal, String title, String unit,
                             final float min, final float max, final float step,
                             final int decimals, float current, final OnNumber cb) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(a);
        int pad = dp(a, 28);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(pal.card, dp(a, 20)));
        box.setClipToOutline(true);
        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                Math.min(screenW - 2 * pad, dp(a, 336)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.CENTER;
        root.addView(box, boxLp);

        TextView t = text(a, title, 17, pal.title, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(a, 16), dp(a, 20), dp(a, 16), dp(a, 2));
        box.addView(t);

        final TextView big = text(a, "", 32, pal.title, true);
        big.setGravity(Gravity.CENTER);
        big.setPadding(dp(a, 16), dp(a, 6), dp(a, 16), 0);
        box.addView(big);

        TextView u = text(a, unit, 13, pal.summary, false);
        u.setGravity(Gravity.CENTER);
        u.setPadding(dp(a, 16), dp(a, 2), dp(a, 16), dp(a, 4));
        box.addView(u);

        final int steps = Math.max(1, Math.round((max - min) / step));
        final SeekBar sb = new SeekBar(a);
        sb.setMax(steps);
        sb.setProgress(Math.round((current - min) / step));
        sb.setProgressTintList(ColorStateList.valueOf(pal.primary));
        sb.setThumbTintList(ColorStateList.valueOf(pal.primary));
        sb.setProgressBackgroundTintList(ColorStateList.valueOf(isNight(a) ? 0x33FFFFFF : 0x1F000000));
        sb.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 16));
        box.addView(sb, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

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

        View div = new View(a);
        div.setBackgroundColor(pal.divider);
        box.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout btns = new LinearLayout(a);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setMinimumHeight(dp(a, 52));

        TextView cancel = text(a, "取消", 17, pal.summary, false);
        cancel.setGravity(Gravity.CENTER);
        ripple(cancel, isNight(a) ? 0x1FFFFFFF : 0x0D000000);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        btns.addView(cancel, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        View vdiv = new View(a);
        vdiv.setBackgroundColor(pal.divider);
        btns.addView(vdiv, new LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView ok = text(a, "确定", 17, pal.primary, true);
        ok.setGravity(Gravity.CENTER);
        ripple(ok, isNight(a) ? 0x1FFFFFFF : 0x0D000000);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cb.onNumber(result[0]);
                d.dismiss();
            }
        });
        btns.addView(ok, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        box.addView(btns, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 52)));

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int screen = a.getResources().getDisplayMetrics().widthPixels;
            int maxW = dp(a, 340) + 2 * pad;
            w.setLayout(Math.min(screen, maxW), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(0.32f);
        }
        d.show();
    }
}
