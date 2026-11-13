package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.text.TextPaint;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「关于」页。
 *
 * 版式取自 SonyPods 的关于页：顶部大面积留白 + 应用图标 / 名称 / 版本，
 * 往下是「开发者」和几组信息卡片，最后是许可信息。
 * 内容全部是 HyperHand 自己的：作者、仓库、系统环境、作用域、模块版本。
 */
final class AboutPage {

    static final String REPO = "https://github.com/zPan2327/HyperHand";
    static final String AUTHOR = "https://github.com/zPan2327";

    private final MainActivity host;
    private final Theme.Colors pal;
    private final Context c;
    private final Ui.Page page;

    /** 关于页的根容器：OS3 背景在最底、滚动内容居中、固定 hero 在最上层。 */
    private final Root root;
    private final MiuixBg wash;
    private final FrameLayout heroLayer;
    private LinearLayout heroCol;
    private View spacer;
    private View tail;
    /** 「开发者」小标题：滚动上限锚在它的字顶（见 scrollLimit）。 */
    private TextView devSection;
    /** 滚动上限（px）：字样的顶贴到顶栏底边时的 scrollY，也是 hero 淡出的预算。 */
    private int scrollLimit;
    private GlassNode iconGlass;
    private GlassNode wordGlass;
    private View heroIcon;
    private View heroWord;
    private View heroVersion;
    /** hero 占掉的滚动预算（px）：滚完这段，背景和 hero 正好全部淡出。 */
    private int heroBudget;
    private float progress;
    /*
     * 玻璃层必须逐帧重录，禁止再加间隔 / 隔帧 / 取色阈值。
     *
     * SonyPods 的 textureBlur(backdrop) 每帧从 LayerBackdrop 采样，是连续的；本项目
     * 用显式重录模拟。真机 60fps 静止录屏实测：加 64ms 间隔后 logo 区域出现单帧 8.4
     * 级亮度跳变（>3 级 6 次）、最长连续 4 帧完全不变的阶梯，而同屏纯背景区只有
     * 1 次 3.2 级跳变 —— 这就是「logo 渐变卡顿」。逐帧重录的成本由 framestats 验证。
     */

    /**
     * 根容器。
     *
     * 对外要能被 {@code MainActivity.scrollOf()} 认出内部滚动容器 —— 顶栏折叠、insets、
     * Rubber Band 都挂在那个 ScrollView 上，不能因为外面套了一层就失联。
     */
    static final class Root extends FrameLayout {
        final android.widget.ScrollView scroll;

        Root(Context c, android.widget.ScrollView scroll) {
            super(c);
            this.scroll = scroll;
        }
    }

    AboutPage(MainActivity host) {
        this.host = host;
        this.c = host;
        this.pal = host.pal;
        this.page = Ui.page(c, pal);

        root = new Root(c, page.scroll);
        root.setClipChildren(false);
        // 渐变淡出之后底下必须还有一层「页面底色」，不能是透明的：
        // 1) 背景看着就是「渐变 → 纯白」的样子，中途不会露出窗口底色；
        // 2) 液态底栏采样的是这块区域，透明的话采不到颜色，胶囊会发黑。
        root.setBackground(Theme.pageBackground(pal));

        // 1) 全屏 OS3 流动背景。它是 View 而不是 Drawable，所以直接铺在最底层；
        //    滚动内容用透明底，渐变才不会被 ScrollView 自己的页面底色盖住。
        wash = new MiuixBg(c, pal);
        root.addView(wash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.scroll.setBackground(null);

        // 2) 滚动内容
        root.addView(page.scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        // 视口高度要等布局完才知道，而尾部补白依赖它（见 fillTail）。
        page.scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                if (b - t != ob - ot) {
                    computeBudget();
                }
            }
        });

