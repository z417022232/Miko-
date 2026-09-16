package com.example.worktimetracker.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.ui.theme.AppTheme

/**
 * 月度柱状图：12 根柱子，手绘 Canvas（**不引第三方图表库**，用户口径：零新依赖）。
 *
 * 交互按用户要求做成「可点击 / 可滑动看数据」：
 * - 点任一根柱子 → 回调该月下标（0..11）；
 * - 横向拖动时连续回调（手指滑过哪个月就报哪个月），所以"滑动看数据"不用抬起手指。
 *
 * 数值**不画在柱子上** —— 12 根柱子在手机上放不下数字，挤在一起反而看不清。
 * 选中月的数据由调用方显示在卡片标题行（`9 月 · 176h 00m`），反馈一定清晰。
 *
 * @param values 12 个月的值（0 = 该月没有数据，画一个小墩而不是不画，让"空月"和"没这个月"区分开）
 * @param selectedIndex 当前选中的下标，null = 没选中；选中的柱子用实色，其余半透明
 */
@Composable
internal fun MonthBarChart(
    values: List<Int>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 116.dp
) {
    val divider = AppTheme.colors.divider
    val muted = AppTheme.colors.muted
    val accent = AppTheme.colors.blue

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(values) {
                    detectTapGestures { offset -> onSelect(indexAt(offset.x, size.width, values.size)) }
                }
                .pointerInput(values) {
                    detectHorizontalDragGestures { change, _ ->
                        onSelect(indexAt(change.position.x, size.width, values.size))
                    }
                }
        ) {
            val count = values.size.coerceAtLeast(1)
            val slot = size.width / count
            val barWidth = slot * 0.5f
            val maxValue = (values.maxOrNull() ?: 0).coerceAtLeast(1)
            val baseline = size.height - 2f

            drawLine(
                color = divider,
                start = Offset(0f, baseline),
                end = Offset(size.width, baseline),
                strokeWidth = 1.5f
            )

            values.forEachIndexed { index, value ->
                val center = slot * index + slot / 2f
                if (value <= 0) {
                    // 空月：一个 3dp 的短墩。有墩 = 这个月确实没数据，不是图没画出来
                    drawRoundRect(
                        color = muted.copy(alpha = 0.35f),
                        topLeft = Offset(center - barWidth / 2f, baseline - 3f),
                        size = Size(barWidth, 3f),
                        cornerRadius = CornerRadius(1.5f, 1.5f)
                    )
                } else {
                    val barHeight = (size.height - 8f) * (value.toFloat() / maxValue)
                    drawRoundRect(
                        color = if (index == selectedIndex) color else color.copy(alpha = 0.38f),
                        topLeft = Offset(center - barWidth / 2f, baseline - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            (1..values.size).forEach { month ->
                val selected = selectedIndex == month - 1
                Text(
                    "$month",
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) accent else muted
                )
            }
        }
    }
}

/** 手指 x 坐标落在第几根柱子上（0-based）。 */
private fun indexAt(x: Float, width: Int, count: Int): Int {
    if (width <= 0 || count <= 0) return 0
    val slot = width / count.toFloat()
    return (x / slot).toInt().coerceIn(0, count - 1)
}
