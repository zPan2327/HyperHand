package io.github.zpan2327.hyperhand;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * miuix squircle（连续曲率圆角）——逐行移植自
 * miuix-squircle/src/commonMain/kotlin/top/yukonga/miuix/kmp/squircle/SquirclePath.kt。
 *
 * 与普通圆角矩形的区别：角不是 1/4 圆弧，而是用两段三次贝塞尔拼出的"超椭圆"，
 * 曲率连续（G2），视觉上更柔和——HyperOS / iOS 图标和卡片用的就是这种轮廓。
 * 关键常量：Extension = 1.1（角块相对半径的倍率），SQUIRCLE_CONTROL = 0.643。
 */
final class Squircle {

    /** 角块大小 = cornerRadius × 1.1（miuix SquircleDefaults.Extension）。 */
    private static final float EXTENSION = 1.1f;
    /** 贝塞尔控制柄比例（miuix SQUIRCLE_CONTROL）。 */
    private static final float CONTROL = 0.643f;

    private Squircle() {
    }

    /** 追加 squircle 矩形路径（左上角原点）。 */
    static void rect(Path p, float width, float height, float cornerRadius) {
        if (width <= 0f || height <= 0f) {
            return;
        }
        float halfMin = Math.min(width, height) * 0.5f;
        float tile = Math.min(Math.max(0f, cornerRadius) * EXTENSION, halfMin);
        if (tile <= 0f) {
            p.addRect(0f, 0f, width, height, Path.Direction.CW);
            return;
        }
        float handle = tile * (1f - CONTROL);
        p.moveTo(tile, 0f);
        p.lineTo(width - tile, 0f);
        p.cubicTo(width - handle, 0f, width, handle, width, tile);
        p.lineTo(width, height - tile);
        p.cubicTo(width, height - handle, width - handle, height, width - tile, height);
        p.lineTo(tile, height);
        p.cubicTo(handle, height, 0f, height - handle, 0f, height - tile);
        p.lineTo(0f, tile);
        p.cubicTo(0f, handle, handle, 0f, tile, 0f);
        p.close();
    }

    /** 追加带绝对坐标的 squircle 矩形路径。 */
    static void rect(Path p, RectF r, float cornerRadius) {
        float left = r.left, top = r.top;
        float width = r.width(), height = r.height();
        if (width <= 0f || height <= 0f) return;
        float halfMin = Math.min(width, height) * 0.5f;
        float tile = Math.min(Math.max(0f, cornerRadius) * EXTENSION, halfMin);
        if (tile <= 0f) {
            p.addRect(r, Path.Direction.CW);
            return;
        }
        float handle = tile * (1f - CONTROL);
        p.moveTo(left + tile, top);
        p.lineTo(left + width - tile, top);
        p.cubicTo(left + width - handle, top, left + width, top + handle, left + width, top + tile);
        p.lineTo(left + width, top + height - tile);
        p.cubicTo(left + width, top + height - handle, left + width - handle, top + height,
                left + width - tile, top + height);
        p.lineTo(left + tile, top + height);
        p.cubicTo(left + handle, top + height, left, top + height - handle, left, top + height - tile);
        p.lineTo(left, top + tile);
        p.cubicTo(left, top + handle, left + handle, top, left + tile, top);
        p.close();
    }

    /** 在画布上直接画填充 squircle。 */
    static void fill(Canvas canvas, RectF r, float cornerRadius, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        Path p = new Path();
        rect(p, r, cornerRadius);
        canvas.drawPath(p, paint);
    }

    /**
     * squircle 背景 Drawable——替代 GradientDrawable 的圆角矩形。
     * 视觉差异集中在 16dp+ 的大圆角（卡片、对话框、按钮）上。
     */
    static final class D extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float radius;
        private int strokeColor;
        private int strokeWidth;
        private int intrinsicW = -1;
        private int intrinsicH = -1;

        D(int color, float radius) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            this.radius = radius;
        }

        void setSize(int w, int h) {
            intrinsicW = w;
            intrinsicH = h;
        }

        D stroke(int color, int width) {
            strokeColor = color;
            strokeWidth = width;
            return this;
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) {
                return;
            }
            Path p = new Path();
            if (strokeWidth > 0) {
                float inset = strokeWidth / 2f;
                rect(p, new RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset), radius);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(p, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(strokeWidth);
                paint.setColor(strokeColor);
                canvas.drawPath(p, paint);
                paint.setStyle(Paint.Style.FILL);
            } else {
                rect(p, new RectF(b), radius);
                canvas.drawPath(p, paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return intrinsicW;
        }

        @Override
        public int getIntrinsicHeight() {
            return intrinsicH;
        }

        @Override
        public void getOutline(Outline outline) {
            // clipToOutline 用：轮廓近似成同半径圆角矩形（差异仅在角部曲率）
            Rect b = getBounds();
            outline.setRoundRect(b, radius);
        }
    }
}
