package io.github.zpan2327.hyperhand;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

/**
 * Miuix 的 OS3 氛围背景（example/shared/component/effect 里的 OS3_BG_FRAG）。
 *
 * 原实现是 Compose + RuntimeShader：4 个彩色光点按正弦慢慢漂移、按周期在 3 组
 * 配色之间插值，再叠一层 perlin 噪声做去饱和/提亮和 1/255 抖动。
 * 这里把同一份 GLSL 改成 AGSL（去掉了数组下标，四个点展开），
 * 用原生 RuntimeShader 画在 View 上；API 33 以下退化成 4 层径向渐变。
 *
 * SonyPods / KernelSU 的关于页、概览页顶部那层粉紫渐变就是这套东西。
 */
final class MiuixBg extends View {

    private static final String FRAG = ""
            + "uniform float2 uResolution;\n"
            + "uniform float uAnimTime;\n"
            + "uniform float2 uP0;\n"
            + "uniform float2 uP1;\n"
            + "uniform float2 uP2;\n"
            + "uniform float2 uP3;\n"
            + "uniform float uRadius;\n"
            + "uniform float uNoiseScale;\n"
            + "uniform float uSaturateOffset;\n"
            + "uniform float uLightOffset;\n"
            + "uniform float4 uC0;\n"
            + "uniform float4 uC1;\n"
            + "uniform float4 uC2;\n"
            + "uniform float4 uC3;\n"
            + "\n"
            + "float3 rgb2hsv(float3 c) {\n"
            + "  float4 K = float4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);\n"
            + "  float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));\n"
            + "  float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));\n"
            + "  float d = q.x - min(q.w, q.y);\n"
            + "  float e = 1.0e-10;\n"
            + "  return float3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);\n"
            + "}\n"
            + "\n"
            + "float3 hsv2rgb(float3 c) {\n"
            + "  float4 K = float4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);\n"
            + "  float3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);\n"
            + "  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);\n"
            + "}\n"
            + "\n"
            + "float hash21(float2 p) {\n"
            + "  float3 p3 = fract(float3(p.xyx) * 0.13);\n"
            + "  p3 += dot(p3, p3.yzx + 3.333);\n"
            + "  return fract((p3.x + p3.y) * p3.z);\n"
            + "}\n"
            + "\n"
            + "float perlin(float2 x) {\n"
            + "  float2 i = floor(x);\n"
            + "  float2 f = fract(x);\n"
            + "  float a = hash21(i);\n"
            + "  float b = hash21(i + float2(1.0, 0.0));\n"
            + "  float c = hash21(i + float2(0.0, 1.0));\n"
            + "  float d = hash21(i + float2(1.0, 1.0));\n"
            + "  float2 u = f * f * (3.0 - 2.0 * f);\n"
            + "  return mix(a, b, u.x) + (c - a) * u.y * (1.0 - u.x) + (d - b) * u.x * u.y;\n"
            + "}\n"
            + "\n"
            + "float gradientNoise(float2 uv) {\n"
            + "  return fract(52.9829189 * fract(dot(uv, float2(0.06711056, 0.00583715))));\n"
            + "}\n"
            + "\n"
            + "float4 addPoint(float4 col, float2 uv, float2 pt, float4 pc) {\n"
            + "  float4 c = pc;\n"
            + "  c.rgb *= c.a;\n"
            + "  float pct = smoothstep(uRadius, 0.0, distance(uv, pt));\n"
            + "  col.rgb = mix(col.rgb, c.rgb, pct);\n"
            + "  col.a = mix(col.a, c.a, pct);\n"
            + "  return col;\n"
            + "}\n"
            + "\n"
            + "vec4 main(vec2 fragCoord) {\n"
            + "  float2 vUv = fragCoord / uResolution;\n"
            + "  vUv.y = 1.0 - vUv.y;\n"
            + "  float noiseValue = perlin(vUv * uNoiseScale + float2(-uAnimTime, -uAnimTime));\n"
            + "  float4 color = float4(0.0);\n"
            + "  color = addPoint(color, vUv, uP0, uC0);\n"
            + "  color = addPoint(color, vUv, uP1, uC1);\n"
            + "  color = addPoint(color, vUv, uP2, uC2);\n"
            + "  color = addPoint(color, vUv, uP3, uC3);\n"
            + "  float opp = smoothstep(0.0, 1.0, noiseValue);\n"
            + "  color.rgb /= max(color.a, 0.0001);\n"
            + "  float3 hsv = rgb2hsv(color.rgb);\n"
            + "  hsv.y = mix(hsv.y, 0.0, opp * uSaturateOffset);\n"
            + "  color.rgb = hsv2rgb(hsv);\n"
            + "  color.rgb += opp * uLightOffset;\n"
            + "  color.a = clamp(color.a, 0.0, 1.0);\n"
            + "  color += (10.0 / 255.0) * gradientNoise(fragCoord) - (5.0 / 255.0);\n"
            + "  return vec4(color.rgb * color.a, color.a);\n"
            + "}\n";

