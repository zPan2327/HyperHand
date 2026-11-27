package io.github.zpan2327.hyperhand.smartkey.ui

import io.github.zpan2327.hyperhand.glass.R
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.zpan2327.hyperhand.smartkey.data.ActionType
import io.github.zpan2327.hyperhand.smartkey.data.AppCatalog
import io.github.zpan2327.hyperhand.smartkey.data.Condition
import io.github.zpan2327.hyperhand.smartkey.data.ConditionType
import io.github.zpan2327.hyperhand.smartkey.data.KeyMapping
import io.github.zpan2327.hyperhand.smartkey.data.KeyType
import io.github.zpan2327.hyperhand.smartkey.data.Logic
import io.github.zpan2327.hyperhand.smartkey.data.PressType
import io.github.zpan2327.hyperhand.smartkey.data.Rule
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Hide
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.MicSlash
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Playlist
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Remove
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Unlock
import top.yukonga.miuix.kmp.icon.extended.VolumeOff
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ---- 展示用标签 ----
// 这些属性被 Compose 页面和（少量）带 Context 的普通函数共用，所以统一改成
// `xxx(context)` 取值：非 composable 的地方没法用 stringResource。

fun ConditionType.label(c: Context): String = c.getString(
    when (this) {
        ConditionType.APP_PLAYING -> R.string.sk_cond_app_playing
        ConditionType.APP_RUNNING -> R.string.sk_cond_app_running
        ConditionType.APP_NOT_RUNNING -> R.string.sk_cond_app_not_running
        ConditionType.ANY_APP_PLAYING -> R.string.sk_cond_any_app_playing
        ConditionType.APP_FOREGROUND -> R.string.sk_cond_app_foreground
        ConditionType.APP_NOT_FOREGROUND -> R.string.sk_cond_app_not_foreground
        ConditionType.APP_PAUSED -> R.string.sk_cond_app_paused
        ConditionType.APP_NO_SESSION -> R.string.sk_cond_app_no_session
        ConditionType.SCREEN_ON -> R.string.sk_cond_screen_on
        ConditionType.SCREEN_OFF -> R.string.sk_cond_screen_off
        ConditionType.UNLOCKED -> R.string.sk_cond_unlocked
        ConditionType.LOCKED -> R.string.sk_cond_locked
        ConditionType.CHARGING -> R.string.sk_cond_charging
        ConditionType.NOT_CHARGING -> R.string.sk_cond_not_charging
        ConditionType.AUDIO_PLAYING -> R.string.sk_cond_audio_playing
        ConditionType.AUDIO_PAUSED -> R.string.sk_cond_audio_paused
        ConditionType.AUDIO_STOPPED -> R.string.sk_cond_audio_stopped
        ConditionType.NO_MEDIA_SESSION -> R.string.sk_cond_no_session
        ConditionType.MULTIPLE_SESSIONS -> R.string.sk_cond_multi_session
    }
)

fun KeyType.label(c: Context): String = c.getString(
    when (this) {
        KeyType.VOLUME_UP -> R.string.sk_key_up
        KeyType.VOLUME_DOWN -> R.string.sk_key_down
    }
)

fun PressType.label(c: Context): String = c.getString(
    when (this) {
        PressType.SHORT -> R.string.sk_press_short
        PressType.LONG -> R.string.sk_press_long
        PressType.DOUBLE -> R.string.sk_press_double
        PressType.REPEAT -> R.string.sk_press_repeat
    }
)

fun ActionType.label(c: Context): String = c.getString(
    when (this) {
        ActionType.PASS_THROUGH -> R.string.sk_action_pass
        ActionType.NEXT -> R.string.sk_action_next
        ActionType.PREVIOUS -> R.string.sk_action_previous
        ActionType.PLAY -> R.string.sk_action_play
        ActionType.PAUSE -> R.string.sk_action_pause
        ActionType.PLAY_PAUSE -> R.string.sk_action_play_pause
    }
)

fun Logic.label(c: Context): String = c.getString(
    when (this) {
        Logic.AND -> R.string.sk_logic_and
        Logic.OR -> R.string.sk_logic_or
    }
)

