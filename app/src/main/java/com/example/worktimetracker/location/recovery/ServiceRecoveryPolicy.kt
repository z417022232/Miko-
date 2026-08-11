package com.example.worktimetracker.location.recovery

object ServiceRecoveryPolicy {
    enum class RecoveryTrigger { USER_VISIBLE, BOOT, GEOFENCE, BACKGROUND_HEALTH_CHECK }
    val actions = setOf(
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.USER_UNLOCKED",
        "android.intent.action.MY_PACKAGE_REPLACED"
    )
    const val healthCheckMinutes = 15L

    fun canStartLocationService(
        trigger: RecoveryTrigger,
        hasFineLocation: Boolean,
        hasCoarseLocation: Boolean,
        hasBackgroundLocation: Boolean = false
    ): Boolean = when (trigger) {
        RecoveryTrigger.USER_VISIBLE -> hasFineLocation || hasCoarseLocation
        RecoveryTrigger.BOOT -> hasBackgroundLocation && (hasFineLocation || hasCoarseLocation)
        RecoveryTrigger.GEOFENCE -> hasBackgroundLocation && (hasFineLocation || hasCoarseLocation)
        RecoveryTrigger.BACKGROUND_HEALTH_CHECK -> false
    }
}
