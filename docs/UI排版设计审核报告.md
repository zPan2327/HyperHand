# HyperHand UI 排版与设计审核报告

> 审核日期：2026-11-27（3.4.3，2026-11-27 修订）
> 基准：Miuix（`top.yukonga.miuix.kmp`）组件源码默认值；标杆：SonyPods、LSPosed 2.2.1、KernelSU。
> 方法：逐行读 `Theme.java` / `Ui.java` / `TopBar.java` / `OverviewPage.java` / `SettingsPage.java` /
> `AboutPage.java` / `StatusCardHost.kt` 源码 + 真机（小米 14，1200×2670 @480dpi，density=3）
> **滚到页面底部后**的截图与 `uiautomator dump` bounds 实测。深浅色均核对。

> **修订说明**：初版报告有两条 P0 结论是错的（作用域被底栏遮挡、状态卡文字与图标打架），
> 原因是只看首屏截图就下判断。改用「滚到底 + 二次滑动确认到底」复测后两条都不成立，
> 已从问题清单撤下，过程与证据保留在 §5，供后续审阅时避免同类误判。

---

## 0. 结论摘要

整体**已经很接近 miuix / SonyPods**：字号阶梯（32 / 24 / 20 / 17 / 16 / 14 / 13 / 11sp）、
行高节奏、卡片圆角、开关尺寸、顶栏折叠、液态底栏、底栏留白全部对得上，**不需要推倒重来**。

复测后仍然成立的问题只有 2 条硬偏差 + 2 条可选微调：

| 优先级 | 问题 | 页 | 性质 |
|---|---|---|---|
| P1 | 分组标题文字起点 26dp vs 行文字 28dp，差 2dp 两条对齐线 | 概览/设置/关于 | 对齐 |
| P1 | 开发者卡行内边距 18dp（其余卡片 16dp），头像比标题多缩进 2dp | 关于 | 对齐 |
| P2 | 关于页开发者姓名 19sp，不在 Miuix 字阶内 | 关于 | 规范 |
| P2 | 版本号格式不统一：概览 `3.4.3 (30403)` / 关于 `3.4.3(30403)` | 概览/关于 | 一致性 |
| P3 | 卡内分段标题与摘要同为 14sp 灰字，深色下分段感偏弱 | 设置 | 层级（主观） |
| P3 | 单手模式卡展开后 2163dp，需要滑 4~5 屏 | 设置 | 密度（取舍） |

前四条已在 2026-11-27 修正并真机复测（见文末 §9）。

---

## 1. 字号与字重：总体合规，一处出阶

Miuix `TextStyles`（`miuix-components.md` §2.1）：
`title1 32 / title2 24 / title3 20 / headline1 17 / body1 16 / body2 14 / subtitle 14 Bold / footnote1 13 / footnote2 11`。
HyperHand `Theme.java` 的常量（`T_DISPLAY 32 / T_TITLE 24 / T_HERO 20 / T_ROW 17 /
T_BODY 16 / T_SUM 14 / T_SECTION 14 / T_CAP 13 / T_TAB 11 / T_STAT 24`）
**逐条能对上字阶，无缺失、无自造层级**。

字重：`regular = sans-serif`、`medium = sans-serif-medium`、`bold = sans-serif Bold` /
`semibold`，在 HyperOS 上分别是 MiSans 400 / 500 / 700，与系统设置一致。合理。

**偏差 1（P2）**：关于页开发者卡姓名用 **19sp**（`AboutPage.java:282`，
`Ui.text(c, "不会买菜.", 19, pal.title, Theme.semibold())`），既不在 Miuix 字阶，
也与同卡下方 `@zPan2327`（14sp）的配比不整齐。SonyPods 的 DeveloperCard 用 20sp title3。

> 建议：19 → **`Theme.T_HERO`(20sp) + `Theme.medium()`**，回到字阶内。

**已排除**：初版写的「状态卡标题 20sp 明显小于统计卡 24sp，视觉权重不对等」——
复测后判定**不是缺陷**。两者语义不同：状态卡是「标题 + 摘要」的 hero 卡（20sp Bold），
统计卡是「标签 + 数值」的指标卡（数值 24sp Bold）。这种 20/24 的差是刻意的主次关系，
SonyPods 的 StatusCard 与 MetricCard 同样不等号。

