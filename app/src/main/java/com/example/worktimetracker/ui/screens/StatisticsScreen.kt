package com.example.worktimetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.ui.UiDayRecord
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import kotlin.math.max

@Composable
fun StatisticsScreen(vm: WorkTimeViewModel) {
    val month by vm.month.collectAsState()
    val records by vm.records.collectAsState()
    var showExport by remember { mutableStateOf(false) }
    val total = records.sumOf { it.finalMinutes }
    val worked = records.filter { it.finalMinutes > 0 }
    val workDays = worked.size
    val average = if (workDays == 0) 0 else total / workDays
    val restDays = records.count { it.status == "休息" }
    val reviewDays = records.count { it.needsReview }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeader(
                title = "月度统计",
                subtitle = "${month.year}年${month.monthValue}月",
                action = {
                    FilledTonalButton(onClick = { showExport = true }) {
                        Icon(Icons.Outlined.IosShare, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("导出")
                    }
                }
            )
        }
        item { StatisticsHero(total, workDays) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallMetricCard("日均工时", formatMinutes(average), AppBlue, Modifier.weight(1f))
                SmallMetricCard("休息", "${restDays}天", AppGreen, Modifier.weight(1f))
                SmallMetricCard("待确认", "${reviewDays}天", AppRed, Modifier.weight(1f))
            }
        }
        item {
            SectionTitle("每周工时")
            WeeklyChart(records)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SectionTitle("每日明细")
                Text("${worked.size}条", color = AppMuted, modifier = Modifier.padding(top = 8.dp, end = 4.dp))
            }
        }
        if (worked.isEmpty()) {
            item { EmptyStatistics() }
        } else {
            items(worked.sortedByDescending { it.date }) { record ->
                DailyStatRow(record)
            }
        }
        item { Spacer(Modifier.height(6.dp)) }
    }

    if (showExport) ExportBottomSheet(vm, onDismiss = { showExport = false })
}

@Composable
private fun StatisticsHero(total: Int, workDays: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .background(AppBlue.copy(alpha = 0.1f), RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Schedule, null, tint = AppBlue)
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text("本月总工时", color = AppMuted)
                Text(formatMinutes(total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("$workDays", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("工作天", color = AppMuted)
            }
        }
    }
}

@Composable
private fun SmallMetricCard(title: String, value: String, color: Color, modifier: Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(13.dp)) {
            Text(value, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
            Text(title, color = AppMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun WeeklyChart(records: List<UiDayRecord>) {
    val weekly = (0..4).map { week ->
        val start = week * 7 + 1
        val end = if (week == 4) 31 else start + 6
        records.filter { it.date.dayOfMonth in start..end }.sumOf { it.finalMinutes }
    }
    val maxMinutes = max(weekly.maxOrNull() ?: 0, 1)
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().height(160.dp).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            weekly.forEachIndexed { index, minutes ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(compactHours(minutes), style = MaterialTheme.typography.labelSmall, color = AppMuted)
                    Spacer(Modifier.height(5.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 5.dp, max = 94.dp)
                            .height(max(5, (94f * minutes / maxMinutes).toInt()).dp)
                            .background(
                                if (minutes > 0) AppBlue.copy(alpha = 0.82f) else AppDivider,
                                RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp)
                            )
                    )
                    Spacer(Modifier.height(5.dp))
                    Text("第${index + 1}周", style = MaterialTheme.typography.labelSmall, color = AppMuted)
                }
            }
        }
    }
}

@Composable
private fun EmptyStatistics() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("本月还没有工时记录", fontWeight = FontWeight.SemiBold)
            Text("自动识别或手动修改后会显示在这里", color = AppMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DailyStatRow(record: UiDayRecord) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${record.date.dayOfMonth}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("日", color = AppMuted, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(record.status.ifBlank { "工作" }, fontWeight = FontWeight.Medium)
                    if (record.needsReview) StatusPill("待确认", AppRed)
                }
                Text(
                    if (record.startText == null && record.endText == null) "手动记录"
                    else "${record.startText ?: "--"} — ${record.endText ?: "--"}",
                    color = AppMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(formatMinutes(record.finalMinutes), fontWeight = FontWeight.Bold, color = statusColor(record.status))
        }
    }
}
