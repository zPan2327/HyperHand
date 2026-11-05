# Changelog

所有值得注意的改动都会记录在这里。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [2.6.1] - 2026-11-05

修掉"缩小动画还没结束，上半屏就先蒙了一层半透明灰"。触发算法与互斥闸门未改动。

### Fixed

- **动画期间的半透明灰层**：`one-handed-tutorial-overlay` 的 alpha 从 0 淡入，且它盖在
  应用**之上**；2.5 把模糊图贴在这层上，于是动画还没开始时，半透明的模糊图就已经压在应用上方
  （逐帧实测：触发后第 337 帧，桌面下半段数值未变，顶部 1~8% 已从 `128 111 85…` 压到 `103 88 76…`）。
  → 现在把模糊图改贴到**垫底层**（`background-panel`，在应用窗口之下），
  tutorial overlay **背景设为透明**（只保留提示文字/图标）。
  于是只有应用真的缩小、让出缝隙时才会露出模糊，曝光节奏与动画一致；稳定态观感不变。
- 兜底：若 400ms 后仍未抓到垫底层（个别机型/裁剪版本），自动退回"overlay 自己贴模糊图"。

### Changed

- 移除只在调试时使用的 `hh_swipe` 入口（它会以 root 执行 `input swipe`，
  放在 release 包里等于给任何能启动 Activity 的应用留了 root 执行通道）。

### 验证（真机 Xiaomi 14 / OS3.0.305.0.WNCCNXM）

- 日志：`overlay window added` 之后不再出现 `applied: one-handed-tutorial-overlay`；
  只有 `applied: background-panel ... bgBefore=Color#FFDFDDE5`。
- 稳定态截图：上半屏模糊壁纸、与下方无缝、无黑边。
- 系统刷回米白仍被拦：`blocked system repaint on overlay`。

## [2.5.0] - 2026-11-05

修掉"手动触发时闪一下 + 先看到一层灰"。**触发算法、互斥闸门、缩小动画与退出逻辑均未改动。**

### Fixed

- **入场闪灰（1 帧）**：录屏 60fps 逐帧取色实测，2.4.6 入场第 80 帧顶部亮度 223
  （米白 `#DFDDE5`）、前后都是 67，即 16ms 的灰。三个成因分别处理：
  1. `addView` 之后原来是 `post` 到 View 的 handler，实测延迟 13~40ms，窗口第一帧已经画完。
     现在该 View **尚未 attach**（没有 ViewRootImpl）时**同步** `setBackground`，首帧即模糊图。
  2. 系统会在入场动画里把主题色刷回去（logcat：`bgBefore=Color#FFDFDDE5`）。
     现在贴好即**上锁**：hook `View#setBackgroundDrawable`，锁定 View 上系统想设非本方 drawable
     就直接跳过原方法，系统改不动（日志 `blocked system repaint`）。不再靠反复重贴对抗。
  3. 缩小动画最初一两帧 overlay 还没合成，露出垫底的 `background-panel` 米白。
     该 View 通过 `BackgroundWindowManager#mViewHost` 精确认领（不靠猜类名），同样贴图+上锁。
- **关闭开关时恢复不彻底**：原来只清缓存不解锁，可能停在模糊图上。现在解锁并把垫底层
  还原成 `getThemeColorForBackground()`、overlay 交回系统，下次入场由系统重设。
- **日志可观测性**：`background-panel` 与 overlay 分开节流，能分别看到是否贴图成功；
  找不到 `mBackgroundView` 时会打印该类的字段名列表，方便换机型时定位。

### Changed

- `build-artifacts/Bands.java` / `Frames.java`：逐帧取色工具（按区域求平均亮度、
  标出"浅色帧"和跳变帧），用于客观判断有没有闪，替代肉眼判断。

### 验证（真机 Xiaomi 14 / OS3.0.305.0.WNCCNXM）

进入→退出→进入连续两轮，429 帧逐帧取色：**浅色帧 0 帧**；系统重绘被拦下；
首帧顶部亮度 67（模糊壁纸）；静止画面无缝无黑边。详见 docs/单手模式增强.md §6。

## [2.4.4] - 2026-11-05

外观与维护性更新。触发算法、三道互斥闸门、缩小动画与退出逻辑**均未改动**。

### Added

