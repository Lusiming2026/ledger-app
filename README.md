# 记账本 · 多端记账应用

一个功能完整的记账应用，**一套业务逻辑，三种形态**。

- 📱 **网页版**（✅ 已上线，点开即用）→ https://student-ledger-11588.app.workbuddy.host/
- 🤖 **Android 原生**（Jetpack Compose）
- 🍎 **iOS 原生**（SwiftUI）

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin_Multiplatform-2.x-7F52FF?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Android-Jetpack_Compose-3DDC84?logo=android" alt="Android">
  <img src="https://img.shields.io/badge/iOS-SwiftUI-000000?logo=apple" alt="iOS">
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="License">
</p>

## ✨ 核心功能

| 模块 | 能力 |
| --- | --- |
| 📝 记账 | 支出 / 收入 / 转账，15 个预置分类、日期、备注 |
| 🏠 首页 | 总资产、本月收支结余、预算进度预警 |
| 📋 账单 | 按日分组、筛选、编辑、软删除（余额回滚） |
| 💳 账户 | 现金 / 银行卡 / 微信 / 支付宝、对账 |
| 🎯 预算 | 月度预算、使用率、历史 |
| 📊 报表 | 收支统计、分类占比、趋势 |
| 🔒 安全 | SQLCipher / SQLite 加密存储，AES-256 备份 |

## 🏗 架构

**Kotlin Multiplatform 共享领域层 + 双端原生 UI**：业务逻辑（领域模型、用例、数据仓储、加密）只写一次，Android / iOS 各接一套原生界面。

```
         ┌─────────────────────────────────┐
         │      shared (Kotlin 共享层)      │
         │  领域模型 · 用例 · 数据仓储 · 加密  │
         └──────────────┬──────────────────┘
              ┌─────────┴─────────┐
     ┌────────▼───────┐   ┌───────▼────────┐
     │  Android 壳     │   │   iOS 壳       │
     │  Jetpack Compose│   │   SwiftUI      │
     └────────────────┘   └────────────────┘
```

## 🚀 快速开始

想直接用？**打开网页版即可**，无需安装。想跑原生端，看各端说明：

- 🤖 [Android 构建](./docs/DEVELOPMENT.md#android)
- 🍎 [iOS 构建](./docs/DEVELOPMENT.md#ios)

## 📚 文档

| 文档 | 内容 |
| --- | --- |
| [开发与构建](./docs/DEVELOPMENT.md) | 目录结构、Android / iOS 构建步骤、验证脚本清单、关键设计决策 |
| [交付说明](./docs/DELIVERY.md) | 与 PRD 的偏离清单、双端一致性约定、数据与安全要点 |

## 🔗 相关项目

- [ledger-web](https://github.com/Lusiming2026/ledger-web) —— 同一套记账业务的零依赖网页版（纯 HTML + JS，点开即用）

## 📄 License

[MIT](./LICENSE)
