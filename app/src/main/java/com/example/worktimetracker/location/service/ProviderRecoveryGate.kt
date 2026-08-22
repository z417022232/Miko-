package com.example.worktimetracker.location.service

class ProviderRecoveryGate {
    private val baselineOnly = mutableSetOf<String>()
    @Synchronized fun providerEnabled(provider: String) { baselineOnly += provider }
    @Synchronized fun shouldProcess(provider: String): Boolean = !baselineOnly.remove(provider)
}
