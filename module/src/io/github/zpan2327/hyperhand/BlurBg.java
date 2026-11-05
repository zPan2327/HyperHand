package io.github.zpan2327.hyperhand;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 把单手模式上方的纯色遮罩换成"高斯模糊的当前壁纸"。
 *
 * 现场取证（HyperOS OS3.0.305，dexdump 读 MiuiSystemUI.apk + dumpsys SurfaceFlinger/window）：
 *
 *  1) com.android.wm.shell.onehanded.BackgroundWindowManager
 *       View mBackgroundView  (挂在 SurfaceControlViewHost 里 → 层名 "background-panel")
 *       int  getThemeColorForBackground() / showBackgroundLayer() / updateThemeOnly() / initView()
 *     —— 全屏铺底，真正的"米白"来源之一。
 *
 *  2) 另有一条独立的 WindowManager 窗口 "one-handed-tutorial-overlay"
 *       Window #4 Window{... u0 one-handed-tutorial-overlay}  type=2024
 *       frame = 只占屏幕上半部分（本机实测 1200×1068，占屏高 40%），不可触摸
 *       —— 尺寸/位置全部运行时读取，代码里不写死任何机型数值
 *     —— 它盖在 background-panel 之上，实测屏幕上看到的那块浅灰就是它。
 *     因为它是普通窗口，直接钩 WindowManagerGlobal#addView(View, LayoutParams, ...)
 *     就能拿到 View，按 window title 匹配即可，不必依赖 WMShell 内部字段名。
 *
 * 于是：两处都换成同一个模糊位图 drawable。只改"遮罩画什么"，
 * 缩小动画、触摸映射、退出逻辑一概不碰。
 *
 * 模糊做法：壁纸按屏幕比例铺满 → 降到 1/6 分辨率（约 200×445）→ 三遍盒式模糊（≈高斯）
 * → 按 blur_dim 压暗。生成在后台线程，按 (壁纸 id | 屏幕尺寸 | 半径 | 压暗) 缓存。
 * drawable 用 FILL_HORIZONTAL|TOP 对齐，这样给"只有上半屏高"的窗口用时也不会被压扁。
 */
final class BlurBg {

    private static final Object LOCK = new Object();

    private static Field sViewField;
    private static boolean sViewFieldResolved;
    private static Method sThemeColor;

    /** 已生成的模糊壁纸。 */
    private static volatile Bitmap sBitmap;
    private static volatile String sBitmapKey;
    private static volatile String sRequestedKey;
    private static volatile boolean sBuilding;

    /** 缓存就绪前排队的 View，生成完再补一次。 */

    /** 最近一次处理过的窗口 View（探针用）。 */
    private static volatile View sLastOverlay;
    private static volatile String sLastOverlayTag;
    /** 遮罩窗口的 LayoutParams 尺寸（View 还没量测时用它算裁切）。 */
    private static volatile int sLastLpW;
    private static volatile int sLastLpH;
    private static volatile Object sLastWm;

    private static volatile boolean sMissingLogged;
    private static volatile long sLastApplyLogAt;
    private static volatile long sLastOverlayLogAt;
    private static volatile long sLastPanelLogAt;
    /** 每次入场递增，旧的延迟任务据此自动作废。 */
    private static volatile int sOverlayToken;

    /**
     * 已被我们接管的 View。接管之后，系统任何 setBackground* 都会被拦掉。
     *
     * 这是消除闪烁的关键：实测 logcat 里出现过
     *   blur bg applied: ... bgBefore=Color#FFDFDDE5
     * 也就是我们贴完模糊图之后，系统在入场动画里又把主题米白刷了回去，
     * 只能靠 620ms 后的重试再盖回来 —— 中间那几百毫秒就是肉眼看到的"灰层 + 闪"。
     * 与其反复重贴（必然和系统重绘打架），不如让系统改不动。
     */
    private static volatile View sLockedOverlay;
    private static volatile View sLockedPanel;
    private static volatile long sLastBlockLogAt;
    /** 诊断探针要能强行涂色，临时放行。 */
    private static volatile boolean sAllowAnyBackground;
    /** overlay 已经改为"透明、由垫底层出图"的状态。 */
    private static volatile boolean sOverlayTransparent;

