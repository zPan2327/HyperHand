package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;

/**
 * 顶栏渐进式模糊 —— 按 SonyPods / miuix 的 {@code LayerBackdrop + RuntimeShader} 架构重写。
 *
 * <p>参考 {@code SonyPods/ui/components/BarBlur.kt}：
 * <pre>
 * rememberLayerBackdrop { drawRect(surfaceColor); drawContent() }   // 只录制内容层
 * progressiveTextureBlur(
 *     backdrop = backdrop,
 *     blurRadius = 16f,
 *     gradient = ProgressiveBlur.Top.copy(startFraction = 0.12f,
 *                                         endFraction = 1f, curve = 1.25f),
 *     colors = blurColors(BlendColorEntry(surfaceColor @ 0.66f))
 * )
 * </pre>
 * 关键点是<b>模糊在 GPU 上一次画完</b>：backdrop 是内容的录制层，不重绘 View 树到
 * 软件位图、不 getPixels / setPixels、不做 CPU 盒式模糊、不逐像素合成。
 *
 * <p>本项目是纯 Java View，没有 Compose graphicsLayer，所以 backdrop 用「一小条低分辨率
 * 位图」代替录制层：{@link View#draw(Canvas)} 只采样顶栏那一窄条，尺寸约
 * {@code (屏宽+24) * 0.30 × (band+24) * 0.30} ≈ 330×60 ≈ 2 万像素；随后交给 AGSL
 * {@link RuntimeShader}，按 y 方向连续改变采样半径，一次 {@code drawRect} 完成
 * "顶部全额模糊 → 中段中等模糊 → 底边像素级清晰"。
 *
 * <p><b>相对上一版的两个致命修复</b>：
 * <ul>
 *   <li><b>实时</b>：坐标变换改为 ViewBackdrop 的正确写法（region 一律用 source 坐标，
 *       {@code scale → translate(-region.left) → clipRect(region)}），截到的就是滚动中的
 *       真实内容；并且<b>每帧强制重采样，取消全部指纹 / 缓存判断</b>。上一版把 window
 *       坐标和 source 坐标混用，clip 掉的几乎是整个内容，只剩纯色底色，指纹恒定 →
 *       永久复用第一帧，看起来就是「模糊不刷新」。</li>
 *   <li><b>不卡</b>：删掉两次 {@code LiquidGlass.blur}（每次 3 趟分离盒式模糊）、
 *       全像素 hash、逐行 mix 合成、4 个大 int[] 与多次 getPixels/setPixels。每帧 CPU
 *       只剩一次约 2 万像素的 View.draw，模糊全在 GPU shader 里，开销从几十毫秒降到
 *       1 毫秒量级。</li>
 * </ul>
 *
 * <p>展开态（{@code collapse < 0.30}）完全不画、不采样，也不画任何分隔线
 * —— 关于页未滚动时头顶那条遮挡带和那条黑线都由此消失。
 *
 * <p><b>刷新采用事件脏驱动，绝不常驻自循环</b>：backdrop 只会在滚动、Rubber Band
 * 位移、横向切页时变化，这些事件由外部显式 {@code topBar.invalidate()} 驱动；
 * {@code draw()} 每次被触发都无条件重采样一次。静止时顶栏完全不重绘，CPU/GPU 占用为零
 * —— 用 Choreographer 帧回调每帧 invalidate() 自驱动看似「更实时」，实际上
 * 会让静止时仍每帧遍历整棵 View 树，正是「一旦出现就很卡」的那类常驻开销。
 */
final class ProgressiveBlur {

    /** miuix {@code ProgressiveBlur}：两点线性梯度 + power-curve 衰减。 */
    static final class Gradient {
        final float angle;
        final float startFraction;
        final float endFraction;
        final float curve;

        /** SonyPods {@code TOP_BAR_PROGRESSIVE_BLUR} 的参数。 */
        static final Gradient TOP = new Gradient(90f, 0.12f, 1f, 1.25f);

        Gradient(float angle, float startFraction, float endFraction, float curve) {
            this.angle = angle;
            this.startFraction = startFraction;
            this.endFraction = Math.abs(endFraction - startFraction) < 0.001f
                    ? startFraction + 0.001f : endFraction;
            this.curve = Math.max(0.01f, curve);
        }

