package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.EnvironmentFingerprintEntity
import com.example.worktimetracker.data.entity.EvidenceObservationEntity
import com.example.worktimetracker.data.entity.LocationHealthEntity

@Dao
interface EnvironmentEvidenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFingerprint(fingerprint: EnvironmentFingerprintEntity)

    @Query("SELECT * FROM environment_fingerprints WHERE place = :place AND source = :source")
    suspend fun fingerprints(place: String, source: String): List<EnvironmentFingerprintEntity>

    @Query("SELECT * FROM environment_fingerprints")
    suspend fun allFingerprints(): List<EnvironmentFingerprintEntity>

    @Insert
    suspend fun insertObservation(observation: EvidenceObservationEntity): Long

    @Query("SELECT * FROM evidence_observations WHERE eventTime >= :since ORDER BY eventTime ASC")
    suspend fun recentObservations(since: Long): List<EvidenceObservationEntity>

    @Query("UPDATE evidence_observations SET usedForEvent = :used WHERE id = :id")
    suspend fun markUsedForEvent(id: Long, used: Boolean)

    @Query("SELECT COUNT(*) FROM evidence_observations WHERE usedForEvent = 1")
    suspend fun usedForEventCount(): Int

    @Query("DELETE FROM evidence_observations WHERE eventTime < :cutoff")
    suspend fun deleteObservationsBefore(cutoff: Long)

    @Query(
        "DELETE FROM evidence_observations WHERE source = :source AND id NOT IN " +
            "(SELECT id FROM evidence_observations WHERE source = :source ORDER BY eventTime DESC, id DESC LIMIT :keep)"
    )
    suspend fun trimObservations(source: String, keep: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHealth(health: LocationHealthEntity)

    @Query("SELECT * FROM location_health")
    suspend fun allHealth(): List<LocationHealthEntity>

    @Query("SELECT * FROM location_health WHERE name = :name")
    suspend fun health(name: String): LocationHealthEntity?
}
