package io.github.zpan2327.hyperhand.smartkey.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.zpan2327.hyperhand.smartkey.data.Rule
import io.github.zpan2327.hyperhand.smartkey.data.RuleRepository
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 模块首页：总开关、真实运行状态、规则列表与新建入口。 */
@Composable
fun HomeScreen(
    repo: RuleRepository,
    rules: List<Rule>,
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
    // 版本串连点计数（隐藏的运行状态入口）。
    val taps = remember { mutableIntStateOf(0) }
    val lastTap = remember { mutableLongStateOf(0L) }
    // 当前弹出下拉菜单的规则；非空即该行的浮层展开。
    var menuRule by remember { mutableStateOf<Rule?>(null) }
    // 等待二次确认删除的规则；非空即弹出确认对话框。
    var pendingDelete by remember { mutableStateOf<Rule?>(null) }

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
                title = "新建规则",
                onClick = { haptics.tick(); onNewRule() },
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
                    // 长按整行弹 Miuix 下拉浮层（复制 / 删除），点按仍然是编辑规则。
                    // combinedClickable 必须挂在 BasicComponent 自己的 modifier 上、并让它的 onClick
                    // 保持 null：否则内层 clickable 先吃掉手势，长按永远不触发。
                    Box(Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = rule.name,
                            summary = "${rule.conditionsDesc(context)}\n${rule.mappingsDesc}\n优先级 ${rule.priority}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = LocalIndication.current,
                                    onClick = { haptics.tick(); onEditRule(rule.id) },
                                    onLongClick = {
                                        haptics.popup()
                                        menuRule = rule
                                    },
                                ),
                            endActions = {
                                Switch(
                                    checked = rule.enabled,
                                    onCheckedChange = { onToggleRule(rule.id, it) },
                                )
                            },
                        )
                        // 锚点就是这一行：浮层贴着被长按的规则弹出（圆角 16dp / surfaceContainer / 背景压暗）。
                        OverlayListPopup(
                            show = menuRule?.id == rule.id,
                            alignment = PopupPositionProvider.Align.Start,
                            onDismissRequest = { menuRule = null },
                            minWidth = 160.dp,
                            maxHeight = 240.dp,
                        ) {
                            ListPopupColumn {
                                RuleMenuItem(text = "复制") {
                                    haptics.click()
                                    menuRule = null
                                    onDuplicateRule(rule.id)
                                }
                                RuleMenuItem(text = "删除", danger = true) {
                                    haptics.tick()
                                    menuRule = null
                                    pendingDelete = rule
                                }
                            }
                        }
                    }
                }
            }
        }

        CardGap()
        Text(
            text = "HyperHand $version · 按键映射",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                // 隐藏入口：连续点 5 下版本串（相邻两次间隔 < 800ms）打开运行状态页，
                // 与「关于页连点 logo」同一套手感；入口不在列表里露出来。
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    val now = android.os.SystemClock.uptimeMillis()
                    taps.intValue = if (now - lastTap.longValue < 800L) taps.intValue + 1 else 1
                    lastTap.longValue = now
                    if (taps.intValue >= 5) {
                        taps.intValue = 0
                        lastTap.longValue = 0L
                        haptics.clickPrimary()
                        onOpenStatus()
                    }
                },
        )

        // 删除二次确认：Miuix OverlayDialog（标题 18sp 居中 + 摘要 + 底部按钮），
        // 「删除」按钮用 error 色，确认时才真正删并给 alert 触感。
        OverlayDialog(
            title = "删除规则",
            summary = pendingDelete?.let { "确定要删除「${it.name}」吗？此操作无法撤销。" } ?: "",
            show = pendingDelete != null,
            onDismissRequest = { pendingDelete = null },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    text = "取消",
                    onClick = { pendingDelete = null },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "删除",
                    onClick = {
                        pendingDelete?.let { rule ->
                            haptics.alert()
                            onDeleteRule(rule.id)
                        }
                        pendingDelete = null
                    },
                    modifier = Modifier.weight(1f),
                    // 危险操作：红色实心按钮 + 白字（TextButtonColors 的 color 是容器色、textColor 是文字色）。
                    colors = ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.error,
                        textColor = MiuixTheme.colorScheme.onError,
                    ),
                )
            }
        }
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

/**
 * 下拉浮层里的一行。
 *
 * 度量对齐 Miuix `DropdownImpl` 的 popup 模式：水平内边距 20dp、首/尾行垂直 20dp、
 * 文本 `body1`(16sp Medium)；危险项（删除）用 `colorScheme.error` 上红色。
 */
@Composable
private fun RuleMenuItem(
    text: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
            color = if (danger) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceContainer,
        )
    }
}
