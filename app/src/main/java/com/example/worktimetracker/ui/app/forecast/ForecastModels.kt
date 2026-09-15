package com.example.worktimetracker.ui.app.forecast

import com.example.worktimetracker.domain.payroll.SlipItemEntry
import com.example.worktimetracker.domain.payroll.SlipItemKey
import com.example.worktimetracker.domain.payroll.SlipReconciler
import com.example.worktimetracker.domain.payroll.SlipStatus
import com.example.worktimetracker.ui.PayrollPresenter

/**
 * 单个分项的录入草稿。
 *
 * [text] 为**原始输入文本**（`"0"` / `"331.03"` / 空串）：
 * - 空串 → `amountCents = null` = **未填写**（不进合计）
 * - `"0"` → `0L` = **明确为零**（进合计）
 *
 * 刻意保留文本而不是直接存 `Long?`：用户输入 `0` 与留空是两件事，用文本能天然区分，
 * 也顺便满足「保留原始数字、不自动修正」（`rawText`）。
 */
data class SlipItemDraft(
    val text: String = "",
    val needsReview: Boolean = false,
)

/**
 * 工资条录入页的可编辑状态（DB v13「第一步」）。
 *
 * 全部字段都是**界面草稿**：`SlipReconciler` 只读它、产出校验结论；
 * 真正落库由 `ForecastViewModel.save()` 完成，且**永不回写** `monthly_salaries`。
 */
data class SlipEditorState(
    val payrollMonth: String,
    val paymentDate: String = "",
    val status: SlipStatus = SlipStatus.DRAFT,
    val slipAttendDays: String = "",
    val slipNightShifts: String = "",
    /** 条上「应发工资」输入文本（空 = 未填写） */
    val grossText: String = "",
    /** 条上「实发工资」输入文本（空 = 未填写） */
    val netText: String = "",
    val note: String = "",
    val items: Map<SlipItemKey, SlipItemDraft> = emptyMap(),
    /** `monthly_salaries` 里同计薪月已录入的实发（**只读比对**，界面原样展示） */
    val recordedNetCents: Long? = null,
    val revision: Int = 1,
    val confirmedAt: Long? = null,
    /** 数据库里是否已有这条工资条（迁移给历史月灌过表头） */
    val hasSlip: Boolean = false,
    /** 是否有未保存的改动（决定保存时是否 `revision + 1`） */
    val dirty: Boolean = false,
) {
    val declaredGrossCents: Long? get() = PayrollPresenter.parseMoneyOrNull(grossText)
    val declaredNetCents: Long? get() = PayrollPresenter.parseMoneyOrNull(netText)

    /** 分项草稿 → 核对器输入。空文本保持 `null`（未填写），`"0"` 是明确为零。 */
    val entries: List<SlipItemEntry>
        get() = SlipItemKey.displayOrder.map { key ->
            val draft = items[key]
            SlipItemEntry(
                key = key,
                amountCents = PayrollPresenter.parseMoneyOrNull(draft?.text.orEmpty()),
                needsReview = draft?.needsReview ?: false,
            )
        }

    /** 实时双校验（纯函数，随每次输入变化）。 */
    val check: SlipReconciler.SlipCheck
        get() = SlipReconciler.check(
            items = entries,
            declaredGrossCents = declaredGrossCents,
            declaredNetCents = declaredNetCents,
            recordedNetCents = recordedNetCents,
        )
}
