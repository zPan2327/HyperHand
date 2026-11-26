package io.github.zpan2327.hyperhand.smartkey.hook;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 智能按键映射规则引擎（运行在 system_server，按键热路径）。
 *
 * <p>职责：
 * <ol>
 *   <li>按键序列状态机：把原始 DOWN / repeat / UP 序列归类为 短按 / 长按 / 双击 / 连按；</li>
 *   <li>条件求值：AND / OR 组合，未知状态按不满足处理（保守透传）；</li>
 *   <li>规则匹配：按优先级取第一个"条件成立 + 含该按键映射"的规则；</li>
 *   <li>决策：消费（吞键）+ 下发媒体动作，或透传。</li>
 * </ol>
 *
 * <p><b>为什么长按必须自己计时（2026-11 修）</b>：{@code interceptKeyBeforeQueueing} 返回 0
 * 表示"不交给用户态"，InputDispatcher 会直接丢弃这个 DOWN，<b>不会</b>为它建立按键重复状态，
 * 所以系统永远不会再给 repeat 事件。旧实现靠 {@code repeat>0} 判长按，在真机上等于永远不触发
 * ——这正是"长按用不了"的原因。现在 DOWN 时用 {@link Clock} 自己起一个
 * {@code longPressMs} 定时器，到点就出 LONG；若设备仍发 repeat，则作为兜底立即触发（同一份动作只出一次）。
 *
 * <p><b>按压方式的优先级</b>：一个键上同时配了多种按压方式时，双击 / 连按优先于短按 ——
 * 短按不再在 DOWN 时立即触发，而是推迟到"多击窗口"关闭后；否则双击的第一下会先执行短按动作
 * （旧实现就是这样，表现为「双击不灵」）。只有"唯一映射"时才在 DOWN 立即触发，保持零延迟。
 *
 * <p><b>时间全部走 {@link Clock}</b>：生产环境是独立 HandlerThread（不占 system_server 主线程），
 * 自检时是虚拟时钟，可以把一整套手势在毫秒内跑完且完全确定。
 */
final class SkEngine {

    /** 时间源：生产用 Handler，自检用虚拟时钟。 */
    interface Clock {
        long uptime();

        void postDelayed(Runnable r, long delayMs);

        void remove(Runnable r);
    }

    /** 动作出口：生产落 MediaActions，自检落记录器。 */
    interface ActionSink {
        void fire(String action, List<String> pkgs);

        /** 被接管但这一下本该由系统调音量（"保持原功能"），补一次音量调节。 */
        void adjustVolume(int keyCode);
    }

    /** 一次事件的处理结果：只回答"这个事件要不要吞"。动作统一走 {@link ActionSink}。 */
    static final class Decision {
        final boolean consume;

        private Decision(boolean consume) {
            this.consume = consume;
        }

        static final Decision CONSUME = new Decision(true);
        static final Decision PASS = new Decision(false);
    }

    /** 默认时间源：独立线程，避免 system_server 主线程繁忙时把长按判定拖晚。 */
    private static final class TimerClock implements Clock {
        private final Handler handler;

        TimerClock() {
            HandlerThread t = new HandlerThread("HyperHand-SkKeys", Process.THREAD_PRIORITY_FOREGROUND);
            t.start();
            handler = new Handler(t.getLooper());
        }

        @Override
        public long uptime() {
            return SystemClock.uptimeMillis();
        }

        @Override
        public void postDelayed(Runnable r, long delayMs) {
            handler.postDelayed(r, delayMs);
        }

        @Override
        public void remove(Runnable r) {
            handler.removeCallbacks(r);
        }
    }

    /**
     * 一种按压方式命中的映射，以及它所属规则的条件包名。
     *
     * <p>包名必须跟着映射走：同一个键的短按可能来自「正在播放」的规则、长按来自另一条
     * 「锁屏时」的规则，动作要按各自那条规则的包投递，不能共用一份。
     */
    private static final class Slot {
        SkModel.Mapping map;
        List<String> pkgs = Collections.emptyList();

        /** 配了具体动作（不是「保持原功能」）。 */
        boolean acts() {
            // 必须限定 SkEngine.：本类里也有个无参 acts()，裸调用会被解析成本类的方法。
            return SkEngine.acts(map);
        }

