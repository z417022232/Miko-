package com.example.worktimetracker.domain.evidence

/**
 * 多源定位证据融合引擎（纯 Kotlin，无 Android 依赖）。
 *
 * 决策分层（证据分为「可以改变状态」和「只能维持状态」两档）：
 * 1. 过滤未来时间与陈旧观察，有效期按来源区分：
 *    GNSS/NetworkLocation 2 分钟、Wi-Fi 5 分钟、蓝牙 3 分钟、基站 10 分钟。
 * 2. 强证据 → CONFIRMED（可以改变工时状态）：
 *    - 质量至少 0.80 的 GNSS / NetworkLocation 直接确认；
 *    - 相同地点至少两类 CELL/WIFI/BLUETOOTH（每类取最新一条）且质量和不低于 1.40。
 * 3. 弱证据 → 只能维持（MAINTAINED），不能导致状态转换：
 *    - 单一环境来源且与上一地点一致时维持上一地点；
 *    - 与上一地点不一致时保持 UNKNOWN（低置信）。
 * 4. 家和公司得分差小于 0.15 时冲突 → UNKNOWN_CONFLICT；否则选最高分。
 * 5. Motion 不再作为地点证据：它只负责唤醒重新取证，由采集层自行处理。
 * 6. 连续性维持（20 分钟窗口内无证据保持上一地点）由协调器结合
 *    EvidenceContinuityPolicy 实现，引擎只负责证据本身的判定。
 * 7. 时间衰减（v8.1）：TTL 之内按年龄**线性**降低「有效质量」，越旧权重越低。
 *    ⚠️ 只作用于同地点内排序、置信度展示与弱证据维持，**不参与确认门槛** ——
 *    「过期即丢」与「两类来源 1.40 才确认」两条既有边界保持逐位不变。
 * 8. 切换余量（v8.1）：两名地点本轮都成立时，新地点必须越过「上一地点得分」的
 *    滞回线才允许翻转地点结论；可靠绝对定位（CONFIRMED_GNSS）不在此列。
 */
class EvidenceFusionEngine {

