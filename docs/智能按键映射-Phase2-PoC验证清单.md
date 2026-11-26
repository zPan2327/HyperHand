# Phase 2 PoC — 真机验证清单

> 目标环境：Xiaomi 14 / HyperOS 3 / Android 16 / KernelSU / LSPosed（或 Vector，libxposed API 102）
> PoC 模块：基于 HyperHand 现有 APK 增量修改，新增 `smartkey.hook` 包
> 日志标签：`HyperHandSK`

---

## 一、构建与安装

1. 本地已能编译（见构建结果），产物：`module/out/HyperHand.apk`
2. 推送安装（首次覆盖需注意签名一致，用仓库内 keystore）：
   ```
   adb install -r module/out/HyperHand.apk
   ```

## 二、LSPosed 作用域配置（关键变更）

模块已从 `staticScope=true` 改为 `false`，`scope.list` 默认含 `system` / `com.android.systemui` / `com.miui.home`。

1. 打开 LSPosed / Vector → 模块 → 启用 HyperHand。
2. **作用域勾选**：
   - ✅ **系统框架（system_server）** — 对应虚拟包名 `system`，**Hook 音量键必需**
   - ✅ 系统界面（com.android.systemui） — 原有单手模式功能
   - ✅ 桌面（com.miui.home） — 原有单手模式功能
   - 音乐软件暂不勾选（PoC 用 system_server 内的 MediaSessionManager 直接枚举会话，不需要 hook 音乐应用）
3. 重启设备（system_server 作用域必须重启才能注入）。

## 三、日志抓取

另开终端实时抓取：
```
adb logcat -s HyperHandSK:I HyperHand:I
```

## 四、开关配置（通过 adb 写 Settings.Global，无需编译）

PoC 配置前缀 `hyperhand_sk_poc_`，4 个开关：

| 开关键 | 含义 |
|---|---|
| `hyperhand_sk_poc_capture` | 打印按键日志（默认 true） |
| `hyperhand_sk_poc_consume_up` | 吞掉音量加（默认 false） |
| `hyperhand_sk_poc_consume_down` | 吞掉音量减（默认 false） |
| `hyperhand_sk_poc_media_next` | 命中时执行"下一首"（默认 false） |

开启 capture（必须，用于测试 1）：
```
adb shell settings put global hyperhand_sk_poc_capture 1
```

## 五、六项验证（严格按序，逐项记录）

### 测试 1：按键捕获
```
adb logcat -s HyperHandSK:I
```
按音量加 / 音量减，观察是否出现：
```
KEY keyCode=24 action=DOWN repeat=0 ...
KEY keyCode=24 action=UP repeat=0 ...
```
- 音量加 keyCode=24，音量减 keyCode=25。
- **判定**：能否捕获 DOWN / UP / 重复事件？重复事件 repeat 是否递增？
- 若日志无输出 → Hook 未安装或签名不匹配，看是否有 `NO interceptKeyBeforeQueueing hooked` / `PhoneWindowManager class NOT FOUND`。

### 测试 2：原始事件拦截
```
adb shell settings put global hyperhand_sk_poc_consume_up 1
```
按音量加，观察：
- 音量条是否还弹出？音量值是否变化？
- logcat 是否有 `CONSUME VOLUME_UP DOWN`？
- **判定**：能否真正阻止系统音量调节？
- 还原：`adb shell settings put global hyperhand_sk_poc_consume_up 0`

### 测试 3：媒体控制
先播放网易云音乐，然后：
```
adb shell settings put global hyperhand_sk_poc_media_next 1
```
按音量加，观察：
- logcat 是否出现 `activeSessions=N`、各会话 `pkg=... state=...`、最终 `skipToNext -> ...`？
- 音乐是否切到下一首？
- **判定**：system_server 内 MediaSessionManager 能否拿到会话并控制？多会话时选了谁？
- 还原：`adb shell settings put global hyperhand_sk_poc_media_next 0`

### 测试 4：息屏拦截
息屏（按电源键灭屏）后，重复测试 1-2：
- **重点**：息屏时音量键是否走相同的 `interceptKeyBeforeQueueing` 路径？
- 首次按键是否唤醒屏幕并改变事件分发？
- 系统是否在 Hook 之前提前消费了事件？

### 测试 5：锁屏拦截
亮屏但未解锁（锁屏界面），重复测试 1-2：
- **判定**：亮屏未解锁时事件是否完整到达 Hook 点？

### 测试 6：原始行为恢复
所有开关归零：
```
adb shell settings put global hyperhand_sk_poc_consume_up 0
adb shell settings put global hyperhand_sk_poc_consume_down 0
adb shell settings put global hyperhand_sk_poc_media_next 0
```
反复按音量键，确认：
- 音量调节完全恢复正常，无吞键、无卡键、无重复切歌。

## 六、必须回传的信息（用于确认 Hook 点）

1. 实际 Hook 类名与方法完整签名（日志里 `candidate:` / `HOOKED` 行）。
2. Hook 所在进程（确认是 system_server，uid=1000）。
3. 测试 1-6 的通过 / 失败结果 + 关键 logcat 片段。
4. 失败场景（如签名不匹配、事件未到达、息屏失效）与现象描述。

## 七、异常恢复验证（补充）

- LSPosed 里停用模块 → 音量键应完全正常（PoC 不运行时系统原生行为）。
- Hook 抛异常 → PROTECTIVE 模式应保证系统不崩溃、音量键不卡死。