---

## 2. 排版与对齐：唯一实测到的硬偏差（26dp vs 28dp）

真机 bounds（px / density=3）：

| 元素 | 视图左边界 | padding-left | 文字起点 |
|---|---|---|---|
| 卡片 / 内容容器 | 36px = 12dp | — | 12dp |
| 行标题（启用按键映射 / 扩展触发） | 84px = 28dp | 0 | **28dp** |
| 卡内分段标题（触发与识别） | 36px = 12dp | 16dp | **28dp** |
| 卡片外分组标题（系统信息 / 按键映射 / 开发者） | 36px = 12dp | 14dp | **26dp** |

`Ui.section()` 的注释写「26dp 与 miuix 28dp 视觉一致」——实测就是 26dp，与真正的内容线
28dp 差 2dp（6px）。**概览页的「系统信息」与设置页的「按键映射」都是 26dp，两页一致**，
所以不是单页问题，而是 `section()` 这一个函数的问题。LSPosed / SonyPods 的同级标题与
行文字严格对齐。

> 建议（P1）：`Ui.section()` 的 `padding-left` 从 **14dp 改为 16dp**，右侧同步。
> 一处改动，三页的分组标题全部落到 28dp 内容线，与行标题、卡内分段标题合成一条线。

---

## 3. 行高与信息密度：64dp 合规

`Ui.rowShell()`：`minHeight = 56dp`、`padding top/bottom = 10dp`；
`Ui.twoLine()`：标题 17sp + 3dp + 摘要 14sp + 2dp lineSpacing。
实测相邻行标题 top 差恒为 **192px = 64dp**（5 组连续行全部一致）。

Miuix `BasicComponent` 规范：`InsideMargin = 16dp` 四边，两行内容算下来约 74dp；
SonyPods 实际 SettingRow 在两行内容下也在 64~68dp 区间。**64dp 合理，不算违规**，
也不需要为了「更密」去压 —— 再压会让 17sp 标题和 14sp 摘要贴得太近。

真正可讨论的是**单手模式卡偏长**（取舍，不是缺陷）：

- 展开后整页约 6489px ≈ 2163dp（7 个分段、20+ 行）；
- 一屏可见约 466dp，滑到底需要 4~5 屏。

> 可选（P3）：慢滑 3 项（慢滑识别 / 所需距离 / 速度下限）只在慢滑开关打开时才有意义，
> 可以跟「显示高级参数」一样随开关展开，省下约 3 行 ≈ 192dp，且不损失可发现性。

---

## 4. 分段标题与行标题的层级：现状合规，微调属主观

卡内分段标题用 `Ui.cardSection()`：14sp Bold、`section` 色（浅 `#8C93B0` / 深 `#787E96`）、
padding 上 16 / 下 4 / 左右 16。行标题 17sp Medium。

浅色下「触发与识别」「触发区」「外观」「动效」是灰蓝小字，与黑色 17sp 行标题层级清楚。
深色下分段标题与摘要文字（都是 14sp 灰）颜色接近，分段感略弱 —— 但 Miuix 的
`SmallTitle` 本来就是 14sp Bold + `onBackgroundVariant`，**严格说合规**。

> 可选（P3）：分段标题上边距 16 → 20dp、下边距 4 → 6dp，靠留白而不是字号/颜色去分段。
> 这是间距层面的微调，不动字号规范。

---

## 5. 复测排除项（初版误判两条，记录以免重犯）

### 5.1 作用域 / 安装时间 —— 不遮挡

初版写「值的后两行被底栏盖住」，只看了首屏。实际 `MainActivity.applyInsets()`
给每个滚动容器的子 View 预留了底部内边距：

```java
// 底栏自身 78dp（64 + 上下 7 的溢出）+ 12dp 外边距 + 16dp 呼吸
int bottom = navLp.bottomMargin + Theme.dp(this, 78 + 16);
```

滚到极限（再滑一次坐标不再变化，确认到底）后实测：

