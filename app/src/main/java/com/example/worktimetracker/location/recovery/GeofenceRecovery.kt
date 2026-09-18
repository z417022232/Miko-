package com.example.worktimetracker.location.recovery

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.data.entity.LearnedPlaceModelEntity
import com.example.worktimetracker.data.entity.PlaceLearningPreferenceEntity
import com.example.worktimetracker.data.entity.SiteEntity
import com.example.worktimetracker.domain.location.GeoPoint
import com.example.worktimetracker.domain.location.PlaceLearningPreference
import com.example.worktimetracker.domain.location.PlaceModelResolver
import com.example.worktimetracker.location.receiver.LocationTransitionReceiver
import com.example.worktimetracker.location.service.toLearnedPlaceModel
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

object GeofenceRecovery {
    data class Target(val siteId: Long, val requestId: String, val latitude: Double, val longitude: Double, val radiusMeters: Float)

    fun resolveTargets(sites: List<SiteEntity>, models: List<LearnedPlaceModelEntity>, preferences: List<PlaceLearningPreferenceEntity>): List<Target> {
        val byModel = models.associateBy { it.placeId }
        val byPreference = preferences.associateBy { it.placeId }
        return sites.asSequence().filter { it.enabled && it.hasGps && it.id > 0L }.sortedBy { it.id }.mapNotNull { site ->
            val configured = GeoPoint(site.latitude!!, site.longitude!!)
            val pref = byPreference[site.id]?.let { PlaceLearningPreference(it.placeId, it.autoApplyEnabled, it.updatedAt) }
            val point = PlaceModelResolver.effectiveAnchor(byModel[site.id]?.toLearnedPlaceModel(), configured, pref)
                ?: return@mapNotNull null
            Target(site.id, "work-time-site-${site.id}", point.latitude, point.longitude, site.radiusMeters.toFloat())
        }.toList()
    }

    suspend fun register(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val background = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine || !background) return false
        val app = context.applicationContext as? WorkTimeApplication ?: return false
        val targets = resolveTargets(app.database.siteDao().all(), app.database.learningModelDao().allPlaces(), app.database.learningModelDao().allPreferences())
        if (targets.isEmpty()) return false
        val request = GeofencingRequest.Builder().setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .apply { targets.forEach { addGeofence(geofence(it)) } }.build()
        val pending = pendingIntent(context)
        return suspendCoroutine { continuation ->
            client(context).removeGeofences(pending).addOnCompleteListener {
                client(context).addGeofences(request, pending)
                    .addOnSuccessListener { continuation.resume(true) }
                    .addOnFailureListener { continuation.resume(registerPlatformFallback(context, targets)) }
            }
        }
    }

    private fun geofence(target: Target): Geofence = Geofence.Builder().setRequestId(target.requestId)
        .setCircularRegion(target.latitude, target.longitude, target.radiusMeters)
        .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
        .setExpirationDuration(Geofence.NEVER_EXPIRE).build()

    private fun client(context: Context): GeofencingClient = LocationServices.getGeofencingClient(context)

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun registerPlatformFallback(context: Context, targets: List<Target>): Boolean = runCatching {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        targets.forEach { target ->
            val pending = platformPendingIntent(context, target)
            manager.removeProximityAlert(pending)
            manager.addProximityAlert(target.latitude, target.longitude, target.radiusMeters, -1L, pending)
        }
        true
    }.getOrDefault(false)

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 24601, Intent(context, LocationTransitionReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    private fun platformPendingIntent(context: Context, target: Target): PendingIntent = PendingIntent.getBroadcast(
        context,
        (target.siteId xor (target.siteId ushr 32)).toInt(),
        Intent(context, LocationTransitionReceiver::class.java).putExtra("siteId", target.siteId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
}