- **模糊壁纸遮罩**：单手模式上方默认是主题米白，现改为当前壁纸的高斯模糊版本。
  - 新增 `oh_tuner_blur_bg`（默认 `1`）、`oh_tuner_blur_radius`（默认 24dp）、
    `oh_tuner_blur_dim`（默认 12%），设置页新增「外观」分组。
  - 真机取证确认米白由**两层**叠出来的：底部 `BackgroundWindowManager.background-panel`
    （全屏、SurfaceControlViewHost）与盖在上面的 `one-handed-tutorial-overlay`
    （WindowManager 窗口，type 2024，只占上半屏 1200×1068）。两层都换成同一个模糊位图。
  - 抓窗口用 `WindowManagerGlobal#addView` 按 **window title** 匹配，不依赖 WMShell 私有字段；
    系统"填色"回调（`fillBackgroundColor` / `updateThemeColor` / `onAnimationUpdate`）之后
    **同一帧**再替换一次，避免入场时先灰一下的闪烁。
  - 壁纸按屏幕比例居中铺满 → 1/6 分辨率 → 三遍盒式模糊（≈高斯）→ 按参数压暗；
    后台线程生成，按 (壁纸 id | 屏幕尺寸 | 半径 | 压暗) 缓存。上半屏窗口按目标尺寸
    从位图顶端裁同比例一块再铺满，既不被压扁也不留黑边。
  - 动态壁纸 / 读不到壁纸时保持系统原生颜色，不做降级猜测。
- **一键重启两个作用域**：设置页底部新增按钮，确认后执行
  `killall com.android.systemui; killall com.miui.home; sleep 1; input keyevent 3`。
  `su` 按四个常见路径依次尝试，25s 超时；**没有 root 时自动退回**
  `oh_tuner_action=reload`（SystemUI 自杀重启，persistent 进程会被系统拉起），这条路不需要 root。
- **调试动作**：「调试」卡片新增「预览遮罩 / 退出单手模式」；另外支持
  `oh_tuner_action=trigger|stop|reload|probe|blur` 五个广播动作，其中
  `probe` 会把两层分别涂成红/绿用于定位层级，`blur` 无条件贴图用于排查。

### Fixed

- `startOneHanded()` 之前调用在 `OneHandedController` 上，但 HyperOS 把启动逻辑放在
  `OneHandedController$OneHandedImpl`，控制器本身没有这个方法 —— 每次触发都会
  落到"写 `one_handed_mode_activated`"的兜底路径。现在按 `asOneHanded()` →
  扫描 `*OneHandedImpl` 字段的顺序取到实现对象再调用，兜底仍保留。
- 配置接收器原本要等第一次触摸才注册，导致冷启动后第一次「保存」或「预览」没有反应；
  现在模块挂载后主动注册（Application 未就绪会重试）。
- 遮罩 View 的 ViewRootImpl 建在 WMShell 自己的线程（`wmshell.main`），
  直接在主线程 `setBackground` 会抛 `CalledFromWrongThreadException`；
  现在统一用 `view.post()` 投到该 View 自己的 handler。
- 构建脚本：类文件变多后 `d8` 撞上 Windows 命令行长度上限（"输入行太长"），
  改为先 `jar` 打包再交给 d8；`build.ps1` / `build.sh` 同步修改。

## [2.2.0] - 2026-11-05

触发算法重做：从"单点峰值速度一刀切"改为"历史采样 + 分窗速度 + 双路径置信度"，
目标是让自然下滑就能触发，接近 iPhone Reachability 的手感。**只改触发层**，
缩小动画/布局/触摸映射/退出方式、三道互斥闸门、钩子点位全部保持不变。

### Added

- **双路径识别**（`Recognizer.java`，新增文件）：
  - 快甩路径：短距离 + 峰值速度达标；
  - 慢拉路径：长距离 + 平均速度达标 + 轨迹稳定（`oh_tuner_slow_enabled`）。
  两条路径各算置信度取 `max`，分项为 `smoothstep` 连续函数，不再是硬阈值。
- **三种识别模式** `oh_tuner_recognition_mode`：`0` 自然（默认）/ `1` 灵敏 / `2` 防误触，
  各自带一组推荐阈值（见 README 参数表）。
- **历史采样入队**：`MotionEvent.getHistorySize()` 的采样全部参与计算，缓冲 192 点（满时抽稀）。
- **分窗速度估计** `oh_tuner_velocity_window_ms`：默认 60ms 窗口取两端位移/时间，
  替代相邻两帧求导；新增 `oh_tuner_max_velocity`（dp/s）对异常峰值截断。
