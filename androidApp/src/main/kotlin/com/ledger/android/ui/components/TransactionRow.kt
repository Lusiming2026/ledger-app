package com.ledger.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ledger.android.ui.format.Fmt
import com.ledger.android.ui.format.parseHexColor
import com.ledger.android.ui.icons.LedgerIcons
import com.ledger.android.ui.theme.LedgerSemanticColors
import com.ledger.domain.model.TransactionDetail
import com.ledger.domain.model.TransactionType

/**
 * 流水行的展示口径统一收在这里，避免「首页用了备注、明细页用了分类名」这类不一致。
 */

/** 主标题：优先展示用户写的备注，没写才退回分类名（转账显示「转账」）。 */
fun TransactionDetail.displayTitle(): String =
    transaction.note?.takeIf { it.isNotBlank() } ?: categoryLabelForExport

/**
 * 副标题：
 *  - 支出 `餐饮 · 现金`
 *  - 收入 `生活费 · 银行卡`
 *  - 转账 `现金 → 银行卡`
 */
fun TransactionDetail.displaySubtitle(): String = when (transaction.type) {
    TransactionType.EXPENSE -> listOfNotNull(
        categoryName, fromAccountName,
    ).joinToString(" · ")

    TransactionType.INCOME -> listOfNotNull(
        categoryName, toAccountName,
    ).joinToString(" · ")

    TransactionType.TRANSFER -> "${fromAccountName ?: "?"} → ${toAccountName ?: "?"}"
}

/** 金额文本与颜色：支出带 `-`、收入带 `+`、转账只显示金额（不参与收支）。 */
fun TransactionDetail.amountText(): String = when (transaction.type) {
    TransactionType.EXPENSE -> "-" + Fmt.money(transaction.amount)
    TransactionType.INCOME -> "+" + Fmt.money(transaction.amount)
    TransactionType.TRANSFER -> Fmt.money(transaction.amount)
}

@Composable
fun TransactionRow(
    detail: TransactionDetail,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    showDate: Boolean = false,
) {
    val type = detail.transaction.type
    val accent = when (type) {
        TransactionType.EXPENSE -> LedgerSemanticColors.Expense
        TransactionType.INCOME -> LedgerSemanticColors.Income
        TransactionType.TRANSFER -> LedgerSemanticColors.Transfer
    }
    val badgeColor = parseHexColor(detail.categoryColor, accent)

    ListRow(
        modifier = modifier,
        onClick = onClick,
        leading = {
            CircleIconBadge(
                icon = if (type == TransactionType.TRANSFER) {
                    LedgerIcons.transactionType(type)
                } else {
                    LedgerIcons.category(detail.categoryIcon)
                },
                tint = badgeColor,
                size = 38.dp,
            )
        },
        trailing = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = detail.amountText(),
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    fontWeight = FontWeight.SemiBold,
                )
                if (showDate) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = Fmt.shortDate(detail.transaction.occurredAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (detail.transaction.isDeleted) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "已删除",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
    ) {
        Text(
            text = detail.displayTitle(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = detail.displaySubtitle(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 未拼装分类名的简化行（首页「最近流水」用）。
 *
 * 首页只订阅 `TransactionRepository.observeRecent`（不额外查分类表），
 * 换取「保存后 < 200ms 刷新」（PRD §11.1）；因此这里只能显示备注/类型 + 账户名，
 * 这正是「流水保存后立即可见」与「分类名要多一次查询」之间的取舍——
 * 点进明细页（P12）才做完整拼装。
 */
@Composable
fun SimpleTransactionRow(
    transaction: com.ledger.domain.model.Transaction,
    accountName: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val type = transaction.type
    val accent = when (type) {
        TransactionType.EXPENSE -> LedgerSemanticColors.Expense
        TransactionType.INCOME -> LedgerSemanticColors.Income
        TransactionType.TRANSFER -> LedgerSemanticColors.Transfer
    }
    val title = transaction.note?.takeIf { it.isNotBlank() }
        ?: when (type) {
            TransactionType.EXPENSE -> "支出"
            TransactionType.INCOME -> "收入"
            TransactionType.TRANSFER -> "转账"
        }
    val amountText = when (type) {
        TransactionType.EXPENSE -> "-" + Fmt.money(transaction.amount)
        TransactionType.INCOME -> "+" + Fmt.money(transaction.amount)
        TransactionType.TRANSFER -> Fmt.money(transaction.amount)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconBadge(
            icon = LedgerIcons.transactionType(type),
            tint = accent,
            size = 32.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (accountName != null) {
                Text(
                    text = accountName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = amountText,
            style = MaterialTheme.typography.bodyLarge,
            color = accent,
            fontWeight = FontWeight.Medium,
        )
    }
}
