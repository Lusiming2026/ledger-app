// 根构建脚本：只声明插件，不 apply（各模块自行 alias）
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.composeCompiler) apply false
}
