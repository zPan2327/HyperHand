package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「设置」页：项目现有的全部参数，按"模块 / 识别 / 触发区 / 外观 / 动效 /
 * 模糊区手势 / 互斥 / 高级"重新分组（调试模块已移除，见关于页隐藏入口）。
 *
 * 交互：所有改动即时通过广播交给 SystemUI 进程写入 Settings.Global，
 * 无需二次确认；动画曲线与时长额外即时下发，方便"改一点、滑一下"地试手感。
 */
final class SettingsPage {

    private final MainActivity host;
    private final Theme.Colors pal;
    private final Context c;
    private final Ui.Page page;

    private Ui.SwitchView swEnabled;
    private Ui.SwitchView swMonitor;
    private Ui.SwitchView swShadeGuard;
    private Ui.SwitchView swClaimZone;
    private Ui.SwitchView swSlow;
    private Ui.SwitchView swAdaptive;
    private Ui.SwitchView swAdvanced;
    private Ui.SwitchView swBlur;
    private Ui.SwitchView swEndBoost;
    private Ui.SwitchView swZoneRatio;
    private Ui.SwitchView swTopShade;
    private Ui.SwitchView swSlide;

    private Ui.ChoiceRow cMode;
    private Ui.ChoiceRow cAnimCurve;
    private Ui.ChoiceRow cTopShadeSide;

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
    private Ui.ValueRow rAnimScale;
    private Ui.ValueRow rEndZone;
    private Ui.ValueRow rZoneRatio;
    private Ui.ValueRow rTopShadeDy;
    private Ui.ValueRow rSlideAngle;
    private Ui.ValueRow rSlideSlop;
    private Ui.ValueRow rSlideTravel;

    /**
     * 「显示高级参数」是否展开 —— **只在本进程内记忆**，不落盘：
     * 冷启动一律收起（默认关），但手动打开后，切主题 / 从别的页面回来导致
     * Activity 重建时不该把它又收回去。与配置值无关，也不参与广播。
     */
    private static boolean sAdvancedOpen;

    private Ui.SwitchView swKeyMap;
    private LinearLayout keyMapBox;
    private LinearLayout oneHandBox;
    private LinearLayout slideBox;
    private LinearLayout advancedBox;
    private TextView animInfo;
    private TextView downGap;
    private TextView restartBtn;
    private TextView statusText;

    SettingsPage(MainActivity host) {
        this.host = host;
        this.c = host;
        this.pal = host.pal;
        this.page = Ui.page(c, pal);
        build();
    }

    View view() {
        return page.scroll;
    }

    // ---------------------------------------------------------------- 构建

