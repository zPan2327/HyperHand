package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/**
 * HyperHand 的视觉 token 层。
 *
 * 取色与字号来自 Miuix（HyperOS 设计语言）的 Colors.kt / TextStyles.kt；
 * 页面节奏（12dp 页边距、16dp 卡片圆角、56dp 行高、8dp 段落间距）来自
 * KernelSU 的 Miuix 页面与 SonyPods 的首页/关于页。
 *
 * 这里只放"值"，不放控件 —— 控件在 {@link Ui}，图标在 {@link Icons}，
 * 悬浮底栏在 {@link GlassBar}。所有页面必须只从这里取值，避免新旧两套观感并存。
 */
final class Theme {

    private Theme() {
    }

    /** 一套完整的语义色。浅色 / 深色各一份，跟随系统 uiMode。 */
    static final class Colors {
        boolean night;

        // 基础层
        int bg;             // surface：页面底色
        int card;           // surfaceContainer：卡片
        int cardHigh;       // surfaceContainerHighest：卡片内的次级块 / 图标底
        int title;          // onSurfaceSecondary：行标题
        int summary;        // onSurfaceVariantSummary：行摘要
        int section;        // onBackgroundVariant：段落标题
        int divider;        // dividerLine：分隔线
        int outline;        // outline：描边
        int chevron;        // onSurfaceVariantActions：箭头

        // 强调
        int primary;        // primary
        int onPrimary;
        int primarySoft;    // tertiaryContainer：强调色底
        int onPrimarySoft;  // onTertiaryContainer：强调色文字

        // 次级按钮
        int fill;
        int onFill;

        // 状态
        int successBg;
        int onSuccess;
        int success;

        // 玻璃
        int glassTint;      // 底栏本体叠加色
        int glassBorder;    // 边缘高光
        int glassShadow;    // 投影
        int glassInner;     // 内阴影（按下时加深）

        // 页面顶部氛围渐变
        int washTop;
        int scrim;

        int ripple;         // 行按压蒙版
    }

