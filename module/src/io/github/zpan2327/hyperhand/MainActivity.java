package io.github.zpan2327.hyperhand;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 参数调节界面。
 *
 * 视觉参照 SonyPods（Miuix / HyperOS 设置页）：
 * 页面底色 surface，卡片 surfaceContainer + 16dp 圆角，
 * 段落标题 14sp Medium、行标题 17sp Medium、摘要 14sp，行高 56dp、内边距 16dp，
 * 分隔线 0.75dp 且左侧内缩 16dp。
 */
public class MainActivity extends Activity {

    private Ui.Pal pal;

    private Ui.SwitchView swEnabled;
    private Ui.SwitchView swMonitor;
    private Ui.SwitchView swDebug;
    private Ui.SwitchView swShadeGuard;
    private Ui.SwitchView swClaimZone;

    private Ui.ValueRow rZone;
    private Ui.ValueRow rMinDy;
    private Ui.ValueRow rVelocity;
    private Ui.ValueRow rSlope;
    private Ui.ValueRow rWidth;
    private Ui.ValueRow rCooldown;
    private Ui.ValueRow rHotDp;

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.hide();
        }

        pal = Ui.pal(this);

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(pal.bg));
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        // API 30+ 的 edge-to-edge 必须走 decorFitsSystemWindows(false)；
        // 只设 SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN 在 31 上不会再撑开窗口，会导致状态栏高度被算两次。
        w.setDecorFitsSystemWindows(false);
        android.view.WindowInsetsController ctrl = w.getInsetsController();
        if (ctrl != null) {
            int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ctrl.setSystemBarsAppearance(Ui.isNight(this) ? 0 : mask, mask);
        }

        setContentView(buildUi());
        loadCurrent();
    }

    private View buildUi() {
        final int page = dp(12);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(pal.bg);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(true);
        scroll.setVerticalScrollBarEnabled(false);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(page, 0, page, dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------------------------------------------------------- 头部
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(4), dp(10), dp(4), dp(4));
        TextView title = Ui.text(this, "HyperHand", 30, pal.title, true);
        header.addView(title);
        TextView sub = Ui.text(this, "单手模式增强 · 小米 14 / HyperOS 3", 14, pal.section, false);
        sub.setPadding(0, dp(4), 0, 0);
        header.addView(sub);
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------------------------------------------------------- 模块
        root.addView(Ui.sectionTitle(this, pal, "模块"));
        LinearLayout cardModule = Ui.card(this, pal);
        swEnabled = Ui.switchRow(this, cardModule, pal, "启用模块",
                "总开关。关闭后所有钩子立即失效，系统恢复原生行为", true);
        swMonitor = Ui.switchRow(this, cardModule, pal, "扩展触发",
                "在系统的一手模式监视通道上判定底部下滑", true);
        Ui.SwitchView.OnCheckedChange dirtyOnChange = new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                markDirty();
            }
        };
        swEnabled.setOnCheckedChange(dirtyOnChange);
        swMonitor.setOnCheckedChange(dirtyOnChange);
        root.addView(cardModule);

        // ---------------------------------------------------------- 触发区
        root.addView(Ui.sectionTitle(this, pal, "触发区"));
        LinearLayout cardZone = Ui.card(this, pal);
        rZone = valueRow(cardZone, "触发区高度", "从屏幕最底部往上算的高度",
                Cfg.DEF_ZONE_DP, 0, 20, 140, 1, "dp");
        rMinDy = valueRow(cardZone, "最小下滑距离", "低于这个位移不算一次下滑",
                Cfg.DEF_MIN_DY_DP, 0, 1, 40, 1, "dp");
        rWidth = valueRow(cardZone, "有效宽度占比", "触发区在屏幕水平方向上的占比，居中",
                Cfg.DEF_WIDTH_RATIO, 2, 0.2f, 1.0f, 0.05f, "");
        root.addView(cardZone);

        // ---------------------------------------------------------- 判定
        root.addView(Ui.sectionTitle(this, pal, "判定"));
        LinearLayout cardJudge = Ui.card(this, pal);
        rVelocity = valueRow(cardJudge, "起效速度", "向下峰值速度门限，慢拖不触发，0 表示关闭速度判定",
                Cfg.DEF_MIN_VELOCITY, 0, 0, 3000, 50, "px/s");
        rSlope = valueRow(cardJudge, "最大横向偏移", "|dx| / dy 超过该值视为斜滑，不触发",
                Cfg.DEF_MAX_SLOPE, 1, 0.5f, 4.0f, 0.1f, "倍");
        rCooldown = valueRow(cardJudge, "触发冷却", "两次触发之间的最小间隔",
                Cfg.DEF_COOLDOWN_MS, 0, 0, 2000, 50, "ms");
        root.addView(cardJudge);

        // ---------------------------------------------------------- 互斥
        root.addView(Ui.sectionTitle(this, pal, "互斥"));
        LinearLayout cardGuard = Ui.card(this, pal);
        swShadeGuard = Ui.switchRow(this, cardGuard, pal, "与下拉手势互斥",
                "手势过程中出现通知/控制中心的意图就放弃单手模式", true);
        swClaimZone = Ui.switchRow(this, cardGuard, pal, "底部区域优先单手模式",
                "触发区内的桌面下拉手势让位，不再打开通知/控制中心", true);
        rHotDp = valueRow(cardGuard, "原生胶囊条高度", "覆盖系统 25dp 的底部窗口，0 表示不改动",
                Cfg.DEF_LAUNCHER_HOT_DP, 0, 0, 40, 1, "dp");
        root.addView(cardGuard);

        // ---------------------------------------------------------- 调试
        root.addView(Ui.sectionTitle(this, pal, "调试"));
        LinearLayout cardDebug = Ui.card(this, pal);
        swDebug = Ui.switchRow(this, cardDebug, pal, "调试日志",
                "用 logcat -s HyperHand 观察判定过程", true);
        swShadeGuard.setOnCheckedChange(dirtyOnChange);
        swClaimZone.setOnCheckedChange(dirtyOnChange);
        swDebug.setOnCheckedChange(dirtyOnChange);
        root.addView(cardDebug);

        // ---------------------------------------------------------- 操作
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(20), 0, 0);

        TextView save = Ui.primaryButton(this, pal, "保存并立即生效");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        actions.addView(save, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        TextView reset = Ui.secondaryButton(this, pal, "恢复默认值");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        rlp.topMargin = dp(10);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyDefaults();
                save();
            }
        });
        actions.addView(reset, rlp);
        root.addView(actions);

        status = Ui.text(this, "", 13, pal.summary, false);
        status.setPadding(dp(4), dp(12), dp(4), 0);
        status.setLineSpacing(dp(3), 1f);
        root.addView(status);

        // ---------------------------------------------------------- 说明
        root.addView(Ui.sectionTitle(this, pal, "说明"));
        LinearLayout cardTips = Ui.card(this, pal);
        TextView tips = Ui.text(this, "• 在 Vector / LSPosed 里启用 HyperHand 即可：作用域已经静态声明，\n"
                + "  不用手动勾「系统界面 / 小米桌面」，也没法挂到别的应用上。\n"
                + "• 保存后立即生效，不需要重启；改动由系统界面进程写入 Settings.Global。\n"
                + "• 快甩才触发、慢滑不触发：调大「起效速度」；想更灵敏就调到 0 关闭速度判定。\n"
                + "• 想完全恢复原状：关掉「启用模块」，或在管理器里停用模块。", 13,
                pal.summary, false);
        tips.setLineSpacing(dp(4), 1f);
        tips.setPadding(dp(16), dp(14), dp(16), dp(16));
        cardTips.addView(tips);
        root.addView(cardTips);

        TextView footer = Ui.text(this, "HyperHand " + versionName() + " · MIT", 12, pal.chevron, false);
        footer.setGravity(android.view.Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, 0);
        root.addView(footer);

        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                // 内容裁在状态栏/导航栏内侧，滚动时不会糊到图标上
                v.setPadding(0, bars.top, 0, bars.bottom);
                header.setPadding(dp(4), dp(14), dp(4), dp(4));
                root.setPadding(page, 0, page, dp(28));
                return insets;
            }
        });

        // 触摸任何控件都算“有未保存的更改”，提示文案统一在 markDirty 里更新
        return scroll;
    }

    /** 数值行工厂：负责弹对话框、回填显示。 */
    private Ui.ValueRow valueRow(LinearLayout card, final String title, String summary,
                                 float def, int decimals, final float min, final float max,
                                 final float step, final String unit) {
        final int dec = decimals;
        final String u = unit == null || unit.length() == 0 ? "" : unit;
        float current = read(keyOf(title), def);
        final Ui.ValueRow row = Ui.valueRow(this, card, pal, title, summary, current, dec);
        row.row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.numberDialog(MainActivity.this, pal, title, u, min, max, step, dec, row.value,
                        new Ui.OnNumber() {
                            @Override
                            public void onNumber(float value) {
                                row.value = value;
                                row.label.setText(Ui.fmt(value, dec));
                                markDirty();
                            }
                        });
            }
        });
        return row;
    }

    private String keyOf(String title) {
        if ("触发区高度".equals(title)) return Cfg.K_ZONE_DP;
        if ("最小下滑距离".equals(title)) return Cfg.K_MIN_DY_DP;
        if ("有效宽度占比".equals(title)) return Cfg.K_WIDTH_RATIO;
        if ("起效速度".equals(title)) return Cfg.K_MIN_VELOCITY;
        if ("最大横向偏移".equals(title)) return Cfg.K_MAX_SLOPE;
        if ("触发冷却".equals(title)) return Cfg.K_COOLDOWN_MS;
        if ("原生胶囊条高度".equals(title)) return Cfg.K_LAUNCHER_HOT_DP;
        return title;
    }

    private float read(String key, float def) {
        try {
            ContentResolver cr = getContentResolver();
            if (Cfg.K_MIN_VELOCITY.equals(key) || Cfg.K_COOLDOWN_MS.equals(key)) {
                return Cfg.intOf(cr, key, Math.round(def));
            }
            return Cfg.floatOf(cr, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    private void markDirty() {
        if (status != null) {
            status.setText("有未保存的更改，点「保存并立即生效」提交。");
        }
    }

    private void loadCurrent() {
        try {
            ContentResolver cr = getContentResolver();
            switchWithoutMark(swEnabled, Cfg.intOf(cr, Cfg.K_ENABLED, 1) != 0);
            switchWithoutMark(swMonitor, Cfg.intOf(cr, Cfg.K_MONITOR_ENABLED, 1) != 0);
            switchWithoutMark(swDebug, Cfg.intOf(cr, Cfg.K_DEBUG, 1) != 0);
            switchWithoutMark(swShadeGuard, Cfg.intOf(cr, Cfg.K_SHADE_GUARD, 1) != 0);
            switchWithoutMark(swClaimZone, Cfg.intOf(cr, Cfg.K_CLAIM_ZONE, 1) != 0);

            rZone.value = Cfg.floatOf(cr, Cfg.K_ZONE_DP, Cfg.DEF_ZONE_DP);
            rMinDy.value = Cfg.floatOf(cr, Cfg.K_MIN_DY_DP, Cfg.DEF_MIN_DY_DP);
            rVelocity.value = Cfg.intOf(cr, Cfg.K_MIN_VELOCITY, Cfg.DEF_MIN_VELOCITY);
            rSlope.value = Cfg.floatOf(cr, Cfg.K_MAX_SLOPE, Cfg.DEF_MAX_SLOPE);
            rWidth.value = Cfg.floatOf(cr, Cfg.K_WIDTH_RATIO, Cfg.DEF_WIDTH_RATIO);
            rCooldown.value = Cfg.intOf(cr, Cfg.K_COOLDOWN_MS, Cfg.DEF_COOLDOWN_MS);
            rHotDp.value = Cfg.floatOf(cr, Cfg.K_LAUNCHER_HOT_DP, Cfg.DEF_LAUNCHER_HOT_DP);

            rZone.label.setText(Ui.fmt(rZone.value, 0));
            rMinDy.label.setText(Ui.fmt(rMinDy.value, 0));
            rVelocity.label.setText(Ui.fmt(rVelocity.value, 0));
            rSlope.label.setText(Ui.fmt(rSlope.value, 1));
            rWidth.label.setText(Ui.fmt(rWidth.value, 2));
            rCooldown.label.setText(Ui.fmt(rCooldown.value, 0));
            rHotDp.label.setText(Ui.fmt(rHotDp.value, 0));

            status.setText("当前值来自系统设置，修改后点下方按钮提交。");
        } catch (Throwable t) {
            status.setText("读取当前值失败：" + t);
        }
    }

    private void switchWithoutMark(Ui.SwitchView sw, boolean v) {
        if (sw != null) {
            sw.setCheckedNoAnim(v);
        }
    }

    private void applyDefaults() {
        switchWithoutMark(swEnabled, true);
        switchWithoutMark(swMonitor, true);
        switchWithoutMark(swDebug, true);
        switchWithoutMark(swShadeGuard, true);
        switchWithoutMark(swClaimZone, true);

        rZone.value = Cfg.DEF_ZONE_DP;
        rMinDy.value = Cfg.DEF_MIN_DY_DP;
        rVelocity.value = Cfg.DEF_MIN_VELOCITY;
        rSlope.value = Cfg.DEF_MAX_SLOPE;
        rWidth.value = Cfg.DEF_WIDTH_RATIO;
        rCooldown.value = Cfg.DEF_COOLDOWN_MS;
        rHotDp.value = Cfg.DEF_LAUNCHER_HOT_DP;

        rZone.label.setText(Ui.fmt(rZone.value, 0));
        rMinDy.label.setText(Ui.fmt(rMinDy.value, 0));
        rVelocity.label.setText(Ui.fmt(rVelocity.value, 0));
        rSlope.label.setText(Ui.fmt(rSlope.value, 1));
        rWidth.label.setText(Ui.fmt(rWidth.value, 2));
        rCooldown.label.setText(Ui.fmt(rCooldown.value, 0));
        rHotDp.label.setText(Ui.fmt(rHotDp.value, 0));
    }

    private void save() {
        Intent i = new Intent(HyperHand.ACTION_SET);
        i.setPackage("com.android.systemui");
        i.putExtra(Cfg.K_ENABLED, swEnabled.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_MONITOR_ENABLED, swMonitor.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_DEBUG, swDebug.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_SHADE_GUARD, swShadeGuard.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_CLAIM_ZONE, swClaimZone.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_ZONE_DP, Ui.fmt(rZone.value, 0));
        i.putExtra(Cfg.K_MIN_DY_DP, Ui.fmt(rMinDy.value, 0));
        i.putExtra(Cfg.K_MIN_VELOCITY, Ui.fmt(rVelocity.value, 0));
        i.putExtra(Cfg.K_MAX_SLOPE, Ui.fmt(rSlope.value, 1));
        i.putExtra(Cfg.K_WIDTH_RATIO, Ui.fmt(rWidth.value, 2));
        i.putExtra(Cfg.K_COOLDOWN_MS, Ui.fmt(rCooldown.value, 0));
        i.putExtra(Cfg.K_LAUNCHER_HOT_DP, Ui.fmt(rHotDp.value, 0));
        sendBroadcast(i);

        status.setText("已提交到系统界面进程，立即生效。可下滑底部区域测试，"
                + "用 logcat -s HyperHand 观察。");
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
