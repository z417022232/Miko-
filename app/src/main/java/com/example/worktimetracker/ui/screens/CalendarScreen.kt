package com.example.worktimetracker.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.ui.UiDayRecord
import com.example.worktimetracker.ui.calendarDayLabel
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import com.example.worktimetracker.domain.engine.PayrollPeriodRules
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun CalendarScreen(vm: WorkTimeViewModel) {
    val month by vm.month.collectAsState()
    val records by vm.records.collectAsState()
    val selectedDate by vm.selectedDate.collectAsState()
    val monthlySalaryCents by vm.monthlySalaryCents.collectAsState()
    val monthlySalaryPaymentDate by vm.monthlySalaryPaymentDate.collectAsState()
    val selected = records.firstOrNull { it.date == selectedDate }
        ?: UiDayRecord(selectedDate, "", finalMinutes = 0)
    var monthDrag by remember { mutableFloatStateOf(0f) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var showSalaryEditor by remember { mutableStateOf(false) }
    var batchMode by remember { mutableStateOf(false) }
    var batchDates by remember(month) { mutableStateOf(emptySet<LocalDate>()) }
    var showBatchEditor by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader(
            title = "工时记录",
            subtitle = "每天的状态与计入工时",
            action = {
                IconButton(onClick = { vm.today() }) {
                    Icon(Icons.Outlined.Today, contentDescription = "回到今天", tint = AppBlue)
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        MonthOverviewCard(month, records, monthlySalaryCents, monthlySalaryPaymentDate) { showSalaryEditor = true }
        Spacer(Modifier.height(12.dp))
        MonthToolbar(
            month = month,
            onPrevious = vm::previousMonth,
            onNext = vm::nextMonth,
            onMonthClick = { showMonthPicker = true }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (batchMode) {
                TextButton(onClick = { batchMode = false; batchDates = emptySet() }) { Text("取消") }
                Button(onClick = { showBatchEditor = true }, enabled = batchDates.isNotEmpty()) {
                    Text("修改已选 ${batchDates.size} 天")
                }
            } else {
                OutlinedButton(onClick = { batchMode = true }) { Text("批量修改") }
            }
        }
        Spacer(Modifier.height(8.dp))
        CalendarCard(
            records = records,
            selectedDate = selectedDate,
            batchMode = batchMode,
            selectedDates = batchDates,
            modifier = Modifier.pointerInput(month) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            monthDrag > 80f -> vm.previousMonth()
                            monthDrag < -80f -> vm.nextMonth()
                        }
                        monthDrag = 0f
                    },
                    onDragCancel = { monthDrag = 0f },
                    onHorizontalDrag = { _, amount -> monthDrag += amount }
                )
            },
            onClick = {
                if (batchMode) {
                    batchDates = if (it.date in batchDates) batchDates - it.date else batchDates + it.date
                } else vm.select(it.date)
            }
        )
        Spacer(Modifier.height(12.dp))
        SelectedDayCard(selected, onEdit = { showDetail = true })
        Spacer(Modifier.height(12.dp))
    }

    if (showMonthPicker) {
        MonthPickerDialog(month, onDismiss = { showMonthPicker = false }) { year, value ->
            vm.jumpToMonth(year.toString(), value.toString())
            showMonthPicker = false
        }
    }
    if (showDetail) {
        DayDetailSheet(selected, vm, onDismiss = { showDetail = false })
    }
    if (showSalaryEditor) {
        SalaryDialog(
            month = month,
            salaryCents = monthlySalaryCents,
            paymentDate = monthlySalaryPaymentDate,
            onDismiss = { showSalaryEditor = false },
            onSave = {
                vm.saveMonthlySalary(it.first, it.second)
                showSalaryEditor = false
            }
        )
    }
    if (showBatchEditor) {
        BatchManualDialog(
            count = batchDates.size,
            onDismiss = { showBatchEditor = false },
            onSave = { hours, shift, note ->
                vm.saveBatchManualHours(batchDates, hours, shift, note)
                showBatchEditor = false
                batchMode = false
                batchDates = emptySet()
            }
        )
    }
}

