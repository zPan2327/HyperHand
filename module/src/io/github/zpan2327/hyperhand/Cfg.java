package io.github.zpan2327.hyperhand;

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
 *
 * 2.2 起引入"识别模式"（{@link #K_MODE}）：模式只决定一组**默认敏感度**，
 * 任何被显式写入的键都会覆盖对应预设值，因此 1.x/2.0/2.1 用户升级后原有配置继续生效。
 *
 * 单位约定（内部一律用 dp，避免不同屏幕密度下阈值含义漂移）：
 *   - 距离：dp
 *   - 速度：dp/s（旧的 {@link #K_MIN_VELOCITY} 是 px/s，读取时按 density 折算成 dp/s）
 *   - 时间：ms
 */
final class Cfg {

    // ---------------------------------------------------------------- 旧键（保持原名，兼容 1.x / 2.0 / 2.1）
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

    // ---------------------------------------------------------------- 新键（2.2）
    static final String K_MODE              = "oh_tuner_recognition_mode";   // 识别模式 0自然/1灵敏/2防误触
    static final String K_MIN_VEL_DP        = "oh_tuner_min_velocity_dp_s";  // 峰值速度门限(dp/s)，路径A
    static final String K_SLOW_ENABLED      = "oh_tuner_slow_enabled";       // 启用慢滑路径（路径B）
    static final String K_SLOW_DY_DP        = "oh_tuner_slow_dy_dp";         // 慢滑所需距离(dp)
    static final String K_MIN_AVG_VEL       = "oh_tuner_min_avg_velocity";   // 平均速度门限(dp/s)，路径B
    static final String K_MAX_VELOCITY      = "oh_tuner_max_velocity";       // 速度上限(dp/s)，超过视为噪声
    static final String K_VEL_WINDOW_MS     = "oh_tuner_velocity_window_ms"; // 速度估计窗口(ms)
    static final String K_MIN_MONOTONIC     = "oh_tuner_min_monotonic";      // 轨迹单调性下限 0~1
    static final String K_MIN_CONFIDENCE    = "oh_tuner_min_confidence";     // 置信度门限 0~1
    static final String K_ADAPTIVE          = "oh_tuner_enable_adaptive_threshold"; // 自适应阈值
    static final String K_ZONE_BIAS         = "oh_tuner_zone_bias";          // 触发区水平窗口中心偏移 -0.5~0.5
    static final String K_LAST_GESTURE      = "oh_tuner_last_gesture";       // 调试：最近手势记录（SystemUI 写）

    static final String[] ALL_KEYS = new String[] {
            K_ENABLED, K_MONITOR_ENABLED, K_ZONE_DP, K_MIN_DY_DP, K_MAX_SLOPE,
            K_COOLDOWN_MS, K_WIDTH_RATIO, K_MAX_DURATION_MS, K_MIN_VELOCITY, K_SHADE_GUARD, K_CLAIM_ZONE,
            K_LAUNCHER_HOT_DP, K_DEBUG,
            K_MODE, K_MIN_VEL_DP, K_SLOW_ENABLED, K_SLOW_DY_DP, K_MIN_AVG_VEL, K_MAX_VELOCITY,
            K_VEL_WINDOW_MS, K_MIN_MONOTONIC, K_MIN_CONFIDENCE, K_ADAPTIVE, K_ZONE_BIAS
    };

    /** 设置页提交时一并带上的键（不含只读的 K_LAST_GESTURE）。 */
    static final String[] EDITABLE_KEYS = ALL_KEYS;

    // ---------------------------------------------------------------- 默认值（旧键保持 2.1 原值）
    static final float DEF_ZONE_DP = 56f;
    static final float DEF_MIN_DY_DP = 12f;      // 2.2：随模式变化，此处只是兜底
    static final float DEF_MAX_SLOPE = 1.2f;
    static final int DEF_COOLDOWN_MS = 500;
    static final float DEF_WIDTH_RATIO = 1.0f;
    static final int DEF_MAX_DURATION_MS = 0;
    static final int DEF_MIN_VELOCITY = 500;     // px/s（旧键原义）
    static final float DEF_LAUNCHER_HOT_DP = 0f;
    static final int DEF_MAX_VELOCITY = 4000;    // dp/s
    static final int DEF_VEL_WINDOW_MS = 60;
    static final float DEF_MIN_MONOTONIC = 0.78f;
    static final float DEF_ZONE_BIAS = 0f;

    static final int MODE_NATURAL = 0;
    static final int MODE_SENSITIVE = 1;
    static final int MODE_STABLE = 2;

    static final String[] MODE_NAMES = new String[] {"自然（iPhone 式）", "灵敏", "防误触"};
    static final String[] MODE_SHORT = new String[] {"自然", "灵敏", "防误触"};
    static final String[] MODE_SUMMARIES = new String[] {
            "自然连续下滑即可触发，兼顾速度与距离",
            "降低门槛，短距离轻扫也能触发",
            "提高门槛，优先保证页面滚动与系统手势",
    };

    /** 模式的默认敏感度。显式写入的键会覆盖这里的值。 */
    static final class Preset {
        final float minDyDp;     // 路径A 最小距离
        final float velLowDp;    // 路径A 峰值速度下限
        final float slowDyDp;    // 路径B 最小距离
        final float slowLowDp;   // 路径B 平均速度下限
        final float confidence;  // 置信度门限

        Preset(float minDyDp, float velLowDp, float slowDyDp, float slowLowDp, float confidence) {
            this.minDyDp = minDyDp;
            this.velLowDp = velLowDp;
            this.slowDyDp = slowDyDp;
            this.slowLowDp = slowLowDp;
            this.confidence = confidence;
        }
    }

    static Preset preset(int mode) {
        switch (mode) {
            case MODE_SENSITIVE:
                return new Preset(8f, 40f, 20f, 25f, 0.42f);
            case MODE_STABLE:
                return new Preset(18f, 120f, 44f, 70f, 0.60f);
            case MODE_NATURAL:
            default:
                return new Preset(12f, 60f, 28f, 35f, 0.50f);
        }
    }

    // ---------------------------------------------------------------- 生效值
    boolean enabled = true;
    boolean monitorEnabled = true;
    boolean debug = true;
    boolean shadeGuard = true;
    boolean claimZone = true;
    boolean slowEnabled = true;
    boolean adaptive = true;

    int mode = MODE_NATURAL;
    float zoneDp = DEF_ZONE_DP;
    float zoneBias = DEF_ZONE_BIAS;
    float widthRatio = DEF_WIDTH_RATIO;
    float minDyDp = DEF_MIN_DY_DP;
    float maxSlope = DEF_MAX_SLOPE;
    float minVelDp = 70f;
    float slowDyDp = 28f;
    float minAvgVelDp = 40f;
    float minConfidence = 0.50f;
    float minMonotonic = DEF_MIN_MONOTONIC;
    float maxVelocityDp = DEF_MAX_VELOCITY;
    int velWindowMs = DEF_VEL_WINDOW_MS;
    int cooldownMs = DEF_COOLDOWN_MS;
    int maxDurationMs = DEF_MAX_DURATION_MS;
    float launcherHotDp = DEF_LAUNCHER_HOT_DP;

    private static Cfg sCached;
    private static long sCachedAt;
    private static volatile boolean sMigrated;

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
            c.enabled = boolOf(cr, K_ENABLED, true);
            c.monitorEnabled = boolOf(cr, K_MONITOR_ENABLED, true);
            c.debug = boolOf(cr, K_DEBUG, true);
            c.shadeGuard = boolOf(cr, K_SHADE_GUARD, true);
            c.claimZone = boolOf(cr, K_CLAIM_ZONE, true);
            c.slowEnabled = boolOf(cr, K_SLOW_ENABLED, true);
            c.adaptive = boolOf(cr, K_ADAPTIVE, true);

            c.mode = (int) clamp(intOf(cr, K_MODE, MODE_NATURAL), MODE_NATURAL, MODE_STABLE);
            Preset p = preset(c.mode);

            c.zoneDp = clamp(floatOf(cr, K_ZONE_DP, DEF_ZONE_DP), 20f, 200f);
            c.zoneBias = clamp(floatOf(cr, K_ZONE_BIAS, DEF_ZONE_BIAS), -0.5f, 0.5f);
            c.widthRatio = clamp(floatOf(cr, K_WIDTH_RATIO, DEF_WIDTH_RATIO), 0.2f, 1f);
            c.maxSlope = clamp(floatOf(cr, K_MAX_SLOPE, DEF_MAX_SLOPE), 0.2f, 5f);
            c.cooldownMs = Math.max(0, intOf(cr, K_COOLDOWN_MS, DEF_COOLDOWN_MS));
            c.maxDurationMs = Math.max(0, intOf(cr, K_MAX_DURATION_MS, DEF_MAX_DURATION_MS));
            c.launcherHotDp = clamp(floatOf(cr, K_LAUNCHER_HOT_DP, DEF_LAUNCHER_HOT_DP), 0f, 200f);
            c.minMonotonic = clamp(floatOf(cr, K_MIN_MONOTONIC, DEF_MIN_MONOTONIC), 0.2f, 1f);
            c.maxVelocityDp = clamp(floatOf(cr, K_MAX_VELOCITY, DEF_MAX_VELOCITY), 300f, 20000f);
            c.velWindowMs = (int) clamp(intOf(cr, K_VEL_WINDOW_MS, DEF_VEL_WINDOW_MS), 20, 200);

            // 距离：旧键 oh_tuner_min_dy_dp 与 2.2 的路径A 是同一个语义，显式写过就用它
            c.minDyDp = clamp(floatOf(cr, K_MIN_DY_DP, p.minDyDp), 1f, 120f);

            // 速度：优先 2.2 的 dp/s 键；否则回落到旧 px/s 键（按密度折算）；都没有才用模式预设
            float density = Screen.get(ctx).density;
            if (has(cr, K_MIN_VEL_DP)) {
                c.minVelDp = clamp(floatOf(cr, K_MIN_VEL_DP, p.velLowDp), 0f, 5000f);
            } else if (has(cr, K_MIN_VELOCITY)) {
                c.minVelDp = clamp(intOf(cr, K_MIN_VELOCITY, 0) / density, 0f, 5000f);
            } else {
                c.minVelDp = p.velLowDp;
            }

            c.slowDyDp = clamp(floatOf(cr, K_SLOW_DY_DP, p.slowDyDp), 1f, 200f);
            c.minAvgVelDp = clamp(floatOf(cr, K_MIN_AVG_VEL, p.slowLowDp), 0f, 5000f);
            c.minConfidence = clamp(floatOf(cr, K_MIN_CONFIDENCE, p.confidence), 0.05f, 1f);
        } catch (Throwable t) {
            Log.w(HyperHand.TAG, "load config failed, use defaults", t);
        }
        return c;
    }

    /**
     * 旧配置迁移（只跑一次，且只在有写权限的 SystemUI 进程里执行）：
     * 把旧键 oh_tuner_min_velocity_px_s 折算成 dp/s 写入新键，
     * 之后设置页改的是新键，不会和旧键打架。
     */
    static void migrateIfNeeded(Context ctx) {
        if (sMigrated) return;
        sMigrated = true;
        try {
            ContentResolver cr = ctx.getContentResolver();
            if (has(cr, K_MIN_VEL_DP) || !has(cr, K_MIN_VELOCITY)) return;
            float density = Screen.get(ctx).density;
            int px = intOf(cr, K_MIN_VELOCITY, 0);
            int dp = Math.round(px / density);
            Settings.Global.putString(cr, K_MIN_VEL_DP, String.valueOf(dp));
            HyperHand.log("migrated " + K_MIN_VELOCITY + "=" + px + "px/s -> " + K_MIN_VEL_DP + "=" + dp + "dp/s");
        } catch (Throwable t) {
            HyperHand.log("migrate config failed: " + t);
        }
    }

    static boolean boolOf(ContentResolver cr, String key, boolean def) {
        try {
            String s = Settings.Global.getString(cr, key);
            if (s == null || s.length() == 0) return def;
            s = s.trim();
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
            return (int) Float.parseFloat(s) != 0;
        } catch (Throwable t) {
            return def;
        }
    }

    static boolean has(ContentResolver cr, String key) {
        try {
            String s = Settings.Global.getString(cr, key);
            return s != null && s.trim().length() > 0;
        } catch (Throwable t) {
            return false;
        }
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
            float v = Float.parseFloat(s.trim());
            if (Float.isNaN(v) || Float.isInfinite(v)) return def;
            return v;
        } catch (Throwable t) {
            return def;
        }
    }

    static float clamp(float v, float min, float max) {
        if (Float.isNaN(v)) return min;
        return v < min ? min : (v > max ? max : v);
    }

    @Override
    public String toString() {
        return "Cfg{mode=" + mode + ", enabled=" + enabled + ", monitor=" + monitorEnabled
                + ", zoneDp=" + zoneDp + ", bias=" + zoneBias + ", width=" + widthRatio
                + ", minDy=" + minDyDp + ", minVel=" + minVelDp + ", slow=" + slowEnabled
                + "/" + slowDyDp + "/" + minAvgVelDp + ", conf=" + minConfidence
                + ", slope=" + maxSlope + ", cooldown=" + cooldownMs
                + ", launcherHotDp=" + launcherHotDp + ", shadeGuard=" + shadeGuard
                + ", claimZone=" + claimZone + ", debug=" + debug + "}";
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

        /** 横屏时原生 startOneHanded() 本身会被系统拒绝，这里直接不做判定。 */
        boolean isLandscape() {
            return width > height;
        }

        /** 触发区顶部（屏幕坐标）。 */
        float zoneTop(Cfg c) {
            return height - c.zoneDp * density;
        }

        /**
         * 落点是否在触发区内。
         * 水平方向：以屏幕中线加 bias 为窗口中心，宽度 = width * ratio。
         * 两个进程（SystemUI 判定 / 桌面区域归属）共用同一套几何，避免行为不一致。
         */
        boolean inTriggerZone(Cfg c, float x, float y) {
            if (y < zoneTop(c)) return false;
            float half = width * c.widthRatio / 2f;
            float cx = width / 2f + c.zoneBias * width;
            return x >= cx - half && x <= cx + half;
        }
    }
}
