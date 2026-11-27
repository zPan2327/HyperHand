package io.github.zpan2327.hyperhand.smartkey.data

import io.github.zpan2327.hyperhand.glass.R
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings

/**
 * 模块运行状态检测（需求 §8）——所有字段尽量来自真实检测，不伪造。
 *
 * 说明：Hook 层在 system_server，主应用无法直接拿到它的内存状态。这里用两条真实来源：
 * 1. LSPosed 安装/模块存在：查包管理器（真实）。
 * 2. Hook 是否成功安装：读取 Hook 层写入 Settings.Global 的探针值（真实上报）。
 *    未上报就是"未知/未激活"，绝不猜成"已激活"。
 */
data class ModuleStatus(
    val lsposedInstalled: Boolean,
    val moduleInstalled: Boolean,
    val hookActive: Boolean,
    val lastHookReport: String,
    val lastConfigSync: String,
    val ruleCount: Int,
) {
    companion object {
        fun detect(context: Context, ruleCount: Int): ModuleStatus {
            val pm = context.packageManager
            val lsposed = isInstalled(pm, "org.lsposed.manager") ||
                isInstalled(pm, "org.lsposed.lspd")
            val module = isInstalled(pm, "io.github.zpan2327.hyperhand")

            // Hook 探针：Hook 安装成功后由 system_server 写入 Settings.Global（系统进程有权限）。
            val hookReport = safeGetGlobal(context, KEY_HOOK_PROBE)
            val hookActive = hookReport != null && hookReport.isNotBlank()
            // 配置同步时间由 App 写入 Settings.Global（首次写入时用 root 授予 WRITE_SECURE_SETTINGS）。
            val sync = safeGetGlobal(context, KEY_LAST_SYNC) ?: context.getString(R.string.sk_142)

            return ModuleStatus(
                lsposedInstalled = lsposed,
                moduleInstalled = module,
                hookActive = hookActive,
                lastHookReport = hookReport?.let(::formatTimestamp)
                    ?: context.getString(R.string.sk_143),
                lastConfigSync = sync,
                ruleCount = ruleCount,
            )
        }

        private fun isInstalled(pm: PackageManager, pkg: String): Boolean = try {
            pm.getPackageInfo(pkg, 0); true
        } catch (t: Throwable) { false }

        /** Hook 上报的是毫秒时间戳，展示时格式化成可读时间；非纯数字则原样返回。 */
        private fun formatTimestamp(raw: String): String {
            val ms = raw.trim().toLongOrNull() ?: return raw
            return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(ms))
        }

        private fun safeGetGlobal(context: Context, key: String): String? = try {
            Settings.Global.getString(context.contentResolver, key)
        } catch (t: Throwable) { null }

        const val KEY_HOOK_PROBE = "hyperhand_sk_hook_probe"
        const val KEY_LAST_SYNC = "hyperhand_sk_last_sync"
    }
}
