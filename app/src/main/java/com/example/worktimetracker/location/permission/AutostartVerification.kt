package com.example.worktimetracker.location.permission

import android.content.Context

enum class AutostartState { UNKNOWN, USER_CONFIRMED, BOOT_VERIFIED }

object AutostartVerificationPolicy {
    fun userConfirmed(current: AutostartState, confirmed: Boolean): AutostartState = when {
        !confirmed -> current
        current == AutostartState.BOOT_VERIFIED -> current
        else -> AutostartState.USER_CONFIRMED
    }

    fun bootRecovery(current: AutostartState, succeeded: Boolean): AutostartState =
        if (succeeded) AutostartState.BOOT_VERIFIED else current
}

class AutostartVerificationStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(): AutostartState = runCatching {
        AutostartState.valueOf(preferences.getString(KEY_STATE, null).orEmpty())
    }.getOrDefault(AutostartState.UNKNOWN)

    fun set(state: AutostartState) {
        preferences.edit().putString(KEY_STATE, state.name).apply()
    }

    fun confirmByUser() {
        set(AutostartVerificationPolicy.userConfirmed(get(), true))
    }

    fun verifyBootRecovery() {
        set(AutostartVerificationPolicy.bootRecovery(get(), true))
    }

    companion object {
        private const val PREFS = "worktime_autostart"
        private const val KEY_STATE = "state"
    }
}
