package io.github.zpan2327.hyperhand.smartkey

import io.github.zpan2327.hyperhand.glass.R
import androidx.compose.ui.res.stringResource
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import io.github.zpan2327.hyperhand.smartkey.data.Condition
import io.github.zpan2327.hyperhand.smartkey.data.ConditionType
import io.github.zpan2327.hyperhand.smartkey.ui.ConditionTypePickerContent
import io.github.zpan2327.hyperhand.smartkey.ui.SmartKeyScaffold
import io.github.zpan2327.hyperhand.smartkey.ui.SmartKeyScaffoldContent

/**
 * 「添加状态条件」页（二级窗口）。
 *
 * 选到应用类条件时要再进一层选应用（三级窗口），选完把**整条条件**回传给编辑页：
 * 编辑页不必知道中间经历了几个窗口。这样「编辑 → 条件列表 → 选应用」返回时
 * 直接落回编辑页，不会停留在条件列表上——用的是系统返回栈，不是手写的 popUntil。
 */
class SkConditionPickerActivity : SkActivity() {

    companion object {
        const val RESULT_CONDITION = "condition_json"
    }

    private var pendingAppType: ConditionType? = null

    private val appPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val type = pendingAppType ?: return@registerForActivityResult
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val packages = result.data?.getStringArrayListExtra(SkAppPickerActivity.RESULT_PACKAGES)
            ?: return@registerForActivityResult
        finishWith(Condition(type, packages))
    }

    @Composable
    override fun Content() {
        SmartKeyScaffold(
            title = stringResource(R.string.sk_090),
            onBack = { finish() },
        ) { padding, behavior ->
            SmartKeyScaffoldContent(padding, behavior) {
                ConditionTypePickerContent(
                    onPick = { type -> finishWith(Condition(type)) },
                    onPickAppCondition = { type ->
                        pendingAppType = type
                        appPicker.launch(
                            Intent(this@SkConditionPickerActivity, SkAppPickerActivity::class.java)
                                .putExtra(SkAppPickerActivity.EXTRA_CONDITION_TYPE, type.id)
                        )
                    },
                )
            }
        }
    }

    private fun finishWith(condition: Condition) {
        setResult(RESULT_OK, Intent().putExtra(RESULT_CONDITION, condition.toJson().toString()))
        finish()
    }
}
