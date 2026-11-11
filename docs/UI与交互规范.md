# HyperHand UI 与交互设计规范

> 本文是 HyperHand 界面与交互的**权威约定**。所有 UI 改动以本文档为准；
> 组件级样式数值的出处见 [`miuix-components.md`](miuix-components.md)（miuix 组件目录）。
>
> 视觉基准：**miuix（top.yukonga.miuix.kmp）组件库** + 参考应用
> **com.mercury.sonypods（SonyPods）** 的真机实现。凡本文未尽之处，
> 一律回到 miuix 源码默认值与 SonyPods 的实际用法。

---

## 1. 总则

1. HyperHand 是原生 View 实现，但所有视觉规格**1:1 对齐 miuix / SonyPods**，
   不得自创参数。改动样式前先查 `miuix-components.md` 的数值表。
2. 玻璃 / 模糊效果一律走「采样背后内容 + GPU 调色」的管线（RenderNode +
   RenderEffect / AGSL），**不使用静态贴图伪造**，保证颜色随背景实时变化。
3. 120Hz 屏一帧预算 8.33ms；任何每帧工作必须留余量（见 §5 性能约束）。

---

## 2. UI 规范

### 2.1 对话窗口（MiuixDialog）

全应用唯一弹窗组件 `MiuixDialog`，规格 1:1 对齐 miuix `OverlayDialog`
（`DialogContentLayout.kt` 的 `DialogDefaults`），按钮布局采用 SonyPods
对话窗口（PowerOffDialog / PodImageConfigDialog）的横排惯例：

| 项 | 规格 |
|---|---|
| 宽度上限 | 420dp（屏幕更窄时收到 `屏宽 − 2×12dp`） |
| 圆角 | 32dp |
| 外边距 | 12dp |
| 内边距 | 24dp |
| 卡片底色 | 不透明 `#FFFFFF`（浅色）/ `#242424`（深色） |
| 标题 | 18sp / Medium / 居中；上 24dp、下 12dp |
| 说明 | 16sp / 居中 / summary 色；下 12dp |
| 按钮 | TextButton：高 40dp、圆角 16dp、文字 17sp / Medium |
| 双按钮 | **横排等宽，间距 8dp**：左 secondary（灰底）/ 右 primary（蓝底） |
| 单按钮 | 全宽一列 |
| 按钮区边距 | 上 12dp、左右 24dp、下 24dp |
| 蒙层 | 黑 α0.3（浅色）/ α0.6（深色） |
| 入场 | alpha 0→1 + scale 0.94→1，220ms Decelerate(1.5) |
| 出场 | alpha 1→0 + scale →0.96，140ms 后真正 dismiss |

> 依据：miuix `DialogDefaults`（MaxWidth 420 / CornerRadius 32 / margins 12、24 /
> title4 18sp / body1 16sp / button 17sp / windowDimming 0.3、0.6）＋
> SonyPods `PowerOffDialog` 的 `Row(Arrangement.spacedBy(8.dp))` 双按钮。

### 2.2 顶部渐进式模糊（TopBar / ProgressiveBlur）

| 项 | 规格 |
|---|---|
| 模糊半径 | 16dp（= SonyPods `BAR_BLUR_RADIUS`） |
| 梯度 | `ProgressiveBlur.Top.copy(startFraction=0.12, endFraction=1, curve=1.25)` |
| 强度曲线 | `intensity = 1 − pow(smoothstep(u), curve)`（miuix 原式，smoothstep 不可省） |
| 叠色 | `surfaceColor @ α0.66`（= SonyPods `TOP_BAR_SURFACE_ALPHA`） |
| 叠色渐变 | `rampWeight = smoothstep(1 − pow(smoothstep(u), curve))`（与模糊同一条 ramp） |
| 模糊带高度 | 状态栏 + 52dp 标题条（展开时附加淡出段） |
| 显隐 | 折叠进度 > 0 开始显形（collapse/0.30 为 ramp）；关于页整页不用模糊带 |

> 禁止偏离 0.66 的雾罩强度与曲线形状；此前 0.45 + `1−u^c` 的组合就是
> 「与参考不符」的来源。

### 2.3 关于页 hero（logo / 应用名 / 版本）

版式与 SonyPods `AboutPage.kt` 逐项对齐：

| 元素 | 规格 |
|---|---|
| logo | 88dp 圆角方块（圆角 = 边长×0.27），内部图形**镂空**（露出背景，非白色描边） |
| logo 玻璃 | 玻璃采样背后背景 + miuix 混色链（见下） |
| 间距 | logo → 字标 12dp；字标 → 版本 5dp |
| 字标 | 「HyperHand」粗体，40dp（帽高 28.5dp，对齐参考字标）；字形做玻璃蒙版 |
| 版本号 | 14sp summary 色，格式 `版本名(版本号)`，如 `3.0.3-beta.1(29905)` |
| 顶部留白 | 状态栏 + 144dp |
| 滚动预算 | hero 底部到内容顶部 + 126dp |

**淡出序列**（滚动进度 p，逐元素）：版本 0.05–0.20 → 字标 0.20–0.35 →
logo 0.35–0.50，各带 scale 1→0.95；背景 alpha = 1−p。

**玻璃混色链**（GlassNode，逐行移植 miuix `Shaders.kt`，三层按序）：

