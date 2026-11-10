package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「概览」页 —— 版式逐条对齐 SonyPods 的 HomePage：
 *
 * <pre>
 * Row(12dp 间距)
 *   ├─ StatusCard  weight 1, aspectRatio 1   ← 状态色底 + 右下角压着大图标
 *   └─ Column(12dp)
 *        ├─ StatCard weight 1  触发模式
 *        └─ StatCard weight 1  触发区高度
 * InfoCard：标签 17sp Medium 在上、值 14sp 在下，条目间距 24dp，无分隔线
 * </pre>
 *
 * 颜色与字号全部走 {@link Theme}；数据仍是原来的 Cfg / 心跳时间。
 */
final class OverviewPage {

    private final MainActivity host;
    private final Theme.Colors pal;
    private final Context c;
    private final Ui.Page page;

    private FrameLayout hero;
    private TextView heroTitle;
    private TextView heroSummary;
    private Icons.IconView heroIcon;

    private TextView modeValue;
    private TextView zoneValue;

    private LinearLayout dynamic;

    OverviewPage(MainActivity host) {
        this.host = host;
        this.c = host;
        this.pal = host.pal;
        this.page = Ui.page(c, pal);
        build();
        refresh();
    }

    View view() {
        return page.scroll;
    }

    private void build() {
        // ---------------------------------------------------------- 状态网格
        // SonyPods StatusGrid（手机宽度）：左状态卡 aspectRatio(1f)，
        // 右侧一列两张 StatCard 各占 1/1、间距 12dp
        int pad = Ui.pagePad(c);
        int square = Math.round((Theme.screenWidth(c) - pad * 2
                - Theme.dpf(c, Theme.CARD_GAP)) / 2f);

        LinearLayout grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.HORIZONTAL);
        page.column.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, square));

        grid.addView(statusCard(), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        View gap = new View(c);
        LinearLayout.LayoutParams gapLp = new LinearLayout.LayoutParams(
                Theme.dp(c, Theme.CARD_GAP), ViewGroup.LayoutParams.MATCH_PARENT);
        grid.addView(gap, gapLp);

        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        grid.addView(right, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        right.addView(statCard(true), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = Theme.dp(c, Theme.CARD_GAP);
        right.addView(statCard(false), slp);
    }

    /**
     * SonyPods StatusCard：状态色底 + 右下角压着 136dp 的大图标（offset 34,38），
     * 标题 title3 20sp SemiBold、摘要 body2 14sp Medium。
     */
    private FrameLayout statusCard() {
        hero = new FrameLayout(c);
        hero.setClipToOutline(true);
        Ui.clickableCard(hero, Theme.CARD_RADIUS, pal.successBg, pal, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showStatusDetail();
            }
        });

        heroIcon = Icons.view(c, Icons.HAND, 96, pal.onSuccess, 0f);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                Theme.dp(c, 96), Theme.dp(c, 96));
        iconLp.gravity = Gravity.BOTTOM | Gravity.END;
        iconLp.rightMargin = Theme.dp(c, -22);
        iconLp.bottomMargin = Theme.dp(c, -24);
        hero.addView(heroIcon, iconLp);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Theme.dp(c, 16), Theme.dp(c, 16), Theme.dp(c, 16), Theme.dp(c, 16));
        hero.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        heroTitle = Ui.text(c, "", Theme.T_HERO, pal.title, Theme.semibold());
        col.addView(heroTitle);

        heroSummary = Ui.text(c, "", Theme.T_SUM, pal.onSuccess, Theme.medium());
        heroSummary.setPadding(0, Theme.dp(c, 2), 0, 0);
        heroSummary.setLineSpacing(Theme.dp(c, 3), 1f);
        heroSummary.setMaxLines(2);
        col.addView(heroSummary);
        return hero;
    }

    /** SonyPods StatCard：小标签 13sp Medium + 数值 24sp SemiBold，内边距 14dp。 */
    private FrameLayout statCard(boolean mode) {
        final FrameLayout card = new FrameLayout(c);
        Ui.clickableCard(card, Theme.CARD_RADIUS, pal.card, pal, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.goSettings();
            }
        });

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(Theme.dp(c, 14), Theme.dp(c, 14), Theme.dp(c, 14), Theme.dp(c, 14));
        card.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(Ui.text(c, mode ? "触发模式" : "触发区高度", 13, pal.summary, Theme.medium()));

        TextView value = Ui.text(c, "", Theme.T_STAT, pal.title, Theme.semibold());
        value.setPadding(0, Theme.dp(c, 2), 0, 0);
        col.addView(value);
        if (mode) {
            modeValue = value;
        } else {
            zoneValue = value;
        }
        return card;
    }

    void refresh() {
        Cfg cfg = host.cfg();
        applyHero();

        modeValue.setText(Cfg.MODE_SHORT[Math.max(0, Math.min(Cfg.MODE_SHORT.length - 1, cfg.mode))]);
        zoneValue.setText(Ui.fmt(cfg.zoneDp, 0) + " dp");

        if (dynamic != null) {
            page.column.removeView(dynamic);
        }
        dynamic = new LinearLayout(c);
        dynamic.setOrientation(LinearLayout.VERTICAL);

        // ------------------------------------------------------ 系统信息
        dynamic.addView(Ui.section(c, pal, "系统信息"));
        LinearLayout cardInfo = Ui.card(c, pal);
        LinearLayout infoBody = new LinearLayout(c);
        infoBody.setOrientation(LinearLayout.VERTICAL);
        infoBody.setPadding(Theme.dp(c, 16), Theme.dp(c, 16), Theme.dp(c, 16), Theme.dp(c, 16));
        cardInfo.addView(infoBody);

        Ui.infoText(c, infoBody, pal, "设备型号",
                safe(android.os.Build.MANUFACTURER) + " " + safe(android.os.Build.MODEL));
        Ui.infoText(c, infoBody, pal, "Android 版本",
                android.os.Build.VERSION.RELEASE + "（API " + android.os.Build.VERSION.SDK_INT + "）");
        Ui.infoText(c, infoBody, pal, "系统版本", safe(android.os.Build.DISPLAY));
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        Ui.infoText(c, infoBody, pal, "屏幕",
                dm.widthPixels + " × " + dm.heightPixels + " · " + dm.densityDpi + " dpi");
        Ui.infoText(c, infoBody, pal, "模块版本", moduleVersion());
        Ui.infoText(c, infoBody, pal, "作用域",
                "系统界面 com.android.systemui\n桌面 com.miui.home");
        Ui.infoText(c, infoBody, pal, "安装时间", installTime(), false);
        dynamic.addView(cardInfo, Ui.cardLp(c, Theme.CARD_GAP));

        TextView foot = Ui.text(c, "所有参数都在「设置」里，改动保存后立即生效。",
                Theme.T_CAP, pal.section, Theme.regular());
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, Theme.dp(c, 20), 0, Theme.dp(c, 8));
        dynamic.addView(foot);

        page.column.addView(dynamic);
    }

    private static String safe(String s) {
        return s == null || s.length() == 0 ? "未知" : s;
    }

    private void applyHero() {
        int level = host.statusLevel();
        int bg;
        int iconTint;
        int summaryColor;
        String title;
        String summary;
        if (level == 2) {
            bg = pal.successBg;
            iconTint = pal.success;
            summaryColor = pal.onSuccess;
            title = "模块已激活";
            summary = "钩子已在系统界面生效";
        } else if (level == 1) {
            bg = pal.warnBg;
            iconTint = pal.primary;
            summaryColor = pal.onWarn;
            title = "模块已启用";
            summary = "等待系统界面重启后注入";
        } else {
            bg = pal.cardHigh;
            iconTint = pal.section;
            summaryColor = pal.summary;
            title = "模块已停用";
            summary = "总开关关闭";
        }

        hero.setBackground(Theme.round(bg, Theme.dp(c, Theme.CARD_RADIUS)));
        heroTitle.setText(title);
        heroSummary.setText(summary);
        heroSummary.setTextColor(summaryColor);
        heroIcon.setIconColor(Theme.alpha(iconTint, 0.78f));
    }

    private String moduleVersion() {
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            String name = pi.versionName == null ? "3.0.0" : pi.versionName;
            long code = android.os.Build.VERSION.SDK_INT >= 28
                    ? pi.getLongVersionCode() : pi.versionCode;
            return name + " (" + code + ")";
        } catch (Throwable t) {
            return "3.0.0 (30000)";
        }
    }

    private String installTime() {
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            java.text.SimpleDateFormat f =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
            return f.format(new java.util.Date(pi.lastUpdateTime));
        } catch (Throwable t) {
            return "未知";
        }
    }
}
