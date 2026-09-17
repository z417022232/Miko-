package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.LocationLogEntity
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.data.entity.WorkRecordEntity
import com.example.worktimetracker.domain.evidence.EvidenceSource
import com.example.worktimetracker.domain.evidence.FusedDecision
import com.example.worktimetracker.domain.evidence.ResolvedPlace
import com.example.worktimetracker.domain.journey.*

/**
 * 用既有原始定位和工时事实预热新状态机。它只生成影子快照，不回写历史工时、不替代前向验证。
 */
object JourneyHistoryPrelearner {
    data class Result(val snapshot: JourneySnapshot, val processedCount: Int)

    fun replay(
        logs: List<LocationLogEntity>,
        records: List<WorkRecordEntity>,
        settings: UserSettingsEntity
    ): Result? {
        val ordered = logs.sortedBy { it.time }
        if (ordered.isEmpty()) return null
        val config = JourneyRuntimeConfigFactory.create(settings)
        var snapshot = JourneySnapshot.initial(ordered.first().time)
        var previousTime: Long? = null

        for (log in ordered) {
            val accuracy = log.accuracyMeters ?: Float.MAX_VALUE
            val reliable = accuracy in 0f..100f
            val place = when (log.locationType) {
                "HOME" -> ResolvedPlace.HOME
                "COMPANY" -> ResolvedPlace.COMPANY
                "OTHER" -> ResolvedPlace.OTHER
                else -> ResolvedPlace.UNKNOWN
            }
            val source = if (log.provider.equals("gps", ignoreCase = true)) {
                EvidenceSource.GNSS
            } else EvidenceSource.NETWORK_LOCATION
            val active = records.any { row ->
                val start = row.startTime
                start != null && start <= log.time && (row.endTime == null || log.time <= row.endTime)
            }
            val gapSeconds = previousTime?.let { ((log.time - it).coerceAtLeast(0L) / 1_000L) } ?: 0L
            val observation = JourneyObservation(
                now = log.time,
                place = place,
                placeDecision = if (reliable && place != ResolvedPlace.UNKNOWN) FusedDecision.CONFIRMED else FusedDecision.UNKNOWN,
                confidence = if (reliable) 0.8 else 0.0,
                evidenceSources = setOf(source),
                motion = MotionPhase.UNKNOWN,
                motionObservedAt = null,
                secondsSinceFix = gapSeconds,
                hasActiveWorkSession = active,
                distanceToHomeMeters = null,
                distanceToWorkMeters = null
            )
            snapshot = JourneyEngine.reduce(snapshot, observation, config).snapshot
            previousTime = log.time
        }
        return Result(snapshot, ordered.size)
    }
}
