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
 *  1. 只抓 miuix 为这块玻璃撑出的「层」（胶囊 + effects 要求的 padding），而不是整屏 1080×2280；
 *  2. 同一帧内多次采样共用一张位图——底栏容器和指示器各调一次，区域是包含关系。
 */
internal class ViewBackdrop : Backdrop {
    var source: View? = null

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
        if (size.width <= 0f || size.height <= 0f) return

        val ds = downscaleFactor.toFloat().coerceAtLeast(1f)

        // DrawScope 的 size 不是胶囊的大小，而是 miuix 为这块玻璃撑出的「层」的大小：
        //   层 = 节点四周各加 effects 要求的 padding（折射带 24dp / 压缩余量 40dp），再整体除以
        //   downscaleFactor。实测 649×176 的胶囊 -> 868×396 的层 -> 这里读到 434×198。
        //
        // 关键：miuix 的 recordBackdrop 在调用本方法前做了 translate(padding / ds)，
        // 也就是「绘制坐标原点 = 胶囊左上角」，不是层的左上角。层向左上是负坐标
        // （-padding/ds 才到层边），向右下是正的。搞反了整张位图就会整体上移/左移一个
        // padding（实测 110px）——玻璃里就出现了「头顶那一行文字」的折射。
        val layerWidth = size.width * ds
        val layerHeight = size.height * ds
        val padX = (layerWidth - node.size.width) / 2f
        val padY = (layerHeight - node.size.height) / 2f

        val sourceOnScreen = IntArray(2).also { source.getLocationInWindow(it) }
        val nodeOnScreen = node.positionInWindow()
        val surfaceInSource = Offset(
            x = nodeOnScreen.x - sourceOnScreen[0],
            y = nodeOnScreen.y - sourceOnScreen[1],
        )
        // 采样区域仍然从「层」的左上角开始，把 padding 内的内容也抓进来给折射带用。
        val layerLeft = surfaceInSource.x - padX
        val layerTop = surfaceInSource.y - padY

        val wanted = Rect(
            Math.floor(layerLeft.toDouble()).toInt(),
            Math.floor(layerTop.toDouble()).toInt(),
            Math.ceil((layerLeft + layerWidth).toDouble()).toInt(),
            Math.ceil((layerTop + layerHeight).toDouble()).toInt(),
        )
        wanted.intersect(0, 0, source.width, source.height)
        if (wanted.isEmpty) return

        val bitmap = capture(source, wanted, 1f / ds) ?: return
        // 每帧最多重抓一次：下一个渲染帧开始时让缓存失效。
        Choreographer.getInstance().postFrameCallback(invalidateNextFrame)

        // 位图按 1/ds 采样（1 位图像素 = 1 个采样像素 = ds 个源像素），绘制坐标系是
        // 「源像素 / ds」，所以两者是 1:1，只平移、不缩放；平移量相对胶囊左上角算。
        withTransform({
            translate(
                (bufferRect.left - surfaceInSource.x) / ds,
                (bufferRect.top - surfaceInSource.y) / ds,
            )
        }) {
            drawImage(bitmap.asImageBitmap())
        }
    }

    private fun capture(source: View, region: Rect, sampleScale: Float): Bitmap? {
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