    fun resolve(
        observations: List<EvidenceObservation>,
        now: Long,
        previous: ResolvedPlace
    ): FusedEvidence {
        if (observations.isEmpty()) {
            return unknown(FusedReason.NO_DATA)
        }
        val fresh = observations.filter { it.isFresh(now) }
        if (fresh.isEmpty()) {
            return unknown(FusedReason.STALE)
        }

        val gnssLike = fresh.filter {
            (it.source == EvidenceSource.GNSS || it.source == EvidenceSource.NETWORK_LOCATION) &&
                it.quality >= GNSS_RELIABLE_QUALITY
        }.maxByOrNull { it.quality }
        if (gnssLike != null) {
            return FusedEvidence(
                place = gnssLike.placeHint,
                confidence = gnssLike.quality,
                firstReliableAt = gnssLike.eventTime,
                sources = setOf(gnssLike.source),
                decision = FusedDecision.CONFIRMED,
                reason = "CONFIRMED_${gnssLike.source.name}"
            )
        }

        val home = scoreFor(fresh, ResolvedPlace.HOME, now)
        val company = scoreFor(fresh, ResolvedPlace.COMPANY, now)
        val homeSupported = home != null
        val companySupported = company != null

        if (homeSupported && companySupported &&
            kotlin.math.abs(home.score - company.score) < PLACE_SCORE_MIN_GAP
        ) {
            return unknown(
                FusedReason.CONFLICT,
                // 保留两位小数：避免 Double 浮点尾数（如 1.5800000000000003）污染诊断日志
                detail = "home=${"%.2f".format(home.score)} company=${"%.2f".format(company.score)}" +
                    " gap<${PLACE_SCORE_MIN_GAP}"
            )
        }

        val winner = when {
            homeSupported && companySupported ->
                // 冲突检查通过（分差足够大）后必须选最高分，而不是固定优先某地点
                if (company.score > home.score) company else home
            homeSupported -> home
            companySupported -> company
            else -> null
        }

        if (winner == null) {
            // 没有任何地点达到强证据门槛：弱证据只能维持，不能改变状态
            val weakHints = fresh.filter { it.source in AMBIENT_SOURCES }
            return if (previous != ResolvedPlace.UNKNOWN &&
                weakHints.isNotEmpty() && weakHints.all { it.placeHint == previous }
            ) {
                FusedEvidence(
                    place = previous,
                    // 弱证据维持的置信度同样按年龄衰减：4 分钟前的环境读数不该和刚扫到的一样可信
                    confidence = weakHints.maxOf {
                        effectiveQuality(it.quality, it.eventTime, now, it.source)
                    },
                    firstReliableAt = weakHints.minOf { it.eventTime },
                    sources = weakHints.map { it.source }.toSet(),
                    decision = FusedDecision.MAINTAINED,
                    reason = "MAINTAIN_WEAK_EVIDENCE"
                )
            } else {
                unknown(FusedReason.LOW_CONFIDENCE)
            }
        }

        // 切换余量：上一地点本轮仍然成立时，新地点必须越过滞回线才允许翻转。
        // 两名地点都成立、分差只比冲突线高一点点时立刻翻转，等于让地点结论与 UI 来回抖动；
        // 状态机对「到岗/离岗」另有候选计数，融合层不该比它更激进。
        // ⚠️ 只在上一地点**本轮也有支撑**时生效：上一地点毫无证据时不阻拦切换。
        val previousScore = when (previous) {
            ResolvedPlace.HOME -> home
            ResolvedPlace.COMPANY -> company
            else -> null
        }
        if (previousScore != null && winner.place != previous) {
            val switchThreshold = maxOf(
                previousScore.score + SWITCH_MARGIN,
                previousScore.score * SWITCH_HYSTERESIS_RATIO
            )
            if (winner.score < switchThreshold) {
                return FusedEvidence(
                    place = previous,
                    confidence = previousScore.confidence.coerceAtMost(1.0),
                    firstReliableAt = previousScore.observations.minOf { it.eventTime },
                    sources = previousScore.observations.map { it.source }.toSet(),
                    decision = FusedDecision.MAINTAINED,
                    reason = "MAINTAIN_HELD_PREVIOUS"
                )
            }
        }

        return FusedEvidence(
            place = winner.place,
            confidence = winner.confidence.coerceAtMost(1.0),
            firstReliableAt = winner.observations.minOf { it.eventTime },
            sources = winner.observations.map { it.source }.toSet(),
            decision = FusedDecision.CONFIRMED,
            reason = "CONFIRMED_AMBIENT"
        )
    }

    /**
     * 证据「有效质量」：TTL 之内按年龄**线性**衰减（越旧越低，下限 [DECAY_FLOOR]）。
     *
     * ⚠️ 只用于同地点内排序、置信度展示与弱证据维持，**不参与 [AMBIENT_CONFIRM_SUM]
     * 确认门槛** ——「过期即丢」和「两类来源 1.40 才确认」两条既有边界逐位不变。
     * 未来时间戳（age < 0）与超期证据一律返回 0，与 [isFresh] 口径一致。
     */
    fun effectiveQuality(
        quality: Double,
        eventTime: Long,
        now: Long,
        source: EvidenceSource
    ): Double {
        val maxAge = maxAgeOf(source) ?: return 0.0
        val age = now - eventTime
        if (age < 0 || age > maxAge) return 0.0
        val factor = (1.0 - age.toDouble() / maxAge.toDouble()).coerceAtLeast(DECAY_FLOOR)
        return quality * factor
    }

    private fun unknown(reason: FusedReason, detail: String = "") = FusedEvidence(
        ResolvedPlace.UNKNOWN, 0.0, null, emptySet(),
        // 统一 UNKNOWN_ 前缀（UNKNOWN_NO_DATA/UNKNOWN_STALE/...）：协调器连续性维持
        // 与诊断日志都依赖该前缀区分「无证据/陈旧」与「证据冲突/低置信」
        FusedDecision.UNKNOWN,
        "UNKNOWN_" + reason.name + if (detail.isEmpty()) "" else ":$detail"
    )

