package io.github.zpan2327.hyperhand;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * miuix {@code OverlayDialog} —— 全应用唯一的弹窗组件。
 *
 * <p>规格 1:1 对齐 miuix {@code DialogContentLayout.kt} 的 {@code DialogDefaults}
 * 与 {@code TextButton}/{@code ButtonDefaults}（SonyPods 全部对话窗口走的同一条）：
 *
 * <pre>
 * 外观          maxWidth 420dp、圆角 32dp、外边距 12dp、内边距 24dp
 * 标题          title4 = 18sp / Medium，居中，下边距 12dp
 * 说明          body1 = 16sp，居中，下边距 12dp
 * 按钮          TextButton 40dp 高、16dp 圆角、MinWidth 58dp、文字 button = 17sp；
 *               双按钮横排（左 secondary / 右 primary），间距 8dp —— 与
 *               SonyPods PowerOffDialog / PodImageConfigDialog 的 Row 布局一致
 * 蒙层          windowDimming = 黑 α0.3（浅色）/ α0.6（深色）
 * </pre>
 *
 * <p>卡片底刻意用不透明色而不是 {@code pal.card}：演示模式（{@code --es bg grad}）
 * 下 {@code pal.card} 是半透明的，Dialog 会透出下层页面。
 */
final class MiuixDialog {

    interface OnClick {
        void onClick(MiuixDialog d);
    }

    private static final int MAX_WIDTH_DP = 420;
    private static final int OUTSIDE_DP = 12;
    private static final int PAD_DP = 24;
    private static final int CORNER_DP = 32;

    /** 对话框内统一的内容左右内边距，供 Ui 里各 dialog 主体复用。 */
    static int pad(android.content.Context c) {
        return Theme.dp(c, PAD_DP);
    }

    private final Activity a;
    private final Theme.Colors p;
    private final Dialog dialog;
    private final LinearLayout box;
    private LinearLayout buttonStack;

    MiuixDialog(Activity a, Theme.Colors p) {
        this.a = a;
        this.p = p;
        dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        int outside = Theme.dp(a, OUTSIDE_DP);
        FrameLayout root = new FrameLayout(a);
        root.setPadding(outside, outside, outside, outside);

        box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        // 不透明，避免 demo 模式下透出下层页面
        int surface = p.night ? 0xFF242424 : 0xFFFFFFFF;
        box.setBackground(Theme.round(surface, Theme.dp(a, CORNER_DP)));
        box.setClipToOutline(true);

        int screenW = a.getResources().getDisplayMetrics().widthPixels;
        int maxW = Math.min(Theme.dp(a, MAX_WIDTH_DP), screenW - 2 * outside);
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                maxW, ViewGroup.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.CENTER;
        root.addView(box, boxLp);

        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(screenW, maxW + 2 * outside),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            // miuix windowDimming：浅色黑 α0.3 / 深色黑 α0.6
            w.setDimAmount(p.night ? 0.6f : 0.3f);
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.setCancelable(true);
    }

    /** 标题：title4 = 18sp Medium，居中，上 24dp（insideMargin）、下 12dp。 */
    MiuixDialog title(String s) {
        TextView t = Ui.text(a, s, 18, p.title, Theme.medium());
        t.setGravity(Gravity.CENTER);
        t.setPadding(pad(a), pad(a), pad(a), Theme.dp(a, 12));
        box.addView(t);
        return this;
    }

    /** 说明文字：body1 = 16sp，居中，下边距 12dp。 */
    MiuixDialog summary(String s) {
        if (s == null || s.length() == 0) {
            return this;
        }
        TextView t = Ui.text(a, s, 16, p.summary, Theme.regular());
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(Theme.dp(a, 2), 1f);
        t.setPadding(pad(a), 0, pad(a), Theme.dp(a, 12));
        box.addView(t);
        return this;
    }

    /** 对话框主体（任意 miuix 组件，例如 Slider / Checkbox 列表 / 长文本）。 */
    MiuixDialog content(View v) {
        box.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return this;
    }

    /** 需要固定高度的主体（隐藏调试页那种可滚动内容）。 */
    MiuixDialog content(View v, int heightPx) {
        box.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
        return this;
    }

    /** 单个按钮（全宽）。多个按钮按调用顺序自上而下排列。 */
    MiuixDialog button(String label, boolean primary, final OnClick cb) {
        if (buttonStack == null) {
            buttonStack = new LinearLayout(a);
            buttonStack.setOrientation(LinearLayout.VERTICAL);
            int m = pad(a);
            buttonStack.setPadding(m, Theme.dp(a, 12), m, m);
            box.addView(buttonStack, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        final TextView b = textButton(label, primary);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(a, 40));
        if (buttonStack.getChildCount() > 0) {
            lp.topMargin = Theme.dp(a, 8);
        }
        buttonStack.addView(b, lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (cb != null) {
                    cb.onClick(MiuixDialog.this);
                }
            }
        });
        return this;
    }

    /**
     * 常用的双按钮：横排等宽（左负 / 右正），间距 8dp ——
     * 与 SonyPods 的 {@code Row(Arrangement.spacedBy(8.dp))} 一致。
     */
    MiuixDialog buttons(String positive, final OnClick onPositive,
                        String negative, final OnClick onNegative) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        int m = pad(a);
        row.setPadding(m, Theme.dp(a, 12), m, m);

        View.OnClickListener posClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onPositive != null) {
                    onPositive.onClick(MiuixDialog.this);
                }
            }
        };
        View.OnClickListener negClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onNegative != null) {
                    onNegative.onClick(MiuixDialog.this);
                }
            }
        };

        if (negative != null) {
            TextView b = textButton(negative, false);
            b.setOnClickListener(negClick);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, Theme.dp(a, 40), 1f);
            row.addView(b, lp);
        }
        if (positive != null) {
            TextView b = textButton(positive, true);
            b.setOnClickListener(posClick);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, Theme.dp(a, 40), 1f);
            if (negative != null) {
                lp.leftMargin = Theme.dp(a, 8);
            }
            row.addView(b, lp);
        }
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return this;
    }

    private TextView textButton(String label, boolean primary) {
        TextView t = Ui.text(a, label, 17,
                primary ? p.onPrimary : p.onFill, Theme.medium());
        t.setGravity(Gravity.CENTER);
        int bg = primary ? p.primary : p.fill;
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(
                        primary ? 0x33FFFFFF : (p.night ? 0x14FFFFFF : 0x0D000000)),
                Theme.round(bg, Theme.dp(a, 16)), null));
        t.setClickable(true);
        t.setFocusable(true);
        Ui.tapFeedback(t, false);
        return t;
    }

    void show() {
        dialog.show();
        // DialogLayout 的 enterTransition：fadeIn + 轻微放大
        box.setAlpha(0f);
        box.setScaleX(0.94f);
        box.setScaleY(0.94f);
        box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start();
    }

    void dismiss() {
        box.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(140)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            dialog.dismiss();
                        } catch (Throwable ignored) {
                            // Activity 已经结束时忽略
                        }
                    }
                }).start();
    }

    Dialog raw() {
        return dialog;
    }
}
