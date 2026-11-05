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
 *       frame = 屏幕上半部分（1200×1068 / 2670），不可触摸
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
    private static volatile Object sLastWm;

    private static volatile boolean sMissingLogged;
    private static volatile long sLastApplyLogAt;
    private static volatile long sLastOverlayLogAt;
    /** 每次入场递增，旧的延迟任务据此自动作废。 */
    private static volatile int sOverlayToken;

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
                        + windowManager.getClass().getName());
            }
            return;
        }
        onViewThread(view, new Runnable() {
            @Override
            public void run() {
                applyOnViewInternal(view, "background-panel");
            }
        });
    }

    /**
     * 任意"单手模式遮罩窗口"的 View（按 window title 命中后调用）。
     * 目前命中 one-handed-tutorial-overlay。
     */
    static void applyToOverlay(final View view, final String tag) {
        if (view == null) return;
        sLastOverlay = view;
        sLastOverlayTag = tag;
        final int token = ++sOverlayToken;
        // 只补几次、而且是稀的：入场动画期间系统会重设颜色，
        // 密集重贴会和系统重绘打架，表现为闪烁。动画结束后贴一次即可稳定。
        scheduleOverlay(view, tag, token, 0);
    }

    private static final int[] OVERLAY_DELAYS = new int[] {260, 620, 1100, 1800};

    private static void scheduleOverlay(final View view, final String tag, final int token,
                                        final int idx) {
        if (idx >= OVERLAY_DELAYS.length) return;
        onViewThread(view, new Runnable() {
            @Override
            public void run() {
                view.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (token != sOverlayToken) return;      // 已被新的一次入场取代
                        if (!view.isAttachedToWindow()) return;  // 已经退出单手模式
                        applyOnViewInternal(view, tag);
                        // 不做"已是我们就不补"的提前退出：系统可能在入场末尾才把
                        // 颜色刷回去，留几次稀疏补丁兜底（每次先判重，不会重复贴）。
                        scheduleOverlay(view, tag, token, idx + 1);
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
                applyOnViewInternal(v, tag);
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


    private static boolean applyOnViewInternal(final View view, final String tag) {
        final Context ctx = Hooks.currentApplication();
        if (ctx == null) return true;

        final Cfg c;
        try {
            c = Cfg.get(ctx);
        } catch (Throwable t) {
            return true;
        }

        // 关掉开关：遮罩还原成系统主题色（背景层才有主题色，overlay 交给系统自己重画）
        if (!c.blurBg) {
            if (sBitmap != null || sRequestedKey != null) {
                sRequestedKey = null;
                sBitmap = null;
                sBitmapKey = null;
                HyperHand.log("blur bg disabled");
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
            view.setBackground(makeDrawable(ctx, bmp, view));
        } catch (Throwable t) {
            HyperHand.log("blur bg: setBackground failed on " + tag + ": " + t);
            return true;
        }
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastOverlayLogAt > 2000) {
            sLastOverlayLogAt = now;
            HyperHand.log("blur bg applied: " + tag + " view=" + view.getWidth() + "x"
                    + view.getHeight() + " vis=" + view.getVisibility()
                    + " bgBefore=" + before + " key=" + key
                    + " bmp=" + bmp.getWidth() + "x" + bmp.getHeight()
                    + " mean=#" + String.format("%06X", meanColor(bmp)));
        }
        return true;
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
                ov.setBackground(makeDrawable(ctx, bmp, ov));
                ov.invalidate();
                HyperHand.log("force: set blur on overlay " + ov.getWidth() + "x"
                        + ov.getHeight() + " -> bg=" + describeBackground(ov.getBackground())
                        + " alpha=" + ov.getAlpha() + " shown=" + ov.isShown());
            }
        });
    }

    /** 诊断探针：把两个遮罩分别涂成红/绿，用来确认屏幕上那块到底是谁画的。 */
    static void probe() {
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

    /**
     * 按目标 View 的尺寸裁出"顶端对齐的那一块"再铺满。
     *
     * 原因：遮罩窗口只有上半屏高（1200×1068），而模糊位图是整屏比例（200×445）。
     * 直接 FILL 会被纵向压扁，用 gravity 缩放又会在某些 window 上留黑边，
     * 所以这里显式裁一段同比例的区域，交给默认的 FILL 拉伸，结果最稳。
     */
    private static Drawable makeDrawable(Context ctx, Bitmap full, View v) {
        int vw = v.getWidth();
        int vh = v.getHeight();
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
                    applyToOverlay(ov, sLastOverlayTag);
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
