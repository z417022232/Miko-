package com.example.worktimetracker.ui

data class CompanyCalibrationProposal(
    val latitude: Double,
    val longitude: Double,
    val stableRadiusMeters: Int,
    val offsetMeters: Int,
    val acceptedCount: Int
)
