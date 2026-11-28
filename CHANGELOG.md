# Changelog

所有值得注意的改动都会记录在这里。格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布]

### 修复
- **概览页「作用域」显示异常**：文案抽取时把换行写成了字面的 `\n`，运行时原样显示；改回真正的换行
  （「重启提示」那条同类问题一并修正）。
- **新建规则「优先级」对话框出现未替换的 `%1$d`**：选项用了带格式符的资源却没把档位数值传进去。
- **概览页状态卡按压时四角被削平**：Miuix 的 Tilt 会把四角画到卡片边界之外，卡片自身与它的直接父容器
  都在裁剪；放开这两层即可（**不能**放开滚动容器的直接子 View，那会让 Compose 宿主落到软件画布上，
  玻璃高亮着色器直接抛异常崩溃——模拟器上实测确认）。
- **拖动液态底栏时下缘缺一截**：胶囊按下会放大并带 10dp 投影，而底栏 View 的底边只比胶囊低 7dp，
  投影的下缘被宿主边界切掉。底栏 View 改为贴到屏幕底，把胶囊下方空着的「7dp + 12dp 外边距 + 系统手势区」
  算成 View 内部的绘制余量；胶囊的屏幕位置和页面底部留白都保持原值。
- **设置页关闭「单手模式增强」/「快捷菜单滑选」后概览页不刷新**：保存走异步广播，App 发完立刻去读，
  SystemUI 往往还没写进 `Settings.Global`；之后切回概览只刷新界面不重读配置。现在用 `ContentObserver`
  盯着这两个键，写进去的那一刻再重读 + 刷新；切到概览与探测回写时也先重读。

### 变更
- **液态底栏实时模糊**：「高级参数」展开 / 收起、总开关关闭收起卡片这类变化不产生滚动事件，玻璃会停在
  变化前那一帧。给每页内容加了布局变化监听，布局一变就重新采样。
- **关于页「引用」从对话框改成二级页面**（Miuix 风格：可折叠大标题 + 列表卡）：逐项列出源码里实际引用
  到的 6 个开源项目，点击打开对应仓库。原长文只有 5 项，补上了底栏渲染实现真正迁入的 LiquidDemo。

## [3.4.7] - 2026-11-27

> versionCode 30407，可直接覆盖安装 3.4.6（30405→30406）。**升级后请在「概览」页点右上角重启按钮，三个作用域全选**（或重启手机），否则新代码不会注入。

### 新增
- **界面语言**（设置页最底部 → 个性化 → 语言）：**跟随系统（默认）** / 简体中文 / 繁體中文 / English，
  切换后界面立即刷新。系统语言不在三者之列时显示简体中文。全部界面文案完成三语翻译（设置 / 概览 / 关于 /
  按键映射各页 / 底栏 / 最近任务标题）。
- **使用说明与许可改为仓库外链**：关于页的「使用说明」按界面语言打开 GitHub 上的中英文文档
  （`docs/USAGE.md` / `docs/USAGE_EN.md`），「许可」打开仓库根目录的 `LICENSE`；正文不再内置在 App 里。


### 安全
- **配置广播加发送方校验**：`io.github.zpan2327.hyperhand.action.SET` 的接收器注册在 SystemUI / 桌面进程，
  此前导出且无权限保护——任何第三方应用都能发它来杀 SystemUI（`act=reload`）、改写全部 `oh_tuner_*`
  配置、强拉控制中心。现在用模块自声明的 signature 级权限
  `io.github.zpan2327.hyperhand.permission.CONTROL` 保护接收器，只有同签名的本模块（或 root）能发。
  adb 调试改为 `adb shell su -c "am broadcast …"`。

### 修复
- **返回键**：Android 16 + targetSdk 36 下系统不再调用 `onBackPressed()`，「设置 / 关于页按返回先回概览」
  实际已失效；改为注册到 `OnBackPressedDispatcher`，逻辑不变。
- **语言跟随系统设置**：用户在「系统设置 → 应用 → 语言」里改过之后，下次启动不再被旧偏好覆盖；
  兼容 `zh-Hans-CN`、`en-US` 这类带地区的写法。
- **首次授权不再卡住**：`WRITE_SECURE_SETTINGS` 的 `su` 授权最多等 8 秒；规则启用 / 删除 / 复制也移到
  IO 线程，避免和启动预热撞车时在主线程等 root。

### 文档
- 新增英文版 README（`README_EN.md`），与中文版章节一一对应；两份 README 与两份使用说明顶部统一加
  「简体中文｜English」切换链接。README 里过时的描述一并修正：签名密钥实际随仓库保留（不是 gitignore）、
  「重启两个作用域」改为「重启作用域」、补充 3.4.7 的界面语言说明与 USAGE 文档入口。

### 翻译
- 英文把「从底部下滑」误译为 swipe up 的 4 处改为 swipe down。
- 「重启两个作用域」改为「重启作用域」（实际已是三个作用域）。
- 英文规则数改为复数：`1 rule / 2 rules`（原来是 `1 rule(s)`）。
- 4 个 `translatable="false"` 的条目不再重复出现在 `values-en` / `values-b+zh+Hant`。

## [3.4.6] - 2026-11-27

> versionCode 30406，可直接覆盖安装 3.4.5（30405）。界面与交互调整，手势识别与 Hook 逻辑零改动。

### 新增
- **「添加触发按键」「添加执行动作」改为先选后加**：点「添加」不再直接往列表尾部塞一条
  `音量加 · 短按`，而是弹出选择对话框——触发选 按键（音量加/减）+ 按压方式（短按/长按/双击/连按），
  动作选具体动作；摘要实时跟着选择走，点「添加」才写入草稿，点「取消」不留痕迹。
  新增动作行会沿用它前面那条映射的按键与按压方式（列表为空时用 `音量加 · 短按`），
  摘要里写明「将同时新增「X键 · Y」的触发」，因为一条映射本身就是「触发 + 动作」的整体。
- **编辑页未保存退出二次确认**：进页面时留一份草稿 JSON 快照，退出时比对；有改动则弹
  `放弃修改？`，`取消` 留在本页继续编辑、`退出` 丢弃改动直接返回。顶栏返回与系统返回键同一条判断；
  保存路径不受影响，点「保存」仍然直接返回。

### 变更
- **按键映射首页**（3.4.6 一并发布）：
  - 标题去掉「智能」，统一为「按键映射」（页面标题、页脚、任务标签）；
  - 首页补返回箭头（与「新建规则」页一致：折叠行、大标题左上方）；
  - 「运行状态」不再占列表一行，改为**连点页脚版本串 5 下**（相邻间隔 < 800ms）进入；
  - 「新建规则」上移到「功能状态」卡里（原「运行状态」的位置），列表下方那张重复的入口卡删除；
  - 规则行的「复制 / 删除」按钮收进**长按菜单**（Miuix 下拉浮层），删除改走二次确认对话框，
    危险项用红色，长按/菜单/确认各带一档触感。

### 文档
- 新增 `docs/规则编辑页-新增弹窗与未保存确认.md`：交互规则、实现位置、五个易踩点与模拟器验证表。

## [3.4.5] - 2026-11-27

> versionCode 30405，可直接覆盖安装 3.4.4（30404）。界面与文案调整，不涉及手势识别与 Hook 逻辑。

### 变更
- **关于页卡片改为半透明**：原先是不透明白卡铺在渐变背景上，与参考实现（LSPosed 2.2.1
  关于页）的观感不同。新增 `Theme.Colors.cardGlass`（浅色 `#A9FFFFFF`、深色 `#17FFFFFF`）
  与 `Ui.cardGlass()`，开发者卡与项目卡改用玻璃填充，能透出背景渐变。其它页面背后是纯色底，
  仍用不透明卡片。
- **单手模式设置分「常用 / 高级」两层**：原先 34 行平铺，滑三四屏才找到「模糊壁纸背景」。
  现在总开关下只留 触发模式、模糊壁纸背景、下滑展开面板、显示高级参数，其余（识别阈值、
  触发区几何、外观细节、动效、面板手势与互斥、算法常量）收进「显示高级参数」，展开后按
  识别判定 / 触发区 / 外观与动效 / 面板手势与互斥 / 算法细节 五段排列。
- **「显示高级参数」只切视图、不碰配置**：收起只是隐藏容器，各行仍持原值，写回照常。
  展开状态用进程内静态位记忆：冷启动一律收起，切主题导致页面重建时不会莫名收回。
- **三个模块总开关统一命名**：按键映射 / 单手模式增强 / 快捷菜单滑选 一律「启用 + 功能名」，
  「长按滑选菜单项」补成「启用长按滑选菜单项」。

### 优化
- **设置页文案重写**：去掉「算数」「手抖」「抬手就开」等口语，「一键」「推荐」「iPhone 式」
  等营销词，以及「通道」「重启两个作用域」这类内部黑话；术语统一为单手模式 / 触发区 /
  通知中心 / 控制中心 / 面板 / Dock。行摘要压到单行、不加句末句号。
- **运行时诊断读数同步改写**：写进 `Settings.Global` 的「最近一次下滑」提示改用客观描述
  （起手距底 / 区线 / 区内），去掉了「把高度调大」这类口语。
- **下拉说明集中到 `Cfg`**：原先设置页自己抄了一份模式说明，与 `Cfg.MODE_SUMMARIES` 已经
  写岔；现在统一从 `Cfg` 取（新增 `TOP_SIDE_SUMMARIES`）。

### 文档
- `docs/UI与交互规范.md`：§3.8 补常用/高级分层与折叠语义，§4.1 新增「设置项文案」规则
  （标题 2~6 字名词短语、摘要 ≤18 字、数值项句式、禁用语表、反例→正例）。

## [3.4.4] - 2026-11-27

> versionCode 30404，可直接覆盖安装 3.4.3（30403）。纯排版修正，不涉及手势、动画与 Hook 逻辑。

### 修复
- **分组标题与行文字差 2dp，出现两条对齐线**：`Ui.section()` 左右内边距 14dp，
  文字起点落在 26dp；而行标题与卡内分段标题都是 28dp。「系统信息」「按键映射」
  「开发者」这些分组标题因此比下面的行文字略靠左。改为 16dp 后，概览、设置、关于
  三页的三个标题层级统一落在 28dp 内容线（真机像素实测 78px → 86px）。
- **关于页开发者卡比其他卡片多缩进 2dp**：该行内边距是 18dp，其余卡片统一 16dp，
  头像左缘落在 30dp 而不是 28dp。改用 `Theme.ROW_PAD_H`，与分组标题和项目卡文字对齐。
- **开发者姓名字号出阶**：19sp 不在 Miuix 字阶内，也不与相邻行标题 17sp 构成层级，
  改为 20sp（title3）+ Medium 字重。
- **版本号格式两页不一致**：关于页 hero 是 `3.4.3(30403)`（规范 §2.3 的格式），
  概览页「模块版本」却多一个空格写成 `3.4.3 (30403)`。统一为无空格。

### 文档
- 新增 `docs/UI排版设计审核报告.md`：按 Miuix 规范 + SonyPods / LSPosed / KernelSU
  三方标杆逐页核对字号、字重、行高、间距，含真机 bounds 实测与复测结论。

## [3.4.3] - 2026-11-27

> versionCode 30403，可直接覆盖安装 3.4.2（30402）。

### 修复
- **控制中心右滑误退出**：在控制中心/通知中心里从屏幕下方起手往右滑想切到通知中心时，
  手指落点常在单手模式触发区内（`zoneTop` 之下）。此前 `shadeGuard` 只凭「起手在区内
  且 2.5 秒内」就拦掉 `NotificationPanelViewController.expandToNotifications`，
  MIUI 的切换动画随后卡在 `pending layout timeout`，最终 `switchHide` 把整个面板收起 ——
  表现就是「右滑没切成通知中心，反而直接退出控制中心」。
  现在拦截额外要求**确实在向下拖**（累计 `dy > 12px` 且 `dy > |dx|`，或单手模式已触发），
  纯横向 / 抬手方向的手势放行给系统。真机复现链路与修复后日志均已核对：
  `blocked shade expand` 0 次，无 `pending layout timeout` / `switchHide`；
  单手模式本身仍正常触发（`TRIGGER(E)`、conf=1.00）。

