package com.example.worktimetracker.location.service

class LocationFailureLimiter(private val windowMillis: Long = 15 * 60_000L) {
    enum class Action { LOG, NOTIFY_AND_THROTTLE, SUPPRESS }
    private var key: String? = null
    private var firstAt = 0L
    private var count = 0

    fun record(errorKey: String, now: Long): Action {
        if (key != errorKey || now - firstAt >= windowMillis) {
            key = errorKey; firstAt = now; count = 1
            return Action.LOG
        }
        count++
        return if (count == 5) Action.NOTIFY_AND_THROTTLE else Action.SUPPRESS
    }

    fun success() { key = null; firstAt = 0; count = 0 }
}
