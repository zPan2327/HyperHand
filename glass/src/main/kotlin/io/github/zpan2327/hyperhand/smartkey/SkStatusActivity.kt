package io.github.zpan2327.hyperhand.smartkey

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.zpan2327.hyperhand.smartkey.data.ModuleStatus
import io.github.zpan2327.hyperhand.smartkey.ui.SmartKeyScaffold
import io.github.zpan2327.hyperhand.smartkey.ui.SmartKeyScaffoldContent
import io.github.zpan2327.hyperhand.smartkey.ui.StatusScreenContent

/** 运行状态页（独立窗口，转场由系统完成）。 */
class SkStatusActivity : SkActivity() {

    @Composable
    override fun Content() {
        // resumeTick 参与 key：从设置页授权返回后重新检测，不显示过期状态。
        val rules = remember(resumeTick.intValue) { repo.loadRules() }
        val status = remember(resumeTick.intValue) {
            ModuleStatus.detect(this, rules.size)
        }
        SmartKeyScaffold(
            title = "运行状态",
            onBack = { finish() },
        ) { padding, behavior ->
            SmartKeyScaffoldContent(padding, behavior) {
                StatusScreenContent(status)
            }
        }
    }
}
