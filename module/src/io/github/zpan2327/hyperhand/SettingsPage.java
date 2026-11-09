package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 「设置」页：项目现有的全部参数，按"模块 / 识别 / 触发区 / 外观 / 动效 /
 * 模糊区手势 / 互斥 / 高级 / 调试"重新分组。
 *
 * 交互与 2.9 完全一致：所有值先在本地改，点「保存并立即生效」后通过广播
 * 交给 SystemUI 进程写入 Settings.Global；动画曲线与时长例外，改完即时下发。
 */
final class SettingsPage {

    private final MainActivity host;
    private final Theme.Colors pal;
    private final Context c;
    private final Ui.Page page;

    private Ui.SwitchView swEnabled;
    private Ui.SwitchView swMonitor;
    private Ui.SwitchView swDebug;
    private Ui.SwitchView swShadeGuard;
    private Ui.SwitchView swClaimZone;
    private Ui.SwitchView swSlow;
    private Ui.SwitchView swAdaptive;
    private Ui.SwitchView swAdvanced;
    private Ui.SwitchView swBlur;
    private Ui.SwitchView swEndBoost;
    private Ui.SwitchView swZoneRatio;
    private Ui.SwitchView swTopShade;

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

    private LinearLayout advancedBox;
    private TextView animInfo;
    private TextView gestureLog;
    private TextView downGap;
    private TextView restartBtn;
    private TextView saveBtn;
    private TextView statusText;

    SettingsPage(MainActivity host) {
        this.host = host;
        this.c = host;
        this.pal = host.pal;
        this.page = Ui.page(c, pal);
        build();
        refreshGestureLog();
    }

    View view() {
        return page.scroll;
    }

    void setBottomInset(int px) {
        page.setBottomInset(px);
    }

    // ---------------------------------------------------------------- 构建

