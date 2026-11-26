# HyperHand 智能按键映射（Smart Key Mapping）— Phase 1 项目审查与技术方案

> 审查对象：`zPan2327/HyperHand` @ commit `0d92a55`（main 分支）
> 审查阶段：Phase 1（项目审查）— 按需求"开发阶段与执行顺序"第一步执行
> 本地环境：JDK 17.0.19 / Android SDK（platforms 31、35、36、37.0；build-tools 31/34/36/37）/ Gradle 9.7.1
>
> **重要声明**：本报告不含任何"已验证"的系统级 Hook 结论。Phase 2 真机 PoC 尚未执行。
> 文中所有 Hook 点均为基于 AOSP 公开资料与项目现有代码分析的**候选方案**，
> 必须在目标设备（Xiaomi 14 / HyperOS 3 / Android 16 / KernelSU + LSPosed）上实测确认后方可标记为可用。

---

## 0. 执行摘要

1. **项目是成熟的单 APK LSPosed 模块**：一个 APK 同时充当"设置应用"和"LSPosed 模块"，使用 libxposed 现代 API 102，静态作用域锁定在 `com.android.systemui` + `com.miui.home`。现有功能为单手模式触发增强与快捷菜单 iOS 式滑选，代码量约 1.9 万行，稳定性设计完善（PROTECTIVE 异常模式、心跳探测、配置缓存、失败透传）。
2. **需求文档与项目现状存在 4 处重大差距**（详见第 3 节）：UI 技术栈、组件形态、Hook 作用域、PoC 验证条件。这些差距不是实现细节，而是需要在编码前拍板的架构决策。
3. **系统级音量键拦截的可行方向**：`com.android.server.policy.PhoneWindowManager#interceptKeyBeforeQueueing(KeyEvent, int)` 运行于 `system_server`，是 AOSP 中音量键默认调节的分支点，返回 `0` 可消费事件、返回 `ACTION_PASS_TO_USER` 可透传。**此点未经验证**，Android 16 / HyperOS 3 的方法签名与调用时序需实测。
4. **强烈建议先做 PoC 再写 UI**：这与需求"不允许跳过 PoC、不允许伪造功能"的最高原则一致。Phase 2 需要真机，我可以在本环境交付 PoC 代码与验证清单，但真机执行必须由你在目标设备上完成。

---

## 1. 项目现状审查（逐项对照需求 Phase 1 检查清单）

| 检查项 | 结论 | 证据 |
|---|---|---|
| 整体架构 | 单 APK 双角色（应用 + LSPosed 模块），2 个 Gradle 子模块 | `settings.gradle.kts`、`AndroidManifest.xml` |
| 模块划分 | `:module`（Java 业务 + Hook，application）+ `:glass`（Kotlin/Compose 液态玻璃底栏与 Miuix 对话框宿主，library） | `module/build.gradle.kts`、`glass/build.gradle.kts` |
| Gradle / 插件版本 | Gradle 9.7.1、AGP 9.4.1、Kotlin 2.4.21、Compose BOM 2026.09.00 | `gradle-wrapper.properties`、`settings.gradle.kts` |
| Kotlin / Compose | **部分使用**：仅 `:glass` 是 Kotlin + Compose；主应用与 Hook 全是 Java | `:glass/**.kt` 共 12 个文件；`module/**.java` 共 30+ 个文件 |
| Miuix 集成 | **已集成真组件**：`top.yukonga.miuix.kmp` 0.9.4-rc01（ui / preference / blur / icons），但主要页面是 Java 原生 View 手绘的"Miuix 风格"，只有底栏和对话框是真正的 Miuix Compose 组件 | `glass/build.gradle.kts`、`docs/miuix-components.md`、`docs/UI与交互规范.md` |
| 导航与主题 | `MainActivity extends ComponentActivity`（为承载 Compose View），三页横滑（概览/设置/关于）+ 液态玻璃底栏 + 折叠顶栏；主题走 `Theme.Colors`，深浅色跟随系统 | `MainActivity.java`、`PagePager.java`、`TopBar.java`、`Theme.java` |
| 现有 LSPosed 实现 | libxposed API 102；入口 `java_init.list` → `HyperHand extends XposedModule`；`onModuleLoaded` / `onPackageLoaded` | `META-INF/xposed/*`、`HyperHand.java:66` |
| Hook 封装 | `Hooks.java` 薄封装 API 102（findClass / hookMethods / hookMethodExact / hookConstructors / hook / call / currentApplication），统一 `ExceptionMode.PROTECTIVE` | `Hooks.java` |
| 权限与 IPC | **无 Android 权限声明**（仅 VIBRATE）；配置存 `Settings.Global`（`oh_tuner_` 前缀 40+ 键）；广播 `ACTION_SET` + `RECEIVER_EXPORTED` 通知作用域进程；`probe` 心跳机制判断真实激活状态 | `AndroidManifest.xml`、`Cfg.java`、`HyperHand.java:1101` |
| 可复用的系统 Hook 代码 | 有：`onInputEvent` 旁路监听、`onTransact` 吞事件、`View#setBackgroundDrawable` 拦截、`canInterceptTouch` 返回值改写等——**均为 SystemUI/Launcher 进程，非 system_server** | `HyperHand.java:168-468`、`1200-1341` |
| 数据持久化方案 | 仅 `Settings.Global` 扁平 key-value + 800ms 内存缓存，**没有 Room / DataStore / 数据库**，没有复杂结构存储能力 | `Cfg.java:326-429` |
| 规则引擎 / 条件判断 | 无现成规则引擎；单手模式手势判定在 `Recognizer.java`（采样 + 分窗速度 + 双路径评分），可借鉴其"热路径无磁盘 IO"设计 | `Recognizer.java` |
| 按键拦截相关代码 | **完全没有**：全项目无 `KeyEvent` 按键 Hook、无 MediaSession、无音量键处理 | 全项目 grep 无相关实现 |

