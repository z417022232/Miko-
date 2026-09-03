package com.example.worktimetracker.location.service

/**
 * 统一的来源注册状态：按来源保存当前配置、注册状态、最后回调时间与恢复基线标记。
 *
 * - 配置相同且已注册时 begin 返回 false，防止重复注册造成定位风暴；
 * - 配置变化或 invalidate 后 begin 返回 true，调用方须先统一 removeUpdates 再注册一次；
 * - Provider 恢复后的第一次回调只用于建立基线，mayEmitEvidence 返回 false，第二次开始返回 true。
 */
class SourceRegistrationState {

    private class SourceState(
        var intervalMillis: Long? = null,
        var registered: Boolean = false,
        var lastCallbackAt: Long? = null,
        var recoveryBaselinePending: Boolean = false
    )

    private val sources = mutableMapOf<String, SourceState>()

    /** 返回 true 表示需要（重新）注册该来源；false 表示配置未变且已注册。 */
    @Synchronized
    fun begin(source: String, intervalMillis: Long): Boolean {
        val state = sources.getOrPut(source) { SourceState() }
        if (state.registered && state.intervalMillis == intervalMillis) return false
        state.intervalMillis = intervalMillis
        state.registered = true
        return true
    }

    /** 标记来源未注册（Provider 状态变化、watchdog 判定陈旧等），下次 begin 必须重新注册。 */
    @Synchronized
    fun invalidate(source: String) {
        sources.getOrPut(source) { SourceState() }.registered = false
    }

    @Synchronized
    fun isRegistered(source: String): Boolean =
        sources[source]?.registered == true

    @Synchronized
    fun currentInterval(source: String): Long? = sources[source]?.intervalMillis

    /** Provider 恢复：下一次回调仅作为基线，不产生证据。 */
    @Synchronized
    fun providerRecovered(source: String) {
        sources.getOrPut(source) { SourceState() }.recoveryBaselinePending = true
    }

    /** 恢复后的第一次回调返回 false（仅基线），之后返回 true。 */
    @Synchronized
    fun mayEmitEvidence(source: String): Boolean {
        val state = sources.getOrPut(source) { SourceState() }
        if (!state.recoveryBaselinePending) return true
        state.recoveryBaselinePending = false
        return false
    }

    @Synchronized
    fun recordCallback(source: String, atMillis: Long) {
        sources.getOrPut(source) { SourceState() }.lastCallbackAt = atMillis
    }

    @Synchronized
    fun lastCallback(source: String): Long? = sources[source]?.lastCallbackAt
}