    /** BackgroundWindowManager 实例（用来精确认出垫底那个 SurfaceControlViewHost）。 */
    private static volatile Object sBgManager;
    private static volatile boolean sPanelViewLogged;
    private static volatile int sOverlayDumped;

    private BlurBg() {
    }

    // ------------------------------------------------------------------ 入口

    /** BackgroundWindowManager 的 showBackgroundLayer / initView / updateThemeOnly 之后调用。 */
    static void apply(final Object windowManager) {
        if (windowManager == null) return;
        sLastWm = windowManager;
        final View view = fieldView(windowManager, "mBackgroundView");
        if (view == null) {
            if (!sMissingLogged) {
                sMissingLogged = true;
                HyperHand.log("blur bg: mBackgroundView not found on "
                        + windowManager.getClass().getName() + " fields=" + fieldNames(windowManager));
            }
            return;
        }
        if (!sPanelViewLogged) {
            sPanelViewLogged = true;
            HyperHand.log("blur bg: panel view captured: " + view.getClass().getName());
            HyperHand.log("blur bg: panel tree >>> " + dumpTree(view, 0));
        }
        applyNow(view, "background-panel", 0, 0);
    }

    /**
     * 任意"单手模式遮罩窗口"的 View（按 window title 命中后调用）。
     * 目前命中 one-handed-tutorial-overlay。
     */
    static void applyToOverlay(final View view, final String tag, final int lpW, final int lpH) {
        if (view == null) return;
        sLastOverlay = view;
        sLastOverlayTag = tag;
        sLastLpW = lpW;
        sLastLpH = lpH;
        final int token = ++sOverlayToken;
        // 关键：addView 之后立刻同步贴（此时 View 还没 attach，也没有 ViewRootImpl，
        // 任何线程都能安全 setBackground）。位图已缓存时这一步当场完成，
        // 窗口第一帧画出来就是模糊壁纸，不会先闪一层灰。
        applyNow(view, tag, lpW, lpH);
        // 前两次入场打印整棵视图树：确认"灰色"是不是某一层子 View 画的
        if (sOverlayDumped < 2) {
            sOverlayDumped++;
            HyperHand.log("blur bg: overlay tree(" + sOverlayDumped + ") >>> " + dumpTree(view, 0));
        }
        // 之后再稀疏补几次，兜住"系统在入场末尾把颜色刷回去"的情况（正常情况下会被锁拦掉）
        scheduleOverlay(view, tag, token, 0, lpW, lpH);
        // 兜底：个别机型可能抓不到垫底层，那样 overlay 保持透明就没有模糊图了。
        // 400ms 后仍未接管垫底层，就让 overlay 自己出图（退回 2.5.0 的行为）。
        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sLockedPanel == null && sLastOverlay == view) {
                    sOverlayTransparent = false;
                    applyNow(view, tag, lpW, lpH);
                    HyperHand.log("blur bg: no panel captured, fallback to overlay blur");
                }
            }
        }, 400);
    }

    private static final int[] OVERLAY_DELAYS = new int[] {260, 620, 1100, 1800};

    private static void scheduleOverlay(final View view, final String tag, final int token,
                                        final int idx, final int lpW, final int lpH) {
        if (idx >= OVERLAY_DELAYS.length) return;
        onViewThread(view, new Runnable() {
            @Override
            public void run() {
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (token != sOverlayToken) return;      // 已被新的一次入场取代
                        if (!view.isAttachedToWindow()) return;  // 已经退出单手模式
                        applyOnViewInternal(view, tag, lpW, lpH);
                        scheduleOverlay(view, tag, token, idx + 1, lpW, lpH);
                    }
                }, OVERLAY_DELAYS[idx]);
            }
        });
    }

    /**
     * 立刻把当前 overlay 的背景刷成模糊壁纸。
     *
     * 由 OneHandedTutorialHandler 的"填色"回调（fillBackgroundColor / updateThemeColor /
     * onAnimationUpdate）在同一帧里调用 —— 系统刚把遮罩刷成主题色，我们紧接着换成模糊图，
     * 绘制发生在同一帧，所以看不到中间态，也就不会闪。
     */
    static void refreshOverlay() {
        final View v = sLastOverlay;
        if (v == null) return;
        final String tag = sLastOverlayTag;
        onViewThread(v, new Runnable() {
            @Override
            public void run() {
                applyOnViewInternal(v, tag, sLastLpW, sLastLpH);
            }
        });
    }

    /** 当前背景是否已经是我们的模糊位图。 */
    private static boolean isOurBackground(View v) {
        Drawable d = v.getBackground();
        if (!(d instanceof BitmapDrawable)) return false;
        Bitmap b = ((BitmapDrawable) d).getBitmap();
        return b == sBitmap || b == sCropped;
    }

    private static boolean isTransparent(Drawable d) {
        if (d == null) return true;
        if (d instanceof ColorDrawable) return (((ColorDrawable) d).getColor() >>> 24) == 0;
        return false;
    }

    private static boolean isTransparentBg(View v) {
        return isTransparent(v.getBackground());
    }

    /**
     * {@code View#setBackgroundDrawable} 的前置拦截。
     *
     * @return true 表示"跳过原方法"，即拦下这次系统上色
     */
    static boolean blockBackgroundOverwrite(View v, Drawable d) {
        if (v == null || sAllowAnyBackground) return false;
        if (v != sLockedOverlay && v != sLockedPanel) return false;
        // overlay 走"透明"策略时，允许我们自己把它清成透明
        if (v == sLockedOverlay && sOverlayTransparent && isTransparent(d)) return false;
        // 我们自己贴的图放行（否则自己的 setBackground 也被拦，永远贴不上）
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b == sBitmap || b == sCropped) return false;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastBlockLogAt > 200) {
            sLastBlockLogAt = now;
            HyperHand.log("blur bg: blocked system repaint on "
                    + (v == sLockedPanel ? "panel" : "overlay") + " -> "
                    + describeBackground(d));
        }
        return true;
    }

    /** BackgroundWindowManager 构造完成：记下实例，后面据此认出垫底 View。 */
    static void onPanelManager(Object mgr) {
        if (mgr == null) return;
        sBgManager = mgr;
        sLastWm = mgr;
        HyperHand.log("blur bg: panel manager captured: " + mgr.getClass().getName());
        // 垫底层是常驻视图（不像 overlay 每次入场重建），提前贴好，
        // 这样缩小动画的第一帧就已经是模糊图，不会先露一帧灰。
        apply(mgr);
        // 视图/位图可能比管理器晚就绪，补几次（都是幂等操作，贴过就不再贴）
        final Object m = mgr;
        android.os.Handler h = new android.os.Handler(Looper.getMainLooper());
        for (final int delay : new int[] {600, 1500, 3000}) {
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    apply(m);
                }
            }, delay);
        }
    }

    /**
     * {@code SurfaceControlViewHost#setView} 之后调用。
     * 只处理 BackgroundWindowManager 自己那个 host —— 用 mViewHost 字段比对，
     * 不做"猜哪块 View 是遮罩"这种容易误伤别的界面的事。
     */
    static void onHostSetView(Object host, final View v, final int w, final int h) {
        final Object mgr = sBgManager;
        if (mgr == null || v == null || host == null) return;
        // 两个条件满足任意一个就认：这个 View 是 mBackgroundView，或者它就是 mViewHost 的宿主。
        // 不同版本里字段赋值时机不同，两条都留着更耐操。
        Object bgView = readField(mgr, "mBackgroundView");
        Object hv = readField(mgr, "mViewHost");
        boolean match = (bgView != null && bgView == v) || (hv != null && hv == host);
        if (!match) return;
        HyperHand.log("blur bg: panel host setView " + v.getClass().getSimpleName()
                + " " + w + "x" + h + " attached=" + v.isAttachedToWindow());
        applyNow(v, "background-panel", w, h);
    }

    /** 教程层在缓存就绪前先记下来，生成完再补一次。 */
    static void dumpTutorialOnce(Object handler) {
        if (handler == null || sTutorialDumped >= 3) return;
        try {
            Object v = readField(handler, "mTutorialView");
            if (!(v instanceof View)) return;
            sTutorialDumped++;
            HyperHand.log("blur bg: tutorial tree(" + sTutorialDumped + ") >>> "
                    + dumpTree((View) v, 0));
        } catch (Throwable t) {
            // 纯诊断
        }
    }

    private static volatile int sTutorialDumped;

    /** 反射读字段（沿父类找），读不到返回 null。 */
    private static Object readField(Object target, String name) {
        if (target == null) return null;
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException e) {
                // 继续往父类找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 字段名列表，只在"某个类找不到目标字段"时打印一次，方便换机型时定位。 */
    private static String fieldNames(Object target) {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : target.getClass().getDeclaredFields()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(f.getName());
            }
        } catch (Throwable t) {
            return "?";
        }
        return sb.toString();
    }

    /** 打印一小段视图树（类名 / 尺寸 / 背景 / 透明度），用于定位"到底哪一块在画灰色"。 */
    private static String dumpTree(View v, int depth) {
        StringBuilder sb = new StringBuilder();
        if (v == null || depth > 5) return sb.toString();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName())
                .append('[').append(v.getWidth()).append('x').append(v.getHeight()).append(']')
                .append(" bg=").append(describeBackground(v.getBackground()))
                .append(" a=").append(v.getAlpha())
                .append(" vis=").append(v.getVisibility());
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            int n = Math.min(g.getChildCount(), 6);
            for (int i = 0; i < n; i++) {
                sb.append("\n").append(dumpTree(g.getChildAt(i), depth + 1));
            }
        }
        return sb.toString();
    }


    private static boolean applyOnViewInternal(final View view, final String tag,
                                                    final int lpW, final int lpH) {
        final Context ctx = Hooks.currentApplication();
        if (ctx == null) return true;

        final Cfg c;
        try {
            c = Cfg.get(ctx);
        } catch (Throwable t) {
            return true;
        }

        // 关掉开关：把两个遮罩还原成系统主题色/透明，并解锁
        if (!c.blurBg) {
            final View lockedOv = sLockedOverlay;
            final View lockedPn = sLockedPanel;
            if (lockedOv != null || lockedPn != null || sBitmap != null) {
                sRequestedKey = null;
                sBitmap = null;
                sBitmapKey = null;
                sLockedOverlay = null;
                sLockedPanel = null;
                // 先解锁再改，否则会被自己的拦截挡下
                restore(lockedPn, true);
                restore(lockedOv, false);
                HyperHand.log("blur bg disabled");
            }
            return true;
        }

        final boolean isPanel = "background-panel".equals(tag);

        // overlay 盖在应用**之上**：如果它自己带模糊图，缩小动画还没开始时
        // 就会先蒙一层半透明的灰在应用上方（真机第 337 帧就是这个现象）。
        // 所以只要垫底层已经能画模糊图，overlay 就保持透明，
        // 让模糊只从"应用缩下去后露出的缝"里出现，动画节奏与原厂一致。
        if (!isPanel) {
            sLockedOverlay = view;
            sOverlayTransparent = true;
            if (!isTransparentBg(view)) {
                try {
                    view.setBackground(null);
                    HyperHand.log("blur bg: overlay -> transparent (panel carries the blur)");
                } catch (Throwable t) {
                    // 纯外观
                }
            }
            return true;
        }

        final String key = keyOf(ctx, c);
        final Bitmap bmp = sBitmap;
        if (bmp == null || !key.equals(sBitmapKey) || bmp.isRecycled()) {
            // 还没算好：保留系统原色，由重试/生成回调补
            if (!key.equals(sRequestedKey) || !sBuilding) {
                sRequestedKey = key;
                buildAsync(ctx, c, key);
            }
            return false;
        }

        // 已经是我们的位图就不再重复设置（避免动画期间反复重贴造成闪烁）
        if (isOurBackground(view)) {
            return true;
        }
        String before = describeBackground(view.getBackground());
        try {
            view.setBackground(makeDrawable(ctx, bmp, view, lpW, lpH));
        } catch (Throwable t) {
            HyperHand.log("blur bg: setBackground failed on " + tag + ": " + t);
            return true;
        }
        // 贴好就立刻上锁：之后系统再怎么刷主题色都会被拦下，不存在"灰一下再变模糊"
        if (isPanel) {
            sLockedPanel = view;
        } else {
            sLockedOverlay = view;
        }
        long now = android.os.SystemClock.uptimeMillis();
        long last = isPanel ? sLastPanelLogAt : sLastOverlayLogAt;
        if (now - last > 800) {
            if (isPanel) {
                sLastPanelLogAt = now;
            } else {
                sLastOverlayLogAt = now;
            }
            HyperHand.log("blur bg applied: " + tag + " view=" + view.getWidth() + "x"
                    + view.getHeight() + " vis=" + view.getVisibility()
                    + " bgBefore=" + before + " key=" + key
                    + " bmp=" + bmp.getWidth() + "x" + bmp.getHeight()
                    + " mean=#" + String.format("%06X", meanColor(bmp)));
        }
        return true;
    }

    /**
     * 模块挂载后预热：把模糊图提前算好并缓存。
     * 否则"装完模块/重启 SystemUI 之后的第一次"入场会因为位图还没生成而先露一层灰。
     */
    static void warmUp(final Context ctx) {
        try {
            final Cfg c = Cfg.get(ctx);
            if (!c.blurBg) return;
            final String key = keyOf(ctx, c);
            if (sBitmap != null && key.equals(sBitmapKey)) return;
            if (sBuilding) return;
            sRequestedKey = key;
            buildAsync(ctx, c, key);
        } catch (Throwable t) {
            // 预热失败不影响功能
        }
    }

    /** 诊断：无条件把模糊位图贴到当前 overlay 上，并回读背景类型。 */
    static void forceApplyOverlay() {
        final View ov = sLastOverlay;
        final Context ctx = Hooks.currentApplication();
        final Bitmap bmp = sBitmap;
        if (ov == null || ctx == null || bmp == null) {
            HyperHand.log("force: overlay=" + (ov != null) + " ctx=" + (ctx != null)
                    + " bmp=" + (bmp != null));
            return;
        }
        onViewThread(ov, new Runnable() {
            @Override
            public void run() {
                ov.setBackground(makeDrawable(ctx, bmp, ov, sLastLpW, sLastLpH));
                ov.invalidate();
                HyperHand.log("force: set blur on overlay " + ov.getWidth() + "x"
                        + ov.getHeight() + " -> bg=" + describeBackground(ov.getBackground())
                        + " alpha=" + ov.getAlpha() + " shown=" + ov.isShown());
            }
        });
    }

    /** 诊断探针：把两个遮罩分别涂成红/绿，用来确认屏幕上那块到底是谁画的。 */
    static void probe() {
        // 探针需要强行涂色，临时放行 4 秒（之后自动恢复"拦系统重绘"）
        sAllowAnyBackground = true;
        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                sAllowAnyBackground = false;
            }
        }, 4000);
        final View bg = sLastWm == null ? null : fieldView(sLastWm, "mBackgroundView");
        if (bg != null) {
            onViewThread(bg, new Runnable() {
                @Override
                public void run() {
                    bg.setBackground(new ColorDrawable(0xFFFF0000));
                    HyperHand.log("probe: background-panel painted red, "
                            + bg.getWidth() + "x" + bg.getHeight() + " shown=" + bg.isShown());
                }
            });
        } else {
            HyperHand.log("probe: background-panel not captured");
        }
        final View ov = sLastOverlay;
        if (ov != null) {
            onViewThread(ov, new Runnable() {
                @Override
                public void run() {
                    ov.setBackground(new ColorDrawable(0xFF00FF00));
                    HyperHand.log("probe: overlay painted green, "
                            + ov.getWidth() + "x" + ov.getHeight() + " shown=" + ov.isShown());
                }
            });
        } else {
            HyperHand.log("probe: overlay not captured");
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 这些 View 的 ViewRootImpl 建在 WMShell 自己的线程（wmshell.main）上，
     * 不是 Android 主线程；必须用 view.post() 投到它自己的 handler。
     */
    private static void onViewThread(View v, Runnable r) {
        android.os.Handler h = v.getHandler();
        if (h != null && h.getLooper() == Looper.myLooper()) {
            r.run();
        } else {
            v.post(r);
        }
    }

    /** 恢复系统外观：垫底层还原主题色，overlay 交回系统（下次入场它自己会重设）。 */
    private static void restore(final View v, final boolean panel) {
        if (v == null) return;
        applyNow2(v, new Runnable() {
            @Override
            public void run() {
                int color = 0;
                if (panel) color = themeColor(sBgManager);
                v.setBackground(color == 0 ? null : new ColorDrawable(color));
            }
        });
    }

    private static void applyNow2(View v, Runnable r) {
        android.os.Handler h = v.getHandler();
        if (h != null && h.getLooper() == Looper.myLooper()) {
            r.run();
        } else if (h != null) {
            v.post(r);
        } else {
            r.run();   // 还没 attach，直接改
        }
    }

    /** 读系统主题色（BackgroundWindowManager#getThemeColorForBackground），拿不到返回 0。 */
    private static int themeColor(Object mgr) {
        if (mgr == null) return 0;
        try {
            for (Method m : mgr.getClass().getDeclaredMethods()) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName();
                if (n.contains("getThemeColor") || n.equals("getThemeColorForBackground")) {
                    m.setAccessible(true);
                    Object r = m.invoke(mgr);
                    if (r instanceof Integer) return (Integer) r;
                }
            }
        } catch (Throwable t) {
            // 拿不到就交回 null，由系统下次自行绘制
        }
        return 0;
    }

    /**
     * 立刻处理（能同步就同步）。
     *
     * 关键点：窗口刚 addView / 刚 setView 时 View 还没 attach，也就没有 ViewRootImpl，
     * 不存在"必须在它自己线程改 View"的约束，可以直接 setBackground。
     * 之前这里一律 post 到 View 的 handler，实测要 13~40ms 才执行 ——
     * 而窗口的第一帧早就画出来了，那 1~2 帧就是肉眼看到的"灰层 + 闪"。
     */
    private static void applyNow(final View view, final String tag, final int lpW, final int lpH) {
        if (view == null) return;
        if (!view.isAttachedToWindow()) {
            applyOnViewInternal(view, tag, lpW, lpH);
            return;
        }
        onViewThread(view, new Runnable() {
            @Override
            public void run() {
                applyOnViewInternal(view, tag, lpW, lpH);
            }
        });
    }

    /**
     * 按目标 View 的尺寸裁出"顶端对齐的那一块"再铺满。
     *
     * 全部尺寸都是运行时取的，不写死任何机型参数：
     *   1) 已量测（getWidth/getHeight）→ 用实际值；
     *   2) 刚 addView 还没量测 → 用 LayoutParams 的 width/height（MATCH_PARENT 会是 -1）；
     *   3) 连 LayoutParams 都是 -1 → 用真实屏幕宽度兜底（Cfg.Screen 走 getRealMetrics）。
     *
     * 目的：遮罩窗口通常只有上半屏高，而模糊位图是整屏比例。直接 FILL 会被纵向压扁，
     * 用 gravity 缩放又会在部分 window 上留黑底，所以显式裁一段同比例区域再交给 FILL。
     */
    private static Drawable makeDrawable(Context ctx, Bitmap full, View v, int lpW, int lpH) {
        int vw = v.getWidth() > 0 ? v.getWidth() : lpW;
        int vh = v.getHeight() > 0 ? v.getHeight() : lpH;
        if (vw <= 0 || vh <= 0) {
            Cfg.Screen sc = Cfg.Screen.get(ctx);
            if (vw <= 0) vw = sc.width;
        }
        Bitmap use = full;
        if (vw > 0 && vh > 0) {
            float scale = vw / (float) full.getWidth();
            int needH = Math.round(vh / scale);
            if (needH > 0 && needH < full.getHeight()) {
                use = cropTop(full, needH);
            }
        }
        BitmapDrawable d = new BitmapDrawable(ctx.getResources(), use);
        d.setFilterBitmap(true);
        return d;
    }

    private static volatile Bitmap sCropped;
    private static volatile int sCroppedH;

    private static Bitmap cropTop(Bitmap full, int needH) {
        Bitmap cached = sCropped;
        if (cached != null && sCroppedH == needH && !cached.isRecycled()) {
            return cached;
        }
        try {
            Bitmap b = Bitmap.createBitmap(full, 0, 0, full.getWidth(), needH);
            sCropped = b;
            sCroppedH = needH;
            return b;
        } catch (Throwable t) {
            return full;
        }
    }

    private static String describeBackground(Drawable d) {
        if (d == null) return "null";
        if (d instanceof ColorDrawable) {
            return "Color#" + String.format("%08X", ((ColorDrawable) d).getColor());
        }
        return d.getClass().getSimpleName();
    }

    private static View fieldView(Object target, String name) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Object o = f.get(target);
                return o instanceof View ? (View) o : null;
            } catch (NoSuchFieldException e) {
                // 往父类找
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 生成

    private static String keyOf(Context ctx, Cfg c) {
        Cfg.Screen sc = Cfg.Screen.get(ctx);
        return wallpaperId(ctx) + "|" + sc.width + "x" + sc.height
                + "|r" + Math.round(c.blurRadiusDp) + "|d" + Math.round(c.blurDim);
    }

    private static int wallpaperId(Context ctx) {
        try {
            WallpaperManager wpm =
                    (WallpaperManager) ctx.getSystemService(Context.WALLPAPER_SERVICE);
            return wpm == null ? -1 : wpm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void buildAsync(final Context ctx, final Cfg cfg, final String key) {
        if (sBuilding) return;
        sBuilding = true;
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    bmp = build(ctx, cfg);
                } catch (Throwable t) {
                    HyperHand.log("blur bg: build failed: " + t);
                }
                sBuilding = false;
                if (bmp == null) {
                    HyperHand.log("blur bg: no wallpaper available, keep native color");
                    return;
                }
                sBitmap = bmp;
                sBitmapKey = key;
                // 生成完把两个遮罩都补一次（overlay 在上、background-panel 在下）
                final Object wm = sLastWm;
                if (wm != null) {
                    apply(wm);
                }
                final View ov = sLastOverlay;
                if (ov != null) {
                    applyToOverlay(ov, sLastOverlayTag, sLastLpW, sLastLpH);
                }
            }
        }, "HyperHand-blur");
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
    }

    /** 取壁纸 → 按屏幕比例铺满小图 → 三遍盒式模糊 → 压暗。 */
    private static Bitmap build(Context ctx, Cfg c) {
        Drawable src = loadWallpaper(ctx);
        if (src == null) return null;

        final Cfg.Screen sc = Cfg.Screen.get(ctx);
        final int w = Math.max(96, sc.width / 6);
        final int h = Math.max(96, Math.round(w * (float) sc.height / sc.width));

        Bitmap small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            Canvas cv = new Canvas(small);
            // 壁纸比例和屏幕比例一般不一致：按"铺满"缩放后居中裁切
            int iw = src.getIntrinsicWidth() > 0 ? src.getIntrinsicWidth() : w;
            int ih = src.getIntrinsicHeight() > 0 ? src.getIntrinsicHeight() : h;
            float scale = Math.max(w / (float) iw, h / (float) ih);
            float dx = (w - iw * scale) / 2f;
            float dy = (h - ih * scale) / 2f;
            cv.save();
            cv.translate(dx, dy);
            cv.scale(scale, scale);
            src.setBounds(0, 0, iw, ih);
            src.draw(cv);
            cv.restore();
        } catch (Throwable t) {
            HyperHand.log("blur bg: rasterize failed: " + t);
            small.recycle();
            return null;
        }

        int[] pix = new int[w * h];
        small.getPixels(pix, 0, w, 0, 0, w, h);
        small.recycle();

        // dp → 小图像素：半径按缩放比折算，保证不同分辨率观感一致
        float radiusPx = c.blurRadiusDp * sc.density * ((float) w / sc.width);
        int r = Math.max(1, Math.round(radiusPx));
        r = Math.min(r, Math.min(w, h) / 2);
        if (r > 0) {
            boxBlur(pix, w, h, r, 3);
        }

        if (c.blurDim > 0f) {
            float k = 1f - Math.min(0.95f, c.blurDim / 100f);
            for (int i = 0; i < pix.length; i++) {
                int p = pix[i];
                int a = p >>> 24;
                int rr = (int) (((p >> 16) & 0xFF) * k);
                int gg = (int) (((p >> 8) & 0xFF) * k);
                int bb = (int) ((p & 0xFF) * k);
                pix[i] = (a << 24) | (rr << 16) | (gg << 8) | bb;
            }
        }

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pix, 0, w, 0, 0, w, h);
        HyperHand.log("blur bg built: " + w + "x" + h + " radiusPx=" + r
                + " dim=" + Math.round(c.blurDim) + "% mean=#"
                + String.format("%06X", meanColor(out)));
        return out;
    }

    /**
     * 读当前壁纸。只用公开 API：getBitmap() 是 SystemApi，编译期拿不到；
     * getDrawable() 在 SystemUI 进程里（持有 READ_WALLPAPER_INTERNAL）可用。
     * 动态壁纸拿不到位图时返回 null —— 此时保持系统原生颜色，不做猜测。
     */
    private static Drawable loadWallpaper(Context ctx) {
        try {
            WallpaperManager wpm =
                    (WallpaperManager) ctx.getSystemService(Context.WALLPAPER_SERVICE);
            if (wpm == null) return null;
            Drawable d = wpm.getDrawable();
            if (d != null) return d;
            return wpm.getBuiltInDrawable();
        } catch (Throwable t) {
            HyperHand.log("blur bg: read wallpaper failed: " + t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 模糊

    /** 三遍盒式模糊 ≈ 高斯（中心极限定理），可分离成横向 + 纵向两趟滑动窗口。 */
    private static void boxBlur(int[] pix, int w, int h, int r, int passes) {
        int[] tmp = new int[pix.length];
        for (int i = 0; i < passes; i++) {
            blurHorizontal(pix, tmp, w, h, r);
            blurVertical(tmp, pix, w, h, r);
        }
    }

    private static void blurHorizontal(int[] in, int[] out, int w, int h, int r) {
        final int span = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            final int row = y * w;
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[row + clamp(i, 0, w - 1)];
                a += p >>> 24;
                rr += (p >> 16) & 0xFF;
                gg += (p >> 8) & 0xFF;
                bb += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((a / span) << 24) | ((rr / span) << 16)
                        | ((gg / span) << 8) | (bb / span);
                int pOut = in[row + clamp(x - r, 0, w - 1)];
                int pIn = in[row + clamp(x + r + 1, 0, w - 1)];
                a += (pIn >>> 24) - (pOut >>> 24);
                rr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                gg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                bb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static void blurVertical(int[] in, int[] out, int w, int h, int r) {
        final int span = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int p = in[clamp(i, 0, h - 1) * w + x];
                a += p >>> 24;
                rr += (p >> 16) & 0xFF;
                gg += (p >> 8) & 0xFF;
                bb += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((a / span) << 24) | ((rr / span) << 16)
                        | ((gg / span) << 8) | (bb / span);
                int pOut = in[clamp(y - r, 0, h - 1) * w + x];
                int pIn = in[clamp(y + r + 1, 0, h - 1) * w + x];
                a += (pIn >>> 24) - (pOut >>> 24);
                rr += ((pIn >> 16) & 0xFF) - ((pOut >> 16) & 0xFF);
                gg += ((pIn >> 8) & 0xFF) - ((pOut >> 8) & 0xFF);
                bb += (pIn & 0xFF) - (pOut & 0xFF);
            }
        }
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    /** 采样平均色，日志里用来判断"我们画上去的到底是什么颜色"。 */
    private static int meanColor(Bitmap b) {
        try {
            int w = b.getWidth();
            int h = b.getHeight();
            long r = 0, g = 0, bl = 0;
            int n = 0;
            for (int y = 0; y < h; y += 8) {
                for (int x = 0; x < w; x += 8) {
                    int p = b.getPixel(x, y);
                    r += (p >> 16) & 0xFF;
                    g += (p >> 8) & 0xFF;
                    bl += p & 0xFF;
                    n++;
                }
            }
            if (n == 0) return 0;
            return ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (bl / n);
        } catch (Throwable t) {
            return 0;
        }
    }
}
