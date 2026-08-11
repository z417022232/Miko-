package com.example.worktimetracker.ui

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.ChinaHolidayProvider
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

object MonthlyRecordIndex {
    fun build(
        month: YearMonth,
        rows: List<WorkRecordEntity>,
        today: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<UiDayRecord> {
        val byDate = rows.associateBy { it.workDate }
        return (1..month.lengthOfMonth()).map { day ->
            val date = month.atDay(day)
            byDate[date.toString()]?.toUi(date, zone) ?: UiDayRecord(
                date = date,
                status = if (date.isAfter(today)) "" else "休息",
                finalMinutes = 0,
                holidayName = ChinaHolidayProvider.name(date)
            )
        }
    }

    private fun WorkRecordEntity.toUi(date: LocalDate, zone: ZoneId): UiDayRecord = UiDayRecord(
        date = date,
        status = when (status) {
            "WORK" -> if (shift == "NIGHT_SHIFT") "夜班" else "白班"
            "REST" -> "休息"
            "OUTSIDE" -> "外出"
            "EARLY_LEAVE" -> "下早班"
            "ARRIVAL_EXCEPTION" -> "到岗异常"
            "MANUAL" -> "手动"
            "LEAVE" -> "请假"
            else -> status
        },
        shift = when (shift) { "DAY_SHIFT" -> "白班"; "NIGHT_SHIFT" -> "夜班"; else -> null },
        startMillis = startTime,
        endMillis = endTime,
        startText = startTime?.timeText(zone),
        endText = endTime?.timeText(zone, startTime),
        actualMinutes = actualMinutes,
        finalMinutes = finalMinutes,
        needsReview = needsReview,
        note = note,
        holidayName = ChinaHolidayProvider.name(date),
        companyArrivalText = startTime?.timeText(zone),
        companyDepartureText = endTime?.timeText(zone, startTime),
        homeDepartureText = homeDepartureTime?.timeText(zone),
        homeArrivalText = homeArrivalTime?.timeText(zone, startTime)
    )

    private fun Long.timeText(zone: ZoneId, start: Long? = null): String {
        val time = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()
        val prefix = if (start != null && Instant.ofEpochMilli(start).atZone(zone).toLocalDate() != time.toLocalDate()) "次日" else ""
        return prefix + "%02d:%02d".format(time.hour, time.minute)
    }
}
