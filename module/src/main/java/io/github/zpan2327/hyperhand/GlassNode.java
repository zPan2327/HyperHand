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
 * <p>链的顺序照搬 miuix 的 {@code BlurEffectKt}：先 blur，再 blend；
 * 混合项取 SonyPods 传的那组 {@code BlurColors.blurColors(...)}：
 * {@code (0xCC4A4A4A, ColorBurn)} 与 {@code (0xFF4F4F4F, LinearLight)}。
 * 公式直接用 W3C 合成规范里那两条（miuix 的 AGSL 也是同一套）：
 * <pre>
 *   ColorBurn(b, s)  = s == 0 ? 0 : max(1 - (1-b)/s, 0)
 *   LinearLight(b, s)= s &lt; .5 ? max(b + 2s - 1, 0) : min(b + 2(s-.5), 1)
 * </pre>
 * 第三个 {@code (0xFF1AF200, Lab)} 项没法照搬 —— Lab 混合要先把两侧都转到 CIELAB
 * 再逐通道混，miuix 的实现藏在 11KB 的 AGSL 里且无法完整提取。它的作用是把前两项
 * 压得过暗的结果拉回可见区间，这里改用一个明度归一（{@link #LIFT}）替代。
 */
final class GlassNode {

    /** 结果明度上限。参考截图里 logo 主色约 #5A2350，v≈0.35，取同一档。 */
    private static final float LIFT_LIGHT = 0.36f;
    private static final float LIFT_DARK = 0.86f;

    private static final String FRAG = ""
            + "uniform shader content;\n"
            + "uniform float3 cBurn;\n"
            + "uniform float aBurn;\n"
            + "uniform float3 cLin;\n"
            + "uniform float aLin;\n"
            + "uniform float lift;\n"
            + "\n"
            + "float3 rgb2hsv(float3 c) {\n"
            + "  float4 K = float4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);\n"
            + "  float4 p = mix(float4(c.bg, K.wz), float4(c.gb, K.xy), step(c.b, c.g));\n"
            + "  float4 q = mix(float4(p.xyw, c.r), float4(c.r, p.yzx), step(p.x, c.r));\n"
            + "  float d = q.x - min(q.w, q.y);\n"
            + "  float e = 1.0e-10;\n"
            + "  return float3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);\n"
            + "}\n"
            + "float3 hsv2rgb(float3 c) {\n"
            + "  float4 K = float4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);\n"
            + "  float3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);\n"
            + "  return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);\n"
            + "}\n"
            + "float burn(float b, float s) {\n"
            + "  return s <= 0.0 ? 0.0 : max(1.0 - (1.0 - b) / s, 0.0);\n"
            + "}\n"
            + "float llight(float b, float s) {\n"
            + "  return s < 0.5 ? max(b + 2.0 * s - 1.0, 0.0) : min(b + 2.0 * (s - 0.5), 1.0);\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 xy) {\n"
            + "  half4 src = content.eval(xy);\n"
            + "  float a = src.a;\n"
            + "  float3 c = a > 0.0039 ? float3(src.rgb) / a : float3(0.0);\n"
            + "  c = mix(c, float3(burn(c.r, cBurn.r), burn(c.g, cBurn.g), burn(c.b, cBurn.b)), aBurn);\n"
            + "  c = mix(c, float3(llight(c.r, cLin.r), llight(c.g, cLin.g), llight(c.b, cLin.b)), aLin);\n"
            + "  float3 hsv = rgb2hsv(clamp(c, 0.0, 1.0));\n"
            + "  hsv.z = mix(hsv.z, lift, 0.85);\n"
            + "  c = hsv2rgb(hsv);\n"
            + "  return half4(c * a, a);\n"
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
                // SonyPods 的 aboutArtworkBlendColors(false)
                blend.setFloatUniform("cBurn", 0x4A / 255f, 0x4A / 255f, 0x4A / 255f);
                blend.setFloatUniform("aBurn", 0xCC / 255f);
                blend.setFloatUniform("cLin", 0x4F / 255f, 0x4F / 255f, 0x4F / 255f);
                blend.setFloatUniform("aLin", 1f);
                blend.setFloatUniform("lift", night ? LIFT_DARK : LIFT_LIGHT);

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
