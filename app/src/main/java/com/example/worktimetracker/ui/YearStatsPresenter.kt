package com.example.worktimetracker.ui

import com.example.worktimetracker.data.entity.MonthlySalaryEntity
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.DayKind
import com.example.worktimetracker.domain.engine.HolidayCalendar
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 「今日」Tab = **XX 年数据统计** 的计算部分（纯函数 + 不碰库，好测）。
 *
 * 三块：
 * 1. 每月工时序列（12 个月，用于波动图）；
 * 2. 每月实发工资序列（只有**已录入**的计薪月才有值 —— 不拿推算值冒充实发）；
 * 3. 年休息日统计（下一个休息日 / 今年已休 / 预计还有）。
 *
 * ## 休息日口径为什么是"自适应"的（用户 2026-09-16 口径）
 *
 * 同样一句"年休息日"，对两班倒和常白班根本不是一回事：
 * - 每天干 8h 的（常白班、周末双休）→ 休息日 = 周末 + 法定节假日；
 * - 每天干 11h 的（车间两班倒，靠加班费吃饭）→ 周末本来就要上班，
 *   把周末算成"休息"会让"今年共休 XX 天"虚高一倍，只剩**法定节假日**才是真休息。
 *
 * 判定线取日均工时 8h：[WEEKEND_SCOPE_THRESHOLD_MINUTES]。日均以**本年度真实出勤日**求平均
 * （没有任何记录时才回落到设置里的默认工时），避免拿一个拍脑袋的数去决定口径。
 *
 * 注意：口径只影响**统计展示**，不参与计薪，也不回写任何记录。
 */
object YearStatsPresenter {

    /** 日均工时判定线：≤ 8h 认为"周末双休"，> 8h 认为"只有法定节假日才休"。 */
    const val WEEKEND_SCOPE_THRESHOLD_MINUTES = 8 * 60

    /** 休息日口径。 */
    enum class RestScope {
        /** 周末 + 法定节假日都算休息。 */
        INCLUDE_WEEKEND,

        /** 只算法定节假日（含假期里的休息日），周末不算。 */
        FESTIVAL_ONLY
    }

    data class MonthPoint(
        val month: Int,
        val minutes: Int,
        val workedDays: Int,
        /** 该**计薪月**已录入的实发；没录入 = null（图表留空，不猜）。 */
        val salaryCents: Long?
    )

    data class RestSummary(
        val scope: RestScope,
        val averageDailyMinutes: Int,
        /** 截至今天，已经过去且没出勤的休息日天数。 */
        val taken: Int,
        /** 今天之后还剩的休息日天数。 */
        val ahead: Int,
        /** taken + ahead，即按本口径全年的净休息日。 */
        val total: Int,
        /** 下一个"不用上班"的日子；今天本身是休息日且没出勤时就是今天。 */
        val nextDate: LocalDate?,
        /** 下一个休息日的节日名（周末为 null）。 */
        val nextName: String?,
        val nextKind: DayKind?,
        /** 距下一个休息日还有几天，0 = 今天。 */
        val daysUntilNext: Int?,
        /** 已经过去的休息日里，实际出勤了几天（加班）。 */
        val workedOnRest: Int
    )

    data class YearStats(
        val year: Int,
        val months: List<MonthPoint>,
        val totalMinutes: Int,
        val workedDays: Int,
        val rest: RestSummary
    )

    fun build(
        year: Int,
        records: List<WorkRecordEntity>,
        salaries: List<MonthlySalaryEntity>,
        today: LocalDate,
        fallbackDailyMinutes: Int = WEEKEND_SCOPE_THRESHOLD_MINUTES
    ): YearStats {
        val rows = records.filter { it.workDate.startsWith(yearKey(year)) }
        val workedByDate = rows.associateBy { it.workDate }
        val salaryByMonth = salaries
            .filter { it.payrollMonth.startsWith(yearKey(year)) }
            .associate { it.payrollMonth to it.netSalaryCents }

        val months = (1..12).map { month ->
            val prefix = "%04d-%02d".format(year, month)
            val monthRows = rows.filter { it.workDate.startsWith(prefix) }
            MonthPoint(
                month = month,
                minutes = monthRows.sumOf { it.finalMinutes.coerceAtLeast(0) },
                workedDays = monthRows.count { it.finalMinutes > 0 },
                salaryCents = salaryByMonth[prefix]?.takeIf { it > 0L }
            )
        }

        val attendedDays = rows.filter { it.finalMinutes > 0 }
        val average = if (attendedDays.isEmpty()) fallbackDailyMinutes
        else attendedDays.sumOf { it.finalMinutes } / attendedDays.size

        return YearStats(
            year = year,
            months = months,
            totalMinutes = months.sumOf { it.minutes },
            workedDays = months.sumOf { it.workedDays },
            rest = restSummary(year, workedByDate, today, average)
        )
    }

    /**
     * 某一天按 [scope] 算不算"本该休息"。
     *
     * 调休上班日（`MAKEUP_WORKDAY`）**永远不算** —— 那是把周末借来上班，反过来说它休息是错的。
     */
    fun isRestDay(date: LocalDate, scope: RestScope): Boolean {
        val kind = HolidayCalendar.info(date).kind
        return when (scope) {
            RestScope.INCLUDE_WEEKEND ->
                kind == DayKind.WEEKEND || kind == DayKind.HOLIDAY_REST || kind == DayKind.FESTIVAL
            RestScope.FESTIVAL_ONLY ->
                kind == DayKind.FESTIVAL || kind == DayKind.HOLIDAY_REST
        }
    }

    private fun restSummary(
        year: Int,
        workedByDate: Map<String, WorkRecordEntity>,
        today: LocalDate,
        averageDailyMinutes: Int
    ): RestSummary {
        val scope = if (averageDailyMinutes <= WEEKEND_SCOPE_THRESHOLD_MINUTES) {
            RestScope.INCLUDE_WEEKEND
        } else {
            RestScope.FESTIVAL_ONLY
        }
        var taken = 0
        var ahead = 0
        var workedOnRest = 0
        var nextDate: LocalDate? = null

        var date = LocalDate.of(year, 1, 1)
        val lastDay = LocalDate.of(year, 12, 31)
        while (!date.isAfter(lastDay)) {
            if (isRestDay(date, scope)) {
                val didWork = (workedByDate[date.toString()]?.finalMinutes ?: 0) > 0
                if (didWork) {
                    // 休息日出了勤：不算休息（是加班）。计数不区分过去/未来，
                    // 以保证"每个休息日恰落进一个桶"：已休 + 未休 + 加班 = 全年休息日。
                    workedOnRest++
                } else {
                    if (date.isAfter(today)) ahead++ else taken++
                    if (nextDate == null && !date.isBefore(today)) nextDate = date
                }
            }
            date = date.plusDays(1)
        }

        return RestSummary(
            scope = scope,
            averageDailyMinutes = averageDailyMinutes,
            taken = taken,
            ahead = ahead,
            total = taken + ahead,
            nextDate = nextDate,
            nextName = nextDate?.let { HolidayCalendar.name(it) },
            nextKind = nextDate?.let { HolidayCalendar.info(it).kind },
            daysUntilNext = nextDate?.let { ChronoUnit.DAYS.between(today, it).toInt() },
            workedOnRest = workedOnRest
        )
    }

    private fun yearKey(year: Int): String = "%04d-".format(year)
}
