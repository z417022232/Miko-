package com.example.worktimetracker.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocationSearching
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.ui.UiDayRecord
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import com.example.worktimetracker.domain.evidence.EvidenceSourceKind
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.FusedStatusFormatter
import com.example.worktimetracker.domain.evidence.FusedStatusSnapshot
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.evidence.SourceStatus
import com.example.worktimetracker.ui.CalendarHeatPresenter
import com.example.worktimetracker.ui.MonthSummary
import com.example.worktimetracker.ui.TodayStatusPresenter
import kotlinx.coroutines.delay
import com.example.worktimetracker.ui.calendarDayLabel
import com.example.worktimetracker.ui.dayKindText
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import com.example.worktimetracker.domain.engine.DayKind
import com.example.worktimetracker.domain.engine.PayrollPeriodRules
import com.example.worktimetracker.domain.payroll.PayrollBreakdown
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import com.example.worktimetracker.ui.theme.AppTheme

/**
 * 「日历」首页。
 *
 * 2026-09-16 重排（界面稿 Phase C）：
 * - 顶部「月度统计」按钮与「今日实时条」一并撤掉：实时状态并入下方「当日记录」卡，
 *   且**只在选中今天时**显示（历史日显示「该日无实时数据」）；
 * - 「本月工时 / 本月工资」卡从页底提到页顶 —— 打开日历第一眼就是本月结论。
 *
 */
