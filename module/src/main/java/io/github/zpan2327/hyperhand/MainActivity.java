package io.github.zpan2327.hyperhand;

import android.app.ActionBar;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;

import io.github.zpan2327.hyperhand.glass.HyperGlassBarView;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 界面外壳：三页（概览 / 设置 / 关于）+ 折叠大标题栏 + 悬浮液态玻璃底栏。
 *
 * 布局参照 SonyPods 的 Scaffold：内容层满屏，顶栏浮在上面随滚动折叠，
 * 底栏悬浮在内容之上（内容从它下面穿过，玻璃才有东西可模糊）。
 * 视觉 token 全部来自 {@link Theme}，控件在 {@link Ui}。
 *
 * 业务逻辑（保存广播、重启作用域、调试动作）与 2.9 / 3.0 完全一致。
 */
/**
 * <p>继承 {@link ComponentActivity} 而不是裸 {@link android.app.Activity}：液态玻璃底栏是 Compose
 * 画的（{@code HyperGlassBarView} 是 {@code AbstractComposeView}），Compose 要求 ViewTree 上挂了
 * LifecycleOwner / SavedStateRegistryOwner，只有 ComponentActivity 会替你装好这两个 owner。
 * 其余行为（ActionBar 隐藏、WindowInsets、onBackPressed 覆写）都与原来一致。
 */
public class MainActivity extends ComponentActivity {

    static final int TAB_OVERVIEW = 0;
    static final int TAB_SETTINGS = 1;
    static final int TAB_ABOUT = 2;

    Theme.Colors pal;
    private Cfg cur;

    /**
     * 最近一次「模块运行状态」主动探测的发出时刻（epoch ms）。
     * 概览页不再相信 7 天内的历史心跳：只有探测之后写回的心跳才能证明
     * LSPosed 当前真的启用了模块，否则一律显示红色的「模块未启用」。
     */
    private long lastProbeAt;
    private final Runnable probeRefresh = new Runnable() {
        @Override
        public void run() {
            if (overviewPage != null) {
                overviewPage.refresh();
            }
        }
    };

    private FrameLayout contentHost;
    private FrameLayout root;
    private io.github.zpan2327.hyperhand.glass.dialogs.SonyPodsDialogHost dialogHost;
    private io.github.zpan2327.hyperhand.glass.dialogs.InfoDialogHost infoDialogHost;
    private io.github.zpan2327.hyperhand.glass.dialogs.MiuixDropdownHost dropdownHost;
    private io.github.zpan2327.hyperhand.glass.dialogs.MiuixNumberPickerHost numberPickerHost;
    private PagePager pager;
    private TopBar topBar;
    private HyperGlassBarView nav;
    private FrameLayout.LayoutParams navLp;

    private View[] pages = new View[3];
    private OverviewPage overviewPage;
    private SettingsPage settingsPage;
    private AboutPage aboutPage;

    private int current = TAB_OVERVIEW;
    private int statusInset;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActionBar bar = getActionBar();
        if (bar != null) {
            bar.hide();
        }

        // 截图/调试用：--es bg grad 换成彩色渐变底，用来看玻璃的折射与通透
        Theme.demoBg = getIntent() != null && "grad".equals(getIntent().getStringExtra("bg"));
        pal = Theme.colors(this);
        try {
            cur = Cfg.get(this);
        } catch (Throwable t) {
            cur = new Cfg();
        }

