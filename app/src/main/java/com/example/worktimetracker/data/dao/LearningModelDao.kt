package com.example.worktimetracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.worktimetracker.data.entity.LearnedPlaceModelEntity
import com.example.worktimetracker.data.entity.LearningModelMetaEntity
import com.example.worktimetracker.data.entity.PlaceAnchorCandidateEntity
import com.example.worktimetracker.data.entity.PlaceLearningPreferenceEntity
import kotlinx.coroutines.flow.Flow

/**
 * 学习层数据访问（DB v14 / v15 / v16）。
 *
 * 四张表放在同一个 DAO 里，是因为它们的生命周期绑定在一起：
 * 一次学习要么同时产出「候选 + （可能的）模型 + 版本元数据」，要么什么都不产出。
 * 拆成四个 DAO 只会让事务边界变得模糊。
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

    /**
     * 只改「可生效」这一列。
     *
     * 刻意**不用** `upsertPlace(读出来改一改再写回)`：那是「读-改-写」，
     * 用户在界面点开关的同时后台正在跑学习，两次写会互相盖掉（后写的赢，
     * 于是刚设的 `autoApplied` 被学习那一轮算出来的旧值覆盖）。
     * 一条定向 UPDATE 只碰目标列，与学习那一轮的整行写入不会互相吃掉。
     */
    @Query(
        "UPDATE learned_place_models SET autoApplied = :autoApplied, updatedAt = :now " +
            "WHERE placeId = :placeId"
    )
    suspend fun setAutoApplied(placeId: Long, autoApplied: Boolean, now: Long)

    // -------------------------------------------------- 用户偏好（DB v16）

    @Query("SELECT * FROM place_learning_preferences")
    fun observePreferences(): Flow<List<PlaceLearningPreferenceEntity>>

    @Query("SELECT * FROM place_learning_preferences")
    suspend fun allPreferences(): List<PlaceLearningPreferenceEntity>

    @Query("SELECT * FROM place_learning_preferences WHERE placeId = :placeId LIMIT 1")
    suspend fun preference(placeId: Long): PlaceLearningPreferenceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreference(preference: PlaceLearningPreferenceEntity)

    @Query("DELETE FROM place_learning_preferences WHERE placeId = :placeId")
    suspend fun deletePreference(placeId: Long)

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

    /**
     * **重开影子窗口**：把这一行的窗口起始时刻推到 [now]，于是它独自构成一个新窗口，
     * 旧窗口的行自然作废（保留期到了自然清理，不删）。
     *
     * ⚠️ 只改**一行**（调用方传最新候选行的 id）。若写成
     * `WHERE placeId = :placeId`（不带 id），会把该地点所有历史窗口的行
     * 全部并进同一个窗口 —— 那等于拿几段不连续的观察当连续观察用，
     * 前向验证立刻失去意义。
     */
    @Query(
        "UPDATE place_anchor_candidates SET firstSeenAt = :now, status = :status, " +
            "explanation = :explanation, updatedAt = :now WHERE id = :id"
    )
    suspend fun reopenShadowWindow(id: Long, now: Long, status: String, explanation: String)

    /** 候选明细只保留最近 N 条/天：学习是长期行为，不能让它把库撑爆（方案 §十 性能要求）。 */
    @Query("DELETE FROM place_anchor_candidates WHERE createdAt < :cutoff")
    suspend fun deleteCandidatesBefore(cutoff: Long)
}
