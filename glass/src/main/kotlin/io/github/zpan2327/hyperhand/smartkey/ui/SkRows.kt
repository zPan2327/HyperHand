package io.github.zpan2327.hyperhand.smartkey.ui

import io.github.zpan2327.hyperhand.glass.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.zpan2327.hyperhand.smartkey.data.ActionType
import io.github.zpan2327.hyperhand.smartkey.data.Condition
import io.github.zpan2327.hyperhand.smartkey.data.KeyMapping
import io.github.zpan2327.hyperhand.smartkey.data.KeyType
import io.github.zpan2327.hyperhand.smartkey.data.PressType
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 规则编辑页的版式底座。
 *
 * 版面照米家「创建/编辑自动化」的形态对齐：分组标题 → 卡片组 → 组内「图标 + 主标题 +
 * 副标题 + 右箭头」信息行 → 组尾主色「＋ 添加…」行；分组标题本身可带一个下拉箭头
 * （「且满足全部状态 ⌄」）。具体数值仍回到 Miuix 规范：
 * - 分组标题用 `SmallTitle` 的原始度量：14sp / Bold / `onBackgroundVariant` / (28dp, 8dp)；
 * - 卡片圆角 16dp、底色 `surfaceContainer`，`InsideMargin` 为 0，行内边距自己给 16dp；
 * - 信息行：图标 26dp、标题 17sp、副标题 13sp，上下各 12dp；
 * - 分隔线 0.75dp，左侧缩进到文字起点（16 + 26 + 14 = 56dp）；
 * - 「添加」行用 `primary`（米家里是品牌绿，这里用主题主色，不写死颜色）。
 */

// ---------------------------------------------------------------- 分组标题

/** 与 Miuix `SmallTitle` 完全同度量，让同页所有分组标题走同一个入口。 */
@Composable
fun SkSectionTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 8.dp),
    )
}

/**
 * 可切换的分组标题（米家的「且满足全部状态 ⌄」）。
 *
 * 点标题就地弹 Miuix 下拉浮层（`OverlayDropdownPopup`：圆角 16dp、`surfaceContainer`、
 * 锚在标题下方 8dp、带背景压暗），不在页面里内联展开列表。
 */
@Composable
fun SkSectionTitleDropdown(
    text: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val haptics = rememberSkHaptics()

    Box(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    haptics.tick()
                    expanded = true
                }
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MiuixTheme.textStyles.subtitle,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Spacer(Modifier.width(4.dp))
            // 行尾箭头旋转 90° 当「可展开」的小折角：`ExpandMore` 在 14dp 下笔画糊成一团，
            // 直接复用信息行同一枚 ArrowRight（10×16）转过来，粗细与行内箭头一致。
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .size(width = 10.dp, height = 16.dp)
                    .rotate(90f),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        // 浮层选项自带 Miuix 触感（DropdownImpl 内部发 HapticFeedback），这里不再叠加。
        OverlayDropdownPopup(
            entries = listOf(
                DropdownEntry(
                    items = options.mapIndexed { index, label ->
                        DropdownItem(
                            text = label,
                            selected = index == selectedIndex,
                            onClick = {
                                expanded = false
                                onSelect(index)
                            },
                        )
                    },
                )
            ),
            show = expanded,
            onDismiss = { expanded = false },
            onDismissFinished = {},
            maxHeight = 420.dp,
            dropdownColors = DropdownDefaults.dropdownColors(),
            renderInRootScaffold = true,
        )
    }
}

// ---------------------------------------------------------------- 卡片内的行

/** 信息行：图标 + 主标题 + 副标题 + 右箭头。副标题单行省略，避免长包名把行撑成两行。 */
@Composable
fun SkItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    val haptics = rememberSkHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = MiuixIcons.Basic.ArrowRight,
            contentDescription = null,
            modifier = Modifier.size(width = 10.dp, height = 16.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}

/** 组尾的主色「＋ 添加…」行（米家是浅绿圆底加号 + 绿字，这里用主色同一处理）。 */
@Composable
fun SkAddRow(text: String, onClick: () -> Unit) {
    val haptics = rememberSkHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标格与信息行同为 26dp，加号圆底正好落在同一列上。
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = MiuixIcons.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

/** 行间分隔线；[startIndent] 默认对齐到文字起点（56dp），卡片内其它行的分隔用 16dp。 */
@Composable
fun SkRowDivider(startIndent: Int = 56) {
    HorizontalDivider(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startIndent.dp),
        thickness = 0.75.dp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f),
    )
}

