package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.UserSettingsEntity

/** 将设置页精度档转换为 LocationManager 的真实注册参数。 */
object LocationAccuracyPolicy {
    data class Plan(
        val activeProviders: List<String>,
        val minDistanceMeters: Float
    )

    fun plan(mode: String?): Plan = when (mode) {
        UserSettingsEntity.LOCATION_ACCURACY_HIGH -> Plan(listOf("gps", "network"), 10f)
        UserSettingsEntity.LOCATION_ACCURACY_POWER_SAVING -> Plan(listOf("network"), 100f)
        else -> Plan(listOf("gps", "network"), 50f)
    }

    fun requiresReconfigure(previous: String?, next: String?): Boolean = plan(previous) != plan(next)
}
