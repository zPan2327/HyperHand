package io.github.zpan2327.hyperhand.smartkey.hook;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Phase 2 PoC 的开关配置。
 *
 * <p>配置写在 {@link Settings#GLOBAL}，因为 Hook 运行在 system_server（UID 1000），
 * 主应用（普通 UID）写、system_server 读，天然跨 UID。PoC 阶段只放 3 个开关：
 * <ul>
 *   <li>{@link #K_CAPTURE}   是否打印按键事件日志（默认开）；</li>
 *   <li>{@link #K_CONSUME_UP}  是否吞掉音量加（默认关，避免误伤）；</li>
 *   <li>{@link #K_CONSUME_DOWN}是否吞掉音量减（默认关）；</li>
 *   <li>{@link #K_MEDIA_NEXT}  命中时是否执行"下一首"（默认关）；</li>
 * </ul>
 *
 * <p>热路径（每次按键）只读进程内缓存，不读 Settings——缓存 500ms 刷新一次，
 * 由 {@link #get(Context)} 惰性触发。这符合需求"不在按键热路径做磁盘/IPC"。
 */
final class PoCConfig {

    static final String TAG = "HyperHandSK";

    // 前缀独立，避免和现有 oh_tuner_* 冲突。
    static final String PREFIX = "hyperhand_sk_poc_";
    static final String K_CAPTURE     = PREFIX + "capture";
    static final String K_CONSUME_UP  = PREFIX + "consume_up";
    static final String K_CONSUME_DOWN= PREFIX + "consume_down";
    static final String K_MEDIA_NEXT  = PREFIX + "media_next";

    /** 最后一次写配置的时间戳（epoch ms），仅用于状态展示，PoC 不做复杂校验。 */
    static final String K_LAST_SYNC  = PREFIX + "last_sync";

    boolean capture = true;
    boolean consumeUp = false;
    boolean consumeDown = false;
    boolean mediaNext = false;

    private static PoCConfig sCached;
    private static long sCachedAt;

    static synchronized PoCConfig get(Context ctx) {
        long now = SystemClock.uptimeMillis();
        if (sCached != null && now - sCachedAt < 500) return sCached;
        PoCConfig c = new PoCConfig();
        try {
            ContentResolver cr = ctx.getContentResolver();
            c.capture = bool(cr, K_CAPTURE, true);
            c.consumeUp = bool(cr, K_CONSUME_UP, false);
            c.consumeDown = bool(cr, K_CONSUME_DOWN, false);
            c.mediaNext = bool(cr, K_MEDIA_NEXT, false);
        } catch (Throwable t) {
            Log.w(TAG, "PoCConfig read failed, use safe defaults (no consume)", t);
            // 读失败时保持"全部关闭"的安全默认 —— 失败透传原则。
            c.capture = true;
            c.consumeUp = c.consumeDown = c.mediaNext = false;
        }
        sCached = c;
        sCachedAt = now;
        return c;
    }

    private static boolean bool(ContentResolver cr, String key, boolean def) {
        try {
            String s = Settings.Global.getString(cr, key);
            if (s == null || s.length() == 0) return def;
            return "true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }
}
