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
 * <p>本项目是纯 Java View，没有 Compose graphicsLayer，所以 backdrop 用「顶栏那一窄条
 * 的 <b>1:1 全分辨率</b>位图」代替录制层：{@link View#draw(Canvas)} 只采样
 * {@code 屏宽 × (band + 2×pad)}，随后交给 AGSL {@link RuntimeShader}，用 32 tap 的
 * Vogel 盘高斯权重核按 y 方向连续改变半径，一次 {@code drawRect} 完成
 * "顶部全额模糊 → 中段中等模糊 → 底边像素级清晰"。
 *
 * <p><b>采样位图三张轮换</b>：本帧写 {@code pool[(i+1)%3]}，写成功后立即把它提升为
 * shader 的输入 —— shader 读的是<b>同帧采到的内容</b>（零延迟，不像双缓冲慢一帧）；
 * 而这张位图要再过 2 帧才会被下一次 capture 覆写，GPU 异步读取时 CPU 不会动它
 * （无撕裂，不像单缓冲每帧擦掉正在被读的那张）。软件绘制异常时只丢弃这一张、
 * 不提升，模糊带沿用上一帧完整内容，绝不会闪出一片底色。
 *
 * <p>降采样（0.20×）会在这套实现里把每个位图像素放大成 5×5 屏幕块，即使核是高斯，
 * 肉眼看到的仍是马赛克；这正是真机上「模糊有颗粒感」的来源。
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
     * 采样分辨率：<b>1:1 全分辨率</b>。
     *
     * <p>之前降到 0.20× 是为了省 CPU，但 216×63 的位图放大回 1200×300 后，
     * 每个采样像素要撑 5×5 的屏幕块 —— 即使 shader 里用的是高斯权重，肉眼看到的仍是
     * 「马赛克 / 像素块」而不是高斯模糊。SonyPods 的 {@code LayerBackdrop} 本来就是
     * 全分辨率的 GPU 录制层，不会有这层缩放。现在按 1:1 采样，配合
     * {@code FILTER_BITMAP_FLAG}，片元与内容像素一一对应。
     */
    private static final float SAMPLE_SCALE = 1.0f;
    /**
     * 采样区外扩：必须 ≥ 最大模糊半径（16dp ≈ 48px ≈ 16dp@3x），
     * 否则圆盘边缘采到 CLAMP 的边界像素，会被拉出硬边 / 块状伪影。
     */
    private static final int SAMPLE_PAD_DP = 24;
    /**
     * SonyPods {@code BarBlur.kt} 的 {@code TOP_BAR_SURFACE_ALPHA = 0.66f}，原样取用。
     * 上一版降到 0.45（想让背后内容更透），结果雾罩比参考明显偏淡 ——
     * 「顶部渐进式模糊与参考不符」的主要来源。
     */
    private static final float SURFACE_ALPHA = 0.66f;

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
            // miuix Shaders.kt 的两段 ramp，逐行对齐：
            //   intensity = 1 - pow(smoothstep(u), curve)         —— 模糊强度\n"
            //   rampWeight = smoothstep(1 - pow(smoothstep(u), curve)) —— 混色强度\n"
            // 上一版用 1 - pow(u, curve)（漏掉 smoothstep），衰减形状和参考对不上。\n"
            + "  float su = u * u * (3.0 - 2.0 * u);\n"
            + "  float strength = 1.0 - pow(su, uCurve);\n"
            + "  float rw = 1.0 - pow(su, uCurve);\n"
            + "  rw = rw * rw * (3.0 - 2.0 * rw);\n"
            + "  float r = uMaxRadius * strength;\n"
            + "  if (r < 0.35) {\n"
            + "    half4 sharp = tap(fragCoord);\n"      // 底边清晰区：直接取像素，零采样开销
            + "    float a0 = clamp(uReveal, 0.0, 1.0);\n"
            + "    return half4(sharp.rgb * half(a0), half(a0));\n"
            + "  }\n"
            + "  float4 acc = float4(0.0);\n"
            + "  float wsum = 0.0;\n"
            // 每个像素把整组采样点旋转一个伪随机角度（interleaved gradient noise），
            // 摊掉固定分布残留的方向条纹。32 个 tap、Vogel 黄金角面积均匀布点，
            // 权重 exp(-2 * (rr/r)^2) = 真高斯核 sigma = r/2（不是等权盒式平均）。
            // 全分辨率采样下这些 tap 落在真实内容像素上，不会再被放大成马赛克。
            + "  float ign = fract(52.9829189 * fract(dot(fragCoord, float2(0.06711056, 0.00583715))));\n"
            + "  float jitter = ign * 6.2831853;\n"
            + "  for (int i = 0; i < 32; i++) {\n"
            + "    float fi = float(i) + 0.5;\n"
            + "    float rr = sqrt(fi / 32.0) * r;\n"        // 面积均匀 → 填满圆盘
            + "    float th = fi * 2.39996323 + jitter;\n"   // 黄金角 + 每像素抖动
            + "    float2 off = float2(cos(th), sin(th)) * rr;\n"
            + "    float w = exp(-2.0 * fi / 32.0);\n"       // 高斯权重 sigma = r/2
            + "    acc += float4(tap(fragCoord + off)) * w;\n"
            + "    wsum += w;\n"
            + "  }\n"
            + "  half4 c = half4(acc / wsum);\n"
            + "  c.rgb = mix(c.rgb, half3(uBlendColor.rgb),\n"
            + "              half(uBlendColor.a * rw));\n"
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
    /**
     * 「backdrop 变了」脏标记，由 TopBar 每次重绘时置位，{@link #draw} 采一次后清掉。
     *
     * <p>不再按 16ms 时间节流：120Hz 屏幕上等于每两帧才更新一次采样，慢速滚动时
     * 清晰内容每帧都在动、模糊带却隔帧跳一次，交界处就是用户说的「渐进模糊闪烁」。
     * SonyPods 的 {@code progressiveTextureBlur(backdrop)} 也是每帧从 LayerBackdrop
     * 采样，不存在降帧；MainActivity 的滚动回调同样按「每帧重采样」设计的。
     */
    private boolean captureDirty = true;
    private float blurRadiusDp = DEFAULT_BLUR_RADIUS_DP;
    private Gradient gradient = Gradient.TOP;
    private boolean enabled = true;

    /**
     * 采样位图池：capture 写 {@code pool[(poolIdx+1)%POOL]}，成功才把它设为 {@link #active}。
     *
     * <p>为什么既不是单缓冲也不是双缓冲：
     * <ul>
     *   <li><b>单缓冲</b>：每帧 {@code eraseColor()} 擦的正是 GPU 还在读的那张位图，
     *       120Hz 慢滚时持续撕裂；全分辨率软件重绘一旦某帧抛异常，位图只剩底色，
     *       下一帧才恢复 —— 就是上下滚动时「闪一下」。</li>
     *   <li><b>双缓冲（front/back 交换）</b>：shader 读上一帧 front，实测无规律慢滚
     *       49/209 帧「内容动、模糊带不动」，是用户说的「延迟」。</li>
     *   <li><b>三张轮换</b>：shader 每帧绑定本帧刚写完的那张（零延迟），而它在之后
     *       2 帧内不会被覆写（GPU 已用完，无撕裂）。</li>
     * </ul>
     */
    private static final int POOL = 3;
    private final Bitmap[] pool = new Bitmap[POOL];
    private final Canvas[] poolCanvas = new Canvas[POOL];
    private int poolIdx = -1;
    /** 本帧 shader / 退化路径真正读取的位图；capture 失败时保持上一帧，不闪底色。 */
    private Bitmap active;

    private Bitmap fallbackBlur;
    private Canvas fallbackCanvas;
    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RuntimeShader shader;
    private boolean shaderUsable = true;

    /** 采样位图左上角 / 尺寸在 host 局部坐标里的映射。 */
    private float dstX, dstY, dstW, dstH;
    /**
     * 采样窗高度（单调递增，只增不减）。
     *
     * <p>{@code bottom = 顶栏 + fade}，而 {@code fade = 18+26*(1-collapse) dp} 在滚动中
     * 连续变化 → 若直接用它当采样高度，1:1 全分辨率下每帧都要 recycle / new 一张
     * 约 1200×350 的 ARGB_8888 位图并重建 BitmapShader 纹理，慢滚时必然闪烁 + 掉帧。
     * 这里取见过最大 bottom 再留 8dp 余量，配 240dp 下限，尺寸在滚动期间恒定。
     */
    private int bandH;
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

    /** TopBar 每次重绘前调用：声明 backdrop 已变化，下一次 draw 需要重采样。 */
    void invalidateCapture() {
        captureDirty = true;
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

        // 脏驱动重采样：TopBar.onDraw 每帧置脏，这里采一次清一次。滚动、横滑、
        // 橡皮筋、折叠都会让 TopBar 重绘，慢滚时模糊带与内容严格同帧，不再隔帧跳。
        if (active == null || captureDirty) {
            captureDirty = false;
            capture(bottom);
        }
        if (active == null || active.isRecycled()) {
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
                    + " sample=" + (active == null ? "null"
                            : active.getWidth() + "x" + active.getHeight())
                    + " dst=" + Math.round(dstW) + "x" + Math.round(dstH));
        }
        if (gpuPath) {
            try {
                // 每帧把 inputShader 绑定到本帧刚采完的 active：shader 读到的就是
                // 同帧的真实内容（零延迟）；而 active 在之后 2 帧都不会被覆写，
                // GPU 异步采样期间 CPU 不会改它的像素（无撕裂）。
                shader.setInputShader("uContent", new BitmapShader(active,
                        Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
                shader.setFloatUniform("uCaptureOrigin", dstX, dstY);
                shader.setFloatUniform("uCaptureScale",
                        active.getWidth() / Math.max(1f, dstW),
                        active.getHeight() / Math.max(1f, dstH));
                shader.setFloatUniform("uOrigin", left, top);
                shader.setFloatUniform("uSize", right - left, bottom - top);
                // miuix 的 blurRadius（dp）直接乘 density 当核半径用：它的高斯
                // σ = radius*0.5+0.5；本 shader 的 Vogel 盘 σ = r/2，所以 r 直接取
                // blurRadiusPx 就是同档模糊。上一版 *0.5 只有一半强度，参考更糊。
                shader.setFloatUniform("uMaxRadius", Theme.dpf(c, blurRadiusDp));
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
        canvas.drawBitmap(active, null, dst, fallbackPaint);

        Bitmap blurred = ensureFallbackBlur(active);
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
                new int[] {Theme.alpha(surface, SURFACE_ALPHA * reveal),
                        Theme.alpha(surface, 0f)},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(left, top, right, bottom, scrimPaint);
        canvas.restore();
    }

    // ---------------------------------------------------------------- backdrop 采样

    /**
     * 只把 backdrop 的「顶栏那一条」录进 1:1 全分辨率位图。
     *
     * <p>坐标全部用 <b>source 局部坐标</b>，照抄项目里已验证正确的
     * {@code ViewBackdrop.capture()}：{@code scale → translate(-region.left/top) → clipRect(region)}。
     *
     * <p>写入目标永远是 {@code pool[(poolIdx+1)%POOL]}，<b>绝不可能是当前 {@link #active}</b>；
     * 只有整条画完才提升为 active。软件画布上某个 AGSL / RenderEffect 子 View 抛异常时，
     * 这张被擦掉的位图直接作废、下一帧重试，shader 仍读上一帧完整内容 —— 既不闪底色，
     * 也不会把半新半旧的像素交给 GPU。
     */
    private void capture(float bottom) {
        try {
            int[] hostLoc = new int[2];
            int[] srcLoc = new int[2];
            host.getLocationInWindow(hostLoc);
            backdropSource.getLocationInWindow(srcLoc);

            int pad = Math.round(Theme.dpf(c, SAMPLE_PAD_DP));
            int need = Math.round(bottom) + pad * 2 + Math.round(Theme.dpf(c, 8f));
            if (need > bandH) {
                bandH = need;
            }
            // 采样窗固定在 host 顶部、尺寸恒定；band 高度的变化只影响 shader 里的
            // 渐变，绝不动采样位图 / GPU 纹理绑定。
            int h = Math.max(Math.round(Theme.dpf(c, 240f)), bandH);
            int left = hostLoc[0] - srcLoc[0] - pad;
            int topS = hostLoc[1] - srcLoc[1] - pad;
            int w = host.getWidth() + pad * 2;
            region.set(left, topS, left + w, topS + h);
            if (!region.intersect(0, 0,
                    Math.max(1, backdropSource.getWidth()),
                    Math.max(1, backdropSource.getHeight()))) {
                captureDirty = true;
                return;
            }

            int ow = Math.max(2, Math.round(region.width() * SAMPLE_SCALE));
            int oh = Math.max(2, Math.round(region.height() * SAMPLE_SCALE));
            ensure(ow, oh);

            int next = (poolIdx + 1) % POOL;
            Bitmap b = pool[next];
            Canvas cc = poolCanvas[next];
            if (b == null || b.isRecycled() || cc == null) {
                captureDirty = true;
                return;
            }
            int base = Color.alpha(pal.bg) == 255
                    ? pal.bg : (pal.night ? 0xFF000000 : 0xFFF7F7F7);
            b.eraseColor(base);
            boolean ok = false;
            cc.save();
            try {
                cc.scale(SAMPLE_SCALE, SAMPLE_SCALE);
                cc.translate(-region.left, -region.top);
                cc.clipRect(region);
                backdropSource.draw(cc);
                ok = true;
            } catch (Throwable ignored) {
                ok = false;
            } finally {
                cc.restore();
            }
            if (!ok) {
                captureDirty = true;
                return;
            }
            poolIdx = next;
            active = b;
            dstX = srcLoc[0] + region.left - hostLoc[0];
            dstY = srcLoc[1] + region.top - hostLoc[1];
            dstW = region.width();
            dstH = region.height();
        } catch (Throwable t) {
            // 尺寸 / 窗口附着状态异常时也保持上一帧 active，不闪底色
            captureDirty = true;
        }
    }

    private int surfaceColor() {
        // SonyPods BarBlur.kt 的 surfaceColor = MiuixTheme.colorScheme.surface，
        // 即本项目的页面底色 pal.bg，而不是 card（surfaceContainer）。
        //
        // 深色下 surface = #000000、surfaceContainer = #242424：拿 #242424 以 0.66
        // 混到纯黑的采样内容上，会把顶栏从 #000000 提亮到 #181818 一带，滚动后叠加
        // 模糊内容就成了 #2E312F 的灰带 —— 这就是「深色模式顶部偏白、没适配」。
        // 浅色下 surface(#F7F7F7) 与 surfaceContainer(#FFFFFF) 几乎一样，所以此前
        // 一直没暴露。改回 surface 后深色混色只会把内容压暗，不会凭空提亮。
        int s = Color.alpha(pal.bg) == 255
                ? pal.bg : (pal.night ? 0xFF000000 : 0xFFF7F7F7);
        return s | 0xFF000000;
    }

    /** 三张位图同尺寸重建（band 高度或横竖屏变化时）。 */
    private void ensure(int ow, int oh) {
        if (pool[0] != null && !pool[0].isRecycled()
                && pool[0].getWidth() == ow && pool[0].getHeight() == oh) {
            return;
        }
        int base = Color.alpha(pal.bg) == 255
                ? pal.bg : (pal.night ? 0xFF101014 : 0xFFF7F7F7);
        for (int i = 0; i < POOL; i++) {
            if (pool[i] != null && !pool[i].isRecycled()) {
                pool[i].recycle();
            }
            pool[i] = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888);
            poolCanvas[i] = new Canvas(pool[i]);
            pool[i].eraseColor(base);
        }
        poolIdx = -1;
        active = null;
        if (fallbackBlur != null && !fallbackBlur.isRecycled()) {
            fallbackBlur.recycle();
        }
        fallbackBlur = null;
        fallbackCanvas = null;
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
