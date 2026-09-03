package com.example.worktimetracker.location.service

/**
 * 证据连续性策略：相邻可靠证据间隔超过 20 分钟视为连续性中断。
 * 中断后候选到达必须重新开始计数，但已确认的 WORKING 会话本身不被丢弃。
 */
class EvidenceContinuityPolicy(private val maxGapMillis: Long = DEFAULT_MAX_GAP_MILLIS) {

    fun isContinuous(previousTime: Long?, currentTime: Long): Boolean =
        previousTime == null || currentTime >= previousTime && currentTime - previousTime <= maxGapMillis

    fun breaks(previousTime: Long?, currentTime: Long): Boolean = !isContinuous(previousTime, currentTime)

    companion object {
        const val DEFAULT_MAX_GAP_MILLIS = 20 * 60_000L
    }
}
