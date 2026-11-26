package io.github.zpan2327.hyperhand.smartkey.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Phase 2 PoC：system_server 进程内 Hook {@code PhoneWindowManager#interceptKeyBeforeQueueing}，
 * 验证"真正拦截原始音量键事件"的六个技术点。
 *
 * <p><b>候选 Hook 点（未验证，需真机确认）：</b>
 * <pre>
 *   类：com.android.server.policy.PhoneWindowManager
 *   方法：int interceptKeyBeforeQueueing(KeyEvent event, int policyFlags)
 *   进程：system_server
 * </pre>
 * 返回 0 = 事件被消费（不再向下分发，系统不会调音量）；返回原方法结果 = 透传。
 *
 * <p>健壮性（对齐需求 §6.4 异常恢复）：
 * <ul>
 *   <li>任何 hook 回调内异常都被 PROTECTIVE 模式吞掉，且自身再包一层 try-catch；</li>
 *   <li>配置读取失败 → 不消费（透传），不吞音量键；</li>
 *   <li>媒体调用抛到主线程异步执行，不阻塞 InputDispatcher 线程；</li>
 *   <li>媒体操作只在 ACTION_DOWN && repeatCount==0 触发一次，避免重复切歌；</li>
 *   <li>hook 安装幂等 + 失败可重试（onModuleLoaded / onPackageLoaded 双入口）。</li>
 * </ul>
 */
public final class KeyHookEntry {

    private static final String TAG = PoCConfig.TAG;
    private static final String CLS_PWM = "com.android.server.policy.PhoneWindowManager";
    private static final String METHOD = "interceptKeyBeforeQueueing";

    /** 表示"透传"的内部哨兵 —— 与下面可能的整数返回值区分开。 */
    private static final Object PASS = new Object();

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /** 正式规则引擎单例（PoC 通道关闭时使用）。 */
    private static final SkEngine ENGINE = new SkEngine(new SkEngine.ActionSink() {
        @Override
        public void fire(String action, java.util.List<String> pkgs) {
            final Context c = currentApplication();
            if (c != null) MediaActions.performAsync(c, action, pkgs);
        }

        @Override
        public void adjustVolume(int keyCode) {
            final Context c = currentApplication();
            if (c != null) MediaActions.adjustVolumeAsync(c, keyCode);
        }
    });

    private final XposedModule module;

    public KeyHookEntry(XposedModule module) {
        this.module = module;
    }

    /** 由 onModuleLoaded(isSystemServer) / onPackageLoaded 调用，双入口幂等。 */
    public void install(ClassLoader cl) {
        Log.i(TAG, "KeyHookEntry.install called, uid=" + android.os.Process.myUid()
                + ", cl=" + (cl == null ? "null" : cl.getClass().getName()));
        // 只在 system_server（UID 1000）安装 —— 双入口可能在高程里从别的进程误触发。
        // PhoneWindowManager 的按键逻辑只在 system_server 有实际意义。
        if (android.os.Process.myUid() != android.os.Process.SYSTEM_UID) {
            Log.i(TAG, "not system_server (uid=" + android.os.Process.myUid() + "), skip");
            return;
        }
        if (INSTALLED.get()) {
            Log.i(TAG, "KeyHookEntry already installed, skip");
            return;
        }
        try {
            // 诊断版：遍历多种 ClassLoader，打印每种方式的真实异常，定位可达路径。
            Class<?> pwm = findClassDiag(CLS_PWM, cl);
            if (pwm == null) {
                Log.w(TAG, "PhoneWindowManager class NOT FOUND via ALL classloaders — PoC 无法安装");
                return;
            }
            Log.i(TAG, "PWM class loaded via diag: " + pwm.getName()
                    + " loader=" + (pwm.getClassLoader() == null ? "bootstrap" : pwm.getClassLoader().getClass().getName()));

            int hooked = 0;
            // 遍历所有同名方法，挑参数含 (KeyEvent, int) 的那个 —— 不假设唯一签名。
            for (Class<?> c = pwm; c != null && c != Object.class; c = c.getSuperclass()) {
                Method[] methods;
                try {
                    methods = c.getDeclaredMethods();
                } catch (Throwable t) {
                    Log.w(TAG, "getDeclaredMethods failed on " + c.getName() + ": " + t);
                    continue;
                }
                for (Method m : methods) {
                    if (!m.getName().equals(METHOD)) continue;
                    if (!isCandidate(m)) continue;
                    Log.i(TAG, "candidate: " + m + " (return=" + m.getReturnType() + ")");
                    try {
                        module.hook(m)
                                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                                .intercept(this::onKey);
                        hooked++;
                        Log.i(TAG, "HOOKED " + METHOD + " in " + c.getName());
                    } catch (Throwable t) {
                        Log.w(TAG, "hook " + METHOD + " failed: " + t);
                    }
                }
            }
            if (hooked == 0) {
                Log.w(TAG, "NO interceptKeyBeforeQueueing hooked — 签名不匹配，需真机 dump 方法表");
                return;
            }
            INSTALLED.set(true);
            Log.i(TAG, "KeyHookEntry installed, hooked=" + hooked);
            // 写真实探针：状态页据此显示"Hook 已激活"，未写就显示未激活，不伪造。
            markHookProbe();
        } catch (Throwable t) {
            Log.w(TAG, "KeyHookEntry.install failed: " + t);
        }
    }

    /** 是否是 KeyEvent + int 参数的候选方法（返回 int 最佳，其它也尝试以抓取签名）。 */
    private static boolean isCandidate(Method m) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length < 1) return false;
        boolean hasKeyEvent = false;
        for (Class<?> t : p) {
            if (t == KeyEvent.class) hasKeyEvent = true;
        }
        return hasKeyEvent;
    }

    /**
     * 按键回调热路径。返回 PASS 表示透传（chain.proceed），返回 Integer 表示消费/改写返回值。
     */
    private Object onKey(XposedInterface.Chain chain) throws Throwable {
        // 先取参数。任何异常都走透传。
        KeyEvent ev = null;
        int policyFlags = 0;
        try {
            for (Object arg : chain.getArgs()) {
                if (arg instanceof KeyEvent && ev == null) ev = (KeyEvent) arg;
                else if (arg instanceof Integer && policyFlags == 0) policyFlags = (Integer) arg;
            }
        } catch (Throwable t) {
            Log.w(TAG, "onKey getArgs failed: " + t);
            return chain.proceed();
        }
        if (ev == null) return chain.proceed();

        final int keyCode = ev.getKeyCode();
        final boolean volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;

        Context ctx = currentApplication();
        PoCConfig cfg = ctx == null ? null : PoCConfig.get(ctx);

        // 非音量键：不动，完整透传。
        if (!volume) return chain.proceed();

        final int action = ev.getAction();
        final int repeat = ev.getRepeatCount();
        final String actName = actionName(action);

        if (cfg != null && cfg.capture) {
            Log.i(TAG, "KEY keyCode=" + keyCode + " action=" + actName
                    + " repeat=" + repeat + " downTime=" + ev.getDownTime()
                    + " flags=" + policyFlags
                    + " cfg[consumeUp=" + cfg.consumeUp + " consumeDown=" + cfg.consumeDown
                    + " mediaNext=" + cfg.mediaNext + "]");
        }

        final boolean isUp = keyCode == KeyEvent.KEYCODE_VOLUME_UP;

        // ---- PoC 回归通道：任一 PoC 开关打开时，沿用 Phase 2 已验证的行为 ----
        // 这样真机可随时用 adb settings put 打开 PoC 开关做底层对照，互不干扰。
        final boolean pocActive = cfg != null
                && (cfg.consumeUp || cfg.consumeDown || cfg.mediaNext);
        if (pocActive) {
            final boolean consume = isUp ? cfg.consumeUp : cfg.consumeDown;
            if (cfg.mediaNext && action == KeyEvent.ACTION_DOWN && repeat == 0 && ctx != null) {
                final Context c = ctx;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        PoCMedia.next(c);
                    }
                });
            }
            if (consume) {
                if (cfg.capture) {
                    Log.i(TAG, "CONSUME(PoC) " + (isUp ? "VOLUME_UP" : "VOLUME_DOWN")
                            + " " + actName);
                }
                return 0;
            }
            return chain.proceed();
        }

        // ---- 正式规则引擎：PoC 开关全关时走这里 ----
        // 动作（含延时的长按 / 双击 / 连按）由引擎自己通过 ActionSink 下发，
        // 这里只决定"这个原始事件要不要吞"。
        SkEngine.Decision d = ENGINE.onKeyEvent(ev, ctx);
        if (d.consume) {
            return 0; // 吞键：系统不调音量、不弹音量条
        }
        return chain.proceed();
    }

    /**
     * 写 Hook 激活探针（epoch ms），供状态页真实检测。
     * onModuleLoaded 时机 currentApplication() 常为 null，故延迟重试到 Context 就绪。
     */
    private static void markHookProbe() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                try {
                    Context ctx = currentApplication();
                    if (ctx == null && tries++ < 12) {
                        new Handler(Looper.getMainLooper()).postDelayed(this, 500);
                        return;
                    }
                    if (ctx == null) return;
                    android.provider.Settings.Global.putString(
                            ctx.getContentResolver(), "hyperhand_sk_hook_probe",
                            Long.toString(System.currentTimeMillis()));
                    Log.i(TAG, "hook probe written");
                } catch (Throwable ignored) {
                    // 探针写失败不影响功能
                }
            }
        }, 500);
    }

    private static String actionName(int action) {
        switch (action) {
            case KeyEvent.ACTION_DOWN:   return "DOWN";
            case KeyEvent.ACTION_UP:     return "UP";
            case KeyEvent.ACTION_MULTIPLE: return "MULTIPLE";
            default: return "ACTION_" + action;
        }
    }

    /** 保留原始简单查找（供其它调用使用）。 */
    private static Class<?> findClass(String name, ClassLoader cl) {
        try {
            if (cl == null) return Class.forName(name, false, Object.class.getClassLoader());
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 诊断版类加载：依次尝试 5 种 ClassLoader 来源，逐个打印成功/真实异常，
     * 用来定位 HyperOS 3 / Android 16 上 system_server framework 类的可达路径。
     */
    private static Class<?> findClassDiag(String name, ClassLoader hint) {
        ClassLoader[] loaders = new ClassLoader[] {
                hint,
                ClassLoader.getSystemClassLoader(),
                Object.class.getClassLoader(),                 // bootstrap
                Thread.currentThread().getContextClassLoader(),
                KeyHookEntry.class.getClassLoader()            // 模块自身
        };
        String[] labels = { "hint", "system", "bootstrap", "contextCL", "moduleCL" };
        for (int i = 0; i < loaders.length; i++) {
            ClassLoader cl = loaders[i];
            try {
                Class<?> c = Class.forName(name, false, cl);
                Log.i(TAG, "tryLoad[" + labels[i] + "] OK loader="
                        + (cl == null ? "null(bootstrap)" : cl.getClass().getName()));
                return c;
            } catch (Throwable t) {
                Log.w(TAG, "tryLoad[" + labels[i] + "] FAIL "
                        + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
            }
        }
        // 最后兜底：从 ActivityThread 的 system Context 取 ClassLoader
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getMethod("currentActivityThread").invoke(null);
            if (cur != null) {
                Object ctx = at.getMethod("getSystemContext").invoke(cur);
                if (ctx != null) {
                    ClassLoader cl = (ClassLoader) ctx.getClass().getMethod("getClassLoader").invoke(ctx);
                    Log.i(TAG, "tryLoad[systemContext] cl=" + (cl == null ? "null" : cl.getClass().getName()));
                    Class<?> c = Class.forName(name, false, cl);
                    Log.i(TAG, "tryLoad[systemContext] OK");
                    return c;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "tryLoad[systemContext] FAIL " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
        }
        return null;
    }

    /** system_server 里拿 Context（不依赖父包的包私有 Hooks 类）。 */
    private static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            return (Context) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
