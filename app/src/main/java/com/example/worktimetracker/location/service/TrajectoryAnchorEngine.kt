package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.domain.model.LocationType
import java.util.UUID

class TrajectoryAnchorEngine {
    data class Config(
        val companyRadiusMeters: Int,
        val homeRadiusMeters: Int,
        val companyStableRadiusMeters: Int,
        val homeStableRadiusMeters: Int,
        val leaveConfirmMinutes: Int
    )

    data class Fix(
        val time: Long,
        val type: LocationType,
        val accuracyMeters: Float,
        val provider: String,
        val companyDistanceMeters: Double?,
        val companyAnchorDistanceMeters: Double?,
        val homeDistanceMeters: Double?,
        val homeAnchorDistanceMeters: Double?,
        val speedMetersPerSecond: Float,
        val movingAway: Boolean
    )

    sealed class Event(open val occurredAt: Long, open val confirmedAt: Long) {
        data class HomeDeparture(override val occurredAt: Long, override val confirmedAt: Long) : Event(occurredAt, confirmedAt)
        data class CompanyArrival(override val occurredAt: Long, override val confirmedAt: Long) : Event(occurredAt, confirmedAt)
        data class CompanyDeparture(override val occurredAt: Long, override val confirmedAt: Long) : Event(occurredAt, confirmedAt)
        data class HomeArrival(override val occurredAt: Long, override val confirmedAt: Long) : Event(occurredAt, confirmedAt)
    }

    data class Decision(val nextState: WorkStateEntity, val events: List<Event>)

    fun next(previous: WorkStateEntity, fix: Fix, config: Config): Decision {
        if (fix.accuracyMeters > 100f || fix.type == LocationType.UNKNOWN) {
            return Decision(previous.copy(lastLocationTime = fix.time, updatedAt = fix.time), emptyList())
        }
        val companyStable = fix.companyAnchorDistanceMeters?.let { it <= config.companyStableRadiusMeters } == true
        val homeStable = fix.homeAnchorDistanceMeters?.let { it <= config.homeStableRadiusMeters } == true
        val events = mutableListOf<Event>()
        val next = when (previous.currentState) {
            "REST" -> when {
                homeStable -> previous.copy(sessionStart = null)
                fix.movingAway || fix.type == LocationType.OTHER -> newSession(previous, fix.time).also {
                    events += Event.HomeDeparture(fix.time, fix.time)
                }
                companyStable -> newSession(previous, fix.time).copy(
                    currentState = "NEAR_COMPANY", candidateCompanyArrivalTime = fix.time, stableCompanyCount = 1
                )
                else -> previous
            }
            "LEAVING_HOME", "NEAR_COMPANY" -> if (companyStable) {
                val candidate = previous.candidateCompanyArrivalTime ?: fix.time
                val count = previous.stableCompanyCount + 1
                if (count >= 2) {
                    events += Event.CompanyArrival(candidate, fix.time)
                    previous.copy(currentState = "WORKING", sessionStart = candidate,
                        candidateCompanyArrivalTime = candidate, companyArrivalConfirmedAt = fix.time,
                        stableCompanyCount = count)
                } else previous.copy(candidateCompanyArrivalTime = candidate, stableCompanyCount = count)
            } else previous.copy(stableCompanyCount = 0)
            "WORKING" -> if (!companyStable && (fix.movingAway || fix.type != LocationType.COMPANY)) {
                previous.copy(currentState = "TEMP_LEAVE",
                    candidateCompanyDepartureTime = previous.candidateCompanyDepartureTime ?: fix.time,
                    movingAwayCount = 1, stableCompanyCount = 0)
            } else previous.copy(stableCompanyCount = if (companyStable) previous.stableCompanyCount + 1 else 0)
            "TEMP_LEAVE" -> updateTemporaryLeave(previous, fix, config, companyStable, homeStable, events)
            "FINISHED" -> if (homeStable) previous.copy(currentState = "REST", sessionStart = null) else previous
            else -> previous
        }
        return Decision(next.copy(lastLocationTime = fix.time, updatedAt = fix.time), events)
    }

    private fun updateTemporaryLeave(
        previous: WorkStateEntity,
        fix: Fix,
        config: Config,
        companyStable: Boolean,
        homeStable: Boolean,
        events: MutableList<Event>
    ): WorkStateEntity {
        if (companyStable) {
            val count = previous.stableCompanyCount + 1
            return if (count >= 2) previous.copy(currentState = "WORKING",
                candidateCompanyDepartureTime = null, movingAwayCount = 0, stableCompanyCount = count,
                candidateHomeArrivalTime = null, stableHomeCount = 0)
            else previous.copy(stableCompanyCount = count)
        }
        val candidate = previous.candidateCompanyDepartureTime ?: fix.time
        val firstHome = if (homeStable) previous.candidateHomeArrivalTime ?: fix.time else previous.candidateHomeArrivalTime
        val movingCount = previous.movingAwayCount + if (fix.movingAway || fix.type == LocationType.HOME) 1 else 0
        val homeCount = if (homeStable) previous.stableHomeCount + 1 else 0
        val elapsed = fix.time - candidate
        val farEnough = fix.companyDistanceMeters?.let { it >= config.companyRadiusMeters + 100.0 } == true
        val confirmed = elapsed >= config.leaveConfirmMinutes * 60_000L && (farEnough || movingCount >= 2 || homeCount >= 2)
        if (!confirmed) return previous.copy(candidateCompanyDepartureTime = candidate,
            candidateHomeArrivalTime = firstHome, movingAwayCount = movingCount,
            stableHomeCount = homeCount, stableCompanyCount = 0)
        events += Event.CompanyDeparture(candidate, fix.time)
        if (firstHome != null) events += Event.HomeArrival(firstHome, fix.time)
        return previous.copy(currentState = "FINISHED", candidateCompanyDepartureTime = candidate,
            confirmedDepartureTime = candidate, companyDepartureConfirmedAt = fix.time,
            candidateHomeArrivalTime = firstHome, homeArrivalTime = firstHome,
            homeArrivalConfirmedAt = if (firstHome != null) fix.time else null,
            movingAwayCount = movingCount, stableHomeCount = homeCount, stableCompanyCount = 0)
    }

    private fun newSession(previous: WorkStateEntity, now: Long) = previous.copy(
        currentState = "LEAVING_HOME", sessionId = UUID.randomUUID().toString(), sessionStart = null,
        candidateHomeDepartureTime = now, homeDepartureTime = now,
        candidateCompanyArrivalTime = null, candidateCompanyDepartureTime = null,
        candidateHomeArrivalTime = null, companyArrivalConfirmedAt = null,
        companyDepartureConfirmedAt = null, homeArrivalConfirmedAt = null,
        confirmedDepartureTime = null, homeArrivalTime = null, tempLeaveStart = null,
        stableCompanyCount = 0, stableHomeCount = 0, movingAwayCount = 1
    )
}
