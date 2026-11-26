package io.github.zpan2327.hyperhand.smartkey.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.zpan2327.hyperhand.smartkey.data.ModuleStatus
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** 运行状态页：所有状态来自真实检测与 Hook 层上报，不伪造激活状态。 */
@Composable
fun ColumnScope.StatusScreenContent(
    status: ModuleStatus,
) {
    SmallTitle(text = "Hook 状态")
    Card(
        modifier = Modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        StatusRow("system_server Hook", if (status.hookActive) "已激活" else "未激活", status.hookActive)
        StatusRow("LSPosed 安装", boolText(status.lsposedInstalled), status.lsposedInstalled)
        StatusRow("HyperHand 模块", boolText(status.moduleInstalled), status.moduleInstalled)
        StatusRow("最近 Hook 上报", status.lastHookReport, null)
        StatusRow("最近配置同步", status.lastConfigSync, null)
        StatusRow("当前规则数", status.ruleCount.toString(), null)
    }

    if (!status.hookActive) {
        CardGap()
        SmallTitle(text = "未激活时请检查")
        Card(
            modifier = Modifier.fillMaxWidth(),
            pressFeedbackType = PressFeedbackType.None,
        ) {
            Text(
                text = "1. 在 LSPosed 中启用 HyperHand 模块\n" +
                    "2. 勾选「系统框架(system)」「系统界面」「桌面」作用域\n" +
                    "3. 重启 system_server 或设备\n" +
                    "4. 返回此页查看 Hook 状态",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean?) {
    val longValue = value.length > 10
    BasicComponent(
        title = label,
        summary = if (longValue) value else null,
        endActions = if (!longValue) {
            {
                Text(
                    text = value,
                    style = MiuixTheme.textStyles.body2,
                    color = when (ok) {
                        true -> MiuixTheme.colorScheme.primary
                        false -> MiuixTheme.colorScheme.error
                        null -> MiuixTheme.colorScheme.onSurfaceSecondary
                    },
                )
            }
        } else null,
    )
}

private fun boolText(v: Boolean) = if (v) "是" else "否"
