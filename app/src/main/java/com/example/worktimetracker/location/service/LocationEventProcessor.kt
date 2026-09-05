package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.data.entity.WorkStateEntity
import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer
import com.example.worktimetracker.domain.model.LocationType

class LocationEventProcessor(private val analyzer: LocationStatusAnalyzer = LocationStatusAnalyzer()) {
    fun classify(lat: Double, lng: Double, accuracyMeters: Float, settings: UserSettingsEntity): LocationType {
        if (accuracyMeters > MAX_USABLE_ACCURACY_METERS) return LocationType.UNKNOWN
        return analyzer.classify(
            latitude = lat,
            longitude = lng,
            companyLat = settings.companyLat,
            companyLng = settings.companyLng,
            companyRadiusMeters = settings.companyRadiusMeters,
            homeLat = settings.homeLat,
            homeLng = settings.homeLng,
            homeRadiusMeters = settings.homeRadiusMeters
        )
    }

    fun nextState(
        previous: WorkStateEntity,
        type: LocationType,
        now: Long,
        settings: UserSettingsEntity,
        distanceFromCompanyMeters: Double? = null,
        isMovingAway: Boolean = false
    ): WorkStateEntity {
        val leaveConfirmMillis = settings.leaveCompanyConfirmMinutes * 60_000L
        val next = when (previous.currentState) {
            "REST" -> when (type) {
                LocationType.HOME -> previous.copy(currentState = "REST", sessionStart = null)
                LocationType.COMPANY -> previous.newSession("NEAR_COMPANY", now, null)
                LocationType.OTHER -> previous.newSession("LEAVING_HOME", now, now)
                LocationType.UNKNOWN -> previous
            }
            "LEAVING_HOME" -> when (type) {
                LocationType.HOME -> previous.copy(currentState = "REST", sessionStart = null)
                LocationType.COMPANY -> previous.copy(currentState = "NEAR_COMPANY", sessionStart = now,
                    candidateCompanyArrivalTime = now, stableCompanyCount = 1)
                LocationType.OTHER, LocationType.UNKNOWN -> previous.copy(currentState = "LEAVING_HOME", sessionStart = previous.sessionStart ?: now)
            }
            "NEAR_COMPANY" -> when (type) {
                LocationType.COMPANY -> previous.copy(currentState = "WORKING", sessionStart = previous.sessionStart ?: now,
                    companyArrivalConfirmedAt = now, stableCompanyCount = previous.stableCompanyCount + 1)
                LocationType.HOME -> previous.copy(currentState = "REST", sessionStart = null)
                else -> previous.copy(currentState = "LEAVING_HOME", sessionStart = previous.sessionStart ?: now)
            }
            "WORKING" -> when (type) {
                LocationType.COMPANY -> previous.copy(tempLeaveStart = null)
                LocationType.UNKNOWN -> previous
                else -> previous.copy(currentState = "TEMP_LEAVE", tempLeaveStart = now,
                    candidateCompanyDepartureTime = now,
                    candidateHomeArrivalTime = if (type == LocationType.HOME) now else null)
            }
            "TEMP_LEAVE" -> when (type) {
                LocationType.COMPANY -> previous.copy(currentState = "WORKING", tempLeaveStart = null,
                    candidateCompanyDepartureTime = null, candidateHomeArrivalTime = null)
                LocationType.UNKNOWN -> previous
                else -> if (previous.tempLeaveStart != null && now - previous.tempLeaveStart >= leaveConfirmMillis &&
                    (type == LocationType.HOME || (isMovingAway && distanceFromCompanyMeters != null &&
                        distanceFromCompanyMeters >= settings.companyRadiusMeters + MIN_DEPARTURE_DISTANCE_METERS))
                ) {
                    previous.copy(
                        currentState = "FINISHED",
                        confirmedDepartureTime = previous.tempLeaveStart,
                        candidateCompanyDepartureTime = previous.tempLeaveStart,
                        candidateHomeArrivalTime = if (type == LocationType.HOME) previous.candidateHomeArrivalTime ?: now else previous.candidateHomeArrivalTime,
                        homeArrivalTime = if (type == LocationType.HOME) previous.candidateHomeArrivalTime ?: now else previous.homeArrivalTime,
                        tempLeaveStart = null
                    )
                } else {
                    previous.copy(candidateHomeArrivalTime = if (type == LocationType.HOME) previous.candidateHomeArrivalTime ?: now else previous.candidateHomeArrivalTime)
                }
            }
            "FINISHED" -> when (type) {
                LocationType.HOME -> if (previous.homeArrivalTime == null) previous.copy(
                    currentState = "REST", sessionStart = null,
                    // 与 TrajectoryAnchorEngine.FINISHED 分支语义一致：迟到到家证据必须补齐到家时间，
                    // 否则记录缺 homeArrivalTime 会被标记 needsReview 要求手动确认
                    homeArrivalTime = previous.candidateHomeArrivalTime ?: now,
                    homeArrivalConfirmedAt = now
                ) else previous.copy(currentState = "REST", sessionStart = null)
                LocationType.COMPANY -> previous.copy(currentState = "WORKING", sessionStart = now)
                LocationType.OTHER -> previous.copy(currentState = "LEAVING_HOME", sessionStart = now)
                LocationType.UNKNOWN -> previous
            }
            else -> previous.copy(currentState = if (type == LocationType.HOME) "REST" else previous.currentState)
        }
        return next.copy(lastLocationTime = now, lastCompanyDistanceMeters = distanceFromCompanyMeters, updatedAt = now)
    }

    private companion object {
        const val MAX_USABLE_ACCURACY_METERS = 100f
        const val MIN_DEPARTURE_DISTANCE_METERS = 100.0
    }

    private fun WorkStateEntity.newSession(state: String, now: Long, homeDeparture: Long?) = copy(
        currentState = state,
        sessionStart = now,
        sessionId = java.util.UUID.randomUUID().toString(),
        tempLeaveStart = null,
        confirmedDepartureTime = null,
        homeDepartureTime = homeDeparture,
        homeArrivalTime = null,
        candidateHomeDepartureTime = homeDeparture,
        candidateCompanyArrivalTime = if (state == "NEAR_COMPANY") now else null,
        candidateCompanyDepartureTime = null,
        candidateHomeArrivalTime = null,
        companyArrivalConfirmedAt = null,
        companyDepartureConfirmedAt = null,
        homeArrivalConfirmedAt = null,
        stableCompanyCount = 0,
        stableHomeCount = 0,
        movingAwayCount = 0
    )
}
