package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.data.entity.ManualField
import com.example.worktimetracker.data.entity.ManualFieldMask
import com.example.worktimetracker.data.entity.WorkRecordEntity

/**
 * A6: 灰区/异常记录的"认可复核"路径。
 *
 * 与 [ReviewRecordEditor.confirm] 的区别（**这是设计要点，勿混用**）：
 *
 * | 场景 | 用户意图 | 走哪条路 | 结果 |
 * |---|---|---|---|
 * | 系统算的值是对的，我认可 | 只是确认 | [ReviewAcknowledger.acknowledge] | 只清 needsReview + 打 NEEDS_REVIEW_ACK |
 * | 值不对，我要改成 X | 修改 | [ReviewRecordEditor.confirm] | isManual=true + 人工位（含 FINAL_MINUTES 锁） |
 *
 * **为什么不改值也要单独一条路**：若认可也走 confirm，会把 FINAL_MINUTES 标成人工保护位，
 * 之后自动算法再也改不动这条记录（A3 修过的同类缺陷）。用户只是点头，不该造成锁定。
 *
 * NEEDS_REVIEW_ACK 属于 HUMAN_BITS，因此：
 * - [com.example.worktimetracker.location.service.ProtectedRecordMerge] 会保留它；
 * - 但 [com.example.worktimetracker.location.service.ConfirmedSession] 在自动重算
 *   （v1RuleTrace 非空且非手工记录）时会**清除**它 —— 情况变了要重新确认，这是刻意行为。
 */
object ReviewAcknowledger {

    fun acknowledge(
        existing: WorkRecordEntity,
        note: String? = null,
        now: Long = System.currentTimeMillis()
    ): Result<WorkRecordEntity> = runCatching {
        require(existing.needsReview) { "该记录已不需要确认" }
        existing.copy(
            needsReview = false,
            // 只加"已确认"人工位；不动 isManual / FINAL_MINUTES，避免把自动结果锁死
            manualFieldsMask = ManualFieldMask.add(existing.manualFieldsMask, ManualField.NEEDS_REVIEW_ACK),
            note = note?.takeIf { it.isNotBlank() } ?: existing.note,
            updatedAt = now
        )
    }
}
