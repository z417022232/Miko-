package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer
import com.example.worktimetracker.domain.engine.SitePoint
import com.example.worktimetracker.domain.engine.SiteResolver
import com.example.worktimetracker.domain.model.LocationType

/**
 * 纯地理分类器：把经纬度映射为 HOME/COMPANY/OTHER/UNKNOWN。
 * 工时状态机唯一实现在 [TrajectoryAnchorEngine]（2026-09-05 收敛），
 * 本类不再承载任何状态转换逻辑，只作为融合证据不可用时的地理兜底分类。
 */
class LocationEventProcessor(private val analyzer: LocationStatusAnalyzer = LocationStatusAnalyzer()) {
    /**
     * 地理兜底分类。
     *
     * [sites] 非空时走多地点判定（v4.3）：任一工作地点进圈 → COMPANY，
     * 任一非工作地点进圈 → HOME；都为空则不判。为空集合时退回旧的
     * 「单公司 + 单家庭」判定，保证没有地点记录的装机行为与 v4.2 一致。
     */
    fun classify(
        lat: Double,
        lng: Double,
        accuracyMeters: Float,
        settings: UserSettingsEntity,
        sites: List<SitePoint> = emptyList()
    ): LocationType {
        if (accuracyMeters > MAX_USABLE_ACCURACY_METERS) return LocationType.UNKNOWN
        if (sites.isNotEmpty()) return SiteResolver.classify(lat, lng, sites)
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
