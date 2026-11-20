package io.github.zpan2327.hyperhand.glass.dialogs

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 概览「模块已激活」状态卡：用真正的 MIUIX [Card] 承载按压倾斜反馈
 * （pressFeedbackType = Tilt、showIndication = true），与 SonyPods StatusCard
 * 同一套库实现，因此按压手感一致。
 *
 * 重要：**视觉（配色、字号字重、图标位置）完全由 Java 侧 OverviewPage 决定并传入**，
 * 本宿主不自行计算任何颜色，避免改动原有配色：
 *  - [setBackground] 传 applyHero() 算好的精确背景色（含夜间色 / 探测中中性色）；
 *  - [setTitle]/[setSummary] 传文字与精确颜色；
 *  - [provideIconView] 传入 module 侧 Canvas 图标 View，颜色由 Java 侧 setIconColor 管理，
 *    宿主只负责摆放——右下角 96dp、负 margin 溢出（与原 FrameLayout 版完全一致）。
 */
class StatusCardHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AbstractComposeView(context, attrs) {

    private var bgColor by mutableStateOf(0xFFDFFAE4.toInt())
    private var title by mutableStateOf("")
    private var titleColor by mutableStateOf(0xFF101010.toInt())
    private var summary by mutableStateOf("")
    private var summaryColor by mutableStateOf(0xFF2F3A32.toInt())
    private var iconView by mutableStateOf<View?>(null)

    init {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
    }

    /** 传入 module 侧 Canvas 图标 View；颜色、尺寸由该 View 自身管理，宿主只摆放位置。 */
    fun provideIconView(v: View?) {
        iconView = v
    }

    /** 背景色：由 applyHero() 精确计算后传入，含夜间 / 探测中 / 未启用各态。 */
    fun setBackground(color: Int) {
        bgColor = color
    }

    fun setTitle(text: String, color: Int) {
        title = text
        titleColor = color
    }

    fun setSummary(text: String, color: Int) {
        summary = text
        summaryColor = color
    }

    @Composable
    override fun Content() {
        val dark = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val colors = if (dark) darkColorScheme() else lightColorScheme()
        MiuixTheme(colors = colors) {
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.defaultColors(color = Color(bgColor)),
                pressFeedbackType = PressFeedbackType.Tilt,
                showIndication = true,
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 图标：还原原 FrameLayout 版的位置——96dp、gravity Bottom|End、
                    // rightMargin=-22dp、bottomMargin=-24dp（图标向右下溢出、压在角上）。
                    // Compose 用 BottomEnd 对齐 + offset(22,24) 向外推实现同样的溢出。
                    val iv = iconView
                    if (iv != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset(22.dp, 24.dp),
                            contentAlignment = Alignment.BottomEnd,
                        ) {
                            AndroidView(factory = { iv })
                        }
                    }
                    // 文字：padding 16dp，标题 20sp Bold（用户要求的状态卡加粗），
                    // 摘要 14sp Medium、顶部间距 2dp、最多两行——均与原 TextView 版一致。
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            text = title,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(titleColor),
                        )
                        Text(
                            text = summary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(summaryColor),
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
