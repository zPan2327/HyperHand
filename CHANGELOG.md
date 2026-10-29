# Changelog

所有值得注意的改动都会记录在这里。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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
