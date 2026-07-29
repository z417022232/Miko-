package com.example.worktimetracker.export

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Environment
import com.example.worktimetracker.data.entity.UserSettingsEntity
import com.example.worktimetracker.ui.UiDayRecord
import java.io.File
import java.time.YearMonth
import org.json.JSONObject

object ExportManager {
    fun exportCsv(context: Context, month: YearMonth, records: List<UiDayRecord>): File {
        val dir = exportDir(context, "Export")
        val file = File(dir, "WorkTime_${month.year}_${month.monthValue.toString().padStart(2, '0')}.csv")
        val header = "日期,状态,班次,进入时间,离开时间,实际在岗分钟,最终工时分钟,节假日,备注\n"
        val rows = records.joinToString("\n") { r ->
            listOf(
                r.date.toString(),
                r.status,
                r.shift ?: "",
                r.startText ?: "",
                r.endText ?: "",
                r.actualMinutes?.toString() ?: "",
                r.finalMinutes.toString(),
                r.holidayName ?: "",
                r.note ?: ""
            ).joinToString(",") { csvCell(it) }
        }
        file.writeText("\uFEFF" + header + rows, Charsets.UTF_8)
        return file
    }

    fun exportExcel(context: Context, month: YearMonth, records: List<UiDayRecord>): File {
        val dir = exportDir(context, "Export")
        val file = File(dir, "WorkTime_${month.year}_${month.monthValue.toString().padStart(2, '0')}.xls")
        val total = records.sumOf { it.finalMinutes }
        val html = buildString {
            append("<html><head><meta charset=\"utf-8\"></head><body>")
            append("<h2>${month.year}年${month.monthValue}月工时统计</h2>")
            append("<p>总工时：${formatMinutes(total)}；工作天数：${records.count { it.finalMinutes > 0 }}天</p>")
            append("<table border=\"1\"><tr><th>日期</th><th>状态</th><th>班次</th><th>进入时间</th><th>离开时间</th><th>实际在岗</th><th>最终工时</th><th>节假日</th><th>备注</th></tr>")
            records.forEach { r -> append("<tr><td>${r.date}</td><td>${escapeHtml(r.status)}</td><td>${escapeHtml(r.shift ?: "")}</td><td>${escapeHtml(r.startText ?: "")}</td><td>${escapeHtml(r.endText ?: "")}</td><td>${r.actualMinutes?.let { formatMinutes(it) } ?: ""}</td><td>${formatMinutes(r.finalMinutes)}</td><td>${escapeHtml(r.holidayName ?: "")}</td><td>${escapeHtml(r.note ?: "")}</td></tr>") }
            append("</table></body></html>")
        }
        file.writeText(html, Charsets.UTF_8)
        return file
    }

    fun exportBackupJson(context: Context, month: YearMonth, records: List<UiDayRecord>, settings: UserSettingsEntity? = null): File {
        val dir = exportDir(context, "Backup")
        val file = File(dir, "WorkTimeBackup_${month.year}_${month.monthValue.toString().padStart(2, '0')}.json")
        val body = buildString {
            append("{\n  \"version\": 1,\n  \"month\": \"$month\"")
            if (settings != null) {
                append(",\n  \"settings\": {")
                append("\"companyLat\":${settings.companyLat?.toString() ?: "null"},")
                append("\"companyLng\":${settings.companyLng?.toString() ?: "null"},")
                append("\"companyRadiusMeters\":${settings.companyRadiusMeters},")
                append("\"homeLat\":${settings.homeLat?.toString() ?: "null"},")
                append("\"homeLng\":${settings.homeLng?.toString() ?: "null"},")
                append("\"homeRadiusMeters\":${settings.homeRadiusMeters},")
                append("\"workStartMinutes\":${settings.workStartMinutes},")
                append("\"workEndMinutes\":${settings.workEndMinutes},")
                append("\"hasDefaultHours\":${settings.hasDefaultHours},")
                append("\"defaultWorkMinutes\":${settings.defaultWorkMinutes?.toString() ?: "null"},")
                append("\"restDeductionMinutes\":${settings.restDeductionMinutes},")
                append("\"outsideThresholdMinutes\":${settings.outsideThresholdMinutes},")
                append("\"leaveCompanyConfirmMinutes\":${settings.leaveCompanyConfirmMinutes},")
                append("\"earlyLeaveToleranceMinutes\":${settings.earlyLeaveToleranceMinutes},")
                append("\"notificationEnabled\":${settings.notificationEnabled},")
                append("\"onboardingDone\":${settings.onboardingDone}")
                append("}")
            }
            append(",\n  \"holidays\": [\n")
            records.mapNotNull { r -> r.holidayName?.let { r.date.toString() to it } }.forEachIndexed { index, (date, name) ->
                append("    {\"date\":\"$date\",\"name\":\"${escapeJson(name)}\",\"type\":\"HOLIDAY\"}")
                if (index != records.mapNotNull { it.holidayName }.lastIndex) append(",")
                append("\n")
            }
            append("  ],\n  \"records\": [\n")
            records.forEachIndexed { index, r ->
                append("    {\"date\":\"${r.date}\",\"status\":\"${escapeJson(r.status)}\",\"shift\":\"${escapeJson(r.shift ?: "")}\",\"finalMinutes\":${r.finalMinutes},\"note\":\"${escapeJson(r.note ?: "")}\"}")
                if (index != records.lastIndex) append(",")
                append("\n")
            }
            append("  ]\n}\n")
        }
        file.writeText(body, Charsets.UTF_8)
        return file
    }

