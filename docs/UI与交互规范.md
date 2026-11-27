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

### 2.2 顶栏背景（TopBar：一页一条 + 高斯模糊）

**2026-11-25 起：顶栏重新加回高斯模糊，并且改成「一页一条」。** 上一版为省开销把模糊整体
删掉（`ProgressiveBlur.java` 已不存在），只留纯色条；用户明确要求恢复模糊质感，因此重做。

| 项 | 规格 |
|---|---|
| 实例 | **每页一条**，挂在 `PageHost`（页面容器）里，随 pager 一起平移 |
| 背景 | **近乎不透光的纯色 + 很淡的高斯模糊**：模糊图 `alpha = solid`，其上叠 `pal.bg`（**90% × solid**） |
| 模糊实现 | 本页内容录进 `RenderNode` → `RenderEffect.createBlurEffect(18dp)` → `canvas.drawRenderNode` |
| 显隐曲线 | 普通页 `solid = clamp01(collapse / 0.12)`；关于页（plain）`clamp01((collapse − 0.86) / 0.14)` |
| 折叠标题 | **水平居中**，title3 = 20sp Medium（LSPosed / Miuix TopAppBar 同款）；展开态大标题仍左对齐 26dp |
| 底边 | **硬截断**，不做渐隐、不画阴影、不画分隔线 |
| 右上角动作 | **常显**（不随大标题淡出），22dp 图标 + 40dp 命中区 + 按下态圆底 |

> **底色为什么是 90%。** 2026-11-26 对着 LSPosed 2.2.1 逐帧比对：它的折叠条在内容真的
> 从条底穿过时（Repository 长列表），看上去就是一片干净底色，只有极近距离能察觉到背后
> 内容的一点点色晕 —— 也就是「几乎不透光，模糊只留很淡一层」。
>
> - 取 100%：那条 10% 的色晕没了，模糊白做；
> - 取 32%（更早的版本）：卡片色块清楚透上来，用户两次报「顶栏还有半透明」；
> - 取 90%：像素统计上层内 `YMIN 225 / YAVG 226.8`（页面底色 225），深色文字透上来时
>   只余极淡灰影，肉眼几乎不可辨 —— 就是 LSPosed 的观感。

**为什么必须是「一页一条」**：旧实现只有一条全屏 TopBar，横滑时按浮点页位置把两页标题一起
平移、底色按两页折叠进度插值。两页折叠状态不同（一页在顶、一页已滚下去）时，那个插值区
就是「谁都不是」的中间态，看起来标题乱飘、底色忽明忽暗。现在顶栏属于页面，横滑就是两条
独立的栏一起滑过去。

**模糊为什么不能用 Bitmap + 盒式模糊**：页面里有 Compose 子树（Miuix squircle 用
`RuntimeShader` 画圆角），往**软件画布**上画会直接抛
`Software rendering doesn't support RuntimeShader`。`RenderNode` 是硬件录制，Compose 与
shader 都能走，模糊由 GPU 完成。

**取材必须按 scrollY 反向平移**：`View#draw()` 从视图原点开始画，不是「当前可见区域」。
不平移就会录到内容顶部的空白，模糊层整块透明 —— 现象与「完全没做模糊」一模一样
（第一版踩过，查了很久）。

**录制节流 16ms**：120Hz 下每帧重录没必要，最多落后一帧；`RenderNode` 只记显示列表、
不栅格化，成本主要在 GPU 的模糊本身。

关于页仍是 plain：它自带整屏渐变，压一条模糊带会把渐变切出一条边，滚到底才化底色。

### 2.3 关于页 hero（logo / 应用名 / 版本）

版式与 SonyPods `AboutPage.kt` 逐项对齐：

| 元素 | 规格 |
|---|---|
| logo | 88dp 圆角方块（圆角 = 边长×0.27），内部图形**镂空**（露出背景，非白色描边） |
| logo 玻璃 | 玻璃采样背后背景 + miuix 混色链（见下） |
| 间距 | logo → 字标 12dp；字标 → 版本 5dp |
| 字标 | 「HyperHand」粗体，40dp（帽高 28.5dp，对齐参考字标）；字形做玻璃蒙版 |
| 版本号 | 14sp summary 色，格式 `版本名(版本号)`，如 `3.1.0(30100)` |
| 顶部留白 | 状态栏 + 144dp |
| 滚动预算 | hero 底部到内容顶部 + 126dp |

**淡出序列**（滚动进度 p，逐元素）：版本 0.05–0.20 → 字标 0.20–0.35 →
logo 0.35–0.50，各带 scale 1→0.95；背景 alpha = 1−p。

