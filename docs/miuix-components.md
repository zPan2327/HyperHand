# miuix (top.yukonga.miuix.kmp) 组件目录

> 基于本地源码 `C:/Users/zpan2/AppData/Local/Temp/refs/miuix-main`（Kotlin Multiplatform，MIUI 风格组件库）逐模块梳理。
> 用途：作为后续开发（HyperHand 对齐）的参考目录。所有路径为该仓库内绝对路径的模块相对缩写：`miuix-xxx/src/<sourceSet>/kotlin/top/yukonga/miuix/kmp/...`。
> 记录约定：「组件 → 子组件/参数槽位 → 样式规范（具体数值） → 交互逻辑（状态/动画/回调）」。数值均为源码默认值。

## 0. 模块总览

| 模块 | 职责 | 主要 sourceSet |
|---|---|---|
| miuix-core | 图标入口 `MiuixIcons`、平台工具 | commonMain + 各平台 |
| miuix-ui | 基础组件（basic）、主题、弹层（overlay/window/layout）、动画、工具 | commonMain + 各平台 |
| miuix-preference | 设置项组件（preference）、下拉菜单（menu/popup） | commonMain |
| miuix-blur | 背景模糊管线（textureBlur / progressiveTextureBlur / Backdrop） | commonMain + android/skiko |
| miuix-squircle | 超椭圆圆角（squircleSurface/Clip/Border/Path） | commonMain + android/skiko |
| miuix-material | 玻璃材质（GlassMaterial/EdgeStroke/RuntimeShader） | commonMain + android/skiko |
| miuix-glass | 玻璃拟态组件（GlassDialog/GlassTopAppBar/GlassPopup…） | commonMain |
| miuix-glass-icons | 玻璃图标（MiuixIcons.Glass.*，176 个图标文件） | commonMain |
| miuix-icons | 扩展图标（MiuixIcons.*，156 个图标文件 × 5 字重） | commonMain |
| miuix-nav | 导航栈/转场/预测性返回手势 | commonMain + android/skiko |
| miuix-window | 桌面窗口壳（MiuixWindow/标题栏/平台适配 Win/macOS/Linux） | desktopMain |

---

## 1. miuix-core（top.yukonga.miuix.kmp）

- **MiuixIcons**（`icon/MiuixIcons.kt`）
  - 子对象：`Glass { Light/Normal/Regular/Medium/Demibold }`、`Basic`、`Light/Normal/Regular/Medium/Demibold`。
  - 扩展属性由 miuix-icons（`icon/extended/*`，156 文件）与 miuix-glass-icons（`icon/glass/*`，176 文件）提供：`val MiuixIcons.Add / MiuixIcons.Light.Add / MiuixIcons.Regular.Add ...`，`ImageVector` 默认画布 24dp（Glass 为 28dp）。
- **Platform / Utils**（`utils/Utils.kt`）
  - `enum class Platform`；各平台 `getRoundedCorner()`（屏幕圆角，供 Dialog 底部贴靠圆角推导）、`getCornerRadiusBottom(context)`（androidMain）等。

---

## 2. miuix-ui（top.yukonga.miuix.kmp）

### 2.1 basic 包（`miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/`）

- **Button / TextButton / ButtonDefaults / ButtonColors / TextButtonColors**（`Button.kt`）
  - 结构：`Box(squircleSurface(color, cornerRadius).clickable(indication)) → Row(insideMargin, 居中) → content(RowScope)`；`TextButton` = `Button` + `Text(style = textStyles.button)`。
  - 形状/indication：`squircleSurface`（miuix-squircle），`indication: Indication? = LocalIndication.current`，`interactionSource` 可注入。
  - 样式（ButtonDefaults）：`MinWidth = 58.dp`、`MinHeight = 40.dp`、`CornerRadius = 16.dp`、`InsideMargin = PaddingValues(horizontal=16.dp, vertical=13.dp)`。
  - 颜色：
    - `buttonColors()`：底 `secondaryVariant` / 字 `onSecondaryVariant`（禁用 `disabledSecondaryVariant` / `disabledOnSecondaryVariant`）。
    - `buttonColorsPrimary()`：底 `primary` / 字 `onPrimary`（禁用 `disabledPrimaryButton` / `disabledOnPrimaryButton`）。
    - `textButtonColors()`：同 buttonColors 的默认（灰底灰字）。
    - `textButtonColorsPrimary()`：底 `primary`、字 `onPrimary`；disabled 底 `disabledPrimaryButton`、字 `disabledOnPrimaryButton`。
  - 交互：`Role.Button` 语义；enabled=false 时用 disabled 色且不可点；文字样式 `MiuixTheme.textStyles.button`（17sp）。
- **Card / CardDefaults / CardColors**（`Card.kt`）
  - 结构：`BasicCard → Box(squircleSurface(color, cornerRadius)) → Column(insideMargin)`；两个重载：不可交互版 / 可交互版。
  - 样式：`CornerRadius = 16.dp`、`InsideMargin = PaddingValues(0.dp)`、颜色 `surfaceContainer` / 内容色 `onSurfaceContainer`。
  - 交互（可交互版）：`combinedClickable(onClick, onLongPress)`；`pressFeedbackType: None / Sink / Tilt`（`PressFeedbackType`，`utils/PressFeedback.kt` 的 `SinkFeedback`/`TiltFeedback`）；`showIndication` 控制是否显示 `LocalIndication`；`holdDownState` 经 `HoldDownObserver` 注入按下态；`LocalContentColor` 下传。
- **Checkbox / CheckboxDefaults / CheckboxColors**（`Checkbox.kt`）
  - 结构：`Box(requiredSize(26.dp).pressable(SinkFeedback).clip(CircleShape).drawWithCache { drawCircle(bg); drawTrimmedCheckmark(...) })`。
  - 样式：整体 **26dp × 26dp 圆形（CircleShape）**；勾线宽 `size.width * 0.09`，`StrokeCap.Round`；勾路径视口 23f（start 5,9.4 → mid 10.3,14.9 → end 17.9,5.1）。
  - 颜色（CheckboxDefaults.checkboxColors）：选中底 `primary` / 勾 `onPrimary`；未选底 `secondary` / 图形 `secondary`；禁用 `disabledPrimary` / `disabledOnPrimary` 等。
  - 交互（含父子三态）：`state: ToggleableState`（**On / Off / Indeterminate**，Indeterminate 用于父子复选的半选父项），`triStateToggleable` 语义；回调 `onClick: (() -> Unit)?`（null 时只读展示）。状态动画（updateTransition）：
    - 底色/前景色 tween 300ms FastOutSlowIn；
    - 勾透明度：出现 10ms / 消失 150ms；
    - 勾 trimStart：出现 0.186（200ms）/ 消失 0.1（keyframes 300ms）；trimEnd：出现 keyframes 0.85@200ms → 0.803@300ms / 消失 0.1；
    - **Indeterminate 十字形变**：`crossCenterGravitation` 0→1（200ms），勾的三个控制点向中心引力收缩成短横/十字；
    - 按压反馈 `SinkFeedback(sinkAmount=0.85f, spring(0.99f, 986.96f))`；点击震动：Off→ToggleOn、On→ToggleOff、Indeterminate→SegmentTick。
