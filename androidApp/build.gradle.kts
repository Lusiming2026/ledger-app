plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

android {
    namespace = "com.ledger.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ledger.android"
        minSdk = 28          // 对齐 PRD：Android 9+（API 28+）
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-mvp"
        // SQLCipher 的 native 库只打包这四种 ABI，去掉 x86 老架构与 mips
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 打包 .enc 时需要把 ICS / SQLCipher 的原生库一起带上
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    implementation(libs.kotlinx.coroutines.android)
    // shared 模块内部用的是 implementation 传递，不会外泄；
    // 而 UI 层要自己格式日期（日期分组、报表周期标签），故显式依赖。
    implementation(libs.kotlinx.datetime)
    // `LedgerApplication` 要持有 Koin 实例、各 ViewModel 走 `GlobalContext` 取用例
    implementation(libs.koin.android)

    debugImplementation(libs.compose.ui.tooling)
}
