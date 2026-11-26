package io.github.zpan2327.hyperhand.smartkey.hook;

import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.util.Log;

import java.util.List;

/**
 * PoC 媒体控制：验证 system_server 进程内能否直接拿到 MediaSession 并下发 TransportControls。
 *
 * <p>system_server 持有 android.permission.MEDIA_CONTENT_CONTROL，可直接
 * {@code getActiveSessions(null)} 拿到全部会话，这正是"在 system_server Hook 环境中
 * 用系统内部调用方式"的验证点。PoC 阶段只实现"下一首"，并把候选会话信息打到日志，
 * 供真机确认多会话选择策略。
 *
 * <p>所有调用都 try-catch 吞掉，绝不因媒体控制失败影响按键分发。
 */
final class PoCMedia {

    static void next(Context ctx) {
        try {
            MediaSessionManager msm =
                    (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) {
                Log.w(PoCConfig.TAG, "media: MediaSessionManager null");
                return;
            }
            // null = 所有用户；system_server 有权限。
            List<MediaController> sessions = msm.getActiveSessions(null);
            Log.i(PoCConfig.TAG, "media: activeSessions=" + (sessions == null ? -1 : sessions.size()));

            MediaController target = pickTarget(sessions);
            if (target == null) {
                Log.i(PoCConfig.TAG, "media: no playing session, skip (不误控其它应用)");
                return;
            }
            MediaController.TransportControls tc = target.getTransportControls();
            if (tc == null) {
                Log.w(PoCConfig.TAG, "media: transportControls null for " + target.getPackageName());
                return;
            }
            tc.skipToNext();
            Log.i(PoCConfig.TAG, "media: skipToNext -> " + target.getPackageName());
        } catch (Throwable t) {
            Log.w(PoCConfig.TAG, "media: next failed (ignored): " + t);
        }
    }

    /**
     * 选择策略（PoC 版）：优先选正在播放（STATE_PLAYING）的会话；没有就返回 null。
     * 需求要求"优先活动播放会话 + 可被应用条件限定"，PoC 先验证能否识别 PLAYING。
     */
    private static MediaController pickTarget(List<MediaController> sessions) {
        if (sessions == null || sessions.isEmpty()) return null;
        MediaController playing = null;
        for (MediaController c : sessions) {
            try {
                PlaybackState st = c.getPlaybackState();
                int state = st == null ? PlaybackState.STATE_NONE : st.getState();
                Log.i(PoCConfig.TAG, "media session: pkg=" + c.getPackageName()
                        + " state=" + state + " active=" + (st != null && st.isActive()));
                if (state == PlaybackState.STATE_PLAYING && playing == null) {
                    playing = c;
                }
            } catch (Throwable ignored) {
            }
        }
        return playing;
    }
}