// Compose 里直接用属性写法（`mapping.key.label`），省得每个调用点都取一次 Context。
// 与上面的 `label(c)` 是同一个映射，只是把 Context 换成 LocalContext。

val ConditionType.label: String
    @Composable get() = label(LocalContext.current)

val KeyType.label: String
    @Composable get() = label(LocalContext.current)

val PressType.label: String
    @Composable get() = label(LocalContext.current)

val ActionType.label: String
    @Composable get() = label(LocalContext.current)

val Logic.label: String
    @Composable get() = label(LocalContext.current)

/** 「且满足全部/任一状态」用短标签，完整说明见 [Logic.label]。 */
val Logic.shortLabel: String
    @Composable get() = shortLabel(LocalContext.current)

/** 需要选择具体应用的条件类型。 */
val appConditionTypes: Set<ConditionType> = setOf(
    ConditionType.APP_PLAYING,
    ConditionType.APP_RUNNING,
    ConditionType.APP_NOT_RUNNING,
    ConditionType.APP_FOREGROUND,
    ConditionType.APP_NOT_FOREGROUND,
    ConditionType.APP_PAUSED,
    ConditionType.APP_NO_SESSION,
)

/**
 * 已选应用的显示名。
 *
 * 规则里存的是包名（`com.netease.cloudmusic`），但界面上给用户看包名没有意义——
 * 这里统一换成应用名称，走 [AppCatalog] 的包名→名称索引（清单已加载时几乎零成本）。
 * 超过两个应用时收敛成「A、B 等 N 个应用」，避免副标题撑成好几行。
 */
fun Condition.appNames(context: Context): String {
    if (packages.isEmpty()) return ""
    val names = packages.map { AppCatalog.labelOf(context, it) }
    return if (names.size <= 2) {
        context.getString(R.string.sk_app_join, names.joinToString(context.getString(R.string.sk_list_sep)))
    } else {
        context.getString(
            R.string.sk_app_join_more,
            names.take(2).joinToString(context.getString(R.string.sk_list_sep)),
            names.size,
        )
    }
}

fun Condition.summary(context: Context): String =
    type.label(context) + if (packages.isEmpty()) "" else
        context.getString(R.string.sk_cond_with_apps, appNames(context))

/** 条件行副标题：应用类条件显示已选**应用名称**，其它类型没有副标题。 */
fun Condition.detail(context: Context): String? = when {
    packages.isNotEmpty() -> appNames(context)
    type in appConditionTypes -> context.getString(R.string.sk_no_app_selected)
    else -> null
}

/** 按压方式的说明（触发行的副标题），把该按压方式的可调参数一并写出来。 */
fun KeyMapping.pressDetail(c: Context): String = when (press) {
    PressType.SHORT -> c.getString(R.string.sk_press_short_detail)
    PressType.LONG -> c.getString(R.string.sk_press_long_detail, longPressMs)
    PressType.DOUBLE -> c.getString(R.string.sk_press_double_detail, doubleTapMs)
    PressType.REPEAT -> c.getString(R.string.sk_press_repeat_detail, repeatCount)
}

/** Compose 版本：不传 Context，其它逻辑与 [pressDetail] 相同。 */
@Composable
fun KeyMapping.pressDetail(): String = pressDetail(LocalContext.current)

/** 「且满足全部/任一状态」用短标签，完整说明见 [Logic.label]。 */
fun Logic.shortLabel(c: Context): String = c.getString(
    if (this == Logic.AND) R.string.sk_logic_short_and else R.string.sk_logic_short_or
)

// ---- 行图标（规则编辑页的信息行）----
// 米家的每行左侧都有设备缩略图；这里没有设备，按条件的语义取 Miuix 图标。

val KeyType.icon: ImageVector get() = when (this) {
    KeyType.VOLUME_UP -> MiuixIcons.VolumeUp
    KeyType.VOLUME_DOWN -> MiuixIcons.VolumeOff
}

val ActionType.icon: ImageVector get() = when (this) {
    ActionType.PASS_THROUGH -> MiuixIcons.Ok
    ActionType.NEXT -> MiuixIcons.ChevronForward
    ActionType.PREVIOUS -> MiuixIcons.ChevronBackward
    ActionType.PLAY -> MiuixIcons.Play
    ActionType.PAUSE -> MiuixIcons.Pause
    ActionType.PLAY_PAUSE -> MiuixIcons.Play
}

