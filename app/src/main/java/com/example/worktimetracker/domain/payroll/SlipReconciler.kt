package com.example.worktimetracker.domain.payroll

/**
 * 工资条核对器（**纯函数，零 IO**）。
 *
 * 只做两件事：按两段式校验工资条是否自相一致，以及把**未填写项与零金额分开**。
 * **绝不修改任何数据** —— 差额只被报告，不被"修正"。
 *
 * ```
 * 校验①  Σ(INCOME) − Σ(DEDUCT_PRE_GROSS)      == 条上「应发工资」
 * 校验②  条上「应发工资」 − Σ(DEDUCT_POST_GROSS) == 条上「实发工资」
 * ```
 *
 * ⚠️ 两条**分别**核对。**绝不**拿「应发」直接减「实发」当误差（那是社保+公积金+个税，
 * 是有意义的固定扣款，不是错账）。
 *
 * 另一条关键语义：`amountCents == null`（未填写）的项**不参与合计**。
 * 于是「已填项推出的应发」是**部分合计**，「差额」正好等于还没补上的那部分 —— 这正是
 * 历史草稿能直接用来自查的原因。
 */
object SlipReconciler {

    enum class IssueKind {
        /** 有未填写项：差额可能来自它们 */
        UNFILLED_ITEMS,

        /** 校验①不平 */
        GROSS_MISMATCH,

        /** 校验②不平 */
        NET_MISMATCH,

        /** 单项金额不合理（负数 / 量级异常） */
        IMPLAUSIBLE,

        /** 收入/扣款属性待裁决（如「病假工资」） */
        AMBIGUOUS_STAGE,

        /** 与 `monthly_salaries` 里已录入的实发不一致 —— **只提示，不改那条记录** */
        MONTHLY_SALARY_MISMATCH,
    }

    data class Issue(
        val kind: IssueKind,
        val itemKeys: List<String> = emptyList(),
        val diffCents: Long? = null,
        val hint: String,
    )

    /**
     * 核对结果。金额单位一律为**分**；差额 = 实际 − 推算（正数 = 条上比推算多）。
     */
    data class SlipCheck(
        /** 只累加**已填写**项 */
        val incomeSumCents: Long,
        val preGrossSumCents: Long,
        val postGrossSumCents: Long,
        /** 已填项推出的应发（部分合计）= incomeSum − preGrossSum */
        val computedGrossCents: Long,
        /** 校验②使用的应发：优先条上值，条上没写才退回 [computedGrossCents] */
        val grossForNetCents: Long,
        val computedNetCents: Long,
        val declaredGrossCents: Long?,
        /** 校验①差额 = 条上应发 − 已填项推出的应发 */
        val grossDiffCents: Long?,
        /** 校验②差额 = 条上实发 −（应发 − Σ应发后扣减） */
        val netDiffCents: Long?,
        /** 条上实发 − `monthly_salaries` 里那条实发（null = 没有可比对的记录） */
        val recordedNetDiffCents: Long?,
        val unfilledKeys: List<SlipItemKey>,
        val zeroKeys: List<SlipItemKey>,
        val needsReviewKeys: List<SlipItemKey>,
        val issues: List<Issue>,
    ) {
        /** 所有分项都填了（不管填的是 0 还是金额） */
        val isComplete: Boolean get() = unfilledKeys.isEmpty()

        /** 两条校验都平，且没有待裁决属性 */
        val isBalanced: Boolean
            get() = isComplete && grossDiffCents == 0L && netDiffCents == 0L &&
                needsReviewKeys.isEmpty()

        /**
         * 建议状态（用户仍可覆盖）：分项没填全 = 草稿；填全了也仍是「待核对」，
         * 必须由用户点确认才变 [SlipStatus.CONFIRMED] —— 确认是不可自动化的动作。
         */
        val suggestedStatus: SlipStatus
            get() = if (!isComplete) SlipStatus.DRAFT else SlipStatus.PENDING_REVIEW
    }

