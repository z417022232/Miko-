package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.LearnedPlaceModelEntity
import com.example.worktimetracker.data.entity.LearningModelMetaEntity
import com.example.worktimetracker.data.entity.PlaceAnchorCandidateEntity
import kotlinx.coroutines.flow.Flow

/**
 * 学习层数据访问（DB v14）。
 *
 * 三张表放在同一个 DAO 里，是因为它们的生命周期绑定在一起：
 * 一次学习要么同时产出「候选 + （可能的）模型 + 版本元数据」，要么什么都不产出。
 * 拆成三个 DAO 只会让事务边界变得模糊。
 *
 * ⚠️ 本 DAO 只被 `LearningCoordinator` / 地点模型展示读取。
 * **地点判定与状态机不得直接查这里的表** —— 判定只走
 * [com.example.worktimetracker.domain.location.PlaceModelResolver]，否则
 * 「影子验证」就形同虚设。
 */
@Dao
interface LearningModelDao {

    // -------------------------------------------------- 模型版本元数据

    @Query("SELECT * FROM learning_model_meta WHERE modelType = :modelType ORDER BY modelVersion DESC")
    fun observeMeta(modelType: String): Flow<List<LearningModelMetaEntity>>

    @Query("SELECT * FROM learning_model_meta ORDER BY modelType ASC, modelVersion DESC")
    suspend fun allMeta(): List<LearningModelMetaEntity>

    @Query("SELECT * FROM learning_model_meta WHERE modelType = :modelType ORDER BY modelVersion DESC LIMIT 1")
    suspend fun latestMeta(modelType: String): LearningModelMetaEntity?

    @Query("SELECT COALESCE(MAX(modelVersion), 0) FROM learning_model_meta WHERE modelType = :modelType")
    suspend fun maxVersion(modelType: String): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: LearningModelMetaEntity)

    /** 回滚/退役：把某类型下除 [keepVersion] 外的 ACTIVE 版本置为退役（不删行）。 */
    @Query(
        "UPDATE learning_model_meta SET status = :retiredStatus, invalidatedAt = :now " +
            "WHERE modelType = :modelType AND modelVersion <> :keepVersion AND status = 'ACTIVE'"
    )
    suspend fun retireOtherVersions(
        modelType: String,
        keepVersion: Long,
        retiredStatus: String,
        now: Long
    )

    // ------------------------------------------------------ 地点学习模型

    @Query("SELECT * FROM learned_place_models")
    fun observePlaces(): Flow<List<LearnedPlaceModelEntity>>

    @Query("SELECT * FROM learned_place_models")
    suspend fun allPlaces(): List<LearnedPlaceModelEntity>

    @Query("SELECT * FROM learned_place_models WHERE placeId = :placeId LIMIT 1")
    suspend fun place(placeId: Long): LearnedPlaceModelEntity?

    @Query("SELECT * FROM learned_place_models WHERE autoApplied = 1")
    suspend fun autoAppliedPlaces(): List<LearnedPlaceModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlace(model: LearnedPlaceModelEntity)

    @Query("DELETE FROM learned_place_models WHERE placeId = :placeId")
    suspend fun deletePlace(placeId: Long)

    // -------------------------------------------------------- 锚点候选

    @Query("SELECT * FROM place_anchor_candidates ORDER BY lastSeenAt DESC")
    fun observeCandidates(): Flow<List<PlaceAnchorCandidateEntity>>

    @Query("SELECT * FROM place_anchor_candidates WHERE placeId = :placeId ORDER BY lastSeenAt DESC LIMIT 1")
    suspend fun latestCandidate(placeId: Long): PlaceAnchorCandidateEntity?

    /**
     * 某个**影子窗口**的全部观测（窗口身份 = `firstSeenAt`），按时间升序。
     *
     * 这是影子验证的唯一数据来源：`ShadowValidator` 的六个条件全从这组行重建，
     * 所以「模型可从原始数据全量重建」在候选层是成立的。
     */
    @Query(
        "SELECT * FROM place_anchor_candidates WHERE placeId = :placeId AND firstSeenAt = :firstSeenAt " +
            "ORDER BY lastSeenAt ASC"
    )
    suspend fun candidatesInWindow(placeId: Long, firstSeenAt: Long): List<PlaceAnchorCandidateEntity>

    /** 同一自然日内重复学习 → 原地刷新该行，不再插新行（「一天一行」的保证）。 */
    @Query(
        "UPDATE place_anchor_candidates SET ambientSourceCount = :ambientSourceCount, " +
            "spreadP90Meters = :spreadP90Meters, sampleCount = :sampleCount, " +
            "distinctDayCount = :distinctDayCount, stableMillis = :stableMillis, " +
            "offsetMeters = :offsetMeters, status = :status, modelVersion = :modelVersion, " +
            "explanation = :explanation, lastSeenAt = :now, updatedAt = :now WHERE id = :id"
    )
    suspend fun updateCandidateObservation(
        id: Long,
        sampleCount: Int,
        distinctDayCount: Int,
        ambientSourceCount: Int,
        stableMillis: Long,
        offsetMeters: Double,
        spreadP90Meters: Double?,
        status: String,
        modelVersion: Long,
        explanation: String,
        now: Long
    )

    @Query("SELECT * FROM place_anchor_candidates ORDER BY lastSeenAt DESC LIMIT :limit")
    suspend fun recentCandidates(limit: Int): List<PlaceAnchorCandidateEntity>

    @Query("SELECT COUNT(*) FROM place_anchor_candidates")
    suspend fun candidateCount(): Int

    @Insert
    suspend fun insertCandidate(candidate: PlaceAnchorCandidateEntity): Long

    /** 候选明细只保留最近 N 条/天：学习是长期行为，不能让它把库撑爆（方案 §十 性能要求）。 */
    @Query("DELETE FROM place_anchor_candidates WHERE createdAt < :cutoff")
    suspend fun deleteCandidatesBefore(cutoff: Long)
}
