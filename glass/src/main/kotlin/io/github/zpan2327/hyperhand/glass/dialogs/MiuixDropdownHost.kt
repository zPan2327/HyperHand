package io.github.zpan2327.hyperhand.glass.dialogs

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Java 友好的 MIUIX Dropdown 桥接层。
 *
 * 设置页本身是 Java 手写 View，不能直接调用 Compose 的 OverlayDropdownPreference；
 * 而这个宿主是一张铺满 Activity 根容器的透明 View，和 RestartScopeDialogHost 一样处在同一
 * window 里，能提供 MIUIX popup 必须的 root Scaffold / MiuixPopupHost。
 *
 * Java 侧只需要把“当前行的屏幕矩形”传进来。我们在 Scaffold 内容里放一个同样位置的
 * 锚点 Box，让 OverlayListPopup 用它算位置（锚下 8dp、空间不足翻到上方、End 对齐）。
 * 弹窗本体、选项行、勾选态、禁用态、震动、退场动画全部走 MIUIX 官方组件，没有手写仿制品。
 */
class MiuixDropdownHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    fun interface OnSelected {
        fun onSelected(index: Int)
    }

    fun interface OnDismiss {
        fun onDismiss()
    }

    private data class Request(
        val items: List<String>,
        val summaries: List<String>?,
        val selectedIndex: Int,
        val anchorLeft: Int,
        val anchorTop: Int,
        val anchorWidth: Int,
        val anchorHeight: Int,
        val maxHeightPx: Int,
        val onSelected: OnSelected,
        val onDismiss: OnDismiss,
    )

    private var request by mutableStateOf<Request?>(null)
    private var showing by mutableStateOf(false)
    private var dark by mutableStateOf(isNightMode())

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    /**
     * @param summaries 每个选项下方的说明，可为 null；长度不足时自动补空。
     * @param anchorLeft/anchorTop 相对本宿主左上角的像素坐标（调用方用 getLocationOnScreen 相减）。
     */
    fun show(
        items: List<String>,
        summaries: List<String>?,
        selectedIndex: Int,
        anchorLeft: Int,
        anchorTop: Int,
        anchorWidth: Int,
        anchorHeight: Int,
        maxHeightPx: Int,
        onSelected: OnSelected,
        onDismiss: OnDismiss,
    ) {
        request = Request(
            items = items,
            summaries = summaries,
            selectedIndex = selectedIndex,
            anchorLeft = anchorLeft,
            anchorTop = anchorTop,
            anchorWidth = anchorWidth,
            anchorHeight = anchorHeight,
            maxHeightPx = maxHeightPx,
            onSelected = onSelected,
            onDismiss = onDismiss,
        )
        showing = true
    }

    /** 当前是否有下拉菜单展开（Java 侧返回键判断用）。 */
    fun isShowing(): Boolean = showing

    /** 主动关闭（切页 / 返回 / 页面重建）：不等待退场动画。 */
    fun hide() {
        showing = false
        request = null
    }

    fun setDarkMode(isDark: Boolean) {
        dark = isDark
    }

    /**
     * 没有弹窗时必须把触摸还给下层设置页；Compose 根节点本身不吃事件。
     * 有弹窗时交给 MIUIX 的蒙层处理“点外面关闭”。
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!showing) return false
        return super.dispatchTouchEvent(event)
    }

    private fun onPopupDismissed() {
        if (!showing) return
        showing = false
        request?.onDismiss?.onDismiss()
    }

    @Composable
    override fun Content() {
        val colors = if (dark) darkColorScheme() else lightColorScheme()
        MiuixTheme(colors = colors) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                val current = request
                if (current != null) {
                    val density = LocalDensity.current
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            modifier = Modifier
                                .offset { IntOffset(current.anchorLeft, current.anchorTop) }
                                .size(
                                    width = with(density) { current.anchorWidth.toDp() },
                                    height = with(density) { current.anchorHeight.toDp() },
                                ),
                        ) {
                            val entry = remember(current) {
                                DropdownEntry(
                                    items = current.items.mapIndexed { index, label ->
                                        DropdownItem(
                                            text = label,
                                            selected = index == current.selectedIndex,
                                            summary = current.summaries?.getOrNull(index),
                                            onClick = { current.onSelected.onSelected(index) },
                                        )
                                    },
                                )
                            }
                            OverlayDropdownPopup(
                                entry = entry,
                                show = showing,
                                onDismiss = { onPopupDismissed() },
                                onDismissFinished = { request = null },
                                maxHeight = with(density) { current.maxHeightPx.toDp() },
                                dropdownColors = DropdownDefaults.dropdownColors(),
                                renderInRootScaffold = true,
                                collapseOnSelection = true,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
