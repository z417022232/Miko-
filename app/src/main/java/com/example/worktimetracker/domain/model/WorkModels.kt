package com.example.worktimetracker.domain.model

enum class LocationType { HOME, COMPANY, OTHER, UNKNOWN }
enum class ShiftType { DAY_SHIFT, NIGHT_SHIFT }
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
    val needsReview: Boolean = false
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
