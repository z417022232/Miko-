package com.example.worktimetracker

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentRecoveryContractTest {
    @Test fun systemLocationNotificationOpensSystemLocationSettings() {
        val source = source("app/src/main/java/com/example/worktimetracker/location/recovery/RecoveryNotifier.kt")
        assertTrue(source.contains("Settings.ACTION_LOCATION_SOURCE_SETTINGS"))
    }

    @Test fun geofenceRegistrationIsSerializedAndPersistsRegisteredIds() {
        val source = source("app/src/main/java/com/example/worktimetracker/location/recovery/GeofenceRecovery.kt")
        assertTrue(source.contains("registrationMutex.withLock"))
        assertTrue(source.contains("registeredSiteIds"))
    }

    @Test fun candidateCleanupIsScopedPerActivePlace() {
        val source = source("app/src/main/java/com/example/worktimetracker/location/service/AnchorLearningService.kt")
        assertTrue(source.contains("deleteCandidatesBeforeForPlace"))
    }

    private fun source(relative: String): String {
        var dir = File(System.getProperty("user.dir"))
        repeat(5) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到源码：$relative")
    }
}
