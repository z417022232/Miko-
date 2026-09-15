package com.example.worktimetracker.ui.app.forecast

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.worktimetracker.WorkTimeApplication
import com.example.worktimetracker.data.entity.PayRateSegmentEntity
import com.example.worktimetracker.data.entity.SalarySlipEntity
import com.example.worktimetracker.data.entity.SalarySlipItemEntity
import com.example.worktimetracker.domain.payroll.PayRateKey
import com.example.worktimetracker.domain.payroll.PayRateResolver
import com.example.worktimetracker.domain.payroll.PayRateSet
import com.example.worktimetracker.domain.payroll.SlipDraftSeeder
import com.example.worktimetracker.domain.payroll.SlipItemKey
import com.example.worktimetracker.domain.payroll.SlipStatus
import com.example.worktimetracker.ui.PayrollPresenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * 「工资条录入与核对」的界面状态（**独立 ViewModel**）。
 *
 * 刻意不往 `WorkTimeViewModel` 里堆：计薪预测与发薪对账是独立业务线，
 * 工时记录只被**只读**引用（`monthly_salaries` 的实发拿来做兼容校验）。
 *
 * 数据流：`month` 变 → 读 `salary_slips` + `salary_slip_items` + 只读实发 → 生成 [SlipEditorState]。
 * 所有编辑只改内存草稿，`save()` 才落库；`revision` 只在「已确认后又被改动」时 +1（决策 D4）。
 */
class ForecastViewModel(application: Application) : AndroidViewModel(application) {

    private val db = (application as WorkTimeApplication).database
    private val slipDao = db.salarySlipDao()

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month

    /** 全部工资条表头（用于「已录入几个月」之类的摘要）。 */
    private val _slips = MutableStateFlow<List<SalarySlipEntity>>(emptyList())
    val slips: StateFlow<List<SalarySlipEntity>> = _slips

    private val _editor = MutableStateFlow<SlipEditorState?>(null)
    val editor: StateFlow<SlipEditorState?> = _editor

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    private var segments: List<PayRateSegmentEntity> = emptyList()

    init {
        viewModelScope.launch {
            segments = db.payrollDao().rateSegments()
            val slips = slipDao.allSlips()
            _slips.value = slips
            // 默认停在**最近一张工资条**的计薪月，而不是自然月：发薪日拿到的是**上月**的条，
            // 自然月通常还没有工资条（用户 2026-09-15 的场景就是如此）。
            slips.lastOrNull()?.payrollMonth
                ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
                ?.let { _month.value = it }
            load(_month.value)
        }
    }

    // ------------------------------------------------------------------ 月份

    fun previousMonth() { moveTo(_month.value.minusMonths(1)) }
    fun nextMonth() { moveTo(_month.value.plusMonths(1)) }
    fun today() { moveTo(YearMonth.now()) }
    fun reload() = load(_month.value)

    private fun moveTo(target: YearMonth) {
        _month.value = target
        load(target)
    }

    private fun load(target: YearMonth) {
        val monthKey = target.toString()
        viewModelScope.launch {
            val header = slipDao.slip(monthKey)
            val rows = slipDao.items(monthKey)
            val recorded = db.monthlySalaryDao().getForPayrollMonth(monthKey)?.netSalaryCents
            _editor.value = buildState(monthKey, header, rows, recorded)
        }
    }

    private fun buildState(
        monthKey: String,
        header: SalarySlipEntity?,
        rows: List<SalarySlipItemEntity>,
        recordedNetCents: Long?,
    ): SlipEditorState {
        val byKey = rows.mapNotNull { row ->
            SlipItemKey.byStorageKey(row.itemKey)?.let { it to row }
        }.toMap()
        val items = SlipItemKey.displayOrder.associateWith { key ->
            val row = byKey[key]
            SlipItemDraft(
                text = PayrollPresenter.moneyText(row?.amountCents),
                needsReview = row?.needsReview ?: false,
            )
        }
        return SlipEditorState(
            payrollMonth = monthKey,
            paymentDate = header?.paymentDate.orEmpty(),
            status = SlipStatus.parse(header?.status),
            slipAttendDays = header?.slipAttendDays?.toString().orEmpty(),
            slipNightShifts = header?.slipNightShifts?.toString().orEmpty(),
            grossText = PayrollPresenter.moneyText(header?.declaredGrossCents),
            netText = PayrollPresenter.moneyText(header?.declaredNetCents),
            note = header?.note.orEmpty(),
            items = items,
            recordedNetCents = recordedNetCents,
            revision = header?.revision ?: 1,
            confirmedAt = header?.confirmedAt,
            hasSlip = header != null,
        )
    }

