package io.github.zpan2327.hyperhand.smartkey.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 应用清单里的一条：只放「列表要显示的东西」，图标不进来。 */
data class AppEntry(val pkg: String, val label: String, val system: Boolean)

/**
 * 已安装应用的清单与图标缓存。
 *
 * ## 为什么不能只是「进程内 List 缓存」
 *
 * 之前选择器里有一个进程级的 `AppCache`，但它是**纯内存**的：进程被回收、或者从设置页
 * 重新进入智能按键映射（新进程）之后，第一次打开选择器仍然要重新枚举全部应用。
 * 而旧实现把三件重活串在同一个线程里做：
 *
 * 1. `getInstalledPackages(GET_META_DATA)` —— 几百个包的 PackageInfo；
 * 2. 逐个 `loadLabel` —— 解析每个包的资源表；
 * 3. 逐个 `loadIcon` —— 这条最贵，每个图标都要打开别的 APK 的资源流并解码，
 *    真机 400+ 应用实测是**秒级**，并且一次性把几百个 Drawable 常驻内存。
 *
 * 于是「每次新进程的第一次进选择器」都要等几秒，缓存形同不存在。
 *
 * ## 现在的分层
 *
 * - **磁盘快照**（`filesDir/smartkey_apps.json`）：只存包名/名称/是否系统应用。
 *   冷启动时几毫秒就能读出来，先让列表立刻有内容；
 * - **后台刷新**：`prewarm()` 在智能按键映射任一页面打开时就发起，重新枚举并回写磁盘；
 * - **图标懒加载**：列表只给可见行取图标，走 [LruCache]，不再一次性解码几百个。
 *
 * 所有方法都可以在主线程调用（磁盘读写是几十 KB 的 JSON；枚举在调用方指定线程里跑）。
 */
object AppCatalog {

    private const val TAG = "HHApps"
    private const val FILE_NAME = "smartkey_apps.json"
    private const val FILE_VERSION = 1

    @Volatile
    private var entries: List<AppEntry>? = null

    /** 包名 → 显示名。列表与条件摘要共用，避免重复 loadLabel。 */
    @Volatile
    private var labelIndex: Map<String, String> = emptyMap()

    @Volatile
    private var recommendedPkgs: Set<String>? = null

    @Volatile
    private var prewarmed = false

    /**
     * 列表图标：几百个 Drawable 全留会白占几十 MB，只留最近用过的。
     * 一屏可见约 8 行，128 条足够覆盖几次来回滚动。
     */
    private val icons = LruCache<String, Drawable>(128)

    /** 清单里没有、但别处要显示名称的包（例如规则里引用了已卸载应用后重装）。 */
    private val extraLabels = ConcurrentHashMap<String, String>()

    private val lock = Any()

    // ------------------------------------------------------------ 读取

    /** 内存里的清单（没有就返回 null）。 */
    fun cached(): List<AppEntry>? = entries

    /**
     * 取清单：先内存，再磁盘快照。都没有返回 null（调用方自己去 [refresh]）。
     *
     * 磁盘快照是几十 KB 的 JSON，主线程读也只要几毫秒，比白屏几秒好得多。
     */
    fun snapshot(context: Context): List<AppEntry>? {
        entries?.let { return it }
        val started = SystemClock.uptimeMillis()
        val disk = readDisk(context) ?: return null
        synchronized(lock) {
            if (entries == null) publish(disk)
        }
        Log.i(TAG, "disk snapshot: ${disk.size} apps in ${SystemClock.uptimeMillis() - started}ms")
        return entries
    }

    /** 显示名：先查索引，再走 PackageManager 兜底（查不到就退回包名）。 */
    fun labelOf(context: Context, pkg: String): String {
        labelIndex[pkg]?.let { return it }
        extraLabels[pkg]?.let { return it }
        val label = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() ?: pkg
        extraLabels[pkg] = label
        return label
    }