        void clear() {
            map = null;
            pkgs = Collections.emptyList();
        }
    }

    /** 单个按键的序列状态。 */
    private static final class KeySeq {
        final String key;
        final int keyCode;
        boolean active;            // 本次按压已被接管（UP 需要吞掉）
        boolean fired;             // 本次（或本组连击）已经出过动作
        int tapCount;              // 多击计数
        long downAt;               // 本次按下（clock.uptime）
        long lastUpAt;             // 上次抬手（clock.uptime）
        final Slot shortSlot = new Slot();
        final Slot longSlot = new Slot();
        final Slot doubleSlot = new Slot();
        final Slot repeatSlot = new Slot();
        Runnable longTask;
        Runnable closeTask;

        KeySeq(String key, int keyCode) {
            this.key = key;
            this.keyCode = keyCode;
        }

        boolean anyActs() {
            return shortSlot.acts() || longSlot.acts() || doubleSlot.acts() || repeatSlot.acts();
        }

        Slot slotFor(String press) {
            if (SkModel.P_SHORT.equals(press)) return shortSlot;
            if (SkModel.P_LONG.equals(press)) return longSlot;
            if (SkModel.P_DOUBLE.equals(press)) return doubleSlot;
            if (SkModel.P_REPEAT.equals(press)) return repeatSlot;
            return null;
        }

        void clearSlots() {
            shortSlot.clear();
            longSlot.clear();
            doubleSlot.clear();
            repeatSlot.clear();
        }
    }

    private final Clock clock;
    private final ActionSink sink;
    private final Object lock = new Object();

    /** 自检注入的配置；非 null 时完全不读 Settings。 */
    private volatile SkModel.Snapshot testSnapshot;

    private final SkContext ctxState = new SkContext();
    private final KeySeq upSeq = new KeySeq(SkModel.K_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_UP);
    private final KeySeq downSeq = new KeySeq(SkModel.K_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN);

    private volatile long lastLogAt;

    SkEngine(ActionSink sink) {
        this(new TimerClock(), sink);
    }

    SkEngine(Clock clock, ActionSink sink) {
        this.clock = clock;
        this.sink = sink;
    }

    /** 自检用：注入配置快照（null = 恢复读 Settings）。 */
    void setTestSnapshot(SkModel.Snapshot snapshot) {
        this.testSnapshot = snapshot;
    }

    /** 处理一个按键事件。任何异常 → 透传。 */
    Decision onKeyEvent(KeyEvent ev, Context ctx) {
        try {
            if (ev == null) return Decision.PASS;
            int code = ev.getKeyCode();
            boolean isUpKey = code == KeyEvent.KEYCODE_VOLUME_UP;
            boolean isDownKey = code == KeyEvent.KEYCODE_VOLUME_DOWN;
            if (!isUpKey && !isDownKey) return Decision.PASS;

            KeySeq seq = isUpKey ? upSeq : downSeq;
            int action = ev.getAction();
            int repeat = ev.getRepeatCount();

            synchronized (lock) {
                if (action == KeyEvent.ACTION_DOWN && repeat == 0) {
                    return onFirstDown(ctx, seq, clock.uptime());
                } else if (action == KeyEvent.ACTION_DOWN) {
                    return onRepeat(seq, clock.uptime());
                } else if (action == KeyEvent.ACTION_UP) {
                    return onUp(seq, clock.uptime());
                }
                return Decision.PASS;
            }
        } catch (Throwable t) {
            Log.w(PoCConfig.TAG, "SkEngine error, pass through: " + t);
            return Decision.PASS;
        }
    }

