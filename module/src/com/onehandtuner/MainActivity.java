package com.onehandtuner;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 参数调节界面。数值通过广播交给 SystemUI 进程写入 Settings.Global，
 * 因此不需要 root，也不需要重新编译模块。
 */
public class MainActivity extends Activity {

    private Switch mEnabled;
    private Switch mMonitor;
    private Switch mDebug;
    private Switch mShadeGuard;
    private Switch mClaimZone;
    private EditText mZone;
    private EditText mMinDy;
    private EditText mVelocity;
    private EditText mSlope;
    private EditText mWidth;
    private EditText mCooldown;
    private EditText mLauncherHot;
    private TextView mStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        loadCurrent();
    }

    private View buildUi() {
        final int pad = dp(18);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#FFF7F7F7"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("单手模式增强", 22, true);
        root.addView(title);
        TextView desc = text("在原生单手模式（底部胶囊条下滑）之外，额外提供一条「旁路」触发通道：\n"
                + "底部区域内的下滑手势同样会调用系统的 startOneHanded()，\n"
                + "缩小动画、缩小布局、触摸映射、退出方式全部保持原生。", 14, false);
        desc.setPadding(0, dp(8), 0, dp(14));
        root.addView(desc);

        mEnabled = switchRow(root, "启用模块", true);
        mMonitor = switchRow(root, "启用扩展触发（推荐）", true);
        mDebug = switchRow(root, "输出调试日志（logcat 标签 OneHandTuner）", true);
        mShadeGuard = switchRow(root, "与下拉通知/控制中心互斥（推荐）", true);
        mClaimZone = switchRow(root, "底部区域优先单手模式（桌面下拉手势让位）", true);

        mZone = editRow(root, "触发区高度（dp，从屏幕底部算起）", "56");
        mMinDy = editRow(root, "最小下滑距离（dp）", "4");
        mVelocity = editRow(root, "触发最小速度（px/s，0=关闭速度判定）", "500");
        mSlope = editRow(root, "最大横向偏移比 |dx|/dy", "1.2");
        mWidth = editRow(root, "有效宽度占比 0.2~1.0", "1.0");
        mCooldown = editRow(root, "触发冷却（毫秒）", "500");
        mLauncherHot = editRow(root, "原生胶囊条窗口高度（dp，0=不改动）", "0");

        Button save = new Button(this);
        save.setText("保存并立即生效");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        saveLp.topMargin = dp(16);
        root.addView(save, saveLp);

        Button reset = new Button(this);
        reset.setText("恢复默认值");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mEnabled.setChecked(true);
                mMonitor.setChecked(true);
                mDebug.setChecked(true);
                mShadeGuard.setChecked(true);
                mClaimZone.setChecked(true);
                mZone.setText("56");
                mMinDy.setText("4");
                mVelocity.setText("500");
                mSlope.setText("1.2");
                mWidth.setText("1.0");
                mCooldown.setText("500");
                mLauncherHot.setText("0");
                save();
            }
        });
        root.addView(reset);

        mStatus = text("", 13, false);
        mStatus.setPadding(0, dp(16), 0, dp(8));
        root.addView(mStatus);

        TextView tips = text("说明：\n"
                + "• 需要先在 LSPosed/Vector 里启用本模块，作用域勾选「系统界面(System UI)」；\n"
                + "  若想用「原生胶囊条加高」方案，再勾选「小米桌面(com.miui.home)」。\n"
                + "• 改动通过系统界面进程写入 Settings.Global，保存后立即生效，无需重启。\n"
                + "• 想「快甩才触发、慢滑不触发」：调大「触发最小速度」；想更灵敏：调小到 0 即取消速度判定。\n"
                + "• 想完全恢复原状：把「启用模块」关掉即可（也可在 LSPosed 里停用模块）。", 12, false);
        root.addView(tips);

        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                root.setPadding(pad, pad + insets.getSystemWindowInsetTop(),
                        pad, pad + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        return scroll;
    }

    private void loadCurrent() {
        try {
            android.content.ContentResolver cr = getContentResolver();
            mEnabled.setChecked(Cfg.intOf(cr, Cfg.K_ENABLED, 1) != 0);
            mMonitor.setChecked(Cfg.intOf(cr, Cfg.K_MONITOR_ENABLED, 1) != 0);
            mDebug.setChecked(Cfg.intOf(cr, Cfg.K_DEBUG, 1) != 0);
            mShadeGuard.setChecked(Cfg.intOf(cr, Cfg.K_SHADE_GUARD, 1) != 0);
            mClaimZone.setChecked(Cfg.intOf(cr, Cfg.K_CLAIM_ZONE, 1) != 0);
            mZone.setText(String.valueOf(Cfg.floatOf(cr, Cfg.K_ZONE_DP, Cfg.DEF_ZONE_DP)));
            mMinDy.setText(String.valueOf(Cfg.floatOf(cr, Cfg.K_MIN_DY_DP, Cfg.DEF_MIN_DY_DP)));
            mVelocity.setText(String.valueOf(Cfg.intOf(cr, Cfg.K_MIN_VELOCITY, Cfg.DEF_MIN_VELOCITY)));
            mSlope.setText(String.valueOf(Cfg.floatOf(cr, Cfg.K_MAX_SLOPE, Cfg.DEF_MAX_SLOPE)));
            mWidth.setText(String.valueOf(Cfg.floatOf(cr, Cfg.K_WIDTH_RATIO, Cfg.DEF_WIDTH_RATIO)));
            mCooldown.setText(String.valueOf(Cfg.intOf(cr, Cfg.K_COOLDOWN_MS, Cfg.DEF_COOLDOWN_MS)));
            mLauncherHot.setText(String.valueOf(Cfg.floatOf(cr, Cfg.K_LAUNCHER_HOT_DP, Cfg.DEF_LAUNCHER_HOT_DP)));
            mStatus.setText("当前生效值已从系统设置读取。");
        } catch (Throwable t) {
            mStatus.setText("读取当前值失败：" + t);
        }
    }

    private void save() {
        Intent i = new Intent(OneHandTuner.ACTION_SET);
        i.setPackage("com.android.systemui");
        i.putExtra(Cfg.K_ENABLED, mEnabled.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_MONITOR_ENABLED, mMonitor.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_DEBUG, mDebug.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_SHADE_GUARD, mShadeGuard.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_CLAIM_ZONE, mClaimZone.isChecked() ? "1" : "0");
        i.putExtra(Cfg.K_ZONE_DP, value(mZone, "56"));
        i.putExtra(Cfg.K_MIN_DY_DP, value(mMinDy, "4"));
        i.putExtra(Cfg.K_MIN_VELOCITY, value(mVelocity, "500"));
        i.putExtra(Cfg.K_MAX_SLOPE, value(mSlope, "1.2"));
        i.putExtra(Cfg.K_WIDTH_RATIO, value(mWidth, "1.0"));
        i.putExtra(Cfg.K_COOLDOWN_MS, value(mCooldown, "500"));
        i.putExtra(Cfg.K_LAUNCHER_HOT_DP, value(mLauncherHot, "0"));
        sendBroadcast(i);
        Toast.makeText(this, "已发送。若长时间无反应，请确认模块已启用并已勾选“系统界面”作用域。",
                Toast.LENGTH_LONG).show();
        mStatus.setText("已提交配置。可下滑底部区域测试，并用 logcat -s OneHandTuner 观察。");
    }

    private static String value(EditText e, String def) {
        String s = e.getText() == null ? "" : e.getText().toString().trim();
        return s.length() == 0 ? def : s;
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        return lp;
    }

    private Switch switchRow(LinearLayout parent, String label, boolean def) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        s.setChecked(def);
        parent.addView(s, rowLp());
        return s;
    }

    private EditText editRow(LinearLayout parent, String label, String def) {
        TextView t = text(label, 14, false);
        t.setPadding(0, dp(10), 0, dp(2));
        parent.addView(t);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        e.setText(def);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        parent.addView(e, rowLp());
        return e;
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.START);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
