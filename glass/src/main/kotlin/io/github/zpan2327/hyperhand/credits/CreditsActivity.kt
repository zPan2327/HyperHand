package io.github.zpan2327.hyperhand.credits

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.zpan2327.hyperhand.glass.R
import io.github.zpan2327.hyperhand.smartkey.ui.CardGap
import io.github.zpan2327.hyperhand.smartkey.ui.ProvideSkHaptics
import io.github.zpan2327.hyperhand.smartkey.ui.SmartKeyScaffold
import io.github.zpan2327.hyperhand.smartkey.ui.SmartKeyScaffoldContent
import io.github.zpan2327.hyperhand.smartkey.ui.SkRowDivider
import io.github.zpan2327.hyperhand.smartkey.ui.rememberSkHaptics
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 「引用与致谢」二级页面（关于页 → 引用）。
 *
 * 原来是一段塞在对话框里的长文；现在改成独立窗口 + Miuix 列表：
 * 顶部一张说明卡，中间一张列表卡逐项列出**源码里实际引用到**的项目
 * （见 [Projects]），每一行点击打开对应仓库，底部一段版权说明。
 *
 * 版式与按键映射各页同一套：[SmartKeyScaffold]（可折叠大标题 + 返回箭头）、
 * `Card` 分组、`ArrowPreference` 行。转场由系统窗口动画完成。
 */
class CreditsActivity : ComponentActivity() {

    /** 列表中的一个项目：仓库路径 + 文案资源 id。 */
    private class Project(
        val url: String,
        val titleRes: Int,
        val summaryRes: Int,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        applySystemBarIcons()
        setContent {
            val dark = remember {
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                ProvideSkHaptics { Screen() }
            }
        }
    }

    @Composable
    private fun Screen() {
        val context = LocalContext.current
        val haptics = rememberSkHaptics()
        SmartKeyScaffold(
            title = stringResource(R.string.cr_title),
            largeTitle = stringResource(R.string.cr_title),
            subtitle = stringResource(R.string.cr_subtitle),
            onBack = { finish() },
        ) { padding, behavior ->
            SmartKeyScaffoldContent(padding, behavior) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    pressFeedbackType = PressFeedbackType.None,
                ) {
                    Text(
                        text = stringResource(R.string.cr_intro),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }

                CardGap()

                Card(modifier = Modifier.fillMaxWidth(), pressFeedbackType = PressFeedbackType.None) {
                    Projects.forEachIndexed { index, p ->
                        if (index > 0) SkRowDivider(startIndent = 16)
                        ArrowPreference(
                            title = stringResource(p.titleRes),
                            summary = stringResource(p.summaryRes),
                            onClick = {
                                haptics.tick()
                                openRepository(context, p.url)
                            },
                        )
                    }
                }

                CardGap()

                Text(
                    text = stringResource(R.string.cr_footnote),
                    style = MiuixTheme.textStyles.footnote2,
                    fontWeight = FontWeight.Normal,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }

    /** 在浏览器里打开仓库；没有任何应用能处理时给一条提示，不让页面崩掉。 */
    private fun openRepository(context: android.content.Context, url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            Toast.makeText(context, R.string.cr_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applySystemBarIcons() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val flag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        window.decorView.systemUiVisibility =
            if (dark) window.decorView.systemUiVisibility and flag.inv()
            else window.decorView.systemUiVisibility or flag
    }

    private companion object {
        /**
         * 源码里实际引用到的开源项目。
         *
         * 清单来源：原关于页长文（5 项）+ 代码与 README 里出现过的仓库地址。
         * 其中 LiquidDemo 是底栏渲染实现（`io.github.zpan2327.liquidglass`）的来源，
         * 原长文里漏掉了，这里补上；顺序按在界面里出现的先后。
         */
        @JvmField
        val Projects = listOf(
            Project(
                "https://github.com/compose-miuix-ui/miuix",
                R.string.cr_miuix_title, R.string.cr_miuix_summary,
            ),
            Project(
                "https://github.com/Kyant0/AndroidLiquidGlass",
                R.string.cr_liquidglass_title, R.string.cr_liquidglass_summary,
            ),
            Project(
                "https://github.com/zPan2327/LiquidDemo",
                R.string.cr_liquiddemo_title, R.string.cr_liquiddemo_summary,
            ),
            Project(
                "https://github.com/Mercury000/SonyPods",
                R.string.cr_sonypods_title, R.string.cr_sonypods_summary,
            ),
            Project(
                "https://github.com/tiann/KernelSU",
                R.string.cr_kernelsu_title, R.string.cr_kernelsu_summary,
            ),
            Project(
                "https://github.com/libxposed/api",
                R.string.cr_libxposed_title, R.string.cr_libxposed_summary,
            ),
        )
    }
}
