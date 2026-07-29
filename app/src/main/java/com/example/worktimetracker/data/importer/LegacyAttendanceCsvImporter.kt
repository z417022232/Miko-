package com.example.worktimetracker.data.importer

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class LegacyAttendanceEvent(
    val eventType: String,
    val timeMillis: Long,
    val localTime: LocalDateTime,
    val latitude: Double,
    val longitude: Double,
    val shiftName: String,
    val corrected: Boolean
)

data class LegacyDailyRecord(
    val date: LocalDate,
    val status: String,
    val shift: String?,
    val startTime: Long?,
    val finalMinutes: Int,
    val sourceEventCount: Int
)

data class LegacyAttendanceImportPlan(
    val events: List<LegacyAttendanceEvent>,
    val dailyRecords: List<LegacyDailyRecord>
)

object LegacyAttendanceCsvImporter {
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun createImportPlan(csvText: String, defaultWorkMinutes: Int): LegacyAttendanceImportPlan {
        val events = parse(csvText)
        require(events.isNotEmpty()) { "CSV 中没有可导入的考勤记录" }
        return LegacyAttendanceImportPlan(
            events = events,
            dailyRecords = aggregate(events, defaultWorkMinutes.coerceAtLeast(0))
        )
    }

    private fun parse(csvText: String): List<LegacyAttendanceEvent> {
        val lines = csvText.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.isNotEmpty()) { "CSV 文件为空" }
        val header = parseCsvLine(lines.first()).map { it.removePrefix("\uFEFF").trim() }
        val required = listOf(
            "eventType",
            "timeMillis",
            "timeLocal",
            "latitude",
            "longitude",
            "shiftName",
            "corrected"
        )
        require(required.all(header::contains)) { "不是受支持的旧考勤 CSV 格式" }
        val indexes = required.associateWith(header::indexOf)

        return lines.drop(1).mapNotNull { line ->
            val values = parseCsvLine(line)
            runCatching {
                LegacyAttendanceEvent(
                    eventType = values.valueAt(indexes.getValue("eventType")).trim().uppercase(),
                    timeMillis = values.valueAt(indexes.getValue("timeMillis")).trim().toLong(),
                    localTime = LocalDateTime.parse(
                        values.valueAt(indexes.getValue("timeLocal")).trim(),
                        timeFormatter
                    ),
                    latitude = values.valueAt(indexes.getValue("latitude")).trim().toDouble(),
                    longitude = values.valueAt(indexes.getValue("longitude")).trim().toDouble(),
                    shiftName = values.valueAt(indexes.getValue("shiftName")).trim(),
                    corrected = values.valueAt(indexes.getValue("corrected")).trim()
                        .equals("true", ignoreCase = true)
                )
            }.getOrNull()
        }.sortedBy { it.timeMillis }
    }

    private fun aggregate(
        events: List<LegacyAttendanceEvent>,
        defaultWorkMinutes: Int
    ): List<LegacyDailyRecord> {
        val manualByDate = events
            .filter { it.shiftName.startsWith("手动补录-") }
            .groupBy { it.localTime.toLocalDate() }

        val workByAssignedDate = events
            .filter { it.eventType == "WORK" && !it.shiftName.startsWith("手动补录-") }
            .groupBy(::assignedWorkDate)

        val calendarEvents = events.groupBy { it.localTime.toLocalDate() }
        val allDates = (calendarEvents.keys + workByAssignedDate.keys).toSortedSet()

        return allDates.map { date ->
            val manualEvents = manualByDate[date].orEmpty()
            val manual = manualEvents.lastOrNull()
            if (manual != null) {
                val isWork = manual.eventType == "WORK"
                return@map LegacyDailyRecord(
                    date = date,
                    status = if (isWork) "WORK" else "REST",
                    shift = if (isWork) "DAY_SHIFT" else null,
                    startTime = if (isWork) manual.timeMillis else null,
                    finalMinutes = if (isWork) defaultWorkMinutes else 0,
                    sourceEventCount = manualEvents.size
                )
            }

            val workEvents = workByAssignedDate[date].orEmpty()
            if (workEvents.isNotEmpty()) {
                val dayEvents = workEvents.filterNot(::isNightEvent)
                val nightEvents = workEvents.filter(::isNightEvent)
                val daySpanMinutes = if (dayEvents.size < 2) {
                    0L
                } else {
                    Duration.between(
                        dayEvents.minOf { it.localTime },
                        dayEvents.maxOf { it.localTime }
                    ).toMinutes()
                }
                val hasStrongDayEvidence = dayEvents.size >= 3 || daySpanMinutes >= 60
                val hasNightStart = nightEvents.any {
                    it.localTime.toLocalDate() == date && it.localTime.hour >= 15
                }
                val isNight = hasNightStart && !hasStrongDayEvidence || dayEvents.isEmpty()
                val startEvent = if (isNight) {
                    nightEvents
                        .filter { it.localTime.toLocalDate() == date && it.localTime.hour >= 15 }
                        .minByOrNull { it.timeMillis }
                        ?: nightEvents.minByOrNull { it.timeMillis }
                } else {
                    dayEvents.minByOrNull { it.timeMillis }
                }
                return@map LegacyDailyRecord(
                    date = date,
                    status = "WORK",
                    shift = if (isNight) "NIGHT_SHIFT" else "DAY_SHIFT",
                    startTime = startEvent?.timeMillis,
                    finalMinutes = defaultWorkMinutes,
                    sourceEventCount = workEvents.size
                )
            }

            val dayEvents = calendarEvents[date].orEmpty()
            val outingEvents = dayEvents.filter { it.eventType == "OUTING" }
            val outingSpanMinutes = if (outingEvents.size < 2) {
                0L
            } else {
                Duration.between(
                    outingEvents.minOf { it.localTime },
                    outingEvents.maxOf { it.localTime }
                ).toMinutes()
            }
            val isOutside = outingSpanMinutes >= 120
            LegacyDailyRecord(
                date = date,
                status = if (isOutside) "OUTSIDE" else "REST",
                shift = null,
                startTime = null,
                finalMinutes = 0,
                sourceEventCount = dayEvents.size
            )
        }
    }

    private fun assignedWorkDate(event: LegacyAttendanceEvent): LocalDate {
        val localDate = event.localTime.toLocalDate()
        return if (isNightEvent(event) && event.localTime.hour < 12) {
            localDate.minusDays(1)
        } else {
            localDate
        }
    }

    private fun isNightEvent(event: LegacyAttendanceEvent): Boolean =
        event.shiftName.contains("夜")

    private fun parseCsvLine(line: String): List<String> {
        val values = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    values += current.toString()
                    current.clear()
                }
                else -> current.append(char)
            }
            index++
        }
        values += current.toString()
        return values
    }

    private fun List<String>.valueAt(index: Int): String =
        getOrElse(index) { error("CSV 行缺少字段") }
}