**卡片**：hero 下方的卡片用**半透明填充**（`Ui.cardGlass` → `pal.cardGlass`，
浅色白 66% / 深色白 9%），让 `MiuixBg` 的流动背景透上来，边界只比背景亮一档。
禁止在这一页用不透明的 `Ui.card`：那块纯色会把渐变切出一个硬边方块，
与 hero 的玻璃质感断层（LSPosed 2.2.1「关于」页同样是半透明卡片，实测台阶 +22/255）。

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
- **液态玻璃底栏（HyperGlassBarView / ViewBackdrop）**：整页软件采样 → 胶囊模糊。
  **软件画布（采样路径）不得绘制 RenderNode**（坐标不跟随 canvas 变换，
  会把 hero 玻璃糊进底栏采样图，产生紫色雾团与文字残影）——软件画布一律退回纯色。
  - **滚动时必须让玻璃重采样**，否则整条底栏会停在滚动前那一帧，表现是
    「滚动时背景不动，点一下底栏才刷新」。链路：`MainActivity` 的
    `onScrollChange` / `onPageScrolled` / `MiuixScrollView.setOnOverScroll` →
    `HyperGlassBarView.onContentScrolled()` → `redrawTick`（`mutableIntStateOf`）→
    `Content()` **组合期**读一次 → `IosLiquidGlassNavigationBar(redrawSignal = tick)`。
  - `redrawSignal` **必须在组合期读**（`val tick = redrawTick.intValue`）。
    改成绘制期（`() -> Int`、或 `Modifier.drawWithContent { redrawTick.intValue }`）真机
    实测都**失效**：`recomp/s` 变 0 的同时背板采样次数也变 0，子树 draw 并不会重跑，
    玻璃冻在上一帧，只剩点底栏时才更新。这条路径不要再试。
  - **不做节流**：每来一次滚动信号就 `redrawTick++`。一帧内写多次会被 Compose 合并成
    一次重组，所以重采样率跟着屏幕刷新率走。曾经按 16ms 节流 = 把底栏锁死在 60fps；
    120Hz 面板上内容每 8.3ms 动一次、玻璃每 16.6ms 才采一次，看起来就是「玻璃只有一半帧
    在更新」。实测（面板 120Hz，`framestats` 的 `FrameInterval` 全为 8.3ms）去掉节流后
    底栏每帧 3 次 `drawBackdrop`（容器 / 图标层 / 指示器），`sample/s ≈ 3 × recomp/s`。
  - **禁止用「布尔锁存位 + Choreographer 回调复位」做帧合并**：
    ```kotlin
    if (redrawScheduled) return          // ← 一旦回调没回来，这个位永远是 true
    redrawScheduled = true
    Choreographer.getInstance().postFrameCallback { redrawScheduled = false; … }
    ```
    回调一旦被丢（队列异常 / 线程异常），之后所有滚动信号都会被 `return` 掉，
    玻璃**永久**冻结在那一帧，直到某次点击触发重组 —— 正是用户报的症状。
    正确写法就是直接 `redrawTick.intValue++`。
  - 验证方法（不要靠肉眼，也**不要只看 gfxinfo 百分位**）：这台机器是 LTPO 面板，
    刷新率会自己切。要判断"底栏到底每秒更新了几次"，用
    `adb shell dumpsys gfxinfo <pkg> framestats` 里的 `FrameInterval` / `IntendedVsync`
    先确认这段时间的 vsync 周期（8.3ms = 120Hz、16.7ms = 60Hz），再对照组件内部的计数。
    `gfxinfo` 的 `50th percentile` 会混进 vsync 等待时间，单独看会误判成渲染耗时。
  - 验证方法（不要靠肉眼）：`input swipe` 期间在设备端用单会话 `screencap` 连拍，
    把底栏区域按三等分分别算像素差 —— 中间那格有选中项高亮在动，**只看右/左
    没有高亮的那一格**，它变了才说明玻璃真的在重采样。注意页面内容本身必须
    覆盖到底栏后面，否则玻璃后面是纯背景，怎么滚都不会变（这会被误判成「没刷新」）。
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

> **横向切页：不做任何越界回弹**（2026-11-25 用户要求）。`PagePager` 的手指位置直接夹在
> `[0, pageCount-1]`，首/末页继续往外拖时页面纹丝不动；松手只做页吸附，没有橡皮筋、
> 没有抛物线阻尼。纵向 `MiuixScrollView` 的 Rubber Band 不受影响（那是页面内滚动）。

- **关于页滚动**：背景渐变与 hero 随滚动淡出（§2.3 序列）；滚到底时
  背景动画停摆（alpha ≤ 0 → 停止动画，静止 0 帧）。
- **Rubber Band（纵向，主界面 Java View）**：`MiuixScrollView` 只有一套逻辑 ——
  一个 `rawDistance`（手指空间越界距离）、一个 `offset = -easing(rawDistance)`、
  一个弹簧。**禁止再出现第二条动画路径**：旧版有手指拖动 / 形变回收 / 惯性冲量
  `impulseTick` / 弹簧回弹四条路径同时写 `offset`，且拖动到形变归零时会就地
  `startRebound()`，弹簧和手指同时写同一个位移 —— 这就是「回弹不干净、像有多套逻辑」。
  - 每帧 MOVE 只做一件事：先让系统 `super.onTouchEvent` 跑（保持
    `mLastMotionY` / `mIsBeingDragged` / `VelocityTracker` 状态机同步），
    处在形变中时把系统这一帧滚掉的量原样撤销，再按「先收形变、再滚内容」分配。
  - `apply()` 里 `if (v == offset) return`：普通滚动每帧都会调到它，位移没变就不
    `invalidate`、不回调，否则会白送一次整页重绘。
  - 未越过 `touchSlop` 的位移不参与形变（`dragging` 标志），否则点击时的 2px 抖动
    也会拉起橡皮筋。
  - **手势起点必须在 `onInterceptTouchEvent` 里也记录一次**（2026-11 修）：
    手指按在可点击的子 View（卡片行、开关、下拉项……）上时，DOWN 被子 View 消费，
    **根本不会进 `onTouchEvent`**，我们第一次拿到的是已被父类判定为滚动的那个 MOVE。
    此时 `lastY` / `downY` 还是上一次手势的残留值，第一帧 `dy` 是上千 px 的假跳变
    （真机实测 `dy=-1451` 而真实位移 13px）；父类照它自己的 `mLastMotionY` 正常滚
    （`moved` 很小），这个假余量一旦方向恰好撞上边界就被当成越界起点，直接拉到
    抛物线饱和值（20% 屏高）—— 表现就是「还没到边界、方向也不对，却猛地弹一下」。
  - **停弹簧/接管形变要等到第一次真正轮到我们处理 MOVE 时**（`takeOverDeformation()`），
    不能在 DOWN 就做：DOWN 可能被子 View 吃掉（纯点击），那样会把正在回弹的形变
    冻在半路（弹簧被 cancel，又没人接着跑）。
  - **`apply()` 里再加一道与事件序列无关的硬约束**（这是根治「中段回弹」的一道闸）：
    ```java
    if (v != 0f && !atOverScrollEdge(v)) {   // v<0 要「已到底」，v>0 要「已到顶」
        v = 0f; rawDistance = 0f;
    }
    ```
    前面几道判断（余量必须撞边界、watchdog 必须滚不动）都依赖「这一帧的事件序列符合
    预期」，而真实手指总会给出没覆盖到的组合（子 View 吃 DOWN、事件批量、抬手残余
    速度……），屡次修一处冒一处。形变只该由「已顶到边、手指还在推」产生，所以这里
    直接按**当前滚动位置**兜底：这一侧还能滚就把形变清零。短页面（range=0）两个方向
    都返回 false，正好允许两边都弹。
  - 真机/模拟器验证：`apply()` 打点统计，167 次非零形变**全部**落在
    `scrollY = 0` 或 `scrollY = max`；顶部下拉按住时画面位移明显，松手后与原始画面
    逐像素一致（差值 0.00）。
  - **余量必须再判一次边界，否则「每滑一次都会触发回弹」**（2026-11 修，用户反馈）：
    ScrollView 在「刚越过 touchSlop」的那一帧会先把 slop 从位移里扣掉再滚，
    于是那一帧 `moved` 比 `dy` 少了整整一个 slop，`dy - moved` 恒为非 0。
    旧代码不判边界、直接把它当成越界起点，页面正中间随便一划都会拉起两三十 px 的
    形变，抬手时弹簧再把它弹回去。正确写法：
    ```java
    float leftover = dy - moved;
    boolean blocked = leftover > 0f
            ? !canScrollVertically(1) : !canScrollVertically(-1);
    if (leftover != 0f && blocked) { rawDistance = leftover; … }
    ```
    真机验证（`logcat` 打点）：页面中段上滑/下滑各一次 → 零条「起手」日志；
    滑到底、顶部下拉 → 正常打点并回弹。
  - **2026-11-24 真机复核（Xiaomi 14 / 设置页 max=6781）**，`DEBUG_OVERSCROLL` 打点统计：
    | 手势 | `apply` 非零次数 | 中段（`0 < scrollY < max`）形变 |
    |---|---|---|
    | 中段慢速上滑 3×300px（内容确实滚了 900px） | 0 | 0 |
    | 中段慢速下滑 3×500px | 0 | 0 |
    | 快速连划 6 次 | 41（全在 `scrollY=max`） | 0 |
    | 快速上甩到底 | 42（峰值 100px，`scrollY=max`） | 0 |
    | 顶部下拉越界 | 73（`scrollY=0`，`SPRING v0=2345` 收敛到 0） | — |
    
    另记录一个「还没到边界就弹」的残余形态：上一次手势留下的弹簧还在回弹途中
    （`scrollY=0`），手指立刻上滑，内容一开始滚动（`scrollY` 变 61）时 `apply()`
    的硬约束就把形变清零（日志 `guard-block v=0 scrollY=61`），视觉上不会出现
    「页面已经在滚、还挂着一层越界形变」的双层位移。这条闸是结构性兜底，不依赖
    事件序列是否完整。
  - **惯性撞边界改用 watchdog，不用 `computeScroll()` 监测**：`computeScroll` 只在
    scroller 还在跑时被框架调用，惯性正好在最后一帧到达边界时 scroller 同时结束，
    之后没有回调，「甩到底」就永远不出橡皮筋。watchdog 自己逐帧 `postOnAnimation`，
    判定"该方向已滚不动"就把上一帧增量折成弹簧初速度 —— 冲出与收回是同一次积分。
  - 参数：`k=190 / ζ=0.9`（miuix 原值 280/1.0，按手感调软）；抬手速度上限 2600px/s；
    惯性折算上限 5600px/s（峰值位移 ≈ 0.0286 × v ≈ 6% 屏高）。
  - 真机轨迹验证（`offset` 逐帧打印）：下拉 `0→26→240→329`，按住 500ms 停在 `332`
    纹丝不动，松手 `321→296→…→0` 单调衰减；拉到一半反向推回 `358→269→91→0`。
    都是单曲线，没有二次弹跳。
