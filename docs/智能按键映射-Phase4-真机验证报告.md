# HyperHand 智能按键映射 — Phase 4 规则引擎真机验证报告

> 验证日期：2026-11-23 10:19–10:55
> 设备：Xiaomi 14 / houji / 23127PN0CC / Android 16 / HyperOS OS3.0.307 / KernelSU (su uid=0 ksu:s0) / LSPosed 2.2.1 (API 102)
> 阶段目标：把 Phase 3 完成的 UI 规则，真正落到 system_server 的 Hook 规则引擎，实现「按条件动态重映射音量键」。

---

## 0. 结论

**Phase 4 真机验证通过。** 规则从 App 写入 `Settings.Global` → system_server Hook 读取 → 条件求值 → 拦截/透传 → 媒体动作，全链路已在真实设备上跑通并逐项确认。

关键证据（真机 logcat，标签 `HyperHandSK`）：

```
SkConfig loaded ver=5 rules=1 master=true
ENGINE-MATCH matched=测试-无条件
ENGINE volume_up DOWN short(immediate) consume=true action=next fg=com.trim.music playing=true
media: next -> com.trim.music
```

配合飞牛音乐会话元数据从「永不失联的爱 / 单依纯」变为「再见二丁目 / 杨千嬅」，证明**按下的音量加键被真正拦截并切到了下一首**，而不是额外补发媒体键。

---

## 1. 本阶段新增/改动的代码

### Hook 侧（`module/src/main/java/.../smartkey/hook/`，运行在 system_server）

| 文件 | 职责 |
|---|---|
| `SkModel.java` | 规则数据模型 + JSON 解析，与 UI 侧 Kotlin `RuleModel.kt` 字段一一对应；单条非法跳过、整体非法抛错由上层回退；按优先级降序 |
| `SkConfig.java` | 读 `Settings.Global` 规则，400ms 缓存 + 版本号 + CRC32 校验；解析失败保留最后有效快照，全失败空规则（安全透传）；**主开关独立刷新**（见 §4.1） |
| `SkContext.java` | 设备/应用状态探测：前台应用、运行应用集合、媒体会话、屏幕、锁屏、充电；带 250ms TTL；失败置「未知」，求值按不满足处理 |
| `SkEngine.java` | 规则引擎：按压状态机（短按/长按/双击/连按）+ 条件求值（AND/OR，17 种条件）+ 优先级匹配 + 决策 |
| `MediaActions.java` | 媒体动作 next/previous/play/pause/play_pause，system_server 内 `getActiveSessions(null)` 定向控制，异步主线程执行 |
| `KeyHookEntry.java` | 接入引擎：PoC 开关全关时走正式规则引擎；安装成功后写真实探针 |

### App 侧（`glass/.../smartkey/data/RuleRepository.kt`）

- `saveRules()` 递增版本号，把规则 JSON + 版本 + CRC32 + 同步时间写入 `Settings.Global`。
- 新增 `syncNow()`，App 启动时把已有规则推送到 Hook（仅当 Global 无规则时写，避免误改同步时间）。
- 键名与 Hook 侧 `SkConfig` 完全一致：`hyperhand_sk_rules` / `_ver` / `_sum` / `hyperhand_sk_master` / `hyperhand_sk_last_sync`。

### 通信机制

App（普通 UID）写 `Settings.Global` 自定义键 → system_server（UID 1000）读。真机确认这条通道可用（现有功能 `oh_tuner_*` 已依赖它）。校验和用 UTF-8 字节的 CRC32 十进制字符串，App/Kotlin 与 Hook/Java 两侧实现一致，实测写入后重新计算的 checksum 完全匹配。

---

## 2. 验证过程与结果

### 2.1 Hook 注入

- 通过 Root 覆盖安装新 APK；LSPosed 模块启用状态 + 作用域（system / SystemUI / 桌面）保留。
- 软重启 system_server 后日志：
  ```
  KeyHookEntry.install called, uid=1000
  tryLoad[contextCL] OK loader=dalvik.system.PathClassLoader
  HOOKED interceptKeyBeforeQueueing in com.android.server.policy.PhoneWindowManager
  KeyHookEntry installed, hooked=1
  ```
- 与 Phase 2 一致的结论：framework 类必须用 `Thread.currentThread().getContextClassLoader()` 加载。

### 2.2 规则拦截与消费（无条件规则）

- 写入规则：音量加 / 短按 / next，无条件。
- 按音量加：`ENGINE ... consume=true action=next`；系统音量值保持不增、音量条未动 → **真正吞键**，不是补发媒体键。
- PoC 开关保持全关，确认走的是正式引擎而非 PoC 通道。

### 2.3 条件求值

| 场景 | 规则条件 | 前台/状态 | 结果 |
|---|---|---|---|
| 指定应用前台 | `app_foreground=com.luna.music` | 前台为 LSPosed Manager | 不匹配 → 透传（无 consume） |
| 指定应用前台 | 同上 | 启动 com.luna.music 后 | `consume=true fg=com.luna.music` → 匹配拦截 |
| 任意应用播放 | `any_app_playing` | 飞牛音乐 PLAYING(3) | `playing=true` → 匹配拦截 |
| 总开关关闭 | 同上（master=0） | 任意 | `ENGINE-SNAP master=false` → 透传 |

