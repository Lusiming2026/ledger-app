# 记账 APP · 多端记账应用

一个功能完整的记账应用，**一套业务逻辑，三种形态**：

- 📱 **网页版**（✅ 已上线，点开即用）→ https://student-ledger-11588.app.workbuddy.host/
- 🤖 **Android 原生**（Jetpack Compose）
- 🍎 **iOS 原生**（SwiftUI）

![Kotlin](https://img.shields.io/badge/Kotlin_Multiplatform-2.x-7F52FF?logo=kotlin)
![Android](https://img.shields.io/badge/Android-Jetpack_Compose-3DDC84?logo=android)
![iOS](https://img.shields.io/badge/iOS-SwiftUI-000000?logo=apple)
![License](https://img.shields.io/badge/license-MIT-blue)

## ✨ 核心功能

| 模块 | 能力 |
|---|---|
| 📝 记账 | 支出 / 收入 / 转账，15 个预置分类、日期、备注 |
| 🏠 首页 | 总资产、本月收支结余、预算进度预警 |
| 📋 账单 | 按日分组、筛选、编辑、软删除（余额回滚） |
| 💳 账户 | 现金 / 银行卡 / 微信 / 支付宝、对账 |
| 🎯 预算 | 月度预算、使用率、历史 |
| 📊 报表 | 收支统计、分类占比、趋势 |
| 🔒 安全 | SQLCipher / SQLite 加密存储，AES-256 备份 |

## 🏗 架构

**Kotlin Multiplatform 共享领域层 + 双端原生 UI**，业务逻辑（领域模型、用例、数据仓储、加密）只写一次，Android / iOS 各接一套原生界面。

> 本 README 同时是**交付说明**：列出了所有与 PRD 的有意偏离，请对照 §5「偏离清单」阅读。

---

## 1. 目录结构

---

## 1. 目录结构

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
│   ├── src/iosMain/                 # iOS 端口：SQLiter 加密配置、Keychain(Settings)、偏好
│   │   └── .../ui/                  # LedgerFacade + IosUiBridge + IosReportBridge（Swift 友好门面）
│   └── src/commonTest/              # 领域用例单测（《UseCase设计》§10 全量 + Money + Balance + Budget）
├── androidApp/                      # Android 壳（Compose，P01–P15 全部页面）
├── iosApp/
│   ├── iosApp/                      # SwiftUI 源码（App/ Platform/ Views/）
│   ├── project.yml                  # XcodeGen 工程描述
│   └── Podfile                      # 唯一 pod：SQLCipher
└── gradle/ libs.versions.toml
```

---

## 2. 快速开始

### Android

```bash
# 依赖：JDK 17、Android Studio（AGP 8.x，compileSdk 35）
./gradlew :androidApp:installDebug          # 直接装到已连接的设备/模拟器
```

- `minSdk 28`（对齐 PRD「Android 9+」），Kotlin 2.x，Compose BOM。
- SQLCipher 由 `net.zetetic:sqlcipher-android` 提供（坐标已用 AAR 反编译核实）。
- ProGuard 规则已写好（kotlinx.serialization / Koin / SQLDelight / SQLCipher / 协程）。

### iOS

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
建库时报 “file is not a database” 或表现为「以为加密了其实没加密」。

### 单元测试（无需任何模拟器）

```bash
./gradlew :shared:allTests                   # JVM/Android 单测；native 侧需 Xcode 环境
```

commonTest 覆盖《UseCase设计》§10 全部 25 个用例编号 + Money 解析 + 余额联动合并 + 预算幂等。

---

## 3. 验证脚本清单（`_inspect/`，全部在交付前跑通）

编译验证分两层：

- **Android 端已真机编译通过**：本机已装 JDK 17 + Gradle 8.11.1 + Android SDK 35，
  `./gradlew :androidApp:assembleDebug` 产出 73MB 的 debug APK，`testDebugUnitTest`
  的 48 个单元测试全绿。
- **iOS 端受限于宿主机**：Kotlin/Native 的 `iosArm64` / `iosSimulatorArm64` 目标
  **只能在 macOS 上编译**（需要 Xcode 的 sysroot），Windows 上 Gradle 会直接
  `SKIPPED` 并提示 `cannot be built on this machine`。因此 iOS 侧改用 6 个 Python
  核查脚本，把**能在纯文本 / 真实 SQLite 层面验证的都验证了**：

| 脚本 | 验证内容 | 结果 |
|---|---|---|
| `verify_sql.py` | 6 表 + 10 索引、CHECK 约束、8 种非法字段组合、乐观锁 1/0 行、软删除幂等、区间聚合排除软删除——在**真实 SQLite** 上执行 | ✅ 全过 |
| `verify_domain.py` | 用 Python 复刻领域算法，在真实 SQLite 上跑《UseCase设计》§10 全部用例 | ✅ 全过 |
| `verify_signatures.py` | 7 个 `.sq` 文件 57 条语句 ↔ 27 个 Kotlin 调用点的生成签名一致性 | ✅ 全过 |
| `verify_android.py` | Koin 供给面（12 个 ViewModel × 构造参数）、import 符号存在性、资源引用 | ✅ A/B/C/D 全绿 |
| `verify_ios.py` | Swift 调用 ↔ Kotlin 门面/桥接函数的一致性、工程文件自洽 | ✅ 全绿 |
| `verify_tests.py` | commonTest 对 §10 的覆盖度、Fake 对仓储接口的完整实现 | ✅ 全绿 |

**风险提示**：iOS 侧静态核查不能替代 Mac 上的真机编译。首次在 Mac 构建时可能
暴露的是 Swift 与 K/N 导出名之间的细节（枚举/伴生对象导出形态、`@OptIn` 注解）
——这些已在代码注释里逐处标注了应对策略，但最终仍需一次真机编译兜底。

---

## 4. 关键设计决策（冻结项）

| 决策 | 取值 | 依据 |
|---|---|---|
| 金额 | Int64「分」，禁用浮点 | PRD §11.6 / R-AMT-03 |
| 金额上限 | 999,999,999 分 = 9,999,999.99 元 | D3 |
| 日期范围 | [2000-01-01, now+24h] | D2 |
| 并发策略 | `updated_at` 作乐观锁版本 | D5 |
| 幂等键 | 客户端 UUID `transaction_id` | D4 |
| 加密 | PBKDF2-HMAC-SHA256 / 600k / 16B 盐 → AES-256-GCM / 12B nonce / 16B tag | PRD §7.6.1 |
| 备份格式 | v1：magic `LEDGER01` + version + kdf + iterations + salt + nonce + ciphertext + tag，固定头 64 字节（kdf 长度前缀 uint16） | PRD §7.6 |
| 通知幂等 | `budget_notification` 表（`uq_notify_once`）是权威判定源，端口只管投递 | PRD §7.3 |

---

## 5. 偏离清单（有意为之，请 review）

### 5.1 对 PRD §9 端口表的三处偏离

1. **`NotificationSchedulerPort` 去掉了 `hasNotified(...)`**
   PRD 把「是否通知过」放在端口上，但权威判定源是 `budget_notification` 表
   （唯一索引 `uq_notify_once`）。端口不该也无法访问数据库，因此端口只保留
   「权限」与「投递」，幂等判定在 `ManageBudgetUseCase` 里完成。

2. **新增 `CryptoPort`**
   PRD §9 只列了 4 个端口，但「备份加密 / 主密码校验」需要 PBKDF2 + AES-GCM，
   这是平台能力（Android 用 `javax.crypto`，iOS 用 CryptoKit + CommonCrypto 的
   `CCKeyDerivationPBKDF`），必须以端口注入，否则领域层绑死平台。

3. **新增 `PreferencePort`**
   PRD §11.1「记忆上次值」与 §11.7「深色模式三态」需要轻量持久化。这类 UI 偏好
   不该进 SQLCipher 库（不是业务数据、不参与备份），也不该双端各写一遍，
   因此收敛成端口（Android → SharedPreferences 独立文件；iOS → NSUserDefaults）。

### 5.2 其余偏离与已知边界

| # | 偏离 | 理由 |
|---|---|---|
| 1 | **编辑流水时 `type` 不可修改** | PRD §11.1 说「类型不可改」，§6.2.3 的组合约束表却允许 type 出现在编辑场景；两者冲突时取更安全的 §11.1（改类型会让余额联动的回滚语义失效）。§10 的 R-LINK-02「expense→transfer」用例已改为等价的「transfer 改金额」用例，测试里注明了原因。 |
| 2 | **`mergeAll` 的余额语义 PRD 未明确** → 代码中显式定义为：新插入账户直接采用快照余额；本地已有账户对**新导入的流水**做正向余额联动。已加注释，建议回填 PRD。 | 恢复合并时「既不动存量、又保证新增流水反映到余额」是唯一自洽的解释。 |
| 3 | **预置「现金」账户是超出 PRD 的补充** | PRD §6.5 只规定了预置分类；没有账户就没法走通「第一次记账」，因此默认建一个 0 元现金账户（幂等，已存在则跳过）。 |
| 4 | **PRD §6.3 索引表（10 条）与 T05 文字（9 条）不一致** → 以 §6.3 表格为准。 | 表格更具体（含 `uq_category_name`、`uq_budget_period`、`uq_notify_once`）。 |
| 5 | **iOS 桌面快捷方式「记一笔」只在 App 已运行时生效** | SwiftUI App 生命周期拿不到冷启动的 launch options（需要 AppDelegate 的 `didFinishLaunching` + `windowScene` 回调桥接）。已在 `LedgerApp.swift` / `Info.plist` 注释标注，如需覆盖冷启动可在 Stage 2 补桥接。 |
| 6 | **iOS raw key 只能以十六进制口令传入 SQLiter** | SQLiter 的 `DatabaseConfiguration.Encryption(key: String?)` 内部走 `PRAGMA key = '...'`，只接受字符串口令；32 字节 raw key 以 `toHexPassphrase()` 形式传入（SQLCipher 对超过 32 字节的 `PRAGMA key` 字符串按 hex key 解释，等效）。 |
| 7 | **T31/T32（上架发布）与 T04（CI）未包含** | 按本次约定的范围（M1–M3 代码 + 双端壳工程）。 |
| 8 | **分类/账户编辑后未做跨页强制刷新** | 依赖 Flow 订阅自动刷新（记账页、首页都是订阅式）。iOS 明细页编辑返回后显式 `reload()`。若发现个别页面有陈旧数据，属已知边界。 |

---

## 6. 双端一致性约定（给后续维护者）

- **金额/日期格式化的唯一实现在 Kotlin**：Android 走 `shared` 的领域模型，
  iOS 经 `IosUiBridge.kt` 的 `moneyText/dateText/...` 转调，Swift 侧的 `Fmt`
  只是薄封装——绝不在 Swift 里重写一份整数格式化，否则双端必然漂移。
- **Kotlin 枚举不直接给 Swift 用**：`IosUiBridge.kt` 把所有枚举降级为
  「字符串 code + label」（`AccountType.dbValue` 等）。原因：K/N 对枚举项的
  Swift 导出命名（`AccountType.cash` vs `.CASH`）随版本变化，code 是稳定的。
- **Swift 不构造带默认参数的 Kotlin 对象**：`ReportPeriod` 等带 `TimeZone` 默认参的
  工厂经 `IosReportBridge.kt` 降级为 4 字段纯数据 `PeriodInfo`，Swift 持有它并在
  翻页时原样传回——周一起点、跨月边界的日历逻辑只有 Kotlin 一份。
- **非 suspend 且可能抛 Kotlin 异常的函数禁止直接暴露给 Swift**：K/N 里未标注
  `@Throws` 的异常穿过 Swift 会直接崩溃。所以 `setMasterPassword` 在门面包成
  `trySetMasterPassword(password, confirmation): String?`（null = 成功）。

---

## 7. 数据与安全要点

- 数据库密钥：Android 存 EncryptedSharedPreferences（Keystore 包裹），
  iOS 存 Keychain（`multiplatform-settings` 的 `KeychainSettings`）。
- 备份文件**不含**任何密钥材料，主密码不落盘（只存盐 + 校验值）；
  导入成功后重建本机主密码元数据，保证「再次导出」能校验密码。
- 导入分「合并 / 覆盖」，**任何失败不写库**：解密校验阶段一次性读完，
  落库阶段单事务回滚。
- Android 侧 `data_extraction_rules.xml` 与 `backup_rules.xml` 均显式排除
  数据库/偏好/文件（API 31+ 的 D2D 迁移是独立开关，也已排除）——
  系统云备份拿到的是密文库但没有密钥， Restore 无意义且引入密钥管理面。
