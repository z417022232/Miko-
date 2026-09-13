package com.example.worktimetracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.data.entity.WorkSegmentEntity
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.FusedStatusFormatter
import com.example.worktimetracker.domain.evidence.FusedStatusSnapshot
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.ui.TodayStatusPresenter
import com.example.worktimetracker.ui.UiDayRecord
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import com.example.worktimetracker.ui.theme.AppTheme
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private enum class TodayPage { HOME, FUSION }

/**
 * 「今日」一级页宿主。
 *
 * 界面稿 v4 把原来的「统计」一级页换成了实时页：「我今天到底算不算在上班、
 * 已经算了多久、离下班还有多远」是这个 App 最高频的问题，值得占一个一级入口；
 * 月度统计降级为日历「本月」卡的下钻页（见 CalendarHost）。
 */
@Composable
fun TodayHost(vm: WorkTimeViewModel) {
    var page by remember { mutableStateOf(TodayPage.HOME) }
    BackHandler(page != TodayPage.HOME) { page = TodayPage.HOME }
    when (page) {
        TodayPage.HOME -> TodayScreen(vm, onOpenFusion = { page = TodayPage.FUSION })
        TodayPage.FUSION -> FusionDetailScreen(vm, onBack = { page = TodayPage.HOME })
    }
}

@Composable
fun TodayScreen(vm: WorkTimeViewModel, onOpenFusion: () -> Unit) {
    val record by vm.todayRecord.collectAsState()
    val settings by vm.settings.collectAsState()
    val fused by vm.fusedStatus.collectAsState()
    val segments by vm.todaySegments.collectAsState()
    val today = remember { LocalDate.now() }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showPunch by remember { mutableStateOf(false) }
    var showSegments by remember { mutableStateOf(false) }

    // 心跳：30 秒一次，只为了让「计时中」的数字往前走、并把外部（前台服务 / 手动补录）
    // 产生的记录变化拉回来。这里绝不写库。
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            vm.refreshToday()
            delay(30_000L)
        }
    }

    val live = TodayStatusPresenter.displayMinutes(
        finalMinutes = record?.finalMinutes ?: 0,
        startMillis = record?.startMillis,
        endMillis = record?.endMillis,
        nowMillis = nowMillis,
        restDeductionMinutes = settings.restDeductionMinutes,
        fixedMinutes = if (settings.hasDefaultHours) settings.defaultWorkMinutes else null
    )
    val headline = TodayStatusPresenter.headline(record)
    val earnings = TodayStatusPresenter.earningsCents(live.minutes, settings.hourlyRateCents)
    val placeLabel = fused?.place?.let { FusedStatusFormatter.placeLabel(it) } ?: "暂不确定"

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader(
            title = "今日",
            subtitle = "${TodayStatusPresenter.dateLabel(today)} · ${TodayStatusPresenter.weekLabel(today)}",
            action = {
                IconButton(onClick = { vm.refreshToday() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = AppTheme.colors.blue)
                }
            }
        )
        Spacer(Modifier.height(14.dp))
        HeroStatusCard(headline, live, earnings, settings.hourlyRateCents > 0L)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricCell("上班", record?.startText ?: "--", Modifier.weight(1f))
            MetricCell("当前地点", placeLabel, Modifier.weight(1f))
            MetricCell("置信度", fused?.let { FusedStatusFormatter.confidenceLabel(it) } ?: "--", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        EvidenceChipRow(fused)
        Spacer(Modifier.height(12.dp))
        DecisionCard(fused, onOpenFusion)
        Spacer(Modifier.height(12.dp))
        TimelineCard(record, segments)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { showPunch = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Fingerprint, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("手动打卡")
            }
            OutlinedButton(onClick = { showSegments = true }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.EditCalendar, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("补录时段")
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showPunch) {
        ManualPunchSheet(
            record = record,
            liveMinutes = live.minutes,
            earningsCents = earnings,
            placeLabel = placeLabel,
            date = today,
            vm = vm,
            onDismiss = { showPunch = false }
        )
    }
    if (showSegments) {
        SegmentEntrySheet(
            date = today,
            existing = segments,
            vm = vm,
            onDismiss = { showSegments = false }
        )
    }
}

