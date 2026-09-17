package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.database.AppDatabase
import com.example.worktimetracker.data.entity.AppLogEntity
import com.example.worktimetracker.data.entity.EnvironmentFingerprintEntity
import com.example.worktimetracker.data.entity.LearnedPlaceModelEntity
import com.example.worktimetracker.data.entity.LearningModelMetaEntity
import com.example.worktimetracker.data.entity.PlaceAnchorCandidateEntity
import com.example.worktimetracker.data.entity.SiteEntity
import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer
import com.example.worktimetracker.domain.learning.LearningModelType
import com.example.worktimetracker.domain.learning.ModelStatus
import com.example.worktimetracker.domain.location.AnchorLearner
import com.example.worktimetracker.domain.location.AnchorSampleBuilder
import com.example.worktimetracker.domain.location.AnchorUpdateAction
import com.example.worktimetracker.domain.location.AnchorUpdatePolicy
import com.example.worktimetracker.domain.location.GeoPoint
import java.time.Instant
import java.time.ZoneId

/**
 * 地点锚点的持续学习（方案 §三.2 / §十一 阶段2 的**闭环**）。
 *
 * 闭环形状：
 * ```
 * location_logs ─▶ AnchorSampleBuilder ─▶ AnchorLearner ─▶ AnchorUpdatePolicy
 *                                                              │
 *                                        ┌─────────────────────┴─────────────────────┐
 *                                   AUTO_SMOOTH                          SHADOW / NEEDS_USER_CONFIRM
 *                                        │                                           │
 *                                        ▼                                           ▼
 *                     learned_place_models(autoApplied=true)          learned_place_models(autoApplied=false)
 *                                        │                                           │
 *                                        ▼                                    （记录但不生效）
 *                     SitePoints.withLearnedAnchors() ─▶ 融合判定
 * ```
 * `place_anchor_candidates` 两个分支都写：它是**观测日志**，不是判定输入。
 *
 * ⚠️ 四条纪律（违反即回归）：
 *  1. **只写三张新表**。`sites` 的坐标一个字都不改 —— 用户配置锚点永久权威，
 *     学习锚点的优先级由**取用顺序**（[com.example.worktimetracker.domain.location.PlaceModelResolver]）体现；
 *  2. **判定不读候选表**。影子验证的语义就靠这条保证；
 *  3. **学习不许把主链路带崩**。所有异常吞掉并记 `learning` 日志 ——
 *     学习是锦上添花，定位服务才是主链路；
 *  4. **数字全部来自观测**。候选行里的样本数、跨天数、环境来源类数一律实测，
 *     不许按置信度反推凑一个（那会让排查时看到的数据全是假的）。
 */
