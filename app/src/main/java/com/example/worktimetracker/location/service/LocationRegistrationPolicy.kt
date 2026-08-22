package com.example.worktimetracker.location.service

object LocationRegistrationPolicy {
    data class Decision(val reconfigure: Boolean, val removeExisting: Boolean, val delayMillis: Long)
    enum class UserVisibleAction { NONE, START_SERVICE }

    fun intervalChange(current: Long, requested: Long): Decision = when {
        current == requested -> Decision(false, false, 0)
        requested < current -> Decision(true, false, 0)
        else -> Decision(true, false, 30_000L)
    }

    fun onUserVisible(serviceAlreadyCreated: Boolean): UserVisibleAction =
        if (serviceAlreadyCreated) UserVisibleAction.NONE else UserVisibleAction.START_SERVICE
}
