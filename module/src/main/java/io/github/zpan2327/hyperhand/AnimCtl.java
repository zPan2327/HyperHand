package io.github.zpan2327.hyperhand;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Field;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 单手模式"缩小 / 恢复"动画的读数与调速。
 *
 * 系统实现（HyperOS 3 / Android 16，取自 MiuiSystemUI.apk 的 classes3.dex）：
 *
 *   OneHandedController.startOneHanded()
 *     → OneHandedDisplayAreaOrganizer.scheduleOffset(dx, dy)
 *     → lambda$scheduleOffset$0 → animateWindows(token, leash, x, y, direction, mEnterExitAnimationDurationMs)
 *     → OneHandedAnimationController.getAnimator(...) → setupOneHandedTransitionAnimator(...)
 *     → animator.setInterpolator(OneHandedInterpolator) / setFloatValues(0f, 1f)
 *     → animator.setDuration(mEnterExitAnimationDurationMs).start()
 *
 * 入场（startOneHanded）与出场（stopOneHanded）走的是**同一个** animateWindows，
 * 时长来自同一个字段，所以调一处两边一起变。
 *
 * 时长来源（OneHandedDisplayAreaOrganizer 构造器）：
 *
 *   mEnterExitAnimationDurationMs = SystemProperties.getInt(
 *           "persist.debug.one_handed_translate_animation_duration",
 *           resources.getInteger(R.integer.config_one_handed_translate_animation_duration));
 *
 * HyperOS 上该资源（0x7f0c004a）的默认值是 **600**，即入场/出场都是 600ms。
 *
 * 曲线（OneHandedAnimationController$OneHandedInterpolator#getInterpolation）反汇编结果：
 *
 *   f(t) = 1 + 2^(-10t) · sin((t - 4) · π / 8)
 *
 * 也就是 Penner 式 "easeOutExpo + 正弦修正"：t=0.1 已经走完 50%，t=0.2 走到 75%，
 * t=0.3 走到 88% —— 前 1/5 时间几乎跑完全程，剩下 400ms 只是在慢慢"贴"。
 * 这就是"傻快"的来源：时长本身不短，是曲线前段太陡。
 *
 * 所以这里做两件事，互不干扰：
 *   1) 时长：改 mEnterExitAnimationDurationMs（只影响单手模式，不碰全局动画）；
 *   2) 曲线：接管 setupOneHandedTransitionAnimator 返回的 animator 的 interpolator。
 *
 * 两项都只改"单手模式这一条动画"，不影响系统其它动画、不碰触摸映射与退出逻辑。
 */
final class AnimCtl {

    static final String CLS_ORGANIZER = "com.android.wm.shell.onehanded.OneHandedDisplayAreaOrganizer";
    static final String CLS_ANIM_CTRL =
            "com.android.wm.shell.onehanded.OneHandedAnimationController";

    private static final String F_DURATION = "mEnterExitAnimationDurationMs";

    /** 系统属性里的同名字段，只用于日志对照（模块不依赖它）。 */
    static final String PROP_DURATION = "persist.debug.one_handed_translate_animation_duration";

    /** 资源取不到时的兜底值（HyperOS 实测 600）。 */
    static final int FALLBACK_MS = 600;

    // ---------------------------------------------------------------- 读取到的系统数据

    private static volatile Field sDurationField;
    private static volatile Object sOrganizer;

    /** 系统原始时长（首次构造时读到，之后不再变，避免反复乘系数）。 */
    private static volatile int sSystemMs = -1;
    /** 当前实际生效时长。 */
    private static volatile int sAppliedMs = -1;
    /** 当前生效的曲线下标。 */
    private static volatile int sAppliedCurve = -1;

    private static volatile int sAppliedCount;

    static int systemMs() {
        return sSystemMs;
    }

    static int appliedMs() {
        return sAppliedMs;
    }

    // ---------------------------------------------------------------- 安装

    static void hook(final ClassLoader cl, final Hooks hooks) {
        hookDuration(cl, hooks);
        hookCurve(cl, hooks);
    }

