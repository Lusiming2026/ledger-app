import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
            }
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlcipher.android)
            implementation(libs.androidx.sqlite)
            implementation(libs.security.crypto)
            implementation(libs.koin.android)
            // AndroidNotificationPort 用到 NotificationCompat / ContextCompat
            implementation(libs.androidx.core.ktx)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
            // IosKeyStorePort 基于 KeychainSettings
            implementation(libs.multiplatform.settings)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

sqldelight {
    databases {
        create("LedgerDatabase") {
            packageName.set("com.ledger.data.db")

            // 说明：本工程 iOS 侧输出的是**静态框架**（`isStatic = true`，见上 `kotlin {}` 块）。
            // SQLDelight 文档明确：`linkSqlite` 开关「对静态框架无效」（只对动态框架的链接元数据起作用），
            // 故此处无需设置该属性；SQLCipher 符号由 iosApp 的 Podfile（pod 'SQLCipher'）在 Xcode
            // 链接阶段提供。详见 iosApp/Podfile 与 README「iOS 构建」一节。
        }
    }
}

android {
    namespace = "com.ledger.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 28   // 对齐 PRD：Android 9+（API 28+）
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}
