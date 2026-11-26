package io.github.zpan2327.hyperhand.smartkey.ui

import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.zpan2327.hyperhand.smartkey.data.AppCatalog
import io.github.zpan2327.hyperhand.smartkey.data.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * 应用选择器：Miuix SearchBar + CheckboxPreference，支持包名精确识别与多选。
 *
 * ## 列表分组
 *
 * 顺序固定为「已选择 → 推荐 → 全部应用」，两个前置分组都只在有内容时出现：
 * - **已选择**：只有 [pinSelected] 为 true（= 从规则编辑页点「更换应用」进来）才置顶，
 *   而且用**进页面那一刻**的选中集合定格——勾掉某一项时行不会当场跳走；
 *   正常「添加状态条件」进来时不置顶，保持常规浏览顺序。
 * - **推荐**：媒体类应用（见 [AppCatalog.recommended]），行尾带「推荐」标记。
 *
 * ## 加载
 *
 * 列表数据来自 [AppCatalog]：先吃磁盘快照（毫秒级），图标按可见行懒加载。
 * 首次枚举不再阻塞首帧，下拉刷新才强制重新扫描。
 */
@Composable
fun AppPickerScreen(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    padding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    pinSelected: Boolean = false,
) {
    val context = LocalContext.current
    // 磁盘快照直接就能用；没有快照才显示「正在读取…」，同时后台扫描。
    val apps = remember { mutableStateOf(AppCatalog.snapshot(context) ?: emptyList()) }
    val loading = remember { mutableStateOf(apps.value.isEmpty()) }
    val refreshing = remember { mutableStateOf(false) }
    val query = remember { mutableStateOf("") }
    val expanded = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pullState = rememberPullToRefreshState()

    // 「更换应用」时把进来那一刻的选中项定格，避免勾选/取消时行在手指下跳走。
    val pinned = remember { if (pinSelected) selected.toSet() else emptySet() }

    // 推荐集合依赖清单，清单变了（下拉刷新）就重算。
    val recommended = remember(apps.value) {
        if (apps.value.isEmpty()) emptySet() else AppCatalog.recommended(context)
    }

    fun reload() {
        if (refreshing.value) return
        refreshing.value = true
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { AppCatalog.refresh(context) }
            apps.value = loaded
            loading.value = false
            refreshing.value = false
        }
    }

    LaunchedEffect(Unit) {
        // 预热兜底：正常路径下首页已经起过预热，这里主要防「直接冷启本页」。
        if (apps.value.isEmpty()) {
            AppCatalog.prewarm(context)
            val loaded = withContext(Dispatchers.IO) {
                AppCatalog.snapshot(context) ?: AppCatalog.refresh(context)
            }
            apps.value = loaded
        }
        loading.value = false
    }

    val keyword = query.value.trim()
    val matched = apps.value.filter { app ->
        keyword.isBlank() || app.label.contains(keyword, true) ||
            app.pkg.contains(keyword, true)
    }
    val pinnedApps = if (pinned.isEmpty()) emptyList() else matched.filter { it.pkg in pinned }
    val remaining = if (pinned.isEmpty()) matched else matched.filterNot { it.pkg in pinned }
    val recommendApps = if (recommended.isEmpty()) emptyList()
    else remaining.filter { it.pkg in recommended }
    val otherApps = if (recommended.isEmpty()) remaining
    else remaining.filterNot { it.pkg in recommended }
    val hasGroups = pinnedApps.isNotEmpty() || recommendApps.isNotEmpty()

    // 搜索栏留在下拉刷新**外面**：指示器必须出现在搜索栏下方、列表上方，
    // 包在 PullToRefresh 里的话会从搜索栏上方（顶栏下沿）拉出来，位置是错的。
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SearchBar(
                inputField = {
                    InputField(
                        query = query.value,
                        onQueryChange = { query.value = it },
                        onSearch = { expanded.value = false },
                        expanded = expanded.value,
                        onExpandedChange = { expanded.value = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = "搜索应用名称或包名",
                    )
                },
                onExpandedChange = { expanded.value = it },
                expanded = expanded.value,
                content = {},
            )
        }

        PullToRefresh(
            isRefreshing = refreshing.value,
            onRefresh = { reload() },
            modifier = Modifier.fillMaxWidth().weight(1f),
            pullToRefreshState = pullState,
            // 让顶栏跟着下拉/回弹联动，和 Miuix 官方示例的接法一致。
            topAppBarScrollBehavior = scrollBehavior,
            // Miuix 自带文案只有英文（Pull down to refresh / Release to refresh …），
            // 按系统语言取对应的一组。
            refreshTexts = refreshTextsForSystemLocale(),
            // 颜色用 Miuix 组件自己的默认值（灰）：之前显式传了 `primary`，指示器变成主题蓝，
            // 和系统/其它 App 的下拉刷新不是一个观感。
        ) {
            if (loading.value && matched.isEmpty()) {
                // 磁盘快照也没有时才可能是这句；保留提示避免白屏。
                Text(
                    text = "正在读取应用列表…",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    // 卡片与屏幕边缘保持 16dp；上下 8dp 与其它页面一致。
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (pinnedApps.isNotEmpty()) {
                        item(key = "header-pinned") { SkListGroupTitle("已选择") }
                        items(pinnedApps, key = { it.pkg }) { app ->
                            // 分组成员是进来那一刻定格的（行不会跳走），但勾选状态必须跟着实时走。
                            AppRow(app, checked = app.pkg in selected, recommended = app.pkg in recommended, onToggle)
                        }
                    }
                    if (recommendApps.isNotEmpty()) {
                        item(key = "header-recommend") { SkListGroupTitle("推荐") }
                        items(recommendApps, key = { it.pkg }) { app ->
                            AppRow(app, checked = app.pkg in selected, recommended = true, onToggle)
                        }
                    }
                    if (hasGroups && otherApps.isNotEmpty()) {
                        item(key = "header-all") { SkListGroupTitle("全部应用") }
                    }
                    items(otherApps, key = { it.pkg }) { app ->
                        AppRow(app, checked = app.pkg in selected, recommended = false, onToggle)
                    }
                    if (matched.isEmpty() && !loading.value) {
                        item(key = "empty") {
                            Text(
                                text = "没有匹配的应用",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceSecondary,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 分组标题：与 16dp 的卡片对齐（`SmallTitle` 默认 28dp 缩进在这里会缩进过深）。 */
@Composable
private fun SkListGroupTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onBackgroundVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun AppRow(
    app: AppEntry,
    checked: Boolean,
    recommended: Boolean,
    onToggle: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        pressFeedbackType = PressFeedbackType.None,
    ) {
        CheckboxPreference(
            title = app.label,
            summary = app.pkg,
            checked = checked,
            // Checkbox 自带触感（Miuix 内部走 HapticFeedback），不重复叠加。
            onCheckedChange = { onToggle(app.pkg) },
            startAction = { AppIcon(app.pkg) },
            endActions = {
                if (recommended) {
                    SkRecommendBadge()
                    Spacer(Modifier.width(10.dp))
                }
            },
            checkboxLocation = CheckboxLocation.End,
        )
    }
}

/** 推荐标记：主色 12% 底 + 主色字，尺寸跟脚注一致，不抢主标题。 */
@Composable
fun SkRecommendBadge() {
    Box(
        modifier = Modifier
            .background(
                MiuixTheme.colorScheme.primary.copy(alpha = 0.12f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "推荐",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.primary,
        )
    }
}

/**
 * 下拉刷新文案按系统语言取。
 *
 * 顺序与 Miuix `PullToRefreshDefaults.refreshTexts` 一致：
 * 下拉 / 松手 / 刷新中 / 刷新完成。
 */
private fun refreshTextsForSystemLocale(): List<String> =
    if (java.util.Locale.getDefault().language.startsWith("zh")) {
        listOf("下拉刷新", "松手刷新", "正在刷新…", "刷新完成")
    } else {
        listOf("Pull down to refresh", "Release to refresh", "Refreshing…", "Refreshed")
    }

/**
 * 行图标：先看 LRU 里有没有（组合期同步取，滚动回来不会闪），没有再去后台解码。
 *
 * 图标是旧实现里最贵的一步，现在只有可见行会取，且解码后常驻 LRU。
 */
@Composable
private fun AppIcon(pkg: String) {
    val context = LocalContext.current
    val icon by produceState(initialValue = AppCatalog.cachedIcon(pkg), key1 = pkg) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { AppCatalog.icon(context.applicationContext, pkg) }
        }
    }
    val drawable: Drawable? = icon
    if (drawable == null) {
        // 占位：和图标同尺寸的浅色圆角块，避免行高在图标到达时跳一下。
        Box(
            Modifier
                .size(40.dp)
                .background(
                    MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f),
                    RoundedCornerShape(10.dp),
                ),
        )
    } else {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = null
                }
            },
            update = { iv -> iv.setImageDrawable(drawable) },
            modifier = Modifier.size(40.dp),
        )
    }
}
