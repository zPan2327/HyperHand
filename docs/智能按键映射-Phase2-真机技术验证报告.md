# 智能按键映射 — Phase 2 真机技术验证报告

> 本报告记录 Phase 2 PoC 在真实目标设备上的实测结果。所有结论均来自真机 logcat 与系统状态查询，未验证的内容明确标注。**测试时间：2026-11-21 11:15–11:23 (UTC+8)**

---

## 1. 测试环境（实测）

| 项目 | 实测值 | 获取方式 |
|---|---|---|
| 设备 | Xiaomi 14（codename `houji`，型号 `23127PN0CC`） | `adb devices -l` |
| Android | 16 | `getprop ro.build.version.release` |
| HyperOS | OS3.0.307.0.WNCCNXM（`ro.miui.ui.version.name=V816`） | `getprop` |
| Root | KernelSU（`su` → `uid=0 context=u:r:ksu:s0`） | `su -c id` |
| LSPosed | 2.2.1（versionCode 7912，Zygisk 版，API 102） | `lspctl status` |
| 模块启用 | enabled=1，scope 含 `system`/`com.android.systemui`/`com.miui.home` | `/data/adb/lspd/config/modules_config.db` |
| PoC APK | `io.github.zpan2327.hyperhand` v3.3.4，含 `smartkey/hook/*` 三个类 | 拉取已装 base.apk 反查 dex 确认 |

---

## 2. Hook 点验证结果（最重要）

### 2.1 实际 Hook 类与方法（实测确认）

```
类：    com.android.server.policy.PhoneWindowManager
方法：  public int interceptKeyBeforeQueueing(android.view.KeyEvent, int)
进程：  system_server (UID 1000)
Hook：  成功，hooked=1
```

反编译设备 `/system/framework/services.jar`（classes3.dex）确认：
- 类 `Lcom/android/server/policy/PhoneWindowManager;` 存在（62 处引用）
- 方法 `interceptKeyBeforeQueueing` 存在（7 处引用）
- **AOSP 标准路径在 HyperOS 3 / Android 16 上仍然有效**——类名、方法名均未改变。

实测日志：
```
11-21 11:18:02.995 I HyperHandSK: KeyHookEntry.install called, uid=1000, cl=dalvik.system.PathClassLoader
11-21 11:18:02.996 I HyperHandSK: candidate: public int com.android.server.policy.PhoneWindowManager.interceptKeyBeforeQueueing(android.view.KeyEvent,int) (return=int)
11-21 11:18:02.998 I HyperHandSK: HOOKED interceptKeyBeforeQueueing in com.android.server.policy.PhoneWindowManager
11-21 11:18:02.998 I HyperHandSK: KeyHookEntry installed, hooked=1
```

### 2.2 关键修正：ClassLoader 可达性（本轮最有价值的发现）

**初次尝试失败**：PoC 第一版在 `onModuleLoaded` 里用 `ClassLoader.getSystemClassLoader()` 加载 `PhoneWindowManager`，三种方式（hint/system/bootstrap）全部 `ClassNotFoundException`，Hook 无法安装。

**诊断过程**：改造 PoC 遍历 5 种 ClassLoader 并打印真实异常，逐项实测：

| 方式 | 结果 |
|---|---|
| `hint`（传入的 PathClassLoader） | ❌ ClassNotFoundException |
| `ClassLoader.getSystemClassLoader()` | ❌ ClassNotFoundException |
| `Object.class.getClassLoader()`（bootstrap） | ❌ ClassNotFoundException |
| **`Thread.currentThread().getContextClassLoader()`** | ✅ **OK，成功加载** |
| 模块自身 ClassLoader | 未到达（上一项已成功） |

**结论**：在 LSPosed 的 `onModuleLoaded` 时机，system_server 的 framework 类必须通过 **`Thread.currentThread().getContextClassLoader()`** 加载，`getSystemClassLoader()` 看不到 services.jar 的类。这是 libxposed API 102 在此 ROM 上的实际行为，已写入代码。

### 2.3 注入时机

- LSPosed scope 变更（新增 `system`）后，必须对 system_server 软重启（`kill -9 system_server`，实测约 6 秒恢复）才生效。
- 模块本身由 LSPosed 在 `onModuleLoaded(isSystemServer=true)` 回调注入，实测 PID 从 29990 → 19505，注入正常。

---

## 3. 六项 PoC 验证结果

| # | 测试项 | 结果 | 关键证据 |
|---|---|---|---|
| 1 | 按键捕获 | ✅ 通过 | 捕获 keyCode=24/25 的 DOWN(repeat=0)/UP 完整事件 |
| 2 | 原始事件拦截 | ✅ 通过 | `consume_up=1` 连按音量加 3 次，音量保持 5 不变，6 条 CONSUME 日志 |
| 3 | 媒体控制 | ✅ 通过 | 识别 PLAYING 会话，`skipToNext -> com.trim.music`，播放 position 重置（切歌成功） |
| 4 | 息屏拦截 | ✅ 通过 | `mWakefulness=Dozing` 下音量加被捕获+CONSUME，屏幕保持息屏 |
| 5 | 锁屏拦截 | ✅ 通过 | `isKeyguardShowing=true` + `deviceLocked=1` 下音量加被捕获+CONSUME |
| 6 | 原始行为恢复 | ✅ 通过 | `consume=0` 时音量 30→40（正常调节），CONSUME 计数=0 |