- **SmallTitle / SmallTitleDefaults**（`SmallTitle.kt`）
  - 结构：`Text(text, style = textStyles.subtitle)`。
  - 样式：字号 `subtitle = 14sp` + `FontWeight.Bold`；颜色 `onBackgroundVariant`；`InsideMargin = PaddingValues(28.dp, 8.dp)`。
- **BasicComponent / BasicComponentDefaults / BasicComponentColors**（`Component.kt`，preference 组件的底座）
  - 槽位：`title`、`summary`、`startAction`、`endActions(RowScope)`、`bottomAction`；`onClick`/`holdDownState`/`enabled`。
  - 样式：`InsideMargin = PaddingValues(16.dp)`；title 色 `onBackground`（禁用 `disabledOnSecondaryVariant`）、summary 色 `onSurfaceVariantSummary`；bottomAction 前 `Spacer(8.dp)`。
- **Scaffold**（`Scaffold.kt`）
  - 槽位：`topBar`、`bottomBar`、`floatingActionButton`（`FabPosition.End`）、`floatingToolbar`（`ToolbarPosition.BottomCenter`）、`snackbarHost`、`popupHost = { MiuixPopupHost() }`、`content(PaddingValues)`。
  - 样式：`containerColor = surface`；`contentWindowInsets = systemBars ∪ displayCutout`（被 topBar/bottomBar 消费的部分自动排除）；FAB 间距 `FabSpacing = 12.dp`，浮动工具栏间距 `FloatingToolbarSpacing = 4.dp`。
  - 交互：内部提供 `LocalPopupStates/LocalDialogStates/LocalRootDialogStates/LocalRootPopupStates`（overlay 弹层/对话框宿主注册）；嵌套 Scaffold 时根宿主接管（`renderInRootScaffold` 语义）。
- **Surface / SurfaceDefaults**（`Surface.kt`）：基础容器；`ShadowElevation = 0.dp`。
- **Text**（`Text.kt`，4 个重载）：MIUI 排版封装，样式来自 `MiuixTheme.textStyles`。
- **Divider（HorizontalDivider / VerticalDivider）/ DividerDefaults**（`Divider.kt`）：`Thickness = 0.75.dp`。
- **Dropdown（DropdownImpl / DropdownEntry / DropdownItem / DropdownColors / DropdownDefaults）**（`Dropdown.kt`）— 详见 §2.4 菜单条目。
- **Badge / BadgedBox / BadgeDefaults**（`Badge.kt`）：`Size = 6.dp`、`LargeSize = 16.dp`、带内容时水平内边距 4.dp、偏移 (12.dp, 14.dp)。
- **BreadcrumbBar / BreadcrumbItem / BreadcrumbBarDefaults / BreadcrumbBarColors**（`BreadcrumbBar.kt`）：`InsideMargin = (12.dp, 8.dp)`、`ItemHeight = 32.dp`、`ItemHorizontalPadding = 10.dp`、`ItemMaxWidth = 160.dp`；`joinToPath()` 扩展。
- **ColorPalette**（`ColorPalette.kt`）：色板组件（描边 6.dp、光晕 2.dp）。
- **ColorPicker**（`ColorPicker.kt`）：`ColorPicker`、`HsvColorPicker`、`OkHsvColorPicker`、`OkLabColorPicker`、`OkLchColorPicker` 及各自分量 Slider（Hue/Saturation/Value/Alpha、Lightness/Chroma/a/b…）；`Modifier.drawCheckerboard`；指示器 20.dp、滑条高 26.dp。底层色彩空间：`color/space/{Hsv,OkHsv,OkLab,OkLch}.kt`、`color/core/Transforms.kt`、`color/api/Extensions.kt`（`Color.toOkLab()/toHsv()/toOkLch()`）。
- **FloatingActionButton / FloatingActionButtonDefaults**（`FloatingActionButton.kt`）：`MinWidth/MinHeight = 60.dp`、`ShadowElevation = 4.dp`。
- **FloatingToolbar / FloatingToolbarDefaults**（`FloatingToolbar.kt`）：`CornerRadius = 50.dp`、`OutSidePadding = (12.dp, 8.dp)`、阴影可配。
- **Icon**（`Icon.kt`，4 重载）：默认尺寸 `24.dp`，支持 ImageVector/Bitmap/Painter。
- **IconButton / IconButtonDefaults**（`IconButton.kt`）：`MinWidth/MinHeight = 40.dp`、`CornerRadius = 40.dp`（圆形）；`holdDownState`、`backgroundColor`（默认 Unspecified → 主题）。
- **ListPopup（ListPopupColumn / ListPopupContent / PopupPositionProvider / ListPopupDefaults / rememberListPopupLayoutInfo / Modifier.popupClipReveal）**（`ListPopup.kt`）
  - 弹窗样式（ListPopupContent）：**圆角 16.dp（squircle）**、背景 `surfaceContainer`；宽度自适应最宽项，钳制 **200.dp ~ 288.dp**（前 8 项参与测宽）；`MinPopupHeight = 50.dp`。
  - 动画：`FractionAnimationSpec = spring(0.82, 362.5)`（缩放/clip-reveal）、进入 alpha 200ms / 退出 150ms、背景 dim 进 300ms / 出 150ms（SinOutEasing）、手势复位 spring(0.82, 362.5)。
  - 定位：`dropdownPositionProvider(verticalMargin = 8.dp, horizontalMargin = 0.dp)`（下方优先，空间不足翻上方）；`Align` 支持 Start/End/TopStart/…；变换原点吸附锚点角（`popupClipReveal` 用 squircle 裁切带）。