@Composable
private fun MonthOverviewCard(
    month: YearMonth,
    records: List<UiDayRecord>,
    salaryCents: Long?,
    paymentDate: String?,
    onSalaryClick: () -> Unit
) {
    val total = records.sumOf { it.finalMinutes }
    val workDays = records.count { it.finalMinutes > 0 }
    val reviewDays = records.count { it.needsReview }
    Card(
        colors = CardDefaults.cardColors(containerColor = AppBlue),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${month.monthValue}月累计", color = Color.White.copy(alpha = 0.78f))
                    Text(
                        formatMinutes(total),
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                OverviewMetric("工作", "${workDays}天")
                Box(Modifier.size(1.dp, 36.dp).background(Color.White.copy(alpha = 0.25f)))
                OverviewMetric("待确认", "${reviewDays}天")
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onSalaryClick)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        paymentDate?.let {
                            runCatching { PayrollPeriodRules().displayLabel(month, LocalDate.parse(it)) }.getOrNull()
                        } ?: "${month.monthValue}月工资（次月15日发放）",
                        color = Color.White.copy(alpha = 0.72f),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(
                        salaryCents?.let(::formatSalary) ?: "点击录入",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
                Icon(Icons.Outlined.Edit, contentDescription = "录入实发工资", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun OverviewMetric(label: String, value: String) {
    Column(
        Modifier.padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = Color.White, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.72f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun MonthToolbar(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onMonthClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        TextButton(onClick = onPrevious) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
        TextButton(onClick = onMonthClick) {
            Icon(Icons.Outlined.Event, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("${month.year}年${month.monthValue}月", fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onNext) { Text("›", style = MaterialTheme.typography.headlineSmall) }
    }
}

@Composable
private fun CalendarCard(
    records: List<UiDayRecord>,
    selectedDate: LocalDate,
    batchMode: Boolean,
    selectedDates: Set<LocalDate>,
    modifier: Modifier = Modifier,
    onClick: (UiDayRecord) -> Unit
) {
    val leading = records.firstOrNull()?.date?.dayOfWeek?.value?.rem(7) ?: 0
    val rawCells: List<UiDayRecord?> = List(leading) { null } + records
    val trailing = (7 - rawCells.size % 7) % 7
    val cells = rawCells + List(trailing) { null }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth()) {
                listOf("日", "一", "二", "三", "四", "五", "六").forEachIndexed { index, label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        color = if (index == 0 || index == 6) AppRed.copy(alpha = 0.8f) else AppMuted,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            cells.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { record ->
                        Box(Modifier.weight(1f)) {
                            if (record == null) Spacer(Modifier.height(62.dp))
                            else DayCell(record, if (batchMode) record.date in selectedDates else record.date == selectedDate, onClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(record: UiDayRecord, selected: Boolean, onClick: (UiDayRecord) -> Unit) {
    val today = record.date == LocalDate.now()
    val color = statusColor(record.status)
    val subLabel = when {
        record.finalMinutes > 0 -> calendarDayLabel(record.shift, record.finalMinutes)
        !record.holidayName.isNullOrBlank() -> record.holidayName.take(3)
        record.status.isNotBlank() -> shortStatus(record.status)
        else -> ""
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AppBlue.copy(alpha = 0.11f) else Color.Transparent)
            .then(if (today && !selected) Modifier.border(1.dp, AppBlue.copy(alpha = 0.45f), RoundedCornerShape(12.dp)) else Modifier)
            .clickable { onClick(record) }
            .padding(top = 4.dp)
    ) {
        Text(
            record.date.dayOfMonth.toString(),
            fontWeight = if (selected || today) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) AppBlue else MaterialTheme.colorScheme.onSurface
        )
        if (!record.holidayName.isNullOrBlank()) {
            Text(
                record.holidayName.take(3),
                color = AppOrange,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
        if (subLabel.isNotBlank() && (record.finalMinutes > 0 || record.holidayName.isNullOrBlank())) {
            Text(subLabel, color = color, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun SelectedDayCard(record: UiDayRecord, onEdit: () -> Unit) {
    val weekday = record.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
    val status = record.status.ifBlank { "暂无记录" }
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${record.date.monthValue}月${record.date.dayOfMonth}日 · $weekday", color = AppMuted)
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            formatMinutes(record.finalMinutes),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        StatusPill(status, statusColor(status))
                    }
                }
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("编辑")
                }
            }
            Spacer(Modifier.height(10.dp))
            LocationEventLine(
                arrival = record.companyArrivalText,
                departure = record.companyDepartureText,
                arrivalLabel = "到达公司",
                departureLabel = "离开公司"
            )
            LocationEventLine(
                arrival = record.homeArrivalText,
                departure = record.homeDepartureText,
                arrivalLabel = "到达家中",
                departureLabel = "离开家中"
            )
            if (record.companyArrivalText == null && record.companyDepartureText == null &&
                record.homeArrivalText == null && record.homeDepartureText == null
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccessTime, null, tint = AppMuted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("没有到达和离开记录", color = AppMuted)
                }
            }
            if (!record.holidayName.isNullOrBlank()) {
                Text(record.holidayName, color = AppOrange, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun LocationEventLine(
    arrival: String?,
    departure: String?,
    arrivalLabel: String,
    departureLabel: String
) {
    if (arrival == null && departure == null) return
    Row(
        Modifier.fillMaxWidth().padding(top = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.AccessTime, null, tint = AppMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            listOfNotNull(
                arrival?.let { "$it$arrivalLabel" },
                departure?.let { "$it$departureLabel" }
            ).joinToString("       "),
            color = AppMuted
        )
    }
}

@Composable
private fun SalaryDialog(
    month: YearMonth,
    salaryCents: Long?,
    paymentDate: String?,
    onDismiss: () -> Unit,
    onSave: (Pair<String, String>) -> Unit
) {
    val defaultPaymentDate = PayrollPeriodRules().defaultPaymentDateForPayrollMonth(month).toString()
    var value by remember(month, salaryCents) {
        mutableStateOf(salaryCents?.let { "%.2f".format(Locale.CHINA, it / 100.0) }.orEmpty())
    }
    var dateValue by remember(month, paymentDate) { mutableStateOf(paymentDate ?: defaultPaymentDate) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${month.monthValue}月工资") },
        text = {
            Column {
                Text("${month.monthValue}月工资，默认次月15日发放", color = AppMuted)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { input ->
                        if (input.matches(Regex("""\d{0,9}([.]\d{0,2})?"""))) value = input
                    },
                    label = { Text("实发金额") },
                    prefix = { Text("¥ ") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = dateValue,
                    onValueChange = { dateValue = it },
                    label = { Text("实际发放日期（YYYY-MM-DD）") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(value to dateValue) },
                enabled = value.toDoubleOrNull() != null && runCatching { LocalDate.parse(dateValue) }.isSuccess
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun formatSalary(cents: Long): String =
    "¥%,.2f".format(Locale.CHINA, cents / 100.0)

@Composable
private fun MonthPickerDialog(
    month: YearMonth,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit
) {
    var year by remember(month) { mutableIntStateOf(month.year) }
    var monthValue by remember(month) { mutableIntStateOf(month.monthValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换月份") },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MonthNumberPicker("年份", year, 2000..2100, "年", { year = it }, Modifier.weight(1f))
                MonthNumberPicker("月份", monthValue, 1..12, "月", { monthValue = it }, Modifier.weight(1f))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(year, monthValue) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun MonthNumberPicker(
    label: String,
    value: Int,
    range: IntRange,
    unit: String,
    onValue: (Int) -> Unit,
    modifier: Modifier
) {
    Column(modifier) {
        Text(label, color = AppMuted, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onValue((value - 1).coerceAtLeast(range.first)) }) { Text("−") }
            Text("$value$unit", modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
            TextButton(onClick = { onValue((value + 1).coerceAtMost(range.last)) }) { Text("+") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayDetailSheet(record: UiDayRecord, vm: WorkTimeViewModel, onDismiss: () -> Unit) {
    var showManual by remember { mutableStateOf(false) }
    var showSegments by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text("${record.date.monthValue}月${record.date.dayOfMonth}日", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                listOfNotNull(record.holidayName, record.status.ifBlank { null }).joinToString(" · ").ifBlank { "暂无记录" },
                color = statusColor(record.status)
            )
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DetailMetric("到达", record.startText ?: "--")
                    DetailMetric("离开", record.endText ?: "--")
                    DetailMetric("计入", formatMinutes(record.finalMinutes))
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { showManual = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Edit, null)
                Spacer(Modifier.size(8.dp))
                Text("修改当天工时")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showSegments = true }, modifier = Modifier.fillMaxWidth()) {
                Text("拆分为两个时间段")
            }
            Spacer(Modifier.height(16.dp))
            Text("当天状态", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("请假" to "LEAVE", "休息" to "REST", "外出" to "OUTSIDE").forEach { (label, value) ->
                    FilterChip(
                        selected = record.status == label,
                        onClick = { vm.updateStatus(record.date, value); onDismiss() },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            if (!record.note.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("备注：${record.note}", color = AppMuted)
            }
        }
    }
    if (showManual) {
        ManualHoursDialog(record, vm, onDismiss = { showManual = false }) {
            showManual = false
            onDismiss()
        }
    }
    if (showSegments) {
        SegmentDialog(record, vm, onDismiss = { showSegments = false }) {
            showSegments = false
            onDismiss()
        }
    }
}

@Composable
private fun DetailMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold)
        Text(label, color = AppMuted, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ManualHoursDialog(
    record: UiDayRecord,
    vm: WorkTimeViewModel,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val lastManual by vm.lastManualHoursText.collectAsState()
    var hours by remember(record.date, lastManual) {
        mutableStateOf(if (record.finalMinutes > 0) "%.1f".format(record.finalMinutes / 60.0) else lastManual)
    }
    var note by remember(record.date) { mutableStateOf(record.note.orEmpty()) }
    var setDefault by remember { mutableStateOf(false) }
    var shift by remember(record.date) { mutableStateOf(record.shift ?: "DAY_SHIFT") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改计入工时") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ShiftSelector(shift) { shift = it }
                OutlinedTextField(
                    value = hours,
                    onValueChange = { hours = it },
                    label = { Text("工时") },
                    suffix = { Text("小时") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(setDefault, onCheckedChange = { setDefault = it })
                    Text("同时设为今后的默认工时")
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.saveManualHours(record.date, hours, setDefault, note, shift); onSaved() }) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ShiftSelector(value: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = value == "DAY_SHIFT",
            onClick = { onChange("DAY_SHIFT") },
            label = { Text("白班") },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = value == "NIGHT_SHIFT",
            onClick = { onChange("NIGHT_SHIFT") },
            label = { Text("夜班") },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun BatchManualDialog(
    count: Int,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var hours by remember { mutableStateOf("11") }
    var shift by remember { mutableStateOf("DAY_SHIFT") }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量修改 $count 天") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("所选日期将使用同一班次和计入工时，原有到岗、离岗、离家、到家时间保持不变。")
                ShiftSelector(shift) { shift = it }
                OutlinedTextField(
                    value = hours,
                    onValueChange = { hours = it },
                    label = { Text("计入工时") },
                    suffix = { Text("小时") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可选）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(hours, shift, note) }, enabled = hours.toDoubleOrNull() != null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private enum class SegmentTime { FIRST_START, FIRST_END, SECOND_START, SECOND_END }

@Composable
private fun SegmentDialog(
    record: UiDayRecord,
    vm: WorkTimeViewModel,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var firstStart by remember { mutableIntStateOf(8 * 60) }
    var firstEnd by remember { mutableIntStateOf(12 * 60) }
    var secondStart by remember { mutableIntStateOf(13 * 60) }
    var secondEnd by remember { mutableIntStateOf(18 * 60) }
    var deductRest by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SegmentTime?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("拆分时间段") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SegmentTimeRow("第一段", firstStart, firstEnd, { editing = SegmentTime.FIRST_START }, { editing = SegmentTime.FIRST_END })
                SegmentTimeRow("第二段", secondStart, secondEnd, { editing = SegmentTime.SECOND_START }, { editing = SegmentTime.SECOND_END })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(deductRest, onCheckedChange = { deductRest = it })
                    Text("继续扣除默认休息时长")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveSplitSegments(
                    record.date,
                    formatClock(firstStart),
                    formatClock(firstEnd),
                    formatClock(secondStart),
                    formatClock(secondEnd),
                    deductRest
                )
                onSaved()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    editing?.let { target ->
        val value = when (target) {
            SegmentTime.FIRST_START -> firstStart
            SegmentTime.FIRST_END -> firstEnd
            SegmentTime.SECOND_START -> secondStart
            SegmentTime.SECOND_END -> secondEnd
        }
        TimeWheelDialog("选择时间", value, { editing = null }) { newValue ->
            when (target) {
                SegmentTime.FIRST_START -> firstStart = newValue
                SegmentTime.FIRST_END -> firstEnd = newValue
                SegmentTime.SECOND_START -> secondStart = newValue
                SegmentTime.SECOND_END -> secondEnd = newValue
            }
            editing = null
        }
    }
}

@Composable
private fun SegmentTimeRow(
    title: String,
    start: Int,
    end: Int,
    onStart: () -> Unit,
    onEnd: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), color = AppMuted)
        TextButton(onClick = onStart) { Text(formatClock(start)) }
        Text("—", color = AppMuted)
        TextButton(onClick = onEnd) { Text(formatClock(end)) }
    }
}
