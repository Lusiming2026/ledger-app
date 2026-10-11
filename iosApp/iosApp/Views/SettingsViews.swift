import SwiftUI
import Shared

// ============================================================================
// P15 设置中心（PRD §4.2）
// ============================================================================

struct SettingsHubView: View {
    @EnvironmentObject private var env: AppEnvironment
    @EnvironmentObject private var quickActions: QuickActionBus

    /// 子页面的数据变更（最典型：导入恢复覆盖了整个账本）需要让首页 / 报表重算
    let onChange: () -> Void
    let refreshToken: Int

    @State private var notificationsAllowed = false
    @State private var appVersion = ""

    var body: some View {
        List {
            Section("记账配置") {
                NavigationLink {
                    AccountListScreen { }.environmentObject(env)
                } label: {
                    SettingRow(
                        icon: "creditcard",
                        title: "账户管理",
                        subtitle: "新增、归档、排序与对账"
                    )
                }

                NavigationLink {
                    CategoryListScreen { }.environmentObject(env)
                } label: {
                    SettingRow(
                        icon: "square.grid.2x2",
                        title: "分类管理",
                        subtitle: "自定义分类、图标与颜色"
                    )
                }

                NavigationLink {
                    BudgetView { }.environmentObject(env)
                } label: {
                    SettingRow(
                        icon: "target",
                        title: "预算",
                        subtitle: "月度总额，80% / 100% 提醒"
                    )
                }
            }

            Section("数据") {
                NavigationLink {
                    DataView(onChange: onChange).environmentObject(env)
                } label: {
                    SettingRow(
                        icon: "externaldrive",
                        title: "数据管理",
                        subtitle: "导出 CSV、加密备份与恢复"
                    )
                }
            }

            Section("通用") {
                NavigationLink {
                    AppearanceView().environmentObject(env)
                } label: {
                    SettingRow(
                        icon: "circle.lefthalf.filled",
                        title: "外观",
                        subtitle: env.themeMode.label
                    )
                }

                HStack {
                    Image(systemName: notificationsAllowed
                          ? "bell.badge.fill"
                          : "bell.slash.fill")
                    .foregroundColor(notificationsAllowed
                                     ? SemanticColor.budgetNormal
                                     : BrandColor.warningTint)
                    .frame(width: 24)
                    Text("预算提醒通知")
                    Spacer()
                    Text(notificationsAllowed ? "已开启" : "未开启")
                        .font(.subheadline)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                    if !notificationsAllowed {
                        Button("开启") {
                            env.facade.notifications.requestPermission { granted in
                                notificationsAllowed = granted
                            }
                        }
                        .font(.subheadline)
                    }
                }

                NavigationLink {
                    AboutView().environmentObject(env)
                } label: {
                    SettingRow(
                        icon: "info.circle",
                        title: "关于",
                        subtitle: "版本 \(appVersion)"
                    )
                }
            }

            Section {
                Button {
                    quickActions.requestRecord()
                } label: {
                    Label("快捷记一笔", systemImage: "plus.circle")
                }
            } footer: {
                Text("记账数据只保存在本机（SQLCipher 加密），不上传任何服务器。")
            }
        }
        .listStyle(.insetGrouped)
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            notificationsAllowed = env.facade.notifications.hasPermission()
            appVersion = SettingsHubView.versionText()
        }
    }

    static func versionText() -> String {
        let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0"
        let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
        return "\(version) (\(build))"
    }
}

struct SettingRow: View {
    let icon: String
    let title: String
    var subtitle: String?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 15))
                .foregroundColor(BrandColor.primary)
                .frame(width: 24)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundColor(BrandColor.onSurface)
                if let subtitle = subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                }
            }
        }
    }
}

// ============================================================================
// P14 外观（PRD §11.7）
// ============================================================================

struct AppearanceView: View {
    @EnvironmentObject private var env: AppEnvironment

    var body: some View {
        List {
            Section {
                ForEach(ThemeMode.allCases) { mode in
                    Button {
                        env.setThemeMode(mode)
                    } label: {
                        HStack {
                            Text(mode.label).foregroundColor(BrandColor.onSurface)
                            Spacer()
                            if env.themeMode == mode {
                                Image(systemName: "checkmark")
                                    .foregroundColor(BrandColor.primary)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            } footer: {
                Text("深色模式覆盖全部页面，包括报表图表与记账键盘。选择会立即生效并跨启动保留。")
            }
        }
        .listStyle(.insetGrouped)
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle("外观")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// ============================================================================
// P16 关于（PRD §11.6 / §11.7）
// ============================================================================

struct AboutView: View {
    @EnvironmentObject private var env: AppEnvironment

    var body: some View {
        List {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    HStack(spacing: 12) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 14, style: .continuous)
                                .fill(BrandColor.primary)
                                .frame(width: 52, height: 52)
                            Image(systemName: "wallet.pass.fill")
                                .font(.system(size: 24))
                                .foregroundColor(BrandColor.onPrimary)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text("记账")
                                .font(.title3.weight(.semibold))
                                .foregroundColor(BrandColor.onSurface)
                            Text("版本 \(SettingsHubView.versionText())")
                                .font(.caption)
                                .foregroundColor(BrandColor.onSurfaceVariant)
                        }
                    }
                    Text("3 步记一笔，账本自己算。")
                        .font(.subheadline)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                }
                .padding(.vertical, 6)
            }

            Section("隐私") {
                PrivacyLine(text: "所有记账数据只写入本机加密数据库（SQLCipher），不联网、不上传。")
                PrivacyLine(text: "主密码只以「盐 + 校验值」的形式保存用于校验输入是否正确，明文本身不落盘。")
                PrivacyLine(text: "备份文件用主密码派生密钥加密后才落盘，忘记主密码无法找回。")
            }

            Section("加密方案") {
                PrivacyLine(text: "密钥派生：PBKDF2-HMAC-SHA256，600,000 次迭代，16 字节随机盐。")
                PrivacyLine(text: "内容加密：AES-256-GCM，12 字节随机 nonce，16 字节认证标签。")
                PrivacyLine(text: "备份文件格式：v1，固定 64 字节头（magic + version + kdf + 迭代次数 + 盐 + nonce）。")
            }

            Section("备份与恢复须知") {
                PrivacyLine(text: "导出的是「重新加密后的明文数据」，而不是直接拷贝加密库文件，因此换设备也能恢复。")
                PrivacyLine(text: "导入分「合并」「覆盖」两种：合并按主键去重、保留本地已有数据；覆盖会先清空本地账本。")
                PrivacyLine(text: "导入过程中任何失败都不会写入本地，现有数据保持不变。")
            }

            Section("开源声明") {
                PrivacyLine(text: "界面：Android Jetpack Compose / iOS SwiftUI。")
                PrivacyLine(text: "业务逻辑：Kotlin Multiplatform 共享模块（领域层不依赖任何平台 API）。")
                PrivacyLine(text: "存储：SQLDelight + SQLCipher；依赖注入：Koin；日期：kotlinx-datetime。")
            }
        }
        .listStyle(.insetGrouped)
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle("关于")
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct PrivacyLine: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.footnote)
            .foregroundColor(BrandColor.onSurfaceVariant)
    }
}
