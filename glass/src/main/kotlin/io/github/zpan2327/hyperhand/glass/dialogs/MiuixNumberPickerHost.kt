package io.github.zpan2327.hyperhand.glass.dialogs

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
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
import top.yukonga.miuix.kmp.basic.ButtonDefaults
// 使用项目内逐字复制自 MIUIX 的 LocalNumberPicker（仅修 itemHeightPx 测量时序，见该文件注释）。
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * Java 友好的 MIUIX 单列 NumberPicker 桥接层。
 *
 * 数值设置项不再走 Dropdown，改为和 SonyPods「临时电量岛显示时长」同款的 MIUIX
 * [NumberPicker] —— 唯一差别是 SonyPods 用两列（数值 + 单位），这里只需要一列：
 * 每个选项已经把数值和单位拼成完整 label，滚轮直接显示它。选中项用「索引 = 值」
 * 表达（range = 0..labels.size-1，label = { labels[it] }），和原 Dropdown 的数据流
 * 完全一致：Java 侧拿到确认索引后仍按 values[index] 回写，配置键 / 广播不变。
 *
 * 宿主结构与 [SonyPodsDialogHost] 一致：透明、零 insets 的 root Scaffold 提供
 * OverlayDialog 必须的 MiuixPopupHost；没有弹窗时 dispatchTouchEvent 把触摸还给下层。
 */
class MiuixNumberPickerHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    fun interface OnConfirm {
        fun onConfirm(index: Int)
    }

    fun interface OnDismiss {
        fun onDismiss()
    }

    internal data class Request(
        val title: String,
        val labels: List<String>,
        val selectedIndex: Int,
        val onConfirm: OnConfirm,
        val onDismiss: OnDismiss,
    )

    private var request by mutableStateOf<Request?>(null)
    private var showing by mutableStateOf(false)
    private var dark by mutableStateOf(isNightMode())

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    /**
     * @param labels 完整的可选值文本（数值 + 单位已在 Java 侧拼好）
     * @param selectedIndex 当前选中项索引，越界时收敛到合法范围
     */
    fun show(
        title: String,
        labels: List<String>,
        selectedIndex: Int,
        onConfirm: OnConfirm,
        onDismiss: OnDismiss,
    ) {
        request = Request(title, labels, selectedIndex, onConfirm, onDismiss)
        showing = true
    }

    /** 当前是否有滚轮弹窗展开（返回键判断用）。 */
    fun isShowing(): Boolean = showing

    /** 主动关闭（切页 / 返回 / 页面重建）：不等待退场动画。 */
    fun hide() {
        showing = false
        request = null
    }

    fun setDarkMode(isDark: Boolean) {
        dark = isDark
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (!showing) return false
        return super.dispatchTouchEvent(event)
    }

    private fun onDismissRequest() {
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
                if (current != null && current.labels.isNotEmpty()) {
                    SingleNumberPickerDialog(
                        request = current,
                        show = showing,
                        onDismissRequest = { onDismissRequest() },
                    )
                }
            }
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}

@Composable
private fun SingleNumberPickerDialog(
    request: MiuixNumberPickerHost.Request,
    show: Boolean,
    onDismissRequest: () -> Unit,
) {
    // 每次打开都从当前存档值对应的索引起步（show 变化时重建状态）。
    var selected by remember(request) { mutableIntStateOf(request.selectedIndex) }
    val lastIndex = (request.labels.size - 1).coerceAtLeast(0)

    OverlayDialog(
        title = request.title,
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        LocalNumberPicker(
            value = selected.coerceIn(0, lastIndex),
            onValueChange = { selected = it },
            range = 0..lastIndex,
            label = { request.labels.getOrNull(it) ?: "" },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = "取消",
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = "确定",
                onClick = {
                    request.onConfirm.onConfirm(selected.coerceIn(0, lastIndex))
                    onDismissRequest()
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