    static boolean isNight(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    static Colors colors(Context c) {
        Colors p = new Colors();
        p.night = isNight(c);
        if (p.night) {
            p.bg = 0xFF000000;
            p.card = 0xFF242424;
            p.cardHigh = 0xFF2D2D2D;
            p.title = 0xE6FFFFFF;
            p.summary = 0x80FFFFFF;
            p.section = 0xFF8B90A6;
            p.divider = 0xFF393939;
            p.outline = 0xFF404040;
            p.chevron = 0x59FFFFFF;

            p.primary = 0xFF277AF7;
            p.onPrimary = 0xFFFFFFFF;
            p.primarySoft = 0xFF16304F;
            p.onPrimarySoft = 0xFF7FB4FF;

            p.fill = 0xFF434343;
            p.onFill = 0xFFD9D9D9;

            p.successBg = 0xFF16301F;
            p.onSuccess = 0xFFB8E8C5;
            p.success = 0xFF5BD07E;

            p.glassTint = 0x8C1C1C1E;
            p.glassBorder = 0x24FFFFFF;
            p.glassShadow = 0x66000000;
            p.glassInner = 0x1FFFFFFF;

            p.washTop = 0x1F3E7BFF;
            p.scrim = 0xFF000000;
            p.ripple = 0x1FFFFFFF;
        } else {
            p.bg = 0xFFF7F7F7;
            p.card = 0xFFFFFFFF;
            p.cardHigh = 0xFFF2F3F5;
            p.title = 0xE6000000;
            p.summary = 0x99000000;
            p.section = 0xFF8C93B0;
            p.divider = 0xFFE9E9E9;
            p.outline = 0xFFD9D9D9;
            p.chevron = 0x59000000;

            p.primary = 0xFF3482FF;
            p.onPrimary = 0xFFFFFFFF;
            p.primarySoft = 0xFFEAF2FF;
            p.onPrimarySoft = 0xFF2C6FD8;

            p.fill = 0xFFF0F0F0;
            p.onFill = 0xFF303030;

            p.successBg = 0xFFE4F8E9;
            p.onSuccess = 0xFF1E7B41;
            p.success = 0xFF36D167;

            p.glassTint = 0xA0FFFFFF;
            p.glassBorder = 0xB3FFFFFF;
            p.glassShadow = 0x1F000000;
            p.glassInner = 0x14000000;

            p.washTop = 0x143482FF;
            p.scrim = 0xFFFFFFFF;
            p.ripple = 0x0D000000;
        }
        return p;
    }

    // ---------------------------------------------------------------- 尺寸

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 屏幕宽度，用于自适应（平板/折叠屏时把内容限宽居中）。 */
    static int screenWidth(Context c) {
        return c.getResources().getDisplayMetrics().widthPixels;
    }

    /** 内容最大宽度：大于 600dp 的窗口限宽，避免卡片被拉成一条长条。 */
    static int contentMaxWidth(Context c) {
        float w = screenWidth(c) / c.getResources().getDisplayMetrics().density;
        return w >= 600 ? dp(c, 560) : Integer.MAX_VALUE;
    }

    // 页面节奏（dp）—— 全部对齐 miuix 组件默认值：
    // CardDefaults.CornerRadius = 16dp、BasicComponentDefaults.InsideMargin = 16dp、
    // BasicComponent minHeight = 56dp、SmallTitleDefaults.InsideMargin = (28, 8)
    static final int PAGE = 12;        // 页边距
    static final int CARD_GAP = 12;    // 卡片间距
    static final int CARD_RADIUS = 16;
    static final int DIALOG_RADIUS = 32;
    static final int ROW_MIN_H = 56;
    static final int ROW_PAD_H = 16;
    static final int SECTION_TOP = 20;
    static final int SECTION_BOTTOM = 6;

    // 字号（sp）—— 逐条对应 miuix TextStyles.kt
    static final float T_DISPLAY = 32;   // Miuix title1
    static final float T_TITLE = 24;     // Miuix title2
    static final float T_HERO = 20;      // Miuix title3
    static final float T_STAT = 24;      // 数值大字
    static final float T_ROW = 17;       // Miuix headline1 / main
    static final float T_BODY = 16;      // Miuix body1
    static final float T_SUM = 14;       // Miuix body2
    static final float T_SECTION = 14;   // Miuix subtitle（14sp Bold）
    static final float T_CAP = 13;       // Miuix footnote1
    static final float T_LABEL = 13;     // Miuix footnote1
    static final float T_TAB = 11;       // Miuix footnote2

    // ---------------------------------------------------------------- 绘制工具

    private static final Typeface BASE = Typeface.create("sans-serif", Typeface.NORMAL);

    static Typeface regular() {
        return BASE;
    }

    /** Miuix 里 Medium（500）：行标题、小标签。 */
    static Typeface medium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    /** SemiBold（600）：卡片数值、英雄标题（SonyPods StatCard/StatusCard 的字重）。 */
    static Typeface semibold() {
        return Typeface.create(BASE, 600, false);
    }

    /** Bold（700）：段落标题（Miuix Subtitle = 14sp Bold）。 */
    static Typeface bold() {
        return Typeface.create(BASE, 700, false);
    }

    static int alpha(int color, float a) {
        int al = Math.round(Color.alpha(color) * clamp01(a));
        return (al << 24) | (color & 0x00FFFFFF);
    }

    static int blend(int from, int to, float t) {
        t = clamp01(t);
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * t);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * t);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * t);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * t);
        return Color.argb(a, r, g, b);
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** squircle 圆角（miuix 连续曲率），替代 GradientDrawable 的普通圆角。 */
    static Squircle.D round(int color, float radius) {
        return new Squircle.D(color, radius);
    }

    /**
     * 页面底：极淡的冷色渐变（上浅下深一点）。
     * 纯平的白底会让上面的液态玻璃没有颜色可透，看起来就是一条白胶带；
     * 这里给一层几乎察觉不到的氛围底，玻璃才立得住。
     */
    static Drawable pageBackground(Colors p) {
        int top = p.night ? 0xFF07080C : 0xFFF1F5FD;
        int bottom = p.night ? 0xFF101319 : 0xFFE9EEF9;
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        return d;
    }

    static Squircle.D roundStroke(int color, float radius, int strokeColor, int strokeWidth) {
        return new Squircle.D(color, radius).stroke(strokeColor, strokeWidth);
    }

    /** 无阴影的柔和投影（硬件层也能画）：多层递减透明度的圆角矩形。 */
    static void softShadow(Canvas canvas, RectF rect, float radius, float spread, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int layers = 6;
        for (int i = layers; i >= 1; i--) {
            float t = i / (float) layers;
            float grow = spread * t;
            p.setColor(alpha(color, (1f - t) * 0.9f / layers * 2.2f));
            RectF r = new RectF(rect.left - grow, rect.top - grow + spread * 0.35f,
                    rect.right + grow, rect.bottom + grow + spread * 0.35f);
            Path path = new Path();
            Squircle.rect(path, r, radius + grow);
            canvas.drawPath(path, p);
        }
    }
}
