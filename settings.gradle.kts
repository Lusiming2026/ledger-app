// 记账 APP · KMP 工程 —— 模块声明（T01）
//
// 国内网络下 maven.google.com 直连超时，此处前置阿里云镜像（google / central / gradle-plugin
// 均有镜像），官方源保留在末尾作 fallback。镜像按需启用即可，不影响海外环境构建。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "ledger-app"

include(":shared")
include(":androidApp")
