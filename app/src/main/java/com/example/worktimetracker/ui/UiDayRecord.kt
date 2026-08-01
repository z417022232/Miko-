package com.example.worktimetracker.ui

import java.time.LocalDate

data class UiDayRecord(
    val date: LocalDate,
    val status: String,
    val shift: String? = null,
    val startText: String? = null,
    val endText: String? = null,
    val actualMinutes: Int? = null,
    val finalMinutes: Int = 0,
    val needsReview: Boolean = false,
    val note: String? = null,
    val holidayName: String? = null,
    val companyArrivalText: String? = null,
    val companyDepartureText: String? = null,
    val homeArrivalText: String? = null,
    val homeDepartureText: String? = null
)

fun calendarDayLabel(shift: String?, minutes: Int): String {
    if (minutes <= 0) return when (shift) { "白班" -> "白"; "夜班" -> "夜"; else -> "" }
    val hours = if (minutes % 60 == 0) "${minutes / 60}" else "%.1f".format(java.util.Locale.US, minutes / 60.0)
    val prefix = when (shift) { "白班" -> "白 "; "夜班" -> "夜 "; else -> "" }
    return "${prefix}${hours}h"
}