- **NavigationBar / NavigationBarItem / NavigationBarDefaults / NavigationBarItemColors**（`NavigationBar.kt`）：`ItemHeight = 64.dp`、`IconSize = 26.dp`、`LabelFontSize = 12.sp`、`IconTopPadding = 8.dp`、`BottomPadding = 8.dp`。
- **FloatingNavigationBar / FloatingNavigationBarItem / FloatingNavigationBarDefaults / NavigationItem**：外侧水平边距 36.dp、阴影 1.dp、水平内边距 12.dp、项间距 12.dp、`IconSize = 28.dp`、`IconPadding = 10.dp`。
- **NavigationRail / NavigationRailItem / NavigationRailDefaults / NavigationRailState / rememberNavigationRailState**（`NavigationRail.kt`）：折叠 `MinWidth = 80.dp`、展开 `ExpandedWidth = 240.dp`、图标 28.dp、标签 12.sp/展开 16.sp、展开项圆角 16.dp 等。
- **NumberPicker / NumberPickerDefaults / NumberPickerColors**（`NumberPicker.kt`）：`ItemHeight = 45.dp`。
- **ProgressIndicator（LinearProgressIndicator / CircularProgressIndicator / InfiniteProgressIndicator / ProgressIndicatorDefaults / ProgressIndicatorColors）**（`ProgressIndicator.kt`）：线性高 6.dp；圆形描边 4.dp、尺寸 30.dp；无限指示器描边 2.dp、轨道点 2.dp、尺寸 20.dp。
- **PullToRefresh / PullToRefreshState / rememberPullToRefreshState / PullToRefreshDefaults**（`PullToRefresh.kt`）：指示圆 `circleSize = 20.dp`。
- **RadioButton / RadioButtonDefaults / RadioButtonColors**（`RadioButton.kt`）：选中色 `primary`（禁用 `disabledPrimary`）。
- **ScrollBar（VerticalScrollBar / HorizontalScrollBar / ScrollBarAdapter / ScrollBarColors / ScrollBarDefaults）**（`ScrollBar.kt`）：`ThumbWidth = 3.64.dp`、`EndPadding = 3.46.dp`、`ThumbMinLength = 36.dp`、`TouchTargetWidth = 48.dp`、`DragThumbWidth = 6.dp`；`@ExperimentalScrollBarApi`。
- **SearchBar / InputField / SearchBarDefaults**（`SearchBar.kt`）：`InsideMargin = DpSize(12.dp, 0.dp)`、`InputFieldMinHeight = 45.dp`、`InputFieldFontSize = 17.sp`、前导图标 start 16.dp/end 8.dp、尾随 start 8.dp/end 16.dp。
- **Slider / VerticalSlider / RangeSlider / SliderDefaults / SliderColors**（`Slider.kt`）：`MinHeight = 28.dp`、关键点半径 `KeyPointRadius = 3.855.dp`。
- **Snackbar / SnackbarHost / SnackbarHostState / SnackbarData / SnackbarVisuals / SnackbarDefaults / SnackbarColors**（`Snackbar.kt`）：`CornerRadius = 16.dp`、`InsideMargin = 12.dp`、`OuterPadding = (12,12,8,0)`、动作按钮圆角 50.dp；Host 底部留白 12.dp。
- **Switch / SwitchDefaults / SwitchColors**（`Switch.kt`）：轨道 49×28.dp，滑块 20.dp（offset 4.dp ↔ 25.dp，spring(damping 0.7, stiffness 987)；缩放 spring(0.6, 987)）；选中轨道 `primary`、滑块 `onPrimary`；未选轨道 `secondary`、滑块 `onSecondary`。
- **TabRow / TabRowWithContour / TabRowDefaults / TabRowColors**（`TabRow.kt`）：`TabRowHeight = 42.dp`、带等高线高 45.dp、圆角 12.dp（带等高线 8.dp）、等高线 padding 5.dp。
- **TextField / TextFieldDefaults / TextFieldColors**（`TextField.kt`，3 重载）：`CornerRadius = 16.dp`、`InsideMargin = DpSize(16.dp, 16.dp)`、聚焦边框 2.dp、浮动标签 10dp/常态 17dp；背景 `secondaryContainer`、标签 `onSecondaryContainer`、边框 `primary`。
- **Tooltip（TooltipState / rememberTooltipState / TooltipBox / TooltipScope.PlainTooltip / TooltipScope.RichTooltip / RichTooltipBox / RichTooltipColors / TooltipDefaults）**（`Tooltip.kt`）。
- **TopAppBar / BlurTopAppBar / SmallTopAppBar / TopAppBarState / rememberTopAppBarState / MiuixScrollBehavior / ScrollBehavior / TopAppBarDefaults**（`TopAppBar.kt`）— 详见 §2.2。
- **Overlay/Window 弹层**（`overlay/`、`window/`）— 详见 §2.3。
- **layout 布局底座**（`layout/`）：`DialogContentLayout`（internal，公开 `DialogDefaults`）、`DialogSurfaceScope`、`BottomSheetContentLayout` + `BottomSheetDefaults`（背景 `background`、拖拽条 `onSurfaceVariantSummary.copy(alpha=0.2f)`、`cornerRadius = 28.dp`、`maxWidth = 640.dp`、`outsideMargin = DpSize(0,0)`）、`BottomSheetSurfaceScope`、`ListPopupLayout`、`CascadingListPopupLayout` + `CascadingPopupDefaults`、`CascadingMorphContent`、`MorphHeaderRow`。

### 2.2 TopAppBar 折叠行为与模糊衔接（`basic/TopAppBar.kt`）

- **TopAppBar**（可折叠大标题栏）
  - 槽位：`title`、`largeTitle`（默认同 title）、`subtitle`、`navigationIcon`、`actions(RowScope)`、`bottomContent`、`scrollBehavior`。
  - 样式（TopAppBarDefaults）：`CollapsedHeight = 52.dp`、`SmallTopAppBarCenterHeight = 50.dp`、`TitlePadding = 26.dp`、`NavigationIconPadding = 16.dp`、`ActionIconPadding = 16.dp`、`LargeTitleBottomPadding = 4.dp`、`SubtitleBottomPadding = 8.dp`；小标题 `title3 = 20sp` Medium、大标题 `title1 = 32sp` Normal、subtitle `body2 = 14sp`；标题宽度上限 = 可用宽 × 0.9（`TITLE_WIDTH_FRACTION`）。
  - 折叠逻辑：`ExitUntilCollapsedScrollBehavior`（`MiuixScrollBehavior`：snap `spring(stiffness = 2500f)`，fling `rememberSplineBasedDecay()`）；`heightOffsetLimit = -大标题测量高度`；`collapsedFraction = heightOffset/heightOffsetLimit`。
    - 大标题淡出：`alpha = 1 - clamp(collapsedFraction * 3)`（前 1/3 折叠内消失）。
    - 小标题显隐阈值：`collapsedFraction * 3 >= 1`；进入 `folmeSpring(damping=1.0, response=0.3)`（alpha 0→1，translationY 20→0），退出 `folmeSpring(damping=1.0, response=0.15)`。
    - 标题颜色 tween(50ms) 过渡；标题层用视口 clip（`clipTopAppBarTitle`），控制图标阴影不受裁。
  - **模糊衔接**：`BlurTopAppBar(title, largeTitleBlurRadius)` 在大标题 `graphicsLayer` 上加 `renderEffect = BlurEffect(radius*fade, TileMode.Decal)`（半径随 `largeTitleFade` 升至 `largeTitleBlurRadius`，如 Glass 用 12dp）。这是「标题失焦」模糊，**不是** miuix-blur 的背景渐进模糊。
  - **渐进背景模糊**在 miuix-glass 的 `GlassTopAppBar` 中实现（见 §7）：`progressiveTextureBlur` 背景带随滚动 ramp 淡入。
- **SmallTopAppBar**：固定不折叠（`SideEffect` 里把 `heightOffsetLimit/heightOffset` 钉为 0、`pinnedBySmallTopAppBar = true`），共享 ScrollBehavior 时不吞滚动；布局居中于 50dp 带。

### 2.3 overlay / window / utils / theme / anim / interfaces

