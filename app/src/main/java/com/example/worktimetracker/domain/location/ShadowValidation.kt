package com.example.worktimetracker.domain.location

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 影子验证的**快照**（方案 §三.2「影子运行验证」的强化形式）。
 *
 * ## 为什么要从「等 7 天」升级成「6 个条件」
 *
 * 只看 `elapsedDays` 的话，影子期本质上只是**等待**：一个已经漂了 200 米的候选，
 * 熬够 7 天照样会写进 `learned_place_models`。而影子期的**唯一目的**是回答
 * 「在不参与判定的情况下，这个候选**是否继续**稳定」—— 那它就必须能观测到
 * 「不稳定」长什么样：漂移、断档、环境支持变弱、指纹冲突、离散度恶化。
 *
 * ## 与「训练」的分工（关键，别混）
 *
 * | 数据 | 承担什么 | 判据 |
 * |---|---|---|
 * | 历史 30 天轨迹 | **训练证明**：这个候选中心值得形成 | `AnchorLearner` 的五道门槛 |
 * | 未来 7 天影子 | **上线验证**：候选是否继续稳定 | 本类 |
 *
 * ⚠️ 二者**绝不能用同一批数据**。用历史 `distinctDayCount` 抵扣影子期天数，
 * 就是「同一批数据既训练又验证」—— 验证集被训练集污染，验证等于没做。
 * 所以影子期天数必须是**候选形成之后前向累计**的。
 *
 * ## 语义
 * - [elapsedDays]：从影子窗口起始自然日到今天跨了几个自然日（**按自然日，不按满 24 小时**）。
 * - [observedDays]：窗口内**有有效样本**的自然日个数。
 *   `observedDays >= elapsedDays + 1` ⟺ 窗口内**每一天都有**有效样本（含起始日与今天）。
 * - [maxCenterDriftMeters]：窗口内候选中心相对**首个**中心的偏移最大值。
 * - [minimumAmbientSources]：窗口内环境来源类数的**最小值**（不是最后一次的值 —— 中途掉到 1 类就该被发现）。
 * - [conflictCount]：窗口内出现的「同一环境指纹同时支持另一个地点」的次数。
 * - [latestSpreadP90Meters]：最近一次 P90 离散度。
 */
data class ShadowValidation(
    val elapsedDays: Int,
    val observedDays: Int,
    val maxCenterDriftMeters: Double,
    val minimumAmbientSources: Int,
    val conflictCount: Int,
    val latestSpreadP90Meters: Double
)

/**
 * 影子窗口里**每天**的一条观测（一天一条，由 `AnchorLearningService` 落库保证）。
 *
 * `spreadP90Meters` 为 null = 该天没记到离散度（DB v15 之前写入的行）——
 * **不许当 0 处理**：0 是「完美集中」，未知不是。
 */
data class ShadowObservation(
    val day: LocalDate,
    val center: GeoPoint,
    val ambientSources: Int,
    val spreadP90Meters: Double?
)

/**
 * 影子验证的判定器（纯函数，可单测）。
 *
 * 门槛刻意写得**比 `AnchorLearner` 更严**：训练门槛回答「值不值得形成候选」，
 * 这里回答「敢不敢让它改判定」。后者错了的代价是「位置永远飘」，
 * 前者错了的代价只是「白学一次」，不对称，所以宁可多拦。
 */
object ShadowValidator {

    /** 最少前向观察自然日数（含起始日与今天，所以 `elapsedDays >= 7` 相当于跨越 8 个自然日）。 */
    const val MIN_ELAPSED_DAYS = AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS

    /** 候选中心最大漂移（米）。超过它说明这个锚点**位移了**，不是校准误差。 */
    const val MAX_CENTER_DRIFT_METERS = 10.0

    /** 环境来源类数下限，与训练门槛同源（≥2 类）。 */
    const val MIN_AMBIENT_SOURCES = AnchorLearner.MIN_AMBIENT_SOURCES

    /** 允许的指纹冲突次数。0 = 窗口内一次都不许出现。 */
    const val MAX_CONFLICTS = 0

    /**
     * 离散度恶化上限倍数。
     *
     * ⚠️ **这个系数是本实现取的默认值，方案原文没给数**（原文只说「P90 离散度没有明显恶化」）。
     * 取 1.5 的理由：`AnchorLearner` 的绝对上限是 150 米，而候选形成时 P90 通常只有几十米，
     * 1.5 倍能在「正常的采样噪声」与「地点本身变模糊了」之间留出余量。
     * 真机跑够 7 天后应按实测分布回调；**改这里必须同步改 `ShadowValidatorTest` 的护栏**。
     */
    const val MAX_SPREAD_GROWTH_RATIO = 1.5

