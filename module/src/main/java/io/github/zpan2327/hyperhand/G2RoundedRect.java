package io.github.zpan2327.hyperhand;

import android.graphics.Path;

/**
 * G2 连续曲率圆角矩形（iOS 平滑圆角 / squircle corner）。
 *
 * <p>逐行移植自 Kyant0/Shapes（Apache License 2.0）：
 * <pre>
 *   shapes/src/commonMain/kotlin/com/kyant/shapes/ContinuousCurvatureRoundedRectangleCornerBuilder.kt
 *   shapes/src/commonMain/kotlin/com/kyant/shapes/RoundedRectangleOutline.kt
 * </pre>
 * 保留原版的三次 / 四次方程求解、kappa 与 lambda 控制点公式，仅把 Kotlin 转成 Java，
 * 使角部与直边的曲率连续（G2），而不是普通圆弧 + 直线的 G1 拼接。
 */
final class G2RoundedRect {

    private static final double SQRT_2 = 1.4142135623730951;
    private static final double FRAC_PI_4 = 0.7853981633974483;
    private static final double FRAC_1_SQRT_2 = 0.7071067811865476;

    private final double extendedFraction;
    private final double arcFraction;
    private final double theta;
    private final double cos, sin, cot;
    private final double cos2, sin2, cos3, sin3;
    private final double k0, k1, k2, k3;
    private final double[][][] cache;

    private G2RoundedRect(double extendedFraction, double arcFraction) {
        this.extendedFraction = extendedFraction;
        this.arcFraction = arcFraction;
        this.theta = (1.0 - arcFraction) * FRAC_PI_4;
        this.cos = Math.cos(theta);
        this.sin = Math.sin(theta);
        this.cot = 1.0 / Math.tan(theta);
        this.cos2 = cos * cos;
        this.sin2 = sin * sin;
        this.cos3 = cos2 * cos;
        this.sin3 = sin2 * sin;
        this.k0 = 27.0 * (SQRT_2 - 6.0 * cos + 6.0 * SQRT_2 * cos2 - 4.0 * cos3) * cot
                + 2.0 * sin * (-9.0 + 2.0 * (SQRT_2 - 2.0 * sin) * sin3
                + 2.0 * SQRT_2 * cos * (9.0 + sin2)
                - 2.0 * cos2 * (9.0 + 2.0 * sin2));
        this.k1 = -81.0 * (-2.0 + SQRT_2 + 4.0 * (-1.0 + SQRT_2) * cos
                + 2.0 * (-2.0 + SQRT_2) * cos2) * cot
                - 4.0 * sin * (-9.0 + 9.0 * SQRT_2 + SQRT_2 * sin3
                + (-2.0 + SQRT_2) * cos * (9.0 + sin2));
        this.k2 = 9.0 * (9.0 * (-4.0 + 3.0 * SQRT_2
                + (-6.0 + 4.0 * SQRT_2) * cos) * cot
                + (-6.0 + 4.0 * SQRT_2) * sin);
        this.k3 = 27.0 * (10.0 - 7.0 * SQRT_2) * cot;
        this.cache = new double[][][] {
                { buildEvenCornerBezierPoints(0.0), buildUnevenCornerBezierPoints(0.0, 1.0) },
                { buildUnevenCornerBezierPoints(1.0, 0.0), buildEvenCornerBezierPoints(1.0) }
        };
    }

    /** 与 Shapes 的 ContinuousCurvatureRoundedRectangleCornerBuilder.Default 同参。 */
    private static final G2RoundedRect DEFAULT = new G2RoundedRect(2.0 / 3.0, 0.5);

    private static double solveCubicSingle(double a, double b, double c, double d) {
        final double f = ((3.0 * c / a) - (b * b) / (a * a)) / 3.0;
        final double g = ((2.0 * b * b * b) / (a * a * a)
                - (9.0 * b * c) / (a * a) + (27.0 * d) / a) / 27.0;
        final double h = g * g / 4.0 + f * f * f / 27.0;
        final double sqrtH = Math.sqrt(h);
        return Math.cbrt(-g / 2.0 + sqrtH) + Math.cbrt(-g / 2.0 - sqrtH) - b / (3.0 * a);
    }

    private static double solveDepressedQuarticSingle(double p, double q, double rParam) {
        final double b = -p / 2.0;
        final double c = -rParam;
        final double d = rParam * p / 2.0 - q * q / 8.0;
        final double f = ((3.0 * c) - (b * b)) / 3.0;
        final double g = ((2.0 * b * b * b) - (9.0 * b * c) + (27.0 * d)) / 27.0;
        final double rr = Math.sqrt(-f * f * f / 27.0);
        final double phi = Math.acos(-g / (2.0 * rr));
        final double y = 2.0 * Math.sqrt(-f / 3.0) * Math.cos(phi / 3.0);
        final double z = y - b / 3.0;
        final double u = Math.sqrt(2.0 * z - p);
        return (u - Math.sqrt(u * u - 4.0 * (z + q / (2.0 * u)))) / 2.0;
    }

