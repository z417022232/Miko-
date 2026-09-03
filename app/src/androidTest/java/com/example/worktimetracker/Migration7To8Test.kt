package com.example.worktimetracker

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.worktimetracker.data.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration7To8Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationPreservesExistingDataAndCreatesEvidenceTables() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL("INSERT INTO work_records(id,workDate,status,shift,startTime,endTime,actualMinutes,finalMinutes,isManual,needsReview,note,createdAt,updatedAt,homeDepartureTime,homeArrivalTime,manualFieldsMask) VALUES(1,'2026-09-01','COMPLETED','NIGHT_SHIFT',100,700,600,660,0,0,'',1,1,90,800,0)")
            execSQL("INSERT INTO monthly_salaries(month,netSalaryCents,updatedAt,payrollMonth,paymentDate) VALUES('2026-09',123456,1,'2026-08','2026-09-15')")
            execSQL("INSERT INTO manual_overrides(id,recordId,oldValue,newValue,reason,modifiedAt) VALUES(1,1,'600','660','late fix',1)")
            execSQL("INSERT INTO work_state(id,currentState,updatedAt,tempLeaveStart,stableCompanyCount,stableHomeCount,movingAwayCount) VALUES(1,'WORKING',1,NULL,0,0,0)")
            close()
        }
        helper.runMigrationsAndValidate(TEST_DB, 8, true, WorkTimeApplication.MIGRATION_7_8).use { db ->
            db.query("SELECT COUNT(*) FROM work_records").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT netSalaryCents FROM monthly_salaries").use { cursor ->
                cursor.moveToFirst()
                assertEquals(123456L, cursor.getLong(0))
            }
            db.query("SELECT COUNT(*) FROM manual_overrides").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            db.query("SELECT currentState FROM work_state").use { cursor ->
                cursor.moveToFirst()
                assertEquals("WORKING", cursor.getString(0))
            }
            db.execSQL("INSERT INTO location_health(name,lastCallbackAt,lastSuccessAt,registered,recoveryCount,lastFailure) VALUES('gnss',1,1,1,0,NULL)")
            db.query("SELECT COUNT(*) FROM location_health").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            db.execSQL("INSERT INTO environment_fingerprints(place,source,identifierHash,observationCount,distinctDayCount,lastObservedDay,lastObservedAt,minSignal,maxSignal,level,discriminative) VALUES('HOME','WIFI','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',6,3,'2026-09-01',1,-80,-50,'STABLE',1)")
            db.query("SELECT COUNT(*) FROM environment_fingerprints").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            db.execSQL("INSERT INTO evidence_observations(eventTime,receivedAt,source,quality,placeHint,identifierHash,signal,usedForEvent) VALUES(1,1,'WIFI',0.9,'HOME',NULL,-60,0)")
            db.query("SELECT COUNT(*) FROM evidence_observations").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    private companion object { const val TEST_DB = "migration-7-8" }
}
