package io.github.zpan2327.hyperhand.smartkey

import io.github.zpan2327.hyperhand.glass.R
import androidx.compose.ui.res.stringResource
import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.zpan2327.hyperhand.smartkey.ui.HomeScreen
import io.github.zpan2327.hyperhand.smartkey.ui.SmartKeyScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 智能按键映射首页（纯 Miuix Compose）。入口位于设置页第一项。
 *
 * 下级页面（运行状态、新建/编辑规则、添加状态条件、选择应用）都是**独立 Activity**，
 * 进入/退出动画由系统窗口转场完成——与「设置页 → 智能按键映射」是同一套机制，
 * 应用侧不写任何动画代码。这也是本模块不再保留手写转场容器的原因。
 */
class SmartKeyActivity : SkActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 预热 root 授权 + 同步已有规则：首次写 Settings.Global 需要 WRITE_SECURE_SETTINGS，
        // 用 su 授权可能阻塞，放到后台线程，避免启动或首次点开关时卡住 UI。
        Thread {
            repo.ensureSecureSettings()
            repo.syncNow()
        }.start()
    }

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        // 本地规则增删改后自增，驱动下面的 remember 重新读取。
        val revision = remember { mutableIntStateOf(0) }
        val refreshKey = revision.intValue * 31 + resumeTick.intValue

        val rules = remember(refreshKey) { repo.loadRules() }
        val master = remember(refreshKey) { repo.isMasterEnabled() }

        SmartKeyScaffold(
            title = stringResource(R.string.sk_133),
            largeTitle = stringResource(R.string.sk_133),
            // 说明文字交给 TopAppBar 的 subtitle 槽位，对齐与字号由组件按规范处理，
            // 不在内容区自己塞一段不同缩进的文字。
            subtitle = stringResource(R.string.sk_134),
            // 本页由设置页 startActivity 进入，属于独立窗口的前进页面，
            // 必须给顶栏返回钮（与运行状态/编辑规则一致），否则只能靠系统返回手势退出。
            onBack = { finish() },
        ) { padding, behavior ->
            HomeScreen(
                repo = repo,
                rules = rules,
                masterEnabled = master,
                onMasterChange = { enabled ->
                    // 写 Settings.Global 需要 WRITE_SECURE_SETTINGS，首次用 su 授权可能阻塞，
                    // 因此放到 IO 线程；失败必须明确告知，不能让开关看起来切了却没生效。
                    scope.launch(Dispatchers.IO) {
                        val ok = repo.setMasterEnabled(enabled)
                        withContext(Dispatchers.Main) {
                            if (ok) {
                                revision.intValue++
                            } else {
                                Toast.makeText(
                                    this@SmartKeyActivity,
                                    getString(R.string.sk_135),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                },
                onNewRule = { openEditRule(null) },
                onEditRule = { id -> openEditRule(id) },
                // 增删改与开关都会写 Settings.Global；若后台预热还没完成，这里可能也要走 su 授权，
                // 所以统一放 IO 线程，避免和预热抢在同一时刻时卡住主线程。
                onToggleRule = { id, en ->
                    scope.launch(Dispatchers.IO) {
                        repo.setEnabled(id, en)
                        withContext(Dispatchers.Main) { revision.intValue++ }
                    }
                },
                onDeleteRule = { id ->
                    scope.launch(Dispatchers.IO) {
                        repo.delete(id)
                        withContext(Dispatchers.Main) { revision.intValue++ }
                    }
                },
                onDuplicateRule = { id ->
                    scope.launch(Dispatchers.IO) {
                        repo.duplicate(id)
                        withContext(Dispatchers.Main) { revision.intValue++ }
                    }
                },
                onOpenStatus = { openStatus() },
                version = try {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: ""
                } catch (t: Throwable) {
                    ""
                },
                padding = padding,
                scrollBehavior = behavior,
            )
        }
    }
}