    private double[] buildCornerBezierPoints(double tH, double tV) {
        return tH == tV ? buildEvenCornerBezierPoints(tH) : buildUnevenCornerBezierPoints(tH, tV);
    }

    private double[] getCornerBezierPoints(double tH, double tV) {
        final int i;
        if (tH == 0.0) i = 0;
        else if (tH == 1.0) i = 1;
        else return buildCornerBezierPoints(tH, tV);
        final int j;
        if (tV == 0.0) j = 0;
        else if (tV == 1.0) j = 1;
        else return buildCornerBezierPoints(tH, tV);
        return cache[i][j];
    }

    private double[] buildEvenCornerBezierPoints(double t) {
        final double k = extendedFraction * t;
        final double kappa = solveCubicSingle(k3, k2, k1 + 8.0 * (-k) * sin3 * sin, k0);
        final double x3 = FRAC_1_SQRT_2 + (-FRAC_1_SQRT_2 + sin) / kappa;
        final double y3 = 1.0 - FRAC_1_SQRT_2 + (FRAC_1_SQRT_2 - cos) / kappa;
        final double x2 = x3 - y3 * cot;
        final double x1 = x2 - 1.5 * kappa * y3 * y3 / sin3;
        final double x0 = -k;
        final double x6 = 1.0 - y3;
        final double y6 = 1.0 - x3;
        final double y7 = 1.0 - x2;
        final double y8 = 1.0 - x1;
        final double y9 = 1.0 - x0;
        final double a = 1.5 * kappa;
        final double g = cos2 - sin2;
        final double x36 = x6 - x3;
        final double y36 = y6 - y3;
        final double c = -(cos * y36 - sin * x36);
        final double lambda = (-g + Math.sqrt(g * g - 4.0 * a * c)) / (2.0 * a);
        final double x4 = x3 + lambda * cos;
        final double y4 = y3 + lambda * sin;
        final double x5 = x6 - lambda * sin;
        final double y5 = y6 - lambda * cos;
        return new double[] {
                x0, 0.0, x1, 0.0, x2, 0.0, x3, y3, x4, y4,
                x5, y5, x6, y6, 1.0, y7, 1.0, y8, 1.0, y9
        };
    }

    private double[] buildUnevenCornerBezierPoints(double tH, double tV) {
        final double kH = extendedFraction * tH;
        final double kV = extendedFraction * tV;
        final double kappa3 = solveCubicSingle(k3, k2, k1 + 8.0 * (-kH) * sin3 * sin, k0);
        final double kappa6 = solveCubicSingle(k3, k2, k1 + 8.0 * (-kV) * sin3 * sin, k0);
        final double x3 = FRAC_1_SQRT_2 + (-FRAC_1_SQRT_2 + sin) / kappa3;
        final double y3 = 1.0 - FRAC_1_SQRT_2 + (FRAC_1_SQRT_2 - cos) / kappa3;
        final double x2 = x3 - y3 * cot;
        final double x1 = x2 - 1.5 * kappa3 * y3 * y3 / sin3;
        final double x0 = -kH;
        final double x3p = FRAC_1_SQRT_2 + (-FRAC_1_SQRT_2 + sin) / kappa6;
        final double y3p = 1.0 - FRAC_1_SQRT_2 + (FRAC_1_SQRT_2 - cos) / kappa6;
        final double x2p = x3p - y3p * cot;
        final double x1p = x2p - 1.5 * kappa6 * y3p * y3p / sin3;
        final double x0p = -kV;
        final double x6 = 1.0 - y3p;
        final double y6 = 1.0 - x3p;
        final double y7 = 1.0 - x2p;
        final double y8 = 1.0 - x1p;
        final double y9 = 1.0 - x0p;
        final double a = 1.5 * kappa3;
        final double b = 1.5 * kappa6;
        final double g = cos2 - sin2;
        final double x36 = x6 - x3;
        final double y36 = y6 - y3;
        final double c = -(cos * y36 - sin * x36);
        final double d = sin * y36 - cos * x36;
        final double p = 2.0 * (d / b);
        final double q = g * g * g / (a * b * b);
        final double r = (a * d * d + c * g * g) / (a * b * b);
        final double lambda6 = solveDepressedQuarticSingle(p, q, r);
        final double lambda3 = (-d - b * lambda6 * lambda6) / g;
        final double x4 = x3 + lambda3 * cos;
        final double y4 = y3 + lambda3 * sin;
        final double x5 = x6 - lambda6 * sin;
        final double y5 = y6 - lambda6 * cos;
        return new double[] {
                x0, 0.0, x1, 0.0, x2, 0.0, x3, y3, x4, y4,
                x5, y5, x6, y6, 1.0, y7, 1.0, y8, 1.0, y9
        };
    }

