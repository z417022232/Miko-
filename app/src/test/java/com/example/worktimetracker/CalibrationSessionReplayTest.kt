package com.example.worktimetracker

import com.example.worktimetracker.location.service.CalibrationSessionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalibrationSessionReplayTest {
    @Test fun calibrationBackfillsFirstStableArrivalBeforeCalibration() {
        val result = CalibrationSessionReplay.findArrival(
            homeDeparture = 100L,
            calibratedAt = 1_000L,
            stableRadiusMeters = 60,
            samples = listOf(
                sample(200, 248.0), sample(300, 67.0),
                sample(400, 60.0), sample(410, 59.0), sample(900, 20.0)
            )
        )
        assertEquals(400L, result)
    }

    @Test fun oneStablePointIsNotEnough() {
        assertNull(CalibrationSessionReplay.findArrival(100, 1_000, 60,
            listOf(sample(200, 50.0), sample(300, 200.0))))
    }

    private fun sample(time: Long, distance: Double) =
        CalibrationSessionReplay.Sample(time, distance, 10f)
}
