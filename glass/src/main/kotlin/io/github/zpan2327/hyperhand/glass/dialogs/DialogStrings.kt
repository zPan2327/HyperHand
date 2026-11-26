package io.github.zpan2327.hyperhand.glass.dialogs

/**
 * SonyPods 对话框文案的中文替换表。
 *
 * 复制过来的 Composable 原本用 stringResource(R.string.xxx)，那些字符串资源属于
 * SonyPods（com.mercury.sonypods），搬进 HyperHand 会解析不到。这里把每条原文案
 * 翻成中文并集中放一处，复制的对话框只改「引用这里」一处即可——其余代码与
 * SonyPods 原版逐字一致。
 */
internal object S {
    const val cancel = "取消"
    const val confirm = "确定"
    const val close = "关闭"
    const val got_it = "知道了"

    // InfoDialogHost（引用 / 使用说明 / 许可 / 运行状态）

    // RestartScopeDialog
    const val restart_scope_title = "重启作用域"
    const val restart_scope_summary = "选择要用 root 重启的进程。勾选「系统框架」会软重启整个系统，约 20 秒后自动恢复。"

    // PowerOffDialog
    const val power_off_title = "关闭耳机电源？"
    const val power_off_summary = "向 %1\$s 发送 Sony 关机命令。"
    const val power_off_warning = "耳机会断开连接，重新连接前需要手动开机。"
    const val power_off_confirm = "关机"
    const val pod_info = "耳机"

    // SafeListeningRootHelpDialog
    const val sl_root_help_title = "为什么需要 Root"
    const val sl_root_help_summary = "声压读数需要 root 权限"
    const val sl_root_help_owner = "测量开关归 Sound Connect 所有，无法通过耳机查询其状态。"
    const val sl_root_help_risk = "只有拿到 root 才能读取该开关当前值。"
    const val sl_root_help_without = "没有 root 时，这一项会显示为不可用。"

    // LdacEnableConfirmDialog
    const val ldac_enable_confirm_title = "开启 LDAC？"
    const val ldac_enable_confirm_body = "LDAC 提高码率带来更好音质。在信号拥挤的环境里可能出现卡顿或断连，如遇到可再关闭。"

    // ConnectionQualityConfirmDialog
    const val connection_quality_change_title = "更改蓝牙连接质量"
    const val connection_quality_confirm_sound_body = "将重新连接音频设备。如果切换到音质优先，声音质量设置可能变为不可用。仍要更改？"
    const val connection_quality_confirm_connection_body = "将重新连接音频设备。仍要更改？"

    // MultipointAlertDialog
    const val mp_reconnect_required_title = "需要重新连接"
    const val mp_ldac_off_body = "音频设备将重新连接。此设置开启时 LDAC 将不可用（即使在音质优先模式下）。仍要重新连接？"
    const val mp_auto_reconnect_body = "音频设备将重新连接。仍要继续？"

    // AncCycleModesDialog
    const val anc_cycle_modes_title = "降噪键循环"
    const val anc_cycle_modes_summary = "选择通知栏「循环降噪」按钮依次切换的模式"
    const val anc_cycle_modes_dialog_summary = "选择要循环的模式，至少要保留一项"
    const val anc_cycle_mode_noise_cancelling = "降噪"
    const val anc_cycle_mode_ambient = "环境音"
    const val anc_cycle_mode_off = "关闭"

    // IslandDurationDialog
    const val island_duration_title = "灵动岛时长"
    const val island_duration_summary = "电量信息在屏幕上停留的时间"
    const val duration_unit_second = "秒"
    const val duration_unit_minute = "分"
    const val duration_unit_hour = "时"

    // LeAudioAlertDialog / LeAudioPairingHelpDialog / LeAudio 系列
    const val lea_confirm_repair_le = "重新配对以将耳机连接切换为 [LE Audio 优先]。耳机也会重新连接。是否继续？"
    const val lea_confirm_standby_le = "将耳机待机模式改为 [LE Audio 优先]？是否继续？"
    const val lea_confirm_pairing_le = "进入配对模式并将耳机连接切换为 [LE Audio 优先]。是否继续？"
    const val lea_confirm_le = "将耳机连接改为 [LE Audio 优先]。是否继续？"
    const val lea_confirm_standby_classic = "将耳机待机模式改为 [仅经典音频]。是否继续？"
    const val lea_confirm_pairing_classic = "进入配对模式并将耳机连接切换为 [仅经典音频]。是否继续？"
    const val lea_confirm_switch_sound = "从 LE Audio 切换到经典音频的音质优先模式。是否继续？"
    const val lea_confirm_switch_connection = "从 LE Audio 切换到经典音频的连接优先模式。是否继续？"
    const val lea_confirm_classic_sound = "将经典音频改为音质优先模式。是否继续？"
    const val lea_confirm_classic_connection = "将经典音频改为连接优先模式。是否继续？"
    const val lea_confirm_classic_reconnect = "将耳机连接改为 [仅经典音频]。耳机也会重新连接。是否继续？"
    const val feature_unavailable_over_le_audio = "使用 LE Audio 连接时此功能不可用"
}