class AnchorLearningService(
    private val db: AppDatabase,
    private val learner: AnchorLearner = AnchorLearner(),
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    /** 单次评估的结论（可直接渲染到地点管理页）。 */
    data class Outcome(
        val placeId: Long,
        val placeName: String,
        val action: AnchorUpdateAction?,
        val reason: AnchorLearner.Reason?,
        val explanation: String,
        val candidate: AnchorLearner.Candidate? = null
    )

    private val analyzer = LocationStatusAnalyzer()

    /**
     * 对所有启用且有坐标的地点跑一轮学习。
     *
     * 幂等：同一份历史跑两遍，第二遍走「候选延续」分支，只续期与刷新解释，
     * 不会重复落行、不会重复升级版本 —— 所以它可以安全地挂在启动流程里反复跑。
     */
    suspend fun learnAll(now: Long = System.currentTimeMillis()): List<Outcome> {
        val sites = runCatching { db.siteDao().all() }.getOrDefault(emptyList())
            .filter { it.enabled && it.hasGps }
        if (sites.isEmpty()) return emptyList()

        val fingerprints = runCatching { db.environmentEvidenceDao().allFingerprints() }
            .getOrDefault(emptyList())
        // 30 天轨迹只查一次：多个地点共用同一份轨迹，逐站点各查一遍会把启动 IO 乘以地点数
        val fixes = runCatching {
            db.locationLogDao().getLogs(now - LOOKBACK_MILLIS, now).map {
                AnchorSampleBuilder.Fix(it.time, it.latitude, it.longitude, it.accuracyMeters ?: 999f)
            }
        }.getOrDefault(emptyList())

        return sites.mapNotNull { site ->
            runCatching { learnSite(site, fixes, fingerprints, now) }
                .onFailure { error ->
                    runCatching { db.appLogDao().insert(log("地点学习失败 site=${site.id}：${error.message}")) }
                }
                .getOrNull()
        }
    }

    private suspend fun learnSite(
        site: SiteEntity,
        fixes: List<AnchorSampleBuilder.Fix>,
        fingerprints: List<EnvironmentFingerprintEntity>,
        now: Long
    ): Outcome {
        val center = GeoPoint(site.latitude!!, site.longitude!!)
        val built = AnchorSampleBuilder.build(
            fixes = fixes,
            center = center,
            radiusMeters = site.radiusMeters,
            zone = zone
        )
        val ambientSources = ambientSourceCount(site.siteType, fingerprints)

        return when (
            val result = learner.learn(
                samples = built.samples,
                ambientSourceCount = ambientSources,
                stableMillis = built.stableMillis,
                configuredAnchor = center
            )
        ) {
            is AnchorLearner.Outcome.Insufficient -> Outcome(
                placeId = site.id,
                placeName = site.name,
                action = null,
                reason = result.reason,
                explanation = result.detail
            )

            is AnchorLearner.Outcome.Found ->
                persist(site, center, result.candidate, ambientSources, now)
        }
    }

    /** 候选达标 → 定档 → 落候选行 +（达标才）升级模型。 */
    private suspend fun persist(
        site: SiteEntity,
        configured: GeoPoint,
        candidate: AnchorLearner.Candidate,
        ambientSources: Int,
        now: Long
    ): Outcome {
        val dao = db.learningModelDao()
        val existing = dao.place(site.id)
        val latest = dao.latestCandidate(site.id)

        // 影子期的计时起点只能靠「几何上是不是同一个候选」来判，不能用上一次的状态 ——
        // 状态本身依赖影子天数，用它做连续性判断会形成环。
        val continuing = latest != null && analyzer.distanceMeters(
            latest.centerLat, latest.centerLng,
            candidate.center.latitude, candidate.center.longitude
        ) < CANDIDATE_DEDUP_METERS
        val firstSeenAt = if (continuing) latest!!.firstSeenAt else now
        val stableDays = AnchorUpdatePolicy.stableDays(firstSeenAt, now, zone)

        val action = AnchorUpdatePolicy.decide(candidate.offsetMeters, stableDays)
        val status = AnchorUpdatePolicy.statusOf(action)
        val explanation = AnchorUpdatePolicy.explain(action, candidate.offsetMeters, stableDays)
        val autoApplied = action == AnchorUpdateAction.AUTO_SMOOTH

        val modelVersion = if (autoApplied) {
            openNewVersion(candidate.sampleCount)
        } else {
            existing?.modelVersion ?: 0L
        }

        // 候选表：同一候选（几何连续）且状态没变 → 只续期，长期运行也不会把表撑爆
        if (continuing && latest!!.status == status.name) {
            dao.updateCandidateStatus(latest.id, status.name, explanation, modelVersion, now)
        } else {
            dao.insertCandidate(
                PlaceAnchorCandidateEntity(
                    placeId = site.id,
                    centerLat = candidate.center.latitude,
                    centerLng = candidate.center.longitude,
                    sampleCount = candidate.sampleCount,
                    distinctDayCount = candidate.distinctDayCount,
                    ambientSourceCount = ambientSources,
                    stableMillis = candidate.stableMillis,
                    firstSeenAt = firstSeenAt,
                    lastSeenAt = now,
                    offsetMeters = candidate.offsetMeters,
                    status = status.name,
                    modelVersion = modelVersion,
                    explanation = explanation,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        // 只有 AUTO_SMOOTH 才让学习锚点生效；其余状态一律 autoApplied = false（永不参与判定）
        val learned = if (autoApplied) {
            AnchorUpdatePolicy.smooth(
                old = existing?.learnedAnchor() ?: configured,
                candidate = candidate.center
            )
        } else {
            candidate.center
        }
        dao.upsertPlace(
            LearnedPlaceModelEntity(
                placeId = site.id,
                placeType = site.siteType,
                // 配置锚点快照每次刷新，便于算偏移与回滚
                configuredLat = configured.latitude,
                configuredLng = configured.longitude,
                learnedLat = learned.latitude,
                learnedLng = learned.longitude,
                coreRadiusMeters = existing?.coreRadiusMeters ?: site.radiusMeters.toDouble(),
                transitionRadiusMeters = existing?.transitionRadiusMeters
                    ?: (site.radiusMeters * TRANSITION_RADIUS_FACTOR),
                anchorConfidence = candidate.confidence,
                fingerprintConfidence = existing?.fingerprintConfidence ?: 0.0,
                modelVersion = modelVersion,
                autoApplied = autoApplied,
                updatedAt = now
            )
        )

        return Outcome(
            placeId = site.id,
            placeName = site.name,
            action = action,
            reason = null,
            explanation = explanation,
            candidate = candidate
        )
    }

    /**
     * 开一个新版本并把旧版本退役（**不删行**）。
     *
     * 回滚 = 把新版本置 `RETIRED`、目标版本置 `ACTIVE`；所以每一版都必须留下
     * 「基于多少样本、训到什么口径」的痕迹，否则回滚时认不出哪版是好的。
     */
    private suspend fun openNewVersion(sampleCount: Int): Long {
        val dao = db.learningModelDao()
        val type = LearningModelType.PLACE_ANCHOR.name
        val now = System.currentTimeMillis()
        val next = dao.maxVersion(type) + 1
        dao.retireOtherVersions(
            modelType = type,
            keepVersion = next,
            retiredStatus = ModelStatus.RETIRED.name,
            now = now
        )
        dao.upsertMeta(
            LearningModelMetaEntity(
                modelType = type,
                modelVersion = next,
                trainedThrough = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString(),
                sampleCount = sampleCount,
                status = ModelStatus.ACTIVE.name,
                createdAt = now,
                note = "地点锚点自动平滑（偏移在 ${AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS.toInt()} 米内）"
            )
        )
        return next
    }

    /**
     * 支持该地点的环境来源类数。
     *
     * 只有到 `STABLE` 档的指纹才算数 —— 与融合引擎「≥2 类来源才算环境确认」
     * 是同一个意思，两处口径将来要改必须一起改。
     */
    private fun ambientSourceCount(
        siteType: String,
        fingerprints: List<EnvironmentFingerprintEntity>
    ): Int {
        val place = if (siteType == SiteEntity.TYPE_NON_WORK) PLACE_HOME else PLACE_COMPANY
        return fingerprints
            .filter { it.place == place && it.level == STABLE_FINGERPRINT_LEVEL }
            .map { it.source }
            .distinct()
            .size
    }

    private fun LearnedPlaceModelEntity.learnedAnchor(): GeoPoint? =
        if (hasLearned) GeoPoint(learnedLat!!, learnedLng!!) else null

    private fun log(message: String) = AppLogEntity(type = LOG_TYPE, content = message)

    private companion object {
        /** 训练窗口：30 天足够跨 ≥5 天门槛，又不至于把整张表读进内存。 */
        const val LOOKBACK_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** 候选延续判定：圆心挪动小于 10 米视为同一个候选。 */
        const val CANDIDATE_DEDUP_METERS = 10.0

        /** 只有 STABLE 档的指纹才计入「环境来源类数」。 */
        const val STABLE_FINGERPRINT_LEVEL = "STABLE"

        const val PLACE_COMPANY = "COMPANY"
        const val PLACE_HOME = "HOME"

        /**
         * 过渡区 = 站点半径 × 2.5。
         *
         * 比例取自 `LearnedPlaceModelEntity` 自身的出厂默认（核心 100 / 过渡 250），
         * 不另立新比例 —— 这样实体默认值与运行期写入值永远是同一套口径。
         */
        const val TRANSITION_RADIUS_FACTOR = 2.5

        const val LOG_TYPE = "LEARNING"
    }
}
