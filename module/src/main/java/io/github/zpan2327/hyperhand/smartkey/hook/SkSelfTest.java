package io.github.zpan2327.hyperhand.smartkey.hook;

import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 按键手势的分类自检：把 {@link SkEngine} 的真实状态机接上**虚拟时钟**，在毫秒内把
 * 「2 个按键 × 4 种按压 × 6 种动作」以及各种组合、时序边界、规则优先级全部跑一遍。
 *
 * <p>为什么用虚拟时钟而不是真按：长按/双击的结果依赖精确到毫秒的时序，
 * 用 adb 注入真实按键既慢又抖（还会真的改音量）。这里让引擎以为时间过去了，
 * 判定逻辑仍然是生产代码，跑完输出 PASS/FAIL 清单。
 *
 * <p>入口：{@code SkSelfTestActivity}（日志 TAG {@code HHSKTest}）。
 */
public final class SkSelfTest {

    private static final String TAG = "HHSKTest";

    private static final int KEY_UP = KeyEvent.KEYCODE_VOLUME_UP;
    private static final int KEY_DOWN = KeyEvent.KEYCODE_VOLUME_DOWN;

    private static final String K_UP = SkModel.K_VOLUME_UP;
    private static final String K_DOWN = SkModel.K_VOLUME_DOWN;

    private static final String SHORT = SkModel.P_SHORT;
    private static final String LONG = SkModel.P_LONG;
    private static final String DOUBLE = SkModel.P_DOUBLE;
    private static final String REPEAT = SkModel.P_REPEAT;

    private static final String A_NEXT = SkModel.A_NEXT;
    private static final String A_PREV = SkModel.A_PREVIOUS;
    private static final String A_PLAY = SkModel.A_PLAY;
    private static final String A_PAUSE = SkModel.A_PAUSE;
    private static final String A_TOGGLE = SkModel.A_PLAY_PAUSE;
    private static final String A_PASS = SkModel.A_PASS;

    private final List<String> out = new ArrayList<String>();
    private int passed;
    private int failed;

    private SkSelfTest() {}

    /** 跑全部用例，返回可读报告（同时打到 logcat）。 */
    public static String run() {
        SkSelfTest t = new SkSelfTest();
        try {
            t.matrixSingle();
            t.matrixCombos();
            t.matrixTiming();
            t.matrixRules();
        } catch (Throwable e) {
            t.failed++;
            t.out.add("[FAIL] selftest crashed: " + e);
        }
        String summary = "TOTAL " + (t.passed + t.failed)
                + "  PASSED " + t.passed + "  FAILED " + t.failed;
        t.out.add(summary);
        for (String line : t.out) Log.i(TAG, line);
        return summary + "\n" + join(t.out);
    }

    // ------------------------------------------------------------ 用例

    /** 2 键 × 4 按压 × 6 动作：单映射矩阵。 */
    private void matrixSingle() {
        String[] actions = {A_NEXT, A_PREV, A_PLAY, A_PAUSE, A_TOGGLE, A_PASS};
        String[] keys = {K_UP, K_DOWN};
        int[] codes = {KEY_UP, KEY_DOWN};

        for (int k = 0; k < keys.length; k++) {
            for (String press : new String[]{SHORT, LONG, DOUBLE, REPEAT}) {
                for (String action : actions) {
                    String name = "single " + keys[k] + "/" + press + "/" + action;
                    Harness h = new Harness(snapOf(map(keys[k], press, action)));
                    switch (press) {
                        case SHORT:
                            h.tap(codes[k], 80, 500);
                            break;
                        case LONG:
                            h.tap(codes[k], 800, 500);
                            break;
                        case DOUBLE:
                            h.tap(codes[k], 80, 100);
                            h.tap(codes[k], 80, 500);
                            break;
                        default: // REPEAT 3 次
                            h.tap(codes[k], 80, 110);
                            h.tap(codes[k], 80, 110);
                            h.tap(codes[k], 80, 500);
                            break;
                    }
                    boolean passthrough = A_PASS.equals(action);
                    expect(name,
                            h.summary(),
                            passthrough
                                    ? "actions=[] vol=0 consume=false"
                                    : "actions=[" + action + "] vol=0 consume=true");
                }
            }
        }

        // 双击：只按一下不该有任何动作；按两下才出动作（只双击映射时一下也不调音量）
        Harness one = new Harness(snapOf(map(K_UP, DOUBLE, A_TOGGLE)));
        one.tap(KEY_UP, 80, 500);
        expect("double/only-one-tap -> nothing", one.summary(), "actions=[] vol=0 consume=true");

        // 连按：差一次不出动作
        Harness two = new Harness(snapOf(map(K_UP, REPEAT, A_NEXT, 500, 300, 3)));
        two.tap(KEY_UP, 80, 110);
        two.tap(KEY_UP, 80, 500);
        expect("repeat/2-of-3 -> nothing", two.summary(), "actions=[] vol=0 consume=true");
    }

