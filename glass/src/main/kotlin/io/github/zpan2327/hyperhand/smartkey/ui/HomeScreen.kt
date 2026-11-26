package io.github.zpan2327.hyperhand.smartkey.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.zpan2327.hyperhand.smartkey.data.ModuleStatus
import io.github.zpan2327.hyperhand.smartkey.data.Rule
import io.github.zpan2327.hyperhand.smartkey.data.RuleRepository
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 模块首页：总开关、真实运行状态、规则列表与新建入口。 */
@Composable
fun HomeScreen(
    repo: RuleRepository,
    rules: List<Rule>,
    status: ModuleStatus,
    masterEnabled: Boolean,
    onMasterChange: (Boolean) -> Unit,
    onNewRule: () -> Unit,
    onEditRule: (String) -> Unit,
    onToggleRule: (String, Boolean) -> Unit,
    onDeleteRule: (String) -> Unit,
    onDuplicateRule: (String) -> Unit,
    onOpenStatus: () -> Unit,
    version: String,
    padding: androidx.compose.foundation.layout.PaddingValues,
    scrollBehavior: ScrollBehavior,
) {
    val conflicts = remember(rules) { repo.detectConflicts() }
    // 条件摘要里要显示应用名称，需要 Context（走 AppCatalog 的包名索引）。
    val context = LocalContext.current
    // 与主设置页同款触感：行 tick / 开关 toggle / 按钮 click。
    val haptics = rememberSkHaptics()

    SmartKeyScaffoldContent(
        padding = padding,
        scrollBehavior = scrollBehavior,
    ) {
        // ---------------------------------------------------------- 功能状态
        SmallTitle(text = "功能状态")
        Card(
            modifier = Modifier.fillMaxWidth(),
            pressFeedbackType = PressFeedbackType.None,
        ) {
            SwitchPreference(
                checked = masterEnabled,
                // Miuix 的 Switch 自带 MIUI TICK 触感，这里不再叠加，否则一次点击震两下。
                onCheckedChange = onMasterChange,
                title = "功能总开关",
                summary = if (masterEnabled) "已开启，按键映射参与拦截" else "已关闭，按键保持系统默认行为",
            )
            ArrowPreference(
                title = "运行状态",
                summary = if (status.hookActive) "system_server Hook 已激活" else "system_server Hook 未激活",
                onClick = { haptics.tick(); onOpenStatus() },
            )
        }

        // ---------------------------------------------------------- 冲突提醒
        if (conflicts.isNotEmpty()) {
            CardGap()
            SmallTitle(text = "冲突提醒")
            Card(
                modifier = Modifier.fillMaxWidth(),
                pressFeedbackType = PressFeedbackType.None,
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        text = "存在 ${conflicts.size} 处规则冲突",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    conflicts.take(3).forEach { c ->
                        Text(
                            text = "${c.key.label} · ${c.press.label}：${c.ruleNames.joinToString("、")}",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceSecondary,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "同一按键事件只执行优先级最高的规则，其余规则自动让位。",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                }
            }
        }

        // ---------------------------------------------------------- 规则
        CardGap()
        SmallTitle(text = "规则列表（${rules.size}）")
        Card(
            modifier = Modifier.fillMaxWidth(),
            pressFeedbackType = PressFeedbackType.None,
        ) {
            if (rules.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "还没有任何规则",
                        style = MiuixTheme.textStyles.subtitle,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "例如：网易云音乐播放且息屏时，音量加 → 下一首",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { haptics.clickPrimary(); onNewRule() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("创建第一条规则", color = MiuixTheme.colorScheme.onPrimary)
                    }
                }
            } else {
                rules.forEach { rule ->
                    BasicComponent(
                        title = rule.name,
                        summary = "${rule.conditionsDesc(context)}\n${rule.mappingsDesc}\n优先级 ${rule.priority}",
                        onClick = { haptics.tick(); onEditRule(rule.id) },
                        endActions = {
                            Switch(
                                checked = rule.enabled,
                                onCheckedChange = { onToggleRule(rule.id, it) },
                            )
                        },
                        bottomAction = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(text = "复制", onClick = { haptics.click(); onDuplicateRule(rule.id) })
                                Spacer(Modifier.width(8.dp))
                                TextButton(text = "删除", onClick = { haptics.click(); onDeleteRule(rule.id) })
                            }
                        },
                    )
                }
            }
        }

        if (rules.isNotEmpty()) {
            CardGap()
            Card(
                modifier = Modifier.fillMaxWidth(),
                pressFeedbackType = PressFeedbackType.None,
            ) {
                ArrowPreference(title = "新建规则", onClick = { haptics.tick(); onNewRule() })
            }
        }

        CardGap()
        Text(
            text = "HyperHand $version · 智能按键映射",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * 首页/二级页共用的滚动内容容器：由 Scaffold 传入 padding 与联动滚动行为。
 *
 * 横向 16dp 是卡片与屏幕边缘的标准距离；**不在这里设置全局 spacedBy**——
 * Miuix 的 SmallTitle 自带 8dp 上下内边距，若再叠加统一的 item 间距，
 * 标题与卡片之间会出现「8 + 12 + 8」的过大空隙。间距由各屏幕用 [CardGap]
 * 显式放在两张卡片之间。
 *
 * ## 关于越界回弹（Rubber Band）
 *
 * 滚动本身走 Miuix 的 overscroll：`MiuixTheme` 会把 `LocalOverscrollFactory` 指向
 * `MiuixOverscrollFactory`，因此 Compose 的 `verticalScroll` / `LazyColumn` 默认就是
 * iOS 式「连续阻尼 + 非线性位移 + 弹簧回弹」，不是 Android 的 EdgeEffect 发光/拉伸。
 *
 * 但 Compose 的 scrollable 在**滚动范围为 0**（内容不足一屏）时不会派发越界位移，
 * 首页、新建规则这类短页面就会「拉不动」。这里给内层内容一个「视口 + 12dp」的最小高度：
 * 多出来的 12dp 可滚动量肉眼不可见，也不改变任何元素位置，只是让手势有越界余量，
 * 从而在短页面上同样能拉出橡皮筋。
 */
@Composable
internal fun SmartKeyScaffoldContent(
    padding: androidx.compose.foundation.layout.PaddingValues,
    scrollBehavior: ScrollBehavior,
    content: @Composable ColumnScope.() -> Unit,
) {
    val viewportPx = remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            // 放在 verticalScroll 之前才是「视口高度」；放在后面量到的是内容高度。
            .onSizeChanged { if (it.height > 0) viewportPx.intValue = it.height }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(
                    min = with(density) {
                        (viewportPx.intValue + OVERSCROLL_ROOM.roundToPx()).toDp()
                    },
                ),
            content = content,
        )
    }
}

/** 短页面为橡皮筋预留的越界余量：略大于 0 即可，肉眼不可见。 */
private val OVERSCROLL_ROOM = 12.dp
