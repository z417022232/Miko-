package com.example.worktimetracker.domain.evidence

/**
 * 多源定位证据融合引擎（纯 Kotlin，无 Android 依赖）。
 *
 * 优先级规则：
 * 1. 过滤未来时间、GNSS 超过 2 分钟、环境证据超过 10 分钟的观察。
 * 2. 质量至少 0.80 的 GNSS 直接优先。
 * 3. 无 GNSS 时，相同地点至少两类 CELL/WIFI/BLUETOOTH 且质量和不低于 1.40 才返回地点；
 *    每个来源只取最新一条有效观察，重复扫描不累加质量。
 * 4. 只有一类环境来源时保持 UNKNOWN，除非同时存在 MOTION 且总质量不低于 1.80。
 * 5. 家和公司得分差小于 0.15 时返回 UNKNOWN；否则返回得分更高的一方。
 */
class EvidenceFusionEngine {

    fun resolve(
        observations: List<EvidenceObservation>,
        now: Long,
        previous: ResolvedPlace
    ): FusedEvidence {
        val fresh = observations.filter { it.isFresh(now) }

        val gnss = fresh.filter { it.source == EvidenceSource.GNSS && it.quality >= GNSS_RELIABLE_QUALITY }
            .maxByOrNull { it.quality }
        if (gnss != null) {
            return FusedEvidence(
                place = gnss.placeHint,
                confidence = gnss.quality,
                firstReliableAt = gnss.eventTime,
                sources = setOf(EvidenceSource.GNSS)
            )
        }

        val home = scoreFor(fresh, ResolvedPlace.HOME)
        val company = scoreFor(fresh, ResolvedPlace.COMPANY)
        val homeSupported = home != null
        val companySupported = company != null

        if (homeSupported && companySupported &&
            kotlin.math.abs(home.score - company.score) < PLACE_SCORE_MIN_GAP
        ) {
            return unknown()
        }

        val winner = when {
            homeSupported && companySupported ->
                // 冲突检查通过（分差足够大）后必须选最高分，而不是固定优先某地点
                if (company.score > home.score) company else home
            homeSupported -> home
            companySupported -> company
            else -> null
        } ?: return unknown()

        return FusedEvidence(
            place = winner.place,
            confidence = winner.confidence.coerceAtMost(1.0),
            firstReliableAt = winner.observations.minOf { it.eventTime },
            sources = winner.observations.map { it.source }.toSet()
        )
    }

    private fun unknown() = FusedEvidence(ResolvedPlace.UNKNOWN, 0.0, null, emptySet())

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
        val motion = fresh.filter { it.source == EvidenceSource.MOTION && it.placeHint == place }
            .maxByOrNull { it.eventTime }

        val supported = when {
            classes.size >= 2 && envSum >= AMBIENT_CONFIRM_SUM -> true
            classes.size == 1 && motion != null && envSum + motion.quality >= SINGLE_SOURCE_WITH_MOTION_SUM -> true
            else -> false
        }
        if (!supported) return null

        val supporting = if (motion != null) latestPerSource + motion else latestPerSource
        return PlaceScore(
            place = place,
            score = envSum,
            confidence = supporting.sumOf { it.quality } / supporting.size,
            observations = supporting
        )
    }

    private fun EvidenceObservation.isFresh(now: Long): Boolean {
        if (eventTime > now) return false
        val age = now - eventTime
        return if (source == EvidenceSource.GNSS) age <= GNSS_MAX_AGE_MILLIS else age <= AMBIENT_MAX_AGE_MILLIS
    }

    private data class PlaceScore(
        val place: ResolvedPlace,
        val score: Double,
        val confidence: Double,
        val observations: List<EvidenceObservation>
    )

    companion object {
        const val GNSS_RELIABLE_QUALITY = 0.80
        const val GNSS_MAX_AGE_MILLIS = 2 * 60_000L
        const val AMBIENT_MAX_AGE_MILLIS = 10 * 60_000L
        const val AMBIENT_CONFIRM_SUM = 1.40
        const val SINGLE_SOURCE_WITH_MOTION_SUM = 1.80
        const val PLACE_SCORE_MIN_GAP = 0.15
        private val AMBIENT_SOURCES = setOf(EvidenceSource.CELL, EvidenceSource.WIFI, EvidenceSource.BLUETOOTH)
    }
}
