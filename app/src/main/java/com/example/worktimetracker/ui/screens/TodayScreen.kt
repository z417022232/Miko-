package com.example.worktimetracker.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.FusedStatusFormatter
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.ui.TodayStatusPresenter
import com.example.worktimetracker.ui.YearStatsPresenter
import com.example.worktimetracker.ui.app.WorkTimeViewModel
import com.example.worktimetracker.ui.theme.AppTheme
import kotlinx.coroutines.delay
import java.time.YearMonth

private enum class TodayPage { HOME, MONTHLY }

/**
 * 「今日」一级页宿主（2026-09-16 起首页主体 = **XX 年数据统计**）。
 *
 * 原先把「今天算不算在上班」摆在这里占一级入口；现在这条实时结论已经并进
 * 日历页的「当日记录」卡（选中今天才显示），于是这一屏腾出来放年度统计：
 * 每月工时波动 / 每月实发波动 / 年休息日。今日的实时一行与手动打卡留在页面底部，
 * 保证「今天要补一次卡」这个高频动作不用先切页面。
 */
@Composable
fun TodayHost(vm: WorkTimeViewModel) {
    var page by remember { mutableStateOf(TodayPage.HOME) }
    BackHandler(page != TodayPage.HOME) { page = TodayPage.HOME }
    when (page) {
        TodayPage.HOME -> TodayScreen(
            vm,
            onOpenMonthly = { month ->
                vm.jumpToMonth(month.year.toString(), month.monthValue.toString())
                page = TodayPage.MONTHLY
            }
        )
        TodayPage.MONTHLY -> StatisticsScreen(vm, onBack = { page = TodayPage.HOME })
    }
}

