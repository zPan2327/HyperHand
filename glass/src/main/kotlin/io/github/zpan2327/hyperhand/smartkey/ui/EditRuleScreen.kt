package io.github.zpan2327.hyperhand.smartkey.ui

import io.github.zpan2327.hyperhand.glass.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.zpan2327.hyperhand.smartkey.data.Condition
import io.github.zpan2327.hyperhand.smartkey.data.ConditionType
import io.github.zpan2327.hyperhand.smartkey.data.KeyMapping
import io.github.zpan2327.hyperhand.smartkey.data.KeyType
import io.github.zpan2327.hyperhand.smartkey.data.Logic
import io.github.zpan2327.hyperhand.smartkey.data.PressType
import io.github.zpan2327.hyperhand.smartkey.data.ActionType
import io.github.zpan2327.hyperhand.smartkey.data.Rule
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 规则编辑页内容：名称 + 触发 / 状态条件 / 执行动作 / 规则设置，滚动由外层 Scaffold 统一处理。
 *
 * ## 版面
 *
 * 照米家「创建/编辑自动化」的层次排：
 * 1. 名称卡（可清空）；
 * 2. 「当以下情况发生」= 按键触发（键位 + 按压方式），米家里是设备事件；
 * 3. 「且满足全部状态 ⌄」= 状态条件，标题右侧下拉切换「全部 / 任一」（= 规则的 AND / OR）；
 * 4. 「就执行」= 每个映射对应的动作；
 * 5. 「规则设置」= 启用开关 + 优先级（米家这一组的语义是「否则就执行」，我们没有 else 分支，
 *    换成同样位于页面末尾的规则属性）。
 *
 * ## 触发与动作为什么成对出现
 *
 * 数据模型里一条 [KeyMapping] 就是「某按键 + 某按压方式 → 某动作」的**一个整体**，
 * 所以「当以下情况发生」和「就执行」两张卡展示的是同一个映射列表的两端，按下标一一对应，
 * 行副标题互相点名（触发行写「按下即触发」，动作行写「由音量加键 · 短按触发」）。
 * 因此两个「添加」按钮做的事完全一样：追加一条映射（即一组「触发 → 动作」）。
 *
 * ## 删除
 *
 * 行内不放删除按钮（对齐米家：行只负责进入编辑）。删除放在点开后的对话框左下角，
 * 与「完成 / 更换应用」并排，一次操作就能撤掉这一条。
 */
