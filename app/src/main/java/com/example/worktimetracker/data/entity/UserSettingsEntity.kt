package com.example.worktimetracker.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val companyLat: Double? = null,
    val companyLng: Double? = null,
    val companyRadiusMeters: Int = 150,
    val homeLat: Double? = null,
    val homeLng: Double? = null,
    val homeRadiusMeters: Int = 150,
    val workStartMinutes: Int = 9 * 60,
    val workEndMinutes: Int = 21 * 60,
    val hasDefaultHours: Boolean = false,
    val defaultWorkMinutes: Int? = null,
    val restDeductionMinutes: Int = 60,
    val outsideThresholdMinutes: Int = 120,
    val leaveCompanyConfirmMinutes: Int = 60,
    val earlyLeaveToleranceMinutes: Int = 3,
    val notificationEnabled: Boolean = true,
    // ---- v4 界面稿新增（DB v11）----
    /** 基本时薪（分/小时）。0 = 未设置，此时工资卡片显示引导语而不是金额。 */
    val hourlyRateCents: Long = 0L,
    /** 常规采集间隔（分钟）：1 / 3 / 5 / 10。 */
    val samplingIntervalMinutes: Int = 5,
    /** Burst 上限（分钟）：3 / 5 / 10。到上限必须回落，这是防后台被限流的硬约束。 */
    val burstCapMinutes: Int = 10,
    /** 定位精度档：[LOCATION_ACCURACY_POWER_SAVING] / [LOCATION_ACCURACY_BALANCED] / [LOCATION_ACCURACY_HIGH]。 */
    val locationAccuracyMode: String = LOCATION_ACCURACY_BALANCED,
    /** 固定休息日：[REST_WEEK_SAT_SUN] / [REST_WEEK_SUN_ONLY] / [REST_WEEK_FLEXIBLE]。 */
    val restWeekPattern: String = REST_WEEK_SAT_SUN,
    /** 节假日数据来源：[HOLIDAY_SOURCE_BUILT_IN] / [HOLIDAY_SOURCE_SYSTEM_CALENDAR]。 */
    val holidaySourceMode: String = HOLIDAY_SOURCE_BUILT_IN,
    val onboardingDone: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val LOCATION_ACCURACY_POWER_SAVING = "POWER_SAVING"
        const val LOCATION_ACCURACY_BALANCED = "BALANCED"
        const val LOCATION_ACCURACY_HIGH = "HIGH"

        const val REST_WEEK_SAT_SUN = "SAT_SUN"
        const val REST_WEEK_SUN_ONLY = "SUN_ONLY"
        const val REST_WEEK_FLEXIBLE = "FLEXIBLE"

        const val HOLIDAY_SOURCE_BUILT_IN = "BUILT_IN"
        const val HOLIDAY_SOURCE_SYSTEM_CALENDAR = "SYSTEM_CALENDAR"
    }
}
