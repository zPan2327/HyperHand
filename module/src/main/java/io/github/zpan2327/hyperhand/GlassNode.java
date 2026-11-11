package io.github.zpan2327.hyperhand;

import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

/**
 * 「玻璃图形」：把背后的背景拍进一张 {@link RenderNode}，挂一条 {@link RenderEffect} 链
 * （高斯模糊 → 混合调色），再用图形的 alpha 当蒙版画出来。
 *
 * <p>这就是 miuix {@code textureBlur} 在 Android 上的实际做法，SonyPods / HyperIsland 的
 * 关于页 logo 与字标都走这条路（前者直接调 miuix `textureBlur`，后者自己用
 * {@code RenderEffect + RuntimeShader}）：Compose 的 graphicsLayer 底下是 RenderNode，
 * RenderEffect 链挂在它上面，模糊与调色全在 GPU 完成，<b>不经过 Bitmap 采样</b>，
 * 所以图形会实时反映背后的背景，颜色随背景一起变。
 *
 * <p>链的顺序照搬 miuix 的 {@code textureBlurEffect}：先 blur，再 blendColors。
 * 混色层就是 SonyPods {@code aboutArtworkBlendColors} 传的那三条（miuix
 * {@code BlurDefaults.BlurBlendMode} 的 ColorBurn/ColorDodge、LinearLight、Lab）：
 * <pre>
 *   浅色： (0xCC4A4A4A, ColorBurn)  (0xFF4F4F4F, LinearLight)  (0xFF1AF200, Lab)
 *   深色： (0xE6A1A1A1, ColorDodge) (0x4DE6E6E6, LinearLight)  (0xFF1AF500, Lab)
 * </pre>
 * 公式逐行移植 miuix {@code Shaders.kt} 的 {@code mi_blend_color_burn /
 * blendLinearLight / labColor}（含它的预乘/反预乘语义与 Lab 明度映射
 * {@code L' = (n-m)·L + 100·m}）—— 上一版用「明度归一 LIFT」近似第三层，
 * 结果把图形压成灰紫、字标发浅，正是「关于页 logo 和应用名显示错误」的根源。
 */
final class GlassNode {

