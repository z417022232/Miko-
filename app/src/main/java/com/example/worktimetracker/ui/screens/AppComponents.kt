package com.example.worktimetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.GpsFixed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.domain.evidence.EvidenceSourceKind
import com.example.worktimetracker.domain.evidence.FusedStatusFormatter
import com.example.worktimetracker.domain.evidence.SourceStatus
import com.example.worktimetracker.domain.payroll.PayrollBreakdown
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import com.example.worktimetracker.ui.theme.AppTheme


fun formatMinutes(minutes: Int): String {
    if (minutes <= 0) return "0小时"
    val hours = minutes / 60
    val remainder = minutes % 60
    return when {
        hours == 0 -> "${remainder}分钟"
        remainder == 0 -> "${hours}小时"
        else -> "${hours}小时${remainder}分"
    }
}

fun compactHours(minutes: Int): String {
    if (minutes <= 0) return "0h"
    val value = minutes / 60.0
    return if (minutes % 60 == 0) "${minutes / 60}h" else "${"%.1f".format(value)}h"
}

/**
 * 金额（分）→ `¥1,234.56`。
 *
 * 单独抽出来是因为月工资卡、今日工资、工资明细页三处都要用，
 * 各写一份 `"¥%,.2f".format(...)` 迟早出现小数位不一致。
 */
fun formatCents(cents: Long): String = "¥%,.2f".format(Locale.CHINA, cents / 100.0)

/**
 * 工资构成明细行（计薪规则页 + 工资明细弹窗共用，避免两处各抄一份后漂移）。
 *
 * 顺序与工资条一致：出勤 → 各项收入 → 应发 → 三项扣款 → 预计到手。
 * 为 0 的浮动项不渲染，免得每月白占一行。
 */
@Composable
fun PayrollCompositionLines(payroll: PayrollBreakdown) {
    PayLine("出勤 / 夜班", "${payroll.attendDays} 天 / ${payroll.nightShiftDays} 夜")
    PayLine("出勤折算系数", "%.2f".format(Locale.CHINA, payroll.attendanceFactor))
    ThinDivider()
    PayLine("基本工资", formatCents(payroll.basicSalaryCents))
    PayLine("岗位津贴", formatCents(payroll.postAllowanceCents))
    PayLine("绩效工资", formatCents(payroll.performancePayCents))
    PayLine("工龄工资", formatCents(payroll.seniorAllowanceCents))
    PayLine("全勤奖", formatCents(payroll.fullAttendanceCents))
    PayLine("加班工资（包干）", formatCents(payroll.overtimePayCents))
    PayLine("夜班津贴", formatCents(payroll.nightAllowanceCents))
    if (payroll.benefitBonusCents != 0L) PayLine("效益奖金", formatCents(payroll.benefitBonusCents))
    if (payroll.heatAllowanceCents != 0L) PayLine("高温补贴", formatCents(payroll.heatAllowanceCents))
    if (payroll.sickPayCents != 0L) PayLine("病假工资", formatCents(payroll.sickPayCents))
    if (payroll.backPayCents != 0L) PayLine("补发", formatCents(payroll.backPayCents))
    if (payroll.otherAddCents != 0L) PayLine("其他加项", formatCents(payroll.otherAddCents))
    ThinDivider()
    PayLine("应发工资", formatCents(payroll.grossCents), emphasize = true)
    PayLine("− 社保个人", formatCents(payroll.socialCents))
    PayLine("− 住房公积金", formatCents(payroll.fundCents))
    PayLine("− 个人所得税", formatCents(payroll.incomeTaxCents))
    ThinDivider()
    PayLine("实发（预计到手）", formatCents(payroll.netCents), emphasize = true)
}

/**
 * 「标签 —— 金额」一行。工资明细、计薪规则页、今日页三处共用。
 *
 * [emphasize] 给「应发 / 预计到手」这类关键行加粗并走强调色。
 * 计薪规则 v2 起取代了各页面自己拼 `Row + SpaceBetween` 的写法。
 */
@Composable
fun PayLine(label: String, value: String, emphasize: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasize) AppTheme.colors.orange else AppTheme.colors.textPrimary
        )
    }
}

fun formatClock(minutes: Int): String {
    val normalized = minutes.coerceIn(0, 1439)
    return "%02d:%02d".format(normalized / 60, normalized % 60)
}

@Composable
fun statusColor(status: String): Color = when (status) {
    "白班" -> AppTheme.colors.blue
    "夜班" -> AppTheme.colors.purple
    "休息" -> AppTheme.colors.muted
    "外出" -> AppTheme.colors.orange
    "下早班", "到岗异常" -> AppTheme.colors.red
    "手动", "请假" -> AppTheme.colors.green
    else -> AppTheme.colors.textPrimary
}

