package com.ledger.android.ui.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledger.android.ui.theme.LedgerSemanticColors
import com.ledger.domain.model.TrendPoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 饼图/环形图的一段。[valueCents] 必须为正。 */
data class ChartSlice(
    val label: String,
    val color: Color,
    val valueCents: Long,
)

/**
 * 分类占比环形图（PRD §7.2 / §4.2 P11「分类占比饼图」）。
 *
 * 用环形而不是实心饼：中间空出来放「总支出」，比在图上标注更清晰，
 * 也顺带避免小占比扇区被压缩到看不见（占比 < 1% 的会被合并进「其它」的逻辑交给上层）。
 *
 * 点击扇区 → [onSliceClick]（PRD §11.5「图表支持点击下钻到对应流水明细」）。
 */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    centerLabel: String,
    centerValue: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 180.dp,
    onSliceClick: ((Int) -> Unit)? = null,
) {
    val total = slices.sumOf { it.valueCents }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(diameter)
                .then(
                    if (onSliceClick == null || total <= 0L) {
                        Modifier
                    } else {
                        Modifier.pointerInput(slices) {
                            detectTapGestures { offset ->
                                val index = hitSlice(offset, size.width.toFloat(), slices, total)
                                if (index >= 0) onSliceClick(index)
                            }
                        }
                    },
                ),
        ) {
            val strokeWidth = size.minDimension * 0.20f
            val inset = strokeWidth / 2f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)

            if (total <= 0L || slices.isEmpty()) {
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeWidth),
                )
                return@Canvas
            }

            // 多段之间留 1.5° 缝隙；只有一段时画满整圈
            val gap = if (slices.size > 1) 1.5f else 0f
            var start = -90f
            slices.forEach { slice ->
                val sweep = slice.valueCents.toFloat() / total.toFloat() * 360f
                drawArc(
                    color = slice.color,
                    startAngle = start + gap / 2f,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = centerValue,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 把点击坐标换算成扇区下标；点到环外或空隙返回 -1。 */
private fun hitSlice(
    offset: Offset,
    canvasWidth: Float,
    slices: List<ChartSlice>,
    total: Long,
): Int {
    val radius = canvasWidth / 2f
    val dx = offset.x - radius
    val dy = offset.y - radius
    val distance = hypot(dx, dy)
    val strokeWidth = canvasWidth * 0.20f
    val outer = radius
    val inner = radius - strokeWidth
    if (distance < inner || distance > outer) return -1

    // atan2 给出 -180..180（0 = 正右方），换算成与 drawArc 一致的「-90 起、顺时针」
    var angle = atan2(dy, dx) * 180f / PI.toFloat()
    angle = (angle + 90f + 360f) % 360f

    var accumulated = 0f
    slices.forEachIndexed { index, slice ->
        val sweep = slice.valueCents.toFloat() / total.toFloat() * 360f
        if (angle >= accumulated && angle < accumulated + sweep) return index
        accumulated += sweep
    }
    return slices.lastIndex
}

/**
 * 收支趋势图（PRD §4.2 P11「收支趋势图」）。
 *
 * 支出用实心面积（视觉重量大，用户最关心「钱花哪去了」），
 * 收入用细线叠在上层。点击任意位置 → 定位到最近的日期并回调（PRD §11.5 下钻）。
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 160.dp,
    onPointClick: ((TrendPoint) -> Unit)? = null,
) {
    if (points.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "这段时间还没有数据",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val expenseColor = LedgerSemanticColors.Expense
    val incomeColor = LedgerSemanticColors.Income

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (onPointClick == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(points) {
                        detectTapGestures { offset ->
                            val step = size.width.toFloat() / points.size
                            val index = (offset.x / step).toInt().coerceIn(0, points.lastIndex)
                            onPointClick(points[index])
                        }
                    }
                },
            ),
    ) {
        val maxValue = points.maxOf { point ->
            maxOf(point.expenseCents, point.incomeCents)
        }.coerceAtLeast(1L)

        // 顶部留 8% 余量，避免峰值贴边
        val chartHeight = size.height * 0.92f
        val bottom = size.height

        // 基线 + 中线
        listOf(0f, 0.5f, 1f).forEach { fraction ->
            val y = bottom - chartHeight * fraction
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }

        val step = size.width / points.size.toFloat()
        fun x(index: Int) = step * index + step / 2f
        fun y(value: Long) = bottom - chartHeight * (value.toFloat() / maxValue.toFloat())

        // 支出面积
        val area = Path().apply {
            moveTo(x(0), bottom)
            points.forEachIndexed { index, point -> lineTo(x(index), y(point.expenseCents)) }
            lineTo(x(points.lastIndex), bottom)
            close()
        }
        drawPath(path = area, color = expenseColor.copy(alpha = 0.18f))

        // 支出折线
        val expenseLine = Path().apply {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(x(index), y(point.expenseCents))
                else lineTo(x(index), y(point.expenseCents))
            }
        }
        drawPath(
            path = expenseLine,
            color = expenseColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
        )

        // 收入折线
        val incomeLine = Path().apply {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(x(index), y(point.incomeCents))
                else lineTo(x(index), y(point.incomeCents))
            }
        }
        drawPath(
            path = incomeLine,
            color = incomeColor,
            style = Stroke(width = 2f, cap = StrokeCap.Round),
        )

        // 数据点：只在点数不多时画，避免变成一条毛边
        if (points.size <= 12) {
            points.forEachIndexed { index, point ->
                drawCircle(
                    color = expenseColor,
                    radius = 3.5f,
                    center = Offset(x(index), y(point.expenseCents)),
                )
            }
        }
    }
}

/** 趋势图下方的 X 轴标签：只标首/中/尾，避免文字挤成一团。 */
@Composable
fun TrendAxisLabels(points: List<TrendPoint>, modifier: Modifier = Modifier) {
    if (points.isEmpty()) return
    val picks = buildList {
        add(points.first())
        if (points.size > 2) add(points[points.size / 2])
        if (points.size > 1) add(points.last())
    }
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth(),
    ) {
        picks.forEachIndexed { index, point ->
            Text(
                text = point.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = when (index) {
                    0 -> androidx.compose.ui.text.style.TextAlign.Start
                    picks.lastIndex -> androidx.compose.ui.text.style.TextAlign.End
                    else -> androidx.compose.ui.text.style.TextAlign.Center
                },
            )
        }
    }
}

/** 供图例列表用：把占比折算成整数百分比文本（与 `CategoryShare.percentText` 口径一致）。 */
fun ratioPercent(ratio: Double): String = "${(ratio * 100.0).roundToInt()}%"
