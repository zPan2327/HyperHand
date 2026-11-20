// 复制自 SonyPods: app/src/main/java/dev/sonypods/ui/dialogs/ 下的纯 UI 对话框。
// 唯一改动：stringResource(R.string.xxx) → S.xxx（中文替换表），去掉 com.mercury.sonypods.R 依赖。
// Composable 结构、组件、参数顺序与 SonyPods 原版逐字一致。
package io.github.zpan2327.hyperhand.glass.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ---------------- PowerOffDialog.kt ----------------
@Composable
fun PowerOffDialog(
    show: Boolean,
    deviceName: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    val effectiveName = if (deviceName.isBlank()) S.pod_info else deviceName
    OverlayDialog(
        title = S.power_off_title,
        summary = String.format(S.power_off_summary, effectiveName),
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = S.power_off_warning,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = S.cancel,
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = S.power_off_confirm,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

// ---------------- SafeListeningRootHelpDialog.kt ----------------
@Composable
fun SafeListeningRootHelpDialog(show: Boolean, onDismiss: () -> Unit) {
    OverlayDialog(
        title = S.sl_root_help_title,
        summary = S.sl_root_help_summary,
        show = show,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(S.sl_root_help_owner)
            Text(S.sl_root_help_risk)
            Text(S.sl_root_help_without)
        }
        TextButton(
            text = S.close,
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.textButtonColorsPrimary(),
        )
    }
}

// ---------------- LdacEnableConfirmDialog.kt ----------------
@Composable
fun LdacEnableConfirmDialog(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    OverlayDialog(
        title = S.ldac_enable_confirm_title,
        summary = S.ldac_enable_confirm_body,
        show = true,
        onDismissRequest = onCancel,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = S.cancel,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = S.confirm,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

// ---------------- ConnectionQualityConfirmDialog.kt ----------------
// 原版参数是 ConnectionQualityMode 枚举；这里改成 soundQualityPrior: Boolean，行为不变。
@Composable
fun ConnectionQualityConfirmDialog(
    soundQualityPrior: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    OverlayDialog(
        title = S.connection_quality_change_title,
        summary = if (soundQualityPrior) {
            S.connection_quality_confirm_sound_body
        } else {
            S.connection_quality_confirm_connection_body
        },
        show = true,
        onDismissRequest = onCancel,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = S.cancel,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = S.confirm,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

// ---------------- MultipointAlertDialog.kt ----------------
// 原版 messageType == ALERT_MESSAGE_TYPE_MULTIPOINT_LDAC_DISABLE 决定文案；改为 ldacDisable: Boolean。
@Composable
fun MultipointAlertDialog(
    show: Boolean,
    ldacDisable: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    OverlayDialog(
        title = S.mp_reconnect_required_title,
        summary = if (ldacDisable) S.mp_ldac_off_body else S.mp_auto_reconnect_body,
        show = show,
        onDismissRequest = onCancel,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = S.cancel,
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = S.confirm,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

// ---------------- AncCycleModesDialog.kt ----------------
@Composable
fun AncCycleModesDialog(
    show: Boolean,
    selected: Set<String>,
    onDismissRequest: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val options = listOf(
        "NOISE_CANCELLING" to S.anc_cycle_mode_noise_cancelling,
        "AMBIENT_SOUND" to S.anc_cycle_mode_ambient,
        "OFF" to S.anc_cycle_mode_off,
    )
    var selection by remember(show) { mutableStateOf(selected) }

    OverlayDialog(
        title = S.anc_cycle_modes_title,
        summary = S.anc_cycle_modes_dialog_summary,
        show = show,
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            options.forEach { (value, label) ->
                val checked = value in selection
                AncCycleModeRow(
                    label = label,
                    checked = checked,
                    onClick = {
                        selection = when {
                            !checked -> selection + value
                            selection.size > 1 -> selection - value
                            else -> selection
                        }
                    },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                text = S.cancel,
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = S.confirm,
                onClick = { onConfirm(selection) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun AncCycleModeRow(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.headline1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Checkbox(
            state = ToggleableState(checked),
            onClick = onClick,
        )
    }
}
