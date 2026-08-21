package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.LocationAnchorCalibration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationAnchorCalibrationTest {
    private val calibration = LocationAnchorCalibration()

    @Test fun medianCalibrationRejectsFarOutlier() {
        val cluster = (0 until 10).map { point(31.0 + it * 0.000001, 121.0, 10f, it.toLong()) }
        val result = calibration.calculate(cluster + point(31.01, 121.01, 10f, 20L))
        assertNotNull(result)
        assertEquals(10, result!!.acceptedCount)
        assertEquals(1, result.rejectedCount)
        assertTrue(result.stableRadiusMeters in 60..150)
    }

    @Test fun calibrationRejectsTooFewAccuratePoints() {
        assertNull(calibration.calculate((0 until 4).map { point(31.0, 121.0, 10f, it.toLong()) }))
    }

    @Test fun calibrationRejectsScatteredPoints() {
        assertNull(calibration.calculate((0 until 10).map { point(31.0 + it * 0.001, 121.0, 10f, it.toLong()) }))
    }

    private fun point(lat: Double, lng: Double, accuracy: Float, time: Long) =
        LocationAnchorCalibration.Point(lat, lng, accuracy, time)
}
