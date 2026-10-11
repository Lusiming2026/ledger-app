package com.ledger.data.mapper

import com.ledger.domain.model.Account
import com.ledger.domain.model.AccountType
import com.ledger.domain.model.Budget
import com.ledger.domain.model.BudgetNotification
import com.ledger.domain.model.Category
import com.ledger.domain.model.CategoryKind
import com.ledger.domain.model.Reconciliation
import com.ledger.domain.model.Transaction
import com.ledger.domain.model.TransactionType

// SQLDelight 生成的行类型与领域模型同名（都在 com.ledger.data.db 下），
// 这里统一起别名，避免两个 `Account` 打架。
import com.ledger.data.db.Account as AccountRow
import com.ledger.data.db.Budget as BudgetRow
import com.ledger.data.db.Budget_notification as BudgetNotificationRow
import com.ledger.data.db.Category as CategoryRow
import com.ledger.data.db.Reconciliation as ReconciliationRow
import com.ledger.data.db.Transaction as TransactionRow

/*
 * 行 ↔ 领域模型映射（T08）。
 *
 * 约定：
 *  - 布尔列在 DDL 里是 INTEGER 0/1，生成类型为 Long，此处转 Boolean；
 *  - 枚举列在 DDL 里是 TEXT，落库值是枚举的 `dbValue`；
 *  - 金额列一律 Int64「分」，不做任何单位换算（PRD §11.6「全程禁用浮点」）。
 */

internal fun AccountRow.toDomain(): Account = Account(
    accountId = account_id,
    name = name,
    type = AccountType.fromDb(type),
    initialBalance = initial_balance,
    currentBalance = current_balance,
    sortOrder = sort_order.toInt(),
    isArchived = is_archived != 0L,
    createdAt = created_at,
    updatedAt = updated_at,
)

internal fun CategoryRow.toDomain(): Category = Category(
    categoryId = category_id,
    name = name,
    kind = CategoryKind.fromDb(kind),
    parentId = parent_id,
    icon = icon,
    color = color,
    isSystem = is_system != 0L,
    sortOrder = sort_order.toInt(),
    isArchived = is_archived != 0L,
)

internal fun TransactionRow.toDomain(): Transaction = Transaction(
    transactionId = transaction_id,
    type = TransactionType.fromDb(type),
    amount = amount,
    categoryId = category_id,
    fromAccountId = from_account_id,
    toAccountId = to_account_id,
    note = note,
    occurredAt = occurred_at,
    createdAt = created_at,
    updatedAt = updated_at,
    deletedAt = deleted_at,
)

internal fun BudgetRow.toDomain(): Budget = Budget(
    budgetId = budget_id,
    amount = amount,
    year = year.toInt(),
    month = month.toInt(),
    createdAt = created_at,
    updatedAt = updated_at,
)

internal fun BudgetNotificationRow.toDomain(): BudgetNotification = BudgetNotification(
    notificationId = notification_id,
    budgetId = budget_id,
    threshold = threshold.toInt(),
    periodKey = period_key,
    notifiedAt = notified_at,
)

internal fun ReconciliationRow.toDomain(): Reconciliation = Reconciliation(
    reconciliationId = reconciliation_id,
    accountId = account_id,
    balance = balance,
    reconciledAt = reconciled_at,
    note = note,
)
