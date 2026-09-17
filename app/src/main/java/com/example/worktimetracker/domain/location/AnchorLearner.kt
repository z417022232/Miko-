package com.example.worktimetracker.domain.location

import com.example.worktimetracker.domain.engine.LocationStatusAnalyzer
import kotlin.math.max

/**
 * 锚点候选学习器（方案 §三.2）。
 *
 * 输入一批**可靠原始观测**，输出一个候选中心；不达标就明确说缺什么。
 *
 * 与既有 `LocationAnchorCalibration` 的关系：那个是**一次性手动校准**用的
 * （用户在设置页点「校准」，取最近 20 条高精度点，算完让用户确认）。
 * 本类服务的是**持续自动学习**，所以门槛严得多，而且必须能解释「为什么没形成候选」——
 * 手动校准失败用户可以再点一次，自动学习失败却没人知道，只能靠日志和 UI。
 *
 * 门槛（方案原文，一个都不改）：
 * | 条件 | 值 |
 * |---|---|
 * | 单点精度 | ≤ 30 米 |
 * | 有效点数 | ≥ 10 |
 * | 跨自然日 | ≥ 5 天 |
 * | 核心区连续稳定 | ≥ 20 分钟 |
 * | 环境来源类数 | ≥ 2（Wi-Fi/蓝牙/基站） |
 */
class AnchorLearner {

    /** 一条原始观测样本。 */
    data class Sample(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float,
        /** 观测时刻 */
        val time: Long,
        /** 本地自然日 `yyyy-MM-dd`，用于「跨 5 天」判定（按设备时区口径） */
        val localDay: String,
        /** 是否落在该地点核心区域 */
        val inCore: Boolean,
        /** 推算补全的样本不参与学习 */
        val inferred: Boolean = false,
        /** 人工回放的样本不参与学习 */
        val manualReplay: Boolean = false
    )

    /** 学出来的候选中心。 */
    data class Candidate(
        val center: GeoPoint,
        val sampleCount: Int,
        val distinctDayCount: Int,
        val acceptedCount: Int,
        val rejectedCount: Int,
        val stableMillis: Long,
        /** 相对用户配置锚点的偏移（米）；调用方保证传入 configuredAnchor 才可能非空 */
        val offsetMeters: Double,
        /** 0..1 置信度，分档与自动生效判定见 [AnchorUpdatePolicy] */
        val confidence: Double
    )

    /**
     * 学习结论。
     *
     * [Insufficient] 必须带**人话原因**：它会直接决定 UI 上显示
     * 「再在公司待一会儿就能学习」还是「位置好像变了，要不要更新」——
     * 只回一个 null 的话，这个功能对用户就是黑盒（违反原则 6）。
     */
    sealed interface Outcome {
        data class Found(val candidate: Candidate) : Outcome
        data class Insufficient(val reason: Reason, val detail: String) : Outcome
    }

    enum class Reason(val detail: String) {
        NO_SAMPLES("还没有可用的高精度样本"),
        TOO_FEW_POINTS("有效样本不足 $MIN_POINTS 个"),
        TOO_FEW_DAYS("观察天数不足 $MIN_DAYS 天"),
        NOT_STABLE_ENOUGH("核心区连续停留不足 ${MIN_STABLE_MILLIS / 60_000} 分钟"),
        NO_AMBIENT_SUPPORT("缺少环境证据支持（需要 $MIN_AMBIENT_SOURCES 类来源）"),
        SCATTERED("样本过于分散，无法形成稳定中心")
    }

    private val analyzer = LocationStatusAnalyzer()