    fun exportPdfReport(context: Context, month: YearMonth, records: List<UiDayRecord>): File {
        val dir = exportDir(context, "PDF")
        val file = File(dir, "WorkTimeReport_${month.year}_${month.monthValue.toString().padStart(2, '0')}.pdf")
        val document = PdfDocument()
        val paint = Paint().apply { textSize = 12f; isAntiAlias = true }
        val titlePaint = Paint().apply { textSize = 20f; isFakeBoldText = true; isAntiAlias = true }
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        var y = 44f
        fun newPage() {
            document.finishPage(page)
            pageNumber += 1
            page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            y = 44f
        }
        page.canvas.drawText("${month.year}年${month.monthValue}月工时记录", 40f, y, titlePaint)
        y += 34f
        val total = records.sumOf { it.finalMinutes }
        page.canvas.drawText("总工时：${formatMinutes(total)}    工作天数：${records.count { it.finalMinutes > 0 }}天", 40f, y, paint)
        y += 28f
        page.canvas.drawText("日期        状态      班次      进入       离开       实际      计入      节假日", 40f, y, paint)
        y += 18f
        records.forEach { r ->
            if (y > 800f) newPage()
            page.canvas.drawText("${r.date}  ${r.status.padEnd(4)}  ${(r.shift ?: "").padEnd(4)}  ${(r.startText ?: "--").padEnd(8)}  ${(r.endText ?: "--").padEnd(8)}  ${(r.actualMinutes?.let { formatMinutes(it) } ?: "--").padEnd(7)}  ${formatMinutes(r.finalMinutes).padEnd(7)}  ${r.holidayName ?: ""}", 40f, y, paint)
            y += 18f
        }
        document.finishPage(page)
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return file
    }

    fun restoreBackupJsonText(json: String): List<RestoredRecord> {
        return restoreFullBackupJsonText(json).records
    }

    fun restoreFullBackupJsonText(json: String): RestoredBackup {
        val root = JSONObject(json)
        val records = root.getJSONArray("records")
        val restoredRecords = (0 until records.length()).map { index ->
            val item = records.getJSONObject(index)
            RestoredRecord(
                date = item.getString("date"),
                status = item.optString("status", ""),
                shift = item.optString("shift", "").ifBlank { null },
                finalMinutes = item.optInt("finalMinutes", 0),
                note = item.optString("note", "").ifBlank { null }
            )
        }
        val restoredSettings = root.optJSONObject("settings")?.let { s ->
            RestoredSettings(
                companyLat = s.optNullableDouble("companyLat"),
                companyLng = s.optNullableDouble("companyLng"),
                companyRadiusMeters = s.optInt("companyRadiusMeters", 150),
                homeLat = s.optNullableDouble("homeLat"),
                homeLng = s.optNullableDouble("homeLng"),
                homeRadiusMeters = s.optInt("homeRadiusMeters", 150),
                workStartMinutes = s.optInt("workStartMinutes", 9 * 60),
                workEndMinutes = s.optInt("workEndMinutes", 21 * 60),
                hasDefaultHours = s.optBoolean("hasDefaultHours", false),
                defaultWorkMinutes = if (s.isNull("defaultWorkMinutes")) null else s.optInt("defaultWorkMinutes"),
                restDeductionMinutes = s.optInt("restDeductionMinutes", 60),
                outsideThresholdMinutes = s.optInt("outsideThresholdMinutes", 120),
                leaveCompanyConfirmMinutes = s.optInt("leaveCompanyConfirmMinutes", 60),
                earlyLeaveToleranceMinutes = s.optInt("earlyLeaveToleranceMinutes", 3),
                notificationEnabled = s.optBoolean("notificationEnabled", true),
                onboardingDone = s.optBoolean("onboardingDone", true)
            )
        }
        return RestoredBackup(restoredSettings, restoredRecords)
    }

    data class RestoredBackup(val settings: RestoredSettings?, val records: List<RestoredRecord>)
    data class RestoredSettings(
        val companyLat: Double?,
        val companyLng: Double?,
        val companyRadiusMeters: Int,
        val homeLat: Double?,
        val homeLng: Double?,
        val homeRadiusMeters: Int,
        val workStartMinutes: Int,
        val workEndMinutes: Int,
        val hasDefaultHours: Boolean,
        val defaultWorkMinutes: Int?,
        val restDeductionMinutes: Int,
        val outsideThresholdMinutes: Int,
        val leaveCompanyConfirmMinutes: Int,
        val earlyLeaveToleranceMinutes: Int,
        val notificationEnabled: Boolean,
        val onboardingDone: Boolean
    )
    data class RestoredRecord(val date: String, val status: String, val shift: String?, val finalMinutes: Int, val note: String?)

    private fun exportDir(context: Context, child: String): File {
        val docs = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        return File(docs, "WorkTimeTracker/$child").apply { mkdirs() }
    }

    private fun formatMinutes(minutes: Int): String = if (minutes <= 0) "0小时" else "${minutes / 60}小时${if (minutes % 60 == 0) "" else "${minutes % 60}分"}"
    private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
    private fun escapeJson(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun JSONObject.optNullableDouble(name: String): Double? = if (isNull(name)) null else optDouble(name)
}

