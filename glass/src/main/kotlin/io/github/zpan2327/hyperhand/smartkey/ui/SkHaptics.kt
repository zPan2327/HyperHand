package io.github.zpan2327.hyperhand.smartkey.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import io.github.zpan2327.hyperhand.Haptics

/**
 * 把 HyperHand 主设置页那套澎湃触感接到 Compose 上。
 *
 * 触感语义与 `Ui.java` / `TopBar.java` / `MiuixCheckbox.java` 完全一致，不另造一套：
 * - 列表行、下拉选择、返回：`tick()`（TAP_LIGHT，最轻一档）
 * - 主按钮：`clickPrimary()`（BUTTON_MIDDLE）
 * - 次按钮、图标按钮：`click()`（BUTTON_SMALL）
 * - 开关、勾选框：`toggle()`（SWITCH）
 */
private val LocalSkHaptics = staticCompositionLocalOf<Haptics?> { null }

/**
 * 页面级提供者：整个 SmartKey 界面只在这里 attach 一次宿主 View。
 *
 * 不能让每个页面各自 attach：页面切换时新旧两页会同时存在，
 * 旧页销毁时会把新页正在用的宿主一起解绑，导致后续震动静默失效。
 */
@Composable
fun ProvideSkHaptics(content: @Composable () -> Unit) {
    val view = LocalView.current
    val haptics = remember(view) { Haptics.get(view.context) }
    DisposableEffect(view) {
        haptics.attach(view)
        onDispose { haptics.detach(view) }
    }
    CompositionLocalProvider(LocalSkHaptics provides haptics, content = content)
}

/** 取当前页可用的触感实例；若外层忘了包 [ProvideSkHaptics]，退化为就地挂载。 */
@Composable
fun rememberSkHaptics(): Haptics {
    LocalSkHaptics.current?.let { return it }
    val view = LocalView.current
    val haptics = remember(view) { Haptics.get(view.context) }
    DisposableEffect(view) {
        haptics.attach(view)
        onDispose { haptics.detach(view) }
    }
    return haptics
}
