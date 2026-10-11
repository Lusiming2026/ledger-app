import SwiftUI
import Shared

// ============================================================================
// P05 分类管理（PRD §4.2 / §11.2）
// ============================================================================

/// 分类列表。支出 / 收入两组分别展示与排序——`sort_order` 是**按 kind 各自一套**的
/// （见 `CategoryRepository.nextSortOrder(kind)`），所以拖拽也必须在组内进行。
///
/// 排序交互用 SwiftUI 原生的 `.onMove` + `EditButton()`，而不是长按拖拽自定义实现：
/// iOS 用户对「编辑模式下拖动把手」有稳定预期，Android 侧长按拖拽是那边的平台习惯。
struct CategoryListScreen: View {
    @EnvironmentObject private var env: AppEnvironment
    let onChange: () -> Void

    @State private var categories: [Category] = []
    @State private var loading = true
    @State private var message: String?
    @State private var subscription: Subscription?

    @State private var showKindDialog = false
    @State private var newKindCode: String?
    @State private var editTarget: CategoryTarget?

    private var expenseCategories: [Category] {
        categories.filter { $0.kind.dbValue == "expense" }
    }

    private var incomeCategories: [Category] {
        categories.filter { $0.kind.dbValue == "income" }
    }

    var body: some View {
        Group {
            if loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    if let message = message {
                        Section {
                            MessageBanner(text: message)
                                .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 0, trailing: 16))
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                        }
                    }

                    section(title: "支出分类", items: expenseCategories)
                    section(title: "收入分类", items: incomeCategories)

                    Section {
                        Text("归档后不再出现在记账页，但历史流水的分类名保持不变。预置分类不可删除，只能归档。")
                            .font(.caption)
                            .foregroundColor(BrandColor.onSurfaceVariant)
                    }
                }
                .listStyle(.insetGrouped)
            }
        }
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle("分类管理")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) { EditButton() }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showKindDialog = true
                } label: {
                    Image(systemName: "plus")
                }
            }
        }
        .confirmationDialog("新建分类", isPresented: $showKindDialog, titleVisibility: .visible) {
            Button("支出分类") { newKindCode = "expense" }
            Button("收入分类") { newKindCode = "income" }
            Button("取消", role: .cancel) {}
        } message: {
            Text("分类的收支类型创建后不可修改。")
        }
        .sheet(item: $editTarget) { target in
            CategoryEditScreen(
                categoryId: target.categoryId,
                kindCode: target.kindCode
            ) {
                editTarget = nil
                onChange()
            }
            .environmentObject(env)
        }
        .onAppear(perform: subscribe)
        .onDisappear { subscription?.cancel(); subscription = nil }
    }

    private func section(title: String, items: [Category]) -> some View {
        Section(title) {
            if items.isEmpty {
                Text("暂无").font(.caption).foregroundColor(BrandColor.onSurfaceVariant)
            } else {
                ForEach(items, id: \.categoryId) { category in
                    Button {
                        editTarget = CategoryTarget(
                            categoryId: category.categoryId,
                            kindCode: category.kind.dbValue
                        )
                    } label: {
                        CategoryRow(category: category)
                    }
                    .buttonStyle(.plain)
                    .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                        Button {
                            Task { await setArchived(category, archived: !category.isArchived) }
                        } label: {
                            Label(
                                category.isArchived ? "取消归档" : "归档",
                                systemImage: category.isArchived ? "tray.and.arrow.up" : "archivebox"
                            )
                        }
                        .tint(BrandColor.warningTint)
                    }
                }
                .onMove { indices, newOffset in
                    // 先在本地重排（即时反馈），再把整组新的 sort_order 落库
                    var reordered = items
                    reordered.move(fromOffsets: indices, toOffset: newOffset)
                    Task { await applyReorder(reordered) }
                }
            }
        }
    }

    private func subscribe() {
        guard subscription == nil else { return }
        subscription = env.facade.observeCategories { value in
            categories = value
            loading = false
        }
    }

    private func setArchived(_ category: Category, archived: Bool) async {
        do {
            try await env.facade.setCategoryArchived(
                categoryId: category.categoryId,
                archived: archived
            )
            message = archived ? "已归档，记账时不再出现" : "已取消归档"
            onChange()
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func applyReorder(_ ordered: [Category]) async {
        do {
            try await env.facade.reorderCategories(orderedIds: ordered.map { $0.categoryId })
        } catch {
            message = ledgerMessage(error)
        }
    }
}

/// 分类行：图标 + 名称 + 「预置 / 已归档」标签。
struct CategoryRow: View {
    let category: Category

    var body: some View {
        HStack(spacing: 12) {
            IconBadge(
                systemName: LedgerIcon.symbol(for: category.icon),
                tint: Color(hex: category.color),
                size: 34
            )
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(category.name)
                        .font(.subheadline)
                        .foregroundColor(BrandColor.onSurface)
                    if category.isSystem { Tag(text: "预置", tint: BrandColor.outline) }
                    if category.isArchived { Tag(text: "已归档", tint: BrandColor.warningTint) }
                }
                Text(category.kind.dbValue == "expense" ? "支出" : "收入")
                    .font(.caption2)
                    .foregroundColor(BrandColor.onSurfaceVariant)
            }
            Spacer()
        }
        .padding(.vertical, 6)
        .contentShape(Rectangle())
    }
}

