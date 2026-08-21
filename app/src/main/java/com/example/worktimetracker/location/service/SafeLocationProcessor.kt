package com.example.worktimetracker.location.service

class SafeLocationProcessor<T> {
    suspend fun process(value: T, block: suspend (T) -> Unit): Throwable? = try {
        block(value)
        null
    } catch (error: Throwable) {
        error
    }
}
