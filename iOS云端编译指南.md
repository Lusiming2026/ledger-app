# iOS 云端编译 + 装机指南（Windows 本机操作）

> 目标：在 **Windows 电脑**上，用 GitHub Actions 的 macOS 运行器把记账 App
> 编译成 `.ipa`，再装到你的 iPhone。
>
> 前置条件：有 GitHub 账号、iPhone（iOS 15.0+）、免费 Apple ID。
> 无 Apple 开发者账号（$99/年）时走「自签」路线，见 §4。

---

## 0. 先搞清一件事：没有开发者账号，装到 iPhone 有限制

苹果强制 iOS App 必须有有效签名才能安装运行。你**没有开发者账号**，所以：

- ✅ 云端能帮你编译出 `.ipa`
- ⚠️ 但这个 `.ipa` **不能直接双击安装**，需要「自签」后装

自签的代价是：**免费 Apple ID 签名的 App 有效期只有 7 天**，7 天后要重新签名。
日常自用完全够，只是要每周点一下。

---

## 1. 把代码推到 GitHub（一次性，约 5 分钟）

### 1.1 在 GitHub 建仓库

1. 打开 https://github.com/new
2. Repository name 填 `ledger-app`（随意）
3. 选 **Private**（私有，代码里有你的记账数据逻辑，别公开）
4. 点 **Create repository**

### 1.2 生成访问令牌（Personal Access Token）

1. 打开 https://github.com/settings/tokens
2. 点 **Generate new token (classic)**
3. 勾选权限：`repo`（整个勾上）+ `workflow`
4. 生成后**复制保存**（只显示一次，形如 `ghp_xxxx...`）

### 1.3 在本机推送（在项目根目录执行）

打开终端（PowerShell 或 Git Bash），逐条执行（把 `你的用户名`、`你的令牌` 替换掉）：

```bash
cd C:/Users/ASUS/WorkBuddy/2026-10-09-22-56-31/ledger-app

git init
git add .
git commit -m "init: 记账 App KMP 工程 + iOS 云端编译工作流"

git branch -M main
git remote add origin https://你的用户名:你的令牌@github.com/你的用户名/ledger-app.git
git push -u origin main
```

> 令牌直接拼在 URL 里推送最省事，但**别把这个 URL 分享给别人**。

---

## 2. 触发云端编译（手动，约 10~20 分钟）

1. 打开你的仓库页面 → 顶部 **Actions** 标签
2. 左侧找到 **Build iOS App** → 点 **Run workflow**
3. 参数 `signing_style` 选 **ad-hoc**（自签，因为你没开发者账号）
4. 点绿色 **Run workflow** 按钮

编译过程会自动做：装 JDK/工具链 → 编译 Kotlin 的 `Shared.framework`（首次约 10 分钟，要下载 1GB 工具链）→ 生成 Xcode 工程 → 编译 Swift → 归档导出 `.ipa`。

### 2.1 下载 .ipa

1. 编译完成后，点进这次运行记录
2. 页面底部 **Artifacts** 区域 → 找到 `LedgerApp-unsigned-ipa`
3. 下载解压，得到 `LedgerApp-unsigned.ipa`

---

## 3. 把 .ipa 装到 iPhone（自签路线）

因为你没开发者账号，`.ipa` 是未签名的，需要自签。三种方式选一个：

### 方式 A：AltStore（推荐，一次配置长期用）

1. 电脑装 [AltServer](https://altstore.io/)（Windows 版）
2. 电脑装 iTunes（苹果官网下载，非 Microsoft Store 版）
3. 打开 AltServer → 菜单栏图标 → Install AltStore → 选你的 iPhone
4. iPhone 上信任 AltStore：设置 → 通用 → VPN 与设备管理 → 信任
5. 把 `LedgerApp-unsigned.ipa` 传到手机（隔空投送/QQ/微信文件助手）
6. 手机 AltStore → My Apps → 右上角 + → 选这个 ipa → 用免费 Apple ID 签名

> AltStore 会自动在后台续签，只要电脑和手机偶尔同网即可，基本摆脱 7 天限制。

### 方式 B：Sideloadly（最简单，但每次 7 天重签）

1. 电脑装 [Sideloadly](https://sideloadly.io/)（Windows 版）
2. 打开，拖入 `.ipa`，填免费 Apple ID
3. 点 Start，输入 Apple ID 密码（用「应用专用密码」）
4. 7 天后重新跑一次即可

### 方式 C：有 Apple 开发者账号（$99/年，一劳永逸）

如果你后面愿意花 $99/年注册开发者账号，`.ipa` 就能**直接装、不用重签、能装多台设备、还能上架**。到那时我可以帮你把签名流程改成正式的。

---

## 4. 常见问题排查

| 现象 | 原因 | 解决 |
|---|---|---|
| Actions 报 `xcodegen: command not found` | brew 安装失败 | 看日志，手动重跑该 step |
| Actions 报 `linkReleaseFrameworkIosArm64` 失败 | K/N 工具链下载失败 | 重跑一次（GitHub 网络偶发超时） |
| `pod install` 报 SQLCipher 找不到 | CocoaPods 源问题 | 工作流里已用默认源，一般不会遇到 |
| 手机上装完点开闪退 | 自签证书未信任 | 设置→通用→VPN与设备管理→信任证书 |
| 7 天后 App 打不开 | 自签过期 | 用 AltStore 重签 / Sideloadly 重跑 |
| 想更新版本 | 改了代码 | 重新 `git push` → 重新触发 Actions → 重下 ipa 重装 |

---

## 5. 你只需要做的三件事（TL;DR）

1. **GitHub 建仓库 + 生成 token**（§1，一次性）
2. **推送代码 + 点 Run workflow**（§1.3 + §2）
3. **下载 ipa + 用 AltStore 装**（§2.1 + §3 方式 A）

其余（工作流、编译脚本、签名占位）我已经全部写好放在仓库里了。
