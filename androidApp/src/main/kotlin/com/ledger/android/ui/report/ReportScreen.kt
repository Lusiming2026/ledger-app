package com.ledger.android.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ledger.android.ui.components.EmptyState
import com.ledger.android.ui.components.ErrorState
import com.ledger.android.ui.components.LedgerScreenScaffold
import com.ledger.android.ui.components.LoadingState
import com.ledger.android.ui.components.SectionCard
import com.ledger.android.ui.format.Fmt
import com.ledger.android.ui.format.parseHexColor
import com.ledger.android.ui.icons.LedgerIcons
import com.ledger.android.ui.theme.LedgerSemanticColors
import com.ledger.domain.model.CategoryShare
import com.ledger.domain.model.ReportData
import com.ledger.domain.model.ReportGranularity
import com.ledger.domain.model.ReportPeriod
import com.ledger.domain.model.TrendPoint

/**
 * P11 报表（PRD §7.2 / §4.2 / §11.5）。
 *
 * 口径（由 `GetReportUseCase` 保证）：`transfer` 不参与收支汇总/分类占比/趋势；
 * 分类占比只统计支出维度。
 *
 * 下钻（§11.5「图表支持点击下钻到对应流水明细」）：
 *  - 点扇区 / 点图例 → P12，带 `categoryId` + 当前周期区间；
 *  - 点趋势图 → P12，带那一天的开区间。
 */
@Composable
fun ReportScreen(
    onOpenTransactions: (fromMillis: Long, toMillis: Long, categoryId: String?, type: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: ReportViewModel = viewModel(factory = ReportViewModel.factory())
    val state by vm.state.collectAsState()

    LedgerScreenScaffold(
        modifier = modifier,
        title = "报表",
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PeriodSelector(
                granularity = state.granularity,
                period = state.period,
                atLatest = state.atLatest,
                onGranularity = vm::switchGranularity,
                onShift = vm::shift,
            )

            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(message = state.error!!, onRetry = vm::refresh)
                else -> {
                    val data = state.data
                    if (data == null || !data.hasData) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                        ) {
                            EmptyState(
                                text = "这个周期还没有收支记录。\n记一笔之后，这里会告诉你钱花在哪、以及趋势如何。",
                            )
                        }
                    } else {
                        ReportContent(
                            data = data,
                            onOpenCategory = { categoryId ->
                                onOpenTransactions(
                                    data.period.startMillis,
                                    data.period.endExclusiveMillis,
                                    categoryId,
                                    "expense",
                                )
                            },
                            onOpenDay = { point ->
                                onOpenTransactions(
                                    point.bucketStartMillis,
                                    point.bucketStartMillis + DAY_MILLIS,
                                    null,
                                    null,
                                )
                            },
                            onOpenAll = {
                                onOpenTransactions(
                                    data.period.startMillis,
                                    data.period.endExclusiveMillis,
                                    null,
                                    null,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 一天的毫秒数。趋势图的每个 bucket 就是一个自然日，日界由报表层按本地时区算好。 */
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

@Composable
private fun PeriodSelector(
    granularity: ReportGranularity,
    period: ReportPeriod?,
    atLatest: Boolean,
    onGranularity: (ReportGranularity) -> Unit,
    onShift: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        TabRow(
            selectedTabIndex = granularity.ordinal,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            ReportGranularity.entries.forEach { item ->
                Tab(
                    selected = item == granularity,
                    onClick = { onGranularity(item) },
                    text = { Text(if (item == ReportGranularity.MONTH) "按月" else "按周") },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { onShift(-1) }) {
                Icon(LedgerIcons.Back, contentDescription = "上一期")
            }
            Text(
                text = period?.label ?: "—",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { onShift(1) }, enabled = !atLatest) {
                Icon(
                    imageVector = LedgerIcons.Forward,
                    contentDescription = "下一期",
                    tint = if (atLatest) {
                        MaterialTheme.colorScheme.outlineVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun ReportContent(
    data: ReportData,
    onOpenCategory: (String) -> Unit,
    onOpenDay: (TrendPoint) -> Unit,
    onOpenAll: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("summary") {
            SummaryCard(data)
        }

        item("donut") {
            SectionCard(
                title = "支出构成",
                trailing = {
                    TextButton(onClick = onOpenAll) { Text("全部明细") }
                },
            ) {
                if (data.expenseShares.isEmpty()) {
                    Text(
                        text = "这个周期没有支出记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    return@SectionCard
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    DonutChart(
                        slices = data.expenseShares.map { share ->
                            ChartSlice(
                                label = share.name,
                                color = parseHexColor(share.color, LedgerSemanticColors.Expense),
                                valueCents = share.amountCents,
                            )
                        },
                        centerLabel = "总支出",
                        centerValue = Fmt.money(data.summary.expenseCents),
                        diameter = 168.dp,
                        onSliceClick = { index ->
                            data.expenseShares.getOrNull(index)?.categoryId?.let(onOpenCategory)
                        },
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        // 只列前 6 个，剩下的合并说明——避免图例比图还长
                        data.expenseShares.take(6).forEach { share ->
                            ShareLegendRow(
                                share = share,
                                onClick = { share.categoryId?.let(onOpenCategory) },
                            )
                        }
                        if (data.expenseShares.size > 6) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "另有 ${data.expenseShares.size - 6} 个分类…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item("trend") {
            SectionCard(title = "收支趋势（点数据点看当天明细）") {
                Column {
                    TrendChart(
                        points = data.trend,
                        onPointClick = onOpenDay,
                    )
                    Spacer(Modifier.height(4.dp))
                    TrendAxisLabels(points = data.trend)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LegendDot(color = LedgerSemanticColors.Expense)
                        Text(
                            text = "支出",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(16.dp))
                        LegendDot(color = LedgerSemanticColors.Income)
                        Text(
                            text = "收入",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item("note") {
            Text(
                text = "报表口径：转账不计入收支统计与分类占比，只改变账户余额（PRD §7.2）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryCard(data: ReportData) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "结余",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = Fmt.signedMoney(data.summary.balanceCents),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column {
                    Text(
                        text = "收入",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                    Text(
                        text = Fmt.money(data.summary.incomeCents),
                        style = MaterialTheme.typography.titleMedium,
                        color = LedgerSemanticColors.Income,
                    )
                }
                Column {
                    Text(
                        text = "支出",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    )
                    Text(
                        text = Fmt.money(data.summary.expenseCents),
                        style = MaterialTheme.typography.titleMedium,
                        color = LedgerSemanticColors.Expense,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShareLegendRow(share: CategoryShare, onClick: () -> Unit) {
    val color = parseHexColor(share.color, LedgerSemanticColors.Expense)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(color = color)
        Spacer(Modifier.width(6.dp))
        Text(
            text = share.name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = Fmt.money(share.amountCents),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = share.percentText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color),
    )
}
