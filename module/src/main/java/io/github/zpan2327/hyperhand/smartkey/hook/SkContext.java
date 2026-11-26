package io.github.zpan2327.hyperhand.smartkey.hook;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 条件求值所需的设备 / 应用状态探测（运行在 system_server，可直接用系统权限）。
 *
 * <p>状态在 {@link #refresh(Context)} 时一次性抓取并缓存，按键回调只读缓存字段，
 * 不做 Binder / 反射查询。刷新只在一次按键序列的首次 DOWN 触发（由 SkEngine 调用），
 * 连击 / repeat 事件复用同一份状态。
 *
 * <p>所有探测都 try-catch：拿不到就置"未知"（{@code null} / false），
 * 求值时"未知"按不匹配处理 → 保守透传，绝不误触发。
 */
final class SkContext {

    /** 一个媒体会话的快照。 */
    static final class MediaInfo {
        final String pkg;
        final int state;
        final boolean active;

        MediaInfo(String pkg, int state, boolean active) {
            this.pkg = pkg;
            this.state = state;
            this.active = active;
        }

        boolean isPlaying() { return state == PlaybackState.STATE_PLAYING; }
        boolean isPaused() { return state == PlaybackState.STATE_PAUSED; }
    }

    // 前台应用包名（可能 null = 未知 / 桌面 / 锁屏）
    volatile String foregroundPkg = null;
    /** 前台应用是否探测成功（失败时否定类条件不得按"未知=满足"误命中）。 */
    volatile boolean foregroundKnown = false;
    /** 正在运行的应用包名集合（system_server 内探测）。 */
    volatile java.util.Set<String> runningPkgs = Collections.emptySet();
    /** 运行应用列表是否探测成功。 */
    volatile boolean runningKnown = false;
    // 屏幕是否点亮交互（未知时 false）
    volatile boolean screenOn = false;
    // 是否处于锁屏（未知时 false）
    volatile boolean locked = false;
    // 是否充电（未知时 false）
    volatile boolean charging = false;
    // 媒体会话快照
    volatile List<MediaInfo> sessions = Collections.emptyList();
    /** 媒体会话是否探测成功。 */
    volatile boolean sessionsKnown = false;

    private volatile long lastRefreshAt;
    private static final long TTL_MS = 250L;

    /** 是否过期。 */
    boolean expired() {
        return System.currentTimeMillis() - lastRefreshAt > TTL_MS;
    }

    /** 强制刷新一次全部状态。失败字段保持默认（未知）。 */
    void refresh(Context ctx) {
        if (ctx == null) return;
        lastRefreshAt = System.currentTimeMillis();

        // 1) 屏幕 / 锁屏（公开 API，system_server 内可靠）
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            screenOn = pm != null && pm.isInteractive();
        } catch (Throwable t) {
            Log.w(PoCConfig.TAG, "SkContext screen failed: " + t);
        }
        try {
            KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
            locked = km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            Log.w(PoCConfig.TAG, "SkContext keyguard failed: " + t);
        }

        // 2) 充电状态（sticky 广播，无需注册长期 receiver）
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent sticky = ctx.registerReceiver(null, f);
            if (sticky != null) {
                int plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                charging = plugged != 0;
            }
        } catch (Throwable t) {
            Log.w(PoCConfig.TAG, "SkContext battery failed: " + t);
        }

        // 3)+4) 进程探测：一次 getRunningAppProcesses 同时得到"运行集合"和"前台应用"。
        // Android 16 上 ActivityTaskManager/ActivityManager 的 getTasks 签名反射不稳定，
        // 改用 importance=FOREGROUND 判定前台，可靠且只需一次调用。
        java.util.Set<String> running = new java.util.HashSet<String>();
        String fg = detectProcesses(ctx, running);
        runningKnown = true; // getRunningAppProcesses 本身成功即视为已知（含空集合）
        runningPkgs = running;
        foregroundPkg = fg;
        foregroundKnown = fg != null;

        // 5) 媒体会话
        List<MediaInfo> ms = detectSessions(ctx);
        sessionsKnown = ms != null;
        sessions = ms == null ? Collections.<MediaInfo>emptyList() : ms;
    }

    /**
     * 探测进程：填充运行包集合，并返回前台应用包名。
     * 前台判定优先级：IMPORTANCE_FOREGROUND(100) > IMPORTANCE_FOREGROUND_SERVICE(125) 之外的都不算。
     * system_server 内可用；失败时返回 null（未知）且 running 集合可能为空。
     */
    private static String detectProcesses(Context ctx, java.util.Set<String> running) {
        String foreground = null;
        try {
            ActivityManager am =
                    (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return null;
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return null;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.pkgList == null) continue;
                for (String s : p.pkgList) if (s != null) running.add(s);
                // 前台进程（用户可见），取第一个 IMPORTANCE_FOREGROUND 的主包
                if (foreground == null && p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                        && p.pkgList.length > 0) {
                    foreground = p.pkgList[0];
                }
            }
        } catch (Throwable t) {
            Log.d(PoCConfig.TAG, "SkContext processes failed: " + t);
        }
        return foreground;
    }

    // ------------------------------------------------------------------ 媒体会话

    private static List<MediaInfo> detectSessions(Context ctx) {
        List<MediaInfo> out = new ArrayList<MediaInfo>();
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return out;
            List<MediaController> cs = msm.getActiveSessions(null); // system_server 有权限
            if (cs == null) return out;
            for (MediaController c : cs) {
                try {
                    PlaybackState st = c.getPlaybackState();
                    int state = st == null ? PlaybackState.STATE_NONE : st.getState();
                    boolean active = st != null && st.isActive();
                    out.add(new MediaInfo(String.valueOf(c.getPackageName()), state, active));
                } catch (Throwable ignored) {
                    // 单个会话读取失败 → 跳过
                }
            }
        } catch (Throwable t) {
            Log.w(PoCConfig.TAG, "SkContext sessions failed: " + t);
        }
        return out;
    }

    /** 是否存在任意正在播放的会话。 */
    boolean anyPlaying() {
        for (MediaInfo m : sessions) if (m.isPlaying()) return true;
        return false;
    }

    /** 指定包名集合里是否有正在播放的会话。 */
    boolean anyPlaying(List<String> pkgs) {
        for (MediaInfo m : sessions) {
            if (m.isPlaying() && contains(pkgs, m.pkg)) return true;
        }
        return false;
    }

    /** 指定包名集合里是否有处于暂停的会话。 */
    boolean anyPaused(List<String> pkgs) {
        for (MediaInfo m : sessions) {
            if (m.isPaused() && contains(pkgs, m.pkg)) return true;
        }
        return false;
    }

    /** 指定包名集合里是否存在任意会话（不论状态）。 */
    boolean anySession(List<String> pkgs) {
        for (MediaInfo m : sessions) if (contains(pkgs, m.pkg)) return true;
        return false;
    }

    /** 是否存在任意暂停的会话（限定包名；pkgs 空则不限定）。 */
    boolean anyPausedAny(List<String> pkgs) {
        for (MediaInfo m : sessions) {
            if (!m.isPaused()) continue;
            if (pkgs == null || pkgs.isEmpty() || contains(pkgs, m.pkg)) return true;
        }
        return false;
    }

    /** 指定包名集合里是否有任意一个在运行。 */
    boolean anyRunning(List<String> pkgs) {
        for (String p : pkgs) if (runningPkgs.contains(p)) return true;
        return false;
    }

    private static boolean contains(List<String> pkgs, String pkg) {
        if (pkg == null) return false;
        for (String p : pkgs) if (p.equals(pkg)) return true;
        return false;
    }

}