    // OS3 手机配色（Miuix BgEffectConfig：OS3_PHONE_LIGHT / OS3_PHONE_DARK）
    private static final float[] LIGHT_C1 = {1.0f, 0.9f, 0.94f, 1f, 1.0f, 0.84f, 0.89f, 1f,
            0.97f, 0.73f, 0.82f, 1f, 0.64f, 0.65f, 0.98f, 1f};
    private static final float[] LIGHT_C2 = {0.58f, 0.74f, 1.0f, 1f, 1.0f, 0.9f, 0.93f, 1f,
            0.74f, 0.76f, 1.0f, 1f, 0.97f, 0.77f, 0.84f, 1f};
    private static final float[] LIGHT_C3 = {0.98f, 0.86f, 0.9f, 1f, 0.6f, 0.73f, 0.98f, 1f,
            0.92f, 0.93f, 1.0f, 1f, 0.56f, 0.69f, 1.0f, 1f};

    private static final float[] DARK_C1 = {0.2f, 0.06f, 0.88f, 0.4f, 0.3f, 0.14f, 0.55f, 0.5f,
            0.0f, 0.64f, 0.96f, 0.5f, 0.11f, 0.16f, 0.83f, 0.4f};
    private static final float[] DARK_C2 = {0.07f, 0.15f, 0.79f, 0.5f, 0.62f, 0.21f, 0.67f, 0.5f,
            0.06f, 0.25f, 0.84f, 0.5f, 0.0f, 0.2f, 0.78f, 0.5f};
    private static final float[] DARK_C3 = {0.58f, 0.3f, 0.74f, 0.4f, 0.27f, 0.18f, 0.6f, 0.5f,
            0.66f, 0.26f, 0.62f, 0.5f, 0.12f, 0.16f, 0.7f, 0.6f};

    private static final float[] POINTS = {0.8f, 0.2f, 0.8f, 0.9f, 0.2f, 0.9f, 0.2f, 0.2f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] c1;
    private final float[] c2;
    private final float[] c3;
    private final float[] colors = new float[16];
    private final float[] animPoints = new float[8];
    private final float period;
    private final float pointOffset;
    private final float saturateOffset;
    private final float lightOffset;
    private final boolean night;

    private Object gpu;              // Gpu 实例；API < 33 时为 null
    private ValueAnimator animator;
    private long startAt;