        // 3) 固定 hero：盖在内容之上，但只有 logo 会消费触摸，
        //    整层淡出后不再挡卡片（见 applyScroll）。
        heroLayer = new FrameLayout(c);
        heroLayer.setClipChildren(false);
        root.addView(heroLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        build();

        // 参考实现：icon 和字标都是 textureBlur（textureBlur 采样的是背后那一层渐变），
        // 所以它们的颜色会跟着背景一起变。这里让背景每帧把当前取色推过来。
        wash.setOnTint(new MiuixBg.OnTint() {
            @Override
            public void onTint(int color) {
                // hero 淡出后没人看得见，别再录了
                if (progress > 0.55f) {
                    return;
                }
                // 主体路径：把背后那块背景重录进 RenderNode，模糊与调色在 GPU 上完成。
                // 逐帧更新，与 60fps 背景动画严格同步 —— 降频会造成可见的阶梯（见字段注释）。
                captureGlass(heroIcon, iconGlass);
                captureGlass(heroWord, wordGlass);
                // 退路：渲染节点不可用时退回纯色填充
                if (heroIcon instanceof AppIcon) {
                    ((AppIcon) heroIcon).setTint(color);
                }
                if (heroWord instanceof GlassText) {
                    ((GlassText) heroWord).setTint(color);
                }
            }
        });
    }

    /** 把 {@code v} 及其下方 {@link GlassNode#pad()} 一圈的背景录进它的 RenderNode。 */
    private void captureGlass(View v, GlassNode g) {
        if (v == null || g == null || !g.usable() || v.getWidth() <= 0 || v.getHeight() <= 0) {
            return;
        }
        int x = offsetInRoot(v, true) - g.pad();
        int y = offsetInRoot(v, false) - g.pad();
        int pad = g.pad();
        g.capture(wash, x, y, v.getWidth() + pad * 2, v.getHeight() + pad * 2);
        v.invalidate();
    }

    /** 视图在根容器（也就是背景所在的坐标系）里的左/上偏移。 */
    private int offsetInRoot(View v, boolean horizontal) {
        int acc = 0;
        View cur = v;
        while (cur != null && cur != root) {
            acc += horizontal ? cur.getLeft() : cur.getTop();
            android.view.ViewParent p = cur.getParent();
            cur = p instanceof View ? (View) p : null;
        }
        return acc;
    }

    /** 返回整页容器（背景 + 内容 + hero），不再是裸的 ScrollView。 */
    View view() {
        return root;
    }

    /** MainActivity 的滚动回调转进来，驱动背景与 hero 的淡出。 */
    void onScrolled(int y) {
        int budget = scrollLimit > 0 ? scrollLimit : heroBudget;
        if (budget <= 0) {
            return;
        }
        // 淡出预算取滚动上限：滚到「开发者」贴住顶栏的那一刻，hero 与背景正好全部消失。
        float p = Math.max(0f, Math.min(1f, (float) y / budget));
        if (Math.abs(p - progress) < 0.0005f) {
            return;
        }
        progress = p;
        float bgAlpha = 1f - p;
        wash.setAlpha(bgAlpha);
        // 参考实现里背景完全淡出后会把动画任务一并取消（alpha <= 0 就 cancel），
        // 否则滚到底还得每帧重跑一遍全屏着色器。回来一点再恢复。
        if (bgAlpha <= 0f) {
            wash.stop();
        } else {
            wash.start();
        }
        applyHeroFade();
    }

    /**
     * 逐元素淡出（照 SonyPods 的顺序：版本最先、字标其次、图标最后）。
     * 每个元素有自己的窗口，所以看起来是「一块一块化掉」而不是整体变淡。
     */
    private void applyHeroFade() {
        fade(heroVersion, 0.05f, 0.20f);
        fade(heroWord, 0.20f, 0.35f);
        fade(heroIcon, 0.35f, 0.50f);
    }

    private void fade(View v, float from, float to) {
        if (v == null) {
            return;
        }
        float t = Math.max(0f, Math.min(1f, (progress - from) / (to - from)));
        v.setAlpha(1f - t);
        float s = 1f - t * 0.05f;
        v.setScaleX(s);
        v.setScaleY(s);
        // 完全淡出后不再吃触摸，免得透明层压着下面的卡片。
        v.setEnabled(t < 1f);
    }

