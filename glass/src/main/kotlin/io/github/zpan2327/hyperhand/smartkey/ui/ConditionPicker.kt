package io.github.zpan2327.hyperhand.smartkey.ui

import io.github.zpan2327.hyperhand.glass.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.zpan2327.hyperhand.smartkey.data.ConditionType
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 条件类型选择页：按 Miuix 规范分区展示，行组件使用 ArrowPreference。
 *
 * 这是一个**独立页面**（`SkConditionPickerActivity`，独立窗口），由编辑页的
 * 「添加状态条件」进入，不在编辑页内联展开——内联展开时条件列表会把编辑页撑得很长，
 * 也缺少明确的前进/返回层级。
 * 滚动由外层 [SmartKeyScaffoldContent] 统一负责，这里不再嵌套 verticalScroll。
 */
@Composable
fun ColumnScope.ConditionTypePickerContent(
    onPick: (ConditionType) -> Unit,
    onPickAppCondition: (ConditionType) -> Unit,
) {
    val haptics = rememberSkHaptics()
    val groups = listOf(
        stringResource(R.string.sk_129) to listOf(
            ConditionType.APP_PLAYING, ConditionType.APP_RUNNING, ConditionType.APP_NOT_RUNNING,
            ConditionType.APP_FOREGROUND, ConditionType.APP_NOT_FOREGROUND,
            ConditionType.APP_PAUSED, ConditionType.APP_NO_SESSION, ConditionType.ANY_APP_PLAYING,
        ),
        stringResource(R.string.sk_130) to listOf(
            ConditionType.AUDIO_PLAYING, ConditionType.AUDIO_PAUSED, ConditionType.AUDIO_STOPPED,
            ConditionType.NO_MEDIA_SESSION, ConditionType.MULTIPLE_SESSIONS,
        ),
        stringResource(R.string.sk_131) to listOf(
            ConditionType.SCREEN_ON, ConditionType.SCREEN_OFF,
            ConditionType.UNLOCKED, ConditionType.LOCKED,
        ),
        stringResource(R.string.sk_132) to listOf(
            ConditionType.CHARGING, ConditionType.NOT_CHARGING,
        ),
    )

    groups.forEachIndexed { index, (groupName, types) ->
        // 第一组紧接顶栏，PageScaffold 已有 8dp 上边距，再加 12dp 会显得空。
        if (index > 0) CardGap()
        SmallTitle(text = groupName)
        Card(
            modifier = Modifier.fillMaxWidth(),
            pressFeedbackType = PressFeedbackType.None,
        ) {
            types.forEach { type ->
                ArrowPreference(
                    title = type.label,
                    onClick = {
                        haptics.tick()
                        if (type in appConditionTypes) onPickAppCondition(type) else onPick(type)
                    },
                )
            }
        }
    }
}