| 元素 | bounds（px） |
|---|---|
| 「安装时间」标签 | y 1991~2058 |
| 页脚说明文字底边 | y ≈2280（含 8dp 内边距） |
| 底栏玻璃容器顶边 | y 2331 |
| **页脚与底栏净距** | **≈51px ≈ 17dp** |

作用域三行（`系统框架 system（按键映射）` / `系统界面 com.android.systemui` /
`桌面 com.miui.home`）与安装时间**完整可见**，底栏没有任何遮挡。

### 5.2 状态卡文字与图标 —— 不打架

初版写「图标左手指与文字末字净距不足 8dp」。实测两者的区域完全不相交：
文字块在卡片左上（16dp 内边距，标题 + 摘要共约 42dp 高），图标在右下角且**刻意向外溢出**
（`StatusCardHost.kt`：96dp、`BottomEnd` + `offset(22dp, 24dp)`，对应原 FrameLayout 版
`rightMargin=-22 / bottomMargin=-24`）。视觉上是「手从卡片角上伸出来」的出血效果，
和 SonyPods 的图标处理同源。**保留不动。**

> 教训：这类「贴边 / 溢出 / 底部遮挡」的判断必须以**滚到底的完整页面**为准，
> 且溢出设计要先读实现再定性为 bug。

---

## 6. 关于页

- **hero 区**：96dp 圆角 logo + 32sp 字标 + 「开发者」标题，留白约 145dp 是刻意的
  （SonyPods 同款 hero 预算），保留。
- **开发者卡**：头像 56dp + 姓名 20sp(title3, Medium) + `@zPan2327` 14sp，行内边距
  **16dp（原 18dp 已改，与其它卡片统一）**，右侧「›」用 32sp 字符 —— SonyPods
  DeveloperCard 同样用字符箭头，可保留。
- **项目卡**：四个 ArrowPreference（源码 / 引用 / 使用说明 / 许可），17sp 标题 + 14sp 摘要，
  箭头为 miuix `ArrowRight`，与设置页一致。✅
- **版本号**：`3.4.3(30403)`，与 hero、规范 §2.3 及概览页「模块版本」行统一（概览行
  原先多一个空格，已改）。✅
- **状态栏图标**：深浅色下均为白色，与背景对比清晰。✅

---

## 7. 四方对照（字号 / 尺寸）

| 项目 | Miuix 规范 | SonyPods | LSPosed 2.2.1 | KernelSU | HyperHand |
|---|---|---|---|---|---|
| 页面大标题 | 32sp | 32sp | ~28sp | 28sp | **32sp ✅** |
| 行标题 | 17sp Medium | 17sp Medium | ~16sp | 16sp Medium | **17sp Medium ✅** |
| 摘要 | 14sp | 14sp | 14sp | 14sp | **14sp ✅** |
| 分组标题 | 14sp Bold | 14sp Bold | 14sp | 14sp Bold | **14sp Bold ✅（缩进差 2dp）** |
| 卡片圆角 | 16dp | 16dp | 12dp | 12dp | **16dp ✅** |
| 卡片间距 | — | 12dp | 8dp | 12dp | **12dp ✅** |
| 两行行高 | ~56~64dp | ~64dp | ~56dp | 56dp | **64dp ✅** |
| 开关 | 49×28 | 49×28 | 48×26 | 48×26 | **49×28 ✅** |
| 顶栏折叠 | 32→20sp | ✅ | ✅ | ✅ | **32→20sp ✅** |
| 底栏内容留白 | 需覆盖悬浮栏 | ✅ | ✅ | — | **78+12+16dp ✅** |

---

## 8. 建议执行顺序

1. **5 分钟级**：`Ui.section()` padding-left 14 → 16dp，三页分组标题对齐到 28dp。
2. **5 分钟级**：关于页姓名 19 → 20sp（`Theme.T_HERO` + medium）。
3. **可选**：卡内分段标题 16/4 → 20/6dp；慢滑 3 项随开关折叠。
4. **文档**：`docs/设计稿对照.md` 的「交互」章节已过时（仍写「点开关行任意位置切换」
   「拖动行下滑杆」），与现在的实现（只有开关可点、Dropdown 弹层）不符，需更新。

前两项都是纯常量改动，不触碰动画与手势逻辑，风险极低。