    /**
     * hero 顶部留白 = 状态栏 + 144dp。
     *
     * SonyPods 的式子：padding(top = contentTopPadding + HERO_EXTRA_TOP + 52dp)，
     * 其中 contentTopPadding 对 SmallTopAppBar 而言就是「状态栏 + 52dp」，
     * HERO_EXTRA_TOP = 40dp，于是 52 + 40 + 52 = 144dp。
     *
     * 注意必须能重复调用：构建页面时 window insets 还没下发（statusInset 还是 0），
     * 只在这里算一次的话 hero 会整体高出状态栏那么多。
     */
    private void applyHeroTop() {
        if (heroCol == null) {
            return;
        }
        heroCol.setPadding(Theme.dp(c, 20), host.statusInset() + Theme.dp(c, 144),
                Theme.dp(c, 20), 0);
    }

    /** insets 下发 / 变化后由宿主调用：重算 hero 顶距与滚动预算。 */
    void onInsets() {
        applyHeroTop();
        computeBudget();
    }

    /** 当前滚动进度（0=完全展开，1=hero 与背景全部消失）。 */
    float progress() {
        return progress;
    }

    private void build() {
        // 顶部留出与固定 hero 等高的空白，撑出滚动预算；高度在 hero 量完后回填。
        spacer = new View(c);
        page.add(spacer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));

