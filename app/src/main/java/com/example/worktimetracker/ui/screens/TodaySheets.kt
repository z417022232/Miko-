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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.data.entity.SiteEntity
import com.example.worktimetracker.data.entity.WorkSegmentEntity
import com.example.worktimetracker.ui.TodayStatusPresenter
import com.example.worktimetracker.ui.UiDayRecord
import com.example.worktimetracker.ui.app.DaySegmentDraft
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import com.example.worktimetracker.ui.theme.AppTheme
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private val sheetZone: ZoneId get() = ZoneId.systemDefault()

private fun minuteOfDay(millis: Long): Int =
    Instant.ofEpochMilli(millis).atZone(sheetZone).let { it.hour * 60 + it.minute }

private fun clockOf(minutes: Int): String =
    "%02d:%02d".format((minutes / 60) % 24, minutes % 60)

@Composable
private fun nowMinuteOfDay(): Int = remember {
    LocalTime.now().let { it.hour * 60 + it.minute }
}

// ---------------------------------------------------------------------------
// 手动打卡（界面稿 05）
// ---------------------------------------------------------------------------

/**
 * 手动打卡抽屉。
 *
 * 打上班卡还是下班卡**由用户显式选择**，不根据记录猜——猜错会直接改写工时。
 * 默认值给「还没打卡的那一半」：没有上班时间就先打上班卡。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualPunchSheet(
    record: UiDayRecord?,
    liveMinutes: Int,
    earningsCents: Long?,
    placeLabel: String,
    date: LocalDate,
    vm: WorkTimeViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var clockIn by remember { mutableStateOf(record?.startMillis == null) }
    // nowMinuteOfDay() 是 @Composable，必须在 Composable 上下文里先求值，
    // 不能塞进 remember { } 的 lambda（那里已经不是 Composable 上下文）。
    val currentMinute = nowMinuteOfDay()
    var minutes by remember {
        mutableIntStateOf(
            when {
                record?.startMillis == null -> currentMinute
                record.endMillis == null -> currentMinute
                else -> minuteOfDay(record.endMillis!!)
            }
        )
    }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 30.dp)
        ) {
            Text("手动打卡", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "${TodayStatusPresenter.dateLabel(date)} · ${TodayStatusPresenter.weekLabel(date)}",
                color = AppTheme.colors.muted,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(14.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large
            ) {
                Row(Modifier.fillMaxWidth().padding(15.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("今日累计工时", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelSmall)
                        Text(formatMinutes(liveMinutes), fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("今日工资", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelSmall)
                        Text(earningsCents?.let(::formatCents) ?: "暂无基准", fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("打卡类型", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = clockIn,
                    onClick = { clockIn = true },
                    label = { Text("上班打卡") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = !clockIn,
                    onClick = { clockIn = false },
                    label = { Text("下班打卡") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(14.dp))
            FieldRow(label = "时间", value = clockOf(minutes)) { showPicker = true }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LocationOn, null, tint = AppTheme.colors.muted, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("地点", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(placeLabel, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = AppTheme.colors.red, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    vm.manualPunch(date, clockIn, minutes, note) { message ->
                        if (message == null) onDismiss() else error = message
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("确认打卡") }
            Spacer(Modifier.height(10.dp))
            Text(
                "手动打卡会标记为「人工修正」，与自动判定结果一并保留在诊断日志中；" +
                    "工时仍按公司 v1 计薪规则重算，不会出现两套算法。",
                color = AppTheme.colors.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showPicker) {
        TimeWheelDialog("选择打卡时间", minutes, onDismiss = { showPicker = false }) {
            minutes = it
            showPicker = false
        }
    }
}

/** 「标签 —— 值 ›」一行，点击进入选择。 */
@Composable
private fun FieldRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Timer, null, tint = AppTheme.colors.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Bold, color = AppTheme.colors.blue)
    }
}

// ---------------------------------------------------------------------------
// 补录时段（界面稿 06）
// ---------------------------------------------------------------------------