fun shortStatus(status: String): String = when (status) {
    "白班" -> "白班"
    "夜班" -> "夜班"
    "休息" -> "休"
    "外出" -> "外出"
    "下早班" -> "早退"
    "到岗异常" -> "异常"
    "手动" -> "手动"
    "请假" -> "请假"
    else -> status
}

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "返回")
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        action?.invoke()
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = AppTheme.colors.muted,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    summary: String? = null,
    tint: Color = AppTheme.colors.blue,
    showChevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(tint.copy(alpha = 0.11f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (!summary.isNullOrBlank()) {
                Text(summary, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
        if (trailing != null) trailing()
        else if (showChevron) Icon(Icons.Outlined.ChevronRight, null, tint = AppTheme.colors.muted)
    }
}

@Composable
fun ThinDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AppTheme.colors.divider))
}

@Composable
fun StatusPill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.11f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
fun WorkTimePickerDialog(
    startMinutes: Int,
    endMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var startHour by remember(startMinutes) { mutableIntStateOf(startMinutes / 60) }
    var startMinute by remember(startMinutes) { mutableIntStateOf(startMinutes % 60) }
    var endHour by remember(endMinutes) { mutableIntStateOf(endMinutes / 60) }
    var endMinute by remember(endMinutes) { mutableIntStateOf(endMinutes % 60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置上下班时间") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("上班", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelLarge)
                WheelRow(startHour, startMinute, { startHour = it }, { startMinute = it })
                Text("下班", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelLarge)
                WheelRow(endHour, endMinute, { endHour = it }, { endMinute = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(startHour * 60 + startMinute, endHour * 60 + endMinute) }) {
                Text("完成")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun NumberWheelDialog(
    title: String,
    value: Int,
    range: IntRange,
    unit: String,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selected by remember(value, range) { mutableIntStateOf(value.coerceIn(range.first, range.last)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            WheelColumn(
                range = range,
                selected = selected,
                unit = unit,
                onSelected = { selected = it },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun TimeWheelDialog(
    title: String,
    value: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var hour by remember(value) { mutableIntStateOf(value / 60) }
    var minute by remember(value) { mutableIntStateOf(value % 60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { WheelRow(hour, minute, { hour = it }, { minute = it }) },
        confirmButton = { TextButton(onClick = { onConfirm(hour * 60 + minute) }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun WheelRow(
    hour: Int,
    minute: Int,
    onHour: (Int) -> Unit,
    onMinute: (Int) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        WheelColumn(0..23, hour, "时", onHour, Modifier.weight(1f))
        WheelColumn(0..59, minute, "分", onMinute, Modifier.weight(1f))
    }
}

@Composable
private fun WheelColumn(
    range: IntRange,
    selected: Int,
    unit: String,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val values = remember(range) { range.toList() }
    val selectedIndex = (selected - range.first).coerceIn(0, values.lastIndex)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)

    LaunchedEffect(state, range) {
        snapshotFlow {
            val info = state.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo
                .minByOrNull { item -> abs((item.offset + item.size / 2) - center) }
                ?.index ?: state.firstVisibleItemIndex
        }
            .map { values.getOrNull(it.coerceIn(0, values.lastIndex)) ?: selected }
            .distinctUntilChanged()
            .collect(onSelected)
    }

    LaunchedEffect(selected) {
        val target = (selected - range.first).coerceIn(0, values.lastIndex)
        if (!state.isScrollInProgress && abs(state.firstVisibleItemIndex - target) > 1) {
            state.scrollToItem(target)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.height(150.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 5.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface)
            )
            LazyColumn(
                state = state,
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = 57.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(values) { item ->
                    val active = item == selected
                    Text(
                        "%02d %s".format(item, unit),
                        color = if (active) MaterialTheme.colorScheme.onSurface else AppTheme.colors.muted,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(item) }
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 四源状态（GPS / Wi-Fi / 蓝牙 / 基站）
// ---------------------------------------------------------------------------

/** 来源 → 图标。唯一的映射点，别在页面里各写一份。 */
fun evidenceSourceIcon(kind: EvidenceSourceKind): ImageVector = when (kind) {
    EvidenceSourceKind.GNSS -> Icons.Outlined.GpsFixed
    EvidenceSourceKind.WIFI -> Icons.Outlined.Wifi
    EvidenceSourceKind.BLUETOOTH -> Icons.Outlined.Bluetooth
    EvidenceSourceKind.CELL -> Icons.Outlined.CellTower
}

/** 状态 → 颜色：正常=蓝、异常=红、未知=灰（灰是「后台还没记录过」，不谎报成正常）。 */
@Composable
fun evidenceSourceTint(status: SourceStatus): Color = when (status) {
    SourceStatus.NORMAL -> AppTheme.colors.blue
    SourceStatus.ABNORMAL -> AppTheme.colors.red
    SourceStatus.UNKNOWN -> AppTheme.colors.muted
}

fun evidenceSourceStatusText(status: SourceStatus): String = when (status) {
    SourceStatus.NORMAL -> "正常"
    SourceStatus.ABNORMAL -> "异常"
    SourceStatus.UNKNOWN -> "暂无数据"
}

/**
 * 可信度可视化：一个档位词 + 一条按比例填充的横条 + 百分比小字。
 *
 * 光给「80%」用户无法判断该不该信（到底算高还是低？），所以档位词在前、
 * 横条给出直观比例、百分比退为补充信息。
 */
@Composable
fun ConfidenceMeter(
    level: FusedStatusFormatter.ConfidenceLevel,
    fraction: Float,
    percentText: String?,
    modifier: Modifier = Modifier,
) {
    val tint = when (level) {
        FusedStatusFormatter.ConfidenceLevel.HIGH -> AppTheme.colors.green
        FusedStatusFormatter.ConfidenceLevel.MEDIUM -> AppTheme.colors.blue
        FusedStatusFormatter.ConfidenceLevel.LOW -> AppTheme.colors.orange
        FusedStatusFormatter.ConfidenceLevel.NONE -> AppTheme.colors.muted
    }
    val safe = fraction.coerceIn(0f, 1f)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("可信度", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .width(96.dp)
                .height(6.dp)
                .clip(CircleShape)
                .background(AppTheme.colors.muted.copy(alpha = 0.18f))
        ) {
            if (safe > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(safe)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(tint)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (percentText == null) level.label else "${level.label} · $percentText",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tint
        )
    }
}

/**
 * 四个来源的状态徽标行。
 *
 * **图标是纯状态展示，不可点**（用户 2026-09-16 口径）：四个图标长得像四个按钮，
 * 点上任何一个又都只触发同一次整体取样，用户会误以为「点哪个只刷哪个」。现在把刷新
 * 收敛成右侧唯一的动作「立即刷新状态」，一次把 GPS / Wi-Fi / 蓝牙 / 基站全部重取
 * —— 四类证据本来就是同一次环境采样里一起拿到的。
 *
 * @param refreshing 正在等新取样：整行降透明度并禁用重复点击
 * @param trailingHint 右侧动作文案，默认「立即刷新状态」
 */
@Composable
fun EvidenceSourceRow(
    health: Map<EvidenceSourceKind, SourceStatus>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    trailingHint: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        EvidenceSourceKind.entries.forEach { kind ->
            val status = health[kind] ?: SourceStatus.UNKNOWN
            EvidenceSourceBadge(kind, status, refreshing)
            Spacer(Modifier.size(14.dp))
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onRefresh, enabled = !refreshing) {
            Text(
                if (refreshing) "刷新中…" else (trailingHint ?: "立即刷新状态"),
                style = MaterialTheme.typography.bodySmall,
                color = AppTheme.colors.blue
            )
        }
    }
}

@Composable
private fun EvidenceSourceBadge(
    kind: EvidenceSourceKind,
    status: SourceStatus,
    refreshing: Boolean,
) {
    val tint = evidenceSourceTint(status)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(tint.copy(alpha = if (refreshing) 0.30f else 1f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                evidenceSourceIcon(kind),
                contentDescription = "${kind.label} ${evidenceSourceStatusText(status)}",
                tint = if (refreshing) tint else Color.White,
                modifier = Modifier.size(21.dp)
            )
        }
        Spacer(Modifier.height(3.dp))
        Text(
            kind.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (status == SourceStatus.ABNORMAL) AppTheme.colors.red else AppTheme.colors.muted
        )
    }
}

/**
 * 「刷新一次」的一次性反馈条：出现几秒后自动消失。
 *
 * 自动消失是刻意的 —— 刷新结果多半是「这次没取到新定位（室内正常）」，
 * 停留在页面上会被误读成故障。**由调用方在展示后消费掉消息**，避免重组时反复弹出。
 */
@Composable
fun EvidenceRefreshBanner(
    message: String?,
    isRunning: Boolean,
    onConsume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(message, isRunning) {
        if (message != null && !isRunning) {
            delay(3_500)
            onConsume()
        }
    }
    if (message == null) return
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) AppTheme.colors.blue.copy(alpha = 0.10f)
            else AppTheme.colors.muted.copy(alpha = 0.10f)
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = AppTheme.colors.textPrimary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}
