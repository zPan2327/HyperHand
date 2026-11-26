package io.github.zpan2327.hyperhand.smartkey.ui

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

// ---- 展示用中文标签 ----

val ConditionType.label: String get() = when (this) {
    ConditionType.APP_PLAYING -> "指定应用正在播放"
    ConditionType.APP_RUNNING -> "指定应用运行中"
    ConditionType.APP_NOT_RUNNING -> "指定应用未运行"
    ConditionType.ANY_APP_PLAYING -> "任意应用正在播放"
    ConditionType.APP_FOREGROUND -> "指定应用在前台"
    ConditionType.APP_NOT_FOREGROUND -> "指定应用不在前台"
    ConditionType.APP_PAUSED -> "指定应用已暂停"
    ConditionType.APP_NO_SESSION -> "指定应用无媒体会话"
    ConditionType.SCREEN_ON -> "屏幕亮起"
    ConditionType.SCREEN_OFF -> "屏幕熄灭"
    ConditionType.UNLOCKED -> "已解锁"
    ConditionType.LOCKED -> "锁屏状态"
    ConditionType.CHARGING -> "正在充电"
    ConditionType.NOT_CHARGING -> "未充电"
    ConditionType.AUDIO_PLAYING -> "正在播放"
    ConditionType.AUDIO_PAUSED -> "已暂停"
    ConditionType.AUDIO_STOPPED -> "已停止"
    ConditionType.NO_MEDIA_SESSION -> "无活动媒体会话"
    ConditionType.MULTIPLE_SESSIONS -> "多个媒体会话"
}

val KeyType.label: String get() = when (this) {
    KeyType.VOLUME_UP -> "音量加"
    KeyType.VOLUME_DOWN -> "音量减"
}

val PressType.label: String get() = when (this) {
    PressType.SHORT -> "短按"
    PressType.LONG -> "长按"
    PressType.DOUBLE -> "双击"
    PressType.REPEAT -> "连按"
}

val ActionType.label: String get() = when (this) {
    ActionType.PASS_THROUGH -> "保持原功能"
    ActionType.NEXT -> "下一首"
    ActionType.PREVIOUS -> "上一首"
    ActionType.PLAY -> "播放"
    ActionType.PAUSE -> "暂停"
    ActionType.PLAY_PAUSE -> "播放/暂停"
}

val Logic.label: String get() = when (this) {
    Logic.AND -> "全部满足（AND）"
    Logic.OR -> "任一满足（OR）"
}

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
    return if (names.size <= 2) names.joinToString("、")
    else names.take(2).joinToString("、") + " 等 ${names.size} 个应用"
}

fun Condition.summary(context: Context): String =
    type.label + if (packages.isEmpty()) "" else "（${appNames(context)}）"

/** 条件行副标题：应用类条件显示已选**应用名称**，其它类型没有副标题。 */
fun Condition.detail(context: Context): String? = when {
    packages.isNotEmpty() -> appNames(context)
    type in appConditionTypes -> "尚未选择应用"
    else -> null
}

/** 按压方式的说明（触发行的副标题），把该按压方式的可调参数一并写出来。 */
fun KeyMapping.pressDetail(): String = when (press) {
    PressType.SHORT -> "按下即触发"
    PressType.LONG -> "长按 ${longPressMs} 毫秒触发"
    PressType.DOUBLE -> "双击（间隔 ${doubleTapMs} 毫秒）"
    PressType.REPEAT -> "连续按 ${repeatCount} 次触发"
}

/** 「且满足全部/任一状态」用短标签，完整说明见 [Logic.label]。 */
val Logic.shortLabel: String get() = if (this == Logic.AND) "全部状态" else "任一状态"

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
    if (conditions.isEmpty()) "无条件" else conditions.joinToString(" + ") { it.summary(context) }

val Rule.mappingsDesc: String get() =
    if (mappings.isEmpty()) "未配置按键" else mappings.joinToString("；") {
        "${it.key.label}·${it.press.label} → ${it.action.label}"
    }

fun KeyMapping.summary(): String = "${key.label} · ${press.label} → ${action.label}"

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
                    contentDescription = "返回",
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