/**
 * 补录时段抽屉。
 *
 * 采用「整天替换」语义：草稿列表最终整批写入 work_segments，再按 v1 的
 * manualSegments 口径重算当天计入工时。只有「在岗（计入）」的时段参与计算，
 * 「离厂（不计入）」只留痕 —— 这样用户在日详情里能看到午休，但工时不被它影响。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentEntrySheet(
    date: LocalDate,
    existing: List<WorkSegmentEntity>,
    vm: WorkTimeViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sites by vm.sites.collectAsState()
    var drafts by remember {
        mutableStateOf(
            existing.map { segment ->
                DaySegmentDraft(
                    startMinutes = minuteOfDay(segment.startTime),
                    endMinutes = minuteOfDay(segment.endTime),
                    segmentType = segment.segmentType,
                    deductRest = segment.deductRest,
                    siteId = segment.siteId,
                    siteLabel = segment.siteLabel,
                    note = segment.note
                )
            }
        )
    }
    var start by remember { mutableIntStateOf(12 * 60) }
    var end by remember { mutableIntStateOf(13 * 60) }
    var type by remember { mutableStateOf(WorkSegmentEntity.TYPE_OFF_SITE) }
    var siteId by remember { mutableStateOf<Long?>(null) }
    var deductRest by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Int?>(null) } // 0 = 开始，1 = 结束

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 30.dp)
        ) {
            Text("补录时段", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "${TodayStatusPresenter.dateLabel(date)}",
                color = AppTheme.colors.muted,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(14.dp))

            if (drafts.isNotEmpty()) {
                Text("已补录 ${drafts.size} 段", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                drafts.forEachIndexed { index, draft ->
                    DraftRow(draft) { drafts = drafts.filterIndexed { i, _ -> i != index } }
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("开始", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editing = 0 }) { Text(clockOf(start)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("结束", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { editing = 1 }) { Text(clockOf(end)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("时长", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    durationText(if (end > start) end - start else end + 24 * 60 - start),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(14.dp))

            Text("时段类型", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = type == WorkSegmentEntity.TYPE_WORK,
                    onClick = { type = WorkSegmentEntity.TYPE_WORK },
                    label = { Text("在岗（计入）") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = type == WorkSegmentEntity.TYPE_OFF_SITE,
                    onClick = { type = WorkSegmentEntity.TYPE_OFF_SITE },
                    label = { Text("离厂（不计入）") },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(14.dp))

            Text("地点", color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(6.dp))
            SiteChips(sites, siteId) { siteId = it }
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(deductRest, onCheckedChange = { deductRest = it })
                Text("这段里含午休，扣除默认休息时长", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选，例如「设备故障补录」）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = AppTheme.colors.red, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    if (start == end) {
                        error = "开始与结束时间不能相同"
                    } else {
                        val label = sites.firstOrNull { it.id == siteId }?.name
                        drafts = drafts + DaySegmentDraft(
                            startMinutes = start,
                            endMinutes = end,
                            segmentType = type,
                            deductRest = deductRest,
                            siteId = siteId,
                            siteLabel = label,
                            note = note.ifBlank { null }
                        )
                        error = null
                        note = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("加入列表") }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = {
                        vm.saveDaySegments(date, drafts, note) { message ->
                            if (message == null) onDismiss() else error = message
                        }
                    },
                    enabled = drafts.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("保存补录") }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "补录内容会标记为「人工修正」，保存后在当日明细与诊断日志中都会留痕，" +
                    "不会与自动判定结果混淆。",
                color = AppTheme.colors.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    editing?.let { which ->
        val value = if (which == 0) start else end
        TimeWheelDialog("选择时间", value, onDismiss = { editing = null }) {
            if (which == 0) start = it else end = it
            editing = null
        }
    }
}

@Composable
private fun DraftRow(draft: DaySegmentDraft, onRemove: () -> Unit) {
    val counted = draft.segmentType == WorkSegmentEntity.TYPE_WORK
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(AppTheme.colors.blue.copy(alpha = if (counted) 0.08f else 0.04f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${clockOf(draft.startMinutes)} – ${clockOf(draft.endMinutes)}",
                fontWeight = FontWeight.Medium
            )
            Text(
                buildString {
                    append(draft.siteLabel ?: if (counted) "在岗" else "离厂")
                    append(" · ")
                    append(if (counted) "已计入" else "未计入")
                },
                color = AppTheme.colors.muted,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Icon(
            Icons.Outlined.Close,
            contentDescription = "移除",
            tint = AppTheme.colors.muted,
            modifier = Modifier
                .size(20.dp)
                .clickable(onClick = onRemove)
        )
    }
}

@Composable
private fun SiteChips(sites: List<SiteEntity>, selected: Long?, onSelect: (Long?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sites.take(2).forEach { site ->
                FilterChip(
                    selected = selected == site.id,
                    onClick = { onSelect(site.id) },
                    label = { Text(site.name) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sites.drop(2).take(2).forEach { site ->
                FilterChip(
                    selected = selected == site.id,
                    onClick = { onSelect(site.id) },
                    label = { Text(site.name) }
                )
            }
        }
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("其他（不计入地点判定）") }
        )
    }
}