    /**
     * 时长：抓住 organizer 的构造，把字段里系统算出来的 600 换成按倍率缩放后的值。
     * 字段只在这一处被读（animateWindows / lambda$scheduleOffset$0 都读它），
     * 所以改字段 = 入场和出场一起变。
     */
    private static void hookDuration(ClassLoader cl, final Hooks hooks) {
        try {
            Class<?> org = hooks.findClass(CLS_ORGANIZER, cl);
            if (org == null) {
                HyperHand.log("anim: OneHandedDisplayAreaOrganizer not found");
                return;
            }
            Field f;
            try {
                f = org.getDeclaredField(F_DURATION);
                f.setAccessible(true);
            } catch (Throwable t) {
                HyperHand.log("anim: field " + F_DURATION + " not found: " + t);
                return;
            }
            sDurationField = f;

            int n = hooks.hookConstructors(org, Hooks.after(new Hooks.After() {
                @Override
                public void run(XposedInterface.Chain chain, Object result) {
                    try {
                        sOrganizer = chain.getThisObject();
                        // 构造刚结束：字段里是系统刚算出来的原始值，先记下来再放大
                        int raw = sDurationField.getInt(sOrganizer);
                        if (raw > 0) {
                            sSystemMs = raw;
                        }
                        applyDuration(contextOf(sOrganizer));
                    } catch (Throwable t) {
                        HyperHand.log("anim: apply duration on ctor failed: " + t);
                    }
                }
            }));
            HyperHand.log("anim: organizer ctor hooked (" + n + "), duration field captured");
        } catch (Throwable t) {
            HyperHand.log("anim: hook duration failed: " + t);
        }
    }

