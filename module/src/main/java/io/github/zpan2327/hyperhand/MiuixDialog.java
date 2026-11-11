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
 * miuix {@code Dialog / SuperDialog / WindowDialog} —— 全应用唯一的弹窗组件。
 *
 * <pre>
 * WindowDialog  cornerRadius 28dp, insideMargin 20dp, outsideMargin 18dp, maxWidth 288dp
 * DialogLayout  enableWindowDim = true (0.32), enter/exit = fadeIn / fadeOut + 轻微缩放
 * 标题          17sp Medium；说明 13sp；正文 / 选项 15sp
 * 按钮          TextButton 40dp 高、14dp 圆角、间距 8dp，primary 在上、secondary 在下
 * </pre>
 *
 * <p>之前 maxWidth 420dp，在 360dp 宽的手机上几乎贴满两边（实际约 93% 屏宽），
 * 这一版统一收到 288dp（约 78%），并把内边距、行距、字号一起收紧。
 * 卡片底刻意用不透明色而不是 {@code pal.card}：演示模式（{@code --es bg grad}）
 * 下 {@code pal.card} 是半透明的，Dialog 会透出下层页面。
 */
final class MiuixDialog {

    interface OnClick {
        void onClick(MiuixDialog d);
    }

    private static final int MAX_WIDTH_DP = 288;
    private static final int OUTSIDE_DP = 18;
    private static final int PAD_DP = 20;
    private static final int CORNER_DP = 28;

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
            w.setDimAmount(0.32f);   // DialogLayout.enableWindowDim = true
        }
        dialog.setCanceledOnTouchOutside(true);
        dialog.setCancelable(true);
    }

    /** 标题：17sp Medium，居中，上方 20dp。 */
    MiuixDialog title(String s) {
        TextView t = Ui.text(a, s, 17, p.title, Theme.medium());
        t.setGravity(Gravity.CENTER);
        t.setPadding(pad(a), Theme.dp(a, 20), pad(a), Theme.dp(a, 8));
        box.addView(t);
        return this;
    }

    /** 说明文字：13sp，居中，行距 2dp。 */
    MiuixDialog summary(String s) {
        if (s == null || s.length() == 0) {
            return this;
        }
        TextView t = Ui.text(a, s, 13, p.summary, Theme.regular());
        t.setGravity(Gravity.CENTER);
        t.setLineSpacing(Theme.dp(a, 2), 1f);
        t.setPadding(pad(a), 0, pad(a), Theme.dp(a, 10));
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

    /** 单个按钮。多个按钮按调用顺序自上而下排列。 */
    MiuixDialog button(String label, boolean primary, final OnClick cb) {
        if (buttonStack == null) {
            buttonStack = new LinearLayout(a);
            buttonStack.setOrientation(LinearLayout.VERTICAL);
            int m = pad(a);
            buttonStack.setPadding(m, Theme.dp(a, 4), m, m);
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

    /** 常用的「primary 在上 / secondary 在下」双按钮。 */
    MiuixDialog buttons(String positive, final OnClick onPositive,
                        String negative, final OnClick onNegative) {
        if (positive != null) {
            button(positive, true, onPositive);
        }
        if (negative != null) {
            button(negative, false, onNegative);
        }
        return this;
    }

    private TextView textButton(String label, boolean primary) {
        TextView t = Ui.text(a, label, 15,
                primary ? p.onPrimary : p.onFill, Theme.medium());
        t.setGravity(Gravity.CENTER);
        int bg = primary ? p.primary : p.fill;
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(
                        primary ? 0x33FFFFFF : (p.night ? 0x14FFFFFF : 0x0D000000)),
                Theme.round(bg, Theme.dp(a, 14)), null));
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
