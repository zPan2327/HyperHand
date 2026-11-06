package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 单手模式下"模糊区任意位置下滑"→ 展开通知中心 / 控制中心，并立即退出单手模式。
 *
 * <h3>为什么要单独做这一层</h3>
 * 单手模式是把**所有** SystemUI 窗口整体下移（本机 1068px），于是：
 * <pre>
 *   y ∈ [0, 1068)     屏幕上半屏，只剩模糊壁纸（background-panel）
 *   y = 1068          状态栏 / NotificationShade 的新位置，也就是"虚拟顶部"
 *   y ∈ (1068, 2670]  缩小后的应用窗口
 * </pre>
 * 系统只把状态栏搬到了 1068 处，所以只有在那条线上往下拉才能出通知栏；
 * 真正铺满上半屏的窗口是 {@code one-handed-tutorial-overlay}
 * （frame=[0,0][1200,1068]，touchableRegion 也是这一块）。它正是"点上方退出单手模式"
 * 的触摸接收者，所以把监听挂在它身上，就天然拿到了整个模糊区的触摸，
 * 不需要再算边界，也不会干扰任何其它窗口的触摸分配。
 *
 * <h3>为什么不消费事件</h3>
 * 未达阈值时返回 false，事件继续走系统原本的处理（点一下 = 退出单手模式）；
 * 只有判定成"下滑"之后才吞掉后续事件，避免系统再按"点击"退一次。
 */
final class TopShade {

    private static final Handler sHandler = new Handler(Looper.getMainLooper());

    /** 已经挂过监听的 View，避免同一实例被反复包装。 */
    private static final Set<View> sAttached =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private static boolean sDown;
    private static boolean sFired;
    private static float sDownX;
    private static float sDownY;
    private static long sLastFireAt;
    /** "面板被拉出来 -> 退出单手模式"这条补丁的节流。 */
    private static long sLastExitAt;

    /** 当前手势落在哪块区域（只在 DOWN 时定一次，后续帧沿用）。 */
    private static final int MODE_NONE = 0;
    private static final int MODE_BLUR = 1;
    private static final int MODE_STRIP = 2;
    /** 单手模式下缩小窗口的内容区（屏幕中部）：下拉同样能出面板，但走的是转发通道。 */
    private static final int MODE_APP = 3;
    private static int sMode = MODE_NONE;

    /** 虚拟顶部那条状态栏上的下拉（系统自己展开面板，我们只管退出单手模式）。 */
    private static float sStripY;
    private static long sStripAt;
    /** 缩小窗口内容区的下拉（桌面/应用把事件转发给 SystemUI 展开面板）。 */
    private static float sAppY;
    private static long sAppAt;

    /** 模糊区那层 View，用来读它的高度（拿不到就按屏高兜底）。 */
    private static WeakReference<View> sOverlay = new WeakReference<View>(null);
    private static volatile int sOverlayHeight;

    private TopShade() {
    }

