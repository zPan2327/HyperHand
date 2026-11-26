package io.github.zpan2327.hyperhand.smartkey.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 智能按键映射的规则数据模型。
 *
 * 设计目标（对齐需求 §5.4）：
 * - 一条规则 = 条件列表 + 条件组合逻辑 + 一个或多个按键映射 + 优先级 + 启用状态。
 * - 条件、按键、动作都用「类型 + 参数」的字符串标识，便于未来增加新类型而不改存储结构。
 * - 与 Hook 层完全解耦：Hook 只读 Settings.Global 里序列化后的配置，不直接依赖这些类。
 */

/** 条件组合逻辑。默认 AND，预留 OR 与分组。 */
enum class Logic(val id: String) {
    AND("and"),
    OR("or");

    companion object {
        fun from(id: String?): Logic = values().firstOrNull { it.id == id } ?: AND
    }
}

/** 条件类别，决定 UI 用哪个选择器、引擎用哪个求值器。 */
enum class ConditionType(val id: String) {
    APP_PLAYING("app_playing"),            // 指定应用正在播放音频
    APP_RUNNING("app_running"),            // 指定应用处于运行状态
    APP_NOT_RUNNING("app_not_running"),    // 指定应用没有运行
    ANY_APP_PLAYING("any_app_playing"),    // 任意应用正在播放音频
    APP_FOREGROUND("app_foreground"),      // 指定应用处于前台
    APP_NOT_FOREGROUND("app_not_foreground"),
    APP_PAUSED("app_paused"),              // 指定应用已暂停播放
    APP_NO_SESSION("app_no_session"),      // 指定应用没有活动的媒体会话
    SCREEN_ON("screen_on"),
    SCREEN_OFF("screen_off"),
    UNLOCKED("unlocked"),
    LOCKED("locked"),
    CHARGING("charging"),
    NOT_CHARGING("not_charging"),
    AUDIO_PLAYING("audio_playing"),
    AUDIO_PAUSED("audio_paused"),
    AUDIO_STOPPED("audio_stopped"),
    NO_MEDIA_SESSION("no_media_session"),
    MULTIPLE_SESSIONS("multiple_sessions");

    companion object {
        fun from(id: String?): ConditionType? = values().firstOrNull { it.id == id }
    }
}

/**
 * 一个触发条件。
 * @param packages 目标应用包名列表（应用类条件使用，支持多选 + 包名精确识别）
 */
data class Condition(
    val type: ConditionType,
    val packages: List<String> = emptyList(),
    val enabled: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type.id)
        put("enabled", enabled)
        put("packages", JSONArray(packages))
    }

    companion object {
        fun fromJson(o: JSONObject): Condition? {
            val type = ConditionType.from(o.optString("type")) ?: return null
            val arr = o.optJSONArray("packages")
            val pkgs = mutableListOf<String>()
            if (arr != null) for (i in 0 until arr.length()) pkgs.add(arr.optString(i))
            return Condition(type, pkgs, o.optBoolean("enabled", true))
        }
    }
}

/** 物理按键类型。第一阶段支持音量加/减，架构预留其它。 */
enum class KeyType(val id: String, val keyCode: Int) {
    VOLUME_UP("volume_up", 24),
    VOLUME_DOWN("volume_down", 25);

    companion object {
        fun from(id: String?): KeyType? = values().firstOrNull { it.id == id }
    }
}

/** 按压方式。 */
enum class PressType(val id: String) {
    SHORT("short"),
    LONG("long"),
    DOUBLE("double"),
    REPEAT("repeat");

    companion object {
        fun from(id: String?): PressType = values().firstOrNull { it.id == id } ?: SHORT
    }
}

/** 执行动作。第一阶段支持媒体控制 + 透传。 */
enum class ActionType(val id: String) {
    PASS_THROUGH("pass_through"),   // 不更改原有功能 / 保留系统原始行为
    NEXT("next"),
    PREVIOUS("previous"),
    PLAY("play"),
    PAUSE("pause"),
    PLAY_PAUSE("play_pause");

    companion object {
        fun from(id: String?): ActionType = values().firstOrNull { it.id == id } ?: PASS_THROUGH
    }
}

/** 一条按键映射：某按键 + 某按压方式 → 某动作。 */
data class KeyMapping(
    val key: KeyType,
    val press: PressType = PressType.SHORT,
    val action: ActionType = ActionType.PASS_THROUGH,
    val longPressMs: Int = 500,      // 长按触发时间
    val doubleTapMs: Int = 300,      // 双击识别间隔
    val repeatCount: Int = 3,        // 连续按压次数
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key.id)
        put("press", press.id)
        put("action", action.id)
        put("longPressMs", longPressMs)
        put("doubleTapMs", doubleTapMs)
        put("repeatCount", repeatCount)
    }

    companion object {
        fun fromJson(o: JSONObject): KeyMapping? {
            val key = KeyType.from(o.optString("key")) ?: return null
            return KeyMapping(
                key = key,
                press = PressType.from(o.optString("press")),
                action = ActionType.from(o.optString("action")),
                longPressMs = o.optInt("longPressMs", 500),
                doubleTapMs = o.optInt("doubleTapMs", 300),
                repeatCount = o.optInt("repeatCount", 3),
            )
        }
    }
}

/** 一条完整规则。 */
data class Rule(
    val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    val logic: Logic = Logic.AND,
    val conditions: List<Condition> = emptyList(),
    val mappings: List<KeyMapping> = emptyList(),
    val priority: Int = 0,
    var lastTriggeredAt: Long = 0L,
    var lastActionResult: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("description", description)
        put("enabled", enabled)
        put("logic", logic.id)
        put("priority", priority)
        put("lastTriggeredAt", lastTriggeredAt)
        put("lastActionResult", lastActionResult)
        put("conditions", JSONArray().apply { conditions.forEach { put(it.toJson()) } })
        put("mappings", JSONArray().apply { mappings.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(o: JSONObject): Rule? {
            val id = o.optString("id") ?: return null
            if (id.isEmpty()) return null
            val conditions = mutableListOf<Condition>()
            o.optJSONArray("conditions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    Condition.fromJson(arr.optJSONObject(i) ?: continue)?.let { conditions.add(it) }
                }
            }
            val mappings = mutableListOf<KeyMapping>()
            o.optJSONArray("mappings")?.let { arr ->
                for (i in 0 until arr.length()) {
                    KeyMapping.fromJson(arr.optJSONObject(i) ?: continue)?.let { mappings.add(it) }
                }
            }
            return Rule(
                id = id,
                name = o.optString("name"),
                description = o.optString("description"),
                enabled = o.optBoolean("enabled", true),
                logic = Logic.from(o.optString("logic")),
                priority = o.optInt("priority", 0),
                lastTriggeredAt = o.optLong("lastTriggeredAt", 0L),
                lastActionResult = o.optString("lastActionResult"),
                conditions = conditions,
                mappings = mappings,
            )
        }
    }
}

/** 条件冲突提示：同一按键被多条启用规则映射到不同动作。 */
data class Conflict(
    val key: KeyType,
    val press: PressType,
    val ruleNames: List<String>,
)
