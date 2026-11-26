package io.github.zpan2327.hyperhand.smartkey

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zpan2327.hyperhand.smartkey.data.ConditionType
import io.github.zpan2327.hyperhand.smartkey.ui.AppPickerScreen
import io.github.zpan2327.hyperhand.smartkey.ui.SmartKeyScaffold
import io.github.zpan2327.hyperhand.smartkey.ui.rememberSkHaptics
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 应用选择页（三级窗口）。
 *
 * 回来路分两种，都由调用方的 requestCode 区分：
 * - 「编辑页 → 添加状态条件 → 选择应用」；
 * - 「编辑页 → 点某条应用类条件 → 重新选择应用」。
 *
 * 两种都只回传包名列表，由调用方决定如何组装条件，本页不关心条件类型。
 */
class SkAppPickerActivity : SkActivity() {

    companion object {
        const val EXTRA_CONDITION_TYPE = "condition_type"
        const val EXTRA_SELECTED = "selected_packages"

        /**
         * 是否把 [EXTRA_SELECTED] 里的应用置顶。
         *
         * 只有「编辑已有条件 → 更换应用」才置顶：用户此刻的目标是在一堆应用里找到**当前那个**，
         * 把它放在最前面最省事。正常「添加状态条件 → 选应用」不置顶，保持常规浏览顺序
         * （否则列表开头会突然跳出一组已勾选项，反而打乱找应用的视线）。
         */
        const val EXTRA_PIN_SELECTED = "pin_selected"
        const val RESULT_PACKAGES = "packages"
    }

    /** 已选包名。不放进 remember 之外的状态，配置变更由系统重建窗口时重新读取 Intent。 */
    private val selected = mutableStateOf<Set<String>>(emptySet())

    /** 是否置顶已选（见 [EXTRA_PIN_SELECTED]）。 */
    private var pinSelected = false

    override fun onCreateState(savedInstanceState: Bundle?) {
        selected.value = intent.getStringArrayListExtra(EXTRA_SELECTED)?.toSet() ?: emptySet()
        pinSelected = intent.getBooleanExtra(EXTRA_PIN_SELECTED, false)
    }

    @Composable
    override fun Content() {
        val haptics = rememberSkHaptics()
        SmartKeyScaffold(
            title = "选择应用",
            onBack = { finish() },
            bottomBar = {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    pressFeedbackType = PressFeedbackType.None,
                ) {
                    Button(
                        onClick = {
                            haptics.clickPrimary()
                            setResult(
                                RESULT_OK,
                                Intent().putStringArrayListExtra(
                                    RESULT_PACKAGES,
                                    ArrayList(selected.value),
                                ),
                            )
                            finish()
                        },
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text("确定", color = MiuixTheme.colorScheme.onPrimary)
                    }
                }
            },
        ) { padding, behavior ->
            AppPickerScreen(
                selected = selected.value,
                onToggle = { pkg ->
                    selected.value = if (pkg in selected.value) selected.value - pkg
                    else selected.value + pkg
                },
                padding = padding,
                scrollBehavior = behavior,
                pinSelected = pinSelected,
            )
        }
    }
}
