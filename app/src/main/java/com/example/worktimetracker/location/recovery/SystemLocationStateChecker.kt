package com.example.worktimetracker.location.recovery

import android.content.Context
import android.location.LocationManager
import android.os.Build

enum class SystemLocationTransition { NONE, DISABLED, RECOVERED }

object SystemLocationStatePolicy {
    fun isEnabled(api: Int, globalEnabled: Boolean?, gpsEnabled: Boolean, networkEnabled: Boolean): Boolean =
        if (api >= Build.VERSION_CODES.P) globalEnabled == true else gpsEnabled || networkEnabled

    fun transition(lastDisabledAt: Long, lastRecoveredAt: Long, enabled: Boolean): SystemLocationTransition = when {
        !enabled && lastDisabledAt <= lastRecoveredAt -> SystemLocationTransition.DISABLED
        enabled && lastDisabledAt > lastRecoveredAt -> SystemLocationTransition.RECOVERED
        else -> SystemLocationTransition.NONE
    }
}

object SystemLocationStateChecker {
    data class Result(
        val enabled: Boolean,
        val transition: SystemLocationTransition,
        val notifyUser: Boolean
    )

    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching {
            SystemLocationStatePolicy.isEnabled(
                Build.VERSION.SDK_INT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) manager.isLocationEnabled else null,
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER),
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            )
        }.getOrDefault(false)
    }

    fun checkAndRecord(
        context: Context,
        now: Long = System.currentTimeMillis(),
        claimNotification: Boolean = true
    ): Result {
        val enabled = isEnabled(context)
        val transition = SystemLocationStatePolicy.transition(
            ServiceRecovery.lastSystemLocationDisabled(context),
            ServiceRecovery.lastSystemLocationRecovered(context),
            enabled
        )
        ServiceRecovery.recordSystemLocationState(context, enabled, now)
        val notify = !enabled && claimNotification && ServiceRecovery.claimSystemLocationNotification(context)
        return Result(enabled, transition, notify)
    }
}
