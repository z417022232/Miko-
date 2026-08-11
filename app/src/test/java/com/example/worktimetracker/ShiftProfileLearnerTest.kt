package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.ShiftProfileLearner
import com.example.worktimetracker.domain.model.ShiftType
import org.junit.Assert.assertEquals
import org.junit.Test

class ShiftProfileLearnerTest {
    private val learner = ShiftProfileLearner()

    @Test fun usesMedianAndExcludesInvalidRows() {
        val records = listOf(
            ShiftProfileLearner.Sample(ShiftType.NIGHT_SHIFT, 20 * 60 + 40, 10 * 60, true),
            ShiftProfileLearner.Sample(ShiftType.NIGHT_SHIFT, 20 * 60 + 50, 11 * 60, true),
            ShiftProfileLearner.Sample(ShiftType.NIGHT_SHIFT, 21 * 60, 12 * 60, true),
            ShiftProfileLearner.Sample(ShiftType.NIGHT_SHIFT, 6 * 60, 20 * 60, false)
        )
        val profile = learner.learn(records, 9 * 60, 21 * 60)
        assertEquals(20 * 60 + 50, profile.nightStartMinutes)
        assertEquals(11 * 60, profile.nightTypicalDurationMinutes)
    }

    @Test fun dynamicMaximumAddsFourHoursAndCapsAtEighteen() {
        assertEquals(15 * 60, learner.maximumDurationMinutes(11 * 60))
        assertEquals(18 * 60, learner.maximumDurationMinutes(17 * 60))
    }
}
