package com.example.worktimetracker

import android.app.Application
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.worktimetracker.data.database.AppDatabase
import com.example.worktimetracker.domain.evidence.FusedStatusSnapshot
import com.example.worktimetracker.location.recovery.ServiceRecovery
import com.example.worktimetracker.location.recovery.GeofenceRecovery
import com.example.worktimetracker.data.HistoricalRecordRepair
import com.example.worktimetracker.data.SalarySlipDraftRepair
import com.example.worktimetracker.domain.payroll.PayRateSeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class WorkTimeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceRecovery.schedule(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            HistoricalRecordRepair.runOnce(this@WorkTimeApplication)
            // 工资条历史草稿的**分项**（DB v13）：表头由迁移灌，分项留在 Kotlin
            // （SlipDraftSeeder）保证与 PayRateSeed 同源，所以在这里补灌一次。
            SalarySlipDraftRepair.runOnce(this@WorkTimeApplication)
            database.userSettingsDao().getSettings()?.let { GeofenceRecovery.register(this@WorkTimeApplication, it) }
        }
    }

    /**
     * 融合状态实时通道（方案十 UI）：前台服务每次融合后覆盖最新快照，
     * ViewModel/UI 订阅展示"当前判断"。进程内单例，服务与界面同进程直接共享。
     */
    val fusedStatus = MutableStateFlow<FusedStatusSnapshot?>(null)

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "work_time_tracker.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                MIGRATION_11_12, MIGRATION_12_13
            )
            .build()
    }

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE user_settings ADD COLUMN onboardingDone INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `monthly_salaries` (`month` TEXT NOT NULL, `netSalaryCents` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`month`))"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work_state ADD COLUMN tempLeaveStart INTEGER")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE monthly_salaries ADD COLUMN payrollMonth TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE monthly_salaries ADD COLUMN paymentDate TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "UPDATE monthly_salaries SET " +
                        "payrollMonth = strftime('%Y-%m', date(month || '-01', '-1 month')), " +
                        "paymentDate = month || '-15'"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work_state ADD COLUMN confirmedDepartureTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN homeDepartureTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN homeArrivalTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN lastGpsFixTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN lastNetworkFixTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN lastCompanyDistanceMeters REAL")
                db.execSQL("ALTER TABLE work_records ADD COLUMN homeDepartureTime INTEGER")
                db.execSQL("ALTER TABLE work_records ADD COLUMN homeArrivalTime INTEGER")
                db.execSQL(
                    "DELETE FROM location_logs WHERE id NOT IN (" +
                        "SELECT MIN(id) FROM location_logs GROUP BY (time / 60000), provider, " +
                        "latitude, longitude, accuracyMeters, locationType)"
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE work_records ADD COLUMN manualFieldsMask INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE work_state ADD COLUMN sessionId TEXT")
                db.execSQL("ALTER TABLE work_state ADD COLUMN candidateHomeDepartureTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN candidateCompanyArrivalTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN candidateCompanyDepartureTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN candidateHomeArrivalTime INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN companyArrivalConfirmedAt INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN companyDepartureConfirmedAt INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN homeArrivalConfirmedAt INTEGER")
                db.execSQL("ALTER TABLE work_state ADD COLUMN stableCompanyCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE work_state ADD COLUMN stableHomeCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE work_state ADD COLUMN movingAwayCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE work_state SET candidateCompanyDepartureTime = tempLeaveStart WHERE tempLeaveStart IS NOT NULL")
                db.execSQL("UPDATE work_state SET homeArrivalTime = NULL, confirmedDepartureTime = NULL WHERE currentState IN ('LEAVING_HOME','NEAR_COMPANY','WORKING','TEMP_LEAVE')")
                db.execSQL(
                    "UPDATE work_records SET manualFieldsMask = 33" +
                        " + CASE WHEN startTime IS NOT NULL THEN 2 ELSE 0 END" +
                        " + CASE WHEN endTime IS NOT NULL THEN 4 ELSE 0 END" +
                        " + CASE WHEN homeDepartureTime IS NOT NULL THEN 8 ELSE 0 END" +
                        " + CASE WHEN homeArrivalTime IS NOT NULL THEN 16 ELSE 0 END" +
                        " + CASE WHEN note IS NOT NULL THEN 64 ELSE 0 END" +
                        " WHERE isManual = 1"
                )
            }
        }
        /**
         * v11：多地点模型。
         *
         * 结构变更：
         *  - 新表 `sites`（用户声明的地点）与 `site_evidence_sources`（地点已选证据源，只存加盐哈希）
         *  - `user_settings` 加 6 列（时薪 / 采集间隔 / Burst 上限 / 精度档 / 固定休息日 / 节假日来源）
         *  - `work_segments` 加 3 列（时段类型 / 地点 id / 地点名快照）
         *
         * 数据迁移：把旧的单公司 / 单家庭展开成两条 site 记录（公司为主工作地点），
         * 老读数路径继续可用，新界面走 sites 表。INSERT ... SELECT 在无定位的行上不产生记录，
         * 因此全新安装（user_settings 尚无坐标）不会插入空地点。
         */
        /**
         * v12：计薪规则 v2（工资条口径）。
         *
         * 结构变更：
         *  - 新表 `pay_rate_segments` —— 计薪参数的**分段常量**（带生效月），调薪只加一段，
         *    回看历史月份仍是旧数值
         *  - 新表 `monthly_pay_params` —— 每月的浮动参数（绩效系数 / 效益奖金 / 高温 / 补发 / 病假…）
         *
         * ⚠️ **不动** `monthly_salaries` 与 `work_records`：用户已录入的实发工资与工时记录
         *    是唯一权威来源，推算结果永不落库（用户 2026-09-13 明确要求）。
         *
         * 数据迁移：写入出厂分段常量（全部来自工资条 2025-12～2026-07 实测值），
         * 用预编译语句插入，不手工拼 SQL。
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pay_rate_segments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`paramKey` TEXT NOT NULL, `effectiveFrom` TEXT NOT NULL, " +
                        "`value` INTEGER NOT NULL, `note` TEXT, `updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_pay_rate_segments_paramKey_effectiveFrom` " +
                        "ON `pay_rate_segments` (`paramKey`, `effectiveFrom`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `monthly_pay_params` (" +
                        "`payrollMonth` TEXT NOT NULL, `perfCoefficient` TEXT, " +
                        "`perfBaseDeltaCents` INTEGER NOT NULL, `perfAmountCents` INTEGER, " +
                        "`benefitBonusCents` INTEGER NOT NULL, " +
                        "`heatAllowanceCents` INTEGER NOT NULL, " +
                        "`sickPayCents` INTEGER NOT NULL, `backPayCents` INTEGER NOT NULL, " +
                        "`otherAddCents` INTEGER NOT NULL, `socialOverrideCents` INTEGER, " +
                        "`housingFundOverrideCents` INTEGER, `nightShiftsOverride` INTEGER, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`payrollMonth`))"
                )

                val stmt = db.compileStatement(
                    "INSERT OR REPLACE INTO pay_rate_segments " +
                        "(paramKey, effectiveFrom, value, note, updatedAt) VALUES (?, ?, ?, ?, ?)"
                )
                val now = System.currentTimeMillis()
                for (seed in PayRateSeed.segments()) {
                    stmt.clearBindings()
                    stmt.bindString(1, seed.paramKey)
                    stmt.bindString(2, seed.effectiveFrom)
                    stmt.bindLong(3, seed.value)
                    if (seed.note != null) stmt.bindString(4, seed.note) else stmt.bindNull(4)
                    stmt.bindLong(5, now)
                    stmt.executeInsert()
                }
            }
        }

        /**
         * 「计薪月 → 条上应发（分）」历史草稿种子。
         *
         * 来源：`verification/计薪规则v2-工资条口径.md` §4 对账表的「实际应发」列，**原样收录、不做修正**
         * （06 月那 9411.86 就是条上印的值，与各部件加总差 360，正是要靠校验①暴露出来的东西）。
         * 2025-12 与 2026-08 两个月口径文档里没有应发数据 → 保持未填写（null），由用户照条补。
         */
        private val SLIP_GROSS_SEED = listOf(
            "2026-01" to 840_051L,    // 8400.51
            "2026-02" to 758_703L,    // 7587.03
            "2026-03" to 1_084_172L,  // 10841.72
            "2026-04" to 940_862L,    // 9408.62
            "2026-05" to 880_955L,    // 8809.55
            "2026-06" to 941_186L,    // 9411.86（条上值；少打一项 360，保留原数字）
            "2026-07" to 871_183L,    // 8711.83
        )

        /**
         * DB v13「计薪预测与发薪对账」第一步：工资条两张表 + 历史草稿表头。
         *
         * **只建新表**，`monthly_salaries` / `work_records` / `manual_override` /
         * `pay_rate_segments` / `monthly_pay_params` 一律不碰。
         *
         * 历史分项（基本工资、加班工资…那些能由分段常量推出的项）**不在这里写 SQL**，
         * 而是由 `SlipDraftSeeder` 在 Kotlin 侧生成 —— 保证 `PayRateSeed` 改了草稿跟着改，只有一处真相。
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `salary_slips` (" +
                        "`payrollMonth` TEXT NOT NULL, `paymentDate` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `slipAttendDays` INTEGER, `slipNightShifts` INTEGER, " +
                        "`declaredGrossCents` INTEGER, `declaredNetCents` INTEGER, `confirmedAt` INTEGER, " +
                        "`revision` INTEGER NOT NULL, `note` TEXT, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`payrollMonth`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `salary_slip_items` (" +
                        "`payrollMonth` TEXT NOT NULL, `itemKey` TEXT NOT NULL, `rawLabel` TEXT, " +
                        "`amountCents` INTEGER, `stage` TEXT NOT NULL, `nature` TEXT NOT NULL, " +
                        "`rawText` TEXT, `needsReview` INTEGER NOT NULL, `note` TEXT, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`payrollMonth`, `itemKey`))"
                )

                // 历史草稿表头：直接从 monthly_salaries 复制（**只读源，不修改那张表**）
                db.execSQL(
                    "INSERT OR IGNORE INTO salary_slips " +
                        "(payrollMonth, paymentDate, status, declaredNetCents, revision, createdAt, updatedAt) " +
                        "SELECT payrollMonth, paymentDate, 'DRAFT', netSalaryCents, 1, updatedAt, updatedAt " +
                        "FROM monthly_salaries WHERE payrollMonth <> ''"
                )
                for ((payrollMonth, grossCents) in SLIP_GROSS_SEED) {
                    db.execSQL(
                        "UPDATE salary_slips SET declaredGrossCents = " + grossCents +
                            " WHERE payrollMonth = '" + payrollMonth + "'"
                    )
                }
                // 已知有疑点的计薪月 → 待核对（06 月夜班津贴、07 月工龄+病假、08 月工龄）
                db.execSQL(
                    "UPDATE salary_slips SET status = 'PENDING_REVIEW' " +
                        "WHERE payrollMonth IN ('2026-06', '2026-07', '2026-08')"
                )
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sites` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`siteType` TEXT NOT NULL, `latitude` REAL, `longitude` REAL, " +
                        "`radiusMeters` INTEGER NOT NULL, `isPrimary` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, `migrated` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sites_isPrimary` ON `sites` (`isPrimary`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_sites_siteType` ON `sites` (`siteType`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `site_evidence_sources` (" +
                        "`siteId` INTEGER NOT NULL, `sourceType` TEXT NOT NULL, " +
                        "`identifierHash` TEXT NOT NULL, `label` TEXT, `lastSignal` INTEGER, " +
                        "`selectedAt` INTEGER NOT NULL, PRIMARY KEY(`siteId`, `sourceType`, `identifierHash`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_site_evidence_sources_siteId` " +
                        "ON `site_evidence_sources` (`siteId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_site_evidence_sources_sourceType_identifierHash` " +
                        "ON `site_evidence_sources` (`sourceType`, `identifierHash`)"
                )

                db.execSQL("ALTER TABLE user_settings ADD COLUMN hourlyRateCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN samplingIntervalMinutes INTEGER NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN burstCapMinutes INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN locationAccuracyMode TEXT NOT NULL DEFAULT 'BALANCED'")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN restWeekPattern TEXT NOT NULL DEFAULT 'SAT_SUN'")
                db.execSQL("ALTER TABLE user_settings ADD COLUMN holidaySourceMode TEXT NOT NULL DEFAULT 'BUILT_IN'")
                db.execSQL("ALTER TABLE work_segments ADD COLUMN segmentType TEXT NOT NULL DEFAULT 'WORK'")
                db.execSQL("ALTER TABLE work_segments ADD COLUMN siteId INTEGER")
                db.execSQL("ALTER TABLE work_segments ADD COLUMN siteLabel TEXT")

                // 旧「公司」→ 主工作地点
                db.execSQL(
                    "INSERT INTO sites (name, siteType, latitude, longitude, radiusMeters, " +
                        "isPrimary, enabled, migrated, createdAt, updatedAt) " +
                        "SELECT '公司', 'WORK', companyLat, companyLng, companyRadiusMeters, 1, 1, 1, 0, 0 " +
                        "FROM user_settings WHERE id = 1 AND companyLat IS NOT NULL AND companyLng IS NOT NULL"
                )
                // 旧「家」→ 非工作地点
                db.execSQL(
                    "INSERT INTO sites (name, siteType, latitude, longitude, radiusMeters, " +
                        "isPrimary, enabled, migrated, createdAt, updatedAt) " +
                        "SELECT '家', 'NON_WORK', homeLat, homeLng, homeRadiusMeters, 0, 1, 1, 0, 0 " +
                        "FROM user_settings WHERE id = 1 AND homeLat IS NOT NULL AND homeLng IS NOT NULL"
                )
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // A2: needsReview 结构化原因（如 "R3 21:00-21:29 灰区"），UI 直接展示
                db.execSQL("ALTER TABLE work_records ADD COLUMN reviewReason TEXT")
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 证据观察保留定位来源与原始精度，便于区分 GPS 与 Network Location 排查误判
                db.execSQL("ALTER TABLE evidence_observations ADD COLUMN provider TEXT")
                db.execSQL("ALTER TABLE evidence_observations ADD COLUMN accuracyMeters REAL")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `environment_fingerprints` (`place` TEXT NOT NULL, " +
                        "`source` TEXT NOT NULL, `identifierHash` TEXT NOT NULL, `observationCount` INTEGER NOT NULL, " +
                        "`distinctDayCount` INTEGER NOT NULL, `lastObservedDay` TEXT NOT NULL, " +
                        "`lastObservedAt` INTEGER NOT NULL, `minSignal` INTEGER NOT NULL, `maxSignal` INTEGER NOT NULL, " +
                        "`level` TEXT NOT NULL, `discriminative` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`place`, `source`, `identifierHash`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_environment_fingerprints_lastObservedAt` " +
                        "ON `environment_fingerprints` (`lastObservedAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_environment_fingerprints_place_source_level` " +
                        "ON `environment_fingerprints` (`place`, `source`, `level`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `evidence_observations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`eventTime` INTEGER NOT NULL, `receivedAt` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                        "`quality` REAL NOT NULL, `placeHint` TEXT NOT NULL, `identifierHash` TEXT, " +
                        "`signal` INTEGER, `usedForEvent` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_evidence_observations_eventTime` " +
                        "ON `evidence_observations` (`eventTime`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_evidence_observations_source_placeHint` " +
                        "ON `evidence_observations` (`source`, `placeHint`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `location_health` (`name` TEXT NOT NULL, " +
                        "`lastCallbackAt` INTEGER NOT NULL, `lastSuccessAt` INTEGER NOT NULL, " +
                        "`registered` INTEGER NOT NULL, `recoveryCount` INTEGER NOT NULL, `lastFailure` TEXT, " +
                        "PRIMARY KEY(`name`))"
                )
            }
        }
    }
}

