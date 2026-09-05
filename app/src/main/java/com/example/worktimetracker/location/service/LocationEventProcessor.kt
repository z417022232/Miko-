package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer
import com.example.worktimetracker.domain.model.LocationType

/**
 * 纯地理分类器：把经纬度映射为 HOME/COMPANY/OTHER/UNKNOWN。
 * 工时状态机唯一实现在 [TrajectoryAnchorEngine]（2026-09-05 收敛），
 * 本类不再承载任何状态转换逻辑，只作为融合证据不可用时的地理兜底分类。
 */
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

    private companion object {
        const val MAX_USABLE_ACCURACY_METERS = 100f
    }
}
