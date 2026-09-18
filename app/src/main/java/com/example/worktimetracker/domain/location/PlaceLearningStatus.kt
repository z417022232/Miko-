package com.example.worktimetracker.domain.location

/**
 * 地点学习校准的对外阶段（地点管理页的最小展示模型）。
 *
 * 六个值互斥且**覆盖所有可达状态** —— 包括「算法说可以自动校准、但置信度还没到线」
 * 这种容易被漏掉的中间态（见 [PENDING_APPLY]）。
 */
enum class PlaceLearningPhase(val label: String) {
    /** 还没有影子窗口（样本不足或刚用完新库） */
    NOT_STARTED("尚未开始学习"),

    /** 影子验证未通过：在观察，但绝不参与判定 */
    SHADOW("影子验证中"),

    /**
     * 影子验证已通过、但还不该生效。
     *
     * 为什么状态表里必须有这一格：[AnchorUpdatePolicy] 说「可以自动平滑」，
     * 而 [PlaceModelResolver] 还要再看一眼置信度（≥0.70）。两者**可能不一致** ——
     * 例如观察满 8 天、环境 2 类，但样本数不多时置信度只有 0.64。
     * 那时算法动作是 `AUTO_SMOOTH`、实际却不会被取用。
     * 若不显式表达，界面就会照着算法动作显示「已启用」，而用户看到的判定其实没变 ——
     * 这正是「界面说的和实际做的不一样」，必须显式拆开。
     */
    PENDING_APPLY("验证已通过，尚未生效"),

    /** 学习锚点已生效：判定正在使用它 */
    AUTO_APPLIED("已启用学习校准"),

    /** 偏移超过影子档上限，必须由用户确认（搬家/换公司） */
    NEEDS_CONFIRM("需要你确认新位置"),

    /** 用户停用（粘性），判定回落用户设置 */
    PAUSED("已暂停学习校准")
}

/** 一条「标签 + 值」的展示明细。 */
data class LearningFact(val label: String, val value: String)

/**
 * 一个地点的学习状态（可直接渲染，无需再判断）。
 *
 * [effectiveAnchorSource] 由 [PlaceModelResolver.resolve] 给出，**不是**本类推断的 ——
 * 展示的「当前用的是哪个锚点」必须和实际取用走同一个函数，否则又是一处会说谎的界面。
 */
data class PlaceLearningStatus(
    val placeId: Long,
    val phase: PlaceLearningPhase,
    val headline: String,
    val facts: List<LearningFact>,
    val failures: List<String>,
    val effectiveAnchorSource: EffectiveAnchorSource,
    val canDisable: Boolean,
    val canReEnable: Boolean
) {
    /** 当前判定用的是不是用户设置的位置。 */
    val usesConfiguredAnchor: Boolean
        get() = effectiveAnchorSource == EffectiveAnchorSource.USER_CONFIGURED
}

/**
 * 构造 [PlaceLearningStatus] 的**纯函数**（不碰数据库、不碰 Android）。
 *
 * 把「显示什么」也纳入单测范围，是因为这类文案最容易悄悄出错：
 * 改了一个门槛常量却忘了改文案，界面就会拿着旧数字解释新行为，且没有任何测试会响。
 */
object PlaceLearningStatusPresenter {

    /** presenter 的全部输入，逐个都是**已由各自唯一负责人算好**的事实。 */
    data class Input(
        val placeId: Long,
        /** 用户偏好；null = 表里没有这一行 = 允许（见 [PlaceLearningPreference]） */
        val preference: PlaceLearningPreference?,
        val hasLearnedAnchor: Boolean,
        /** 候选与用户配置锚点的偏移；null = 还没有候选 */
        val candidateOffsetMeters: Double?,
        /** 训练侧：参与聚类的样本数与跨天数（来自候选行，都是实测值） */
        val trainingSampleCount: Int,
        val trainingDistinctDays: Int,
        /** 影子验证结果；null = 还没有窗口 */
        val shadow: ShadowValidator.Result?,
        /** 由 [PlaceModelResolver.resolve] 得出的实际取用来源 */
        val effectiveAnchorSource: EffectiveAnchorSource
    )

