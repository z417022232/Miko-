package com.example.worktimetracker.location.service

class LocationFixGate(private val maxAgeMillis: Long) {
    private val latestByProvider = mutableMapOf<String, Long>()

    @Synchronized
    fun shouldAccept(provider: String?, fixTimeMillis: Long, receivedAtMillis: Long): Boolean {
        val key = provider ?: "unknown"
        if (fixTimeMillis <= 0L || receivedAtMillis - fixTimeMillis > maxAgeMillis) return false
        val latest = latestByProvider[key]
        if (latest != null && fixTimeMillis <= latest) return false
        latestByProvider[key] = fixTimeMillis
        return true
    }
}
