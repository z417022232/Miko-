package com.example.worktimetracker.data

data class ManualOverrideSnapshot(val shift: String, val startTime: Long?, val endTime: Long?, val finalMinutes: Int) {
    companion object {
        fun parse(value: String): ManualOverrideSnapshot? {
            val parts = value.split(":")
            if (parts.size != 4) return null
            val minutes = parts[3].toIntOrNull() ?: return null
            return ManualOverrideSnapshot(parts[0], parts[1].toLongOrNull(), parts[2].toLongOrNull(), minutes)
        }
    }
}
