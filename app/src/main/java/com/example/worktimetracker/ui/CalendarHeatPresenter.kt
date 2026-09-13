package com.example.worktimetracker.ui

import com.example.worktimetracker.domain.engine.DayInfo
import com.example.worktimetracker.domain.engine.DayKind
import com.example.worktimetracker.domain.engine.HolidayCalendar
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** 日历格子的热力档位：没有出工 / 工时不足 / 满勤。 */
enum class HeatLevel { NONE, PARTIAL, FULL }

/**
 * 一个日历格子的展示模型。
 *
 * **只管语义，不管颜色**——颜色由 UI 层按主题 token 映射。这样浅色/深色两套主题
 * 共用同一份判定逻辑，也让"哪个格子该是什么样"能脱离 Compose 做单元测试。
 */
data class DayCellModel(
    val date: LocalDate,
    val dayOfMonth: Int,
    val heat: HeatLevel,
    /** 格子下半部短文本：`白 11h` / `夜 10.5h` / `休` / `中秋` / `班` / `—`。 */
    val text: String,
    val isToday: Boolean,
    val isSelected: Boolean,
    /** 周末（含被调休成上班的周末）且有出工 → 虚线描边。 */
    val weekendWork: Boolean,
    /** 本该休息且没有出工 → 深灰底。 */
    val isRest: Boolean,
    /** 法定节日当天 → 紫底。 */
    val isFestival: Boolean,
    /** 调休上班日 → 浅橙底（这是本 App 比国家安排多出来的一层信息）。 */
    val isMakeup: Boolean,
    /** 有出工但缺下班卡，或系统判定需确认 → 右上角橙点。 */
    val missingPunch: Boolean,
    /** 无出工、也非休息/节日 → 文字压暗（"这天没有记录"）。 */
    val isDim: Boolean
)

/** 本月汇总四指标。全部由 existing 的 [UiDayRecord] 派生，不新增任何算法口径。 */
data class MonthSummary(
    val totalMinutes: Int,
    val workDays: Int,
    val overtimeMinutes: Int,
    val averageMinutes: Int
)

/** 「下一个假期」提示。 */
data class HolidayTip(
    val date: LocalDate,
    val name: String,
    val daysUntil: Long
)

/**
 * 日历页（界面稿 v4 屏 01）的纯逻辑层。
 *
 * 三条硬约束：
 * 1. **只读**——从 [UiDayRecord] 派生展示模型，不改任何记录，也不参与状态机；
 * 2. **不写裸色值**——只输出语义标记（heat / isRest / isFestival …），颜色归主题层；
 * 3. **满勤阈值固定 8h**——与稿子图例「满勤 8h+」一致，不读用户设置，
 *    避免"我改了默认工时，日历历史格子全变色"这种回溯性改动。
 */
object CalendarHeatPresenter {

    /** 满勤阈值：8 小时。界面稿图例口径。 */
    const val FULL_DAY_MINUTES = 480

    /**
     * 加班口径：**法定标准工作日 8 小时之上的部分**。
     *
     * 这是中国工厂语境与《劳动法》的通用口径（"每天加班 3 小时"指的是 8 小时之外那 3 小时），
     * 而不是本 App 内部的排班时长。这里只用于「本月卡」把多出来的工时单独标出来，
     * 不参与工资计算（工资只按"工时 × 时薪"，不做倍率）。
     */
    const val STANDARD_WORKDAY_MINUTES = 480

    /** 搜索「下一个假期」的最大天数：一年足够覆盖。 */
    private const val HOLIDAY_LOOKAHEAD_DAYS = 400

    /** 节日名压缩成格子能放下的短名：`中秋节` → `中秋`，`中秋节·国庆节` → `中秋`。 */
    fun shortFestivalName(name: String): String {
        val first = name.split('·').first().trim()
        val trimmed = if (first.length > 1 && first.endsWith("节")) first.dropLast(1) else first
        return trimmed.take(3)
    }

    /** 周几短标签（周日 = 周日，不用 "周天"）。 */
    fun weekdayShort(date: LocalDate): String = when (date.dayOfWeek.value) {
        1 -> "周一"
        2 -> "周二"
        3 -> "周三"
        4 -> "周四"
        5 -> "周五"
        6 -> "周六"
        else -> "周日"
    }