---

## 2. 现有架构中值得复用的部分

这些是增量开发的资产，应保持不动：

- **`Hooks.java`**：libxposed API 102 兼容层，直接可用于 Hook system_server 的方法。
- **配置广播 + 心跳探测**：`probe` 广播 → hook 进程写回心跳 → UI 判断真实状态（而非只看安装状态），正好满足需求第 8 节"状态必须来源于实际检测"的要求，可平移到 system_server。
- **`Cfg` 的缓存与安全回退**：800ms 缓存、读失败回默认值、clamp 越界——热路径不读磁盘的设计思路适用于按键规则缓存。
- **`glass` 模块的 Miuix 真组件**：`StatusCardHost`、`MiuixDropdownHost`、`MiuixNumberPickerHost`、`SonyPodsDialogHost` 等已是真 Miuix Compose，新页面可复用同一套对话框/选择器宿主。
- **`ExceptionMode.PROTECTIVE`**：Hook 回调异常不会击穿系统，符合"不牺牲系统稳定性"。

---

## 3. 关键差距与决策点（必须在编码前拍板）

### 差距 A：UI 技术栈与组件库

- **需求**：Kotlin + Jetpack Compose，使用 Miuix 官方组件，不使用普通 Compose 控件模拟外观。
- **现状**：主应用（30+ 个 Java 类、约 1.9 万行）是**原生 View 手绘**，视觉规范 1:1 对齐 Miuix，但并非真正的 Miuix Composable；只有 `:glass` 用 Kotlin/Compose + 真 Miuix。需求同时要求"不要过度重构、基于现有代码增量开发"。
- **矛盾**：若严格按"完整 Miuix UI"重写全部页面 = 全量重构，违反"不要过度重构"；若沿用原生 View 手绘 = 不满足"使用真实 Miuix 组件"。
- **推荐方案**：**新增页面用真 Miuix Compose**（`:glass` 里已有的 Miuix 依赖可直接复用），通过 `AbstractComposeView` 桥接进现有 `MainActivity` 的页面容器，与现有三页并存。这样既不重写旧代码，又让新模块成为"完整使用 Miuix 组件"的独立功能区。

### 差距 B：组件形态

- **需求**：APK（UI/规则管理）+ LSPosed 模块（Hook/拦截/条件判断）两个组件，建立"可靠的配置通信机制"，且强调跨 UID、跨进程。
- **现状**：单一 APK 双角色。LSPosed 模块与设置应用同包同 UID，Hook 类名完全相同。
- **分析**：需求列出的约束（跨 UID、Hook 不依赖主应用常驻、本地缓存、权限校验）**在单 APK 方案下依然要全部满足**——因为 Hook 运行在 `system_server`（UID 1000），UI 运行在应用 UID，两者天然跨进程跨 UID。拆成两个 APK 反而增加签名一致性与分发复杂度，且不带来额外收益。
- **推荐方案**：**保持单 APK**，但把 Hook 逻辑独立成新的入口类并加入 system_server 作用域；跨进程通信按需求重新设计（见第 5 节）。

### 差距 C：Hook 作用域（最关键）

- **需求**：Hook `PhoneWindowManager` / `InputDispatcher` 等 **system_server** 内类。
- **现状**：`module.prop` 声明 `staticScope=true`，`scope.list` 只有 `com.android.systemui` 与 `com.miui.home`。system_server 不在作用域内，无法注入。
- **影响**：必须修改 `scope.list` 加入 system_server 的框架包名（LSPosed 中通常为 `android` / `system_server`，**需在真机确认实际取值**）。由于 `staticScope=true` 会让模块强制覆盖所有用户的作用域选择，加入 system_server 意味着所有安装者都会被注入 system_server——风险显著上升。
- **推荐方案**：新增 system_server 作用域，但将其与现有功能解耦：Hook 入口内对所有新增 Hook 做"配置总开关"判定，关闭时零副作用透传；并考虑是否把 `staticScope` 改为 false（让用户自行勾选），这会影响现有产品行为，需要你决策。

