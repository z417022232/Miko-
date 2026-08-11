package com.example.worktimetracker.ui

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class ReviewEditorState(
    val startMillis: Long,
    val endMillis: Long,
    val endDate: LocalDate,
    val validationError: String?
) {
    companion object {
        fun from(
            recordDate: LocalDate,
            shift: String,
            startMinute: Int,
            endMinute: Int,
            zone: ZoneId = ZoneId.systemDefault()
        ): ReviewEditorState {
            val startDateTime = recordDate.atTime(LocalTime.of(startMinute / 60, startMinute % 60))
            val crossesMidnight = shift == "NIGHT_SHIFT" && endMinute <= startMinute
            val endDate = if (crossesMidnight) recordDate.plusDays(1) else recordDate
            val endDateTime = endDate.atTime(LocalTime.of(endMinute / 60, endMinute % 60))
            val start = startDateTime.atZone(zone).toInstant().toEpochMilli()
            val end = endDateTime.atZone(zone).toInstant().toEpochMilli()
            return ReviewEditorState(
                startMillis = start,
                endMillis = end,
                endDate = endDate,
                validationError = if (end <= start) "离岗时间必须晚于到岗时间" else null
            )
        }
    }
}
