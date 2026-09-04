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

        val home = scoreFor(fresh, ResolvedPlace.HOME)
        val company = scoreFor(fresh, ResolvedPlace.COMPANY)
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
                    confidence = weakHints.maxOf { it.quality },
                    firstReliableAt = weakHints.minOf { it.eventTime },
                    sources = weakHints.map { it.source }.toSet(),
                    decision = FusedDecision.MAINTAINED,
                    reason = "MAINTAIN_WEAK_EVIDENCE"
                )
            } else {
                unknown(FusedReason.LOW_CONFIDENCE)
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

    private fun unknown(reason: FusedReason, detail: String = "") = FusedEvidence(
        ResolvedPlace.UNKNOWN, 0.0, null, emptySet(),
        // 统一 UNKNOWN_ 前缀（UNKNOWN_NO_DATA/UNKNOWN_STALE/...）：协调器连续性维持
        // 与诊断日志都依赖该前缀区分「无证据/陈旧」与「证据冲突/低置信」
        FusedDecision.UNKNOWN,
        "UNKNOWN_" + reason.name + if (detail.isEmpty()) "" else ":$detail"
    )

    private fun scoreFor(fresh: List<EvidenceObservation>, place: ResolvedPlace): PlaceScore? {
        val envObservations = fresh.filter {
            it.source in AMBIENT_SOURCES && it.placeHint == place
        }
        if (envObservations.isEmpty()) return null
        // 每个来源只保留最新一条有效观察：重复扫描不能累加质量，
        // 扫描次数不能替代证据质量（低质量来源重复采样不应凑成确认）
        val latestPerSource = envObservations.groupBy { it.source }
            .map { (_, sameSource) -> sameSource.maxByOrNull { it.eventTime }!! }
        val classes = latestPerSource.map { it.source }.toSet()
        val envSum = latestPerSource.sumOf { it.quality }

        val supported = classes.size >= 2 && envSum >= AMBIENT_CONFIRM_SUM
        if (!supported) return null

        return PlaceScore(
            place = place,
            score = envSum,
            confidence = envSum / classes.size,
            observations = latestPerSource
        )
    }

    private fun EvidenceObservation.isFresh(now: Long): Boolean {
        if (eventTime > now) return false
        val age = now - eventTime
        val maxAge = when (source) {
            EvidenceSource.GNSS, EvidenceSource.NETWORK_LOCATION -> GNSS_MAX_AGE_MILLIS
            EvidenceSource.WIFI -> WIFI_MAX_AGE_MILLIS
            EvidenceSource.BLUETOOTH -> BLUETOOTH_MAX_AGE_MILLIS
            EvidenceSource.CELL -> CELL_MAX_AGE_MILLIS
            else -> return false // MOTION/SHIFT_WINDOW 不作为地点证据
        }
        return age <= maxAge
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
        private val AMBIENT_SOURCES = setOf(EvidenceSource.CELL, EvidenceSource.WIFI, EvidenceSource.BLUETOOTH)
    }
}