### 差距 D：PoC 验证条件（硬约束）

- **需求**：Phase 2 必须在目标设备上验证 6 项（按键捕获、原始拦截、媒体控制、息屏、锁屏、原始行为恢复）。
- **当前环境**：Windows 开发环境，无 Xiaomi 14 真机，无 Root/LSPosed 运行环境。
- **我能交付**：可运行的 PoC 模块源码 + 详细真机验证清单 + 日志抓取方法 + 失败排查路径。
- **我不能交付**：任何"已在真机验证成功"的结论。按需求"不允许将理论上可能实现的内容描述为已经验证成功"，我不会写这样的报告。
- **建议**：先由我完成 PoC 代码，你在真机按清单逐项验证并回传日志；确认 Hook 点与时序后再进入 Phase 3 UI 开发。

---

## 4. 系统级按键拦截技术分析（候选方案，未验证）

### 4.1 按键分发链路（AOSP 通用）

```
物理按键 → InputReader → InputDispatcher
   → 【system_server】PhoneWindowManager.interceptKeyBeforeQueueing()  ← 音量键默认调节在此分支
   → 【system_server】PhoneWindowManager.interceptKeyBeforeDispatching()
   → 窗口 / Activity / View.onKeyDown()
```

### 4.2 候选 Hook 点

| 候选方法 | 所在进程 | 能力 | 风险 / 待验证 |
|---|---|---|---|
| `PhoneWindowManager#interceptKeyBeforeQueueing(KeyEvent, int)` → int | system_server | 入队前拦截；返回 0 消费事件，返回 `ACTION_PASS_TO_USER`(1) 透传；音量键默认调节分支在此 | **首选**。需验证 Android 16 签名、HyperOS 覆写、息屏唤醒时序 |
| `PhoneWindowManager#interceptKeyBeforeDispatching(WindowState, KeyEvent, int)` → long | system_server | 派发前拦截，适合组合键 | 返回 -1 才是透传；音量键可能已被上一点处理 |
| `PhoneWindowManager#dispatchUnhandledKey` | system_server | 处理未消费的媒体键 | 音量为 handled key，可能不走这里 |

- **媒体控制在 system_server 内的优势**：system_server 持有 `MEDIA_CONTENT_CONTROL` 等系统权限，可直接用 `MediaSessionManager#getActiveSessions(null)` + `MediaController#getTransportControls()` 控制目标应用，绕过普通应用所需的 Notification Listener / 签名权限限制。这是 system_server Hook 相比普通应用的核心价值。
- **禁止方案**：Hook 普通 Activity 的 `onKeyDown()`（不满足系统级）、监听按键后额外发媒体事件（需求明确禁止的"伪拦截"）。

### 4.3 息屏 / 锁屏的特别注意点

- 息屏时按键会走 `interceptKeyBeforeQueueing` 的 wakefulness 分支，可能与"是否唤醒设备"逻辑交织；音量键在息屏下是否唤醒取决于 ROM 配置。
- 屏幕状态（Display power）与锁屏状态（Keyguard）是两条独立状态线，AOSP 中分别由 `DisplayPowerController` / `KeyguardManager` 管理。PoC 必须分别验证"亮屏未解锁""息屏锁定""亮屏已解锁"三种组合。
- **上述均为理论分析**，实际路径需通过真机 logcat + Hook 日志确认。

---

## 5. APK ↔ Hook 层 IPC 方案分析

需求 8 条约束逐条对应设计：

| 需求约束 | 设计方案 |
|---|---|
| 跨 UID / 跨进程 | UI 进程 vs system_server，天然跨进程；用 Binder/ContentProvider 或 Settings 中转 |
| 规则更新后通知刷新 | UI 写配置后发一次广播 / Provider notify；Hook 层收到后全量重建内存缓存 |
| Hook 不依赖主应用常驻 | 配置落到系统可读的持久存储（如 `Settings.Global` JSON 或 `/data/system/...`），Hook 启动/收到广播时自行读取 |
| 主应用关闭/重启后仍正常 | 同上，内存缓存 + 持久存储双保险 |
| Hook 层本地缓存 | 进程内不可变快照（copy-on-write 原子替换），按键热路径只读内存 |
| 不允许每次按键 IPC | 按键回调零 IPC，只读内存快照 |
| 通信失败 / 版本不一致 / 配置损坏 | 带版本号 + 校验和；解析失败保留"最后一次有效配置"，全失败则安全透传 |
| 权限校验 | Provider 侧用 `Binder.getCallingUid()` 只放行自身 UID 与 system_server；广播用 signature 级自定义权限（参考已落地的同类模块做法） |