    private fun scoreFor(
        fresh: List<EvidenceObservation>,
        place: ResolvedPlace,
        now: Long
    ): PlaceScore? {
        val envObservations = fresh.filter {
            it.source in AMBIENT_SOURCES && it.placeHint == place
        }
        if (envObservations.isEmpty()) return null
        // 每个来源只保留最新一条有效观察：重复扫描不能累加质量，
        // 扫描次数不能替代证据质量（低质量来源重复采样不应凑成确认）
        val latestPerSource = envObservations.groupBy { it.source }
            .map { (_, sameSource) -> sameSource.maxByOrNull { it.eventTime }!! }
        val classes = latestPerSource.map { it.source }.toSet()
        // 确认门槛仍按**原始质量**判定（复查口径：衰减不改门槛）；
        // 年龄只影响后面的排序与置信度，避免一步改动同时挪动门槛与排序两个变量
        val rawSum = latestPerSource.sumOf { it.quality }
        val supported = classes.size >= 2 && rawSum >= AMBIENT_CONFIRM_SUM
        if (!supported) return null

        val effectiveSum = latestPerSource.sumOf {
            effectiveQuality(it.quality, it.eventTime, now, it.source)
        }
        return PlaceScore(
            place = place,
            score = effectiveSum,
            confidence = effectiveSum / classes.size,
            observations = latestPerSource
        )
    }

    /** 各来源的有效期；MOTION/SHIFT_WINDOW 不作为地点证据（返回 null）。 */
    private fun maxAgeOf(source: EvidenceSource): Long? = when (source) {
        EvidenceSource.GNSS, EvidenceSource.NETWORK_LOCATION -> GNSS_MAX_AGE_MILLIS
        EvidenceSource.WIFI -> WIFI_MAX_AGE_MILLIS
        EvidenceSource.BLUETOOTH -> BLUETOOTH_MAX_AGE_MILLIS
        EvidenceSource.CELL -> CELL_MAX_AGE_MILLIS
        else -> null
    }

    private fun EvidenceObservation.isFresh(now: Long): Boolean {
        if (eventTime > now) return false
        val maxAge = maxAgeOf(source) ?: return false
        return now - eventTime <= maxAge
    }

    private data class PlaceScore(
        val place: ResolvedPlace,
        val score: Double,
        val confidence: Double,
        val observations: List<EvidenceObservation>
    )

    /** 决策原因细分：供 UI 与诊断日志区分 UNKNOWN 的具体成因。 */
    enum class FusedReason { NO_DATA, STALE, CONFLICT, LOW_CONFIDENCE }

    companion object {
        const val GNSS_RELIABLE_QUALITY = 0.80
        const val GNSS_MAX_AGE_MILLIS = 2 * 60_000L

        /** 分来源有效期：蓝牙 10 分钟前扫到的设备已很难证明「现在还在那里」 */
        const val WIFI_MAX_AGE_MILLIS = 5 * 60_000L
        const val BLUETOOTH_MAX_AGE_MILLIS = 3 * 60_000L
        const val CELL_MAX_AGE_MILLIS = 10 * 60_000L

        const val AMBIENT_CONFIRM_SUM = 1.40
        const val PLACE_SCORE_MIN_GAP = 0.15

        /**
         * 时间衰减下限：TTL 之内最旧的证据仍保留一半有效质量。
         *
         * 取 0.5 而不是 0 ——「临期证据不太可信」不等于「不可信」，直接把系数压到 0
         * 只是把原来那条「过期即丢」的悬崖原样搬到 TTL 内侧，问题没解决。
         */
        const val DECAY_FLOOR = 0.5

        /**
         * 切换余量（加法项）。与冲突线同源，保证「余量不小于冲突线」这条自洽性。
         *
         * ⚠️ 单独用它**不会生效**：能走到地点比较就说明两名地点分差已经 ≥
         * [PLACE_SCORE_MIN_GAP]，加法项恰好被冲突线吞掉。真正起作用的是
         * [SWITCH_HYSTERESIS_RATIO]；改成更小的值只会更激进，不会更保守。
         */
        const val SWITCH_MARGIN = PLACE_SCORE_MIN_GAP

        /**
         * 切换滞回比例：新地点得分须超过「上一地点本轮得分」的这么多倍才允许翻转。
         *
         * 取 1.25：上一地点得分 1.50 时新地点要 ≥1.875 才换，挡掉「只高一档就来回抖」；
         * 上一地点越高，需要高出的绝对量越大（得分 2.00 时需 ≥2.50），
         * 这与「待得越稳越不该被边缘证据推翻」的直觉一致。
         */
        const val SWITCH_HYSTERESIS_RATIO = 1.25

        private val AMBIENT_SOURCES = setOf(EvidenceSource.CELL, EvidenceSource.WIFI, EvidenceSource.BLUETOOTH)
    }
}