## [3.4.2] - 2026-11-26

> versionCode 30402，可直接覆盖安装 3.4.1（30401）。

### 修复
- **「为什么只有单手模式增强这个折叠动画奇怪」——真正原因查明并修好**：
  真机打点：单手模式那张卡完整高度 **6489px**，而滚动视口只有 2670px，
  开关行底边在 y≈1272，屏幕上只看得到约 **1398px**。
  此前（含 3.4.0 的 ω=10/480ms 分档）都是把 6489px 全程当动画距离：
  前约 250ms 裁掉的 5091px 全在屏幕外，用户看不到任何变化；最后才把屏幕内那 1398px
  一口气抽走 —— 观感就是「先卡住不动，然后内容突然消失」。
  按键映射（56px）、快捷菜单滑选（168px）两张卡整张在视口内，全程可见，所以很正常。
  现在 `expand(v, anchor, show)` 用开关行作锚点算出可见高度，**只对可见段做动画**，
  屏幕外那段在动画前一次性砍掉、动画结束后由 `WRAP_CONTENT` 恢复；
  三个模块统一 300ms / ω=20。真机日志确认每帧都在动屏幕内内容：
  `t=43ms h=1100 → 84ms h=698 → 168ms h=212 → 292ms h=28`。

## [3.4.1] - 2026-11-26

> versionCode 30401，可直接覆盖安装 3.4.0（30400）。

### 修复
- **主页横滑回退到单段弹簧**：3.4.0 按 Compose `flingBehavior` 的形状加了
  「decay 惯性滑行 → 越过目标页切 spring」两段，真机反馈「完全不好用，还不如之前」。
  原因是 Java 侧只能用 `OverScroller` 的摩擦模型，它的自然终点与页位对不上：
  - 不设 `minX/maxX` → decay 一路滑过目标页，越过那一帧把位置**直接赋成页位**，
    观感是「滑一截 → 啪地跳到页位」；
  - 设 `minX/maxX = 目标页` → `OverScroller` 撞界会在**一帧内**把 `currX` 赋成边界值，
    比纯弹簧更硬。
  Compose 那边顺，是因为 `splineBasedDecay` 的预测终点天然接近页位、到点速度≈0，
  换成 `OverScroller` 后这个前提不成立。
  现在删掉整套 decay 段，恢复为**单段临界阻尼弹簧** `k=400 / ζ=1.0`、初速取手指速度
  （限幅 2.4 页/秒），点底栏/代码切页仍是初速 0 的同一条弹簧，「一次手势只翻一页」不变。
  真机实测：关于 → 设置 → 概览 逐次一页，末页继续外甩纹丝不动，无 FATAL。

## [3.4.0] - 2026-11-26

> versionCode 30400，可直接覆盖安装 3.3.9（30309）。

### 修复
- **单手模式那张卡的折叠动画「很奇怪」**：这张卡展开后有 2600px（7 段、20 多行），
  而折叠曲线是统一 `ω=20 / 300ms` —— 前 50ms 就走完 60%，视口里那一千多像素内容等于
  「一帧被抽走」；卡片边界又在屏幕外，看不到任何空间变化，只剩内容凭空消失。
  现在**按容器高度分档**：普通容器保持 `ω=20 / 300ms`（按键映射、滑选两张卡不变），
  超过 1600px 的长容器改 `ω=10 / 480ms`。真机录屏：位移帧 2 → 6。
- **主页横滑「不像 LSPosed」**：根因是松手后把手指速度**直接灌进弹簧** —— 临界阻尼下
  50ms 就走完 90%，观感是「啪一下吸过去」。LSPosed 用的是 Compose
  `PagerDefaults.flingBehavior`，它实际是**两段**：`decay` 惯性滑行 + `spring` 吸附
  （`SnapFlingBehavior.TargetAnimation`）。现在照这个形状实现：
  - `OverScroller.fling` **不设 min/max**（设成目标页会让它一帧跳到边界，比纯弹簧更硬）；
  - 每帧比对目标页，**越过的那一帧切 spring**：位置落在目标页、速度取 decay 瞬时速度 ×0.7；
  - 滑行自然结束（速度耗尽）则位置不动，由 spring 走完最后一段；
  - 首/末页继续朝界外甩时目标等于当前页，直接按吸附处理（原地不动，不滑出去再拉回）。
  真机录屏：交界从 5%→10%→15%→30%→45%→60%→85%→98%，连续推进，没有跳变。
  慢拖/取消仍是纯临界阻尼弹簧，点底栏不变。

## [3.3.9] - 2026-11-26

> versionCode 30309，可直接覆盖安装 3.3.8（30308）。

### 修复
- **双击 / 长按不生效（真机反馈）**：根因是引擎「先取第一条条件成立的规则，再只从这一条里
  取映射」。用户最常见的配法是「短按下一首」「长按上一首」各建一条规则、都写在同一个键上，
  于是第二条永远轮不到 —— 短按能用，长按 / 双击完全没反应。
  现在改为**按按压方式分别匹配**：short / long / double / repeat 各自取「优先级最高、且
  提供了该按压方式」的规则，动作按各自规则的包投递（`Slot` 同时带 `map` 与 `pkgs`）。
  高优先级那条显式配「保持原功能」时仍然占位，不会被低优先级规则顶掉。
  自检新增 4 组用例（拆分规则短按/长按、拆分规则双击、pass 占位、包名跟随），共 83 例。

### 调整
- **设置页改成「一个功能一张卡」**：按键映射 / 单手模式增强 / 快捷菜单滑选 各一张卡片，
  卡片第一行是总开关，开关下面是该模块全部设置项（卡内折叠，开关关闭时卡片整体收短）。
  原先「开关一张卡 + 设置一张卡」外加多个分组大标题，整页被切成几段；现在卡内改用
  16dp 缩进的 `Ui.cardSection` 分段（触发与识别 / 触发区 / 外观 / 动效 / 模糊区手势 /
  互斥 / 高级），不再另起卡片。折叠动画仍是临界阻尼弹簧收缩、alpha 恒 1。

## [3.3.8] - 2026-11-26

> versionCode 30308，可直接覆盖安装 3.3.7（30307）。

3.3.7 里我误加了一条「一甩可跨两页」，属于自造行为，本版撤销。

### 修复
- **主页横滑恢复「一次手势只翻一页」**：Compose `PagerDefaults.flingBehavior` 的默认
  `pagerSnapDistance = PagerSnapDistance.atMost(1)`，LSPosed 用的就是这个默认值 ——
  **甩多快都只前进/后退一页**。3.3.7 用 `OverScroller` 估惯性距离再取整，快速甩动会跨两页，
  与 LSPosed 不符。
- 删除 `PagePager.flingPages()` / `flingProbe` 及配套注释；吸附目标恒为
  「朝甩动方向的邻页」（`floor(offset)+1` / `ceil(offset)-1`，offset 恰为整数也能前进），
  初速只影响到位快慢。

## [3.3.7] - 2026-11-26

> versionCode 30307，可直接覆盖安装 3.3.6（30306）。

对着 LSPosed（Vector 2.2.1，Compose + Miuix）逐帧比对后，把主页的三处动效/视觉对齐：

### 调整
- **顶栏改为「近乎不透光的纯色 + 很淡的高斯模糊」**：底色从 `pal.bg × 0.32` 提到 **0.90**
  （之前能明显看到卡片色块，就是用户说的「半透明」；1.0 又完全没有模糊了）。模糊层仍是
  本页内容 `RenderNode + RenderEffect(18dp)`，只在条体上留 10% 的色晕。
  条底保持硬截断，不画渐隐、不画阴影、不画分隔线 —— 与 LSPosed 一致。
- **折叠标题改为水平居中**（20sp Medium）。LSPosed / Miuix TopAppBar 的折叠标题就是居中，
  展开态大标题仍左对齐 26dp。
- **设置页模块折叠动画改为「临界阻尼弹簧收缩」**：只有高度在动、内容保持全不透明被上边缘
  裁切，`k=400 / ζ=1.0`（Compose `shrinkVertically` 的默认 spring），120Hz 下逐帧收紧，
  224ms 到 94%。旧版是 300ms 定时曲线 + 透明度淡出，少了 LSPosed 那种「快速收掉、
  不灰一片」的观感。
- **主页横滑改为 Compose Pager 手感**：
  - 松手吸附从欠阻尼（ζ=0.8，到位会轻微过冲再回弹）改为 **ζ=1.0 临界阻尼**，到位干脆停住；
  - 点底栏切页从 300ms 定时 EaseInOut 改为**同一条弹簧**（初速 0），起步即走，不是先加速。

### 修复
- 顺带修掉 `PagePager` 里 tween 分支残留的 `animFrom/animStartAt/TWEEN_MS` 死代码，
  弹簧积分步长上限从 50ms 收到 32ms（k 提到 400 后半隐式欧拉的稳定边界）。

## [3.3.6] - 2026-11-25

> versionCode 30306，可直接覆盖安装 3.3.5（30305）。

### 修复
- **概览页右上角「重启作用域」按钮看不见却能点**：图标透明度错误地挂在了折叠标题的
  `smallAlpha` 上（页面停在顶部时 `collapse = 0` → alpha = 0），但触摸命中区照常建立。
  现在图标常显（与 Miuix `TopAppBar` 的 action 一致），并补了按下态圆底。
- **重启作用域对话框缺「系统框架」**：`scope.list` 早已加了 `system`，但对话框里的条目是
  硬编码的两项。现在补上「系统框架」（`killall system_server`，等于软重启整机，约 20 秒），
  确认逻辑按 system → 系统界面 + 桌面 → 单项的顺序分派；system 分支不做 `reload` 兜底。
- **概览页「作用域」一栏**同步补上系统框架。
- **顶栏边缘仍有半透明**：底色从 `72% × solid` 降到 `32% × solid`（模糊层本身就是不透明的
  页面内容，整条不再透光），并去掉条底 8dp 渐隐，改为硬截断。

### 调整
- **设置页模块折叠动画改为 Miuix 形态**：高度从 0 连续伸展到实际高度 + 透明度同步，
  300ms 标准曲线；旧实现只做 alpha + 6dp 位移，高度是瞬间跳变的，所以不像 Miuix 的展开。
- **「按键映射」组两张卡紧贴**：补 12dp 间距（原先 0 间距，两个 16dp 圆角相扣形成一道凹槽）。

## [3.3.5] - 2026-11-25

> versionCode 30305，可直接覆盖安装 3.3.4（30304）。**所有构建变体统一签名**
> （`module/signing/hyperhand.keystore`），debug / fast / release 之间可任意覆盖。

### 调整
- **主页顶栏改为一页一条 + 高斯模糊**：每页的顶栏挂在页面容器里、随页面一起平移，各自维护
  自己的折叠进度（不再用一条共享顶栏按浮点页位置画两页标题，那种插值在两页折叠状态不同时
  会出现「谁都不是」的中间态）。背景改为「纯色基底 + RenderNode/RenderEffect 高斯模糊」，
  条底 8dp 渐隐。
- **关闭主页横向越界回弹**：首/末页继续往外拖时页面硬止，不再有抛物线橡皮筋；
  纵向滚动回弹不变。
- **设置页按三个功能模块重组**：按键映射 / 单手模式增强 / 快捷菜单滑选，每块最上面是该功能
  的总开关，关闭即折叠其余设置项；三个开关互相独立，折叠只隐藏不清值。

### 新增
- **智能按键映射的「新建 / 编辑规则」按米家「编辑自动化」重做**：名称卡 →「当以下情况发生」
  （按键触发）→「且满足全部/任一状态」（条件，标题可点开切换 AND/OR）→「就执行」（动作）→
  「规则设置」（启用 + 优先级）→ 底部整行「保存」。对话框一律 Miuix 默认贴底形态。
- **应用选择页支持推荐与置顶**：媒体类应用（`APP_MUSIC` 分类 / 已知包名 / `MediaBrowserService`
  且非系统应用）置顶为「推荐」分区并带推荐标记；「更换应用」时把当前已选应用置顶为「已选择」分区。