    MiuixBg(Context c, Theme.Colors pal) {
        super(c);
        night = pal.night;
        c1 = night ? DARK_C1 : LIGHT_C1;
        c2 = night ? DARK_C2 : LIGHT_C2;
        c3 = night ? DARK_C3 : LIGHT_C3;
        period = night ? 8f : 5f;
        pointOffset = night ? 0.4f : 0.2f;
        saturateOffset = night ? 0.17f : 0.2f;
        lightOffset = night ? 0f : 0.1f;
        System.arraycopy(c2, 0, colors, 0, 16);

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                gpu = new Gpu(FRAG);
            } catch (Throwable t) {
                gpu = null;
            }
        }
        fade.setShader(new LinearGradient(0, 0, 0, 1, new int[] {0x00000000, pal.bg},
                new float[] {0f, 1f}, Shader.TileMode.CLAMP));
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void start() {
        if (animator != null) {
            return;
        }
        startAt = SystemClock.uptimeMillis();
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(9000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                invalidate();
            }
        });
        animator.start();
    }

    void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // 关于页默认是 GONE 的，看不见时不该还在跑动画
        if (visibility == VISIBLE) {
            start();
        } else {
            stop();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = Math.max(getWidth(), 1);
        float h = Math.max(getHeight(), 1);
        float t = (SystemClock.uptimeMillis() - startAt) / 1000f;

        if (gpu != null) {
            // 配色按周期在 3 组之间循环：c2 -> c1 -> c2 -> c3
            float stage = (t / period) % 4f;
            int base = (int) stage;
            float frac = stage - base;
            float[] from = cycle(base);
            float[] to = cycle(base + 1);
            for (int i = 0; i < 16; i++) {
                colors[i] = from[i] + (to[i] - from[i]) * frac;
            }
            for (int i = 0; i < 4; i++) {
                float sx = POINTS[i * 2];
                float sy = POINTS[i * 2 + 1];
                float ax = sx + (float) Math.sin(t + sy) * pointOffset;
                float ay = sy + (float) Math.cos(t + ax) * pointOffset;
                animPoints[i * 2] = ax;
                animPoints[i * 2 + 1] = ay;
            }
            ((Gpu) gpu).render(canvas, paint, w, h, t, animPoints, colors,
                    saturateOffset, lightOffset);
        } else {
            drawFallback(canvas, w, h, t);
        }

        // 底部渐隐回页面底色，卡片才不会直接压在一刀切的色块上。
        if (Theme.demoBg) {
            // 演示模式没有"页面底色"可回 —— 用 DST_OUT 把这层自己淡成透明，
            // 露出页面的彩色渐变。用 Theme.alpha(0x00000000, .55f) 会算成半透明黑，
            // 在 42% 高度切出一条脏带。
            Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
            cut.setShader(new LinearGradient(0, h * 0.42f, 0, h,
                    new int[] {0x00000000, 0xFF000000}, null, Shader.TileMode.CLAMP));
            cut.setXfermode(new android.graphics.PorterDuffXfermode(
                    android.graphics.PorterDuff.Mode.DST_OUT));
            canvas.drawRect(0, h * 0.42f, w, h, cut);
            return;
        }
        fade.setShader(new LinearGradient(0, h * 0.42f, 0, h,
                new int[] {0x00000000, Theme.alpha(fadeColor(), 0.55f),
                        Theme.alpha(fadeColor(), 0.92f), fadeColor()},
                new float[] {0f, 0.45f, 0.8f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, h * 0.42f, w, h, fade);
    }

    private int fadeColor() {
        // 演示模式（彩色渐变背景）下淡出到全透明，否则那块不透明的面色会
        // 在 420dp 处切出一条硬边，把页面的渐变盖掉。
        if (Theme.demoBg) {
            return 0x00000000;
        }
        return night ? 0xFF000000 : 0xFFF7F7F7;
    }

    /** OS3 的配色循环：索引 1 -> colors1，3 -> colors3，其它 -> colors2。 */
    private float[] cycle(int index) {
        int m = ((index % 4) + 4) % 4;
        if (m == 1) {
            return c1;
        }
        if (m == 3) {
            return c3;
        }
        return c2;
    }

    /** API 33 以下：用 4 层径向渐变近似（没有噪声与去饱和，观感接近）。 */
    private void drawFallback(Canvas canvas, float w, float h, float t) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float stage = (t / period) % 4f;
        int base = (int) stage;
        float frac = stage - base;
        float[] from = cycle(base);
        float[] to = cycle(base + 1);
        float r = Math.max(w, h);
        for (int i = 0; i < 4; i++) {
            float sx = POINTS[i * 2];
            float sy = POINTS[i * 2 + 1];
            float ax = sx + (float) Math.sin(t + sy) * pointOffset;
            float ay = sy + (float) Math.cos(t + ax) * pointOffset;
            int col = Color.argb(
                    Math.round((from[i * 4 + 3] + (to[i * 4 + 3] - from[i * 4 + 3]) * frac) * 255),
                    Math.round((from[i * 4] + (to[i * 4] - from[i * 4]) * frac) * 255),
                    Math.round((from[i * 4 + 1] + (to[i * 4 + 1] - from[i * 4 + 1]) * frac) * 255),
                    Math.round((from[i * 4 + 2] + (to[i * 4 + 2] - from[i * 4 + 2]) * frac) * 255));
            p.setShader(new RadialGradient(ax * w, ay * h, r,
                    new int[] {col, Theme.alpha(col, 0f)}, new float[] {0f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, p);
        }
    }

    /**
     * RuntimeShader 的封装：单独放一个类，只在 API 33+ 才会被加载，
     * 这样 API 31/32 上连类校验都不会碰到 android.graphics.RuntimeShader。
     */
    private static final class Gpu {
        private final RuntimeShader shader;

        Gpu(String frag) {
            shader = new RuntimeShader(frag);
        }

        void render(Canvas canvas, Paint paint, float w, float h, float t,
                    float[] points, float[] colors, float saturate, float light) {
            shader.setFloatUniform("uResolution", w, h);
            shader.setFloatUniform("uAnimTime", t);
            shader.setFloatUniform("uRadius", 1f);
            shader.setFloatUniform("uNoiseScale", 1.5f);
            shader.setFloatUniform("uSaturateOffset", saturate);
            shader.setFloatUniform("uLightOffset", light);
            for (int i = 0; i < 4; i++) {
                shader.setFloatUniform("uP" + i, points[i * 2], points[i * 2 + 1]);
                shader.setFloatUniform("uC" + i, colors[i * 4], colors[i * 4 + 1],
                        colors[i * 4 + 2], colors[i * 4 + 3]);
            }
            paint.setShader(shader);
            canvas.drawRect(0, 0, w, h, paint);
        }
    }
}
