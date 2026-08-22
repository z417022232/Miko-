package com.example.worktimetracker.location.service

object CalibrationSessionReplay {
    data class Sample(val time: Long, val companyDistanceMeters: Double, val accuracyMeters: Float)

    fun findArrival(
        homeDeparture: Long,
        calibratedAt: Long,
        stableRadiusMeters: Int,
        samples: List<Sample>
    ): Long? {
        val usable = samples.filter { it.time in homeDeparture..calibratedAt && it.accuracyMeters <= 100f }
            .sortedBy { it.time }
        return usable.zipWithNext().firstOrNull { (first, second) ->
            first.companyDistanceMeters <= stableRadiusMeters &&
                second.companyDistanceMeters <= stableRadiusMeters &&
                second.time - first.time <= 5 * 60_000L
        }?.first?.time
    }
}