    fun present(input: Input): PlaceLearningStatus {
        val enabled = input.preference?.autoApplyEnabled ?: PlaceLearningPreference.DEFAULT_AUTO_APPLY_ENABLED
        val shadow = input.shadow
        val offset = input.candidateOffsetMeters
        val facts = buildFacts(input, enabled, shadow, offset)

        // 禁用态优先：用户停用之后，算法怎么想都不该影响「界面说没说准」。
        if (!enabled) {
            return PlaceLearningStatus(
                placeId = input.placeId,
                phase = PlaceLearningPhase.PAUSED,
                headline = "学习校准已暂停：候选、模型和验证进度已冻结。",
                facts = facts,
                failures = emptyList(),
                effectiveAnchorSource = input.effectiveAnchorSource,
                canDisable = false,
                canReEnable = true
            )
        }

        // 正在生效是**事实**，优先于算法动作：算法认为该走影子、但模型仍被取用时，
        // 界面必须如实说「正在用学习锚点」，否则用户会以为它已经停了。
        if (input.effectiveAnchorSource == EffectiveAnchorSource.LEARNED) {
            return PlaceLearningStatus(
                placeId = input.placeId,
                phase = PlaceLearningPhase.AUTO_APPLIED,
                headline = "已启用学习校准：判定正在使用学习位置，你设置的原始位置仍完整保留。",
                facts = facts,
                failures = emptyList(),
                effectiveAnchorSource = input.effectiveAnchorSource,
                canDisable = true,
                canReEnable = false
            )
        }

        // 还没有窗口（或还没形成候选）：只能说「在学」。
        if (shadow == null || offset == null) {
            return PlaceLearningStatus(
                placeId = input.placeId,
                phase = PlaceLearningPhase.NOT_STARTED,
                headline = "还没攒够样本，正在观察。当前判定使用你设置的位置。",
                facts = facts,
                failures = emptyList(),
                effectiveAnchorSource = input.effectiveAnchorSource,
                canDisable = false,
                canReEnable = false
            )
        }

        // 算法动作由 AnchorUpdatePolicy 唯一裁决，这里只做翻译，不重写判断。
        val action = AnchorUpdatePolicy.decide(offset, shadow)
        val phase = when (action) {
            AnchorUpdateAction.NEEDS_USER_CONFIRM -> PlaceLearningPhase.NEEDS_CONFIRM
            AnchorUpdateAction.AUTO_SMOOTH -> PlaceLearningPhase.PENDING_APPLY
            AnchorUpdateAction.SHADOW, AnchorUpdateAction.REJECTED -> PlaceLearningPhase.SHADOW
        }
        val headline = when (phase) {
            PlaceLearningPhase.NEEDS_CONFIRM ->
                "候选位置与你的设置相差 ${offset.toInt()} 米，超过 " +
                    "${AnchorUpdatePolicy.SHADOW_MAX_OFFSET_METERS.toInt()} 米，不自动生效。"
            PlaceLearningPhase.PENDING_APPLY ->
                "影子验证已通过，但置信度还没到自动生效线，暂不生效。"
            else -> "影子验证中，暂不影响判定。当前判定使用你设置的位置。"
        }
        // 只要形成过候选/窗口，就允许用户提前停用（不必等它先生效一次再关）。
        val canDisable = input.hasLearnedAnchor || shadow != null
        return PlaceLearningStatus(
            placeId = input.placeId,
            phase = phase,
            headline = headline,
            facts = facts,
            failures = shadow.failures,
            effectiveAnchorSource = input.effectiveAnchorSource,
            canDisable = canDisable,
            canReEnable = false
        )
    }

    private fun buildFacts(
        input: Input,
        enabled: Boolean,
        shadow: ShadowValidator.Result?,
        offset: Double?
    ): List<LearningFact> = buildList {
        add(
            LearningFact(
                "训练样本",
                if (input.trainingSampleCount > 0) {
                    "${input.trainingSampleCount} 个 · 跨 ${input.trainingDistinctDays} 天"
                } else {
                    "还没有"
                }
            )
        )
        if (input.effectiveAnchorSource == EffectiveAnchorSource.LEARNED &&
            input.trainingDistinctDays >= AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS
        ) {
            add(LearningFact("历史预学习", "已完成 · 跨 ${input.trainingDistinctDays} 天"))
        } else {
            add(LearningFact("前向验证", shadowProgress(shadow)))
        }
        add(
            LearningFact(
                "候选偏移",
                offset?.let { "${it.toInt()} m（自动档 ≤ ${AnchorUpdatePolicy.AUTO_SMOOTH_MAX_OFFSET_METERS.toInt()} m）" }
                    ?: "还没有候选"
            )
        )
        add(
            LearningFact(
                "中心漂移",
                shadow?.let { "${it.validation.maxCenterDriftMeters.toInt()} m（上限 ${ShadowValidator.MAX_CENTER_DRIFT_METERS.toInt()} m）" }
                    ?: "还没有窗口"
            )
        )
        add(
            LearningFact(
                "环境来源",
                shadow?.let { "最少 ${it.validation.minimumAmbientSources} 类（要求 ≥ ${ShadowValidator.MIN_AMBIENT_SOURCES} 类）" }
                    ?: "还没有窗口"
            )
        )
        add(LearningFact("离散度 P90", spread(shadow)))
        add(LearningFact("自动校准开关", if (enabled) "开启" else "已暂停（状态冻结）"))
    }

    private fun shadowProgress(shadow: ShadowValidator.Result?): String {
        if (shadow == null) return "还没有窗口"
        val v = shadow.validation
        val base = "${v.elapsedDays}/${ShadowValidator.MIN_ELAPSED_DAYS} 天"
        // 窗口只有今天一天时，「每天都有样本」在算术上成立但读起来像在报好消息 ——
        // 实际含义是「刚开始观察」。技术正确但误导的文案必须改掉。
        if (v.elapsedDays == 0) return "$base · 今天开始观察"
        val missing = v.elapsedDays + 1 - v.observedDays
        return if (missing > 0) "$base · 有 $missing 天没样本" else "$base · 每天都有样本"
    }

    private fun spread(shadow: ShadowValidator.Result?): String {
        val v = shadow?.validation ?: return "还没有窗口"
        // null 是「未知」，绝不说成 0 米 —— 0 是「完美集中」，两者相反。
        return v.latestSpreadP90Meters?.let { "${it.toInt()} m" } ?: "无读数（按不通过处理）"
    }
}
