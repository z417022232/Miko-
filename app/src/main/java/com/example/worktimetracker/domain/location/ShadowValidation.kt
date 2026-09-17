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
 * - [latestSpreadP90Meters]：最近一次 P90 离散度；**null = 窗口内没有任何离散度读数（未知）**。
 *
 * ⚠️ [latestSpreadP90Meters] 是 `Double?` 而不是 `Double`：v15 之前写入的候选行没有这个读数，
 * 而 `0.0` 的含义是「完美集中」—— 两者是**完全相反**的两件事。
 * 不能用 `?: 0.0` 抹平：判定逻辑确实会因 null 而失败（不会误放行），
 * 但**输出快照**一旦把"未知"写成"0 米"，后续读这个快照的人（学习成果页、日志、
 * 跨模型诊断）就会得到错误语义。判定正确不能成为输出层丢语义的理由。
 */
data class ShadowValidation(
    val elapsedDays: Int,
    val observedDays: Int,
    val maxCenterDriftMeters: Double,
    val minimumAmbientSources: Int,
    val conflictCount: Int,
    val latestSpreadP90Meters: Double?
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

    /**
     * 候选中心漂移门槛（米）。**口径：≤ 该值允许、> 该值失败**（等号归**允许**侧）。
     *
     * 为什么等号归允许侧：本项目所有门槛都是「上限含等号」这一套 ——
     * 精度 ≤ 30m、自动档偏移 ≤ 30m、影子档偏移 ≤ 100m、环境来源 ≥ 2 类。
     * 只让这一条「到 10 米就失败」，会变成唯一一个上限不含等号的阈值，
     * 以后改的人无法从其他门槛推断出该写 `>` 还是 `>=`。
     *
     * 判定一律走 [driftExceedsLimit]，不要在别处重写这个比较 —— 见该函数的说明。
     */
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
     * 漂移是否**超出**允许范围。
     *
     * 单独抽出来的理由：边界（正好等于门槛）没法用几何夹具测 ——
     * `distanceMeters` 的浮点往返会让「正好 10 米」落在 10.0±1e-13，
     * 于是「等号归哪一侧」在测试里既不可控也不可断言。
     * 做成一个吃 `Double` 的纯函数后，`driftExceedsLimit(10.0)` 就是**精确**可测的，
     * 门槛的等号归属从此由测试钉住，而不是靠读代码时数符号。
     *
     * 另一个理由：门槛只允许有一个比较处。散落的 `>=`/`>` 迟早会漂移成两种口径，
     * 而这种漂移不报错、不崩溃，只会让某一侧静默放宽。
     */
    fun driftExceedsLimit(driftMeters: Double): Boolean =
        driftMeters > MAX_CENTER_DRIFT_METERS

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
                    // 空窗口**没有任何离散度读数** —— 是未知，不是「0 米完美集中」
                    latestSpreadP90Meters = null
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
            // 原样透出可空读数：null（未知）不许在这里被压成 0.0（完美集中）
            latestSpreadP90Meters = latestP90
        )

        val failures = buildList {
            if (elapsedDays < MIN_ELAPSED_DAYS) {
                add("前向观察 ${elapsedDays}/${MIN_ELAPSED_DAYS} 天")
            }
            // 窗口内每一天（含起始日与今天）都必须有有效样本
            if (observedDays < elapsedDays + 1) {
                add("有 ${elapsedDays + 1 - observedDays} 天没采到有效样本")
            }
            if (driftExceedsLimit(maxDrift)) {
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