@Composable
fun ColumnScope.EditRuleScreenContent(
    draft: MutableState<Rule>,
    onAddCondition: () -> Unit,
    onPickApps: (conditionIndex: Int) -> Unit,
    onOverlayOpenChange: (Boolean) -> Unit = {},
) {
    var state by draft
    val haptics = rememberSkHaptics()
    // 条件行的副标题要把包名翻成应用名称，需要 Context。
    val context = androidx.compose.ui.platform.LocalContext.current

    // 对话框用下标定位，-1 = 关闭。删除后下标可能越界，取值时统一走 getOrNull。
    var triggerIndex by remember { mutableStateOf(-1) }
    var actionIndex by remember { mutableStateOf(-1) }
    var conditionIndex by remember { mutableStateOf(-1) }
    var priorityOpen by remember { mutableStateOf(false) }
    // 「添加」不再是就地塞一条默认值：先弹选择框，确认后才追加。
    var addTriggerOpen by remember { mutableStateOf(false) }
    var addActionOpen by remember { mutableStateOf(false) }

    // 贴底对话框会压到页面底部那条「保存」上，只留出一小截蓝底。Miuix 的对话框没法从外部
    // 改抽屉高度（改 outsideMargin 就不再是默认贴底形态了），所以改成「弹窗打开时把按钮收起来」：
    // 底部槽位高度保持不变，页面不会因此跳动。
    val overlayOpen = triggerIndex >= 0 || actionIndex >= 0 || conditionIndex >= 0 ||
        priorityOpen || addTriggerOpen || addActionOpen
    SideEffect { onOverlayOpenChange(overlayOpen) }

    // ------------------------------------------------------------ 名称
    Card(
        modifier = Modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                SkPlainTextField(
                    value = state.name,
                    placeholder = stringResource(R.string.sk_084),
                    textStyle = MiuixTheme.textStyles.main,
                    textColor = MiuixTheme.colorScheme.onSurface,
                    onValueChange = { state = state.copy(name = it) },
                )
                Spacer(Modifier.height(2.dp))
                SkPlainTextField(
                    value = state.description,
                    placeholder = stringResource(R.string.sk_085),
                    textStyle = MiuixTheme.textStyles.footnote1,
                    textColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                    onValueChange = { state = state.copy(description = it) },
                )
            }
            if (state.name.isNotEmpty() || state.description.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(
                            MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.14f),
                            CircleShape,
                        )
                        .clickable {
                            haptics.click()
                            state = state.copy(name = "", description = "")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Close,
                        contentDescription = stringResource(R.string.sk_086),
                        modifier = Modifier.size(12.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ 当以下情况发生
    CardGap()
    SkSectionTitle(stringResource(R.string.sk_087))
    Card(
        modifier = Modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        state.mappings.forEachIndexed { index, mapping ->
            if (index > 0) SkRowDivider()
            SkItemRow(
                icon = mapping.key.icon,
                title = stringResource(R.string.sk_088, mapping.key.label, mapping.press.label),
                subtitle = mapping.pressDetail(),
                onClick = { triggerIndex = index },
            )
        }
        if (state.mappings.isNotEmpty()) SkRowDivider()
        SkAddRow(text = stringResource(R.string.sk_059)) { addTriggerOpen = true }
    }

    // ------------------------------------------------------------ 且满足…状态
    CardGap()
    SkSectionTitleDropdown(
        text = stringResource(R.string.sk_089, state.logic.shortLabel),
        options = listOf(Logic.AND.shortLabel, Logic.OR.shortLabel),
        selectedIndex = if (state.logic == Logic.AND) 0 else 1,
        onSelect = { state = state.copy(logic = if (it == 0) Logic.AND else Logic.OR) },
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        state.conditions.forEachIndexed { index, condition ->
            if (index > 0) SkRowDivider()
            SkItemRow(
                icon = condition.type.icon,
                title = condition.type.label,
                subtitle = condition.detail(context),
                onClick = { conditionIndex = index },
            )
        }
        if (state.conditions.isNotEmpty()) SkRowDivider()
        SkAddRow(text = stringResource(R.string.sk_090), onClick = onAddCondition)
    }

    // ------------------------------------------------------------ 就执行
    CardGap()
    SkSectionTitle(stringResource(R.string.sk_091))
    Card(
        modifier = Modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        state.mappings.forEachIndexed { index, mapping ->
            if (index > 0) SkRowDivider()
            SkItemRow(
                icon = mapping.action.icon,
                title = mapping.action.label,
                subtitle = stringResource(R.string.sk_092, mapping.key.label, mapping.press.label),
                onClick = { actionIndex = index },
            )
        }
        if (state.mappings.isNotEmpty()) SkRowDivider()
        SkAddRow(text = stringResource(R.string.sk_063)) { addActionOpen = true }
    }

    // ------------------------------------------------------------ 规则设置
    CardGap()
    SkSectionTitle(stringResource(R.string.sk_093))
    Card(
        modifier = Modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        SkSwitchRow(
            title = stringResource(R.string.sk_094),
            summary = if (state.enabled) stringResource(R.string.sk_095) else stringResource(R.string.sk_096),
            checked = state.enabled,
            // Switch 自带触感，不重复调用。
            onCheckedChange = { state = state.copy(enabled = it) },
        )
        SkRowDivider(startIndent = 16)
        SkValueRow(
            title = stringResource(R.string.sk_097),
            value = state.priority.toString(),
            summary = stringResource(R.string.sk_098),
            onClick = { priorityOpen = true },
        )
    }

    // ------------------------------------------------------------ 对话框
    val triggerMapping = state.mappings.getOrNull(triggerIndex)
    SkTriggerDialog(
        show = triggerMapping != null,
        mapping = triggerMapping ?: KeyMapping(KeyType.VOLUME_UP),
        onKeyChange = { key -> updateMapping(draft, triggerIndex) { it.copy(key = key) } },
        onPressChange = { press -> updateMapping(draft, triggerIndex) { it.copy(press = press) } },
        onDelete = {
            val index = triggerIndex
            triggerIndex = -1
            if (index >= 0) {
                state = state.copy(
                    mappings = state.mappings.filterIndexed { i, _ -> i != index }
                )
            }
        },
        onDismiss = { triggerIndex = -1 },
    )

    val actionMapping = state.mappings.getOrNull(actionIndex)
    SkOptionDialog(
        show = actionMapping != null,
        title = stringResource(R.string.sk_099),
        summary = stringResource(R.string.sk_100),
        options = ActionType.values().map { it.label },
        selectedIndex = ActionType.values().indexOf(actionMapping?.action ?: ActionType.PASS_THROUGH)
            .coerceAtLeast(0),
        onSelect = { index ->
            updateMapping(draft, actionIndex) { it.copy(action = ActionType.values()[index]) }
        },
        onDismiss = { actionIndex = -1 },
        onDelete = {
            val index = actionIndex
            actionIndex = -1
            if (index >= 0) {
                state = state.copy(
                    mappings = state.mappings.filterIndexed { i, _ -> i != index }
                )
            }
        },
    )

    val condition = state.conditions.getOrNull(conditionIndex)
    val pickAppsAction: (() -> Unit)? = if (condition != null && condition.type in appConditionTypes) {
        {
            val index = conditionIndex
            conditionIndex = -1
            onPickApps(index)
        }
    } else {
        null
    }
    SkConditionDialog(
        show = condition != null,
        condition = condition ?: Condition(ConditionType.SCREEN_ON),
        onPickApps = pickAppsAction,
        onDelete = {
            val index = conditionIndex
            conditionIndex = -1
            if (index >= 0) {
                state = state.copy(
                    conditions = state.conditions.filterIndexed { i, _ -> i != index }
                )
            }
        },
        onDismiss = { conditionIndex = -1 },
    )

    // ------------------------------------------------------------ 新增（先选后加）
    SkNewTriggerDialog(
        show = addTriggerOpen,
        onConfirm = { key, press ->
            state = state.copy(mappings = state.mappings + KeyMapping(key = key, press = press))
            addTriggerOpen = false
        },
        onDismiss = { addTriggerOpen = false },
    )

    val lastMapping = state.mappings.lastOrNull()
    SkNewActionDialog(
        show = addActionOpen,
        baseKey = lastMapping?.key ?: KeyType.VOLUME_UP,
        basePress = lastMapping?.press ?: PressType.SHORT,
        onConfirm = { action ->
            state = state.copy(
                mappings = state.mappings + KeyMapping(
                    key = lastMapping?.key ?: KeyType.VOLUME_UP,
                    press = lastMapping?.press ?: PressType.SHORT,
                    action = action,
                )
            )
            addActionOpen = false
        },
        onDismiss = { addActionOpen = false },
    )

    SkOptionDialog(
        show = priorityOpen,
        title = stringResource(R.string.sk_101),
        summary = stringResource(R.string.sk_102),
        // sk_103 是带 %1$d 的格式串，必须把档位数值传进去，否则选项里原样显示「优先级 %1$d」。
        options = (0..10).map { stringResource(R.string.sk_103, it) },
        selectedIndex = state.priority.coerceIn(0, 10),
        onSelect = { state = state.copy(priority = it) },
        onDismiss = { priorityOpen = false },
    )
}

/**
 * 名称/说明用的无底色输入框。
 *
 * `Miuix` 的 `TextField` 自带 `secondaryContainer` 灰底，米家的标题卡是「白底直接写字」，
 * 用 `BasicTextField` 才能既保留 Miuix 的字体与主色光标、又不出灰块。
 */
@Composable
private fun SkPlainTextField(
    value: String,
    placeholder: String,
    textStyle: TextStyle,
    textColor: androidx.compose.ui.graphics.Color,
    onValueChange: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle.copy(color = textColor),
        cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                innerTextField()
            }
        },
    )
}

private fun updateMapping(
    draft: MutableState<Rule>,
    index: Int,
    transform: (KeyMapping) -> KeyMapping,
) {
    if (index < 0) return
    val state = draft.value
    if (state.mappings.getOrNull(index) == null) return
    draft.value = state.copy(
        mappings = state.mappings.mapIndexed { i, m -> if (i == index) transform(m) else m }
    )
}
