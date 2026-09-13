package com.example.worktimetracker.location.service

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

class LocationSamplingPolicy(private val zoneId: ZoneId = ZoneId.systemDefault()) {
    fun intervalMillis(
        currentState: String,
        locationType: String,
        distanceToFenceMeters: Double?,
        fenceRadiusMeters: Int,
        speedMetersPerSecond: Float,
        nowMillis: Long,
        workStartMinutes: Int,
        workEndMinutes: Int,
        /**
         * 「常规采集间隔」（用户档位，1/3/5/10 分钟）。
         *
         * 只替换"班次窗口附近"这一档：快速档（转换/贴边/移动）与稳定档（家/公司久坐）
         * 仍由状态决定——那是省电与判准的核心，不交给用户调。默认 5 分钟 = 历史行为。
         */
        normalIntervalMillis: Long = WORK_WINDOW_INTERVAL_MILLIS
    ): Long {
        val transitionState = currentState in setOf("LEAVING_HOME", "NEAR_COMPANY", "TEMP_LEAVE")
        val nearFenceEdge = distanceToFenceMeters?.let {
            abs(it - fenceRadiusMeters) <= FENCE_EDGE_METERS
        } == true
        val moving = speedMetersPerSecond >= MOVING_SPEED_METERS_PER_SECOND
        if (transitionState || nearFenceEdge || moving) return FAST_INTERVAL_MILLIS

        val minuteOfDay = Instant.ofEpochMilli(nowMillis).atZone(zoneId).hour * 60 +
            Instant.ofEpochMilli(nowMillis).atZone(zoneId).minute
        if (withinWindow(minuteOfDay, workStartMinutes) || withinWindow(minuteOfDay, workEndMinutes)) {
            // 夹在 [快速档, 默认档] 之间：用户档位不能比 1 分钟更激进，也不该比 10 分钟更省电
            return normalIntervalMillis.coerceIn(FAST_INTERVAL_MILLIS, DEFAULT_INTERVAL_MILLIS)
        }

        val stable = (currentState == "REST" && locationType == "HOME") ||
            (currentState == "WORKING" && locationType == "COMPANY")
        return if (stable) STABLE_INTERVAL_MILLIS else DEFAULT_INTERVAL_MILLIS
    }

    private fun withinWindow(minuteOfDay: Int, centerMinutes: Int): Boolean {
        val direct = abs(minuteOfDay - centerMinutes)
        val wrapped = MINUTES_PER_DAY - direct
        return minOf(direct, wrapped) <= WORK_WINDOW_HALF_WIDTH_MINUTES
    }

    companion object {
        const val FAST_INTERVAL_MILLIS = 60_000L
        const val WORK_WINDOW_INTERVAL_MILLIS = 5 * 60_000L
        const val DEFAULT_INTERVAL_MILLIS = 10 * 60_000L
        const val STABLE_INTERVAL_MILLIS = 30 * 60_000L
        private const val WORK_WINDOW_HALF_WIDTH_MINUTES = 90
        private const val FENCE_EDGE_METERS = 50.0
        private const val MOVING_SPEED_METERS_PER_SECOND = 1.5f
        private const val MINUTES_PER_DAY = 24 * 60
    }
}
