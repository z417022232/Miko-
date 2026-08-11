package com.example.worktimetracker.ui.app

class AppForegroundReset {
    private var foreground = false

    fun onStart(): Boolean {
        if (foreground) return false
        foreground = true
        return true
    }

    fun onStop() {
        foreground = false
    }
}
