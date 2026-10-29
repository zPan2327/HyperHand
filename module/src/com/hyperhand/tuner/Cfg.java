package com.hyperhand.tuner;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

/**
 * 配置读取。所有配置存放在 Settings.Global（任何进程都能读，SystemUI/Launcher 都有写权限）。
 * 键名统一加 oh_tuner_ 前缀，避免与系统/其它模块冲突。
 */
final class Cfg {

    static final String K_ENABLED           = "oh_tuner_enabled";            // 总开关
    static final String K_MONITOR_ENABLED   = "oh_tuner_monitor_enabled";    // 扩展触发（SystemUI 手势监视器）
    static final String K_ZONE_DP           = "oh_tuner_zone_dp";            // 底部触发区高度(dp)
    static final String K_MIN_DY_DP         = "oh_tuner_min_dy_dp";          // 最小下滑距离(dp)
    static final String K_MAX_SLOPE         = "oh_tuner_max_slope";          // 最大横向偏移比 |dx|/dy
    static final String K_COOLDOWN_MS       = "oh_tuner_cooldown_ms";        // 触发冷却(ms)
    static final String K_WIDTH_RATIO       = "oh_tuner_width_ratio";        // 有效宽度占比 0~1
    static final String K_MAX_DURATION_MS   = "oh_tuner_max_duration_ms";    // 最长手势时长(ms)，0=不限
    static final String K_MIN_VELOCITY      = "oh_tuner_min_velocity_px_s";  // 最小下滑速度(px/s)，0=不限
    static final String K_LAUNCHER_HOT_DP   = "oh_tuner_launcher_hot_dp";    // 原生胶囊条高度覆盖(dp)，0=不改
    static final String K_SHADE_GUARD       = "oh_tuner_shade_guard";        // 与通知/控制中心下拉互斥
    static final String K_CLAIM_ZONE        = "oh_tuner_claim_zone";         // 底部区域优先单手（桌面下拉让位）
    static final String K_DEBUG             = "oh_tuner_debug";              // 调试日志

    static final String[] ALL_KEYS = new String[] {
            K_ENABLED, K_MONITOR_ENABLED, K_ZONE_DP, K_MIN_DY_DP, K_MAX_SLOPE,
            K_COOLDOWN_MS, K_WIDTH_RATIO, K_MAX_DURATION_MS, K_MIN_VELOCITY, K_SHADE_GUARD, K_CLAIM_ZONE,
            K_LAUNCHER_HOT_DP, K_DEBUG
    };

    // 默认值
    static final float DEF_ZONE_DP = 56f;
    static final float DEF_MIN_DY_DP = 4f;
    static final float DEF_MAX_SLOPE = 1.2f;
    static final int DEF_COOLDOWN_MS = 500;
    static final float DEF_WIDTH_RATIO = 1.0f;
    static final int DEF_MAX_DURATION_MS = 0;
    static final int DEF_MIN_VELOCITY = 500;
    static final float DEF_LAUNCHER_HOT_DP = 0f;

    boolean enabled = true;
    boolean monitorEnabled = true;
    float zoneDp = DEF_ZONE_DP;
    float minDyDp = DEF_MIN_DY_DP;
    float maxSlope = DEF_MAX_SLOPE;
    int cooldownMs = DEF_COOLDOWN_MS;
    float widthRatio = DEF_WIDTH_RATIO;
    int maxDurationMs = DEF_MAX_DURATION_MS;
    int minVelocity = DEF_MIN_VELOCITY;
    float launcherHotDp = DEF_LAUNCHER_HOT_DP;
    boolean debug = true;
    boolean shadeGuard = true;
    boolean claimZone = true;

    private static Cfg sCached;
    private static long sCachedAt;

    static synchronized Cfg get(Context ctx) {
        long now = SystemClock.uptimeMillis();
        if (sCached != null && now - sCachedAt < 800) {
            return sCached;
        }
        Cfg c = load(ctx);
        sCached = c;
        sCachedAt = now;
        return c;
    }