- **OverlayDialog**（`overlay/OverlayDialog.kt`）— 详见 §2.5。
- **OverlayListPopup**（`overlay/OverlayListPopup.kt`）：Scaffold 宿主内的列表弹窗（ListPopupColumn 内容），`alignment` 由 `PopupPositionProvider.Align` 控制，配 `onDismissRequest/onDismissFinished/maxHeight/renderInRootScaffold`。
- **OverlayCascadingListPopup**（`overlay/OverlayCascadingListPopup.kt`，2 重载）：级联（子菜单）弹窗。
- **OverlayBottomSheet**（`overlay/OverlayBottomSheet.kt`）：底部面板（配合 `BottomSheetContentLayout`）。
- **WindowDialog / WindowListPopup / WindowCascadingListPopup / WindowBottomSheet**（`window/`）：独立系统窗口版（desktop 用 ComposeWindow/Dialog），与 overlay 版共享 `DialogContentLayout` 等内部布局。
- **MiuixPopupUtils / MiuixPopupHost**（`utils/MiuixPopupUtils.kt`）：弹层注册中心（`PopupState`/`DialogState`、`DialogLayout`、根 Scaffold 渲染）。
- **MiuixIndication**（`utils/MiuixIndication.kt`）：MIUI 按压指示（indication 体系）。
- **Pressable / SinkFeedback / TiltFeedback / PressFeedbackType**（`utils/Pressable.kt`、`utils/PressFeedback.kt`）：按压反馈（下沉/倾斜）。
- **Overscroll**（`utils/Overscroll.kt`、`OverscrollFactory.kt`）：`Modifier.overScrollVertical/Horizontal/OutOfBound`、`MiuixOverscrollFactory`、`OverScrollState`。
- **scrollEndHaptic**（`utils/ScrollEndHaptic.kt`）：滚动到底震动；**PagerGestureUtils**（`pagerGestureOverride`）；**SpringUtils**（`folmeSpring` 弹簧）；**WindowNavigationEventScope**。
- **interfaces**：`HoldDownInteraction` + `collectIsHeldDownAsState()`、`@ExperimentalScrollBarApi`。
- **theme**（`theme/`）：
  - `MiuixTheme`（组合入口 + `object MiuixTheme { colorScheme/textStyles }`）；
  - `Colors` + `lightColorScheme()/darkColorScheme()`（关键值：`windowDimming` = **light `Color.Black.copy(alpha=0.3f)` / dark `Color.Black.copy(alpha=0.6f)`**；`background/surface/surfaceContainer/onSurfaceContainer/onSurfaceVariantSummary/primary/onPrimary/secondaryVariant/...` 全套）；
  - `TextStyles` + `defaultTextStyles()`：`main 17sp`、`paragraph 17sp(行高1.2em)`、`body1 16sp`、`body2 14sp`、`button 17sp`、`footnote1 13sp`、`footnote2 11sp`、`headline1 17sp`、`headline2 16sp`、`subtitle 14sp Bold`、`title1 32sp`、`title2 24sp`、`title3 20sp`、`title4 18sp`；
  - `ThemeController`（明暗/动态色控制）、`DynamicColors`（android/skiko）、`MonetMapping`、`DismissState` + `LocalDismissState`、`ContentColor` + `LocalContentColor`、`ProvideDisplayCutoutFix`。
- **anim**（`anim/`）：`folmeSpring(damping, response)`（MIUI folme 弹簧）、`AccelerateEasing`、`DecelerateEasing(power)`、`SinOutEasing`。
- **color**（`color/`）：`Hsv/OkHsv/OkLab/OkLch` 色彩空间、`Transforms`、`Color.toOkLab()/toHsv()/toOkLch()`。
- **icon.basic**（`icon/basic/`）：`ArrowRight`、`ArrowUpDown`、`Check`、`Close`、`Search`、`SearchCleanup`、`Sidebar`（组件内部使用的基础矢量）。

### 2.4 Dropdown 菜单条目与弹出窗口规范（`basic/Dropdown.kt` + `preference/popup`）

- **DropdownItem**（数据）：`text: String`、`enabled: Boolean = true`、`selected: Boolean = false`、`onClick: (() -> Unit)?`、`icon: (@Composable (Modifier)->Unit)?`、`summary: String?`、`children: List<DropdownItem>?`（非空即级联子菜单触发器）；兼容构造 `(icon, title, summary)`。
- **DropdownEntry**（数据）：`items: List<DropdownItem>`、`enabled: Boolean = true`（组；组标题预留未用）。
- **DropdownImpl**（行渲染，2 重载；旧 `SpinnerItemImpl` 已弃用）
  - 结构：`Row(drawBehind{行底色}.selectable) → Row(icon + Column(title, summary)) + 尾部（Check 20dp 或 子菜单 Chevron 10×16dp）`。
  - 样式（DropdownDefaults）：popup 模式行水平内边距 **20.dp**，首/尾行垂直 **20.dp**、中间行 **12.dp**；文本 `body1 16sp Medium`、summary `body2 14sp`；行文字最大宽 **216.dp**；icon 格最小 **26.dp**、icon-end 间距 **12.dp**；check-start 间距 **12.dp**。
  - dialog 模式：`MinHeight = 56.dp`、`MinWidth = 200.dp`、水平内边距 **28.dp**、垂直统一 12.dp。
  - 颜色（`DropdownDefaults.dropdownColors()`）：未选 `onSurfaceContainer`/summary `onSurfaceVariantSummary`/底 `surfaceContainer`；**选中文字/summary/勾 均 `primary`**，选中底色同 `surfaceContainer`（popup）；`dialogDropdownColors()`：选中底 `tertiaryContainer`、文字 `onTertiaryContainer`、容器透明。
  - 交互：`selectable(Role.RadioButton；子菜单触发行 Role.Button)`；禁用行不可点；点击回调 `onSelectedIndexChange(index)`。
- **DropdownEntriesPopupContent / dropdownEntriesDialogItems**（`preference/popup/DropdownEntriesContent.kt`）：多组渲染，组间 `HorizontalDivider(thickness=1.5.dp, padding(h=20.dp, v=4.dp))`；popup 全局首/尾行大间距。
- **OverlayDropdownPopup / OverlayDropdownDialog**（`preference/popup/OverlayDropdownPopup.kt`）：前者走 `OverlayListPopup(align=End)`；后者是「选项对话框」= `OverlayDialog(title, insideMargin=DpSize(0,24.dp))` + LazyColumn 选项 + **底部 `TextButton(dialogButtonString, minHeight = 50.dp, padding(start=24.dp, top=12.dp, end=24.dp), fillMaxWidth)`**（自定义 Layout 把按钮压在列表下方）。
- **弹出窗口（ListPopupContent）样式**：圆角 **16.dp**（squircle）、背景 **`surfaceContainer`**、宽 **200~288.dp**、最小高 50.dp；入场 spring(0.82, 362.5) + alpha 200ms、背景 dim 300ms（SinOutEasing）；定位锚点下方（垂直间距 8.dp），空间不足翻到上方，End 对齐。

### 2.5 OverlayDialog 对话窗口（详细）（`overlay/OverlayDialog.kt` + `layout/DialogContentLayout.kt`）

