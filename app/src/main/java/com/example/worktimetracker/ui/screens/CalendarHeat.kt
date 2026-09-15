package com.example.worktimetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import com.example.worktimetracker.ui.PayrollPresenter
import com.example.worktimetracker.ui.app.MonthProjection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.ui.DayCellModel
import com.example.worktimetracker.ui.HeatLevel
import com.example.worktimetracker.ui.MonthSummary
import com.example.worktimetracker.ui.TodayStatusPresenter
import com.example.worktimetracker.domain.payroll.PayrollBreakdown
import com.example.worktimetracker.ui.theme.AppTheme

/*
 * 日历页（界面稿 v4 · 屏 01）的展示组件。
 *
 * 分工：CalendarHeatPresenter 出"语义"（这格是满勤/不足/休息/节日），本文件只把语义映射成
 * 主题色与排版。所有颜色都走 AppTheme.colors，不写裸色值，浅色/深色共用一套判定。
 */

// ---------------------------------------------------------------------------
// 今日实时条
// ---------------------------------------------------------------------------

/**
 * 日历页顶部的「今日实时条」。
 *
 * 存在的意义：日历页是一级首页，用户打开 App 第一眼最想知道的是"我现在算不算在上班"。
 * 与其让他切到「今日」页，不如把结论直接摆在日历上方；点一下才进详情。
 * 这里只读 [TodayStatusPresenter] 与融合快照，不写库。
 */