@Composable
fun TodayScreen(vm: WorkTimeViewModel, onOpenMonthly: (YearMonth) -> Unit) {
    val stats by vm.yearStats.collectAsState()
    val snapshot = stats
    val today by vm.workday.collectAsState()
    // 选中的**月份下标**（0..11，与图表回调同一口径）；null = 跟随默认
    // （当年看当前月，往年看最后一个有数据的月）
    var pickedWorkIndex by remember(snapshot?.year) { mutableStateOf<Int?>(null) }
    var pickedSalaryIndex by remember(snapshot?.year) { mutableStateOf<Int?>(null) }

    // 心跳 30 秒：只为把外部写入（前台服务 / 手动补录）拉回来，并让「计时中」的数字往前走。
    // 顺手重算年度统计 —— 不然刚补录完今天、切过来还是旧数。全程只读，不写库。
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshYearStats()
            delay(30_000L)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        ScreenHeader(
            title = "${snapshot?.year ?: today.year} 年数据统计",
            subtitle = snapshot?.let { "已记录 ${it.workedDays} 天 · 合计 ${durationText(it.totalMinutes)}" }
                ?: "正在读取今年的记录…",
            action = {
                IconButton(onClick = { vm.refreshYearStats(); vm.refreshToday() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = AppTheme.colors.blue)
                }
            }
        )
        Spacer(Modifier.height(12.dp))
        if (snapshot == null) {
            StatCard(title = "年度统计", accentText = "统计中…", accentColor = AppTheme.colors.muted) {
                Text("正在汇总今年的工时与工资记录", color = AppTheme.colors.muted, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
        } else {
            MonthHoursCard(
                snapshot,
                pickedWorkIndex,
                onSelect = { pickedWorkIndex = it },
                onOpenMonth = onOpenMonthly
            )
            Spacer(Modifier.height(12.dp))
            MonthSalaryCard(snapshot, pickedSalaryIndex, onSelect = { pickedSalaryIndex = it })
            Spacer(Modifier.height(12.dp))
            RestDaysCard(snapshot)
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// 每月工时波动
// ---------------------------------------------------------------------------

/**
 * 每月工时卡。
 *
 * [pickedIndex] 是**月份下标**（0..11），与 [MonthBarChart.onSelect] 回调同一口径 ——
 * 早先这里误当 1-based 月份又减了一次 1，导致选月整体偏一月（点最左还会算出 -1 → "暂无数据"）。
 */
@Composable
private fun MonthHoursCard(
    stats: YearStatsPresenter.YearStats,
    pickedIndex: Int?,
    onSelect: (Int) -> Unit,
    onOpenMonth: (YearMonth) -> Unit
) {
    val index = pickedIndex ?: defaultWorkIndex(stats)
    val point = stats.months.getOrNull(index)
    StatCard(
        title = "每月工时",
        accentText = point?.let { "${it.month} 月 · ${durationText(it.minutes)}" } ?: "暂无数据",
        accentColor = AppTheme.colors.blue,
        caption = point?.let { "出勤 ${it.workedDays} 天" }
    ) {
        MonthBarChart(
            values = stats.months.map { it.minutes },
            selectedIndex = index,
            onSelect = onSelect,
            color = AppTheme.colors.blue
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "点一下或横向滑过柱子看某个月 · 全年合计 ${durationText(stats.totalMinutes)}",
            color = AppTheme.colors.muted,
            style = MaterialTheme.typography.labelSmall
        )
        if (point != null) {
            TextButton(
                onClick = { onOpenMonth(YearMonth.of(stats.year, point.month)) },
                modifier = Modifier.align(Alignment.End)
            ) { Text("查看 ${point.month} 月工时明细") }
        }
    }
}

// ---------------------------------------------------------------------------
// 每月实发工资波动
// ---------------------------------------------------------------------------

/** 每月实发工资卡。口径同 [MonthHoursCard]：[pickedIndex] 是 0-based 月份下标。 */
@Composable
private fun MonthSalaryCard(
    stats: YearStatsPresenter.YearStats,
    pickedIndex: Int?,
    onSelect: (Int) -> Unit
) {
    val paidMonths = stats.months.count { it.salaryCents != null }
    val index = pickedIndex ?: defaultSalaryIndex(stats)
    val point = stats.months.getOrNull(index)
    StatCard(
        title = "每月实发工资",
        accentText = point?.salaryCents?.let(::formatCents) ?: "未录入",
        accentColor = AppTheme.colors.orange,
        caption = "按计薪月 · 已录入 $paidMonths / 12 个月"
    ) {
        MonthBarChart(
            values = stats.months.map { (it.salaryCents ?: 0L).toInt() },
            selectedIndex = index,
            onSelect = onSelect,
            color = AppTheme.colors.orange
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "只画已经录入实发的月份（推算值不冒充实发）；缺的月份去日历「本月工资 → 工资条录入与核对」补",
            color = AppTheme.colors.muted,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

// ---------------------------------------------------------------------------
// 年休息日
// ---------------------------------------------------------------------------

@Composable
private fun RestDaysCard(stats: YearStatsPresenter.YearStats) {
    val rest = stats.rest
    val next = rest.nextDate
    val nextText = when {
        next == null -> "今年已无休息日"
        else -> {
            val suffix = when {
                rest.nextName != null -> " · ${rest.nextName}"
                rest.scope == YearStatsPresenter.RestScope.INCLUDE_WEEKEND -> " · 周末"
                else -> " · 法定节假日"
            }
            TodayStatusPresenter.dateLabel(next) + suffix
        }
    }
    StatCard(
        title = "年休息日",
        accentText = nextText,
        accentColor = AppTheme.colors.purple,
        caption = rest.daysUntilNext?.let { if (it == 0) "就是今天" else "还有 $it 天" }
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCell("今年已休", "${rest.taken} 天", Modifier.weight(1f))
            StatCell("预计还有", "${rest.ahead} 天", Modifier.weight(1f))
            StatCell("全年合计", "${rest.total} 天", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            when (rest.scope) {
                YearStatsPresenter.RestScope.INCLUDE_WEEKEND ->
                    "口径：周末 + 法定节假日都算休息（日均 ${durationText(rest.averageDailyMinutes)}，不超过 8h）"
                YearStatsPresenter.RestScope.FESTIVAL_ONLY ->
                    "口径：只算法定节假日，周末本来就要上班（日均 ${durationText(rest.averageDailyMinutes)}，超过 8h）"
            },
            color = AppTheme.colors.muted,
            style = MaterialTheme.typography.labelSmall
        )
        if (rest.workedOnRest > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "已过去的休息日里出勤 ${rest.workedOnRest} 天（算加班，不计入休息）",
                color = AppTheme.colors.muted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 底部「今日」：实时一行 + 手动打卡 / 补录时段
// ---------------------------------------------------------------------------

@Composable
private fun TodayActionsCard(
    headline: TodayStatusPresenter.Headline,
    live: TodayStatusPresenter.TodayMinutes,
    placeLabel: String,
    confidence: String?,
    earningsCents: Long?,
    onOpenFusion: () -> Unit,
    onPunch: () -> Unit,
    onSegments: () -> Unit
) {
    Card(
        onClick = onOpenFusion,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusPill(headline.text, toneColor(headline.tone))
                if (live.running) StatusPill("计时中", AppTheme.colors.blue)
                Spacer(Modifier.weight(1f))
                Text(placeLabel, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelMedium)
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = "查看融合详情",
                    tint = AppTheme.colors.muted,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "今日 ${durationText(live.minutes)}" +
                    " · 置信度 ${confidence ?: "--"}" +
                    (earningsCents?.let { " · 约 ${formatCents(it)}" } ?: "") +
                    " · 点这里看判定与四源明细",
                color = AppTheme.colors.muted,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPunch, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Fingerprint, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("手动打卡")
                }
                OutlinedButton(onClick = onSegments, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.EditCalendar, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("补录时段")
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 版面零件
// ---------------------------------------------------------------------------

/** 统计卡外框：左标题 + 右侧大数字（选中月的读数）+ 一行小字说明。 */
@Composable
private fun StatCard(
    title: String,
    accentText: String,
    accentColor: Color = AppTheme.colors.orange,
    caption: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    color = AppTheme.colors.muted,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    accentText,
                    color = accentColor,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            if (caption != null) {
                Spacer(Modifier.height(4.dp))
                Text(caption, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, color = AppTheme.colors.muted, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

/** 默认看哪个月：当年 → 当前月；往年 → 最后一个有数据的月。 */
private fun defaultWorkIndex(stats: YearStatsPresenter.YearStats): Int {
    val now = YearMonth.now()
    if (stats.year == now.year) return now.monthValue - 1
    val last = stats.months.indexOfLast { it.minutes > 0 }
    return if (last >= 0) last else 0
}

/** 工资图的默认月：最后一个已录入实发的计薪月。 */
private fun defaultSalaryIndex(stats: YearStatsPresenter.YearStats): Int {
    val last = stats.months.indexOfLast { it.salaryCents != null }
    if (last >= 0) return last
    return defaultWorkIndex(stats)
}

// ---------------------------------------------------------------------------
// 给别的页面复用的口径（日历页当日记录 / 融合详情页都在用）
// ---------------------------------------------------------------------------

@Composable
internal fun decisionColor(decision: FusedDecision): Color = when (decision) {
    FusedDecision.CONFIRMED -> AppTheme.colors.green
    FusedDecision.MAINTAINED -> AppTheme.colors.blue
    FusedDecision.UNKNOWN -> AppTheme.colors.orange
}

/** 分钟 → "1h 00m" / "45m"。 */
internal fun durationText(minutes: Int): String {
    val safe = minutes.coerceAtLeast(0)
    val h = safe / 60
    val m = safe % 60
    return if (h > 0) "${h}h %02dm".format(m) else "${m}m"
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