- **文件**：
  - 入口 `top.yukonga.miuix.kmp.overlay.OverlayDialog`（`miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/overlay/OverlayDialog.kt`）；
  - 布局实现 `top.yukonga.miuix.kmp.layout.DialogContentLayout` / `DialogContent` / `DialogDefaults`（`miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/layout/DialogContentLayout.kt`）。
- **内部结构（Column 自上而下）**：
  1. `title`：`Text`，`fillMaxWidth`，`padding(bottom = 12.dp)`，`fontSize = textStyles.title4 (18sp)`，**`FontWeight.Medium`**，`TextAlign.Center`，色 `DialogDefaults.titleColor() = onBackground`；
  2. `summary`：`Text`，`fillMaxWidth`，`padding(bottom = 12.dp)`，`fontSize = textStyles.body1 (16sp)`，`TextAlign.Center`，色 `summaryColor() = onSurfaceSecondary`；
  3. `content()`：自由内容区（按钮由调用方放这里，见下）。
  - 外层 `Box`：`fillMaxSize`，点击任意外部 → `onDismissRequest`（semantics onClick "Dismiss"）；`padding(horizontal = outsideMargin.width, top = 状态栏 max(statusBars,captionBar,displayCutout), bottom = outsideMargin.height)`；`imePadding/navigationBarsPadding/captionBarPadding`（`defaultWindowInsetsPadding=true` 时）。
- **尺寸 / 圆角 / 边距**：
  - 宽度上限 `DialogDefaults.MaxWidth = 420.dp`（`widthIn(max=...)`）；
  - 大屏（`width ≥ 840.dp && height ≥ 480.dp`）居中、高度上限 `windowHeight * 2/3`；小屏底部贴靠（`Alignment.BottomCenter`）；
  - 圆角：居中呈现 = `DialogDefaults.CornerRadius = 32.dp`；底部贴靠 = `(屏幕圆角 getRoundedCorner() - outsideMargin.width).coerceIn(32.dp, 48.dp)`；均可被 `cornerRadius` 参数覆盖；
  - 背景：`squircleSurface(color = backgroundColor)`，默认 `backgroundColor() = MiuixTheme.colorScheme.background`（可用 `surface` 槽位整体替换表面）；
  - 内边距 `insideMargin = DpSize(24.dp, 24.dp)`（水平 24dp / 垂直 24dp，应用在表面内）；外边距 `outsideMargin = DpSize(12.dp, 12.dp)`。
- **按钮（content 槽位惯例）**：组件不内置按钮；惯例与内置用法（`OverlayDropdownDialog`）：
  - `TextButton(text, onClick, modifier = Modifier.fillMaxWidth().padding(start=24.dp, top=12.dp, end=24.dp), minHeight = 50.dp)`；
  - TextButton 自身规范：圆角 **16.dp**、`MinHeight 40.dp`（对话框内覆写 50.dp）、`MinWidth 58.dp`、`InsideMargin 16×13.dp`、文字 `textStyles.button 17sp`；
  - 颜色：默认 `textButtonColors()`（底 `secondaryVariant`/字 `onSecondaryVariant`）；主按钮用 **`textButtonColorsPrimary()`**（底 `primary`/字 `onPrimary`，禁用 `disabledPrimaryButton`/`disabledOnPrimaryButton`）；
  - 多按钮排列：竖排 Column、每个 `fillMaxWidth`（demo 惯例）；`OverlayDropdownDialog` 中列表+单按钮竖排（按钮在列表下方）。
- **蒙层**：`dimColor = MiuixTheme.colorScheme.windowDimming`（**light: Black α=0.3；dark: Black α=0.6**；demo 常 `copy(alpha=0.2f)`）；实际 `scrimColor = dimColor.copy(alpha = dimColor.alpha × dimAlpha × dimProgress)`，全屏 `drawRect`；`enableWindowDim=false` 时透明。
- **动画**：
  - 显示：dim `tween(300ms, DecelerateEasing(1.5f))`；内容 大屏 `folmeSpring(damping=0.9f, response=0.3f)`（scale 0.8→1 + alpha 0→1）、小屏 `spring(dampingRatio=0.88f, stiffness=450f)`（`translationY` 从窗口底部滑入，alpha=1）；
  - 隐藏：dim `tween(250ms, DecelerateEasing(1.5f))`、内容 `tween(260ms, DecelerateEasing(1.5f))`，完成后回调 `onDismissFinished`；
  - 预测性返回（NavigationBackHandler）：大屏 `scale = 1 - 0.2×progress`、小屏 `translationY = progress × (对话框高+底部安全距)`，`dimAlpha = 1 - progress`；取消回弹 `tween(150ms)`。
- **交互/状态**：`show: Boolean` 控制；`onDismissRequest`（点外部/返回键）；`onDismissFinished`（动画结束，隐藏中被打断不触发）；`renderInRootScaffold`（true=全屏根 Scaffold，false=当前 Scaffold）；`LocalDismissState` 下发给 content；`largeScreen: Boolean?` 可强制形态。

---

## 3. miuix-preference（top.yukonga.miuix.kmp.preference / menu / popup）

### 3.1 preference 包（`miuix-preference/src/commonMain/kotlin/top/yukonga/miuix/kmp/preference/`）

- **ArrowPreference / ArrowPreferenceDefaults / EndActionColors**（`ArrowPreference.kt`）
  - 结构：`BasicComponent(title, summary, startAction, endActions, bottomAction)` + 尾部 `Row(endActions).padding(end=8.dp)` + 箭头。
  - 样式：箭头 `MiuixIcons.Basic.ArrowRight`，尺寸 **10×16.dp**，色 `onSurfaceVariantActions`（禁用 `disabledOnSecondaryVariant`），RTL 下 `scaleX = -1` 镜像；条目本身内边距 `BasicComponentDefaults.InsideMargin = 16.dp`，title `onBackground`、summary `onSurfaceVariantSummary`。
  - 交互：`onClick`（null 则不可点）、`holdDownState`（按住态反馈）、`enabled`。
- **CheckboxPreference / CheckboxLocation**（`CheckboxPreference.kt`）：`BasicComponent` + `Checkbox`；`checkboxLocation: Start/End`（复选框在首/尾）；`startAction` 与复选框同行（间距 5.dp）；颜色走 `CheckboxDefaults.checkboxColors()`。
- **SwitchPreference**（`SwitchPreference.kt`）：`BasicComponent` + `Switch`（49×28.dp 轨道）。
- **RadioButtonPreference / RadioButtonPreferenceDefaults / RadioButtonPreferenceColors / RadioButtonLocation**（`RadioButtonPreference.kt`）。
- **SliderPreference / RangeSliderPreference**（`SliderPreference.kt`）：`BasicComponent` + Slider/RangeSlider。
- **OverlayDropdownPreference**（`OverlayDropdownPreference.kt`，3 重载）/ **WindowDropdownPreference**（`WindowDropdownPreference.kt`，3 重载）：条目点击弹出 `OverlayDropdownPopup`/`WindowDropdownPopup`（或对话框形态），选择后收起。
- **OverlaySpinnerPreference**（`OverlaySpinnerPreference.kt`，6 重载）/ **WindowSpinnerPreference**（6 重载）：spinner 形态选择器（旧 SpinnerEntry 兼容）。
- **internal/StartActionSlot**：startAction 插槽布局。