    /**
     * 绝对离散度容差（米）：`firstP90` 很小时倍数门槛会过分敏感
     * （窗口初 1 米、后来 3 米＝「涨了 3 倍」但完全无害），所以给一个绝对下限兜住。
     *
     * 取 15 米的理由：固定点的 GPS P90 通常在 10 米以内，15 米以内的变化属于采样噪声量级。
     * ⚠️ 与 [MAX_SPREAD_GROWTH_RATIO] 一样，**原文没给数，是本实现的选择**。
     */
    const val MIN_SPREAD_CEILING_METERS = 15.0

    private val analyzer = com.example.worktimetracker.domain.engine.LocationStatusAnalyzer()

    /** 判定结果：[validation] 是可直接落库/展示的快照，[failures] 是**人话**未达标项。 */
    data class Result(val validation: ShadowValidation, val failures: List<String>) {
        val passed: Boolean get() = failures.isEmpty()
    }

    /**
     * 评估一个影子窗口。
     *
     * @param observations 窗口内按日期升序的一天一条观测；**空列表会直接判定不通过**
     * @param today        当前自然日
     * @param conflictCount 窗口内指纹冲突次数（由数据层查出来，判定器不碰数据库）
     */
    fun evaluate(
        observations: List<ShadowObservation>,
        today: LocalDate,
        conflictCount: Int
    ): Result {
        if (observations.isEmpty()) {
            return Result(
                validation = ShadowValidation(
                    elapsedDays = 0,
                    observedDays = 0,
                    maxCenterDriftMeters = 0.0,
                    minimumAmbientSources = 0,
                    conflictCount = conflictCount,
                    latestSpreadP90Meters = 0.0
                ),
                failures = listOf("影子窗口内还没有任何有效观测")
            )
        }

        val startDay = observations.first().day
        val elapsedDays = ChronoUnit.DAYS.between(startDay, today).coerceAtLeast(0).toInt()
        val observedDays = observations.map { it.day }.distinct().size
        val firstCenter = observations.first().center
        val maxDrift = observations.maxOf { analyzer.distanceMeters(firstCenter.latitude, firstCenter.longitude, it.center.latitude, it.center.longitude) }
        val minAmbient = observations.minOf { it.ambientSources }
        val firstP90 = observations.firstNotNullOfOrNull { it.spreadP90Meters }
        val latestP90 = observations.lastOrNull { it.spreadP90Meters != null }?.spreadP90Meters

        val validation = ShadowValidation(
            elapsedDays = elapsedDays,
            observedDays = observedDays,
            maxCenterDriftMeters = maxDrift,
            minimumAmbientSources = minAmbient,
            conflictCount = conflictCount,
            latestSpreadP90Meters = latestP90 ?: 0.0
        )

        val failures = buildList {
            if (elapsedDays < MIN_ELAPSED_DAYS) {
                add("前向观察 ${elapsedDays}/${MIN_ELAPSED_DAYS} 天")
            }
            // 窗口内每一天（含起始日与今天）都必须有有效样本
            if (observedDays < elapsedDays + 1) {
                add("有 ${elapsedDays + 1 - observedDays} 天没采到有效样本")
            }
            if (maxDrift >= MAX_CENTER_DRIFT_METERS) {
                add("候选中心漂移 ${maxDrift.toInt()} 米，超过 ${MAX_CENTER_DRIFT_METERS.toInt()} 米")
            }
            if (minAmbient < MIN_AMBIENT_SOURCES) {
                add("环境来源一度只剩 $minAmbient 类，低于 $MIN_AMBIENT_SOURCES 类")
            }
            if (conflictCount > MAX_CONFLICTS) {
                add("出现 $conflictCount 次地点指纹冲突")
            }
            if (latestP90 == null) {
                add("缺少离散度读数，无法确认是否恶化")
            } else {
                val ceiling = if (firstP90 == null) {
                    AnchorLearner.MAX_SPREAD_P90_METERS
                } else {
                    maxOf(firstP90 * MAX_SPREAD_GROWTH_RATIO, MIN_SPREAD_CEILING_METERS)
                }
                if (latestP90 > ceiling) {
                    add(
                        "离散度 P90 ${latestP90.toInt()} 米，较窗口初的 " +
                            "${(firstP90 ?: 0.0).toInt()} 米明显恶化（上限 ${ceiling.toInt()} 米）"
                    )
                }
            }
        }

        return Result(validation, failures)
    }

    /**
     * 把快照翻译成一句给用户看的话（原则：每次判定必须能解释依据）。
     * 通过时给出「已连续稳定 N 天」，不通过时列出**还差什么**。
     */
    fun explain(result: Result, offsetMeters: Double): String {
        val v = result.validation
        if (result.passed) {
            return "已前向观察 ${v.elapsedDays} 天且始终稳定，与设置位置相差 ${offsetMeters.toInt()} 米，自动小幅校准"
        }
        return "与设置位置相差 ${offsetMeters.toInt()} 米，" +
            "影子验证中（${v.observedDays} 天有样本 / 漂移 ${v.maxCenterDriftMeters.toInt()} 米）：" +
            result.failures.joinToString("；")
    }
}
