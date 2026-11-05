package io.github.zpan2327.hyperhand;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.provider.Settings;
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
 *
 * 2.2 起按"识别模式 / 触发区 / 高级 / 互斥 / 调试"分组，
 * 常用参数在上，低频参数收进"高级"折叠区。
 */
public class MainActivity extends Activity {

    private Ui.Pal pal;
    private Cfg cur;

    private Ui.SwitchView swEnabled;
    private Ui.SwitchView swMonitor;
    private Ui.SwitchView swDebug;
    private Ui.SwitchView swShadeGuard;
    private Ui.SwitchView swClaimZone;
    private Ui.SwitchView swSlow;
    private Ui.SwitchView swAdaptive;
    private Ui.SwitchView swAdvanced;
    private Ui.SwitchView swBlur;

    private Ui.ChoiceRow cMode;

    private Ui.ValueRow rZone;
    private Ui.ValueRow rWidth;
    private Ui.ValueRow rBias;
    private Ui.ValueRow rMinDy;
    private Ui.ValueRow rVelocity;
    private Ui.ValueRow rSlowDy;
    private Ui.ValueRow rSlowVel;
    private Ui.ValueRow rConf;
    private Ui.ValueRow rSlope;
    private Ui.ValueRow rWindow;
    private Ui.ValueRow rMaxVel;
    private Ui.ValueRow rMono;
    private Ui.ValueRow rCooldown;
    private Ui.ValueRow rHotDp;
    private Ui.ValueRow rBlurRadius;
    private Ui.ValueRow rBlurDim;

    private Ui.ChoiceRow cAnimCurve;
    private Ui.ValueRow rAnimScale;
    private TextView animInfo;

