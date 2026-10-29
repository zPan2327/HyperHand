package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * libxposed API 102 的薄封装。
 *
 * legacy API 里的 {@code XposedHelpers.findClassIfExists} / {@code XposedBridge.hookAllMethods} /
 * {@code XposedHelpers.callMethod} 在现代 API 中都不再提供，统一用反射 + {@code hook()} 的
 * 拦截器链实现，钩子点只需要关心"要不要改返回值"。
 */
final class Hooks {

    /** before 钩子返回它，表示"没意见，继续走原方法"。 */
    static final Object PROCEED = new Object();

    interface Before {
        Object run(XposedInterface.Chain chain) throws Throwable;
    }

    interface After {
        void run(XposedInterface.Chain chain, Object result) throws Throwable;
    }

    private final XposedModule module;

    Hooks(XposedModule module) {
        this.module = module;
    }

    static XposedInterface.Hooker before(final Before before) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = before.run(chain);
                return r == PROCEED ? chain.proceed() : r;
            }
        };
    }

    static XposedInterface.Hooker after(final After after) {
        return new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                Object r = chain.proceed();
                after.run(chain, r);
                return r;
            }
        };
    }

    /** 等价于 {@code XposedHelpers.findClassIfExists}：找不到返回 null，不抛异常。 */
    Class<?> findClass(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 等价于 {@code XposedBridge.hookAllMethods}：连父类一起，按方法名匹配，忽略参数表。
     *
     * @return 实际钩上的方法数
     */
    int hookMethods(Class<?> cls, String name, XposedInterface.Hooker hooker) {
        int n = 0;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.isBridge() || m.isSynthetic()) continue;
                if (hook(m, hooker)) n++;
            }
        }
        return n;
    }

    /** 等价于 {@code XposedBridge.hookAllConstructors}。 */
    int hookConstructors(Class<?> cls, XposedInterface.Hooker hooker) {
        int n = 0;
        for (java.lang.reflect.Constructor<?> ct : cls.getDeclaredConstructors()) {
            if (hook(ct, hooker)) n++;
        }
        return n;
    }

    boolean hook(Executable ex, XposedInterface.Hooker hooker) {
        try {
            module.hook(ex)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            return true;
        } catch (Throwable t) {
            Log.w(HyperHand.TAG, "hook " + ex + " failed: " + t);
            return false;
        }
    }

    /** 等价于 {@code XposedHelpers.callMethod(obj, name)}：无参调用，绕过访问检查。 */
    Object call(Object target, String name) throws Throwable {
        Method m = findMethod(target.getClass(), name);
        if (m == null) throw new NoSuchMethodException(name);
        return module.getInvoker(m).invoke(target);
    }

    private static Method findMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
            }
        }
        return null;
    }

    /** 等价于 {@code AndroidAppHelper.currentApplication()}。 */
    static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method m = activityThread.getMethod("currentApplication");
            return (Context) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }
}
