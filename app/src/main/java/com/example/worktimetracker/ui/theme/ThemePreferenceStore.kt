package com.example.worktimetracker.ui.theme

import android.content.Context

/** 主题模式偏好。存 SharedPreferences，不涉及数据库迁移。 */
class ThemePreferenceStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(): ThemeMode = ThemeMode.parse(prefs.getString(KEY_MODE, null))

    fun setMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        // 名字里不要写 .xml 后缀，系统会自动补（写 "display_theme.xml" 会变成 display_theme.xml.xml）。
        private const val PREFS = "display_theme"
        private const val KEY_MODE = "theme_mode"
    }
}