- **Rubber Band（Compose 页面）**：实现上**不需要写任何代码** —— `MiuixTheme` 内部已把 `LocalOverscrollFactory` 覆盖成
    `MiuixOverscrollFactory`（字节码确认 `MiuixThemeKt` 里 `getLocalOverscrollFactory` +
    `MiuixOverscrollFactory.INSTANCE`），所有 Compose `verticalScroll` / `LazyColumn`
    默认就是连续阻尼 + 松手弹簧回弹，不是 Android 的 EdgeEffect 发光/拉伸。
    **禁止**自己套 `Modifier.overscroll` 或手写位移，会和 miuix 的补偿叠加成二次阻尼。
  - 需要关闭某一处越界时用 `Modifier.overScrollVertical(nestedScrollToParent = true, isEnabled = { … })`。
  - **短页面拉不出橡皮筋**是 Compose 的固有行为：滚动范围为 0 时不派发越界位移。
    `SmartKeyScaffoldContent` 的做法是给内容一个「视口高度 + 12dp」的最小高度，
    让滚动范围至少 12dp，短页也能拉出阻尼：
    ```kotlin
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().heightIn(
            min = with(density) { (viewportPx + OVERSCROLL_ROOM.roundToPx()).toDp() }
        ), content = content)
    }
    ```
    `viewportPx` 用 `onSizeChanged` 记外层高度，`OVERSCROLL_ROOM = 12.dp`。
- **下拉刷新（应用选择器）**：用 miuix `PullToRefresh`，禁止手写。
  - 结构必须是 `Column { 搜索栏; PullToRefresh(weight(1f)) { LazyColumn } }`：
    `SearchBar` 要放在 `PullToRefresh` **外面**，否则指示器会跑到搜索栏上方。
  - 默认文案是英文四项（`PullToRefreshDefaults.refreshTexts`），**必须按系统语言给**：
    ```kotlin
    if (Locale.getDefault().language.startsWith("zh")) listOf("下拉刷新", "松手刷新", "正在刷新…", "刷新完成")
    else listOf("Pull down to refresh", "Release to refresh", "Refreshing…", "Refreshed")
    ```
  - 刷新要绕过进程级缓存（`AppCache`），重新枚举一次应用列表。
- **顶栏横滑**：每页一条顶栏随页面整体平移，两页可处于不同折叠状态。LSPosed 的
  HorizontalPager 也是「纯平移、无缩放/变暗/阴影/圆角」，顶栏跟着页面走，不要加额外效果。
- **横滑无边界回弹**：`PagePager` 把位移直接夹在 `[0, pageCount-1]`，首/末页继续往外拖
  页面纹丝不动（用户 2026-11-25 明确要求）。旧版那套抛物线阻尼 + 边界弹簧已全部删除。
- **横滑动画 = 单段临界阻尼弹簧**（2026-11-26 最后一次回退后的定稿）：
  - 手势拖动：手指位移 **1:1** 跟手（`translationX`），无阻尼；
  - **松手吸附**：临界阻尼弹簧 `k=400 / ζ=1.0`（Compose `StiffnessMediumLow`），
    手指速度作为初速、限幅 2.4 页/秒；目标 = 朝甩动方向的相邻页；
  - 点底栏 / 代码切页：同一条弹簧、初速 0（对应 `animateScrollToPage`）；
  - **一次手势只翻一页**：Compose `PagerDefaults.flingBehavior` 默认
    `pagerSnapDistance = PagerSnapDistance.atMost(1)`，LSPosed 就是这个默认值 ——
    甩得再快也只是「当前页 ↔ 相邻页」，**不存在一甩跨两页**。目标恒为
    「朝甩动方向的那个邻页」（`floor(offset)+1` / `ceil(offset)-1`），
    初速只影响到位快慢，不影响页数；
  - 弹簧积分的步长上限 32ms（k=400 时半隐式欧拉的稳定边界是 ω·dt < 2，留足余量，
    掉帧到 30fps 也不会数值发散）。

