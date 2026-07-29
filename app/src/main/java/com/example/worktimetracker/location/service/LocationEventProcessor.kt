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

    fun nextState(previous: WorkStateEntity, type: LocationType, now: Long, settings: UserSettingsEntity): WorkStateEntity {
        val leaveConfirmMillis = settings.leaveCompanyConfirmMinutes * 60_000L
        val next = when (previous.currentState) {
            "REST" -> when (type) {
                LocationType.HOME -> previous.copy(currentState = "REST", sessionStart = null)
                LocationType.COMPANY -> previous.copy(currentState = "NEAR_COMPANY", sessionStart = now)
                LocationType.OTHER -> previous.copy(currentState = "LEAVING_HOME", sessionStart = now)
                LocationType.UNKNOWN -> previous
            }
            "LEAVING_HOME" -> when (type) {
                LocationType.HOME -> previous.copy(currentState = "REST", sessionStart = null)
                LocationType.COMPANY -> previous.copy(currentState = "WORKING", sessionStart = now)
                LocationType.OTHER, LocationType.UNKNOWN -> previous.copy(currentState = "LEAVING_HOME", sessionStart = previous.sessionStart ?: now)
            }
            "NEAR_COMPANY" -> when (type) {
                LocationType.COMPANY -> previous.copy(currentState = "WORKING", sessionStart = previous.sessionStart ?: now)
                LocationType.HOME -> previous.copy(currentState = "REST", sessionStart = null)
                else -> previous.copy(currentState = "LEAVING_HOME", sessionStart = previous.sessionStart ?: now)
            }
            "WORKING" -> when (type) {
                LocationType.COMPANY -> previous.copy(tempLeaveStart = null)
                LocationType.UNKNOWN -> previous
                else -> previous.copy(currentState = "TEMP_LEAVE", tempLeaveStart = now)
            }
            "TEMP_LEAVE" -> when (type) {
                LocationType.COMPANY -> previous.copy(currentState = "WORKING", tempLeaveStart = null)
                LocationType.UNKNOWN -> previous
                else -> if (previous.tempLeaveStart != null && now - previous.tempLeaveStart >= leaveConfirmMillis) {
                    previous.copy(currentState = "FINISHED", tempLeaveStart = null)
                } else {
                    previous
                }
            }
            "FINISHED" -> when (type) {
                LocationType.HOME -> previous.copy(currentState = "REST", sessionStart = null)
                LocationType.COMPANY -> previous.copy(currentState = "WORKING", sessionStart = now)
                LocationType.OTHER -> previous.copy(currentState = "LEAVING_HOME", sessionStart = now)
                LocationType.UNKNOWN -> previous
            }
            else -> previous.copy(currentState = if (type == LocationType.HOME) "REST" else previous.currentState)
        }
        return next.copy(lastLocationTime = now, updatedAt = now)
    }

    private companion object {
        const val MAX_USABLE_ACCURACY_METERS = 100f
    }
}