    /** 同一按键上多种按压方式并存时的优先级与互不串扰。 */
    private void matrixCombos() {
        // 短按 + 长按
        SkModel.Snapshot s1 = snapOf(map(K_UP, SHORT, A_NEXT), map(K_UP, LONG, A_TOGGLE));
        Harness shortTap = new Harness(s1);
        shortTap.tap(KEY_UP, 80, 500);
        expect("combo short+long / quick tap -> short", shortTap.summary(),
                "actions=[" + A_NEXT + "] vol=0 consume=true");
        Harness longTap = new Harness(s1);
        longTap.tap(KEY_UP, 800, 500);
        expect("combo short+long / hold -> long only", longTap.summary(),
                "actions=[" + A_TOGGLE + "] vol=0 consume=true");

        // 短按 + 双击：双击时不能再先执行短按
        SkModel.Snapshot s2 = snapOf(map(K_UP, SHORT, A_NEXT), map(K_UP, DOUBLE, A_PREV));
        Harness single = new Harness(s2);
        single.tap(KEY_UP, 80, 500);
        expect("combo short+double / single -> short", single.summary(),
                "actions=[" + A_NEXT + "] vol=0 consume=true");
        Harness dbl = new Harness(s2);
        dbl.tap(KEY_UP, 80, 100);
        dbl.tap(KEY_UP, 80, 500);
        expect("combo short+double / double -> double only", dbl.summary(),
                "actions=[" + A_PREV + "] vol=0 consume=true");

        // 双击之后再单击：不能重复触发双击
        Harness third = new Harness(s2);
        third.tap(KEY_UP, 80, 100);
        third.tap(KEY_UP, 80, 100);
        third.tap(KEY_UP, 80, 500);
        expect("combo short+double / 3rd tap -> short", third.summary(),
                "actions=[" + A_PREV + ", " + A_NEXT + "] vol=0 consume=true");

        // 短按 + 连按
        SkModel.Snapshot s3 = snapOf(map(K_UP, SHORT, A_NEXT), map(K_UP, REPEAT, A_PAUSE, 500, 300, 3));
        Harness rep = new Harness(s3);
        rep.tap(KEY_UP, 80, 110);
        rep.tap(KEY_UP, 80, 110);
        rep.tap(KEY_UP, 80, 500);
        expect("combo short+repeat3 / 3 taps -> repeat only", rep.summary(),
                "actions=[" + A_PAUSE + "] vol=0 consume=true");

        // 四种全开
        SkModel.Snapshot s4 = snapOf(
                map(K_UP, SHORT, A_NEXT),
                map(K_UP, LONG, A_PLAY),
                map(K_UP, DOUBLE, A_PREV),
                map(K_UP, REPEAT, A_PAUSE, 500, 300, 4));
        Harness allShort = new Harness(s4);
        allShort.tap(KEY_UP, 80, 500);
        expect("combo all4 / tap -> short", allShort.summary(),
                "actions=[" + A_NEXT + "] vol=0 consume=true");
        Harness allLong = new Harness(s4);
        allLong.tap(KEY_UP, 800, 500);
        expect("combo all4 / hold -> long", allLong.summary(),
                "actions=[" + A_PLAY + "] vol=0 consume=true");
        Harness allDouble = new Harness(s4);
        allDouble.tap(KEY_UP, 80, 100);
        allDouble.tap(KEY_UP, 80, 500);
        expect("combo all4 / double -> double", allDouble.summary(),
                "actions=[" + A_PREV + "] vol=0 consume=true");
        // 双击与连按同时配置时：**先达成的次数赢** —— 第 2 下双击就命中并清零计数，
        // 所以 4 下快速按是「两次双击」，连按(4) 在这种组合下不可达（文档里写明，UI 侧也要提示）。
        Harness allRepeat = new Harness(s4);
        allRepeat.tap(KEY_UP, 80, 110);
        allRepeat.tap(KEY_UP, 80, 110);
        allRepeat.tap(KEY_UP, 80, 110);
        allRepeat.tap(KEY_UP, 80, 500);
        expect("combo all4 / 4 taps -> double x2 (repeat 不可达)", allRepeat.summary(),
                "actions=[" + A_PREV + ", " + A_PREV + "] vol=0 consume=true");

        // 只要不配双击，连按(3) 正常命中（上一条 combo short+repeat3 已覆盖），
        // 这里补一条「双击+连按(3)」确认双击优先。
        Harness dblRepeat = new Harness(snapOf(
                map(K_UP, DOUBLE, A_PREV), map(K_UP, REPEAT, A_PAUSE, 500, 300, 3)));
        dblRepeat.tap(KEY_UP, 80, 110);
        dblRepeat.tap(KEY_UP, 80, 110);
        dblRepeat.tap(KEY_UP, 80, 500);
        expect("combo double+repeat3 / 3 taps -> double only", dblRepeat.summary(),
                "actions=[" + A_PREV + "] vol=0 consume=true");

        // 「保持原功能」：短按设成 pass、长按映射下一首 → 轻点要真的调音量
        SkModel.Snapshot s5 = snapOf(map(K_UP, SHORT, A_PASS), map(K_UP, LONG, A_NEXT));
        Harness passShort = new Harness(s5);
        passShort.tap(KEY_UP, 80, 500);
        expect("pass/short=pass long=next / tap -> volume", passShort.summary(),
                "actions=[] vol=1 consume=true");
        Harness passLong = new Harness(s5);
        passLong.tap(KEY_UP, 800, 500);
        expect("pass/short=pass long=next / hold -> next", passLong.summary(),
                "actions=[" + A_NEXT + "] vol=0 consume=true");

        // 兜底：如果某个 ROM 仍然把 repeat 事件送进 interceptKeyBeforeQueueing，
        // 长按也只能出一次动作（计时器已触发过就不再重复）。
        Harness withRepeat = new Harness(snapOf(map(K_UP, LONG, A_PLAY, 500, 300, 3)));
        withRepeat.keyDown(KEY_UP);
        withRepeat.clock.advance(600);       // 计时器到点 → 长按
        withRepeat.keyRepeat(KEY_UP, 1);
        withRepeat.keyRepeat(KEY_UP, 2);
        withRepeat.clock.advance(100);
        withRepeat.keyUp(KEY_UP);
        withRepeat.clock.advance(300);
        expect("long + stray repeat -> fired once", withRepeat.summary(),
                "actions=[" + A_PLAY + "] vol=0 consume=true");

        // 没到时间就松手，且同时送了 repeat（部分 ROM 的短按也会带 repeat=0 之后的抖动）
        Harness earlyRepeat = new Harness(snapOf(map(K_UP, SHORT, A_NEXT), map(K_UP, LONG, A_PLAY)));
        earlyRepeat.keyDown(KEY_UP);
        earlyRepeat.clock.advance(200);
        earlyRepeat.keyRepeat(KEY_UP, 1);
        earlyRepeat.keyUp(KEY_UP);
        earlyRepeat.clock.advance(300);
        expect("short + stray repeat -> short once", earlyRepeat.summary(),
                "actions=[" + A_NEXT + "] vol=0 consume=true");

        // 只有长按映射：轻点没到时间 → 补系统音量
        Harness onlyLong = new Harness(snapOf(map(K_UP, LONG, A_NEXT)));
        onlyLong.tap(KEY_UP, 80, 500);
        expect("long-only / quick tap -> volume", onlyLong.summary(),
                "actions=[] vol=1 consume=true");
    }