@Composable
fun CalendarScreen(
    vm: WorkTimeViewModel,
    /** 从月卡进「工资条录入与核对」；参数是**当前日历所在月**（作为录入页的锚点） */
    onOpenSlip: (YearMonth) -> Unit = {}
) {
    val month by vm.month.collectAsState()
    val records by vm.records.collectAsState()
    val selectedDate by vm.selectedDate.collectAsState()
    val settings by vm.settings.collectAsState()
    val todayRecord by vm.todayRecord.collectAsState()
    val fused by vm.fusedStatus.collectAsState()
    val monthlySalaryCents by vm.monthlySalaryCents.collectAsState()
    val monthlySalaryPaymentDate by vm.monthlySalaryPaymentDate.collectAsState()
    val monthPayroll by vm.monthPayroll.collectAsState()
    val payBaseline by vm.payBaseline.collectAsState()
    val monthProjection by vm.monthProjection.collectAsState()
    // 「今天」= 当前工作日：夜班没下班时仍是上班那一天（见 WorkdayClock）
    val today by vm.workday.collectAsState()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val selected = records.firstOrNull { it.date == selectedDate }
        ?: UiDayRecord(selectedDate, "", finalMinutes = 0)
    // 整月模型会给「当天没有数据库记录」的日子合成一条 status="休息" 的占位行
    // （见 MonthlyRecordIndex.build），那是给热力图兜底的，不是事实。
    // 而「今天」这一天有更权威的来源：todayRecord（按当前工作日单日查库，30 秒心跳刷新）。
    // 有记录就用它；确实没记录就把占位行降级成「暂无记录」——否则卡片上会出现
    // 「休息」和实时块里的「今天还没有记录」并排打架（2026-09-16 真机发现）。
    // （委托属性（by collectAsState）不支持智能转换，先落到局部变量）
    val todayRow = todayRecord
    val cardRecord = when {
        selectedDate != today -> selected
        todayRow != null -> todayRow
        else -> selected.copy(status = "")
    }
    var monthDrag by remember { mutableFloatStateOf(0f) }
    var showMonthPicker by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var showSalaryEditor by remember { mutableStateOf(false) }
    var showPayroll by remember { mutableStateOf(false) }
    var batchMode by remember { mutableStateOf(false) }
    var batchDates by remember(month) { mutableStateOf(emptySet<LocalDate>()) }
    var showBatchEditor by remember { mutableStateOf(false) }

    // 实时心跳：30 秒一次。撤掉顶部条幅后它仍有两个作用 ——
    // 让「当日记录」里的「实时计入」数字往前走，并把后台服务写进库的到岗/离岗拉回界面。
    // 与「今日」页同一口径：这里绝不写库。
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            vm.refreshToday()
            vm.refreshJourneyShadowStatus()
            delay(30_000L)
        }
    }

    val live = TodayStatusPresenter.displayMinutes(
        finalMinutes = todayRecord?.finalMinutes ?: 0,
        startMillis = todayRecord?.startMillis,
        endMillis = todayRecord?.endMillis,
        nowMillis = nowMillis,
        restDeductionMinutes = settings.restDeductionMinutes,
        fixedMinutes = if (settings.hasDefaultHours) settings.defaultWorkMinutes else null
    )
    // 当日记录卡里的「实时」块：只在选中**今天**（当前工作日）时才有内容。
    // 融合快照与四源健康都是内存态，不落库、也不往历史日回填 ——
    // 给历史日编一份"当时的实时状态"只会误导，那里一律显示「该日无实时数据」。
    val sourceHealth by vm.sourceHealth.collectAsState()
    val evidenceRefresh by vm.evidenceRefresh.collectAsState()
    val journeyStatus by vm.journeyShadowStatus.collectAsState()
    val todayLive = TodayLiveInfo(
            liveMinutes = live,
            headline = TodayStatusPresenter.headline(todayRecord),
            placeSentence = FusedStatusFormatter.placeSentence(fused),
            place = fused?.place,
            decision = fused?.decision,
            basis = FusedStatusFormatter.basisLabel(fused),
            confidenceLevel = FusedStatusFormatter.confidenceLevel(fused),
            confidenceFraction = (fused?.confidence?.toFloat() ?: 0f).coerceIn(0f, 1f),
            confidencePercent = fused?.let { FusedStatusFormatter.confidenceLabel(it) },
            health = sourceHealth,
            refreshing = evidenceRefresh.running,
            refreshMessage = evidenceRefresh.message
        )
    val cells = remember(month, records, selectedDate, today) {
        CalendarHeatPresenter.buildCells(month, records, today, selectedDate)
    }
    val summary = remember(records) { CalendarHeatPresenter.summarize(records) }
    val isCurrentMonth = month == YearMonth.from(today)
    val payrollRules = remember { PayrollPeriodRules() }
    // 只往下提示：翻回 3 月还显示"下一个假期还有 12 天"没有意义
    val holidayTip = remember(month, today) {
        if (month < YearMonth.from(today)) null
        else CalendarHeatPresenter.nextHoliday(today)?.let(CalendarHeatPresenter::holidayTipText)
    }
    val paymentLabel = remember(month, monthlySalaryPaymentDate) {
        val date = monthlySalaryPaymentDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val label = date?.let { runCatching { payrollRules.displayLabel(month, it) }.getOrNull() }
        label ?: "实发工资以厂里工资单为准，可手动录入存档"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader(
            title = "首页",
            subtitle = if (isCurrentMonth) {
                "${TodayStatusPresenter.weekLabel(today)} · 已记录 ${summary.workDays} 天"
            } else {
                "已记录 ${summary.workDays} 天 · 点格子看当天"
            },
            action = {
                // 顶部只留「回到今天」。原「月度统计」按钮已撤（月度结论已由本月卡 + 年度统计页承担）
                IconButton(onClick = { vm.today() }) {
                    Icon(Icons.Outlined.Today, contentDescription = "回到今天", tint = AppTheme.colors.blue)
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        AuthorityStatusCard(
            journeyStatus = journeyStatus,
            live = todayLive,
            onRefreshEvidence = { vm.refreshEvidenceNow() },
            onConsumeRefreshMessage = { vm.clearEvidenceRefreshMessage() }
        )
        Spacer(Modifier.height(12.dp))
        SelectedDayCard(
            record = cardRecord,
            dayPayCents = vm.dailyPayCents(cardRecord.finalMinutes),
            hasBaseline = payBaseline != null,
            onEdit = { showDetail = true }
        )
        Spacer(Modifier.height(12.dp))
        // 月份切换过渡动画：按新旧月份大小决定滑动方向（去下一个月，新内容从右进；
        // 回上一个月，新内容从左进），250ms + 透明度，与系统页面切换节奏一致。
        // 标题用 targetMonth（每次动画的入参固定），避免退场内容跟着状态一起变字。
        AnimatedContent(
            targetState = month,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(tween(250)) { if (forward) it / 3 else -it / 3 } +
                    fadeIn(tween(250))) togetherWith
                    (slideOutHorizontally(tween(250)) { if (forward) -it / 3 else it / 3 } +
                        fadeOut(tween(250)))
            },
            label = "monthSwitch"
        ) { targetMonth ->
            Column {
                MonthToolbar(
                    month = targetMonth,
                    recordedDays = summary.workDays,
                    isCurrentMonth = isCurrentMonth,
                    onPrevious = vm::previousMonth,
                    onNext = vm::nextMonth,
                    onToday = vm::today,
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
                HeatMonthCard(
                    cells = cells,
                    onDayClick = {
                        if (batchMode) {
                            batchDates = if (it.date in batchDates) batchDates - it.date else batchDates + it.date
                        } else {
                            vm.select(it.date)
                        }
                    },
                    modifier = Modifier
                        // 拖动跟手：translationX 在 draw 阶段读状态，不触发重组
                        .graphicsLayer { translationX = monthDrag }
                        .pointerInput(month) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when {
                                        monthDrag > 120f -> vm.previousMonth()
                                        monthDrag < -120f -> vm.nextMonth()
                                    }
                                    monthDrag = 0f
                                },
                                onDragCancel = { monthDrag = 0f },
                                onHorizontalDrag = { _, amount -> monthDrag += amount }
                            )
                        }
                )
                HeatLegend()
                HolidayTipLine(holidayTip)
            }
        }
        Spacer(Modifier.height(12.dp))
        MonthSummaryCard(
            summary = summary,
            salaryCents = monthlySalaryCents,
            payroll = monthPayroll,
            projection = monthProjection,
            paymentLabel = paymentLabel,
            onOpenPayroll = { showPayroll = true },
            onOpenSlip = { onOpenSlip(month) }
        )
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
    if (showPayroll) {
        PayrollDetailDialog(
            month = month,
            summary = summary,
            payroll = monthPayroll,
            recordedSalaryCents = monthlySalaryCents,
            onEditSalary = { showPayroll = false; showSalaryEditor = true },
            onDismiss = { showPayroll = false }
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

/**
 * 月份工具栏：`‹ 2026 年 9 月 / 本月 · 已记录 12 天 今天 ›`。
 *
 * 中间整块可点，弹出月份选择器（保留原有的跳月能力）；「今天」只在离开当月时可点，
 * 避免回到当前月时按钮看起来能按却没反应。
 */
@Composable
private fun MonthToolbar(
    month: YearMonth,
    recordedDays: Int,
    isCurrentMonth: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onMonthClick: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "上个月", tint = AppTheme.colors.muted)
        }
        Column(
            Modifier
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onMonthClick)
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "${month.year} 年 ${month.monthValue} 月",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (isCurrentMonth) "本月 · 已记录 $recordedDays 天" else "已记录 $recordedDays 天",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.muted
            )
        }
        TextButton(onClick = onToday, enabled = !isCurrentMonth) { Text("今天") }
        IconButton(onClick = onNext) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "下个月", tint = AppTheme.colors.muted)
        }
    }
}