@Composable
internal fun TodayLiveStrip(
    minutes: TodayStatusPresenter.TodayMinutes,
    headline: TodayStatusPresenter.Headline,
    placeLabel: String,
    confidence: String?,
    onOpenToday: () -> Unit
) {
    val tone = toneColor(headline.tone)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(AppTheme.colors.blue.copy(alpha = if (AppTheme.colors.isDark) 0.14f else 0.08f))
            .clickable(onClick = onOpenToday)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    durationText(minutes.minutes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.blue
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "今日",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.muted,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${headline.text} · $placeLabel",
                style = MaterialTheme.typography.labelMedium,
                color = tone
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                confidence ?: "--",
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.blue
            )
            Text("置信度", style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted)
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = "打开今日",
            tint = AppTheme.colors.muted,
            modifier = Modifier.size(20.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// 热力月历
// ---------------------------------------------------------------------------

/** 格子高度：与界面稿一致（47px CSS ≈ 48dp）。 */
private val CELL_HEIGHT = 52.dp

@Composable
internal fun HeatMonthCard(
    cells: List<DayCellModel?>,
    onDayClick: (DayCellModel) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        color = AppTheme.colors.muted,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { cell ->
                        Box(Modifier.weight(1f)) {
                            if (cell == null) Spacer(Modifier.height(CELL_HEIGHT))
                            else HeatCell(cell) { onDayClick(cell) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatCell(cell: DayCellModel, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.small
    Box(modifier = Modifier.fillMaxWidth().height(CELL_HEIGHT).padding(2.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(CELL_HEIGHT - 4.dp)
                .clip(shape)
                .background(heatBackground(cell))
                .then(
                    when {
                        // 今天优先：描边要压得住浅橙 / 紫 / 深灰底，细线会糊
                        cell.isToday ->
                            Modifier.dashedOrSolidBorder(0, 1.5.dp, AppTheme.colors.blue.copy(alpha = 0.85f), 10.dp)
                        cell.weekendWork ->
                            Modifier.dashedOrSolidBorder(1, 1.dp, AppTheme.colors.blue.copy(alpha = 0.7f), 10.dp)
                        else -> Modifier
                    }
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                cell.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (cell.isToday || cell.isSelected) FontWeight.Bold else FontWeight.Normal,
                color = numberColor(cell)
            )
            Text(
                cell.text,
                style = MaterialTheme.typography.labelSmall,
                color = hoursColor(cell),
                maxLines = 1
            )
        }
        // 缺卡 / 需确认：右上角橙点，让用户在月视图上就能看到哪天要处理
        if (cell.missingPunch) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 7.dp)
                    .size(6.dp)
                    .background(AppTheme.colors.orange, MaterialTheme.shapes.extraSmall)
            )
        }
    }
}

/**
 * 实线 / 虚线的统一入口。[dashed] 为 0 时是实线描边，为 1 时是虚线。
 *
 * Compose 的 `Modifier.border` 不支持虚线，只能用 drawBehind + PathEffect 自己画；
 * 注意把描边画在 bounds 内侧半个线宽的位置，否则虚线会被裁掉半根。
 */
private fun Modifier.dashedOrSolidBorder(
    dashed: Int,
    width: Dp,
    color: Color,
    radius: Dp
): Modifier = this.drawBehind {
    val strokeWidth = width.toPx()
    val inset = strokeWidth / 2f
    val style = if (dashed == 1) {
        Stroke(width = strokeWidth, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f), 0f))
    } else {
        Stroke(width = strokeWidth)
    }
    drawRoundRect(
        color = color,
        topLeft = Offset(inset, inset),
        size = Size(size.width - inset * 2f, size.height - inset * 2f),
        cornerRadius = CornerRadius(radius.toPx()),
        style = style
    )
}

/** 格子底色。优先级：选中 > 今天 > 法定节日 > 休息日 > 调休 > 热力深浅。 */
@Composable
private fun heatBackground(cell: DayCellModel): Color {
    val c = AppTheme.colors
    val dark = c.isDark
    return when {
        cell.isSelected -> c.blue.copy(alpha = if (dark) 0.30f else 0.14f)
        cell.isToday -> c.blue.copy(alpha = if (dark) 0.26f else 0.13f)
        cell.isFestival -> c.purple.copy(alpha = if (dark) 0.22f else 0.14f)
        cell.isRest -> c.muted.copy(alpha = if (dark) 0.11f else 0.07f)
        cell.isMakeup -> c.orange.copy(alpha = if (dark) 0.20f else 0.13f)
        cell.heat == HeatLevel.FULL -> c.blue.copy(alpha = if (dark) 0.34f else 0.20f)
        cell.heat == HeatLevel.PARTIAL -> c.blue.copy(alpha = if (dark) 0.16f else 0.09f)
        else -> Color.Transparent
    }
}

@Composable
private fun numberColor(cell: DayCellModel): Color = when {
    cell.isToday || cell.isSelected -> AppTheme.colors.blue
    cell.isFestival -> AppTheme.colors.purple
    cell.isDim || cell.isRest -> AppTheme.colors.muted
    else -> MaterialTheme.colorScheme.onSurface
}

/**
 * 格子下半部文字色。
 *
 * 「不足」只在**本该上满 8h 的工作日**才转橙——周末/节假日出了半天工是正常事，
 * 把它标橙会让整月看起来到处都是告警。
 */
@Composable
private fun hoursColor(cell: DayCellModel): Color {
    val shortOnWorkday = cell.heat == HeatLevel.PARTIAL &&
        !cell.weekendWork && !cell.isFestival && !cell.isRest && !cell.isMakeup
    return when {
        shortOnWorkday -> AppTheme.colors.orange
        cell.isToday -> AppTheme.colors.blue
        cell.isFestival -> AppTheme.colors.purple
        cell.heat == HeatLevel.FULL -> AppTheme.colors.muted
        else -> AppTheme.colors.muted
    }
}

// ---------------------------------------------------------------------------
// 图例 / 假期提示
// ---------------------------------------------------------------------------

/** 热力与公休图例。两行排布，避免在窄屏上换行成三行。 */
@Composable
internal fun HeatLegend() {
    val c = AppTheme.colors
    val dark = c.isDark
    Column(Modifier.padding(top = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendItem(c.blue.copy(alpha = if (dark) 0.34f else 0.20f), "满勤 8h+")
            LegendItem(c.blue.copy(alpha = if (dark) 0.16f else 0.09f), "不足")
            LegendItem(c.orange, "缺卡")
            LegendItem(c.blue.copy(alpha = if (dark) 0.26f else 0.13f), "今天", border = c.blue)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendItem(c.purple.copy(alpha = if (dark) 0.55f else 0.35f), "法定节假日")
            LegendItem(c.muted.copy(alpha = if (dark) 0.16f else 0.10f), "休息日")
            LegendItem(c.blue.copy(alpha = if (dark) 0.30f else 0.16f), "周末上班", dashed = true)
            LegendItem(c.orange.copy(alpha = if (dark) 0.20f else 0.13f), "调休上班")
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String, border: Color? = null, dashed: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(9.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(color)
                .then(
                    when {
                        dashed -> Modifier.dashedOrSolidBorder(1, 1.dp, AppTheme.colors.blue, 3.dp)
                        border != null -> Modifier.dashedOrSolidBorder(0, 1.dp, border, 3.dp)
                        else -> Modifier
                    }
                )
        )
        Spacer(Modifier.size(5.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted)
    }
}

/** 「下一个假期」提示行。没有可报的假期时整行不渲染。 */
@Composable
internal fun HolidayTipLine(text: String?) {
    if (text.isNullOrBlank()) return
    Row(
        Modifier.padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(5.dp)
                .background(AppTheme.colors.purple, CircleShape)
        )
        Spacer(Modifier.size(7.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.purple)
    }
}

// ---------------------------------------------------------------------------
// 本月工时 / 本月工资卡
// ---------------------------------------------------------------------------

/**
 * 「本月工时 / 本月工资」卡（稿子屏 01 底部、屏 08 的上钻入口）。
 *
 * 计薪规则 v2（2026-09-13）起口径变了：
 * - **已手动录入实发**的月份 → 大数字是录入值（权威），推算只作对照
 * - **未录入**的月份 → 大数字是「预计到手（推算实发）」，并给出应发与扣款构成
 *
 * 推算结果纯展示、不落库（见 verification/计薪规则v2-工资条口径.md）。
 */
@Composable
internal fun MonthSummaryCard(
    summary: MonthSummary,
    salaryCents: Long?,
    payroll: PayrollBreakdown?,
    projection: MonthProjection?,
    paymentLabel: String,
    onOpenPayroll: () -> Unit,
    onEditSalary: () -> Unit,
    onOpenSlip: () -> Unit = {}
) {
    val headline = salaryCents ?: payroll?.netCents
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("本月工时", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
                    Text(
                        durationText(summary.totalMinutes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        when {
                            salaryCents != null -> "实发（已录入）"
                            payroll != null -> "预计到手（估算）"
                            else -> "本月工资"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = AppTheme.colors.muted
                    )
                    Text(
                        headline?.let(::formatCents) ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppTheme.colors.orange
                    )
                }
            }
            payroll?.let { p ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "推算应发 " + formatCents(p.grossCents) + " · 扣款 " +
                        formatCents(p.socialCents + p.fundCents + p.incomeTaxCents),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.muted
                )
            }
            projection?.let { pj ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "整月预估 ≈ " + formatCents(pj.netCents) +
                        "（含未记录 ${pj.stats.unrecordedDays} 天 · " +
                        PayrollPresenter.hoursLabel(pj.stats.projectedMinutes) + "）",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTheme.colors.orange
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                MonthMetric("出勤", "${summary.workDays} 天", Modifier.weight(1f))
                MonthMetric("加班", durationText(summary.overtimeMinutes), Modifier.weight(1f))
                MonthMetric("日均", durationText(summary.averageMinutes), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(paymentLabel, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted)
            // 估算值不能盖掉用户自己录的实际工资：两个数不一致时，差异本身就是要看的信息
            if (salaryCents != null && payroll != null && salaryCents != payroll.netCents) {
                Text(
                    "推算 " + formatCents(payroll.netCents) + "（差额即当月浮动项）",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.muted
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onOpenPayroll) { Text("工资明细") }
                TextButton(onClick = onEditSalary) {
                    Text(if (salaryCents == null) "录入实发工资" else "修改实发工资")
                }
            }
            // 分项工资条（另一套表 salary_slips）：按**这个月**当锚点进录入页
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                TextButton(onClick = onOpenSlip) {
                    Text("工资条录入与核对（分项 · 双校验）›")
                }
            }
        }
    }
}

@Composable
private fun MonthMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AppTheme.colors.muted)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}