- **轨迹稳定性判定** `oh_tuner_min_monotonic`：净位移/路程，识别"来回蹭"。
- **自适应阈值** `oh_tuner_enable_adaptive_threshold`：快而干脆的手势略放宽、拖沓的长手势略收紧。
- **手势调试**：调试开启时把最近 3 次判定（位移/速度/单调性/置信度/未触发原因）写入
  `oh_tuner_last_gesture`，设置页「调试 → 最近手势」直接可读；关闭调试则完全不写。
- **触发区左右偏置** `oh_tuner_zone_bias`：有效区域中心可左右移动，配合左右手落指习惯。
- 设置页新增"识别模式"选择器与可折叠的"高级参数"区，参数按功能分组。

### Changed

- **速度单位统一为 dp/s**（内部计算全部走 dp，与屏幕密度解耦）。旧的
  `oh_tuner_min_velocity_px_s`（px/s）保留读取，首次加载时按 density 折算写入
  `oh_tuner_min_velocity_dp_s`，之后以新键为准。
- `oh_tuner_min_dy_dp` 默认值不再固定 4dp，改为跟随模式（12 / 8 / 18）；
  显式保存过的值继续生效。
- 触发判定前新增"抬手前是否已停住"的惩罚项，"下拉→按住→抬手"不再算一次下滑。
- 横屏直接跳过判定（原生 `startOneHanded()` 本来就会拒绝）。
- **闸门① 的几何与触发判定对齐**：`StatusBarSwipeController.canInterceptTouch()` 现在使用与
  SystemUI 侧完全相同的区域（高度 + 宽度占比 + 左右偏置）。此前宽度收窄后，底部角落会出现
  "桌面已让位、监视器又不认"的死区。
- 日志格式改为单行可 grep：
  `GESTURE mode=0 dy=45.2dp dx=6.1dp dur=205ms vavg=214 vpeak=386 mono=0.94 conf=0.68 n=26 -> TRIGGER(A)`。

### Fixed

- `ACTION_CANCEL` / 第二根手指按下时会打印原因（此前静默丢弃，排查时看不出发生过）。
- 配置读取增加 NaN / 无穷值 / 越界回退，旧键缺失时不会把阈值读成 0。
- 屏幕尺寸缓存增加横竖屏判断，旋转后不再用旧的 height 计算触发区。

### Compatibility

- `applicationId`、libxposed API 102、静态作用域（systemui + miui.home）、
  `Settings.Global` 的 `oh_tuner_*` 键名与语义、构建脚本、签名流程全部不变。
- 旧版本（1.x / 2.0 / 2.1）参数升级后继续生效，不需要重置。

## [2.1.0] - 2026-10-29

包名再改一次：`com.hyperhand.tuner` → `io.github.zpan2327.hyperhand`。

### Changed

- **包名改为 `io.github.zpan2327.hyperhand`**。原包名对应的域名 `hyperhand.com` 不归本项目所有，
  LSPosed 模块仓库的提交机器人判定为「无法验证归属」并关闭了申请（issue #2343）。
  `io.github.<用户名>.*` 属于 GitHub Pages 自带的命名空间（`zpan2327.github.io`），无需域名验证即可收录。
- 入口类、广播 action 随之变更：`io.github.zpan2327.hyperhand.HyperHand` /
  `io.github.zpan2327.hyperhand.action.SET`。
- `Settings.Global` 里的 `oh_tuner_*` 键保持不变，已调好的参数在升级后继续生效。

> 2.0.0 只发布了十几分钟、包名不可用于模块仓库，可直接用 2.1.0 覆盖；两者包名不同，需先卸载再装。

## [2.0.0] - 2026-10-29

**不兼容升级**：包名、模块入口、Xposed API 全部变更，1.x 无法覆盖安装。

### Changed

- **改名 HyperHand**：包名 `com.onehandtuner` → `com.hyperhand.tuner`，应用名/模块名改为 HyperHand
  （旧版请先 `adb uninstall com.onehandtuner`）。
- **迁移到 libxposed 现代 API 102**：入口类改为 `HyperHand extends XposedModule`，
  钩子换成拦截器链（`Hooker#intercept`），新增 `Hooks.java` 把 `findClass` / `hookMethods` /
  `hookConstructors` / `call` 等 legacy 习惯封装回来；`startOneHanded()` 改用 `getInvoker`。
  需要实现 API 102 的框架（Vector 2.x / 新版 LSPosed），LSPosed 1.9.x 会跳过本模块。
- **静态作用域**：`META-INF/xposed/scope.list` + `module.prop` 的 `staticScope=true` 声明
  `com.android.systemui` 与 `com.miui.home`，用户无需（也无法）手动勾选作用域。
