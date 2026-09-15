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

/**
 * v12 → v13（工资条两张表）迁移护栏。
 *
 * ⚠️ 本仓库**不跑** connectedAndroidTest（会装测试包并卸载，清空真机用户数据，见 skill
 * `android-worktracker-delivery`）。此文件用于在安全环境手动执行，或用真实的 Room 校验器
 * 证明「迁移后 `work_records` / `monthly_salaries` 条数与数值不变、新表可用」。
 */
@RunWith(AndroidJUnit4::class)
class Migration12To13Test {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationKeepsExistingDataAndCreatesSlipTables() {
        helper.createDatabase(TEST_DB, 12).apply {
            execSQL("INSERT INTO work_records(id,workDate,status,shift,startTime,endTime,actualMinutes,finalMinutes,isManual,needsReview,note,createdAt,updatedAt,homeDepartureTime,homeArrivalTime,manualFieldsMask) VALUES(1,'2026-09-01','COMPLETED','NIGHT_SHIFT',100,700,600,660,0,0,'',1,1,90,800,0)")
            // 计薪月 2026-07，实发 7243.34（用户原始录入，迁移后必须一字不改）
            execSQL("INSERT INTO monthly_salaries(month,netSalaryCents,updatedAt,payrollMonth,paymentDate) VALUES('2026-08',724334,1,'2026-07','2026-08-15')")
            execSQL("INSERT INTO manual_overrides(id,recordId,oldValue,newValue,reason,modifiedAt) VALUES(1,1,'600','660','late fix',1)")
            close()
        }

        helper.runMigrationsAndValidate(TEST_DB, 13, true, WorkTimeApplication.MIGRATION_12_13).use { db ->
            // ---------- 原有数据一字不改 ----------
            db.query("SELECT COUNT(*) FROM work_records").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }
            db.query("SELECT netSalaryCents, payrollMonth FROM monthly_salaries").use { c ->
                c.moveToFirst()
                assertEquals(724334L, c.getLong(0))
                assertEquals("2026-07", c.getString(1))
            }
            db.query("SELECT COUNT(*) FROM manual_overrides").use { c ->
                c.moveToFirst(); assertEquals(1, c.getInt(0))
            }

            // ---------- 新表由迁移灌好历史表头 ----------
            db.query("SELECT status, declaredGrossCents, declaredNetCents, revision FROM salary_slips WHERE payrollMonth='2026-07'").use { c ->
                c.moveToFirst()
                assertEquals("PENDING_REVIEW", c.getString(0))   // 已知疑点月
                assertEquals(871183L, c.getLong(1))              // 口径文档 §4 的实际应发
                assertEquals(724334L, c.getLong(2))              // ← 原样取自 monthly_salaries
                assertEquals(1, c.getInt(3))
            }
            // 历史月的**分项**由 Kotlin（SlipDraftSeeder）在启动时补灌，迁移本身不建分项
            db.query("SELECT COUNT(*) FROM salary_slip_items").use { c ->
                c.moveToFirst(); assertEquals(0, c.getInt(0))
            }

            // ---------- 分项表可用（amountCents 可空 = 未填写） ----------
            db.execSQL(
                "INSERT INTO salary_slip_items(payrollMonth,itemKey,rawLabel,amountCents,stage,nature,rawText,needsReview,note,updatedAt) " +
                    "VALUES('2026-07','PERFORMANCE_PAY','绩效工资',NULL,'INCOME','FLOATING',NULL,0,NULL,1)"
            )
            db.query("SELECT amountCents FROM salary_slip_items WHERE payrollMonth='2026-07' AND itemKey='PERFORMANCE_PAY'").use { c ->
                c.moveToFirst()
                assertEquals(true, c.isNull(0))   // 未填写 = NULL，不是 0
            }
        }
    }

    private companion object { const val TEST_DB = "migration-12-13" }
}
