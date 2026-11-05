package io.github.zpan2327.hyperhand;

/**
 * 离线算法验证（不依赖设备）：把合成轨迹喂给 Recognizer，打印判定结果。
 * 只用于验证评分模型的数值行为，不代表真机手感。
 */
public class Test {

    static final float DENSITY = 3.04f;
    static final int W = 1200;
    static final int H = 2670;

    static Cfg cfg(int mode) {
        Cfg c = new Cfg();
        Cfg.Preset p = Cfg.preset(mode);
        c.mode = mode;
        c.minDyDp = p.minDyDp;
        c.minVelDp = p.velLowDp;
        c.slowDyDp = p.slowDyDp;
        c.minAvgVelDp = p.slowLowDp;
        c.minConfidence = p.confidence;
        c.slowEnabled = true;
        c.adaptive = true;
        c.enabled = true;
        c.monitorEnabled = true;
        c.zoneDp = 56f;
        c.widthRatio = 1f;
        c.zoneBias = 0f;
        c.maxSlope = 1.2f;
        c.minMonotonic = 0.78f;
        c.maxVelocityDp = 4000f;
        c.velWindowMs = 60;
        c.cooldownMs = 500;
        c.maxDurationMs = 0;
        return c;
    }

    static Cfg.Screen screen() {
        Cfg.Screen s = new Cfg.Screen();
        s.width = W;
        s.height = H;
        s.density = DENSITY;
        return s;
    }

    /** dyCum / dxCum 都是"累计位移(dp)"，逐帧等间隔。 */
    static Recognizer.Result go(Cfg c, float startY, float[] dyCum, float[] dxCum, int frameMs) {
        Recognizer r = new Recognizer();
        long t0 = 1000L;
        float x0 = W / 2f;
        r.begin(t0, x0, startY);
        int n = dyCum.length;
        for (int i = 0; i < n; i++) {
            float dx = dxCum == null ? 0f : dxCum[i];
            r.add(t0 + (long) (i + 1) * frameMs, x0 + dx * DENSITY, startY + dyCum[i] * DENSITY);
        }
        return r.finish(t0 + (long) n * frameMs, c, screen());
    }

    static float[] linear(float total, int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = total * (i + 1) / n;
        }
        return a;
    }

    static float[] hold(float[] base, int extraFrames) {
        float[] a = new float[base.length + extraFrames];
        System.arraycopy(base, 0, a, 0, base.length);
        float last = base[base.length - 1];
        for (int i = base.length; i < a.length; i++) a[i] = last;
        return a;
    }

    static void line(Cfg c, String name, Recognizer.Result r) {
        System.out.printf("%-28s mode=%d  dy=%6.1f dp  dur=%4d ms  vavg=%6.1f  vpeak=%6.1f  mono=%.2f  conf=%.2f  -> %s%n",
                name, c.mode, r.dyDp, r.durMs, r.vAvgDp, r.vPeakDp, r.monotonic, r.conf,
                r.trigger ? "TRIGGER(" + r.path + ")" : "SKIP(" + r.reason + ")");
    }

    public static void main(String[] args) {
        System.out.println("=== 自然模式（默认）===");
        Cfg c0 = cfg(Cfg.MODE_NATURAL);
        float yIn = H - 40f;

        line(c0, "A 快甩 12dp/48ms", go(c0, yIn, linear(12, 6), null, 8));
        line(c0, "B 自然下拉 40dp/400ms", go(c0, yIn, linear(40, 40), null, 10));
        line(c0, "C 中距离 20dp/250ms", go(c0, yIn, linear(20, 25), null, 10));
        line(c0, "D 短慢拖 8dp/400ms", go(c0, yIn, linear(8, 40), null, 10));
        line(c0, "E 轻点 2dp/60ms", go(c0, yIn, linear(2, 6), null, 10));
        line(c0, "F 斜滑 30dp/40dp横", go(c0, yIn, linear(30, 15), linear(40, 15), 10));
        line(c0, "G 来回蹭 0→35→15→35", go(c0, yIn,
                new float[]{10, 20, 30, 35, 25, 15, 25, 35}, null, 10));
        line(c0, "H 上滑（回桌面）", go(c0, yIn, linear(-60, 12), null, 10));
        line(c0, "I 抖动(慢滑+一帧尖峰)", go(c0, yIn,
                new float[]{1, 2, 3, 4, 30, 5, 6, 7}, null, 40));
        line(c0, "J 下拉后按住 400ms", go(c0, yIn, hold(linear(40, 20), 40), null, 10));
        line(c0, "K 极快甩 8dp/24ms", go(c0, yIn, linear(8, 3), null, 8));

        System.out.println();
        System.out.println("=== 灵敏模式 ===");
        Cfg c1 = cfg(Cfg.MODE_SENSITIVE);
        line(c1, "C 中距离 20dp/250ms", go(c1, yIn, linear(20, 25), null, 10));
        line(c1, "L 短轻扫 10dp/120ms", go(c1, yIn, linear(10, 10), null, 12));
        line(c1, "D 短慢拖 8dp/400ms", go(c1, yIn, linear(8, 40), null, 10));

        System.out.println();
        System.out.println("=== 防误触模式 ===");
        Cfg c2 = cfg(Cfg.MODE_STABLE);
        line(c2, "A 快甩 12dp/48ms", go(c2, yIn, linear(12, 6), null, 8));
        line(c2, "C 中距离 20dp/250ms", go(c2, yIn, linear(20, 25), null, 10));
        line(c2, "B 自然下拉 40dp/400ms", go(c2, yIn, linear(40, 40), null, 10));
        line(c2, "M 长慢拉 60dp/900ms", go(c2, yIn, linear(60, 90), null, 10));

        System.out.println();
        System.out.println("=== 慢拉路径（自然模式）===");
        line(c0, "P 慢拉 40dp/800ms (50dp/s)", go(c0, yIn, linear(40, 80), null, 10));
        line(c0, "Q 慢拉 25dp/600ms (42dp/s)", go(c0, yIn, linear(25, 60), null, 10));
        line(c0, "R 慢拉 60dp/1200ms (50dp/s)", go(c0, yIn, linear(60, 120), null, 10));
        line(c0, "S 慢拉 18dp/500ms (36dp/s)", go(c0, yIn, linear(18, 50), null, 10));

        System.out.println();
        System.out.println("=== 120Hz 断帧（16ms 采样，模拟卡顿）===");
        Cfg c3 = cfg(Cfg.MODE_NATURAL);
        line(c3, "N 快甩 12dp/64ms@16ms", go(c3, yIn, linear(12, 4), null, 16));
        line(c3, "O 自然下拉 40dp/480ms@16ms", go(c3, yIn, linear(40, 30), null, 16));
    }
}