    /**
     * 把一个 G2 连续曲率圆角矩形写进 path（顺时针，起点在右上角），完全对应
     * Shapes 的 {@code continuousCurvatureRoundedRectanglePath(size, radius)}。
     */
    static void addToPath(Path path, float width, float height, float radius) {
        final double w = width;
        final double h = height;
        final double maxRadius = Math.min(w, h) * 0.5;
        final double r = Math.max(0.0, Math.min(radius, maxRadius));
        if (r == 0.0) {
            path.addRect(0f, 0f, width, height, Path.Direction.CW);
            return;
        }
        // 与 Shapes 的 RoundedRectangleOutline 一致：正方形且半径到半宽时是正圆。
        if (w == h && r >= maxRadius) {
            path.addCircle((float) (w * 0.5), (float) (h * 0.5), (float) r, Path.Direction.CW);
            return;
        }
        final double[] p = DEFAULT.getCornerBezierPoints(
                clamp01((w * 0.5 - r) / r), clamp01((h * 0.5 - r) / r));
        if (p.length < 20) {
            return;
        }
        double x, y;
        // 右上角
        x = w - r; y = 0.0;
        path.moveTo((float) (x + p[0] * r), (float) (y + p[1] * r));
        path.cubicTo((float) (x + p[2] * r), (float) (y + p[3] * r),
                (float) (x + p[4] * r), (float) (y + p[5] * r),
                (float) (x + p[6] * r), (float) (y + p[7] * r));
        path.cubicTo((float) (x + p[8] * r), (float) (y + p[9] * r),
                (float) (x + p[10] * r), (float) (y + p[11] * r),
                (float) (x + p[12] * r), (float) (y + p[13] * r));
        path.cubicTo((float) (x + p[14] * r), (float) (y + p[15] * r),
                (float) (x + p[16] * r), (float) (y + p[17] * r),
                (float) (x + p[18] * r), (float) (y + p[19] * r));
        // 右下角
        x = w - r; y = h;
        path.lineTo((float) (x + p[18] * r), (float) (y - p[19] * r));
        path.cubicTo((float) (x + p[16] * r), (float) (y - p[17] * r),
                (float) (x + p[14] * r), (float) (y - p[15] * r),
                (float) (x + p[12] * r), (float) (y - p[13] * r));
        path.cubicTo((float) (x + p[10] * r), (float) (y - p[11] * r),
                (float) (x + p[8] * r), (float) (y - p[9] * r),
                (float) (x + p[6] * r), (float) (y - p[7] * r));
        path.cubicTo((float) (x + p[4] * r), (float) (y - p[5] * r),
                (float) (x + p[2] * r), (float) (y - p[3] * r),
                (float) (x + p[0] * r), (float) (y - p[1] * r));
        // 左下角
        x = r; y = h;
        path.lineTo((float) (x - p[0] * r), (float) (y - p[1] * r));
        path.cubicTo((float) (x - p[2] * r), (float) (y - p[3] * r),
                (float) (x - p[4] * r), (float) (y - p[5] * r),
                (float) (x - p[6] * r), (float) (y - p[7] * r));
        path.cubicTo((float) (x - p[8] * r), (float) (y - p[9] * r),
                (float) (x - p[10] * r), (float) (y - p[11] * r),
                (float) (x - p[12] * r), (float) (y - p[13] * r));
        path.cubicTo((float) (x - p[14] * r), (float) (y - p[15] * r),
                (float) (x - p[16] * r), (float) (y - p[17] * r),
                (float) (x - p[18] * r), (float) (y - p[19] * r));
        // 左上角
        x = r; y = 0.0;
        path.lineTo((float) (x - p[18] * r), (float) (y + p[19] * r));
        path.cubicTo((float) (x - p[16] * r), (float) (y + p[17] * r),
                (float) (x - p[14] * r), (float) (y + p[15] * r),
                (float) (x - p[12] * r), (float) (y + p[13] * r));
        path.cubicTo((float) (x - p[10] * r), (float) (y + p[11] * r),
                (float) (x - p[8] * r), (float) (y + p[9] * r),
                (float) (x - p[6] * r), (float) (y + p[7] * r));
        path.cubicTo((float) (x - p[4] * r), (float) (y + p[5] * r),
                (float) (x - p[2] * r), (float) (y + p[3] * r),
                (float) (x - p[0] * r), (float) (y + p[1] * r));
        path.close();
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }
}