        Gradient copy(float start, float end, float curve) {
            return new Gradient(angle, start, end, curve);
        }
    }

    /** SonyPods {@code BAR_BLUR_RADIUS}。 */
    private static final float DEFAULT_BLUR_RADIUS_DP = 16f;
    /**
     * 采样降分辨率。
     *
     * <p>采样走的是<b>软件 Canvas</b>（{@code contentHost.draw()} 光栅化到 Bitmap），
     * 成本基本正比于目标位图面积。0.30 时约 324×94，滚动时每帧都要重来一遍，
     * 在慢机上就是掉帧/卡顿的主因。降到 0.20（约 216×63，面积 −56%）后每帧成本大幅
     * 下降，而模糊本身就是要糊掉细节的，位图小一点在观感上没有差别。
     */
    private static final float SAMPLE_SCALE = 0.20f;
    /** 采样区外扩，给 shader 的模糊半径留像素，避免边缘被 CLAMP 拉出硬边。 */
    private static final int SAMPLE_PAD_DP = 12;
    /**
     * SonyPods 用 {@code surfaceColor @ 0.66f}；这里略低一些，让「背后的模糊内容」更可见
     * （上一轮用户反馈顶栏像糊了一层死色），且混色随 strength 渐进到底边为 0，
     * 不会形成一块不透明色带。
     */
    private static final float SURFACE_ALPHA = 0.45f;

    /**
     * 变半径高斯模糊。
     *
     * <p><b>为什么不是 13-tap 十字</b>：固定方向的十字 / 对角采样在半径小时看不出问题，
     * 但这里半径是几十 px（16dp @440dpi ≈ 44px），13 个点摊在那个圆上相邻间距接近
     * 20px，圆盘中间是大片空洞 —— 采出来是「多重鬼影」，观感就是散光。用户反馈的
     * 正是这个。
     *
     * <p>改成 <b>Vogel / 黄金角螺旋盘采样</b>：第 i 个采样点的半径取
     * {@code sqrt(i/N) * r}（面积均匀，点会填满整个圆盘而不是只落在环上），
     * 角度取 {@code i * 2.39996}（黄金角，保证任意 N 下都不出现方向性条纹），
     * 权重取高斯 {@code exp(-2i/N)}。20 个点就能得到连续、无方向伪影的高斯核，
     * 等效于一个直径 2r 的真高斯模糊。
     *
     * <p>半径随 y 连续变化（{@code strength = 1 - pow(u, curve)}），所以是「模糊强度
     * 渐进」而不是「全模糊图 alpha 渐隐」。
     */
    private static final String AGSL = ""
            + "uniform shader uContent;\n"
            + "uniform float2 uCaptureOrigin;\n"   // 采样位图左上角对应的 canvas 坐标
            + "uniform float2 uCaptureScale;\n"    // canvas px -> bitmap px
            + "uniform float2 uOrigin;\n"          // band 左上角（canvas 坐标）
            + "uniform float2 uSize;\n"            // band 宽高
            + "uniform float uMaxRadius;\n"        // 顶部全额模糊半径（canvas px）
            + "uniform float uStart;\n"
            + "uniform float uEnd;\n"
            + "uniform float uCurve;\n"
            + "uniform float uReveal;\n"
            + "uniform float4 uBlendColor;\n"
            + "\n"
            + "half4 tap(float2 p) {\n"
            + "  return uContent.eval((p - uCaptureOrigin) * uCaptureScale);\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "  float2 uv = (fragCoord - uOrigin) / uSize;\n"
            + "  float t = clamp(uv.y, 0.0, 1.0);\n"
            + "  float u = clamp((t - uStart) / max(uEnd - uStart, 0.001), 0.0, 1.0);\n"
            + "  float strength = 1.0 - pow(u, uCurve);\n"
            + "  float r = uMaxRadius * strength;\n"
            + "  if (r < 0.35) {\n"
            + "    half4 sharp = tap(fragCoord);\n"      // 底边清晰区：直接取像素，零采样开销
            + "    float a0 = clamp(uReveal, 0.0, 1.0);\n"
            + "    return half4(sharp.rgb * half(a0), half(a0));\n"
            + "  }\n"
            + "  float4 acc = float4(0.0);\n"
            + "  float wsum = 0.0;\n"
            // 每个像素把整组采样点旋转一个伪随机角度（interleaved gradient noise），
            // 20 个点固定分布残留的轻微条纹就会被摊成看不见的噪声，
            // 相当于用时间/空间抖动换取「用更少 tap 达到接近无限 tap 的均匀度」。
            + "  float ign = fract(52.9829189 * fract(dot(fragCoord, float2(0.06711056, 0.00583715))));\n"
            + "  float jitter = ign * 6.2831853;\n"
            + "  for (int i = 0; i < 20; i++) {\n"
            + "    float fi = float(i) + 0.5;\n"
            + "    float rr = sqrt(fi / 20.0) * r;\n"        // 面积均匀 → 填满圆盘
            + "    float th = fi * 2.39996323 + jitter;\n"   // 黄金角 + 每像素抖动
            + "    float2 off = float2(cos(th), sin(th)) * rr;\n"
            + "    float w = exp(-2.0 * fi / 20.0);\n"       // 高斯权重
            + "    acc += float4(tap(fragCoord + off)) * w;\n"
            + "    wsum += w;\n"
            + "  }\n"
            + "  half4 c = half4(acc / wsum);\n"
            + "  c.rgb = mix(c.rgb, half3(uBlendColor.rgb),\n"
            + "              half(uBlendColor.a * strength));\n"
            + "  float a = clamp(uReveal, 0.0, 1.0);\n"
            + "  return half4(c.rgb * half(a), half(a));\n"
            + "}\n";

