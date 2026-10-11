# 交付说明

本文件列出所有与 PRD 的**有意偏离**、双端一致性约定、数据与安全要点，请对照 PRD 阅读。

## 1. 偏离清单（有意为之，请 review）

### 1.1 对 PRD §9 端口表的三处偏离

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

### 1.2 其余偏离与已知边界

| # | 偏离 | 理由 |
| --- | --- | --- |
| 1 | **编辑流水时 `type` 不可修改** | PRD §11.1 说「类型不可改」，§6.2.3 的组合约束表却允许 type 出现在编辑场景；两者冲突时取更安全的 §11.1（改类型会让余额联动的回滚语义失效）。§10 的 R-LINK-02「expense→transfer」用例已改为等价的「transfer 改金额」用例，测试里注明了原因。 |
| 2 | **`mergeAll` 的余额语义 PRD 未明确** → 代码中显式定义为：新插入账户直接采用快照余额；本地已有账户对**新导入的流水**做正向余额联动。已加注释，建议回填 PRD。 | 恢复合并时「既不动存量、又保证新增流水反映到余额」是唯一自洽的解释。 |
| 3 | **预置「现金」账户是超出 PRD 的补充** | PRD §6.5 只规定了预置分类；没有账户就没法走通「第一次记账」，因此默认建一个 0 元现金账户（幂等，已存在则跳过）。 |
| 4 | **PRD §6.3 索引表（10 条）与 T05 文字（9 条）不一致** → 以 §6.3 表格为准。 | 表格更具体（含 `uq_category_name`、`uq_budget_period`、`uq_notify_once`）。 |
| 5 | **iOS 桌面快捷方式「记一笔」只在 App 已运行时生效** | SwiftUI App 生命周期拿不到冷启动的 launch options（需要 AppDelegate 的 `didFinishLaunching` + `windowScene` 回调桥接）。已在 `LedgerApp.swift` / `Info.plist` 注释标注，如需覆盖冷启动可在 Stage 2 补桥接。 |
| 6 | **iOS raw key 只能以十六进制口令传入 SQLite** | SQLite 的 `DatabaseConfiguration.Encryption(key: String?)` 内部走 `PRAGMA key = '...'`，只接受字符串口令；32 字节 raw key 以 `toHexPassphrase()` 形式传入（SQLCipher 对超过 32 字节的 `PRAGMA key` 字符串按 hex key 解释，等效）。 |
| 7 | **T31/T32（上架发布）与 T04（CI）未包含** | 按本次约定的范围（M1–M3 代码 + 双端壳工程）。 |
| 8 | **分类/账户编辑后未做跨页强制刷新** | 依赖 Flow 订阅自动刷新（记账页、首页都是订阅式）。iOS 明细页编辑返回后显式 `reload()`。若发现个别页面有陈旧数据，属已知边界。 |

## 2. 双端一致性约定（给后续维护者）

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

## 3. 数据与安全要点

- 数据库密钥：Android 存 EncryptedSharedPreferences（Keystore 包裹），
  iOS 存 Keychain（`multiplatform-settings` 的 `KeychainSettings`）。
- 备份文件**不含**任何密钥材料，主密码不落盘（只存盐 + 校验值）；
  导入成功后重建本机主密码元数据，保证「再次导出」能校验密码。
- 导入分「合并 / 覆盖」，**任何失败不写库**：解密校验阶段一次性读完，
  落库阶段单事务回滚。
- Android 侧 `data_extraction_rules.xml` 与 `backup_rules.xml` 均显式排除
  数据库/偏好/文件（API 31+ 的 D2D 迁移是独立开关，也已排除）——
  系统云备份拿到的是密文库但没有密钥，Restore 无意义且引入密钥管理面。
