package io.github.zpan2327.hyperhand;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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

    /** 手势判定状态。轨迹本身由 Recognizer 维护。 */
    private static final Recognizer sRec = new Recognizer();
    private static boolean sTracking;
    private static boolean sTriggered;
    private static long sDownTime;
    private static long sLastTriggerAt;

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

        // 5) 提前注册配置接收器：否则要等第一次触摸才会注册，
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
        } catch (Throwable t) {
            log("hook BackgroundWindowManager failed: " + t);
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
                        BlurBg.applyToOverlay((View) v, title);
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
                log("NotificationPanelViewController hooked (shade guard, " + n + ")");
            } else {
                log("NotificationPanelViewController class not found");
            }
        } catch (Throwable t) {
            log("hook panel controller failed: " + t);
        }

        try {
            Class<?> cc = hooks.findClass(CLS_CC_HANDLER, cl);
            if (cc != null) {
                int n = hooks.hookMethods(cc, "handleExpandEvent", Hooks.before(new Hooks.Before() {
                    @Override
                    public Object run(XposedInterface.Chain chain) {
                        markShadeIntent("control_center");
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
    }

    static void markShadeIntent(String why) {
        sShadeIntentAt = SystemClock.uptimeMillis();
        if (sDebug && sShadeIntentAt - sLastShadeLogAt > 500) {
            sLastShadeLogAt = sShadeIntentAt;
            log("shade intent: " + why);
        }
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

        if (action == MotionEvent.ACTION_DOWN) {
            sTracking = false;
            sTriggered = false;
            if (ev.getPointerCount() > 1) return;
            if (sc.isLandscape()) return;

            final float x = ev.getX();
            final float y = ev.getY();
            if (sc.inTriggerZone(c, x, y)) {
                if (isOneHandedActive(ctx)) return;
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
            return;
        }

        if (action == MotionEvent.ACTION_UP) {
            if (sTracking && !sTriggered) {
                sRec.add(now, ev.getX(), ev.getY());
                decideOnUp(handler, ctx, c, sc, now);
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

    /** 抬手时统一判定，避免"某次 MOVE 恰好没满足条件"导致时灵时不灵。 */
    private static void decideOnUp(Object handler, Context ctx, Cfg c, Cfg.Screen sc, long now) {
        final Recognizer.Result r = sRec.finish(now, c, sc);

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
            log("GESTURE mode=" + c.mode + " " + r.oneLine() + " -> "
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

    private static boolean isOneHandedActive(Context ctx) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            if (Settings.Secure.getInt(cr, "one_handed_mode_enabled", 0) != 1) return true;
            return Settings.Secure.getInt(cr, "one_handed_mode_activated", 0) == 1;
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
                            int n = 0;
                            for (String k : Cfg.ALL_KEYS) {
                                if (intent.hasExtra(k)) {
                                    Settings.Global.putString(cr, k, String.valueOf(intent.getStringExtra(k)));
                                    n++;
                                }
                            }
                            Cfg.invalidate();
                            log("config applied (" + n + " keys): " + Cfg.get(context));
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
                            if (sc.inTriggerZone(c, ev.getX(), ev.getY())) {
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
    }

    // ------------------------------------------------------------------ misc

    static void log(String msg) {
        Log.i(TAG, msg);
    }
}
