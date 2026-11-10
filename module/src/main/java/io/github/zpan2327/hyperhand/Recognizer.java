package io.github.zpan2327.hyperhand;

/**
 * 手势识别核心（纯计算，不依赖系统 API）。
 *
 * 设计目标：把 2.1 的"单点峰值速度二值判定"换成更接近 iPhone Reachability 的综合判定。
 *
 * 一、采样
 *   把 MotionEvent 的历史采样（getHistoricalXxx）和当前采样一起收进来，按时间戳排序后
 *   存成以 ACTION_DOWN 为零点的相对时间轴（float 存毫秒，避免 uptimeMillis 的绝对值
 *   在 float 下丢精度）。120Hz / 144Hz 屏一帧 6~8ms，单帧位移只有几 px，
 *   用相邻两帧直接算速度噪声极大；有历史采样 + 分窗估计才能稳定。
 *
 * 二、速度估计
 *   不再取相邻两帧的瞬时速度，而是在 {@code velocity_window_ms}（默认 60ms）的滑动窗口上
 *   取两端位移 / 两端时间：
 *       v(i) = (y[i] - y[k]) / (t[i] - t[k]) * 1000 / density        [dp/s]
 *   k 是窗口起点（t[i]-t[k] <= window 的最远采样）。窗口越大越平滑、越小越灵敏。
 *   依次算出：
 *       vPeak —— 窗口速度的最大值（只取向下分量），代表"甩"的力度；
 *       vAvg  —— 整段位移 / 整段时长，代表"整体拖动"的快慢；
 *       末端速度 —— 抬手前的窗口速度，用来判断"抬手瞬间还在动"还是"早就停住了"。
 *   超过 {@code max_velocity} 的估计值直接按上限截断，避免一帧抖造成的假峰值。
 *
 * 三、双路径判定（不再要求所有条件同时达标）
 *   路径A（快甩）：短距离 + 峰值速度够
 *   路径B（慢拉）：长距离 + 平均速度够 + 轨迹稳定
 *   两条路径各自算一个置信度，取较大者；置信度用 min() 组合各分项，
 *   每个分项本身是 smoothstep 的连续函数，所以不存在"差 1px 就完全不触发"的悬崖。
 *
 * 四、轨迹稳定性
 *   monotonic = 净位移 / 总路程（只算 Y 方向）。中途反向往回拉会把比值拉低，
 *   低于 {@code min_monotonic} 直接判为"来回蹭"，避免误触。
 */
final class Recognizer {

    // ---------------------------------------------------------------- 未触发原因
    static final String R_OK           = "ok";
    static final String R_TOO_FEW      = "too_few_samples";
    static final String R_NOT_DOWN     = "not_downward";
    static final String R_TOO_SHORT    = "too_short";
    static final String R_LATERAL      = "lateral";
    static final String R_BACKTRACK    = "backtrack";
    static final String R_REVERSE      = "reverse_at_end";
    static final String R_TOO_SLOW     = "too_slow";
    static final String R_TOO_LONG     = "too_long";
    static final String R_LOW_CONF     = "low_confidence";
    static final String R_NOT_AT_BOTTOM = "not_at_bottom";

    /** 原因 → 中文说明（设置页直接展示）。 */
    static String text(String reason) {
        if (R_OK.equals(reason)) return "已触发单手模式";
        if (R_TOO_FEW.equals(reason)) return "采样点太少，无法判定";
        if (R_NOT_DOWN.equals(reason)) return "方向不是向下";
        if (R_TOO_SHORT.equals(reason)) return "下滑距离太短";
        if (R_LATERAL.equals(reason)) return "横向偏移过大，判定为斜滑";
        if (R_BACKTRACK.equals(reason)) return "轨迹来回，判定为误触";
        if (R_REVERSE.equals(reason)) return "抬手前反向回拉";
        if (R_TOO_SLOW.equals(reason)) return "速度不足";
        if (R_TOO_LONG.equals(reason)) return "手势时间过长";
        if (R_LOW_CONF.equals(reason)) return "综合置信度不足";
        if (R_NOT_AT_BOTTOM.equals(reason)) return "抬手点没拖到屏幕底边，按页面滑动处理";
        if ("shade".equals(reason)) return "检测到通知/控制中心下拉，让位原生";
        if ("cooldown".equals(reason)) return "冷却期内";
        if ("not_in_zone".equals(reason)) return "起手点不在触发区";
        if ("cancel".equals(reason)) return "事件被取消";
        if ("multi_touch".equals(reason)) return "多指手势";
        if ("landscape".equals(reason)) return "横屏不触发";
        if ("keyguard".equals(reason)) return "锁屏状态";
        if ("active".equals(reason)) return "单手模式已激活";
        if ("disabled".equals(reason)) return "模块未启用";
        return reason;
    }

