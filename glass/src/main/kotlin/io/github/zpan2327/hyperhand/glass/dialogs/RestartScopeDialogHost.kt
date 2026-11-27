package io.github.zpan2327.hyperhand.glass.dialogs

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Java 友好桥接：把 [RestartScopeDialog] 这个 Compose 弹窗装进一个普通 Android View。
 *
 * OverlayDialog（经 DialogLayout）必须有 root 级 MiuixPopupHost 才能渲染，否则弹窗不显示。
 * 这里用一个透明、零 insets 的 Scaffold 提供该宿主，主题深浅跟随系统
 * （与 HyperHand 其余 miuix 组件同源）。
 *
 * Java 侧用法：
 *   RestartScopeDialogHost host = new RestartScopeDialogHost(activity);
 *   host.setRestartScopes(scopes);                 // List<RestartScope>，Kotlin data class 对 Java 可见
 *   host.setCallbacks(onDismiss, onConfirm);
 *   // 放进一个全屏透明 Dialog 的 contentView，再 host.show()；
 *   // 关闭时 host.hide() 让 OverlayDialog 走退出动画。
 */
class RestartScopeDialogHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    fun interface OnDismiss {
        fun onDismiss()
    }

    fun interface OnConfirm {
        fun onConfirm(selectedPackages: List<String>)
    }

    private var show by mutableStateOf(false)
    private var scopes by mutableStateOf<List<RestartScope>>(emptyList())
    private var dismissCb by mutableStateOf<OnDismiss?>(null)
    private var confirmCb by mutableStateOf<OnConfirm?>(null)
    private var dark by mutableStateOf(isNightMode())

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun setRestartScopes(items: List<RestartScope>) {
        scopes = items
    }

    fun setCallbacks(onDismiss: OnDismiss?, onConfirm: OnConfirm?) {
        dismissCb = onDismiss
        confirmCb = onConfirm
    }

    fun show() {
        show = true
    }

    fun hide() {
        show = false
    }

    fun setDarkMode(isDark: Boolean) {
        dark = isDark
    }

    @Composable
    override fun Content() {
        val colors = if (dark) darkColorScheme() else lightColorScheme()
        MiuixTheme(colors = colors) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
            ) {
                RestartScopeDialog(
                    show = show,
                    scopes = scopes,
                    onDismissRequest = { dismissCb?.onDismiss() },
                    onConfirm = { selected -> confirmCb?.onConfirm(selected) },
                )
            }
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
