package com.example.worktimetracker.location.service

import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.data.entity.ManualFieldMask
import com.example.worktimetracker.data.entity.WorkRecordEntity

object ConfirmedSession {
    fun merge(
        existing: WorkRecordEntity?,
        shift: String,
        companyArrival: Long,
        companyDeparture: Long?,
        homeDeparture: Long?,
        homeArrival: Long?,
        actualMinutes: Int?,
        calculatedMinutes: Int,
        needsReview: Boolean,
        status: String = "WORK",
        mode: MergeMode = MergeMode.REPAIR_FILL,
        /** v1 规则触发的规则 ID 列表（如 R1,R2,R8）。finalize 时由 WorkSessionEngine 填充。 */
        v1RuleTrace: List<String> = emptyList(),
        /** v1 算法对齐后的有效 start（迟到向上取整后的整点）。finalize 时由 WorkSessionEngine 填充。 */
        v1EffectiveStartMillis: Long? = null,
        /** v1 算法对齐后的有效 end。灰区/超限不计加班时为 expectedEnd；正常为 endMillis。 */
        v1EffectiveEndMillis: Long? = null,
        /** A2: needsReview 结构化原因。null = 无需复核。 */
        reviewReason: String? = null
    ): WorkRecordEntity {
        val validDeparture = companyDeparture?.takeIf { it >= companyArrival }
        val validHomeDeparture = homeDeparture?.takeIf { it <= companyArrival }
        val validHomeArrival = homeArrival?.takeIf { validDeparture != null && it >= validDeparture }
        val invalidOrder = validDeparture != companyDeparture || validHomeDeparture != homeDeparture || validHomeArrival != homeArrival
        val base = existing ?: WorkRecordEntity(workDate = "", status = status, finalMinutes = calculatedMinutes)
        // 人工记录/带人工保护字段的记录：原审核标记必须保留，避免自动计算把人工确认状态清掉
        val preserveExistingReview = base.isManual || base.manualFieldsMask != 0
        val review = when (mode) {
            // 正常下班完结：审核结果以最终计算为准，自动草稿阶段的临时标记不再粘住
            MergeMode.FINALIZE_SESSION -> needsReview || invalidOrder || (preserveExistingReview && base.needsReview)
            // 历史修复/恢复补全：补全本身就是异常信号，原有标记一律保留
            MergeMode.REPAIR_FILL -> base.needsReview || needsReview || invalidOrder
        }
        val v1Note = buildV1Note(v1RuleTrace, calculatedMinutes, v1EffectiveStartMillis, v1EffectiveEndMillis, base.finalMinutes)
        val newMask = if (v1RuleTrace.isNotEmpty()) {
            ManualFieldMask.add(base.manualFieldsMask, ManualField.FINAL_MINUTES)
        } else base.manualFieldsMask
        return base.copy(
            status = if (base.isManual) base.status else status,
            shift = shift,
            startTime = companyArrival,
            endTime = validDeparture,
            homeDepartureTime = validHomeDeparture,
            homeArrivalTime = validHomeArrival,
            actualMinutes = actualMinutes,
            finalMinutes = if (base.isManual) base.finalMinutes else calculatedMinutes,
            needsReview = review,
            reviewReason = if (base.isManual) base.reviewReason else reviewReason,
            note = if (base.isManual) base.note else v1Note ?: base.note,
            manualFieldsMask = newMask,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 根据 v1 规则 ID 列表生成可读 note。返回 null 表示不需要写 note（保留原值）。
     */
    internal fun buildV1Note(
        rules: List<String>,
        finalMinutes: Int,
        effectiveStartMillis: Long?,
        effectiveEndMillis: Long?,
        previousFinalMinutes: Int
    ): String? {
        if (rules.isEmpty()) return null
        val rulesText = rules.joinToString(",")
        return when {
            rules.contains("R3_GREY") -> "v1[$rulesText]: 21:00-21:29 灰区（不计加班，需复核）→ final=${finalMinutes}min"
            rules.contains("R4_NO_OVERTIME") -> "v1[$rulesText]: 21:30+ 不计加班（需复核）→ final=${finalMinutes}min"
            rules.contains("R1_ALIGN_UP") -> "v1[$rulesText]: 迟到按整点起算，-1h 分散吃饭 → final=${finalMinutes}min"
            rules.contains("R7") && !rules.contains("R1_ALIGN_UP") -> "v1[$rulesText]: 早退按公式，-1h 分散吃饭 → final=${finalMinutes}min"
            rules.contains("R2") && rules.contains("R8") && rules.size <= 4 -> "v1[$rulesText]: 21:00 整下班 - 1h 分散吃饭 → final=${finalMinutes}min"
            else -> "v1[$rulesText]: final=${finalMinutes}min"
        }
    }
}