- 模块声明改为 `META-INF/xposed/{java_init.list,scope.list,module.prop}`，
  移除 `assets/xposed_init` 与 `xposedmodule / xposedminversion` 等 legacy meta-data。
- 模块名与简介改用 `res/values/strings.xml`（`android:label` / `android:description`）；
  构建脚本新增 `aapt2 compile --dir res` 步骤。
- 签名密钥文件更名为 `module/signing/hyperhand.keystore`。
- 设置页文案随打包名与静态作用域更新。

### Added

- CI 增加结构自检（校验 APK 内三个 `META-INF/xposed/*` 文件）与
  `Publish to Xposed-Modules-Repo` 步骤：打 tag 时以 `版本号-版本名` 的 tag 同步到官方模块仓库
  （需配置 `MODULE_REPO_TOKEN`）。
- 新增 [docs/发布到模块仓库.md](docs/发布到模块仓库.md)：收录要求、提交步骤、校验与排错。

## [1.0.2] - 2026-10-26

### Changed

- **设置界面重做**：按 HyperOS / Miuix 的设计语言重写配置页（观感参照 [SonyPods](https://github.com/Mercury000/SonyPods)），新增组件层 `Ui.java`：
  - `#F7F7F7` 页面底色 + 白色 16dp 圆角卡片；段落标题 14sp Medium、行标题 17sp Medium、摘要 14sp；0.75dp 分隔线左内缩 16dp。
  - 自绘 Miuix 风格开关（49×28dp 轨道、20dp 圆钮、180ms 动画）与按钮，去掉 `Switch` / `Button` 的系统默认外观。
  - 数值不再手输：点行弹出滑杆对话框（带范围与步长），底部统一「保存并立即生效」；未提交时状态行提示。
  - 深色模式跟随系统（`#000000` 底 / `#242424` 卡片 / `#277AF7` 强调色），状态栏与导航栏改为 edge-to-edge。
  - 设计 token 与差异说明见 [docs/设计稿对照.md](docs/设计稿对照.md)。

## [1.0.1] - 2026-10-26

### Fixed

- **CI 构建失败**：`android-actions/setup-android@v3` 在托管 runner 上失败（cmdline-tools 下载/安装问题）。改为直接使用 runner 预装的 Android SDK（`ANDROID_HOME=/usr/local/lib/android/sdk`），并在 `build-tools` / `platforms` 缺失时自动退到镜像内的最新版本。
- 构建脚本支持 `VERSION_CODE` / `VERSION_NAME` 环境变量；`versionCode` 可由版本号自动推导（`1.0.1` → `10001`），打 tag 构建时版本号自动写进 APK。

## [1.0.0] - 2026-10-26

首个可用版本。在 Xiaomi 14 / HyperOS 3（Android 16）实机验证。

### Added

- **扩展触发区**：在 SystemUI 已有的 `[Gesture Monitor] onehanded-touch` 通道上判定底部下滑，调用原生 `OneHandedController.startOneHanded()`，触发区由 25dp 扩大到 56dp（可配）。
- **速度门限**：按相邻事件增量计算向下峰值速度，默认要求 ≥500 px/s —— 慢拖不触发、快甩必定触发；设 `0` 可退化为纯位移触发。
- **意图判别三道闸门**
  - ① 区域归属：钩桌面 `StatusBarSwipeController.canInterceptTouch()`，触发区内的桌面下拉手势让位。
  - ② 抬手判定：触发时机对齐原生 `NavStubView`，改为 `ACTION_UP`。
  - ③ 下拉信号互斥：监听 `LauncherProxyService$1.onTransact(case 10)`、`ShadeViewController.startInputFocusTransfer()`、`ControlCenterEventHandlerImpl.handleExpandEvent()`，手势期间出现即放弃单手模式。
- **配置界面**：无需 root，参数写入 `Settings.Global`（`oh_tuner_*`），保存即生效；也可用 `am broadcast` 修改。
- **构建脚本**：`module/build.ps1`（Windows）与 `module/build.sh`（Linux/macOS），固定签名密钥位置避免覆盖安装签名冲突。
- **CI**：GitHub Actions 构建 APK 并上传 artifact。
- **文档**：`docs/单手模式增强.md` 记录真机调查结论、反编译证据、方案比较、实测日志与回滚步骤。

### Known issues

- 仅手势导航生效；横屏/分屏/隐藏提示线时受系统原生限制。
- 开启区域归属后，触发区内的桌面下拉不再打开通知/控制中心（刻意取舍，可关闭）。
