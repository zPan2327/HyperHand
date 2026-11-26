package io.github.zpan2327.hyperhand.smartkey.hook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Hook 侧规则数据模型（运行在 system_server）。
 *
 * <p>刻意与 UI 侧（glass 模块 Kotlin {@code RuleModel.kt}）的 JSON 字段一一对应，
 * 但<b>不</b>直接依赖那边的类——Hook 与 UI 天然跨进程跨 ClassLoader，
 * 通过 {@link Settings.Global}（见 {@link SkConfig}）里的 JSON 字符串解耦。
 *
 * <p>解析策略（对齐需求"配置损坏保留最后有效配置/失败安全"）：
 * <ul>
 *   <li>整体 JSON 解析失败 → 抛 {@link RuntimeException}，由 SkConfig 保留旧快照；</li>
 *   <li>单条规则/条件/映射字段非法 → 跳过该条，不影响其它规则；</li>
 *   <li>未知 type/press/action → 保留原始字符串，求值时按"不匹配"处理（保守透传）。</li>
 * </ul>
 */
final class SkModel {

    // ---- 条件类型 id（与 ConditionType.id 一致）----
    static final String C_APP_PLAYING        = "app_playing";
    static final String C_APP_RUNNING        = "app_running";
    static final String C_APP_NOT_RUNNING    = "app_not_running";
    static final String C_ANY_APP_PLAYING    = "any_app_playing";
    static final String C_APP_FOREGROUND     = "app_foreground";
    static final String C_APP_NOT_FOREGROUND = "app_not_foreground";
    static final String C_APP_PAUSED         = "app_paused";
    static final String C_APP_NO_SESSION     = "app_no_session";
    static final String C_SCREEN_ON          = "screen_on";
    static final String C_SCREEN_OFF         = "screen_off";
    static final String C_UNLOCKED           = "unlocked";
    static final String C_LOCKED             = "locked";
    static final String C_CHARGING           = "charging";
    static final String C_NOT_CHARGING       = "not_charging";
    static final String C_AUDIO_PLAYING      = "audio_playing";
    static final String C_AUDIO_PAUSED       = "audio_paused";
    static final String C_AUDIO_STOPPED      = "audio_stopped";
    static final String C_NO_MEDIA_SESSION   = "no_media_session";
    static final String C_MULTIPLE_SESSIONS  = "multiple_sessions";

    // ---- 按键 / 按压 / 动作 id ----
    static final String K_VOLUME_UP   = "volume_up";
    static final String K_VOLUME_DOWN = "volume_down";

    static final String P_SHORT  = "short";
    static final String P_LONG   = "long";
    static final String P_DOUBLE = "double";
    static final String P_REPEAT = "repeat";

    static final String A_PASS = "pass_through";
    static final String A_NEXT = "next";
    static final String A_PREVIOUS = "previous";
    static final String A_PLAY = "play";
    static final String A_PAUSE = "pause";
    static final String A_PLAY_PAUSE = "play_pause";

    private SkModel() {}

    /** 一个触发条件。 */
    static final class Condition {
        final String type;
        final boolean enabled;
        final List<String> packages;

        Condition(String type, boolean enabled, List<String> packages) {
            this.type = type;
            this.enabled = enabled;
            this.packages = packages == null ? Collections.<String>emptyList() : packages;
        }