    /** 时序边界：窗口/阈值左右各差 20%，以及不同的 longPressMs / doubleTapMs / repeatCount。 */
    private void matrixTiming() {
        // 双击窗口 300ms：250 命中、350 不命中
        SkModel.Snapshot d = snapOf(map(K_UP, DOUBLE, A_PREV, 500, 300, 3));
        Harness inWindow = new Harness(d);
        inWindow.tap(KEY_UP, 80, 250);
        inWindow.tap(KEY_UP, 80, 500);
        expect("timing double 300ms / gap 250 -> hit", inWindow.summary(),
                "actions=[" + A_PREV + "] vol=0 consume=true");
        Harness outWindow = new Harness(d);
        outWindow.tap(KEY_UP, 80, 350);
        outWindow.tap(KEY_UP, 80, 500);
        expect("timing double 300ms / gap 350 -> miss", outWindow.summary(),
                "actions=[] vol=0 consume=true");

        // 自定义双击窗口 600ms：gap 500 命中
        Harness wide = new Harness(snapOf(map(K_UP, DOUBLE, A_NEXT, 500, 600, 3)));
        wide.tap(KEY_UP, 80, 500);
        wide.tap(KEY_UP, 80, 500);
        expect("timing double 600ms / gap 500 -> hit", wide.summary(),
                "actions=[" + A_NEXT + "] vol=0 consume=true");

        // 长按阈值 500ms：450 松手算短按，560 算长按
        SkModel.Snapshot sl = snapOf(map(K_UP, SHORT, A_NEXT), map(K_UP, LONG, A_TOGGLE, 500, 300, 3));
        Harness early = new Harness(sl);
        early.tap(KEY_UP, 450, 500);
        expect("timing long 500ms / hold 450 -> short", early.summary(),
                "actions=[" + A_NEXT + "] vol=0 consume=true");
        Harness late = new Harness(sl);
        late.tap(KEY_UP, 560, 500);
        expect("timing long 500ms / hold 560 -> long", late.summary(),
                "actions=[" + A_TOGGLE + "] vol=0 consume=true");

        // 自定义长按阈值 1200ms：800ms 松手仍是短按
        SkModel.Snapshot slow = snapOf(
                map(K_UP, SHORT, A_NEXT), map(K_UP, LONG, A_PREV, 1200, 300, 3));
        Harness s800 = new Harness(slow);
        s800.tap(KEY_UP, 800, 500);
        expect("timing long 1200ms / hold 800 -> short", s800.summary(),
                "actions=[" + A_NEXT + "] vol=0 consume=true");
        Harness s1300 = new Harness(slow);
        s1300.tap(KEY_UP, 1300, 500);
        expect("timing long 1200ms / hold 1300 -> long", s1300.summary(),
                "actions=[" + A_PREV + "] vol=0 consume=true");

        // 连按次数 2
        Harness r2 = new Harness(snapOf(map(K_UP, REPEAT, A_PAUSE, 500, 300, 2)));
        r2.tap(KEY_UP, 80, 110);
        r2.tap(KEY_UP, 80, 500);
        expect("timing repeat x2 -> hit", r2.summary(),
                "actions=[" + A_PAUSE + "] vol=0 consume=true");
    }

