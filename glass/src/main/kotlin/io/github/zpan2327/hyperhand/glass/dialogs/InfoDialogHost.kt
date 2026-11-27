package io.github.zpan2327.hyperhand.glass.dialogs

import androidx.compose.ui.res.stringResource
import io.github.zpan2327.hyperhand.glass.R
import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Java 友好桥接：把长文本信息对话框（引用 / 使用说明 / 许可 / 运行状态）交给
 * MIUIX 原生的 [OverlayDialog] 渲染，而不是 Java 手写仿制品。
 *
 * 宿主结构与 [RestartScopeDialogHost] 完全一致——OverlayDialog（经 DialogLayout）
 * 必须有 root 级 MiuixPopupHost 才能渲染，因此这里用透明、零 insets 的 Scaffold
 * 提供宿主。Java 侧把本 View 叠到 Activity 根容器（与宿主同 window），再 show()。
 */
class InfoDialogHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    fun interface OnDismiss {
        fun onDismiss()
    }

    private var show by mutableStateOf(false)
    private var title by mutableStateOf("")
    private var body by mutableStateOf("")
    private var dismissCb by mutableStateOf<OnDismiss?>(null)
    private var dark by mutableStateOf(isNightMode())

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    fun setContent(dialogTitle: String, dialogBody: String) {
        title = dialogTitle
        body = dialogBody
    }

    fun setOnDismiss(cb: OnDismiss?) {
        dismissCb = cb
    }

    fun show() {
        show = true
    }

    fun hide() {
        show = false
    }

    fun isShowing(): Boolean = show

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
                InfoDialog(
                    show = show,
                    title = title,
                    body = body,
                    onDismissRequest = { dismissCb?.onDismiss() },
                )
            }
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

/**
 * 长文本信息对话框——严格使用 MIUIX 组件与排版：
 * - 标题走 OverlayDialog 内置 title（title4，18sp Medium，居中）；
 * - 正文用 MIUIX [Text] + body1（16sp）+ onSurfaceSecondary，可滚动、高度封顶 360dp；
 * - 底部单个主按钮 [TextButton]，minHeight 50dp、textButtonColorsPrimary、fillMaxWidth。
 */
@Composable
private fun InfoDialog(
    show: Boolean,
    title: String,
    body: String,
    onDismissRequest: () -> Unit,
) {
    OverlayDialog(
        title = title,
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = body,
                style = MiuixTheme.textStyles.body1,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            )
            TextButton(
                text = stringResource(R.string.sk_got_it),
                onClick = onDismissRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                minHeight = 50.dp,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
