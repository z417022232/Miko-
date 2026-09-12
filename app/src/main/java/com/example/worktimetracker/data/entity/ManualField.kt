package com.example.worktimetracker.data.entity

/**
 * work_records.manualFieldsMask 位定义。
 *
 * **位序向后兼容**：bit 0–6 是 2026-09-06 起沿用的历史位，不得改动含义。
 * bit 7+ 为 A3 新增。
 *
 * 语义分两类：
 * - **人工保护位**（HUMAN_BITS）：用户手动改过的字段，自动合并必须保留原值。
 * - **自动痕迹位**（AUTO_BITS）：系统按 v1 规则算过 / 判过的痕迹，**不代表人工意图**，
 *   不参与"人工保护"判断，否则自动算法会把自己的结果锁死、后续无法重算。
 */
enum class ManualField(val bit: Int) {
    SHIFT(1 shl 0),
    COMPANY_ARRIVAL(1 shl 1),
    COMPANY_DEPARTURE(1 shl 2),
    HOME_DEPARTURE(1 shl 3),
    HOME_ARRIVAL(1 shl 4),

    /** 人工保护位：用户手动覆盖 finalMinutes。 */
    FINAL_MINUTES(1 shl 5),

    /** 人工保护位：用户手动改备注。 */
    NOTE(1 shl 6),

    // ---------- A3 新增（bit 7+） ----------

    /** 自动痕迹位：v1 规则自动算出 finalMinutes（A1）。 */
    AUTO_FINAL_MINUTES(1 shl 7),

    /** 自动痕迹位：v1 规则自动判定 needsReview（A2/A3）。 */
    AUTO_NEEDS_REVIEW(1 shl 8),

    /** 人工保护位：用户已确认过 needsReview（A6 UI 会写）。 */
    NEEDS_REVIEW_ACK(1 shl 9)
}

object ManualFieldMask {
    /** 人工保护位合集：这些位置位代表"用户在 UI 改过 / 确认过"，自动合并不得覆盖。 */
    const val HUMAN_BITS: Int =
        (1 shl 0) or (1 shl 1) or (1 shl 2) or (1 shl 3) or (1 shl 4) or (1 shl 5) or (1 shl 6) or (1 shl 9)

    /** 自动痕迹位合集：系统算法留下的标记，不参与人工保护。 */
    const val AUTO_BITS: Int = (1 shl 7) or (1 shl 8)

    fun add(mask: Int, field: ManualField): Int = mask or field.bit
    fun remove(mask: Int, field: ManualField): Int = mask and field.bit.inv()
    fun contains(mask: Int, field: ManualField): Boolean = mask and field.bit != 0

    /** 只保留人工保护位（剥离自动痕迹）。 */
    fun humanOnly(mask: Int): Int = mask and HUMAN_BITS

    /** 是否存在任何人工保护位。 */
    fun hasHumanProtection(mask: Int): Boolean = humanOnly(mask) != 0

    /** 是否有 v1 自动算过 finalMinutes 的痕迹。 */
    fun hasAutoFinalMinutes(mask: Int): Boolean = contains(mask, ManualField.AUTO_FINAL_MINUTES)

    /** 是否有 v1 自动判定 needsReview 的痕迹。 */
    fun hasAutoNeedsReview(mask: Int): Boolean = contains(mask, ManualField.AUTO_NEEDS_REVIEW)

    /** 用户是否已确认过 needsReview。 */
    fun isNeedsReviewAcknowledged(mask: Int): Boolean = contains(mask, ManualField.NEEDS_REVIEW_ACK)

    fun fromLegacy(record: WorkRecordEntity): Int {
        if (!record.isManual) return 0
        var mask = ManualField.SHIFT.bit or ManualField.FINAL_MINUTES.bit
        if (record.startTime != null) mask = add(mask, ManualField.COMPANY_ARRIVAL)
        if (record.endTime != null) mask = add(mask, ManualField.COMPANY_DEPARTURE)
        if (record.homeDepartureTime != null) mask = add(mask, ManualField.HOME_DEPARTURE)
        if (record.homeArrivalTime != null) mask = add(mask, ManualField.HOME_ARRIVAL)
        if (record.note != null) mask = add(mask, ManualField.NOTE)
        return mask
    }
}