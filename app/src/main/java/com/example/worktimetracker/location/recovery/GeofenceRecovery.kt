package com.example.worktimetracker.location.recovery

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.location.receiver.LocationTransitionReceiver
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object GeofenceRecovery {
    private const val HOME_ID = "work-time-home"
    private const val COMPANY_ID = "work-time-company"

    suspend fun register(context: Context, settings: UserSettingsEntity): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        val homeLat = settings.homeLat ?: return false
        val homeLng = settings.homeLng ?: return false
        val companyLat = settings.companyLat ?: return false
        val companyLng = settings.companyLng ?: return false
        if (!fine || !background) return false
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence(HOME_ID, homeLat, homeLng, settings.homeRadiusMeters.toFloat()))
            .addGeofence(geofence(COMPANY_ID, companyLat, companyLng, settings.companyRadiusMeters.toFloat()))
            .build()
        return suspendCoroutine { continuation ->
            client(context).addGeofences(request, pendingIntent(context))
                .addOnSuccessListener { continuation.resume(true) }
                .addOnFailureListener { continuation.resume(registerPlatformFallback(context, settings)) }
        }
    }

    private fun geofence(id: String, latitude: Double, longitude: Double, radius: Float): Geofence =
        Geofence.Builder().setRequestId(id).setCircularRegion(latitude, longitude, radius)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setExpirationDuration(Geofence.NEVER_EXPIRE).build()

    private fun client(context: Context): GeofencingClient = LocationServices.getGeofencingClient(context)

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun registerPlatformFallback(context: Context, settings: UserSettingsEntity): Boolean = runCatching {
        val homeLat = requireNotNull(settings.homeLat)
        val homeLng = requireNotNull(settings.homeLng)
        val companyLat = requireNotNull(settings.companyLat)
        val companyLng = requireNotNull(settings.companyLng)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val pending = pendingIntent(context)
        manager.addProximityAlert(homeLat, homeLng, settings.homeRadiusMeters.toFloat(), -1L, pending)
        manager.addProximityAlert(companyLat, companyLng, settings.companyRadiusMeters.toFloat(), -1L, pending)
        true
    }.getOrDefault(false)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 24601, Intent(context, LocationTransitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
}