        static Condition fromJson(JSONObject o) {
            String type = o.optString("type", "");
            if (type.length() == 0) return null;
            List<String> pkgs = new ArrayList<String>();
            JSONArray arr = o.optJSONArray("packages");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String p = arr.optString(i, "");
                    if (p.length() > 0) pkgs.add(p);
                }
            }
            return new Condition(type, o.optBoolean("enabled", true), pkgs);
        }
    }

    /** 一条按键映射。 */
    static final class Mapping {
        final String key;
        final String press;
        final String action;
        final int longPressMs;
        final int doubleTapMs;
        final int repeatCount;

        Mapping(String key, String press, String action,
                int longPressMs, int doubleTapMs, int repeatCount) {
            this.key = key;
            this.press = press;
            this.action = action;
            this.longPressMs = clamp(longPressMs, 100, 5000, 500);
            this.doubleTapMs = clamp(doubleTapMs, 100, 2000, 300);
            this.repeatCount = clamp(repeatCount, 2, 20, 3);
        }

        static Mapping fromJson(JSONObject o) {
            String key = o.optString("key", "");
            if (key.length() == 0) return null;
            return new Mapping(
                    key,
                    o.optString("press", P_SHORT),
                    o.optString("action", A_PASS),
                    o.optInt("longPressMs", 500),
                    o.optInt("doubleTapMs", 300),
                    o.optInt("repeatCount", 3)
            );
        }

        private static int clamp(int v, int lo, int hi, int def) {
            if (v < lo || v > hi) return def;
            return v;
        }
    }

    /** 一条完整规则。 */
    static final class Rule {
        final String id;
        final String name;
        final boolean enabled;
        final boolean andLogic;
        final int priority;
        final List<Condition> conditions;
        final List<Mapping> mappings;

        Rule(String id, String name, boolean enabled, boolean andLogic, int priority,
             List<Condition> conditions, List<Mapping> mappings) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.andLogic = andLogic;
            this.priority = priority;
            this.conditions = conditions;
            this.mappings = mappings;
        }

        static Rule fromJson(JSONObject o) {
            String id = o.optString("id", "");
            if (id.length() == 0) return null;
            List<Condition> conds = new ArrayList<Condition>();
            JSONArray ca = o.optJSONArray("conditions");
            if (ca != null) {
                for (int i = 0; i < ca.length(); i++) {
                    JSONObject c = ca.optJSONObject(i);
                    if (c == null) continue;
                    Condition cond = Condition.fromJson(c);
                    if (cond != null) conds.add(cond);
                }
            }
            List<Mapping> maps = new ArrayList<Mapping>();
            JSONArray ma = o.optJSONArray("mappings");
            if (ma != null) {
                for (int i = 0; i < ma.length(); i++) {
                    JSONObject m = ma.optJSONObject(i);
                    if (m == null) continue;
                    Mapping map = Mapping.fromJson(m);
                    if (map != null) maps.add(map);
                }
            }
            String logic = o.optString("logic", "and");
            return new Rule(
                    id,
                    o.optString("name", id),
                    o.optBoolean("enabled", true),
                    !"or".equalsIgnoreCase(logic),
                    o.optInt("priority", 0),
                    conds,
                    maps
            );
        }
    }

    /** 一次读取的完整快照（不可变，供热路径只读）。 */
    static final class Snapshot {
        final long version;
        final String checksum;
        final boolean master;
        final List<Rule> rules;

        Snapshot(long version, String checksum, boolean master, List<Rule> rules) {
            this.version = version;
            this.checksum = checksum;
            this.master = master;
            this.rules = rules;
        }
    }

    /**
     * 解析规则数组 JSON。任何结构性错误（非数组、JSON 非法）抛异常，
     * 由调用方保留旧快照；单条规则非法则跳过。
     */
    static List<Rule> parseRules(String json) {
        if (json == null || json.length() == 0) return Collections.emptyList();
        final JSONArray arr;
        try {
            arr = new JSONArray(json);   // 非法 JSON 会抛 JSONException（受检）
        } catch (org.json.JSONException e) {
            throw new RuntimeException("invalid rules json", e);
        }
        List<Rule> out = new ArrayList<Rule>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Rule r = Rule.fromJson(o);
            if (r != null) out.add(r);
        }
        // 优先级降序：高优先级先匹配（与 UI 侧 loadRules 排序一致）
        Collections.sort(out, new java.util.Comparator<Rule>() {
            @Override
            public int compare(Rule a, Rule b) {
                return Integer.compare(b.priority, a.priority);
            }
        });
        return out;
    }
}
