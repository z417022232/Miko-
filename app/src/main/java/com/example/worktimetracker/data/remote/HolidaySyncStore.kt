package com.example.worktimetracker.data.remote

import android.content.Context

/**
 * 节假日同步元信息（最近同步时间 / 数据来源 / 失败原因 / 覆盖年份）。
 *
 * 存 SharedPreferences 而非 Room：这是"同步状态"不是业务数据，
 * 且这样**不需要数据库迁移**（holidays 表结构保持 v10 原样）。
 */
class HolidaySyncStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastSuccessAt(): Long? = prefs.getLong(KEY_SUCCESS_AT, 0L).takeIf { it > 0L }

    fun lastAttemptAt(): Long? = prefs.getLong(KEY_ATTEMPT_AT, 0L).takeIf { it > 0L }

    fun lastHost(): String? = prefs.getString(KEY_HOST, null)

    fun lastError(): String? = prefs.getString(KEY_ERROR, null)

    fun coveredYears(): Set<Int> =
        prefs.getStringSet(KEY_YEARS, emptySet())
            .orEmpty()
            .mapNotNull { it.toIntOrNull() }
            .toSet()

    fun lastPaper(): String? = prefs.getString(KEY_PAPER, null)

    fun recordSuccess(atMillis: Long, host: String, years: Set<Int>) {
        prefs.edit()
            .putLong(KEY_SUCCESS_AT, atMillis)
            .putLong(KEY_ATTEMPT_AT, atMillis)
            .putString(KEY_HOST, host)
            .putStringSet(KEY_YEARS, years.map { it.toString() }.toSet())
            .remove(KEY_ERROR)
            .apply()
    }

    fun recordFailure(atMillis: Long, message: String) {
        prefs.edit()
            .putLong(KEY_ATTEMPT_AT, atMillis)
            .putString(KEY_ERROR, message)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        // 名字里**不要写 .xml 后缀**：系统会自动补，写 "holiday_sync.xml" 会在设备上变成
        // holiday_sync.xml.xml。重命名后旧的同步元信息作废，下次启动会自动重新拉取一次。
        private const val PREFS = "holiday_sync"
        private const val KEY_SUCCESS_AT = "success_at"
        private const val KEY_ATTEMPT_AT = "attempt_at"
        private const val KEY_HOST = "host"
        private const val KEY_ERROR = "error"
        private const val KEY_YEARS = "years"
        private const val KEY_PAPER = "paper"
    }
}
