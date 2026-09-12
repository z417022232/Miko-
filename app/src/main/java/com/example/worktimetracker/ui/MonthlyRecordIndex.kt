package com.example.worktimetracker.ui

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.ChinaHolidayProvider
import com.example.worktimetracker.domain.engine.ReviewReasonResolver
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
                holidayName = ChinaHolidayProvider.name(date),
                dayKind = ChinaHolidayProvider.info(date).kind,
                dayBadge = ChinaHolidayProvider.badge(date)
            )
        }
    }

    private fun WorkRecordEntity.toUi(date: LocalDate, zone: ZoneId): UiDayRecord {
        val dayInfo = ChinaHolidayProvider.info(date)
        return UiDayRecord(
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
            // 同时接受枚举名与历史遗留的中文标签（id=15/158 由旧版手动工时对话框写入），避免班次标签丢失
            shift = when (shift) { "DAY_SHIFT", "白班" -> "白班"; "NIGHT_SHIFT", "夜班" -> "夜班"; else -> null },
            startMillis = startTime,
            endMillis = endTime,
            startText = startTime?.timeText(zone),
            endText = endTime?.timeText(zone, startTime),
            actualMinutes = actualMinutes,
            finalMinutes = finalMinutes,
            needsReview = needsReview,
            // A7: 优先用自动流程写的规则原因；A2 之前的旧记录 reviewReason 为空，
            // 此时从记录自身数据合法性推导，避免横幅只显示无意义的通用兜底文案
            reviewReason = if (needsReview) ReviewReasonResolver.resolve(this, zone) else reviewReason,
            reviewAcknowledged = com.example.worktimetracker.data.entity.ManualFieldMask
                .isNeedsReviewAcknowledged(manualFieldsMask),
            note = note,
            // 仅法定节日当天带节日名；周末/假期休息日/调休上班 由 dayBadge 显示 "休" / "班"
            holidayName = dayInfo.festivalName,
            dayKind = dayInfo.kind,
            dayBadge = dayInfo.festivalName ?: dayInfo.kind.shortLabel,
            companyArrivalText = startTime?.timeText(zone),
            companyDepartureText = endTime?.timeText(zone, startTime),
            homeDepartureText = homeDepartureTime?.timeText(zone),
            homeArrivalText = homeArrivalTime?.timeText(zone, startTime)
        )
    }

    private fun Long.timeText(zone: ZoneId, start: Long? = null): String {
        val time = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()
        val prefix = if (start != null && Instant.ofEpochMilli(start).atZone(zone).toLocalDate() != time.toLocalDate()) "次日" else ""
        return prefix + "%02d:%02d".format(time.hour, time.minute)
    }
}
