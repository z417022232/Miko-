package com.example.worktimetracker.domain.engine

import com.example.worktimetracker.data.entity.MonthlySalaryEntity
import java.time.LocalDate
import java.time.YearMonth

class PayrollPeriodRules {
    fun payrollMonthForPaymentMonth(paymentMonth: YearMonth): YearMonth = paymentMonth.minusMonths(1)

    fun defaultPaymentDateForPayrollMonth(payrollMonth: YearMonth): LocalDate =
        payrollMonth.plusMonths(1).atDay(15)

    fun createEntry(
        payrollMonth: YearMonth,
        paymentDate: LocalDate,
        netSalaryCents: Long
    ): MonthlySalaryEntity = MonthlySalaryEntity(
        month = YearMonth.from(paymentDate).toString(),
        netSalaryCents = netSalaryCents,
        payrollMonth = payrollMonth.toString(),
        paymentDate = paymentDate.toString()
    )

    fun displayLabel(payrollMonth: YearMonth, paymentDate: LocalDate): String =
        "${payrollMonth.monthValue}月工资（${paymentDate.monthValue}月${paymentDate.dayOfMonth}日发放）"
}