/**
 * 「本月工资明细」（稿子屏 08 的收敛版）。
 *
 * 计薪规则 v2：给出推算的「应发构成 → 扣款 → 预计到手」。已录入实发时并列显示 ——
 * 两者的差额本身就是信息（哪个月浮动项被扣了）。**推算值不落库。**
 */
@Composable
private fun PayrollDetailDialog(
    month: YearMonth,
    summary: MonthSummary,
    payroll: PayrollBreakdown?,
    recordedSalaryCents: Long?,
    onEditSalary: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${month.monthValue} 月工资明细") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "按当前计薪规则推算",
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTheme.colors.muted
                )
                if (payroll == null) {
                    Text("还没有可推算的工时数据。", color = AppTheme.colors.muted)
                } else {
                    PayLine("总工时", durationText(summary.totalMinutes))
                    PayLine("其中加班", durationText(summary.overtimeMinutes))
                    ThinDivider()
                    PayrollCompositionLines(payroll)
                    Text(
                        "加班工资按公司包干小时数计算，不是按真实加班时长；实际以厂里工资单为准。",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.muted
                    )
                }
                if (recordedSalaryCents != null) {
                    ThinDivider()
                    PayLine("已录入实发工资", formatCents(recordedSalaryCents), emphasize = true)
                }
                // 月卡上的「录入实发工资」已并入工资条录入页；这里留一个次级入口，
                // 保证「月度实发」这个计薪基准在任何时候都改得到。
                ThinDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "月度实发是计薪基准（基准月单价由它反推）",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTheme.colors.muted,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onEditSalary) {
                        Text(if (recordedSalaryCents == null) "录入" else "修改")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun dayBadgeColor(kind: DayKind): Color = when (kind) {
    DayKind.FESTIVAL -> AppTheme.colors.red
    DayKind.MAKEUP_WORKDAY -> AppTheme.colors.orange
    else -> AppTheme.colors.muted
}

/**
 * 选中日的实时信息（只有「选中的就是今天」时才构造，其余时候为 null）。
 *
 * 全部来自内存态：融合快照（地点 / 依据 / 可信度）+ 四源健康 + 实时计入分钟。**不落库**。
 *
 * 位置这块按用户 2026-09-16 口径改成「一眼看懂」三段式：主句（[placeSentence]）
 * + 判断依据（[basis]）+ 可视化可信度（[confidenceLevel] / [confidenceFraction]），
 * 不再直译成「位置：家 · 置信度 80%」这种看不出所以然的写法。
 */
private data class TodayLiveInfo(
    val liveMinutes: TodayStatusPresenter.TodayMinutes,
    val headline: TodayStatusPresenter.Headline,
    val placeSentence: String,
    val place: ResolvedPlace?,
    val decision: FusedDecision?,
    val basis: String?,
    val confidenceLevel: FusedStatusFormatter.ConfidenceLevel,
    val confidenceFraction: Float,
    val confidencePercent: String?,
    val health: Map<EvidenceSourceKind, SourceStatus>,
    val refreshing: Boolean,
    val refreshMessage: String?
)

/** 地点 → 图标（UI 层唯一映射点，与四源图标一个思路）。 */
private fun placeIcon(place: ResolvedPlace?): ImageVector = when (place) {
    ResolvedPlace.HOME -> Icons.Outlined.Home
    ResolvedPlace.COMPANY -> Icons.Outlined.Business
    ResolvedPlace.MOVING -> Icons.Outlined.DirectionsWalk
    else -> Icons.Outlined.LocationSearching
}

/** 决策档位 → 图标色：已确认=蓝、只能维持=橙、不确定=灰。 */
@Composable
private fun placeTint(decision: FusedDecision?): Color = when (decision) {
    FusedDecision.CONFIRMED -> AppTheme.colors.blue
    FusedDecision.MAINTAINED -> AppTheme.colors.orange
    else -> AppTheme.colors.muted
}

/**
 * 「当日记录」卡。
 *
 * 2026-09-16 起它同时承接原「今日」页的两块内容（打卡去向 + 实时状态）：
 * - 到岗 / 离岗 / 离家 / 到家时间 = 已经发生的**打卡事实**，任何一天都能显示；
 * - [live] != null（选中的是今天）= 当前实时判断：在上班 / 已下班、位置、置信度，
 *   以及 GPS / Wi-Fi / 蓝牙 / 基站四源状态徽标（正常蓝、异常红，点图标重取一次并给短反馈）。
 *   选中历史日时这一块退化成一句「该日无实时数据」。
 */
@Composable
private fun AuthorityStatusCard(
    journeyStatus: String,
    live: TodayLiveInfo,
    onRefreshEvidence: () -> Unit,
    onConsumeRefreshMessage: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("实时状态", style = MaterialTheme.typography.labelMedium, color = AppTheme.colors.muted)
            Spacer(Modifier.height(6.dp))
            Text(
                journeyStatus.lineSequence().firstOrNull() ?: "位置暂时判断不出来",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            journeyStatus.lineSequence().drop(1).firstOrNull()?.let {
                Text(it, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(10.dp))
            LiveStatusBlock(live, onRefreshEvidence, onConsumeRefreshMessage, showHeadline = false)
        }
    }
}

@Composable
private fun SelectedDayCard(
    record: UiDayRecord,
    dayPayCents: Long?,
    hasBaseline: Boolean,
    onEdit: () -> Unit
) {
    val weekday = record.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)
    val status = record.status.ifBlank { "暂无记录" }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${record.date.monthValue}月${record.date.dayOfMonth}日 · $weekday", color = AppTheme.colors.muted)
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            formatMinutes(record.finalMinutes),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        StatusPill(status, statusColor(status))
                        // A6: 待确认 / 已复核 状态
                        if (record.needsReview) StatusPill("待确认", AppTheme.colors.red)
                        else if (record.reviewAcknowledged) StatusPill("已复核", AppTheme.colors.green)
                    }
                }
                FilledTonalButton(onClick = onEdit) {
                    Icon(Icons.Outlined.Edit, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("编辑")
                }
            }
            if (record.finalMinutes > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        dayPayCents != null -> "当日工资 ≈ ${formatCents(dayPayCents)}"
                        !hasBaseline -> "当日工资：暂无基准（录入一个月实发工资后自动校准）"
                        else -> "当日工资：这天没有计薪工时"
                    },
                    color = if (dayPayCents != null) AppTheme.colors.orange else AppTheme.colors.muted,
                    style = MaterialTheme.typography.labelMedium
                )
            }
            if (record.needsReview && !record.reviewReason.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(record.reviewReason, color = AppTheme.colors.red, style = MaterialTheme.typography.bodySmall)
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
                    Icon(Icons.Outlined.AccessTime, null, tint = AppTheme.colors.muted, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("没有到达和离开记录", color = AppTheme.colors.muted)
                }
            }
            dayKindText(record.dayKind, record.holidayName, record.finalMinutes > 0)?.let {
                Text(it, color = dayBadgeColor(record.dayKind), modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/**
 * 当日记录卡里的「实时块」。
 *
 * 原先是日历顶部的常驻条幅（已删除的 `TodayLiveStrip`）：占着首页黄金位置，内容却只在
 * 今天成立 —— 翻到别的月份时它还在报「今天」的状态，反而误导。现在并入当日记录卡：
 * 选中今天 → 实时状态 + 四源徽标；选中历史日 → 那一句「该日无实时数据」。
 *
 * 四源一次环境采样本来就一起拿到，所以点任意一个图标都是**整体重取一次**，
 * 由 `WorkTimeViewModel.refreshEvidenceNow()` 拉起前台服务做一次性定位 + 环境扫描。
 */
@Composable
private fun LiveStatusBlock(
    live: TodayLiveInfo,
    onRefresh: () -> Unit,
    onConsumeMessage: () -> Unit,
    showHeadline: Boolean = true
) {
    Column(Modifier.fillMaxWidth()) {
        if (showHeadline) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(live.headline.text, toneColor(live.headline.tone))
                if (live.liveMinutes.running) StatusPill("计时中", AppTheme.colors.blue)
                Text(
                    "实时计入 ${durationText(live.liveMinutes.minutes)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = AppTheme.colors.muted
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        // 位置主句：图标 + 一句结论（现在在家 / 现在在公司 / 位置暂时判断不出来）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                placeIcon(live.place),
                contentDescription = null,
                tint = placeTint(live.decision),
                modifier = Modifier.size(19.dp)
            )
            Text(
                live.placeSentence,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (live.basis != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                "判断依据：${live.basis}",
                style = MaterialTheme.typography.labelSmall,
                color = AppTheme.colors.muted
            )
        }
        Spacer(Modifier.height(9.dp))
        ConfidenceMeter(
            level = live.confidenceLevel,
            fraction = live.confidenceFraction,
            percentText = live.confidencePercent
        )
        Spacer(Modifier.height(12.dp))
        EvidenceSourceRow(
            health = live.health,
            refreshing = live.refreshing,
            onRefresh = onRefresh
        )
        if (live.refreshMessage != null) {
            Spacer(Modifier.height(8.dp))
            EvidenceRefreshBanner(
                message = live.refreshMessage,
                isRunning = live.refreshing,
                onConsume = onConsumeMessage
            )
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
        Icon(Icons.Outlined.AccessTime, null, tint = AppTheme.colors.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(
            listOfNotNull(
                arrival?.let { "$it$arrivalLabel" },
                departure?.let { "$it$departureLabel" }
            ).joinToString("       "),
            color = AppTheme.colors.muted
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
                Text("${month.monthValue}月工资，默认次月15日发放", color = AppTheme.colors.muted)
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
        Text(label, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
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
                listOfNotNull(dayKindText(record.dayKind, record.holidayName, record.finalMinutes > 0), record.status.ifBlank { null }).joinToString(" · ").ifBlank { "暂无记录" },
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
            // A6: 系统判定需确认 → 展示原因 + 一键认可（认可不改值，不会锁死自动算法）
            if (record.needsReview) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppTheme.colors.red.copy(alpha = 0.08f)),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text("系统判定需确认", color = AppTheme.colors.red, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            record.reviewReason ?: "自动识别结果需要人工确认",
                            color = AppTheme.colors.red,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = {
                            vm.acknowledgeReview(record.date, record.note.orEmpty()) { if (it == null) onDismiss() }
                        }) { Text("认可，不改值") }
                    }
                }
                Spacer(Modifier.height(16.dp))
            } else if (record.reviewAcknowledged) {
                Text("已复核", color = AppTheme.colors.green, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(12.dp))
            }
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
                Text("备注：${record.note}", color = AppTheme.colors.muted)
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
        Text(label, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
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
    // record.shift 是显示标签（"白班"/"夜班"），而 ShiftSelector 用枚举名比较。
    // 旧实现写成 `record.shift ?: "DAY_SHIFT"`：既有记录打开时两个胶囊都不选中，
    // 且未改班次直接保存会把中文标签写回 shift 字段（id=15/158 的成因）。
    // StatisticsScreen 早已按此方式转换，此处对齐。
    var shift by remember(record.date) { mutableStateOf(if (record.shift == "夜班") "NIGHT_SHIFT" else "DAY_SHIFT") }
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
        Text(title, modifier = Modifier.weight(1f), color = AppTheme.colors.muted)
        TextButton(onClick = onStart) { Text(formatClock(start)) }
        Text("—", color = AppTheme.colors.muted)
        TextButton(onClick = onEnd) { Text(formatClock(end)) }
    }
}
