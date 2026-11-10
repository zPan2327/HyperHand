// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package io.github.zpan2327.liquidglass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * One destination in a [LiquidGlassNavigationBar].
 *
 * Deliberately a plain label + [ImageVector] pair rather than miuix's `NavigationItem`, so call
 * sites only depend on `androidx.compose.ui`.
 */
@Immutable
data class LiquidTab(
    val label: String,
    val icon: ImageVector,
)

/**
 * The [LayerBackdrop] the nearest [LiquidGlassScaffold] records the page into.
 *
 * A glass bar is only glass when it has something to sample. [LiquidGlassScaffold] creates this
 * and records [LiquidGlassScaffold]'s `content` into it; [LiquidGlassNavigationBar] picks it up
 * automatically. Read it directly only when hand-rolling the layout.
 */
val LocalLiquidGlassBackdrop: ProvidableCompositionLocal<LayerBackdrop?> = staticCompositionLocalOf { null }

/**
 * Hosts a [LiquidGlassNavigationBar] over a page that can be seen through it.
 *
 * Everything passed as [content] is recorded into a layer that the bar samples, which is what
 * produces the refracted/frosted backdrop. The bar must therefore stay *outside* that recorded
 * subtree — which is why it lives in [bottomBar] here instead of inside [content].
 *
 * ```kotlin
 * LiquidGlassScaffold(
 *     bottomBar = {
 *         LiquidGlassNavigationBar(
 *             tabs = tabs,
 *             selectedIndex = selected,
 *             onTabClick = { selected = it },
 *         )
 *     },
 * ) {
 *     Image(bitmap = background, contentDescription = null, contentScale = ContentScale.Crop)
 * }
 * ```
 *
 * @param scrimColor painted underneath [content] inside the recorded layer. Only visible where
 *   [content] itself is transparent; a solid colour keeps the bar from sampling through to
 *   whatever was on screen before.
 */
@Composable
fun LiquidGlassScaffold(
    modifier: Modifier = Modifier,
    scrimColor: Color = Color.Black,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val backdrop = rememberLayerBackdrop {
        drawRect(scrimColor)
        drawContent()
    }
    CompositionLocalProvider(LocalLiquidGlassBackdrop provides backdrop) {
        Box(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                content()
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                bottomBar()
            }
        }
    }
}

/**
 * iOS 26 style liquid-glass tab bar: a floating pill with a draggable indicator, press-time
 * refraction and a specular rim highlight that follows device tilt.
 *
 * Requires a [LayerBackdrop] to actually look like glass. Use [LiquidGlassScaffold] and this
 * reads it from [LocalLiquidGlassBackdrop]; pass [backdrop] explicitly to sample some other
 * layer. With a `null` backdrop the bar degrades to a plain tinted pill.
 *
 * Colours come from the ambient miuix theme. Wrapping the call site in `MiuixTheme` matches the
 * app's palette; without it miuix's default light scheme is used.
 *
 * @param selectedIndex index of the highlighted tab; out-of-range values are coerced.
 * @param onTabClick invoked when a different tab is tapped or dragged onto.
 */
@Composable
fun LiquidGlassNavigationBar(
    tabs: List<LiquidTab>,
    selectedIndex: Int,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    backdrop: LayerBackdrop? = LocalLiquidGlassBackdrop.current,
) {
    val items = remember(tabs) { tabs.map { NavigationItem(label = it.label, icon = it.icon) } }
    IosLiquidGlassNavigationBar(
        items = items,
        selectedIndex = selectedIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
        onItemClick = onTabClick,
        backdrop = backdrop,
        modifier = modifier,
    )
}
