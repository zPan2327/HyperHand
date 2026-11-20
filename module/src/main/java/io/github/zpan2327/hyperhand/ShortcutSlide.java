package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * 快捷菜单「iOS 式长按滑选」——长按图标弹出快捷菜单后，手指不离开屏幕，
 * 直接滑到某个菜单项上抬手即选中该项；原生「长按后点菜单项」完全保留。
 *
 * <p>本类的 Hook 点、方法签名、字段名全部来自本机真机反汇编 + Frida 动态验证
 * （HyperOS OS3.0.307 / launcher RELEASE-7.50.06.2581，见 research/真机验证记录.md）：</p>
 * <ul>
 *   <li>取消回调 {@code com.miui.home.dragdrop.ShortcutMenuDragListener
 *       .onDragOverThresholdWhenShortcutMenuShowing(IDragObject)V}（实现者
 *       {@code ItemLongClickListener$2}）：其字节码里唯一的"关菜单"动作是
 *       {@code launcher.getStateManager().goToState(LauncherState.NORMAL, false)}。
 *       短路它即可保住菜单；真机证实图标拖动拾取发生在回到 NORMAL 之后，
 *       所以短路同一次回调也顺带阻止了图标被挪动。动态实测阈值约 100px，MOVE 中实时触发。</li>
 *   <li>手势观测 {@code com.miui.home.launcher.SuperDraglayer.dispatchTouchEvent(MotionEvent)}：
 *       DOWN→MOVE→UP 全程可见，能读 raw 坐标与 pointerCount；before hook 只观察、不改返回值。</li>
 *   <li>菜单几何：{@code ShortcutMenuLayer.mShortcutMenu} → {@code shortcuts.ShortcutMenu}
 *       的 mAppShortcutMenu / mSystemShortcutMenu / mFolderShortcutMenu /
 *       mAddWidgetShortcutMenu / mWidgetShortcutMenu 五个 ViewGroup，对直接子 View 取
 *       {@code getGlobalVisibleRect()} 得到 item 屏幕矩形。</li>
 *   <li>提交：item 根 View（真机为匿名 LinearLayout）的 {@code performClick()} 已被动态证实
 *       等价于原生点击（bindShortcutMenuItem 把 ShortcutMenuItem.getOnClickListener()
 *       包成 View 的 OnClickListener）。</li>
 * </ul>
 *
 * <p><b>划区拦截</b>：只在本次手势由长按打开菜单、单指、且手指位移"朝菜单矩形靠近"
 * （夹角小于可配阈值 + 距离确实收敛）时才短路取消；其余一律 {@link Hooks#PROCEED}
 * 放行原生，保证"长按后往非菜单位置拖 = 原生拖动排序"完全不变。</p>
 */
final class ShortcutSlide {

    // 真机已验证的类名 / 方法名
    // 接口（仅用于类探测/兼容）；真正的取消回调实现类是 ItemLongClickListener$2——
    // Xposed 精确 Hook 接口方法拦不到实现类调用，必须 Hook 实现类本身（真机验证）。
    private static final String CLS_DRAG_LISTENER_IMPL =
            "com.miui.home.launcher.touch.ItemLongClickListener$2";
    private static final String CLS_DRAG_LISTENER =
            "com.miui.home.dragdrop.ShortcutMenuDragListener";
    private static final String CLS_SUPER_DRAGLAYER =
            "com.miui.home.launcher.SuperDraglayer";
    private static final String CLS_MENU_LAYER =
            "com.miui.home.launcher.ShortcutMenuLayer";
    private static final String CLS_APP = "com.miui.home.launcher.Application";
    private static final String CLS_LAUNCHER_STATE =
            "com.miui.home.launcher.LauncherState";
    // 真正的取消汇聚点（真机 Frida 实证）：所有关菜单最终都走
    // BaseLauncher.cancelShortcutMenu(int flag, CancelShortcutMenuReason)。
    // flag=9 = drag_over_threshold（滑动超阈值），flag=7 = click_outside_of_menu。
    // ShortcutMenuDragListener 的回调在这条路径上【不会被调用】，不能作为拦截点。
    private static final String CLS_BASE_LAUNCHER =
            "com.miui.home.launcher.BaseLauncher";
    private static final String M_CANCEL_MENU = "cancelShortcutMenu";
    private static final int FLAG_DRAG_OVER_THRESHOLD = 9;
    // flag=9 的唯一源头（真机反汇编 + Frida 实证）：每次拖动超阈值都会调用，
    // 参数 IDragObject.getX()/getY() 是【跟手的真实屏幕坐标】，是可靠判定来源。
    private static final String M_ON_THRESHOLD_SRC =
            "onDragOverThresholdWhenShortcutMenuShowing";
    // 真实手指坐标源（Frida 实证 x/y 参数 == ev.getRawX/Y，每次 MOVE 必调用）
    private static final String CLS_DRAG_CONTROLLER =
            "com.miui.home.launcher.DragController";
    private static final String M_HANDLE_MOVE = "handleMoveEvent";
    // 图标浮层跟随手指的真正方法（真机反汇编实证）：DragObject.move → mDragViews[0].moveX/moveY。
    // 只短路它就能让图标停在原地，而不破坏 handleMoveEvent 里的 flag=9 与 drop 状态机。
    private static final String CLS_DRAG_OBJECT = "com.miui.home.launcher.DragObject";
    private static final String M_DRAG_OBJ_MOVE = "move";
    // UP 落位点：滑选时用 mMotionDownX/Y 让图标落回原格
    private static final String M_DROP = "drop";
    private static final String F_MOTION_DOWN_X = "mMotionDownX";
    private static final String F_MOTION_DOWN_Y = "mMotionDownY";

    private static final String M_ON_THRESHOLD =
            "onDragOverThresholdWhenShortcutMenuShowing";
    private static final String M_ON_SECONDARY =
            "onSecondaryPointerDownWhenShortcutMenuShowing";

    // 会话状态
    private static final int IDLE = 0;
    private static final int MENU_OPEN = 1;   // 菜单已弹出，等待滑动判定
    private static final int SLIDING = 2;     // 已判定朝菜单，保住菜单并跟踪高亮
    private static final int COMMITTED = 3;   // 已提交，等待收尾

    private final XposedModule module;
    private final Hooks hooks;
    private final ClassLoader cl;
    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    private int state = IDLE;
    private long sessionId;
    private float downX, downY;      // 长按 DOWN 的屏幕坐标（锚点 = 图标位置）
    private float lastX, lastY;      // SuperDraglayer 观测到的坐标（仅辅助，真人快速滑动时会过时）
    private float liveX = Float.NaN, liveY = Float.NaN; // 来自 IDragObject 的【可靠】拖动坐标
    private boolean pointerDown;
    private boolean multiTouch;
    private boolean headingToMenu;   // 最近一帧的"朝菜单"判定
    private long guardUntil;         // 提交后短暂抑制残留回调
    private volatile boolean restoreIconOnDrop = false; // 滑选提交后，drop 时把图标放回原格
    private long restoreIconUntil = 0L;
    // 独立"待提交"标志：一旦确认滑选意图就置位，真人抬手的 drop() 里消费。
    // 不依赖 state 在多次异步回调间不被改写（ACTION_CANCEL 等可能干扰 state）。
    private volatile boolean pendingCommit = false;

    private Object shortcutMenu;     // shortcuts.ShortcutMenu 实例
    private View highlighted;
    private Drawable highlightedOldForeground; // 高亮前 item 的 foreground，用于恢复
    // 菜单项的浅灰色按压态（iOS 风格）：半透明黑叠加在浅色菜单项上得到的中性灰填充，
    // 不使用任何强调色。用户截图里"新建日程"项即为此种浅灰按压态。
    private static final int HIGHLIGHT_COLOR = 0x2E000000;

    // 菜单是否【物理可见】。与 state 分离：长按抬手后 state 会回 IDLE，但菜单仍显示。
    // 用于「菜单开着时点击图标直接打开 App」判定（真机证实此时 DOWN 到 SuperDraglayer、
    // UP 被菜单层吞掉，performClick/onClick 都不会触发，必须自己补）。
    private volatile boolean menuShowing = false;

    // 「菜单开着点图标」的延迟判定：DOWN 后不立即打开——菜单开着时用户可能想重新长按/
    // 滑动（真机证实这类 DOWN 也会到达 SuperDraglayer）。延迟一个短窗口，期间若有 MOVE
    // （滑动）或新的 MENU_SHOW（重新长按）则取消；只有"按下不动"才判定为点击并打开 App。
    // 单一复用的 Runnable：队列里永远最多一个任务，removeCallbacks 才能彻底取消。
    // （之前每次 DOWN new 一个 Runnable 会导致旧任务残留在队列、取消失效。）
    private final Runnable iconTapRunnable = new Runnable() {
        @Override public void run() {
            if (iconTapCancelled || iconTapSid != sessionId || state != IDLE) {
                if (Cfg.get(ctx).debug) log("MENU_OPEN_ICON_TAP cancelled/dropped");
                return;
            }
            View t = iconTapTarget;
            log("MENU_OPEN_ICON_TAP -> performClick icon");
            closeMenu();                 // 关菜单
            menuShowing = false;
            clearHighlight();
            iconTapTarget = null;
            try {
                if (t != null) {
                    // 菜单开着点图标 / 滑选未命中后再点图标：只关菜单 + 打开 App，
                    // 这里刻意不调用 Haptics —— 用户不需要这种"补点按"震动。
                    // 滑选命中菜单项的反馈仍在 commitOrAbort() 里，不受影响。
                    t.performClick();    // 打开对应 App（与关菜单同帧）
                }
            } catch (Throwable e) {
                log("icon tap open failed: " + e);
            }
        }
    };
    private View iconTapTarget;
    private long iconTapSid;
    private long lastIconTapDownAt;     // 时间去重：区分"新点击"与同一手势的重复 DOWN
    private volatile boolean iconTapCancelled; // 本次 DOWN 的点击判定是否被 MOVE/长按打断

    // 弹出当前菜单的【宿主图标 A】的按下锚点。菜单开着时只有点在 A 上才
    // "关菜单 + 打开 A 的 App"；点背景模糊的其它图标 B 不能再走同一路径（否则误开 B 的 App）。
    // onMenuShown 时 downX/downY 还是长按 A 的锚点，记下它；判定时看目标图标矩形是否
    // 包含该锚点——比持有 View 引用更可靠，不受图标重排 / View 复用影响。
    private float hostAnchorX = Float.NaN, hostAnchorY = Float.NaN;

    private final List<Field> menuGroupFields = new ArrayList<Field>();
    private Method getLauncher;
    private Method getStateManager;
    private Method goToState;
    private Object normalState;

    ShortcutSlide(XposedModule module, Hooks hooks, ClassLoader cl, Context ctx) {
        this.module = module;
        this.hooks = hooks;
        this.cl = cl;
        this.ctx = ctx;
    }

    /** 由 {@code HyperHand.hookLauncher()} 调用，返回钩上的方法数。 */
    int hookAll() {
        final Cfg cfg = Cfg.get(ctx);
        int n = 0;

        // 0a) 【关键坐标源】DragController.handleMoveEvent(int x, int y, MotionEvent ev)
        //    Frida 实证 x/y 参数 == ev.getRawX()/getRawY()，每次 MOVE 必调用，
        //    菜单弹出后手势被 DragController 接管时仍持续更新。用它替代过时的
        //    SuperDraglayer 缓存（真人快速滑动时缓存只到 d=24px，而实际已滑上百像素）。
        try {
            Class<?> dc = hooks.findClass(CLS_DRAG_CONTROLLER, cl);
            if (dc != null) {
                Method hm = null;
                try {
                    hm = dc.getDeclaredMethod(M_HANDLE_MOVE, int.class, int.class,
                            MotionEvent.class);
                } catch (Throwable ignored) {
                }
                if (hm == null) {
                    for (Method mm : dc.getDeclaredMethods()) {
                        if (mm.getName().equals(M_HANDLE_MOVE)
                                && mm.getParameterTypes().length == 3) { hm = mm; break; }
                    }
                }
                if (hm != null) {
                    if (hooks.hook(hm, Hooks.before(new Hooks.Before() {
                        @Override
                        public Object run(XposedInterface.Chain chain) {
                            try {
                                List<Object> args = chain.getArgs();
                                if (args.size() < 3) return Hooks.PROCEED;
                                float mx = args.get(0) instanceof Number
                                        ? ((Number) args.get(0)).floatValue() : Float.NaN;
                                float my = args.get(1) instanceof Number
                                        ? ((Number) args.get(1)).floatValue() : Float.NaN;
                                if (Float.isNaN(mx) || Float.isNaN(my)) return Hooks.PROCEED;
                                // 关键：真实坐标只用于方向/命中判定；喂给原生的坐标在滑选
                                // 会话里钉在按下点，从根源阻止 findDropTarget → onDragOver
                                // 让「其他图标」让位重排（用户反馈的核心问题）。
                                return onHandleMove(chain, mx, my);
                            } catch (Throwable t) {
                                log("handleMove hook error: " + t);
                                return Hooks.PROCEED;
                            }
                        }
                    }))) n++;
                    log("DragController.handleMoveEvent hooked (pin coord to origin during slide)");
                } else {
                    log("WARN: DragController.handleMoveEvent not found — fallback to cached coord");
                }
            } else {
                log("WARN: DragController not found — fallback to cached coord");
            }
        } catch (Throwable t) {
            log("hook handleMoveEvent failed: " + t);
        }

        // 0a1) 【图标不跟随】DragObject.move(II)V —— 让 DragView 浮层跟随手指。
        //      真机反汇编实证：handleMoveEvent 006b 调 mDragObject.move(x,y) →
        //      mDragViews[0].moveX/moveY。滑选会话中短路它，图标就停在原格不动；
        //      只短路这一个方法，flag=9 与 drop 状态机照常运行（不破坏原生流程）。
        try {
            Class<?> dobj = hooks.findClass(CLS_DRAG_OBJECT, cl);
            if (dobj != null) {
                Method mv = null;
                try {
                    mv = dobj.getDeclaredMethod(M_DRAG_OBJ_MOVE, int.class, int.class);
                } catch (Throwable ignored) {
                }
                if (mv == null) {
                    for (Method mm : dobj.getDeclaredMethods()) {
                        if (mm.getName().equals(M_DRAG_OBJ_MOVE)
                                && mm.getParameterTypes().length == 2) { mv = mm; break; }
                    }
                }
                if (mv != null) {
                    if (hooks.hook(mv, Hooks.before(new Hooks.Before() {
                        @Override
                        public Object run(XposedInterface.Chain chain) {
                            try {
                                return shouldFreezeIcon() ? null : Hooks.PROCEED;
                            } catch (Throwable t) {
                                return Hooks.PROCEED;
                            }
                        }
                    }))) n++;
                    log("DragObject.move hooked (freeze icon during slide)");
                } else {
                    log("WARN: DragObject.move not found");
                }
            } else {
                log("WARN: DragObject not found");
            }
        } catch (Throwable t) {
            log("hook DragObject.move failed: " + t);
        }

        // 0a2) 【防图标落位】DragController.drop(FF)V —— UP 时用手指终点落位。
        //      滑选会话中把落点替换为原生按下点 mMotionDownX/Y，图标落回原格。
        try {
            Class<?> dc = hooks.findClass(CLS_DRAG_CONTROLLER, cl);
            if (dc != null) {
                Method drop = null;
                try {
                    drop = dc.getDeclaredMethod(M_DROP, float.class, float.class);
                } catch (Throwable ignored) {
                }
                if (drop != null) {
                    if (hooks.hook(drop, new XposedInterface.Hooker() {
                        @Override
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                List<Object> dargs = chain.getArgs();
                                float inX = dargs.size() > 0 && dargs.get(0) instanceof Number
                                        ? ((Number) dargs.get(0)).floatValue() : Float.NaN;
                                float inY = dargs.size() > 1 && dargs.get(1) instanceof Number
                                        ? ((Number) dargs.get(1)).floatValue() : Float.NaN;
                                int st = state;
                                Object dcObj = chain.getThisObject();
                                Float dx = readIntField(dcObj, F_MOTION_DOWN_X);
                                Float dy = readIntField(dcObj, F_MOTION_DOWN_Y);
                                if (Cfg.get(ctx).debug) {
                                    log("DROP invoked in=(" + (int) inX + "," + (int) inY
                                            + ") state=" + st + " down="
                                            + (dx == null ? "null" : (int) dx.floatValue())
                                            + "," + (dy == null ? "null" : (int) dy.floatValue())
                                            + " enabled=" + slideEnabled());
                                }

                                // 【关键修复 3 —— 真人抬手捕获】菜单弹出后系统发 ACTION_CANCEL，
                                // 手势被 DragController 接管，observe 所在的 SuperDraglayer
                                // 再也收不到 ACTION_UP —— 真人操作日志里从无 UP inSlide/COMMIT，
                                // 这才是"脚本能成、真人不行"的根因。drop(FF) 是每次拖动抬手
                                // 必然调用的点，用它作为滑选提交的可靠信号：抬手时若在滑选
                                // 会话中，直接用落点提交（performClick + 关菜单 + 恢复图标）。
                                // 若脚本场景已由 ACTION_UP 提交（state=COMMITTED），这里不重复。
                                if ((pendingCommit || st == SLIDING) && slideEnabled()
                                        && !multiTouch
                                        && !Float.isNaN(inX) && !Float.isNaN(inY)) {
                                    pendingCommit = false;
                                    if (cfg.debug) log("DROP inSlide COMMIT hitAt="
                                            + (int) inX + "," + (int) inY + " (via drop, no ACTION_UP)");
                                    commitOrAbort(cfg, inX, inY);   // 内部置 restoreIconOnDrop=true
                                }

                                // 【防漂移保险 B】restore 窗口内【每次】drop 都钉回原格，
                                // 不再一次性消费。真机实证事件会重复分发（一次手势 5 发
                                // ACTION_CANCEL）；若第一个 drop 消费掉标志，后续 drop 就
                                // 会用真实落点 (inX,inY) 把图标挪到菜单位置——这正是用户
                                // 看到的"放开后漂移"。改为窗口期内持续 restore，靠超时失效。
                                boolean shouldRestore = restoreIconOnDrop
                                        && System.currentTimeMillis() < restoreIconUntil;
                                if (shouldRestore && dx != null && dy != null) {
                                    log("DROP restore to down point (" + (int) dx.floatValue()
                                            + "," + (int) dy.floatValue() + ") state=" + st);
                                    return chain.proceed(new Object[]{dx, dy});
                                }
                            } catch (Throwable t) {
                                log("drop hook error: " + t);
                            }
                            return chain.proceed();
                        }
                    })) n++;
                    log("DragController.drop hooked (restore icon position)");
                } else {
                    log("WARN: DragController.drop(FF) not found");
                }
            }
        } catch (Throwable t) {
            log("hook drop failed: " + t);
        }

        // 0b) flag=9 源头拦截：onDragOverThresholdWhenShortcutMenuShowing(IDragObject)。
        //     坐标已由 0a 的 handleMoveEvent 提供（liveX/liveY），这里只做拦截判定。
        try {
            Class<?> bl = hooks.findClass(CLS_BASE_LAUNCHER, cl);
            Class<?> dragCls = hooks.findClass("com.miui.home.dragdrop.IDragObject", cl);
            if (bl != null && dragCls != null) {
                Method src = null;
                try {
                    src = bl.getDeclaredMethod(M_ON_THRESHOLD_SRC, dragCls);
                } catch (Throwable t) {
                    for (Method mm : bl.getDeclaredMethods()) {
                        if (mm.getName().equals(M_ON_THRESHOLD_SRC)
                                && mm.getParameterTypes().length == 1) { src = mm; break; }
                    }
                }
                if (src != null) {
                    if (hooks.hook(src, Hooks.before(new Hooks.Before() {
                        @Override
                        public Object run(XposedInterface.Chain chain) {
                            try {
                                return onThresholdSource(chain.getThisObject(), liveX, liveY);
                            } catch (Throwable t) {
                                log("onThresholdSource error: " + t);
                                return Hooks.PROCEED;
                            }
                        }
                    }))) n++;
                    log("onDragOverThresholdWhenShortcutMenuShowing hooked");
                } else {
                    log("WARN: onDragOverThreshold source method not found");
                }
            } else {
                log("WARN: BaseLauncher/IDragObject not found for source hook");
            }
        } catch (Throwable t) {
            log("hook onDragOverThreshold source failed: " + t);
        }

        // 1) 取消汇聚点 —— 第二道防线：所有关菜单都走
        //    BaseLauncher.cancelShortcutMenu(int flag, reason)，其中 flag=9 是滑动超阈值。
        //    以前试图 Hook ShortcutMenuDragListener / ItemLongClickListener$2 的回调，
        //    真机证明在这条取消路径上根本不会被调用，已废弃。
        try {
            Class<?> bl = hooks.findClass(CLS_BASE_LAUNCHER, cl);
            if (bl != null) {
                // 用 getDeclaredMethod(name, 精确参数类型) 而非遍历 getDeclaredMethods()：
                // 后者在同一类里其它方法签名引用本 ROM 缺失类（NewHomeView）时会整体
                // 抛 NoClassDefFoundError，导致目标方法拿不到（真机踩坑）。
                Method cancel = findMethodExact(bl, M_CANCEL_MENU, 2);
                int m = 0;
                if (cancel != null) {
                    if (hooks.hook(cancel, Hooks.before(new Hooks.Before() {
                        @Override
                        public Object run(XposedInterface.Chain chain) {
                            try {
                                List<Object> args = chain.getArgs();
                                int flag = -1;
                                if (args.size() >= 1 && args.get(0) instanceof Integer) {
                                    flag = (Integer) args.get(0);
                                }
                                return onCancelMenu(flag, chain.getThisObject());
                            } catch (Throwable t) {
                                log("onCancelMenu error: " + t);
                                return Hooks.PROCEED;
                            }
                        }
                    }))) m = 1;
                }
                n += m;
                log("BaseLauncher.cancelShortcutMenu hooked (" + m + ")");
                if (m == 0) {
                    log("WARN: cancelShortcutMenu not hooked — slide WILL NOT work");
                }
            } else {
                log("BaseLauncher NOT FOUND — slide disabled");
            }
        } catch (Throwable t) {
            log("hook cancelShortcutMenu failed: " + t);
        }

        // 2) 手势观测 —— 只观察，不改返回值
        try {
            Class<?> sdl = hooks.findClass(CLS_SUPER_DRAGLAYER, cl);
            if (sdl != null) {
                int m = hooks.hookMethods(sdl, "dispatchTouchEvent",
                        Hooks.before(new Hooks.Before() {
                            @Override
                            public Object run(XposedInterface.Chain chain) {
                                try {
                                    List<Object> args = chain.getArgs();
                                    if (args.size() == 1 && args.get(0) instanceof MotionEvent) {
                                        observe((MotionEvent) args.get(0), chain.getThisObject());
                                    }
                                } catch (Throwable t) {
                                    log("observe error: " + t);
                                }
                                return Hooks.PROCEED;
                            }
                        }));
                n += m;
                log("SuperDraglayer.dispatchTouchEvent hooked (" + m + ")");
            } else {
                log("SuperDraglayer NOT FOUND — slide disabled");
            }
        } catch (Throwable t) {
            log("hook dispatchTouchEvent failed: " + t);
        }

        // 3) 菜单显示/隐藏 —— 抓实例、界定会话
        try {
            Class<?> layer = hooks.findClass(CLS_MENU_LAYER, cl);
            if (layer != null) {
                int m = hooks.hookMethods(layer, "showShortcutMenu",
                        Hooks.after(new Hooks.After() {
                            @Override
                            public void run(XposedInterface.Chain chain, Object result) {
                                try {
                                    onMenuShown(chain.getThisObject());
                                } catch (Throwable t) {
                                    log("onMenuShown error: " + t);
                                }
                            }
                        }));
                m += hooks.hookMethods(layer, "hideShortcutMenu",
                        Hooks.before(new Hooks.Before() {
                            @Override
                            public Object run(XposedInterface.Chain chain) {
                                onMenuHidden();
                                return Hooks.PROCEED;
                            }
                        }));
                n += m;
                log("ShortcutMenuLayer show/hide hooked (" + m + ")");
            }
        } catch (Throwable t) {
            log("hook ShortcutMenuLayer failed: " + t);
        }

        prepareReflection();
        log("ShortcutSlide installed (" + n + " hooks, enabled=" + cfg.slideEnabled + ")");
        return n;
    }

    // ------------------------------------------------------------------ 会话

    private void onMenuShown(Object layer) {
        shortcutMenu = readField(layer, "mShortcutMenu");
        if (shortcutMenu != null) cacheMenuGroups(shortcutMenu.getClass());
        sessionId++;
        cancelPendingIconTap();   // 菜单（重新）弹出 = 本次是长按，不是"点击图标"
        state = MENU_OPEN;
        menuShowing = true;
        // 菜单刚弹出，此刻 downX/downY 正是长按宿主图标 A 的按下锚点。记下来用于
        // 之后区分"点 A"（打开 A）和"点背景图标 B"（只关菜单，不打开 B）。
        hostAnchorX = downX;
        hostAnchorY = downY;
        headingToMenu = false;
        clearHighlight();
        guardUntil = 0L;
        pendingCommit = false;
        restoreIconOnDrop = false;
        liveX = Float.NaN; liveY = Float.NaN;
        if (Cfg.get(ctx).debug) {
            log("MENU_SHOW id=" + sessionId + " menu=" + (shortcutMenu == null ? "?" : "ok")
                    + " groups=" + menuGroupFields.size());
        }
    }

    private void onMenuHidden() {
        menuShowing = false;
        // 注意：这里不能 cancelPendingIconTap()——菜单开着点图标时，原生也会先 hide 菜单，
        // 把待执行的"补点图标打开 App"任务一起取消掉会让点击失效（真机回归实测）。
        if (state == IDLE || state == COMMITTED) return;
        cancel("native_hide");
    }

    // ------------------------------------------------------------------ 观测

    private void observe(MotionEvent ev, Object dragLayer) {
        final int action = ev.getActionMasked();
        final Cfg cfg = Cfg.get(ctx);

        if (ev.getPointerCount() > 1) {
            multiTouch = true;
            if (state == SLIDING || state == MENU_OPEN) cancel("multi_touch");
        }

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                pointerDown = true;
                multiTouch = false;
                downX = ev.getRawX();
                downY = ev.getRawY();
                lastX = downX;
                lastY = downY;
        liveX = Float.NaN; liveY = Float.NaN;
        headingToMenu = false;
        pendingCommit = false;
        restoreIconOnDrop = false;

        // 【新功能】菜单正开着、且这是长按抬手之后的一次全新按下（state==IDLE）：
        // 真机证实此时点击图标，原生会把 DOWN 交给菜单层吞掉、performClick/onClick
        // 都不触发（TOP 仍是桌面）。这里主动命中图标 View 并打开对应 App + 关菜单。
        // 判定排除：滑选进行中(SLIDING/COMMITTED)、多指、菜单刚弹出的长按手势本身。
        //
        // 关键：不能只信 menuShowing 布尔——滑动没选中项的路径（ABORT/cancel）会把它清成
        // false，但菜单物理上仍开着，此时点击图标同样应打开 App。用 menuPhysicallyVisible()
        // 兜底，让判定跟着菜单的真实可见状态走。
        boolean menuOpen = menuShowing || menuPhysicallyVisible();
        if (cfg.debug && pointerDown) {
            log("DOWN tapCheck menuOpen=" + menuOpen + " menuShowing=" + menuShowing
                    + " physVis=" + menuPhysicallyVisible() + " state=" + state);
        }
        if (cfg.slideEnabled && menuOpen && state == IDLE && !multiTouch
                && dragLayer instanceof android.view.ViewGroup) {
            // 【菜单项点击冲突】菜单正开着时点菜单项（如"添加小部件"），原生会处理菜单项点击，
            // 但同一次 DOWN 也会分发到 SuperDraglayer。若菜单项下面正好压着某个桌面图标，
            // 旧逻辑会把这次 DOWN 当成"点图标"，400ms 后再 performClick 一次 → 两个操作同时发生
            // （真机复现：长按米家 → 点"添加小部件" → 小组件选择器弹出，同时打开了底下的 Meta）。
            // 菜单项区域的 DOWN 一律不参与"点图标"判定，并清掉可能残留的待执行任务。
            if (isOnMenuItem(downX, downY)) {
                cancelPendingIconTap();
                if (cfg.debug) log("MENU_OPEN_ICON_TAP skipped: DOWN on menu item ("
                        + (int) downX + "," + (int) downY + ")");
                break;
            }
            View icon = findIconViewAt((ViewGroup) dragLayer, downX, downY);
            if (icon != null) {
                // 【只认宿主 A】菜单开着时点的是背景模糊的其它图标 B → 不打开 B。
                // 判定：目标图标矩形是否包含本次长按的锚点（onMenuShown 时记下的 A 位置）。
                // 背景 B 的矩形不含该锚点，直接放弃本次"补点打开"；菜单关闭仍由原生处理。
                if (!isHostIcon(icon)) {
                    cancelPendingIconTap();
                    if (cfg.debug) log("MENU_OPEN_ICON_TAP skipped: non-host icon ("
                            + (int) downX + "," + (int) downY + ") anchor=("
                            + (int) hostAnchorX + "," + (int) hostAnchorY + ")");
                    break;
                }
                long now = System.currentTimeMillis();
                // 时间去重：SuperDraglayer 会把同一手势的 DOWN 重复分发十几次。
                // 只有间隔 >100ms 才算"新的点击"，重复 DOWN 不重置取消标志、不重新 arm。
                boolean newGesture = now - lastIconTapDownAt > 100L;
                if (newGesture) {
                    main.removeCallbacks(iconTapRunnable);
                    iconTapCancelled = false;
                    iconTapTarget = icon;
                    iconTapSid = sessionId;
                    // 关键真机事实（Frida 探测）：菜单开着时，点击与长按图标的原生事件【完全相同】
                    // ——只有 DOWN，UP 被菜单层吞掉，无 performClick/startDrag。因此无法靠时长或 UP
                    // 区分二者。唯一可靠区分信号是【MOVE】：滑动一定会产生 MOVE，点击不会。
                    // 故用一个较短但足够 MOVE 到达的窗口——滑动在 MOVE 到达时取消，点击则窗口到即打开。
                    // 400ms：真人滑动的 MOVE 通常在 DOWN 后 50~300ms 内发出，能可靠取消；
                    // 点击手指早已抬起（虽 UP 被吞），400ms 后打开无感知延迟。
                    // （"菜单开着时长按图标不动"在原生 MIUI 本就无任何响应，无实际用途，无需保留。）
                    main.postDelayed(iconTapRunnable, 400L);
                    if (cfg.debug) log("MENU_OPEN_ICON_TAP armed (menuOpen=" + menuOpen + ")");
                }
                lastIconTapDownAt = now;
                break;
            }
        }
        break;

            case MotionEvent.ACTION_MOVE:
                lastX = ev.getRawX();
                lastY = ev.getRawY();
                // 有 MOVE 说明是滑动/拖动，不是"点击图标"：取消待执行的打开 App 判定。
                iconTapCancelled = true;
                main.removeCallbacks(iconTapRunnable);
                if (state == SLIDING) {
                    // 高亮优先用可靠的 drag 坐标，跟手更新
                    float hx = Float.isNaN(liveX) ? lastX : liveX;
                    float hy = Float.isNaN(liveY) ? lastY : liveY;
                    updateHighlight(cfg, hx, hy);
                }
                break;

            case MotionEvent.ACTION_UP:
                pointerDown = false;
                lastX = ev.getRawX();
                lastY = ev.getRawY();
                if (state == SLIDING && !multiTouch) {
                    // 命中判定优先用 drag 现场坐标（真人操作时 SuperDraglayer 缓存已过时）
                    float ux = Float.isNaN(liveX) ? lastX : liveX;
                    float uy = Float.isNaN(liveY) ? lastY : liveY;
                    if (cfg.debug) log("UP inSlide hitAt=" + (int) ux + "," + (int) uy
                            + " src=" + (Float.isNaN(liveX) ? "cached" : "drag"));
                    pendingCommit = false;   // 已由 ACTION_UP 提交，避免随后 drop 重复提交
                    commitOrAbort(cfg, ux, uy);
                } else if (state == COMMITTED) {
                    // 【防漂移修复】SuperDraglayer 会把同一个 ACTION_UP 重复分发多次
                    // （真机日志实证 ACTION_CANCEL 一次手势打了 5 发）。第二次 UP 到达时
                    // state 已是 COMMITTED，若走 else 把它重置成 IDLE，随后原生补发的
                    // flag=9 就会在 onCancelMenu 里被放行 → 执行拖动落位 → 图标漂移。
                    // 这里必须保住 COMMITTED + restoreIconOnDrop，直到 drop 真正消费。
                    if (cfg.debug) log("UP ignored (already COMMITTED, keep restore guard)");
                } else {
                    state = IDLE;
                    clearHighlight();
                    liveX = Float.NaN; liveY = Float.NaN;
                }
                multiTouch = false;
                break;

            case MotionEvent.ACTION_CANCEL:
                // 关键：不要在这里清 state。真机上 ACTION_CANCEL 往往先于
                // cancelShortcutMenu(flag=9) 到达，若此刻清空会话，随后的阈值取消
                // 就会因 state==IDLE 被放行，滑选失效。把决定权交给 flag=9 / UP。
                pointerDown = false;
                if (cfg.debug && state == MENU_OPEN) {
                    log("ACTION_CANCEL received, holding state for cancelShortcutMenu");
                }
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                pointerDown = false;
                cancel("pointer_down");
                break;

            default:
                break;
        }
    }

    // ------------------------------------------------------------------ 拦截判定

    /**
     * 判断当前手指是否"从图标朝菜单矩形移动"。两条件同时成立才算滑选意图：
     * <ol>
     *   <li>位移向量与"锚点→菜单中心"向量夹角 &lt; 可配阈值（默认 60°）；</li>
     *   <li>手指到菜单中心的距离比锚点明显变小（至少收敛 slop）。</li>
     * </ol>
     * 菜单矩形每帧从真实 View 取，天然适应弹出方向、动画与滚动。
     */
    private boolean isHeadingToMenu(Cfg cfg, float ax, float ay, float cx, float cy) {
        float density = Cfg.Screen.get(ctx).density;
        float dx = cx - ax;
        float dy = cy - ay;
        float dist = (float) Math.hypot(dx, dy);
        if (dist < cfg.slideMinTravelDp * density) return false;

        Rect bounds = menuBounds();
        if (bounds == null || bounds.isEmpty()) return false;

        float mx = bounds.centerX() - ax;
        float my = bounds.centerY() - ay;
        float mlen = (float) Math.hypot(mx, my);
        if (mlen < 1f) return false;

        float cos = (dx * mx + dy * my) / (dist * mlen);
        float minCos = (float) Math.cos(Math.toRadians(cfg.slideAngleDeg));
        if (cos < minCos) return false;

        // 【移除"滑过头"翻转】真机日志实证：用户滑向菜单里较远的 item 时会越过菜单
        // 中心，原先 dCur > mlen + slop*3 会在那一帧翻回 false，导致会话被 cancel、
        // 图标被挪走。夹角条件本身与菜单在上/下、左/右无关；位移下限已在上面用 density
        // 判过。方向一旦对上就锁定（见 onThresholdSource 的 SLIDING 分支），这里不再设上限。
        return true;
    }

    /**
     * handleMoveEvent 的统一拦截（解决「其他图标被影响位置」的核心）。
     *
     * <p>反编译原生 handleMoveEvent：在 flag=9 未触发的早期帧，它会用传入的
     * (x,y) 调 findDropTarget → DropTarget.onDragOver，Workspace 据此让手指下方
     * 的其它图标让位重排。之前的版本只冻结了本图标浮层（DragObject.move），
     * 却让这条让位链路照常跑，所以用户看到「本图标不动、别的图标乱动」。</p>
     *
     * <p>这里在滑选会话里把喂给原生的坐标替换成原生按下点 mMotionDownX/Y：
     * findDropTarget 因此永远命中图标原格、onDragOver 让位的也是原格自身，
     * 其它图标完全不受影响；同时 mDragObject.move(x,y) 也用原格坐标，本图标
     * 不跟手。真实手指坐标仍记录在 liveX/liveY 供方向判定与跟手高亮使用。
     * 朝非菜单拖动时 cancel 后放行真实坐标，原生拖动排序 100% 保留。</p>
     */
    private Object onHandleMove(XposedInterface.Chain chain, float x, float y) {
        final Cfg cfg = Cfg.get(ctx);
        // 真实坐标永远记录（方向判定、命中、高亮都靠它）。
        liveX = x;
        liveY = y;

        if (!cfg.slideEnabled || multiTouch) return Hooks.PROCEED;
        // 【防"松开后图标动一下"修复】提交收尾期：observe(UP) 会先把 state 改成
        // COMMITTED 并设 guardUntil，紧接着 DragController.onTouchEvent(UP) 里的
        // handleMoveEvent 携带 UP 的真实落点 (171,1823) 再次进入这里。若此时放行，
        // 原生会用真实落点 findDropTarget → shake view 让位（图标动一下 / 换位置），
        // 尽管随后 drop 会归位，让位动画已经触发。必须在整个 restore 窗口内继续
        // 钉住坐标，让 UP 帧的 findDropTarget 也命中原格，从源头消除让位。
        if (restoreIconOnDrop && System.currentTimeMillis() < restoreIconUntil) {
            if (cfg.debug) log("HANDLE_MOVE PIN-ON-UP (restore window, live=("
                    + (int) x + "," + (int) y + "))");
            return pinCoords(chain);
        }
        if (System.currentTimeMillis() < guardUntil) return Hooks.PROCEED;
        // 未进入会话：完全放行原生。
        if (state != MENU_OPEN && state != SLIDING) return Hooks.PROCEED;

        float travel = (float) Math.hypot(x - downX, y - downY);
        float minTravel = cfg.slideMinTravelDp * Cfg.Screen.get(ctx).density;

        if (state == MENU_OPEN) {
            // 位移还太小（含长按触发时的 d=0 伪 move）：方向不可靠，
            // 但这一阶段原生已开始 findDropTarget/onDragOver → 必须钉住坐标
            // 保住菜单且不让任何图标让位，否则「其他图标」在起手几帧就被挪动。
            if (travel < minTravel) {
                return pinCoords(chain);
            }
            if (!isHeadingToMenu(cfg, downX, downY, x, y)) {
                // 明确朝非菜单（如向下拖到别的格子）→ 放行真实坐标，原生拖动排序。
                if (cfg.debug) {
                    log("HANDLE_MOVE pass-through (not heading; live=("
                            + (int) x + "," + (int) y + ") d=" + (int) travel + "px)");
                }
                cancel("drag_not_heading");
                return Hooks.PROCEED;
            }
            state = SLIDING;
            pendingCommit = true;
            if (cfg.debug) {
                log("HANDLE_MOVE PIN (session " + sessionId + ", live=("
                        + (int) x + "," + (int) y + ") d=" + (int) travel + "px)");
            }
            // 【去掉多余震动】刚判定进入滑选时不打 tick：此刻手指还没滑到任何菜单项，
            // 这记提前的反馈对用户是多余的。真正的反馈只在滑到菜单项时给（updateHighlight）。
        }
        // SLIDING：钉住坐标 + 按真实坐标跟手高亮。
        updateHighlight(cfg, x, y);
        return pinCoords(chain);
    }

    /** 把 handleMoveEvent 的 (int x, int y, MotionEvent) 前两参替换为原生按下点。 */
    private Object pinCoords(XposedInterface.Chain chain) {
        try {
            List<Object> args = chain.getArgs();
            if (args.size() < 3) return Hooks.PROCEED;
            Object dc = chain.getThisObject();
            Float fx = readIntField(dc, F_MOTION_DOWN_X);
            Float fy = readIntField(dc, F_MOTION_DOWN_Y);
            int px = fx != null ? Math.round(fx.floatValue()) : Math.round(downX);
            int py = fy != null ? Math.round(fy.floatValue()) : Math.round(downY);
            Object ev = args.get(2);
            return chain.proceed(new Object[]{px, py, ev});
        } catch (Throwable t) {
            log("pinCoords failed: " + t);
            return Hooks.PROCEED;
        }
    }

    /**
     * DragController.handleMoveEvent 拦截（历史实现，现在只观察、不由 hook 调用）。
     * 原生在这里 mDragObject.move(x,y) 让图标跟随并最终落位。滑选会话中短路整个方法：
     * 图标不跟随手指 → UP 时落回原格；同时高亮由我们按 live 坐标更新。
     */
    private Object onDragMove(Object dragController, float x, float y) {
        final Cfg cfg = Cfg.get(ctx);
        if (!cfg.slideEnabled) return Hooks.PROCEED;
        if (System.currentTimeMillis() < guardUntil) return Hooks.PROCEED;
        if (state == COMMITTED) return Hooks.PROCEED;
        if ((state != MENU_OPEN && state != SLIDING) || multiTouch) return Hooks.PROCEED;

        // 位移还太小（含长按刚触发时的 d=0 伪 move）：暂不判定，放行原生。
        float moveDist = (float) Math.hypot(x - downX, y - downY);
        if (moveDist < cfg.slideMinTravelDp * Cfg.Screen.get(ctx).density) {
            return Hooks.PROCEED;
        }

        if (state == MENU_OPEN) {
            if (!isHeadingToMenu(cfg, downX, downY, x, y)) {
                // 明确朝非菜单（如向下拖到别的格子）→ 放行原生拖动
                if (cfg.debug) {
                    log("DRAG_MOVE pass-through (not heading; live=("
                            + (int) x + "," + (int) y + ") d="
                            + (int) Math.hypot(x - downX, y - downY) + "px)");
                }
                cancel("drag_not_heading");
                return Hooks.PROCEED;
            }
            state = SLIDING;
            if (cfg.debug) {
                log("DRAG_MOVE SWALLOW start (session " + sessionId + ", live=("
                        + (int) x + "," + (int) y + ") d="
                        + (int) Math.hypot(x - downX, y - downY) + "px)");
            }
            // 【去掉多余震动】进入滑选不再 tick（见 onHandleMove 说明）。
        }
        // SLIDING：持续高亮，且短路 move 让图标不跟随
        updateHighlight(cfg, x, y);
        return null;   // void 方法短路 = mDragObject.move 不执行 = 图标不挪动
    }

    /**
     * 图标浮层是否应冻结在原地（不跟随手指）。在 DragObject.move 里调用。
     * 注意此时 liveX/liveY 已由更早执行的 handleMoveEvent before hook 更新为当前帧真实坐标。
     */
    private boolean shouldFreezeIcon() {
        final Cfg cfg = Cfg.get(ctx);
        if (!cfg.slideEnabled || multiTouch) return false;
        if (System.currentTimeMillis() < guardUntil) return false;
        // 已进入滑选会话 → 冻结
        if (state == SLIDING || state == COMMITTED || pendingCommit) return true;
        if (state != MENU_OPEN) return false;
        // 菜单弹出但尚未判方向：位移足够且朝菜单则预判冻结，避免第一帧图标先窜出去。
        float x = liveX, y = liveY;
        if (Float.isNaN(x) || Float.isNaN(y)) return false;
        float travel = (float) Math.hypot(x - downX, y - downY);
        if (travel < cfg.slideMinTravelDp * Cfg.Screen.get(ctx).density) return false;
        return isHeadingToMenu(cfg, downX, downY, x, y);
    }

    /**
     * flag=9 源头拦截（真机实证的可靠坐标点）。这里拿到的 x/y 来自 IDragObject，
     * 是跟手的真实屏幕坐标，替代过时的 SuperDraglayer 缓存。
     * 朝菜单 → 短路（保住菜单、阻止图标进拖动）；否则放行原生拖动排序。
     */
    private Object onThresholdSource(Object launcher, Float x, Float y) {
        final Cfg cfg = Cfg.get(ctx);
        if (!cfg.slideEnabled) return Hooks.PROCEED;
        // 【防漂移保险 A'】源头同样要在 restore 窗口内吞掉 flag=9，
        // 与 onCancelMenu 保持一致；否则 state 被重复事件改成 IDLE 时源头就放行了。
        if (restoreIconOnDrop && System.currentTimeMillis() < restoreIconUntil) {
            return null;
        }
        if (System.currentTimeMillis() < guardUntil) return null; // 提交收尾期，吞掉
        if (state == COMMITTED) return null;

        // 没有有效坐标或没有活动会话 → 放行原生
        if (x == null || y == null) return Hooks.PROCEED;
        float fx = x.floatValue(), fy = y.floatValue();
        if (Float.isNaN(fx) || Float.isNaN(fy)) {
            // handleMoveEvent 尚未观察到 MOVE，回退到缓存坐标
            fx = lastX; fy = lastY;
            if (Float.isNaN(liveX)) liveX = fx;
            if (Float.isNaN(liveY)) liveY = fy;
        }
        if ((state != MENU_OPEN && state != SLIDING) || multiTouch) {
            return Hooks.PROCEED;
        }

        float travel = (float) Math.hypot(fx - downX, fy - downY);

        // 【关键修复 1】位移还太小时（真机实测第一帧常只有 ~24px）不判方向：
        // 此时方向向量近似噪声，夹角判定会误判 "not heading" 并把整个会话 cancel，
        // 之后手指真正朝菜单滑也没用了（用户操作二三就是死在这里）。
        // 正确做法：吞掉 flag=9 保住菜单，state 保持 MENU_OPEN，等位移足够再判。
        float minTravel = cfg.slideMinTravelDp * Cfg.Screen.get(ctx).density;
        if (state == MENU_OPEN && travel < minTravel) {
            if (cfg.debug) log("THRESHOLD_SRC too-small d=" + (int) travel
                    + "px -> hold menu (wait for more movement)");
            return null;   // 保住菜单，继续观察后续帧
        }

        // 用可靠坐标现场判定
        boolean head = isHeadingToMenu(cfg, downX, downY, fx, fy);
        if (!head) {
            // 【关键修复 2】已经进入 SLIDING 就不因后续帧的方向抖动而 cancel：
            // 用户滑向菜单深处时，菜单可能跟随移动，某一帧夹角可能瞬时超阈值；
            // 一旦确认滑选意图就锁定，避免像操作一那样最后一段翻转放行。
            if (state == SLIDING) {
                pendingCommit = true;
                if (cfg.debug) log("THRESHOLD_SRC keep SLIDING (latched, d=" + (int) travel + "px)");
                updateHighlight(cfg, fx, fy);
                return null;
            }
            if (cfg.debug) {
                log("THRESHOLD_SRC pass-through (not heading; live=("
                        + (int) fx + "," + (int) fy + ") d="
                        + (int) travel + "px)");
            }
            cancel("src_not_heading");
            return Hooks.PROCEED;   // 原生关菜单 + 拖动排序
        }

        state = SLIDING;
        pendingCommit = true;
        if (cfg.debug) {
            log("THRESHOLD_SRC SWALLOW -> menu kept (session " + sessionId + ", live=("
                    + (int) fx + "," + (int) fy + ") d="
                    + (int) Math.hypot(fx - downX, fy - downY) + "px)");
        }
        updateHighlight(cfg, fx, fy);
        // 【去掉多余震动】进入滑选不再 tick（见 onHandleMove 说明）。
        return null;   // void 方法短路 = 不发 flag=9 = 菜单保住、图标不进拖动
    }

    /**
     * 取消汇聚点（真机实证所有关菜单都走这里）。flag=9=滑动超阈值，flag=7=点外部。
     * 仅在 flag==9 且本次会话由长按打开、且手指朝菜单靠近时短路，保住菜单、阻止图标被拖走；
     * 其余（含点外部、多指、非滑选）一律放行原生。
     */
    private Object onCancelMenu(int flag, Object launcher) {
        final Cfg cfg = Cfg.get(ctx);
        final long now = System.currentTimeMillis();
        if (!cfg.slideEnabled) return Hooks.PROCEED;
        if (now < guardUntil) return null;   // 自己收尾时的回调，吞掉

        // 只有 drag_over_threshold 才可能是滑选；其它取消原因立刻放行。
        if (flag != FLAG_DRAG_OVER_THRESHOLD) {
            if (state != IDLE && cfg.debug) log("CANCEL_MENU flag=" + flag + " -> pass-through");
            cancel("cancel_menu_flag_" + flag);
            return Hooks.PROCEED;
        }
        // 【防漂移保险 A】提交后的收尾阶段：只要 restoreIconOnDrop 窗口还开着，
        // 无论 state 被谁改写成 IDLE（重复 UP / ACTION_CANCEL 都可能），都无条件
        // 吞掉补发的 flag=9。放行它=native 执行拖动落位=图标漂移。这条不依赖 state。
        if (restoreIconOnDrop && System.currentTimeMillis() < restoreIconUntil) {
            if (cfg.debug) log("THRESHOLD flag=9 in restore window -> swallow (state=" + state + ")");
            return null;
        }
        // 提交后的收尾阶段：UP 后原生还会补发一次 flag=9，若放行会执行拖动落位，
        // 把图标挪到落点。必须吞掉，直到 guardUntil 结束。
        if (state == COMMITTED) {
            if (cfg.debug) log("THRESHOLD flag=9 after commit -> swallow (prevent icon drop)");
            return null;
        }
        // 会话必须由本次长按打开，且没有多指干扰。
        if ((state != MENU_OPEN && state != SLIDING) || multiTouch) {
            if (cfg.debug) log("THRESHOLD flag=9 but no active slide session -> pass-through");
            return Hooks.PROCEED;
        }

        // 现场判定：优先用 IDragObject 的可靠坐标 liveX/liveY；没有才回退到缓存。
        float cx = Float.isNaN(liveX) ? lastX : liveX;
        float cy = Float.isNaN(liveY) ? lastY : liveY;
        boolean head = isHeadingToMenu(cfg, downX, downY, cx, cy);
        if (!head) {
            // 与源头 hook 一致：已进入 SLIDING 就不因后续帧抖动撤销会话。
            if (state == SLIDING || pendingCommit) {
                if (cfg.debug) log("THRESHOLD keep SLIDING (latched)");
                return null;
            }
            if (cfg.debug) {
                log("THRESHOLD pass-through (not heading to menu; d="
                        + (int) Math.hypot(cx - downX, cy - downY) + "px"
                        + " src=" + (Float.isNaN(liveX) ? "cached" : "drag") + ")");
            }
            cancel("not_heading");
            return Hooks.PROCEED;   // 原生关菜单 + 拖动排序
        }

        state = SLIDING;
        pendingCommit = true;
        if (cfg.debug) {
            log("THRESHOLD SWALLOW -> menu kept (session " + sessionId
                    + ", d=" + (int) Math.hypot(lastX - downX, lastY - downY) + "px)");
        }
        updateHighlight(cfg, lastX, lastY);
        // 【去掉多余震动】进入滑选不再 tick（见 onHandleMove 说明）。
        return null;                // void 方法：不调用原方法 = 菜单保住、图标不进拖动
    }

    // ------------------------------------------------------------------ 命中 / 高亮 / 提交

    /**
     * 从 root 递归找到 (x,y) 处的应用图标 View。
     * 真机探测证实桌面图标是 {@code com.miui.home.launcher.ShortcutIcon}，
     * 其 tag 是 {@code ShortcutInfo}。用「命中矩形 + 图标特征」双重判定，
     * 避免误命中菜单项、widget、根目录的其它 View。
     */
    private View findIconViewAt(ViewGroup root, float x, float y) {
        try {
            Rect tmp = new Rect();
            if (root.getGlobalVisibleRect(tmp)
                    && x >= tmp.left && x <= tmp.right && y >= tmp.top && y <= tmp.bottom) {
                String cls = root.getClass().getName();
                Object tag = root.getTag();
                String tagCls = tag == null ? "" : tag.getClass().getName();
                if (cls.contains("ShortcutIcon") || tagCls.contains("ShortcutInfo")
                        || "android.widget.TextView".equals(cls) && tagCls.contains("ShortcutInfo")) {
                    return root;
                }
            }
            for (int i = 0; i < root.getChildCount(); i++) {
                android.view.View child = root.getChildAt(i);
                if (child instanceof ViewGroup) {
                    View hit = findIconViewAt((ViewGroup) child, x, y);
                    if (hit != null) return hit;
                } else {
                    child.getGlobalVisibleRect(tmp);
                    if (x >= tmp.left && x <= tmp.right && y >= tmp.top && y <= tmp.bottom) {
                        String cls = child.getClass().getName();
                        Object tag = child.getTag();
                        String tagCls = tag == null ? "" : tag.getClass().getName();
                        if (cls.contains("ShortcutIcon") || tagCls.contains("ShortcutInfo")) {
                            return child;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("findIconViewAt error: " + t);
        }
        return null;
    }

    /**
     * icon 是否就是弹出当前菜单的宿主图标 A：其屏幕矩形包含长按锚点即为宿主。
     * 菜单开着时点背景图标 B，B 的矩形不含 A 的锚点 → 返回 false，不打开 B。
     * 锚点无效（异常情况没记到）时保守返回 true，避免出现"点 A 却打不开"的回归。
     */
    private boolean isHostIcon(View icon) {
        if (icon == null) return false;
        if (Float.isNaN(hostAnchorX) || Float.isNaN(hostAnchorY)) return true;
        try {
            Rect r = new Rect();
            if (!icon.getGlobalVisibleRect(r) || r.width() <= 0 || r.height() <= 0) return true;
            // 向外扩 8dp 容差，容忍菜单弹出动画带来的小幅位移；远小于图标间距，不会误判相邻图标。
            int pad = (int) (8f * Cfg.Screen.get(ctx).density);
            r.inset(-pad, -pad);
            boolean inside = r.contains((int) hostAnchorX, (int) hostAnchorY);
            if (Cfg.get(ctx).debug) {
                log("isHostIcon " + icon.getClass().getSimpleName() + " rect=" + r.toShortString()
                        + " anchor=(" + (int) hostAnchorX + "," + (int) hostAnchorY
                        + ") -> " + inside);
            }
            return inside;
        } catch (Throwable t) {
            return true;   // 反射/几何异常时宁可放行，不破坏原有"点 A 打开"功能
        }
    }

    /**
     * 菜单是否【物理可见】——直接看菜单 item 此刻是否还在屏幕上。
     * 与 menuShowing 布尔互补：后者会被滑动 abort/cancel 路径清掉，而菜单可能仍开着。
     * 只要还有可见且有尺寸的 item，就认为菜单在显示。
     */
    private boolean menuPhysicallyVisible() {
        try {
            List<View> items = collectItems();
            if (items.isEmpty()) return false;
            Rect tmp = new Rect();
            for (View v : items) {
                tmp.setEmpty();
                if (v.getGlobalVisibleRect(tmp) && tmp.width() > 0 && tmp.height() > 0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 五个菜单容器里所有可见的直接子 View 视为 item 候选。 */
    private List<View> collectItems() {
        List<View> out = new ArrayList<View>();
        if (shortcutMenu == null) return out;
        for (Field f : menuGroupFields) {
            Object group = readField(shortcutMenu, f);
            if (!(group instanceof ViewGroup)) continue;
            ViewGroup vg = (ViewGroup) group;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View v = vg.getChildAt(i);
                if (v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && v.getHeight() > 0) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    /** 菜单整体矩形 = 所有可见 item 矩形的并集。 */
    private Rect menuBounds() {
        Rect r = new Rect();
        Rect tmp = new Rect();
        boolean first = true;
        for (View v : collectItems()) {
            tmp.setEmpty();
            if (!v.getGlobalVisibleRect(tmp)) continue;
            if (first) {
                r.set(tmp);
                first = false;
            } else {
                r.union(tmp);
            }
        }
        return first ? null : r;
    }

    /**
     * 落点是否落在当前菜单的任一可见菜单项上。用于把"点菜单项"和"点菜单外的桌面图标"分开：
     * 前者由原生菜单自己处理，绝不能触发我们的"补点图标打开 App"。
     */
    private boolean isOnMenuItem(float x, float y) {
        Rect tmp = new Rect();
        for (View v : collectItems()) {
            tmp.setEmpty();
            if (!v.getGlobalVisibleRect(tmp)) continue;
            if (tmp.contains((int) x, (int) y)) return true;
        }
        return false;
    }

    private View hitAt(Cfg cfg, float x, float y) {
        float slopPx = cfg.slideSlopDp * Cfg.Screen.get(ctx).density;
        Rect tmp = new Rect();
        for (View v : collectItems()) {
            tmp.setEmpty();
            if (!v.getGlobalVisibleRect(tmp)) continue;
            tmp.inset((int) -slopPx, (int) -slopPx);
            if (tmp.contains((int) x, (int) y)) return v;
        }
        return null;
    }

    private void updateHighlight(Cfg cfg, float x, float y) {
        View hit = hitAt(cfg, x, y);
        if (hit == highlighted) return;
        // 恢复上一个：pressed + 原 foreground
        if (highlighted != null) {
            try {
                highlighted.setPressed(false);
                highlighted.setForeground(highlightedOldForeground);
            } catch (Throwable ignored) {
            }
        }
        highlighted = hit;
        highlightedOldForeground = null;
        if (highlighted != null) {
            try {
                highlighted.setPressed(true);                       // 系统 pressed 态
                highlightedOldForeground = highlighted.getForeground();
                highlighted.setForeground(new ColorDrawable(HIGHLIGHT_COLOR)); // 可见填充色
            } catch (Throwable ignored) {
            }
            Haptics.get(ctx).rebound();                            // 「设置」边界反弹同款手感
        }
    }

    private void clearHighlight() {
        if (highlighted != null) {
            try {
                highlighted.setPressed(false);
                highlighted.setForeground(highlightedOldForeground);
            } catch (Throwable ignored) {
            }
            highlighted = null;
            highlightedOldForeground = null;
        }
    }

    /** 抬手：命中 item 则 performClick 提交并关菜单；否则仅安全关菜单。 */
    private void commitOrAbort(final Cfg cfg, final float x, final float y) {
        // 幂等：ACTION_UP 与 drop 都可能触发提交，只处理第一次。
        if (state == COMMITTED) return;
        final long sid = sessionId;
        state = COMMITTED;
        pendingCommit = false;
        // 关键：立刻标记"下次 drop 要把图标放回原格"。drop 在 performClick 打开新
        // Activity、桌面状态重建后才发生（晚于 state 归 IDLE），所以不能用 state 判断，
        // 必须用独立标志 + 超时窗口。
        restoreIconOnDrop = true;
        restoreIconUntil = System.currentTimeMillis() + 1500L;
        // 立刻设 guard：UP 之后原生会补发 flag=9，必须在它到达前就进入抑制窗口，
        // 否则被放行会导致图标落到滑选终点位置（iOS 式滑选不该移动图标）。
        guardUntil = System.currentTimeMillis() + 600L;

        // 延迟一帧：让 UP 先完成原生分发，避免在分发链中改菜单状态。
        // target 在延迟任务里【重新计算】——UP 当场 collectItems 可能因动画/状态切换
        // 取不到 item，而此时菜单已被保住，矩形是稳定的。
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (sid != sessionId) return;
                    View target = hitAt(cfg, x, y);
                    if (cfg.debug) {
                        StringBuilder sb = new StringBuilder();
                        Rect tmp = new Rect();
                        for (View v : collectItems()) {
                            tmp.setEmpty();
                            v.getGlobalVisibleRect(tmp);
                            sb.append('[').append(tmp.left).append(',').append(tmp.top)
                                    .append('-').append(tmp.right).append(',').append(tmp.bottom)
                                    .append(']');
                        }
                        log("COMMIT hitAt(" + (int) x + "," + (int) y + ") -> "
                                + (target == null ? "null" : target.getClass().getSimpleName())
                                + " rects=" + sb);
                    }
                    clearHighlight();
                    if (target != null) {
                        target.performClick();            // 等价原生点击（真机已验证）
                        // 【去掉多余震动】放开抬手时不再补震动：滑到该项时 updateHighlight()
                        // 已经给过一记边界反弹，这里再震一次是用户不想要的"松手震动"。
                        if (cfg.debug) {
                            log("COMMIT " + target.getClass().getName());
                        }
                    } else if (cfg.debug) {
                        log("ABORT no item under finger");
                    }
                    closeMenu();                          // 短路了原生取消，需自己收尾
                    menuShowing = false;
                    guardUntil = System.currentTimeMillis() + 350L;
                    state = IDLE;
                    liveX = Float.NaN; liveY = Float.NaN;
                } catch (Throwable t) {
                    log("commit failed: " + t);
                    state = IDLE;
                    liveX = Float.NaN; liveY = Float.NaN;
                }
            }
        });
    }

    /** 取消尚未执行的"点击图标打开 App"判定（新菜单弹出时调用）。 */
    private void cancelPendingIconTap() {
        iconTapCancelled = true;
        try { main.removeCallbacks(iconTapRunnable); } catch (Throwable ignored) {}
    }

    private void cancel(String why) {
        if (state == IDLE) return;
        // 【防漂移保险 C】已提交（COMMITTED）时绝不清 restoreIconOnDrop：
        // performClick 打开新页面后原生会发 flag=7 等取消，若此刻 cancel 清掉标志，
        // 待补发的 flag=9 就没人吞、图标落到滑选终点。只有"非滑选"才需要清。
        boolean wasCommitted = (state == COMMITTED);
        state = IDLE;
        menuShowing = false;
        headingToMenu = false;
        clearHighlight();
        liveX = Float.NaN; liveY = Float.NaN;
        if (!wasCommitted) restoreIconOnDrop = false;
        pendingCommit = false;      // 撤销滑选意图：抬手时按原生拖动处理
        if (Cfg.get(ctx).debug) log("CANCEL " + why + (wasCommitted ? " (keep restore)" : ""));
    }

    // ------------------------------------------------------------------ 反射工具

    private void cacheMenuGroups(Class<?> menuCls) {
        menuGroupFields.clear();
        String[] names = {"mAppShortcutMenu", "mSystemShortcutMenu", "mFolderShortcutMenu",
                "mAddWidgetShortcutMenu", "mWidgetShortcutMenu"};
        for (String name : names) {
            Field f = findField(menuCls, name);
            if (f != null) menuGroupFields.add(f);
        }
        if (menuGroupFields.isEmpty()) {
            // 兜底：类型名含 ShortcutMenu 且是 View 的字段
            for (Class<?> c = menuCls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (View.class.isAssignableFrom(f.getType())
                            && f.getType().getName().contains("ShortcutMenu")) {
                        f.setAccessible(true);
                        menuGroupFields.add(f);
                    }
                }
            }
        }
    }

    private void prepareReflection() {
        try {
            Class<?> app = hooks.findClass(CLS_APP, cl);
            if (app != null) getLauncher = app.getMethod("getLauncher");
        } catch (Throwable t) {
            log("getLauncher reflect failed: " + t);
        }
        try {
            Class<?> st = hooks.findClass(CLS_LAUNCHER_STATE, cl);
            if (st != null) normalState = st.getField("NORMAL").get(null);
        } catch (Throwable t) {
            log("LauncherState.NORMAL reflect failed: " + t);
        }
    }

    /** 关菜单：复用原生取消回调的 goToState(LauncherState.NORMAL, false)。 */
    private void closeMenu() {
        try {
            if (getLauncher == null) return;
            Object launcher = module.getInvoker(getLauncher).invoke(null);
            if (launcher == null) return;
            if (getStateManager == null) {
                for (Class<?> c = launcher.getClass(); c != null && c != Object.class;
                     c = c.getSuperclass()) {
                    try {
                        getStateManager = c.getMethod("getStateManager");
                        break;
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
            if (getStateManager == null) return;
            Object sm = module.getInvoker(getStateManager).invoke(launcher);
            if (sm == null) return;
            if (goToState == null) {
                for (Method m : sm.getClass().getMethods()) {
                    if (m.getName().equals("goToState") && m.getParameterCount() == 2) {
                        goToState = m;
                        break;
                    }
                }
            }
            if (goToState == null || normalState == null) return;
            module.getInvoker(goToState).invoke(sm, normalState, false);
            log("closed menu via goToState(NORMAL,false)");
        } catch (Throwable t) {
            log("closeMenu failed: " + t);
        }
    }

    /**
     * 精确取方法：cancelShortcutMenu 的真实签名是 (int, CancelShortcutMenuReason)。
     * 用 getDeclaredMethod(name, 参数类型) 只解析该签名，绕开 getDeclaredMethods()
     * 因类里其它方法引用缺失类（NewHomeView）而整体抛错的问题。
     */
    private Method findMethodExact(Class<?> cls, String name, int paramCount) {
        Class<?> reason = hooks.findClass("com.miui.home.state.api.CancelShortcutMenuReason", cl);
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                if (reason != null && paramCount == 2) {
                    Method m = c.getDeclaredMethod(name, int.class, reason);
                    if (m != null) return m;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 兜底：扫描 Dex 里实现了 ShortcutMenuDragListener 且带 onDragOverThreshold 方法的类。 */
    private Class<?> findListenerImplByScan(ClassLoader cl) {
        try {
            Class<?> iface = hooks.findClass(CLS_DRAG_LISTENER, cl);
            if (iface == null) return null;
            // Hook 框架通常提供扫描能力；这里用 ClassLoader 已加载类 + 常见实现类清单兜底。
            String[] candidates = {
                    "com.miui.home.launcher.touch.ItemLongClickListener$2",
                    "com.miui.home.launcher.touch.ItemLongClickListener$3",
                    "com.miui.home.launcher.touch.ItemLongClickListener$1"
            };
            for (String name : candidates) {
                Class<?> c = hooks.findClass(name, cl);
                if (c != null && iface.isAssignableFrom(c)) return c;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean slideEnabled() {
        try { return Cfg.get(ctx).slideEnabled; } catch (Throwable t) { return false; }
    }

    /** 读对象 int 字段（如 DragController.mMotionDownX/Y），失败返回 null。 */
    private static Float readIntField(Object target, String name) {
        if (target == null) return null;
        try {
            for (Class<?> c = target.getClass(); c != null && c != Object.class;
                 c = c.getSuperclass()) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (v instanceof Number) return ((Number) v).floatValue();
                } catch (NoSuchFieldException ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 反射调用目标对象无参 int 方法（如 IDragObject.getX/getY），失败返回 null。 */
    private static Float callInt(Object target, String methodName) {
        if (target == null) return null;
        try {
            for (Class<?> c = target.getClass(); c != null && c != Object.class;
                 c = c.getSuperclass()) {
                try {
                    Method m = c.getDeclaredMethod(methodName);
                    m.setAccessible(true);
                    Object r = m.invoke(target);
                    if (r instanceof Number) return ((Number) r).floatValue();
                    return null;
                } catch (NoSuchMethodException ignored) {
                }
            }
            // 接口默认方法可能在接口上
            for (Class<?> itf : target.getClass().getInterfaces()) {
                try {
                    Method m = itf.getMethod(methodName);
                    m.setAccessible(true);
                    Object r = m.invoke(target);
                    if (r instanceof Number) return ((Number) r).floatValue();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return null;
        }
        return null;
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static Object readField(Object target, String fieldName) {
        return readField(target, findField(target == null ? null : target.getClass(), fieldName));
    }

    private static Object readField(Object target, Field f) {
        if (target == null || f == null) return null;
        try {
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String s) {
        HyperHand.log("[Slide] " + s);
    }
}
