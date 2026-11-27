package io.github.zpan2327.hyperhand;

import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.LocaleList;

import java.util.Locale;

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
     * 启动时对齐一次，两条规则：
     * <ol>
     *   <li>第一次启动：偏好是空的，写入默认值并同步给系统（于是「默认简体中文」对系统语言是英文的用户同样成立）；</li>
     *   <li>之后：以系统里存的应用级语言为准反向更新偏好——用户可能在「系统设置 → 应用 → HyperHand →
     *       语言」里改过，这里不能把他的选择覆盖回旧值。</li>
     * </ol>
     * 两边本来就一致时什么都不写，不会每次冷启都触发一次无谓的 Activity 重建。
     */
    public static void applyOnStart(Context c) {
        SharedPreferences p = prefs(c);
        try {
            LocaleManager lm = (LocaleManager) c.getSystemService(Context.LOCALE_SERVICE);
            if (lm == null || !p.contains(KEY)) {
                if (!p.contains(KEY)) p.edit().putString(KEY, DEFAULT_CODE).apply();
                apply(c, DEFAULT_CODE);
                return;
            }
            String systemCode = codeOf(lm.getApplicationLocales());
            String saved = p.getString(KEY, DEFAULT_CODE);
            if (systemCode != null) {
                // 系统侧有明确值：以它为准（包括「跟随系统」= 空列表）。
                if (!systemCode.equals(saved)) p.edit().putString(KEY, systemCode).apply();
                return;
            }
            // 系统侧是本模块不认识的语言（例如用户在系统里选了日语）：保持资源回退到默认，
            // 偏好不动，由下面的 apply 把它拉回来。
            apply(c, saved);
        } catch (Throwable ignored) {
            apply(c, prefs(c).getString(KEY, DEFAULT_CODE));
        }
    }

    /**
     * 把系统里的应用级语言换算成 {@link #CODES} 里的 code。
     * 空列表 = 跟随系统；认不出 = null。系统设置里可能给出带地区的写法（如 zh-Hans-CN、en-US），
     * 所以按「语言 + 文字」匹配，而不是比较整串。
     */
    private static String codeOf(LocaleList ll) {
        if (ll == null || ll.isEmpty()) return "system";
        Locale sys = ll.get(0);
        for (String code : CODES) {
            if ("system".equals(code)) continue;
            Locale known = Locale.forLanguageTag(code);
            if (!known.getLanguage().equals(sys.getLanguage())) continue;
            String knownScript = known.getScript();
            String sysScript = sys.getScript();
            if (!sysScript.isEmpty()) {
                if (sysScript.equals(knownScript)) return code;
                continue;
            }
            if (knownScript.isEmpty()) return code;          // 不区分文字的语言（英文）
            // 系统只给了地区：按地区推断文字（简体：大陆 / 新加坡 / 马来西亚；繁體：台 / 港 / 澳）
            String country = sys.getCountry();
            if ("Hans".equals(knownScript) && ("CN".equals(country) || "SG".equals(country) || "MY".equals(country))) {
                return code;
            }
            if ("Hant".equals(knownScript)
                    && ("TW".equals(country) || "HK".equals(country) || "MO".equals(country))) {
                return code;
            }
        }
        return null;
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
