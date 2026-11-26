package io.github.zpan2327.hyperhand;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * 一页的容器：页面内容 + **这一页自己的**顶栏。
 *
 * <p>把顶栏放进页面容器（而不是全屏只放一条），横滑时顶栏就跟着页面一起被
 * {@link PagePager} 平移——每页的标题、折叠进度、右上角动作互相独立，不存在
 * 「一条顶栏按两页状态插值」的中间态。
 *
 * <p>页面内容本身仍是原来的 ScrollView（或关于页的 Root），
 * {@link MainActivity#scrollOf(View)} 负责从容器里取出它。
 */
final class PageHost extends FrameLayout {

    final View content;
    final TopBar bar;

    PageHost(Context c, View content, TopBar bar) {
        super(c);
        this.content = content;
        this.bar = bar;
        addView(content, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (bar != null) {
            addView(bar, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    static View contentOf(View v) {
        return v instanceof PageHost ? ((PageHost) v).content : v;
    }
}
