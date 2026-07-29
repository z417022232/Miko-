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
