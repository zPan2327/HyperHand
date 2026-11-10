package io.github.zpan2327.hyperhand.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.Log
import android.view.Choreographer
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A [Backdrop] that samples a plain [View] instead of a Compose-recorded layer.
 *
 * HyperHand's pages are ordinary Android views; the glass bar is a sibling overlay, so there is
 * no Compose subtree to record. This re-implements the old `GlassBar` sampling path: render the
 * source view into a reusable bitmap and blit it at the right offset, which is what
 * `LayerBackdrop` does for Compose content.
 *
 * 两个性能约束（初版把整页全分辨率重画，实测非常卡）：
 *  1. 只抓「本次绘制区域 + 余量」这一条，而不是整屏 1080×2280；
 *  2. 同一帧内多次采样共用一张位图——底栏容器和指示器各调一次，区域是包含关系。
 */
internal class ViewBackdrop : Backdrop {

    var source: View? = null

    /** 采样分辨率：玻璃本来就要模糊，半分辨率肉眼无差，成本降 4 倍。 */
    private val sampleScale = 0.5f

    private var buffer: Bitmap? = null
    private var canvas: Canvas? = null
    private val bufferRect = Rect()
    private var bufferValid = false

    private val warned = AtomicBoolean(false)

    override val isCoordinatesDependent: Boolean = true
    override val offsetResidualX: Float = 0f
    override val offsetResidualY: Float = 0f

    /** 下一个渲染帧开始时让缓存失效，等于"每帧最多重抓一次"。 */
    private val invalidateNextFrame = Choreographer.FrameCallback { bufferValid = false }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        val source = source ?: return
        val node = coordinates ?: return
        if (source.width <= 0 || source.height <= 0 || !source.isAttachedToWindow) return

        val sourceOnScreen = IntArray(2).also { source.getLocationInWindow(it) }
        val nodeOnScreen = node.positionInWindow()
        // 符号约定必须和 miuix 的 LayerBackdrop 一致：offset 是「绘制区域（胶囊）在源（页面）
        // 坐标系里的位置」，也就是 surface - source，而不是反过来。取反了就会把整张位图推出
        // 可见区（实测偏移 -1900px），录出来的层全空 → 玻璃只剩黑底 + 半透明白色蒙层。
        val surfaceInSource = Offset(
            x = nodeOnScreen.x - sourceOnScreen[0],
            y = nodeOnScreen.y - sourceOnScreen[1],
        )

        // 采样区域：本次绘制区域，四周各留一点余量给折射带与模糊越界读取。
        val margin = 12f
        val wanted = Rect(
            Math.floor((surfaceInSource.x - margin).toDouble()).toInt(),
            Math.floor((surfaceInSource.y - margin).toDouble()).toInt(),
            Math.ceil((surfaceInSource.x + size.width + margin).toDouble()).toInt(),
            Math.ceil((surfaceInSource.y + size.height + margin).toDouble()).toInt(),
        )
        wanted.intersect(0, 0, source.width, source.height)
        if (wanted.isEmpty) return

        val bitmap = capture(source, wanted) ?: return
        // 每帧最多重抓一次：下一个渲染帧开始时让缓存失效。
        Choreographer.getInstance().postFrameCallback(invalidateNextFrame)

        // 位图原点是裁剪区域左上角（已按 sampleScale 缩小）。换算：位图像素 (p-left)*s
        // 先放大 1/s 回到源坐标，再整体平移到「surface 原点」。
        val dx = bufferRect.left - surfaceInSource.x
        val dy = bufferRect.top - surfaceInSource.y

        withTransform({
            translate(dx, dy)
            scale(1f / sampleScale, 1f / sampleScale, Offset.Zero)
        }) {
            drawImage(bitmap.asImageBitmap())
        }
    }

    private fun capture(source: View, region: Rect): Bitmap? {
        // 同一帧里容器与指示器都会调一次，区域有包含关系：命中缓存直接复用。
        if (bufferValid && buffer != null && bufferRect.contains(region)) {
            return buffer
        }

        val w = Math.max(2, (region.width() * sampleScale).toInt())
        val h = Math.max(2, (region.height() * sampleScale).toInt())
        var bitmap = buffer
        if (bitmap == null || bitmap.width != w || bitmap.height != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buffer = bitmap
            canvas = Canvas(bitmap)
        }
        val c = canvas ?: return null
        c.save()
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        c.scale(sampleScale, sampleScale)
        c.translate(-region.left.toFloat(), -region.top.toFloat())
        c.clipRect(region)
        // 这里是软件画布：任何依赖 RuntimeShader / RenderEffect 的子 View 都可能抛。
        // 采样失败只该丢掉这一帧的玻璃，绝不能让整个界面崩掉。
        try {
            source.draw(c)
        } catch (t: Throwable) {
            if (warned.compareAndSet(false, true)) {
                Log.w("HyperHandGlass", "backdrop capture failed; glass falls back to tint", t)
            }
        }
        c.restore()

        bufferRect.set(region)
        bufferValid = true
        return bitmap
    }
}