    private final Context c;
    private final Theme.Colors pal;
    private final View host;
    private final Paint shaderPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect region = new Rect();
    private final RectF dst = new RectF();

    private View backdropSource;
    private float blurRadiusDp = DEFAULT_BLUR_RADIUS_DP;
    private Gradient gradient = Gradient.TOP;
    private boolean enabled = true;

    private Bitmap sample;
    private Canvas sampleCanvas;
    private Bitmap fallbackBlur;
    private Canvas fallbackCanvas;
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RuntimeShader shader;
    private BitmapShader contentShader;
    private Bitmap contentShaderBitmap;
    private boolean shaderUsable = true;

    /** 采样位图左上角 / 尺寸在 host 局部坐标里的映射。 */
    private float dstX, dstY, dstW, dstH;
    private boolean loggedPath;

    ProgressiveBlur(Context c, Theme.Colors pal, View host) {
        this.c = c;
        this.pal = pal;
        this.host = host;
        try {
            shader = new RuntimeShader(AGSL);
        } catch (Throwable t) {
            shader = null;
            shaderUsable = false;
        }
    }

    void setBackdropSource(View v) {
        backdropSource = v;
    }

    /** miuix / SonyPods 的 {@code blurRadius}（dp，顶部全强度）。 */
    void setBlurRadius(float dp) {
        blurRadiusDp = Math.max(0f, Math.min(150f, dp));
    }

    void setGradient(Gradient g) {
        gradient = g == null ? Gradient.TOP : g;
    }

    void setEnabled(boolean v) {
        enabled = v;
    }

    float noiseCoefficient() {
        return 0f;
    }

