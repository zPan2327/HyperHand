package io.github.zpan2327.hyperhand.smartkey

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zpan2327.hyperhand.smartkey.data.Condition
import io.github.zpan2327.hyperhand.smartkey.data.Rule
import io.github.zpan2327.hyperhand.smartkey.ui.EditRuleScreenContent
import io.github.zpan2327.hyperhand.smartkey.ui.SmartKeyScaffold
import io.github.zpan2327.hyperhand.smartkey.ui.SmartKeyScaffoldContent
import io.github.zpan2327.hyperhand.smartkey.ui.rememberSkHaptics
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 新建/编辑规则页（独立窗口）。
 *
 * 草稿只活在本窗口内：进「添加状态条件」「选择应用」都是 [registerForActivityResult]，
 * 结果回来后合并进草稿，草稿不跨窗口传递，避免多份副本互相覆盖。
 * 旋转/进程重建时草稿随 [onSaveInstanceState] 一起恢复。
 *
 * 版面对齐米家「编辑自动化」：大标题 + 分组卡片，底部是**悬浮**的整行「保存」按钮
 * （内容从按钮下面滚过去，所以 [EditRuleScreenContent] 末尾留了 84dp 空档）。
 */
class SkEditRuleActivity : SkActivity() {

    companion object {
        const val EXTRA_RULE_ID = "rule_id"
        private const val STATE_DRAFT = "draft_json"
        private const val STATE_PENDING_INDEX = "pending_app_index"
    }

    private lateinit var draftState: MutableState<Rule>
    private val saving = mutableStateOf(false)

    /** 点「更换应用」时记住是第几条条件，结果回来据此替换（按位置而不是按类型，避免同类型多条互相串）。 */
    private var pendingAppIndex: Int = -1

    private val conditionPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val json = result.data?.getStringExtra(SkConditionPickerActivity.RESULT_CONDITION)
            ?: return@registerForActivityResult
        val condition = Condition.fromJson(JSONObject(json)) ?: return@registerForActivityResult
        draftState.value = draftState.value.copy(
            conditions = draftState.value.conditions + condition
        )
    }

    private val appPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val index = pendingAppIndex
        pendingAppIndex = -1
        if (index < 0 || result.resultCode != RESULT_OK) return@registerForActivityResult
        val packages = result.data?.getStringArrayListExtra(SkAppPickerActivity.RESULT_PACKAGES)
            ?: return@registerForActivityResult
        val state = draftState.value
        if (state.conditions.getOrNull(index) == null) return@registerForActivityResult
        draftState.value = state.copy(
            conditions = state.conditions.mapIndexed { i, c ->
                if (i == index) c.copy(packages = packages) else c
            }
        )
    }

    override fun onCreateState(savedInstanceState: Bundle?) {
        pendingAppIndex = savedInstanceState?.getInt(STATE_PENDING_INDEX, -1) ?: -1
        val restored = savedInstanceState?.getString(STATE_DRAFT)
            ?.let { runCatching { Rule.fromJson(JSONObject(it)) }.getOrNull() }
        draftState = mutableStateOf(
            restored ?: loadInitialDraft() ?: Rule(id = repo.newId(), name = "", enabled = true)
        )
    }

    private fun loadInitialDraft(): Rule? {
        val ruleId = intent.getStringExtra(EXTRA_RULE_ID) ?: return null
        return repo.loadRules().firstOrNull { it.id == ruleId }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::draftState.isInitialized) {
            outState.putString(STATE_DRAFT, draftState.value.toJson().toString())
        }
        outState.putInt(STATE_PENDING_INDEX, pendingAppIndex)
    }

    @Composable
    override fun Content() {
        val isNew = intent.getStringExtra(EXTRA_RULE_ID) == null
        val title = if (isNew) "新建规则" else "编辑规则"
        val haptics = rememberSkHaptics()
        val overlayOpen = remember { mutableStateOf(false) }

        SmartKeyScaffold(
            title = title,
            largeTitle = title,
            onBack = { finish() },
            // 「保存」整行主色按钮占满底部一条（米家的创建/保存按钮同位置）。
            // 必须放在 Scaffold 的 bottomBar 槽里而不是浮在 Scaffold 外层：两者的差别是 z 序 ——
            // 弹窗浮层由 Scaffold 内部的 MiuixPopupHost 渲染，外层 Box 里的按钮会画在弹窗之上，
            // 把对话框底部的「删除 / 完成」挡住（真机实测踩到过）。
            bottomBar = {
                // 高度写死 76dp（52dp 按钮 + 上下各 12dp）：弹窗打开时只是把按钮收起来，
                // 槽位仍占同样的高度，内容不会跟着上下跳。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(76.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!overlayOpen.value) Button(
                        onClick = {
                            if (saving.value) return@Button
                            haptics.clickPrimary()
                            val draft = draftState.value.let {
                                if (it.name.isBlank()) it.copy(name = "未命名规则") else it
                            }
                            saving.value = true
                            // upsert 会写 Settings.Global（首次可能触发 su 授权），放后台线程；
                            // 写完再关窗口，保证首页读到的一定是已落盘的数据。
                            Thread {
                                repo.upsert(draft)
                                runOnUiThread { finish() }
                            }.start()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(
                            text = "保存",
                            color = MiuixTheme.colorScheme.onPrimary,
                        )
                    }
                }
            },
        ) { padding, behavior ->
            SmartKeyScaffoldContent(padding, behavior) {
                EditRuleScreenContent(
                    draft = draftState,
                    onOverlayOpenChange = { overlayOpen.value = it },
                    onAddCondition = {
                        conditionPicker.launch(
                            Intent(this@SkEditRuleActivity, SkConditionPickerActivity::class.java)
                        )
                    },
                    onPickApps = { index ->
                        val condition = draftState.value.conditions.getOrNull(index)
                        if (condition != null) {
                            pendingAppIndex = index
                            // 「更换应用」：带上已选包名（可取消勾选），并把这些应用**置顶**——
                            // 此行的目的就是改当前这个应用，先让它在最前面。
                            appPicker.launch(
                                Intent(this@SkEditRuleActivity, SkAppPickerActivity::class.java)
                                    .putExtra(
                                        SkAppPickerActivity.EXTRA_CONDITION_TYPE,
                                        condition.type.id,
                                    )
                                    .putStringArrayListExtra(
                                        SkAppPickerActivity.EXTRA_SELECTED,
                                        ArrayList(condition.packages),
                                    )
                                    .putExtra(SkAppPickerActivity.EXTRA_PIN_SELECTED, true)
                            )
                        }
                    },
                )
            }
        }
    }
}
