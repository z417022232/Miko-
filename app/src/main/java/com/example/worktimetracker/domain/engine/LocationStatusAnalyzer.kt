package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.domain.model.LocationType
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class LocationStatusAnalyzer {
    fun classify(
        latitude: Double,
        longitude: Double,
        companyLat: Double?,
        companyLng: Double?,
        companyRadiusMeters: Int,
        homeLat: Double?,
        homeLng: Double?,
        homeRadiusMeters: Int
    ): LocationType {
        if (companyLat != null && companyLng != null && distanceMeters(latitude, longitude, companyLat, companyLng) <= companyRadiusMeters) {
            return LocationType.COMPANY
        }
        if (homeLat != null && homeLng != null && distanceMeters(latitude, longitude, homeLat, homeLng) <= homeRadiusMeters) {
            return LocationType.HOME
        }
        return LocationType.OTHER
    }

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
