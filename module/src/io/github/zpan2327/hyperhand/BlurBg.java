package io.github.zpan2327.hyperhand;

import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 把单手模式上方的纯色遮罩换成"高斯模糊的当前壁纸"。
 *
 * 现场证据（HyperOS OS3.0.305 / MiuiSystemUI.apk 反编译 + SurfaceFlinger 层列表）：
 *
 *   com.android.wm.shell.onehanded.BackgroundWindowManager
 *       ├── View mBackgroundView          ← 就是那块米白色（颜色取自 getThemeColor()）
 *       ├── SurfaceControlViewHost mViewHost   （把这个 View 挂到 RootDisplayArea 上）
 *       ├── SurfaceControl mLeash
 *       ├── int  getThemeColorForBackground()
 *       └── void showBackgroundLayer() / updateThemeOnly() / boolean initView()
 *
 * 也就是说它是一块普通的 View（跑在 SurfaceControlViewHost 的 windowless root 里），
 * 不是 SurfaceControl 的颜色层，所以可以直接换 background drawable —— 缩小动画、
 * 触摸映射、退出方式全部不受影响（我们只换了"遮罩画什么"）。
 *
 * 模糊做法：先按屏幕比例居中裁剪壁纸，降到 1/6 分辨率（约 200×445），
 * 再跑三遍盒式模糊（等价高斯），最后按 {@link Cfg#blurDim} 压暗。
 * 低分辨率 + 整数运算是为了在 SystemUI 主线程/后台线程里都足够便宜：
 * 单帧量级 9 万像素 × 6 次扫描，2~4ms 就能出图，不做实时 RenderEffect。
 *
 * 线程模型：壁纸解码与模糊在 "HyperHand-blur" 线程做，结果缓存；
 * 应用 drawable 一定在主线程（SystemUI 的状态回调本来就在主线程）。
 */
final class BlurBg {

    /** BackgroundWindowManager 里装遮罩的 View 字段名。 */
    private static final String FIELD_VIEW = "mBackgroundView";
    /** 遮罩颜色（主题 surface 色，浅色下就是米白）。 */
    private static final String METHOD_THEME_COLOR = "getThemeColor";

    private static final Object LOCK = new Object();

    private static Field sViewField;
    private static boolean sViewFieldResolved;
    private static Method sThemeColor;

    /** 已生成好的模糊壁纸；key 里含壁纸 id / 屏幕尺寸 / 半径 / 压暗，任一变化都重算。 */
    private static volatile Bitmap sBitmap;
    private static volatile String sBitmapKey;
    /** 正在生成的 key（避免重复起线程）。 */
    private static volatile String sRequestedKey;
    private static volatile boolean sBuilding;

    private static volatile boolean sMissingLogged;
    private static volatile long sLastApplyLogAt;

    private BlurBg() {
    }

    // ------------------------------------------------------------------ 对外入口

    /**
     * 在 BackgroundWindowManager 的生命周期回调之后调用（showBackgroundLayer / initView /
     * updateThemeOnly）。参数就是 BackgroundWindowManager 实例。
     */
    static void apply(final Object windowManager) {
        if (windowManager == null) return;
        // ShellExecutor 有时会在别的线程回调 updateThemeOnly()，碰 View 必须回主线程
        if (Looper.myLooper() != Looper.getMainLooper()) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    apply(windowManager);
                }
            });
            return;
        }
        final Context ctx = Hooks.currentApplication();
        if (ctx == null) return;

        final Cfg c;
        try {
            c = Cfg.get(ctx);
        } catch (Throwable t) {
            return;
        }

        final View view = targetView(windowManager);
        if (view == null) {
            if (!sMissingLogged) {
                sMissingLogged = true;
                HyperHand.log("blur bg: " + FIELD_VIEW + " not found on "
                        + windowManager.getClass().getName());
            }
            return;
        }

        // 关掉开关：把遮罩还原成系统主题色
        if (!c.blurBg) {
            if (sBitmap != null || sRequestedKey != null) {
                int color = themeColor(windowManager);
                view.setBackground(new ColorDrawable(color));
                sRequestedKey = null;
                sBitmap = null;
                sBitmapKey = null;
                HyperHand.log("blur bg disabled -> restore theme color #"
                        + String.format("%08X", color));
            }
            return;
        }

        final String key = keyOf(ctx, c);
        final Bitmap cached = sBitmap;
        if (cached != null && key.equals(sBitmapKey) && !cached.isRecycled()) {
            try {
                view.setBackground(new BitmapDrawable(ctx.getResources(), cached));
            } catch (Throwable t) {
                HyperHand.log("blur bg: setBackground failed: " + t);
                return;
            }
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sLastApplyLogAt > 3000) {
                sLastApplyLogAt = now;
                HyperHand.log("blur bg applied: " + key + " (" + cached.getWidth() + "x"
                        + cached.getHeight() + ")");
            }
            return;
        }

        // 还没算好：先保留系统原色，后台算完再补一次
        if (!key.equals(sRequestedKey) || !sBuilding) {
            sRequestedKey = key;
            buildAsync(ctx, c, key, windowManager);
        }
    }

    // ------------------------------------------------------------------ 视图 / 颜色

    private static View targetView(Object wm) {
        try {
            Field f = sViewField;
            if (f == null) {
                synchronized (LOCK) {
                    if (!sViewFieldResolved) {
                        sViewFieldResolved = true;
                        try {
                            Field t = wm.getClass().getDeclaredField(FIELD_VIEW);
                            t.setAccessible(true);
                            sViewField = t;
                        } catch (Throwable e) {
                            HyperHand.log("blur bg: reflect " + FIELD_VIEW + " failed: " + e);
                        }
                    }
                    f = sViewField;
                }
            }
            if (f == null) return null;
            Object v = f.get(wm);
            return v instanceof View ? (View) v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static int themeColor(Object wm) {
        try {
            Method m = sThemeColor;
            if (m == null) {
                synchronized (LOCK) {
                    if (sThemeColor == null) {
                        Method t = wm.getClass().getDeclaredMethod(METHOD_THEME_COLOR);
                        t.setAccessible(true);
                        sThemeColor = t;
                    }
                    m = sThemeColor;
                }
            }
            Object v = m.invoke(wm);
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {
            // 取不到就退回一个中性的浅色，至少不会变成纯黑
        }
        return 0xFFF5F5F5;
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

    private static void buildAsync(final Context ctx, final Cfg cfg, final String key,
                                   final Object windowManager) {
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
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        // 缓存就绪，补一次应用（此时 key 已匹配）
                        apply(windowManager);
                    }
                });
            }
        }, "HyperHand-blur");
        th.setPriority(Thread.MIN_PRIORITY);
        th.start();
    }

    /** 取壁纸 → 按屏幕比例居中铺满小图 → 三遍盒式模糊 → 压暗。 */
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
        return out;
    }

    /**
     * 读当前壁纸。
     *
     * 只用公开 API：WallpaperManager#getBitmap() 是 SystemApi，编译期拿不到；
     * getDrawable() 在 SystemUI 进程里（持有 READ_WALLPAPER_INTERNAL）可用。
     * 动态壁纸拿不到位图时返回 null —— 此时保持系统原生米白色，不做降级猜测。
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
}
