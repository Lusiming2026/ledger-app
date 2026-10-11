package com.ledger.android.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

/**
 * 长按拖拽排序列表（PRD §11.2「拖拽排序」/ §11.3「账户手动排序」）。
 *
 * ### 为什么不是 `LazyColumn` + 拖拽库
 * 分类最多二三十条、账户个位数，完全不需要懒加载；而 LazyColumn 的拖拽要自己维护
 * `LazyListState`、可视区偏移与 item key 映射，复杂度高且容易在滚动边界出 bug。
 * 这里用固定行高的普通 Column：拖拽只改 `translationY`，松手时按「位移 / 行高」
 * 折算目标下标，一次性把新顺序交给上层持久化（`reorder(orderedIds)`）。
 *
 * ### 约束
 * 所有行必须**等高**（[itemHeight]），否则位移折算会错位。
 * 排序结果只在松手时产生一次回调，拖动过程中不改数据，避免每帧写库。
 */
@Composable
fun <T> DragReorderColumn(
    items: List<T>,
    itemHeight: Dp,
    keyOf: (T) -> String,
    onReorder: (List<T>) -> Unit,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, isDragging: Boolean) -> Unit,
) {
    val itemHeightPx = with(LocalDensity.current) { itemHeight.toPx() }

    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    /** 把 [from] 位置的元素移动到 [to]，其余顺延。 */
    fun move(from: Int, to: Int) {
        if (from == to || from !in items.indices || to !in items.indices) return
        val mutable = items.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        onReorder(mutable)
    }

    androidx.compose.foundation.layout.Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            val key = keyOf(item)
            val isDragging = key == draggingKey

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        if (isDragging) translationY = dragOffsetY
                    }
                    .alpha(if (isDragging) 0.92f else 1f)
                    .pointerInput(key, items) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingKey = key
                                dragOffsetY = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetY += dragAmount.y
                            },
                            onDragEnd = {
                                val steps = (dragOffsetY / itemHeightPx).roundToInt()
                                val target = (index + steps).coerceIn(0, items.lastIndex.coerceAtLeast(0))
                                draggingKey = null
                                dragOffsetY = 0f
                                move(index, target)
                            },
                            onDragCancel = {
                                draggingKey = null
                                dragOffsetY = 0f
                            },
                        )
                    },
            ) {
                itemContent(item, isDragging)
            }
        }
    }
}

/** 拖拽手柄（三条横线）。放在行尾提示「这里可以长按拖动」。 */
@Composable
fun DragHandle(modifier: Modifier = Modifier) {
    androidx.compose.material3.Icon(
        imageVector = com.ledger.android.ui.icons.LedgerIcons.DragHandleIcon,
        contentDescription = "长按拖动排序",
        tint = androidx.compose.material3.MaterialTheme.colorScheme.outline,
        modifier = modifier,
    )
}