    private static final String FRAG = ""
            + "uniform shader content;\n"
            // 第 1 层：ColorBurn（浅）/ ColorDodge（深），颜色为预乘值
            + "uniform float3 c1;\n"
            + "uniform float a1;\n"
            + "uniform float isDodge;\n"
            // 第 2 层：LinearLight（直通色 + alpha）
            + "uniform float3 c2;\n"
            + "uniform float a2;\n"
            // 第 3 层：Lab，m = c3.r、n = c3.g（miuix 的 labColor 语义）
            + "uniform float3 c3;\n"
            + "uniform float a3;\n"
            + "\n"
            // ---- miuix Shaders.kt 原样移植 ----\n"
            + "float guarded_divide(float a, float b) {\n"
            + "  return abs(b) < 1e-6 ? 0.0 : a / b;\n"
            + "}\n"
            + "float color_burn_component(float2 s, float2 d) {\n"
            + "  float delta = abs(s.x) >= 1e-6\n"
            + "      ? d.y - min(d.y, guarded_divide((d.y - d.x) * s.y, s.x))\n"
            + "      : 0.0;\n"
            + "  return delta * s.y + s.x * (1.0 - d.y) + d.x * (1.0 - s.y);\n"
            + "}\n"
            + "float color_dodge_component(float2 s, float2 d) {\n"
            + "  float delta = min(d.y, abs(s.y - s.x) >= 1e-6\n"
            + "      ? guarded_divide(d.x * s.y, s.y - s.x) : d.y);\n"
            + "  return delta * s.y + s.x * (1.0 - d.y) + d.x * (1.0 - s.y);\n"
            + "}\n"
            + "float llight(float base, float blend) {\n"
            + "  return blend < 0.5 ? max(base + 2.0 * blend - 1.0, 0.0)\n"
            + "                     : min(base + 2.0 * (blend - 0.5), 1.0);\n"
            + "}\n"
            + "float3 llight3(float3 base, float3 blend) {\n"
            + "  return float3(llight(base.r, blend.r), llight(base.g, blend.g),\n"
            + "                llight(base.b, blend.b));\n"
            + "}\n"
            // Lab（D65）：rgb2xyz -> xyz2lab -> lab2xyz -> xyz2rgb\n"
            + "float3 rgb2xyz(float3 c) {\n"
            + "  c.r = c.r > 0.04045 ? pow((c.r + 0.055) / 1.055, 2.4) : c.r / 12.92;\n"
            + "  c.g = c.g > 0.04045 ? pow((c.g + 0.055) / 1.055, 2.4) : c.g / 12.92;\n"
            + "  c.b = c.b > 0.04045 ? pow((c.b + 0.055) / 1.055, 2.4) : c.b / 12.92;\n"
            + "  c *= 100.0;\n"
            + "  return float3(\n"
            + "      c.r * 0.4124 + c.g * 0.3576 + c.b * 0.1805,\n"
            + "      c.r * 0.2126 + c.g * 0.7152 + c.b * 0.0722,\n"
            + "      c.r * 0.0193 + c.g * 0.1192 + c.b * 0.9505);\n"
            + "}\n"
            + "float3 xyz2lab(float3 c) {\n"
            + "  float3 w = float3(95.047, 100.0, 108.883);\n"
            + "  c /= w;\n"
            + "  c.x = c.x > 0.008856 ? pow(c.x, 1.0 / 3.0) : 7.787 * c.x + 16.0 / 116.0;\n"
            + "  c.y = c.y > 0.008856 ? pow(c.y, 1.0 / 3.0) : 7.787 * c.y + 16.0 / 116.0;\n"
            + "  c.z = c.z > 0.008856 ? pow(c.z, 1.0 / 3.0) : 7.787 * c.z + 16.0 / 116.0;\n"
            + "  return float3(116.0 * c.y - 16.0, 500.0 * (c.x - c.y), 200.0 * (c.y - c.z));\n"
            + "}\n"
            + "float3 rgb2lab(float3 c) { return xyz2lab(rgb2xyz(c)); }\n"
            + "float3 lab2xyz(float3 lab) {\n"
            + "  float y = (lab.x + 16.0) / 116.0;\n"
            + "  float x = lab.y / 500.0 + y;\n"
            + "  float z = y - lab.z / 200.0;\n"
            + "  y = pow(y, 3.0) > 0.008856 ? pow(y, 3.0) : (y - 16.0 / 116.0) / 7.787;\n"
            + "  x = pow(x, 3.0) > 0.008856 ? pow(x, 3.0) : (x - 16.0 / 116.0) / 7.787;\n"
            + "  z = pow(z, 3.0) > 0.008856 ? pow(z, 3.0) : (z - 16.0 / 116.0) / 7.787;\n"
            + "  float3 w = float3(95.047, 100.0, 108.883);\n"
            + "  return float3(x * w.x, y * w.y, z * w.z);\n"
            + "}\n"
            + "float3 xyz2rgb(float3 xyz) {\n"
            + "  xyz /= 100.0;\n"
            + "  float r = xyz.x * 3.2406 + xyz.y * -1.5372 + xyz.z * -0.4986;\n"
            + "  float g = xyz.x * -0.9689 + xyz.y * 1.8758 + xyz.z * 0.0415;\n"
            + "  float b = xyz.x * 0.0557 + xyz.y * -0.2040 + xyz.z * 1.0570;\n"
            + "  r = r > 0.0031308 ? 1.055 * pow(r, 1.0 / 2.4) - 0.055 : 12.92 * r;\n"
            + "  g = g > 0.0031308 ? 1.055 * pow(g, 1.0 / 2.4) - 0.055 : 12.92 * g;\n"
            + "  b = b > 0.0031308 ? 1.055 * pow(b, 1.0 / 2.4) - 0.055 : 12.92 * b;\n"
            + "  return float3(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0));\n"
            + "}\n"
            + "float3 lab2rgb(float3 lab) { return xyz2rgb(lab2xyz(lab)); }\n"
            + "float3 labColor(float3 c, float m, float n) {\n"
            + "  float3 lab = rgb2lab(c);\n"
            + "  lab.x = (n - m) * lab.x + m * 100.0;\n"
            + "  return lab2rgb(lab);\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 xy) {\n"
            + "  half4 src = content.eval(xy);\n"
            + "  float a = src.a;\n"
            + "  if (a <= 0.0039) return half4(0.0);\n"
            // currentColor 全程按「预乘」流转，与 miuix MAIN_EXTENDED 一致\n"
            + "  float3 cp = float3(src.rgb);   // 预乘\n"
            + "  // ---- 第 1 层：ColorBurn / ColorDodge（标准族，预乘公式） ----\n"
            + "  float3 out1;\n"
            + "  out1.r = isDodge > 0.5\n"
            + "      ? color_dodge_component(float2(c1.r, a1), float2(cp.r, a))\n"
            + "      : color_burn_component(float2(c1.r, a1), float2(cp.r, a));\n"
            + "  out1.g = isDodge > 0.5\n"
            + "      ? color_dodge_component(float2(c1.g, a1), float2(cp.g, a))\n"
            + "      : color_burn_component(float2(c1.g, a1), float2(cp.g, a));\n"
            + "  out1.b = isDodge > 0.5\n"
            + "      ? color_dodge_component(float2(c1.b, a1), float2(cp.b, a))\n"
            + "      : color_burn_component(float2(c1.b, a1), float2(cp.b, a));\n"
            + "  float aOut = a1 + (1.0 - a1) * a;\n"
            + "  // ---- 第 2 层：LinearLight（自定义族，反预乘进、预乘出） ----\n"
            + "  float3 cur = aOut > 0.0001 ? out1 / aOut : float3(0.0);\n"
            + "  float3 l2 = llight3(cur, c2) * a2 + cur * (1.0 - a2);\n"
            + "  // ---- 第 3 层：Lab（mix(cur, labColor(cur, m, n), a3)） ----\n"
            + "  float3 l3 = mix(l2, labColor(l2, c3.r, c3.g), a3);\n"
            + "  return half4(l3 * aOut, aOut);\n"
            + "}\n";