    private void build() {
        Cfg cur = host.cfg();

        page.add(Ui.pageHeader(c, pal, "设置", "所有参数保存后立即生效", null));

        // ---------------------------------------------------------- 模块
        page.add(Ui.section(c, pal, "模块", true));
        LinearLayout cardModule = Ui.card(c, pal);
        swEnabled = Ui.switchRow(c, cardModule, pal, "启用模块",
                "总开关。关闭后所有钩子立即失效，系统恢复原生行为", cur.enabled);
        swMonitor = Ui.switchRow(c, cardModule, pal, "扩展触发",
                "在系统的一手模式监视通道上判定底部下滑", cur.monitorEnabled);
        dirty(swEnabled);
        dirty(swMonitor);
        page.add(cardModule);

        // ---------------------------------------------------------- 识别
        page.add(Ui.section(c, pal, "识别", false));
        LinearLayout cardMode = Ui.card(c, pal);
        cMode = Ui.choiceRow(c, cardMode, pal, "触发模式",
                "切换模式会填入一组推荐敏感度，保存后生效",
                Cfg.MODE_SHORT, cur.mode, new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        Ui.singleChoiceDialog(host, pal, "触发模式",
                                "切换会把该模式的推荐值填进下面的参数，保存后生效",
                                Cfg.MODE_NAMES, index, new Ui.OnChoice() {
                                    @Override
                                    public void onChoice(int picked) {
                                        applyMode(picked);
                                    }
                                });
                    }
                });
        page.add(cardMode);

        LinearLayout cardJudge = Ui.card(c, pal);
        rMinDy = numberRow(cardJudge, "最小下滑距离", "短促快甩所需的最小位移", "dp",
                cur.minDyDp, 0, 1, 60, 1);
        swEndBoost = Ui.switchRow(c, cardJudge, pal, "贴底才算单手模式",
                "抬手点必须拖到屏幕最底边；没到底就当成页面滑动，不再抢手势",
                cur.endBoost);
        dirty(swEndBoost);
        rEndZone = numberRow(cardJudge, "贴底判定距离", "抬手点距屏幕底边小于这个值就算\"到底了\"", "dp",
                cur.endZoneDp, 0, 8, 80, 1);
        rVelocity = numberRow(cardJudge, "起效速度", "快甩路径的峰值速度门限，0 表示只看距离", "dp/s",
                cur.minVelDp, 0, 0, 800, 10);
        swSlow = Ui.switchRow(c, cardJudge, pal, "慢滑识别",
                "连续慢速下拉也能触发（长距离路径）", cur.slowEnabled);
        dirty(swSlow);
        rSlowDy = numberRow(cardJudge, "慢滑所需距离", "慢滑路径要求的最小位移", "dp",
                cur.slowDyDp, 0, 1, 120, 1);
        rSlowVel = numberRow(cardJudge, "慢滑速度下限", "慢滑路径的平均速度门限", "dp/s",
                cur.minAvgVelDp, 0, 0, 400, 5);
        rConf = numberRow(cardJudge, "判定置信度", "综合得分门槛，越低越灵敏、越高越保守", "",
                cur.minConfidence, 2, 0.10f, 1.0f, 0.02f);
        page.add(cardJudge, Ui.cardLp(c, Theme.CARD_GAP));

        // ---------------------------------------------------------- 触发区
        page.add(Ui.section(c, pal, "触发区", false));
        LinearLayout cardZone = Ui.card(c, pal);
        swZoneRatio = Ui.switchRow(c, cardZone, pal, "延伸到 dock 栏上方",
                "按屏幕高度比例算触发区，覆盖整个 dock；这块区域里的下拉不会打开通知/控制中心",
                cur.zoneRatioMode);
        dirty(swZoneRatio);
        rZoneRatio = numberRow(cardZone, "屏幕占比", "默认 15%，大约就是 dock 栏上沿的高度", "%",
                cur.zoneRatio * 100f, 0, 5, 30, 1);
        rZone = numberRow(cardZone, "触发区高度", "固定高度模式（上面的开关关闭时生效）", "dp",
                cur.zoneDp, 0, 20, 200, 1);
        rWidth = numberRow(cardZone, "有效宽度占比", "触发区在屏幕水平方向上的占比，居中", "%",
                cur.widthRatio * 100f, 0, 20, 100, 5);
        rBias = numberRow(cardZone, "左右偏置", "把有效区域中心左右移动，配合左右手落指习惯", "%",
                cur.zoneBias * 100f, 0, -40, 40, 5);
        downGap = Ui.hintBlock(c, pal, downGapText(), pal.primary);
        cardZone.addView(downGap);
        page.add(cardZone, Ui.cardLp(c, Theme.CARD_GAP));

        // ---------------------------------------------------------- 外观
        page.add(Ui.section(c, pal, "外观", false));
        LinearLayout cardLook = Ui.card(c, pal);
        swBlur = Ui.switchRow(c, cardLook, pal, "模糊壁纸背景",
                "把上方米白遮罩换成高斯模糊的当前壁纸", cur.blurBg);
        dirty(swBlur);
        rBlurRadius = numberRow(cardLook, "模糊半径", "越大越糊；0 近似纯壁纸原图", "dp",
                cur.blurRadiusDp, 0, 0, 60, 1);
        rBlurDim = numberRow(cardLook, "压暗程度", "在模糊壁纸上叠一层黑，拉开与缩小窗口的层次", "%",
                cur.blurDim, 0, 0, 60, 1);
        page.add(cardLook, Ui.cardLp(c, Theme.CARD_GAP));

        // ---------------------------------------------------------- 动效
        page.add(Ui.section(c, pal, "动效", false));
        LinearLayout cardAnim = Ui.card(c, pal);
        cAnimCurve = Ui.choiceRow(c, cardAnim, pal, "缓动曲线",
                "按起步速度从慢到快排列；改完即时生效，可来回对比",
                Cfg.ANIM_CURVE_SHORT, cur.animCurve, new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        Ui.singleChoiceDialog(host, pal, "缓动曲线",
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
        rAnimScale = Ui.valueRow(c, cardAnim, pal, "动画时长",
                "1.00 = 系统原生 600ms；越大越慢，改动即时生效", "×", cur.animScale, 2);
        realTime(rAnimScale, "动画时长", "×", 0.60f, 2.00f, 0.05f, 2);
        animInfo = Ui.block(c, pal, animInfoText());
        Ui.addRow(c, cardAnim, pal, animInfo);
        page.add(cardAnim, Ui.cardLp(c, Theme.CARD_GAP));

        // ---------------------------------------------------------- 模糊区手势
        page.add(Ui.section(c, pal, "模糊区手势", false));
        LinearLayout cardTop = Ui.card(c, pal);
        swTopShade = Ui.switchRow(c, cardTop, pal, "下滑展开通知/控制中心",
                "在模糊区任意位置下滑即可拉开面板并退出单手模式；"
                        + "不触发时点一下仍然是原生的\"退出单手模式\"",
                cur.topShade);
        dirty(swTopShade);
        rTopShadeDy = numberRow(cardTop, "触发距离", "下滑多少 dp 才算数，防止把\"点一下\"误判成下拉", "dp",
                cur.topShadeDyDp, 0, 8, 80, 1);
        cTopShadeSide = Ui.choiceRow(c, cardTop, pal, "拉开哪个",
                "左侧下滑出通知中心，右侧下滑出控制中心（HyperOS 习惯）",
                Cfg.TOP_SIDE_SHORT, cur.topShadeSide, new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        Ui.singleChoiceDialog(host, pal, "拉开哪个",
                                "决定模糊区下滑时展开哪一块面板", Cfg.TOP_SIDE_NAMES, index,
                                new Ui.OnChoice() {
                                    @Override
                                    public void onChoice(int picked) {
                                        cTopShadeSide.value = picked;
                                        cTopShadeSide.label.setText(Cfg.TOP_SIDE_SHORT[picked]);
                                        markDirty();
                                    }
                                });
                    }
                });
        cardTop.addView(Ui.block(c, pal,
                "单手模式把整个系统界面向下平移了约 40% 屏高，状态栏也跟着下移到缩小窗口的顶部。"
                        + "这块模糊区原本没有任何手势，现在整个模糊区都可以当\"状态栏\"用：\n"
                        + "左半屏下滑 → 通知中心；右半屏下滑 → 控制中心（可在上面改成固定一个）"));
        page.add(cardTop, Ui.cardLp(c, Theme.CARD_GAP));

        // ---------------------------------------------------------- 互斥
        page.add(Ui.section(c, pal, "互斥", false));
        LinearLayout cardGuard = Ui.card(c, pal);
        swShadeGuard = Ui.switchRow(c, cardGuard, pal, "与下拉手势互斥",
                "手势中出现通知/控制中心意图就放弃单手模式", cur.shadeGuard);
        swClaimZone = Ui.switchRow(c, cardGuard, pal, "底部区域优先单手模式",
                "触发区内的桌面下拉让位，不再打开通知/控制中心", cur.claimZone);
        rHotDp = numberRow(cardGuard, "原生胶囊条高度", "覆盖系统 25dp 的底部窗口，0 表示不改动", "dp",
                cur.launcherHotDp, 0, 0, 40, 1);
        dirty(swShadeGuard);
        dirty(swClaimZone);
        page.add(cardGuard, Ui.cardLp(c, Theme.CARD_GAP));

        // ---------------------------------------------------------- 高级
        page.add(Ui.section(c, pal, "高级", false));
        LinearLayout cardToggle = Ui.card(c, pal);
        swAdvanced = Ui.switchRow(c, cardToggle, pal, "显示高级参数",
                "速度采样窗口、轨迹稳定性、冷却等", false);
        page.add(cardToggle);

        advancedBox = new LinearLayout(c);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.setVisibility(View.GONE);

        LinearLayout cardAdv = Ui.card(c, pal);
        rWindow = numberRow(cardAdv, "速度采样窗口", "用多长的滑动窗口估速度，越大越稳、越小越灵敏", "ms",
                cur.velWindowMs, 0, 20, 200, 5);
        rMaxVel = numberRow(cardAdv, "速度上限", "超过该值按噪声截断，防止单帧抖动误判", "dp/s",
                cur.maxVelocityDp, 0, 300, 8000, 100);
        rMono = numberRow(cardAdv, "轨迹稳定度", "净位移占路程的比例下限，低于它判为来回蹭", "",
                cur.minMonotonic, 2, 0.40f, 1.0f, 0.02f);
        rSlope = numberRow(cardAdv, "最大横向偏移", "|dx| / dy 超过该值视为斜滑，不触发", "倍",
                cur.maxSlope, 1, 0.5f, 4.0f, 0.1f);
        rCooldown = numberRow(cardAdv, "触发冷却", "两次触发之间的最小间隔", "ms",
                cur.cooldownMs, 0, 0, 2000, 50);
        swAdaptive = Ui.switchRow(c, cardAdv, pal, "自适应阈值",
                "干脆利落的快手势略放宽，拖沓的长手势略收紧", cur.adaptive);
        dirty(swAdaptive);
        advancedBox.addView(cardAdv, Ui.cardLp(c, Theme.CARD_GAP));
        page.add(advancedBox);

        swAdvanced.setOnCheckedChange(new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                expand(advancedBox, checked);
            }
        });

        // ---------------------------------------------------------- 调试
        page.add(Ui.section(c, pal, "调试", false));
        LinearLayout cardDebug = Ui.card(c, pal);
        swDebug = Ui.switchRow(c, cardDebug, pal, "调试日志",
                "用 logcat -s HyperHand 观察判定过程，并记录最近手势", cur.debug);
        dirty(swDebug);
        Ui.choiceRow(c, cardDebug, pal, "最近手势",
                "读取系统里记录的上一次判定结果", new String[] {"刷新"}, 0,
                new Ui.OnChoice() {
                    @Override
                    public void onChoice(int index) {
                        refreshGestureLog();
                        host.toast("已刷新");
                    }
                });
        Ui.actionRow(c, cardDebug, pal, "预览遮罩", "立刻进入一次单手模式，直接看背景效果", new Runnable() {
            @Override
            public void run() {
                host.previewOneHanded();
            }
        });
        Ui.actionRow(c, cardDebug, pal, "退出单手模式", "立刻退出，恢复正常窗口", new Runnable() {
            @Override
            public void run() {
                host.stopOneHanded();
            }
        });
        gestureLog = Ui.block(c, pal, "还没有记录。开启调试日志后，在屏幕底部下滑一次再看这里。");
        Ui.addRow(c, cardDebug, pal, gestureLog);
        page.add(cardDebug, Ui.cardLp(c, Theme.CARD_GAP));

        // ---------------------------------------------------------- 底部操作
        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, Theme.dp(c, 20), 0, 0);

        saveBtn = Ui.primaryButton(c, pal, "保存并立即生效");
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.save();
            }
        });
        actions.addView(saveBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(c, 48)));

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

        restartBtn = Ui.secondaryButton(c, pal, "重启两个作用域");
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
                "重启系统界面与桌面进程，让新装/新改的钩子重新注入。需要 root 授权（首次会弹授权框）。",
                Theme.T_CAP, pal.summary, false);
        restartHint.setPadding(Theme.dp(c, 4), Theme.dp(c, 10), Theme.dp(c, 4), 0);
        restartHint.setLineSpacing(Theme.dp(c, 3), 1f);
        actions.addView(restartHint);
        page.add(actions);

        statusText = Ui.text(c, "当前值来自系统设置，修改后点上方按钮提交。",
                Theme.T_CAP, pal.section, false);
        statusText.setPadding(Theme.dp(c, 4), Theme.dp(c, 12), Theme.dp(c, 4), 0);
        statusText.setLineSpacing(Theme.dp(c, 3), 1f);
        page.add(statusText);
    }

    // ---------------------------------------------------------------- 行工厂

    private void dirty(Ui.SwitchView sw) {
        sw.setOnCheckedChange(new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                markDirty();
            }
        });
    }

    /** 数值行 + 滑杆对话框。 */
    private Ui.ValueRow numberRow(LinearLayout card, final String title, String summary,
                                  final String unit, float value, final int dec,
                                  final float min, final float max, final float step) {
        final Ui.ValueRow row = Ui.valueRow(c, card, pal, title, summary, unit, value, dec);
        row.row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.numberDialog(host, pal, title, unit, min, max, step, dec, row.value,
                        new Ui.OnNumber() {
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

    /** 即时生效的数值行（动画时长）。 */
    private void realTime(final Ui.ValueRow row, final String title, final String unit,
                          final float min, final float max, final float step, final int dec) {
        row.row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Ui.numberDialog(host, pal, title, unit, min, max, step, dec, row.value,
                        new Ui.OnNumber() {
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

    private void expand(View v, boolean show) {
        if (show) {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(0f);
            v.setTranslationY(-Theme.dp(c, 6));
            v.animate().alpha(1f).translationY(0f).setDuration(190)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f)).start();
        } else {
            v.animate().alpha(0f).translationY(-Theme.dp(c, 6)).setDuration(140)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            v.setVisibility(View.GONE);
                        }
                    }).start();
        }
    }

    // ---------------------------------------------------------------- 行为

    void markDirty() {
        if (statusText != null) {
            statusText.setText("有未保存的更改，点「保存并立即生效」提交。");
        }
        host.markDirty();
    }

    void onSaved() {
        if (statusText != null) {
            statusText.setText("已提交到系统界面进程，立即生效。可下滑底部区域测试，点「最近手势」查看判定结果。");
        }
    }

    /** 切换模式：把该模式的推荐值填进参数行（保存后才提交）。 */
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
        switchWithoutMark(swDebug, true);
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
            // 纯外观，发不出去就等下次保存
        }
    }

    void refreshAnimInfo() {
        if (animInfo != null) {
            animInfo.setText(animInfoText());
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
                + "\n保存并在底部下滑一次后，这里会显示实际生效的时长。";
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
        return "在屏幕底部附近按一下，这里会显示那次起手离底边多少 dp，"
                + "用来判断上面的触发区高度该调大还是调小。";
    }

    void refreshGestureLog() {
        if (gestureLog == null) {
            return;
        }
        try {
            String s = android.provider.Settings.Global.getString(
                    host.getContentResolver(), Cfg.K_LAST_GESTURE);
            if (s == null || s.trim().length() == 0) {
                gestureLog.setText("还没有记录。开启调试日志后，在屏幕底部下滑一次再看这里。");
            } else {
                gestureLog.setText(s);
            }
        } catch (Throwable t) {
            gestureLog.setText("读取失败：" + t);
        }
        if (downGap != null) {
            downGap.setText(downGapText());
        }
    }

    void setRestarting(boolean restarting) {
        if (restartBtn != null) {
            restartBtn.setText(restarting ? "重启中…" : "重启两个作用域");
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
