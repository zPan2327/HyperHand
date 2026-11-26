package io.github.zpan2327.hyperhand.smartkey;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import io.github.zpan2327.hyperhand.smartkey.hook.SkSelfTest;

/**
 * 按键手势自检入口（无界面，跑完自己关掉）。
 *
 * <p>把 {@link SkSelfTest} 的结论打到 logcat，TAG 为 {@code HHSKTest}。它跑的是生产用的
 * {@code SkEngine}，只是把时间源换成虚拟时钟，所以不用 LSPosed、不用真按键也能验证
 * 「短按 / 长按 / 双击 / 连按 × 各动作」的全部组合。
 *
 * <pre>
 * adb shell am start -n io.github.zpan2327.hyperhand/.smartkey.SkSelfTestActivity
 * adb logcat -s HHSKTest
 * </pre>
 */
public class SkSelfTestActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            String report = SkSelfTest.run();
            Log.i("HHSKTest", report);
        } catch (Throwable t) {
            Log.e("HHSKTest", "self test crashed", t);
        }
        finish();
    }
}
