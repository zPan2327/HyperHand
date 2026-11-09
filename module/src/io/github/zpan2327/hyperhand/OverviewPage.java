package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「概览」页。
 *
 * 版式对齐 SonyPods 的概览页：
 * 大标题 + 右上角刷新，一张彩色状态卡（右下角压着大图标）+ 右侧两张数值卡，
 * 下面是一张"标签在上、数值在下"的信息卡（无分隔线），再接手势记录与快捷操作。
 */
final class OverviewPage {

    private final MainActivity host;
    private final Theme.Colors pal;
    private final Context c;
    private final Ui.Page page;

    private FrameLayout hero;
    private TextView heroTitle;
    private TextView heroSummary;
    private TextView heroFoot;
    private Icons.IconView heroIcon;

    private TextView modeValue;
    private TextView zoneValue;

    private LinearLayout dynamic;
    private View restartBtn;

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

    void setBottomInset(int px) {
        page.setBottomInset(px);
    }

    private void build() {
        restartBtn = Ui.ghostIconButton(c, pal, Icons.RESTART, new Runnable() {
            @Override
            public void run() {
                // 重启作用域：二级菜单选择要重启的作用域
                Ui.popupMenu(c, pal, restartBtn,
                        new String[] {"重启系统界面", "重启小米桌面", "重启两个作用域"},
                        new Ui.OnChoice() {
                            @Override
                            public void onChoice(int index) {
                                if (index == 0) {
                                    host.restartScope("sysui");
                                } else if (index == 1) {
                                    host.restartScope("home");
                                } else {
                                    host.restartScopes();
                                }
                            }
                        });
            }
        });
        page.add(Ui.pageHeader(c, pal, "概览",
                "单手模式增强 · " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
                restartBtn));

        // ---------------------------------------------------------- 状态卡（整宽）
        hero = new FrameLayout(c);
        hero.setClipToOutline(true);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(c, 126));
        heroLp.topMargin = Theme.dp(c, 14);
        page.column.addView(hero, heroLp);

        // 水印图标压右下角：SonyPods StatusCard 的 offset(34, 38)
        heroIcon = Icons.view(c, Icons.HAND, 104, pal.onSuccess, 1.6f);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        iconLp.gravity = Gravity.BOTTOM | Gravity.END;
        iconLp.rightMargin = Theme.dp(c, -30);
        iconLp.bottomMargin = Theme.dp(c, -34);
        hero.addView(heroIcon, iconLp);

        LinearLayout heroText = new LinearLayout(c);
        heroText.setOrientation(LinearLayout.VERTICAL);
        heroText.setPadding(Theme.dp(c, 18), Theme.dp(c, 16), Theme.dp(c, 18), Theme.dp(c, 14));
        hero.addView(heroText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        heroTitle = Ui.text(c, "", 20, pal.title, Theme.semibold());
        heroText.addView(heroTitle);
        heroSummary = Ui.text(c, "", 14, pal.summary, Theme.medium());
        heroSummary.setPadding(0, Theme.dp(c, 2), 0, 0);
        heroSummary.setLineSpacing(Theme.dp(c, 2), 1f);
        heroText.addView(heroSummary);

        heroText.addView(new View(c), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        heroFoot = Ui.text(c, "", 12, pal.summary, false);
        heroText.addView(heroFoot);

        // 两张数值卡并排（miuix 卡片间距 12dp）
        LinearLayout stats = new LinearLayout(c);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(c, 92));
        statsLp.topMargin = Theme.dp(c, Theme.CARD_GAP);
        page.column.addView(stats, statsLp);

        stats.addView(statCard(true), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        stats.addView(new View(c), new LinearLayout.LayoutParams(
                Theme.dp(c, Theme.CARD_GAP), ViewGroup.LayoutParams.MATCH_PARENT));
        stats.addView(statCard(false), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
    }

    /** 数值卡：小标签 + 大字，无图标（与 SonyPods 一致）。 */
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
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col.addView(Ui.text(c, mode ? "触发模式" : "触发区高度", 13, pal.summary, Theme.medium()));

        TextView value = Ui.text(c, "", 24, pal.title, Theme.semibold());
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

        // ------------------------------------------------------ 系统信息（原「关于」页内容）
        dynamic.addView(Ui.section(c, pal, "系统信息", false));
        LinearLayout cardInfo = Ui.card(c, pal);
        Ui.infoRow(c, cardInfo, pal, "设备型号",
                android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        Ui.infoRow(c, cardInfo, pal, "Android 版本",
                android.os.Build.VERSION.RELEASE + "（API " + android.os.Build.VERSION.SDK_INT + "）");
        Ui.infoRow(c, cardInfo, pal, "系统版本",
                safe(android.os.Build.DISPLAY));
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        Ui.infoRow(c, cardInfo, pal, "屏幕",
                dm.widthPixels + " × " + dm.heightPixels + " · " + dm.densityDpi + " dpi");
        Ui.infoRow(c, cardInfo, pal, "模块版本", moduleVersion());
        Ui.infoRow(c, cardInfo, pal, "作用域", "系统界面 com.android.systemui\n桌面 com.miui.home");
        Ui.infoRow(c, cardInfo, pal, "安装时间", installTime());
        dynamic.addView(cardInfo, Ui.cardLp(c, Theme.CARD_GAP));

        TextView foot = Ui.text(c, "所有参数都在「设置」里，改动保存后立即生效。",
                Theme.T_CAP, pal.section, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, Theme.dp(c, 20), 0, Theme.dp(c, 28));
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
        String title;
        String summary;
        if (level == 2) {
            bg = pal.successBg;
            iconTint = pal.success;
            title = "模块已激活";
            summary = "钩子已在系统界面生效，\n底部下滑即可缩小窗口";
        } else if (level == 1) {
            bg = pal.primarySoft;
            iconTint = pal.primary;
            title = "模块已启用";
            summary = "等待系统界面重启后\n注入钩子";
        } else {
            bg = pal.cardHigh;
            iconTint = pal.section;
            title = "模块已停用";
            summary = "总开关关闭，\n系统回到原生行为";
        }

        hero.setBackground(Theme.round(bg, Theme.dp(c, Theme.CARD_RADIUS)));
        heroTitle.setText(title);
        heroSummary.setText(summary);
        heroIcon.setIconColor(Theme.alpha(iconTint, 0.32f));
        heroFoot.setText(host.aliveSummary());
        heroFoot.setTextColor(pal.section);

        hero.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showStatusDetail();
            }
        });
        Ui.pressScale(hero);
        hero.setClickable(true);
    }

    private static int safe(int curve) {
        return curve < 0 || curve >= Cfg.ANIM_CURVE_SHORT.length ? 0 : curve;
    }

    private String moduleVersion() {
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            String name = pi.versionName == null ? "3.0.0" : pi.versionName;
            long code = android.os.Build.VERSION.SDK_INT >= 28
                    ? pi.getLongVersionCode() : pi.versionCode;
            return name + "(" + code + ")";
        } catch (Throwable t) {
            return "3.0.0(30000)";
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
