package io.github.zpan2327.hyperhand.smartkey.hook;

import android.content.Context;
import android.media.AudioManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * 媒体动作执行（运行在 system_server，异步到主线程）。
 *
 * <p>system_server 持有 MEDIA_CONTENT_CONTROL 权限，可直接 getActiveSessions(null)
 * 拿到全部会话并下发 TransportControls——这是 system_server Hook 相对普通应用的核心价值。
 *
 * <p>目标会话选择（对齐需求"可被应用条件限定"）：
 * <ol>
 *   <li>若规则条件带包名，优先在这些包名里找正在播放（STATE_PLAYING）的会话；</li>
 *   <li>否则选任意正在播放的会话；</li>
 *   <li>没有播放会话 → 跳过，绝不误控后台/暂停的其它应用。</li>
 * </ol>
 *
 * <p>所有调用 try-catch；动作在 ACTION_DOWN 首次按下时经主线程 post 执行，
 * 不阻塞 InputDispatcher 线程。
 */
final class MediaActions {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MediaActions() {}

    /** 异步执行一个动作（供按键热路径调用，立即返回）。 */
    static void performAsync(final Context ctx, final String action, final List<String> targetPkgs) {
        if (ctx == null || action == null) return;
        if (SkModel.A_PASS.equals(action)) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                perform(ctx, action, targetPkgs);
            }
        });
    }

    /**
     * 补偿系统音量：按键被规则接管，但这一下本该由系统调音量（短按映射设成「保持原功能」、
     * 或长按没到时间就松手）。DOWN 已经被吞掉、系统不会自己调，所以这里替它调一次。
     */
    static void adjustVolumeAsync(final Context ctx, final int keyCode) {
        if (ctx == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
                    if (am == null) return;
                    int dir = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                            ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                    Log.i(PoCConfig.TAG, "media: volume "
                            + (dir == AudioManager.ADJUST_RAISE ? "up" : "down") + " (compensate)");
                } catch (Throwable t) {
                    Log.w(PoCConfig.TAG, "media: volume adjust failed (ignored): " + t);
                }
            }
        });
    }

    /** 同步执行（已在主线程时可直接调用；测试也可直接调）。 */
    static void perform(Context ctx, String action, List<String> targetPkgs) {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) {
                Log.w(PoCConfig.TAG, "media: MediaSessionManager null");
                return;
            }
            List<MediaController> sessions = msm.getActiveSessions(null);
            if (sessions == null || sessions.isEmpty()) {
                Log.i(PoCConfig.TAG, "media: no active sessions, skip");
                return;
            }
            MediaController target = pick(sessions, targetPkgs, action);
            if (target == null) {
                Log.i(PoCConfig.TAG, "media: no playing target session, skip (不误控其它应用)");
                return;
            }
            MediaController.TransportControls tc = target.getTransportControls();
            if (tc == null) return;

            String pkg = String.valueOf(target.getPackageName());
            if (SkModel.A_NEXT.equals(action)) {
                tc.skipToNext();
            } else if (SkModel.A_PREVIOUS.equals(action)) {
                tc.skipToPrevious();
            } else if (SkModel.A_PLAY.equals(action)) {
                tc.play();
            } else if (SkModel.A_PAUSE.equals(action)) {
                tc.pause();
            } else if (SkModel.A_PLAY_PAUSE.equals(action)) {
                PlaybackState st = target.getPlaybackState();
                boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
                if (playing) tc.pause(); else tc.play();
            } else {
                Log.w(PoCConfig.TAG, "media: unknown action " + action);
                return;
            }
            Log.i(PoCConfig.TAG, "media: " + action + " -> " + pkg);
        } catch (Throwable t) {
            Log.w(PoCConfig.TAG, "media: " + action + " failed (ignored): " + t);
        }
    }

    /**
     * 目标选择。
     *
     * <p>旧实现只认 {@code STATE_PLAYING}，导致「播放 / 播放暂停」这两类动作永远找不到会话
     * （暂停中的会话被跳过）——表现为「暂停能用、播放和播放暂停没反应」。
     * 现在按动作区分优先级，并且仍然保持包名限定的严格模式（不在限定包内就绝不退回全局）：
     * <ul>
     *   <li>下一首 / 上一首 / 播放 / 播放暂停：正在播放 → 暂停中 → 其它有会话的；</li>
     *   <li>暂停：正在播放优先（对已暂停的会话再暂停无意义，但不至于选不到目标）。</li>
     * </ul>
     */
    private static MediaController pick(List<MediaController> sessions, List<String> targetPkgs,
                                        String action) {
        boolean strict = targetPkgs != null && !targetPkgs.isEmpty();
        MediaController playing = null;
        MediaController paused = null;
        MediaController other = null;

        for (MediaController c : sessions) {
            try {
                String pkg = String.valueOf(c.getPackageName());
                if (strict && !contains(targetPkgs, pkg)) continue;
                PlaybackState st = c.getPlaybackState();
                int state = st == null ? PlaybackState.STATE_NONE : st.getState();
                if (state == PlaybackState.STATE_PLAYING) {
                    if (playing == null) playing = c;
                } else if (state == PlaybackState.STATE_PAUSED) {
                    if (paused == null) paused = c;
                } else if (state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_FAST_FORWARDING
                        || state == PlaybackState.STATE_REWINDING) {
                    if (other == null) other = c;
                }
            } catch (Throwable ignored) { }
        }

        if (strict) {
            if (playing != null) return playing;
            if (paused != null) return paused;
            return other; // 限定包内都没有会话 → null，不误控其它应用
        }
        // 未限定包名：优先正在播放的，其次暂停中的（播放类动作需要它），最后才是其它状态
        if (playing != null) return playing;
        if (paused != null) return paused;
        return other;
    }

    private static boolean contains(List<String> pkgs, String pkg) {
        for (String p : pkgs) if (p.equals(pkg)) return true;
        return false;
    }
}