`SCREEN_ON` / `LOCKED` 探测也在引擎日志中实时可见（`screenOn=true` / `locked=...`），状态来源真实。

### 2.4 媒体控制（真实切歌）

- 飞牛音乐 `com.trim.music`（有标准 MediaSession）播放「永不失联的爱」。
- 规则：`any_app_playing` → 音量加短按 → next。
- 按音量加后：
  ```
  ENGINE ... consume=true action=next fg=com.trim.music playing=true
  media: next -> com.trim.music
  ```
  会话元数据变为「再见二丁目 / 杨千嬅」→ **切歌成功**。
- 目标会话严格限定：规则带包名时只控制该包名的 PLAYING 会话，不误控其它应用。

### 2.5 原始行为恢复

- 关闭总开关（master=0）后按键：日志 `SkConfig master changed -> false`、`ENGINE-SNAP master=false`，无 `ENGINE-MATCH`、无 consume → **完全透传**，系统音量行为恢复。
- 测试配置已清理：规则键删除、master=0、PoC 开关恢复默认、媒体音量恢复 5。

---

## 3. 本轮发现并修复的问题

### 3.1 前台应用探测失败（fg=null）

- 现象：`ActivityManagerService.getTasks [int, int]` / ATM 反射在 Android 16 上签名不匹配，前台包名永远拿不到，导致「指定应用前台/运行」类条件永不命中。
- 修复：改用 `getRunningAppProcesses()` 中 `IMPORTANCE_FOREGROUND` 的进程主包作为前台应用，一次调用同时得到运行集合与前台包。
- 验证：`fg=com.luna.music` / `fg=com.trim.music` 正确识别。

### 3.2 总开关关闭后仍拦截（严重）

- 现象：只改 `hyperhand_sk_master=0` 而不改版本号时，`SkConfig` 因版本未变直接返回旧快照（master 仍为 true），导致 App 里关掉总开关后 Hook 侧**可能永远不生效**。
- 修复：把 master 从快照缓存中剥离，每次 400ms 复核时单独读一次 `K_MASTER`；若变化则复用规则列表、只替换 master 生成新快照。
- 验证：`SkConfig master changed -> false` → 立即透传。

### 3.3 Hook 探针写不进去

- 现象：`onModuleLoaded` 时机 `currentApplication()` 尚为 null，探针写失败，状态页会一直显示未激活。
- 修复：延迟重试（Handler postDelayed，最多 12 次 × 500ms）直到 Context 就绪。
- 验证：探针写入 `hyperhand_sk_hook_probe=1795402148536`。

---

## 4. 一次操作失误与恢复（如实记录）

为更新 LSPosed 数据库里的模块 APK 路径，我执行了 `kill -9 lspd`，导致 LSPosed Manager 一度显示「未安装」。**这是我的操作失误**。

- 恢复方式：整机 reboot，LSPosed daemon 从磁盘重载配置。
- 恢复结果：Manager 显示「已激活 2.2.1 (7912) / API 102」，Dex 优化器包装「支持（已挂载）」、Xposed API 调用保护「已启用」，与操作前一致，无残留损坏。
- 后续不再手动杀 lspd；覆盖安装（不卸载）可保留 LSPosed 模块启用状态与作用域，且 LSPosed 会按包名加载当前安装路径的新 DEX。

---

## 5. 已知限制 / 待办

1. **无 MediaSession 的应用**：酷狗概念版 `com.luna.music` 播放时不建 MediaSession（用老式 receiver），本引擎的媒体动作会跳过并打日志 `no playing target session, skip`，不会误控其它应用。这是 Phase 2 已发现的兼容性问题，正式版若要支持需单独设计（例如 Hook 其 receiver 或改用音频焦点判断），本阶段按需求「不伪造、不误控」原则如实跳过。
2. **按压方式状态机**：短按/长按/双击/连按已实现，本轮真机重点验证了短按；长按/双击/连按的逐项真机调参留待 Phase 6 整体回归。
3. **诊断日志已清理**：排障期间临时加入的 `ENGINE-IN` / `ENGINE-SNAP` / `ENGINE-MATCH` 日志已从源码移除，正式日志保留 `ENGINE ...`（节流 120ms）。

---

## 6. 交付物

- 源码：`smartkey/hook/{SkModel,SkConfig,SkContext,SkEngine,MediaActions,KeyHookEntry}.java`；`RuleRepository.kt` 同步逻辑。
- 编译产物：`module/build/outputs/apk/debug/module-debug.apk`（BUILD SUCCESSFUL）。
- 本报告：`docs/智能按键映射-Phase4-真机验证报告.md`。

下一步：Phase 5 正式集成收尾 / Phase 6 全功能回归（含息屏、锁屏、长短按、双击、连按与多应用组合）。
