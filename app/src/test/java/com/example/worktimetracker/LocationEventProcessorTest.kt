package com.example.worktimetracker

import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.domain.model.LocationType
import com.example.worktimetracker.location.service.LocationEventProcessor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LocationEventProcessor 自 2026-09-05 收敛后只承担纯地理分类，
 * 工时状态机唯一实现在 TrajectoryAnchorEngine（见 TrajectoryAnchorEngineTest）。
 */
class LocationEventProcessorTest {
    private val processor = LocationEventProcessor()

    @Test fun inaccurateLocationIsUnknown() {
        val type = processor.classify(
            lat = 30.002,
            lng = 120.0005,
            accuracyMeters = 239.88745f,
            settings = UserSettingsEntity(
                companyLat = 30.0,
                companyLng = 120.0,
                companyRadiusMeters = 200
            )
        )
        assertEquals(LocationType.UNKNOWN, type)
    }

    @Test fun insideCompanyRadiusClassifiesCompany() {
        val type = processor.classify(
            lat = 30.0,
            lng = 120.0,
            accuracyMeters = 20f,
            settings = UserSettingsEntity(
                companyLat = 30.0,
                companyLng = 120.0,
                companyRadiusMeters = 200
            )
        )
        assertEquals(LocationType.COMPANY, type)
    }

    @Test fun insideHomeRadiusClassifiesHome() {
        val type = processor.classify(
            lat = 31.0,
            lng = 121.0,
            accuracyMeters = 20f,
            settings = UserSettingsEntity(
                homeLat = 31.0,
                homeLng = 121.0,
                homeRadiusMeters = 300
            )
        )
        assertEquals(LocationType.HOME, type)
    }
}