@Composable
private fun HeroStatusCard(
    headline: TodayStatusPresenter.Headline,
    live: TodayStatusPresenter.TodayMinutes,
    earningsCents: Long?,
    rateConfigured: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(headline.text, toneColor(headline.tone))
                if (live.running) StatusPill("计时中", AppTheme.colors.blue)
                if (live.fixed) StatusPill("固定工时", AppTheme.colors.purple)
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("今日累计工时", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                    Text(
                        formatMinutes(live.minutes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("今日工资", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                    Text(
                        earningsCents?.let(::formatCents) ?: "未设时薪",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (earningsCents != null) MaterialTheme.colorScheme.onSurface else AppTheme.colors.muted
                    )
                    if (!rateConfigured) {
                        Text(
                            "到「计薪规则」填时薪",
                            color = AppTheme.colors.muted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricCell(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(label, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(3.dp))
            Text(value, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** 证据源芯片行：有该来源就点亮。横向可滚，避免来源变多时在窄屏被压扁。 */
@Composable
private fun EvidenceChipRow(fused: FusedStatusSnapshot?) {
    val chips = TodayStatusPresenter.evidenceChips(fused)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        chips.forEach { chip ->
            val color = if (chip.active) AppTheme.colors.blue else AppTheme.colors.muted
            Text(
                chip.label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = if (chip.active) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (chip.active) 0.13f else 0.07f))
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun DecisionCard(fused: FusedStatusSnapshot?, onOpenFusion: () -> Unit) {
    Card(
        onClick = onOpenFusion,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("本次判定", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
                if (fused == null) {
                    Text("暂无", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                } else {
                    Text(
                        fused.decision.name,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = decisionColor(fused.decision)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (fused == null) "定位服务尚未运行或还没有任何位置证据"
                else FusedStatusFormatter.reasonLabel(fused.reason),
                color = AppTheme.colors.muted,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(10.dp))
            ThinDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    TodayStatusPresenter.evidenceLine(fused) ?: "尚无证据明细",
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "查看融合详情",
                    color = AppTheme.colors.blue,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Icon(Icons.Outlined.ChevronRight, null, tint = AppTheme.colors.blue, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
internal fun decisionColor(decision: FusedDecision): Color = when (decision) {
    FusedDecision.CONFIRMED -> AppTheme.colors.green
    FusedDecision.MAINTAINED -> AppTheme.colors.blue
    FusedDecision.UNKNOWN -> AppTheme.colors.orange
}

// ------------------------------------------------------------------ 时间线

private enum class TimelineTone { BLUE, GREEN, MUTED }

private data class TimelineEvent(
    val time: String,
    val title: String,
    val detail: String?,
    val tone: TimelineTone
)

@Composable
private fun TimelineCard(record: UiDayRecord?, segments: List<WorkSegmentEntity>) {
    val zone = remember { ZoneId.systemDefault() }
    val events = remember(record, segments) { buildTimeline(record, segments, zone) }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("今天的记录", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (events.isEmpty()) {
                Text(
                    "还没有打卡或判定记录。开始自动识别，或用下面的按钮手动补一次。",
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                events.forEachIndexed { index, event ->
                    if (index > 0) Spacer(Modifier.height(10.dp))
                    TimelineRow(event)
                }
            }
        }
    }
}

private fun buildTimeline(
    record: UiDayRecord?,
    segments: List<WorkSegmentEntity>,
    zone: ZoneId
): List<TimelineEvent> {
    if (record == null) return emptyList()
    val out = mutableListOf<TimelineEvent>()
    record.homeDepartureText?.let { out.add(TimelineEvent(it, "离开家", null, TimelineTone.MUTED)) }
    record.startText?.let {
        out.add(
            TimelineEvent(
                it,
                "上班打卡",
                record.shift?.let { shift -> "$shift · 已确认" } ?: "已确认",
                TimelineTone.BLUE
            )
        )
    }
    segments.sortedBy { it.startTime }.forEach { segment ->
        val counted = segment.segmentType == WorkSegmentEntity.TYPE_WORK
        val detail = buildString {
            append(durationText(segment.minutes))
            append(" · ")
            append(if (counted) "已计入" else "未计入")
            if (!segment.note.isNullOrBlank()) append(" · ").append(segment.note)
        }
        out.add(
            TimelineEvent(
                clockText(segment.startTime, zone),
                segment.siteLabel ?: if (counted) "在岗" else "离厂",
                detail,
                if (counted) TimelineTone.BLUE else TimelineTone.MUTED
            )
        )
    }
    record.endText?.let {
        out.add(
            TimelineEvent(
                it,
                "下班打卡",
                if (record.finalMinutes > 0) "计入 ${formatMinutes(record.finalMinutes)}" else null,
                TimelineTone.GREEN
            )
        )
    }
    record.homeArrivalText?.let { out.add(TimelineEvent(it, "到家", null, TimelineTone.MUTED)) }
    if (record.startMillis != null && record.endMillis == null) {
        out.add(TimelineEvent("--:--", "时长累计中", "在岗", TimelineTone.BLUE))
    }
    return out
}

private fun clockText(millis: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(millis).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }

/** 分钟 → "1h 00m" / "45m"。 */
internal fun durationText(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val h = safe / 60
    val m = safe % 60
    return if (h > 0) "${h}h %02dm".format(m) else "${m}m"
}

@Composable
private fun TimelineRow(event: TimelineEvent) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            event.time,
            style = MaterialTheme.typography.labelMedium,
            color = AppTheme.colors.muted,
            modifier = Modifier.size(width = 50.dp, height = 18.dp)
        )
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(7.dp)
                .background(timelineColor(event.tone), CircleShape)
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(event.title, fontWeight = FontWeight.Medium)
            event.detail?.let {
                Text(it, color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun timelineColor(tone: TimelineTone): Color = when (tone) {
    TimelineTone.BLUE -> AppTheme.colors.blue
    TimelineTone.GREEN -> AppTheme.colors.green
    TimelineTone.MUTED -> AppTheme.colors.muted
}

@Composable
internal fun toneColor(tone: TodayStatusPresenter.TodayTone): Color = when (tone) {
    TodayStatusPresenter.TodayTone.WORKING -> AppTheme.colors.blue
    TodayStatusPresenter.TodayTone.DONE -> AppTheme.colors.green
    TodayStatusPresenter.TodayTone.OFF -> AppTheme.colors.muted
    TodayStatusPresenter.TodayTone.WARN -> AppTheme.colors.orange
    TodayStatusPresenter.TodayTone.IDLE -> AppTheme.colors.muted
}

/** 未使用但保留：融合页与今日页共用「地点标签」口径。 */
internal fun placeLabelOf(place: ResolvedPlace?): String =
    place?.let { FusedStatusFormatter.placeLabel(it) } ?: "暂不确定"