/** 开关行（是否启用）。`Switch` 自带 Miuix 触感，这里不叠加。 */
@Composable
fun SkSwitchRow(
    title: String,
    summary: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 带当前值的可点行（优先级这类「点开再选」的项）。 */
@Composable
fun SkValueRow(
    title: String,
    value: String,
    summary: String?,
    onClick: () -> Unit,
) {
    val haptics = rememberSkHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.main,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = MiuixIcons.Basic.ArrowRight,
            contentDescription = null,
            modifier = Modifier.size(width = 10.dp, height = 16.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
    }
}

// ---------------------------------------------------------------- 选择对话框

/** 对话框里的一行单选项：选中项标题用主色，尾部补一个勾。 */
@Composable
fun SkOptionRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val haptics = rememberSkHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptics.tick()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = MiuixIcons.Ok,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/** 对话框内的分组小标题（比页面分组标题更小、更贴边）。 */
@Composable
private fun SkDialogGroupLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

/**
 * 对话框底部按钮（UI 规范 §2.1）：等宽横排、间距 8dp，左次要（灰底）右主要（主色）。
 * 只有一个动作时占满整行。
 */
@Composable
private fun SkDialogButtons(
    left: Pair<String, () -> Unit>?,
    rightText: String,
    onRight: () -> Unit,
) {
    val haptics = rememberSkHaptics()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (left != null) {
            TextButton(
                text = left.first,
                onClick = {
                    haptics.click()
                    left.second()
                },
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(
            text = rightText,
            onClick = {
                haptics.clickPrimary()
                onRight()
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

/**
 * 单列选择对话框（执行动作、优先级都用它）。
 *
 * 选项超出高度时在卡片内部滚动，不把对话框撑出屏幕；给了 [onDelete] 才出现「删除」。
 */
@Composable
fun SkOptionDialog(
    show: Boolean,
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    summary: String? = null,
    deleteLabel: String = stringResource(R.string.sk_051),
    onDelete: (() -> Unit)? = null,
) {
    OverlayDialog(
        show = show,
        title = title,
        summary = summary,
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                pressFeedbackType = PressFeedbackType.None,
            ) {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    options.forEachIndexed { index, label ->
                        if (index > 0) SkRowDivider(startIndent = 16)
                        SkOptionRow(title = label, selected = index == selectedIndex) {
                            onSelect(index)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SkDialogButtons(
                left = onDelete?.let { deleteLabel to it },
                rightText = stringResource(R.string.sk_052),
                onRight = onDismiss,
            )
        }
    }
}

/**
 * 触发按键对话框：物理按键 + 按压方式两组单选。
 *
 * 只有 2 + 4 个选项，用两组单选比内联两个下拉更好点，也省得再叠一层浮层。
 */
@Composable
fun SkTriggerDialog(
    show: Boolean,
    mapping: KeyMapping,
    onKeyChange: (KeyType) -> Unit,
    onPressChange: (PressType) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.sk_053),
        summary = stringResource(R.string.sk_054, mapping.key.label, mapping.press.label),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            // 6 个选项在小屏上可能超出「窗口高 2/3」的对话框上限：选项区自己滚，按钮固定在下面。
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SkDialogGroupLabel(stringResource(R.string.sk_055))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    pressFeedbackType = PressFeedbackType.None,
                ) {
                    KeyType.values().forEachIndexed { index, key ->
                        if (index > 0) SkRowDivider(startIndent = 16)
                        SkOptionRow(title = key.label, selected = key == mapping.key) {
                            onKeyChange(key)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SkDialogGroupLabel(stringResource(R.string.sk_056))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    pressFeedbackType = PressFeedbackType.None,
                ) {
                    PressType.values().forEachIndexed { index, press ->
                        if (index > 0) SkRowDivider(startIndent = 16)
                        SkOptionRow(title = press.label, selected = press == mapping.press) {
                            onPressChange(press)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            SkDialogButtons(
                left = stringResource(R.string.sk_051) to onDelete,
                rightText = stringResource(R.string.sk_052),
                onRight = onDismiss,
            )
        }
    }
}

/** 状态条件对话框：条件本身没有参数页，这里只提供「更换应用 / 删除」。 */
@Composable
fun SkConditionDialog(
    show: Boolean,
    condition: Condition,
    onPickApps: (() -> Unit)?,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = condition.type.label,
        // 副标题给应用**名称**（不是包名）。
        summary = condition.detail(androidx.compose.ui.platform.LocalContext.current)
            ?: stringResource(R.string.sk_057),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            SkDialogButtons(
                left = stringResource(R.string.sk_051) to onDelete,
                rightText = if (onPickApps != null) stringResource(R.string.sk_058) else stringResource(R.string.sk_052),
                onRight = { onPickApps?.invoke() ?: onDismiss() },
            )
        }
    }
}


/**
 * 「添加触发按键」的选择对话框：先选物理按键 + 按压方式，点「添加」才真正追加一条映射。
 *
 * 之前是点一下就直接在列表尾部塞一条 `音量加 · 短按`，用户还得再点开这一行改一遍，
 * 等于两步操作；这里把「新增」和「编辑」分开：[SkTriggerDialog] 改已有行，
 * 本对话框负责新增，带取消/添加，未确认不会污染草稿。
 */
@Composable
fun SkNewTriggerDialog(
    show: Boolean,
    onConfirm: (KeyType, PressType) -> Unit,
    onDismiss: () -> Unit,
) {
    // 每次打开都从默认值开始，不记住上一次的选择（避免误加）。
    var key by remember(show) { mutableStateOf(KeyType.VOLUME_UP) }
    var press by remember(show) { mutableStateOf(PressType.SHORT) }

    OverlayDialog(
        show = show,
        title = stringResource(R.string.sk_059),
        summary = stringResource(R.string.sk_060, key.label, press.label),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SkDialogGroupLabel(stringResource(R.string.sk_055))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    pressFeedbackType = PressFeedbackType.None,
                ) {
                    KeyType.values().forEachIndexed { index, item ->
                        if (index > 0) SkRowDivider(startIndent = 16)
                        SkOptionRow(title = item.label, selected = item == key) { key = item }
                    }
                }
                Spacer(Modifier.height(12.dp))
                SkDialogGroupLabel(stringResource(R.string.sk_056))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    pressFeedbackType = PressFeedbackType.None,
                ) {
                    PressType.values().forEachIndexed { index, item ->
                        if (index > 0) SkRowDivider(startIndent = 16)
                        SkOptionRow(title = item.label, selected = item == press) { press = item }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            SkDialogButtons(
                left = stringResource(R.string.sk_061) to onDismiss,
                rightText = stringResource(R.string.sk_062),
                onRight = { onConfirm(key, press) },
            )
        }
    }
}

/**
 * 「添加执行动作」的选择对话框：先选动作，确认后才追加。
 *
 * 一条映射是「触发 + 动作」的整体，所以新增动作行时同时要有一条触发；
 * 这里沿用**上一条映射的按键与按压方式**（没有上一条就用音量加 · 短按），
 * 并把这件事写进摘要，避免用户以为动作是凭空多出来的。
 */
@Composable
fun SkNewActionDialog(
    show: Boolean,
    baseKey: KeyType,
    basePress: PressType,
    onConfirm: (ActionType) -> Unit,
    onDismiss: () -> Unit,
) {
    var action by remember(show) { mutableStateOf(ActionType.NEXT) }

    OverlayDialog(
        show = show,
        title = stringResource(R.string.sk_063),
        summary = stringResource(R.string.sk_064, baseKey.label, basePress.label),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                pressFeedbackType = PressFeedbackType.None,
            ) {
                Column(
                    Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    ActionType.values().forEachIndexed { index, item ->
                        if (index > 0) SkRowDivider(startIndent = 16)
                        SkOptionRow(title = item.label, selected = item == action) { action = item }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            SkDialogButtons(
                left = stringResource(R.string.sk_061) to onDismiss,
                rightText = stringResource(R.string.sk_062),
                onRight = { onConfirm(action) },
            )
        }
    }
}

/**
 * 「有未保存修改」的退出确认。
 *
 * 取消 = 关掉对话框留在本页继续编辑；退出 = 丢弃草稿直接返回上一页，不写任何数据。
 */
@Composable
fun SkDiscardChangesDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = stringResource(R.string.sk_065),
        summary = stringResource(R.string.sk_066),
        onDismissRequest = onDismiss,
    ) {
        Column(Modifier.fillMaxWidth()) {
            SkDialogButtons(
                left = stringResource(R.string.sk_061) to onDismiss,
                rightText = stringResource(R.string.sk_067),
                onRight = onConfirm,
            )
        }
    }
}