    // ------------------------------------------------------------------ 编辑

    private fun update(transform: (SlipEditorState) -> SlipEditorState) {
        _editor.value = _editor.value?.let { transform(it).copy(dirty = true) }
    }

    fun updatePaymentDate(v: String) = update { it.copy(paymentDate = v) }
    fun updateGross(v: String) = update { it.copy(grossText = v) }
    fun updateNet(v: String) = update { it.copy(netText = v) }
    fun updateNote(v: String) = update { it.copy(note = v) }
    fun updateAttendDays(v: String) = update { it.copy(slipAttendDays = v) }
    fun updateNightShifts(v: String) = update { it.copy(slipNightShifts = v) }

    fun updateItem(key: SlipItemKey, text: String) = update { state ->
        val cur = state.items[key] ?: SlipItemDraft()
        state.copy(items = state.items + (key to cur.copy(text = text)))
    }

    /** 裁决「这项是收入还是扣款」（如「病假工资」）。 */
    fun toggleItemReview(key: SlipItemKey) = update { state ->
        val cur = state.items[key] ?: SlipItemDraft()
        state.copy(items = state.items + (key to cur.copy(needsReview = !cur.needsReview)))
    }

    fun setStatus(status: SlipStatus) = update { it.copy(status = status) }

    fun clearMessage() { _message.value = "" }

    /**
     * 按计薪参数把**能确定的 7 项**填进草稿（基本/岗位/工龄/全勤/加班/社保/公积金）；
     * 浮动项保持**空白 = 未填写**，交给核对器报差额 —— 这正是「差额即待补项合计」的来源。
     *
     * 只填当前为空的项，绝不覆盖用户已经输入的值。
     */
    fun generateDraft() {
        val rates = ratesFor(_month.value.toString())
        update { state ->
            val drafts = state.items.toMutableMap()
            for (entry in SlipDraftSeeder.draftItems(rates)) {
                val existing = drafts[entry.key]
                if (existing == null || existing.text.isBlank()) {
                    drafts[entry.key] = SlipItemDraft(
                        text = PayrollPresenter.moneyText(entry.amountCents),
                        needsReview = entry.needsReview || existing?.needsReview == true,
                    )
                }
            }
            state.copy(items = drafts)
        }
        _message.value = "已按本计薪月的分段常量填入可确定的项；浮动项留空 = 未填写，由校验差额提示还差多少"
    }

    // ------------------------------------------------------------------ 保存

