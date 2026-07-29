package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.WorkCalculationInput
import java.util.concurrent.TimeUnit
import kotlin.math.max

class WorkHourCalculator {
    fun calculateFinalMinutes(input: WorkCalculationInput): Int {
        input.manualFinalMinutes?.let { return max(0, it) }
        if (input.manualSegments.isNotEmpty()) {
            val total = input.manualSegments.sumOf { segment ->
                minutesBetween(segment.startMillis, segment.endMillis) - if (segment.deductRest) input.settings.restDeductionMinutes else 0
            }
            return max(0, total)
        }
        if (input.settings.hasDefaultHours && input.settings.defaultWorkMinutes != null) {
            return max(0, input.settings.defaultWorkMinutes)
        }
        val start = input.startMillis ?: input.fallbackStartMillis
        val end = input.endMillis ?: input.fallbackEndMillis
        if (start == null || end == null) return 0
        return max(0, minutesBetween(start, end) - input.settings.restDeductionMinutes)
    }

    fun actualMinutes(startMillis: Long?, endMillis: Long?): Int {
        if (startMillis == null || endMillis == null) return 0
        return max(0, minutesBetween(startMillis, endMillis))
    }

    private fun minutesBetween(startMillis: Long, endMillis: Long): Int =
        TimeUnit.MILLISECONDS.toMinutes(endMillis - startMillis).toInt()
}
