package io.github.zpan2327.hyperhand;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * HyperHand 的界面外壳：三个页面（概览 / 设置 / 关于）+ 悬浮式液态玻璃底栏。
 *
 * 设计语言统一由 {@link Theme} 提供，组件在 {@link Ui}，图标在 {@link Icons}，
 * 底栏在 {@link GlassBar}。业务逻辑（保存广播、重启作用域、调试动作）与 2.9 完全一致。
 */
public class MainActivity extends Activity {

    static final int TAB_OVERVIEW = 0;
    static final int TAB_SETTINGS = 1;
    static final int TAB_ABOUT = 2;

    Theme.Colors pal;
    private Cfg cur;

    private FrameLayout contentHost;
    private FrameLayout root;
    private GlassBar nav;
    private TextView savePill;
    private FrameLayout.LayoutParams navLp;

    private View[] pages = new View[3];
    private OverviewPage overviewPage;
    private SettingsPage settingsPage;
    private AboutPage aboutPage;

    private int current = TAB_OVERVIEW;
    private boolean dirty;
    private int bottomInset;
    private int statusInset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.hide();
        }

        pal = Theme.colors(this);
        try {
            cur = Cfg.get(this);
        } catch (Throwable t) {
            cur = new Cfg();
        }

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(pal.bg));
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setDecorFitsSystemWindows(false);
        android.view.WindowInsetsController ctrl = w.getInsetsController();
        if (ctrl != null) {
            int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ctrl.setSystemBarsAppearance(Theme.isNight(this) ? 0 : mask, mask);
        }

        setContentView(buildShell());
        // 支持 am start --es tab settings 直接打开某一页（截图/调试用，正常启动仍是概览）
        int start = TAB_OVERVIEW;
        String tab = getIntent() == null ? null : getIntent().getStringExtra("tab");
        if ("settings".equals(tab)) {
            start = TAB_SETTINGS;
        } else if ("about".equals(tab)) {
            start = TAB_ABOUT;
        }
        current = start;
        nav.setSelectedImmediate(start);
        selectTab(start, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户常见操作：回桌面滑一下 → 切回来看看判定结果
        if (overviewPage != null && current == TAB_OVERVIEW) {
            overviewPage.refresh();
        }
        if (settingsPage != null) {
            settingsPage.refreshGestureLog();
            settingsPage.refreshAnimInfo();
        }
    }

    // ---------------------------------------------------------------- 外壳

    private View buildShell() {
        root = new FrameLayout(this);
        root.setBackgroundColor(pal.bg);

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 三页一次性建好，切换只做显隐与位移动画，滚动位置和未保存的编辑都能留住
        overviewPage = new OverviewPage(this);
        settingsPage = new SettingsPage(this);
        aboutPage = new AboutPage(this);
        pages[TAB_OVERVIEW] = overviewPage.view();
        pages[TAB_SETTINGS] = settingsPage.view();
        pages[TAB_ABOUT] = aboutPage.view();
        for (int i = 0; i < pages.length; i++) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            pages[i].setVisibility(i == TAB_OVERVIEW ? View.VISIBLE : View.GONE);
            contentHost.addView(pages[i], lp);
        }

        nav = new GlassBar(this, pal);
        navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.BOTTOM;
        navLp.leftMargin = Theme.dp(this, 14);
        navLp.rightMargin = Theme.dp(this, 14);
        navLp.bottomMargin = Theme.dp(this, 14);
        root.addView(nav, navLp);
        nav.setOnTabSelected(new GlassBar.OnTabSelected() {
            @Override
            public void onSelected(int index) {
                selectTab(index, true);
            }
        });

        // 有未保存改动时浮在底栏上方的"保存"胶囊
        savePill = Ui.primaryButton(this, pal, "保存并立即生效");
        savePill.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Theme.T_SUM);
        savePill.setPadding(Theme.dp(this, 18), Theme.dp(this, 10), Theme.dp(this, 18), Theme.dp(this, 10));
        savePill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pillLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pillLp.bottomMargin = Theme.dp(this, 86);
        savePill.setVisibility(View.GONE);
        savePill.setAlpha(0f);
        root.addView(savePill, pillLp);

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                contentHost.setPadding(0, 0, 0, 0);
                statusInset = bars.top;
                navLp.bottomMargin = Theme.dp(MainActivity.this, 14) + bars.bottom;
                navLp.leftMargin = Theme.dp(MainActivity.this, 14);
                navLp.rightMargin = Theme.dp(MainActivity.this, 14);
                nav.setLayoutParams(navLp);

                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) savePill.getLayoutParams();
                lp.bottomMargin = Theme.dp(MainActivity.this, 86) + bars.bottom;
                savePill.setLayoutParams(lp);

                // 滚动时内容会从玻璃下面穿过（玻璃才有东西可模糊），
                // 滚到底时最后一行正好停在栏上沿
                bottomInset = Theme.dp(MainActivity.this, 64 + 14 + 8) + bars.bottom;
                applyBottomInset();
                nav.setBackdropSource(contentHost);
                return insets;
            }
        });

        return root;
    }

    private void applyBottomInset() {
        for (int i = 0; i < pages.length; i++) {
            View p = pages[i];
            if (p instanceof android.widget.ScrollView) {
                android.widget.ScrollView sv = (android.widget.ScrollView) p;
                // 关于页全屏出血：渐变直接铺到状态栏下面，和 SonyPods 一致
                sv.setClipToPadding(i != TAB_ABOUT);
                sv.setPadding(0, i == TAB_ABOUT ? 0 : statusInset, 0, 0);
                View child = sv.getChildAt(0);
                if (child != null) {
                    int top = i == TAB_ABOUT ? 0 : Theme.dp(this, 8);
                    child.setPadding(child.getPaddingLeft(), top,
                            child.getPaddingRight(), bottomInset);
                }
                p.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                    @Override
                    public void onScrollChange(View v, int x, int y, int ox, int oy) {
                        if (nav != null) {
                            nav.onContentScrolled();
                        }
                    }
                });
            }
        }
    }

    // ---------------------------------------------------------------- 导航

    void selectTab(int index, boolean animate) {
        if (index < 0 || index > 2) {
            return;
        }
        if (index == current && pages[index].getVisibility() == View.VISIBLE && animate) {
            nav.select(index, true);
            return;
        }
        int dir = index > current ? 1 : -1;
        if (!animate) {
            dir = 1;
        }
        View in = pages[index];
        View out = pages[current];
        in.animate().cancel();
        out.animate().cancel();

        if (!animate || in == out) {
            for (int i = 0; i < pages.length; i++) {
                pages[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
                pages[i].setAlpha(1f);
                pages[i].setTranslationX(0f);
            }
        } else {
            in.setVisibility(View.VISIBLE);
            in.setAlpha(0f);
            in.setTranslationX(dir * Theme.dp(this, 20));
            in.animate().alpha(1f).translationX(0f).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f))
                    .start();
            out.setVisibility(View.VISIBLE);
            final View outgoing = out;
            out.animate().alpha(0f).translationX(-dir * Theme.dp(this, 20)).setDuration(170)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (outgoing != pages[current]) {
                                outgoing.setVisibility(View.GONE);
                            }
                        }
                    }).start();
        }

        current = index;
        nav.select(index, true);

        if (index == TAB_OVERVIEW) {
            overviewPage.refresh();
        } else if (index == TAB_SETTINGS) {
            settingsPage.refreshGestureLog();
        }
        contentHost.postInvalidateOnAnimation();
    }

    void goSettings() {
        selectTab(TAB_SETTINGS, true);
    }

    @Override
    public void onBackPressed() {
        if (current != TAB_OVERVIEW) {
            selectTab(TAB_OVERVIEW, true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 深浅色切换后整页重建，避免两套 token 混用
        recreate();
    }

    // ---------------------------------------------------------------- 数据

    int statusInset() {
        return statusInset;
    }

    Cfg cfg() {
        if (cur == null) {
            cur = Cfg.get(this);
        }
        return cur;
    }

    void reloadCfg() {
        Cfg.invalidate();
        cur = Cfg.get(this);
    }

    void markDirty() {
        if (dirty) {
            return;
        }
        dirty = true;
        showSavePill(true);
    }

    private void clearDirty() {
        dirty = false;
        showSavePill(false);
    }

    private void showSavePill(boolean show) {
        if (show) {
            if (savePill.getVisibility() != View.VISIBLE) {
                savePill.setVisibility(View.VISIBLE);
                savePill.setAlpha(0f);
                savePill.setTranslationY(Theme.dp(this, 12));
            }
            savePill.animate().alpha(1f).translationY(0f).setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
        } else {
            savePill.animate().alpha(0f).translationY(Theme.dp(this, 12)).setDuration(150)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            if (!dirty) {
                                savePill.setVisibility(View.GONE);
                            }
                        }
                    }).start();
        }
    }

    /** 模块运行状态：0 停用 / 1 已启用但没读到注入 / 2 已激活。 */
    int statusLevel() {
        Cfg cfg = cfg();
        if (!cfg.enabled) {
            return 0;
        }
        long ts = Cfg.aliveTs(this, Cfg.K_ALIVE_SYSUI);
        if (ts > 0 && System.currentTimeMillis() - ts < 7L * 24 * 3600 * 1000) {
            return 2;
        }
        long lt = Cfg.aliveTs(this, Cfg.K_ALIVE_LAUNCHER);
        if (lt > 0 && System.currentTimeMillis() - lt < 7L * 24 * 3600 * 1000) {
            return 2;
        }
        return 1;
    }

    String aliveSummary() {
        long sysui = Cfg.aliveTs(this, Cfg.K_ALIVE_SYSUI);
        long launcher = Cfg.aliveTs(this, Cfg.K_ALIVE_LAUNCHER);
        if (sysui <= 0 && launcher <= 0) {
            return statusLevel() == 0 ? "总开关关闭" : "尚无注入记录";
        }
        StringBuilder sb = new StringBuilder();
        if (sysui > 0) {
            sb.append("系统界面 ").append(ago(sysui));
        }
        if (launcher > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("桌面 ").append(ago(launcher));
        }
        return sb.toString();
    }

    String gestureLog() {
        try {
            String s = Settings.Global.getString(getContentResolver(), Cfg.K_LAST_GESTURE);
            if (s != null && s.trim().length() > 0) {
                return s;
            }
        } catch (Throwable ignored) {
            // 走默认提示
        }
        return "还没有记录。开启调试日志后，在屏幕底部下滑一次再看这里。";
    }

    /** 全屏二级页 overlay：盖在所有页面与底栏之上（比 Dialog 可靠，MIUI 上不会透底）。 */
    void showOverlay(View v) {
        if (root == null) {
            return;
        }
        hideOverlay(v);
        root.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    void hideOverlay(View v) {
        if (root != null && v.getParent() == root) {
            root.removeView(v);
        }
    }

    void showStatusDetail() {
        Cfg cfg = cfg();
        StringBuilder sb = new StringBuilder();
        sb.append("总开关：").append(cfg.enabled ? "已启用" : "已关闭").append('\n');
        sb.append("系统界面：").append(aliveText(Cfg.K_ALIVE_SYSUI)).append('\n');
        sb.append("桌面：").append(aliveText(Cfg.K_ALIVE_LAUNCHER)).append("\n\n");
        sb.append("识别模式：").append(Cfg.MODE_SHORT[safeMode(cfg.mode)]).append('\n');
        sb.append("触发区：").append(Ui.fmt(cfg.zoneDp, 0)).append(" dp")
                .append(cfg.zoneRatioMode ? "（屏幕高度 " + Ui.fmt(cfg.zoneRatio * 100f, 0) + "%）" : "（固定高度）")
                .append('\n');
        sb.append("扩展触发：").append(cfg.monitorEnabled ? "开" : "关")
                .append("　贴底直达：").append(cfg.endBoost ? "开" : "关").append('\n');
        sb.append("外观：").append(cfg.blurBg ? "模糊壁纸 " + Ui.fmt(cfg.blurRadiusDp, 0) + "dp" : "原生遮罩").append('\n');
        sb.append("动画：").append(Cfg.ANIM_CURVE_SHORT[safeCurve(cfg.animCurve)])
                .append(" · ").append(Ui.fmt(cfg.animScale, 2)).append("×\n\n");
        sb.append("注入记录由模块在系统界面 / 桌面进程启动时写入，")
                .append("换了模块版本或刚重启过系统，这里会显示对应时间。");
        Ui.infoDialog(this, pal, "运行状态", sb.toString());
    }

    private String aliveText(String key) {
        long ts = Cfg.aliveTs(this, key);
        if (ts <= 0) {
            return "尚无记录";
        }
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        return f.format(new Date(ts)) + "（" + ago(ts) + "）";
    }

    private static int safeMode(int v) {
        return v < 0 || v >= Cfg.MODE_SHORT.length ? 0 : v;
    }

    private static int safeCurve(int v) {
        return v < 0 || v >= Cfg.ANIM_CURVE_SHORT.length ? 0 : v;
    }

    private static String ago(long ts) {
        long d = System.currentTimeMillis() - ts;
        if (d < 90_000L) {
            return "刚刚";
        }
        if (d < 3600_000L) {
            return (d / 60_000L) + " 分钟前";
        }
        if (d < 86_400_000L) {
            return (d / 3600_000L) + " 小时前";
        }
        return (d / 86_400_000L) + " 天前";
    }

    // ---------------------------------------------------------------- 动作

    void save() {
        Intent i = new Intent(HyperHand.ACTION_SET);
        i.setPackage("com.android.systemui");
        try {
            settingsPage.applyTo(i);
        } catch (Throwable t) {
            toast("保存失败：" + t);
            return;
        }
        sendBroadcast(i);
        settingsPage.onSaved();
        clearDirty();
        reloadCfg();
        overviewPage.refresh();
        toast("已保存");
        if (settingsPage != null) {
            settingsPage.refreshAnimInfo();
        }
    }

    void previewOneHanded() {
        broadcastAction("trigger");
        toast("已请求进入单手模式");
    }

    void stopOneHanded() {
        broadcastAction("stop");
        toast("已请求退出");
    }

    void restartScopes() {
        Ui.confirmDialog(this, pal, "重启两个作用域",
                "会结束「系统界面」与「小米桌面」两个进程，屏幕闪一下、短暂黑屏后自动恢复。\n"
                        + "未保存的参数修改会丢失。",
                "重启", new Ui.OnConfirm() {
                    @Override
                    public void onConfirm() {
                        doRestart();
                    }
                });
    }

    /** 概览右上角二级菜单：按作用域重启。scope = "sysui" / "home"。 */
    void restartScope(final String scope) {
        final String label = "sysui".equals(scope) ? "系统界面" : "小米桌面";
        final String pkg = "sysui".equals(scope) ? "com.android.systemui" : "com.miui.home";
        Ui.confirmDialog(this, pal, "重启" + label,
                "会结束「" + label + "」进程，屏幕可能闪一下后自动恢复。\n"
                        + "未保存的参数修改会丢失。",
                "重启", new Ui.OnConfirm() {
                    @Override
                    public void onConfirm() {
                        settingsPage.setRestarting(true);
                        settingsPage.setStatus("正在重启" + label + "……若弹出 root 授权请允许。");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                final String err = su("killall " + pkg + "; sleep 1; input keyevent 3", 25000L);
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        settingsPage.setRestarting(false);
                                        if (err == null) {
                                            settingsPage.setStatus("已结束" + label + "，钩子会在它重启后重新注入。");
                                            toast("已重启" + label);
                                        } else {
                                            broadcastAction("reload");
                                            settingsPage.setStatus("没有 root：" + err);
                                            toast("重启失败");
                                        }
                                        if (current == TAB_OVERVIEW) {
                                            overviewPage.refresh();
                                        }
                                    }
                                });
                            }
                        }).start();
                    }
                });
    }

    private void broadcastAction(String action) {
        Intent i = new Intent(HyperHand.ACTION_SET);
        i.setPackage("com.android.systemui");
        i.putExtra(Cfg.K_ACTION, action);
        sendBroadcast(i);
    }

    private void doRestart() {
        settingsPage.setRestarting(true);
        settingsPage.setStatus("正在重启系统界面与桌面……若弹出 root 授权请允许。");
        final int keepTab = current;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String err = su("killall com.android.systemui; killall com.miui.home; "
                        + "sleep 1; input keyevent 3", 25000L);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        settingsPage.setRestarting(false);
                        if (err == null) {
                            settingsPage.setStatus("已结束两个进程，钩子会在它们重启后重新注入。"
                                    + "现在可以下滑底部区域试试。");
                            toast("已重启");
                        } else {
                            broadcastAction("reload");
                            settingsPage.setStatus("没有 root：" + err
                                    + "\n已改为让系统界面自行重启（桌面侧钩子未刷新，可稍后手动重启）。");
                            toast("已请求系统界面重启");
                        }
                        if (keepTab == TAB_OVERVIEW) {
                            overviewPage.refresh();
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 跑一条 root 命令：成功返回 null，失败返回原因。
     * KernelSU / Magisk 的 su 路径不统一，按常见位置依次尝试。
     */
    private String su(String cmd, long timeoutMs) {
        final String[] bins = new String[] {"su", "/system/bin/su", "/debug_ramdisk/su", "/sbin/su"};
        String last = "未知错误";
        for (String bin : bins) {
            Process p;
            try {
                p = Runtime.getRuntime().exec(new String[] {bin, "-c", cmd});
            } catch (Throwable t) {
                last = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                continue;
            }
            try {
                if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroy();
                    return "等待授权超时，请在 KernelSU / Magisk 里允许 HyperHand";
                }
                int code = p.exitValue();
                String err = readStream(p.getErrorStream());
                if (code == 0) {
                    return null;
                }
                return "退出码 " + code + (err.length() > 0 ? "：" + err.trim() : "");
            } catch (Throwable t) {
                return String.valueOf(t);
            } finally {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return "没找到可用的 su（" + last + "）。请确认已 root，并允许 HyperHand 的超级用户请求";
    }

    private static String readStream(java.io.InputStream in) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