    fun learn(
        samples: List<Sample>,
        ambientSourceCount: Int,
        /** 由状态机给出的「核心区连续稳定停留」时长（毫秒） */
        stableMillis: Long,
        /** 用户配置锚点；为 null 时以样本中位数当参照（偏移恒为 0） */
        configuredAnchor: GeoPoint? = null
    ): Outcome {
        // 1) 只看可靠原始观测：精度合格、在核心区、非推算、非回放
        val accurate = samples.filter {
            it.inCore && !it.inferred && !it.manualReplay && it.accuracyMeters > 0f &&
                it.accuracyMeters <= MAX_ACCURACY_METERS
        }
        if (samples.isEmpty()) return Outcome.Insufficient(Reason.NO_SAMPLES, Reason.NO_SAMPLES.detail)
        if (accurate.size < MIN_POINTS) {
            return Outcome.Insufficient(
                Reason.TOO_FEW_POINTS,
                "有效样本 ${accurate.size} 个，不足 $MIN_POINTS 个"
            )
        }

        // 2) 跨天与稳定性：这两条是「不是路过」的核心证据，比重房位置更早判
        val distinctDays = accurate.map { it.localDay }.distinct().size
        if (distinctDays < MIN_DAYS) {
            return Outcome.Insufficient(
                Reason.TOO_FEW_DAYS,
                "跨 $distinctDays 天，不足 $MIN_DAYS 天"
            )
        }
        if (stableMillis < MIN_STABLE_MILLIS) {
            return Outcome.Insufficient(
                Reason.NOT_STABLE_ENOUGH,
                "连续稳定 ${stableMillis / 60_000} 分钟，不足 ${MIN_STABLE_MILLIS / 60_000} 分钟"
            )
        }
        if (ambientSourceCount < MIN_AMBIENT_SOURCES) {
            return Outcome.Insufficient(
                Reason.NO_AMBIENT_SUPPORT,
                "环境来源 $ambientSourceCount 类，不足 $MIN_AMBIENT_SOURCES 类"
            )
        }

        // 3) 聚类：中位数中心 → 按距离剔离群 → 重算中位数中心
        //    （与 LocationAnchorCalibration 同一套做法，已被实机验证过，不另造轮子）
        val initial = GeoPoint(median(accurate.map { it.latitude }), median(accurate.map { it.longitude }))
        val distances = accurate.map { distance(it, initial) }
        val threshold = max(OUTLIER_FLOOR_METERS, median(distances) * OUTLIER_MEDIAN_FACTOR)
        val accepted = accurate.filterIndexed { index, _ -> distances[index] <= threshold }
        if (accepted.size < MIN_POINTS) {
            return Outcome.Insufficient(
                Reason.SCATTERED,
                "剔离群后只剩 ${accepted.size} 个样本"
            )
        }
        val center = GeoPoint(median(accepted.map { it.latitude }), median(accepted.map { it.longitude }))

        // 4) 离散度上限：P90 超过上限说明这个"地点"根本不是一个点（例如车间跨了好几栋楼）
        val spread = accepted.map { distance(it, center) }.sorted()
        val p90 = spread[((spread.size - 1) * 0.9).toInt()]
        if (p90 > MAX_SPREAD_P90_METERS) {
            return Outcome.Insufficient(
                Reason.SCATTERED,
                "样本离散度 P90 ${p90.toInt()} 米，超过 ${MAX_SPREAD_P90_METERS.toInt()} 米"
            )
        }

        val reference = configuredAnchor ?: center
        val offset = analyzer.distanceMeters(
            reference.latitude, reference.longitude, center.latitude, center.longitude
        )
        return Outcome.Found(
            Candidate(
                center = center,
                sampleCount = accurate.size,
                distinctDayCount = distinctDays,
                acceptedCount = accepted.size,
                rejectedCount = accurate.size - accepted.size,
                stableMillis = stableMillis,
                offsetMeters = offset,
                confidence = AnchorUpdatePolicy.confidence(
                    sampleCount = accepted.size,
                    distinctDays = distinctDays,
                    ambientSourceCount = ambientSourceCount,
                    offsetMeters = offset
                )
            )
        )
    }

    private fun distance(sample: Sample, point: GeoPoint): Double =
        analyzer.distanceMeters(sample.latitude, sample.longitude, point.latitude, point.longitude)

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return 0.0
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    companion object {
        /** 单点精度门槛：方案 §三.2「精度 ≤ 30 米」。 */
        const val MAX_ACCURACY_METERS = 30f

        /** 有效点数门槛。 */
        const val MIN_POINTS = 10

        /** 跨自然日门槛。 */
        const val MIN_DAYS = 5

        /** 核心区连续稳定停留门槛：20 分钟。 */
        const val MIN_STABLE_MILLIS = 20 * 60_000L

        /** 环境来源类数门槛：Wi-Fi / 蓝牙 / 基站里至少两类。 */
        const val MIN_AMBIENT_SOURCES = 2

        /** 离群阈值下限（米）：样本极集中时也要留出基本容差。 */
        const val OUTLIER_FLOOR_METERS = 75.0

        /** 离群阈值 = 中位距离 × 该系数。 */
        const val OUTLIER_MEDIAN_FACTOR = 3.0

        /** 离散度上限（P90 米）。超过它意味着"地点"本身不是一个点。 */
        const val MAX_SPREAD_P90_METERS = 150.0
    }
}
