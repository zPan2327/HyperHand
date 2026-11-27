import java.io.File

plugins {
    id("com.android.application")
}

// ===========================================================================
// 版本号
//
// 规则与原来的 module/build.sh 完全一致，CI 换到 Gradle 后产物的 versionName /
// versionCode 不会漂：
//   正式版 1.0.1           -> 10001
//   预发布 3.0.2-beta.1    -> 29903（= 30002 - 100 + 1）
//
// 预发布版落在「目标正式版」下方 100 格的区间里，所以三条约束同时成立：
//   已发布正式版 2.9.10 (20910) < beta (29903) < 正式版 3.0.2 (30002)
// 于是测试版能覆盖安装到正式版上，正式版发布后也能把测试版顶掉。
// ===========================================================================
val defaultVersionName = "3.4.7"

fun fromEnvOrProperty(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
        ?: providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }

val versionNameValue = fromEnvOrProperty("VERSION_NAME") ?: defaultVersionName

val derivedVersionCode: Int = run {
    val match = Regex("""^(\d+)\.(\d+)\.(\d+)(-beta\.(\d+))?$""").matchEntire(versionNameValue)
        ?: error("无法从 '$versionNameValue' 推导 versionCode，请显式设置 VERSION_CODE")
    val (major, minor, patch) = match.groupValues.drop(1).take(3).map { it.toInt() }
    val base = major * 10000 + minor * 100 + patch
    val betaIndex = match.groupValues[5].takeIf { it.isNotEmpty() }?.toInt()
    if (betaIndex == null) base else base - 100 + betaIndex
}

// 已经发布过的最高 versionCode。beta 段的公式（base - 100 + N）在跨 patch 线时会重叠：
// 3.0.2-beta.2 / 3.0.3-beta.1 / 3.0.3-beta.2 按公式分别是 29904 / 29904 / 29905，
// 而 3.0.3-beta.1 因下限实际发成 29905，会和 beta.2 的推导值撞号。
// Android 要求 versionCode 严格递增（否则覆盖安装失败），所以下限跟着发版走：
// 每次发版把 minVersionCode 抬到本次的号即可，公式本身不用动，历史版本仍可复现。
// 3.1.0 正式版 = 30100；3.3.0 正式版 = 30300（新增 iOS 式快捷菜单滑选）；
// 3.3.1 正式版 = 30301（文档/发布说明整理，无功能变动）。
// 3.3.2 正式版 = 30302（修复关于页浅色 logo 渐变卡顿）。
// 3.3.3 正式版 = 30303（去掉滑选进入/抬手的两处多余震动）。
// 3.3.4 正式版 = 30304（设置交互 MIUIX 化、自动保存、状态卡 Tilt 按压、黑块/误触修复）。
// 3.3.5 = 30305（规则编辑页按米家「编辑自动化」复刻、按压手势状态机重写、应用选择器
//   改磁盘快照 + 图标懒加载 + 推荐/置顶、条件行显示应用名、所有变体统一签名）。
// 3.3.6 = 30306（概览右上角图标恢复常显；重启作用域对话框补上「系统框架」）。
// 3.3.7 = 30307（对齐 LSPosed：顶栏近不透光 + 淡模糊、折叠标题居中、折叠改弹簧收缩、
//   横滑改临界阻尼弹簧）。
// 3.3.8 = 30308（撤销 3.3.7 的「一甩跨两页」：Compose Pager 默认 atMost(1)，一次手势一页）。
// 3.3.9 = 30309（按键规则按「按压方式」匹配，修双击/长按不生效；设置页改一功能一卡 + 卡内折叠）。
// 3.4.0 = 30400（长容器折叠改用 ω=10/480ms；横滑松手改成 decay 滑行 + spring 吸附两段）。
// 3.4.1 = 30401（横滑回退为单段临界阻尼弹簧：OverScroller 的 decay 无法与页位对齐）。
// 3.4.2 = 30402（折叠动画改为只对「屏幕内可见高度」做动画，修单手模式卡的「先卡住后突消」）。
// 3.4.3 = 30403（shadeGuard 增加方向判定：从底部触发区起手的横向滑动不再拦
//   NotificationPanelViewController.expand*，修控制中心右滑误退出）。
// 3.4.4 = 30404（排版权值统一：分组标题 28dp 内容线、关于页姓名 20sp title3、
//   开发者卡内边距 16dp、版本号格式统一为 `版本名(版本号)`）。
// 3.4.5 = 30405（关于页卡片改半透明对齐 LSPosed；单手模式设 常用/高级 两层，
//   总开关统一「启用 + 功能名」；设置页文案重写去口语）。
val minVersionCode = 30407

val versionCodeValue = fromEnvOrProperty("VERSION_CODE")?.toIntOrNull()
    ?: maxOf(derivedVersionCode, minVersionCode)

// ===========================================================================
// 签名
//
// 密钥固定放在 module/signing/hyperhand.keystore（已 gitignore）。保留同一个文件才能
// 持续用 adb install -r 覆盖升级，也才能和 LSPosed 模块仓库里的历史版本同签名。
// 首次构建没有密钥时自动生成一个——和原来的 build.sh 行为一致。
// ===========================================================================
val keystorePass = fromEnvOrProperty("KEYSTORE_PASS") ?: "onehand123"
val signingDir = layout.projectDirectory.dir("signing")
val keystoreFile = signingDir.file("hyperhand.keystore").asFile

fun keytoolExecutable(): String {
    val candidate = File(System.getProperty("java.home"), "bin/keytool")
    return when {
        candidate.exists() -> candidate.absolutePath
        File(candidate.absolutePath + ".exe").exists() -> candidate.absolutePath + ".exe"
        else -> "keytool"
    }
}

