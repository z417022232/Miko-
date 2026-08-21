package com.example.worktimetracker.location.recovery

import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.worktimetracker.location.service.ForegroundLocationService
import java.util.concurrent.TimeUnit

object ServiceRecovery {
    private const val UNIQUE_WORK = "work-time-location-health"
    private const val PREFS = "location_service_health"
    private const val HEARTBEAT = "service_heartbeat"
    private const val CALLBACK = "last_location_callback"
    private const val RELIABLE = "last_reliable_location"
    private const val PROVIDER = "provider_available"

    fun start(context: Context, trigger: ServiceRecoveryPolicy.RecoveryTrigger): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!ServiceRecoveryPolicy.canStartLocationService(trigger, fine, coarse, background)) return false
        return runCatching {
            ContextCompat.startForegroundService(context, Intent(context, ForegroundLocationService::class.java))
            true
        }.getOrDefault(false)
    }

    fun schedule(context: Context): Boolean = runCatching {
        val request = PeriodicWorkRequestBuilder<LocationHealthWorker>(
            ServiceRecoveryPolicy.healthCheckMinutes,
            TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        true
    }.getOrDefault(false)

    fun heartbeat(context: Context, now: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(HEARTBEAT, now).apply()
    }

    fun locationCallback(context: Context, reliable: Boolean, now: Long = System.currentTimeMillis()) {
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(CALLBACK, now)
        if (reliable) edit.putLong(RELIABLE, now)
        edit.apply()
    }

    fun providerAvailable(context: Context, available: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PROVIDER, available).apply()
    }

    fun snapshot(context: Context): ServiceHealthSnapshot = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        ServiceHealthSnapshot(it.getLong(HEARTBEAT, 0), it.getLong(CALLBACK, 0),
            it.getLong(RELIABLE, 0), it.getBoolean(PROVIDER, true))
    }

    fun isHealthy(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(HEARTBEAT, 0L)
        return last > 0L && now - last < 25 * 60_000L
    }
}