    /** 行图标：LRU 命中直接返回，否则现取现缓存。**只能在后台线程调**。 */
    fun icon(context: Context, pkg: String): Drawable? {
        icons.get(pkg)?.let { return it }
        val d = runCatching { context.packageManager.getApplicationIcon(pkg) }.getOrNull()
        if (d != null) icons.put(pkg, d)
        return d
    }

    /** 缓存里已有的图标（组合期用，避免在组合里做 IO）。 */
    fun cachedIcon(pkg: String): Drawable? = icons.get(pkg)

    // ------------------------------------------------------------ 刷新

    /**
     * 进程内预热：第一次调用时起一个线程，先吃磁盘快照、再重新枚举。
     * 幂等，可在每个页面的 `onCreate` 里无脑调用。
     */
    fun prewarm(context: Context) {
        if (prewarmed) return
        prewarmed = true
        val app = context.applicationContext
        Thread({
            runCatching {
                snapshot(app)          // 先让列表可用
                refresh(app)           // 再对一遍真实清单
                recommended(app)       // 顺带把推荐集合算好，选择器组合期就不用再查 PackageManager
            }.onFailure { Log.w(TAG, "prewarm failed: $it") }
        }, "app-catalog").start()
    }

    /**
     * 重新枚举已安装应用（不含图标）并回写磁盘。
     *
     * @return 新的清单（已排序：用户应用在前，各自按名称）
     */
    fun refresh(context: Context): List<AppEntry> {
        val started = SystemClock.uptimeMillis()
        val pm = context.packageManager
        // 不需要 GET_META_DATA：图标与名称都在 ApplicationInfo 上，带上它只是多解析一遍 meta-data。
        val infos = pm.getInstalledApplications(0)
        val out = ArrayList<AppEntry>(infos.size)
        for (ai in infos) {
            val pkg = ai.packageName ?: continue
            val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg)
            val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            out.add(AppEntry(pkg, label, system))
        }
        out.sortWith(compareBy({ it.system }, { it.label.lowercase() }))
        publish(out)
        writeDisk(context, out)
        Log.i(TAG, "scan: ${out.size} apps in ${SystemClock.uptimeMillis() - started}ms")
        return out
    }

    // ------------------------------------------------------------ 推荐

    /**
     * 推荐应用（当前是媒体类：音乐 / 播客 / 本地播放器）。
     *
     * 判定三条，都只在**已安装**里取，不会推荐用户没装的应用：
     * 1. 声明了 `android.intent.category.APP_MUSIC` 启动入口——这是「音乐应用」最强的信号
     *    （系统桌面也据此把它们归到音乐类）；
     * 2. [KNOWN_MEDIA_APPS] 里的知名包名——兜住少数没声明上面分类的老播放器；
     * 3. 声明了 `MediaBrowserService` **且不是系统应用**——补第三方播放器，同时避免把
     *    「蓝牙」「系统搜索」这类同样声明了该服务的系统组件误判成音乐应用
     *    （模拟器上实测这两个都会被第 3 条老规则命中）。
     *
     * 结果按进程缓存，`refresh()` 之后自动失效。
     */
    fun recommended(context: Context): Set<String> {
        recommendedPkgs?.let { return it }
        val installedAll = entries ?: emptyList()
        val installed = installedAll.mapTo(HashSet()) { it.pkg }
        val systemPkgs = installedAll.filter { it.system }.mapTo(HashSet()) { it.pkg }
        val out = HashSet<String>()
        if (installed.isNotEmpty()) {
            // 1) 音乐类启动入口
            runCatching {
                val intent = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_APP_MUSIC)
                context.packageManager.queryIntentActivities(intent, 0).forEach { ri ->
                    val pkg = ri.activityInfo?.packageName
                    if (pkg != null && pkg in installed) out.add(pkg)
                }
            }.onFailure { Log.w(TAG, "query APP_MUSIC failed: $it") }

            // 3) MediaBrowserService，且排除系统应用
            runCatching {
                val intent = Intent(MEDIA_BROWSER_SERVICE)
                context.packageManager.queryIntentServices(intent, 0).forEach { si ->
                    val pkg = si.serviceInfo?.packageName
                    if (pkg != null && pkg in installed && pkg !in systemPkgs) out.add(pkg)
                }
            }.onFailure { Log.w(TAG, "query media services failed: $it") }

            // 2) 已知包名兜底
            for (pkg in KNOWN_MEDIA_APPS) {
                if (pkg in installed) out.add(pkg)
            }
        }
        recommendedPkgs = out
        return out
    }

    // ------------------------------------------------------------ 内部

    private fun publish(list: List<AppEntry>) {
        entries = list
        labelIndex = HashMap<String, String>(list.size).apply {
            list.forEach { put(it.pkg, it.label) }
        }
        recommendedPkgs = null   // 装了/卸了应用之后推荐列表要重算
    }

    private fun diskFile(context: Context) = File(context.filesDir, FILE_NAME)

    private fun readDisk(context: Context): List<AppEntry>? {
        val file = diskFile(context)
        if (!file.isFile) return null
        return runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("v", 0) != FILE_VERSION) return null
            val arr = root.optJSONArray("apps") ?: return null
            val out = ArrayList<AppEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.optJSONArray(i) ?: continue
                val pkg = row.optString(0)
                if (pkg.isEmpty()) continue
                out.add(AppEntry(pkg, row.optString(1, pkg), row.optBoolean(2, false)))
            }
            if (out.isEmpty()) null else out
        }.getOrNull()
    }

    private fun writeDisk(context: Context, list: List<AppEntry>) {
        // 预热线程与下拉刷新可能同时回写，串行化避免两个线程写同一个 .tmp。
        synchronized(lock) {
            runCatching {
                val arr = JSONArray()
                list.forEach { arr.put(JSONArray().put(it.pkg).put(it.label).put(it.system)) }
                val root = JSONObject()
                    .put("v", FILE_VERSION)
                    .put("ts", System.currentTimeMillis())
                    .put("apps", arr)
                val target = diskFile(context)
                val tmp = File(target.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(root.toString())
                if (!tmp.renameTo(target)) {
                    target.delete()
                    tmp.renameTo(target)
                }
            }.onFailure { Log.w(TAG, "write cache failed: $it") }
        }
    }

    private const val MEDIA_BROWSER_SERVICE = "android.media.browse.MediaBrowserService"

    /**
     * 常见媒体应用的包名兜底。
     *
     * 只做「已安装才推荐」，所以列多了不会出错，只是帮那些没有声明
     * `MediaBrowserService` 的老播放器也能进推荐位。
     */
    private val KNOWN_MEDIA_APPS = listOf(
        // 国内音乐
        "com.netease.cloudmusic",           // 网易云音乐
        "com.tencent.qqmusic",              // QQ 音乐
        "com.kugou.android",                // 酷狗音乐
        "com.kuwo.player",                  // 酷我音乐
        "cn.kuwo.player",                   // 酷我音乐（旧包名）
        "com.ximalaya.ting.android",        // 喜马拉雅
        "com.tencent.karaoke",              // 全民 K 歌
        "com.miui.player",                  // 小米音乐
        "com.huawei.music",                 // 华为音乐
        "com.heytap.music",                 // OPPO 音乐
        "com.vivo.music",                   // vivo 音乐
        "com.sec.android.app.music",        // 三星音乐
        // 本地 / 第三方播放器
        "com.luna.music",                   // 椒盐音乐
        "com.trim.music",                   // Trim Music
        "com.maxmpz.audioplayer",           // Poweramp
        "org.videolan.vlc",                 // VLC
        "com.videolan.vlc",
        // 海外
        "com.spotify.music",                // Spotify
        "com.google.android.apps.youtube.music", // YouTube Music
        "com.apple.android.music",          // Apple Music
        "com.amazon.mp3",                   // Amazon Music
        "deezer.android.app",               // Deezer
    )
}
