package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「关于」页。
 *
 * 版式取自 SonyPods 的关于页：顶部大面积留白 + 应用图标 / 名称 / 版本，
 * 往下是「开发者」和几组信息卡片，最后是许可信息。
 * 内容全部是 HyperHand 自己的：作者、仓库、系统环境、作用域、模块版本。
 */
final class AboutPage {

    static final String REPO = "https://github.com/zPan2327/HyperHand";
    static final String AUTHOR = "https://github.com/zPan2327";

    private final MainActivity host;
    private final Theme.Colors pal;
    private final Context c;
    private final Ui.Page page;

    AboutPage(MainActivity host) {
        this.host = host;
        this.c = host;
        this.pal = host.pal;
        this.page = Ui.page(c, pal);
        build();
    }

    View view() {
        return page.scroll;
    }

    private void build() {
        buildHero();

        // ---------------------------------------------------------- 开发者
        page.add(Ui.section(c, pal, "开发者"));
        LinearLayout cardDev = Ui.card(c, pal);
        LinearLayout row = Ui.rowShell(c);
        Ui.rowRipple(row, pal);
        row.setPadding(Theme.dp(c, 18), Theme.dp(c, 14), Theme.dp(c, 18), Theme.dp(c, 14));
        row.addView(avatar(56), new LinearLayout.LayoutParams(
                Theme.dp(c, 56), Theme.dp(c, 56)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.text(c, "不会买菜.", 19, pal.title, Theme.semibold()));
        TextView sub = Ui.text(c, "@zPan2327", 14, pal.summary, false);
        sub.setPadding(0, Theme.dp(c, 1), 0, 0);
        col.addView(sub);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.leftMargin = Theme.dp(c, 14);
        row.addView(col, colLp);
        // SonyPods DeveloperCard 用 32sp 的「›」而不是小箭头图标
        TextView arrow = Ui.text(c, "›", 32, pal.chevron, false);
        row.addView(arrow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl(AUTHOR);
            }
        });
        Ui.addRow(c, cardDev, pal, row);
        page.add(cardDev);

        // ---------------------------------------------------------- 项目
        page.add(Ui.section(c, pal, "项目"));
        LinearLayout cardProj = Ui.card(c, pal);
        Ui.navRow(c, cardProj, pal, -1, "源码", "GitHub 上的 HyperHand 仓库", "GitHub",
                new Runnable() {
                    @Override
                    public void run() {
                        openUrl(REPO);
                    }
                });
        Ui.navRow(c, cardProj, pal, -1, "引用", "Miuix / KernelSU / SonyPods 等", null,
                new Runnable() {
                    @Override
                    public void run() {
                        Ui.infoDialog(host, pal, "引用与致谢", REFERENCES);
                    }
                });
        Ui.navRow(c, cardProj, pal, -1, "使用说明", "上手前值得看一眼的几条", null,
                new Runnable() {
                    @Override
                    public void run() {
                        Ui.infoDialog(host, pal, "使用说明", USAGE);
                    }
                });
        Ui.navRow(c, cardProj, pal, -1, "许可", "MIT License", null,
                new Runnable() {
                    @Override
                    public void run() {
                        Ui.infoDialog(host, pal, "许可", LICENSE);
                    }
                });
        page.add(cardProj, Ui.cardLp(c, Theme.CARD_GAP));