        buildHero();
        // ---------------------------------------------------------- 开发者
        // 滚动上限就锚在这个标题上：字样的顶不允许越过顶栏的底（见 scrollLimit）。
        devSection = Ui.section(c, pal, "开发者");
        page.add(devSection);
        LinearLayout cardDev = Ui.card(c, pal);
        LinearLayout row = Ui.rowShell(c);
        Ui.rowRipple(row, pal);
        row.setPadding(Theme.dp(c, 18), Theme.dp(c, 14), Theme.dp(c, 18), Theme.dp(c, 14));
        row.addView(avatar(56), new LinearLayout.LayoutParams(
                Theme.dp(c, 56), Theme.dp(c, 56)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(c, "不会买菜.", 19, pal.title, Theme.semibold()));
        TextView sub = Ui.text(c, "@zPan2327", 14, pal.summary, false);
        sub.setPadding(0, Theme.dp(c, 1), 0, 0);
        col.addView(sub);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = Theme.dp(c, 14);
        row.addView(col, colLp);
        // SonyPods DeveloperCard 用 32sp 的「›」而不是小箭头图标
        TextView arrow = Ui.text(c, "›", 32, pal.chevron, false);
        row.addView(arrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(AUTHOR);
            }
        });
        Ui.addRow(c, cardDev, pal, row);
        page.add(cardDev);

        // ---------------------------------------------------------- 项目
        // 参考版式里开发者卡片下面直接就是这张卡片，没有「项目」小标题。
        LinearLayout cardProj = Ui.card(c, pal);
        Ui.navRow(c, cardProj, pal, -1, "源码", "GitHub 上的 HyperHand 仓库", "GitHub",
                new Runnable() {
                    @Override
                    public void run() {
                        openUrl(REPO);
                    }
                });
        Ui.navRow(c, cardProj, pal, -1, "引用", "Miuix / KernelSU / SonyPods 等", null,
                new Runnable() {
                    @Override
                    public void run() {
                        Ui.infoDialog(host, pal, "引用与致谢", REFERENCES);
                    }
                });
        Ui.navRow(c, cardProj, pal, -1, "使用说明", "上手前值得看一眼的几条", null,
                new Runnable() {
                    @Override
                    public void run() {
                        Ui.infoDialog(host, pal, "使用说明", USAGE);
                    }
                });
        Ui.navRow(c, cardProj, pal, -1, "许可", "MIT License", null,
                new Runnable() {
                    @Override
                    public void run() {
                        Ui.infoDialog(host, pal, "许可", LICENSE);
                    }
                });
        page.add(cardProj, Ui.cardLp(c, Theme.CARD_GAP));

        TextView foot = Ui.text(c, "HyperHand · MIT License\n为小米 14 / HyperOS 3 打造的单手模式增强",
                Theme.T_CAP, pal.section, false);
        foot.setGravity(Gravity.CENTER);
        foot.setLineSpacing(Theme.dp(c, 3), 1f);
        foot.setPadding(0, Theme.dp(c, 24), 0, 0);
        page.add(foot);

        // 尾部补足：让「可滚动距离」至少等于 hero 预算。
        //
        // 参考实现里第一屏的内容块是 Box + fillParentMaxHeight，天然把内容撑满一个
        // 视口，所以滚到底时 scrollProgress 正好到 1。我们这里是普通的顺序布局，
        // 卡片少的时候内容比「预算 + 视口」短，滚到底 progress 才 0.3 左右 ——
        // 背景和 logo 都淡不干净。高度在 computeBudget 里回填。
        tail = new View(c);
        page.add(tail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0));
    }

    // ---------------------------------------------------------------- 调试页（隐藏）

    /**
     * 隐藏调试页：连续点击 logo 5 次弹出。
     * 打包原「设置 → 调试」与「概览」页的调试开关 / 运行状态 / 最近手势 / 快速操作。
     * 和其它弹出式子窗口一样走 MiuixDialog，不再用 root overlay
     * （Dialog 卡片是不透明的，不会像旧注释担心的那样透出下层页面）。
     */
    private void showDebugPage() {
        final MiuixDialog d = new MiuixDialog(host, pal);
        d.title("调试");

        Ui.Page dbg = Ui.page(c, pal);
        dbg.scroll.setBackground(Theme.round(pal.night ? 0xFF242424 : 0xFFFFFFFF, 0f));
        dbg.column.setPadding(MiuixDialog.pad(c), Theme.dp(c, 8),
                MiuixDialog.pad(c), Theme.dp(c, 16));
        TextView dbgTitle = Ui.text(c, "运行状态 / 手势 / 快速操作",
                Theme.T_SUM, pal.summary, false);
        dbg.add(dbgTitle);

        // ---- 调试开关（原「设置 → 调试」搬到这里，设置主界面不再展示）
        dbg.add(Ui.section(c, pal, "调试开关"));
        LinearLayout swCard = Ui.card(c, pal);
        Ui.SwitchView swDbg = Ui.switchRow(c, swCard, pal, "调试日志",
                "用 logcat -s HyperHand 观察判定过程，并记录最近手势", host.cfg().debug);
        swDbg.setOnCheckedChange(new Ui.SwitchView.OnCheckedChange() {
            @Override
            public void onCheckedChanged(boolean checked) {
                host.toggleDebug(checked);
            }
        });
        dbg.add(swCard, Ui.cardLp(c, Theme.CARD_GAP));

        // ---- 运行状态
        dbg.add(Ui.section(c, pal, "运行状态"));
        LinearLayout status = Ui.card(c, pal);
        Cfg cfg = host.cfg();
        Ui.infoPair(c, status, pal, "扩展触发").setText(cfg.monitorEnabled ? "已开启" : "已关闭");
        Ui.infoPair(c, status, pal, "模糊壁纸背景").setText(cfg.blurBg
                ? "已开启 · " + Ui.fmt(cfg.blurRadiusDp, 0) + " dp / 压暗 " + Ui.fmt(cfg.blurDim, 0) + "%"
                : "已关闭");
        Ui.infoPair(c, status, pal, "缩小动画").setText(Cfg.ANIM_CURVE_SHORT[safe(cfg.animCurve)] + " · "
                + Ui.fmt(cfg.animScale, 2) + "× · " + Math.round(Cfg.SYSTEM_ANIM_MS * cfg.animScale) + " ms");
        Ui.infoPair(c, status, pal, "模糊区下滑").setText(
                cfg.topShade ? Cfg.TOP_SIDE_SHORT[safeSide(cfg.topShadeSide)] : "已关闭");
        Ui.infoPair(c, status, pal, "贴底直达").setText(
                cfg.endBoost ? Ui.fmt(cfg.endZoneDp, 0) + " dp 内算到底" : "已关闭");
        dbg.add(status, Ui.cardLp(c, Theme.CARD_GAP));

        // ---- 最近手势
        dbg.add(Ui.section(c, pal, "最近手势"));
        LinearLayout log = Ui.card(c, pal);
        final TextView gestureText = Ui.block(c, pal, host.gestureLog());
        log.addView(gestureText);
        Ui.actionRow(c, log, pal, "刷新记录", "读取系统界面写回的最近一次判定", new Runnable() {
            @Override
            public void run() {
                gestureText.setText(host.gestureLog());
                host.toast("已刷新");
            }
        });
        dbg.add(log, Ui.cardLp(c, Theme.CARD_GAP));

        // ---- 快速操作
        dbg.add(Ui.section(c, pal, "快速操作"));
        LinearLayout quick = Ui.card(c, pal);
        Ui.actionRow(c, quick, pal, "预览单手模式", "立刻缩小窗口，直接看背景效果", new Runnable() {
            @Override
            public void run() {
                host.previewOneHanded();
            }
        });
        Ui.actionRow(c, quick, pal, "退出单手模式", "恢复正常窗口大小", new Runnable() {
            @Override
            public void run() {
                host.stopOneHanded();
            }
        });
        Ui.actionRow(c, quick, pal, "重启两个作用域", "结束系统界面与桌面进程，重新注入钩子", new Runnable() {
            @Override
            public void run() {
                host.restartScopes();
            }
        });
        dbg.add(quick, Ui.cardLp(c, Theme.CARD_GAP));

        int maxH = Math.min(Theme.dp(c, 340),
                (int) (c.getResources().getDisplayMetrics().heightPixels * 0.50f));
        d.content(dbg.scroll, maxH);
        d.button("关闭", false, new MiuixDialog.OnClick() {
            @Override
            public void onClick(MiuixDialog dlg) {
                dlg.dismiss();
            }
        });
        d.show();
    }

    private static int safe(int curve) {
        return curve < 0 || curve >= Cfg.ANIM_CURVE_SHORT.length ? 0 : curve;
    }

    private static int safeSide(int side) {
        return side < 0 || side >= Cfg.TOP_SIDE_SHORT.length ? 0 : side;
    }

    // ---------------------------------------------------------------- 顶部

    /**
     * 固定 hero（不随内容滚动）：logo / 名称 / 版本。
     *
     * SonyPods 的关于页把 hero 放在 LazyColumn 之外用 padding 定位，内容区第一个 item 是一个
     * 同样高度的 Spacer 撑出滚动预算；页面一滚，hero 原地不动、逐块淡出，背景跟着变透明。
     * 这里用相同结构：hero 放在根容器的覆盖层里，ScrollView 顶部留等高的空白。
     */
    private void buildHero() {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        heroCol = col;
        applyHeroTop();

        heroIcon = new AppIcon(c, Theme.dp(c, 88), pal.primary);
        // 玻璃：和参考一样由 RenderNode + RenderEffect 出图（见 GlassNode），
        // 渲染半径取参考的 blurRadius = 200f（图标）/ 150f（字标）按 dp 折算。
        iconGlass = new GlassNode(pal.night, Theme.dpf(c, 40), Theme.dp(c, 26));
        ((AppIcon) heroIcon).setGlass(iconGlass);
        // 隐藏入口：连续点击 logo 5 次弹出调试页
        heroIcon.setClickable(true);
        heroIcon.setOnClickListener(new View.OnClickListener() {
            private int taps;
            private long last;

            @Override
            public void onClick(View v) {
                long now = android.os.SystemClock.uptimeMillis();
                taps = (now - last < 800) ? taps + 1 : 1;
                last = now;
                if (taps >= 5) {
                    taps = 0;
                    showDebugPage();
                }
            }
        });
        col.addView(heroIcon, new LinearLayout.LayoutParams(
                Theme.dp(c, 88), Theme.dp(c, 88)));

        // 字号对齐参考字标的帽高：about_wordmark.xml（193x40dp，SegUI Bold 轮廓）
        // 的大写字母高约 28.5dp —— Roboto Bold 帽高 0.711em，取 40dp 正好同高。
        GlassText name = new GlassText(c, "HyperHand", Theme.dpf(c, 40), pal.title);
        wordGlass = new GlassNode(pal.night, Theme.dpf(c, 30), Theme.dp(c, 20));
        name.setGlass(wordGlass);
        heroWord = name;
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.topMargin = Theme.dp(c, 12);
        col.addView(name, nameLp);

        TextView ver = Ui.text(c, versionName() + "(" + versionCode() + ")", 14,
                pal.summary, false);
        ver.setPadding(0, Theme.dp(c, 5), 0, 0);
        ver.setGravity(Gravity.CENTER);
        heroVersion = ver;
        col.addView(ver);

        heroLayer.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // hero 高度依赖状态栏 inset，布局完成后再算滚动预算（SonyPods 的 heroHeightDp）。
        col.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or, int ob) {
                computeBudget();
            }
        });
    }

    /**
     * 滚动预算 = hero 底部到内容顶部的距离 + 126dp。
     *
     * 126dp 是 SonyPods 里留的「滚完这一段英雄区才彻底消失」的余量；滚满这段，
     * scrollProgress 到 1，背景完全透明、英雄区全部淡出。
     */
    private void computeBudget() {
        if (heroLayer.getHeight() <= 0 || page.column == null) {
            return;
        }
        int colTop = page.column.getPaddingTop();
        int budget = Math.max(0, heroLayer.getBottom() - colTop) + Theme.dp(c, 126);
        if (budget != heroBudget) {
            heroBudget = budget;
            if (spacer != null) {
                ViewGroup.LayoutParams lp = spacer.getLayoutParams();
                lp.height = budget;
                spacer.setLayoutParams(lp);
            }
        }
        fillTail();
        onScrolled(page.scroll.getScrollY());
    }

    /**
     * 补足尾部空白，保证「可滚动距离 ≥ hero 预算」，滚到底时 progress 能到 1。
     *
     * 用「除尾部以外的内容高度」来算，而不是「当前可滚动距离」：后者加上尾部之后
     * 正好等于预算，下一次计算又会得出 0，会来回抖。这里的 bodyH 不含尾部，
     * 所以换成新尾部高度后再算一次结果不变，一次收敛。
     */
    private void fillTail() {
        if (tail == null || page.column == null || page.scroll.getHeight() <= 0) {
            return;
        }
        int colH = page.column.getHeight();
        if (colH <= 0) {
            return;
        }
        int tailH = tail.getHeight();
        int bodyH = colH - tailH;
        // 滚动上限（产品约定）：「开发者」字样的顶不允许越过顶栏的底。
        // 把尾部补白调成「正好能滚到这条线」，ScrollView 自己的边界就是钳制点 ——
        // 拖动、fling、橡皮筋都不用再单独处理，手感保持一致。
        scrollLimit = computeScrollLimit();
        int want = Math.max(0, scrollLimit + page.scroll.getHeight() - bodyH);
        if (want == tailH) {
            return;
        }
        ViewGroup.LayoutParams lp = tail.getLayoutParams();
        lp.height = want;
        tail.setLayoutParams(lp);
    }

    /**
     * 允许的最大 scrollY：「开发者」字样的顶（content 坐标）− 顶栏底边。
     * 顶栏底边 = 状态栏 + 52dp 标题条（{@link Theme#BAR_H}）。
     */
    private int computeScrollLimit() {
        if (devSection == null || page.column == null) {
            return heroBudget;
        }
        int textTop = page.column.getTop() + devSection.getTop() + devSection.getTotalPaddingTop();
        int barBottom = host.statusInset() + Theme.dp(c, Theme.BAR_H);
        return Math.max(0, textTop - barBottom);
    }

    /**
     * 圆形头像：centerCrop —— 等比缩放到刚好铺满正方形，超出部分被圆形 clip 裁掉。
     * 不做任何非等比拉伸，也不会像旧的 1:1 drawBitmap 那样只显示大图左上角一小块。
     * 图源是用户指定的 940×940 正方图，铺满后就是完整的一张脸，不需要额外偏移。
     */
    private View avatar(int sizeDp) {
        final int size = Theme.dp(c, sizeDp);
        final Bitmap bmp = loadAvatar();
        View v = new View(c) {
            private final Paint bitPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path clip = new Path();

            @Override
            protected void onMeasure(int w, int h) {
                setMeasuredDimension(size, size);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                clip.reset();
                float r = size / 2f;
                clip.addCircle(r, r, r - Theme.dp(c, 1), Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clip);
                if (bmp != null && !bmp.isRecycled()) {
                    // 等比铺满（centerCrop）：取宽高两个方向里较大的缩放比，
                    // 再居中平移，短边多出来的部分由圆形 clip 裁掉，绝不拉伸变形。
                    float scale = Math.max((float) size / bmp.getWidth(),
                            (float) size / bmp.getHeight());
                    Matrix m = new Matrix();
                    m.setScale(scale, scale);
                    m.postTranslate((size - bmp.getWidth() * scale) / 2f,
                            (size - bmp.getHeight() * scale) / 2f);
                    canvas.drawBitmap(bmp, m, bitPaint);
                } else {
                    canvas.drawColor(Theme.alpha(pal.primary, pal.night ? 0.24f : 0.12f));
                }
                canvas.restore();
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(Theme.dp(c, 2));
                ringPaint.setColor(Theme.alpha(0xFFFFFFFF, 0.85f));
                canvas.drawCircle(r, r, r - Theme.dp(c, 1), ringPaint);
            }
        };
        return v;
    }

    private Bitmap loadAvatar() {
        try {
            int id = c.getResources().getIdentifier("avatar", "drawable", c.getPackageName());
            if (id == 0) {
                return null;
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            // 不让 aapt2 按屏幕密度把图放大成 2000+ 再缩回来，那样会糊。
            o.inScaled = false;
            // 图源 940×940，/2 = 470px，仍远大于 56dp 的显示尺寸：
            // 解码阶段只做整数降采样，绘制时由 Matrix 等比缩放到刚好铺满。
            o.inSampleSize = 2;
            return android.graphics.BitmapFactory.decodeResource(c.getResources(), id, o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 图标

    /** 应用图标：圆角方块 + 玻璃（RenderNode + RenderEffect）+ 手掌镂空。 */
    static final class AppIcon extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final float size;
        private final int accent;
        private GlassNode glass;
        /** 玻璃不可用时的退路：由背景取样得到的取色；0 表示还没拿到。 */
        private int tint;

        AppIcon(Context c, float sizePx, int accent) {
            super(c);
            this.size = sizePx;
            this.accent = accent;
        }

        void setGlass(GlassNode g) {
            glass = g;
            invalidate();
        }

        void setTint(int color) {
            if (color == tint) {
                return;
            }
            tint = color;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round(size), Math.round(size));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = getWidth();
            float r = s * 0.27f;
            RectF box = new RectF(0, 0, s, s);

            // 参考 about_logo_mark.png 的结构：圆角方块是「磨砂玻璃」，手掌图形是
            // **镂空**（DstIn 蒙版上的透明洞）—— 图形区域透出下层背景，而不是
            // 叠一层白色描边。上一版把白手掌画在玻璃之上，正是「logo 显示错误」的
            // 一个来源（方块灰、图形死白，和参考的深紫方块 + 浅色镂空对不上）。
            float inset = s * 0.22f;
            float gs = s - inset * 2;
            Path hand = Icons.glyph(Icons.HAND, inset, inset, gs);
            shape.reset();
            shape.addRoundRect(box, r, r, Path.Direction.CW);
            // 圆角方块减去手掌轮廓 —— 图形是镂空洞，透出下层背景
            shape.op(hand, Path.Op.DIFFERENCE);

            canvas.save();
            canvas.clipPath(shape);
            if (glass != null && glass.usable() && canvas.isHardwareAccelerated()) {
                // 图形本身是「磨砂玻璃」—— 圆角矩形当蒙版，里面填的是背后那块背景
                // 的模糊采样 + miuix 混色链（见 GlassNode）。
                canvas.translate(-glass.pad(), -glass.pad());
                glass.draw(canvas);
            } else {
                int base = tint != 0 ? tint : accent;
                bg.setShader(new LinearGradient(0, 0, s, s,
                        new int[] {Theme.blend(base, 0xFFFFFFFF, 0.22f), base,
                                Theme.blend(base, 0xFF000000, 0.18f)},
                        new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, s, s, bg);
            }
            canvas.restore();
        }
    }

    /**
     * 字标：字形本身是玻璃。
     *
     * 参考里 wordmark 是一张 PNG，套同样的 {@code textureBlur(..., contentBlendMode = DstIn)}，
     * 也就是用字形的 alpha 当蒙版去裁那块模糊背景。这里把字形转成 {@link Path} 来当蒙版，
     * 效果一致，而且不用额外塞一张图。
     */
    static final class GlassText extends View {
        private final String text;
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Path glyph = new Path();
        private final int fallback;
        private GlassNode glass;
        private final int w;
        private final int h;
        private float baseline;

        GlassText(Context c, String s, float sizePx, int fallbackColor) {
            super(c);
            text = s;
            fallback = fallbackColor;
            tp.setTypeface(Theme.bold());
            tp.setTextSize(sizePx);
            tp.setColor(fallbackColor);
            Paint.FontMetrics fm = tp.getFontMetrics();
            baseline = -fm.ascent;
            h = Math.max(1, Math.round(fm.descent - fm.ascent));
            w = Math.max(1, Math.round(tp.measureText(s)));
            tp.getTextPath(s, 0, s.length(), 0f, baseline, glyph);
        }

        void setGlass(GlassNode g) {
            glass = g;
            invalidate();
        }

        void setTint(int color) {
            if (tp.getColor() == color) {
                return;
            }
            tp.setColor(color);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(w, h);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (glass != null && glass.usable() && canvas.isHardwareAccelerated()) {
                canvas.save();
                canvas.clipPath(glyph);
                canvas.translate(-glass.pad(), -glass.pad());
                glass.draw(canvas);
                canvas.restore();
            } else {
                canvas.drawText(text, 0f, baseline, tp);
            }
        }
    }

    // ---------------------------------------------------------------- 数据
    private String versionName() {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "3.0.0" : pi.versionName;
        } catch (Throwable t) {
            return "3.0.0";
        }
    }

    private long versionCode() {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable t) {
            return 30000;
        }
    }

    private String installTime() {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            return f.format(new Date(pi.lastUpdateTime));
        } catch (Throwable t) {
            return "未知";
        }
    }

    private static String safe(String s) {
        return s == null || s.length() == 0 ? "未知" : s;
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            host.toast("没有可用的浏览器");
        }
    }

    // ---------------------------------------------------------------- 文案

    private static final String REFERENCES =
            "Miuix（compose-miuix-ui/miuix）\n"
                    + "整套色彩、字号、圆角与组件规格的来源，Apache-2.0。\n\n"
                    + "KernelSU（tiann/KernelSU）\n"
                    + "页面层级、卡片分组与信息排布参考，GPL-3.0。\n\n"
                    + "SonyPods（Mercury000/SonyPods）\n"
                    + "悬浮底栏、背景模糊与关于页版式参考。\n\n"
                    + "libxposed API（libxposed/api）\n"
                    + "模块的现代 API 入口，Apache-2.0。\n\n"
                    + "AndroidLiquidGlass（Kyant0/AndroidLiquidGlass）\n"
                    + "液态玻璃的折射与高光思路，Apache-2.0。\n\n"
                    + "以上项目仅作为设计参考，HyperHand 的全部界面代码都是\n"
                    + "按 Miuix 的取值用原生 View 手写实现的。";

    private static final String USAGE =
            "• 在 Vector / LSPosed 里启用 HyperHand 即可：作用域已经静态声明，\n"
                    + "  不用手动勾「系统界面 / 小米桌面」，也没法挂到别的应用上。\n\n"
                    + "• 保存后立即生效，不需要重启；改动由系统界面进程写入 Settings.Global。\n\n"
                    + "• 自然模式：底部连续下滑即可，不要求甩；灵敏模式门槛更低；防误触模式更保守。\n\n"
                    + "• 速度单位是 dp/s，与屏幕密度无关；旧版的 px/s 参数会自动折算过来。\n\n"
                    + "• 想完全恢复原状：关掉「启用模块」，或在管理器里停用模块。";

    private static final String LICENSE =
            "MIT License\n\n"
                    + "Copyright (c) 2026 zPan2327\n\n"
                    + "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
                    + "of this software and associated documentation files (the \"Software\"), to deal\n"
                    + "in the Software without restriction, including without limitation the rights\n"
                    + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
                    + "copies of the Software, and to permit persons to whom the Software is\n"
                    + "furnished to do so, subject to the following conditions:\n\n"
                    + "The above copyright notice and this permission notice shall be included in all\n"
                    + "copies or substantial portions of the Software.\n\n"
                    + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
                    + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
                    + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
                    + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
                    + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
                    + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
                    + "SOFTWARE.";
}
