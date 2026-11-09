package io.github.zpan2327.hyperhand;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * 液态玻璃渲染管线 —— 直接移植 miuix-blur / SonyPods 的实现，不是"画出来的像玻璃"。
 *
 * 对照源码：
 * - {@code top.yukonga.miuix.kmp.blur.internal.BlurEffect}      → RenderEffect 高斯模糊
 * - {@code dev.sonypods.ui.components.liquid.Vibrancy}           → 饱和度 1.5 的 colorControls
 * - {@code dev.sonypods.ui.components.liquid.Lens}               → 圆角矩形 SDF 折射 shader
 * - {@code top.yukonga.miuix.kmp.blur.internal.Shaders}          → BloomStroke 边缘高光 shader
 * - {@code dev.sonypods.ui.components.liquid.InnerShadow}        → 内阴影
 *
 * 取样顺序与 SonyPods 的 drawBackdrop effects 一致：
 * {@code vibrancy() → blur(4dp) → lens(24dp, 24dp)}，再叠 surfaceContainer 40% 底色。
 *
 * API 分级：模糊（RenderEffect）31+；折射与高光（RuntimeShader）33+。
 * 低版本保留模糊 + 渐变高光降级，与 miuix 的 isRuntimeShaderSupported() 分支一致。
 */
final class LiquidGlass {

    private LiquidGlass() {
    }

    // ---------------------------------------------------------------- 能力分级

    static boolean gpuBlur() {
        return Build.VERSION.SDK_INT >= 31;
    }

    static boolean shaders() {
        return Build.VERSION.SDK_INT >= 33;
    }

    // ---------------------------------------------------------------- 管线入口

    /** 一条玻璃带：把 sample 按 effects 链渲染到 canvas 的 rect 区域。 */
    static void drawGlass(Canvas canvas, Bitmap sample, Rect src, RectF dst,
                          float blurPx, float lensHeightPx, float lensAmountPx,
                          float cornerRadiusPx, boolean dispersion) {
        drawGlass(canvas, sample, src, dst, blurPx, lensHeightPx, lensAmountPx,
                cornerRadiusPx, dispersion, 0f);
    }

    /**
     * padPx：内容四周的采样余量（Kyant0 的 {@code padding = maxOf(padding, 40.dp)}）。
     * src / dst 都是「形状 + pad」的外接矩形；lens 用 offset = -pad 折射采样
     * 边缘外的真实内容——没有这圈余量，边缘折射会采到空白，玻璃感就没了。
     */
    static void drawGlass(Canvas canvas, Bitmap sample, Rect src, RectF dst,
                          float blurPx, float lensHeightPx, float lensAmountPx,
                          float cornerRadiusPx, boolean dispersion, float padPx) {
        drawGlass(canvas, sample, src, null, dst, blurPx, lensHeightPx, lensAmountPx,
                cornerRadiusPx, dispersion, padPx);
    }

    /**
     * overlay：与 sample 同坐标系、同尺寸的附加层（Kyant0 的 tabsBackdrop ——
     * 一层只参与折射、不直接显示的强调色图标）。放进去之后，指示器的透镜里
     * 才会出现"被放大的图标"，这是液态玻璃最标志性的观感。
     */
    static void drawGlass(Canvas canvas, Bitmap sample, Rect src,
                          Bitmap overlay, RectF dst,
                          float blurPx, float lensHeightPx, float lensAmountPx,
                          float cornerRadiusPx, boolean dispersion, float padPx) {
        if (sample == null || sample.isRecycled()) {
            return;
        }
        if (!gpuBlur()) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            p.setColorFilter(vibrancyFilter());
            canvas.drawBitmap(sample, src, dst, p);
            if (overlay != null && !overlay.isRecycled()) {
                canvas.drawBitmap(overlay, null, dst, p);
            }
            return;
        }

        int w = Math.max(1, Math.round(dst.width()));
        int h = Math.max(1, Math.round(dst.height()));
        float shapeW = Math.max(1f, dst.width() - padPx * 2f);
        float shapeH = Math.max(1f, dst.height() - padPx * 2f);

