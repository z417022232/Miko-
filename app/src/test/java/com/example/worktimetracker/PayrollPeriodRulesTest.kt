package com.example.worktimetracker

import com.example.worktimetracker.domain.engine.PayrollPeriodRules
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class PayrollPeriodRulesTest {
    private val rules = PayrollPeriodRules()

    @Test fun julyPaymentMapsToJunePayrollPeriod() {
        assertEquals(
            YearMonth.of(2026, 6),
            rules.payrollMonthForPaymentMonth(YearMonth.of(2026, 7))
        )
        assertEquals(
            LocalDate.of(2026, 7, 15),
            rules.defaultPaymentDateForPayrollMonth(YearMonth.of(2026, 6))
        )
    }

    @Test fun salarySummaryUsesPayrollMonthRatherThanPaymentMonth() {
        val entry = rules.createEntry(
            payrollMonth = YearMonth.of(2026, 6),
            paymentDate = LocalDate.of(2026, 7, 15),
            netSalaryCents = 1_234_500L
        )
        assertEquals("2026-06", entry.payrollMonth)
        assertEquals("2026-07", entry.month)
        assertEquals("2026-07-15", entry.paymentDate)
        assertEquals(1_234_500L, entry.netSalaryCents)
    }

    @Test fun displayClearlyNamesPayrollAndPaymentPeriods() {
        assertEquals(
            "6月工资（7月15日发放）",
            rules.displayLabel(
                payrollMonth = YearMonth.of(2026, 6),
                paymentDate = LocalDate.of(2026, 7, 15)
            )
        )
    }
}
