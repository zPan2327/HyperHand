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
val defaultVersionName = "3.0.3-beta.2"

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
val minVersionCode = 29906

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
        release {
            // 原来的构建链（javac + d8）不做任何压缩，Xposed 模块也最怕 R8 改类名/方法体，
            // 所以这里显式关掉，保持和之前一致的行为。
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("hyperhand")
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