        // 链：blur → lens（外层 lens 采样内层 blur 的结果）。
        // 关键：用 Paint.setRenderEffect 而不是 RenderNode.setRenderEffect ——
        // 后者在这台机器的硬件画布上会被整个忽略（实测 40dp 模糊都毫无效果）。
        RenderEffect blur = blurPx > 0f
                ? RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP)
                : null;
        RenderEffect chain;
        if (shaders() && lensHeightPx > 0f && lensAmountPx > 0f) {
            RuntimeShader lens = new RuntimeShader(
                    dispersion ? LENS_DISPERSION_SHADER : LENS_SHADER);
            lens.setFloatUniform("size", shapeW, shapeH);
            lens.setFloatUniform("offset", -padPx, -padPx);
            float r = Math.min(cornerRadiusPx, Math.min(shapeW, shapeH) * 0.5f);
            lens.setFloatUniform("cornerRadii", r, r, r, r);
            lens.setFloatUniform("refractionHeight", lensHeightPx);
            lens.setFloatUniform("refractionAmount", -lensAmountPx);
            lens.setFloatUniform("depthEffect", dispersion ? 1f : 0f);
            if (dispersion) {
                lens.setFloatUniform("chromaticAberration", 1f);
            }
            RenderEffect lensFx = RenderEffect.createRuntimeShaderEffect(lens, "content");
            chain = blur == null ? lensFx : RenderEffect.createChainEffect(lensFx, blur);
        } else {
            chain = blur;
        }

        Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        glass.setColorFilter(vibrancyFilter());

        // ---- 折射：手工网格变形（不依赖 RenderEffect）----
        // 本机硬件画布会忽略 RenderNode/Paint 上的 RenderEffect，但 drawBitmapMesh
        // 是普通绘制指令，一定能用。做法与 Kyant0 的 lens shader 一致：
        // 靠近边缘的顶点按 circleMap 曲线朝内偏移，形成"内容被吸进玻璃"的形变。
        if (lensHeightPx > 0f && lensAmountPx > 0f) {
            Bitmap flat = meshBitmap(w, h);
            Canvas fc = new Canvas(flat);
            fc.drawBitmap(sample, src, new Rect(0, 0, w, h), glass);
            if (overlay != null && !overlay.isRecycled()) {
                Paint op = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                fc.drawBitmap(overlay, src, new Rect(0, 0, w, h), op);
            }
            drawRefracted(canvas, flat, dst, w, h, shapeW, shapeH,
                    Math.min(cornerRadiusPx, Math.min(shapeW, shapeH) * 0.5f),
                    lensHeightPx, lensAmountPx, padPx);
            return;
        }

        RenderNode node = new RenderNode("liquidGlass");
        node.setPosition(0, 0, w, h);
        Canvas rc = node.beginRecording();
        rc.drawBitmap(sample, src, new Rect(0, 0, w, h), glass);
        if (overlay != null && !overlay.isRecycled()) {
            rc.drawBitmap(overlay, src, new Rect(0, 0, w, h),
                    new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        }
        node.endRecording();
        if (blurPx > 0f) {
            node.setRenderEffect(RenderEffect.createBlurEffect(
                    blurPx, blurPx, Shader.TileMode.CLAMP));
        }
        canvas.save();
        canvas.translate(dst.left, dst.top);
        canvas.drawRenderNode(node);
        canvas.restore();
    }

    /** 网格变形用的临时位图（按尺寸复用，避免每帧分配近 1MB）。 */
    private static final ThreadLocal<Bitmap> MESH = new ThreadLocal<>();

    private static Bitmap meshBitmap(int w, int h) {
        Bitmap b = MESH.get();
        if (b == null || b.isRecycled() || b.getWidth() != w || b.getHeight() != h) {
            if (b != null && !b.isRecycled()) {
                b.recycle();
            }
            b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            MESH.set(b);
        } else {
            b.eraseColor(0);
        }
        return b;
    }

    /** 圆角矩形 SDF（与 shader 中 sdRoundedRect 同式）。 */
    private static float sdRoundRect(float x, float y, float hx, float hy, float r) {
        float qx = Math.abs(x) - (hx - r);
        float qy = Math.abs(y) - (hy - r);
        float ox = Math.max(qx, 0f);
        float oy = Math.max(qy, 0f);
        float outside = (float) Math.sqrt(ox * ox + oy * oy) - r;
        float inside = Math.min(Math.max(qx, qy), 0f);
        return outside + inside;
    }

    /**
     * 把 flat 位图按 lens 曲线做网格变形后画到 dst。
     * 顶点位移公式与 LENS_SHADER 的 main() 完全一致，只是从 GPU 搬到 CPU。
     */
    private static void drawRefracted(Canvas canvas, Bitmap flat, RectF dst, int w, int h,
                                      float shapeW, float shapeH, float r,
                                      float lensH, float lensAmount, float pad) {
        final int mw = 96;
        final int mh = 16;
        // 位移上限：不超过一个格距，否则网格顶点互相交叉，画面会出现条纹
        float cellW = (float) w / mw;
        float cellH = (float) h / mh;
        lensAmount = Math.min(Math.abs(lensAmount), Math.min(cellW, cellH) * 0.9f);
        float[] verts = new float[(mw + 1) * (mh + 1) * 2];
        float hx = shapeW * 0.5f;
        float hy = shapeH * 0.5f;
        int k = 0;
        for (int j = 0; j <= mh; j++) {
            float y = h * (float) j / mh;
            for (int i = 0; i <= mw; i++) {
                float x = w * (float) i / mw;
                float cx = x - pad - hx;
                float cy = y - pad - hy;
                float sd = sdRoundRect(cx, cy, hx, hy, r);
                // 只形变形状内部的边缘一圈；形状外的顶点必须钉死，否则会被
                // 反向拉伸成条纹（之前 m4/m5 那些竖条纹就是这么来的）
                if (sd <= 0f && -sd < lensH) {
                    float t = 1f + sd / lensH;              // 1.0 - (-sd)/h
                    t = Math.max(0f, Math.min(1f, t));
                    float d = (1f - (float) Math.sqrt(Math.max(0f, 1f - t * t))) * lensAmount;
                    // SDF 梯度（向外），与 gradSdRoundedRect 同式
                    float qx = Math.abs(cx) - (hx - r);
                    float qy = Math.abs(cy) - (hy - r);
                    float gx;
                    float gy;
                    if (qx >= 0f || qy >= 0f) {
                        float ax = Math.max(qx, 0f);
                        float ay = Math.max(qy, 0f);
                        float len = (float) Math.sqrt(ax * ax + ay * ay);
                        if (len < 1e-5f) {
                            len = 1e-5f;
                        }
                        gx = Math.signum(cx) * ax / len;
                        gy = Math.signum(cy) * ay / len;
                    } else {
                        float step = qx >= qy ? 1f : 0f;
                        gx = Math.signum(cx) * step;
                        gy = Math.signum(cy) * (1f - step);
                    }
                    x += d * gx;
                    y += d * gy;
                }
                verts[k++] = x;
                verts[k++] = y;
            }
        }
        canvas.save();
        canvas.translate(dst.left, dst.top);
        canvas.drawBitmapMesh(flat, mw, mh, verts, 0, null, 0,
                new Paint(Paint.FILTER_BITMAP_FLAG));
        canvas.restore();
    }

    /** vibrancy()：colorControls(saturation = 1.5)。 */
    static ColorMatrixColorFilter vibrancyFilter() {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(1.3f);
        return new ColorMatrixColorFilter(m);
    }

    // ---------------------------------------------------------------- BloomStroke 高光

    /**
     * 边缘镜面高光。参数取 SonyPods 的 iosIndicatorSpecular：
     * width 1dp、white 0.12、innerBlur 2dp、dualPeak、
     * 主光 (0.5, -0.3, -0.05)、次光 (0.5, 0.8, -0.5)。
     */
    static void drawHighlight(Canvas canvas, RectF rect, float cornerRadiusPx,
                              float strokeWidthPx, float innerBlurPx, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        if (!shaders()) {
            // 降级：顶部亮、底部淡的描边
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(strokeWidthPx);
            p.setShader(new android.graphics.LinearGradient(0, rect.top, 0, rect.bottom,
                    new int[] {Theme.alpha(0xFFFFFFFF, 0.55f * alpha), Theme.alpha(0xFFFFFFFF, 0.10f * alpha)},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, p);
            return;
        }

        RuntimeShader s = new RuntimeShader(BLOOM_STROKE_SHADER);
        float w = rect.width();
        float h = rect.height();
        s.setFloatUniform("halfView", w * 0.5f, h * 0.5f);
        s.setFloatUniform("halfViewFloor", (float) Math.floor(w * 0.5f), (float) Math.floor(h * 0.5f));
        float r = Math.min(cornerRadiusPx, Math.min(w, h) * 0.5f);
        s.setFloatUniform("cornerRadii", r, r, r, r);
        s.setFloatUniform("strokeWidth", strokeWidthPx);
        s.setFloatUniform("innerBlurRadius", innerBlurPx);
        s.setFloatUniform("innerBlurRadiusSq", innerBlurPx * innerBlurPx);
        s.setFloatUniform("highlightAlpha", alpha);
        s.setColorUniform("strokeColor", 0xFFFFFFFF);
        s.setFloatUniform("strokeAlphaMul", 0.12f);

        // 主光：LightPosition(0.5, -0.3, -0.05)，相对 LIGHT_REF(0.5, 0.7)
        setLight(s, "1", 0.5f, -0.3f, -0.05f, 1f);
        // 次光：LightPosition(0.5, 0.8, -0.5)
        setLight(s, "2", 0.5f, 0.8f, -0.5f, 0.4f);

        // BloomStroke 是叠加发光（源码 blendMode = BlendMode.Plus）：
        // shader 输出 (rgb, 1.0)，黑色无光区靠"加法合成"变成不叠加。
        // 加法必须放在 saveLayer 的合成 Paint 上——layer 内 SRC_OVER 画 shader，
        // restore 时以 ADD 贴回，无光区的 (0,0,0,1) 加 0 等于不变。
        Paint layerPaint = new Paint();
        layerPaint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.ADD));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(s);
        canvas.saveLayer(rect.left, rect.top, rect.right, rect.bottom, layerPaint);
        canvas.translate(rect.left, rect.top);
        canvas.drawRect(0, 0, w, h, p);
        canvas.restore();
    }

    /** applyLightUniforms 的移植（dualPeak：无 axis）。 */
    private static void setLight(RuntimeShader s, String suffix,
                                 float x, float y, float z, float intensity) {
        float dx = x - 0.5f;   // LIGHT_REF_X
        float dy = y - 0.7f;   // LIGHT_REF_Y
        float dz = z;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) {
            len = 1e-6f;
        }
        s.setFloatUniform("lightDir" + suffix, dx / len, dy / len, dz / len);
        s.setColorUniform("lightColor" + suffix, 0xFFFFFFFF);
        s.setFloatUniform("lightIntensity" + suffix, intensity);
    }

    // ---------------------------------------------------------------- SDF 边缘高光（Kyant0 HighlightStyle.Default）

    /**
     * Kyant0 的默认高光：沿形状轮廓 1dp 描边，亮度 = |dot(SDF梯度, 光方向)|^falloff，
     * 叠加混合。光角 45°（上左/下右边缘亮），falloff = 1。
     * Highlight(width=0.5dp) → 描边 1dp；style color white 0.5。
     */
    static void drawSdfHighlight(Canvas canvas, RectF rect, float cornerRadiusPx,
                                 float strokeWidthPx, float alpha, float angleDeg) {
        if (alpha <= 0.01f || strokeWidthPx <= 0f) {
            return;
        }
        // 统一走普通渐变描边：RuntimeShader 版本的 RenderEffect 在本机硬件画布上
        // 完全不生效（实测 40dp 模糊都无变化），只有这条路径能稳定画出亮边。
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(strokeWidthPx);
        p.setShader(new android.graphics.LinearGradient(0, rect.top, 0, rect.bottom,
                new int[] {
                        Theme.alpha(0xFFFFFFFF, 0.9f * alpha),
                        Theme.alpha(0xFFFFFFFF, 0.55f * alpha),
                        Theme.alpha(0xFFFFFFFF, 0.18f * alpha)},
                new float[] {0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, p);
    }

    /** 跟随手指的白色辉光（Kyant0 InteractiveHighlight）：平铺白 0.08 + 径向白 0.15。 */
    static void drawInteractiveHighlight(Canvas canvas, RectF barRect,
                                         float cx, float cy, float progress) {
        if (progress <= 0.01f) {
            return;
        }
        Paint flat = new Paint(Paint.ANTI_ALIAS_FLAG);
        flat.setColor(Theme.alpha(0xFFFFFFFF, 0.08f * progress));
        Paint layerPaint = new Paint();
        layerPaint.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.ADD));
        canvas.saveLayer(barRect.left, barRect.top, barRect.right, barRect.bottom, layerPaint);
        canvas.drawRect(barRect, flat);
        float radius = Math.min(barRect.width(), barRect.height()) * 1.5f;
        Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        glow.setShader(new android.graphics.RadialGradient(
                cx, cy, radius,
                new int[] {Theme.alpha(0xFFFFFFFF, 0.15f * progress), 0x00FFFFFF},
                new float[] {0.5f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, glow);
        canvas.restore();
    }

    /** InnerShadow：radius 24dp、offset(0, radius)、black 0.15 —— 用投影方式近似内部渐变。 */
    static void drawInnerShadow(Canvas canvas, RectF rect, float cornerRadiusPx,
                                float radiusPx, float offsetY, int color, float alpha) {
        if (alpha <= 0.01f || radiusPx <= 0f) {
            return;
        }
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(radiusPx);
        p.setShader(new android.graphics.LinearGradient(0, rect.top + offsetY - radiusPx, 0, rect.top + offsetY + radiusPx * 0.5f,
                new int[] {0x00000000, Theme.alpha(color, alpha), Theme.alpha(color, alpha * 0.4f)},
                new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        RectF inset = new RectF(rect);
        inset.inset(radiusPx * 0.5f, radiusPx * 0.5f);
        canvas.save();
        canvas.clipRect(rect);
        canvas.drawRoundRect(inset, cornerRadiusPx, cornerRadiusPx, p);
        canvas.restore();
    }

    // ---------------------------------------------------------------- 投影

    /**
     * dropShadow 的 GPU 版：形状填充后整体高斯模糊（RenderEffect），
     * 比 BlurMaskFilter 可靠（硬件加速下 MaskFilter 会被忽略）。
     */
    static void drawSoftShadow(Canvas canvas, RectF rect, float cornerRadiusPx,
                               float blurPx, int color, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        if (!gpuBlur()) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Theme.alpha(color, alpha));
            for (int i = 4; i >= 1; i--) {
                float grow = blurPx * i / 4f;
                canvas.drawRoundRect(rect.left - grow, rect.top - grow,
                        rect.right + grow, rect.bottom + grow,
                        cornerRadiusPx + grow, cornerRadiusPx + grow, p);
            }
            return;
        }
        int w = Math.max(1, Math.round(rect.width() + blurPx * 4));
        int h = Math.max(1, Math.round(rect.height() + blurPx * 4));
        RenderNode node = new RenderNode("glassShadow");
        node.setPosition(0, 0, w, h);
        Canvas rc = node.beginRecording();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Theme.alpha(color, alpha));
        float inset = blurPx * 2;
        rc.drawRoundRect(new RectF(inset, inset, inset + rect.width(), inset + rect.height()),
                cornerRadiusPx, cornerRadiusPx, p);
        node.endRecording();
        node.setRenderEffect(RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP));
        canvas.save();
        canvas.translate(rect.left - inset, rect.top - inset);
        canvas.drawRenderNode(node);
        canvas.restore();
    }

    // ---------------------------------------------------------------- 采样

    /**
     * LayerBackdrop 的 View 版：把 source 里被 rect 覆盖（外扩 padding）的区域
     * 画进 bitmap。padding 取 40dp —— 与 drawBackdrop 的
     * {@code padding = maxOf(padding, 40.dp.toPx())} 一致，给 lens 折射留出采样余量。
     */
    static Bitmap capture(View source, int[] barLoc, Rect region, int outW, int outH) {
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, outW), Math.max(1, outH), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        int[] srcLoc = new int[2];
        source.getLocationInWindow(srcLoc);
        float sx = (float) outW / region.width();
        float sy = (float) outH / region.height();
        c.scale(sx, sy);
        c.translate(srcLoc[0] - region.left, srcLoc[1] - region.top);
        c.clipRect(region);
        source.draw(c);
        return bmp;
    }

    // ================================================================= AGSL

    /**
     * 圆角矩形 SDF 折射 lens（无色散）。移植自
     * dev.sonypods.ui.components.liquid.Lens.kt 的 ROUNDED_RECT_REFRACTION_SHADER。
     */
    static final String LENS_SHADER =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + "float radiusAt(float2 coord, float4 radii) {\n"
            + "    if (coord.x >= 0.0) {\n"
            + "        if (coord.y <= 0.0) return radii.y;\n"
            + "        else return radii.z;\n"
            + "    } else {\n"
            + "        if (coord.y <= 0.0) return radii.x;\n"
            + "        else return radii.w;\n"
            + "    }\n"
            + "}\n"
            + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n"
            + "float circleMap(float x) {\n"
            + "    return 1.0 - sqrt(1.0 - x * x);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = (coord + offset) - halfSize;\n"
            + "    float radius = radiusAt(centeredCoord, cornerRadii);\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            + "    if (-sd >= refractionHeight) {\n"
            + "        return content.eval(coord);\n"
            + "    }\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)\n"
            + "            + depthEffect * normalize(centeredCoord));\n"
            + "    float2 refractedCoord = coord + d * grad;\n"
            + "    return content.eval(refractedCoord);\n"
            + "}\n";

    /** 带色散的 lens（指示器按下时用），chromaticAberration = 0.5。 */
    static final String LENS_DISPERSION_SHADER =
            "uniform shader content;\n"
            + "uniform float2 size;\n"
            + "uniform float2 offset;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float refractionHeight;\n"
            + "uniform float refractionAmount;\n"
            + "uniform float depthEffect;\n"
            + "uniform float chromaticAberration;\n"
            + "float radiusAt(float2 coord, float4 radii) {\n"
            + "    if (coord.x >= 0.0) {\n"
            + "        if (coord.y <= 0.0) return radii.y;\n"
            + "        else return radii.z;\n"
            + "    } else {\n"
            + "        if (coord.y <= 0.0) return radii.x;\n"
            + "        else return radii.w;\n"
            + "    }\n"
            + "}\n"
            + "float sdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    float outside = length(max(cornerCoord, 0.0)) - radius;\n"
            + "    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);\n"
            + "    return outside + inside;\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n"
            + "float circleMap(float x) {\n"
            + "    return 1.0 - sqrt(1.0 - x * x);\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = (coord + offset) - halfSize;\n"
            + "    float radius = radiusAt(centeredCoord, cornerRadii);\n"
            + "    float sd = sdRoundedRect(centeredCoord, halfSize, radius);\n"
            + "    if (-sd >= refractionHeight) {\n"
            + "        return content.eval(coord);\n"
            + "    }\n"
            + "    sd = min(sd, 0.0);\n"
            + "    float d = circleMap(1.0 - -sd / refractionHeight) * refractionAmount;\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = normalize(gradSdRoundedRect(centeredCoord, halfSize, gradRadius)\n"
            + "            + depthEffect * normalize(centeredCoord));\n"
            + "    float2 refractedCoord = coord + d * grad;\n"
            + "    float dispersionIntensity = chromaticAberration\n"
            + "            * ((centeredCoord.x * centeredCoord.y) / (halfSize.x * halfSize.y));\n"
            + "    float2 dispersedCoord = d * grad * dispersionIntensity;\n"
            + "    half4 color = half4(0.0);\n"
            + "    half4 red = content.eval(refractedCoord + dispersedCoord);\n"
            + "    color.r += red.r / 3.5;\n"
            + "    color.a += red.a / 7.0;\n"
            + "    half4 orange = content.eval(refractedCoord + dispersedCoord * (2.0 / 3.0));\n"
            + "    color.r += orange.r / 3.5;\n"
            + "    color.g += orange.g / 7.0;\n"
            + "    color.a += orange.a / 7.0;\n"
            + "    half4 yellow = content.eval(refractedCoord + dispersedCoord * (1.0 / 3.0));\n"
            + "    color.r += yellow.r / 3.5;\n"
            + "    color.g += yellow.g / 3.5;\n"
            + "    color.a += yellow.a / 7.0;\n"
            + "    half4 green = content.eval(refractedCoord);\n"
            + "    color.g += green.g / 3.5;\n"
            + "    color.a += green.a / 7.0;\n"
            + "    half4 cyan = content.eval(refractedCoord - dispersedCoord * (1.0 / 3.0));\n"
            + "    color.g += cyan.g / 3.5;\n"
            + "    color.b += cyan.b / 3.0;\n"
            + "    color.a += cyan.a / 7.0;\n"
            + "    half4 blue = content.eval(refractedCoord - dispersedCoord * (2.0 / 3.0));\n"
            + "    color.b += blue.b / 3.0;\n"
            + "    color.a += blue.a / 7.0;\n"
            + "    half4 purple = content.eval(refractedCoord - dispersedCoord);\n"
            + "    color.r += purple.r / 7.0;\n"
            + "    color.b += purple.b / 3.0;\n"
            + "    color.a += purple.a / 7.0;\n"
            + "    return color;\n"
            + "}\n";

    /**
     * Kyant0 DefaultHighlightShaderString：沿 SDF 梯度方向的轮廓高光。
     * 亮度 = pow(abs(dot(gradient, normal)), falloff)，normal 由 angle 决定（45°）。
     */
    static final String SDF_HIGHLIGHT_SHADER =
            "uniform float2 size;\n"
            + "uniform float4 cornerRadii;\n"
            + "layout(color) uniform half4 color;\n"
            + "uniform float angle;\n"
            + "uniform float falloff;\n"
            + "uniform float alphaMul;\n"
            + "float radiusAt(float2 coord, float4 radii) {\n"
            + "    if (coord.x >= 0.0) {\n"
            + "        if (coord.y <= 0.0) return radii.y;\n"
            + "        else return radii.z;\n"
            + "    } else {\n"
            + "        if (coord.y <= 0.0) return radii.x;\n"
            + "        else return radii.w;\n"
            + "    }\n"
            + "}\n"
            + "float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {\n"
            + "    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));\n"
            + "    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {\n"
            + "        return sign(coord) * normalize(max(cornerCoord, 0.0));\n"
            + "    } else {\n"
            + "        float gradX = step(cornerCoord.y, cornerCoord.x);\n"
            + "        return sign(coord) * float2(gradX, 1.0 - gradX);\n"
            + "    }\n"
            + "}\n"
            + "half4 main(float2 coord) {\n"
            + "    float2 halfSize = size * 0.5;\n"
            + "    float2 centeredCoord = coord - halfSize;\n"
            + "    float radius = radiusAt(centeredCoord, cornerRadii);\n"
            + "    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));\n"
            + "    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);\n"
            + "    float2 normal = float2(cos(angle), sin(angle));\n"
            + "    float d = dot(grad, normal);\n"
            + "    float intensity = pow(abs(d), falloff);\n"
            + "    return color * intensity * alphaMul;\n"
            + "}\n";

    /**
     * BloomStroke（dualPeak）—— 圆角矩形 SDF + 半球法线 + 双光源。
     * 移植自 top.yukonga.miuix.kmp.blur.internal.Shaders.kt 的
     * buildBloomStrokeShader(dualPeak = true)。
     */
    static final String BLOOM_STROKE_SHADER =
            "uniform float2 halfView;\n"
            + "uniform float2 halfViewFloor;\n"
            + "uniform float4 cornerRadii;\n"
            + "uniform float strokeWidth;\n"
            + "uniform float innerBlurRadius;\n"
            + "uniform float innerBlurRadiusSq;\n"
            + "uniform float highlightAlpha;\n"
            + "layout(color) uniform half4 strokeColor;\n"
            + "uniform float strokeAlphaMul;\n"
            + "uniform float3 lightDir1;\n"
            + "layout(color) uniform half4 lightColor1;\n"
            + "uniform float lightIntensity1;\n"
            + "uniform float3 lightDir2;\n"
            + "layout(color) uniform half4 lightColor2;\n"
            + "uniform float lightIntensity2;\n"
            + "float pickRadius(float2 fragCoord, float4 radii) {\n"
            + "    float2 up = fragCoord.y < halfView.y ? radii.xy : radii.zw;\n"
            + "    return fragCoord.x < halfView.x ? up.x : up.y;\n"
            + "}\n"
            + "float roundedBoxSDF(float2 pos, float2 halfSize, float radius) {\n"
            + "    radius = min(radius, min(halfSize.x, halfSize.y));\n"
            + "    float2 d = pos - halfSize + radius;\n"
            + "    return length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - radius;\n"
            + "}\n"
            + "float3 getNormal(float2 fragCoord, float sdf, float R) {\n"
            + "    float2 xy = fragCoord - halfViewFloor;\n"
            + "    float2 xy_a = abs(xy);\n"
            + "    float t = smoothstep(-innerBlurRadius, 0.0, sdf);\n"
            + "    float z = sqrt(max(innerBlurRadiusSq - t * t, 0.0));\n"
            + "    float3 coord = float3(xy_a, -z);\n"
            + "    float2 corner = halfView - R;\n"
            + "    corner.x = min(corner.x, xy_a.x);\n"
            + "    corner.y = min(corner.y, xy_a.y);\n"
            + "    float2 dir = normalize(coord.xy - corner.xy);\n"
            + "    corner += dir * (R - innerBlurRadius);\n"
            + "    if (any(lessThan(xy_a, corner))) {\n"
            + "        return float3(0.0, 0.0, -1.0);\n"
            + "    }\n"
            + "    float2 signal = sign(xy);\n"
            + "    float3 n = normalize(coord - float3(corner, 0.0));\n"
            + "    n.xy *= signal;\n"
            + "    return n;\n"
            + "}\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float2 xy = abs(fragCoord - halfView);\n"
            + "    float originRadius = pickRadius(fragCoord, cornerRadii);\n"
            + "    float R = max(originRadius, innerBlurRadius);\n"
            + "    if (all(lessThan(xy, halfView - R))) {\n"
            + "        return half4(0.0);\n"
            + "    }\n"
            + "    float sdf = roundedBoxSDF(xy, halfView, originRadius);\n"
            + "    half outMask = half(smoothstep(0.0, -1.0, sdf));\n"
            + "    float strokeAlpha = smoothstep(-strokeWidth, -strokeWidth + 1.0, sdf);\n"
            + "    half3 rgb = strokeColor.rgb * half(strokeAlphaMul * strokeAlpha * strokeAlpha);\n"
            + "    float3 n = getNormal(fragCoord, sdf, R);\n"
            + "    float l1 = dot(n.xy, lightDir1.xy);\n"
            + "    rgb += half(l1 * l1 * lightIntensity1) * lightColor1.rgb;\n"
            + "    float l2 = dot(n.xy, lightDir2.xy);\n"
            + "    rgb += half(l2 * l2 * lightIntensity2) * lightColor2.rgb;\n"
            + "    return half4(rgb * half(highlightAlpha), 1.0) * outMask;\n"
            + "}\n";
}
