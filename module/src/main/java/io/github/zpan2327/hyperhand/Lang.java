package io.github.zpan2327.hyperhand;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.LocaleList;

/**
 * 界面语言（应用级 locale）。
 *
 * <p>Android 13 起系统给每个应用存一份独立的语言设置，{@link LocaleManager#setApplicationLocales}
 * 设置后由系统负责重建 Activity、并把资源限定符切到对应语言——不需要自己包 Context、
 * 也不用改 Configuration。这里只负责三件事：记住用户选了什么、把它同步给系统、
 * 启动时把两者对齐（默认简体中文，不是「跟随系统」）。</p>
 *
 * <p>四个选项的 code 与资源目录一一对应：
 * {@code zh-Hans} → {@code values/}（默认）、{@code zh-Hant} → {@code values-b+zh+Hant/}、
 * {@code en} → {@code values-en/}；{@code system} 表示清空应用级设置、交还给系统。</p>
 */
public final class Lang {

    /** 与下拉菜单顺序一一对应；不要单独调整某一项，索引会被持久化。 */
    public static final String[] CODES = {"system", "zh-Hans", "zh-Hant", "en"};

    /** 默认值：简体中文（需求明确「默认使用简体中文」，所以不是 system）。 */
    private static final String DEFAULT_CODE = "zh-Hans";

    private static final String PREF = "hyperhand_ui";
    private static final String KEY = "language";

    private Lang() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 当前选择的索引（下拉菜单用）。 */
    public static int currentIndex(Context c) {
        String code = prefs(c).getString(KEY, DEFAULT_CODE);
        for (int i = 0; i < CODES.length; i++) {
            if (CODES[i].equals(code)) return i;
        }
        return 1;
    }

    /** 下拉菜单里的选项名：语言名一律用它自己的语言书写，不随界面语言变化。 */
    public static String[] names(Context c) {
        return new String[]{
                c.getString(R.string.lang_system),
                c.getString(R.string.lang_zh_hans),
                c.getString(R.string.lang_zh_hant),
                c.getString(R.string.lang_en),
        };
    }

    /** 每个选项对应的一行说明。 */
    public static String[] summaries(Context c) {
        return new String[]{
                c.getString(R.string.lang_system_summary),
                c.getString(R.string.lang_zh_hans_summary),
                c.getString(R.string.lang_zh_hant_summary),
                c.getString(R.string.lang_en_summary),
        };
    }

    /**
     * 选中某一项：先落盘，再交给系统。
     *
     * <p>写入后会触发配置变更，当前 Activity 会被系统重建，页面文案随之切换——
     * 调用方不需要再手动刷新，也不要在这个回调里做其它 UI 操作。</p>
     */
    public static void select(Context c, int index) {
        int i = Math.max(0, Math.min(CODES.length - 1, index));
        prefs(c).edit().putString(KEY, CODES[i]).apply();
        apply(c, CODES[i]);
    }

    /**
     * 启动时对齐一次。
     *
     * <p>只在「偏好与系统里存的值不一致」时写，避免每次冷启都触发一次无谓的 Activity 重建。
     * 首次安装时偏好是 {@code zh-Hans}、系统里是空（跟随系统），这里会补一次，
     * 于是「默认简体中文」对系统语言是英文的用户同样成立。</p>
     */
    public static void applyOnStart(Context c) {
        apply(c, prefs(c).getString(KEY, DEFAULT_CODE));
    }

    private static void apply(Context c, String code) {
        try {
            LocaleManager lm = (LocaleManager) c.getSystemService(Context.LOCALE_SERVICE);
            if (lm == null) return;
            LocaleList target = "system".equals(code)
                    ? LocaleList.getEmptyLocaleList()
                    : LocaleList.forLanguageTags(code);
            if (!target.equals(lm.getApplicationLocales())) {
                lm.setApplicationLocales(target);
            }
        } catch (Throwable ignored) {
            // 系统不支持（或被裁掉）时静默降级：界面保持默认语言，不影响其它功能。
        }
    }
}
