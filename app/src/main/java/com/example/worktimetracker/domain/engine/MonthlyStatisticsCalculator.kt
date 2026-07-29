package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.WorkSession

class MonthlyStatisticsCalculator {
    fun totalMinutes(records: List<WorkSession>): Int = records.sumOf { it.finalMinutes }
    fun workDays(records: List<WorkSession>): Int = records.count { it.finalMinutes > 0 }
    fun averageMinutes(records: List<WorkSession>): Int = if (workDays(records) == 0) 0 else totalMinutes(records) / workDays(records)
}
