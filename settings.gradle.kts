pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "9.4.1"
        id("com.android.library") version "9.4.1"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "HyperHand"

// :module 是模块本体（原来的 aapt2 + javac + d8 手工构建）。
// :glass 是液态玻璃底栏，Kotlin/Compose 写的，作为库被 :module 依赖，
// 资源与 R 类由 AGP 统一合并。
include(":module")
include(":glass")
