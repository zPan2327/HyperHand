package com.onehandtuner;

import android.app.AndroidAppHelper;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 小米 14 / HyperOS 3 (Android 16) 单手模式触发增强。
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
 *
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
 */
public class OneHandTuner implements IXposedHookLoadPackage {

    static final String TAG = "OneHandTuner";

    static final String PKG_SYSUI = "com.android.systemui";
    static final String PKG_LAUNCHER = "com.miui.home";

    static final String CLS_TOUCH = "com.android.wm.shell.onehanded.OneHandedTouchHandler";
    static final String CLS_CTRL = "com.android.wm.shell.onehanded.OneHandedController";
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

    static final String ACTION_SET = "com.onehandtuner.action.SET";

    /** transaction code: ISystemUiProxy.onStatusBarMotionEvent(MotionEvent)。 */
    static final int TX_ON_STATUS_BAR_MOTION_EVENT = 10;

    /** OneHandedController 实例（构造时抓取，供 startOneHanded() 调用）。 */
    private static volatile Object sController;

    /** 最近一次"通知中心/控制中心下拉"信号的时间戳（uptimeMillis）。 */
    private static volatile long sShadeIntentAt;
    private static volatile long sLastShadeLogAt;

    /** 手势判定状态。 */
    private static boolean sTracking;
    private static boolean sTriggered;
    private static float sDownX;
    private static float sDownY;
    private static long sDownTime;
    private static long sLastTriggerAt;
    private static float sPrevY;
    private static long sPrevT;
    /** 本次手势的向下峰值速度（px/s）。 */
    private static float sPeakVy;