    private void build() {
        Cfg cur = host.cfg();

        // 版面：三个功能各**一张卡片**。卡片第一行是这个模块的总开关，开关下面是它的
        // 全部设置项；关掉开关时容器高度收到 0（卡片跟着变矮），打开时再长回来。
        //
        // 以前是「开关一张卡 + 设置一张卡」，中间还夹着分组标题，整页看起来被切成两半；
        // 现在同一张卡内部用「卡内分段标题」（{@link Ui#cardSection}）区分段落，不另起卡片。
        //
        // 三个开关互相独立：
        //   按键映射     → hyperhand_sk_master（SmartKey 自己的总开关）
        //   单手模式     → Cfg.enabled（oh_tuner_enabled）
        //   快捷菜单滑选 → Cfg.slideEnabled
        // 折叠只把 View 高度收掉，值仍留在各自的行里，提交时照常写回，不会丢参数。

        // ================================================== 1. 按键映射
        page.add(Ui.section(c, pal, "按键映射"));
        LinearLayout cardKey = Ui.card(c, pal);
        swKeyMap = Ui.switchRow(c, cardKey, pal, "启用按键映射",
                "关闭后音量键恢复系统默认功能", smartKeyMaster());

        keyMapBox = bodyBox();
        Ui.navRow(c, keyMapBox, pal, -1, "规则与按键",
                smartKeySummary(), null,
                new Runnable() {
                    @Override
                    public void run() {
                        try {
                            c.startActivity(new Intent(c,
                                    io.github.zpan2327.hyperhand.smartkey.SmartKeyActivity.class));
                        } catch (Throwable ignored) {
                        }
                    }
                });
        cardKey.addView(keyMapBox, fullWidth());
        page.add(cardKey);

        swKeyMap.setOnCheckedChange(new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                setSmartKeyMaster(checked);
                expand(keyMapBox, swKeyMap, checked);
            }
        });

        // ================================================== 2. 单手模式增强
        page.add(Ui.section(c, pal, "单手模式增强"));
        LinearLayout cardOne = Ui.card(c, pal);
        swEnabled = Ui.switchRow(c, cardOne, pal, "启用单手模式增强",
                "从屏幕底部下滑触发；关闭后恢复系统手势", cur.enabled);

        oneHandBox = bodyBox();
        cardOne.addView(oneHandBox, fullWidth());

        // ---------------------------------------------------------- 常用（默认展开）
        // 卡内 30+ 行全平铺时，「模糊壁纸」这类常用项要滑三四屏才找得到，被淹没在
        // 阈值里。这里只留日常会动的四项，其余全部收进「显示高级参数」——
        // 分组不变，只是不再默认占屏。
        cMode = Ui.choiceRow(c, oneHandBox, pal, "触发模式",
                "选择后写入该模式预设参数",
                Cfg.MODE_SHORT, cur.mode, new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        // 说明统一取 Cfg.MODE_SUMMARIES，别在这里再抄一份（两处容易写岔）。
                        Ui.choiceDropdown(host, cMode, Cfg.MODE_NAMES,
                                Cfg.MODE_SUMMARIES, new Ui.OnChoice() {
                                    @Override
                                    public void onChoice(int picked) {
                                        applyMode(picked);
                                    }
                                });
                    }
                });
        swBlur = Ui.switchRow(c, oneHandBox, pal, "模糊壁纸背景",
                "以模糊壁纸替代米白遮罩", cur.blurBg);
        dirty(swBlur);
        swTopShade = Ui.switchRow(c, oneHandBox, pal, "下滑展开面板",
                "从触发区下滑直接拉出面板", cur.topShade);
        dirty(swTopShade);

        swAdvanced = Ui.switchRow(c, oneHandBox, pal, "显示高级参数",
                "识别阈值、触发区、外观与算法常量", sAdvancedOpen);

        advancedBox = bodyBox();
        advancedBox.setVisibility(sAdvancedOpen ? View.VISIBLE : View.GONE);

        // ---------------------------------------------------------- 识别判定
        advancedBox.addView(Ui.cardSection(c, pal, "识别判定"));
        swMonitor = Ui.switchRow(c, advancedBox, pal, "扩展触发",
                "由系统单手模式判定下滑", cur.monitorEnabled);
        dirty(swMonitor);
        rMinDy = numberRow(advancedBox, "最小下滑距离", "快速下滑触发的最小距离", "dp",
                cur.minDyDp, 0, 1, 60, 1);
        rVelocity = numberRow(advancedBox, "触发速度", "0 表示仅按距离判定", "dp/s",
                cur.minVelDp, 0, 0, 800, 10);
        swSlow = Ui.switchRow(c, advancedBox, pal, "慢滑识别",
                "慢速长距离下拉也可触发", cur.slowEnabled);
        dirty(swSlow);
        rSlowDy = numberRow(advancedBox, "慢滑所需距离", "慢滑触发所需的最小位移", "dp",
                cur.slowDyDp, 0, 1, 120, 1);
        rSlowVel = numberRow(advancedBox, "慢滑速度下限", "慢滑的平均速度下限", "dp/s",
                cur.minAvgVelDp, 0, 0, 400, 5);
        swEndBoost = Ui.switchRow(c, advancedBox, pal, "要求拖到底边",
                "未拖到底边时按页面滚动处理", cur.endBoost);
        dirty(swEndBoost);
        rEndZone = numberRow(advancedBox, "贴底判定距离", "距底边小于该值视为到底", "dp",
                cur.endZoneDp, 0, 8, 80, 1);
        rConf = numberRow(advancedBox, "判定置信度", "越低越灵敏，越高越严格", "",
                cur.minConfidence, 2, 0.10f, 1.0f, 0.02f);

        // ---------------------------------------------------------- 触发区
        advancedBox.addView(Ui.cardSection(c, pal, "触发区"));
        swZoneRatio = Ui.switchRow(c, advancedBox, pal, "延伸至 Dock 上方",
                "按屏幕比例计算，覆盖底部 Dock", cur.zoneRatioMode);
        dirty(swZoneRatio);
        rZoneRatio = numberRow(advancedBox, "屏幕占比", "默认 15%，约等于 Dock 上沿高度", "%",
                cur.zoneRatio * 100f, 0, 5, 30, 1);
        rZone = numberRow(advancedBox, "触发区高度", "固定高度；关闭上方开关后生效", "dp",
                cur.zoneDp, 0, 20, 200, 1);
        rWidth = numberRow(advancedBox, "宽度占比", "触发区宽度，水平居中", "%",
                cur.widthRatio * 100f, 0, 20, 100, 5);
        rBias = numberRow(advancedBox, "左右偏置", "按左右手习惯偏移中心", "%",
                cur.zoneBias * 100f, 0, -40, 40, 5);
        downGap = Ui.hintBlock(c, pal, downGapText(), pal.primary);
        advancedBox.addView(downGap);

        // ---------------------------------------------------------- 外观与动效
        advancedBox.addView(Ui.cardSection(c, pal, "外观与动效"));
        rBlurRadius = numberRow(advancedBox, "模糊半径", "越大越模糊，0 为不模糊", "dp",
                cur.blurRadiusDp, 0, 0, 60, 1);
        rBlurDim = numberRow(advancedBox, "压暗程度", "在模糊壁纸上叠加暗色", "%",
                cur.blurDim, 0, 0, 60, 1);
        cAnimCurve = Ui.choiceRow(c, advancedBox, pal, "缓动曲线",
                "由慢到快排列，改动立即生效",
                Cfg.ANIM_CURVE_SHORT, cur.animCurve, new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        Ui.choiceDropdown(host, cAnimCurve, Cfg.ANIM_CURVE_NAMES,
                                Cfg.ANIM_CURVE_SUMMARIES, new Ui.OnChoice() {
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
        rAnimScale = Ui.valueRow(c, advancedBox, pal, "动画时长",
                "1.00 为原生 600ms，越大越慢", "×", cur.animScale, 2);
        realTime(rAnimScale, "动画时长", "1.00 为原生 600ms，越大越慢",
                "×", 0.60f, 2.00f, 0.05f, 2);
        animInfo = Ui.block(c, pal, animInfoText());
        Ui.addRow(c, advancedBox, pal, animInfo);

        // ---------------------------------------------------------- 面板手势与互斥
        advancedBox.addView(Ui.cardSection(c, pal, "面板手势与互斥"));
        rTopShadeDy = numberRow(advancedBox, "触发距离", "判定为下滑所需的最小位移", "dp",
                cur.topShadeDyDp, 0, 8, 80, 1);
        cTopShadeSide = Ui.choiceRow(c, advancedBox, pal, "展开方向",
                "按下滑位置选择面板",
                Cfg.TOP_SIDE_SHORT, cur.topShadeSide, new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        Ui.choiceDropdown(host, cTopShadeSide, Cfg.TOP_SIDE_NAMES,
                                Cfg.TOP_SIDE_SUMMARIES, new Ui.OnChoice() {
                                    @Override
                                    public void onChoice(int picked) {
                                        cTopShadeSide.value = picked;
                                        cTopShadeSide.label.setText(Cfg.TOP_SIDE_SHORT[picked]);
                                        markDirty();
                                    }
                                });
                    }
                });
        swShadeGuard = Ui.switchRow(c, advancedBox, pal, "与下拉手势互斥",
                "面板手势优先，放弃单手模式", cur.shadeGuard);
        swClaimZone = Ui.switchRow(c, advancedBox, pal, "底部区域优先单手",
                "触发区内下滑按单手处理", cur.claimZone);
        rHotDp = numberRow(advancedBox, "原生胶囊条高度", "0 表示保持系统默认", "dp",
                cur.launcherHotDp, 0, 0, 40, 1);
        dirty(swShadeGuard);
        dirty(swClaimZone);

        // ---------------------------------------------------------- 算法细节
        advancedBox.addView(Ui.cardSection(c, pal, "算法细节"));
        swAdaptive = Ui.switchRow(c, advancedBox, pal, "自适应阈值",
                "快速手势放宽，慢速手势收紧", cur.adaptive);
        dirty(swAdaptive);
        rWindow = numberRow(advancedBox, "速度采样窗口", "越大越稳定，越小越灵敏", "ms",
                cur.velWindowMs, 0, 20, 200, 5);
        rMaxVel = numberRow(advancedBox, "速度上限", "超过该值按噪声截断", "dp/s",
                cur.maxVelocityDp, 0, 300, 8000, 100);
        rMono = numberRow(advancedBox, "轨迹稳定度", "净位移占轨迹长度的下限", "",
                cur.minMonotonic, 2, 0.40f, 1.0f, 0.02f);
        rSlope = numberRow(advancedBox, "最大横向偏移", "超过该值视为斜滑", "倍",
                cur.maxSlope, 1, 0.5f, 4.0f, 0.1f);
        rCooldown = numberRow(advancedBox, "触发冷却", "两次触发之间的最小间隔", "ms",
                cur.cooldownMs, 0, 0, 2000, 50);

        oneHandBox.addView(advancedBox, fullWidth());

        page.add(cardOne);

        swAdvanced.setOnCheckedChange(new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                // 只切视图，不 markDirty：收起后各行的值仍留着，submit 照常按原值写回，
                // 「关掉高级参数」不等于「恢复默认」。
                sAdvancedOpen = checked;
                expand(advancedBox, swAdvanced, checked);
            }
        });

        swEnabled.setOnCheckedChange(new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                markDirty();
                applyEnabledStates();
                expand(oneHandBox, swEnabled, checked);
            }
        });

        // ================================================== 3. 快捷菜单滑选
        page.add(Ui.section(c, pal, "快捷菜单滑选"));
        LinearLayout cardSlide = Ui.card(c, pal);
        swSlide = Ui.switchRow(c, cardSlide, pal, "启用长按滑选菜单项",
                "滑到菜单项抬起即选中，需重启桌面", cur.slideEnabled);

        slideBox = bodyBox();
        rSlideAngle = numberRow(slideBox, "滑选判定夹角",
                "与菜单方向的夹角上限，越小越严格", "°",
                cur.slideAngleDeg, 0, 15, 90, 5);
        rSlideSlop = numberRow(slideBox, "命中容差",
                "菜单项向外扩展的命中范围", "dp",
                cur.slideSlopDp, 0, 2, 40, 1);
        rSlideTravel = numberRow(slideBox, "起判位移",
                "开始判定所需的最小位移", "dp",
                cur.slideMinTravelDp, 0, 4, 60, 1);
        cardSlide.addView(slideBox, fullWidth());
        page.add(cardSlide);

        swSlide.setOnCheckedChange(new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                markDirty();
                applyEnabledStates();
                expand(slideBox, swSlide, checked);
            }
        });

        // ================================================== 底部操作
        // 改动即时保存并生效，不再需要「保存并立即生效」按钮 / 胶囊二次确认。
        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, Theme.dp(c, 20), 0, 0);

        TextView reset = Ui.secondaryButton(c, pal, "恢复默认参数");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(c, 48));
        rlp.topMargin = Theme.dp(c, 10);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyDefaults();
                host.save();
            }
        });
        actions.addView(reset, rlp);

        restartBtn = Ui.secondaryButton(c, pal, "重启系统界面与桌面");
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(c, 48));
        slp.topMargin = Theme.dp(c, 10);
        restartBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.restartScopes();
            }
        });
        actions.addView(restartBtn, slp);

        TextView restartHint = Ui.text(c,
                "重新注入钩子，需要 root 授权",
                Theme.T_CAP, pal.summary, false);
        restartHint.setPadding(Theme.dp(c, 4), Theme.dp(c, 10), Theme.dp(c, 4), 0);
        restartHint.setLineSpacing(Theme.dp(c, 3), 1f);
        actions.addView(restartHint);
        page.add(actions);

        statusText = Ui.text(c, "改动自动保存并立即生效",
                Theme.T_CAP, pal.section, false);
        statusText.setPadding(Theme.dp(c, 4), Theme.dp(c, 12), Theme.dp(c, 4), 0);
        statusText.setLineSpacing(Theme.dp(c, 3), 1f);
        page.add(statusText);

        applyEnabledStates();
        // 首次进入按当前配置决定各模块内容的展开状态（不做动画，避免冷启时闪一下）。
        keyMapBox.setVisibility(swKeyMap.isChecked() ? View.VISIBLE : View.GONE);
        oneHandBox.setVisibility(swEnabled.isChecked() ? View.VISIBLE : View.GONE);
        slideBox.setVisibility(swSlide.isChecked() ? View.VISIBLE : View.GONE);
    }

    /** 卡片内可折叠容器的布局参数：宽度跟随卡片，高度由内容（或折叠动画）决定。 */
    private static LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 一个模块的可折叠内容容器。 */
    private LinearLayout bodyBox() {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    // ---------------------------------------------------------------- 按键映射主开关

    /** SmartKey 自己的总开关（Settings.Global 里的 hyperhand_sk_master）。 */
    private boolean smartKeyMaster() {
        try {
            return new io.github.zpan2327.hyperhand.smartkey.data.RuleRepository(c)
                    .isMasterEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 写 SmartKey 总开关（Settings.Global 的 hyperhand_sk_master）。
     *
     * <p>写 Global 需要 WRITE_SECURE_SETTINGS（signature|development 级），普通安装拿不到，
     * 所以放在后台线程：先直接写，失败再走 su 授权后重写一次。**写入失败必须把开关回退**，
     * 不能让界面显示「已开启」而 Hook 侧读到的还是关闭 —— 那是最难排查的一种状态。
     */
    private void setSmartKeyMaster(final boolean enabled) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    ok = new io.github.zpan2327.hyperhand.smartkey.data.RuleRepository(c)
                            .setMasterEnabled(enabled);
                } catch (Throwable ignored) {
                }
                final boolean written = ok;
                host.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!written) {
                            // 回退开关与内容区，并把原因说清楚。
                            if (swKeyMap != null) {
                                swKeyMap.setCheckedNoAnim(!enabled);
                            }
                            if (keyMapBox != null) {
                                keyMapBox.setVisibility(enabled ? View.GONE : View.VISIBLE);
                            }
                            if (statusText != null) {
                                statusText.setText("写入总开关失败：需要 root 授权（KernelSU / Magisk）后重试。");
                            }
                            return;
                        }
                        if (statusText != null) {
                            statusText.setText(enabled
                                    ? "已启用按键映射。"
                                    : "已关闭按键映射，音量键恢复系统默认功能。");
                        }
                    }
                });
            }
        }, "sk-master").start();
    }

    /** 按键映射入口的副标题：只报数量；全部启用时不重复写「启用 N 条」。 */
    private String smartKeySummary() {
        try {
            java.util.List<io.github.zpan2327.hyperhand.smartkey.data.Rule> rules =
                    new io.github.zpan2327.hyperhand.smartkey.data.RuleRepository(c).loadRules();
            int enabled = 0;
            for (io.github.zpan2327.hyperhand.smartkey.data.Rule r : rules) {
                if (r.getEnabled()) {
                    enabled++;
                }
            }
            if (rules.isEmpty()) {
                return "尚未创建规则";
            }
            return enabled == rules.size()
                    ? rules.size() + " 条规则"
                    : rules.size() + " 条规则 · 启用 " + enabled + " 条";
        } catch (Throwable t) {
            return "按应用、音频、状态触发";
        }
    }

    // ---------------------------------------------------------------- 行工厂

    private void dirty(Ui.SwitchView sw) {
        sw.setOnCheckedChange(new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                markDirty();
                applyEnabledStates();
            }
        });
    }

    /**
     * 依赖开关的设置项同步禁用态：MIUIX 的禁用行不接收点击、文字降到次要色。
     * 值本身不清除，重新打开开关后原值还在，数据流与以前完全一致。
     */
    private void applyEnabledStates() {
        rEndZone.setEnabled(swEndBoost.isChecked());
        rSlowDy.setEnabled(swSlow.isChecked());
        rSlowVel.setEnabled(swSlow.isChecked());
        rZoneRatio.setEnabled(swZoneRatio.isChecked());
        rZone.setEnabled(!swZoneRatio.isChecked());
        rBlurRadius.setEnabled(swBlur.isChecked());
        rBlurDim.setEnabled(swBlur.isChecked());
        rTopShadeDy.setEnabled(swTopShade.isChecked());
        cTopShadeSide.setEnabled(swTopShade.isChecked());
        rSlideAngle.setEnabled(swSlide.isChecked());
        rSlideSlop.setEnabled(swSlide.isChecked());
        rSlideTravel.setEnabled(swSlide.isChecked());
    }

    /**
     * 数值行：min/max/step 决定 MIUIX NumberPicker 的有限选项集（单列滚轮）。
     * 点行弹单列数值选择器，选中即回写；不再打开 Slider 或 Dropdown。
     */
    private Ui.ValueRow numberRow(LinearLayout card, final String title, final String summary,
                                  final String unit, float value, final int dec,
                                  final float min, final float max, final float step) {
        final Ui.ValueRow row = Ui.valueRow(c, card, pal, title, summary, unit,
                value, dec, min, max, step);
        row.row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!row.row.isEnabled()) {
                    return;
                }
                Ui.numericPicker(host, row, title, unit, dec, new Ui.OnNumber() {
                    @Override
                    public void onNumber(float nv) {
                        row.set(nv);
                        markDirty();
                    }
                });
            }
        });
        return row;
    }

    /** 即时生效的数值行（动画时长）：同样走 NumberPicker，只是提交后额外即时下发。 */
    private void realTime(final Ui.ValueRow row, final String title, final String summary,
                          final String unit, final float min, final float max,
                          final float step, final int dec) {
        row.configure(min, max, step);
        row.row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!row.row.isEnabled()) {
                    return;
                }
                Ui.numericPicker(host, row, title, unit, dec, new Ui.OnNumber() {
                    @Override
                    public void onNumber(float nv) {
                        row.set(nv);
                        markDirty();
                        applyAnimNow();
                    }
                });
            }
        });
    }

    /**
     * 模块内容的折叠 / 展开 —— 形态照 LSPosed（Vector 2.2 / Compose）的折叠分组。
     *
     * <p>对 LSPosed「主题设置 → 关闭悬浮底栏 → 液态玻璃那行消失」逐帧比对：它是
     * **in-place 高度收缩**（内容被上边缘裁掉，逐帧变矮，下方行一路平滑上移），
     * 大约 150ms 内收掉 90%，且**没有透明度淡出** —— 收缩到最后一两帧时内容仍然
     * 是全不透明的，只是被裁得只剩一条。
     *
     * <p>这正是 Compose {@code AnimatedVisibility(shrinkVertically())} 的默认观感：
     * 临界阻尼弹簧（stiffness 400 → ω = 20 rad/s，5τ ≈ 250ms），起步快、末尾平滑收敛、
     * 不过冲。这里用同一个解析解作为 Interpolator：{@code f = 1 − (1 + ωt)·e^(−ωt)}。
     */
    private static final long FOLD_MS = 300L;
    private static final float FOLD_OMEGA = 20f;     // sqrt(Spring.StiffnessMediumLow = 400)

    private static android.view.animation.Interpolator foldSpring(final float omega,
                                                                  final long ms) {
        return new android.view.animation.Interpolator() {
            @Override
            public float getInterpolation(float t) {
                float x = omega * (t * ms / 1000f);
                return 1f - (1f + x) * (float) Math.exp(-x);
            }
        };
    }

    /**
     * 模块内容折叠 / 展开。
     *
     * <p><b>只对「屏幕内可见的那一段高度」做动画</b>。原因（2026-11-26 真机打点）：
     * 单手模式那张卡完整高度 <b>6489px</b>，而视口只有 2670px、开关行底边在 y≈1272，
     * 屏幕上只看得到约 <b>1398px</b>。若把 6489px 全程当动画距离（即便此前试过 ω=10/480ms），
     * 前面一大半位移裁的都是屏幕外内容 —— 用户看不到任何变化，最后才把屏幕内那
     * 1398px 一口气抽走，观感就是「先卡住、然后突然消失」。而按键映射（56px）、
     * 滑选（168px）两张卡整张都在视口内，全程可见，所以用同一条曲线就很自然。
     *
     * <p>现在：动画开始前把屏幕外那段一次性砍掉（那部分本来就在屏幕外，用户看不到），
     * 弹簧只负责可见段 0 ↔ visible。三个模块动画时长与曲线完全一致（300ms / ω=20）。
     *
     * @param v      可折叠的内容容器
     * @param anchor 该模块的总开关行，其底边即容器顶边；用来算容器顶部在屏幕上的位置
     */
    private void expand(final View v, final View anchor, final boolean show) {
        // 同一容器上一次动画还在跑（快速连点开关）：先停掉，再按当前实际状态重新起。
        Object running = v.getTag();
        if (running instanceof android.animation.Animator) {
            ((android.animation.Animator) running).cancel();
        }
        v.animate().cancel();
        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null) {
            v.setVisibility(show ? View.VISIBLE : View.GONE);
            return;
        }
        final int width = page.column.getWidth() > 0
                ? page.column.getWidth() : Theme.screenWidth(c);
        final int full;
        if (show) {
            v.setVisibility(View.VISIBLE);
            // 手动量一次拿到「完全展开」的高度（measure 用传入的 spec，不看 lp.height）。
            v.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            full = v.getMeasuredHeight();
        } else {
            if (v.getVisibility() != View.VISIBLE) {
                return;
            }
            full = v.getHeight();
            if (full <= 0) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                v.setLayoutParams(lp);
                v.setVisibility(View.GONE);
                return;
            }
        }

        // 容器在屏幕视口内的可见高度：从锚点（开关行）底边量到滚动视口底部。
        int visible = full;
        if (anchor != null && anchor.isAttachedToWindow() && page.scroll.getHeight() > 0) {
            int[] a = new int[2];
            int[] s = new int[2];
            anchor.getLocationOnScreen(a);
            page.scroll.getLocationOnScreen(s);
            int containerTop = a[1] + anchor.getHeight() - s[1];
            visible = page.scroll.getHeight() - containerTop;
            if (visible < 0) {
                visible = 0;
            }
            if (visible > full) {
                visible = full;
            }
        }
        final int animHeight = visible;

        // 屏幕外的部分直接跳过（收起时先砍掉、展开时动画结束后补回），
        // 弹簧只走可见段。这样三个模块的折叠观感完全统一。
        lp.height = show ? 0 : animHeight;
        v.setLayoutParams(lp);

        final android.animation.ValueAnimator anim =
                android.animation.ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(FOLD_MS);
        anim.setInterpolator(foldSpring(FOLD_OMEGA, FOLD_MS));
        anim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator a) {
                float f = (Float) a.getAnimatedValue();
                // 只有高度在动；内容保持不透明，被上边缘裁切 —— 与 LSPosed 一致。
                lp.height = Math.round(animHeight * (show ? f : 1f - f));
                v.setLayoutParams(lp);
            }
        });
        final boolean[] cancelled = {false};
        anim.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled[0] = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (cancelled[0]) {
                    return;     // 被下一次开关打断，终态交给新的那次动画
                }
                // 回到 WRAP_CONTENT：完整高度（含屏幕外部分）恢复，之后数值行变高也能自适应。
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                v.setLayoutParams(lp);
                if (!show) {
                    v.setVisibility(View.GONE);
                }
                v.setTag(null);
            }
        });
        v.setTag(anim);
        anim.start();
    }

    // ---------------------------------------------------------------- 行为

    /** 任何改动即时提交：把当前整页配置打包广播给 SystemUI 写入 Settings.Global。 */
    void markDirty() {
        host.save();
    }

    void onSaved() {
        if (statusText != null) {
            statusText.setText("已自动保存并生效。可下滑底部区域测试，点「最近手势」查看判定结果。");
        }
    }

    /** 切换模式：把该模式的推荐值填进参数行，并立即提交。 */
    private void applyMode(int mode) {
        cMode.value = mode;
        cMode.label.setText(Cfg.MODE_SHORT[mode]);
        Cfg.Preset p = Cfg.preset(mode);
        rMinDy.set(p.minDyDp);
        rVelocity.set(p.velLowDp);
        rSlowDy.set(p.slowDyDp);
        rSlowVel.set(p.slowLowDp);
        rConf.set(p.confidence);
        markDirty();
    }

    private void applyDefaults() {
        switchWithoutMark(swEnabled, true);
        switchWithoutMark(swMonitor, true);
        switchWithoutMark(swShadeGuard, true);
        switchWithoutMark(swClaimZone, true);
        switchWithoutMark(swSlow, true);
        switchWithoutMark(swAdaptive, true);
        switchWithoutMark(swBlur, true);

        cMode.value = Cfg.MODE_NATURAL;
        cMode.label.setText(Cfg.MODE_SHORT[Cfg.MODE_NATURAL]);
        Cfg.Preset p = Cfg.preset(Cfg.MODE_NATURAL);
        rMinDy.set(p.minDyDp);
        rVelocity.set(p.velLowDp);
        rSlowDy.set(p.slowDyDp);
        rSlowVel.set(p.slowLowDp);
        rConf.set(p.confidence);
        rZone.set(Cfg.DEF_ZONE_DP);
        switchWithoutMark(swZoneRatio, true);
        rZoneRatio.set(Cfg.DEF_ZONE_RATIO * 100f);
        rWidth.set(Cfg.DEF_WIDTH_RATIO * 100f);
        rBias.set(Cfg.DEF_ZONE_BIAS * 100f);
        rSlope.set(Cfg.DEF_MAX_SLOPE);
        rWindow.set(Cfg.DEF_VEL_WINDOW_MS);
        rMaxVel.set(Cfg.DEF_MAX_VELOCITY);
        rMono.set(Cfg.DEF_MIN_MONOTONIC);
        rCooldown.set(Cfg.DEF_COOLDOWN_MS);
        rHotDp.set(Cfg.DEF_LAUNCHER_HOT_DP);
        rBlurRadius.set(Cfg.DEF_BLUR_RADIUS);
        rBlurDim.set(Cfg.DEF_BLUR_DIM);
        cAnimCurve.value = Cfg.DEF_ANIM_CURVE;
        cAnimCurve.label.setText(Cfg.ANIM_CURVE_SHORT[Cfg.DEF_ANIM_CURVE]);
        rAnimScale.set(Cfg.DEF_ANIM_SCALE);
        switchWithoutMark(swEndBoost, true);
        rEndZone.set(Cfg.DEF_END_ZONE_DP);
        switchWithoutMark(swTopShade, true);
        rTopShadeDy.set(Cfg.DEF_TOP_SHADE_DY_DP);
        cTopShadeSide.value = Cfg.DEF_TOP_SHADE_SIDE;
        cTopShadeSide.label.setText(Cfg.TOP_SIDE_SHORT[Cfg.DEF_TOP_SHADE_SIDE]);
        switchWithoutMark(swSlide, true);
        rSlideAngle.set(Cfg.DEF_SLIDE_ANGLE_DEG);
        rSlideSlop.set(Cfg.DEF_SLIDE_SLOP_DP);
        rSlideTravel.set(Cfg.DEF_SLIDE_MIN_TRAVEL_DP);
        applyEnabledStates();
        // 恢复默认把三个功能的开关都打开了，内容容器跟着展开（不做动画）。
        if (keyMapBox != null) {
            keyMapBox.setVisibility(View.VISIBLE);
        }
        if (oneHandBox != null) {
            oneHandBox.setVisibility(View.VISIBLE);
        }
        if (slideBox != null) {
            slideBox.setVisibility(View.VISIBLE);
        }
    }

    private void switchWithoutMark(Ui.SwitchView sw, boolean v) {
        if (sw != null) {
            sw.setCheckedNoAnim(v);
        }
    }

    /** 把当前界面上的所有值写进广播。 */
    void applyTo(Intent i) {
        i.putExtra(Cfg.K_ENABLED, swEnabled.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_MONITOR_ENABLED, swMonitor.isChecked() ? "1" : "0");
        // 调试模块已从设置主界面移除（关于页连点 logo 5 次唤起），这里直通现值
        i.putExtra(Cfg.K_DEBUG, host.cfg().debug ? "1" : "0");
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
        i.putExtra(Cfg.K_ZONE_RATIO_MODE, swZoneRatio.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_ZONE_RATIO, Ui.fmt(rZoneRatio.value / 100f, 3));
        i.putExtra(Cfg.K_WIDTH_RATIO, Ui.fmt(rWidth.value / 100f, 2));
        i.putExtra(Cfg.K_ZONE_BIAS, Ui.fmt(rBias.value / 100f, 2));
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
        i.putExtra(Cfg.K_END_BOOST, swEndBoost.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_END_ZONE_DP, Ui.fmt(rEndZone.value, 0));
        i.putExtra(Cfg.K_TOP_SHADE, swTopShade.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_TOP_SHADE_DY_DP, Ui.fmt(rTopShadeDy.value, 0));
        i.putExtra(Cfg.K_TOP_SHADE_SIDE, String.valueOf(cTopShadeSide.value));
        i.putExtra(Cfg.K_SLIDE_ENABLED, swSlide.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_SLIDE_ANGLE_DEG, Ui.fmt(rSlideAngle.value, 0));
        i.putExtra(Cfg.K_SLIDE_SLOP_DP, Ui.fmt(rSlideSlop.value, 0));
        i.putExtra(Cfg.K_SLIDE_MIN_TRAVEL_DP, Ui.fmt(rSlideTravel.value, 0));
    }

    /** 动画参数单独即时下发，方便"改一点、滑一下"地试手感。 */
    void applyAnimNow() {
        try {
            Intent i = new Intent(HyperHand.ACTION_SET);
            i.setPackage("com.android.systemui");
            i.putExtra(Cfg.K_ANIM_CURVE, String.valueOf(cAnimCurve.value));
            i.putExtra(Cfg.K_ANIM_SCALE, Ui.fmt(rAnimScale.value, 2));
            host.sendBroadcast(i);
            if (animInfo != null) {
                animInfo.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (animInfo != null) {
                            animInfo.setText(animInfoText());
                        }
                    }
                }, 400);
            }
        } catch (Throwable t) {
            // 纯外观，发不出去不影响其余已即时提交的配置
        }
    }

    void refreshAnimInfo() {
        if (animInfo != null) {
            animInfo.setText(animInfoText());
        }
        // 从「规则与按键」页回来时，那边的「功能总开关」可能已经改了，这里同步一次。
        if (swKeyMap != null) {
            boolean on = smartKeyMaster();
            if (swKeyMap.isChecked() != on) {
                swKeyMap.setCheckedNoAnim(on);
                if (keyMapBox != null) {
                    keyMapBox.setVisibility(on ? View.VISIBLE : View.GONE);
                }
            }
        }
    }

    private String animInfoText() {
        try {
            String live = android.provider.Settings.Global.getString(
                    host.getContentResolver(), Cfg.K_ANIM_INFO);
            if (live != null && live.trim().length() > 0) {
                return live;
            }
        } catch (Throwable ignored) {
            // 下面走静态说明
        }
        return "系统原生 " + Cfg.SYSTEM_ANIM_MS + "ms · " + Cfg.ANIM_CURVE_RAW_DESC
                + "；下滑一次后显示实测值";
    }

    private String downGapText() {
        try {
            String s = android.provider.Settings.Global.getString(
                    host.getContentResolver(), Cfg.K_LAST_DOWN);
            if (s != null && s.trim().length() > 0) {
                return s;
            }
        } catch (Throwable ignored) {
            // 走默认提示
        }
        return "在底部下滑一次，显示起手点距底边高度，用于校准";
    }


    void setRestarting(boolean restarting) {
        if (restartBtn != null) {
            restartBtn.setText(restarting ? "重启中…" : "重启系统界面与桌面");
            restartBtn.setEnabled(!restarting);
            restartBtn.setAlpha(restarting ? 0.6f : 1f);
        }
    }

    void setStatus(String s) {
        if (statusText != null) {
            statusText.setText(s);
        }
    }

    void jumpToTop() {
        page.scroll.smoothScrollTo(0, 0);
    }
}
