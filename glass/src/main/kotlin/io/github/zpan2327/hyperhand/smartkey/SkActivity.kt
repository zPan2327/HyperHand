package io.github.zpan2327.hyperhand.smartkey

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import io.github.zpan2327.hyperhand.smartkey.data.AppCatalog
import io.github.zpan2327.hyperhand.smartkey.data.RuleRepository
import io.github.zpan2327.hyperhand.smartkey.ui.ProvideSkHaptics
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 智能按键映射各页面的公共基类。
 *
 * ## 为什么每个页面是一个独立 Activity
 *
 * 「HyperHand 设置页 → 智能按键映射」那一次的进入/退出动画是**系统窗口转场**
 * （Android 14+ 的 Shell Transition，由 WindowManager 在 Surface 层完成），
 * 应用侧没有任何代码参与。想在应用内复刻它，唯一忠实的做法就是让内层页面
 * 也变成真正的窗口——即独立 Activity，把动画完全交给系统。
 *
 * 之前两版都在单个 Activity 里用 Compose 手写位移/缩放/压暗去"仿"系统转场，
 * 参数是逐帧反推出来的近似值，观感始终对不上（用户三次反馈"不是一回事"）。
 * 现在不再仿：只要跳转方式相同，动画就必然逐帧相同。
 *
 * 副作用（都是正向的）：
 * - 返回栈由系统管理，三级页面按返回键回到上一级是天然行为，不再需要手写 NavState；
 * - 每个页面的滚动位置、输入焦点由各自的窗口独立保存；
 * - 页面之间不共享内存状态，规则一律经 [RuleRepository]（SharedPreferences）读写，
 *   不会出现"两个页面看到不同数据"。
 */
abstract class SkActivity : ComponentActivity() {

    protected lateinit var repo: RuleRepository

    /** 每次回到前台自增，驱动 Compose 重新读取规则与状态（下级页返回后立即刷新）。 */
    protected val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 应用主题自带 ActionBar；与 MainActivity 一样隐藏，避免叠加在 Miuix TopAppBar 上方。
        actionBar?.hide()
        applySystemBarIcons()
        repo = RuleRepository(this)
        // 应用清单预热：任一智能按键页面打开就开始在后台枚举并落盘，
        // 等用户点到「选择应用」时列表已经是现成的（幂等，重复调用无副作用）。
        AppCatalog.prewarm(this)
        onCreateState(savedInstanceState)
        setContent {
            val dark = remember {
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                // 触感宿主每个窗口挂一次（Activity 之间不同时复用同一个 View）。
                ProvideSkHaptics { Content() }
            }
        }
    }

    /** 在 [setContent] 之前初始化页面自有状态（可读取 [savedInstanceState] 与 Intent）。 */
    protected open fun onCreateState(savedInstanceState: Bundle?) {}

    @Composable
    protected abstract fun Content()

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    // ------------------------------------------------------------ 页面跳转
    // 全部走 startActivity：动画由系统窗口转场完成，与「设置页 → 智能按键映射」同源。

    /** 打开规则编辑页；[ruleId] 为 null 表示新建。 */
    protected fun openEditRule(ruleId: String?) {
        startActivity(
            Intent(this, SkEditRuleActivity::class.java)
                .putExtra(SkEditRuleActivity.EXTRA_RULE_ID, ruleId)
        )
    }

    /** 打开运行状态页。 */
    protected fun openStatus() {
        startActivity(Intent(this, SkStatusActivity::class.java))
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
}