        TextView foot = Ui.text(c, "HyperHand · MIT License\n为小米 14 / HyperOS 3 打造的单手模式增强",
                Theme.T_CAP, pal.section, false);
        foot.setGravity(Gravity.CENTER);
        foot.setLineSpacing(Theme.dp(c, 3), 1f);
        foot.setPadding(0, Theme.dp(c, 24), 0, 0);
        page.add(foot);
    }

    // ---------------------------------------------------------------- 调试页（隐藏）

    /**
     * 隐藏调试页：连续点击 logo 5 次弹出。
     * 打包原「概览」页的运行状态 / 最近手势 / 快速操作三个区块。
     * 用 root overlay 实现（Dialog 在 MIUI 上会透出下层页面）。
     */
    private void showDebugPage() {
        FrameLayout overlay = new FrameLayout(c);
        overlay.setBackgroundColor(pal.bg);

        Ui.Page dbg = Ui.page(c, pal);
        dbg.column.setPadding(Ui.pagePad(c), host.statusInset() + Theme.dp(c, 16),
                Ui.pagePad(c), Theme.dp(c, 32));
        TextView dbgTitle = Ui.text(c, "调试", Theme.T_TITLE, pal.title, Theme.semibold());
        dbg.add(dbgTitle);

        // ---- 运行状态
        dbg.add(Ui.section(c, pal, "运行状态"));
        LinearLayout status = Ui.card(c, pal);
        Cfg cfg = host.cfg();
        Ui.infoPair(c, status, pal, "扩展触发").setText(cfg.monitorEnabled ? "已开启" : "已关闭");
        Ui.infoPair(c, status, pal, "模糊壁纸背景").setText(cfg.blurBg
                ? "已开启 · " + Ui.fmt(cfg.blurRadiusDp, 0) + " dp / 压暗 " + Ui.fmt(cfg.blurDim, 0) + "%"
                : "已关闭");
        Ui.infoPair(c, status, pal, "缩小动画").setText(Cfg.ANIM_CURVE_SHORT[safe(cfg.animCurve)] + " · "
                + Ui.fmt(cfg.animScale, 2) + "× · " + Math.round(Cfg.SYSTEM_ANIM_MS * cfg.animScale) + " ms");
        Ui.infoPair(c, status, pal, "模糊区下滑").setText(
                cfg.topShade ? Cfg.TOP_SIDE_SHORT[safeSide(cfg.topShadeSide)] : "已关闭");
        Ui.infoPair(c, status, pal, "贴底直达").setText(
                cfg.endBoost ? Ui.fmt(cfg.endZoneDp, 0) + " dp 内算到底" : "已关闭");
        dbg.add(status, Ui.cardLp(c, Theme.CARD_GAP));

        // ---- 最近手势
        dbg.add(Ui.section(c, pal, "最近手势"));
        LinearLayout log = Ui.card(c, pal);
        final TextView gestureText = Ui.block(c, pal, host.gestureLog());
        log.addView(gestureText);
        Ui.actionRow(c, log, pal, "刷新记录", "读取系统界面写回的最近一次判定", new Runnable() {
            @Override
            public void run() {
                gestureText.setText(host.gestureLog());
                host.toast("已刷新");
            }
        });
        dbg.add(log, Ui.cardLp(c, Theme.CARD_GAP));

        // ---- 快速操作
        dbg.add(Ui.section(c, pal, "快速操作"));
        LinearLayout quick = Ui.card(c, pal);
        Ui.actionRow(c, quick, pal, "预览单手模式", "立刻缩小窗口，直接看背景效果", new Runnable() {
            @Override
            public void run() {
                host.previewOneHanded();
            }
        });
        Ui.actionRow(c, quick, pal, "退出单手模式", "恢复正常窗口大小", new Runnable() {
            @Override
            public void run() {
                host.stopOneHanded();
            }
        });
        Ui.actionRow(c, quick, pal, "重启两个作用域", "结束系统界面与桌面进程，重新注入钩子", new Runnable() {
            @Override
            public void run() {
                host.restartScopes();
            }
        });
        dbg.add(quick, Ui.cardLp(c, Theme.CARD_GAP));

        TextView close = Ui.text(c, "关闭", Theme.T_ROW, pal.primary, true);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, Theme.dp(c, 16), 0, Theme.dp(c, 8));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.hideOverlay(overlay);
            }
        });
        dbg.add(close);

        overlay.addView(dbg.scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        host.showOverlay(overlay);
    }

    private static int safe(int curve) {
        return curve < 0 || curve >= Cfg.ANIM_CURVE_SHORT.length ? 0 : curve;
    }

    private static int safeSide(int side) {
        return side < 0 || side >= Cfg.TOP_SIDE_SHORT.length ? 0 : side;
    }

    // ---------------------------------------------------------------- 顶部

    private void buildHero() {
        // 允许 hero 溢出页面内边距，渐变才能铺满整屏宽
        page.column.setClipChildren(false);
        page.column.setClipToPadding(false);

        FrameLayout hero = new FrameLayout(c);
        hero.setClipChildren(false);

        // 顶部氛围渐变：直接用 Miuix 的 OS3 背景着色器（见 MiuixBg）
        MiuixBg wash = new MiuixBg(c, pal);
        hero.addView(wash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(c, 420)));


        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(Theme.dp(c, 20), Theme.dp(c, 116) + host.statusInset(), Theme.dp(c, 20), Theme.dp(c, 26));

        AppIcon icon = new AppIcon(c, Theme.dp(c, 88), pal.primary);
        // 隐藏入口：连续点击 logo 5 次弹出调试页
        icon.setClickable(true);
        icon.setOnClickListener(new View.OnClickListener() {
            private int taps;
            private long last;

            @Override
            public void onClick(View v) {
                long now = android.os.SystemClock.uptimeMillis();
                taps = (now - last < 800) ? taps + 1 : 1;
                last = now;
                if (taps >= 5) {
                    taps = 0;
                    showDebugPage();
                }
            }
        });
        col.addView(icon, new LinearLayout.LayoutParams(Theme.dp(c, 88), Theme.dp(c, 88)));

        TextView name = Ui.text(c, "HyperHand", 30, pal.title, Theme.semibold());
        name.setLetterSpacing(-0.01f);
        name.setPadding(0, Theme.dp(c, 16), 0, 0);
        name.setGravity(Gravity.CENTER);
        col.addView(name);

        TextView ver = Ui.text(c, versionName() + "(" + versionCode() + ")", 14,
                pal.summary, false);
        ver.setPadding(0, Theme.dp(c, 5), 0, 0);
        ver.setGravity(Gravity.CENTER);
        col.addView(ver);

        hero.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 渐变要横向出血到屏幕两侧，抵消页面的左右内边距
        int pad = Ui.pagePad(c);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        heroLp.leftMargin = -pad;
        heroLp.rightMargin = -pad;
        page.add(hero, heroLp);
    }

    /**
     * 圆形头像。avatar.png 已经是按猫脸裁好的正方形（320×320），
     * 这里只需等比铺满，不再做偏移运算 —— 之前在运行期裁切容易取错区域。
     */
    private View avatar(int sizeDp) {
        final int size = Theme.dp(c, sizeDp);
        final Bitmap bmp = loadAvatar();
        View v = new View(c) {
            private final Paint bitPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path clip = new Path();

            @Override
            protected void onMeasure(int w, int h) {
                setMeasuredDimension(size, size);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                clip.reset();
                float r = size / 2f;
                clip.addCircle(r, r, r - Theme.dp(c, 1), Path.Direction.CW);
                canvas.save();
                canvas.clipPath(clip);
                if (bmp != null && !bmp.isRecycled()) {
                    // 正方形图源：等比铺满即可
                    float s = (float) size / bmp.getWidth();
                    float dw = bmp.getWidth() * s;
                    float dh = bmp.getHeight() * s;
                    canvas.drawBitmap(bmp, (size - dw) / 2f, (size - dh) / 2f, bitPaint);
                } else {
                    canvas.drawColor(Theme.alpha(pal.primary, pal.night ? 0.24f : 0.12f));
                }
                canvas.restore();
                ringPaint.setStyle(Paint.Style.STROKE);
                ringPaint.setStrokeWidth(Theme.dp(c, 2));
                ringPaint.setColor(Theme.alpha(0xFFFFFFFF, 0.85f));
                canvas.drawCircle(r, r, r - Theme.dp(c, 1), ringPaint);
            }
        };
        return v;
    }

    private Bitmap loadAvatar() {
        try {
            int id = c.getResources().getIdentifier("avatar", "drawable", c.getPackageName());
            if (id == 0) {
                return null;
            }
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            // 关键：不让 aapt2 按屏幕密度把 320 的图放大成 880 —— 那样再缩到 154
            // 就是 5.7 倍的双线性跳采样，头像会糊成一团。
            o.inScaled = false;
            // 解码阶段就降到接近显示尺寸（320 / 2 = 160 ≈ 154），由解码器做盒式平均，最清晰。
            o.inSampleSize = 2;
            return android.graphics.BitmapFactory.decodeResource(c.getResources(), id, o);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 图标

    /** 应用图标：圆角方块 + 蓝色渐变 + 白色下滑手势，和桌面图标同一套视觉。 */
    static final class AppIcon extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final float size;
        private final int accent;

        AppIcon(Context c, float sizePx, int accent) {
            super(c);
            this.size = sizePx;
            this.accent = accent;
            glyph.setStyle(Paint.Style.STROKE);
            glyph.setStrokeCap(Paint.Cap.ROUND);
            glyph.setStrokeJoin(Paint.Join.ROUND);
            glyph.setColor(Color.WHITE);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round(size), Math.round(size));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float s = getWidth();
            float r = s * 0.27f;
            RectF box = new RectF(0, 0, s, s);
            bg.setShader(new LinearGradient(0, 0, s, s,
                    new int[] {Theme.blend(accent, 0xFFFFFFFF, 0.28f), accent, Theme.blend(accent, 0xFF001A4D, 0.28f)},
                    new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, r, r, bg);

            // 顶部高光，让图标有一点玻璃质感
            Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            sheen.setShader(new LinearGradient(0, 0, 0, s * 0.6f,
                    new int[] {0x33FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(box, r, r, sheen);

            float inset = s * 0.22f;
            float gs = s - inset * 2;
            Icons.draw(canvas, Icons.HAND, inset, inset, gs, Color.WHITE, Math.max(2f, s * 0.055f));
        }
    }

    // ---------------------------------------------------------------- 数据

    private String versionName() {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName == null ? "3.0.0" : pi.versionName;
        } catch (Throwable t) {
            return "3.0.0";
        }
    }

    private long versionCode() {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable t) {
            return 30000;
        }
    }

    private String installTime() {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            return f.format(new Date(pi.lastUpdateTime));
        } catch (Throwable t) {
            return "未知";
        }
    }

    private static String safe(String s) {
        return s == null || s.length() == 0 ? "未知" : s;
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            host.toast("没有可用的浏览器");
        }
    }

    // ---------------------------------------------------------------- 文案

    private static final String REFERENCES =
            "Miuix（compose-miuix-ui/miuix）\n"
                    + "整套色彩、字号、圆角与组件规格的来源，Apache-2.0。\n\n"
                    + "KernelSU（tiann/KernelSU）\n"
                    + "页面层级、卡片分组与信息排布参考，GPL-3.0。\n\n"
                    + "SonyPods（Mercury000/SonyPods）\n"
                    + "悬浮底栏、背景模糊与关于页版式参考。\n\n"
                    + "libxposed API（libxposed/api）\n"
                    + "模块的现代 API 入口，Apache-2.0。\n\n"
                    + "AndroidLiquidGlass（Kyant0/AndroidLiquidGlass）\n"
                    + "液态玻璃的折射与高光思路，Apache-2.0。\n\n"
                    + "以上项目仅作为设计参考，HyperHand 的全部界面代码都是\n"
                    + "按 Miuix 的取值用原生 View 手写实现的。";

    private static final String USAGE =
            "• 在 Vector / LSPosed 里启用 HyperHand 即可：作用域已经静态声明，\n"
                    + "  不用手动勾「系统界面 / 小米桌面」，也没法挂到别的应用上。\n\n"
                    + "• 保存后立即生效，不需要重启；改动由系统界面进程写入 Settings.Global。\n\n"
                    + "• 自然模式：底部连续下滑即可，不要求甩；灵敏模式门槛更低；防误触模式更保守。\n\n"
                    + "• 速度单位是 dp/s，与屏幕密度无关；旧版的 px/s 参数会自动折算过来。\n\n"
                    + "• 想完全恢复原状：关掉「启用模块」，或在管理器里停用模块。";

    private static final String LICENSE =
            "MIT License\n\n"
                    + "Copyright (c) 2026 zPan2327\n\n"
                    + "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
                    + "of this software and associated documentation files (the \"Software\"), to deal\n"
                    + "in the Software without restriction, including without limitation the rights\n"
                    + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
                    + "copies of the Software, and to permit persons to whom the Software is\n"
                    + "furnished to do so, subject to the following conditions:\n\n"
                    + "The above copyright notice and this permission notice shall be included in all\n"
                    + "copies or substantial portions of the Software.\n\n"
                    + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
                    + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
                    + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
                    + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
                    + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
                    + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
                    + "SOFTWARE.";
}