### 修复
- **长按、双击手势失效**：`interceptKeyBeforeQueueing` 收不到 repeat 事件（repeat 由
  `InputDispatcher` 内部合成），旧实现靠 `repeatCount > 0` 判长按，是死代码。改为 DOWN 起定时器
  自行判定，repeat 仅作兜底；短按在有双击/连按映射时推迟到多击窗口关闭再判，触发后清零计数。
- **「保持原功能」会吞掉音量键**：全 pass 时改为完全不接管，部分 pass 时松手补一次
  `adjustStreamVolume`。
- **「播放 / 播放暂停」无反应**：媒体会话选择只认 `STATE_PLAYING`，暂停中的会话永远选不到；
  改为 playing → paused → other 的候选序，包名限定仍严格。
- **进入「选择应用」要等几秒**：旧实现把「枚举全部应用 + 逐个 loadLabel + 逐个 loadIcon」
  同步做完才渲染第一行（真机秒级），且缓存只在进程内有效。改为磁盘快照（毫秒级出列表）+
  后台重扫 + 图标按可见行懒加载。
- **条件行显示包名**：改为应用名称，多个应用收敛成「A、B 等 N 个应用」。
- **下拉刷新是主题蓝**：`PullToRefresh` 不再传 `color`，回到 Miuix 默认灰。
- **主界面越界回弹误触发**：合并为单一状态机（一个 `rawDistance` + 一个 `offset` + 一个弹簧），
  惯性撞边改逐帧 watchdog，`apply()` 增加「不在边界不许有形变」的结构性约束，
  修掉「可点击子 View 吃掉 DOWN 导致 lastY 残留」造成的假越界。
- **顶栏变半透明**：撤掉折叠进度驱动的 alpha 渐显，条体恒不透明，底边渐隐固定 10dp。
- **概览页状态卡闪现「检测中」**：只保留「模块已激活 / 模块未激活」两态。
- **液态底栏刷新率偏低**：去掉 16ms 节流（曾把玻璃锁在 60fps）。

### 构建
- `debug` / `fast` / `release` 统一使用 `hyperhand` 密钥，避免跨变体覆盖安装时的
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
- 新增 `fast` 变体的说明补充：非 debuggable，ART 才会做 AOT，冷启动
  `TotalTime` 从 1320～1396ms 降到 421～517ms。

## [3.3.4] - 2026-11-20 - 正式版

> GitHub tag `v3.3.4`，LSPosed 模块仓库 tag `30304-3.3.4`；versionCode 30304，
> 可直接覆盖安装 3.3.3（30303），无需卸载。

### 调整
- **设置改动即时保存生效，去掉二次确认**：任何开关 / 数值 / 下拉改动后立刻全量广播给
  SystemUI 写入 `Settings.Global`，不再需要点「保存并立即生效」。移除设置页底部保存
  按钮与悬浮保存胶囊；状态栏改为「改动自动保存并立即生效」。动画曲线 / 时长仍额外
  即时下发。重启对话框移除「未保存会丢失」的提示。
- **设置页排版按 SonyPods / MIUIX 规范重整**：每组分一个 Card、组间靠 SmallTitle 间距，
  所有 summary 精简为一句话短句（8–20 字、无句号），移除卡片内 2 段大段静态说明块，
  校准提示 / 实测时长两个动态信息块文案缩短。配置项与数据流不变。
- **数值设置改用 MIUIX NumberPicker（单列）**：所有带数字的设置项不再用 Dropdown 选值，
  改为 SonyPods「临时电量岛显示时长」同款的 MIUIX `NumberPicker` 单列滚轮对话框
  （SonyPods 原版是「数值 + 单位」双列，这里选项文本已含单位故只需单列）。沿用
  「索引 = 值」数据流，min/max/step 生成的有限选项 + 回写链路完全不变。
- **选择类设置行右侧箭头改为 MIUIX 上下双箭头**：触发模式 / 缓动曲线 / 拉开哪个以及所有
  数值行右侧的 `>` 换成 MIUIX `ArrowUpDown`（逐字复制 miuix-ui 的 10×16dp 矢量，即
  `DropdownArrowEndAction`）。只有真正跳转到新页面的行（关于页链接）才保留 `>`。

### 重构
- **设置页选择类交互统一改为 MIUIX Dropdown**：原先「触发模式 / 缓动曲线 / 拉开哪个」
  三个单选对话框，以及约 23 个数值行的 Slider 参数子页，全部改为 MIUIX
  `OverlayDropdownPopup`（DropdownImpl 选项行 + 勾选态 + 每项说明）。数值项由原来的
  min/max/step 生成有限选项，索引即值，天然带范围与步长校验；选中即回写，配置键、
  广播与 SystemUI 写入链路完全不变。
- 通过一张全屏透明 Compose 宿主把 MIUIX popup 桥接进 Java 设置页：锚点按被点行定位，
  空间不足自动翻到上方；无弹窗时把触摸还给下层。切页、返回、重启对话框弹出前自动收起。
- 依赖开关的项（如慢滑所需距离、模糊半径、拉开哪个）补上 MIUIX 禁用态，值不清零，
  重新打开开关后原值保留。

### 修复
- **底栏液态玻璃「上下冒黑块」**：`ViewBackdrop` 原先把采样区裁到 source 边界，底栏贴底时
  折射 padding 会越出 source，被裁小后的位图铺不满 layer，透明缝被 lens/色散按 half4(0)
  采样成纯黑。改为保留完整 layer 区域、capture 用页面底色铺底兜住未覆盖区，并对
  `pressProgress` 做 `[0,1]` 钳制（连点/快拖时 spring 越界会叠加出错配中间态）。真机按住
  拖动抓帧确认黑块消失。
- **菜单开着时点背景图标误开其它 App**：记下长按宿主图标 A 的按下锚点，菜单开着时只有
  点在 A 上才「关菜单 + 打开 A」；点背景模糊的其它图标 B 只关菜单、不再误开 B。用图标
  矩形是否包含锚点判定（外扩 8dp 容差），不受图标重排 / View 复用影响。
- **数值滚轮偶发只显示一个数字**：本地化 MIUIX `NumberPicker`，把 `itemHeightPx` 改为直接
  由 `itemHeight × density` 取整派生，去掉依赖 `onSizeChanged` 的二次测量；修复首次打开时
  动画测量异常导致候选项挤成单个数字（第二次打开才正常）的问题。
- **重启作用域去掉第二个确认框**：第一个对话框点确定后直接重启对应进程，不再弹二次确认。

### 视觉
- **概览状态卡改为 SonyPods 同款按压反馈**：从 Java 手写 FrameLayout 换成真正的 MIUIX
  `Card`（`PressFeedbackType.Tilt` + `showIndication`），倾斜 / 高亮 / 回正全由 MIUIX 原生
  实现，按住边角持续形变、松手回正；颜色与右下角图标位置按原版逐像素还原。概览副标题
  移除「单手模式增强」只留「概览」，两处状态值字重加粗。
- **关于页对话框与许可**：信息类对话框统一改 MIUIX `OverlayDialog`；MIT 许可说明改为中文
  翻译在前、英文原文在后，保留 `HyperHand · MIT License` 字标。字标字重 800（不识别数值
  字重的 ROM 回退 Bold），Logo 用 G2 连续曲率圆角。
- **复选框形状对齐 MIUIX**：26dp 圆形 `CircleShape`、未选中 secondary 实心、勾路径取
  miuix viewport(23f) 三点坐标，按物理弹簧在 secondary/primary 间过渡。

## [3.3.3] - 2026-11-18 - 正式版

> GitHub tag `v3.3.3`，LSPosed 模块仓库 tag `30303-3.3.3`；versionCode 30303，
> 可直接覆盖安装 3.3.2（30302），无需卸载。

### 修复
- **去掉滑选过程的两处多余震动**：此前长按图标、手指刚开始朝菜单移动（还没滑到任何
  菜单项）时就会先震一下，且滑到目标项抬手提交时又会补震一下。现在这两处都去掉，
  只在手指真正滑到某个菜单项、高亮切到它时保留一记震动（仍是系统「设置」边界反弹
  同款手感，MIUI 预烘焙 effect 201）。滑选的其余行为不变。

## [3.3.2] - 2026-11-18 - 正式版

> GitHub tag `v3.3.2`，LSPosed 模块仓库 tag `30302-3.3.2`；versionCode 30302，
> 可直接覆盖安装 3.3.1（30301），无需卸载。

### 修复
- **关于页 logo 在浅色模式下渐变一卡一卡**：`MiuixBg.notifyTint()` 里残留了
  `if (tint == lastTint) return;` 的 8bit 量化去重 —— tint 整数不变时整个 `onTint`
  回调被跳过，关于页的玻璃 logo / 字标跟着定格，只在 tint 跨过整数边界那一刻一次性
  跳过去。浅色把 tint 压到 V=0.30、值域更窄、整数重复更多，所以阶梯远比深色明显。
  真机与本机模拟器 60fps 逐帧取色实测：修复前浅色 logo 相邻帧最大跳变 15.4 级、
  最长连续 4 帧完全不动（同屏背景区仅 1 次 3.2 级、深色 logo 最大 5.6 级），修复后
  定格游程归零、每帧平滑更新。`onTint` 里的 `captureGlass` 每次都重拍当前背景、并不
  使用 tint 参数，tint 只喂给有自己去重的 fallback，因此去掉这层去重不影响正确性，
  且让玻璃录制重新对齐 UI 规范 §5.3 的「随 60fps 背景逐帧走，禁止阈值 / 隔帧」。
  深色路径同样受益（更平滑），无回归。

## [3.3.1] - 2026-11-18 - 正式版

> GitHub tag `v3.3.1`，LSPosed 模块仓库 tag `30301-3.3.1`；versionCode 30301，
> 可直接覆盖安装 3.3.0（30300），无需卸载。

### 文档
- 两个仓库的 README 重写为「解决什么问题」的白话结构，浅色截图为主、深浅都补全，
  并全部换成 3.3.1 实机截图。
- 修正 LSP 模块仓库 3.3.0 release 说明错用旧模板（只写单手模式）的问题。
- 签名密钥（keystore）单独整理成带密码与指纹的备份包，方便跨机器复用。

> 本版本无功能代码变动，功能与 3.3.0 完全一致。

## [3.3.0] - 2026-11-18 - 正式版

> GitHub tag `v3.3.0`，LSPosed 模块仓库 tag `30300-3.3.0`；versionCode 30300，
> 可直接覆盖安装 3.1.0（30100），无需卸载。

### 新增
- **桌面快捷菜单 iOS 式滑选**：长按桌面图标弹出快捷菜单后，手指不离开直接滑到菜单项，
  抬手即选中并打开对应功能；同时保留原生「长按后点击菜单项」。
  - 菜单在图标上方或下方都能用，不写死方向；滑选时图标和其他图标都不重排、不漂移。
  - 命中项显示浅灰按压态；震动改为与系统「设置」列表边界反弹同款（MIUI 预烘焙 effect 201）。
  - 菜单开着直接点图标：关菜单并打开 App，且不再附带震动。
  - 点菜单项（如「添加小部件」）只交给原生处理，不再误打开菜单下方压着的图标。
  - 新增设置开关 `oh_tuner_slide_enabled` 及角度 / 容差 / 最小位移参数。
  - 完整设计与真机日志分析见 `docs/桌面快捷菜单滑动选择.md`。

### 工程
- `module/signing/hyperhand.keystore` 改为随仓库提交，保证换机器构建仍是同一签名，
  可持续覆盖安装，不再因签名变化被要求先卸载。

## [3.1.0] - 2026-11-13 - 正式版

> 正式版：GitHub Release 标为 latest（**非 prerelease**），并同步到 LSPosed 模块仓库
> tag `30100-3.1.0`。versionCode 30100，可直接覆盖安装 3.0.3-beta.2（29906）。
> 应用 / 模块显示名统一为 `HyperHand`，不再带「-媲美iPhone的单手模式增强」。

### 变更
- **名称统一为 `HyperHand`**：`strings.xml` 的 `app_name`（即 `android:label`，
  LSPosed 读到的应用名）、README 标题、模块仓库 release 名一律只用 `HyperHand`；
  模块仓库 description 已为 `HyperHand`，历史 release 标题里的长名一并修正。

