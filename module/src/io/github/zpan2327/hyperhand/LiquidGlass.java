package io.github.zpan2327.hyperhand;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;

/**
 * 液态玻璃渲染 —— 结构完全照抄手机上 {@code io.github.hyperisland} 反编译出来的实现。
 *
 * <p>原实现（{@code LiquidGlassDrawable.draw}）的管线是：
 * <ol>
 *   <li>{@code RefractiveScreenCapture} 抓到屏幕内容，<b>先在自己的 blurNode 上做高斯模糊</b>，
 *       得到一张"已经模糊好的"位图；</li>
 *   <li>把这尊位图塞进 {@link BitmapShader}，作为 {@code uContent} 输入给折射 AGSL；</li>
 *   <li>{@code refractionPaint.setShader(refractionShader)}，直接
 *       {@code canvas.drawRect(glassRect, paint)}。</li>
 * </ol>
 *
 * 关键点：<b>模糊在折射之前</b>。先模糊再折射，边缘位移采样到的都是模糊块，不会把文字
 * 撕成彩条；反过来就会得到"脏拖影"。这里把第 1 步用 CPU 可分离盒式模糊实现
 * （采样只有 0.5 分辨率、一千多像素宽，代价可以接受），第 2、3 步与源码逐字对应。
 */
final class LiquidGlass {

    private LiquidGlass() {
    }

    static boolean canShader() {
        return Build.VERSION.SDK_INT >= 33;
    }

    /** 上一帧走的路径（调试用）。 */
    static String lastPath = "init";

    // ---------------------------------------------------------------- 模糊

