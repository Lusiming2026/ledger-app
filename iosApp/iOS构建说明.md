# 记账 App · iOS 端构建说明

本目录是记账 App 的 iOS 端（SwiftUI）+ 共享模块的 Kotlin/Native 桥接层。
**iOS 只能在一台 Mac 上编译**——Kotlin/Native 的 Apple 目标需要 Xcode 的 sysroot，
Windows/Linux 上 Gradle 会直接 `SKIPPED`（提示 `cannot be built on this machine`）。

## 一、环境要求（Mac）

| 组件 | 版本 | 说明 |
|---|---|---|
| macOS | 13+ | 建议 Ventura 及以上 |
| Xcode | 15+ | 自带 Swift 5.9 / SDK |
| XcodeGen | 最新 | `brew install xcodegen`，用于从 `project.yml` 生成 `.xcodeproj` |
| CocoaPods | 最新 | `sudo gem install cocoapods`，装 SQLCipher |
| JDK | 17 | Gradle 8.11.1 需要 |

## 二、构建步骤（在 Mac 上，从仓库根目录）

```bash
# 1. 先产出 Shared.framework（Kotlin/Native → 静态 framework）
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64   # 模拟器（Apple Silicon）
# 真机：./gradlew :shared:linkReleaseFrameworkIosArm64

# 2. 生成 Xcode 工程
cd iosApp
xcodegen generate          # 产出 LedgerApp.xcodeproj

# 3. 安装依赖（SQLCipher）
pod install                # 产出 LedgerApp.xcworkspace

# 4. 打开 workspace（注意是 .xcworkspace，不是 .xcodeproj）
open LedgerApp.xcworkspace
```

在 Xcode 里选一个模拟器或真机，直接 Run 即可。`project.yml` 里的
`preBuildScripts` 会自动调 `embedAndSignAppleFrameworkForXcode` 重新构建 framework，
但首次建议先手动执行第 1 步，避免 Xcode 脚本里的 Gradle 环境变量不齐全。

## 三、关键工程说明

- **framework 是静态的**（`shared/build.gradle.kts` 里 `isStatic = true`），
  所以 `Podfile` 用 `use_frameworks! :linkage => :static`，避免重复符号。
- **SQLCipher 是唯一 pod**：`shared` 侧 SQLDelight 的 `linkSqlite=false`，
  `PRAGMA key` 的符号必须由 `pod 'SQLCipher'` 在链接阶段提供，否则会报
  `file is not a database` 或静默不加密。
- **端口分工**：`CryptoPort`/`FilePickerPort`/`NotificationSchedulerPort` 由 Swift 实现
  （`iosApp/iosApp/Platform/*.swift`）；`KeyStorePort` 由 Kotlin 实现
  （`shared/.../iosMain/.../platform/IosKeyStorePort.kt`，用 `KeychainSettings`）；
  `PreferencePort` 由 Kotlin 实现（`IosPreferencePort.kt`，用 `NSUserDefaults`）。
- **DB Key 首次生成**：`IosKeyStorePort.getDbKey()` 用 `arc4random_buf`
  （iOS 系统 CSPRNG）生成 32 字节，持久化在 Keychain；接口签名是非空 `ByteArray`，
  与 Android 侧对齐。

## 四、如果编译报错的排查顺序

1. **`Task 'embedAndSignAppleFrameworkForXcode' not found`**：KGP 没为静态 framework
   生成该任务，改手动 `linkDebugFrameworkIosSimulatorArm64`，并把
   `FRAMEWORK_SEARCH_PATHS` 指向 `shared/build/bin/iosSimulatorArm64/debugFramework`。
2. **枚举 / 伴生对象导出名对不上**：代码里已刻意用「字符串 code + 顶层函数」降级
   （见 `IosUiBridge.kt` / `IosReportBridge.kt` 的注释），不要回退成直接引用 Kotlin 枚举。
3. **`@OptIn` / `ExperimentalForeignApi` 报错**：`IosKeyStorePort.kt` 用 `arc4random_buf`
   已加 `@OptIn(ExperimentalForeignApi::class)`，若 Kotlin 版本升级导致注解名变化，
   按编译提示补注解即可。
4. **SQLCipher 链接错误**：确认跑的是 `.xcworkspace` 而非 `.xcodeproj`，
   且 `pod install` 成功。

## 五、目录结构

```
iosApp/
├── project.yml                 # XcodeGen 工程描述（不提交 .xcodeproj）
├── Podfile                     # 唯一依赖 SQLCipher
├── iOS构建说明.md              # 本文件
└── iosApp/
    ├── App/                    # 入口 / 主题 / 环境 / 格式化
    │   ├── LedgerApp.swift     # @main + AppDelegate（桌面快捷方式）
    │   ├── AppEnvironment.swift
    │   ├── Theme.swift         # 品牌色 + 语义色
    │   ├── Formatters.swift    # Fmt（转调 Kotlin 侧）
    │   └── Errors.swift
    ├── Platform/               # 三个 Swift 端口实现
    │   ├── IosCryptoPort.swift     # CryptoKit + CommonCrypto
    │   ├── IosFilePickerPort.swift # UIDocumentPicker
    │   ├── IosNotificationPort.swift # UNUserNotificationCenter
    │   └── KotlinDataBridge.swift # KotlinByteArray ↔ Data
    └── Views/                  # 11 个 SwiftUI 页面
```

共享模块的 iOS 桥接层在 `shared/src/iosMain/`（`LedgerFacade.kt` / `IosUiBridge.kt` /
`IosReportBridge.kt` / 平台端口），业务逻辑在 `shared/src/commonMain/`。