    /** 一次判定的完整结果。 */
    static final class Result {
        boolean trigger;
        String reason = R_TOO_FEW;
        char path = '-';

        float downX, downY, upX, upY;
        float dyDp, dxDp;
        float vAvgDp, vPeakDp;
        float monotonic;
        float conf;
        /** 抬手点距屏幕底边的距离（dp）。手指滑出屏幕时坐标被裁到底边，所以"拖到底"接近 0。 */
        float endGapDp;
        int durMs;
        int samples;
        boolean stillAtEnd;

        String oneLine() {
            return String.format(java.util.Locale.US,
                    "dy=%.1fdp dx=%.1fdp end=%.1fdp dur=%dms vavg=%.0f vpeak=%.0f mono=%.2f conf=%.2f n=%d%s",
                    dyDp, dxDp, endGapDp, durMs, vAvgDp, vPeakDp, monotonic, conf, samples,
                    stillAtEnd ? " still" : "");
        }
    }

    // ---------------------------------------------------------------- 轨迹缓冲
    private static final int CAP = 192;

    private final float[] ts = new float[CAP];   // 相对 DOWN 的毫秒
    private final float[] xs = new float[CAP];
    private final float[] ys = new float[CAP];
    private int n;
    private long t0;

    private float lastX;
    private float lastY;

    void begin(long eventTime, float x, float y) {
        n = 0;
        t0 = eventTime;
        lastX = x;
        lastY = y;
        push(0f, x, y);
    }

    /** 追加一个采样；同一个时间戳重复上报时用后者覆盖前者。 */
    void add(long eventTime, float x, float y) {
        lastX = x;
        lastY = y;
        if (n == 0) {
            t0 = eventTime;
        }
        float t = (float) (eventTime - t0);
        if (t < 0f) t = 0f;
        if (n > 0 && t <= ts[n - 1]) {
            xs[n - 1] = x;
            ys[n - 1] = y;
            return;
        }
        if (n == CAP) {
            // 缓冲满了：隔点抽稀而不是丢前半段 —— 必须保住 DOWN 原点，
            // 否则"起点到终点的位移"会算错。抽稀后分辨率减半，容量翻倍。
            decimate();
        }
        push(t, x, y);
    }

    private void push(float t, float x, float y) {
        if (n >= CAP) return;
        ts[n] = t;
        xs[n] = x;
        ys[n] = y;
        n++;
    }

    private void decimate() {
        int m = 0;
        for (int i = 0; i < n; i += 2) {
            ts[m] = ts[i];
            xs[m] = xs[i];
            ys[m] = ys[i];
            m++;
        }
        n = m;
    }

    int size() {
        return n;
    }

    float lastX() {
        return lastX;
    }

    float lastY() {
        return lastY;
    }