struct Tag: View {
    let text: String
    let tint: Color

    var body: some View {
        Text(text)
            .font(.caption2)
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(tint.opacity(0.16))
            .foregroundColor(tint)
            .clipShape(Capsule())
    }
}

struct CategoryTarget: Identifiable {
    let id = UUID()
    let categoryId: String
    let kindCode: String
}

// ============================================================================
// P06 分类新建 / 编辑（PRD §11.2）
// ============================================================================

struct CategoryEditScreen: View {
    @EnvironmentObject private var env: AppEnvironment
    @Environment(\.dismiss) private var dismiss

    /// nil = 新建
    let categoryId: String?
    /// 新建时的收支类型；编辑模式加载后以库中值为准
    let kindCode: String
    let onDone: () -> Void

    @State private var loading = true
    @State private var saving = false
    @State private var message: String?

    @State private var name = ""
    @State private var currentKindCode = "expense"
    @State private var icon = "more_horiz"
    @State private var color = "#42A5F5"

    @State private var isSystem = false
    @State private var isArchived = false
    @State private var referencedCount = 0
    @State private var migrationCandidates: [Category] = []

    @State private var alert: ConfirmSpec?
    @State private var showMigrationPicker = false

    private var isEdit: Bool { categoryId != nil }

    var body: some View {
        Group {
            if loading {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Form {
                    if let message = message {
                        Section { MessageBanner(text: message) }
                    }

                    Section("预览") {
                        HStack(spacing: 12) {
                            IconBadge(
                                systemName: LedgerIcon.symbol(for: icon),
                                tint: Color(hex: color),
                                size: 40
                            )
                            Text(name.isEmpty ? "分类名" : name)
                                .font(.headline)
                                .foregroundColor(name.isEmpty
                                                 ? BrandColor.onSurfaceVariant
                                                 : BrandColor.onSurface)
                            Spacer()
                        }
                    }

                    Section("名称") {
                        TextField("比如「宠物」", text: nameBinding)
                    }

                    if !isEdit {
                        Section("收支类型") {
                            Picker("收支类型", selection: $currentKindCode) {
                                Text("支出").tag("expense")
                                Text("收入").tag("income")
                            }
                            .pickerStyle(.segmented)
                        }
                    }

                    Section("图标") {
                        LazyVGrid(
                            columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 6),
                            spacing: 10
                        ) {
                            ForEach(LedgerIcon.presets, id: \.name) { preset in
                                Button {
                                    icon = preset.name
                                } label: {
                                    ZStack {
                                        Circle()
                                            .fill(icon == preset.name
                                                  ? Color(hex: color).opacity(0.2)
                                                  : BrandColor.surfaceContainerHigh)
                                        Image(systemName: preset.symbol)
                                            .font(.system(size: 16))
                                            .foregroundColor(icon == preset.name
                                                             ? Color(hex: color)
                                                             : BrandColor.onSurfaceVariant)
                                    }
                                    .frame(height: 40)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(.vertical, 4)
                    }

                    Section("颜色") {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 10) {
                                ForEach(LedgerIcon.colorPresets, id: \.self) { hex in
                                    Button {
                                        color = hex
                                    } label: {
                                        ZStack {
                                            Circle().fill(Color(hex: hex))
                                            if color == hex {
                                                Circle()
                                                    .stroke(BrandColor.onSurface, lineWidth: 2)
                                                    .padding(-3)
                                            }
                                        }
                                        .frame(width: 30, height: 30)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.vertical, 6)
                        }
                    }

                    if isEdit {
                        Section {
                            if isSystem {
                                Text("预置分类不可删除，可改为归档。")
                                    .font(.caption)
                                    .foregroundColor(BrandColor.onSurfaceVariant)
                            } else {
                                Button(role: .destructive) {
                                    Task { await prepareDelete() }
                                } label: {
                                    Text("删除分类")
                                }
                                .disabled(saving)
                            }
                            Button {
                                Task { await toggleArchived() }
                            } label: {
                                Text(isArchived ? "取消归档" : "归档该分类")
                            }
                            .disabled(saving)
                        } footer: {
                            Text("归档后不再出现在记账页；历史流水的分类名不变。")
                        }
                    }
                }
            }
        }
        .background(BrandColor.background.ignoresSafeArea())
        .navigationTitle(isEdit ? "编辑分类" : "新建分类")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("保存") { Task { await save() } }
                    .disabled(!canSave)
            }
        }
        .alert(item: $alert) { spec in
            Alert(
                title: Text(spec.title),
                message: Text(spec.message),
                primaryButton: .destructive(Text(spec.confirmLabel), action: spec.onConfirm),
                secondaryButton: .cancel(Text("取消"))
            )
        }
        .sheet(isPresented: $showMigrationPicker) {
            MigrationTargetSheet(
                candidates: migrationCandidates,
                referencedCount: referencedCount
            ) { targetId in
                showMigrationPicker = false
                Task { await performDelete(migrateTo: targetId) }
            }
            .environmentObject(env)
        }
        .task { await load() }
    }

