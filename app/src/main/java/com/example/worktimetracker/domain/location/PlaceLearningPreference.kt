package com.example.worktimetracker.domain.location

/**
 * 用户对「地点学习校准」的偏好（方案 §三.2 / §十一 阶段2 的回退入口，DB v16）。
 *
 * ## 为什么必须是一张独立的表，而不是往模型上加一列
 *
 * 这里有三件**语义完全不同**的事，混在一起之后就无法回答「为什么现在没生效」：
 *
 * | 层 | 存在哪 | 回答的问题 | 谁改它 |
 * |---|---|---|---|
 * | 模型是否有效 | `learning_model_meta.status` | 这个版本被作废了吗 | 算法（`RETIRED`/`INVALID`） |
 * | 算法是否通过 | `learned_place_models.autoApplied` | 影子验证通过了吗 | 算法（每轮学习重算） |
 * | 用户是否允许用 | **本表** `autoApplyEnabled` | 我愿意让它自动校准吗 | **只有用户** |
 *
 * 一旦把第三层塞进第一层，就会出现「用户按了暂停、模型被标成 RETIRED」这种谎话：
 * 模型其实好得很，只是用户不让用。之后无法区分「模型坏了」和「用户关了」，
 * 而这两种情况该做的事完全不同（前者要重训，后者只需等用户开回来）。
 *
 * 因此 [autoApplyEnabled] 是**粘性暂停**：暂停后候选、窗口和模型版本全部冻结；
 * 恢复时从冻结点继续，取锚点仍由 [PlaceModelResolver] 统一决定。
 *
 * ## 缺行 = 启用
 *
 * 表里**没有这个地点的行**，语义就是 `autoApplyEnabled = true`。
 * 于是 DB v15 → v16 迁移**不需要回填任何数据**：老库升级上来自然就是全启用，
 * 「零回归」是结构上成立的，而不是靠迁移脚本写对。
 * 只有用户真的停用过，才会留下第一行。
 */
data class PlaceLearningPreference(
    /** = `sites.id` */
    val placeId: Long,
    /** 用户是否允许学习锚点参与自动校准；缺行时按 true 处理（见类注释） */
    val autoApplyEnabled: Boolean,
    val updatedAt: Long
) {
    companion object {
        /** 缺行时的语义值：允许。集中在这里，避免各调用点各写一遍 `!= false`。 */
        const val DEFAULT_AUTO_APPLY_ENABLED = true
    }
}

/**
 * 停用 / 重新开启学习校准的**纯决策**（不碰数据库、不碰 Android）。
 *
 * 抽成纯函数的理由和 [PlaceModelResolver] 一样：「停用到底该不该改模型状态」
 * 这种问题必须在单测里能钉死，而不是靠读几行 `if`。这个 object 只回答
 * 「该变成什么样」，落库与日志由 `location/service` 负责。
 *
 * ## 两条不对称的规则（刻意不对称）
 *
 * - **停用只写偏好**：`autoApplied` 是「算法是否通过」的事实，用户没资格改它（改了就丢事实）。
 *   停用表现在**取用时**：`PlaceModelResolver` 见到 `autoApplyEnabled = false` 直接回落配置锚点。
 *   好处是「停用 → 重新开启」不会因为中途的重训而漂移 —— 模型状态自始至终是算法自己的账。
 * - **恢复保持原状态**：暂停期间学习层不写候选或模型，因此恢复时无需吊销
 *   `autoApplied`，也不重开影子窗口。
 *
 * 两条合起来的意思：停用是**用户意志**（不动模型），开启是**算法重验**（动模型）。
 */
object PlaceLearningPreferencePolicy {

    /** 停用/开启后该落到库里的一组变化。 */
    data class Effect(
        /** 该写入偏好表的那一行 */
        val preference: PlaceLearningPreference,
        /**
         * 模型 `autoApplied` 该置成什么；
         * **null = 不要动模型**（停用时走这条：用户偏好不改算法事实）。
         */
        val modelAutoAppliedAfter: Boolean?,
        /**
         * 是否重开前向影子窗口（把窗口计时起点推到此刻）。
         * true = 停用期间那段观察不计入连续稳定性，必须重新观察满
         * [AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS] 天。
         */
        val reopenShadowWindow: Boolean,
        /** 给用户看的一句话（会出现在地点管理页） */
        val explanation: String
    )

    /**
     * 用户停用学习校准。
     *
     * ⚠️ [Effect.modelAutoAppliedAfter] 恒为 null —— **不改模型**。
     * 这是「用户偏好 / 算法事实 / 模型状态」三层分离的落点：
     * 停用**不是**退役模型，模型仍可继续训练与升版本，只是不被取用。
     */
    fun disable(placeId: Long, now: Long): Effect = Effect(
        preference = PlaceLearningPreference(
            placeId = placeId,
            autoApplyEnabled = false,
            updatedAt = now
        ),
        modelAutoAppliedAfter = null,
        reopenShadowWindow = false,
        explanation = "已暂停学习校准：当前学习状态已冻结，判定使用你设置的位置。"
    )

    /**
     * 用户重新开启学习校准。
     *
     * 两条动作一起做才有意义：
     *  1. 吊销 `autoApplied`（[Effect.modelAutoAppliedAfter] = false）——
     *     否则下一次学习前它就立刻生效了，重新验证等于没做；
     *  2. 重开影子窗口（[Effect.reopenShadowWindow] = true）——
     *     否则窗口里已经攒够 7 天，同一轮学习就会重新判通过，第 1 条也被绕过。
     *
     * 只做其中一条都构造得出「开启后立刻生效」的输入，所以两条都得在。
     */
    fun enable(placeId: Long, now: Long): Effect = Effect(
        preference = PlaceLearningPreference(
            placeId = placeId,
            autoApplyEnabled = true,
            updatedAt = now
        ),
        modelAutoAppliedAfter = null,
        reopenShadowWindow = false,
        explanation = "已恢复学习校准：从冻结前的学习状态继续。"
    )

    /** 偏好行 → 领域对象；缺行按默认启用（见 [PlaceLearningPreference] 的「缺行 = 启用」）。 */
    fun of(placeId: Long, autoApplyEnabled: Boolean?, updatedAt: Long): PlaceLearningPreference =
        PlaceLearningPreference(
            placeId = placeId,
            autoApplyEnabled = autoApplyEnabled ?: PlaceLearningPreference.DEFAULT_AUTO_APPLY_ENABLED,
            updatedAt = updatedAt
        )
}