    /** 规则层：优先级、停用、总开关、没有该键映射、两个键互不影响。 */
    private void matrixRules() {
        // 优先级：高优先级规则先命中
        SkModel.Rule hi = rule("hi", true, 5, Arrays.asList(map(K_UP, SHORT, A_NEXT)));
        SkModel.Rule lo = rule("lo", true, 0, Arrays.asList(map(K_UP, SHORT, A_PREV)));
        Harness prio = new Harness(snapshot(true, Arrays.asList(hi, lo)));
        prio.tap(KEY_UP, 80, 500);
        expect("rule priority -> next", prio.summary(),
                "actions=[" + A_NEXT + "] vol=0 consume=true");

        // 停用的规则不参与
        SkModel.Rule off = rule("off", false, 9, Arrays.asList(map(K_UP, SHORT, A_NEXT)));
        Harness disabled = new Harness(snapshot(true, Collections.singletonList(off)));
        disabled.tap(KEY_UP, 80, 500);
        expect("rule disabled -> passthrough", disabled.summary(),
                "actions=[] vol=0 consume=false");

        // 总开关关闭
        SkModel.Rule on = rule("on", true, 0, Arrays.asList(map(K_UP, SHORT, A_NEXT)));
        Harness master = new Harness(snapshot(false, Collections.singletonList(on)));
        master.tap(KEY_UP, 80, 500);
        expect("master off -> passthrough", master.summary(),
                "actions=[] vol=0 consume=false");

        // 规则里没有这个键的映射
        Harness otherKey = new Harness(snapOf(map(K_DOWN, SHORT, A_NEXT)));
        otherKey.tap(KEY_UP, 80, 500);
        expect("key not mapped -> passthrough", otherKey.summary(),
                "actions=[] vol=0 consume=false");

        // 两个键各自映射，互不串扰
        Harness both = new Harness(snapOf(map(K_UP, SHORT, A_NEXT), map(K_DOWN, SHORT, A_PREV)));
        both.tap(KEY_UP, 80, 300);
        both.tap(KEY_DOWN, 80, 500);
        expect("both keys -> own action", both.summary(),
                "actions=[" + A_NEXT + ", " + A_PREV + "] vol=0 consume=true");

        // ---- 同一个键的按压方式**分散在多条规则**里（用户最常见的配法）----
        // 「短按下一首」「长按上一首」各建一条规则，两条都写 volume_up。
        // 旧实现只取第一条命中的规则，长按 / 双击永远轮不到 —— 这是真机反馈的
        // 「双击和长按不生效」的根因，这两条用例专门守住它。
        SkModel.Rule rShort = rule("short-next", true, 5,
                Arrays.asList(map(K_UP, SHORT, A_NEXT)));
        SkModel.Rule rLong = rule("long-prev", true, 0,
                Arrays.asList(map(K_UP, LONG, A_PREV)));

        Harness split = new Harness(snapshot(true, Arrays.asList(rShort, rLong)));
        split.tap(KEY_UP, 80, 300);
        split.tap(KEY_UP, 800, 500);
        expect("split rules: short from r1, long from r2", split.summary(),
                "actions=[" + A_NEXT + ", " + A_PREV + "] vol=0 consume=true");

        SkModel.Rule rDouble = rule("double-play", true, 0,
                Arrays.asList(map(K_DOWN, DOUBLE, A_TOGGLE)));
        SkModel.Rule rDownShort = rule("short-play", true, 5,
                Arrays.asList(map(K_DOWN, SHORT, A_PLAY)));
        Harness splitDouble = new Harness(snapshot(true, Arrays.asList(rDownShort, rDouble)));
        splitDouble.tap(KEY_DOWN, 80, 100);
        splitDouble.tap(KEY_DOWN, 80, 500);
        expect("split rules: double from r2 wins over r1 short", splitDouble.summary(),
                "actions=[" + A_TOGGLE + "] vol=0 consume=true");

        // 高优先级那条显式配「保持原功能」：占位，不能被低优先级的规则顶掉
        SkModel.Rule rPass = rule("short-pass", true, 9,
                Arrays.asList(map(K_UP, SHORT, A_PASS)));
        SkModel.Rule rNext = rule("short-next-lo", true, 0,
                Arrays.asList(map(K_UP, SHORT, A_NEXT)));
        Harness passWins = new Harness(snapshot(true, Arrays.asList(rPass, rNext)));
        passWins.tap(KEY_UP, 80, 500);
        // 全部按压方式都是「保持原功能」→ 引擎完全不接管：不吞键、不补偿，
        // 音量由系统自己调（所以 vol 仍为 0，consume 为 false）。
        expect("split rules: higher-priority pass blocks lower rule", passWins.summary(),
                "actions=[] vol=0 consume=false");

        // 动作按各自规则的条件包投递（短按来自 A 应用，长按来自 B 应用）
        SkModel.Rule appA = ruleWithPkgs("a", 5, map(K_UP, SHORT, A_NEXT), "com.a");
        SkModel.Rule appB = ruleWithPkgs("b", 0, map(K_UP, LONG, A_PREV), "com.b");
        Harness pkgSplit = new Harness(snapshot(true, Arrays.asList(appA, appB)));
        pkgSplit.tap(KEY_UP, 80, 300);
        pkgSplit.tap(KEY_UP, 800, 500);
        expect("split rules: pkgs follow their own rule", pkgSplit.fireLog(),
                "next@[com.a], previous@[com.b]");
    }