> **不要再往这里加「decay 惯性滑行」。** 2026-11-26 试过照 Compose
> `SnapFlingBehavior.TargetAnimation` 做成「decay 滑行 → 越过目标页切 spring」两段，
> 用户装上后直接反馈「完全不好用，还不如之前」。原因是 Java 侧只能用
> `OverScroller` 的摩擦模型，它算出来的自然终点与页位对不上：
> - 不设 `minX/maxX` → decay 会一路滑过目标页，越过那一帧再把位置**直接赋成页位**，
>   观感是「滑一截 → 啪地跳到位」；
> - 设 `minX/maxX = 目标页` → `OverScroller` 撞界会在**一帧内**把 `currX` 赋成边界值，
>   比纯弹簧更硬。
>
> Compose 那边之所以顺，是因为 `splineBasedDecay` 的预测终点天然接近页位、到达时速度≈0；
> 换成 `OverScroller` 后这个前提不成立。要真正复刻只有上 Compose `HorizontalPager`，
> 而三页是 Android View（`MiuixScrollView` 在 DOWN 就消费触摸），塞进 `AndroidView`
> 会让 pager 收不到横滑手势 —— 所以维持单段弹簧。
- **底栏**：三页（概览/设置/关于）胶囊选中态高亮；切换页时玻璃采样必须
  跟随页面内容实时更新。
- **底栏拖拽物理**（与 SonyPods `DampedDragAnimation` 逐值一致，勿改）：
  手指拖动胶囊指示器时，**目标**按手指增量累加、显示值由
  `spring(1f, 1000f)` 追（约 180ms 收敛）——这份阻尼滞后就是
  「有阻尼、有弹性」的手感；松手吸附最近格、面板越界 rubber band ≤ 4dp
  后 `spring(1f, 300f)` 弹回；按下放大 78/56 用 `spring(0.6/0.7, 250f)`
  过冲回弹。禁止改成直接钉值（无弹性）或降低刚度（变糊）。

### 3.3 智能按键映射：页内过渡与触感

智能按键映射是独立 `SmartKeyActivity`（纯 Compose），有两套「动」的体验必须与主设置页一致：

1. **每个页面都是一个独立的 Activity，转场交给系统**
   - 「设置页 → 智能按键映射」那一次的进入/退出动画是**系统窗口转场**（Android 14+ 的 Shell
     Transition，由 WindowManager 在 Surface 层完成），应用侧没有任何代码参与。
     想让内层页面拿到同样的动画，唯一忠实的做法是**让内层页面也是真正的窗口**：
     `SmartKeyActivity`（首页）/ `SkStatusActivity` / `SkEditRuleActivity` /
     `SkConditionPickerActivity` / `SkAppPickerActivity`，全部继承 `SkActivity`。
   - **禁止在单个 Activity 内用 Compose 手写位移/缩放/压暗去"仿"系统转场**。已经踩过两次：
     先后用 `AnimatedContent`（shared-axis）和自管 `Animatable`（旧页 -18%、63% 亮度、
     新页 48dp 圆角、336ms）去逼近，参数是逐帧反推的近似值，观感始终对不上。
     真机逐帧对比确认：改为 Activity 后，两段转场的形态完全一致（新页左边缘 726→254 推入、
     旧页亮度 250→142 同步压暗；对照系统那段 831→307、229→155）。
   - 由此得到的规范：
     - 页面之间只允许 `startActivity` / `finish()`，不手写导航栈（返回栈由系统管理，
       三级页面按返回键回上一级是天然行为）；
     - 需要回传数据的页面用 `registerForActivityResult`，草稿留在**发起页**自己手里，
       不跨窗口传多份副本；「编辑页 → 添加触发条件 → 选择应用」由条件选择页内部再起一层，
       选完直接把整条条件回传给编辑页，因此一次就落回编辑页，不需要 `popUntil` 之类的补偿逻辑。
     - 页面间不共享内存状态，规则一律经 `RuleRepository`（SharedPreferences + `Settings.Global`）读写。
   - **不要**用整页 `Modifier.shadow` 模拟窗口投影：真机实测该写法会让页面内容不参与合成
     （整页空白）。
   - **禁止用 `BoxWithConstraints` 包 `Scaffold`**：真机实测会打断 Miuix 顶栏的系统栏内边距，
     标题被顶进状态栏（大标题页与二级页整体上移 134px）。
   - 独立窗口冷启有代价：枚举全部应用并解码图标首次要 2–4 秒。`AppPickerScreen` 用进程级
     缓存（`AppCache`）只取一次，并在加载中显示「正在读取应用列表…」，不留白屏。

2. **触感与主设置页同一套**（`Haptics` → `ProvideSkHaptics` → `rememberSkHaptics`）
   - `Haptics` 已从 `:module` 移到 `:glass`（同包名、`public`），Compose 与 Java UI
     共用同一个单例，禁止在 Compose 侧另写一套 Vibrator 波形。
   - 语义固定：列表行 / 下拉选择 / 返回 → `tick()`；主按钮 → `clickPrimary()`；
     次按钮与图标按钮 → `click()`；开关、勾选框 → `toggle()`。
   - **禁止给 Miuix 自带触感的组件再叠一层**：`Switch`、`Checkbox`、`Slider`、
     `RadioButton`、下拉弹窗（`OverlayDropdown*`）内部已经调用 Compose
     `HapticFeedback`（真机表现为 `constant=22` 的 TEXTURE_TICK）。再调 `toggle()`
     会出现「一次点击震两下」（实测两次相隔约 78ms）。行组件 `ArrowPreference` /
     `BasicComponent`、`Button` / `TextButton`、顶栏返回 **不带**触感，这些才需要手动补。
   - 宿主 View 只能挂一次：`ProvideSkHaptics` 在 `Root()` 包一层，各页面用
     `rememberSkHaptics()` 取实例。页面切换时新旧两页同时存在，若每页各自
     `attach`，旧页销毁会把新页正在用的宿主一起解绑，导致后续震动静默失效。
     `Haptics` 内部因此维护宿主列表，并按「最新且 `isAttachedToWindow()`」挑选。

### 3.3.1 规则匹配粒度：**按按压方式**匹配，不是按「键」

