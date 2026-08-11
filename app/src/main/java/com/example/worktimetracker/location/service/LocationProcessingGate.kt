package com.example.worktimetracker.location.service

class LocationProcessingGate<T> {
    data class Pending<T>(val provider: String, val time: Long, val value: T)
    private val pendingByProvider = mutableMapOf<String, Pending<T>>()

    @Synchronized
    fun offer(provider: String, time: Long, value: T) {
        val existing = pendingByProvider[provider]
        if (existing == null || time > existing.time) pendingByProvider[provider] = Pending(provider, time, value)
    }

    @Synchronized
    fun takePending(): Pending<T>? {
        val next = pendingByProvider.values.minByOrNull { it.time } ?: return null
        pendingByProvider.remove(next.provider)
        return next
    }
}

class RevisionCache<T> {
    private var revision: Long? = null
    private var value: T? = null

    @Synchronized fun put(revision: Long, value: T) {
        this.revision = revision
        this.value = value
    }

    @Synchronized fun get(revision: Long): T? = if (this.revision == revision) value else null
    @Synchronized fun clear() { revision = null; value = null }
}
