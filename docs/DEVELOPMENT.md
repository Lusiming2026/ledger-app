# 开发与构建

面向开发者的构建与验证说明。

## 目录结构

```
ledger-app/
├── shared/                          # KMP 共享模块（领域层 + 数据层 + 平台端口）
│   ├── src/commonMain/kotlin/com/ledger/
│   │   ├── domain/
│   │   │   ├── model/               # Account / Category / Transaction / Budget / Money / YearMonth / Report
│   │   │   ├── error/               # DomainError 体系（code + 中文 message）
│   │   │   ├── port/                # Clock / KeyStore / Crypto / FilePicker / Notification / Preference
│   │   │   ├── repository/          # 6 个仓储接口（含 BalanceDelta、LedgerSnapshot）
│   │   │   ├── service/             # Balance / Budget / Report / Crypto / Csv / Backup / Validator
│   │   │   └── usecase/             # AddTransaction / Transfer / ManageAccount / ManageCategory / ManageBudget / GetReport / GetHomeOverview / Export / Import
│   │   ├── data/
│   │   │   ├── db/                  # SQLDelight schema（7 个 .sq）+ DatabaseFactory
│   │   │   ├── repository/          # 7 个 SQLDelight 实现
│   │   │   ├── mapper/ seed/        # 行映射、预置数据（幂等 seed）
│   │   │   └── AppInitializer.kt    # 启动引导：建库 + 幂等写预置分类/账户
│   │   └── di/CommonModule.kt       # Koin 公共装配
│   ├── src/androidMain/             # Android 端口：SQLCipher 工厂、Keystore、文件、通知、偏好
│   ├── src/iosMain/                 # iOS 端口：SQLite 加密配置、Keychain(Settings)、偏好
│   │   └── .../ui/                  # LedgerFacade + IosUiBridge + IosReportBridge（Swift 友好门面）
│   └── src/commonTest/              # 领域用例单测（《UseCase设计》§10 全量 + Money + Balance + Budget）
├── androidApp/                      # Android 壳（Compose，P01–P15 全部页面）
├── iosApp/
│   ├── iosApp/                      # SwiftUI 源码（App/ Platform/ Views/）
│   ├── project.yml                  # XcodeGen 工程描述
│   └── Podfile                      # 唯一 pod：SQLCipher
├── gradle/
│   └── libs.versions.toml
└── docs/
```

## Android 构建

```bash
# 依赖：JDK 17、Android Studio（AGP 8.x，compileSdk 35）
./gradlew :androidApp:installDebug          # 直接装到已连接的设备/模拟器
```

- `minSdk 28`（对齐 PRD「Android 9+」），Kotlin 2.x，Compose BOM。
- SQLCipher 由 `net.zetetic:sqlcipher-android` 提供（坐标已用 AAR 反编译核实）。
- ProGuard 规则已写好（kotlinx.serialization / Koin / SQLDelight / SQLCipher / 协程）。

## iOS 构建

```bash
# 依赖：macOS + Xcode 15+、XcodeGen、CocoaPods、JDK 17
./gradlew :shared:embedAndSignAppleFrameworkForXcode   # 先产出 Shared.framework（或让 Xcode 脚本自动做）
cd iosApp
xcodegen generate                            # 生成 LedgerApp.xcodeproj
pod install                                  # 安装 SQLCipher
open LedgerApp.xcworkspace                   # 注意是 .xcworkspace 不是 .xcodeproj
```

**关键约束**：`shared/build.gradle.kts` 里 `linkSqlite = false`——系统 SQLite 不认
`PRAGMA key`，SQLCipher 的符号必须由 Podfile 提供。如果不装 pod 直接跑，会在
建库时报 "file is not a database" 或表现为「以为加密了其实没加密」。

## 单元测试（无需任何模拟器）

```bash
./gradlew :shared:allTests                   # JVM/Android 单测；native 侧需 Xcode 环境
```

commonTest 覆盖《UseCase设计》§10 全部 25 个用例编号 + Money 解析 + 余额联动合并 + 预算幂等。

## 验证脚本清单（`_inspect/`）

编译验证分两层：

- **Android 端已真机编译通过**：本机已装 JDK 17 + Gradle 8.11.1 + Android SDK 35，
  `./gradlew :androidApp:assembleDebug` 产出 73MB 的 debug APK，`testDebugUnitTest`
  的 48 个单元测试全绿。
- **iOS 端受限于宿主机**：Kotlin/Native 的 `iosArm64` / `iosSimulatorArm64` 目标
  **只能在 macOS 上编译**（需要 Xcode 的 sysroot），Windows 上 Gradle 会直接
  `SKIPPED` 并提示 `cannot be built on this machine`。因此 iOS 侧改用 6 个 Python
  核查脚本，把**能在纯文本 / 真实 SQLite 层面验证的都验证了**：

| 脚本 | 验证内容 | 结果 |
| --- | --- | --- |
| `verify_sql.py` | 6 表 + 10 索引、CHECK 约束、8 种非法字段组合、乐观锁 1/0 行、软删除幂等、区间聚合排除软删除——在**真实 SQLite** 上执行 | ✅ 全过 |
| `verify_domain.py` | 用 Python 复刻领域算法，在真实 SQLite 上跑《UseCase设计》§10 全部用例 | ✅ 全过 |
| `verify_signatures.py` | 7 个 `.sq` 文件 57 条语句 ↔ 27 个 Kotlin 调用点的生成签名一致性 | ✅ 全过 |
| `verify_android.py` | Koin 供给面（12 个 ViewModel × 构造参数）、import 符号存在性、资源引用 | ✅ A/B/C/D 全绿 |
| `verify_ios.py` | Swift 调用 ↔ Kotlin 门面/桥接函数的一致性、工程文件自洽 | ✅ 全绿 |
| `verify_tests.py` | commonTest 对 §10 的覆盖度、Fake 对仓储接口的完整实现 | ✅ 全绿 |

**风险提示**：iOS 侧静态核查不能替代 Mac 上的真机编译。首次在 Mac 构建时可能
暴露的是 Swift 与 K/N 导出名之间的细节（枚举/伴生对象导出形态、`@OptIn` 注解）
——这些已在代码注释里逐处标注了应对策略，但最终仍需一次真机编译兜底。

## 关键设计决策（冻结项）

| 决策 | 取值 | 依据 |
| --- | --- | --- |
| 金额 | Int64「分」，禁用浮点 | PRD §11.6 / R-AMT-03 |
| 金额上限 | 999,999,999 分 = 9,999,999.99 元 | D3 |
| 日期范围 | [2000-01-01, now+24h] | D2 |
| 并发策略 | `updated_at` 作乐观锁版本 | D5 |
| 幂等键 | 客户端 UUID `transaction_id` | D4 |
| 加密 | PBKDF2-HMAC-SHA256 / 600k / 16B 盐 → AES-256-GCM / 12B nonce / 16B tag | PRD §7.6.1 |
| 备份格式 | v1：magic `LEDGER01` + version + kdf + iterations + salt + nonce + ciphertext + tag，固定头 64 字节（kdf 长度前缀 uint16） | PRD §7.6 |
| 通知幂等 | `budget_notification` 表（`uq_notify_once`）是权威判定源，端口只管投递 | PRD §7.3 |