`SkEngine.onFirstDown()` 为 short / long / double / repeat **各自**挑一条规则：

```java
for (Rule r : snap.rules) {                 // 已按 priority 降序
    if (!r.enabled || !conditionsMet(r) || !hasMappingForKey(r, key)) continue;
    for (Mapping m : mappingsFor(r, key)) {
        Slot slot = seq.slotFor(m.press);   // short / long / double / repeat
        if (slot.map == null) { slot.map = m; slot.pkgs = collectPkgs(r); }
    }
}
```

- **为什么必须这样**：用户在「规则与按键」里最自然的配法是「短按下一首」「长按上一首」各建
  一条规则，两条都写同一个键。旧实现是「先找第一条命中的规则，只从这一条取四种映射」，
  于是第二条永远轮不到 —— 表现为**短按能用、长按和双击完全没反应**（2026-11-26 真机反馈）。
- **「保持原功能」也占位**：同一按压方式的归属由优先级最高的那条规则决定，显式设为
  「保持原功能」就不能被低优先级规则顶掉；四种全都不接管时直接 `PASS`（不吞键、不补偿，
  音量由系统自己调）。
- **动作的包名跟着规则走**：`Slot` 同时存 `map` 与 `pkgs`，短按来自 A 应用条件、长按来自
  B 应用条件时，各自投递到自己那条规则的包。
- 自检 `SkSelfTest` 有 4 组用例守住此行为（拆分规则短按/长按、拆分规则双击、pass 占位、
  包名跟随），共 **83 例**。
- 真机注入方式（阈值默认 500ms / 300ms 时）：
  `input keyevent --duration 700 25`（长按）、`input keyevent --delay 150 25 25`（双击）。
  注意 `input keyevent --longpress` 只 hold ROM 的 longPressTimeout（小米 14 上约 303ms），
  够不到 500ms 阈值，不能用来测长按。

3. **页面层级与输入块间距**
   - 「添加状态条件」是**独立页面**（`SkConditionPickerActivity`，标题「添加状态条件」），
     从编辑页条件组卡片末尾的「＋ 添加状态条件」行进（`SkAddRow`），禁止在编辑页内联展开
     条件列表——内联展开会把编辑页撑得很长，且缺少明确的前进/返回层级。
     应用类条件继续进三级应用选择器，选完一次直接回编辑页（条件页把整条条件回传后自己 finish）。
   - 名称卡里的两个输入框是 `BasicTextField`（无灰底，见 §3.5），不用 `TextField`；
     这样也不会出现「两块 16dp 内边距的灰底贴在一起」的问题。

### 3.4 概览页

- **状态卡必须检测真实运行状态**：`MainActivity.statusLevel()` 通过
  `ACTION_SET/probe` 主动向 `com.android.systemui` / `com.miui.home` 探测，
  只有探测后写回的心跳才算「已激活」。对外只有两态：
  - 已激活 → 绿 `模块已激活` / `钩子已在系统界面生效`；
  - 未注入（**LSPosed 里未启用模块**或作用域未重启）/ 总开关关闭 →
    红 `模块未启用`，卡片红色。
- **状态卡只有两种状态：`模块已激活` / `模块未启用`**，**禁止再出现「检测中」中性态**。
  每次切页 `refreshPage()` 都会重新探测，若探测在途就把卡片改成中性态，左右滑页就会看到
  「已激活 → 检测中 → 已激活」闪一下（用户明确要求去掉）。
  - 实现：`MainActivity.displayStatus()` 在 `probePending()`（探测发出后 700ms 内）期间
    **沿用上一次结论**，探测回来再更新；进程内还没有结论时（冷启动第一帧）用
    「有没有注入心跳」作初值，避免把已激活的模块先画成红色再翻绿。
  - 状态卡任何状态都不可点击。
- 右上角按钮弹出「重启作用域」对话窗口（miuix Dialog 嵌 Checkbox）：
  父项「全部作用域」三态（全选/全不选/半选）与两个子项联动；
  布局与按钮样式遵循 §2.1。

### 3.5 规则编辑页（新建 / 编辑规则）版式

版面参考**米家「创建 / 编辑自动化」**（首页右上角 `＋` → 自动化），组件一律用 Miuix，
数值回到 §2 与 `miuix-components.md`。截图见 `docs/screenshots/sk-edit-*.png`。

| 区块 | 规格 |
|---|---|
| 顶栏 | `TopAppBar` 大标题（`SmartKeyScaffold(largeTitle = …)`），标题「新建规则 / 编辑规则」 |
| 名称卡 | 白底卡内直接写字：`BasicTextField`（17sp 名称 + 13sp 说明占位），右侧 22dp 圆形 × 清空（`onSurfaceVariantSummary` α0.14 底 + 12dp `Close`） |
| 分组标题 | `SkSectionTitle` = `SmallTitle` 原度量（14sp Bold / `onBackgroundVariant` / (28dp, 8dp)） |
| 条件组标题 | 同上 + 10×16 折角（`Basic.ArrowRight` 转 90°）；点击弹 `OverlayDropdownPopup` 切「全部状态 / 任一状态」 |
| 信息行 | `SkItemRow`：图标 26dp（`onSurfaceVariantSummary`）→ 14dp → 标题 17sp → 副标题 13sp → 10×16 箭头；上下各 12dp |
| 添加行 | `SkAddRow`：26dp 圆底（`primary` α0.12）+ 16dp `Add` + 16sp `primary` 文字 |
| 分隔线 | `SkRowDivider` 0.75dp / α0.18，缩进 56dp（= 16 + 26 + 14），卡片内其它行缩进 16dp |
| 底部按钮 | 整行「保存」：Scaffold `bottomBar` 槽，槽高 76dp（52dp 按钮 + 上下 12dp），`primary` 底 + `onPrimary` 字 |

区块语义（与数据模型逐一对应）：

1. `当以下情况发生` = **按键触发**（`KeyMapping.key` + `press`）；
2. `且满足全部 / 任一状态` = 规则条件（`Rule.conditions`，标题文案即 `Logic.AND / OR`）；
3. `就执行` = 该映射的动作（`KeyMapping.action`）；
4. `规则设置` = 启用开关 + 优先级（米家这一组的「否则就执行」我们没有 else 分支，换成规则属性）。

交互约定：

- **触发与动作是同一个 `KeyMapping` 的两端**，两张卡按下标一一对应，副标题互相点名
  （触发「按下即触发」/ 动作「由音量加键 · 短按触发」）。两个「添加」按钮做的是同一件事：
  追加一条映射（即一组「触发 → 动作」）。