| 层 | 浅色 | 深色 |
|---|---|---|
| 1 | ColorBurn `#4A4A4A @ α0.80` | ColorDodge `#A1A1A1 @ α0.90` |
| 2 | LinearLight `#4F4F4F @ α1.0` | LinearLight `#E6E6E6 @ α0.30` |
| 3 | Lab `m=0x1A/255, n=0xF2/255` | Lab `m=0x1A/255, n=0xF5/255` |

> Lab 层语义（miuix `labColor`）：`L' = (n−m)·L + 100·m`，色度不变。
> 这条链把浅色背景压成深紫、深色背景提亮，正是参考 logo/字标随背景变色的原因。
> **不得用「明度归一」之类的近似替代**（旧版 LIFT 近似导致 logo 灰紫、字标发浅）。

### 2.4 其它组件速记

- **顶栏（TopBar）**：折叠标题 20sp Medium；展开大标题 32sp；左边距 26dp；
  52dp 折叠条高度；横滑跟手、每页独立折叠进度。
- **液态玻璃底栏（GlassBar / ViewBackdrop）**：整页软件采样 → 胶囊模糊。
  **软件画布（采样路径）不得绘制 RenderNode**（坐标不跟随 canvas 变换，
  会把 hero 玻璃糊进底栏采样图，产生紫色雾团与文字残影）——软件画布一律退回纯色。
- **设置页分组**：触发 / 高级 / 模糊区手势 / 互斥 / 高级。**不含调试**（见 §3.1）。
- **卡片**：圆角 16dp，间距 12dp（`Theme.CARD_GAP`）。
- **单选弹窗 / Slider / Checkbox**：沿用 miuix 规格（详见 miuix-components.md
  §2.1 Checkbox 父子三态、Button/Slider 数值）。

---

## 3. 交互约定

### 3.1 调试模块（隐藏入口）

- **调试模块不在设置主界面中展示。** 设置页不出现任何「调试」分组或开关。
- 唤起方式：**关于页连续点击 logo 五次**（相邻两次间隔 ≤ 800ms），弹出「调试」
  对话窗口。
- 调试窗口内容：调试开关（调试日志）/ 运行状态 / 最近手势 / 快速操作
  （预览单手模式、退出单手模式、重启两个作用域）。
- 「调试日志」开关在调试窗口内切换，即时生效并持久化；设置页保存配置时
  该值直通（不会被重置）。
- 设计意图：调试能力面向开发者，不应出现在普通用户的设置路径里；
  五连点是各厂商通用的隐藏入口惯例，零学习成本且不易误触。

### 3.2 滚动与手势

- **关于页滚动**：背景渐变与 hero 随滚动淡出（§2.3 序列）；滚到底时
  背景动画停摆（alpha ≤ 0 → 停止动画，静止 0 帧）。
- **Rubber Band**：页面到顶/到底后继续拖动，内容按橡皮筋阻尼回弹；
  回弹期间底栏玻璃不得出现残影或色块。
- **顶栏横滑**：标题跟手平移，两页可处于不同折叠状态；落定后吸附。
- **底栏**：三页（概览/设置/关于）胶囊选中态高亮；切换页时玻璃采样必须
  跟随页面内容实时更新。

### 3.3 概览页

- 右上角按钮弹出「重启作用域」对话窗口（miuix Dialog 嵌 Checkbox）：
  父项「全部作用域」三态（全选/全不选/半选）与两个子项联动；
  布局与按钮样式遵循 §2.1。

---

## 4. 文案与格式

- 版本号格式：`版本名(版本号)`，与参考一致（如 `1.8.1(23)`）。
- 对话窗口按钮：主动作在**右**、取消在**左**；主按钮用 primary 色。
- 日期时间：`yyyy-MM-dd HH:mm`。

---

## 5. 性能约束（改动 UI 时不得回退）

1. **动画节流 60fps**：背景渐变动画最短帧间隔 16ms（对齐 SonyPods
   `minDeltaNanos = 1s/60`）。
2. **alpha ≤ 0 停动画**：淡出到不可见立即停止动画任务。
3. **hero 玻璃隔帧重录**：背景 5 秒一周期，30fps 重录 RenderNode 足够；
   每帧两遍全屏着色器是滚动掉帧主因。
4. **软件画布不画 RenderNode**（见 §2.4 液态底栏）。
5. 预算参考（小米 14，120Hz）：GPU 执行中位 ≤ 7ms；静止渐变稳定 60fps
   （帧间隔 16.6ms）；滚到底静止 0 帧。

---

## 6. 文件映射

| 关注点 | 文件 |
|---|---|
| 对话窗口 | `MiuixDialog.java` |
| 顶部渐进模糊 | `TopBar.java` / `ProgressiveBlur.java` / `TopShade.java` |
| 关于页 hero | `AboutPage.java`（AppIcon / GlassText）/ `GlassNode.java` |
| 图标路径 | `Icons.java`（`glyph()` 返回蒙版用路径） |
| 调试入口 | `AboutPage.showDebugPage()` / `MainActivity.toggleDebug()` |
| 设置页 | `SettingsPage.java`（无调试分组） |
| 液态底栏 | `GlassBar.java` / `ViewBackdrop.kt`（glass 模块） |
| 版本号 | `module/build.gradle.kts`（`defaultVersionName` + `minVersionCode` 下限） |
