package io.github.zpan2327.hyperhand.glass

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
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
    private var items by mutableStateOf(defaultItems(context))
    private var dark by mutableStateOf(isNightMode())
    private var listener: OnTabSelected? = null
    private val backdrop = ViewBackdrop()

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    /**
     * 胶囊底边到本 View 底边的距离（dp）。
     *
     * 按下 / 拖动时胶囊会放大并带 10dp 投影，这些都要画在胶囊之外；而 Compose 在 Android 上
     * **画不出宿主 View 的边界**，余量不够时下缘的投影和放大量就被一条水平线切掉
     * （「拖动时底栏下面缺一截」）。宿主把自己贴到屏幕底，把胶囊下方本来空着的
     * 「12dp 外边距 + 系统手势区」全部算进这里，胶囊的屏幕位置不变，投影有地方画。
     */
    private var bottomRoomDp by mutableStateOf(7f)

    /** 胶囊底边以下预留的绘制余量（dp）。宿主在 insets 回调里按实际手势区高度设置。 */
    fun setBottomRoom(dp: Float) {
        if (bottomRoomDp != dp) bottomRoomDp = dp
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
        if (pagePosition != position) {
            pagePosition = position
        }
    }

    /**
     * 收回跟手位置，指示器改由自身弹簧追 [currentIndex]。
     *
     * 代码触发的切页（点底栏、外部 intent 切页）必须走这里：那种滚动是 280ms 的一大段，
     * 每帧位移能到半格，让指示器逐帧跟它就会看到「飞一下、卡几帧、再跳一下」。
     */
    fun clearPagePosition() {
        if (pagePosition != null) {
            pagePosition = null
        }
    }

    fun setSelectedIndex(index: Int) {
        currentIndex = index
    }

    fun setOnTabSelected(l: OnTabSelected?) {
        listener = l
    }

    /** Marks the view whose pixels the glass samples. Replaces `GlassBar.setBackdropSource`. */
    fun setBackdropSource(source: View?, fallbackColor: Int) {
        backdrop.source = source
        backdrop.fallbackColor = fallbackColor
    }

    /** Called when the page scrolls so the sampled backdrop follows. */
    fun onContentScrolled() {
        // 不节流：每来一次滚动信号就 +1。一帧内写多次也会被 Compose 合并成一次重组，
        // 所以底栏的重采样率等于屏幕刷新率（120Hz 屏就是 120fps）。
        //
        // 曾经这里按 16ms 节流，等于把底栏锁死在 60fps：120Hz 屏上内容每 8.3ms 动一次、
        // 玻璃却每 16.6ms 才采一次，看起来就是「玻璃只有一半帧在更新」（用户反馈的
        // 「采样率太低 / 帧率有点低」）。
        //
        // 也**不要**用「布尔锁存位 + Choreographer 回调复位」做合并：那个位一旦挂住，
        // 之后所有滚动信号都会被 return 掉，玻璃会永久冻结到下次点击底栏。
        redrawTick.intValue++
    }

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
        // 组合期读一次：值变化驱动底栏重组，绘制节点随之重建、背板重新采样。
        //
        // 试过改到绘制期（`Modifier.drawWithContent { redrawTick.intValue }`，本意是
        // 只让绘制失效、不重组），真机实测**失效**：recomp/s 变 0 的同时采样次数也变 0，
        // 子树 draw 并没有重跑，玻璃冻在上一帧。这条路径不要再试。
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
                bottomPadding = bottomRoomDp.dp,
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
        /** 按下放大 + 投影需要的溢出余量（只加在顶部，胶囊视觉位置不变）。 */
        val OverhangRoom = 14.dp

        fun defaultItems(c: android.content.Context): List<NavigationItem> = listOf(
            NavigationItem(label = c.getString(R.string.tab_overview), icon = MiuixIcons.Home),
            NavigationItem(label = c.getString(R.string.tab_settings), icon = MiuixIcons.Settings),
            NavigationItem(label = c.getString(R.string.tab_about), icon = MiuixIcons.Info),
        )
    }
}
