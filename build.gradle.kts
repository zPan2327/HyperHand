// 根工程只负责把两个子模块的插件版本对上（版本写在 settings.gradle.kts 的 pluginManagement 里），
// 具体配置分别在 module/ 与 glass/ 下。
tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
    subprojects.forEach { delete(it.layout.buildDirectory) }
}