    /**
     * 可分离盒式模糊（三次近似高斯），就地修改。
     * 与源码一致的用法：在<b>采样位图空间</b>里模糊，半径 = 屏幕半径 / 采样缩放。
     */
    static void blur(Bitmap bmp, int radiusPx) {
        if (bmp == null || bmp.isRecycled() || radiusPx < 1) {
            return;
        }
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        if (w < 2 || h < 2) {
            return;
        }
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        int[] tmp = new int[px.length];
        int r = Math.max(1, Math.min(radiusPx, Math.min(w, h) / 2));
        for (int pass = 0; pass < 3; pass++) {
            blurH(px, tmp, w, h, r);
            blurV(tmp, px, w, h, r);
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blurH(int[] src, int[] dst, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[row + clampI(i, 0, w - 1)];
                a += (c >>> 24);
                rr += (c >> 16) & 0xFF;
                gg += (c >> 8) & 0xFF;
                bb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                dst[row + x] = ((a / div) << 24) | ((rr / div) << 16)
                        | ((gg / div) << 8) | (bb / div);
                int out = src[row + clampI(x - r, 0, w - 1)];
                int in = src[row + clampI(x + r + 1, 0, w - 1)];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                gg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                bb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static void blurV(int[] src, int[] dst, int w, int h, int r) {
        int div = r * 2 + 1;
        for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, gg = 0, bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[clampI(i, 0, h - 1) * w + x];
                a += (c >>> 24);
                rr += (c >> 16) & 0xFF;
                gg += (c >> 8) & 0xFF;
                bb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                dst[y * w + x] = ((a / div) << 24) | ((rr / div) << 16)
                        | ((gg / div) << 8) | (bb / div);
                int out = src[clampI(y - r, 0, h - 1) * w + x];
                int in = src[clampI(y + r + 1, 0, h - 1) * w + x];
                a += (in >>> 24) - (out >>> 24);
                rr += ((in >> 16) & 0xFF) - ((out >> 16) & 0xFF);
                gg += ((in >> 8) & 0xFF) - ((out >> 8) & 0xFF);
                bb += (in & 0xFF) - (out & 0xFF);
            }
        }
    }

    private static int clampI(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ---------------------------------------------------------------- 玻璃

    /**
     * 画一块液态玻璃：折射 AGSL 直接作为 Paint 的 shader，采样已模糊的位图。
     *
     * @param sample     已经模糊好的采样位图
     * @param captureX/Y 该位图左上角在画布坐标系里的位置
     * @param scale      位图 → 画布的缩放（位图 px / 画布 px 的倒数）
     * @param shape      玻璃形状（画布坐标系，不含外扩）
     * @param edgePx     折射带宽 uRefractionHeight
     * @param amountPx   折射位移 uRefractionAmount（负值 = 朝外推）
     * @param dispersion 色散强度
     * @param tint       叠色（uBlendColor）
     */
    static void drawGlass(Canvas canvas, Bitmap sample, float captureX, float captureY,
                          float scale, android.graphics.RectF shape, float radius,
                          float edgePx, float amountPx, float dispersion, int tint,
                          boolean clipToShape) {
        if (sample == null || sample.isRecycled() || shape.width() <= 0 || shape.height() <= 0) {
            return;
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        boolean ok = false;

        if (canShader() && canvas.isHardwareAccelerated()) {
            try {
                RuntimeShader shader = new RuntimeShader(REFRACTION);
                shader.setInputShader("uContent",
                        new BitmapShader(sample, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                shader.setFloatUniform("uOrigin", shape.left, shape.top);
                shader.setFloatUniform("uSize", shape.width(), shape.height());
                shader.setFloatUniform("uCornerRadius",
                        Math.min(radius, Math.min(shape.width(), shape.height()) * 0.5f));
                shader.setFloatUniform("uRefractionHeight", Math.max(1f, edgePx));
                shader.setFloatUniform("uRefractionAmount", amountPx);
                shader.setFloatUniform("uDepthEffect", 1f);
                shader.setFloatUniform("uDispersion", dispersion * 0.12f);
                shader.setFloatUniform("uCaptureScale", 1f / Math.max(0.05f, scale));
                shader.setFloatUniform("uCaptureOrigin", captureX, captureY);
                shader.setFloatUniform("uBlendColor",
                        Color.red(tint) / 255f, Color.green(tint) / 255f,
                        Color.blue(tint) / 255f, Color.alpha(tint) / 255f);
                paint.setShader(shader);
                ok = true;
            } catch (Throwable t) {
                ok = false;
            }
        }

        if (!ok) {
            // 退化：直接画模糊后的采样（也不裁圆角，由调用方的 clipPath 负责）
            lastPath = "fallback";
            paint.setShader(new BitmapShader(sample, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.postScale(scale, scale);
            m.postTranslate(captureX, captureY);
            paint.getShader().setLocalMatrix(m);
        }

        int save = -1;
        if (clipToShape) {
            save = canvas.save();
            Path clip = new Path();
            Squircle.rect(clip, shape, radius);
            canvas.clipPath(clip);
        }
        canvas.drawRect(shape, paint);
        if (save >= 0) {
            canvas.restoreToCount(save);
        }
        lastPath = ok ? "paint+refraction" : "fallback";
    }

    /** 不需要折射时：把模糊采样按位图坐标画到矩形上（标题栏用）。 */
    static void drawBlurredRect(Canvas canvas, Bitmap sample, float captureX, float captureY,
                                float scale, android.graphics.RectF dst) {
        if (sample == null || sample.isRecycled()) {
            return;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(sample, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.postScale(scale, scale);
        m.postTranslate(captureX, captureY);
        shader.setLocalMatrix(m);
        p.setShader(shader);
        canvas.drawRect(dst, p);
    }

    // ---------------------------------------------------------------- 边缘高光

    /**
     * 边缘高光 / 内阴影 / 色散 —— 移植 {@code LiquidGlassEdgeHighlight.SDR_EDGE_SHADER}。
     */
    static boolean drawEdgeHighlight(Canvas canvas, android.graphics.RectF rect, float radius,
                                     float lightX, float lightY, float edgeWidth,
                                     float highlight, float shadow, float lensBand,
                                     float dispersion) {
        if (!canShader() || !canvas.isHardwareAccelerated()) {
            return false;
        }
        try {
            RuntimeShader shader = new RuntimeShader(EDGE);
            shader.setFloatUniform("uOrigin", rect.left, rect.top);
            shader.setFloatUniform("uSize", rect.width(), rect.height());
            shader.setFloatUniform("uCornerRadius", radius);
            shader.setFloatUniform("uLightDir", lightX, lightY);
            shader.setFloatUniform("uEdgeWidth", Math.max(1f, edgeWidth));
            shader.setFloatUniform("uRefraction", lensBand);
            shader.setFloatUniform("uEdgeAlpha", highlight * 0.56f);
            shader.setFloatUniform("uEdgeShadow", shadow * 0.34f);
            shader.setFloatUniform("uDispersion", dispersion * 0.16f);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setShader(shader);
            canvas.drawRect(rect.left - 1f, rect.top - 1f,
                    rect.right + 1f, rect.bottom + 1f, p);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 投影：中心外扩多层递减透明度。 */
    static void drawShadow(Canvas canvas, android.graphics.RectF r, float radius,
                           float spreadPx, int color, float alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int layers = 7;
        for (int i = layers; i >= 1; i--) {
            float t = i / (float) layers;
            float grow = spreadPx * t;
            p.setColor(color(color, alpha * (1f - t) / layers * 2.2f));
            android.graphics.RectF box = new android.graphics.RectF(
                    r.left - grow, r.top - grow * 0.55f + spreadPx * 0.5f,
                    r.right + grow, r.bottom + grow * 0.55f + spreadPx * 0.5f);
            Path path = new Path();
            Squircle.rect(path, box, radius + grow);
            canvas.drawPath(path, p);
        }
    }

    /** 上表面反光：从顶边往下衰减的白色渐变。 */
    static void drawTopGloss(Canvas canvas, android.graphics.RectF r, float heightFraction,
                             int color, float alpha) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, r.top, 0, r.top + r.height() * heightFraction,
                new int[] {color(color, alpha), color(color, 0f)}, null, Shader.TileMode.CLAMP));
        Path path = new Path();
        Squircle.rect(path, r, Math.min(r.height(), r.width()) / 2f);
        canvas.drawPath(path, p);
    }

    private static int color(int c, float a) {
        int al = Math.round(Color.alpha(c) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (c & 0x00FFFFFF);
    }

    // ---------------------------------------------------------------- AGSL

    /**
     * 来自 {@code LiquidGlassDrawable.REFRACTION_SHADER}（HyperIsland）。
     *
     * <p>⚠ 所有局部变量名都必须全局唯一：Skia 的 AGSL 编译器会把辅助函数<b>内联</b>进
     * {@code main}，两个函数各自声明的同名局部量（例如 {@code q}、{@code distance}）
     * 内联后会落在同一作用域里，直接报 {@code redefinition} 让整颗 shader 编译失败。
     * 原生 GPU 驱动上不一定报，模拟器的驱动上必报 —— 所以这里统一加前缀。
     */
    private static final String REFRACTION = ""
            + "uniform shader uContent;\n"
            + "uniform float2 uCaptureScale;\n"
            + "uniform float2 uCaptureOrigin;\n"
            + "uniform float2 uOrigin;\n"
            + "uniform float2 uSize;\n"
            + "uniform float uCornerRadius;\n"
            + "uniform float uRefractionHeight;\n"
            + "uniform float uRefractionAmount;\n"
            + "uniform float uDepthEffect;\n"
            + "uniform float uDispersion;\n"
            + "uniform float4 uBlendColor;\n"
            + "\n"
            + "float sdRoundedBox(float2 sdP, float2 sdH, float sdR) {\n"
            + "  float2 sdQ = abs(sdP) - sdH + float2(sdR);\n"
            + "  return min(max(sdQ.x, sdQ.y), 0.0) + length(max(sdQ, 0.0)) - sdR;\n"
            + "}\n"
            + "\n"
            + "float2 gradRoundedBox(float2 gbP, float2 gbH, float gbR) {\n"
            + "  float2 gbQ = abs(gbP) - gbH + float2(gbR);\n"
            + "  float2 gbS = sign(gbP);\n"
            + "  gbS.x = gbS.x == 0.0 ? 1.0 : gbS.x;\n"
            + "  gbS.y = gbS.y == 0.0 ? 1.0 : gbS.y;\n"
            + "  if (gbQ.x >= 0.0 || gbQ.y >= 0.0) {\n"
            + "    return gbS * normalize(max(gbQ, 0.0) + float2(0.0001));\n"
            + "  }\n"
            + "  float gbGx = step(gbQ.y, gbQ.x);\n"
            + "  return gbS * float2(gbGx, 1.0 - gbGx);\n"
            + "}\n"
            + "\n"
            + "float circleMap(float cmX) {\n"
            + "  return 1.0 - sqrt(max(1.0 - cmX * cmX, 0.0));\n"
            + "}\n"
            + "\n"
            + "half4 sampleContent(float2 scCoord) {\n"
            + "  half4 scC = uContent.eval((scCoord - uCaptureOrigin) * uCaptureScale);\n"
            + "  return half4(mix(scC.rgb, half3(uBlendColor.rgb), half(uBlendColor.a)), 1.0);\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "  float2 mHalf = uSize * 0.5;\n"
            + "  float2 mP = fragCoord - uOrigin - mHalf;\n"
            + "  float mSd = sdRoundedBox(mP, mHalf, uCornerRadius);\n"
            + "  if (mSd > 0.0) {\n"
            + "    return half4(0.0);\n"
            + "  }\n"
            + "  if (-mSd >= uRefractionHeight) {\n"
            + "    return sampleContent(fragCoord);\n"
            + "  }\n"
            + "  float mDepth = clamp(-min(mSd, 0.0) / uRefractionHeight, 0.0, 1.0);\n"
            + "  float mFade = 1.0 - mDepth * mDepth * (3.0 - 2.0 * mDepth);\n"
            + "  float mDisp = circleMap(1.0 - mDepth) * uRefractionAmount * mFade;\n"
            + "  float2 mCenterDir = normalize(mP + float2(0.0001));\n"
            + "  float2 mNormal = normalize(\n"
            + "      gradRoundedBox(mP, mHalf, uCornerRadius)\n"
            + "      + uDepthEffect * mFade * mCenterDir);\n"
            + "  float2 mRefracted = fragCoord + mNormal * mDisp;\n"
            + "  float mPosF = abs(mP.x * mP.y) / max(mHalf.x * mHalf.y, 1.0);\n"
            + "  float2 mCa = mDisp * mNormal * uDispersion * (0.4 + 0.6 * mPosF);\n"
            + "  half mR = sampleContent(mRefracted + mCa).r;\n"
            + "  half4 mC = sampleContent(mRefracted);\n"
            + "  half mB = sampleContent(mRefracted - mCa).b;\n"
            + "  return half4(half3(mR, mC.g, mB), 1.0);\n"
            + "}\n";

    /**
     * 来自 {@code LiquidGlassEdgeHighlight.SDR_EDGE_SHADER}（HyperIsland）。
     * 局部变量同样全部加前缀，理由见 {@link #REFRACTION}。
     */
    private static final String EDGE = ""
            + "uniform float2 uOrigin;\n"
            + "uniform float2 uSize;\n"
            + "uniform float uCornerRadius;\n"
            + "uniform float2 uLightDir;\n"
            + "uniform float uEdgeWidth;\n"
            + "uniform float uRefraction;\n"
            + "uniform float uEdgeAlpha;\n"
            + "uniform float uEdgeShadow;\n"
            + "uniform float uDispersion;\n"
            + "\n"
            + "float sdRoundedBoxE(float2 seP, float2 seH, float seR) {\n"
            + "  float2 seQ = abs(seP) - seH + float2(seR);\n"
            + "  return min(max(seQ.x, seQ.y), 0.0) + length(max(seQ, 0.0)) - seR;\n"
            + "}\n"
            + "\n"
            + "float edgeCoverage(float ecD) {\n"
            + "  return 1.0 - smoothstep(-0.75, 0.75, ecD);\n"
            + "}\n"
            + "\n"
            + "float3 edgeGeometry(float2 egP, float2 egH, float egR,\n"
            + "                    float egW, float2 egL) {\n"
            + "  float egD = sdRoundedBoxE(egP, egH, egR);\n"
            + "  float2 egDx = float2(1.0, 0.0);\n"
            + "  float2 egDy = float2(0.0, 1.0);\n"
            + "  float2 egN = normalize(float2(\n"
            + "      sdRoundedBoxE(egP + egDx, egH, egR) - sdRoundedBoxE(egP - egDx, egH, egR),\n"
            + "      sdRoundedBoxE(egP + egDy, egH, egR) - sdRoundedBoxE(egP - egDy, egH, egR)\n"
            + "  ) + float2(0.0001));\n"
            + "  float egF = dot(egN, normalize(egL));\n"
            + "  float egEdge = pow(smoothstep(-egW, 0.0, egD), 2.2);\n"
            + "  return float3(egD, egF, egEdge);\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "  float2 eHalf = uSize * 0.5;\n"
            + "  float2 eP = fragCoord - uOrigin - eHalf;\n"
            + "  float3 eGeo = edgeGeometry(eP, eHalf, uCornerRadius, uEdgeWidth, uLightDir);\n"
            + "  float eD = eGeo.x;\n"
            + "  float eCov = edgeCoverage(eD);\n"
            + "  if (eCov <= 0.0) return half4(0.0);\n"
            + "  float eFacing = eGeo.y;\n"
            + "  float eEdge = eGeo.z;\n"
            + "  float eBright = max(eFacing, 0.0) * eEdge * uEdgeAlpha;\n"
            + "  float eOpposite = max(-eFacing, 0.0) * eEdge * uEdgeShadow;\n"
            + "  float eBand = pow(smoothstep(-uEdgeWidth * 2.2, -uEdgeWidth * 0.25, eD), 2.0)\n"
            + "      * (1.0 - eEdge) * uRefraction;\n"
            + "  float eDisp = eFacing * eEdge * uDispersion * 0.22;\n"
            + "  float eAlpha = clamp(eBright + eOpposite + eBand, 0.0, 1.0);\n"
            + "  float3 ePrimary = float3(1.0 + max(eDisp, 0.0), 0.99,\n"
            + "      0.96 + max(-eDisp, 0.0)) * (eBright + eBand * 0.45);\n"
            + "  float3 eSecondary = float3(0.92, 0.96, 1.0) * eOpposite;\n"
            + "  return half4(half3((ePrimary + eSecondary) * eCov), half(eAlpha * eCov));\n"
            + "}\n";
}