- **删除不在行内**（对齐米家：行只负责进入编辑）：点行打开对话框，删除在对话框左下角，
  与「完成 / 更换应用」并排。应用类条件在对话框右下角是「更换应用」。
- 对话框必须是 **Miuix 默认贴底形态**：不传 `largeScreen`、不改 `outsideMargin`
  （用户明确要求「从底部弹出、用 miuix 默认贴底」）。居中形态与自定义 `outsideMargin`
  都已试过并被否。
- 因为底部还有整行「保存」，抽屉贴底会从按钮中间切断、露出一截蓝底。解决办法是
  **弹窗打开时把按钮收起**（`EditRuleScreenContent(onOverlayOpenChange)` →
  `bottomBar` 里 `if (!overlayOpen)`），槽位高度固定 76dp，所以页面不会上下跳。
- **「保存」必须在 Scaffold 的 `bottomBar` 槽里，不能浮在 Scaffold 外的 Box 里**：
  弹窗浮层由 Scaffold 内部的 `MiuixPopupHost` 渲染，外层按钮会画在弹窗**之上**，
  把对话框底部的「删除 / 完成」直接挡住（真机踩到过）。
- 名称留空直接保存时自动填「未命名规则」，不留「按了没反应」的死按钮。

### 3.6 应用选择页（`SkAppPickerActivity` / `AppPickerScreen`）

列表固定三段，只有有内容时才出现：

| 段 | 何时出现 | 说明 |
|---|---|---|
| `已选择` | **仅**「更换应用」进入（`EXTRA_PIN_SELECTED=true`） | 把进来那一刻已勾选的应用置顶，方便直接改当前那个 |
| `推荐` | 有媒体类应用时 | 行尾带「推荐」标记（主色 12% 底 + 主色字，见 `SkRecommendBadge`） |
| `全部应用` | 前三段有任一存在时 | 兜底分区标题 |

- **置顶只在「更换应用」时做**：正常「添加状态条件 → 选应用」不置顶——否则一进页面
  就在最前面堆一组已勾选项，反而打乱找应用的视线（用户明确要求）。
- **置顶分组的成员用进来那一刻的集合定格**（`remember { selected.toSet() }`），
  但 `checked` 跟着实时选中集合走。这样勾掉某项时行不会在手指下跳走，勾选状态又是准的。
- 分组标题与卡片左边缘对齐（16dp 卡片 + 0dp 额外缩进），不用 `SmallTitle` 默认的 28dp。
- 推荐判定见 `AppCatalog.recommended()`：`APP_MUSIC` 启动分类 ∪ 已知包名 ∪
  （`MediaBrowserService` 且非系统应用）。最后一条必须排除系统应用，否则「蓝牙」「系统搜索」
  也会被当成媒体应用（模拟器实测）。
- **下拉刷新用 Miuix 默认色（灰）**：不要传 `color = primary`——那会让指示器变成主题蓝，
  和系统其它 App 的下拉刷新不是一个观感（用户提出过）。
- **条件行/规则摘要显示应用名称，不显示包名**：`Condition.appNames(context)` 走
  `AppCatalog` 的包名→名称索引；超过两个应用收敛成「A、B 等 N 个应用」。

### 3.7 应用清单加载（`AppCatalog`）

选择器「等几秒才出列表」的根因与修法（实测数据见 §5.7）：

- 旧实现是**进程内 List 缓存 + 串行重活**：`getInstalledPackages(GET_META_DATA)` →
  每包 `loadLabel` → 每包 `loadIcon`，全部做完才显示第一行。`loadIcon` 要逐个打开别的
  APK 的资源流解码，几百个应用就是秒级；而且纯内存缓存只在**同一进程**的第二次访问生效。
- 现在分三层：**磁盘快照**（`filesDir/smartkey_apps.json`，只存包名/名称/是否系统应用）
  → **后台重扫**（`SkActivity.onCreate` 里 `AppCatalog.prewarm()` 幂等触发）→
  **图标懒加载**（只给可见行取，`LruCache(192)` 命中即用，未命中给同尺寸占位块防跳行）。
- 因此列表首帧不再依赖枚举：快照 10ms 级，重扫在后台完成；下拉刷新才强制重扫。

### 3.8 设置页：三个功能模块 + 各自总开关

设置页按**功能模块**重新分组，每块最上面就是该模块的总开关，开关下面是这个模块的全部设置项：

| 模块 | 总开关 | 配置键 | 内容 |
|---|---|---|---|
| 按键映射 | 启用按键映射 | `Settings.Global: hyperhand_sk_master` | 「规则与按键」入口（副标题带规则数量） |
| 单手模式增强 | 启用单手模式增强 | `Cfg.enabled`（`oh_tuner_enabled`） | 常用四项（触发模式 / 模糊壁纸背景 / 下滑展开面板 / 显示高级参数）+ 高级参数内五段（识别判定 / 触发区 / 外观与动效 / 面板手势与互斥 / 算法细节） |
| 快捷菜单滑选 | 启用长按滑选菜单项 | `Cfg.slideEnabled` | 滑选判定夹角 / 命中容差 / 起判位移 |

- **三个开关互不影响**：按键映射写 SmartKey 自己的 Global 键；单手模式用原来的
  `enabled`；滑选用 `slideEnabled`（这个开关本来就是这个功能的开关，现在升格为模块总开关）。
- **关闭只隐藏、不清值**：`bodyBox` 整体 `GONE`，各行的值仍在 View 上，`applyTo()`
  照常把全部参数写回广播，所以关掉再打开、重启应用都不会丢配置。
- **写入失败必须回退开关**：写 `Settings.Global` 需要 `WRITE_SECURE_SETTINGS`，没有 root 时
  写入会失败；此时把开关拨回去、内容区折回，并在状态行说明原因，绝不能让界面显示「已开启」
  而 Hook 侧读到的还是关闭。