    /**
     * 画从 {@code top} 到 {@code bottom} 的渐进模糊带。
     * 展开态直接返回：不采样、不画任何东西（也没有分隔线）。
     * 被调用即代表 backdrop 发生了变化，所以每次都无条件重采样。
     */
    void draw(Canvas canvas, float top, float bottom, float collapse) {
        if (!enabled || host == null || backdropSource == null || bottom <= top
                || host.getWidth() <= 0) {
            return;
        }
        float reveal = Theme.clamp01(collapse / 0.30f);
        if (reveal <= 0.001f) {
            return;
        }

        // 每帧无条件重采样：不设指纹、不设缓存、不设时间节流。
        // 顶栏只在折叠态才走到这里，采样区约 330×60，成本远低于一帧预算。
        capture(top, bottom);
        if (sample == null || sample.isRecycled()) {
            return;
        }

        float left = 0f;
        float right = host.getWidth();
        boolean gpuPath = shaderUsable && shader != null && canvas.isHardwareAccelerated()
                && LiquidGlass.canShader();
        if (!loggedPath) {
            loggedPath = true;
            android.util.Log.i("HyperHandBlur", "path=" + (gpuPath ? "gpu" : "fallback")
                    + " hw=" + canvas.isHardwareAccelerated()
                    + " sdk=" + android.os.Build.VERSION.SDK_INT
                    + " sample=" + (sample == null ? "null"
                            : sample.getWidth() + "x" + sample.getHeight())
                    + " dst=" + Math.round(dstW) + "x" + Math.round(dstH));
        }
        if (gpuPath) {
            try {
                if (contentShader == null || contentShaderBitmap != sample) {
                    contentShader = new BitmapShader(sample,
                            Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                    contentShaderBitmap = sample;
                }
                shader.setInputShader("uContent", contentShader);
                shader.setFloatUniform("uCaptureOrigin", dstX, dstY);
                shader.setFloatUniform("uCaptureScale",
                        sample.getWidth() / Math.max(1f, dstW),
                        sample.getHeight() / Math.max(1f, dstH));
                shader.setFloatUniform("uOrigin", left, top);
                shader.setFloatUniform("uSize", right - left, bottom - top);
                // uMaxRadius 是「采样盘半径」，对应的高斯 σ ≈ r/2。16dp @440dpi = 44px
                // 直接当 r 用就是 σ=22px、模糊直径 130px，比顶栏还高，整条全糊成一片雾。
                // 乘 0.5 → r=22px、σ≈11px，保留住形状轮廓但看不清文字，才是顶栏该有的手感。
                shader.setFloatUniform("uMaxRadius", Theme.dpf(c, blurRadiusDp) * 0.5f);
                shader.setFloatUniform("uStart", gradient.startFraction);
                shader.setFloatUniform("uEnd", gradient.endFraction);
                shader.setFloatUniform("uCurve", gradient.curve);
                shader.setFloatUniform("uReveal", reveal);
                int surface = surfaceColor();
                shader.setFloatUniform("uBlendColor",
                        Color.red(surface) / 255f, Color.green(surface) / 255f,
                        Color.blue(surface) / 255f, SURFACE_ALPHA);
                shaderPaint.setShader(shader);
                canvas.drawRect(left, top, right, bottom, shaderPaint);
                return;
            } catch (Throwable t) {
                // 软件画布 / RuntimeShader 不被支持：只退化这一次，不反复抛异常
                android.util.Log.w("HyperHandBlur", "gpu blur path failed, fallback", t);
                shaderUsable = false;
            }
        }

        // 退化路径（软件画布 / 不支持 RuntimeShader）：小位图上做一次轻量盒式模糊，
        // 再用 DST_IN 渐变把"模糊强度"从顶部满额衰减到底边 0，叠在清晰采样之上。
        // 位图只有约 330×60，一次模糊 + 两次 drawBitmap 的成本可以忽略；
        // 绝不回到上一版那种全屏分辨率上的多次模糊 + 逐像素混合。
        int surface = surfaceColor();
        canvas.save();
        canvas.clipRect(left, top, right, bottom);
        dst.set(dstX, dstY, dstX + dstW, dstY + dstH);
        fallbackPaint.setAlpha(Math.round(255 * reveal));
        canvas.drawBitmap(sample, null, dst, fallbackPaint);

        Bitmap blurred = ensureFallbackBlur(sample);
        int layer = canvas.saveLayer(left, top, right, bottom, null);
        canvas.drawBitmap(blurred, null, dst, fallbackPaint);
        maskPaint.setShader(new LinearGradient(0, top, 0, bottom,
                new int[] {0xFF000000, Theme.alpha(0xFF000000, 0f)},
                null, Shader.TileMode.CLAMP));
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawRect(left, top, right, bottom, maskPaint);
        maskPaint.setXfermode(null);
        canvas.restoreToCount(layer);

        scrimPaint.setShader(new LinearGradient(0, top, 0, bottom,
                new int[] {Theme.alpha(surface, SURFACE_ALPHA * 0.6f * reveal),
                        Theme.alpha(surface, 0f)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(left, top, right, bottom, scrimPaint);
        canvas.restore();
    }

    // ---------------------------------------------------------------- backdrop 采样

    /**
     * 只把 backdrop 的「顶栏那一条」录进低分辨率位图。
     *
     * <p>坐标全部用 <b>source 局部坐标</b>，照抄项目里已验证正确的
     * {@code ViewBackdrop.capture()}：{@code scale → translate(-region.left/top) → clipRect(region)}。
     * 上一版把 window 偏移塞进 translate、又用 window 偏移做 clipRect，两者坐标系不一致，
     * 结果 clip 掉了几乎全部内容 —— 这就是「模糊不刷新、只有一片死色」的根因。
     */
    private void capture(float top, float bottom) {
        try {
            int[] hostLoc = new int[2];
            int[] srcLoc = new int[2];
            host.getLocationInWindow(hostLoc);
            backdropSource.getLocationInWindow(srcLoc);

            int pad = Math.round(Theme.dpf(c, SAMPLE_PAD_DP));
            // host 局部 (0, top) 落在 source 局部的哪个位置
            int left = hostLoc[0] - srcLoc[0] - pad;
            int topS = hostLoc[1] - srcLoc[1] + Math.round(top) - pad;
            int w = host.getWidth() + pad * 2;
            int h = Math.round(bottom - top) + pad * 2;
            region.set(left, topS, left + w, topS + h);
            if (!region.intersect(0, 0,
                    Math.max(1, backdropSource.getWidth()),
                    Math.max(1, backdropSource.getHeight()))) {
                return;
            }

            int ow = Math.max(2, Math.round(region.width() * SAMPLE_SCALE));
            int oh = Math.max(2, Math.round(region.height() * SAMPLE_SCALE));
            ensure(ow, oh);

            int base = Color.alpha(pal.bg) == 255
                    ? pal.bg : (pal.night ? 0xFF101014 : 0xFFF7F7F7);
            sample.eraseColor(base);
            Canvas cc = sampleCanvas;
            cc.save();
            cc.scale(SAMPLE_SCALE, SAMPLE_SCALE);
            cc.translate(-region.left, -region.top);
            cc.clipRect(region);
            try {
                // 软件画布：个别依赖 RuntimeShader / RenderEffect 的子 View 可能抛。
                // 和 ViewBackdrop 一样只吞掉这一帧的异常，保留已画好的底色，
                // 绝不能像上一版那样把结果整体置空导致永不刷新。
                backdropSource.draw(cc);
            } catch (Throwable ignored) {
                // 保留底色，下一帧继续尝试
            }
            cc.restore();

            dstX = srcLoc[0] + region.left - hostLoc[0];
            dstY = srcLoc[1] + region.top - hostLoc[1];
            dstW = region.width();
            dstH = region.height();
        } catch (Throwable t) {
            // 尺寸 / 窗口附着状态异常时跳过这一帧，不置空 sample
        }
    }

    private int surfaceColor() {
        int s = Color.alpha(pal.card) == 255
                ? pal.card : (pal.night ? 0xFF242424 : 0xFFFFFFFF);
        return s | 0xFF000000;
    }

    private void ensure(int ow, int oh) {
        if (sample != null && sample.getWidth() == ow && sample.getHeight() == oh) {
            return;
        }
        if (sample != null && !sample.isRecycled()) {
            sample.recycle();
        }
        sample = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
        sampleCanvas = new Canvas(sample);
        contentShader = null;
        contentShaderBitmap = null;
    }

    /** 退化路径用：只在采样尺寸变化时重建，之后每帧复用，模糊半径也随位图缩放。 */
    private Bitmap ensureFallbackBlur(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (fallbackBlur == null || fallbackBlur.getWidth() != w
                || fallbackBlur.getHeight() != h) {
            if (fallbackBlur != null && !fallbackBlur.isRecycled()) {
                fallbackBlur.recycle();
            }
            fallbackBlur = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            fallbackCanvas = new Canvas(fallbackBlur);
        }
        fallbackBlur.eraseColor(0);
        fallbackCanvas.drawBitmap(src, 0, 0, null);
        int r = Math.max(1, Math.round(Theme.dpf(c, blurRadiusDp) * SAMPLE_SCALE * 0.35f));
        LiquidGlass.blur(fallbackBlur, r);
        return fallbackBlur;
    }
}
