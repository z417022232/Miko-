package com.example.worktimetracker

import com.example.worktimetracker.export.ExportManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportManagerTest {
    @Test fun restoreBackupJsonParsesRecords() {
        val json = """
            {"version":1,"month":"2026-07","records":[
              {"date":"2026-07-22","status":"手动","shift":"夜班","finalMinutes":510,"note":"测试"},
              {"date":"2026-07-23","status":"休息","shift":"","finalMinutes":0,"note":""}
            ]}
        """.trimIndent()
        val records = ExportManager.restoreBackupJsonText(json)
        assertEquals(2, records.size)
        assertEquals("2026-07-22", records[0].date)
        assertEquals(510, records[0].finalMinutes)
        assertEquals("夜班", records[0].shift)
        assertEquals(null, records[1].shift)
    }

    @Test fun restoreBackupJsonParsesSettings() {
        val json = """
            {"version":1,"month":"2026-07",
             "settings":{
               "companyLat":30.1,"companyLng":120.2,"companyRadiusMeters":200,
               "homeLat":31.1,"homeLng":121.2,"homeRadiusMeters":180,
               "workStartMinutes":540,"workEndMinutes":1260,
               "hasDefaultHours":true,"defaultWorkMinutes":720,
               "restDeductionMinutes":45,"outsideThresholdMinutes":150,
               "leaveCompanyConfirmMinutes":75,"earlyLeaveToleranceMinutes":3,
               "notificationEnabled":true
             },
             "holidays":[{"date":"2026-10-01","name":"国庆节","type":"HOLIDAY"}],
             "records":[]}
        """.trimIndent()
        val backup = ExportManager.restoreFullBackupJsonText(json)
        assertEquals(30.1, backup.settings?.companyLat)
        assertEquals(720, backup.settings?.defaultWorkMinutes)
        assertEquals(150, backup.settings?.outsideThresholdMinutes)
        assertEquals(0, backup.records.size)
    }
}
