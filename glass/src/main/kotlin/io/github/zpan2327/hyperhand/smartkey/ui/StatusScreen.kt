package io.github.zpan2327.hyperhand.smartkey.ui

import io.github.zpan2327.hyperhand.glass.R
import androidx.compose.ui.res.stringResource
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
    SmallTitle(text = stringResource(R.string.sk_104))
    Card(
        modifier = Modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        StatusRow("system_server Hook", if (status.hookActive) stringResource(R.string.sk_105) else stringResource(R.string.sk_106), status.hookActive)
        StatusRow(stringResource(R.string.sk_107), boolText(status.lsposedInstalled), status.lsposedInstalled)
        StatusRow(stringResource(R.string.sk_108), boolText(status.moduleInstalled), status.moduleInstalled)
        StatusRow(stringResource(R.string.sk_109), status.lastHookReport, null)
        StatusRow(stringResource(R.string.sk_110), status.lastConfigSync, null)
        StatusRow(stringResource(R.string.sk_111), status.ruleCount.toString(), null)
    }

    if (!status.hookActive) {
        CardGap()
        SmallTitle(text = stringResource(R.string.sk_112))
        Card(
            modifier = Modifier.fillMaxWidth(),
            pressFeedbackType = PressFeedbackType.None,
        ) {
            Text(
                text = stringResource(R.string.sk_113) +
                    stringResource(R.string.sk_114) +
                    stringResource(R.string.sk_115) +
                    stringResource(R.string.sk_116),
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

@Composable
private fun boolText(v: Boolean) = if (v) stringResource(R.string.sk_117) else stringResource(R.string.sk_118)
