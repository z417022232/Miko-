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
    val onboardingDone: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
