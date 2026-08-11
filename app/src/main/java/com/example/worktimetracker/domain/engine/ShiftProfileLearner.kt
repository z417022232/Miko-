package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.ShiftType

class ShiftProfileLearner {
    data class Sample(
        val shift: ShiftType,
        val startMinutes: Int,
        val durationMinutes: Int,
        val valid: Boolean
    )

    data class Profile(
        val dayStartMinutes: Int,
        val nightStartMinutes: Int,
        val dayTypicalDurationMinutes: Int,
        val nightTypicalDurationMinutes: Int
    )

    fun learn(samples: List<Sample>, fallbackDayStartMinutes: Int, fallbackNightStartMinutes: Int): Profile {
        val valid = samples.filter { it.valid }.takeLast(MAX_SAMPLES)
        val day = valid.filter { it.shift == ShiftType.DAY_SHIFT }
        val night = valid.filter { it.shift == ShiftType.NIGHT_SHIFT }
        return Profile(
            dayStartMinutes = median(day.map { it.startMinutes }) ?: fallbackDayStartMinutes,
            nightStartMinutes = median(night.map { it.startMinutes }) ?: fallbackNightStartMinutes,
            dayTypicalDurationMinutes = median(day.map { it.durationMinutes }) ?: DEFAULT_DURATION_MINUTES,
            nightTypicalDurationMinutes = median(night.map { it.durationMinutes }) ?: DEFAULT_DURATION_MINUTES
        )
    }

    fun maximumDurationMinutes(typicalDurationMinutes: Int): Int =
        (typicalDurationMinutes + FOUR_HOURS_MINUTES).coerceAtMost(EIGHTEEN_HOURS_MINUTES)

    private fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        const val MAX_SAMPLES = 14
        const val DEFAULT_DURATION_MINUTES = 11 * 60
        const val FOUR_HOURS_MINUTES = 4 * 60
        const val EIGHTEEN_HOURS_MINUTES = 18 * 60
    }
}