    fun check(
        items: List<SlipItemEntry>,
        declaredGrossCents: Long?,
        declaredNetCents: Long?,
        /** `monthly_salaries` 里同计薪月已录入的实发（只读比对） */
        recordedNetCents: Long? = null,
    ): SlipCheck {
        val income = items.filter { it.key.stage == SlipItemStage.INCOME }
        val preGross = items.filter { it.key.stage == SlipItemStage.DEDUCT_PRE_GROSS }
        val postGross = items.filter { it.key.stage == SlipItemStage.DEDUCT_POST_GROSS }

        // 未填写（null）不进合计；明确为零（0）进合计 —— 这是本模块的核心语义
        fun filled(rows: List<SlipItemEntry>): Long =
            rows.sumOf { it.amountCents ?: 0L }

        val incomeSum = filled(income)
        val preSum = filled(preGross)
        val postSum = filled(postGross)
        val computedGross = incomeSum - preSum
        val grossForNet = declaredGrossCents ?: computedGross
        val computedNet = grossForNet - postSum

        val grossDiff = declaredGrossCents?.let { it - computedGross }
        val netDiff = declaredNetCents?.let { it - computedNet }
        val recordedDiff = if (declaredNetCents != null && recordedNetCents != null) {
            declaredNetCents - recordedNetCents
        } else {
            null
        }

        val unfilled = items.filter { it.isUnfilled }.map { it.key }
        val zeros = items.filter { it.isExplicitZero }.map { it.key }
        // 只看条目上的标记：`SlipItemKey.reviewByDefault` 只用于「草稿种子」阶段打标，
        // 用户裁决后把 needsReview 置回 false 就能消掉这条告警（不能被枚举永远钉住）。
        val review = items.filter { it.needsReview }.map { it.key }

        val issues = buildList {
            if (unfilled.isNotEmpty()) {
                add(
                    Issue(
                        IssueKind.UNFILLED_ITEMS,
                        itemKeys = unfilled.map { it.storageKey },
                        hint = "有 ${unfilled.size} 项未填写（${unfilled.joinToString("、") { it.label }}）" +
                            "，它们**不参与合计**，下面的差额上限即来自这里"
                    )
                )
            }
            grossDiff?.takeIf { it != 0L }?.let {
                add(
                    Issue(
                        IssueKind.GROSS_MISMATCH,
                        diffCents = it,
                        hint = "校验①不平：已填项推出应发 ${money(computedGross)}，" +
                            "条上应发 ${money(declaredGrossCents!!)}，差 ${money(it)}" +
                            if (unfilled.isEmpty()) "（分项已填全，需人工找原因）"
                            else "（还有未填写项，多半就是它们）"
                    )
                )
            }
            netDiff?.takeIf { it != 0L }?.let {
                add(
                    Issue(
                        IssueKind.NET_MISMATCH,
                        diffCents = it,
                        hint = "校验②不平：应发 − 应发后扣款 = ${money(computedNet)}，" +
                            "条上实发 ${money(declaredNetCents!!)}，差 ${money(it)}"
                    )
                )
            }
            items.filter { (it.amountCents ?: 0L) < 0L }.map { it.key }.takeIf { it.isNotEmpty() }?.let { neg ->
                add(
                    Issue(
                        IssueKind.IMPLAUSIBLE,
                        itemKeys = neg.map { it.storageKey },
                        hint = "有负数金额（${neg.joinToString("、") { it.label }}）—— 扣款项请填正数"
                    )
                )
            }
            if (review.isNotEmpty()) {
                add(
                    Issue(
                        IssueKind.AMBIGUOUS_STAGE,
                        itemKeys = review.map { it.storageKey },
                        hint = "属性待裁决：${review.joinToString("、") { it.label }} —— " +
                            "要确认它是**收入**还是**扣款**，不要自动猜"
                    )
                )
            }
            recordedDiff?.takeIf { it != 0L }?.let {
                add(
                    Issue(
                        IssueKind.MONTHLY_SALARY_MISMATCH,
                        diffCents = it,
                        hint = "与已录入的实发 ${money(recordedNetCents!!)} 不一致（差 ${money(it)}）" +
                            "—— 只作提示，**不会改动那条记录**"
                    )
                )
            }
        }

        return SlipCheck(
            incomeSumCents = incomeSum,
            preGrossSumCents = preSum,
            postGrossSumCents = postSum,
            computedGrossCents = computedGross,
            grossForNetCents = grossForNet,
            computedNetCents = computedNet,
            declaredGrossCents = declaredGrossCents,
            grossDiffCents = grossDiff,
            netDiffCents = netDiff,
            recordedNetDiffCents = recordedDiff,
            unfilledKeys = unfilled,
            zeroKeys = zeros,
            needsReviewKeys = review,
            issues = issues,
        )
    }

    private fun money(cents: Long): String =
        "¥%.2f".format(java.util.Locale.CHINA, cents / 100.0)
}