### 3.2 menu / popup 包（弹出菜单窗口）

- **OverlayIconDropdownMenu**（`menu/OverlayIconDropdownMenu.kt`，2 重载）— 详细：
  - 结构：`Box { IconButton(trigger) + OverlayDropdownPopup(entries, show, ...) }`；单 `DropdownEntry` 重载自动包装 `listOf(entry)`。
  - 触发器（IconButton）：`MinWidth/MinHeight = 40.dp`、`CornerRadius = 40.dp`（圆形）、`backgroundColor = Color.Unspecified`、`holdDownState`（弹窗展开期间保持按下态，`onDismissFinished` 后释放）。
  - 弹窗：`OverlayDropdownPopup → OverlayListPopup(alignment = PopupPositionProvider.Align.End) → ListPopupColumn → DropdownEntriesPopupContent → DropdownImpl`。弹窗窗口规范见 §2.4（圆角 16.dp、背景 surfaceContainer、宽 200~288.dp、定位锚下 8.dp、spring(0.82, 362.5) 动画）。
  - 交互：点击切换展开（`onExpandedChange(Boolean)`）；展开时 `HapticFeedbackType.ContextClick` 震动；选项点击 `HapticFeedbackType.Confirm` + 调 `DropdownItem.onClick`；`collapseOnSelection`（单组默认 true，多组默认 false）决定选后是否收起；空组自动过滤，全空则禁用。
- **OverlayIconCascadingDropdownMenu / WindowIconCascadingDropdownMenu**（各 4 重载）：级联子菜单版（`DropdownItem.children` 递归展开 `OverlayCascadingListPopup`，子菜单触发行尾部 Chevron 10×16.dp）。
- **OverlayDropdownMenu / WindowDropdownMenu**（各 2 重载）：非图标锚点的下拉菜单。
- **WindowIconDropdownMenu / WindowDropdownMenu / WindowIconCascadingDropdownMenu**：独立窗口版（同上结构，宿主换 Window）。
- **OverlayDropdownPopup / OverlayDropdownDialog / WindowDropdownPopup / WindowDropdownDialog**（`popup/`）：见 §2.4。
- **DropdownEntriesPopupContent / dropdownEntriesDialogItems**（`popup/DropdownEntriesContent.kt`）：条目组渲染（分隔线 1.5.dp / 水平 20.dp / 垂直 4.dp）。

---

## 4. miuix-blur（top.yukonga.miuix.kmp.blur）— 详细

源码：`miuix-blur/src/commonMain/kotlin/top/yukonga/miuix/kmp/blur/`（平台实现在 `internal/RenderEffectCompat.*.kt`）。

- **textureBlur**（`TextureEffect.kt`）
  - 签名：`Modifier.textureBlur(backdrop: Backdrop, shape: Shape, blurRadius: Float = BlurDefaults.BlurRadius(=20f), noiseCoefficient: Float = BlurDefaults.NoiseCoefficient(=0.0045f), colors: BlurColors = BlurColors(), contentBlendMode: BlendMode = SrcOver, enabled = true)`；另有双半径重载 `textureBlur(backdrop, shape, blurRadiusX, blurRadiusY, ...)`。
  - 语义：均匀背景模糊（后台内容降采样多级采样），半径单位 dp（按 density 转 px），钳制 **[0, 150f]**（`BlurDefaults.MaxBlurRadius`）；`noiseCoefficient` 抗色带抖动（0 关闭）；`contentBlendMode = DstIn` 可做前景遮罩模糊。
  - 底层：`textureEffect(...)` → `drawBackdrop { textureBlurEffect(blurRadiusX, blurRadiusY, noiseCoefficient, colors) }`。
- **progressiveTextureBlur**（`TextureEffect.kt`）— 详细：
  - 签名：`Modifier.progressiveTextureBlur(backdrop, shape, blurRadius: Float = BlurDefaults.BlurRadius(=20f), gradient: ProgressiveBlur = ProgressiveBlur.Top, noiseCoefficient: Float = BlurDefaults.ProgressiveNoiseCoefficient(=0f), colors: BlurColors = BlurColors(), contentBlendMode: BlendMode = SrcOver, enabled = true)`；双半径重载 `progressiveTextureBlur(backdrop, shape, blurRadiusX, blurRadiusY, ...)`。
  - 语义：**渐进（梯度）背景模糊**——模糊强度沿 `gradient` 方向从全量到 0 平滑过渡；模糊中段是真模糊，清晰端为全分辨率（每帧多一次全分辨率叠加 pass，成本高于 textureBlur，适合导航栏/边缘渐隐带）；色彩/混合/噪声管线与 textureBlur 相同并随模糊一起淡出。
  - 半径同样钳制 **[0, 150f]**，单位 dp。
- **ProgressiveBlur**（`ProgressiveBlur.kt`，`@Immutable data class`）：
  - 字段：`angle: Float = 90f`（方向，度，顺时针离 +X：0=左→右，90=上→下）、`startFraction: Float = 0f`（全量模糊处，沿方向占表面比例）、`endFraction: Float = 1f`（归零处；可小于 start 反向）、`curve: Float = 1f`（衰减指数：<1 变化集中在强端，>1 集中在清晰端，必须为正）。
  - 预设：`Top = ProgressiveBlur(angle=90f, startFraction=0f, endFraction=1f)`（**curve 用默认 1f**）；`Bottom = (270f, 0f, 1f)`；`Left = (0f, 0f, 1f)`；`Right = (180f, 0f, 1f)`。
- **BlurColors / BlendColorEntry / BlurBlendMode / BlurDefaults**（`BlurDefaults.kt`）：
  - `BlurColors(blendColors: List<BlendColorEntry> = emptyList(), brightness: Float = 0f（[-1,1]）, contrast: Float = 1f, saturation: Float = 1f)`——模糊后的色彩调整。
  - `BlendColorEntry(color: Color, mode: BlurBlendMode = BlurBlendMode.SrcOver)`——叠色层，按序绘制。
  - `BlurBlendMode(value: Int)`：SkBlendMode 0–29（Clear…Luminosity）+ 自定义 **100–121**（LinearLight/Lab 系/PlusDarker/PlusLighter…）、**200–203**（AlphaBlend/MiSaturation/MiBrightness/MiLuminance）。
  - `BlurDefaults`：`BlurRadius = 20f`、`NoiseCoefficient = GlassDefaults.NoiseCoefficient = 0.0045f`、`ProgressiveNoiseCoefficient = 0f`、`MaxBlurRadius = 150f`；**`BlurDefaults.blurColors(blendColors, brightness, contrast, saturation)`** 记忆工厂（`@Composable`，List 不稳定，务必用工厂）。
