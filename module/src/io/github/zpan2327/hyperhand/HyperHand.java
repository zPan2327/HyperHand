package io.github.zpan2327.hyperhand;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * HyperHand · 小米 14 / HyperOS 3 (Android 16) 单手模式触发增强。
 *
 * 原理（全部复用原生单手模式，不重写动画/布局/触摸映射/退出逻辑）：
 *
 * 1) 主路径（com.android.systemui）：WMShell 的 OneHandedTouchHandler 持有一条
 *    "[Gesture Monitor] onehanded-touch" 输入监视通道，只要单手模式处于"已启用"状态，
 *    该通道就能收到全屏所有触摸事件（monitor 语义：只观察、不消费，因此不会影响 App、
 *    不影响返回手势、不影响小白条上滑回桌面）。
 *    我们在这条通道上做一次"底部下滑"判定，命中后调用
 *    OneHandedController.startOneHanded() —— 也就是原生小白条下滑所调用的同一个方法，
 *    后续的缩小动画、缩小布局、触摸映射、退出（点击上方/上滑/超时）全部沿用系统实现。
 *    因为它只是"旁路监听"，所以把触发区从原生 25dp 的胶囊条扩大到底部 56dp（可配）
 *    不会改变任何窗口的触摸分配，不会造成点击延迟、长按丢失之类的问题。
 *
 * 2) 互斥判断（关键）：桌面自身的"下拉打开通知中心 / 控制中心"手势和我们抢同一段区域。
 *    实测链路：MiuiHome 的 StatusBarSwipeController 判定 dy > 2*touchSlop 后，把整段
 *    MotionEvent 通过 binder 转发给 SystemUI（ISystemUiProxy.onStatusBarMotionEvent，
 *    transaction code 10 → LauncherProxyService$1.onTransact），由 SystemUI 的
 *    MiuiShadeTouchHandler / ControlCenterEventHandler 接管。
 *    所以我们把触发时机改到 ACTION_UP（与原生 NavStubView 一致），并在 UP 时检查：
 *    本次手势期间是否收到过"下拉通知/控制中心"的信号；收到就放弃单手模式。
 *    这样长距离下拉 = 通知/控制中心，短促下滑 = 单手模式，两者不再打架。
 *
 * 3) 备选路径（com.miui.home，默认关闭）：覆盖 NavStubView#getHotSpaceHeight()，
 *    直接把原生 GameStubHome 窗口（type=2024）的高度改大。
 *    缺点是窗口变大后其内部的点击透传逻辑只对"快速点击"注入事件，超过 300ms 的长按会被吞掉，
 *    故仅作为可选项。
 *
 * 4) 模块形态：libxposed 现代 API（API 102）。入口类由 META-INF/xposed/java_init.list 声明，
 *    作用域由 META-INF/xposed/scope.list + module.prop 的 staticScope 静态声明，
 *    用户既不用手动勾作用域，也无法把模块挂到别的应用上。
 *
 * 2.2 起：判定算法移到 {@link Recognizer}（历史采样 + 分窗速度 + 双路径评分），
 * 本类只负责"钩子 + 前置闸门（区域/锁屏/多指/互斥/冷却）+ 触发 + 结果发布"。
 */
public class HyperHand extends XposedModule {

    static final String TAG = "HyperHand";

    static final String PKG_SYSUI = "com.android.systemui";
    static final String PKG_LAUNCHER = "com.miui.home";

    static final String CLS_TOUCH = "com.android.wm.shell.onehanded.OneHandedTouchHandler";
    static final String CLS_CTRL = "com.android.wm.shell.onehanded.OneHandedController";
    /** 单手模式上方那块纯色遮罩（SurfaceControlViewHost + 普通 View）。 */
    static final String CLS_BG_WINDOW = "com.android.wm.shell.onehanded.BackgroundWindowManager";
    /** 教程层：一条独立窗口，铺在上半屏，米白通常由它画出来。 */
    static final String CLS_TUTORIAL = "com.android.wm.shell.onehanded.OneHandedTutorialHandler";
    static final String CLS_NAV = "com.miui.home.recents.NavStubView";
    /** 桌面的"下拉打开通知/控制中心"手势控制器。 */
    static final String CLS_STATUS_BAR_SWIPE = "com.miui.home.launcher.uioverrides.StatusBarSwipeController";

    /** 桌面下拉手势转发到 SystemUI 的 binder 入口（ISystemUiProxy 的实现类）。 */
    static final String CLS_LAUNCHER_PROXY = "com.android.systemui.recents.LauncherProxyService$1";
    /** 通知中心开始下拉。 */
    static final String CLS_PANEL_VC = "com.android.systemui.shade.NotificationPanelViewController";
    /** 控制中心下拉事件处理。 */
    static final String CLS_CC_HANDLER =
            "com.miui.systemui.controlcenter.container.ControlCenterEventHandlerImpl";
    /** 控制中心本体（expand 入口在这里，手势之外的展开也会走）。 */
    static final String CLS_CC_IMPL = "com.miui.systemui.controlcenter.ControlCenterImpl";
    /** 状态栏状态机：面板真正展开/收起时会 setState，用来判断"面板已经落定"。 */
    static final String CLS_SB_STATE =
            "com.android.systemui.statusbar.StatusBarStateControllerImpl";

    /** 桌面下拉手势真正发出事件的入口（桌面进程）。 */
    static final String CLS_SYSUI_PROXY_WRAPPER = "com.miui.home.recents.SystemUiProxyWrapper";
    static final String CLS_SYSUI_PROXY_WRAPPER_MIUI = "com.miui.home.recents.MiuiSystemUiProxyWrapper";

    static final String ACTION_SET = "io.github.zpan2327.hyperhand.action.SET";

    /** transaction code: ISystemUiProxy.onStatusBarMotionEvent(MotionEvent)。 */
    static final int TX_ON_STATUS_BAR_MOTION_EVENT = 10;

    /** 静态判定路径（startOneHanded）也要用 getInvoker，留一个进程内引用。 */
    private static volatile Hooks sHooks;

    private final Hooks hooks;

    public HyperHand() {
        hooks = new Hooks(this);
        sHooks = hooks;
    }

    /** OneHandedController 实例（构造时抓取，供 startOneHanded() 调用）。 */
    private static volatile Object sController;

    /** 最近一次"通知中心/控制中心下拉"信号的时间戳（uptimeMillis）。 */
    private static volatile long sShadeIntentAt;
    private static volatile long sLastShadeLogAt;

    /**
     * 桌面进程：本次手势是否从底部触发区里起手。
     * 桌面有两处会碰下拉：StatusBarSwipeController 决定要不要接管，
     * 接管之后由 DispatchTouchHandler → SystemUiProxyWrapper 真正把事件发出去。
     * 只有同时卡住这两处，58dp 以内才真的不会冒出通知/控制中心。
     */
    private static volatile boolean sLauncherZoneDown;
    private static volatile long sLauncherZoneDownAt;