        Window w = getWindow();
        w.setBackgroundDrawable(new ColorDrawable(pal.bg));
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);
        w.setDecorFitsSystemWindows(false);
        android.view.WindowInsetsController ctrl = w.getInsetsController();
        if (ctrl != null) {
            int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            ctrl.setSystemBarsAppearance(Theme.isNight(this) ? 0 : mask, mask);
        }

        setContentView(buildShell());
        // 触感需要 View 宿主走 View#performHapticFeedback（MIUI 的 DecorView 认 flag）
        Haptics.get(this).attach(root);
        int start = TAB_OVERVIEW;
        String tab = getIntent() == null ? null : getIntent().getStringExtra("tab");
        if ("settings".equals(tab)) {
            start = TAB_SETTINGS;
        } else if ("about".equals(tab)) {
            start = TAB_ABOUT;
        }
        current = start;
        nav.setSelectedImmediate(start);
        selectTab(start, false);
        // 截图/调试用：am start --ei scroll 600 直接把当前页滚到指定位置，
        // 用来验证折叠标题栏与玻璃底栏在滚动中的表现
        int scroll = getIntent() == null ? 0 : getIntent().getIntExtra("scroll", 0);
        final android.widget.ScrollView startScroller = scrollOf(pages[start]);
        if (scroll > 0 && startScroller != null) {
            startScroller.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startScroller.scrollTo(0, scroll);
                }
            }, 400);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        probeModule();
        if (overviewPage != null && current == TAB_OVERVIEW) {
            overviewPage.refresh();
        }
        if (settingsPage != null) {
            settingsPage.refreshAnimInfo();
        }
    }

    // ---------------------------------------------------------------- 外壳

    private View buildShell() {
        root = new FrameLayout(this);
        root.setBackgroundColor(pal.bg);

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        overviewPage = new OverviewPage(this);
        settingsPage = new SettingsPage(this);
        aboutPage = new AboutPage(this);
        pages[TAB_OVERVIEW] = overviewPage.view();
        pages[TAB_SETTINGS] = settingsPage.view();
        pages[TAB_ABOUT] = aboutPage.view();

        // 三页平级：横向容器同时承载，靠左右滑动或点底栏切换。
        // 三页常驻（不销毁重建），滚动位置与各页状态都留住。
        pager = new PagePager(this);
        for (int i = 0; i < pages.length; i++) {
            pager.addView(pages[i], new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        pager.setPageCount(pages.length);
        pager.setOnPageChange(new PagePager.OnPageChange() {
            @Override
            public void onPageSelected(int position) {
                if (position != current) {
                    current = position;
                    nav.select(position, true);
                    applyTopBar(position);
                    refreshPage(position);
                    contentHost.postInvalidateOnAnimation();
                }
            }

            @Override
            public void onPageScrolled(float position, boolean following) {
                // 拖动中：底栏指示器、顶栏标题都跟手。
                // 顶栏不再是「吸附后整块换标题」，而是按这个浮点位置把两页的标题
                // 一起平移 —— 旧页向左滑出、新页从右滑入，和内容同步。
                //
                // 底栏只在手势滚动时跟手；点底栏切页是 280ms 的整段滚动，一帧最多跨半格，
                // 让指示器逐帧跟它反而会「一帧飞过去、卡三帧、再跳一下」，交给它自己的弹簧。
                if (following) {
                    nav.setPagePosition(position);
                } else {
                    nav.clearPagePosition();
                }
                nav.onContentScrolled();
                if (topBar != null) {
                    topBar.setPagePosition(position);
                    // 横向拖动改变了顶栏背后的内容，必须重采样，否则渐进模糊会停在拖动前那一帧。
                    topBar.invalidate();
                }
            }
        });
        contentHost.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 折叠大标题栏：浮在内容之上，随当前页的滚动位置折叠
        topBar = new TopBar(this, pal);
        // 一次性注册三页文案：横滑时要同时画「滑出的页」和「滑入的页」，
        // 索引与 pager 的子 View 顺序一致。关于页 showLarge = false（自带渐变英雄区）。
        topBar.setPages(
                new String[] {"概览", "设置", "关于"},
                new String[] {"", "改动后立即生效", ""},
                new boolean[] {true, true, false},
                // 关于页的顶栏不加渐进模糊：它自带整屏渐变，压一条模糊带会把渐变切出边。
                // 参考版式也是「滚动时顶栏透明，快到底才化成页面底色」。
                new boolean[] {false, false, true});
        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        nav = new HyperGlassBarView(this);
        // 强调色沿用应用的 accent（原 GlassBar 里写死的 0xFF0088FF 与这里并非同一个值）。
        nav.setAccentColor(pal.primary);
        navLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        navLp.gravity = Gravity.BOTTOM;
        navLp.leftMargin = Theme.dp(this, 20);
        navLp.rightMargin = Theme.dp(this, 20);
        navLp.bottomMargin = Theme.dp(this, 12);
        root.addView(nav, navLp);
        nav.setOnTabSelected(new HyperGlassBarView.OnTabSelected() {
            @Override
            public void onSelected(int index) {
                selectTab(index, true);
            }
        });

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                // Android 15（API 35）强制 edge-to-edge 后，手势导航的底部条不再算进
                // systemBars()：`systemBars().bottom` 在全面屏手势模式下返回 0，实际挡在
                // 底部的是 systemGestures / mandatorySystemGestures。只取 systemBars 会让
                // 页面底部 padding 少算一个手势条高度 —— 模拟器实测最后一行被悬浮底栏压住
                // （设置页「起效速度」、关于页「使用说明」）。几个 type 一起传给 getInsets
                // 会取并集，这样无论三键导航还是手势导航都能拿到正确的底部高度。
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars()
                                | WindowInsets.Type.displayCutout()
                                | WindowInsets.Type.systemGestures()
                                | WindowInsets.Type.mandatorySystemGestures());
                // 顶部不能取上面那个并集：小米 14 上 mandatorySystemGestures 的 top 是 170px
                // （56.7dp），而真正的状态栏只有 134px（44.7dp）。拿并集当状态栏高度会让
                // 每个页面的内容整体下移 12dp，关于页的 logo 位置也因此对不上参考版式。
                // 顶部只认状态栏 / 挖孔，底部仍旧取并集（手势条只有并集才拿得到）。
                android.graphics.Insets topBars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                statusInset = topBars.top;
                topBar.setStatusInset(topBars.top);

                navLp.bottomMargin = Theme.dp(MainActivity.this, 12) + bars.bottom;
                navLp.leftMargin = Theme.dp(MainActivity.this, 20);
                navLp.rightMargin = Theme.dp(MainActivity.this, 20);
                nav.setLayoutParams(navLp);

                applyInsets();
                nav.setBackdropSource(contentHost, pal.bg);
                topBar.setBackdropSource(contentHost);
                return insets;
            }
        });

        // 全屏透明的 MIUIX Dropdown 宿主：设置页是 Java View，借它拿到 root Scaffold /
        // MiuixPopupHost。最后 add = 盖在内容/顶栏/底栏之上；没有弹窗时它把触摸还给下层。
        dropdownHost = new io.github.zpan2327.hyperhand.glass.dialogs.MiuixDropdownHost(this);
        dropdownHost.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dropdownHost.setDarkMode((getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        root.addView(dropdownHost);

        // 数值项的单列 NumberPicker 宿主：与 dropdownHost 同为全屏透明 View，
        // OverlayDialog 走它自己的 root Scaffold。没有弹窗时把触摸还给下层。
        numberPickerHost = new io.github.zpan2327.hyperhand.glass.dialogs.MiuixNumberPickerHost(this);
        numberPickerHost.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        numberPickerHost.setDarkMode((getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        root.addView(numberPickerHost);

        return root;
    }

    /** 内容顶部留给折叠标题栏（展开高度），底部留给悬浮底栏。 */
    private void applyInsets() {
        int top = topBar.expandedHeight();
        // 底栏自身 78dp（64 + 上下 7 的溢出）+ 12dp 外边距 + 16dp 呼吸
        int bottom = navLp.bottomMargin + Theme.dp(this, 78 + 16);
        // 关于页的 hero 顶距也要状态栏高度：页面是在 insets 下发之前构建的，
        // 那会儿 statusInset 还是 0，必须在这里补一次。
        if (aboutPage != null) {
            aboutPage.onInsets();
        }
        for (int i = 0; i < pages.length; i++) {
            android.widget.ScrollView sv = scrollOf(pages[i]);
            if (sv != null) {
                sv.setClipToPadding(false);
                View child = sv.getChildAt(0);
                if (child != null) {
                    // 关于页的渐变要铺到状态栏下面，所以顶部不加内边距，由页内自行处理
                    int padTop = i == TAB_ABOUT ? 0 : top;
                    child.setPadding(child.getPaddingLeft(), padTop,
                            child.getPaddingRight(), bottom);
                }
                final int pageIndex = i;
                // miuix overScrollVertical()：橡皮筋位移期间 backdrop 一直在变，
                // 顶栏的渐进式模糊和玻璃底栏都要跟着重画，否则会停在拖动前那一帧。
                if (sv instanceof MiuixScrollView) {
                    ((MiuixScrollView) sv).setOnOverScroll(new MiuixScrollView.OnOverScroll() {
                        @Override
                        public void onOverScroll(float offset) {
                            // 每帧都刷新，保证「实时」。采样位图已降到 0.20×，
                            // 单帧成本足够低，不再需要靠降帧换性能。
                            if (topBar != null) {
                                topBar.invalidate();
                            }
                            if (nav != null) {
                                nav.onContentScrolled();
                            }
                        }
                    });
                }
                sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                    @Override
                    public void onScrollChange(View v, int x, int y, int ox, int oy) {
                        if (pageIndex == current && topBar != null) {
                            if (pageIndex == TAB_ABOUT && aboutPage != null) {
                                // 关于页的顶栏不吃「120dp 折叠预算」，直接跟 hero 的
                                // 滚动进度 —— SonyPods 里 title 的 alpha 也是
                                // ((scrollProgress - 0.35) / 0.65)，两页才对得上。
                                aboutPage.onScrolled(y);
                                topBar.setCollapse(aboutPage.progress());
                            } else {
                                topBar.setScroll(y);
                            }
                            // collapse 到 1 饱和后 setCollapse 会提前 return、不再 invalidate，
                            // 但内容仍在滚动 —— 顶栏的 backdrop 必须每帧重采样，否则渐进
                            // 模糊会永久定格在「刚折叠完」那一帧。这里无条件补一次重绘。
                            topBar.invalidate();
                        }
                        // 玻璃要"实时"透出背后的内容：页面滚动不会让底栏自己重绘，
                        // 不主动重画就会停在滚动前那一帧上（旧 GlassBar 也有这个毛病）。
                        if (nav != null) {
                            nav.onContentScrolled();
                        }
                        // 不在滚动到顶/底时震动：Rubber Band 本身已经给了边界反馈，
                        // 触感只保留在点击类控件上。
                    }
                });
            }
        }
    }

    // ---------------------------------------------------------------- 导航

    void selectTab(int index, boolean animate) {
        if (index < 0 || index > 2) {
            return;
        }
        hideDropdown();
        nav.select(index, true);
        // 即使 index == current 也要让 pager 回正：切页动画进行中 current 已被提前改写，
        // 此时再点当前 tab，若直接 return，页面会永远停在两页之间。setCurrentPage
        // 内部对「已在目标且无偏移」有短路，正常重复点击不会有副作用。
        current = index;
        if (pager != null) {
            pager.setCurrentPage(index, animate);
        }
        applyTopBar(index);
        refreshPage(index);
        contentHost.postInvalidateOnAnimation();
    }

    /** 切到某一页时刷新它的数据（三页常驻，不重建）。 */
    private void refreshPage(int index) {
        if (index == TAB_OVERVIEW) {
            probeModule();
            overviewPage.refresh();
        } else if (index == TAB_SETTINGS) {
            settingsPage.refreshAnimInfo();
        }
    }

    /** 每一页的右上角动作；标题文案在启动时由 {@code TopBar.setPages} 一次给全。 */
    private void applyTopBar(int index) {
        int icon = index == TAB_OVERVIEW ? Icons.RESTART : -1;
        TopBar.OnAction act = index == TAB_OVERVIEW ? new TopBar.OnAction() {
            @Override
            public void onAction() {
                showRestartDialog();
            }
        } : null;
        topBar.setCurrentPage(index, icon, act);
        topBar.setPagePosition(index);
        if (index == TAB_ABOUT && aboutPage != null) {
            aboutPage.onScrolled(scrollYOf(index));
            topBar.setCollapse(aboutPage.progress());
        } else {
            topBar.setScroll(scrollYOf(index));
        }
        applyInsets();
    }

    private int scrollYOf(int index) {
        android.widget.ScrollView sv = scrollOf(pages[index]);
        return sv != null ? sv.getScrollY() : 0;
    }

    /**
     * 取一页内部的滚动容器。
     *
     * 关于页现在返回的是「OS3 背景 + 滚动内容 + 固定 hero」的容器的 FrameLayout，
     * 不再是裸 ScrollView，所以所有按类型判断的地方都改走这里。
     */
    static android.widget.ScrollView scrollOf(View page) {
        if (page instanceof android.widget.ScrollView) {
            return (android.widget.ScrollView) page;
        }
        if (page instanceof AboutPage.Root) {
            return ((AboutPage.Root) page).scroll;
        }
        return null;
    }

    /**
     * 设置页的单选 / 有限选项行统一走这里：真正渲染的是 MIUIX
     * {@code OverlayDropdownPopup}（DropdownImpl 选项行 + 勾选态 + 禁用态），
     * 只是一张 Java 行把锚点矩形交给 Compose 宿主。
     *
     * @param anchor 被点的一级行，弹窗按它的位置定位（空间不足自动翻到上方）
     * @param summaries 每项下方的说明，可为 null
     */
    void showDropdown(View anchor, String[] items, String[] summaries, int selected,
                      Ui.OnChoice onSelected) {
        if (dropdownHost == null || anchor == null || items == null || items.length == 0) {
            return;
        }
        hideNumberPicker();
        dropdownHost.setDarkMode((getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        int[] a = new int[2];
        int[] r = new int[2];
        anchor.getLocationOnScreen(a);
        root.getLocationOnScreen(r);
        List<String> labels = Arrays.asList(items);
        List<String> desc = summaries == null ? null : Arrays.asList(summaries);
        dropdownHost.show(
                labels,
                desc,
                Math.max(0, Math.min(items.length - 1, selected)),
                a[0] - r[0],
                a[1] - r[1],
                anchor.getWidth(),
                anchor.getHeight(),
                Math.round(getResources().getDisplayMetrics().heightPixels * 0.6f),
                index -> onSelected.onChoice(index),
                () -> { });
    }

    /** 切页 / 返回 / 重启对话框弹出前先收起下拉菜单与数值滚轮，避免两个弹出层叠在一起。 */
    void hideDropdown() {
        if (dropdownHost != null) {
            dropdownHost.hide();
        }
        hideNumberPicker();
    }

    /**
     * 数值项：MIUIX 单列 NumberPicker 对话框。索引即值，和 Dropdown 的数据流一致 ——
     * Java 侧用 min/max/step 生成 labels（数值 + 单位已拼好），确认后把索引交回回调。
     */
    void showNumberPicker(String title, String[] labels, int selected, Ui.OnChoice onConfirm) {
        if (numberPickerHost == null || labels == null || labels.length == 0) {
            return;
        }
        hideDropdown();
        numberPickerHost.setDarkMode((getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        numberPickerHost.show(
                title,
                Arrays.asList(labels),
                Math.max(0, Math.min(labels.length - 1, selected)),
                index -> onConfirm.onChoice(index),
                () -> { });
    }

    void hideNumberPicker() {
        if (numberPickerHost != null) {
            numberPickerHost.hide();
        }
    }

    /**
     * 概览右上角按钮：miuix {@code Dialog} 里嵌 {@code Checkbox}。
     *
     * <p>原来是 {@code Ui.popupMenu} 的 PopupWindow 锚点菜单（三个固定选项），
     * 现在改成可多选的作用域对话框：父项「全部作用域」是三态 Checkbox
     * （全选 On / 全不选 Off / 部分选中 Indeterminate），与两个子项联动 ——
     * 正是 miuix Checkbox 文档里的父子复选框用法。
     */
    /**
     * 重启作用域对话框——直接用 SonyPods 的 {@code RestartScopeDialog} Composable 原版渲染，
     * 通过 {@link io.github.zpan2327.hyperhand.glass.dialogs.SonyPodsDialogHost} 桥接到 Java。
     * 这里只负责提供作用域数据与确认后的业务动作，界面代码一行都不手写。
     */
    private void showRestartDialog() {
        hideDropdown();
        // miuix OverlayDialog 经 DialogLayout 把内容画到 Compose 宿主的 popup 层，必须和
        // 宿主同处一个 window 才能显示——所以不新建 Dialog window，而是把桥接 View 直接
        // 叠到 Activity 根容器 root 之上（也在玻璃底栏之上）。ComponentDialog 那种独立
        // window 里 Composable 虽会执行（Checkbox 有编译日志），但 popup 不可见。
        if (dialogHost != null && dialogHost.getParent() == root) {
            root.removeView(dialogHost);
        }
        final io.github.zpan2327.hyperhand.glass.dialogs.SonyPodsDialogHost host =
                new io.github.zpan2327.hyperhand.glass.dialogs.SonyPodsDialogHost(this);
        dialogHost = host;
        host.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.setDarkMode((getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        root.addView(host);   // 追加到末尾 = 盖在 contentHost / topBar / 底栏之上

        final java.util.List<io.github.zpan2327.hyperhand.glass.dialogs.RestartScope> scopes =
                new java.util.ArrayList<>();
        scopes.add(new io.github.zpan2327.hyperhand.glass.dialogs.RestartScope(
                "com.android.systemui", "系统界面"));
        scopes.add(new io.github.zpan2327.hyperhand.glass.dialogs.RestartScope(
                "com.miui.home", "小米桌面"));
        host.setRestartScopes(scopes);

        final io.github.zpan2327.hyperhand.glass.dialogs.SonyPodsDialogHost.OnDismiss onDismiss =
                new io.github.zpan2327.hyperhand.glass.dialogs.SonyPodsDialogHost.OnDismiss() {
                    @Override
                    public void onDismiss() {
                        dismissRestartDialog(host);
                    }
                };
        host.setCallbacks(onDismiss,
                new io.github.zpan2327.hyperhand.glass.dialogs.SonyPodsDialogHost.OnConfirm() {
                    @Override
                    public void onConfirm(java.util.List<String> selected) {
                        boolean sysui = selected.contains("com.android.systemui");
                        boolean home = selected.contains("com.miui.home");
                        dismissRestartDialog(host);
                        // 第一个对话框点确定即直接重启，不再弹第二个确认框。
                        if (sysui && home) {
                            doRestart();
                        } else if (sysui) {
                            restartScopeNow("sysui");
                        } else if (home) {
                            restartScopeNow("home");
                        }
                    }
                });
        host.show();
    }

    /** 关掉原版 Composable 对话框：先让它播放退出动画，再从 root 移除宿主 View。 */
    private void dismissRestartDialog(
            final io.github.zpan2327.hyperhand.glass.dialogs.SonyPodsDialogHost host) {
        host.hide();
        host.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (host.getParent() == root) {
                    root.removeView(host);
                }
                if (dialogHost == host) {
                    dialogHost = null;
                }
            }
        }, 280);
    }

    /**
     * 长文本信息对话框（引用 / 使用说明 / 许可 / 运行状态）。
     *
     * <p>和重启对话框同理：MIUIX OverlayDialog 的 popup 必须与宿主同处一个 window，
     * 所以不新建 Dialog window，而是把 InfoDialogHost 叠到 Activity 根容器 root 之上。
     * 界面全部由 MIUIX OverlayDialog 渲染，这里只提供标题/正文与关闭回调。
     */
    void showInfoDialog(String title, String body) {
        hideDropdown();
        if (infoDialogHost != null && infoDialogHost.getParent() == root) {
            root.removeView(infoDialogHost);
        }
        final io.github.zpan2327.hyperhand.glass.dialogs.InfoDialogHost host =
                new io.github.zpan2327.hyperhand.glass.dialogs.InfoDialogHost(this);
        infoDialogHost = host;
        host.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.setDarkMode((getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        host.setContent(title, body);
        host.setOnDismiss(new io.github.zpan2327.hyperhand.glass.dialogs.InfoDialogHost.OnDismiss() {
            @Override
            public void onDismiss() {
                dismissInfoDialog(host);
            }
        });
        root.addView(host);
        host.show();
    }

    /** 关闭信息对话框：先播 MIUIX 退出动画，再从 root 移除宿主 View。 */
    private void dismissInfoDialog(
            final io.github.zpan2327.hyperhand.glass.dialogs.InfoDialogHost host) {
        host.hide();
        host.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (host.getParent() == root) {
                    root.removeView(host);
                }
                if (infoDialogHost == host) {
                    infoDialogHost = null;
                }
            }
        }, 280);
    }

    void goSettings() {
        selectTab(TAB_SETTINGS, true);
    }

    @Override
    public void onBackPressed() {
        if (dropdownHost != null && dropdownHost.isShowing()) {
            dropdownHost.hide();
            return;
        }
        if (numberPickerHost != null && numberPickerHost.isShowing()) {
            numberPickerHost.hide();
            return;
        }
        if (dialogHost != null) {
            dismissRestartDialog(dialogHost);
            return;
        }
        if (infoDialogHost != null) {
            dismissInfoDialog(infoDialogHost);
            return;
        }
        if (current != TAB_OVERVIEW) {
            selectTab(TAB_OVERVIEW, true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        recreate();
    }

    // ---------------------------------------------------------------- 数据

    int statusInset() {
        return statusInset;
    }

    Cfg cfg() {
        if (cur == null) {
            cur = Cfg.get(this);
        }
        return cur;
    }

    void reloadCfg() {
        Cfg.invalidate();
        cur = Cfg.get(this);
    }

    /**
     * 模块运行状态：0 总开关关闭 / 1 未检测到注入（LSP 未启用或作用域没重启）/ 2 已激活。
     *
     * <p>旧实现只看「7 天内的历史心跳」：模块在 LSPosed 里被禁用后旧心跳仍落在窗口里，
     * 概览页会一直显示绿色的「模块已激活」。现在改成主动探测：app 发
     * {@code ACTION_SET/probe} 给作用域进程，只有探测之后写回的心跳才算数；没回应就是
     * 没有注入 —— 与 SonyPods 用 HookStateMirror 查询 hook 端状态是同一思路。
     */
    int statusLevel() {
        Cfg cfg = cfg();
        if (!cfg.enabled) {
            return 0;
        }
        long ts = Math.max(Cfg.aliveTs(this, Cfg.K_ALIVE_SYSUI),
                Cfg.aliveTs(this, Cfg.K_ALIVE_LAUNCHER));
        if (lastProbeAt > 0 && ts >= lastProbeAt - 1000L) {
            return 2;
        }
        return 1;
    }

    /** 探测结果还没回来：状态卡显示「检测中」，避免先闪一下红色再变绿。 */
    boolean probing() {
        // 总开关关掉时结论已经确定，不要拿「检测中」盖住红色的未启用状态。
        if (!cfg().enabled || statusLevel() == 2) {
            return false;
        }
        return lastProbeAt == 0 || System.currentTimeMillis() - lastProbeAt < 700L;
    }

    /** 主动探测 LSPosed 是否真的启用了模块并注入了作用域进程。 */
    private void probeModule() {
        lastProbeAt = System.currentTimeMillis();
        // systemui 里注册了 ACTION_SET 接收器；桌面也一并问，将来注册了就能覆盖。
        String[] pkgs = {"com.android.systemui", "com.miui.home"};
        for (String pkg : pkgs) {
            Intent i = new Intent(HyperHand.ACTION_SET);
            i.setPackage(pkg);
            i.putExtra(Cfg.K_ACTION, "probe");
            sendBroadcast(i);
        }
        if (overviewPage != null) {
            View v = overviewPage.view();
            v.removeCallbacks(probeRefresh);
            // 180ms 常规回写、650ms 兜底慢机广播排队；关键是最末这一发必须落在
            // probing() 的 700ms 窗口之外（取 850ms），否则刷新时窗口还没结束、
            // 状态卡会永远停在「检测中」——LSPosed 没启用模块/没勾作用域时无人应答，
            // 就靠这一发把结论落成红色「模块未启用」。
            v.postDelayed(probeRefresh, 180);
            v.postDelayed(probeRefresh, 650);
            v.postDelayed(probeRefresh, 850);
        }
    }

    String aliveSummary() {
        long sysui = Cfg.aliveTs(this, Cfg.K_ALIVE_SYSUI);
        long launcher = Cfg.aliveTs(this, Cfg.K_ALIVE_LAUNCHER);
        if (sysui <= 0 && launcher <= 0) {
            return statusLevel() == 0 ? "总开关关闭" : "尚无注入记录";
        }
        StringBuilder sb = new StringBuilder();
        if (sysui > 0) {
            sb.append("系统界面 ").append(ago(sysui));
        }
        if (launcher > 0) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append("桌面 ").append(ago(launcher));
        }
        return sb.toString();
    }

    String gestureLog() {
        try {
            String s = Settings.Global.getString(getContentResolver(), Cfg.K_LAST_GESTURE);
            if (s != null && s.trim().length() > 0) {
                return s;
            }
        } catch (Throwable ignored) {
            // 走默认提示
        }
        return "还没有记录。开启调试日志后，在屏幕底部下滑一次再看这里。";
    }

    void showStatusDetail() {
        Cfg cfg = cfg();
        StringBuilder sb = new StringBuilder();
        sb.append("总开关：").append(cfg.enabled ? "已启用" : "已关闭").append('\n');
        sb.append("系统界面：").append(aliveText(Cfg.K_ALIVE_SYSUI)).append('\n');
        sb.append("桌面：").append(aliveText(Cfg.K_ALIVE_LAUNCHER)).append("\n\n");
        sb.append("识别模式：").append(Cfg.MODE_SHORT[safeMode(cfg.mode)]).append('\n');
        sb.append("触发区：").append(Ui.fmt(cfg.zoneDp, 0)).append(" dp")
                .append(cfg.zoneRatioMode ? "（屏幕高度 " + Ui.fmt(cfg.zoneRatio * 100f, 0) + "%）" : "（固定高度）")
                .append('\n');
        sb.append("扩展触发：").append(cfg.monitorEnabled ? "开" : "关")
                .append("　贴底直达：").append(cfg.endBoost ? "开" : "关").append('\n');
        sb.append("外观：").append(cfg.blurBg ? "模糊壁纸 " + Ui.fmt(cfg.blurRadiusDp, 0) + "dp" : "原生遮罩").append('\n');
        sb.append("动画：").append(Cfg.ANIM_CURVE_SHORT[safeCurve(cfg.animCurve)])
                .append(" · ").append(Ui.fmt(cfg.animScale, 2)).append("×\n\n");
        sb.append("注入记录由模块在系统界面 / 桌面进程启动时写入，")
                .append("换了模块版本或刚重启过系统，这里会显示对应时间。");
        Ui.infoDialog(this, pal, "运行状态", sb.toString());
    }

    private String aliveText(String key) {
        long ts = Cfg.aliveTs(this, key);
        if (ts <= 0) {
            return "尚无记录";
        }
        SimpleDateFormat f = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        return f.format(new Date(ts)) + "（" + ago(ts) + "）";
    }

    private static int safeMode(int v) {
        return v < 0 || v >= Cfg.MODE_SHORT.length ? 0 : v;
    }

    private static int safeCurve(int v) {
        return v < 0 || v >= Cfg.ANIM_CURVE_SHORT.length ? 0 : v;
    }

    private static String ago(long ts) {
        long d = System.currentTimeMillis() - ts;
        if (d < 90_000L) {
            return "刚刚";
        }
        if (d < 3600_000L) {
            return (d / 60_000L) + " 分钟前";
        }
        if (d < 86_400_000L) {
            return (d / 3600_000L) + " 小时前";
        }
        return (d / 86_400_000L) + " 天前";
    }

    // ---------------------------------------------------------------- 动作

    void save() {
        Intent i = new Intent(HyperHand.ACTION_SET);
        i.setPackage("com.android.systemui");
        try {
            settingsPage.applyTo(i);
        } catch (Throwable t) {
            toast("保存失败：" + t);
            return;
        }
        sendBroadcast(i);
        settingsPage.onSaved();
        reloadCfg();
        overviewPage.refresh();
        toast("已保存");
        if (settingsPage != null) {
            settingsPage.refreshAnimInfo();
        }
    }

    /**
     * 隐藏调试弹窗里的「调试日志」开关：只写 K_DEBUG 一项。
     * 接收端按 hasExtra 合并（见 HyperHand 的 ACTION_SET 处理），单键广播不影响其它配置。
     */
    void toggleDebug(boolean on) {
        Intent i = new Intent(HyperHand.ACTION_SET);
        i.setPackage("com.android.systemui");
        i.putExtra(Cfg.K_DEBUG, on ? "1" : "0");
        sendBroadcast(i);
        reloadCfg();
    }

    void previewOneHanded() {
        broadcastAction("trigger");
        toast("已请求进入单手模式");
    }

    void stopOneHanded() {
        broadcastAction("stop");
        toast("已请求退出");
    }

    void restartScopes() {
        Ui.confirmDialog(this, pal, "重启两个作用域",
                "会结束「系统界面」与「小米桌面」两个进程，屏幕闪一下、短暂黑屏后自动恢复。",
                "重启", new Ui.OnConfirm() {
                    @Override
                    public void onConfirm() {
                        doRestart();
                    }
                });
    }

    /** 单作用域直接重启（第一个对话框点确定后调用，不再弹第二个确认框）。scope = "sysui" / "home"。 */
    void restartScopeNow(final String scope) {
        final String label = "sysui".equals(scope) ? "系统界面" : "小米桌面";
        final String pkg = "sysui".equals(scope) ? "com.android.systemui" : "com.miui.home";
        settingsPage.setRestarting(true);
        settingsPage.setStatus("正在检查 root 权限……若弹出授权请允许。");
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 【未授权 root 不允许重启】没有 root 直接中止，不发 reload、不杀进程。
                if (!hasRoot()) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            settingsPage.setRestarting(false);
                            settingsPage.setStatus("未获得 root 权限，已取消重启。请先在 "
                                    + "KernelSU / Magisk 里授予 HyperHand 超级用户权限后重试。");
                            toast("没有 root，未执行重启");
                            if (current == TAB_OVERVIEW) overviewPage.refresh();
                        }
                    });
                    return;
                }
                final String err = su("killall " + pkg + "; sleep 1; input keyevent 3", 25000L);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        settingsPage.setRestarting(false);
                        if (err == null) {
                            settingsPage.setStatus("已结束" + label + "，钩子会在它重启后重新注入。");
                            toast("已重启" + label);
                        } else {
                            // 已确认有 root，命令本身失败才走 reload 兜底。
                            broadcastAction("reload");
                            settingsPage.setStatus("root 命令执行失败：" + err);
                            toast("重启失败");
                        }
                        if (current == TAB_OVERVIEW) {
                            overviewPage.refresh();
                        }
                    }
                });
            }
        }).start();
    }

    private void broadcastAction(String action) {
        Intent i = new Intent(HyperHand.ACTION_SET);
        i.setPackage("com.android.systemui");
        i.putExtra(Cfg.K_ACTION, action);
        sendBroadcast(i);
    }

    private void doRestart() {
        settingsPage.setRestarting(true);
        settingsPage.setStatus("正在检查 root 权限……若弹出授权请允许。");
        final int keepTab = current;
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 【未授权 root 不允许重启】没有 root 就直接中止：既不 killall，也不再
                // fallback 到 broadcastAction("reload") 让系统界面自行重启。
                if (!hasRoot()) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            settingsPage.setRestarting(false);
                            settingsPage.setStatus("未获得 root 权限，已取消重启。请先在 KernelSU / Magisk "
                                    + "里授予 HyperHand 超级用户权限后重试。");
                            toast("没有 root，未执行重启");
                            if (keepTab == TAB_OVERVIEW) overviewPage.refresh();
                        }
                    });
                    return;
                }
                final String err = su("killall com.android.systemui; killall com.miui.home; "
                        + "sleep 1; input keyevent 3", 25000L);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        settingsPage.setRestarting(false);
                        if (err == null) {
                            settingsPage.setStatus("已结束两个进程，钩子会在它们重启后重新注入。"
                                    + "现在可以下滑底部区域试试。");
                            toast("已重启");
                        } else {
                            // 已确认有 root，但 killall 执行失败（权限已给、命令环境问题）。
                            // 这里保留 reload 兜底——与"未授权"无关，属于另一类失败。
                            broadcastAction("reload");
                            settingsPage.setStatus("root 命令执行失败：" + err
                                    + "\n已改为让系统界面自行重启（桌面侧钩子未刷新，可稍后手动重启）。");
                            toast("已请求系统界面重启");
                        }
                        if (keepTab == TAB_OVERVIEW) {
                            overviewPage.refresh();
                        }
                    }
                });
            }
        }).start();
    }

    /**
     * 是否已获得 root（su）授权：跑一条无害的 {@code id -u}，只有输出为 0 才算有 root。
     * 不能只判断"su 文件存在"——授权被拒时 su 也在，但命令会以非 0 退出。
     *
     * <p>首次 su 会弹授权框、需要等待，所以【必须在后台线程调用】，不要放 UI 线程。
     * 等待授权最多 10s，超时按"无 root"处理（不允许重启）。
     */
    private boolean hasRoot() {
        final String[] bins = new String[] {"su", "/system/bin/su", "/debug_ramdisk/su", "/sbin/su"};
        for (String bin : bins) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[] {bin, "-c", "id -u"});
                if (!p.waitFor(10000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return false;   // 等授权超时 → 视为无 root
                }
                String out = readStream(p.getInputStream()).trim();
                int code = p.exitValue();
                // su 存在但被拒绝 / 不是 root shell：不要再去试其它路径（都是同一个 su），
                // 直接判定无 root。只有 exec 本身抛异常（该路径没有 su）才继续试下一个。
                return code == 0 && "0".equals(out);
            } catch (Throwable t) {
                // 该路径没有可用的 su，继续试下一个
            } finally {
                if (p != null) {
                    try { p.destroy(); } catch (Throwable ignored) { }
                }
            }
        }
        return false;
    }

    /**
     * 跑一条 root 命令：成功返回 null，失败返回原因。
     * KernelSU / Magisk 的 su 路径不统一，按常见位置依次尝试。
     */
    private String su(String cmd, long timeoutMs) {
        final String[] bins = new String[] {"su", "/system/bin/su", "/debug_ramdisk/su", "/sbin/su"};
        String last = "未知错误";
        for (String bin : bins) {
            Process p;
            try {
                p = Runtime.getRuntime().exec(new String[] {bin, "-c", cmd});
            } catch (Throwable t) {
                last = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                continue;
            }
            try {
                if (!p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroy();
                    return "等待授权超时，请在 KernelSU / Magisk 里允许 HyperHand";
                }
                int code = p.exitValue();
                String err = readStream(p.getErrorStream());
                if (code == 0) {
                    return null;
                }
                return "退出码 " + code + (err.length() > 0 ? "：" + err.trim() : "");
            } catch (Throwable t) {
                return String.valueOf(t);
            } finally {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
        return "没找到可用的 su（" + last + "）。请确认已 root，并允许 HyperHand 的超级用户请求";
    }

    private static String readStream(java.io.InputStream in) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