    /** 首次按下：唯一的"是否接管"决策点。 */
    private Decision onFirstDown(Context ctx, KeySeq seq, long now) {
        if (ctx != null && ctxState.expired()) ctxState.refresh(ctx);

        SkModel.Snapshot snap = testSnapshot;
        if (snap == null) snap = SkConfig.get(ctx);

        if (snap == null || !snap.master || snap.rules.isEmpty()) {
            reset(seq);
            return Decision.PASS;
        }

        // 每种按压方式**分别**找「第一个（优先级最高）提供了该方式」的规则。
        //
        // 旧实现是「先找第一条命中的规则，再只从这一条里取映射」。用户在「规则与按键」里
        // 最常见的配置就是「短按下一首」「长按上一首」各建一条规则 —— 两条都用同一个键，
        // 于是第二条永远轮不到：短按能用，长按 / 双击完全没反应。这就是「双击和长按不生效」
        // 的根因，不是手势识别的问题。
        seq.clearSlots();
        for (SkModel.Rule r : snap.rules) {
            if (!r.enabled) continue;
            if (!conditionsMet(r)) continue;
            if (!hasMappingForKey(r, seq.key)) continue;
            List<String> pkgs = collectPkgs(r);
            for (SkModel.Mapping m : mappingsFor(r, seq.key)) {
                Slot slot = seq.slotFor(m.press);
                // 「保持原功能」也占位：同一按压方式的归属是「优先级最高的那条规则」说了算，
                // 显式配了保持原功能就表示这个按压方式不要接管，不能被后面的规则顶掉。
                if (slot != null && slot.map == null) {
                    slot.map = m;
                    slot.pkgs = pkgs;
                }
            }
        }
        // 全是「保持原功能」（或没有任何规则提供该键的动作）→ 完全不归我们管：
        // 不吞键，系统音量照常。
        if (!seq.anyActs()) {
            reset(seq);
            return Decision.PASS;
        }

        // 上一轮可能还挂着长按计时器 / 多击窗口，进新按压前先清掉（多击计数保留）。
        cancelTasks(seq);

        long window = windowFor(seq.doubleSlot.map, seq.repeatSlot.map);
        boolean continues = seq.tapCount > 0 && window > 0 && now - seq.lastUpAt <= window;

        seq.active = true;
        seq.fired = false;
        seq.downAt = now;
        seq.tapCount = continues ? seq.tapCount + 1 : 1;

        // 1) 连按 / 双击 达成 → 立即出动作（抬手时只吞键，不再触发别的）
        if (seq.repeatSlot.acts() && seq.tapCount >= seq.repeatSlot.map.repeatCount) {
            int taps = seq.tapCount;
            seq.tapCount = 0;
            fire(seq, seq.repeatSlot, "REPEAT x" + taps);
            return Decision.CONSUME;
        }
        if (seq.doubleSlot.acts() && seq.tapCount >= 2) {
            seq.tapCount = 0;
            fire(seq, seq.doubleSlot, "DOUBLE");
            return Decision.CONSUME;
        }

        // 2) 长按：自己计时（系统不会再给 repeat 事件）
        if (seq.longSlot.acts()) {
            startLongTimer(seq, seq.longSlot.map.longPressMs);
        }

        // 3) 短按：只有"唯一映射"才在 DOWN 立即触发；否则等抬手 / 多击窗口关闭
        if (seq.shortSlot.acts() && !seq.longSlot.acts()
                && !seq.doubleSlot.acts() && !seq.repeatSlot.acts()) {
            fire(seq, seq.shortSlot, "SHORT(down)");
        }
        logDecision(seq.key, "DOWN tap=" + seq.tapCount + " window=" + window
                + " slots=" + seq.shortSlot.acts() + "/" + seq.longSlot.acts()
                + "/" + seq.doubleSlot.acts() + "/" + seq.repeatSlot.acts(), null);
        return Decision.CONSUME;
    }

    /**
     * repeat>0：兜底。正常情况**不会**来 —— repeat 是 InputDispatcher 内部合成的，
     * 只经过 interceptKeyBeforeDispatching，不会再进 interceptKeyBeforeQueueing。
     * 万一某些 ROM 送来，也要求已按住 >= 长按阈值才算长按（否则只能等计时器）。
     */
    private Decision onRepeat(KeySeq seq, long now) {
        if (!seq.active) return Decision.PASS;
        if (!seq.fired && seq.longSlot.acts()
                && now - seq.downAt >= seq.longSlot.map.longPressMs) {
            cancelLong(seq);
            fire(seq, seq.longSlot, "LONG(repeat)");
            return Decision.CONSUME;
        }
        return Decision.CONSUME;
    }