    /** 手势判定状态。轨迹本身由 Recognizer 维护。 */
    private static final Recognizer sRec = new Recognizer();
    private static boolean sTracking;
    private static boolean sTriggered;
    private static long sDownTime;
    private static long sLastTriggerAt;
    /** 最近一次 StatusBarState 切换的时刻（毫秒时间戳，0 = 从未），调试用。 */
    static volatile long sLastSetStateAt;
    /** 最近一次 StatusBarState 的目标值，调试用。 */
    static volatile int sLastSetStateValue = -1;
    /** 本次手势的 DOWN 时刻单手模式是否已激活（供 2.9 的模糊区手势复用，避免每帧读 Settings）。 */
    private static boolean sOhActiveAtDown;

    private static volatile boolean sDebug = true;
    private static volatile boolean sReceiverRegistered;
    private static volatile long sLastPublishAt;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        Log.i(TAG, "module loaded: " + getFrameworkName() + " " + getFrameworkVersion()
                + " (api " + getApiVersion() + "), process=" + param.getProcessName());
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        final String pkg = param.getPackageName();
        if (PKG_SYSUI.equals(pkg)) {
            hookSystemUi(param.getDefaultClassLoader());
        } else if (PKG_LAUNCHER.equals(pkg)) {
            hookLauncher(param.getDefaultClassLoader());
        }
    }

    // ------------------------------------------------------------------ SystemUI

    private void hookSystemUi(final ClassLoader cl) {
        log("attached to SystemUI");

        // 1) 抓取 OneHandedController 实例
        try {
            Class<?> ctrlCls = hooks.findClass(CLS_CTRL, cl);
            if (ctrlCls != null) {
                hooks.hookConstructors(ctrlCls, Hooks.after(new Hooks.After() {
                    @Override
                    public void run(XposedInterface.Chain chain, Object result) {
                        sController = chain.getThisObject();
                        log("OneHandedController captured");
                    }
                }));
            } else {
                log("OneHandedController class not found");
            }
        } catch (Throwable t) {
            log("hook OneHandedController failed: " + t);
        }

        // 2) 在原生手势监视器上做扩展触发判定
        try {
            Class<?> touchCls = hooks.findClass(CLS_TOUCH, cl);
            if (touchCls != null) {
                int n = hooks.hookMethods(touchCls, "onInputEvent", Hooks.before(new Hooks.Before() {
                    @Override
                    public Object run(XposedInterface.Chain chain) {
                        try {
                            List<Object> args = chain.getArgs();
                            if (args.size() == 1 && args.get(0) instanceof MotionEvent) {
                                onMonitorEvent(chain.getThisObject(), (MotionEvent) args.get(0));
                            }
                        } catch (Throwable t) {
                            log("monitor handler error: " + t);
                        }
                        return Hooks.PROCEED;
                    }
                }));
                log("OneHandedTouchHandler hooked (" + n + ")");
            } else {
                log("OneHandedTouchHandler class not found");
            }
        } catch (Throwable t) {
            log("hook OneHandedTouchHandler failed: " + t);
        }

        // 3) 记录"通知中心/控制中心下拉"信号，用于互斥
        hookShadeSignals(cl);

        // 4) 遮罩外观：把米白色换成模糊壁纸
        hookBackground(cl);

        // 5) 缩小/恢复动画：读系统时长并按其曲线调缓（只动单手模式这一条动画）
        try {
            AnimCtl.hook(cl, hooks);
        } catch (Throwable t) {
            log("hook anim failed: " + t);
        }

        // 6) 提前注册配置接收器：否则要等第一次触摸才会注册，
        //    设置页的「保存」「预览遮罩」在冷启动后第一次点会没有反应。
        registerReceiverLater();
    }

    private static void registerReceiverLater() {
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Context ctx = Hooks.currentApplication();
                    if (ctx == null) {
                        h.postDelayed(this, 800);
                        return;
                    }
                    ensureConfigReceiver(ctx);
                    // 预热模糊壁纸，保证第一次入场也是"第一帧就模糊"
                    BlurBg.warmUp(ctx);
                } catch (Throwable t) {
                    log("register config receiver (early) failed: " + t);
                }
            }
        }, 600);
    }

    /**
     * 单手模式上方那块纯色遮罩的处理。
     *
     * HyperOS 把它放在 {@code BackgroundWindowManager}：一个 View（mBackgroundView）
     * 挂在 SurfaceControlViewHost 上，颜色取自 getThemeColor()。它是普通 View，
     * 所以直接换 background drawable 即可 —— 只改"遮罩画什么"，不碰缩小动画、
     * 不碰触摸映射、不碰退出逻辑。
     *
     * 三个回调都补一刀：initView（首次创建）、showBackgroundLayer（每次进入单手模式）、
     * updateThemeOnly（深浅色/换主题时系统会重设颜色，要重新盖回去）。
     */
    private void hookBackground(ClassLoader cl) {
        try {
            Class<?> bg = hooks.findClass(CLS_BG_WINDOW, cl);
            if (bg == null) {
                log("BackgroundWindowManager class not found");
                return;
            }
            Hooks.After after = new Hooks.After() {
                @Override
                public void run(XposedInterface.Chain chain, Object result) {
                    try {
                        BlurBg.apply(chain.getThisObject());
                    } catch (Throwable t) {
                        log("blur bg hook error: " + t);
                    }
                }
            };
            XposedInterface.Hooker hooker = Hooks.after(after);
            int n = hooks.hookMethods(bg, "showBackgroundLayer", hooker);
            n += hooks.hookMethods(bg, "initView", hooker);
            n += hooks.hookMethods(bg, "updateThemeOnly", hooker);
            log("BackgroundWindowManager hooked (" + n + ")");

            // 记住实例，后面用它的 mViewHost 字段精确认出"垫底那层"的 View，
            // 不靠"猜哪块 View 是遮罩"，避免误伤别界面。
            int c = hooks.hookConstructors(bg, Hooks.after(new Hooks.After() {
                @Override
                public void run(XposedInterface.Chain chain, Object result) {
                    try {
                        BlurBg.onPanelManager(chain.getThisObject());
                    } catch (Throwable t) {
                        // 纯外观
                    }
                }
            }));
            log("BackgroundWindowManager ctor hooked (" + c + ")");
        } catch (Throwable t) {
            log("hook BackgroundWindowManager failed: " + t);
        }

        // 垫底层是一个 SurfaceControlViewHost 里的普通 View，走 setView 抓最直接
        try {
            Class<?> sch = hooks.findClass("android.view.SurfaceControlViewHost", cl);
            if (sch != null) {
                int n = hooks.hookMethods(sch, "setView", Hooks.after(new Hooks.After() {
                    @Override
                    public void run(XposedInterface.Chain chain, Object result) {
                        try {
                            List<Object> args = chain.getArgs();
                            if (args.size() < 3) return;
                            Object v = args.get(0);
                            if (!(v instanceof View)) return;
                            int w = args.get(1) instanceof Integer ? (Integer) args.get(1) : 0;
                            int h = args.get(2) instanceof Integer ? (Integer) args.get(2) : 0;
                            BlurBg.onHostSetView(chain.getThisObject(), (View) v, w, h);
                        } catch (Throwable t) {
                            // 纯外观
                        }
                    }
                }));
                log("SurfaceControlViewHost#setView hooked (" + n + ")");
            }
        } catch (Throwable t) {
            log("hook SurfaceControlViewHost failed: " + t);
        }

        // 关键：锁住背景。系统在入场动画里会把遮罩刷回主题米白，
        // 反复重贴必然闪；这里直接让它改不动（只放行我们自己的模糊图）。
        try {
            Class<?> viewCls = hooks.findClass("android.view.View", cl);
            if (viewCls != null) {
                int n = hooks.hookMethods(viewCls, "setBackgroundDrawable",
                        Hooks.before(new Hooks.Before() {
                            @Override
                            public Object run(XposedInterface.Chain chain) {
                                try {
                                    List<Object> args = chain.getArgs();
                                    Drawable d = null;
                                    if (args.size() > 0 && args.get(0) instanceof Drawable) {
                                        d = (Drawable) args.get(0);
                                    }
                                    Object self = chain.getThisObject();
                                    if (self instanceof View
                                            && BlurBg.blockBackgroundOverwrite((View) self, d)) {
                                        return null;   // 不调用原方法 = 系统改不动
                                    }
                                } catch (Throwable ignored) {
                                    // 拦截失败就走原方法，不影响系统
                                }
                                return Hooks.PROCEED;
                            }
                        }));
                log("View#setBackgroundDrawable guard (" + n + ")");
            }
        } catch (Throwable t) {
            log("hook View#setBackgroundDrawable failed: " + t);
        }

        // 教程层窗口：真正盖在上半屏的那条窗口（Window type 2024，
        // 标题 "one-handed-tutorial-overlay"）。它由 WindowManager 添加，
        // 直接在 addView 时按标题抓到 View，比反射 WMShell 内部字段可靠得多。
        try {
            Class<?> wmg = hooks.findClass("android.view.WindowManagerGlobal", cl);
            if (wmg == null) {
                log("WindowManagerGlobal class not found");
                return;
            }
            int m = hooks.hookMethods(wmg, "addView", Hooks.after(new Hooks.After() {
                @Override
                public void run(XposedInterface.Chain chain, Object result) {
                    try {
                        List<Object> args = chain.getArgs();
                        if (args.size() < 2) return;
                        Object v = args.get(0);
                        Object lp = args.get(1);
                        if (!(v instanceof View) || !(lp instanceof WindowManager.LayoutParams)) {
                            return;
                        }
                        CharSequence cs =
                                ((WindowManager.LayoutParams) lp).getTitle();
                        if (cs == null) return;
                        String title = cs.toString();
                        String lower = title.toLowerCase(Locale.US);
                        if (!lower.contains("one-hand") && !lower.contains("onehand")) {
                            return;
                        }
                        log("blur bg: overlay window added: " + title);
                        BlurBg.applyToOverlay((View) v, title,
                                ((WindowManager.LayoutParams) lp).width,
                                ((WindowManager.LayoutParams) lp).height);
                        // 同一个窗口也是"模糊区"唯一的触摸接收者（touchableRegion 就是上半屏），
                        // 顺手把"下滑拉通知栏"的语义挂上去。
                        if (lower.contains("overlay")) {
                            TopShade.attach((View) v);
                        }
                    } catch (Throwable t) {
                        log("window add hook error: " + t);
                    }
                }
            }));
            log("WindowManagerGlobal#addView hooked (" + m + ")");
        } catch (Throwable t) {
            log("hook WindowManagerGlobal failed: " + t);
        }

        // 教程层"填色"回调：系统把遮罩刷成主题色的同一帧里，我们紧接着换成模糊图，
        // 这样不会出现"先灰一下再变模糊"的闪烁。
        try {
            Class<?> tut = hooks.findClass(CLS_TUTORIAL, cl);
            if (tut != null) {
                Hooks.After after = new Hooks.After() {
                    @Override
                    public void run(XposedInterface.Chain chain, Object result) {
                        try {
                            BlurBg.dumpTutorialOnce(chain.getThisObject());
                            BlurBg.refreshOverlay();
                        } catch (Throwable t) {
                            // 纯外观，失败不影响主流程
                        }
                    }
                };
                XposedInterface.Hooker hooker = Hooks.after(after);
                int n = hooks.hookMethods(tut, "fillBackgroundColor", hooker);
                n += hooks.hookMethods(tut, "updateThemeColor", hooker);
                n += hooks.hookMethods(tut, "onAnimationUpdate", hooker);
                n += hooks.hookMethods(tut, "createViewAndAttachToWindow", hooker);
                log("OneHandedTutorialHandler color hooks (" + n + ")");
            } else {
                log("OneHandedTutorialHandler class not found");
            }
        } catch (Throwable t) {
            log("hook OneHandedTutorialHandler failed: " + t);
        }
    }

    /**
     * 三个信号点互为备份，任意一个命中即可判定"用户是在下拉通知/控制中心"：
     *   A. 桌面把下拉手势转发给 SystemUI 的 binder 入口（覆盖两条路径，最可靠）
     *   B. 通知中心开始接收下拉（startInputFocusTransfer）
     *   C. 控制中心开始处理下拉（ControlCenterEventHandlerImpl#handleExpandEvent）
     */
    private void hookShadeSignals(ClassLoader cl) {
        try {
            Class<?> proxy = hooks.findClass(CLS_LAUNCHER_PROXY, cl);
            if (proxy != null) {
                int n = hooks.hookMethods(proxy, "onTransact", Hooks.before(new Hooks.Before() {
                    @Override
                    public Object run(XposedInterface.Chain chain) {
                        try {
                            List<Object> args = chain.getArgs();
                            if (args.size() == 4 && args.get(0) instanceof Integer
                                    && ((Integer) args.get(0)) == TX_ON_STATUS_BAR_MOTION_EVENT) {
                                // 桌面把下拉手势转发过来时，如果这次手势的起点就在我们认领的
                                // 底部区域里（sTracking 只在这种手势期间为真），说明它是
                                // "从底部往下拖" —— 这块地归单手模式，通知/控制中心直接让位。
                                //
                                // 只在桌面侧让位（canInterceptTouch）不够：桌面仍可能把事件
                                // 转发过来，SystemUI 一收就展开通知中心，盖在单手模式上面；
                                // 而且这条转发还会点亮 shadeGuard，把已经判定的单手模式误杀。
                                // 在 binder 入口直接吞掉最干净：不展开、不点亮闸门、不影响
                                // 我们自己的监视通道（它是独立通道，早就收到同一串事件了）。
                                Context ctx = Hooks.currentApplication();
                                Cfg c = ctx == null ? null : Cfg.get(ctx);
                                if (c != null && c.enabled && c.claimZone
                                        && sTracking
                                        && SystemClock.uptimeMillis() - sDownTime < 2500) {
                                    // 注意这里**不看** sTriggered：触发之后手指往往还在继续拖，
                                    // 那些 MOVE 如果漏出去，桌面/系统就会顺手把通知中心拉下来。
                                    // 只要手势起于底部触发区、且还没结束，整段都归单手模式。
                                    if (c.debug) log("swallowed launcher shade forward (zone "
                                            + (sTriggered ? "after trigger" : "owned") + ")");
                                    return Boolean.TRUE;
                                }
                                markShadeIntent("launcher_forward");
                            }
                        } catch (Throwable t) {
                            // ignore
                        }
                        return Hooks.PROCEED;
                    }
                }));
                log("LauncherProxyService$1 hooked (shade guard, " + n + ")");
            } else {
                log("LauncherProxyService$1 class not found");
            }
        } catch (Throwable t) {
            log("hook launcher proxy failed: " + t);
        }

        try {
            Class<?> pvc = hooks.findClass(CLS_PANEL_VC, cl);
            if (pvc != null) {
                int n = 0;
                for (Method m : pvc.getDeclaredMethods()) {
                    if (m.getName().startsWith("startInputFocusTransfer")) {
                        hooks.hook(m, Hooks.before(new Hooks.Before() {
                            @Override
                            public Object run(XposedInterface.Chain chain) {
                                markShadeIntent("shade_transfer");
                                return Hooks.PROCEED;
                            }
                        }));
                        n++;
                    }
                }
                // 2.9.3：面板真正的展开入口只有这三个。
                // 不管手势从哪儿起手（状态栏、应用内、锁屏、程序化调用），
                // 最终都会落到 expand/expandToNotifications/expandToQs 上，
                // 挂这里比逐个猜手势起点可靠得多。
                int opened = 0;
                for (String name : new String[]{"expand", "expandToNotifications", "expandToQs"}) {
                    opened += hooks.hookMethods(pvc, name, Hooks.before(new Hooks.Before() {
                        @Override
                        public Object run(XposedInterface.Chain chain) {
                            if (blockShadeByZoneGesture(chain, "notification_shade")) return null;
                            markShadeIntent("shade_expand");
                            // 顺手记下面板实例：后面判断"面板到底有没有真的展开"
                            // 要读它的展开比例（launcher_forward 只代表意图，不够）
                            TopShade.rememberPanel(chain.getThisObject());
                            TopShade.onShadeExpanded("notification_shade");
                            return Hooks.PROCEED;
                        }
                    }));
                }
                log("NotificationPanelViewController hooked (shade guard, " + n
                        + "; expand entry, " + opened + ")");
            } else {
                log("NotificationPanelViewController class not found");
            }
        } catch (Throwable t) {
            log("hook panel controller failed: " + t);
        }

        // 控制中心自己的展开入口（手势之外，快捷方式/程序化调用也走这里）
        try {
            Class<?> ccImpl = hooks.findClass(CLS_CC_IMPL, cl);
            if (ccImpl != null) {
                // 控制中心不走 NotificationPanelViewController，展开状态得读它自己的。
                // 构造器记实例，probe 才能看到它的真实状态。
                hooks.hookMethods(ccImpl, "<init>", Hooks.after(new Hooks.After() {
                    @Override
                    public void run(XposedInterface.Chain chain, Object result) {
                        TopShade.rememberControlCenter(chain.getThisObject());
                    }
                }));
                int n = hooks.hookMethods(ccImpl, "expand", Hooks.before(new Hooks.Before() {
                    @Override
                    public Object run(XposedInterface.Chain chain) {
                        if (blockShadeByZoneGesture(chain, "control_center")) return null;
                        markShadeIntent("control_center_expand");
                        TopShade.rememberControlCenter(chain.getThisObject());
                        TopShade.onShadeExpanded("control_center");
                        return Hooks.PROCEED;
                    }
                }));
                log("ControlCenterImpl hooked (expand entry, " + n + ")");
            } else {
                log("ControlCenterImpl class not found");
            }
        } catch (Throwable t) {
            log("hook control center impl failed: " + t);
        }

        try {
            Class<?> cc = hooks.findClass(CLS_CC_HANDLER, cl);
            if (cc != null) {
                int n = hooks.hookMethods(cc, "handleExpandEvent", Hooks.before(new Hooks.Before() {
                    @Override
                    public Object run(XposedInterface.Chain chain) {
                        markShadeIntent("control_center");
                        // 2.9.1：控制中心被拉出来之后，把单手模式一并退掉（和模糊区手势保持一致）。
                        // 只认抬手，避免拖动过程中窗口跟着上移。
                        try {
                            List<Object> args = chain.getArgs();
                            if (args.size() >= 1 && args.get(0) instanceof MotionEvent) {
                                MotionEvent me = (MotionEvent) args.get(0);
                                if (me.getActionMasked() == MotionEvent.ACTION_UP) {
                                    // 抬手不代表面板一定出来：进度不够时系统会把它收回。
                                    // 只进入"待确认"，等状态机切换/展开出口来证实。
                                    TopShade.armConfirm("control_center");
                                }
                            }
                        } catch (Throwable ignored) {
                            // 纯补丁，失败不影响原有行为
                        }
                        return Hooks.PROCEED;
                    }
                }));
                log("ControlCenterEventHandlerImpl hooked (shade guard, " + n + ")");
            } else {
                log("ControlCenterEventHandlerImpl class not found");
            }
        } catch (Throwable t) {
            log("hook control center failed: " + t);
        }

        // 面板"落定"信号：StatusBarState 切到展开态时会调 setState。
        // 不判断具体状态值 —— 只有在我们刚发过"面板正在展开"的信号之后才响应，
        // 所以锁屏/解锁引起的状态切换不会误触发。
        try {
            Class<?> ssc = hooks.findClass(CLS_SB_STATE, cl);
            if (ssc != null) {
                int n = hooks.hookMethods(ssc, "setState", Hooks.before(new Hooks.Before() {
                    @Override
                    public Object run(XposedInterface.Chain chain) {
                        try {
                            Object a0 = chain.getArg(0);
                            if (a0 instanceof Integer) sLastSetStateValue = (Integer) a0;
                            sLastSetStateAt = SystemClock.uptimeMillis();
                            TopShade.onShadeSettled("panel_settled");
                        } catch (Throwable ignored) {
                            // 纯补丁
                        }
                        return Hooks.PROCEED;
                    }
                }));
                log("StatusBarStateControllerImpl hooked (settled signal, " + n + ")");
            } else {
                log("StatusBarStateControllerImpl class not found");
            }
        } catch (Throwable t) {
            log("hook status bar state controller failed: " + t);
        }
    }

    /**
     * 底部触发区起手的手势不该把面板拉出来。
     *
     * 日志证据：`TRIGGER(E)` 之后 7ms 就冒出 `shade_expand` —— 触发单手模式后
     * 手指还在继续往下拖，这串 MOVE 被系统当成了"下拉状态栏"，
     * 结果就是"进单手模式的同时通知中心滑下来"。
     *
     * @return true 表示调用方应该直接返回 null，跳过原方法
     */
    private static boolean blockShadeByZoneGesture(XposedInterface.Chain chain, String tag) {
        if (!isZoneGesture()) return false;
        // 只拦 void 方法：返回 null 会让非 void 方法的调用方拿到 null
        java.lang.reflect.Executable e = chain.getExecutable();
        if (e instanceof Method && ((Method) e).getReturnType() != void.class) return false;
        if (sDebug) log("blocked shade expand (" + tag + ", zone gesture)");
        return true;
    }

    static void markShadeIntent(String why) {
        sShadeIntentAt = SystemClock.uptimeMillis();
        if (sDebug && sShadeIntentAt - sLastShadeLogAt > 500) {
            sLastShadeLogAt = sShadeIntentAt;
            log("shade intent: " + why);
        }
    }

    /** 最近一次"面板开始下拉"的时刻（uptimeMillis），供 2.9.1 的虚拟顶部判定使用。 */
    static long lastShadeIntentAt() {
        return sShadeIntentAt;
    }

    /**
     * 手势监视器回调：只观察，不改变原生事件流。
     *
     * 前置闸门（不满足直接放弃，不进算法）：
     *   区域 / 横屏 / 多指 / 锁屏 / 单手模式未启用或已激活。
     * 收尾时的额外闸门：
     *   通知-控制中心互斥、冷却。
     */
    private static void onMonitorEvent(Object handler, MotionEvent ev) {
        final Context ctx = Hooks.currentApplication();
        if (ctx == null) return;
        ensureConfigReceiver(ctx);

        final Cfg c = Cfg.get(ctx);
        sDebug = c.debug;
        if (!c.enabled || !c.monitorEnabled) return;

        final int action = ev.getActionMasked();
        final long now = ev.getEventTime();
        final Cfg.Screen sc = Cfg.Screen.get(ctx);

        // 2.9：模糊区下滑拉通知栏的兜底路径。
        // 单手模式激活时，overlay 上的子 View 可能吃掉触摸，挂在父 View 的 listener 就收不到；
        // 而手势监视通道拿到的是全屏事件，与窗口层级无关。这里只在 DOWN 读一次状态，
        // 避免每个 MOVE 都去读 Settings。
        if (action == MotionEvent.ACTION_DOWN) {
            sOhActiveAtDown = isOneHandedActive(ctx);
        }
        if (sOhActiveAtDown) {
            TopShade.observe(action, ev.getRawX(), ev.getRawY());
        }

        if (action == MotionEvent.ACTION_DOWN) {
            sTracking = false;
            sTriggered = false;
            if (ev.getPointerCount() > 1) return;
            if (sc.isLandscape()) return;

            final float x = ev.getX();
            final float y = ev.getY();
            final boolean inZone = sc.inTriggerZone(c, x, y);

            // 校准用：把"这一次起手有多高"写出去，设置页能直接看到自己
            // 平时的落点离触发区边界差多少，不用去翻 logcat。
            publishDownGap(ctx, c, sc, x, y, inZone);

            if (inZone) {
                if (sOhActiveAtDown) return;
                if (isKeyguardLocked(ctx)) return;
                sTracking = true;
                sDownTime = now;
                sRec.begin(now, x, y);
                if (c.debug) {
                    log(String.format(Locale.US, "DOWN zone x=%.0f y=%.0f h=%d zonePx=%.0f mode=%d",
                            x, y, sc.height, c.zoneDp * sc.density, c.mode));
                }
            } else if (c.debug) {
                log(String.format(Locale.US, "DOWN outside zone x=%.0f y=%.0f zoneTop=%.0f",
                        x, y, sc.zoneTop(c)));
            }
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (!sTracking || sTriggered) return;
            if (ev.getPointerCount() > 1) {
                sTracking = false;
                if (c.debug) log("SKIP(multi_touch)");
                return;
            }
            // 历史采样一起收：120/144Hz 屏靠它才能算出稳定的窗口速度
            final int hist = ev.getHistorySize();
            for (int i = 0; i < hist; i++) {
                sRec.add(ev.getHistoricalEventTime(i), ev.getHistoricalX(i), ev.getHistoricalY(i));
            }
            sRec.add(now, ev.getX(), ev.getY());

            // 提前触发：手指一碰到屏幕底边就立刻判定，不等抬手。
            // 等 UP 再判的话，在可滚动的页面上这几十毫秒里内容已经跟着滚了一段，
            // 观感上就是"既滚了页面又进了单手模式"。到边即判能把这段滚动压到最短。
            if (c.endBoost && !sTriggered && ev.getY() >= sc.height - c.endZoneDp * sc.density) {
                decide(handler, ctx, c, sc, now, true);
            }
            return;
        }

        if (action == MotionEvent.ACTION_UP) {
            if (sTracking && !sTriggered) {
                sRec.add(now, ev.getX(), ev.getY());
                decide(handler, ctx, c, sc, now, false);
            }
            sTracking = false;
            sTriggered = false;
            return;
        }

        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (sTracking && c.debug) {
                log("SKIP(cancel)");
                publish(ctx, c, null, "cancel");
            }
            sTracking = false;
            sTriggered = false;
        }
    }

    /**
     * 记录最近一次"底部附近"的起手高度。
     * 只在距底 150dp 以内记，且 300ms 节流 —— 目的是让设置页能显示
     * "你刚才那一下离触发区还差几 dp"，避免只有翻 logcat 才能校准。
     */
    private static volatile long sLastDownPubAt;

    private static void publishDownGap(Context ctx, Cfg c, Cfg.Screen sc,
                                       float x, float y, boolean inZone) {
        if (!c.debug) return;
        final float gapDp = (sc.height - y) / Math.max(sc.density, 0.1f);
        if (gapDp < 0f || gapDp > 150f) return;
        long now = SystemClock.uptimeMillis();
        if (now - sLastDownPubAt < 300) return;
        sLastDownPubAt = now;
        try {
            float delta = gapDp - c.zoneDp;
            String tip;
            if (inZone) {
                // delta 恒为负：越接近 0 越贴边
                tip = delta > -10f ? "贴着边界" : String.format(Locale.US, "深入区内 %.0f dp", -delta);
            } else if (delta > 0f) {
                tip = String.format(Locale.US, "超出区线 %.0f dp，把高度调大约这个数", delta);
            } else {
                // y 明明在区线内却判成区外，只可能是水平方向出了范围
                tip = "高度够，问题在水平范围（宽度占比/左右偏置）";
            }
            Settings.Global.putString(ctx.getContentResolver(), Cfg.K_LAST_DOWN,
                    String.format(Locale.US, "最近一次起手：距底 %.0f dp · %s\n区线 %.0f dp · %s",
                            gapDp, inZone ? "区内" : "区外", c.zoneDp, tip));
        } catch (Throwable ignored) {
            // 纯调试显示
        }
    }

    /**
     * 统一判定入口。
     *
     * @param early true = 手指刚碰到屏幕底边时的提前判定（MOVE 阶段）；
     *              false = 抬手判定（UP 阶段）。
     *              提前判定没成功时静默返回，不写日志也不发布，等下一帧或抬手再判，
     *              否则一帧一条会把调试日志刷爆。
     */
    private static void decide(Object handler, Context ctx, Cfg c, Cfg.Screen sc, long now,
                               boolean early) {
        final Recognizer.Result r = sRec.finish(now, c, sc);

        if (!r.trigger && early) {
            return;
        }

        // 互斥：本次手势期间出现过通知中心/控制中心下拉信号 → 让位给原生
        if (r.trigger && c.shadeGuard && sShadeIntentAt >= (sDownTime - 60)) {
            r.trigger = false;
            r.reason = "shade";
        }
        // 冷却
        if (r.trigger && SystemClock.uptimeMillis() - sLastTriggerAt < c.cooldownMs) {
            r.trigger = false;
            r.reason = "cooldown";
        }

        if (c.debug) {
            log("GESTURE mode=" + c.mode + (early ? " early " : " ") + r.oneLine() + " -> "
                    + (r.trigger ? "TRIGGER(" + r.path + ")" : "SKIP(" + r.reason + ")"));
        }
        publish(ctx, c, r, r.reason);

        if (r.trigger) {
            sTriggered = true;
            sLastTriggerAt = SystemClock.uptimeMillis();
            startOneHanded(handler, ctx);
        }
    }

    private static void startOneHanded(Object handler, Context ctx) {
        Object ctrl = controller(handler);
        if (ctrl != null) {
            // HyperOS 把启动逻辑放在 OneHandedController 的内部实现里
            // （OneHandedImpl / IOneHandedImpl，实现 OneHanded 接口），
            // 控制器本身并没有 startOneHanded()，所以要先取到实现对象。
            Object impl = oneHandedImpl(ctrl);
            if (impl != null) {
                try {
                    sHooks.call(impl, "startOneHanded");
                    log("startOneHanded() via " + impl.getClass().getSimpleName());
                    return;
                } catch (Throwable t) {
                    log("startOneHanded() on " + impl.getClass().getSimpleName() + " failed: " + t);
                }
            } else {
                log("OneHandedImpl not resolved, falling back to activation flag");
            }
        }
        // 兜底：写原生状态位，等价于无障碍快捷方式激活。
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), "one_handed_mode_activated", 1);
            log("startOneHanded() fallback: wrote one_handed_mode_activated=1");
        } catch (Throwable t) {
            log("fallback failed: " + t);
        }
    }

    /**
     * 退出单手模式（走原生路径，带恢复动画）。
     *
     * OneHanded 接口上是无参的 stopOneHanded()；HyperOS 的 controller 上另有一个
     * stopOneHanded(int reason)，MiuiFreeformModeController 就是反射调它并传 5，
     * 所以两条都试一遍，最后才退回写状态位。
     */
    static void stopOneHanded() {
        final Object ctrl = sController;
        if (ctrl != null) {
            Object impl = oneHandedImpl(ctrl);
            if (impl != null) {
                try {
                    sHooks.call(impl, "stopOneHanded");
                    log("stopOneHanded() via " + impl.getClass().getSimpleName());
                    return;
                } catch (Throwable t) {
                    log("stopOneHanded() on " + impl.getClass().getSimpleName() + " failed: " + t);
                }
            }
            try {
                Method m = null;
                for (Class<?> k = ctrl.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Method mm : k.getDeclaredMethods()) {
                        if (mm.getName().equals("stopOneHanded") && mm.getParameterCount() == 1) {
                            m = mm;
                            break;
                        }
                    }
                    if (m != null) break;
                }
                if (m != null) {
                    m.setAccessible(true);
                    m.invoke(ctrl, 5);
                    log("stopOneHanded(int) via controller");
                    return;
                }
            } catch (Throwable t) {
                log("stopOneHanded(int) failed: " + t);
            }
        }
        try {
            Context ctx = Hooks.currentApplication();
            if (ctx != null) {
                Settings.Secure.putInt(ctx.getContentResolver(), "one_handed_mode_activated", 0);
                log("stopOneHanded() fallback: wrote one_handed_mode_activated=0");
            }
        } catch (Throwable t) {
            log("stopOneHanded fallback failed: " + t);
        }
    }

    /** 取 OneHandedController 里的 OneHanded 实现（有 startOneHanded() 的那个）。 */
    private static Object oneHandedImpl(Object ctrl) {
        try {
            Object o = sHooks.call(ctrl, "asOneHanded");
            if (o != null && hasNoArg(o, "startOneHanded")) {
                return o;
            }
        } catch (Throwable ignored) {
            // 没有 asOneHanded() 的话继续走字段扫描
        }
        for (Class<?> c = ctrl.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().contains("OneHandedImpl")) continue;
                try {
                    f.setAccessible(true);
                    Object o = f.get(ctrl);
                    if (o != null && hasNoArg(o, "startOneHanded")) {
                        return o;
                    }
                } catch (Throwable ignored) {
                    // 下一个
                }
            }
        }
        return null;
    }

    private static boolean hasNoArg(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return true;
            }
        }
        return false;
    }

    /** 优先用构造时抓到的实例；否则从 mTouchEventCallback 的外层引用反推。 */
    private static Object controller(Object handler) {
        Object c = sController;
        if (c != null) return c;
        if (handler == null) return null;
        try {
            Field f = handler.getClass().getDeclaredField("mTouchEventCallback");
            f.setAccessible(true);
            Object cb = f.get(handler);
            if (cb == null) return null;
            for (Field cf : cb.getClass().getDeclaredFields()) {
                if (CLS_CTRL.equals(cf.getType().getName())) {
                    cf.setAccessible(true);
                    Object o = cf.get(cb);
                    if (o != null) {
                        sController = o;
                        return o;
                    }
                }
            }
            for (Field cf : cb.getClass().getDeclaredFields()) {
                if (cf.getName().startsWith("this$")) {
                    cf.setAccessible(true);
                    Object o = cf.get(cb);
                    if (o != null && o.getClass().getName().startsWith("com.android.wm.shell.onehanded")) {
                        sController = o;
                        return o;
                    }
                }
            }
        } catch (Throwable t) {
            log("resolve controller failed: " + t);
        }
        return null;
    }

    /**
     * 当前手势是否起手于底部触发区、且还没结束。
     *
     * 这类手势整段归单手模式：触发之后手指往往还在继续往下拖，
     * 如果剩下的 MOVE 被系统解释成"下拉状态栏"，就会出现
     * "刚进单手模式，通知中心跟着滑下来"的现象。
     */
    static boolean isZoneGesture() {
        return sTracking && SystemClock.uptimeMillis() - sDownTime < 2500;
    }

    static boolean isOneHandedActive(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            if (Settings.Secure.getInt(cr, "one_handed_mode_enabled", 0) != 1) return true;
            if (Settings.Secure.getInt(cr, "one_handed_mode_activated", 0) == 1) return true;
            // 刚触发的那一下系统还没来得及回写激活位：startOneHanded() 的状态写盘跟在
            // 窗口动画后面，实测有 0.3~0.8s 的空窗。这段时间按"已激活"处理，否则
            // "刚落手就在中部下拉"会因为读不到 1 而被判成不在单手模式（日志里的
            // `ignored (not in one-handed)`）。stop 是幂等的，多认一次无害。
            final long t = sLastTriggerAt;
            return t > 0 && SystemClock.uptimeMillis() - t < 1800;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isKeyguardLocked(Context ctx) {
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把最近一次手势结果写进 Settings.Global，供设置页「手势调试」直接读取。
     * 只在调试模式下发写，且 200ms 节流；最多保留最近 3 条。
     */
    private static void publish(Context ctx, Cfg c, Recognizer.Result r, String reason) {
        if (!c.debug) return;
        long now = SystemClock.uptimeMillis();
        if (now - sLastPublishAt < 200) return;
        sLastPublishAt = now;
        try {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            StringBuilder sb = new StringBuilder();
            sb.append(time).append("  ");
            if (r != null) {
                sb.append(r.oneLine()).append('\n');
                sb.append("          → ").append(Recognizer.text(reason));
                if (r.trigger) sb.append("（路径 ").append(r.path).append('）');
            } else {
                sb.append("→ ").append(Recognizer.text(reason));
            }

            ContentResolver cr = ctx.getContentResolver();
            String old = Settings.Global.getString(cr, Cfg.K_LAST_GESTURE);
            StringBuilder all = new StringBuilder(sb);
            if (old != null && old.length() > 0) {
                String[] lines = old.split("\n(?=\\d{2}:\\d{2}:\\d{2})");
                int keep = Math.min(2, lines.length);
                for (int i = 0; i < keep; i++) {
                    all.append('\n').append(lines[i]);
                }
            }
            Settings.Global.putString(cr, Cfg.K_LAST_GESTURE, all.toString());
        } catch (Throwable t) {
            // 调试信息写失败不影响触发
        }
    }

    /**
     * 配置接收器：本模块界面（或 adb am broadcast）把参数发过来，
     * 由 SystemUI 进程写进 Settings.Global，两个被 hook 的进程都能读到。
     */
    private static void ensureConfigReceiver(final Context ctx) {
        if (sReceiverRegistered) return;
        synchronized (HyperHand.class) {
            if (sReceiverRegistered) return;
            try {
                final BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        try {
                            ContentResolver cr = context.getContentResolver();
                            // 调试动作：手动进入/退出一次单手模式（设置页的「预览遮罩」用它）
                            String act = intent.getStringExtra(Cfg.K_ACTION);
                            if ("reload".equals(act)) {
                                // 不需要 root：SystemUI 是 persistent 进程，自己退出后系统会立刻拉起，
                                // 模块随之重新加载最新代码。
                                log("debug action: reload (restart SystemUI)");
                                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        android.os.Process.killProcess(android.os.Process.myPid());
                                    }
                                }, 400);
                                return;
                            }
                            if ("blur".equals(act)) {
                                log("debug action: blur");
                                BlurBg.forceApplyOverlay();
                                return;
                            }
                            if ("probe".equals(act)) {
                                log("debug action: probe");
                                BlurBg.probe();
                                log("probe: panelExpanded=" + TopShade.isPanelExpanded()
                                        + " fraction=" + TopShade.panelFraction()
                                        + " cc=[" + TopShade.controlCenterState() + "]"
                                        + " setStateValue=" + sLastSetStateValue
                                        + " setStateAgo=" + (sLastSetStateAt == 0 ? -1
                                        : (SystemClock.uptimeMillis() - sLastSetStateAt))
                                        + " activated=" + isOneHandedActive(context));
                                return;
                            }
                            if ("armconfirm".equals(act)) {
                                log("debug action: armconfirm");
                                TopShade.armConfirm("debug_confirm");
                                return;
                            }
                            if ("trigger".equals(act)) {
                                log("debug action: trigger");
                                startOneHanded(null, context);
                                return;
                            }
                            if ("stop".equals(act)) {
                                log("debug action: stop");
                                Settings.Secure.putInt(cr, "one_handed_mode_activated", 0);
                                return;
                            }
                            if ("topshade".equals(act)) {
                                log("debug action: topshade");
                                TopShade.fireNow(context);
                                return;
                            }
                            int n = 0;
                            for (String k : Cfg.ALL_KEYS) {
                                if (intent.hasExtra(k)) {
                                    Settings.Global.putString(cr, k, String.valueOf(intent.getStringExtra(k)));
                                    n++;
                                }
                            }
                            Cfg.invalidate();
                            log("config applied (" + n + " keys): " + Cfg.get(context));
                            // 动画参数改了要立刻落到 organizer 的字段上，否则要等进程重启
                            AnimCtl.onConfigChanged(context);
                        } catch (Throwable t) {
                            log("apply config failed: " + t);
                        }
                    }
                };
                IntentFilter filter = new IntentFilter(ACTION_SET);
                if (Build.VERSION.SDK_INT >= 33) {
                    ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                } else {
                    ctx.registerReceiver(receiver, filter);
                }
                sReceiverRegistered = true;
                log("config receiver registered");
                Cfg.migrateIfNeeded(ctx);
            } catch (Throwable t) {
                log("register config receiver failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------------ Launcher

    private void hookLauncher(final ClassLoader cl) {
        log("attached to Launcher");
        try {
            Class<?> navCls = hooks.findClass(CLS_NAV, cl);
            if (navCls == null) {
                log("NavStubView class not found");
                return;
            }
            int n = hooks.hookMethods(navCls, "getHotSpaceHeight", Hooks.before(new Hooks.Before() {
                @Override
                public Object run(XposedInterface.Chain chain) {
                    try {
                        Context ctx = Hooks.currentApplication();
                        if (ctx == null) return Hooks.PROCEED;
                        Cfg c = Cfg.get(ctx);
                        if (!c.enabled || c.launcherHotDp <= 0f) return Hooks.PROCEED;
                        Cfg.Screen sc = Cfg.Screen.get(ctx);
                        int px = Math.round(c.launcherHotDp * sc.density);
                        if (c.debug) {
                            log("getHotSpaceHeight -> " + px + "px (" + c.launcherHotDp + "dp)");
                        }
                        return px;
                    } catch (Throwable t) {
                        // 出错就沿用原生返回值
                        return Hooks.PROCEED;
                    }
                }
            }));
            log("NavStubView#getHotSpaceHeight hooked (" + n + ")");
        } catch (Throwable t) {
            log("hook NavStubView failed: " + t);
        }

        // 底部区域归属：让桌面自身的"下拉打开通知/控制中心"手势在触发区内让位。
        // 它会在 dy > 2*touchSlop（约 16dp）时就把整段事件转发给状态栏，
        // 一旦转发成功，通知中心就会盖过单手模式，用户看到的就是"想进单手却开了通知中心"。
        // 2.2 起改用与 SystemUI 判定完全相同的几何（含宽度占比与左右偏置），
        // 避免"宽度收窄后桌面让位了、监视器又不认"的死区。
        try {
            Class<?> sbc = hooks.findClass(CLS_STATUS_BAR_SWIPE, cl);
            if (sbc != null) {
                int n = hooks.hookMethods(sbc, "canInterceptTouch", Hooks.before(new Hooks.Before() {
                    @Override
                    public Object run(XposedInterface.Chain chain) {
                        try {
                            Context ctx = Hooks.currentApplication();
                            if (ctx == null) return Hooks.PROCEED;
                            Cfg c = Cfg.get(ctx);
                            if (!c.enabled || !c.claimZone) return Hooks.PROCEED;
                            List<Object> args = chain.getArgs();
                            if (args.size() != 1 || !(args.get(0) instanceof MotionEvent)) {
                                return Hooks.PROCEED;
                            }
                            MotionEvent ev = (MotionEvent) args.get(0);
                            if (ev.getActionMasked() != MotionEvent.ACTION_DOWN) return Hooks.PROCEED;
                            Cfg.Screen sc = Cfg.Screen.get(ctx);
                            // 记下"这一次手势是不是从底部触发区里起手的"，
                            // 后面的转发入口（SystemUiProxyWrapper）靠它决定要不要放行。
                            // 每次 DOWN 都覆盖，所以不会跨手势残留，时间窗只是兜底。
                            sLauncherZoneDown = sc.inTriggerZone(c, ev.getX(), ev.getY());
                            sLauncherZoneDownAt = SystemClock.uptimeMillis();
                            if (sLauncherZoneDown) {
                                if (c.debug) {
                                    log(String.format(Locale.US,
                                            "launcher shade swipe declined: y=%.0f zoneTop=%.0f",
                                            ev.getY(), sc.zoneTop(c)));
                                }
                                return Boolean.FALSE;
                            }
                        } catch (Throwable t) {
                            // ignore
                        }
                        return Hooks.PROCEED;
                    }
                }));
                log("StatusBarSwipeController hooked (zone ownership, " + n + ")");
            } else {
                log("StatusBarSwipeController class not found");
            }
        } catch (Throwable t) {
            log("hook StatusBarSwipeController failed: " + t);
        }

        // 真正的转发动作：DispatchTouchHandler 是异步的，会在 canInterceptTouch 之后
        // 把整段 MotionEvent 直接交给 SystemUiProxyWrapper，不再回头问控制器。
        // 所以这里再卡一刀 —— 区内起手的手势，事件根本不出桌面进程，
        // SystemUI 那边自然既不会展开通知中心，也不会点亮 shadeGuard。
        try {
            Class<?> wrapper = hooks.findClass(CLS_SYSUI_PROXY_WRAPPER_MIUI, cl);
            if (wrapper == null) {
                wrapper = hooks.findClass(CLS_SYSUI_PROXY_WRAPPER, cl);
            }
            if (wrapper != null) {
                int n = hooks.hookMethods(wrapper, "onStatusBarMotionEvent",
                        Hooks.before(new Hooks.Before() {
                            @Override
                            public Object run(XposedInterface.Chain chain) {
                                try {
                                    if (!sLauncherZoneDown) return Hooks.PROCEED;
                                    if (SystemClock.uptimeMillis() - sLauncherZoneDownAt > 2000) {
                                        return Hooks.PROCEED;
                                    }
                                    List<Object> a = chain.getArgs();
                                    MotionEvent e = a.size() == 1 && a.get(0) instanceof MotionEvent
                                            ? (MotionEvent) a.get(0) : null;
                                    // 抬手/取消时收尾，避免标记跨到下一次手势
                                    if (e != null && (e.getActionMasked() == MotionEvent.ACTION_UP
                                            || e.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
                                        sLauncherZoneDown = false;
                                    }
                                    Context ctx = Hooks.currentApplication();
                                    Cfg c = ctx == null ? null : Cfg.get(ctx);
                                    if (c != null && c.debug && sShadeLogAllowed()) {
                                        log("swallowed launcher forward (zone owned) "
                                                + (e == null ? "?" : String.valueOf(e.getActionMasked())));
                                    }
                                    // 方法返回 boolean（是否发送成功），false = 不转发
                                    return Boolean.FALSE;
                                } catch (Throwable t) {
                                    return Hooks.PROCEED;
                                }
                            }
                        }));
                log("SystemUiProxyWrapper#onStatusBarMotionEvent hooked (" + n + ")");
            } else {
                log("SystemUiProxyWrapper class not found");
            }
        } catch (Throwable t) {
            log("hook SystemUiProxyWrapper failed: " + t);
        }
    }

    /** 吞转发的日志每 800ms 最多一条，避免每个 MOVE 刷爆。 */
    private static volatile long sLastSwallowLogAt;

    private static boolean sShadeLogAllowed() {
        long now = SystemClock.uptimeMillis();
        if (now - sLastSwallowLogAt < 800) return false;
        sLastSwallowLogAt = now;
        return true;
    }

    // ------------------------------------------------------------------ misc

    static void log(String msg) {
        Log.i(TAG, msg);
    }
}
