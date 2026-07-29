package com.example.worktimetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

val AppBlue = Color(0xFF2F6BFF)
val AppPurple = Color(0xFF7257E7)
val AppGreen = Color(0xFF16A06A)
val AppOrange = Color(0xFFE88922)
val AppRed = Color(0xFFE34D59)
val AppMuted = Color(0xFF7D889B)
val AppDivider = Color(0xFFE9EDF3)

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

fun formatClock(minutes: Int): String {
    val normalized = minutes.coerceIn(0, 1439)
    return "%02d:%02d".format(normalized / 60, normalized % 60)
}

fun statusColor(status: String): Color = when (status) {
    "白班" -> AppBlue
    "夜班" -> AppPurple
    "休息" -> AppMuted
    "外出" -> AppOrange
    "下早班", "到岗异常" -> AppRed
    "手动", "请假" -> AppGreen
    else -> Color(0xFF273142)
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
                Text(subtitle, color = AppMuted, style = MaterialTheme.typography.bodyMedium)
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
        color = AppMuted,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
    )
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(20.dp),
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
    tint: Color = AppBlue,
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
                .clip(RoundedCornerShape(12.dp))
                .background(tint.copy(alpha = 0.11f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(21.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (!summary.isNullOrBlank()) {
                Text(summary, color = AppMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
        }
        if (trailing != null) trailing()
        else if (showChevron) Icon(Icons.Outlined.ChevronRight, null, tint = AppMuted)
    }
}

@Composable
fun ThinDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AppDivider))
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
                Text("上班", color = AppMuted, style = MaterialTheme.typography.labelLarge)
                WheelRow(startHour, startMinute, { startHour = it }, { startMinute = it })
                Text("下班", color = AppMuted, style = MaterialTheme.typography.labelLarge)
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
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.height(150.dp)
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(horizontal = 5.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
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
                        color = if (active) MaterialTheme.colorScheme.onSurface else AppMuted,
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