    /**
     * 把整月记录映射成 42 格（含前后补空）的网格。
     *
     * 补空格用 `null` 表示，避免 UI 层再算一次「这个月 1 号是周几」；
     * 返回值长度恒为 7 的整数倍。
     */
    fun buildCells(
        month: YearMonth,
        records: List<UiDayRecord>,
        today: LocalDate,
        selectedDate: LocalDate,
        infoOf: (LocalDate) -> DayInfo = { HolidayCalendar.info(it) }
    ): List<DayCellModel?> {
        val byDate = records.associateBy { it.date }
        val cells = ArrayList<DayCellModel?>(42)
        // DayOfWeek.value：周一 = 1 … 周日 = 7；周日要落到第 0 列，故 rem 7
        repeat(month.atDay(1).dayOfWeek.value % 7) { cells.add(null) }
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            cells.add(cellOf(date, byDate[date], today, selectedDate, infoOf))
        }
        while (cells.size % 7 != 0) cells.add(null)
        return cells
    }

    private fun cellOf(
        date: LocalDate,
        record: UiDayRecord?,
        today: LocalDate,
        selectedDate: LocalDate,
        infoOf: (LocalDate) -> DayInfo
    ): DayCellModel {
        val info = infoOf(date)
        val minutes = record?.finalMinutes ?: 0
        val worked = minutes > 0
        val isWeekend = date.dayOfWeek.value >= 6
        val isRest = !worked && (info.kind == DayKind.WEEKEND || info.kind == DayKind.HOLIDAY_REST)
        val isFestival = info.kind == DayKind.FESTIVAL
        val isMakeup = info.kind == DayKind.MAKEUP_WORKDAY
        val text = when {
            // 格子第二行必须带班次前缀（白/夜）——只显示 "11.0" 会丢掉班次信息，
            // 这是 dffb63c 就定下的口径，v4.1 换热力月历时被 hoursText 顶掉了。
            worked -> calendarDayLabel(record?.shift, minutes)
            isFestival -> info.festivalName?.let(::shortFestivalName) ?: "节"
            isRest -> "休"
            isMakeup -> "班"
            else -> "—"
        }
        return DayCellModel(
            date = date,
            dayOfMonth = date.dayOfMonth,
            heat = when {
                !worked -> HeatLevel.NONE
                minutes >= FULL_DAY_MINUTES -> HeatLevel.FULL
                else -> HeatLevel.PARTIAL
            },
            text = text,
            isToday = date == today,
            isSelected = date == selectedDate,
            weekendWork = isWeekend && worked,
            isRest = isRest,
            isFestival = isFestival,
            isMakeup = isMakeup,
            // 缺卡 = 有出工却没有下班时刻（只打了上班卡）；needsReview 也算，
            // 因为这两件事对用户的下一步动作是同一个：去核这一天。
            missingPunch = worked && (record?.endMillis == null || record.needsReview),
            isDim = !worked && !isRest && !isFestival && !isMakeup
        )
    }

    /** 本月汇总。加班按 [STANDARD_WORKDAY_MINUTES] 之上的部分累加。 */
    fun summarize(
        records: List<UiDayRecord>,
        standardMinutes: Int = STANDARD_WORKDAY_MINUTES
    ): MonthSummary {
        val total = records.sumOf { it.finalMinutes }
        val workDays = records.count { it.finalMinutes > 0 }
        val overtime = records.sumOf { (it.finalMinutes - standardMinutes).coerceAtLeast(0) }
        return MonthSummary(
            totalMinutes = total,
            workDays = workDays,
            overtimeMinutes = overtime,
            averageMinutes = if (workDays > 0) total / workDays else 0
        )
    }

    /**
     * 从 [from] 之后找第一个法定节日。
     *
     * 只认 [DayKind.FESTIVAL]（节日**当天**），不认假期里的普通休息日——
     * "下一个假期"对用户的意义就是"下一个能放假的日子"，报 9/25 中秋 比报 9/26 更有用。
     */
    fun nextHoliday(
        from: LocalDate,
        infoOf: (LocalDate) -> DayInfo = { HolidayCalendar.info(it) },
        maxDays: Int = HOLIDAY_LOOKAHEAD_DAYS
    ): HolidayTip? {
        var date = from.plusDays(1)
        repeat(maxDays) {
            val info = infoOf(date)
            val name = info.festivalName
            if (info.kind == DayKind.FESTIVAL && name != null) {
                return HolidayTip(
                    date = date,
                    name = shortFestivalName(name),
                    daysUntil = ChronoUnit.DAYS.between(from, date)
                )
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** 「下一个假期」整句文案，如 `下一个假期：9 月 25 日 中秋（周五）· 还有 12 天`。 */
    fun holidayTipText(tip: HolidayTip): String {
        val d = tip.date
        val suffix = if (tip.daysUntil <= 0L) "就是今天" else "还有 ${tip.daysUntil} 天"
        return "下一个假期：${d.monthValue} 月 ${d.dayOfMonth} 日 ${tip.name}（${weekdayShort(d)}）· $suffix"
    }
}
