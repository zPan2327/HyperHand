package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 触觉反馈 —— 走澎湃 OS 自己的触感引擎，不是通用 {@link Vibrator} 波形。
 *
 * 反编译 {@code /system_ext/framework/miui-framework.jar} 得到
 * {@code miui.view.MiuiHapticFeedbackConstants} 的完整常量表（FLAG_MIUI_HAPTIC | effectId）：
 *
 * <pre>
 * TAP_LIGHT      268435457   轻点：列表行、tab
 * TAP_NORMAL     268435456   普通点按
 * FLICK          268435458
 * SWITCH         268435459   开关切换
 * MESH_LIGHT     268435462   滑杆每格（骁龙 Richtap 网格）
 * MESH_NORMAL    268435461
 * MESH_HEAVY     268435460
 * POPUP_LIGHT    268435465
 * PICK_UP        268435466
 * SCROLL_EDGE    268435467   滚动到顶/底
 * FLICK_LIGHT    268435469   轻扫（翻页）
 * HOLD           268435470
 * BUTTON_SMALL   268435475
 * BUTTON_MIDDLE  268435474
 * BUTTON_LARGE   268435473
 * ALERT          268435479   警示
 * </pre>
 *
 * 调用路径按可用性依次降级：
 * <ol>
 *   <li>{@code miuix.util.HapticFeedbackCompat#performHapticFeedback(int)}
 *       —— miuix 组件自己用的入口，走 HapticFeedbackUtil → Richtap；</li>
 *   <li>{@code View#performHapticFeedback(int)}（同样带 MIUI flag，
 *       MIUI 的 DecorView 认这个 flag 并转给 HapticFeedbackUtil）；</li>
 *   <li>{@link Vibrator} 的 {@link VibrationEffect.Composition}（API 30+）；</li>
 *   <li>one-shot 波形。</li>
 * </ol>
 *
 * 这些常量是"效果 id"而不是时长/振幅，所以出来的手感是线性马达的短促脉冲，
 * 不是转子马达的嗡嗡声。
 */
final class Haptics {

    // 澎湃 OS 触感常量（miui.view.MiuiHapticFeedbackConstants，已反编译核对）
    static final int TAP_LIGHT = 268435457;
    static final int TAP_NORMAL = 268435456;
    static final int SWITCH = 268435459;
    static final int MESH_LIGHT = 268435462;
    static final int MESH_NORMAL = 268435461;
    static final int POPUP_LIGHT = 268435465;
    static final int SCROLL_EDGE = 268435467;
    static final int FLICK_LIGHT = 268435469;
    static final int BUTTON_SMALL = 268435475;
    static final int BUTTON_MIDDLE = 268435474;
    static final int BUTTON_LARGE = 268435473;
    static final int ALERT = 268435479;

    private static final long THROTTLE_MS = 32L;

    private static Haptics sInstance;

    static synchronized Haptics get(Context c) {
        if (sInstance == null) {
            sInstance = new Haptics(c.getApplicationContext());
        }
        return sInstance;
    }

    private final Context app;
    private final Vibrator vibrator;
    private final boolean compositionSupported;

    /** miuix.util.HapticFeedbackCompat 实例，不可用时为 null。 */
    private Object compat;
    private Method compatPerform;
    private boolean compatTried;

    private View hostView;
    private long lastAt;
    private int lastEffect;

    private Haptics(Context c) {
        app = c;
        Vibrator v = null;
        try {
            v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable ignored) {
            // 无振动器
        }
        vibrator = v;
        compositionSupported = Build.VERSION.SDK_INT >= 30 && v != null
                && v.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_THUD);
    }

    /** 提供一个 View 作为 performHapticFeedback 的宿主（通常是 Activity 根 View）。 */
    void attach(View v) {
        hostView = v;
    }

    private boolean enabled() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return false;
        }
        try {
            if (Settings.System.getInt(app.getContentResolver(),
                    "haptic_feedback_disable", 0) != 0) {
                return false;
            }
            return Settings.System.getInt(app.getContentResolver(),
                    Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    // ---------------------------------------------------------------- 语义入口

    /** 轻点：列表行、底栏 tab、二级菜单项。 */
    void tick() {
        play(TAP_LIGHT);
    }

    /** 按钮：主按钮更实、次按钮更轻。 */
    void click() {
        play(BUTTON_SMALL);
    }

    void clickPrimary() {
        play(BUTTON_MIDDLE);
    }

    /** 开关切换。 */
    void toggle() {
        play(SWITCH);
    }

    /** 滑杆每格。 */
    void step() {
        play(MESH_LIGHT);
    }

    /** 滚动到顶/底的边界。 */
    void edge() {
        play(SCROLL_EDGE);
    }

    /** 左右滑动翻页。 */
    void flip() {
        play(FLICK_LIGHT);
    }

    /** 弹出菜单 / 对话框出现。 */
    void popup() {
        play(POPUP_LIGHT);
    }

    /** 破坏性操作确认、失败。 */
    void alert() {
        play(ALERT);
    }

    // ---------------------------------------------------------------- 播放

    private void play(int effect) {
        if (!enabled() || isThrottled(effect)) {
            return;
        }
        if (playCompat(effect)) {
            return;
        }
        if (playView(effect)) {
            return;
        }
        playFallback(effect);
    }

    /** miuix.util.HapticFeedbackCompat#performHapticFeedback(int)。 */
    private boolean playCompat(int effect) {
        ensureCompat();
        if (compat == null || compatPerform == null) {
            return false;
        }
        try {
            Object r = compatPerform.invoke(compat, effect);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            compat = null;
            compatPerform = null;
            return false;
        }
    }

    private void ensureCompat() {
        if (compatTried) {
            return;
        }
        compatTried = true;
        try {
            Class<?> cls = Class.forName("miuix.util.HapticFeedbackCompat");
            Constructor<?> ctor = cls.getConstructor(Context.class);
            compat = ctor.newInstance(app);
            compatPerform = cls.getMethod("performHapticFeedback", int.class);
        } catch (Throwable t) {
            compat = null;
            compatPerform = null;
        }
    }

    /** View#performHapticFeedback(int)：MIUI 的 DecorView 识别 FLAG_MIUI_HAPTIC。 */
    private boolean playView(int effect) {
        View v = hostView;
        if (v == null) {
            return false;
        }
        try {
            return v.performHapticFeedback(effect);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 最后兜底：Composition 原语或 one-shot 波形。 */
    private void playFallback(int effect) {
        int primitive;
        long ms;
        int amp;
        if (effect == SWITCH || effect == BUTTON_MIDDLE || effect == BUTTON_LARGE) {
            primitive = VibrationEffect.Composition.PRIMITIVE_THUD;
            ms = 14;
            amp = 150;
        } else if (effect == SCROLL_EDGE || effect == MESH_NORMAL) {
            primitive = VibrationEffect.Composition.PRIMITIVE_LOW_TICK;
            ms = 8;
            amp = 60;
        } else if (effect == BUTTON_SMALL || effect == ALERT) {
            primitive = VibrationEffect.Composition.PRIMITIVE_CLICK;
            ms = 10;
            amp = 110;
        } else {
            primitive = VibrationEffect.Composition.PRIMITIVE_TICK;
            ms = 6;
            amp = 70;
        }
        try {
            if (compositionSupported) {
                VibrationEffect.Composition c = VibrationEffect.startComposition();
                c.addPrimitive(primitive, 1f);
                vibrator.vibrate(c.compose());
            } else if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, amp));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Throwable ignored) {
            // 静默
        }
    }

    private boolean isThrottled(int effect) {
        long now = android.os.SystemClock.uptimeMillis();
        if (effect == lastEffect && now - lastAt < THROTTLE_MS) {
            return true;
        }
        lastEffect = effect;
        lastAt = now;
        return false;
    }
}