    static void invalidate() {
        sCached = null;
    }

    private static Cfg load(Context ctx) {
        Cfg c = new Cfg();
        try {
            ContentResolver cr = ctx.getContentResolver();
            c.enabled = intOf(cr, K_ENABLED, 1) != 0;
            c.monitorEnabled = intOf(cr, K_MONITOR_ENABLED, 1) != 0;
            c.debug = intOf(cr, K_DEBUG, 1) != 0;
            c.zoneDp = clamp(floatOf(cr, K_ZONE_DP, DEF_ZONE_DP), 20f, 200f);
            c.minDyDp = clamp(floatOf(cr, K_MIN_DY_DP, DEF_MIN_DY_DP), 1f, 200f);
            c.maxSlope = clamp(floatOf(cr, K_MAX_SLOPE, DEF_MAX_SLOPE), 0.2f, 5f);
            c.cooldownMs = Math.max(0, intOf(cr, K_COOLDOWN_MS, DEF_COOLDOWN_MS));
            c.widthRatio = clamp(floatOf(cr, K_WIDTH_RATIO, DEF_WIDTH_RATIO), 0.2f, 1f);
            c.maxDurationMs = Math.max(0, intOf(cr, K_MAX_DURATION_MS, DEF_MAX_DURATION_MS));
            c.minVelocity = Math.max(0, intOf(cr, K_MIN_VELOCITY, DEF_MIN_VELOCITY));
            c.launcherHotDp = clamp(floatOf(cr, K_LAUNCHER_HOT_DP, DEF_LAUNCHER_HOT_DP), 0f, 200f);
            c.shadeGuard = intOf(cr, K_SHADE_GUARD, 1) != 0;
            c.claimZone = intOf(cr, K_CLAIM_ZONE, 1) != 0;
        } catch (Throwable t) {
            Log.w(HyperHand.TAG, "load config failed, use defaults", t);
        }
        return c;
    }

    static int intOf(ContentResolver cr, String key, int def) {
        try {
            String s = Settings.Global.getString(cr, key);
            if (s == null || s.length() == 0) return def;
            return (int) Float.parseFloat(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    static float floatOf(ContentResolver cr, String key, float def) {
        try {
            String s = Settings.Global.getString(cr, key);
            if (s == null || s.length() == 0) return def;
            return Float.parseFloat(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    @Override
    public String toString() {
        return "Cfg{enabled=" + enabled + ", monitor=" + monitorEnabled
                + ", zoneDp=" + zoneDp + ", minDyDp=" + minDyDp + ", maxSlope=" + maxSlope
                + ", cooldownMs=" + cooldownMs + ", widthRatio=" + widthRatio
                + ", maxDurationMs=" + maxDurationMs + ", minVelocity=" + minVelocity
                + ", launcherHotDp=" + launcherHotDp + ", shadeGuard=" + shadeGuard + ", claimZone=" + claimZone + ", debug=" + debug + "}";
    }

    /** 屏幕尺寸/密度缓存（每 3 秒刷新一次，兼容折叠屏/旋转/分屏变化）。 */
    static final class Screen {
        int width;
        int height;
        float density;
        long at;

        static Screen sInst;

        static synchronized Screen get(Context ctx) {
            long now = SystemClock.uptimeMillis();
            Screen s = sInst;
            if (s != null && now - s.at < 3000) {
                return s;
            }
            Screen n = new Screen();
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getRealMetrics(dm);
                n.width = dm.widthPixels;
                n.height = dm.heightPixels;
                n.density = dm.density;
            } catch (Throwable t) {
                DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                n.width = dm.widthPixels;
                n.height = dm.heightPixels;
                n.density = dm.density;
            }
            if (n.density <= 0f) n.density = 3f;
            n.at = now;
            sInst = n;
            return n;
        }
    }
}
