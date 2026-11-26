package io.github.zpan2327.hyperhand.smartkey.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Hook 侧规则配置读取（运行在 system_server）。
 *
 * <p>App 侧把规则 JSON 写进 {@link Settings#GLOBAL}，本类读取并解析成不可变快照。
 * 设计严格对齐需求"通信约束"：
 * <ul>
 *   <li><b>按键热路径零 IPC</b>：内存快照 + 版本号，{@link #get(Context)} 每 400ms 才复核一次
 *       version，未变直接返回同一对象；按键回调只读这个对象。</li>
 *   <li><b>版本 + 校验和</b>：version 变化才重新解析；checksum（CRC32）不匹配视为损坏。</li>
 *   <li><b>失败保留最后有效配置</b>：解析/校验失败时保留上一次成功快照；从未成功则空规则
 *       （= 全部透传），绝不因配置问题吞键。</li>
 *   <li><b>主开关</b>：关闭时快照 {@code master=false}，引擎直接透传。</li>
 * </ul>
 *
 * <p>键名必须与 App 侧 {@code RuleRepository} 完全一致。
 */
final class SkConfig {

    // ---- Settings.Global 键名（与 App 侧 RuleRepository 对齐）----
    static final String K_RULES   = "hyperhand_sk_rules";       // 规则 JSON 数组
    static final String K_VERSION = "hyperhand_sk_rules_ver";   // 版本号（递增字符串）
    static final String K_SUM     = "hyperhand_sk_rules_sum";   // CRC32 校验和（十进制字符串）
    static final String K_MASTER  = "hyperhand_sk_master";      // 总开关 "1"/"0"
    static final String K_SYNC    = "hyperhand_sk_last_sync";   // 最后同步时间（状态页展示）

    private static final long CACHE_MS = 400L;

    /** volatile 保证跨线程（按键分发线程 / 主线程）可见。 */
    private static volatile SkModel.Snapshot sSnapshot =
            new SkModel.Snapshot(-1L, "", false, Collections.<SkModel.Rule>emptyList());
    private static volatile long sLastCheckAt;
    private static volatile String sLastVersionSeen = null;

    private SkConfig() {}

    /**
     * 热路径调用：返回当前快照。每隔 CACHE_MS 复核一次版本，未变直接返回缓存。
     * 任何异常都返回当前（或安全空）快照，绝不抛出到按键回调。
     */
    static SkModel.Snapshot get(Context ctx) {
        long now = SystemClock.uptimeMillis();
        if (sLastVersionSeen != null && now - sLastCheckAt < CACHE_MS) {
            return sSnapshot;
        }
        sLastCheckAt = now;
        try {
            if (ctx == null) return sSnapshot;
            ContentResolver cr = ctx.getContentResolver();
            // 主开关独立于规则版本：每次复核都读一次，保证 App 关总开关后立即透传。
            // 只读一个字符串，开销与读 version 相当，不在按键热路径（400ms 节流外）。
            String masterRaw = readSetting(cr, K_MASTER);
            boolean master = "1".equals(masterRaw) || "true".equalsIgnoreCase(masterRaw);

            String version = readSetting(cr, K_VERSION);
            // 版本没变（含首次读到 null），规则缓存复用；但 master 要单独刷新。
            if (equal(version, sLastVersionSeen)) {
                if (sLastVersionSeen == null) {
                    // 首次：仍需读一次 rules 以加载持久化配置（例如 Hook 重启后）
                    reload(cr, version);
                } else if (sSnapshot.master != master) {
                    // 只有主开关变了：换一个快照，规则列表复用（不可变，安全）。
                    sSnapshot = new SkModel.Snapshot(
                            sSnapshot.version, sSnapshot.checksum, master, sSnapshot.rules);
                    Log.i(PoCConfig.TAG, "SkConfig master changed -> " + master);
                }
                return sSnapshot;
            }
            sLastVersionSeen = version;
            reload(cr, version);
        } catch (Throwable t) {
            Log.w(PoCConfig.TAG, "SkConfig.get failed, keep last snapshot: " + t);
        }
        return sSnapshot;
    }

    /** 强制下次 get 重新读取（App 写配置后可主动触发，非必须）。 */
    static void invalidate() {
        sLastCheckAt = 0L;
    }

    private static void reload(ContentResolver cr, String version) {
        try {
            String rulesJson = readSetting(cr, K_RULES);
            String sum = readSetting(cr, K_SUM);
            String masterRaw = readSetting(cr, K_MASTER);
            boolean master = "1".equals(masterRaw) || "true".equalsIgnoreCase(masterRaw);

            long ver = parseLong(version, 0L);
            if (rulesJson == null || rulesJson.length() == 0) {
                // 无配置：空规则（安全），但仍记录版本与主开关状态。
                sSnapshot = new SkModel.Snapshot(ver, "", master,
                        Collections.<SkModel.Rule>emptyList());
                return;
            }
            // 校验和：App 写入时对 rulesJson 算 CRC32。
            if (sum != null && sum.length() > 0 && !equal(sum, crc32(rulesJson))) {
                Log.w(PoCConfig.TAG, "SkConfig checksum mismatch (ver=" + version
                        + "), keep last snapshot");
                return; // 保留旧快照
            }
            List<SkModel.Rule> rules = SkModel.parseRules(rulesJson);
            sSnapshot = new SkModel.Snapshot(ver, sum == null ? "" : sum, master, rules);
            Log.i(PoCConfig.TAG, "SkConfig loaded ver=" + ver + " rules=" + rules.size()
                    + " master=" + master);
        } catch (Throwable t) {
            Log.w(PoCConfig.TAG, "SkConfig.reload failed, keep last snapshot: " + t);
        }
    }

    /**
     * 读取配置项：优先 {@link Settings.Global}（App 经 root 授权 WRITE_SECURE_SETTINGS 后写入），
     * 回退 {@link Settings.System}（兼容早期尝试写入的残留）。
     *
     * <p>为什么不直接用 System：MIUI 的 SettingsProvider 对普通 App 写自定义 System key
     * 会抛 {@code IllegalArgumentException: You cannot keep your settings in the secure
     * settings}，所以正式通道仍是 Global。
     */
    private static String readSetting(ContentResolver cr, String key) {
        try {
            String v = Settings.Global.getString(cr, key);
            if (v != null) return v;
        } catch (Throwable ignored) {
        }
        try {
            return Settings.System.getString(cr, key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static long parseLong(String s, long def) {
        try { return s == null ? def : Long.parseLong(s.trim()); }
        catch (Throwable t) { return def; }
    }

    /** CRC32 十进制字符串，与 App 侧 RuleRepository.checksum() 完全一致。 */
    static String crc32(String s) {
        CRC32 c = new CRC32();
        c.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Long.toString(c.getValue());
    }
}