    /**
     * 落库工资条（表头 + 全部分项）。
     *
     * 校验格式后再写，避免存进半截数据。`revision` 规则（决策 D4）：
     * - 首次确认 / 已确认后又改动 → `revision + 1`
     * - 未确认的草稿反复保存 → 不变
     * **`monthly_salaries` 一律不写**（只读比对）。
     */
    fun save() {
        val state = _editor.value ?: return

        val badItem = state.items.entries.firstOrNull { (_, draft) ->
            draft.text.isNotBlank() && PayrollPresenter.parseMoneyOrNull(draft.text) == null
        }
        if (badItem != null) {
            _message.value = "${badItem.key.label} 的金额格式不对"
            return
        }
        if (state.grossText.isNotBlank() && state.declaredGrossCents == null) {
            _message.value = "条上应发金额格式不对"
            return
        }
        if (state.netText.isNotBlank() && state.declaredNetCents == null) {
            _message.value = "条上实发金额格式不对"
            return
        }
        val nights = state.slipNightShifts.trim().let { if (it.isEmpty()) null else it.toIntOrNull() }
        if (nights != null && nights !in 0..31) {
            _message.value = "计薪夜班数应在 0–31"
            return
        }
        val days = state.slipAttendDays.trim().let { if (it.isEmpty()) null else it.toIntOrNull() }
        if (days != null && days !in 0..31) {
            _message.value = "计薪出勤天数应在 0–31"
            return
        }

        viewModelScope.launch {
            val existing = slipDao.slip(state.payrollMonth)
            val wasConfirmed = existing?.confirmedAt != null
            val nowConfirmed = state.status == SlipStatus.CONFIRMED
            val bump = (wasConfirmed && state.dirty) || (nowConfirmed && !wasConfirmed)
            val now = System.currentTimeMillis()

            slipDao.saveSlip(
                SalarySlipEntity(
                    payrollMonth = state.payrollMonth,
                    paymentDate = state.paymentDate.trim(),
                    status = state.status.name,
                    slipAttendDays = days,
                    slipNightShifts = nights,
                    declaredGrossCents = state.declaredGrossCents,
                    declaredNetCents = state.declaredNetCents,
                    confirmedAt = if (nowConfirmed) now else null,
                    revision = if (bump) state.revision + 1 else state.revision,
                    note = state.note.trim().ifBlank { null },
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            )
            slipDao.saveItems(
                state.entries.map { entry ->
                    val draft = state.items[entry.key]
                    SalarySlipItemEntity(
                        payrollMonth = state.payrollMonth,
                        itemKey = entry.key.storageKey,
                        rawLabel = entry.key.label,
                        amountCents = entry.amountCents,
                        stage = entry.key.stage.name,
                        nature = entry.key.nature.name,
                        rawText = draft?.text?.trim()?.ifBlank { null },
                        needsReview = entry.needsReview,
                        note = null,
                        updatedAt = now,
                    )
                }
            )
            _slips.value = slipDao.allSlips()
            _message.value = if (nowConfirmed) {
                "已保存并标记为「已确认」，本月的浮动项可参与后续学习"
            } else {
                "已保存（可继续修改；确认后才算已核对）"
            }
            _editor.value = buildState(
                state.payrollMonth, slipDao.slip(state.payrollMonth), slipDao.items(state.payrollMonth),
                db.monthlySalaryDao().getForPayrollMonth(state.payrollMonth)?.netSalaryCents
            )
        }
    }

    /** 一键把浮动项按**上月已确认工资条**的金额填入草稿（历史延续；仅填空白项）。 */
    fun fillFromPreviousMonth() {
        val state = _editor.value ?: return
        val prevKey = _month.value.minusMonths(1).toString()
        viewModelScope.launch {
            val prevRows = slipDao.items(prevKey)
            if (prevRows.isEmpty()) {
                _message.value = "上一个月没有工资条分项可参照"
                return@launch
            }
            val prev = prevRows.mapNotNull { row ->
                SlipItemKey.byStorageKey(row.itemKey)?.let { it to row.amountCents }
            }.toMap()
            val drafts = state.items.toMutableMap()
            for (key in SlipItemKey.displayOrder) {
                if (key.nature == com.example.worktimetracker.domain.payroll.SlipItemNature.ONE_TIME) continue
                val cur = drafts[key]
                if (cur == null || cur.text.isBlank()) {
                    prev[key]?.let { drafts[key] = SlipItemDraft(PayrollPresenter.moneyText(it), cur?.needsReview == true) }
                }
            }
            _editor.value = state.copy(items = drafts, dirty = true)
            _message.value = "已参照 ${prevKey} 的工资条填入空白项（一次性项不延续）"
        }
    }

    // ------------------------------------------------------------------ 内部

    private fun ratesFor(payrollMonth: String): PayRateSet {
        val map = segments
            .groupBy { PayRateKey.byStorageKey(it.paramKey) }
            .mapNotNull { (key, rows) ->
                key?.let { it to rows.map { row -> PayRateResolver.Segment(row.effectiveFrom, row.value) } }
            }
            .toMap()
        return PayRateResolver.resolve(payrollMonth, map)
    }
}
