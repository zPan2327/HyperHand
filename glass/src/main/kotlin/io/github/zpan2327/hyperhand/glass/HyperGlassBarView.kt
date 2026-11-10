package io.github.zpan2327.hyperhand.glass

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import io.github.zpan2327.liquidglass.IosLiquidGlassNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * The liquid-glass tab bar from LiquidDemo, packaged as a plain Android [View] so HyperHand's
 * javac-based build can drop it into the existing `FrameLayout`.
 *
 * Mirrors the API of the Java `GlassBar` it replaces: [select], [setSelectedImmediate],
 * [setPagePosition], [setOnTabSelected], [setBackdropSource].
 *
 * 与原 `GlassBar` 的几何对齐：胶囊 64dp 高、最宽 76dp×3+8dp、距宿主底部 7dp（原实现 78dp 高
 * 的 View 里上下各留 7dp 给按下放大与投影）。所以这里把组件自带的底部内边距覆盖成 7dp，并在
 * 顶部留出 [OverhangRoom] 给放大/投影，其他尺寸一律交给组件自己算。
 */
class HyperGlassBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    /** Java-friendly tab callback, same shape as the previous `GlassBar.OnTabSelected`. */
    fun interface OnTabSelected {
        fun onSelected(index: Int)
    }

    private var currentIndex by mutableIntStateOf(0)
    private var pagePosition by mutableStateOf<Float?>(null)
    private var accent by mutableStateOf<Int?>(null)
    /**
     * 只用于「强制重画」的计数器。
     *
     * 页面滚动时玻璃必须重新采样背后的像素，但 View#invalidate() 只会让父 View 的
     * RenderNode 变脏，Compose 那张 AndroidComposeView 仍然是干净的，玻璃会僵在
     * 滚动前那一帧。在绘制阶段读这个状态，才会真正让 Compose 重跑 draw。
     */
    private val redrawTick = mutableIntStateOf(0)
    private var items by mutableStateOf(defaultItems())
    private var dark by mutableStateOf(isNightMode())
    private var listener: OnTabSelected? = null
    private val backdrop = ViewBackdrop()

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    /** Selects a tab without notifying [setOnTabSelected]. */
    fun select(index: Int, animate: Boolean) {
        // 动画交给组件自己的 selectedIndex 变化；先清掉跟手位置，否则它会立刻把指示器钉住。
        pagePosition = null
        currentIndex = index
    }

    /** Restores the selection during activity setup, before any listener is attached. */
    fun setSelectedImmediate(index: Int) {
        pagePosition = null
        currentIndex = index
    }

    /**
     * 页面横滑过程中的连续位置（0..2），指示器直接跟手——原 `GlassBar.setPagePosition` 的语义。
     */
    fun setPagePosition(position: Float) {
        pagePosition = position
    }

    fun setSelectedIndex(index: Int) {
        currentIndex = index
    }

    fun setOnTabSelected(l: OnTabSelected?) {
        listener = l
    }

    /** Marks the view whose pixels the glass samples. Replaces `GlassBar.setBackdropSource`. */
    fun setBackdropSource(source: View?) {
        backdrop.source = source
    }

    /** Called when the page scrolls so the sampled backdrop follows. */
    fun onContentScrolled() {
        // 一次拖动/惯性里 onScrollChange 每帧可能来好几次，合并成一帧一次，
        // 否则会在一帧内反复重组。
        if (redrawScheduled) return
        redrawScheduled = true
        Choreographer.getInstance().postFrameCallback {
            redrawScheduled = false
            redrawTick.intValue++
        }
    }

    private var redrawScheduled = false

    /** 强调色（选中态的图标/文字）。不设则用 miuix 主题默认值。 */
    fun setAccentColor(argb: Int) {
        accent = argb
    }

    fun setDarkMode(isDark: Boolean) {
        dark = isDark
    }

    @Composable
    override fun Content() {
        val currentItems = items
        val isDark = dark
        val accentArgb = accent
        // 组合期读一次：页面滚动时靠它驱动重组（进而重建 drawBackdrop 的绘制节点）。
        val tick = redrawTick.intValue
        // miuix 的默认主题色和 HyperHand 的设计 token 是同一套（primary #3482FF、
        // surfaceContainer #FFFFFF），所以浅/深色方案直接用默认值，只把 primary 换成
        // 宿主传进来的强调色。
        val colors = if (isDark) {
            darkColorScheme(primary = accentArgb?.let { Color(it) } ?: Color(0xFF3482FF))
        } else {
            lightColorScheme(primary = accentArgb?.let { Color(it) } ?: Color(0xFF3482FF))
        }
        MiuixTheme(colors = colors) {
            IosLiquidGlassNavigationBar(
                items = remember(currentItems) { currentItems },
                selectedIndex = currentIndex,
                onItemClick = { index ->
                    currentIndex = index
                    listener?.onSelected(index)
                },
                backdrop = backdrop,
                position = pagePosition,
                // 宿主已经用 margin 让开系统导航栏，组件不要再叠加一遍 inset。
                bottomPadding = BottomGap,
                // 胶囊在这个 Composable 里是顶格画的，按下时容器会整体放大 + 10dp 投影。
                // ComposeView 的绘制会被自己的边界裁掉，没有余量时胶囊顶边会被削平成一条直线。
                modifier = Modifier.padding(top = OverhangRoom),
                redrawSignal = tick,
            )
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private companion object {
        /** 胶囊底边到 View 底边的距离，与原 GlassBar 的 7dp 溢出量一致。 */
        val BottomGap = 7.dp

        /** 按下放大 + 投影需要的溢出余量（只加在顶部，胶囊视觉位置不变）。 */
        val OverhangRoom = 14.dp

        fun defaultItems(): List<NavigationItem> = listOf(
            NavigationItem(label = "概览", icon = MiuixIcons.Home),
            NavigationItem(label = "设置", icon = MiuixIcons.Settings),
            NavigationItem(label = "关于", icon = MiuixIcons.Info),
        )
    }
}
