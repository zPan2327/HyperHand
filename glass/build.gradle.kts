plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 液态玻璃底栏（从 LiquidDemo 抽出来的 Compose 库）。
//
// 这里保持成一个普通的 Android library：Compose/miuix 的资源、R 类、so 全都交给 AGP
// 合并进 :module，对外只暴露 HyperGlassBarView（Android View，Java 友好）。
android {
    namespace = "io.github.zpan2327.hyperhand.glass"
    compileSdk = 37

    defaultConfig {
        // miuix-blur 要求 33，见 :module 里的同一条注释。
        minSdk = 33
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // 这个库只是把一个 Compose 组件包成 View，没有资源/API 兼容性负担要挡构建。
        abortOnError = false
    }
}

dependencies {
    // :module 是纯 Java，javac 编译 MainActivity 时需要在 classpath 上看到
    // HyperGlassBarView 的父类 AbstractComposeView，所以 compose 必须是 api 而不是 implementation。
    api(platform("androidx.compose:compose-bom:2026.09.00"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4-rc01")
    api("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4-rc01")
}