    // ------------------------------------------------------------ 断言与报告

    private void expect(String name, String actual, String expected) {
        if (expected.equals(actual)) {
            passed++;
            out.add("[PASS] " + name);
        } else {
            failed++;
            out.add("[FAIL] " + name + " | expected=" + expected + " | actual=" + actual);
        }
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(s).append('\n');
        return sb.toString();
    }

    // ------------------------------------------------------------ 测试脚手架

    /** 虚拟时钟：postDelayed 只排队，advance 时按时间顺序执行。 */
    private static final class VirtualClock implements SkEngine.Clock {
        private static final class Task {
            final Runnable r;
            final long at;

            Task(Runnable r, long at) {
                this.r = r;
                this.at = at;
            }
        }

        private long now = 100000L;
        private final List<Task> tasks = new ArrayList<Task>();

        @Override
        public long uptime() {
            return now;
        }

        @Override
        public void postDelayed(Runnable r, long delayMs) {
            tasks.add(new Task(r, now + Math.max(1L, delayMs)));
        }

        @Override
        public void remove(Runnable r) {
            for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
                if (it.next().r == r) it.remove();
            }
        }

        void advance(long ms) {
            long target = now + ms;
            while (true) {
                Task due = null;
                for (Task t : tasks) {
                    if (t.at <= target && (due == null || t.at < due.at)) due = t;
                }
                if (due == null) break;
                tasks.remove(due);
                now = due.at;
                due.r.run();
            }
            now = target;
        }
    }

    /** 记录引擎下发的动作与音量补偿次数。 */
    private static final class Recorder implements SkEngine.ActionSink {
        final List<String> actions = new ArrayList<String>();
        final List<List<String>> pkgs = new ArrayList<List<String>>();
        int volume;

        @Override
        public void fire(String action, List<String> p) {
            actions.add(action);
            pkgs.add(new ArrayList<String>(p == null ? Collections.<String>emptyList() : p));
        }

        @Override
        public void adjustVolume(int keyCode) {
            volume++;
        }

        /** "next@[com.a], previous@[com.b]" —— 用来看动作是否跟着各自规则的包走。 */
        String fireLog() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < actions.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(actions.get(i)).append('@').append(pkgs.get(i));
            }
            return sb.toString();
        }
    }

    /** 一个用例的运行环境：虚拟时钟 + 记录器 + 真实引擎。 */
    private static final class Harness {
        final VirtualClock clock = new VirtualClock();
        final Recorder rec = new Recorder();
        final SkEngine engine = new SkEngine(clock, rec);
        boolean allConsumed = true;

        Harness(SkModel.Snapshot snap) {
            engine.setTestSnapshot(snap);
        }

        long downAt;

        void keyDown(int keyCode) {
            downAt = clock.uptime();
            allConsumed &= engine.onKeyEvent(
                    new KeyEvent(downAt, downAt, KeyEvent.ACTION_DOWN, keyCode, 0), null).consume;
        }

        void keyRepeat(int keyCode, int count) {
            allConsumed &= engine.onKeyEvent(
                    new KeyEvent(downAt, clock.uptime(), KeyEvent.ACTION_DOWN, keyCode, count),
                    null).consume;
        }

        void keyUp(int keyCode) {
            allConsumed &= engine.onKeyEvent(
                    new KeyEvent(downAt, clock.uptime(), KeyEvent.ACTION_UP, keyCode, 0), null).consume;
        }

        void tap(int keyCode, long holdMs, long gapMs) {
            keyDown(keyCode);
            clock.advance(holdMs);
            keyUp(keyCode);
            clock.advance(gapMs);
        }

        String summary() {
            return "actions=" + rec.actions + " vol=" + rec.volume + " consume=" + allConsumed;
        }

        /** 动作 + 各自目标的记录（验证包名跟随规则）。 */
        String fireLog() {
            return rec.fireLog();
        }
    }

    // ---- 构造配置 ----

    private static SkModel.Mapping map(String key, String press, String action) {
        return new SkModel.Mapping(key, press, action, 500, 300, 3);
    }

    private static SkModel.Mapping map(String key, String press, String action,
                                       int longMs, int doubleMs, int count) {
        return new SkModel.Mapping(key, press, action, longMs, doubleMs, count);
    }

    private static SkModel.Rule rule(String id, boolean enabled, int priority,
                                     List<SkModel.Mapping> maps) {
        return new SkModel.Rule(id, id, enabled, true, priority,
                Collections.<SkModel.Condition>emptyList(), maps);
    }

    /** 带一条「屏幕关闭」条件的规则（自检里 SkContext 的 screenOn 默认 false，条件恒成立），
     *  用来验证动作是否按各自规则的包投递。 */
    private static SkModel.Rule ruleWithPkgs(String id, int priority, SkModel.Mapping m, String pkg) {
        SkModel.Condition cond = new SkModel.Condition(SkModel.C_SCREEN_OFF, true,
                Collections.singletonList(pkg));
        return new SkModel.Rule(id, id, true, true, priority,
                Collections.singletonList(cond), Collections.singletonList(m));
    }

    private static SkModel.Snapshot snapOf(SkModel.Mapping... maps) {
        return snapshot(true, Collections.singletonList(rule("r", true, 0, Arrays.asList(maps))));
    }

    private static SkModel.Snapshot snapshot(boolean master, List<SkModel.Rule> rules) {
        return new SkModel.Snapshot(1L, "", master, rules);
    }
}
