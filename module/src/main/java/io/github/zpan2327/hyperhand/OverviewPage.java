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
 *        ├─ StatCard weight 1  单手模式增强（已启用/已禁用）
 *        └─ StatCard weight 1  快捷菜单滑选（已启用/已禁用）
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

    private io.github.zpan2327.hyperhand.glass.dialogs.StatusCardHost hero;
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
    private View statusCard() {
        // 逐字照抄 SonyPods StatusCard：真正的 MIUIX Card + pressFeedbackType=Tilt +
        // showIndication=true，倾斜/高亮/回正全由 MIUIX 原生实现，手感与 SonyPods 100% 一致。
        // 不再用 Java 手写 rotationX/rotationY/cameraDistance 复刻（在该 546px 大卡上会透视爆炸）。
        hero = new io.github.zpan2327.hyperhand.glass.dialogs.StatusCardHost(c);
        // module 侧的 Canvas 图标 View 交给 Compose 侧 AndroidView 原样嵌入，零视觉差异。
        // 保留引用，applyHero() 里按状态更新图标颜色（与原版一致）。
        heroIcon = Icons.view(c, Icons.HAND, 96, pal.onSuccess, 0f);
        hero.provideIconView(heroIcon);
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

        col.addView(Ui.text(c, mode ? "单手模式增强" : "快捷菜单滑选", 13, pal.summary, Theme.medium()));

        TextView value = Ui.text(c, "", Theme.T_STAT, pal.title, Theme.bold());
        value.setPadding(0, Theme.dp(c, 2), 0, 0);
        col.addView(value);
        if (mode) {
            modeValue = value;
        } else {
            zoneValue = value;
        }
        return card;
    }

    /** 功能状态值：沿用上一版配色（statCard 的 pal.title），只用文字区分状态。 */
    private void applyState(TextView v, boolean enabled) {
        v.setText(enabled ? "已启用" : "已禁用");
    }

    void refresh() {
        Cfg cfg = host.cfg();
        applyHero();

        // 两张卡改为直接呈现两个功能的启用状态（单手模式增强 / 快捷菜单滑选），
        // 沿用上一版配色，只用「已启用 / 已禁用」文字区分。
        applyState(modeValue, cfg.enabled);
        applyState(zoneValue, cfg.slideEnabled);

        if (dynamic != null) {
            // 系统信息区里全是进程生命周期内不变的值（机型、版本、安装时间），
            // 之前每次 refresh 都整块拆掉重建，连带整页 ScrollView 重新测量；切到本页
            // 时底栏指示器正走到一半，这一下布局就把那几帧吃掉 —— 看起来像「卡住再跳」。
            return;
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
                "系统框架 system（按键映射）\n系统界面 com.android.systemui\n桌面 com.miui.home");
        Ui.infoText(c, infoBody, pal, "安装时间", installTime(), false);
        dynamic.addView(cardInfo, Ui.cardLp(c, Theme.CARD_GAP));

        TextView foot = Ui.text(c, "所有参数都在「设置」里，改动后立即生效。",
                Theme.T_CAP, pal.section, Theme.regular());
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, Theme.dp(c, 20), 0, Theme.dp(c, 8));
        dynamic.addView(foot);

        page.column.addView(dynamic);
    }

    private static String safe(String s) {
        return s == null || s.length() == 0 ? "未知" : s;
    }

    /**
     * 状态卡配色与 SonyPods {@code StatusCard} 逐值一致：
     * 已激活 = 绿 {@code #36D167}/{@code #DFFAE4}，未启用 = 红 {@code #FF5A52}/{@code #FFE5E3}。
     *
     * <p><b>只有两种状态，没有「检测中」</b>：{@link MainActivity#displayStatus()} 在探测
     * 在途时沿用上一次结论，因此左右滑页不会看到「已激活 → 检测中 → 已激活」的闪动。
     * 卡不可点击（见 {@link #statusCard()}）。
     */
    private void applyHero() {
        int level = host.displayStatus();
        int bg;
        int iconTint;
        int summaryColor;
        String title;
        String summary;
        if (level == 2) {
            bg = pal.night ? 0xFF16301F : 0xFFDFFAE4;
            iconTint = pal.night ? 0xFF5BD07E : 0xFF36D167;
            summaryColor = Theme.alpha(pal.night ? 0xFFB8E8C5 : 0xFF2F3A32, 0.78f);
            title = "模块已激活";
            summary = "钩子已在系统界面生效";
        } else {
            bg = pal.night ? 0xFF3A201E : 0xFFFFE5E3;
            iconTint = pal.night ? 0xFFFF8A82 : 0xFFFF5A52;
            summaryColor = pal.night ? 0xFFFF8A82 : 0xFFFF5A52;
            title = "模块未启用";
            summary = level == 0 ? "总开关关闭" : "未在 LSPosed 启用或作用域未注入";
        }

        // 状态卡由 MIUIX Compose Card 承载按压反馈，但配色完全沿用本页原有逻辑：
        // 背景、标题、摘要、图标颜色都由这里精确计算后传入，不在 Compose 宿主内另算。
        hero.setBackground(bg);
        hero.setTitle(title, pal.title);
        hero.setSummary(summary, summaryColor);
        heroIcon.setIconColor(Theme.alpha(iconTint, 0.78f));
    }

    private String moduleVersion() {
        try {
            android.content.pm.PackageInfo pi =
                    c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            String name = pi.versionName == null ? "3.0.0" : pi.versionName;
            long code = android.os.Build.VERSION.SDK_INT >= 28
                    ? pi.getLongVersionCode() : pi.versionCode;
            // 与关于页 hero 及规范 §2.3 保持同一格式「版本名(版本号)」，中间不留空格。
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