    /**
     * 曲线：setupOneHandedTransitionAnimator 返回的就是即将 start 的 animator，
     * 在这里把 interpolator 换掉即可（之后系统只会 setDuration / start，不会重设曲线）。
     */
    private static void hookCurve(ClassLoader cl, final Hooks hooks) {
        try {
            Class<?> ac = hooks.findClass(CLS_ANIM_CTRL, cl);
            if (ac == null) {
                HyperHand.log("anim: OneHandedAnimationController not found");
                return;
            }
            int n = hooks.hookMethods(ac, "setupOneHandedTransitionAnimator",
                    Hooks.after(new Hooks.After() {
                        @Override
                        public void run(XposedInterface.Chain chain, Object result) {
                            try {
                                if (!(result instanceof ValueAnimator)) return;
                                Context ctx = contextOf(chain.getThisObject());
                                if (ctx == null) ctx = Hooks.currentApplication();
                                if (ctx == null) return;

                                Cfg c = Cfg.get(ctx);
                                TimeInterpolator t = curveOf(c.animCurve);
                                sAppliedCurve = c.animCurve;
                                if (t != null) {
                                    ((ValueAnimator) result).setInterpolator(t);
                                }
                                attachProbe((ValueAnimator) result, ctx, c);
                            } catch (Throwable t) {
                                HyperHand.log("anim: set curve failed: " + t);
                            }
                        }
                    }));
            HyperHand.log("anim: setupOneHandedTransitionAnimator hooked (" + n + ")");
        } catch (Throwable t) {
            HyperHand.log("anim: hook curve failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 计时探针

    private static volatile int sLastMeasuredMs = -1;
    private static volatile long sLastEndAt;

    /**
     * 给这一条动画挂一个计时器：start/end 各记一次 uptimeMillis。
     * 系统的 setDuration 在 setup 之后才调用，所以 start 时读 getDuration 才是最终值。
     */
    private static void attachProbe(final ValueAnimator a, final Context ctx, final Cfg c) {
        a.addListener(new AnimatorListenerAdapter() {
            private long t0;
            private boolean counted;

            @Override
            public void onAnimationStart(Animator animation) {
                t0 = SystemClock.uptimeMillis();
                counted = false;
                if (c.debug) {
                    HyperHand.log("anim: start duration=" + a.getDuration() + "ms curve="
                            + Cfg.ANIM_CURVE_SHORT[safeCurve(c.animCurve)]);
                }
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (counted || t0 == 0) return;
                counted = true;
                int ms = (int) (SystemClock.uptimeMillis() - t0);
                sLastMeasuredMs = ms;
                sLastEndAt = SystemClock.uptimeMillis();
                if (c.debug) {
                    HyperHand.log("anim: end after " + ms + "ms (requested "
                            + a.getDuration() + "ms)");
                }
                publish(ctx, c, sAppliedMs > 0 ? sAppliedMs : (int) a.getDuration());
            }
        });
    }

    // ---------------------------------------------------------------- 生效

    /** 配置改动后重新算一遍（不用重启进程，下一次动画就是新参数）。 */
    static void onConfigChanged(Context ctx) {
        applyDuration(ctx);
    }

    private static void applyDuration(Context ctx) {
        Field f = sDurationField;
        Object o = sOrganizer;
        if (f == null || o == null) return;
        if (ctx == null) ctx = Hooks.currentApplication();
        if (ctx == null) return;

        try {
            if (sSystemMs <= 0) {
                int raw = f.getInt(o);
                sSystemMs = raw > 0 ? raw : FALLBACK_MS;
            }
            Cfg c = Cfg.get(ctx);
            int target = scaledDuration(sSystemMs, c.animScale);
            f.setInt(o, target);
            sAppliedMs = target;
            sAppliedCount++;
            publish(ctx, c, target);
            if (c.debug) {
                HyperHand.log("anim: duration " + sSystemMs + "ms -> " + target + "ms"
                        + " (x" + String.format(java.util.Locale.US, "%.2f", c.animScale)
                        + "), curve=" + Cfg.ANIM_CURVE_SHORT[safeCurve(c.animCurve)]);
            }
        } catch (Throwable t) {
            HyperHand.log("anim: apply duration failed: " + t);
        }
    }

    static int scaledDuration(int systemMs, float scale) {
        if (systemMs <= 0) systemMs = FALLBACK_MS;
        int v = Math.round(systemMs * scale);
        // 太短会让动画看起来"闪一下就没"，太长会明显拖沓；两头都收一下
        if (v < 120) v = 120;
        if (v > 2000) v = 2000;
        return v;
    }

    /** 把"系统原始值 / 当前应用值"写进 Settings.Global，设置页直接显示，不用抓日志。 */
    private static void publish(Context ctx, Cfg c, int appliedMs) {
        try {
            ContentResolver cr = ctx.getContentResolver();
            String s = "系统原生 " + sSystemMs + "ms · " + Cfg.ANIM_CURVE_RAW_DESC + "\n"
                    + "当前 " + appliedMs + "ms（×" + String.format(java.util.Locale.US, "%.2f", c.animScale)
                    + "）· " + Cfg.ANIM_CURVE_SHORT[safeCurve(c.animCurve)]
                    + (c.animCurve == Cfg.ANIM_CURVE_NATIVE ? "（未改动曲线）" : "");
            if (sLastMeasuredMs > 0) {
                s += "\n上次实测 " + sLastMeasuredMs + "ms";
            }
            Settings.Global.putString(cr, Cfg.K_ANIM_INFO, s);
        } catch (Throwable t) {
            // 只为了显示，失败无所谓
        }
    }

    private static int safeCurve(int v) {
        return v < 0 || v >= Cfg.ANIM_CURVE_NAMES.length ? Cfg.ANIM_CURVE_NATIVE : v;
    }

    private static Context contextOf(Object organizer) {
        if (organizer != null) {
            try {
                Field f = organizer.getClass().getDeclaredField("mContext");
                f.setAccessible(true);
                Object v = f.get(organizer);
                if (v instanceof Context) return (Context) v;
            } catch (Throwable ignored) {
                // 换到下一个来源
            }
        }
        return Hooks.currentApplication();
    }

    // ---------------------------------------------------------------- 曲线

    static TimeInterpolator curveOf(int curve) {
        switch (curve) {
            case Cfg.ANIM_CURVE_SOFT:
                return new EaseOutCubic();
            case Cfg.ANIM_CURVE_SMOOTH:
                return new SmoothStep();
            case Cfg.ANIM_CURVE_STANDARD:
                return new EaseOutQuart();
            case Cfg.ANIM_CURVE_SNAPPY:
                return new EaseOutQuint();
            case Cfg.ANIM_CURVE_NATIVE:
            default:
                return null;    // null = 保留系统 OneHandedInterpolator
        }
    }

    /**
     * ease-out cubic：1 - (1-t)^3。
     * t=0.1 → 27%，t=0.3 → 66%，t=0.5 → 88%。
     * 中段（0.2~0.6）几乎是直线，位移太均匀，容易觉得"机械、线性"。
     */
    static final class EaseOutCubic implements TimeInterpolator {
        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            float u = 1f - t;
            return 1f - u * u * u;
        }
    }

    /**
     * ease-out quart：1 - (1-t)^4。
     * t=0.1 → 34%，t=0.3 → 76%，t=0.5 → 94%。
     * 比 cubic 更快起步、更快收进尾部，中段不再是直线 —— 既有加速感，又不至于像原生那样一冲到底。
     */
    static final class EaseOutQuart implements TimeInterpolator {
        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            float u = 1f - t;
            float u2 = u * u;
            return 1f - u2 * u2;
        }
    }

    /**
     * ease-out quint：1 - (1-t)^5。
     * t=0.1 → 41%，t=0.3 → 83%，节奏最接近系统原生，但前段没那么极端。
     */
    static final class EaseOutQuint implements TimeInterpolator {
        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            float u = 1f - t;
            float u2 = u * u;
            return 1f - u2 * u2 * u;
        }
    }

    /**
     * smoothstep：3t² - 2t³，起手和收尾都缓。
     * t=0.1 → 3%，t=0.3 → 22%，位移最均匀，但起手会有一小段"还没动"。
     */
    static final class SmoothStep implements TimeInterpolator {
        @Override
        public float getInterpolation(float t) {
            if (t <= 0f) return 0f;
            if (t >= 1f) return 1f;
            return t * t * (3f - 2f * t);
        }
    }

    // ---------------------------------------------------------------- 诊断

    static String dump() {
        return "system=" + sSystemMs + "ms applied=" + sAppliedMs + "ms curve=" + sAppliedCurve
                + " applies=" + sAppliedCount;
    }
}
