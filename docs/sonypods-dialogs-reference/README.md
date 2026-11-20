# SonyPods 对话框源码复制说明

本目录记录把 SonyPods（Mercury000/SonyPods，miuix 0.9.4-rc01）对话框搬到 HyperHand 的处理结果。

## 已复制并编译进 APK

源码在 `glass/src/main/kotlin/io/github/zpan2327/hyperhand/glass/dialogs/`，Composable 与
SonyPods 原版逐字一致，只把 `stringResource(R.string.xxx)` 换成中文常量表 `DialogStrings.kt`：

| 文件 | 原版 | 中文替换 / 适配 |
|---|---|---|
| `RestartScopeDialog.kt` | 重启作用域 | 标题/说明/按钮中文；作用域改为系统界面 + 小米桌面；实际在用（概览页右上角） |
| `SimpleDialogs.kt` | PowerOff / SafeListeningRootHelp / LdacEnableConfirm / ConnectionQualityConfirm / MultipointAlert / AncCycleModes | 文案中文；两个枚举参数改为 Boolean（行为不变） |
| `IslandDurationDialog.kt` | 时长选择 | 单位秒/分/时中文；`MAX_ISLAND_DURATION_SECONDS` 常量内联 |
| `SonyPodsDialogHost.kt` | —— | 桥接层（非 SonyPods 原版）：`AbstractComposeView` + 透明 Scaffold 提供 `MiuixPopupHost`，让 OverlayDialog 在 Java Activity 里渲染 |

启动入口：`MainActivity.showRestartDialog()` 通过 `SonyPodsDialogHost` 显示原版
`RestartScopeDialog`，不再使用手写的 `MiuixDialog` + `MiuixCheckbox` 行。

## 仅留档、未编译进 APK

本目录下 4 个文件保持 SonyPods 原文，**不参与构建**（在 `docs/` 下，非源码目录）：

- `LeAudioAlertDialog.kt` / `LeAudioPairingHelpDialog.kt`：依赖 LE Audio 配对流程的
  40+ 条设备文案与配对状态机（`pairStage` / `identityAddress` 等）。
- `AppPickerDialog.kt`：运行时加载已安装应用列表（`loadLaunchableApps` / `InstalledApp`）。
- `PodImageConfigDialog.kt`：依赖 Xposed 服务与图片同步桥接（`SonyBridge` / `ModelImageSync` /
  `PodImageLoader`）。

这些都是 SonyPods 耳机控制业务的专属弹窗，HyperHand（单手模式模块）没有对应功能与
运行时服务；编译进 APK 只会增加死代码和体积，因此保留源码供参考、不接入构建。

如将来要给 HyperHand 增加对应业务，把这 4 个文件移入 `glass/.../dialogs/` 并补上文案
中文替换即可，组件结构无需改动。
