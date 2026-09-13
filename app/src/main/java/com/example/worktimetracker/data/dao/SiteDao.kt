package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.SiteEntity
import com.example.worktimetracker.data.entity.SiteEvidenceSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteDao {

    // ---------------------------------------------------------------- 地点

    @Query("SELECT * FROM sites ORDER BY isPrimary DESC, id ASC")
    fun observeAll(): Flow<List<SiteEntity>>

    @Query("SELECT * FROM sites ORDER BY isPrimary DESC, id ASC")
    suspend fun all(): List<SiteEntity>

    @Query("SELECT * FROM sites WHERE enabled = 1 ORDER BY isPrimary DESC, id ASC")
    suspend fun enabled(): List<SiteEntity>

    @Query("SELECT * FROM sites WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): SiteEntity?

    @Query("SELECT * FROM sites WHERE siteType = :siteType AND enabled = 1 ORDER BY isPrimary DESC, id ASC")
    suspend fun byType(siteType: String): List<SiteEntity>

    @Query("SELECT COUNT(*) FROM sites")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(site: SiteEntity): Long

    @Query("UPDATE sites SET isPrimary = 0")
    suspend fun clearPrimary()

    @Query("UPDATE sites SET enabled = :enabled, updatedAt = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    @Query("DELETE FROM sites WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sites")
    suspend fun deleteAll()

    // ------------------------------------------------------------ 证据源

    @Query("SELECT * FROM site_evidence_sources ORDER BY siteId ASC, sourceType ASC")
    fun observeSources(): Flow<List<SiteEvidenceSourceEntity>>

    @Query("SELECT * FROM site_evidence_sources WHERE siteId = :siteId ORDER BY sourceType ASC")
    suspend fun sourcesFor(siteId: Long): List<SiteEvidenceSourceEntity>

    @Query("SELECT * FROM site_evidence_sources")
    suspend fun allSources(): List<SiteEvidenceSourceEntity>

    @Query("SELECT COUNT(*) FROM site_evidence_sources WHERE siteId = :siteId")
    suspend fun sourceCount(siteId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSource(source: SiteEvidenceSourceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSources(sources: List<SiteEvidenceSourceEntity>)

    @Query("DELETE FROM site_evidence_sources WHERE siteId = :siteId")
    suspend fun deleteSourcesFor(siteId: Long)

    @Query("DELETE FROM site_evidence_sources WHERE siteId = :siteId AND sourceType = :sourceType")
    suspend fun deleteSources(siteId: Long, sourceType: String)

    @Query("DELETE FROM site_evidence_sources")
    suspend fun deleteAllSources()
}
