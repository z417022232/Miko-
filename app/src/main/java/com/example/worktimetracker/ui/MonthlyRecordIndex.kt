package com.example.worktimetracker.ui

import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.engine.HolidayCalendar
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
            byDate[date.toString()]?.let { toUi(date, it, zone) } ?: UiDayRecord(
                date = date,
                status = if (date.isAfter(today)) "" else "休息",
                finalMinutes = 0,
                holidayName = HolidayCalendar.name(date),
                dayKind = HolidayCalendar.info(date).kind,
                dayBadge = HolidayCalendar.badge(date)
            )
        }
    }

    /**
     * 单日映射。语义与 [build] 完全一致，只是把扩展函数改成普通函数，
     * 好让「今日」页只查一天就能拿到同样的界面模型（不必为了今天把整月重算一遍）。
     */
    fun toUi(
        date: LocalDate,
        entity: WorkRecordEntity,
        zone: ZoneId = ZoneId.systemDefault()
    ): UiDayRecord {
        val dayInfo = HolidayCalendar.info(date)
        return UiDayRecord(
            date = date,
            status = statusLabel(entity.status, entity.shift),
            shift = shiftLabel(entity.shift),
            startMillis = entity.startTime,
            endMillis = entity.endTime,
            startText = entity.startTime?.timeText(zone),
            endText = entity.endTime?.timeText(zone, entity.startTime),
            actualMinutes = entity.actualMinutes,
            finalMinutes = entity.finalMinutes,
            needsReview = entity.needsReview,
            // A7: 优先用自动流程写的规则原因；A2 之前的旧记录 reviewReason 为空，
            // 此时从记录自身数据合法性推导，避免横幅只显示无意义的通用兜底文案
            reviewReason = if (entity.needsReview) ReviewReasonResolver.resolve(entity, zone) else entity.reviewReason,
            reviewAcknowledged = com.example.worktimetracker.data.entity.ManualFieldMask
                .isNeedsReviewAcknowledged(entity.manualFieldsMask),
            note = entity.note,
            // 仅法定节日当天带节日名；周末/假期休息日/调休上班 由 dayBadge 显示 "休" / "班"
            holidayName = dayInfo.festivalName,
            dayKind = dayInfo.kind,
            dayBadge = dayInfo.festivalName ?: dayInfo.kind.shortLabel,
            companyArrivalText = entity.startTime?.timeText(zone),
            companyDepartureText = entity.endTime?.timeText(zone, entity.startTime),
            homeDepartureText = entity.homeDepartureTime?.timeText(zone),
            homeArrivalText = entity.homeArrivalTime?.timeText(zone, entity.startTime)
        )
    }

    /** work_records.status → 界面显示标签。 */
    fun statusLabel(status: String, shift: String?): String = when (status) {
        "WORK" -> if (shift == "NIGHT_SHIFT") "夜班" else "白班"
        "REST" -> "休息"
        "OUTSIDE" -> "外出"
        "EARLY_LEAVE" -> "下早班"
        "ARRIVAL_EXCEPTION" -> "到岗异常"
        "MANUAL" -> "手动"
        "LEAVE" -> "请假"
        else -> status
    }

    /** 班次原始值 → 显示标签：同时接受枚举名与历史遗留的中文标签（id=15/158 由旧版对话框写入）。 */
    fun shiftLabel(shift: String?): String? = when (shift) {
        "DAY_SHIFT", "白班" -> "白班"
        "NIGHT_SHIFT", "夜班" -> "夜班"
        else -> null
    }

    private fun Long.timeText(zone: ZoneId, start: Long? = null): String {
        val time = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()
        val prefix = if (start != null && Instant.ofEpochMilli(start).atZone(zone).toLocalDate() != time.toLocalDate()) "次日" else ""
        return prefix + "%02d:%02d".format(time.hour, time.minute)
    }
}