    /** 抬手：定短按 / 开启多击窗口。 */
    private Decision onUp(KeySeq seq, long now) {
        if (!seq.active) return Decision.PASS;
        cancelLong(seq);
        seq.lastUpAt = now;
        seq.active = false;

        if (!seq.fired) {
            boolean multiTap = seq.doubleSlot.acts() || seq.repeatSlot.acts();
            if (multiTap && seq.tapCount >= 1) {
                // 还没到次数：等窗口关闭后再决定短按，期间若来下一次 DOWN 就取消
                scheduleClose(seq);
            } else if (seq.shortSlot.acts()) {
                fire(seq, seq.shortSlot, "SHORT(up)");
            } else if (seq.shortSlot.map != null || seq.longSlot.map != null) {
                // 短按被显式设为「保持原功能」，长按没到时间 → 补一次系统音量
                adjustVolume(seq);
            }
        }
        return Decision.CONSUME;
    }

    /** 长按到点：出一次动作。 */
    private void startLongTimer(final KeySeq seq, final int thresholdMs) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    seq.longTask = null;
                    if (!seq.active || seq.fired) return;
                    if (!seq.longSlot.acts()) return;
                    fire(seq, seq.longSlot, "LONG(timer " + thresholdMs + "ms)");
                }
            }
        };
        seq.longTask = r;
        clock.postDelayed(r, thresholdMs);
    }

    /** 多击窗口关闭：还没凑够次数 → 补短按（或补系统音量）。 */
    private void scheduleClose(final KeySeq seq) {
        long window = windowFor(seq.doubleSlot.map, seq.repeatSlot.map);
        if (window <= 0) return;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                synchronized (lock) {
                    seq.closeTask = null;
                    boolean fired = seq.fired;
                    seq.tapCount = 0;
                    if (fired) return;
                    if (seq.shortSlot.acts()) {
                        fire(seq, seq.shortSlot, "SHORT(window)");
                    } else if (seq.shortSlot.map != null || seq.longSlot.map != null) {
                        adjustVolume(seq);
                    }
                }
            }
        };
        seq.closeTask = r;
        clock.postDelayed(r, window);
    }

    private void fire(KeySeq seq, Slot slot, String kind) {
        seq.fired = true;
        String action = slot.map == null ? null : slot.map.action;
        logDecision(seq.key, kind, action);
        if (action != null && !SkModel.A_PASS.equals(action) && sink != null) {
            sink.fire(action, slot.pkgs);
        }
    }

    private void adjustVolume(KeySeq seq) {
        if (sink != null) sink.adjustVolume(seq.keyCode);
    }

    private static boolean acts(SkModel.Mapping m) {
        return m != null && !SkModel.A_PASS.equals(m.action);
    }

    /** 多击窗口：双击用自己的间隔；只有连按时用一个略宽松的固定窗口。 */
    private static long windowFor(SkModel.Mapping doubleMap, SkModel.Mapping repeatMap) {
        if (acts(doubleMap)) return doubleMap.doubleTapMs;
        if (acts(repeatMap)) return Math.max(repeatMap.doubleTapMs, 400);
        return 0L;
    }

    private void cancelLong(KeySeq seq) {
        if (seq.longTask != null) {
            clock.remove(seq.longTask);
            seq.longTask = null;
        }
    }

    private void cancelTasks(KeySeq seq) {
        cancelLong(seq);
        if (seq.closeTask != null) {
            clock.remove(seq.closeTask);
            seq.closeTask = null;
        }
    }

    private void reset(KeySeq seq) {
        cancelTasks(seq);
        seq.active = false;
        seq.fired = false;
        seq.tapCount = 0;
        seq.downAt = 0L;
        seq.clearSlots();
    }

    // ---- 条件求值 ----

    private boolean conditionsMet(SkModel.Rule rule) {
        if (rule.conditions == null || rule.conditions.isEmpty()) return true;
        boolean anyTrue = false;
        for (SkModel.Condition c : rule.conditions) {
            if (!c.enabled) continue;
            boolean ok = eval(c);
            if (rule.andLogic) {
                if (!ok) return false;
            } else if (ok) {
                anyTrue = true;
            }
        }
        return rule.andLogic ? true : anyTrue;
    }

    private boolean eval(SkModel.Condition c) {
        String t = c.type;
        List<String> p = c.packages;
        boolean hasPkg = p != null && !p.isEmpty();

        if (SkModel.C_APP_PLAYING.equals(t))
            return hasPkg && ctxState.sessionsKnown && ctxState.anyPlaying(p);
        if (SkModel.C_APP_RUNNING.equals(t))
            return hasPkg && ctxState.runningKnown && ctxState.anyRunning(p);
        if (SkModel.C_APP_NOT_RUNNING.equals(t))
            return hasPkg && ctxState.runningKnown && !ctxState.anyRunning(p);
        if (SkModel.C_ANY_APP_PLAYING.equals(t))
            return ctxState.sessionsKnown && ctxState.anyPlaying();
        if (SkModel.C_APP_FOREGROUND.equals(t))
            return hasPkg && ctxState.foregroundKnown && contains(p, ctxState.foregroundPkg);
        if (SkModel.C_APP_NOT_FOREGROUND.equals(t))
            return hasPkg && ctxState.foregroundKnown && !contains(p, ctxState.foregroundPkg);
        if (SkModel.C_APP_PAUSED.equals(t))
            return hasPkg && ctxState.sessionsKnown && ctxState.anyPaused(p);
        if (SkModel.C_APP_NO_SESSION.equals(t))
            return hasPkg && ctxState.sessionsKnown && !ctxState.anySession(p);
        if (SkModel.C_SCREEN_ON.equals(t))
            return ctxState.screenOn;
        if (SkModel.C_SCREEN_OFF.equals(t))
            return !ctxState.screenOn;
        if (SkModel.C_UNLOCKED.equals(t))
            return !ctxState.locked;
        if (SkModel.C_LOCKED.equals(t))
            return ctxState.locked;
        if (SkModel.C_CHARGING.equals(t))
            return ctxState.charging;
        if (SkModel.C_NOT_CHARGING.equals(t))
            return !ctxState.charging;
        if (SkModel.C_AUDIO_PLAYING.equals(t))
            return ctxState.sessionsKnown && ctxState.anyPlaying();
        if (SkModel.C_AUDIO_PAUSED.equals(t))
            return ctxState.sessionsKnown && ctxState.anyPausedAny(Collections.<String>emptyList());
        if (SkModel.C_AUDIO_STOPPED.equals(t))
            return ctxState.sessionsKnown && !ctxState.anyPlaying()
                    && !ctxState.anyPausedAny(Collections.<String>emptyList());
        if (SkModel.C_NO_MEDIA_SESSION.equals(t))
            return ctxState.sessionsKnown && ctxState.sessions.isEmpty();
        if (SkModel.C_MULTIPLE_SESSIONS.equals(t))
            return ctxState.sessionsKnown && ctxState.sessions.size() >= 2;
        return false; // 未知条件类型 → 不满足
    }

    // ---- helpers ----

    private static boolean hasMappingForKey(SkModel.Rule r, String key) {
        if (r.mappings == null) return false;
        for (SkModel.Mapping m : r.mappings) if (key.equals(m.key)) return true;
        return false;
    }

    private static List<SkModel.Mapping> mappingsFor(SkModel.Rule r, String key) {
        List<SkModel.Mapping> out = new ArrayList<SkModel.Mapping>();
        if (r.mappings == null) return out;
        for (SkModel.Mapping m : r.mappings) if (key.equals(m.key)) out.add(m);
        return out;
    }

    private static List<String> collectPkgs(SkModel.Rule r) {
        List<String> out = new ArrayList<String>();
        if (r.conditions == null) return out;
        for (SkModel.Condition c : r.conditions) {
            if (!c.enabled || c.packages == null) continue;
            for (String p : c.packages) if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    private static boolean contains(List<String> list, String v) {
        for (String s : list) if (s.equals(v)) return true;
        return false;
    }

    private void logDecision(String key, String kind, String action) {
        long now = clock.uptime();
        if (testSnapshot == null && now - lastLogAt < 120L) return; // 节流（自检不节流）
        lastLogAt = now;
        Log.i(PoCConfig.TAG, "ENGINE " + key + " " + kind
                + " action=" + (action == null ? "-" : action)
                + " fg=" + ctxState.foregroundPkg + " playing=" + ctxState.anyPlaying()
                + " screenOn=" + ctxState.screenOn + " locked=" + ctxState.locked);
    }
}