val ensureKeystore = tasks.register("ensureKeystore") {
    group = "build"
    description = "首次构建时生成 module/signing/hyperhand.keystore。"
    outputs.upToDateWhen { keystoreFile.exists() }
    doLast {
        if (keystoreFile.exists()) return@doLast
        signingDir.asFile.mkdirs()
        logger.lifecycle("未发现 keystore，自动生成一个新的：$keystoreFile")
        val result = ProcessBuilder(
            keytoolExecutable(),
            "-genkeypair",
            "-keystore", keystoreFile.absolutePath,
            "-alias", "hyperhand",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "10000",
            "-storepass", keystorePass,
            "-keypass", keystorePass,
            "-dname", "CN=HyperHand, OU=dev, O=dev, L=CN, S=CN, C=CN",
        ).redirectErrorStream(true).start()
        result.inputStream.bufferedReader().forEachLine { logger.info(it) }
        check(result.waitFor() == 0) { "keytool 生成 keystore 失败，退出码 ${result.exitValue()}" }
    }
}

// 签名校验任务会比打包先跑，必须让它等密钥生成完。
tasks.configureEach {
    if (name.startsWith("validateSigning") || name == "preBuild") {
        dependsOn(ensureKeystore)
    }
}

android {
    namespace = "io.github.zpan2327.hyperhand"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.zpan2327.hyperhand"
        // 原来是 31（手工 aapt2 不跑 manifest 合并，所以从没撞上依赖的 minSdk）。
        // miuix-blur（玻璃底栏的模糊/折射来源）声明 minSdk 33，用 overrideLibrary 强压会在
        // Android 12 上留下无兜底的运行时崩溃，所以直接抬到 33。本项目面向 HyperOS（Android 14+），
        // 实际只放弃 Android 12。
        minSdk = 33
        targetSdk = 36
        versionCode = versionCodeValue
        versionName = versionNameValue
    }

    signingConfigs {
        create("hyperhand") {
            storeFile = keystoreFile
            storePassword = keystorePass
            keyAlias = "hyperhand"
            keyPassword = keystorePass
        }
    }

    buildTypes {
        // 【统一签名】所有变体（debug / fast / release）都用工程自带的
        // `module/signing/hyperhand.keystore`，不再用机器的 `~/.android/debug.keystore`。
        //
        // 缘由（踩过两次）：fast 当初为了「能覆盖 debug 包」签的是 debug 钥匙，于是
        //   release / 模块仓库历史版本 = hyperhand（912fa6bf…）
        //   debug / fast               = debug    （76dfe496…）
        // 两把钥匙互不兼容，拿一个覆盖另一个必然 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，
        // 而卸载重装会丢掉 LSPosed 的模块启用状态与作用域。现在统一成一把，
        // 任何变体都能直接 `pm install -r` 覆盖另一个，代价只是 debug 包也签成发布钥匙
        // （本项目不经 Play 分发，没有影响）。
        debug {
            signingConfig = signingConfigs.getByName("hyperhand")
        }
        release {
            // 原来的构建链（javac + d8）不做任何压缩，Xposed 模块也最怕 R8 改类名/方法体，
            // 所以这里显式关掉，保持和之前一致的行为。
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("hyperhand")
        }
        // 本地自用安装包：代码与 debug 完全一致，唯一差别是 debuggable = false。
        //
        // 这个差别对启动速度是决定性的：debuggable=true 的包会被 ART 跳过 AOT 编译
        // （安装后 `dumpsys package` 里是 status=run-from-apk / verify），整套 Compose
        // 类加载与执行全靠解释器 + JIT。真机实测（Xiaomi 14 / 120Hz）冷启动：
        //   debug  : TotalTime 1320～1396ms，首帧 693～729ms，掉帧 600～650ms
        //   fast   : TotalTime  421～ 517ms，首帧 188～222ms，掉帧 200ms
        // 日常使用装 fast，需要断点调试时才装 debug。
        create("fast") {
            initWith(getByName("debug"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("hyperhand")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // AGP 默认把 dex 原样（不压缩）存进 APK，让 ART 能直接 mmap。代价是体积翻三倍：
        // Compose + miuix 的 dex 有 20MB 上下，存下来 APK 就是 20MB+，压缩后只有 8MB。
        // 这个模块是通过 LSPosed 模块仓库分发的，下载体积比安装速度重要，所以走压缩。
        dex {
            useLegacyPackaging = true
        }

        resources {
            // libxposed 只认 APK 根下 META-INF/xposed/*，所以这里只排掉与模块无关的许可证文本，
            // 不能整目录排除 META-INF。
            excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/*.version")
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(project(":glass"))
    // MainActivity 继承 ComponentActivity：Compose 需要 ViewTree 上有 LifecycleOwner /
    // SavedStateRegistryOwner，只有 ComponentActivity 会装好它们。activity 只是被其它依赖
    // 间接带上来的（没有 api 暴露），所以这里显式声明，避免依赖解析变动后突然编译不过。
    implementation("androidx.activity:activity:1.13.0")
    // libxposed 的 API 只在编译期需要，绝对不能进 dex（框架在运行时提供）。
    compileOnly(files("libs/api-102.jar"))
}

// 产物落到 module/out/HyperHand.apk，保持和旧脚本、README、发布脚本一致的位置。
val assembleHyperHand = tasks.register<Copy>("assembleHyperHand") {
    group = "build"
    description = "构建 release APK 并复制到 module/out/HyperHand.apk。"
    dependsOn("assembleRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
        exclude("**/*unsigned*", "**/*unaligned*")
    }
    into(layout.projectDirectory.dir("out"))
    rename { "HyperHand.apk" }
}
