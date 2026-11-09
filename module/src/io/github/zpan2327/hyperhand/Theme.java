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

/**
 * 视觉 token 层：颜色、字号、字重、间距。
 *
 * 全部取值逐条对应 compose-miuix-ui/miuix 的
 * {@code theme/Colors.kt}（lightColorScheme / darkColorScheme）与
 * {@code theme/TextStyles.kt}，以及各组件的 Defaults：
 *
 * <pre>
 * CardDefaults.CornerRadius            = 16dp   InsideMargin 0
 * BasicComponentDefaults.InsideMargin  = 16dp   minHeight 56dp
 * SmallTitleDefaults.InsideMargin      = (28, 8)   subtitle = 14sp Bold
 * TopAppBarDefaults.TitlePadding       = 26dp   CollapsedHeight 52dp
 * Switch: 49 x 28dp，圆钮 20dp（关 4 / 开 25）
 * DividerDefaults.Thickness            = 0.75dp
 * </pre>
 *
 * 页面节奏（12dp 页边距、12dp 卡片间距）取自 SonyPods 的 LazyColumn
 * contentPadding 与 verticalArrangement.spacedBy。
 *
 * 这里只放"值"，不放控件 —— 控件在 {@link Ui}，折叠标题栏在 {@link TopBar}，
 * 悬浮底栏在 {@link GlassBar}。
 */
final class Theme {

    private Theme() {
    }

    /** 一套完整的语义色。浅色 / 深色各一份，跟随系统 uiMode。 */
    static final class Colors {
        boolean night;

        // 基础层
        int bg;             // background：页面底色（#F7F7F7）
        int card;           // surfaceContainer：卡片
        int cardHigh;       // secondaryVariant：卡片内的次级块
        int title;          // onSurface
        int summary;        // onSurfaceVariantSummary
        int section;        // onBackgroundVariant
        int divider;        // dividerLine
        int outline;        // outline
        int chevron;        // onSurfaceVariantActions

        // 强调
        int primary;
        int onPrimary;
        int primarySoft;    // tertiaryContainer
        int onPrimarySoft;  // onTertiaryContainer

        // 次级按钮
        int fill;           // secondaryVariant
        int onFill;         // onSecondaryVariant

        // 状态
        int successBg;
        int onSuccess;
        int success;
        int warnBg;
        int onWarn;

        // 玻璃
        int glassTint;
        int glassPill;
        int glassBorder;
        int glassShadow;

        // 顶部标题栏
        int barBg;
        int barLine;
    }