- **Backdrop / LayerBackdrop / rememberLayerBackdrop / Modifier.layerBackdrop**（`Backdrop.kt`、`LayerBackdrop.kt`、`LayerBackdropModifier.kt`）：
  - `Backdrop` 接口：`isCoordinatesDependent`、`offsetResidualX/Y`（亚像素残差补偿）、`DrawScope.drawBackdrop(density, coordinates, layerBlock, downscaleFactor)`（downscale>1 时按 1/factor 降采样绘制，1/4 倍频对齐）。
  - `LayerBackdrop`：从 `GraphicsLayer` 捕获内容作为背景；`rememberLayerBackdrop(graphicsLayer = rememberGraphicsLayer(), onDraw = { drawContent() })`；内容侧用 `Modifier.layerBackdrop(backdrop)` 标记录制区；坐标经 `localPositionOf` 对齐，降采样时平移+缩放补偿。
- **底层管线**（internal + `BackdropEffects.kt`/`BackdropEffectScope.kt`）：
  - `Modifier.drawBackdrop(backdrop, shape, effects, contentBlendMode, progressiveGradient, enabled)`；
  - `BackdropEffectScope` 效果 DSL：`blur(radiusX, radiusY)`、`progressiveBlur(...)`、`noiseDither(coefficient)`、`blendColors(colors)`、`colorControls(brightness, contrast, saturation)`、`effect(RenderEffect)`、`runtimeShaderEffect(...)`、聚合 `textureBlurEffect(...)` / `progressiveTextureBlurEffect(...)`。
  - `internal/`：`BlurEffect`、`LayerRecorder`、`Shaders`（运行时着色器）、`ShapeProvider`、`RenderEffectCompat`（android/skiko 各自实现）、`InverseLayerScope`。

---

## 5. miuix-squircle（top.yukonga.miuix.kmp.squircle）

- **Modifier.squircleSurface / squircleBackground / squircleClip**（`SquircleBackground.kt`，各含 absoluteSquircle* 变体）：MIUI 超椭圆圆角表面（颜色 + 裁切），是 Card/Button/Dialog 的统一形状底座；不支持 RuntimeShader 时回退普通圆角。
- **Modifier.squircleBorder**（`SquircleBorder.kt`）：超椭圆描边。
- **SquircleDefaults / Path.addSquircleRect**（`SquirclePath.kt`）：路径构建。
- **LocalSquircleEnabled / isSquircleEnabled()**（`LocalSquircleEnabled.kt`）：全局开关。

---

## 6. miuix-material（top.yukonga.miuix.kmp.material）

- **GlassMaterial / GlassColorLayer / withUnderlayBlur**（`GlassMaterial.kt`）；**GlassMaterials**（`GlassMaterials.kt`，如 `puredThinGlass(isDark)`）。
- **GlassStyle / GlassBlend / GlassInner / GlassEdge**（`GlassStyle.kt`）、**GlassStyles**（`GlassStyles.kt`）。
- **GlassDefaults**（`GlassDefaults.kt`）：`NoiseCoefficient = 0.0045f`、`Smoothing` 等。
- **GlassShape**（`GlassShape.kt`）：玻璃形状（圆角+平滑度）；**GlassShadow / GlassShadows**（`GlassShadow.kt`）。
- **EdgeStroke / EdgeLightSource / EdgeStrokes / GlassEdgeStrokes**（`EdgeStroke.kt`）、`Modifier.edgeStroke`（`EdgeStrokeModifier.kt`）、`DrawScope.drawEdgeStroke/drawGlassRim/drawGlassEdge/drawGlassMask/drawGlassShadow`（`EdgeStrokeDrawing.kt`、`GlassOverlays.kt`）。
- **RuntimeShader / RuntimeShaderCache / RenderEffect**（android/skiko 双实现）、**MaterialAgsl**（AGSL 着色器源）、**TiltLight / DeviceTilt**（倾斜光传感器）。

---

## 7. miuix-glass（top.yukonga.miuix.kmp.glass）

- **Modifier.glass / glassShadow**（`Glass.kt`）、**Modifier.glassPanel**（`GlassPanel.kt`）、**glassSurface**（`GlassSurface.kt`）。
- **GlassTopAppBar / GlassTopAppBarDefaults**（`GlassTopAppBar.kt`）— 渐进模糊衔接实例：
  - `HorizontalPadding = 12.dp`、`RampStart = 0.dp`、`RampDistance = 32.dp`（阴影 ramp 滚动阈值）、`LargeTitleBlurRadius = 12.dp`（大标题折叠失焦）、`ButtonSize = 44.dp`（玻璃药丸按钮）、`ProgressiveBlurRadius = 80.dp`、`ProgressiveBlurRampDistance = 48.dp`（渐进带淡入滚动距离）、`PressedContentAlpha = 0.6f`。
  - 背景带实现：`Box.matchParentSize().layerBackdrop(progressiveBackdrop).progressiveTextureBlur(backdrop = rawBackdrop, shape = RectangleShape, gradient = ProgressiveBlur.Top.copy(curve = 0.5f), blurRadius = 80f, colors = BlurDefaults.blurColors(blendColors = listOf(BlendColorEntry(surface.copy(alpha=0.3f)))))`；带高 = `(状态栏 + 52.dp + 20.dp) × 1.2`；alpha = `(-contentOffset / 48.dp)` 的 ramp（clamp 0..1）。