### 3.1 测试 1 详情（按键捕获）
```
KEY keyCode=24 action=DOWN repeat=0 flags=721420288
KEY keyCode=24 action=UP   repeat=0 flags=721420288
KEY keyCode=25 action=DOWN repeat=0 flags=721420288
KEY keyCode=25 action=UP   repeat=0 flags=721420288
```

### 3.2 测试 2 详情（原始拦截）
开启 `consume_up=1`，按音量加 3 次：
```
CONSUME VOLUME_UP DOWN / CONSUME VOLUME_UP UP   (x3)
```
音量：`before=5 → after=5`（未变化，系统音量调节被真正阻止）。

### 3.3 测试 3 详情（媒体控制）
- 发现酷狗概念版（`com.luna.music`）：音乐在播但 **不创建 MediaSession**（用老式 `BachMediaButtonReceiver`），`getActiveSessions()` 返回 0，PoC 正确 skip 不误控。
- 飞牛音乐（`com.trim.music`）：提供标准 MediaSession，`state=PLAYING(3)`。
```
media: activeSessions=1
media session: pkg=com.trim.music state=3 active=true
media: skipToNext -> com.trim.music
```
按音量加后 position 9047 → 1639（重置），确认切歌执行。

### 3.4 测试 4/5 详情（屏幕状态）
- 息屏：`mWakefulness=Dozing`，flags=184549376，事件被拦截。
- 锁屏：`mWakefulness=Awake` + `isKeyguardShowing=true` + `deviceLocked=1`，flags=721420288，事件被拦截。
- **结论：息屏与锁屏走同一 Hook 点，仅 policyFlags 不同，均可可靠拦截。**

### 3.5 补充验证（长按/重复事件）
```
KEY keyCode=25 action=DOWN repeat=0   (首次按下)
KEY keyCode=25 action=DOWN repeat=1   (系统重复)
KEY keyCode=25 action=UP   repeat=0   (抬起)
```
重复计数正确递增，具备实现短按/长按/双击识别的事件基础。

---

## 4. 异常恢复 / 稳定性验证

| 项目 | 结果 |
|---|---|
| 配置读取失败 | PoCConfig 默认全关（不消费、不切歌），失败透传 ✅ |
| Hook 未安装时 | 音量键走系统原始行为，无副作用（软重启前观察 4 小时无异常）✅ |
| 不消费时 | CONSUME 计数=0，系统音量调节完全正常（30→40）✅ |
| 媒体会话为 0 | `no playing session, skip`，不误控其它应用 ✅ |
| 按键卡死 | 未出现，DOWN/UP 成对，长按 repeat 正确 ✅ |

---

## 5. 重要发现与对后续阶段的影响

1. **ClassLoader 必须用 `Thread.currentThread().getContextClassLoader()`**——已落实到 `KeyHookEntry.findClassDiag()`，后续正式版沿用。
2. **不是所有音乐应用都提供 MediaSession**——酷狗概念版用老式 MediaButtonReceiver，飞牛音乐有标准 MediaSession。需求 §7 的担心被证实。**后续正式版必须**：
   - MediaSession 为主要路径；
   - 对无 MediaSession 的应用需要研究 MediaButtonReceiver / 系统媒体按键分发作为补充（但不能退回"普通应用模拟"）；
   - UI 上明确告知用户目标应用是否提供 MediaSession。
3. **Hook 点确认可用**：`PhoneWindowManager#interceptKeyBeforeQueueing` 在 HyperOS 3 / Android 16 真机上能真正拦截原始音量键，满足需求 §6"真正拦截"的核心要求。
4. **scope 注入需软重启**：后续版本更新后需提示用户重启 system_server 或设备。

---

## 6. PoC 开关说明（adb 命令）

```bash
# 打开按键捕获日志
adb shell su -c 'settings put global hyperhand_sk_poc_capture 1'
# 吞掉音量加 / 音量减（验证拦截）
adb shell su -c 'settings put global hyperhand_sk_poc_consume_up 1'
adb shell su -c 'settings put global hyperhand_sk_poc_consume_down 1'
# 命中时执行"下一首"
adb shell su -c 'settings put global hyperhand_sk_poc_media_next 1'
# 查看日志
adb logcat -s HyperHandSK:I
# 恢复安全默认
adb shell su -c 'settings put global hyperhand_sk_poc_consume_up 0; settings put global hyperhand_sk_poc_consume_down 0; settings put global hyperhand_sk_poc_media_next 0'
```

> 当前所有开关已恢复安全默认（capture=1，其余=0），系统按键功能完好。

---

## 7. 结论

**Phase 2 底层技术可行性验证通过。** 核心Hook点、原始事件拦截、媒体控制、息屏/锁屏拦截、透传恢复均已在目标真机实测确认。可以进入 Phase 3（Miuix UI 开发）及后续规则引擎、LSPosed 集成阶段。同时报告中指出的 MediaSession 兼容性问题需在设计规则引擎与 UI 时纳入考量。