### 修复
- **关于页 logo 渐变卡顿（真机实测纠错）**：先按「配色几秒才变一次」的推测给
  `MiuixBg` 加了 RGB 阈值 + `AboutPage` 64ms 重录间隔，结果无线/有线 adb
  60fps 静止录屏显示 logo 区域出现单帧 8.4 级亮度跳变、最长连续 4 帧完全不变的
  阶梯（同屏纯背景区只有 1 次 3.2 级跳变）—— 降频反而制造了可见阶梯。
  现撤销全部阈值与间隔，tint 与两个 `GlassNode` 重录都跟着 60fps 背景逐帧走，
  与 SonyPods `textureBlur(backdrop)` 的连续采样一致。
- **顶栏渐进模糊：延迟 + 马赛克 + 非高斯（真机实测纠错）**：无规律慢滚录屏显示
  49/209 帧「内容动了但模糊带没动」（双缓冲的一帧延迟），规律滚动则 0 帧；
  0.20× 采样位图放大回屏幕会形成 5×5 像素块。现改为 1:1 全分辨率采样、
  三张位图轮换（同帧内容、零延迟，且位图 2 帧内不被覆写）、32 tap Vogel 盘 +
  `exp(-2(rr/r)²)` 高斯权重核（σ = r/2）、采样外扩 24dp ≥ 模糊半径。
  时间节流与固定 front/back 双缓冲均已移除。
- **顶栏渐进模糊上下滚动闪烁（上一条修复的回归）**：单缓冲每帧 `eraseColor`
  擦掉的正是 GPU 还在异步读取的那张位图；且 `bottom`（顶栏 + fade）随 collapse
  每帧变化 1px，1:1 全分辨率下 `ensure()` 每帧 recycle / new 一张约 1200×350
  的 ARGB_8888 位图并重建 BitmapShader 纹理，慢滚时交替出现半更新纹理与底色帧。
  现固定采样窗尺寸（单调递增 `bandH` + 240dp 下限，滚动期间零位图分配），并改
  三张轮换：capture 写临时张、整条画完才提升为 `active`，shader 每帧绑定本帧
  内容（零延迟）、该张之后 2 帧不被覆写（无撕裂）；软件绘制异常只丢弃这一张、
  沿用上一帧完整内容，绝不闪底色。
- **深色模式顶栏渐进模糊偏白（未适配）**：`surfaceColor()` 错用了 `pal.card`
  （= miuix `surfaceContainer`，深色 `#242424`），而 SonyPods `BarBlur.kt` 用的是
  `MiuixTheme.colorScheme.surface`（= 本项目 `pal.bg`，深色 `#000000`）。浅色下两者
  都接近白，一直没暴露；深色下用 `#242424` 以 `SURFACE_ALPHA=0.66` 混到纯黑采样
  内容上，会把顶栏从 `#000000` 提亮成 `#2E312F` 的灰白雾罩。真机取色：展开态顶栏
  `(5,6,6)`、滚动折叠后 `(44,50,46)`，页面主体为纯黑。现改回 `pal.bg`，并把采样
  底色兜底统一为 `#000000`；`SURFACE_ALPHA` 与高斯核等参数不变。
- **概览页状态卡不检测 LSPosed 启用状态**：`statusLevel()` 只看 app 总开关和
  「7 天内历史心跳」，模块在 LSP 里禁用后仍显示绿色「模块已激活」。现改为
  `ACTION_SET/probe` 主动探测作用域进程、只有探测后回写的心跳才算已激活；
  未启用一律红色「模块未启用」（探测在途显示中性「检测中」），状态卡仍不可点击。

## [3.0.3-beta.2] - 2026-11-13 - 横滑边界与交互一致性修复

> 测试版，只发 GitHub Releases 的 prerelease 通道。versionCode 29906，可直接覆盖安装
> 3.0.3-beta.1（29905）。同时同步到 LSPosed 模块仓库，tag `29906-3.0.3-beta.2`。

### 修复
- **横滑左右边界阻尼生硬且不跟手**：`PagePager` 旧实现每帧把「已压缩过的
  `offset`」再套一次边界阻尼公式（复合压缩），越拉越拉不动、往回拖也不沿原路
  返回；松手时又把整段 fling 速度灌进 `k=300` 的切页弹簧，越界会先大幅外冲
  再猛绷回。现按 miuix `MiuixOverscrollEffect.rawTouchAccumulationX` 的语义，
  用未压缩的 `rawOffset` 1:1 记录手指位移，超界部分一次性过
  `DefaultParabolaScrollEasing`（复用纵向 `MiuixScrollView` 的同款曲线，
  饱和 20% 屏宽）；越界松手改走软弹簧 `k=190 / ζ=0.9` 并把甩速限幅到
  0.9 页/秒，回弹动画被手指接管时反解当前形变、不跳位。页内吸附保持不变，
  仅在甩向首/末页时把朝界外的速度分量折减 75%，避免撞墙式回弹。
- **关于页 logo / 应用名显示错误**：玻璃混色链里第三层 Lab 被「明度归一 LIFT」
  近似替代，把方块压成灰紫、字标发浅。现逐行移植 miuix `Shaders.kt` 的
  `mi_blend_color_burn / color_dodge / blendLinearLight / labColor`（含预乘语义与
  `L' = (n−m)·L + 100·m` 明度映射），浅/深色两套 BlendColorEntry 与 SonyPods
  `aboutArtworkBlendColors` 完全一致；logo 手掌图形改为**镂空蒙版**（透出背景，
  与参考 about_logo_mark.png 同构），不再叠白色描边；字标 34dp → 40dp
  （帽高 28.5dp 对齐参考字标）。
- **顶部渐进式模糊与参考不符**：雾罩强度 0.45 → 0.66（SonyPods
  `TOP_BAR_SURFACE_ALPHA`）；强度曲线补上 smoothstep（`1−pow(smoothstep(u), curve)`），
  叠色渐变改用 miuix 的 `rampWeight`；模糊半径去掉 0.5 折减，与 miuix
  `σ = radius·0.5` 同档。
- **对话窗口样式 1:1 对齐 SonyPods 概览右上角按钮弹出的窗口**：MiuixDialog 从
  WindowDialog 规格（288dp/28dp 圆角/竖排按钮）改到 OverlayDialog 规格
  （420dp 上限、圆角 32dp、外 12dp、内 24dp、标题 18sp、说明 16sp、蒙层
  黑 α0.3/0.6），双按钮横排等宽间距 8dp（左 secondary / 右 primary，17sp）。

### 变更
- **调试模块隐藏**：设置页不再展示「调试」分组；仅可通过**关于页连续点击
  logo 五次**（800ms 连点窗口）唤起调试窗口（含调试日志开关 / 运行状态 /
  最近手势 / 快速操作）。设置页保存配置时调试日志值直通，不会被重置。

### 文档
- 新增 `docs/UI与交互规范.md`：UI 与交互的权威约定（对话窗口、渐进模糊、
  关于页 hero、调试入口、性能约束、文件映射）。
- 新增 `docs/miuix-components.md`：miuix 组件目录（11 模块 / 117 组件条目，
  含子组件层级、样式数值、交互逻辑，及 HyperHand View 侧对齐速查表）。

## [3.0.3-beta.1] - 2026-11-11 - miuix UI 一致性改造

