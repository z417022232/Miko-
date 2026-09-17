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
import com.example.worktimetracker.domain.location.AnchorCandidateStatus
import com.example.worktimetracker.domain.location.AnchorLearner
import com.example.worktimetracker.domain.location.AnchorSampleBuilder
import com.example.worktimetracker.domain.location.AnchorUpdateAction
import com.example.worktimetracker.domain.location.AnchorUpdatePolicy
import com.example.worktimetracker.domain.location.GeoPoint
import com.example.worktimetracker.domain.location.ShadowObservation
import com.example.worktimetracker.domain.location.ShadowValidator
import java.time.LocalDate
import java.time.ZoneId

/**
 * 地点锚点的持续学习（方案 §三.2 / §十一 阶段2 的**闭环**）。
 *
 * ## 闭环形状
 * ```
 * location_logs ─▶ AnchorSampleBuilder ─▶ AnchorLearner ─▶ ShadowValidator + AnchorUpdatePolicy
 *                                                              │
 *                                        ┌─────────────────────┴─────────────────────┐
 *                                   AUTO_SMOOTH                          SHADOW / NEEDS_USER_CONFIRM
 *                                        │                                           │
 *                     learned_place_models(autoApplied=true)         learned_place_models(autoApplied=false)
 *                                        │                                           │
 *                                        ▼                                    （记录但不生效）
 *                     SitePoints.withLearnedAnchors() ─▶ 融合判定
 * ```
 * `place_anchor_candidates` 两个分支都写：它是**观测日志**，不是判定输入。
 *
 * ## 影子窗口 = 一组共享 `firstSeenAt` 的候选行
 *
 * 候选表自 DB v15 起是「一个影子窗口**每天一行**」：
 * 同日重复学习原地刷新；跨天且候选没变则插入新行并沿用同一个 `firstSeenAt`；
 * 候选移动 >[CANDIDATE_DEDUP_METERS] 米或状态变化则 `firstSeenAt = now`（**重开窗口**）。
 *
 * 于是 [ShadowValidator] 的六个条件全部**可以从这组行重建**，
 * 不需要任何额外的累积状态 —— 这是「模型必须能从原始数据全量重建」的落法。
 *
 * ## 四条纪律（违反即回归）
 *  1. **只写三张学习表**。`sites` 的坐标一个字都不改 —— 用户配置锚点永久权威，
 *     学习锚点的优先级由**取用顺序**（[com.example.worktimetracker.domain.location.PlaceModelResolver]）体现；
 *     自 DB v16 起**还要再让开** `place_learning_preferences`：用户停用后学习照常跑，
 *     但这个方法**绝不读写那张表** —— 粘性停用是取用层的事，
 *     学习层一旦也去参考它，就会出现「停用期间模型停止演化」这种把两层搅在一起的行为；
 *  2. **判定不读候选表**。影子验证的语义就靠这条保证；
 *  3. **学习不许把主链路带崩**。所有异常吞掉并记 `LEARNING` 日志 ——
 *     学习是增强层，定位服务才是主链路（异常只能「记日志 / 模型降级 / 回落既有算法」）；
 *  4. **数字全部来自观测**。候选行里的样本数、跨天数、环境来源类数、离散度一律实测，
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
        val candidate: AnchorLearner.Candidate? = null,
        /** 影子验证快照；候选未形成时为 null */
        val shadow: com.example.worktimetracker.domain.location.ShadowValidation? = null,
        /** 影子验证还差什么（人话）；已通过则为空 */
        val shadowFailures: List<String> = emptyList()
    )

    private val analyzer = LocationStatusAnalyzer()

    /**
     * 对所有启用且有坐标的地点跑一轮学习。
     *
     * 幂等：同一份历史跑两遍，第二遍走「同日原地刷新」分支，
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

        // 旧候选行长期积压：一个窗口最多一行/天，留 180 天足够回放
        runCatching { db.learningModelDao().deleteCandidatesBefore(now - CANDIDATE_RETENTION_MILLIS) }

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
                persist(site, center, result.candidate, ambientSources, fingerprints, now)
        }
    }

    /** 候选达标 → 影子验证 → 定档 → 落候选行 +（通过才）升级模型。 */
    private suspend fun persist(
        site: SiteEntity,
        configured: GeoPoint,
        candidate: AnchorLearner.Candidate,
        ambientSources: Int,
        fingerprints: List<EnvironmentFingerprintEntity>,
        now: Long
    ): Outcome {
        val dao = db.learningModelDao()
        val existing = dao.place(site.id)
        val latest = dao.latestCandidate(site.id)
        val today = dayOf(now)

        // 「几何上是不是同一个候选」只能用几何判 —— 状态本身依赖影子验证结果，用它判会成环。
        // 等号归**同候选**侧（`<=`），与漂移门槛「≤10 允许、>10 失败」同口径：
        // 正好 10 米既不重开窗口、也不判漂移超限，两处不会对同一个输入给出相反结论。
        val sameCandidate = latest != null && analyzer.distanceMeters(
            latest.centerLat, latest.centerLng,
            candidate.center.latitude, candidate.center.longitude
        ) <= CANDIDATE_DEDUP_METERS

        // 窗口身份：候选连续 → 沿用窗口；否则开新窗口（firstSeenAt = now）
        val windowStart = if (sameCandidate) latest!!.firstSeenAt else now
        val windowRows = if (sameCandidate) dao.candidatesInWindow(site.id, windowStart) else emptyList()
        val observations = buildObservations(windowRows, today, candidate, ambientSources)
        val conflictCount = fingerprintConflicts(fingerprints, windowStart)
        val shadow = ShadowValidator.evaluate(observations, today, conflictCount)

        val action = AnchorUpdatePolicy.decide(candidate.offsetMeters, shadow)
        val status = AnchorUpdatePolicy.statusOf(action)
        val explanation = AnchorUpdatePolicy.explain(action, candidate.offsetMeters, shadow)
        val wasApplied = existing?.autoApplied == true
        val autoApplied = action == AnchorUpdateAction.AUTO_SMOOTH

        // 已经生效过就不再重复升版本：否则每次启动都会 +1，版本号会被噪声淹掉。
        // 反之，一旦验证不再通过（例如候选漂移导致重开窗口），autoApplied 落回 false ——
        // 学习锚点立刻停用、回落用户配置锚点，等重新验证通过再启用。
        val modelVersion = when {
            autoApplied && wasApplied -> existing!!.modelVersion
            autoApplied -> openNewVersion(candidate.sampleCount)
            else -> existing?.modelVersion ?: 0L
        }

        // 同窗口同一天 → 原地刷新；否则插入新行（沿用窗口身份，或开新窗口）
        val sameDay = sameCandidate && latest!!.lastSeenAt.let { dayOf(it) } == today
        if (sameDay) {
            dao.updateCandidateObservation(
                id = latest.id,
                sampleCount = candidate.sampleCount,
                distinctDayCount = candidate.distinctDayCount,
                ambientSourceCount = ambientSources,
                stableMillis = candidate.stableMillis,
                offsetMeters = candidate.offsetMeters,
                spreadP90Meters = candidate.spreadP90Meters,
                status = status.name,
                modelVersion = modelVersion,
                explanation = explanation,
                now = now
            )
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
                    firstSeenAt = windowStart,
                    lastSeenAt = now,
                    offsetMeters = candidate.offsetMeters,
                    status = status.name,
                    spreadP90Meters = candidate.spreadP90Meters,
                    modelVersion = modelVersion,
                    explanation = explanation,
                    createdAt = now,
                    updatedAt = now
                )
            )
        }

        // 只有影子验证通过才让学习锚点生效；其余状态一律 autoApplied = false（永不参与判定）
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
            candidate = candidate,
            shadow = shadow.validation,
            shadowFailures = shadow.failures
        )
    }

    /**
     * 把窗口内的候选行还原成「一天一条」的影子观测，再把**本轮**的读数覆盖进去。
     *
     * 重建规则（同一天取最后一条）由 [ShadowWindow] 唯一持有 —— 展示侧用的是同一份实现，
     * 所以页面上看到的「前向验证 N 天」与这里判定的天数不可能不一致。
     *
     * 本轮的读数一定比库里的新，所以按「同一天去重 + 追加今天」处理。
     */
    private fun buildObservations(
        windowRows: List<PlaceAnchorCandidateEntity>,
        today: LocalDate,
        candidate: AnchorLearner.Candidate,
        ambientSources: Int
    ): List<ShadowObservation> {
        val fromDb = ShadowWindow.observations(windowRows, zone)
            .filter { it.day != today }
        val current = ShadowObservation(
            day = today,
            center = candidate.center,
            ambientSources = ambientSources,
            spreadP90Meters = candidate.spreadP90Meters
        )
        return fromDb + current
    }

    /**
     * 影子窗口内的**指纹冲突**次数（判据说明见 [ShadowWindow.conflicts]）。
     */
    private fun fingerprintConflicts(
        fingerprints: List<EnvironmentFingerprintEntity>,
        windowStart: Long
    ): Int = ShadowWindow.conflicts(fingerprints, windowStart)

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
                trainedThrough = dayOf(now).toString(),
                sampleCount = sampleCount,
                status = ModelStatus.ACTIVE.name,
                createdAt = now,
                note = "地点锚点自动平滑（影子验证六条件通过，偏移在 " +
                    "${AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS.toInt()} 米内）"
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

    private fun dayOf(millis: Long): LocalDate = ShadowWindow.dayOf(millis, zone)

    private fun log(message: String) = AppLogEntity(type = LOG_TYPE, content = message)

    private companion object {
        /** 训练窗口：30 天足够跨 ≥5 天门槛，又不至于把整张表读进内存。 */
        const val LOOKBACK_MILLIS = 30L * 24 * 60 * 60 * 1000

        /**
         * 「同一个候选」的几何容差（米）。**同时是影子验证的漂移门槛**
         * （[ShadowValidator.MAX_CENTER_DRIFT_METERS]），两处等号都归**允许/同候选**侧：
         * `<=` 同候选且漂移合格、`>` 才重开窗口。所以「候选中心漂移 >10 米」表现为
         * **重开影子窗口**而不是判失败 —— 候选一旦移动，旧的验证成果作废、必须重新观察 7 天。
         * 这比「判失败」更严，也刻意更严。
         * **两个值必须一起改**，否则会出现「结构上不可能失败的条件」。
         */
        const val CANDIDATE_DEDUP_METERS = ShadowValidator.MAX_CENTER_DRIFT_METERS

        /** 候选行保留期：一个窗口最多一行/天，180 天足够回放且不至于撑爆库。 */
        const val CANDIDATE_RETENTION_MILLIS = 180L * 24 * 60 * 60 * 1000

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