    /**
     * 收尾判定。调用方应先做区域 / 锁屏 / 多指等硬性前置条件，
     * 这里只负责"这条轨迹本身是否算一次下单手模式的下滑"。
     */
    Result finish(long eventTime, Cfg c, Cfg.Screen sc) {
        Result r = new Result();
        add(eventTime, lastX, lastY);

        final float density = sc.density <= 0f ? 3f : sc.density;
        r.samples = n;
        if (n < 2) {
            r.reason = R_TOO_FEW;
            return r;
        }

        final int first = 0;
        final int last = n - 1;
        r.downX = xs[first];
        r.downY = ys[first];
        r.upX = xs[last];
        r.upY = ys[last];

        final float dyPx = ys[last] - ys[first];
        final float dxPx = xs[last] - xs[first];
        final int dur = Math.max(0, Math.round(ts[last] - ts[first]));

        r.dyDp = dyPx / density;
        r.dxDp = dxPx / density;
        r.durMs = dur;
        // 抬手点离屏幕底边还有多远。手指滑出屏幕时系统会把坐标裁到底边，
        // 所以"一直拖到底"这个值接近 0，而页面滚动的抬手点通常在屏幕中段。
        r.endGapDp = clamp((sc.height - ys[last]) / density, 0f, 999f);

        if (n < 3) {
            r.reason = R_TOO_FEW;
            return r;
        }
        if (dur <= 0) {
            r.reason = R_NOT_DOWN;
            return r;
        }

        // ---- 速度（分窗）
        final int window = Math.max(20, c.velWindowMs);
        float vPeakPx = 0f;
        for (int i = 1; i < n; i++) {
            int k = i - 1;
            while (k > 0 && ts[i] - ts[k - 1] <= window) {
                k--;
            }
            float dt = ts[i] - ts[k];
            if (dt < Math.min(window * 0.5f, 24f)) continue;   // 窗口太短不可靠
            float v = (ys[i] - ys[k]) * 1000f / dt;            // px/s（向下为正）
            if (v > vPeakPx) vPeakPx = v;
        }
        r.vPeakDp = clamp(vPeakPx / density, 0f, c.maxVelocityDp);
        r.vAvgDp = clamp((dyPx * 1000f / dur) / density, -c.maxVelocityDp, c.maxVelocityDp);

        // 末端速度：抬手前最后一个窗口的方向，用于识别"拉下去又推回来"
        float vEndPx = 0f;
        {
            int k = last;
            while (k > 0 && ts[last] - ts[k - 1] <= window) {
                k--;
            }
            float dt = ts[last] - ts[k];
            if (dt >= 16f) vEndPx = (ys[last] - ys[k]) * 1000f / dt;
        }
        float vEndDp = vEndPx / density;

        // ---- 轨迹
        float pathY = 0f;
        for (int i = 1; i < n; i++) {
            pathY += Math.abs(ys[i] - ys[i - 1]);
        }
        r.monotonic = pathY <= 0.001f ? 0f : clamp(dyPx / pathY, 0f, 1f);

        // 抬手前是否已经停住（下拉后按住不动再抬手，不应算作下滑手势）
        r.stillAtEnd = dur > 150 && tailStill(window * 2f, density);

        // ---- 硬性闸门
        if (dyPx <= 0f) {
            r.reason = R_NOT_DOWN;
            return r;
        }
        final float lateralRatio = Math.abs(dxPx) / Math.max(dyPx, 1f);
        if (lateralRatio > c.maxSlope) {
            r.reason = R_LATERAL;
            return r;
        }
        if (r.monotonic < c.minMonotonic) {
            r.reason = R_BACKTRACK;
            return r;
        }
        if (vEndDp < -25f && r.monotonic < 0.92f) {
            r.reason = R_REVERSE;
            return r;
        }
        if (c.maxDurationMs > 0 && dur > c.maxDurationMs) {
            r.reason = R_TOO_LONG;
            return r;
        }

        // ---- 落点判定（2.8）：抬手点是不是拖到了屏幕最底部
        //
        // 只看"起点在底部触发区内"还不够 —— 桌面抽屉、可滚动的列表里，
        // 手指恰好从底部 56dp 内起手往下拖 20dp，同样满足距离+速度，
        // 但它想的是滚动页面。真正的单手模式手势一定是一路拖到屏幕底边
        // （拖出去以后坐标被裁到底边），两者在终点位置上有明显差别。
        //
        // 反过来，贴底本身就是很强的意图信号，所以这条路径不再要求速度、
        // 置信度和单调性，门槛只留"确实向下移动了一点点"，灵敏度反而更高。
        final boolean atBottom = r.endGapDp <= c.endZoneDp;
        if (c.endBoost) {
            if (!atBottom) {
                r.reason = R_NOT_AT_BOTTOM;
                return r;
            }
            if (r.dyDp < Cfg.END_MIN_DY_DP || dur < Cfg.END_MIN_DUR_MS) {
                r.reason = R_TOO_SHORT;
                return r;
            }
            r.conf = 1f;
            r.path = 'E';
            r.trigger = true;
            r.reason = R_OK;
            return r;
        }

        final float shortest = Math.min(c.minDyDp, c.slowEnabled ? c.slowDyDp : c.minDyDp);
        if (r.dyDp < shortest * 0.6f) {
            r.reason = R_TOO_SHORT;
            return r;
        }

        // ---- 分项评分
        // 方向分：横向偏移在 maxSlope 的 60% 以内不扣分，之后线性掉到 0
        double ratioN = lateralRatio / Math.max(c.maxSlope, 0.01f);
        double dirS = ratioN <= 0.6 ? 1.0 : 1.0 - (ratioN - 0.6) / 0.4;
        dirS = clamp(dirS, 0.0, 1.0);

        // 稳定分：达到下限后 0.7 → 1.0
        double stabS = c.minMonotonic >= 0.999f ? 1.0
                : 0.7 + 0.3 * ((r.monotonic - c.minMonotonic) / Math.max(0.001, 1.0 - c.minMonotonic));
        stabS = clamp(stabS, 0.7, 1.0);

        // 路径A：短距离 + 峰值速度
        double dA = score(r.dyDp, c.minDyDp);
        double vA = score(r.vPeakDp, c.minVelDp);
        double confA = Math.min(Math.min(dA, vA), Math.min(dirS, stabS));

        // 路径B：长距离 + 平均速度（慢滑）
        double confB = 0.0;
        if (c.slowEnabled) {
            double dB = score(r.dyDp, c.slowDyDp);
            double vB = score(r.vAvgDp, c.minAvgVelDp);
            confB = Math.min(Math.min(dB, vB), Math.min(dirS, stabS));
        }

        double conf = Math.max(confA, confB);
        r.path = confA >= confB ? 'A' : 'B';

        // 自适应：干脆利落的快速手势略加分，拖泥带水的长手势略减分
        if (c.adaptive) {
            if (dur <= 300) {
                conf += 0.06 * clamp((300 - dur) / 200.0, 0.0, 1.0);
            } else if (dur >= 700) {
                conf -= 0.06 * clamp((dur - 700) / 500.0, 0.0, 1.0);
            }
        }
        if (r.stillAtEnd) {
            conf *= 0.75;
        }
        r.conf = (float) clamp(conf, 0.0, 1.0);

        if (r.conf < c.minConfidence) {
            r.reason = r.vPeakDp < c.minVelDp * 0.6f && r.vAvgDp < c.minAvgVelDp * 0.6f
                    ? R_TOO_SLOW : R_LOW_CONF;
            return r;
        }

        r.trigger = true;
        r.reason = R_OK;
        return r;
    }

    /** 末尾 lookBackMs 内的垂直位移是否小到可以视为"停住了"。 */
    private boolean tailStill(float lookBackMs, float density) {
        if (n < 2) return false;
        int k = n - 1;
        while (k > 0 && ts[n - 1] - ts[k - 1] <= lookBackMs) {
            k--;
        }
        return Math.abs(ys[n - 1] - ys[k]) / density < 2f;
    }

    /**
     * 单项得分：达到门限即满分，低于门限平滑衰减到 0（45% 门限以下为 0）。
     *
     * 这样"刚好达标"不会被削成半分，而"略低于门限"会按比例掉分；
     * 多个分项取 min() 后，允许某一项稍弱、由其它项兜住，但不会出现断崖。
     */
    private static double score(double v, double threshold) {
        if (threshold <= 0.0) return 1.0;
        return smooth(threshold * 0.45, threshold, v);
    }

    private static double smooth(double edge0, double edge1, double x) {
        if (edge1 <= edge0) return x >= edge1 ? 1.0 : 0.0;
        double t = clamp((x - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}
