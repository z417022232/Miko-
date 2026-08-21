package com.example.worktimetracker.domain.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class LocationAnchorCalibration {
    data class Point(val latitude: Double, val longitude: Double, val accuracyMeters: Float, val time: Long)
    data class Result(
        val centerLat: Double,
        val centerLng: Double,
        val stableRadiusMeters: Int,
        val acceptedCount: Int,
        val rejectedCount: Int
    )

    fun calculate(points: List<Point>): Result? {
        val accurate = points.filter { it.accuracyMeters <= 30f }
        if (accurate.size < 5) return null
        val initialLat = median(accurate.map { it.latitude })
        val initialLng = median(accurate.map { it.longitude })
        val distances = accurate.map { distance(it.latitude, it.longitude, initialLat, initialLng) }
        val threshold = max(75.0, median(distances) * 3.0)
        val accepted = accurate.filterIndexed { index, _ -> distances[index] <= threshold }
        if (accepted.size < 5) return null
        val centerLat = median(accepted.map { it.latitude })
        val centerLng = median(accepted.map { it.longitude })
        val acceptedDistances = accepted.map { distance(it.latitude, it.longitude, centerLat, centerLng) }.sorted()
        val p90 = acceptedDistances[((acceptedDistances.size - 1) * 0.9).toInt()]
        if (p90 > 150.0) return null
        return Result(centerLat, centerLng, p90.toInt().coerceIn(60, 150), accepted.size, points.size - accepted.size)
    }

    private fun median(values: List<Double>): Double = values.sorted().let {
        if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2.0
    }

    private fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
