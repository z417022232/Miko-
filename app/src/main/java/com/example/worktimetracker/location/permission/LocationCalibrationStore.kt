package com.example.worktimetracker.location.permission

import android.content.Context

class LocationCalibrationStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("location_calibration", Context.MODE_PRIVATE)
    fun companyStableRadius(): Int = prefs.getInt("company_stable_radius", 100).coerceIn(60, 150)
    fun companyCalibratedAt(): Long = prefs.getLong("company_calibrated_at", 0L)
    fun saveCompany(radius: Int, calibratedAt: Long) {
        prefs.edit().putInt("company_stable_radius", radius.coerceIn(60, 150))
            .putLong("company_calibrated_at", calibratedAt).apply()
    }
}