- 规则结构（多规则 / 多条件 / 多按键映射）建议序列化为 JSON（Gson/Moshi），存单个 `Settings.Global` 键或系统可读文件，附带 version 与 checksum。
- 现有 `Cfg` 的 `Settings.Global` + 广播机制可作为**简化版起点**，但需补充权限校验与结构化数据支持。

---

## 6. 增量开发方案（建议）

在 `:module` 中新增独立包 `io.github.zpan2327.hyperhand.smartkey`，不改动现有类：

```
smartkey/
  model/      Rule, Condition, KeyBinding, Action, DeviceState, MediaState
  store/      RuleRepository（持久化 + 版本 + checksum）
  ipc/        ConfigProvider / ConfigSync（跨进程同步 + 调用方校验）
  engine/     RuleEngine, ConditionEvaluator, RuleConflictDetector
  hook/       KeyHookEntry（system_server 入口）, KeyEventInterceptor, MediaActionExecutor, DeviceStateProvider
  ui/         SmartKeyHome, RuleEditor, ConditionPicker, AppPicker, StatusPage（真 Miuix Compose）
```

- `HyperHand.java` 仅增加：在 `onPackageLoaded` 中识别 system_server 包名并委派给 `KeyHookEntry.install(...)`；其余现有 Hook 不动。
- `scope.list` 增加 system_server 包名；`MainActivity` 增加进入新功能区的入口（不改动现有三页逻辑）。
- 数据持久化新增独立的 `Settings.Global` 键空间（如 `hyperhand_sk_` 前缀），与现有 `oh_tuner_` 隔离，避免相互污染。

---

## 7. Phase 2 PoC 计划（需真机）

### 7.1 PoC 代码交付范围（我可在当前环境完成）

- 最小化 LSPosed 模块：只 Hook `interceptKeyBeforeQueueing`，打印音量键 DOWN/UP/重复事件的完整参数。
- 三个开关（走 `Settings.Global`）：① 是否消费音量加；② 是否消费音量减；③ 是否执行媒体下一首。
- 详细 logcat 标签与抓取命令。

### 7.2 真机验证清单（需你在 Xiaomi 14 执行）

| # | 测试 | 观察点 | 判定 |
|---|---|---|---|
| 1 | 按键捕获 | logcat 是否出现 KEYCODE_VOLUME_UP/DOWN 的 DOWN/UP/重复 | Hook 点是否有效 |
| 2 | 原始拦截 | 开启消费开关后，系统音量条是否不再变化 | 能否真正吞掉事件 |
| 3 | 媒体控制 | 开启媒体开关后，是否切到下一首 | MediaSession 控制是否成功 |
| 4 | 息屏 | 息屏后重复 1-3 | 息屏路径是否一致 |
| 5 | 锁屏 | 亮屏未解锁重复 1-3 | 锁屏路径是否一致 |
| 6 | 原始行为恢复 | 关闭所有开关后音量键完全恢复 | 无误吞、无卡键 |

### 7.3 必须输出的实际信息（用于确认 Hook 点）

- 实际 Hook 类名与方法签名（含参数类型）。
- Hook 所在进程（确认 system_server）。
- 不同屏幕状态下的成功/失败结果与 logcat 片段。
- 失败场景与原因（如方法签名不符、事件未到达、系统提前消费）。

---

## 8. 风险与限制

1. **无真机**：无法执行 Phase 2，无法确认 Android 16 / HyperOS 3 的实际 Hook 点、签名与时序。这是当前最大阻塞。
2. **作用域风险**：加入 system_server 作用域会使模块影响系统核心进程；任何 Hook 异常都可能影响系统按键。必须保留 PROTECTIVE 模式与"失败透传"。
3. **架构决策未定**：第 3 节的 A/B/C 三项需拍板后才能进入编码，否则可能大量返工。
4. **坑点预警（来自同类模块经验）**：Android 16 上 ART 积极内联可能使方法无法 Hook（LSPosed 新分支通过 dex2oat 参数处理）；媒体会话多应用并存时的选择策略需实测；息屏按键可能被系统提前唤醒/消费。

---

## 9. 下一步

1. 请对第 3 节的 A/B/C 三项架构决策与第 7 节的 PoC 执行方式做出选择。
2. 确认后，我将交付 Phase 2 的 PoC 源码 + 真机验证清单。
3. 真机验证通过后再进入 Phase 3（Miuix UI）及后续阶段，严格按需求顺序推进。
