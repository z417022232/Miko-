package com.example.worktimetracker.data.entity

enum class ManualField(val bit: Int) {
    SHIFT(1 shl 0),
    COMPANY_ARRIVAL(1 shl 1),
    COMPANY_DEPARTURE(1 shl 2),
    HOME_DEPARTURE(1 shl 3),
    HOME_ARRIVAL(1 shl 4),
    FINAL_MINUTES(1 shl 5),
    NOTE(1 shl 6)
}

object ManualFieldMask {
    fun add(mask: Int, field: ManualField): Int = mask or field.bit
    fun contains(mask: Int, field: ManualField): Boolean = mask and field.bit != 0

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
