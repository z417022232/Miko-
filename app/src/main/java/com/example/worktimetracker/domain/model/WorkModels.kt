package com.example.worktimetracker.domain.model

enum class LocationType { HOME, COMPANY, OTHER, UNKNOWN }
enum class ShiftType {
    DAY_SHIFT, NIGHT_SHIFT;

    companion object {
        /**
         * 统一解析落库/显示/导入三种来源的班次字符串。
         *
         * 历史上同一条记录出现过 3 种写法：枚举名（DAY_SHIFT/NIGHT_SHIFT）、
         * 中文显示标签（白班/夜班）、以及 null。UI 曾把显示标签直接写回库
         * （CalendarScreen 手动工时对话框），导致这些记录的班次在学习与展示链路中丢失。
         *
         * [normalize] 用于**写入侧**归一（未知按白班，与历史默认一致）；
         * [parse] 用于**读取侧**，无法识别返回 null 由调用方决定跳过或兜底。
         */
        fun normalize(raw: String?): String = parse(raw)?.name ?: DAY_SHIFT.name

        fun parse(raw: String?): ShiftType? = when (raw?.trim()) {
            DAY_SHIFT.name, "白班" -> DAY_SHIFT
            NIGHT_SHIFT.name, "夜班" -> NIGHT_SHIFT
            else -> null
        }
    }
}
enum class RecordStatus { WORK, REST, OUTSIDE, LEAVE, EARLY_LEAVE, ARRIVAL_EXCEPTION }
enum class WorkState { REST, LEAVING_HOME, NEAR_COMPANY, WORKING, TEMP_LEAVE, FINISHED }

data class WorkSettings(
    val workStartMinutes: Int = 9 * 60,
    val workEndMinutes: Int = 21 * 60,
    val hasDefaultHours: Boolean = false,
    val defaultWorkMinutes: Int? = null,
    val restDeductionMinutes: Int = 60,
    val outsideThresholdMinutes: Int = 120,
    val leaveCompanyConfirmMinutes: Int = 60,
    val earlyLeaveToleranceMinutes: Int = 3,
    val arrivalToleranceMinutes: Int = 3
)

data class WorkSegment(
    val startMillis: Long,
    val endMillis: Long,
    val deductRest: Boolean = false
)

data class WorkSession(
    val startMillis: Long?,
    val endMillis: Long?,
    val assignedDate: String,
    val shiftType: ShiftType,
    val status: RecordStatus,
    val actualMinutes: Int,
    val finalMinutes: Int,
    val needsReview: Boolean = false,
    /** v1 规则按 status 分支对齐后的有效开始时间（迟到向上取整、早退/灰区按 endTime）。null 表示 rest。 */
    val v1EffectiveStartMillis: Long? = null,
    /** v1 规则按 status 分支对齐后的有效结束时间（早退时保留原始 endTime，灰区/超限不计加班时 == endMillis 或 null）。 */
    val v1EffectiveEndMillis: Long? = null,
    /** 触发的 v1 规则 ID 列表，如 listOf("R1","R2","R8")，供 UI / note 复用。 */
    val v1RuleTrace: List<String> = emptyList(),
    /** A2: needsReview 的结构化原因（可读字符串，UI 直接展示）。null 表示无需复核。 */
    val reviewReason: String? = null,
    /**
     * A5/R5: 该班次是否跨夜（start 与 end 不在同一本地日期）。
     *
     * 归属约定（2026-09-12 用户确认）：跨夜班次 workDate 一律取【上班日期（开班日）】，
     * 例如夜班 8/1 21:00 → 8/2 09:00 记 8/1。这样与次日白班 8/2 09:00→21:00 不会同日撞车。
     */
    val crossesMidnight: Boolean = false
)

data class WorkCalculationInput(
    val startMillis: Long?,
    val endMillis: Long?,
    val manualFinalMinutes: Int? = null,
    val manualSegments: List<WorkSegment> = emptyList(),
    val settings: WorkSettings = WorkSettings(),
    val fallbackStartMillis: Long? = null,
    val fallbackEndMillis: Long? = null
)
