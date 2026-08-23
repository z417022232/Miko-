package com.example.worktimetracker.location.service

class ProviderAlertAggregator(private val notifyDelayMillis: Long = 60_000L) {
    private val disabled = linkedSetOf<String>()
    private var firstDisabledAt: Long? = null
    private var notified = false

    @Synchronized fun disabled(provider: String, now: Long) {
        if (disabled.isEmpty()) firstDisabledAt = now
        disabled += provider
    }

    @Synchronized fun disabledProviders(): Set<String> = disabled.toSet()
    @Synchronized fun wasGlobalNotified(): Boolean = notified

    @Synchronized fun shouldNotifyGlobal(locationEnabled: Boolean, now: Long): Boolean {
        val first = firstDisabledAt ?: return false
        if (locationEnabled || notified || now - first < notifyDelayMillis) return false
        notified = true
        return true
    }

    @Synchronized fun recovered(locationEnabled: Boolean, now: Long): Boolean {
        if (!locationEnabled || disabled.isEmpty()) return false
        disabled.clear()
        firstDisabledAt = null
        notified = false
        return true
    }
}