val ConditionType.icon: ImageVector get() = when (this) {
    ConditionType.APP_PLAYING,
    ConditionType.ANY_APP_PLAYING,
    ConditionType.AUDIO_PLAYING,
    ConditionType.AUDIO_PAUSED,
    ConditionType.AUDIO_STOPPED -> MiuixIcons.Music

    ConditionType.APP_RUNNING,
    ConditionType.APP_NOT_RUNNING,
    ConditionType.APP_FOREGROUND,
    ConditionType.APP_NOT_FOREGROUND,
    ConditionType.APP_PAUSED -> MiuixIcons.GridView

    ConditionType.APP_NO_SESSION,
    ConditionType.NO_MEDIA_SESSION -> MiuixIcons.MicSlash

    ConditionType.MULTIPLE_SESSIONS -> MiuixIcons.Playlist

    ConditionType.SCREEN_ON -> MiuixIcons.Show
    ConditionType.SCREEN_OFF -> MiuixIcons.Hide
    ConditionType.UNLOCKED -> MiuixIcons.Unlock
    ConditionType.LOCKED -> MiuixIcons.Lock

    ConditionType.CHARGING -> MiuixIcons.Refresh
    ConditionType.NOT_CHARGING -> MiuixIcons.Remove
}

/** 规则摘要（首页列表用）：条件带上应用名称。 */
fun Rule.conditionsDesc(context: Context): String =
    if (conditions.isEmpty()) context.getString(R.string.sk_no_condition)
    else conditions.joinToString(context.getString(R.string.sk_cond_sep)) { it.summary(context) }

fun Rule.mappingsDesc(c: Context): String =
    if (mappings.isEmpty()) c.getString(R.string.sk_no_mapping)
    else mappings.joinToString(c.getString(R.string.sk_mapping_sep)) {
        c.getString(R.string.sk_mapping_item, it.key.label(c), it.press.label(c), it.action.label(c))
    }

/**
 * 智能按键映射页面统一骨架。
 *
 * 严格按 Miuix 规范：
 * - Scaffold 使用 surface 容器，TopAppBar 与内容通过 [ScrollBehavior] 联动；
 * - Home 用可折叠大标题栏，二级页面用固定 SmallTopAppBar；
 * - 返回按钮使用 Miuix IconButton + Miuix 图标，不手写文本箭头。
 */
@Composable
fun SmartKeyScaffold(
    title: String,
    largeTitle: String? = null,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues, ScrollBehavior) -> Unit,
) {
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = MiuixScrollBehavior(topAppBarState)
    val haptics = rememberSkHaptics()

    val navigation: @Composable () -> Unit = {
        if (onBack != null) {
            IconButton(onClick = { haptics.click(); onBack() }) {
                Icon(
                    imageVector = MiuixIcons.Back,
                    contentDescription = stringResource(R.string.sk_back),
                    tint = MiuixTheme.colorScheme.onSurface,
                )
            }
        }
    }

    Scaffold(
        topBar = {
            if (largeTitle != null) {
                TopAppBar(
                    title = title,
                    largeTitle = largeTitle,
                    subtitle = subtitle ?: "",
                    navigationIcon = navigation,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            } else {
                SmallTopAppBar(
                    title = title,
                    navigationIcon = navigation,
                    actions = actions,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = bottomBar,
    ) { padding ->
        content(padding, scrollBehavior)
    }
}

/**
 * 卡片之间的标准间距，取自 HyperHand UI 规范 §2.4「卡片：圆角 16dp，间距 12dp」。
 *
 * 关键：Miuix 的 [SmallTitle] 自带 `PaddingValues(28.dp, 8.dp)`，它已经提供了
 * 分组标题与卡片之间的呼吸感，因此标题与紧跟的卡片之间**不得**再插入间距，
 * 否则上下 padding 会与卡片间距叠加，出现「间距翻倍」的松垮排版。
 * 只有在两张卡片之间、或「卡片 → 下一组标题」之间才使用本间距。
 */
@Composable
internal fun CardGap() {
    Spacer(Modifier.height(12.dp))
}