界面层仍是 `:module` 的纯 Java 原生 View（按 [miuix](https://github.com/compose-miuix-ui/miuix)
规格手写，不引 AndroidX / Compose）；`:glass` 继续用 miuix Compose 库画液态玻璃底栏。
新增 / 替换的组件全部按 miuix 的规格实现。

### Added

- **`ProgressiveBlur`**：按 [SonyPods `BarBlur.kt`](https://github.com/Mercury000/SonyPods)
  的 `LayerBackdrop + progressiveTextureBlur` 架构实现。`backdrop` 是顶栏背后内容的一条
  低分辨率（0.30×，约 324×94）采样位图；渐进模糊由 AGSL `RuntimeShader` 在 GPU 上
  一次画完：<b>Vogel / 黄金角螺旋盘采样</b>（`r_i = sqrt(i/N) * R`，面积均匀地填满整个
  圆盘）配高斯权重 `exp(-2i/N)`，每像素再用 interleaved gradient noise 把整组采样点
  随机旋转一个角度，20 个 tap 就能得到连续、无方向伪影的高斯核。
  <b>无 CPU 盒式模糊、无 getPixels/setPixels、无逐像素合成</b>；事件脏驱动，
  静止时零开销。软件画布 / 不支持 RuntimeShader 时退化到小位图上的一次轻量模糊 + 渐变遮罩。
- **`MiuixScrollView`**：按 `overScrollVertical()` 的 NestedScroll `onPostScroll` 语义
  重做 Rubber Band —— 系统 ScrollView 先消费滚动，<b>同一帧</b>把未消费的 delta 转成
  越界位移；`DefaultParabolaScrollEasing` 抛物线阻尼，关闭系统 EdgeEffect。拖动与
  <b>惯性（fling）</b>两条路径都能撞出橡皮筋，抬手速度作为弹簧初速度延续惯性；
  位移期间刷新顶栏模糊与玻璃底栏的 backdrop。
  弹簧取 SonyPods 参数再放软一档：`springStiff 280 → 190`、`dampRatio 1 → 0.9`
  （ω 从 16.7 降到 13.8，实测 404px 回弹由 ~240ms 变为 ~330ms，末端带一点点余韵）。
- **`MiuixCheckbox`**：三态 `Checkbox`（`ToggleableState.On / Off / Indeterminate`）。
- **`MiuixDialog`**：全应用唯一的弹窗组件（28dp 圆角、20dp 内边距、18dp 外边距、
  maxWidth 288dp、dim 0.32、fade + scale 进出场）。卡片底强制不透明。

### Changed

- **顶部折叠栏**：删掉 `#17171B @ 86%` 实色黑底与 0.75dp 分隔线，改为折叠时才淡入的
  GPU 渐进模糊（顶部全额 → 底边像素级清晰）；展开态完全透明、完全不采样。
- **设置页 Slider 下沉**：一级页面只留「标题 + 当前值 + 箭头」，点行打开参数子页，
  miuix Slider 只在子页出现；滑杆只在轨道矩形内响应，空白区域不再跳值。
- **开关行**：只有右侧 `SwitchView` 自身可点 / 拖，行标题、摘要、空白一律不触发切换。
- **弹窗统一**：四个 dialog 全部走 `MiuixDialog`；概览右上角的 `PopupWindow` 菜单与
  关于页隐藏调试页的 root overlay 一并移除。
- **概览右上角按钮**：`MiuixDialog` + 嵌套 `MiuixCheckbox`，父项三态联动两个子项。
- **对话框排版**：maxWidth 420dp → 288dp，圆角 / 内外边距 / 按钮高度 / 标题正文与选项
  字号行距全部收紧；长文本与调试页的最大高度同步下调。
- **头像**：换成用户指定的 940×940 图片，绘制改为 `Matrix` 等比 centerCrop 铺满圆形，
  不再用 1:1 `drawBitmap` 只截大图左上角。
- **触感**：移除切页 `tick`、滚动到顶/底 `edge`、Rubber Band 边界 `edge` 三处震动，
  点击类控件触感保留。

### Fixed

- **顶栏切页时标题突然跳变**：旧实现把标题画在固定位置，只在 `onPageSelected` 里
  整块换成新文案，横滑过程中根本看不到过渡。现在 `TopBar.setPages()` 一次注册全部页面的
  文案，`setPagePosition(浮点页索引)` 在横滑时把「滑出的页」和「滑入的页」两套标题
  **按手势比例整体平移**（旧页向左移出 `-frac×w`、新页从右移入 `(1-frac)×w`），
  副标题、大标题、右上角按钮一起跟随；折叠高度统一为「状态栏 + 52dp + 大标题块」，
  不再因为切到没有大标题的关于页而整体矮一截。模糊带的强度取两页折叠状态的插值，
  切换过程中带高与模糊厚度同样连续。
- **模糊卡顿 + 不实时**：采样走的是软件 Canvas（`contentHost.draw()` 光栅化到 Bitmap），
  成本正比于目标位图面积，0.30×（324×94）、滚动时每帧重来一遍，慢机上直接掉帧。
  降到 **0.20×（216×63，面积 −56%）** 并去掉上一版为保证流畅而加的 48ms 节流 ——
  重点在于<b>恢复每帧刷新</b>，模糊重新跟着内容实时变化。真机实测（小米 14 / Android 16，
  `dumpsys gfxinfo`）：1729 帧中 Janky **1 帧（0.06%）**，99 分位 14ms。
- **顶部模糊像「散光」而不是高斯**：旧的 13-tap 是「中心 + 两个固定环」的十字 / 对角
  布局，半径几十 px 时相邻采样点间距接近 20px，圆盘中间是大片空洞，采出来是多重鬼影。
  改成 Vogel 螺旋盘 + 高斯权重 + 每像素 IGN 抖动旋转，采样点均匀填满圆盘，条纹被摊成
  不可见噪声。半径同时从 `16dp × 1.5`（66px，整条糊成一片雾）改回真实高斯半径
  `16dp × 0.5`（采样盘半径 22px，σ≈11px）—— 保留形状轮廓、看不清文字。
- **惯性停在临界位置、必须再滑一次才回弹**：`computeScroll()` 原先只在「上一帧
  `scrollY` 有变化」时才判定撞边界。若抬手瞬间就已经贴边、或 scroller 立刻结束，
  `delta` 从头到尾都是 0，判定永不成立。现在 `fling()` 先做一次即时判定（贴边就直接
  用速度生成冲量，连 scroller 都不启动），`computeScroll()` 里再用初始速度折算单帧
  增量兜底。实测 `atB=true cons=0 avail=80`，到底后每帧余量全部转成形变。
- **橡皮筋回弹期间整机 ANR**：`onOverScroll` 每帧都 `topBar.invalidate()`，而顶栏一重绘
  就要 `ProgressiveBlur.capture()` 把整个内容树重画进采样位图。回弹 30 多帧全部重采样，
  在慢机上直接把主线程拖死（模拟器实测 777ms/帧）。现在顶栏与玻璃底栏的刷新按
  ~20fps 节流（48ms），回弹本身只有 300ms 左右，视觉上看不出滞后，开销降到 1/3。
  另外给 `rebound` / `impulseTick` 两个动画循环各加了帧数硬上限（180 / 150 帧），
  任何数值异常都不会变成常驻循环。
- **模糊不实时（真正根因）**：`capture()` 把 window 坐标塞进 `translate`、又用 window
  偏移做 `clipRect`，两套坐标系不一致，实际截到的几乎全是纯色底色；再叠加"指纹命中
  就复用上一帧"的缓存，画面永久定格。现在坐标一律按项目里已验证正确的
  `ViewBackdrop.capture()` 写法（region 用 source 局部坐标，`scale → translate → clipRect`），
  并且<b>彻底删除指纹与全部缓存判断，每次重绘无条件重采样</b>。另外 `MainActivity` 原来
  只在 `collapse` 值变化时才 invalidate 顶栏，滚动到底 `collapse = 1` 饱和后就再也不重绘；
  现在滚动、横向切页、Rubber Band 位移都显式驱动顶栏重采样。
- **模糊一出现就很卡（真正根因）**：旧实现每帧在 UI 线程对约 16 万像素做
  `getPixels` → 全像素 hash → 两次三趟盒式模糊 → 逐行 `mix` 合成 → `setPixels`，
  几十毫秒一帧。现在 CPU 只剩一次约 2 万像素的 `View.draw`，模糊在 GPU shader 里完成；
  且改成事件脏驱动，<b>不注册常驻帧回调</b>，静止时顶栏零重绘、零占用。
- **关于页顶部一条遮挡带**：展开态也画模糊带 + scrim，未滚动时就有；现在只在
  collapse > 0 的头部 30% 内淡入，且不再画任何分隔线（那条线就是"黑线"）。
- **Rubber Band 只有按住拖才出现，惯性滚到底不弹**：旧实现只处理 `onTouchEvent`，
  而「快速滑一下再抬手」的后续滚动完全由 `computeScroll()` 里的 `Scroller` 驱动，
  根本不经过 `onTouchEvent` —— 惯性撞到边界时完全看不见，必须再滑一次（手指还在屏上）
  才触发。现在覆写 `computeScroll()` 检测「scroller 还在跑但 `scrollY` 已不再变化」，
  用上一帧的滚动增量做冲量，按惯性逐帧衰减累积成越界位移再弹回；`onTouchEvent` 与
  `computeScroll` 两条路径的手感因此一致。实测一次强甩：抬手 → 273ms 后自动撞出
  橡皮筋 → 599ms 渐进冲出 → 回弹，无需二次滑动。
- **Rubber Band 位移不连续**：旧实现每帧 `rawDistance = -available`（覆盖），位移只
  反映单帧增量，手指匀速拖动时反而一顿一顿。现在累加；并且统一成
  `Δ形变 = -available` 一个式子，反向拖动时 `available` 与形变同号、绝对值自然减小，
  形变跟着手指收回，不会像旧实现那样「继续外推 / 系统已消费」两个分支都不命中而卡住不动。
- **Rubber Band 先卡一下 + 退出后跳变**：旧实现进入 band 后完全绕过
  `super.onTouchEvent`，ScrollView 的 `mLastMotionY` / `mIsBeingDragged` /
  `VelocityTracker` 停在旧帧，退出 band 的第一帧按「当前 y − 过期 y」算位移，内容会猛跳；
  再次按下还要重过 `touchSlop`。现在每个 MOVE 都原样转发给系统（等价于 NestedScroll
  先让滚动源消费），只用 `getScrollY()` 前后差值取余量转成 `translationY`，状态机全程同步。
- **底部内容被悬浮底栏压住**（Android 15 模拟器实测）：insets 只取了
  `systemBars()`，而 API 35 强制 edge-to-edge 后手势导航的底部条属于
  `systemGestures()` / `mandatorySystemGestures()`，`systemBars().bottom` 返回 0，
  页面底部 padding 少算一个手势条高度 —— 设置页「起效速度」、关于页「使用说明」
  的最后一行被胶囊底栏盖住。现在几类 inset 一起取并集。
- **Slider 比例失真**：`MiuixSlider` 把 `SliderDefaults.MinHeight = 28dp`（触点高度）
  误当成轨道厚度，轨道被画成 28dp 粗的实心条、圆钮被 `r * 0.72` 撑到接近轨道高，
  看起来像 iOS 的粗 SeekBar。现在按规格改为轨道 4dp / 圆钮 20dp / 28dp 只作触摸区，
  命中区也收窄到圆心上下 14dp（实测点轨道上方空白不再跳值）。
- Dialog 在 MIUI 上透出下层页面：卡片底色改用不透明 surface。
- **液态底栏选中项「跳来跳去」**（滑动底栏 / 点击底栏 / 双击来回切时偶发）：逐帧
  录屏（600×1336 @≈100fps，按 pts 还原位移曲线）定位到三个独立成因，都已修掉。
  1. **`snapshotFlow` 捕获了过期的 `selectedIndex`**。上一版为了消除「每帧重建
     `LaunchedEffect`」把驱动改成单个长驻协程，但 effect 只启动一次，直接引用
     `position` / `selectedIndex` 这两个参数会把**首次组合时的值**永久捕获 ——
     实测第二次点击后 `sel` 仍是初始的 0，指示器于是从第 2 格往第 0 格跑，
     正常应当去第 2 格。改用 `rememberUpdatedState` 后再经 `snapshotFlow` 读取。
  2. **拖动底栏时用弹簧追手**。`onDrag` 里对每一帧 `dragAmount` 调 `updateValue`
     （`animateTo`），快速划过两格时弹簧追不上、松手再补一段；且累加基准取的是
     `targetValue` 而非当前位置，按下瞬间弹簧才走一半时第一帧会倒跳。改为
     `dragTo()` 直接按位置（`value` 为基准累加），松手才交给弹簧收尾。
  3. **点击被重复启动动画**。按下时 `onDragStarted` 已启动朝该格的归位动画，
     宿主 `select()` 回来又 `animateTo` 一次，把速度清零后重新加速 —— 视觉上
     「顿一下再窜出去」。现在 `targetValue` 已是目标格时不再重启。
- **底栏跨两格切换时中途卡住**：`PagePager` 的整段 280ms 滚动（点底栏触发）原本也
  逐帧下发 `onPageScrolled`，让指示器跟着这个粗粒度步长走（单帧位移可达半格），
  与自身弹簧互相拽。新增 `following` 标志区分「用户手势滚动」与「代码触发的切页」，
  只有前者让底栏跟手；后者由底栏自己的弹簧走完。
- **切到概览页顶掉一帧**：`OverviewPage.refresh()` 每次都把「系统信息」整块
  `removeView` + 重建，连带整页 ScrollView 重新测量；此时底栏指示器正走到一半，
  这一下布局吃掉几帧，表现成「卡住再跳」。系统信息在进程生命周期内不变，改为只构建一次。
- **`PagePager` 按住吸附动画不放**：吸附过程中按下但不横滑就松手，`settling` 已被
  清掉而 `offset` 停在两页中间，页面会永久卡在半路。抬手时把被打断的吸附续上。
- **底栏指示器移动过快到看不出过程**：`valueAnimationSpec` 的 stiffness 1000f
  意味着跨一格只要约 32ms（比一帧还短），观感是「闪过去」。降到 500f
  （临界阻尼、约 210ms 到位），既跟得上手指也不再像瞬移。

### 关于页按参考版式重做

版式来自 [SonyPods](https://github.com/Mercury000/SonyPods) 的 `AboutPage.kt`
（它又是照 miuix 示例应用的 AboutPage 抄的）：整屏 OS3 流动背景 + 固定 hero
（图标 / 字标 / 版本号）+ 滚动内容，hero 与背景按同一个 `scrollProgress` 依次淡出。
真机（小米 14，1200×2670 @480dpi）逐像素比对过，logo 顶部落在 188dp、直径 88dp，
与参考完全一致。

- **背景**：`MiuixBg`（miuix 官方 OS3 `BgEffectBackground` 的 AGSL 移植，配色就是
  `OS3_PHONE_LIGHT` / `OS3_PHONE_DARK` 那两组）铺满整屏，透明度 `1 - scrollProgress`。
  以前它只铺 420dp 高的一块、底部渐隐到页面色，现在铺满并整体淡出，中途不会切出色带。
- **hero 元素淡出顺序**：版本号 `0.05–0.20` → 字标 `0.20–0.35` → 图标 `0.35–0.50`，
  每段各带 `1 → 0.95` 的缩放，与参考的 `graphicsLayer` 窗口一一对应。
- **logo 与字标是「玻璃」的**：参考里两者都套了 `textureBlur(blurRadius = 200f/150f,
  contentBlendMode = DstIn)` —— 用背景的模糊采样去填图形，所以<b>颜色会跟着背景变</b>。
  miuix 的 `textureBlur` 实际是把<b>RenderEffect 链</b>（高斯模糊 → 混合调色，全部由
  RuntimeShader 驱动）挂在 Compose 图层上，而 Compose 图层背后就是 RenderNode；
  HyperIsland 的关于页同样引用了 `RenderEffect + RuntimeShader`。这里按同一套做
  （`GlassNode`）：把图形背后那块背景录进一张 `RenderNode`，挂
  `RenderEffect.createChainEffect(混合, 模糊)`，再用图形 alpha（圆角矩形 / 字形 Path）
  当蒙版画出来。混合项取 SonyPods 传的那组 `BlurColors`：
  `(0xCC4A4A4A, ColorBurn)` 与 `(0xFF4F4F4F, LinearLight)`，公式就是 W3C 合成规范里那两条。
  第三个 `(0xFF1AF200, Lab)` 项无法照搬（Lab 混合要转 CIELAB，miuix 的实现藏在 11KB
  的 AGSL 里且提不全），它的作用是把前两项压得过暗的结果拉回可见区间，这里用一个
  明度归一替代。实测同一位置的 logo 会随背景色相变化：紫背景 `#391162`、偏蓝背景 `#273569`。
- **滚动预算**：参考的第一屏内容块是 `Box + fillParentMaxHeight`，天然撑满一个视口，
  滚到底时进度正好到 1。顺序布局做不到这点，于是补了一个尾部占位，按「除尾部以外的
  内容高度」反算，保证<b>可滚动距离 ≥ hero 预算</b>且一次收敛不抖 —— 否则卡片少的时候
  滚到底进度只有 0.3，背景和 logo 都淡不干净。
- **顶栏**：关于页不走渐进模糊（自带整屏渐变，压一条模糊带会切出色带）。滚动过程中
  顶栏保持透明、内容从底下经过，快到底时才化成页面底色（`gradient → 纯白`）；
  标题 `((scrollProgress - 0.35) / 0.65)` 淡入并居中，横滑切页时<b>不跟着平移</b>，
  改为原地交叉淡出。
- 删掉参考版式里没有的「项目」小标题，开发者卡片下面直接接卡片。

### 图标

- 桌面图标原来是手绘的三指简化图，与关于页 logo 不是同一套。改为<b>自适应图标</b>
  （`mipmap-anydpi-v26`）：背景是对角渐变（`#6DA5FF → #3482FF → #2564CD`，
  即 `blend(accent, white, .28) → accent → blend(accent, #001A4D, .28)`），
  前景直接复用关于页那条 Material Symbols `front_hand` 路径（按可见区 72dp 的 56%
  缩放居中，与 `AppIcon` 的 `inset = 0.22 × s` 完全一致），另带 monochrome 层
  以支持 HyperOS 的主题图标。旧的 `drawable/ic_hyperhand.xml` 已删除。

### 修复

- **顶部 inset 多算了 12dp**：`statusInset` 取的是 `systemBars | displayCutout |
  systemGestures | mandatorySystemGestures` 的并集，小米 14 上 `mandatorySystemGestures`
  的 top 是 170px（56.7dp），而真正的状态栏只有 134px（44.7dp）。顶部只认前两者、
  底部继续取并集后，所有页面内容整体上移 12dp，关于页 logo 也因此落到正确高度。
- **关于页滚到底背景还是半透明**：`scrollProgress` 到不了 1（见上「滚动预算」）。
- **关于页滚到底液态底栏发黑**：渐变淡出后根容器是透明的，底栏采样不到颜色。
  给根容器补一层页面底色，同时保证「渐变 → 纯白」的过渡中间不露窗口底色。
- **液态底栏滚到底出现紫色雾团与「HyperHand」残影**：底栏采样走的是软件画布
  （`ViewBackdrop` 把整页 draw 进 Bitmap），而 hero 的 `GlassNode` 用的是
  `Canvas.drawRenderNode` —— RenderNode 的坐标**不跟随 canvas 的 translate/clip**，
  于是在采样图里被画到画布原点，正好糊在底栏那块。现在 `GlassNode#draw` 遇到软件
  画布直接不画，让 `AppIcon` / `GlassText` 退回纯色（`MiuixBg` 早有同类守卫）。

### 性能

- **关于页渐变在动的时候掉帧**：背景是满屏 AGSL，1200×2670 每帧重跑一遍着色器，
  在小米 14 的 120Hz 上实测 GPU 中位 8.4ms —— 已经超过 8.3ms 的一帧预算。
  按 SonyPods（miuix 示例 AboutPage 的同一套做法）对齐两处：
  1. 动画按 60fps 节流（SonyPods 的 `minDeltaNanos = 1s/60`），余下的帧留给合成；
  2. 背景完全淡出后把动画一并停掉（SonyPods 的 `alpha <= 0 → cancel`），
     滚到底静止时帧数归零。
  另外 hero 玻璃的重录改成隔帧：参考实现里 logo / 字标是 `textureBlur`，
  直接从背景图层 GPU 采样、零 CPU 成本；我们得显式把背景重录进 RenderNode，
  这正是滚动时最大的一笔开销。背景 5 秒一周期，30fps 的更新速度看不出差别。
  结果：静止渐变由 240fps 满速（每帧超预算）变成稳定的 60fps，帧间隔 16.6ms、
  GPU 中位 6.3ms；滚动时 GPU 由 8.4ms 降到 7.0ms。

## [3.0.2-beta.2] - 2026-11-10

> 测试版，只发 GitHub Releases 的 prerelease 通道。versionCode 29904，可直接覆盖安装
> 3.0.2-beta.1（29903）/ 2.9.10（20910）。

底栏从 Java 手写近似，换成 [LiquidDemo](https://github.com/zPan2327/LiquidDemo) 的 Compose 组件
（后者复刻自 [miuix](https://github.com/compose-miuix-ui/miuix) 与
[AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)）。界面版式、参数一律不变，
只把「假折射」换成真 AGSL：24dp 圆角矩形 SDF 透镜、按压色散 0.5、跟随重力的镜面高光、阻尼拖拽指示器。

### Changed

- **构建迁到 Gradle**（AGP 9.4.1 + Gradle 9.7.1 + Compose BOM 2026.09.00，compileSdk 37）。
  旧的 `aapt2 + javac + d8` 手工链合并不了 `androidx.customview` 的 R 资源，
  `AbstractComposeView` 会 `NoClassDefFoundError: customview.R$id`。`module/build.sh` /
  `build.ps1` 保留成薄壳，产物路径仍是 `module/out/HyperHand.apk`。
- `MainActivity` 继承 `ComponentActivity`（Compose 需要 `ViewTreeLifecycleOwner`）。
- 原 Java `GlassBar` 保留作对照，`Theme` 里改为注入 `HyperGlassBarView`。

### Fixed

- **玻璃参照位置偏上 110px**：`Backdrop.drawBackdrop()` 的 `size` 是「(胶囊 + 2×padding) / downscaleFactor」
  的采样层尺寸，而绘制坐标原点是**胶囊左上角**（miuix 调用前做了 `translate(padding / ds)`）。
- **只采样到胶囊中间一条**：把 `size` 当成胶囊宽度（434 vs 649），右侧近 200px 没有采样，
  只剩 40% 白蒙层，看起来「边缘更透明」。
- **选中项不实时刷新**：`layerBackdrop` 只在录制源节点重画时才 `recordLayer()`。拖动底栏一次后
  动画收敛，再滚页面就定格；`redrawSignal` 现在同时接给容器、隐藏图标行、指示器。
- **按压时圆角黑楔**：折射 + 色散越出采样层，`ViewBackdrop` 越界即透明。`effects` 的 `padding` 抬到 40dp。
- **胶囊顶边被削平 / 底栏位置偏高**：加 14dp 顶部余量给按下放大与投影；用 `bottomPadding = 7dp`
  避免与宿主 margin 叠加 inset。
- **关于页崩溃**：`MiuixBg` 的 `RuntimeShader` 在软件 Canvas（`ViewBackdrop` 的采样位图）上会抛，
  加了硬件加速守卫；采样失败只丢这一帧的玻璃。
- **滚动掉帧**：只截采样层、按 `1/downscaleFactor` 采样、容器与指示器共享一张位图并在下一帧失效。

### Docs

- 新增 [docs/液态玻璃底栏.md](docs/液态玻璃底栏.md)：接入结构与 10 条迁移踩坑（现象 / 根因 / 改法）。
- README 的常见问题补了 5 条玻璃相关问答。

## [3.0.2-beta.1] - 2026-11-10

> **这是测试版，不是正式版。** 正式版仍是 [2.9.10]。测试版只发在 GitHub Releases
> 的 prerelease 通道，不会同步到 LSPosed 模块仓库，也不会被标成 latest。
> versionCode 29903，介于 2.9.10（20910）与将来的正式版 3.0.2（30002）之间，
> 因此可以直接覆盖安装到 2.9.10 上；将来正式版发布后也能把测试版顶掉。

这一版是「液态玻璃」重写：直接反编译手机上的 `io.github.hyperisland`，
把它的折射实现搬过来替换原来的近似做法。界面版式不变。

### 较 2.9.10 的主要变化

- 底部导航栏从"一排按钮"换成 iOS 式悬浮液态玻璃胶囊（3.0.0 起）；
- 全站字号、间距、圆角按 miuix 重新对齐，卡片之间去掉分隔线；
- 概览页改成 SonyPods 的状态网格版式，关于页换成渐变英雄区。

### Fixed

- **液态玻璃此前只有模糊、没有折射**：Skia 的 AGSL 编译器会把辅助函数<b>内联</b>进 `main`，
  `sdRoundedBox` / `gradRoundedBox` 与 `main` 里同名的局部变量（`q`、`distance`…）
  内联后落在同一作用域，报 `redefinition`，整颗 shader 编译失败后静默退化成"只模糊"。
  现在所有局部变量都加了唯一前缀，并修正了 `uOrigin` 的语义（形状左上角相对采样区左上角）。
- 演示模式下 `pal.barBg` / `pal.bg` 是全透明，`setColor()` 之后再 `setAlpha()` 会画出
  <b>黑带</b>（折叠标题栏变黑、`Theme.alpha(0x00000000, .55f)` 算成半透明黑）；
  涉及 `TopBar` 的底色渐变与 `MiuixBg` 的底部收边。
- 关于页英雄区在 420dp 处有一道硬边：`MiuixBg` 在演示模式下改用 `DST_OUT` 自淡出。
- 滑杆轨道被压成一条线：`MiuixSlider.onMeasure` 把高度写死成 28dp，
  而后调用方给的是「轨道 28dp + 上下 padding」，padding 把轨道吃光了。

### Changed

- **折射用真 AGSL**（`LiquidGlass`）：移植 `LiquidGlassDrawable.REFRACTION_SHADER` 与
  `LiquidGlassEdgeHighlight.SDR_EDGE_SHADER`。半径矩形 SDF + `circleMap` 位移 + RGB 分离采样
  （色散），边缘高光由 SDF 梯度当法线、和光照方向点乘得出。管线顺序也照原实现：
  <b>先模糊，再折射</b>——反了会把文字撕成彩条。
- **底栏拖动像"推一颗水珠"**：指示器的折射带宽与位移随按下/拖动速度放大，
  `lensBand` 与色散同步拉满；形变直接作用在形状矩形上（不套 canvas 变换，
  否则 AGSL 的设备坐标会和 `uOrigin` 错位）。
- **折叠标题栏改用渐进式模糊**（`ProgressiveBlur.Top` 语义）：顶部全额模糊、
  向下衰减到 0，取代原来的整体上移淡出；收起后底色 0.86 透明度，模糊仍可见。

### Added

- **miuix Slider**（`MiuixSlider`）：设置页的数值行（最小下滑距离、贴底判定距离、起效速度…）
  在行下直接挂一条滑杆，按 `SliderDefaults` 取 28dp 轨道、圆头、knob = 0.72r、
  按下 1.127 缩放、拖动时轨道叠 black 4.4%；每格给 `MESH_LIGHT` 触感。点行仍可弹窗精确输入。
- **澎湃 OS 线性马达触感**（`Haptics`）：从 `/system_ext/framework/miui-framework.jar`
  反编译出 `miui.view.MiuiHapticFeedbackConstants` 的效果 id
  （`FLAG_MIUI_HAPTIC | n`：TAP_LIGHT / SWITCH / SCROLL_EDGE / MESH_LIGHT / FLICK_LIGHT…），
  经 `miuix.util.HapticFeedbackCompat` 反射下发。开关、按钮、翻页、滚动到边界、
  滑杆每一格、弹出菜单各用不同效果，替换原来用 `VibrationEffect` 合成波形的做法。

## [3.0.1] - 2026-11-09

按 [Miuix](https://github.com/compose-miuix-ui/miuix) 的组件规格、[SonyPods](https://github.com/Mercury000/SonyPods) 的页面版式、
[AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 的液态玻璃结构，把界面整体重做一遍。
只动界面，识别算法、钩子、参数键与广播协议完全不变。

### Added

- **折叠大标题栏**（`TopBar.java`）：照 miuix `TopAppBar` 的规格——大标题 title1 32sp Normal、左内缩 26dp，
  副标题 body2 14sp；随内容滚动上移淡出，收起后 20sp Medium 的小标题垂直居中在 52dp 条里，
  并渐显底色与一条 0.75dp 分隔线。关于页因为自带渐变英雄区，收起后只留小标题。

### Changed

- **颜色与字号逐条重对齐 miuix**：`onSurface` 改用纯黑/`#F2F2F2`（原来的 90% 黑偏灰），
  行尾值与箭头统一为 `onSurfaceVariantActions`（0x66），行摘要与单位统一 body2 14sp。
  深色模式的卡片、段落标题、次按钮底全部按 `darkColorScheme()` 取色。
- **卡片不再画分隔线**：miuix 的 `BasicComponent` 之间本来就没有线，HyperOS 系统设置页也没有；
  之前每行之间那条 0.75dp 线让整页显得比系统设置更"碎"。
- **概览页改用 SonyPods 的状态网格**：左侧状态卡做成正方形（`aspectRatio(1f)`），
  右侧两张数值卡各占一半、间距 12dp，与 SonyPods `StatusGrid` 的手机分支一致；
  状态卡内只留标题与摘要，去掉了和右下角水印图标打架的时间戳行。
- **系统信息卡改成 SonyPods `InfoCard` 的样式**：标签 17sp Medium 在上、值 14sp 在下，条目间距 24dp，
  没有分隔线、没有右侧对齐——之前"标签左值右"的表格感在窄屏上会把长值挤到换行。
- **关于页英雄区收窄**：图标下的留白按折叠标题栏的高度重算，卡片提前一屏出现。
- **对话框按 miuix `WindowDialog` 重做**：32dp 圆角、inside 24dp、maxWidth 420dp，
  标题 title4 18sp Medium，数值 34sp，按钮竖排且 primary 在上；
  长文本对话框改成"内容短时按内容收缩、长时才滚动"（`MaxScrollView`），不再固定成一大块空白。
- **保存胶囊上移**：浮在底栏之上 108dp，不再压住最后一行内容。

### Fixed

- **玻璃底栏的"脏痕"**：本机硬件画布不执行 `Paint/RenderNode` 上的 `RenderEffect`，
  CPU 网格折射在静止时会把底下文字拖成模糊污渍。现在底栏本体走"0.28 降采样 + 可分离盒式模糊"，
  指示器静止时退化成磨砂胶囊（对齐 miuix `GlassTabRow` / SonyPods 无模糊分支），按下才叠折射与色散。
- **底栏图标与文字排版**：图标 22dp + 文字 11sp 作为一组整体垂直居中（对齐源码的
  `Column + Arrangement.spacedBy(1dp, CenterVertically)`），之前图标贴顶、文字贴底。
- **底栏左右边距**：从 14dp 改为 20dp，和 miuix 悬浮胶囊的默认收进量一致。
- **底栏颜色**：改成源码里的强调色（亮 `#0088FF` / 暗 `#0091FF`）与 `#FAFAFA @ 40%` 底，
  不再用全局 `primary`，深色模式下不会再是"深蓝压深灰"。
- 移除页面左上角那条 4dp 宽的浅灰竖条（旧 `pageHeader` 的装饰条），它和 miuix / SonyPods 都没有对应物。

## [2.9.10] - 2026-11-06

模块在 LSPosed 仓库里的显示名改成「HyperHand-媲美iPhone的单手模式增强」，同时把发布签名统一。

### Changed

- `android:label` 改为 `HyperHand-媲美iPhone的单手模式增强`。LSPosed 模块库（modules.lsposed.org）
  与 LSPosed 管理器里显示的模块名都取自这里，改名后两处同步生效。
- 模块仓库（`Xposed-Modules-Repo/io.github.zpan2327.hyperhand`）自此使用与本地构建**相同的签名密钥**
  （CI 从 `ANDROID_KEYSTORE_B64` 还原 keystore）。此前 CI 无密钥、用临时签名，从模块仓库更新会和
  本地安装的版本签名冲突、必须卸载重装；2.9.10 起可以覆盖安装。

### Fixed

- CI 手动补发（`workflow_dispatch`）拿不到 tag 名，会一路退到默认 `2.0.0`，把 release 发成
  `20000-2.0.0` 同步进模块仓库。现在必须显式填写 `version` 输入，缺失直接失败。
- 普通 main / PR 构建不再被版本号校验拦住（只有 tag 与手动补发才强制要求版本号）。

## [2.9.9] - 2026-11-06

修掉"桌面触发单手模式时顺手把通知中心拉下来"。

### Fixed

- 日志显示 `TRIGGER(E)` 之后 7ms 就出现 `shade_expand`：触发后手指仍在继续拖，
  剩余 MOVE 被当成下拉状态栏。两层修复：
  - 桌面转发入口的吞掉条件去掉 `!sTriggered`（触发后同样要吞），
    时间窗 1500ms → 2500ms；
  - `NotificationPanelViewController#expand*` 与 `ControlCenterImpl#expand`
    增加拦截：起手于底部触发区、手势未结束时直接跳过原方法（仅拦 void 方法）。
- 判据用新增的 `isZoneGesture()`（`sTracking` 且距 DOWN < 2500ms），
  抬手指后自动失效，不影响之后正常拉面板。

## [2.9.8] - 2026-11-06

控制中心走的是另一套面板，判据补上；同时把"等落定"改成"待确认 + 轮询"，更跟手。

### Fixed

- **中部下拉控制中心不退出**：HyperOS 的控制中心不走 `NotificationPanelViewController`，
  通知面板的 `mExpandedFraction` 对它永远是 0（实测控制中心展开时仍为 0.0）。
  补上控制中心自己的状态：`ControlCenterImpl#getExpandedState()` 取 `getValue()`。
  两者并联，任一为真即认定"面板确实打开了"。
- `ControlCenterImpl` 实例改从**构造器**钩子拿 —— 中部下拉这条路径不会调用 `expand()`，
  只挂 expand 会拿到 null。

### Changed

- 内容区 / 控制中心拖动不再固定等 320ms 或 520ms，改成"待确认 + 60ms 轮询"：
  面板露头或控制中心状态为 true 就**立刻**退，700ms 内没证据就放弃（保持单手）。
- 证据统一为三种：`expand` 出口、`setState` 状态机切换、`panel_visible`/`cc_expanded` 轮询。

### Added

- `armconfirm` 调试动作（模拟"待确认"状态，便于不开手也能验证）。
- `probe` 增加 `fraction` / `cc=[…]` / `setStateValue` / `setStateAgo`。
- 实测：armconfirm 后展开 → 39ms 内退出；不展开 → 2s 后仍 `activated=1`。

## [2.9.7] - 2026-11-06

把判据从"下拉意图"换成"面板真的展开了"，修掉内容区误退。

### Fixed

- 内容区下拉的旧判据是 `lastShadeIntentAt()`（出现过 `launcher_forward`）。
  但这条信号只说明"桌面把触摸转发给了 SystemUI"，位移不够时面板会自己缩回去，
  于是**面板没拉出来也把单手模式退掉**。
- 改为读面板真实的展开比例：`NotificationPanelViewController#mExpandedFraction >= 0.35`，
  面板实例从 `expand` 钩子的 `chain.getThisObject()` 取得；字段读不到退化成
  `isExpanded()`，两者都读不到就当作"没展开"（宁可不退）。
- 内容区与控制中心拖动改为"延迟 320ms 确认"：真的展开就立刻退，
  没展开就什么都不做。`launcher_forward` 只作为"值得检查"的前置条件。
- 模糊区 / 状态栏边缘不变（自己调 expand，天然知道面板出来了），仍 120ms 快退。

### Added

- `probe` 调试动作多输出 `panelExpanded=` 与 `activated=`，便于现场确认判据。

## [2.9.6] - 2026-11-06

退出改成分速：跟手的走快退，内容区走慢退。

### Changed

- **模糊区 / 状态栏边缘 → 快退 120ms**（实测 122ms）：这两条是自己拉面板，
  原生手感就是"拉下来就走"，上一版统一成等落定后变成 500ms，手感发闷。
- **缩小窗口内容区 → 慢退 380~520ms**（实测 523ms）：这条是应用/桌面转发展开，
  等面板真正铺开再退，避免"面板还没出来窗口就往上跳"。
- 内容区位移门槛从 10dp **恢复为 36dp（本机 ≈108px）**：这块是应用的地盘，
  门槛太低会把正常滚动误判。
- 排队规则：快退可以覆盖已排队的慢退，慢退不会拖累已排队的快退 ——
  `expand` 钩子经常先于我们的手势判定排队，靠这条保证模糊区/状态栏一定按快退走。

## [2.9.5] - 2026-11-06

修掉"有时候行有时候不行"和"面板还没拉完就退出"。

### Fixed

- **刚进单手模式 1.8s 内下拉不生效**：`startOneHanded()` 的状态写盘跟在窗口动画后面，
  实测有 0.3~0.8s 空窗，这期间读不到 `one_handed_mode_activated=1`，
  面板已经开了却被判成"不在单手模式"。`isOneHandedActive()` 改为
  "距上次触发 1.8s 内一律视为激活"（stop 是幂等的）。
- **内容区下拉位移门槛过高**：原来沿用状态栏的 36dp(≈108px)，
  但面板是"跟手 + 惯性展开"，用户常拖 40~80px 就松手。内容区改用独立的
  10dp 门槛，并在 MOVE 阶段挂 400ms 兜底（面板展开时输入焦点转移，有时收不到 UP）。
- **面板没拉完就退出**：固定 120ms 的延迟改成"等面板落定"——
  `armExit()` 先挂意图与 520ms 超时，`StatusBarStateControllerImpl#setState`
  回调时再提前到 380ms（覆盖展开动画长度），之后才真正 `stopOneHanded()`。
- debug 下新增 `app_swipe skip dy=.. need=.. shade=..` 与 `panel settled after ..ms`，
  下次再出现异常能直接看出卡在哪一步。

## [2.9.4] - 2026-11-06

补上最后一块漏网：在缩小窗口的**中间内容区**下拉，面板会出来但单手模式不退。

### Fixed

- 单手模式下屏幕分三段（模糊区 / 虚拟顶部 / 内容区 / 底部触发区），
  在内容区下拉时事件由桌面或应用接住再**转发**给 SystemUI（日志 `shade intent: launcher_forward`），
  这条转发通道既没有抬手钩子、也不一定经过 `NotificationPanelViewController#expand`，
  所以之前只有模糊区和状态栏带能退出。
- 手势监视通道新增 `MODE_APP` 归属：DOWN 时按 y 落点定一次归属，
  UP 时要求位移 ≥ 触发距离**且**本次手势期间出现过 shade intent，
  两者同时满足才退出 —— 避免"在应用里正常滚动一屏"被误判。
- 拖动过程中不退出，仍只在抬手时结算，否则窗口会带着面板一起上移。
- `scheduleExit` 在 debug 下会打印被拒绝的原因（`not in one-handed` / `throttled`），
  方便排查"面板开了却没退"到底断在哪一步。

## [2.9.3] - 2026-11-06

改成挂"面板的展开出口"，覆盖所有能拉出通知中心/控制中心的路径。

### Fixed

- 之前是手写手势判定（模糊区 + 状态栏带 + 控制中心手势），总有覆盖不到的入口。
  现在额外挂 `NotificationPanelViewController#expand/expandToNotifications/expandToQs`
  与 `ControlCenterImpl#expand` —— 无论从状态栏、应用内、锁屏还是程序化调用展开，
  最终都会经过这些方法，命中即退出单手模式。
- 120ms 延迟保留，避免退出流程把刚展开的面板收掉。

### Verified

- `cmd statusbar expand-notifications` → `shade opened via notification_shade` + 退出，面板保持展开
- `cmd statusbar expand-settings` → `shade opened via control_center` + 退出，控制中心保持展开

## [2.9.2] - 2026-11-06

### Fixed

- 状态栏带的区域判断被写成了**每帧校验**：手指往下拖之后 UP 的 y 已经越过区线，
  "抬手退出"永远执行不到 —— 表现成控制中心能退、通知中心不退。
  改成只在 ACTION_DOWN 判定一次手势归属，MOVE/UP 按归属走完。
- 顺带补上"必须确认这次手势真的把面板拉出来了"（看 shade intent），
  避免在状态栏上轻划一下也把单手模式退掉。

## [2.9.1] - 2026-11-06

统一"退出单手模式"的时机：**只要把通知中心/控制中心拉出来，就退出单手模式**。

### Fixed

- 之前只有模糊区手柄会退出，在"虚拟顶部"（缩小窗口顶部那条状态栏）下拉时，
  面板出来了但手机仍停在单手模式。现在三条路径统一：
  - 模糊区下滑：自己展开面板 + 120ms 后退出；
  - 虚拟顶部下拉（通知中心）：手势监视通道识别该区域，抬手时退出；
  - 虚拟顶部下拉（控制中心）：`ControlCenterEventHandlerImpl#handleExpandEvent`
    的 ACTION_UP 触发退出。
- 三条路径都只认抬手，避免拖动中途窗口带着面板一起上移；共享 500ms 节流。

## [2.9.0] - 2026-11-06

单手模式下，**上半屏模糊区任意位置下滑**即可拉出通知中心 / 控制中心，并自动退出单手模式。

### Added

- 新增「模糊区手势」分组：开关、触发距离（默认 36dp）、拉开哪个（左通知右控制 / 总是通知 / 总是控制）。
- `TopShade.java`：监听 `one-handed-tutorial-overlay`（模糊区唯一的触摸窗口），
  下滑判定通过后展开面板并在背后退出单手模式。

### Changed

- 新增原生退出路径 `stopOneHanded()`（`OneHandedImpl.stopOneHanded()`，
  兜底 `controller.stopOneHanded(int)`），替换原来直接写 `one_handed_mode_activated=0` 的兜底。
- 新增调试动作 `--es oh_tuner_action topshade`，不经过手势直接跑完整链路，便于验证。

### Fixed

- 展开面板与退出单手模式的**顺序**：先退出会把刚展开的面板收掉（日志成功、画面空白），
  改为"先展开 → 等 120ms → 再退出"，面板得以保留。

### Verified

- 真机：`expanded via StatusBarManager` + `stopOneHanded() via OneHandedController$OneHandedImpl`，
  截图确认通知中心 / 控制中心均保持展开，`one_handed_mode_activated=0`。

## [2.8.5] / [2.8.6] - 2026-11-05

把"屏蔽区"从固定 72dp 改成**按屏幕高度比例、覆盖到 dock 栏上方**。

### Added

- 「触发区」新增「延伸到 dock 栏上方」开关（默认开）+「屏幕占比」（默认 15%，可调 5~30%）。
  比例模式下触发区高度 = 屏幕高度 × 占比，本机 2670px × 0.15 = 400px ≈ **134dp**，
  `zoneTop` 从 2496 上移到 2270 —— 整个 dock 都落在区内。
- 用比例而不是固定 dp：换 21:9 / 折叠屏时边界跟着屏幕走，不会在小屏上吃掉半个屏幕。
  关掉开关则回到原来的固定 dp 模式（72dp）。
- 「触发区」卡片底部的校准提示改为更准确的说法：区内显示"深入区内 xx dp"或"贴着边界"，
  区外显示"超出区线 xx dp，把高度调大约这个数"（2.8.6）。

### 实机确认

```
DOWN zone x=729 y=2668 h=2670 zonePx=401 mode=0        ← 401px ≈ 134dp
最近一次起手：距底 72 dp · 区内 / 区线 134 dp
```

### 事故记录（重要）

短时间内连续 5 次"装 APK + 重启 SystemUI + force-stop 桌面"，
触发了 LSPosed 的防崩溃保护，**框架进入安全模式、本模块被停用**（日志里完全没有模块加载记录，
表现为所有功能突然失效）。重启设备后自动恢复，无需重新勾选作用域。

以后更新节奏改为：一轮改完只装一次、只重启一次，不要反复"装一次验一次"。

## [2.8.4] - 2026-11-05

修掉"明明从底部拖，却还是弹了通知中心"—— 问题在触发区边界，不在拦截。

### 数据

一次会话的起手落点（距屏幕底边）：

| 落点 y | 距底 | 58dp 判定 | 72dp 判定 |
|---|---|---|---|
| 2590 / 2553 / 2519 | 26 / 39 / 50dp | 区内，正常触发 | 区内 |
| 2493 | **59dp** | 区外 → 弹通知中心 | 区内 |
| 2487 | **61dp** | 区外 → 弹通知中心 | 区内 |
| 2457 / 2437 | 71 / 78dp | 区外 → 弹通知中心 | 区内 |

58dp 这条线正好切在用户习惯落点中间，差 1dp 就整段失效 —— 而日志里这些手势
在 SystemUI 侧确实没有任何 `shade intent`，说明拦截链路本身是通的。

### Changed

- 触发区高度默认 58dp → **72dp**（覆盖 59~78dp 这段真实落点）。
- 「触发区」卡片新增校准提示：显示最近一次底部附近起手离底边多少 dp、
  是区内还是区外、超出区线多少，不用翻 logcat 就能判断该调大还是调小。

## [2.8.3] - 2026-11-05

桌面底部 58dp 内的下拉改成"事件不出桌面进程"，区外完全不受影响。

### Fixed

- 2.8.2 只在 `StatusBarSwipeController#canInterceptTouch` 让位，
  但桌面真正发事件的是 `DispatchTouchHandler.handleMessage` → `SystemUiProxyWrapper`
  这条**异步**链路，它不会再回头问控制器，所以照样会发出去。
  → 现在直接 hook `com.miui.home.recents(SystemUiProxyWrapper|MiuiSystemUiProxyWrapper)#onStatusBarMotionEvent`，
  区内起手的手势返回 false，连 binder 都不走；`canInterceptTouch` 与 SystemUI 侧的
  `onTransact` 保留为前后两道。
- 用 `sLauncherZoneDown` 记录"本次手势是否从触发区内起手"（DOWN 时按同一套几何写入，
  UP/CANCEL 清除，另加 2s 时间窗），不会跨手势残留。

### 行为

| 操作 | 结果 |
|---|---|
| 从底部 58dp 内往下拖 | 不展开通知/控制中心；拖到底进单手模式 |
| 从 58dp 之外下拉 | 照常展开通知/控制中心 |

## [2.8.2] - 2026-11-05

按"桌面底部 58dp 内不打开通知/控制中心，并在这个范围里更灵敏"调整。

### Fixed

- **通知中心盖住单手模式**：只让桌面侧不拦截（`canInterceptTouch` → false）不够，
  桌面仍会把事件转发到 `ISystemUiProxy.onStatusBarMotionEvent`，SystemUI 一收就展开通知中心，
  还会点亮 `shadeGuard` 把刚判定的单手模式误杀。
  → 现在在 `LauncherProxyService$1#onTransact` 入口判断：若当前手势起点在触发区内
  （`sTracking` 为真），直接 `return true` 吞掉，不转发、不展开、不点亮闸门。
  从屏幕上部下拉打开通知中心不受影响。

### Changed

- 触发区高度默认 56dp → **58dp**。
- 贴底路径门槛：最小位移 6dp → **4dp**，最短时长 50ms → **40ms**。
  58dp 内已经不会被通知中心抢，门槛可以压到"手指真的动了"。

## [2.8.1] - 2026-11-05

根据实机日志修掉"难触发 / 还冲突"的两个直接原因。

### Fixed

- **到边即触发**：原来等 ACTION_UP 才判定，在可滚动页面上这 100~200ms 里内容已经滚了一段，
  观感是"既滚了页面又进了单手模式"。现在 MOVE 阶段一进入底边带子就判定并触发，提前判定失败时静默等下一帧。
- **带宽 24dp → 32dp**：日志里用户自认为"拖到底"的那次 `end=28.4dp`，恰好差 4dp 没进。

### 数据（一次会话，42 次底部触摸）

起点在区外 22 次（页面中部滚动）／区内触发 10 次／区内上滑被正确判掉 9 次／
区内向下但没到底 1 次。说明判定逻辑本身没漏，瓶颈是"等抬手"和带宽。

## [2.8.0] - 2026-11-05

新增「落点判定」：抬手点必须拖到屏幕最底边才算单手模式，没到底一律按页面滑动放行。

### Added

- **贴底直达（默认开）**：`endGapDp = (屏幕高 - 抬手点 y) / density`，小于
  「贴底判定距离」（默认 24dp）才算数。手指滑出屏幕时坐标会被裁到底边，所以真的拖到底时接近 0；
  桌面抽屉 / 可滚动列表的抬手点停在屏幕中段，直接被判为滚动，不再抢手势。
- 贴底路径不再看速度、平均速度和置信度，只要求向下位移 ≥ 6dp、时长 ≥ 50ms（用来挡点击），
  所以**触发比之前更灵敏**，同时误触更少。日志里记为 `TRIGGER(E)`。
- 设置页「识别模式」新增：「贴底才算单手模式」开关、「贴底判定距离」8~80dp。
- 手势调试的每条记录新增 `end=xx.x dp`，可直接看到抬手点离底边多远。

### 验证（离线，`module/test/Test.java`）

| 用例 | 结果 |
|---|---|
| 拖到底 20dp/200ms | TRIGGER(E) |
| 贴底但只动 3dp | SKIP(too_short) |
| 底部起手 10dp **未到底** | SKIP(not_at_bottom) |
| 底部起手上滑 | SKIP(not_downward) |
| 慢速拖到底 15dp/900ms | TRIGGER(E) |
| 到底后停 300ms | TRIGGER(E) |

## [2.7.1] - 2026-11-05

曲线档位重排：上一版默认的 ease-out cubic 中段接近直线，观感"过于线性"。

### Changed

- 曲线从 3 档扩到 5 档，按起步速度排序：原生 / 舒缓(smoothstep) / 柔和(cubic) /
  **标准(quart，默认)** / 迅捷(quint)。
  - cubic 在 t=0.2~0.6 的位移几乎是等速的（0.49 → 0.94），这是"线性感"的来源；
  - quartz `1-(1-t)⁴` 在 t=0.1/0.3/0.5 分别是 34%/76%/94%，斜率一直在变，尾部收得住。
- 「缓动曲线」「动画时长」两项改为**改动即时生效**，不必再点保存（调手感要反复试）。

### 验证（真机）

`settings get global oh_tuner_anim_info` → `系统原生 600ms · 当前 660ms（×1.10）· 标准 · 上次实测 676ms`。

## [2.7.0] - 2026-11-05

把"缩小 / 恢复"动画的实测参数公开出来，并支持在不改系统窗口动画的前提下调缓它。
触发算法、互斥闸门、遮罩外观均未改动。

### Added

- **动画参数实测**（dexdump + aapt2 + animator 计时探针）：
  - 入场与出场共用 `OneHandedDisplayAreaOrganizer.mEnterExitAnimationDurationMs`，
    默认 **600ms**（资源 `config_one_handed_translate_animation_duration` = 600，
    可被系统属性 `persist.debug.one_handed_translate_animation_duration` 覆盖，构造时只读一次）。
  - 一次切换会起 **8 个 animator**（每个 display area 一个），同一毫秒内 start。
  - 曲线是 `f(t) = 1 + 2^(-10t)·sin((t-4)π/8)`，**t=0.1 已走完 50% 位移、t=0.3 走完 88%**，
    这才是"傻快"的真正原因；单纯拉长时长只会变成"冲到位再干等"。
- 设置页新增「动画」分组：
  - 缓动曲线：原生 / 柔和（ease-out cubic，默认）/ 平缓（smoothstep）
  - 动画时长倍率：0.60 ~ 2.00（默认 ×1.10 → 660ms）
  - 卡片底部回显真实生效值（`oh_tuner_anim_info`，由 SystemUI 写入）：系统原生时长、当前值、上次实测。

### Changed

- `AnimCtl` 新增：hook `OneHandedDisplayAreaOrganizer` 构造器改写时长字段、
  hook `setupOneHandedTransitionAnimator` 替换 interpolator、给 animator 挂 start/end 计时探针。
  只作用于单手模式这一条动画，不碰 `ValueAnimator` 全局、不碰窗口动画缩放。
- 保存配置后立即重算时长字段（`AnimCtl.onConfigChanged`），无需重启进程。

### 验证（真机 Xiaomi 14 / OS3.0.305.0.WNCCNXM）

| 配置 | 请求 | 实测 start→end |
|---|---|---|
| ×1.00 + 原生曲线 | 600ms | 614 ~ 626ms |
| ×1.10 + 柔和曲线 | 660ms | 674 ~ 691ms |

两者差值 60ms 与设定增量一致；日志见 `anim: duration 600ms -> 660ms (x1.10), curve=柔和`。

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