- 从「规则与按键」页返回时，`refreshAnimInfo()` 会重新同步按键映射总开关（那边也能改它）。
- **一个功能 = 一张卡片（2026-11-26 用户要求）**：模块总开关是该卡的**第一行**，开关下面是
  同一张卡内的设置项容器（`keyMapBox` / `oneHandBox` / `slideBox`，`LinearLayout` +
  `MATCH_PARENT × WRAP_CONTENT`）。关闭开关时容器高度收到 0，**卡片整体变矮**，而不是
  把「开关卡」和「设置卡」两张卡分开。
  - 以前是「开关一张卡 + 设置一张卡」，中间还夹 `Ui.section` 大标题，整页被切成几段；
    现在同一张卡内部用 **`Ui.cardSection`（14sp Bold、section 色、左右 16dp、上 16dp 下 4dp）**
    分段。
  - **常用 / 高级两层（2026-11-27 用户要求）**：单手模式卡原先 34 行平铺，滑三四屏才看到
    「模糊壁纸」这类常用项。现在总开关下**只留 4 行常用**：触发模式、模糊壁纸背景、
    下滑展开面板、显示高级参数（`swAdvanced`，默认关）；其余全部并入
    `advancedBox`（`GONE`），展开后按 **识别判定 / 触发区 / 外观与动效 /
    面板手势与互斥 / 算法细节** 五段排列。
  - **「显示高级参数」只切视图、不碰配置**：收起只是把 `advancedBox` 设 `GONE`，
    各行仍持有原值，`submit` 照常按原值写回 —— 收起 ≠ 恢复默认。展开状态用
    进程内静态位 `sAdvancedOpen` 记忆：**冷启动一律收起（默认关）**，但切主题等
    原因重建 Activity 时不会被莫名收回。
  - 三个模块的总开关标题统一「**启用** + 功能名」（启用按键映射 / 启用单手模式增强 /
    启用长按滑选菜单项），不要写成「长按滑选菜单项」这类缺动词的名词短语。
  - `Ui.section`（28dp 缩进的大标题）**只用于卡片外**（三个模块名），卡内一律用 `cardSection`，
    这样分段标题与行文字的左边界都在 16dp，不会出现「两种缩进线」。
  - 卡内不要再给行加 `topMargin`：`Ui.cardLp()` 是**卡片之间**的 12dp，卡内加会出现假间距；
    行自身的 16dp/10dp 内边距就是唯一间距来源。
- **折叠动效 = LSPosed 的「临界阻尼弹簧收缩」**（`SettingsPage.expand()`）：
  - 只有**高度**在动（`ValueAnimator` 每帧写 `lp.height`），内容保持 `alpha = 1`
    被上边缘裁切 —— 对 LSPosed「关闭悬浮底栏 → 液态玻璃那行消失」逐帧比对，它就是
    in-place 收缩、**没有透明度淡出**，150ms 内收掉 90%；
  - 曲线统一用临界阻尼弹簧的解析解 `f = 1 − (1 + ωt)·e^(−ωt)`，
    `ω = 20`（= √400，Compose `Spring.StiffnessMediumLow`）、300ms，**三个模块参数相同**；
  - **只对「屏幕内可见的那段高度」做动画**（`expand(v, anchor, show)` 用锚点开关行底边
    量出可见高度 `visible`）：
    - 真机打点：单手模式那张卡完整高度 **6489px**，视口 2670px、开关底边在 y≈1272，
      **屏幕上只看得到 1398px**。此前把 6489px 全程当动画距离，前 250ms 裁的全是
      屏幕外的 5091px（用户看不到任何变化），最后才把屏幕内 1398px 一口气抽走 ——
      这就是「只有单手模式这折叠动画奇怪、另外两张卡正常」的真正原因。
      按键映射（56px）、滑选（168px）两张卡整张在视口内，所以全程可见、观感正常。
    - 现在动画开始前把屏幕外那段**一次性砍掉**（那部分本就在屏幕外，用户看不到），
      弹簧只走 `0 ↔ visible`；动画结束再把 `lp.height` 设回 `WRAP_CONTENT`
      恢复完整高度。真机日志：`t=43ms h=1100 → 126ms h=396 → 243ms h=63 → 292ms h=28`，
      从第一帧起每帧都是屏幕内可见位移。
    - **不要退回「按容器总高做动画 + 给长容器换慢曲线」**：换 ω=10/480ms 只是把
      「卡住」的时间拉长，屏幕上仍然前 200ms 无变化，用户照样觉得奇怪。
  - 结束回 `WRAP_CONTENT`，否则之后数值行变高时卡片不会自适应；
  - 快速连点开关：旧动画 `cancel()` 后按当前实际高度重起（`View#getTag` 存 animator），
    不能靠 `isCancelled()`（`Animator` 没这个公开方法）。
  - 真机日志实测：`alpha` 恒 1.0、帧间隔 8.4ms（120Hz 满帧）、224ms 到 f=0.94。
  - 每帧都会 `requestLayout` 整个页面（设置页约 7000px 高、200+ View），模拟器
    （SwiftShader）上只能跑到个位数帧；真机 measure/draw 都在 5ms 内，不受影响。

---

## 4. 文案与格式

- 版本号格式：`版本名(版本号)`，与参考一致（如 `1.8.1(23)`）。
- 对话窗口按钮：主动作在**右**、取消在**左**；主按钮用 primary 色。
- 日期时间：`yyyy-MM-dd HH:mm`。

### 4.1 设置项文案

| 位置 | 规则 |
|---|---|
| 行标题 | 名词短语，2~6 字；如「触发距离」「展开方向」，不用「下拉多少算数」这类句子 |
| 行摘要 | 一句话说清**作用对象 / 触发条件 / 取值含义**，≤ 18 字，**不加句末句号** |
| 数值项 | 统一「0 表示…」「越大越…」句式，例：「0 表示保持系统默认」「越大越模糊，0 为不模糊」 |
| 选项说明 | 必须单行，只描述差异、不重复行摘要；下拉说明统一放 `Cfg` 常量，页面里不要再抄一份 |

禁止项：

- 营销词与主观判断：「一键」「轻松」「推荐」「最适合」「秒开」。
- 口语：「算数」「手抖」「糊」「抬手就开」「没…就当…」。
- 借势命名：「iPhone 式」；需要说明手感时写客观差异（「速度与距离综合判定」）。
- 中英混排不统一：`Dock` 统一首字母大写（不写 `dock`）；不把「屏幕高度」简写成「屏高」。
- 术语另立：只用**单手模式 / 触发区 / 通知中心 / 控制中心 / 面板 / 作用域**，
  不写「一手模式」「下拉区」「悬浮栏」。

> 反例 → 正例：「下滑多少算数，防误判成下拉」→「判定为下滑所需的最小位移」；
> 「没拖到底边就当页面滑动」→「未拖到底边时按页面滚动处理」；
> 「滑到菜单项抬手即打开，改后重启桌面生效」→「滑到菜单项抬起即选中，需重启桌面」。

