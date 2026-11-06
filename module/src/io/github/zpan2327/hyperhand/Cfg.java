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

    // ---------------------------------------------------------------- 新键（2.8 落点判定）
    /** 贴底直达：抬手点必须落在屏幕最底部这条带子里才算单手模式。 */
    static final String K_END_BOOST         = "oh_tuner_end_boost";
    /** 贴底判定的带宽（dp）：抬手点距屏幕底边不超过这个距离就算"到底了"。 */
    static final String K_END_ZONE_DP       = "oh_tuner_end_zone_dp";
    /** 触发区改按屏幕高度比例算（覆盖到 dock 栏上方），1 = 开。 */
    static final String K_ZONE_RATIO_MODE   = "oh_tuner_zone_ratio_mode";
    /** 比例模式的屏幕占比，0.15 约等于 dock 栏上沿。 */
    static final String K_ZONE_RATIO        = "oh_tuner_zone_ratio";

    // ---------------------------------------------------------------- 新键（2.3 外观）
    static final String K_BLUR_BG           = "oh_tuner_blur_bg";            // 遮罩用模糊壁纸
    static final String K_BLUR_RADIUS       = "oh_tuner_blur_radius";        // 模糊半径(dp)
    static final String K_BLUR_DIM          = "oh_tuner_blur_dim";           // 模糊壁纸压暗程度(%)

    // ---------------------------------------------------------------- 新键（2.7 动画）
    /** 缩小/恢复动画的时长倍率（1.0 = 系统原生的 600ms）。 */
    static final String K_ANIM_SCALE        = "oh_tuner_anim_scale";
    /** 动画曲线：0 原生 / 1 柔和 / 2 平缓。 */
    static final String K_ANIM_CURVE        = "oh_tuner_anim_curve";
    /** 只读：SystemUI 把"系统原始时长 + 当前生效值"写这里，供设置页显示。 */
    static final String K_ANIM_INFO         = "oh_tuner_anim_info";

    // ---------------------------------------------------------------- 新键（2.9 模糊区下拉）
    /** 单手模式下，在上半屏模糊区域下滑 = 展开通知中心/控制中心，并立即退出单手模式。 */
    static final String K_TOP_SHADE         = "oh_tuner_top_shade";
    /** 触发所需的下滑距离（dp）。模糊区本身很矮，给一个"确实在往下拖"的下限即可。 */
    static final String K_TOP_SHADE_DY_DP   = "oh_tuner_top_shade_dy_dp";
    /** 展开哪一个：0 左通知右控制 / 1 总是通知中心 / 2 总是控制中心。 */
    static final String K_TOP_SHADE_SIDE    = "oh_tuner_top_shade_side";

    /** 只读：最近一次屏幕底部附近的起手高度，供设置页校准触发区高度。 */
    static final String K_LAST_DOWN         = "oh_tuner_last_down";

    /** 调试动作（不落盘，只当广播参数）：trigger / stop。 */
    static final String K_ACTION            = "oh_tuner_action";

    static final String[] ALL_KEYS = new String[] {
            K_ENABLED, K_MONITOR_ENABLED, K_ZONE_DP, K_MIN_DY_DP, K_MAX_SLOPE,
            K_COOLDOWN_MS, K_WIDTH_RATIO, K_MAX_DURATION_MS, K_MIN_VELOCITY, K_SHADE_GUARD, K_CLAIM_ZONE,
            K_LAUNCHER_HOT_DP, K_DEBUG,
            K_MODE, K_MIN_VEL_DP, K_SLOW_ENABLED, K_SLOW_DY_DP, K_MIN_AVG_VEL, K_MAX_VELOCITY,
            K_VEL_WINDOW_MS, K_MIN_MONOTONIC, K_MIN_CONFIDENCE, K_ADAPTIVE, K_ZONE_BIAS,
            K_BLUR_BG, K_BLUR_RADIUS, K_BLUR_DIM,
            K_ANIM_SCALE, K_ANIM_CURVE,
            K_END_BOOST, K_END_ZONE_DP, K_ZONE_RATIO_MODE, K_ZONE_RATIO,
            K_TOP_SHADE, K_TOP_SHADE_DY_DP, K_TOP_SHADE_SIDE
    };

    /** 设置页提交时一并带上的键（不含只读的 K_LAST_GESTURE）。 */
    static final String[] EDITABLE_KEYS = ALL_KEYS;

    // ---------------------------------------------------------------- 默认值（旧键保持 2.1 原值）
    /**
     * 底部触发区高度。
     *
     * 这个值同时决定两件事：单手模式从哪里起手算数，以及桌面在这段区域里
     * 下拉**不会**打开通知/控制中心。所以它必须覆盖用户实际的落点，
     * 否则就会出现"我明明从底部拖的，却弹了通知中心"——实机日志里
     * 这类落点集中在距底 59~78dp，58dp 的边界恰好切在中间，差 1dp 就整段失效。
     * 72dp 能覆盖这段分布，同时仍远低于屏幕中部的手势。
     */
    static final float DEF_ZONE_DP = 72f;

    /**
     * 比例模式下的屏幕占比。dock 栏上沿大约在屏幕底部 15% 处，
     * 用比例而不是固定 dp，换机型（尤其是 21:9 / 折叠屏）时边界不会跑偏。
     */
    static final float DEF_ZONE_RATIO = 0.15f;
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

    /** 2.3 外观：默认开模糊壁纸；半径 24dp、压暗 12%（保留层次又不至于发灰）。 */
    static final float DEF_BLUR_RADIUS = 24f;
    static final float DEF_BLUR_DIM = 12f;

    // ---------------------------------------------------------------- 2.7 动画
    /** HyperOS 上 config_one_handed_translate_animation_duration 的默认值，仅作说明用。 */
    static final int SYSTEM_ANIM_MS = 600;

    /**
     * 曲线按"前段推进速度"从慢到快排列，方便顺着试：
     * 舒缓（起手慢）→ 柔和 → 标准 → 迅捷 → 原生（起手最冲）。
     *
     * 括号里是 t=0.1 / t=0.3 时的位移完成度，这是判断"傻快"还是"线性"的直接依据：
     * 前段太快 = 傻快，中段接近直线 = 线性。
     */
    static final int ANIM_CURVE_NATIVE = 0;
    static final int ANIM_CURVE_SOFT = 1;
    static final int ANIM_CURVE_SMOOTH = 2;
    static final int ANIM_CURVE_STANDARD = 3;
    static final int ANIM_CURVE_SNAPPY = 4;

    static final String[] ANIM_CURVE_NAMES = new String[] {
            "原生（系统曲线）", "舒缓（smoothstep）", "柔和（ease-out cubic）",
            "标准（ease-out quart）", "迅捷（ease-out quint）"
    };
    static final String[] ANIM_CURVE_SHORT = new String[] {"原生", "舒缓", "柔和", "标准", "迅捷"};
    static final String[] ANIM_CURVE_SUMMARIES = new String[] {
            "系统自带：10% 时间走完 50% 位移，观感最冲",
            "起手与收尾都减速：10% 只走 3%，位移最均匀，但起手像\"顿一下\"",
            "10% 走 27%、30% 走 66%：中段接近直线，容易觉得机械",
            "10% 走 34%、30% 走 76%：起步果断、尾部从容，推荐",
            "10% 走 41%、30% 走 83%：最接近原生节奏，只是没那么极端",
    };
    /** 原生曲线的一句话描述，设置页直接显示。 */
    static final String ANIM_CURVE_RAW_DESC = "曲线 ease-out expo（10% 时间 → 50% 位移）";

    /** 默认：标准的 ease-out quart + 稍长一点的时长，既有减速感又不至于冲。 */
    static final float DEF_ANIM_SCALE = 1.10f;
    static final int DEF_ANIM_CURVE = ANIM_CURVE_STANDARD;

    // ---------------------------------------------------------------- 2.8 落点判定
    /**
     * 贴底直达的默认带宽。
     * 手指滑出屏幕时坐标会被裁到屏幕底边，所以"真的拖到底"时这个值接近 0。
     * 实机日志里用户觉得自己已经拖到底的那次是 28.4dp，24dp 恰好差一点没进 ——
     * 32dp 给这段主观误差留出余量，又不至于把"停在底部区域"的滚动动作也吞进来。
     */
    static final float DEF_END_ZONE_DP = 32f;

    // ---------------------------------------------------------------- 2.9 模糊区下拉
    /**
     * 模糊区高度只有屏幕的 40% 左右，不可能像从顶部下拉那样需要很长距离；
     * 36dp 是"手指确实在往下拖"与"手滑了一下"的分界。
     */
    static final float DEF_TOP_SHADE_DY_DP = 36f;

    static final int TOP_SIDE_SPLIT = 0;
    static final int TOP_SIDE_NOTIFY = 1;
    static final int TOP_SIDE_CONTROL = 2;
    static final String[] TOP_SIDE_NAMES = new String[] {
            "左侧通知 / 右侧控制", "总是通知中心", "总是控制中心"
    };
    static final String[] TOP_SIDE_SHORT = new String[] {"左通知右控制", "通知中心", "控制中心"};
    static final int DEF_TOP_SHADE_SIDE = TOP_SIDE_SPLIT;

    /**
     * 退出单手模式后等一小会儿再展开面板。
     * 这两个动作动的是同一批"被整体下移"的窗口，同时做的话面板会跟着上移，
     * 看起来像"拉下来又跳回去"；120ms 足够让窗口先归位。
     */
    static final int DEF_TOP_SHADE_DELAY_MS = 120;

    /**
     * 贴底路径要求的最小向下位移。
     * 起点已经在底部区域里，只需要一点点位移就能到底，所以这里比常规路径低得多；
     * 4dp 是"手指真的动了"的下限，用来挡住零位移的点击。
     */
    static final float END_MIN_DY_DP = 4f;
    /** 贴底路径要求的最短时长，同样是挡点击。 */
    static final int END_MIN_DUR_MS = 40;

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
    boolean blurBg = true;

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
    float blurRadiusDp = DEF_BLUR_RADIUS;
    float blurDim = DEF_BLUR_DIM;
    float animScale = DEF_ANIM_SCALE;
    int animCurve = DEF_ANIM_CURVE;
    boolean endBoost = true;
    float endZoneDp = DEF_END_ZONE_DP;
    boolean zoneRatioMode = true;
    float zoneRatio = DEF_ZONE_RATIO;
    boolean topShade = true;
    float topShadeDyDp = DEF_TOP_SHADE_DY_DP;
    int topShadeSide = DEF_TOP_SHADE_SIDE;
    int topShadeDelayMs = DEF_TOP_SHADE_DELAY_MS;

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

            // 触发区：比例模式优先。按屏幕高度算，dock 栏上沿约 15%，
            // 换机型时边界跟着屏幕走，不会像固定 dp 那样在小屏上吃掉半个屏幕。
            c.zoneRatioMode = boolOf(cr, K_ZONE_RATIO_MODE, true);
            c.zoneRatio = clamp(floatOf(cr, K_ZONE_RATIO, DEF_ZONE_RATIO), 0.05f, 0.30f);
            if (c.zoneRatioMode) {
                Screen zs = Screen.get(ctx);
                float d = zs.density <= 0f ? 3f : zs.density;
                c.zoneDp = clamp(zs.height * c.zoneRatio / d, 20f, 320f);
            } else {
                c.zoneDp = clamp(floatOf(cr, K_ZONE_DP, DEF_ZONE_DP), 20f, 200f);
            }
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

            // 外观：模糊壁纸遮罩（2.3）。读不到或越界一律回退到安全默认值。
            c.blurBg = boolOf(cr, K_BLUR_BG, true);
            c.blurRadiusDp = clamp(floatOf(cr, K_BLUR_RADIUS, DEF_BLUR_RADIUS), 0f, 80f);
            c.blurDim = clamp(floatOf(cr, K_BLUR_DIM, DEF_BLUR_DIM), 0f, 70f);

            // 动画：倍率 0.6~2.0，越界/NaN 一律回落到默认；曲线 0~2。
            c.animScale = clamp(floatOf(cr, K_ANIM_SCALE, DEF_ANIM_SCALE), 0.6f, 2.0f);
            c.animCurve = (int) clamp(intOf(cr, K_ANIM_CURVE, DEF_ANIM_CURVE),
                    ANIM_CURVE_NATIVE, ANIM_CURVE_SNAPPY);

            // 落点判定：默认开（抬手必须贴底），带宽 8~80dp
            c.endBoost = boolOf(cr, K_END_BOOST, true);
            c.endZoneDp = clamp(floatOf(cr, K_END_ZONE_DP, DEF_END_ZONE_DP), 8f, 80f);

            // 模糊区下拉（2.9）：默认开，36dp，左通知右控制
            c.topShade = boolOf(cr, K_TOP_SHADE, true);
            c.topShadeDyDp = clamp(floatOf(cr, K_TOP_SHADE_DY_DP, DEF_TOP_SHADE_DY_DP), 8f, 120f);
            c.topShadeSide = (int) clamp(intOf(cr, K_TOP_SHADE_SIDE, DEF_TOP_SHADE_SIDE),
                    TOP_SIDE_SPLIT, TOP_SIDE_CONTROL);
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
                + ", claimZone=" + claimZone + ", debug=" + debug
                + ", blurBg=" + blurBg + "/" + blurRadiusDp + "dp/" + blurDim + "%}"
                + ", anim=" + animScale + "x/curve" + animCurve
                + ", endBoost=" + endBoost + "/" + endZoneDp + "dp"
                + ", topShade=" + topShade + "/" + topShadeDyDp + "dp/side" + topShadeSide
                + ", zoneRatio=" + (zoneRatioMode ? zoneRatio + "(" + zoneDp + "dp)" : "off") + "}";
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
