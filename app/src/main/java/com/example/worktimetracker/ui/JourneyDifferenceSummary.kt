package com.example.worktimetracker.ui

data class JourneyDifferenceSummary(
    val total: Int,
    val matched: Int,
    val expectedSplit: Int,
    val needsReview: Int
) {
    companion object {
        fun fromContents(contents: List<String>): JourneyDifferenceSummary {
            val types = contents.mapNotNull { line ->
                Regex("differenceType=([A-Z_]+)").find(line)?.groupValues?.getOrNull(1)
            }
            val matched = types.count { it == "NONE" }
            val expected = types.count { it == "EXPECTED_SPLIT" }
            return JourneyDifferenceSummary(types.size, matched, expected, types.size - matched - expected)
        }
    }
}