    private final RenderNode node = new RenderNode("hh-glass");
    private final RenderEffect chain;
    private boolean usable;
    private int w;
    private int h;

    /** 图形四周多拍一圈，模糊核才不会把透明边吸进图形里。 */
    private final int pad;

    GlassNode(boolean night, float blurPx, int padPx) {
        this.pad = padPx;
        RenderEffect effect = null;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                RuntimeShader blend = new RuntimeShader(FRAG);
                if (night) {
                    // SonyPods aboutArtworkBlendColors(true)
                    // (0xE6A1A1A1, ColorDodge) —— 预乘：rgb 已乘 alpha
                    float a1 = 0xE6 / 255f;
                    blend.setFloatUniform("c1", 0xA1 / 255f * a1, 0xA1 / 255f * a1, 0xA1 / 255f * a1);
                    blend.setFloatUniform("a1", a1);
                    blend.setFloatUniform("isDodge", 1f);
                    // (0x4DE6E6E6, LinearLight)
                    float a2 = 0x4D / 255f;
                    blend.setFloatUniform("c2", 0xE6 / 255f, 0xE6 / 255f, 0xE6 / 255f);
                    blend.setFloatUniform("a2", a2);
                    // (0xFF1AF500, Lab)：m = r、n = g
                    blend.setFloatUniform("c3", 0x1A / 255f, 0xF5 / 255f, 0f);
                    blend.setFloatUniform("a3", 1f);
                } else {
                    // SonyPods aboutArtworkBlendColors(false)
                    float a1 = 0xCC / 255f;
                    blend.setFloatUniform("c1", 0x4A / 255f * a1, 0x4A / 255f * a1, 0x4A / 255f * a1);
                    blend.setFloatUniform("a1", a1);
                    blend.setFloatUniform("isDodge", 0f);
                    float a2 = 1f;
                    blend.setFloatUniform("c2", 0x4F / 255f, 0x4F / 255f, 0x4F / 255f);
                    blend.setFloatUniform("a2", a2);
                    blend.setFloatUniform("c3", 0x1A / 255f, 0xF2 / 255f, 0f);
                    blend.setFloatUniform("a3", 1f);
                }

                RenderEffect blur = RenderEffect.createBlurEffect(
                        blurPx, blurPx, Shader.TileMode.CLAMP);
                RenderEffect mix = RenderEffect.createRuntimeShaderEffect(blend, "content");
                // 先模糊再调色：chainEffect(outer, inner) = outer(inner(x))
                effect = RenderEffect.createChainEffect(mix, blur);
            } catch (Throwable t) {
                effect = null;
            }
        }
        chain = effect;
        usable = chain != null;
        if (usable) {
            node.setRenderEffect(chain);
        }
    }

    boolean usable() {
        return usable;
    }

    int pad() {
        return pad;
    }

    /**
     * 把 {@code backdrop} 在 (vx,vy) 处、尺寸 (nw,nh) 的那一块拍进节点。
     *
     * <p>坐标是 backdrop 自己的坐标系 —— 调用方传图形左上角减去 {@link #pad()}，
     * 这样节点像素 (pad,pad) 正好落在图形左上角。
     */
    void capture(View backdrop, int vx, int vy, int nw, int nh) {
        if (!usable || nw <= 0 || nh <= 0) {
            return;
        }
        if (node.getWidth() != nw || node.getHeight() != nh) {
            node.setPosition(0, 0, nw, nh);
        }
        android.graphics.RecordingCanvas rc = node.beginRecording(nw, nh);
        try {
            rc.translate(-vx, -vy);
            backdrop.draw(rc);
        } finally {
            node.endRecording();
        }
        w = nw;
        h = nh;
    }

    /** 在 (0,0)-(w,h) 画出来；调用方负责裁剪成图形。 */
    void draw(Canvas canvas) {
        if (!usable || w <= 0 || h <= 0) {
            return;
        }
        // drawRenderNode 的坐标是节点自己的绝对坐标，**不跟随 canvas 的 translate/clip**。
        // 液态底栏采样时会把整个页面 draw 进一张软件 Bitmap，那条路径上节点会被直接
        // 糊到画布原点 —— 结果就是 glass 胶囊里出现 hero 的紫色玻璃和 "HyperHand" 残影。
        // 软件画布一律不画，让调用方退回纯色。
        if (!canvas.isHardwareAccelerated()) {
            return;
        }
        canvas.drawRenderNode(node);
    }
}
