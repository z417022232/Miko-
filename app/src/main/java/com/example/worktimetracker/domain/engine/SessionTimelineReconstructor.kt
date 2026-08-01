package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.LocationType

class SessionTimelineReconstructor {
    data class Point(val time: Long, val type: LocationType)
    data class Result(val companyDeparture: Long, val homeArrival: Long, val homeDeparture: Long?)

    fun reconstruct(companyArrival: Long, points: List<Point>, maximumDurationMinutes: Int): Result? {
        val endLimit = companyArrival + maximumDurationMinutes * 60_000L
        val ordered = points.filter { it.time <= endLimit }.sortedBy { it.time }
        val compact = ordered.filterIndexed { index, point -> index == 0 || ordered[index - 1].type != point.type }
        val homeDeparture = compact.zipWithNext()
            .filter { (a, b) -> a.time < companyArrival && a.type == LocationType.HOME && b.type != LocationType.HOME }
            .lastOrNull()?.second?.time
        compact.forEachIndexed { index, point ->
            if (point.time <= companyArrival || point.type == LocationType.COMPANY || point.type == LocationType.UNKNOWN) return@forEachIndexed
            for (next in compact.drop(index + 1)) {
                if (next.type == LocationType.COMPANY) break
                if (next.type == LocationType.HOME && next.time >= point.time) {
                    return Result(point.time, next.time, homeDeparture)
                }
            }
        }
        return null
    }
}
