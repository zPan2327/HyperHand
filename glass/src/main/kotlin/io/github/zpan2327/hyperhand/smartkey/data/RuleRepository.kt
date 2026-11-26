package io.github.zpan2327.hyperhand.smartkey.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import org.json.JSONArray
import java.util.zip.CRC32

/**
 * 规则持久化仓库。
 *
 * 用 SharedPreferences + org.json（Android 内置，零新依赖）存储规则列表。
 * 规则数据量小（通常几条到几十条），SharedPreferences 完全够用且稳定。
 * 所有规则存成一个 JSON 数组字符串，读写整体替换，避免部分写入损坏。
 */
class RuleRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("smartkey_rules", Context.MODE_PRIVATE)

    private val listener = mutableListOf<() -> Unit>()

    fun addListener(cb: () -> Unit) { listener.add(cb) }
    fun removeListener(cb: () -> Unit) { listener.remove(cb) }
    private fun notifyChanged() { listener.toList().forEach { it() } }

    /** 读取全部规则，按优先级降序。损坏的条目会被跳过，不导致崩溃。 */
    fun loadRules(): List<Rule> {
        val raw = prefs.getString(KEY_RULES, null) ?: return emptyList()
        val result = mutableListOf<Rule>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                Rule.fromJson(o)?.let { result.add(it) }
            }
        } catch (t: Throwable) {
            // 配置损坏：返回空列表（安全默认），不崩溃。
        }
        return result.sortedByDescending { it.priority }
    }

    fun saveRules(rules: List<Rule>) {
        val arr = JSONArray()
        rules.forEach { arr.put(it.toJson()) }
        val json = arr.toString()
        val version = (prefs.getLong(KEY_VERSION, 0L) + 1L)
        prefs.edit()
            .putString(KEY_RULES, json)
            .putLong(KEY_VERSION, version)
            .apply()
        // 同步给 system_server 的 Hook 层（跨进程唯一通道）
        syncToHook(json, version)
        notifyChanged()
    }

    /**
     * 把规则写到 Settings.Global 供 Hook 层读取。
     *
     * 这些键名必须与 Hook 侧 SkConfig 完全一致。写入失败（无权限）不抛异常，
     * 状态页会如实显示"尚未同步"——绝不伪装成功。
     */
    private fun syncToHook(json: String, version: Long) {
        if (!ensureSecureSettings()) {
            android.util.Log.w("HyperHandSk", "syncToHook skipped: no WRITE_SECURE_SETTINGS")
            return
        }
        try {
            val cr = context.contentResolver
            Settings.Global.putString(cr, K_RULES, json)
            Settings.Global.putString(cr, K_VERSION, version.toString())
            Settings.Global.putString(cr, K_SUM, checksum(json))
            Settings.Global.putString(
                cr, K_SYNC,
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
            )
        } catch (t: Throwable) {
            // 无 WRITE_SETTINGS 权限时写入可能失败；状态页读取这些键会显示未同步。
        }
    }

    /**
     * 把当前已有规则重新推送到 Hook（App 启动时调用一次）。
     * 版本号沿用本地已存值，不额外递增；仅当 Global 里没有对应键时才真正写入，
     * 避免每次打开页面都改写同步时间造成误判。
     */
    fun syncNow() {
        try {
            val cr = context.contentResolver
            if (Settings.Global.getString(cr, K_RULES) != null) return
            val json = prefs.getString(KEY_RULES, null) ?: "[]"
            val version = prefs.getLong(KEY_VERSION, 0L)
            syncToHook(json, version)
        } catch (t: Throwable) { }
    }

    /** CRC32（UTF-8）十进制字符串，必须与 Hook 侧 SkConfig.crc32() 一致。 */
    private fun checksum(s: String): String {
        val c = CRC32()
        c.update(s.toByteArray(Charsets.UTF_8))
        return c.value.toString()
    }

    fun upsert(rule: Rule) {
        val list = loadRules().toMutableList()
        val idx = list.indexOfFirst { it.id == rule.id }
        if (idx >= 0) list[idx] = rule else list.add(rule)
        saveRules(list)
    }

    fun delete(id: String) {
        saveRules(loadRules().filterNot { it.id == id })
    }

    fun duplicate(id: String): Rule? {
        val src = loadRules().firstOrNull { it.id == id } ?: return null
        val copy = src.copy(
            id = newId(),
            name = "${src.name}（副本）",
            priority = src.priority - 1,
        )
        upsert(copy)
        return copy
    }

    fun setEnabled(id: String, enabled: Boolean) {
        loadRules().firstOrNull { it.id == id }?.let { upsert(it.copy(enabled = enabled)) }
    }

    fun newId(): String = "r_" + System.currentTimeMillis().toString(36) +
        "_" + (1000..9999).random().toString(36)

    /**
     * 规则冲突检测（需求 §3）：多条启用的规则对同一「按键+按压方式」映射了不同动作。
     */
    fun detectConflicts(): List<Conflict> {
        val enabled = loadRules().filter { it.enabled }
        val groups = mutableMapOf<Pair<KeyType, PressType>, MutableList<String>>()
        for (rule in enabled) {
            for (m in rule.mappings) {
                groups.getOrPut(m.key to m.press) { mutableListOf() }.add(rule.name)
            }
        }
        return groups.filter { it.value.size > 1 }
            .map { (k, names) -> Conflict(k.first, k.second, names) }
    }

    /**
     * 确保具备 {@code WRITE_SECURE_SETTINGS}（写 {@link Settings.Global} 的唯一途径）。
     *
     * <p>该权限是 signature|development 级：普通安装拿不到、App 自己也不弹授权框，
     * 但可以被 {@code pm grant} 授予。本模块只服务于 Root/LSPosed 环境，因此首次写配置时
     * 用 su 自动授权一次，之后直接写 Global 无额外延迟。
     *
     * <p>注意：曾尝试改用 {@code Settings.System} 绕开该权限，但 MIUI 的 SettingsProvider
     * 会对自定义 key 抛 {@code IllegalArgumentException: You cannot keep your settings
     * in the secure settings}，故不可行。
     */
    fun ensureSecureSettings(): Boolean {
        if (hasSecureSettings()) return true
        return try {
            val p = Runtime.getRuntime().exec(
                arrayOf(
                    "su", "-c",
                    "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS",
                )
            )
            val code = p.waitFor()
            // 关键：pm grant 成功后，同一进程内的 checkSelfPermission 可能仍读到旧值，
            // 所以不能只用它判断成败；以 su 退出码为准，真正能否写由后续 putString 决定。
            val ok = code == 0 || hasSecureSettings()
            android.util.Log.i("HyperHandSk", "ensureSecureSettings code=$code ok=$ok")
            ok
        } catch (t: Throwable) {
            android.util.Log.w("HyperHandSk", "ensureSecureSettings failed", t)
            false
        }
    }

    private fun hasSecureSettings(): Boolean = try {
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** 总开关（写 Settings.Global，供 Hook 层读取；这里只读回显）。 */
    fun isMasterEnabled(): Boolean = try {
        Settings.Global.getString(
            context.contentResolver, K_MASTER
        ) == "1"
    } catch (t: Throwable) { false }

    /**
     * 切换总开关。
     *
     * @return true 表示已真实写入 Settings.Global；false 表示缺少 WRITE_SECURE_SETTINGS
     *         （root 授权失败）或写入异常。调用方必须据此提示用户，
     *         **不能**让开关看起来切换了、实际却没生效。
     */
    fun setMasterEnabled(enabled: Boolean): Boolean {
        val value = if (enabled) "1" else "0"
        // 先直接写：已授权时这一步就成功，不额外跑 su。
        if (writeMaster(value)) return true
        // 失败通常是首次还没有 WRITE_SECURE_SETTINGS：用 root 授权后重试一次。
        if (!ensureSecureSettings()) return false
        return writeMaster(value)
    }

    private fun writeMaster(value: String): Boolean = try {
        val ok = Settings.Global.putString(context.contentResolver, K_MASTER, value)
        if (ok) notifyChanged()
        if (!ok) android.util.Log.w("HyperHandSk", "putString(master) returned false")
        ok
    } catch (t: Throwable) {
        android.util.Log.w("HyperHandSk", "putString(master) failed", t)
        false
    }

    companion object {
        private const val KEY_RULES = "rules_json"
        private const val KEY_VERSION = "rules_version"

        // ---- 与 Hook 侧 SkConfig 完全一致的 Settings.Global 键名 ----
        const val K_RULES = "hyperhand_sk_rules"
        const val K_VERSION = "hyperhand_sk_rules_ver"
        const val K_SUM = "hyperhand_sk_rules_sum"
        const val K_MASTER = "hyperhand_sk_master"
        const val K_SYNC = "hyperhand_sk_last_sync"
    }
}