    private LinearLayout advancedBox;
    private TextView gestureLog;
    private TextView status;
    private TextView restartBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.hide();
        }

        pal = Ui.pal(this);
        try {
            cur = Cfg.get(this);
        } catch (Throwable t) {
            cur = new Cfg();
        }

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

        final Ui.SwitchView.OnCheckedChange dirtyOnChange = new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                markDirty();
            }
        };

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
                "总开关。关闭后所有钩子立即失效，系统恢复原生行为", cur.enabled);
        swMonitor = Ui.switchRow(this, cardModule, pal, "扩展触发",
                "在系统的一手模式监视通道上判定底部下滑", cur.monitorEnabled);
        swEnabled.setOnCheckedChange(dirtyOnChange);
        swMonitor.setOnCheckedChange(dirtyOnChange);
        root.addView(cardModule);

        // ---------------------------------------------------------- 识别模式
        root.addView(Ui.sectionTitle(this, pal, "识别模式"));
        LinearLayout cardMode = Ui.card(this, pal);
        cMode = Ui.choiceRow(this, cardMode, pal, "触发模式",
                "决定一组默认敏感度；切换后下面的参数会填入该模式的推荐值",
                Cfg.MODE_NAMES, cur.mode, new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        Ui.singleChoiceDialog(MainActivity.this, pal, "触发模式",
                                "切换会把该模式的推荐值填进下面的参数，保存后生效",
                                Cfg.MODE_NAMES, index, new Ui.OnChoice() {
                                    @Override
                                    public void onChoice(int picked) {
                                        applyMode(picked);
                                    }
                                });
                    }
                });
        root.addView(cardMode);

        LinearLayout cardJudge = Ui.card(this, pal);
        LinearLayout.LayoutParams judgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        judgeLp.topMargin = dp(10);
        root.addView(cardJudge, judgeLp);

        rMinDy = valueRow(cardJudge, "最小下滑距离", "短促快甩所需的最小位移", "dp",
                cur.minDyDp, 0, 1, 60, 1);
        rVelocity = valueRow(cardJudge, "起效速度", "快甩路径的峰值速度门限，0 表示只看距离", "dp/s",
                cur.minVelDp, 0, 0, 800, 10);
        swSlow = Ui.switchRow(this, cardJudge, pal, "慢滑识别",
                "连续慢速下拉也能触发（长距离路径）", cur.slowEnabled);
        swSlow.setOnCheckedChange(new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                markDirty();
            }
        });
        rSlowDy = valueRow(cardJudge, "慢滑所需距离", "慢滑路径要求的最小位移", "dp",
                cur.slowDyDp, 0, 1, 120, 1);
        rSlowVel = valueRow(cardJudge, "慢滑速度下限", "慢滑路径的平均速度门限", "dp/s",
                cur.minAvgVelDp, 0, 0, 400, 5);
        rConf = valueRow(cardJudge, "判定置信度", "综合得分门槛，越低越灵敏、越高越保守", "",
                cur.minConfidence, 2, 0.10f, 1.0f, 0.02f);

        // ---------------------------------------------------------- 触发区
        root.addView(Ui.sectionTitle(this, pal, "触发区"));
        LinearLayout cardZone = Ui.card(this, pal);
        rZone = valueRow(cardZone, "触发区高度", "从屏幕最底部往上算的高度", "dp",
                cur.zoneDp, 0, 20, 140, 1);
        rWidth = valueRow(cardZone, "有效宽度占比", "触发区在屏幕水平方向上的占比，居中", "",
                cur.widthRatio, 2, 0.2f, 1.0f, 0.05f);
        rBias = valueRow(cardZone, "左右偏置", "把有效区域中心左右移动，配合左右手落指习惯", "",
                cur.zoneBias, 2, -0.4f, 0.4f, 0.05f);
        root.addView(cardZone);

        // ---------------------------------------------------------- 外观
        root.addView(Ui.sectionTitle(this, pal, "外观"));
        LinearLayout cardLook = Ui.card(this, pal);
        swBlur = Ui.switchRow(this, cardLook, pal, "模糊壁纸背景",
                "单手模式上方的米白遮罩换成高斯模糊的当前壁纸", cur.blurBg);
        swBlur.setOnCheckedChange(dirtyOnChange);
        rBlurRadius = valueRow(cardLook, "模糊半径", "越大越糊；0 近似纯壁纸原图", "dp",
                cur.blurRadiusDp, 0, 0, 60, 1);
        rBlurDim = valueRow(cardLook, "压暗程度", "在模糊壁纸上叠一层黑，拉开与缩小窗口的层次", "%",
                cur.blurDim, 0, 0, 60, 1);
        root.addView(cardLook);

        // ---------------------------------------------------------- 动画
        root.addView(Ui.sectionTitle(this, pal, "动画"));
        LinearLayout cardAnim = Ui.card(this, pal);
        cAnimCurve = Ui.choiceRow(this, cardAnim, pal, "缓动曲线",
                "按起步速度从慢到快排列；改完即时生效，可来回对比",
                Cfg.ANIM_CURVE_SHORT, cur.animCurve, new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        Ui.singleChoiceDialog(MainActivity.this, pal, "缓动曲线",
                                Cfg.ANIM_CURVE_RAW_DESC + "\n\n" + Cfg.ANIM_CURVE_SUMMARIES[index],
                                Cfg.ANIM_CURVE_NAMES, index, new Ui.OnChoice() {
                                    @Override
                                    public void onChoice(int picked) {
                                        cAnimCurve.value = picked;
                                        cAnimCurve.label.setText(Cfg.ANIM_CURVE_SHORT[picked]);
                                        markDirty();
                                        applyAnimNow();
                                    }
                                });
                    }
                });
        rAnimScale = valueRow(cardAnim, "动画时长", "1.00 = 系统原生 600ms；越大越慢，改动即时生效", "×",
                cur.animScale, 2, 0.60f, 2.00f, 0.05f);
        // 覆盖通用数值行：动画时长要即时下发，方便一边看一边调
        rAnimScale.row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.numberDialog(MainActivity.this, pal, "动画时长", "×", 0.60f, 2.00f, 0.05f, 2,
                        rAnimScale.value, new Ui.OnNumber() {
                            @Override
                            public void onNumber(float v2) {
                                rAnimScale.value = v2;
                                rAnimScale.label.setText(Ui.fmt(v2, 2));
                                markDirty();
                                applyAnimNow();
                            }
                        });
            }
        });
        animInfo = Ui.block(this, pal, animInfoText());
        cardAnim.addView(animInfo);
        root.addView(cardAnim);

        // ---------------------------------------------------------- 互斥
        root.addView(Ui.sectionTitle(this, pal, "互斥"));
        LinearLayout cardGuard = Ui.card(this, pal);
        swShadeGuard = Ui.switchRow(this, cardGuard, pal, "与下拉手势互斥",
                "手势过程中出现通知/控制中心的意图就放弃单手模式", cur.shadeGuard);
        swClaimZone = Ui.switchRow(this, cardGuard, pal, "底部区域优先单手模式",
                "触发区内的桌面下拉手势让位，不再打开通知/控制中心", cur.claimZone);
        rHotDp = valueRow(cardGuard, "原生胶囊条高度", "覆盖系统 25dp 的底部窗口，0 表示不改动", "dp",
                cur.launcherHotDp, 0, 0, 40, 1);
        swShadeGuard.setOnCheckedChange(dirtyOnChange);
        swClaimZone.setOnCheckedChange(dirtyOnChange);
        root.addView(cardGuard);

        // ---------------------------------------------------------- 高级（可折叠）
        advancedBox = new LinearLayout(this);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.setVisibility(View.GONE);

        final LinearLayout cardToggle = Ui.card(this, pal);
        swAdvanced = Ui.switchRow(this, cardToggle, pal, "显示高级参数",
                "速度采样窗口、轨迹稳定性、冷却等", false);
        swAdvanced.setOnCheckedChange(new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                advancedBox.setVisibility(checked ? View.VISIBLE : View.GONE);
            }
        });
        LinearLayout.LayoutParams advLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        advLp.topMargin = dp(10);
        root.addView(cardToggle, advLp);
        root.addView(advancedBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout cardAdv = Ui.card(this, pal);
        LinearLayout.LayoutParams advCardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        advCardLp.topMargin = dp(10);
        advancedBox.addView(cardAdv, advCardLp);

        rWindow = valueRow(cardAdv, "速度采样窗口", "用多长的滑动窗口估速度，越大越稳、越小越灵敏", "ms",
                cur.velWindowMs, 0, 20, 200, 5);
        rMaxVel = valueRow(cardAdv, "速度上限", "超过该值按噪声截断，防止单帧抖动炸出假峰值", "dp/s",
                cur.maxVelocityDp, 0, 300, 8000, 100);
        rMono = valueRow(cardAdv, "轨迹稳定度", "净位移占路程的比例下限，低于它判为来回蹭", "",
                cur.minMonotonic, 2, 0.40f, 1.0f, 0.02f);
        rSlope = valueRow(cardAdv, "最大横向偏移", "|dx| / dy 超过该值视为斜滑，不触发", "倍",
                cur.maxSlope, 1, 0.5f, 4.0f, 0.1f);
        rCooldown = valueRow(cardAdv, "触发冷却", "两次触发之间的最小间隔", "ms",
                cur.cooldownMs, 0, 0, 2000, 50);
        swAdaptive = Ui.switchRow(this, cardAdv, pal, "自适应阈值",
                "干脆利落的快手势略放宽，拖沓的长手势略收紧", cur.adaptive);
        swAdaptive.setOnCheckedChange(dirtyOnChange);

        // ---------------------------------------------------------- 调试
        root.addView(Ui.sectionTitle(this, pal, "调试"));
        LinearLayout cardDebug = Ui.card(this, pal);
        swDebug = Ui.switchRow(this, cardDebug, pal, "调试日志",
                "用 logcat -s HyperHand 观察判定过程，并记录最近手势", cur.debug);
        swDebug.setOnCheckedChange(dirtyOnChange);
        Ui.ChoiceRow rRefresh = Ui.choiceRow(this, cardDebug, pal, "最近手势",
                "点这一行读取系统里记录的上一次判定结果", new String[] {"刷新"}, 0,
                new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        refreshGestureLog();
                    }
                });
        rRefresh.label.setTextColor(pal.primary);
        Ui.choiceRow(this, cardDebug, pal, "预览遮罩",
                "立刻进入一次单手模式，直接看背景效果", new String[] {"进入"}, 0,
                new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        sendAction("trigger");
                    }
                });
        Ui.choiceRow(this, cardDebug, pal, "退出单手模式",
                "立刻退出，恢复正常窗口", new String[] {"退出"}, 0,
                new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        sendAction("stop");
                    }
                });
        gestureLog = Ui.block(this, pal, "还没有记录。开启调试日志后，在屏幕底部下滑一次再看这里。");
        cardDebug.addView(gestureLog);
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

        TextView reset = Ui.secondaryButton(this, pal, "恢复默认参数");
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

        restartBtn = Ui.secondaryButton(this, pal, "重启两个作用域");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        slp.topMargin = dp(10);
        restartBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmRestart();
            }
        });
        actions.addView(restartBtn, slp);

        TextView restartHint = Ui.text(this,
                "重启系统界面与桌面进程，让新装/新改的钩子重新注入。需要 root 授权（首次会弹授权框）。",
                13, pal.summary, false);
        restartHint.setPadding(dp(4), dp(8), dp(4), 0);
        restartHint.setLineSpacing(dp(3), 1f);
        actions.addView(restartHint);
        root.addView(actions);

        status = Ui.text(this, "当前值来自系统设置，修改后点下方按钮提交。", 13, pal.summary, false);
        status.setPadding(dp(4), dp(12), dp(4), 0);
        status.setLineSpacing(dp(3), 1f);
        root.addView(status);

        // ---------------------------------------------------------- 说明
        root.addView(Ui.sectionTitle(this, pal, "说明"));
        LinearLayout cardTips = Ui.card(this, pal);
        TextView tips = Ui.text(this, "• 在 Vector / LSPosed 里启用 HyperHand 即可：作用域已经静态声明，\n"
                + "  不用手动勾「系统界面 / 小米桌面」，也没法挂到别的应用上。\n"
                + "• 保存后立即生效，不需要重启；改动由系统界面进程写入 Settings.Global。\n"
                + "• 自然模式：底部连续下滑即可，不要求甩；灵敏模式门槛更低；防误触模式更保守。\n"
                + "• 速度单位是 dp/s，与屏幕密度无关；旧版的 px/s 参数会自动折算过来。\n"
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

        refreshGestureLog();
        return scroll;
    }

    /** 数值行工厂：负责弹对话框、回填显示。 */
    private Ui.ValueRow valueRow(LinearLayout card, final String title, String summary, final String unit,
                                 float value, final int dec, final float min, final float max,
                                 final float step) {
        final String u = unit == null ? "" : unit;
        final Ui.ValueRow row = Ui.valueRow(this, card, pal, title, summary, value, dec);
        row.row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.numberDialog(MainActivity.this, pal, title, u, min, max, step, dec, row.value,
                        new Ui.OnNumber() {
                            @Override
                            public void onNumber(float v2) {
                                row.value = v2;
                                row.label.setText(Ui.fmt(v2, dec));
                                markDirty();
                            }
                        });
            }
        });
        return row;
    }

    /** 切换模式：把该模式的推荐值填进参数行（保存后才提交）。 */
    private void applyMode(int mode) {
        cMode.value = mode;
        cMode.label.setText(Cfg.MODE_SHORT[mode]);
        Cfg.Preset p = Cfg.preset(mode);
        rMinDy.value = p.minDyDp;
        rVelocity.value = p.velLowDp;
        rSlowDy.value = p.slowDyDp;
        rSlowVel.value = p.slowLowDp;
        rConf.value = p.confidence;
        relabel();
        markDirty();
    }

    private void relabel() {
        rMinDy.label.setText(Ui.fmt(rMinDy.value, 0));
        rVelocity.label.setText(Ui.fmt(rVelocity.value, 0));
        rSlowDy.label.setText(Ui.fmt(rSlowDy.value, 0));
        rSlowVel.label.setText(Ui.fmt(rSlowVel.value, 0));
        rConf.label.setText(Ui.fmt(rConf.value, 2));
        rZone.label.setText(Ui.fmt(rZone.value, 0));
        rWidth.label.setText(Ui.fmt(rWidth.value, 2));
        rBias.label.setText(Ui.fmt(rBias.value, 2));
        rSlope.label.setText(Ui.fmt(rSlope.value, 1));
        rWindow.label.setText(Ui.fmt(rWindow.value, 0));
        rMaxVel.label.setText(Ui.fmt(rMaxVel.value, 0));
        rMono.label.setText(Ui.fmt(rMono.value, 2));
        rCooldown.label.setText(Ui.fmt(rCooldown.value, 0));
        rHotDp.label.setText(Ui.fmt(rHotDp.value, 0));
        rBlurRadius.label.setText(Ui.fmt(rBlurRadius.value, 0));
        rBlurDim.label.setText(Ui.fmt(rBlurDim.value, 0));
        rAnimScale.label.setText(Ui.fmt(rAnimScale.value, 2));
    }

    /**
     * 动画参数单独即时下发：调曲线/时长本来就是为了"试手感"，
     * 每次都要点保存再回桌面下滑一次太折腾，所以这两项改了就直接生效。
     */
    private void applyAnimNow() {
        try {
            Intent i = new Intent(HyperHand.ACTION_SET);
            i.setPackage("com.android.systemui");
            i.putExtra(Cfg.K_ANIM_CURVE, String.valueOf(cAnimCurve.value));
            i.putExtra(Cfg.K_ANIM_SCALE, Ui.fmt(rAnimScale.value, 2));
            sendBroadcast(i);
            if (animInfo != null) {
                animInfo.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (animInfo != null) animInfo.setText(animInfoText());
                    }
                }, 400);
            }
        } catch (Throwable t) {
            // 纯外观，发不出去就等下次保存
        }
    }

    /**
     * 动画区底部的说明：优先显示 SystemUI 写回来的真实系统值
     * （Settings.Global 的 oh_tuner_anim_info），读不到就退回已知的静态数据。
     */
    private String animInfoText() {
        try {
            String live = Settings.Global.getString(getContentResolver(), Cfg.K_ANIM_INFO);
            if (live != null && live.trim().length() > 0) return live;
        } catch (Throwable ignored) {
            // 下面走静态说明
        }
        return "系统原生 " + Cfg.SYSTEM_ANIM_MS + "ms · " + Cfg.ANIM_CURVE_RAW_DESC
                + "\n保存并在底部下滑一次后，这里会显示实际生效的时长。";
    }

    private void markDirty() {
        if (status != null) {
            status.setText("有未保存的更改，点「保存并立即生效」提交。");
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
        switchWithoutMark(swSlow, true);
        switchWithoutMark(swAdaptive, true);
        switchWithoutMark(swBlur, true);

        cMode.value = Cfg.MODE_NATURAL;
        cMode.label.setText(Cfg.MODE_SHORT[Cfg.MODE_NATURAL]);
        Cfg.Preset p = Cfg.preset(Cfg.MODE_NATURAL);
        rMinDy.value = p.minDyDp;
        rVelocity.value = p.velLowDp;
        rSlowDy.value = p.slowDyDp;
        rSlowVel.value = p.slowLowDp;
        rConf.value = p.confidence;
        rZone.value = Cfg.DEF_ZONE_DP;
        rWidth.value = Cfg.DEF_WIDTH_RATIO;
        rBias.value = Cfg.DEF_ZONE_BIAS;
        rSlope.value = Cfg.DEF_MAX_SLOPE;
        rWindow.value = Cfg.DEF_VEL_WINDOW_MS;
        rMaxVel.value = Cfg.DEF_MAX_VELOCITY;
        rMono.value = Cfg.DEF_MIN_MONOTONIC;
        rCooldown.value = Cfg.DEF_COOLDOWN_MS;
        rHotDp.value = Cfg.DEF_LAUNCHER_HOT_DP;
        rBlurRadius.value = Cfg.DEF_BLUR_RADIUS;
        rBlurDim.value = Cfg.DEF_BLUR_DIM;
        cAnimCurve.value = Cfg.DEF_ANIM_CURVE;
        cAnimCurve.label.setText(Cfg.ANIM_CURVE_SHORT[Cfg.DEF_ANIM_CURVE]);
        rAnimScale.value = Cfg.DEF_ANIM_SCALE;
        relabel();
    }

    /** 读取 SystemUI 进程写下的最近手势记录。 */
    private void refreshGestureLog() {
        if (gestureLog == null) return;
        try {
            String s = Settings.Global.getString(getContentResolver(), Cfg.K_LAST_GESTURE);
            if (s == null || s.trim().length() == 0) {
                gestureLog.setText("还没有记录。开启调试日志后，在屏幕底部下滑一次再看这里。");
            } else {
                gestureLog.setText(s);
            }
        } catch (Throwable t) {
            gestureLog.setText("读取失败：" + t);
        }
    }

    private void save() {
        Intent i = new Intent(HyperHand.ACTION_SET);
        i.setPackage("com.android.systemui");
        i.putExtra(Cfg.K_ENABLED, swEnabled.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_MONITOR_ENABLED, swMonitor.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_DEBUG, swDebug.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_SHADE_GUARD, swShadeGuard.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_CLAIM_ZONE, swClaimZone.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_SLOW_ENABLED, swSlow.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_ADAPTIVE, swAdaptive.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_MODE, String.valueOf(cMode.value));
        i.putExtra(Cfg.K_MIN_DY_DP, Ui.fmt(rMinDy.value, 0));
        i.putExtra(Cfg.K_MIN_VEL_DP, Ui.fmt(rVelocity.value, 0));
        i.putExtra(Cfg.K_SLOW_DY_DP, Ui.fmt(rSlowDy.value, 0));
        i.putExtra(Cfg.K_MIN_AVG_VEL, Ui.fmt(rSlowVel.value, 0));
        i.putExtra(Cfg.K_MIN_CONFIDENCE, Ui.fmt(rConf.value, 2));
        i.putExtra(Cfg.K_ZONE_DP, Ui.fmt(rZone.value, 0));
        i.putExtra(Cfg.K_WIDTH_RATIO, Ui.fmt(rWidth.value, 2));
        i.putExtra(Cfg.K_ZONE_BIAS, Ui.fmt(rBias.value, 2));
        i.putExtra(Cfg.K_MAX_SLOPE, Ui.fmt(rSlope.value, 1));
        i.putExtra(Cfg.K_VEL_WINDOW_MS, Ui.fmt(rWindow.value, 0));
        i.putExtra(Cfg.K_MAX_VELOCITY, Ui.fmt(rMaxVel.value, 0));
        i.putExtra(Cfg.K_MIN_MONOTONIC, Ui.fmt(rMono.value, 2));
        i.putExtra(Cfg.K_COOLDOWN_MS, Ui.fmt(rCooldown.value, 0));
        i.putExtra(Cfg.K_LAUNCHER_HOT_DP, Ui.fmt(rHotDp.value, 0));
        i.putExtra(Cfg.K_BLUR_BG, swBlur.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_BLUR_RADIUS, Ui.fmt(rBlurRadius.value, 0));
        i.putExtra(Cfg.K_BLUR_DIM, Ui.fmt(rBlurDim.value, 0));
        i.putExtra(Cfg.K_ANIM_SCALE, Ui.fmt(rAnimScale.value, 2));
        i.putExtra(Cfg.K_ANIM_CURVE, String.valueOf(cAnimCurve.value));
        sendBroadcast(i);

        status.setText("已提交到系统界面进程，立即生效。可下滑底部区域测试，"
                + "点「最近手势」查看判定结果。");
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        if (animInfo != null) {
            // 广播是异步的，SystemUI 写回新的生效值要一点点时间
            animInfo.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (animInfo != null) animInfo.setText(animInfoText());
                }
            }, 600);
        }
    }

    // ---------------------------------------------------------------- 重启作用域

    /** 发一条调试动作（trigger / stop / reload），由 SystemUI 进程里的模块执行。 */
    private void sendAction(String action) {
        broadcastAction(action);
        String t;
        if ("trigger".equals(action)) {
            t = "已请求进入单手模式";
        } else if ("stop".equals(action)) {
            t = "已请求退出";
        } else {
            t = "已请求重启系统界面";
        }
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }

    private void broadcastAction(String action) {
        Intent i = new Intent(HyperHand.ACTION_SET);
        i.setPackage("com.android.systemui");
        i.putExtra(Cfg.K_ACTION, action);
        sendBroadcast(i);
    }

    private void confirmRestart() {
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

    private void doRestart() {
        if (restartBtn != null) {
            restartBtn.setText("重启中…");
            restartBtn.setEnabled(false);
        }
        if (status != null) {
            status.setText("正在重启系统界面与桌面……若弹出 root 授权请允许。");
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                // killall 只是结束进程：系统会立刻把 SystemUI 拉起来，
                // 桌面在收到 HOME 后重新启动，两边都会重新加载模块。
                final String err = su("killall com.android.systemui; killall com.miui.home; "
                        + "sleep 1; input keyevent 3", 25000L);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (restartBtn != null) {
                            restartBtn.setText("重启两个作用域");
                            restartBtn.setEnabled(true);
                        }
                        if (err == null) {
                            if (status != null) {
                                status.setText("已结束两个进程，钩子会在它们重启后重新注入。"
                                        + "现在可以下滑底部区域试试。");
                            }
                            Toast.makeText(MainActivity.this, "已重启", Toast.LENGTH_SHORT).show();
                        } else {
                            // 没有 root 就退回"让模块自己退出"：SystemUI 是 persistent 进程，
                            // 自杀后系统会立刻拉起，钩子同样会重新加载（桌面侧这次动不了）。
                            broadcastAction("reload");
                            if (status != null) {
                                status.setText("没有 root：" + err
                                        + "\n已改为让系统界面自行重启（桌面侧钩子未刷新，可稍后手动重启）。");
                            }
                            Toast.makeText(MainActivity.this, "已请求系统界面重启", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 跑一条 root 命令：成功返回 null，失败返回原因。
     *
     * KernelSU / Magisk 的 su 路径不统一（有的只在授权后的命名空间里可见），
     * 所以按常见位置依次尝试；第一个能起来的就用它。
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
                if (code == 0) return null;
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

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "2.3";
        }
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }
}