    private var canSave: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty && !saving }

    private var nameBinding: Binding<String> {
        Binding(get: { name }, set: { name = String($0.prefix(12)) })
    }

    private func load() async {
        defer { loading = false }
        if let id = categoryId {
            // 编辑：读出现有值 + 引用条数 + 迁移候选
            do {
                guard let existing = try await env.facade.categoryById(categoryId: id) else {
                    message = "分类不存在"
                    return
                }
                name = existing.name
                currentKindCode = existing.kind.dbValue
                icon = existing.icon
                color = existing.color
                isSystem = existing.isSystem
                isArchived = existing.isArchived
                referencedCount = try await env.facade.countCategoryRefs(categoryId: id)
                migrationCandidates = try await env.facade
                    .activeCategoriesByKindCode(kindCode: existing.kind.dbValue)
                    .filter { $0.categoryId != id }
            } catch {
                message = ledgerMessage(error)
            }
        } else {
            currentKindCode = kindCode
        }
    }

    private func save() async {
        guard canSave else { return }
        saving = true
        defer { saving = false }
        do {
            if let id = categoryId {
                // 收支类型不可改：改了会让历史流水违反 R-CAT-04
                try await env.facade.updateCategory(
                    categoryId: id,
                    name: name,
                    icon: icon,
                    color: color
                )
            } else {
                _ = try await env.facade.createCategory(
                    categoryId: Fmt.newCategoryId(),
                    name: name,
                    kindCode: currentKindCode,
                    icon: icon,
                    color: color
                )
            }
            onDone()
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func toggleArchived() async {
        guard let id = categoryId else { return }
        saving = true
        defer { saving = false }
        do {
            try await env.facade.setCategoryArchived(categoryId: id, archived: !isArchived)
            isArchived.toggle()
            message = isArchived ? "已归档，记账时不再出现" : "已取消归档"
        } catch {
            message = ledgerMessage(error)
        }
    }

    /// 删除前必须先弄清「有没有历史流水挂着这个分类」（PRD §11.2 第 4 条）。
    private func prepareDelete() async {
        guard let id = categoryId else { return }
        do {
            referencedCount = try await env.facade.countCategoryRefs(categoryId: id)
            if referencedCount > 0 {
                if migrationCandidates.isEmpty {
                    message = "该分类下有 \(referencedCount) 笔流水，但没有可迁移的同类型分类，请先新建一个。"
                    return
                }
                showMigrationPicker = true
            } else {
                alert = ConfirmSpec(
                    title: "删除分类「\(name)」？",
                    message: "该分类下没有流水，将直接删除。",
                    onConfirm: { Task { await performDelete(migrateTo: nil) } }
                )
            }
        } catch {
            message = ledgerMessage(error)
        }
    }

    private func performDelete(migrateTo: String?) async {
        guard let id = categoryId else { return }
        saving = true
        defer { saving = false }
        do {
            let migrated = try await env.facade.deleteCategory(
                categoryId: id,
                migrateToCategoryId: migrateTo
            )
            message = "已删除，迁移了 \(migrated) 笔流水"
            onDone()
        } catch {
            message = ledgerMessage(error)
        }
    }
}

/// 删除分类时的迁移目标选择。
///
/// 这一步不能省：PRD §11.2 明确要求「删除自定义分类时必须指定历史流水的迁移目标」，
/// 否则历史流水会变成悬空引用（备份校验里会被当成 `DANGLING_REFERENCE` 拒收）。
struct MigrationTargetSheet: View {
    @Environment(\.dismiss) private var dismiss

    let candidates: [Category]
    let referencedCount: Int
    let onConfirm: (String) -> Void

    @State private var selectedId: String?

    var body: some View {
        NavigationView {
            List {
                Section {
                    Text("该分类下有 \(referencedCount) 笔流水，请选择迁移到哪个分类。")
                        .font(.subheadline)
                        .foregroundColor(BrandColor.onSurfaceVariant)
                }
                ForEach(candidates, id: \.categoryId) { category in
                    Button {
                        selectedId = category.categoryId
                    } label: {
                        HStack(spacing: 12) {
                            IconBadge(
                                systemName: LedgerIcon.symbol(for: category.icon),
                                tint: Color(hex: category.color),
                                size: 32
                            )
                            Text(category.name)
                                .foregroundColor(BrandColor.onSurface)
                            Spacer()
                            if selectedId == category.categoryId {
                                Image(systemName: "checkmark")
                                    .foregroundColor(BrandColor.primary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .navigationTitle("迁移到")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("迁移并删除") {
                        if let id = selectedId { onConfirm(id) }
                    }
                    .disabled(selectedId == nil)
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}