    /**
     * 给模糊区的遮罩 View 挂上语义：下滑 = 拉通知栏。
     * 由 {@code WindowManagerGlobal#addView} 在标题含 "one-hand…overlay" 时调用。
     */
    static void attach(final View v) {
        if (v == null) return;
        synchronized (sAttached) {
            if (sAttached.contains(v)) return;
            sAttached.add(v);
        }
        sOverlay = new WeakReference<View>(v);
        sOverlayHeight = v.getHeight();
        final View.OnTouchListener prev = readListener(v);
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent ev) {
                boolean mine = false;
                try {
                    mine = handle(ev);
                } catch (Throwable t) {
                    HyperHand.log("top shade gesture error: " + t);
                }
                if (mine) return true;
                return prev != null && prev.onTouch(view, ev);
            }
        });
        int children = v instanceof android.view.ViewGroup
                ? ((android.view.ViewGroup) v).getChildCount() : -1;
        HyperHand.log("top shade: swipe handler attached (" + v.getClass().getName()
                + ", h=" + sOverlayHeight + ", children=" + children + ")");
    }

    /** 保留系统原有的 OnTouchListener，避免把"点上方退出"之类的原生行为顶掉。 */
    private static View.OnTouchListener readListener(View v) {
        try {
            Field f = View.class.getDeclaredField("mListenerInfo");
            f.setAccessible(true);
            Object info = f.get(v);
            if (info == null) return null;
            Field lf = info.getClass().getDeclaredField("mOnTouchListener");
            lf.setAccessible(true);
            return (View.OnTouchListener) lf.get(info);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 模糊区下边界：优先用 overlay 实测高度，拿不到按 45% 屏高兜底（本机实测约 40%）。 */
    static float blurZoneBottom(Context ctx) {
        View v = sOverlay.get();
        if (v != null) {
            int h = v.getHeight();
            if (h > 0) {
                sOverlayHeight = h;
                return h;
            }
        }
        if (sOverlayHeight > 0) return sOverlayHeight;
        return Cfg.Screen.get(ctx).height * 0.45f;
    }

    /**
     * 手势监视通道的观察入口（兜底路径 + "虚拟顶部"区域）。
     *
     * monitor 语义只观察、不消费，所以这里不需要返回值，也不会影响其它窗口。
     * 之所以需要这条兜底：overlay 是个 FrameLayout，如果某个子 View 吃掉了触摸，
     * 挂在父 View 上的 OnTouchListener 就收不到事件；而 monitor 收到的是全屏事件，
     * 与窗口层级、子 View 无关。
     *
     * 除了模糊区，这里还接管"虚拟顶部"那条状态栏：在那一带往下拉是系统自己在展开
     * 通知中心/控制中心，我们不复刻这个动作，只负责在抬手时把单手模式退掉，
     * 让"无论从哪儿拉出面板都会退出单手模式"这件事保持一致。
     */
    static void observe(int action, float x, float y) {
        final Context ctx = Hooks.currentApplication();
        if (ctx == null) return;

        if (action == MotionEvent.ACTION_DOWN) {
            final float blurBottom = blurZoneBottom(ctx);
            final Cfg.Screen sc = Cfg.Screen.get(ctx);
            final Cfg c = Cfg.get(ctx);
            // 注意边界：overlay 的 touchableRegion 是 [0, 1068)，1068 本身已经属于状态栏，
            // 所以用 < 而不是 <=，避免在分界线上同时触发"自己展开"和"系统展开"。
            sMode = MODE_NONE;
            if (y < blurBottom) {                       // 模糊区：走完整判定（展开 + 退出）
                sMode = MODE_BLUR;
                handleCore(ctx, action, x, y);
            } else if (y <= blurBottom + statusStripHeight(ctx)) {
                sMode = MODE_STRIP;                     // 虚拟顶部那条状态栏
                sStripY = y;
                sStripAt = android.os.SystemClock.uptimeMillis();
            } else if (y < sc.zoneTop(c) - 8f * sc.density) {
                // 缩小窗口的内容区（屏幕中部）。
                // 单手模式下这一块是应用/桌面的地盘，下拉时它们会把事件转发给 SystemUI
                // 去展开面板（日志里的 shade intent: launcher_forward），而那条转发通道
                // 没有"抬手"钩子可挂 —— 之前只有模糊区和状态栏带能退出，中间这块漏了。
                // 这里按同一套语义补上：抬手时若确认面板被拉出来过，就退出单手模式。
                sMode = MODE_APP;
                sAppY = y;
                sAppAt = android.os.SystemClock.uptimeMillis();
            }
            return;
        }

        // 关键：区域只在 DOWN 时判断一次。
        // 之前每帧都拿 y 去比区线，手指往下拖之后 y 早就越过区线，UP 直接被
        // "不在区域内"挡掉 —— 左侧下拉（通知中心）因此永远等不到退出。
        if (sMode == MODE_BLUR) {
            handleCore(ctx, action, x, y);
            return;
        }
        if (sMode == MODE_APP) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                sMode = MODE_NONE;
                final boolean pulledDown = (y - sAppY) >= barPullPx(ctx);
                // 同样要确认这次手势真的引发了面板展开，否则"在应用里往下滚一屏"
                // 也会把单手模式退掉。
                final boolean shadeOpened = HyperHand.lastShadeIntentAt() >= sAppAt - 100;
                if (pulledDown && shadeOpened) {
                    onShadeGestureEnd("app_swipe");
                }
            }
            return;
        }
        if (sMode != MODE_STRIP) return;

        if (action == MotionEvent.ACTION_MOVE) return;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            sMode = MODE_NONE;
            final boolean pulledDown = (y - sStripY) >= barPullPx(ctx);
            // 还要确认这次手势真的把面板拉出来了（系统在处理下拉时会点亮 shade intent），
            // 否则"在状态栏上滑一下又缩回去"也会把单手模式退掉。
            final boolean shadeOpened = HyperHand.lastShadeIntentAt() >= sStripAt - 100;
            if (pulledDown && shadeOpened) {
                onShadeGestureEnd("virtual_top");
            }
        }
    }

    /** 虚拟顶部那条状态栏的高度（含一点余量，覆盖手指压线的情况）。 */
    private static float statusStripHeight(Context ctx) {
        final float density = Cfg.Screen.get(ctx).density;
        int h = 0;
        try {
            android.content.res.Resources r = ctx.getResources();
            int id = r.getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) h = r.getDimensionPixelSize(id);
        } catch (Throwable ignored) {
            // 走下面的兜底
        }
        if (h <= 0) h = Math.round(28 * density);
        return h + 12 * density;
    }

    /** 状态栏那条里判定"确实在往下拉"的最小距离。 */
    private static float barPullPx(Context ctx) {
        return Math.max(12f, Cfg.get(ctx).topShadeDyDp) * Cfg.Screen.get(ctx).density;
    }

    /**
     * 系统自己把面板拉出来之后，补一次"退出单手模式"。
     *
     * 接在三处：MIUI 控制中心的手势处理器（handleExpandEvent 的 ACTION_UP）、
     * 控制中心的 expand()、通知面板的 expand/expandToNotifications/expandToQs。
     * 后两个是**唯一**的展开出口 —— 不管手势从哪儿起手，最后都要经过它，
     * 所以挂在这里能覆盖状态栏、应用内、程序化调用等所有路径。
     *
     * 120ms 的延迟是为了让面板先起来，否则会被退出流程收掉（和模糊区那条一样的坑）。
     */
    static void onShadeExpanded(String why) {
        scheduleExit(why, 120);
    }

    static void onShadeGestureEnd(String why) {
        scheduleExit(why, 120);
    }

    private static void scheduleExit(String why, int delay) {
        final Context ctx = Hooks.currentApplication();
        if (ctx == null) return;
        final Cfg c = Cfg.get(ctx);
        if (!c.enabled || !c.topShade) return;
        final boolean active = HyperHand.isOneHandedActive(ctx);
        if (!active) {
            // 面板确实开了、但此刻已经不在单手模式：正常（比如刚被别的路径退过）。
            // 只在 debug 下留一行，方便排查"面板开了却没退"到底断在哪一步。
            if (c.debug) HyperHand.log("TOP SHADE: " + why + " ignored (not in one-handed)");
            return;
        }

        final long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastExitAt < 500) {
            if (c.debug) HyperHand.log("TOP SHADE: " + why + " ignored (throttled)");
            return;
        }
        sLastExitAt = now;

        HyperHand.log("TOP SHADE: shade opened via " + why + " -> exit one-handed");
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                HyperHand.stopOneHanded();
            }
        }, delay);
    }

    /** @return true 表示这次事件由我们处理掉，不再交给系统。 */
    private static boolean handle(MotionEvent ev) {
        final Context ctx = Hooks.currentApplication();
        if (ctx == null) return false;
        if (ev.getPointerCount() > 1) return false;
        final boolean fired = handleCore(ctx, ev.getActionMasked(), ev.getRawX(), ev.getRawY());
        return fired;
    }

    private static boolean handleCore(Context ctx, int action, float x, float y) {
        final Cfg c = Cfg.get(ctx);
        if (!c.enabled || !c.topShade) {
            sDown = false;
            sFired = false;
            return false;
        }

        if (action == MotionEvent.ACTION_DOWN) {
            sDown = false;
            sFired = false;
            // 只在单手模式激活时接管；普通状态下这块窗口根本不存在，这里只是保险
            if (!HyperHand.isOneHandedActive(ctx)) return false;
            sDown = true;
            sDownX = x;
            sDownY = y;
            return false;
        }

        if (!sDown) return false;

        if (action == MotionEvent.ACTION_MOVE) {
            if (sFired) return true;
            final Cfg.Screen sc = Cfg.Screen.get(ctx);
            final float dy = y - sDownY;
            final float dx = Math.abs(x - sDownX);
            final float need = Math.max(6f, c.topShadeDyDp) * sc.density;
            // 竖直为主才认，横向滑动留给系统
            if (dy >= need && dx <= dy * 1.6f) {
                sFired = true;
                fire(ctx, c, sc, sDownX);
            }
            return sFired;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            final boolean fired = sFired;
            sDown = false;
            sFired = false;
            return fired;
        }
        return false;
    }

    /** 调试入口：不经过手势判定，直接走"退出单手模式 + 展开面板"的完整链路。 */
    static void fireNow(Context ctx) {
        try {
            Cfg c = Cfg.get(ctx);
            Cfg.Screen sc = Cfg.Screen.get(ctx);
            sLastFireAt = 0;                     // 调试入口不受防抖限制
            fire(ctx, c, sc, sc.width / 2f);
        } catch (Throwable t) {
            HyperHand.log("top shade fireNow failed: " + t);
        }
    }

    /**
     * 先展开面板，等它站稳之后再在背后退出单手模式。
     *
     * 顺序是实测出来的：反过来（先 stop 再 expand）时，退出单手模式的流程会把
     * 刚展开的面板收掉 —— 日志里 expand 成功，最后看到的却是干净桌面。
     * 面板盖在最上层，窗口归位的动画藏在后面，观感上是"一下就拉下来了"，
     * 等用户收起面板时手机已经在正常模式里，不需要再手动退出。
     */
    private static void fire(final Context ctx, final Cfg c, final Cfg.Screen sc,
                             final float downX) {
        final boolean controlCenter;
        if (c.topShadeSide == Cfg.TOP_SIDE_CONTROL) {
            controlCenter = true;
        } else if (c.topShadeSide == Cfg.TOP_SIDE_NOTIFY) {
            controlCenter = false;
        } else {
            // 默认按落点左右分：与 HyperOS"右侧下拉出控制中心"的习惯一致
            controlCenter = downX > sc.width * 0.5f;
        }

        final long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastFireAt < 500) return;        // 防抖：同一次手势只拉一次
        sLastFireAt = now;

        HyperHand.log("TOP SHADE: swipe in blur area -> open "
                + (controlCenter ? "control center" : "notification center")
                + " then exit one-handed (dy=" + c.topShadeDyDp + "dp, x=" + (int) downX + ")");

        final boolean opened = expand(ctx, controlCenter);
        if (!opened) {
            // 展不开就至少保证退出单手模式，别把用户困在缩小窗口里
            HyperHand.log("TOP SHADE: expand failed, exit one-handed directly");
            HyperHand.stopOneHanded();
            return;
        }
        final int delay = Math.max(120, c.topShadeDelayMs);
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                HyperHand.stopOneHanded();
            }
        }, delay);
    }

    /**
     * 展开面板。SystemUI 自己是 system uid，因此 StatusBarManager 这条隐藏 API 可以直接用；
     * 万一被 ROM 改签名，再退回 binder 直连。
     */
    private static boolean expand(Context ctx, boolean controlCenter) {
        if (viaStatusBarManager(ctx, controlCenter)) {
            HyperHand.log("TOP SHADE: expanded via StatusBarManager");
            return true;
        }
        if (viaBinder(controlCenter)) {
            HyperHand.log("TOP SHADE: expanded via IStatusBarService");
            return true;
        }
        return false;
    }

    private static boolean viaStatusBarManager(Context ctx, boolean controlCenter) {
        try {
            Object sbm = ctx.getSystemService("statusbar");
            if (sbm == null) return false;
            Class<?> k = sbm.getClass();
            if (controlCenter) {
                if (invoke(k, sbm, "expandSettingsPanel",
                        new Class<?>[]{String.class}, new Object[]{null})) {
                    return true;
                }
                if (invoke(k, sbm, "expandSettingsPanel",
                        new Class<?>[]{}, new Object[]{})) {
                    return true;
                }
            }
            return invoke(k, sbm, "expandNotificationsPanel",
                    new Class<?>[]{}, new Object[]{});
        } catch (Throwable t) {
            HyperHand.log("expand via StatusBarManager failed: " + t);
            return false;
        }
    }

    private static boolean viaBinder(boolean controlCenter) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "statusbar");
            if (binder == null) return false;
            Class<?> stub = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub");
            Object svc = stub.getMethod("asInterface", android.os.IBinder.class)
                    .invoke(null, binder);
            if (svc == null) return false;
            Class<?> k = svc.getClass();
            if (controlCenter && invoke(k, svc, "expandSettingsPanel",
                    new Class<?>[]{String.class}, new Object[]{null})) {
                return true;
            }
            return invoke(k, svc, "expandNotificationsPanel",
                    new Class<?>[]{}, new Object[]{});
        } catch (Throwable t) {
            HyperHand.log("expand via IStatusBarService failed: " + t);
            return false;
        }
    }

    private static boolean invoke(Class<?> k, Object target, String name,
                                  Class<?>[] types, Object[] args) {
        try {
            Method m = k.getMethod(name, types);
            m.setAccessible(true);
            m.invoke(target, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