    private static volatile boolean sDebug = true;
    private static volatile boolean sReceiverRegistered;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        final String pkg = lp == null ? null : lp.packageName;
        if (pkg == null) return;
        if (PKG_SYSUI.equals(pkg)) {
            hookSystemUi(lp);
        } else if (PKG_LAUNCHER.equals(pkg)) {
            hookLauncher(lp);
        }
    }

    // ------------------------------------------------------------------ SystemUI

    private void hookSystemUi(XC_LoadPackage.LoadPackageParam lp) {
        final ClassLoader cl = lp.classLoader;
        log("attached to SystemUI");

        // 1) 抓取 OneHandedController 实例
        try {
            Class<?> ctrlCls = XposedHelpers.findClassIfExists(CLS_CTRL, cl);
            if (ctrlCls != null) {
                XposedBridge.hookAllConstructors(ctrlCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        sController = param.thisObject;
                        log("OneHandedController captured");
                    }
                });
            } else {
                log("OneHandedController class not found");
            }
        } catch (Throwable t) {
            log("hook OneHandedController failed: " + t);
        }

        // 2) 在原生手势监视器上做扩展触发判定
        try {
            Class<?> touchCls = XposedHelpers.findClassIfExists(CLS_TOUCH, cl);
            if (touchCls != null) {
                XposedBridge.hookAllMethods(touchCls, "onInputEvent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args != null && param.args.length == 1
                                    && param.args[0] instanceof MotionEvent) {
                                onMonitorEvent(param.thisObject, (MotionEvent) param.args[0]);
                            }
                        } catch (Throwable t) {
                            log("monitor handler error: " + t);
                        }
                    }
                });
                log("OneHandedTouchHandler hooked");
            } else {
                log("OneHandedTouchHandler class not found");
            }
        } catch (Throwable t) {
            log("hook OneHandedTouchHandler failed: " + t);
        }

        // 3) 记录"通知中心/控制中心下拉"信号，用于互斥
        hookShadeSignals(cl);
    }

    /**
     * 三个信号点互为备份，任意一个命中即可判定"用户是在下拉通知/控制中心"：
     *   A. 桌面把下拉手势转发给 SystemUI 的 binder 入口（覆盖两条路径，最可靠）
     *   B. 通知中心开始接收下拉（startInputFocusTransfer）
     *   C. 控制中心开始处理下拉（ControlCenterEventHandlerImpl#handleExpandEvent）
     */
    private void hookShadeSignals(ClassLoader cl) {
        try {
            Class<?> proxy = XposedHelpers.findClassIfExists(CLS_LAUNCHER_PROXY, cl);
            if (proxy != null) {
                XposedBridge.hookAllMethods(proxy, "onTransact", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args != null && param.args.length == 4
                                    && param.args[0] instanceof Integer
                                    && ((Integer) param.args[0]) == TX_ON_STATUS_BAR_MOTION_EVENT) {
                                markShadeIntent("launcher_forward");
                            }
                        } catch (Throwable t) {
                            // ignore
                        }
                    }
                });
                log("LauncherProxyService$1 hooked (shade guard)");
            } else {
                log("LauncherProxyService$1 class not found");
            }
        } catch (Throwable t) {
            log("hook launcher proxy failed: " + t);
        }

        try {
            Class<?> pvc = XposedHelpers.findClassIfExists(CLS_PANEL_VC, cl);
            if (pvc != null) {
                int n = 0;
                for (Method m : pvc.getDeclaredMethods()) {
                    if (m.getName().startsWith("startInputFocusTransfer")) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                markShadeIntent("shade_transfer");
                            }
                        });
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
            Class<?> cc = XposedHelpers.findClassIfExists(CLS_CC_HANDLER, cl);
            if (cc != null) {
                XposedBridge.hookAllMethods(cc, "handleExpandEvent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        markShadeIntent("control_center");
                    }
                });
                log("ControlCenterEventHandlerImpl hooked (shade guard)");
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

    /** 手势监视器回调：只观察，不改变原生事件流。 */
    private static void onMonitorEvent(Object handler, MotionEvent ev) {
        final Context ctx = AndroidAppHelper.currentApplication();
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
            sPeakVy = 0f;
            if (ev.getPointerCount() > 1) return;

            final float x = ev.getX();
            final float y = ev.getY();
            final float zonePx = c.zoneDp * sc.density;
            if (y < sc.height - zonePx) return;

            final float halfWidth = sc.width * c.widthRatio / 2f;
            if (Math.abs(x - sc.width / 2f) > halfWidth) return;

            if (isKeyguardLocked(ctx)) return;
            if (isOneHandedActive(ctx)) return;

            sTracking = true;
            sDownX = x;
            sDownY = y;
            sDownTime = now;
            sPrevY = y;
            sPrevT = now;
            if (c.debug) {
                log(String.format("DOWN zone x=%.0f y=%.0f h=%d zonePx=%.0f",
                        x, y, sc.height, zonePx));
            }
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (!sTracking || sTriggered) return;
            if (ev.getPointerCount() > 1) {
                sTracking = false;
                return;
            }
            // 峰值速度：只看向下分量，用相邻两帧的增量算瞬时速度再取最大。
            // 这样"先慢后快"的甩动也能被识别，而匀速慢滑会被正确拒绝。
            final long dt = now - sPrevT;
            if (dt > 0 && dt <= 120) {
                final float v = (ev.getY() - sPrevY) * 1000f / dt;
                if (v > sPeakVy) sPeakVy = v;
            }
            sPrevY = ev.getY();
            sPrevT = now;
            return;
        }

        if (action == MotionEvent.ACTION_UP) {
            if (sTracking && !sTriggered) {
                final long dt = now - sPrevT;
                if (dt > 0 && dt <= 120) {
                    final float v = (ev.getY() - sPrevY) * 1000f / dt;
                    if (v > sPeakVy) sPeakVy = v;
                }
                decideOnUp(handler, ctx, c, sc, ev, now);
            }
            sTracking = false;
            sTriggered = false;
            return;
        }

        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            sTracking = false;
            sTriggered = false;
        }
    }

    /**
     * 抬手时统一判定，避免"某次 MOVE 恰好没满足条件"导致时灵时不灵。
     *
     * 规则（从上到下，命中即返回）：
     *   1. 位移方向必须向下（dy > 0），横向偏移不能超过 maxSlope * dy；
     *   2. 位移至少 minDyDp（默认 4dp），避免把轻点当成手势；
     *   3. 速度模式（minVelocity > 0，默认开）：峰值速度必须 >= minVelocity，
     *      于是"慢慢滑"永不触发、"快甩"必定触发；
     *      速度模式关闭（minVelocity = 0）时退化为纯位移触发；
     *   4. 手势期间若出现通知中心/控制中心下拉信号，则让位给原生（shadeGuard）；
     *   5. 冷却期内不重复触发。
     */
    private static void decideOnUp(Object handler, Context ctx, Cfg c, Cfg.Screen sc,
                                   MotionEvent ev, long now) {
        final float dy = ev.getY() - sDownY;
        final float dx = ev.getX() - sDownX;
        final long dur = now - sDownTime;
        final float minDyPx = c.minDyDp * sc.density;

        if (c.debug) {
            log(String.format("UP dy=%.0f dx=%.0f dur=%dms vpeak=%.0f",
                    dy, dx, dur, sPeakVy));
        }
        if (dy <= 0f) return;
        if (Math.abs(dx) > dy * c.maxSlope) return;
        if (dy < minDyPx) return;

        if (c.minVelocity > 0 && sPeakVy < c.minVelocity) {
            log(String.format("SKIP: too slow (vpeak=%.0f < %d px/s)", sPeakVy, c.minVelocity));
            return;
        }
        if (c.maxDurationMs > 0 && dur > c.maxDurationMs) {
            log("SKIP: too long (" + dur + "ms)");
            return;
        }
        if (c.shadeGuard && sShadeIntentAt >= (sDownTime - 60)) {
            log("SKIP: shade/control-center pull detected, keep native behaviour");
            return;
        }
        if (SystemClock.uptimeMillis() - sLastTriggerAt < c.cooldownMs) {
            log("SKIP: cooldown");
            return;
        }

        sTriggered = true;
        sLastTriggerAt = SystemClock.uptimeMillis();
        log(String.format("TRIGGER dy=%.0f dx=%.0f dur=%dms vpeak=%.0f", dy, dx, dur, sPeakVy));
        startOneHanded(handler, ctx);
    }

    private static void startOneHanded(Object handler, Context ctx) {        Object ctrl = controller(handler);
        if (ctrl != null) {
            try {
                XposedHelpers.callMethod(ctrl, "startOneHanded");
                log("startOneHanded() called on OneHandedController");
                return;
            } catch (Throwable t) {
                log("startOneHanded() failed: " + t);
            }
        }
        // 兜底：写原生状态位，等价于快捷方式开关。
        try {
            Settings.Secure.putInt(ctx.getContentResolver(), "one_handed_mode_activated", 1);
            log("startOneHanded() fallback: wrote one_handed_mode_activated=1");
        } catch (Throwable t) {
            log("fallback failed: " + t);
        }
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
     * 配置接收器：本模块界面（或 adb am broadcast）把参数发过来，
     * 由 SystemUI 进程写进 Settings.Global，两个被 hook 的进程都能读到。
     */
    private static void ensureConfigReceiver(final Context ctx) {
        if (sReceiverRegistered) return;
        synchronized (OneHandTuner.class) {
            if (sReceiverRegistered) return;
            try {
                final BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        try {
                            ContentResolver cr = context.getContentResolver();
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
            } catch (Throwable t) {
                log("register config receiver failed: " + t);
            }
        }
    }

    // ------------------------------------------------------------------ Launcher

    private void hookLauncher(XC_LoadPackage.LoadPackageParam lp) {
        log("attached to Launcher");
        try {
            Class<?> navCls = XposedHelpers.findClassIfExists(CLS_NAV, lp.classLoader);
            if (navCls == null) {
                log("NavStubView class not found");
                return;
            }
            XposedBridge.hookAllMethods(navCls, "getHotSpaceHeight", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Context ctx = AndroidAppHelper.currentApplication();
                        if (ctx == null) return;
                        Cfg c = Cfg.get(ctx);
                        if (!c.enabled || c.launcherHotDp <= 0f) return;
                        Cfg.Screen sc = Cfg.Screen.get(ctx);
                        int px = Math.round(c.launcherHotDp * sc.density);
                        param.setResult(px);
                        if (c.debug) {
                            log("getHotSpaceHeight -> " + px + "px (" + c.launcherHotDp + "dp)");
                        }
                    } catch (Throwable t) {
                        // 出错就沿用原生返回值
                    }
                }
            });
            log("NavStubView#getHotSpaceHeight hooked");
        } catch (Throwable t) {
            log("hook NavStubView failed: " + t);
        }

        // 底部区域归属：让桌面自身的"下拉打开通知/控制中心"手势在触发区内让位。
        // 它会在 dy > 2*touchSlop（约 16dp）时就把整段事件转发给状态栏，
        // 一旦转发成功，通知中心就会盖过单手模式，用户看到的就是"想进单手却开了通知中心"。
        try {
            Class<?> sbc = XposedHelpers.findClassIfExists(CLS_STATUS_BAR_SWIPE, lp.classLoader);
            if (sbc != null) {
                XposedBridge.hookAllMethods(sbc, "canInterceptTouch", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Context ctx = AndroidAppHelper.currentApplication();
                            if (ctx == null) return;
                            Cfg c = Cfg.get(ctx);
                            if (!c.enabled || !c.claimZone) return;
                            if (param.args == null || param.args.length != 1
                                    || !(param.args[0] instanceof MotionEvent)) return;
                            MotionEvent ev = (MotionEvent) param.args[0];
                            if (ev.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                            Cfg.Screen sc = Cfg.Screen.get(ctx);
                            float zoneTop = sc.height - c.zoneDp * sc.density;
                            if (ev.getY() >= zoneTop) {
                                param.setResult(Boolean.FALSE);
                                if (c.debug) {
                                    log(String.format("launcher shade swipe declined: y=%.0f zoneTop=%.0f",
                                            ev.getY(), zoneTop));
                                }
                            }
                        } catch (Throwable t) {
                            // ignore
                        }
                    }
                });
                log("StatusBarSwipeController hooked (zone ownership)");
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
