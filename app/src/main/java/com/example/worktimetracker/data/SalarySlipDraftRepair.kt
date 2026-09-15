package com.example.worktimetracker.data

import android.content.Context
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.data.entity.SalarySlipItemEntity
import com.example.worktimetracker.domain.payroll.PayRateKey
import com.example.worktimetracker.domain.payroll.PayRateResolver
import com.example.worktimetracker.domain.payroll.SlipDraftSeeder
import com.example.worktimetracker.domain.payroll.SlipItemKey

/**
 * 历史工资条草稿的**分项**种子（一次性）。
 *
 * `MIGRATION_12_13` 只建了两张表并给历史月灌了**表头**（应发/实发/状态）；
 * 分项刻意留在 Kotlin（[SlipDraftSeeder]），这样 `PayRateSeed` 改了草稿跟着改，只有一处真相 ——
 * 但代价是迁移里没法调用它，所以在应用启动时补灌一次。
 *
 * 幂等：只处理「还没有任何分项」的计薪月；`PREFS` 打标记后不再重复。
 * **不碰** `monthly_salaries` / `work_records`。
 */
object SalarySlipDraftRepair {
    private const val PREFS = "salary_slip_draft_repair"
    private const val KEY = "draft_items_v1"

    /**
     * 「保留原始数字、不自动修正」的疑点清单 —— 对应设计稿 §7 第一步的三处工单。
     * 这些项即使金额留空也要打上 `needsReview`，提醒用户对着纸质条裁决。
     */
    private val REVIEW_SEED: Map<String, Set<SlipItemKey>> = mapOf(
        "2026-05" to setOf(SlipItemKey.NIGHT_ALLOWANCE),
        "2026-06" to setOf(SlipItemKey.SENIOR_ALLOWANCE, SlipItemKey.SICK_PAY),
        "2026-07" to setOf(SlipItemKey.SENIOR_ALLOWANCE),
    )

    suspend fun runOnce(app: WorkTimeApplication) {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return

        val db = app.database
        val slips = db.salarySlipDao().allSlips()
        if (slips.isEmpty()) {
            prefs.edit().putBoolean(KEY, true).apply()
            return
        }

        val segments = db.payrollDao().rateSegments()
            .groupBy { PayRateKey.byStorageKey(it.paramKey) }
            .mapNotNull { (key, rows) ->
                key?.let { it to rows.map { row -> PayRateResolver.Segment(row.effectiveFrom, row.value) } }
            }
            .toMap()

        val now = System.currentTimeMillis()
        for (slip in slips) {
            if (db.salarySlipDao().items(slip.payrollMonth).isNotEmpty()) continue
            val rates = PayRateResolver.resolve(slip.payrollMonth, segments)
            val forced = REVIEW_SEED[slip.payrollMonth].orEmpty()
            val rows = SlipDraftSeeder.draftItems(rates).map { entry ->
                SalarySlipItemEntity(
                    payrollMonth = slip.payrollMonth,
                    itemKey = entry.key.storageKey,
                    rawLabel = entry.key.label,
                    amountCents = entry.amountCents,
                    stage = entry.key.stage.name,
                    nature = entry.key.nature.name,
                    rawText = null,
                    needsReview = entry.needsReview || entry.key in forced,
                    note = null,
                    updatedAt = now,
                )
            }
            db.salarySlipDao().saveItems(rows)
        }
        prefs.edit().putBoolean(KEY, true).apply()
    }
}
