package com.example.worktimetracker.location.recovery

object ServiceRecoveryPolicy {
    val actions = setOf(
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.USER_UNLOCKED",
        "android.intent.action.MY_PACKAGE_REPLACED"
    )
    const val healthCheckMinutes = 15L

    fun canStartLocationService(hasFineLocation: Boolean, hasCoarseLocation: Boolean): Boolean =
        hasFineLocation || hasCoarseLocation
}
