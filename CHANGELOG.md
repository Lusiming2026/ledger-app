# 更新日志（CHANGELOG）

本文件记录「记账本」原生 App 各版本的变更。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/) 与语义化版本号（[SemVer](https://semver.org/lang/zh-CN/)）。

- **版本号规则**：大改（新模块/不兼容）→ 主版本 +1；新功能 → 次版本 +1；修 bug/小改 → 修订号 +1。
- **每次更新**：在本文件顶部追加一个新版本段（最新的在最上面）。

---

## [0.1.0] - 2026-10-11

### 新增
- 完成 MVP（M1–M3）全量代码：Kotlin Multiplatform 共享领域层 + Android（Jetpack Compose）+ iOS（SwiftUI）。
- 记账：支出 / 收入 / 转账，15 个预置分类（emoji 图标 + 配色）、日期、备注。
- 首页总览：总资产、本月收支结余、预算进度预警。
- 账单列表：按日分组、筛选、编辑、软删除（余额自动回滚）。
- 账户管理：现金 / 银行卡 / 微信 / 支付宝，增删归档、对账。
- 预算：月度预算、使用率、历史。
- 报表：收支统计、分类占比、趋势。
- 安全：SQLCipher / SQLite 加密存储，AES-256 备份 / 恢复。
- Android 端真实编译通过（`assembleDebug` 产出 APK，48 个单元测试全绿）。

### 已知边界
- iOS 端因宿主机限制（需 macOS/Xcode）尚未真机编译，仅通过静态核查；详见 `docs/DELIVERY.md`。
- 上架发布（T31/T32）与 CI（T04）未包含，属后续范围。
