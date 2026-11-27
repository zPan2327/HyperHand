package io.github.zpan2327.hyperhand.smartkey

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
            title = "按键映射",
            largeTitle = "按键映射",
            // 说明文字交给 TopAppBar 的 subtitle 槽位，对齐与字号由组件按规范处理，
            // 不在内容区自己塞一段不同缩进的文字。
            subtitle = "按应用、音频与设备状态重定义音量键",
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
                                    "无法写入总开关：需要 root 授权以修改系统设置",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                },
                onNewRule = { openEditRule(null) },
                onEditRule = { id -> openEditRule(id) },
                onToggleRule = { id, en -> repo.setEnabled(id, en); revision.intValue++ },
                onDeleteRule = { id -> repo.delete(id); revision.intValue++ },
                onDuplicateRule = { id -> repo.duplicate(id); revision.intValue++ },
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