    static boolean isNight(Context c) {
        int mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    static Colors colors(Context c) {
        Colors p = new Colors();
        p.night = isNight(c);
        if (p.night) {
            // darkColorScheme()
            p.bg = 0xFF000000;
            p.card = 0xFF242424;
            p.cardHigh = 0xFF2D2D2D;
            p.title = 0xFFF2F2F2;
            p.summary = 0x80FFFFFF;
            p.section = 0xFF787E96;
            p.divider = 0xFF393939;
            p.outline = 0xFF404040;
            p.chevron = 0x66FFFFFF;

            p.primary = 0xFF277AF7;
            p.onPrimary = 0xFFFFFFFF;
            p.primarySoft = 0xFF2B3B54;
            p.onPrimarySoft = 0xFF4788FF;

            p.fill = 0xFF434343;
            p.onFill = 0xFFD9D9D9;

            p.successBg = 0xFF16301F;
            p.onSuccess = 0xFFB8E8C5;
            p.success = 0xFF5BD07E;
            p.warnBg = 0xFF2B3B54;
            p.onWarn = 0xFF7FB4FF;

            p.glassTint = 0x3DFFFFFF;
            p.glassPill = 0x1FFFFFFF;
            p.glassBorder = 0x33FFFFFF;
            p.glassShadow = 0x66000000;

            p.barBg = 0xE6000000;
            p.barLine = 0x1FFFFFFF;
        } else {
            // lightColorScheme()
            p.bg = 0xFFF7F7F7;
            p.card = 0xFFFFFFFF;
            p.cardHigh = 0xFFF0F0F0;
            p.title = 0xFF000000;
            p.summary = 0x99000000;
            p.section = 0xFF8C93B0;
            p.divider = 0xFFE0E0E0;
            p.outline = 0xFFD9D9D9;
            p.chevron = 0x66000000;

            p.primary = 0xFF3482FF;
            p.onPrimary = 0xFFFFFFFF;
            p.primarySoft = 0xFFEAF2FF;
            p.onPrimarySoft = 0xFF3482FF;

            p.fill = 0xFFF0F0F0;
            p.onFill = 0xFF303030;

            p.successBg = 0xFFDFFAE4;
            p.onSuccess = 0xFF1E7B41;
            p.success = 0xFF36D167;
            p.warnBg = 0xFFEAF2FF;
            p.onWarn = 0xFF2C6FD8;

            p.glassTint = 0x6BFFFFFF;
            p.glassPill = 0x8CFFFFFF;
            p.glassBorder = 0x8CFFFFFF;
            p.glassShadow = 0x1A000000;

            p.barBg = 0xF2F7F7F7;
            p.barLine = 0x14000000;
        }
        return p;
    }

    // ---------------------------------------------------------------- 尺寸

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static float dpf(Context c, float v) {
        return v * c.getResources().getDisplayMetrics().density;
    }

    static int screenWidth(Context c) {
        return c.getResources().getDisplayMetrics().widthPixels;
    }

    /** 内容最大宽度：大于 600dp 的窗口限宽，避免卡片被拉成一条长条。 */
    static int contentMaxWidth(Context c) {
        float w = screenWidth(c) / c.getResources().getDisplayMetrics().density;
        return w >= 600 ? dp(c, 560) : Integer.MAX_VALUE;
    }

    // 页面节奏（dp）
    static final int PAGE = 12;         // 页边距（SonyPods: 12dp）
    static final int CARD_GAP = 12;     // 卡片间距
    static final int CARD_RADIUS = 16;  // CardDefaults.CornerRadius
    static final int ROW_MIN_H = 56;    // BasicComponent minHeight
    static final int ROW_PAD_H = 16;    // BasicComponentDefaults.InsideMargin
    static final int TITLE_PAD = 26;    // TopAppBarDefaults.TitlePadding
    static final int BAR_H = 52;        // TopAppBarDefaults.CollapsedHeight

    // 字号（sp）—— 逐条对应 TextStyles.kt
    static final float T_DISPLAY = 32;  // title1（大标题，Normal 字重）
    static final float T_TITLE = 24;    // title2
    static final float T_HERO = 20;     // title3（折叠后的小标题，Medium）
    static final float T_STAT = 24;     // 数值大字
    static final float T_ROW = 17;      // headline1 / main（行标题，Medium）
    static final float T_BODY = 16;     // body1
    static final float T_SUM = 14;      // body2（摘要 / 行尾值）
    static final float T_SECTION = 14;  // subtitle（SmallTitle，Bold）
    static final float T_CAP = 13;      // footnote1
    static final float T_TAB = 11;      // footnote2

    // 折叠标题栏：大标题块高度（大标题 32sp + 副标题 14sp + 底部留白）
    static final int HEADER_BLOCK = 76;

    // ---------------------------------------------------------------- 字体

    private static final Typeface BASE = Typeface.create("sans-serif", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final Typeface SEMI = Typeface.create("sans-serif", 600);
    private static final Typeface BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    static Typeface regular() {
        return BASE;
    }

    /** Medium（500）：行标题、小标题、标签。 */
    static Typeface medium() {
        return MEDIUM;
    }

    /** SemiBold（600）：数值大字、状态卡标题。 */
    static Typeface semibold() {
        return SEMI;
    }

    /** Bold（700）：SmallTitle 段落标题。 */
    static Typeface bold() {
        return BOLD;
    }

    // ---------------------------------------------------------------- 工具

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

    static Squircle.D roundStroke(int color, float radius, int strokeColor, int strokeWidth) {
        return new Squircle.D(color, radius).stroke(strokeColor, strokeWidth);
    }

    /** 页面底色：miuix 的 surface 是纯色，玻璃底栏才有"透过去"的对象。 */
    static Drawable pageBackground(Colors p) {
        return round(p.bg, 0f);
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