---

## 5. 性能约束（改动 UI 时不得回退）

1. **动画节流 60fps**：背景渐变动画最短帧间隔 16ms（对齐 SonyPods
   `minDeltaNanos = 1s/60`）。
2. **alpha ≤ 0 停动画**：淡出到不可见立即停止动画任务。
3. **hero 玻璃必须逐帧重录，禁止阈值 / 间隔 / 隔帧**：SonyPods
   `textureBlur(backdrop)` 每帧从 LayerBackdrop 采样，颜色连续。真机 60fps 静止录屏
   实测：加「RGB 累计差 ≥ 4 + 64ms 间隔」后，logo 区域出现单帧 8.4 级亮度跳变、
   最长连续 4 帧完全不变的阶梯，同屏纯背景区只有 1 次 3.2 级跳变 —— 这就是
   「logo 渐变卡顿」。**降频换性能会直接制造可见阶梯**，一律禁止。
4. **顶栏模糊走 RenderNode + RenderEffect**（2026-11-25 用户要求恢复，见 §2.2）：
   - 只录**条体那一截**（状态栏 + 52dp），不要把整页录进节点；
   - 录制按 16ms 节流，`RenderNode` 只记显示列表不栅格化，成本主要是 GPU 的 blur；
   - **禁止**回到「软件位图 + 32 tap 高斯」：一是页面里有 Compose/RuntimeShader，软件画布
     会抛 `Software rendering doesn't support RuntimeShader`；二是它每帧要把内容软件重绘
     进约 1200×650 位图再走 AGSL，是当时滚动最大的一笔开销（去掉后 draw 3.14 → 2.54ms）。
   - 模拟器（SwiftShader）上 RenderEffect 退化到软件，50th 会从 ~48ms 涨到 ~100ms，
     这是模拟器特性，不能拿来判断真机表现。
5. **软件画布不画 RenderNode**（见 §2.4 液态底栏）。
6. **测量方法（这块机器的面板是 LTPO，会自己切刷新率）**：
   - 只 `dumpsys gfxinfo` 看 `50th / 90th percentile` 会把 vsync 等待混进渲染耗时，
     60Hz 下 15ms 看着像「很慢」，其实每帧都命中了 vsync。
   - 要先确定这段时间的 vsync 周期：`dumpsys gfxinfo <pkg> framestats` 里
     `FrameInterval` / `IntendedVsync` 的差分（8.3ms = 120Hz、16.7ms = 60Hz），
     再看 `Number Missed Vsync` / `Janky frames`（新指标按当前刷新率算 deadline）。
   - 参考值（小米 14，滚动 5 个来回，面板 120Hz）：开启玻璃背板 50th ≈ 18ms、
     关掉背板 50th ≈ 14ms —— 玻璃每帧大约 4ms，是 120fps 的主要阻碍，
     但**不要**因此把刷新率降回 60fps（§2.4）。
   - 短页面/内容不足一屏时，底栏背后是纯背景，滚动时玻璃本来就不会变，
     别拿它判断「是否实时刷新」。

### 5.7 应用选择器的加载（改这块必须看）

旧实现在选择器里**同步做完三件事才出第一行**，真机实测秒级：

| 步骤 | 代价 |
|---|---|
| `getInstalledPackages(GET_META_DATA)` | 几百个 `PackageInfo`，还要解析每包的 meta-data |
| 每包 `loadLabel` | 逐个解析资源表 |
| 每包 `loadIcon` | **最贵**：逐个打开别的 APK 的资源流并解码 bitmap；同时几百个 Drawable 常驻内存 |

再加上它只是**进程内**缓存，`从设置页重新进入智能按键映射`（新进程）后的第一次进选择器
又会重算一遍——这就是「明明加过缓存还是要等」的原因。

现在（`AppCatalog`）的分段实测（模拟器 Pixel_4_API_35，245 个包）：

| 指标 | 数值 |
|---|---|
| 磁盘快照读取（主线程，首帧前） | **10ms**（245 条） |
| 后台重扫（不含图标） | 223ms |
| 冷进程直接拉起选择器 `TotalTime` | 2256ms（**大头是进程冷启 + Compose/Miuix 类加载**，与列表无关） |

复测命令：

```bash
adb logcat -s HHApps            # "disk snapshot: N apps in Xms" / "scan: N apps in Xms"
adb shell am start -W -n io.github.zpan2327.hyperhand/.smartkey.SkAppPickerActivity
```

**约束**：不要再把图标放回「一次性全量加载」，也不要在组合期做 `PackageManager` 查询
（推荐集合已在 `prewarm()` 里算好并缓存）。

---

## 6. 文件映射

| 关注点 | 文件 |
|---|---|
| 对话窗口 | `MiuixDialog.java` |
| 顶栏（不透明模糊条，无底边渐隐） | `TopBar.java`（`ProgressiveBlur.java` 已删除） |
| 关于页 hero | `AboutPage.java`（AppIcon / GlassText）/ `GlassNode.java` |
| 图标路径 | `Icons.java`（`glyph()` 返回蒙版用路径） |
| 调试入口 | `AboutPage.showDebugPage()` / `MainActivity.toggleDebug()` |
| 设置页 | `SettingsPage.java`（无调试分组） |
| 液态底栏 | `HyperGlassBarView.kt`（`AbstractComposeView` 宿主）/ `LiquidGlassNavigationBar.kt` / `ViewBackdrop.kt`（glass 模块） |
| 智能按键映射 | `glass/.../smartkey/SkActivity.kt`（基类）/ `SmartKeyActivity.kt` + `Sk*Activity.kt`（各页独立窗口）/ `ui/*Screen.kt` |
| 规则编辑页版式（分组标题 / 信息行 / 添加行 / 选择对话框） | `glass/.../smartkey/ui/SkRows.kt` + `EditRuleScreen.kt` |
| 应用选择页（分组 / 推荐标记 / 置顶） | `glass/.../smartkey/ui/AppPickerScreen.kt` + `SkAppPickerActivity.kt` |
| 应用清单缓存（磁盘快照 / 后台重扫 / 图标 LRU） | `glass/.../smartkey/data/AppCatalog.kt` |
| 触感 | `glass/src/main/java/io/github/zpan2327/hyperhand/Haptics.java` / `smartkey/ui/SkHaptics.kt` |
| 版本号 | `module/build.gradle.kts`（`defaultVersionName` + `minVersionCode` 下限） |