- **GlassDialog / GlassDialogSurface / GlassDialogButton / GlassDialogDefaults / GlassDialogButtonDefaults**（`GlassDialog*.kt`）。
- **GlassBottomSheet / GlassBottomSheetSurface / GlassBottomSheetDefaults**。
- **GlassPopup / GlassPopupItem / GlassPopupColumn / GlassPopupSurface / GlassPopupSizing / GlassPopupVisuals / GlassPopupFrame / GlassPopupBackProgress / GlassPopupDefaults**、**GlassSecondaryPopup**、**GlassTransformPopup / GlassPopupAnchor / rememberGlassPopupAnchor**（变换式弹窗）。
- **GlassDropdownPopup / GlassDropdownDefaults / GlassDropdownPreference**、**GlassIconDropdownMenu / GlassIconButton**。
- **GlassNavigationBar / GlassNavigationBarLayout / GlassNavigationItem / GlassNavigationBarDefaults**、**GlassTabRow / GlassSegmentedTabRow / GlassTabColors / *Defaults**。
- **glassPress / glassPressScale**（`GlassPress.kt`）、**NavigationDragTarget**、**GlassMotion**（动效常量）、**internal/**（GlassShader、GlassUniforms、ScrimBackdrop）。

---

## 8. miuix-icons / miuix-glass-icons

- **miuix-icons**（`icon/extended/`，156 文件）：`val MiuixIcons.Add`（默认取 Regular）及 `MiuixIcons.Light/Normal/Regular/Medium/Demibold.Add` 五字重 ImageVector（24dp 画布）。
- **miuix-glass-icons**（`icon/glass/`，176 文件）：`MiuixIcons.Glass.Light/Normal/Regular/Medium/Demibold.*`（28dp 画布，保留原生符号尺寸+完整描边）。

---

## 9. miuix-nav（top.yukonga.miuix.kmp.nav）

- **core**：`NavDisplay`（2 重载）、`NavEntryBuilder`、`NavBackStack`、`NavController`、`NavEntry`、`NavKey`、`NavDisplayEffects`/`NavCornerClipMode`、`NavSystemCornerRadius`、`LocalNavTransitionScope`/`LiveNavTransitionScope`。
- **gesture**：`PredictiveBackHandler`/`NavBackEvent`、`NavPredictiveBackDriver`、`NavSwipeDismiss`（`Modifier.navSwipeDismiss`）、`NavSwipeArbitrator`、`WindowNavigationEventBridge`（android/skiko）。
- **transition**：`NavTransition`、`NavTransitions`、`NavDirectionalTransition`（`navDirectionalTransition`）、`navGraphicsTransition`、`NavMotion`/`NavSettleSpec`、`NavTransitionScope`/`NavGesture`/`NavSettle`/`NavSettlePhase`/`NavSwipeDirection`/`NavSwipeEdge`。
- **runtime/state**：`NavDriver`、`NavReconciler`、`NavPresentation`、`NavChange`、`NavSettleEasing`、`NavEntryLifecycle`、`NavEntryViewModel`、`NavSaveableStateHolder`。

---

## 10. miuix-window（top.yukonga.miuix.kmp.window，desktopMain）

- **MiuixWindow / MiuixDialogWindow**（自定义壳窗口）、**MiuixCaptionBar**、**MiuixWindowProperties**（`defaultCaptionBarHeight()`）、**MiuixWindowInfo / CaptionButtonsAlign**、**MiuixWindowBackgroundType**。
- **WindowDraggableArea / WindowResizeEdge**。
- **basic/**：`MiuixCaptionButton`、`MiuixCaptionButtonClose/Maximize/Minimize`、`rememberWindowsCaptionBarFontIconFamily`。
- **platform/**：windows（Dwmapi/WindowProc/触控/命中测试）、macOS（振动/标题栏）、linux（X11/XInput2/手势）三套适配；`internal/`（WindowStyler、DesktopOS、DesktopSystemTheme 等）。

---

## 11. HyperHand View 侧对齐速查

### 11.1 OverlayDialog 关键数值清单

| 项 | 数值 | 含义 |
|---|---|---|
| 宽度上限 | 420.dp | `DialogDefaults.MaxWidth`，`widthIn(max)` |
| 圆角（居中/大屏） | 32.dp | `DialogDefaults.CornerRadius` |
| 圆角（底部贴靠/小屏） | (屏幕圆角 − 12.dp)，钳 32~48.dp | outsideMargin.width=12.dp 参与同心推导 |
| 外边距 outsideMargin | 12.dp × 12.dp (h×v) | 对话框与屏幕边缘间距 |
| 内边距 insideMargin | 24.dp × 24.dp (h×v) | 表面内 padding |
| 背景色 | `MiuixTheme.colorScheme.background` | squircleSurface 填充 |
| 标题 | title4 = 18sp，FontWeight.Medium，居中 | 底边距 12.dp；色 onBackground |
| 摘要 | body1 = 16sp，居中 | 底边距 12.dp；色 onSurfaceSecondary |
| 内容间距（按钮惯例） | TextButton 上方 12.dp，左右 24.dp | OverlayDropdownDialog 布局 |
| 按钮（TextButton） | 高 50.dp（对话框惯例；默认 MinHeight 40.dp）、圆角 16.dp、MinWidth 58.dp、内边距 16×13.dp、文字 button=17sp | fillMaxWidth 竖排 |
| 按钮色（默认） | 底 secondaryVariant / 字 onSecondaryVariant | `textButtonColors()` |
| 按钮色（主） | 底 primary / 字 onPrimary | `textButtonColorsPrimary()` |
| 蒙层 | windowDimming = Black α 0.3（light）/ 0.6（dark） | 实际 alpha = base × dimAlpha × dimProgress |
| 大屏判定 | 宽 ≥ 840.dp 且 高 ≥ 480.dp | 居中+缩放进入，否则底部滑入 |
| 大屏高度上限 | 窗口高 × 2/3 | heightIn(max) |
| 入场-内容（大屏） | folmeSpring(damping=0.9, response=0.3)；scale 0.8→1，alpha 0→1 | |
| 入场-内容（小屏） | spring(dampingRatio=0.88, stiffness=450)；translationY 自底部 | |
| 入场-蒙层 | tween(300ms, DecelerateEasing(1.5f)) | |
| 出场-内容 | tween(260ms, DecelerateEasing(1.5f)) | 结束后 onDismissFinished |
| 出场-蒙层 | tween(250ms, DecelerateEasing(1.5f)) | |
| 预测性返回 | 大屏 scale=1−0.2×p；小屏 ty=p×(内容高+底安全距)；蒙层 alpha=1−p；取消回弹 tween(150ms) | |

### 11.2 progressiveTextureBlur 关键数值清单

| 项 | 数值 | 含义 |
|---|---|---|
| blurRadius 默认 | 20f (dp) | `BlurDefaults.BlurRadius`，全量端模糊半径 |
| blurRadius 钳制 | [0, 150f] | `BlurDefaults.MaxBlurRadius` |
| gradient 默认 | `ProgressiveBlur.Top` = (angle 90°, startFraction 0f, endFraction 1f, **curve 1f**) | 顶边全模糊 → 底边全清晰 |
| curve 语义 | <1 变化集中强端，>1 集中清晰端 | Top 可覆写（Glass 用 0.5f） |
| noiseCoefficient（progressive） | 0f | `ProgressiveNoiseCoefficient`，默认关抖动 |
| noiseCoefficient（textureBlur） | 0.0045f | `GlassDefaults.NoiseCoefficient` |
| colors 默认 | `BlurColors()`：无叠色、brightness 0、contrast 1、saturation 1 | 工厂 `BlurDefaults.blurColors(...)` |
| 叠色项 | `BlendColorEntry(color, mode = BlurBlendMode.SrcOver)` | 按序绘制；扩展混合 100-121/200-203 |
| contentBlendMode | SrcOver（DstIn=前景遮罩模糊） | |
| 配套 API | `rememberLayerBackdrop(graphicsLayer, onDraw)` + `Modifier.layerBackdrop` | 捕获背景层 |
| GlassTopAppBar 实例 | blurRadius=80f、gradient=Top.copy(curve=0.5f)、带高=(状态栏+52+20)dp×1.2、ramp=−contentOffset/48dp、叠色 surface α0.3 | 渐进带随滚动淡入 |

> 提示：OverlayDialog 的「按钮区」是 content 槽位自由布局。**SonyPods 全部对话窗口（PowerOffDialog / PodImageConfigDialog 等）采用 `Row(fillMaxWidth, Arrangement.spacedBy(8.dp))` 横排等宽 TextButton（左 secondary / 右 primary，`Modifier.weight(1f)`）**，miuix example 的 DialogSection 同款；miuix docs demo 里也有竖排 fillMaxWidth 的写法。HyperHand 按 SonyPods 用法对齐（横排 8.dp 间距、高 40.dp、圆角 16.dp、文字 17sp）。`OverlayIconDropdownMenu` 弹窗对齐 §2.4 的 16.dp 圆角 / surfaceContainer / 200~288.dp 宽 / spring(0.82, 362.5)。
