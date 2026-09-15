# 计薪预测与发薪对账 —— 设计稿（v6，**决策已确认，未实施**）

> 状态：**4 项决策已于 2026-09-15 确认（见 §9），尚未写任何代码 / 未动 GitHub**。
> 本文描述改动方案、数据模型与实施顺序；确认后再按 §7 的四步落地。
> 约束：**保留现有工时记录与计薪公式，不动定位算法**。
> 结论口径：发薪前分项预测 → 发薪后核对差额 → 学习常规变化 → 改善下月预测。
> **不是**"录入实发后把预测替换成实际"，而是**保留原预测、真实衡量误差**。

---

## 0. 现状实测（2026-09-15 真机拉库核对）

| 事实 | 证据 |
|---|---|
| `monthly_salaries` 9 行，**只有实发一个数** | 列为 `month / payrollMonth / paymentDate / netSalaryCents`；`month` = 发薪月 = `payrollMonth` + 1 |
| 已录计薪月 2025-12 ~ 2026-08 | 今天(9/15)刚发 `payrollMonth=2026-08`，实发 ¥9,364.52 |
| `monthly_pay_params` **空表** | `SELECT *` 返回 0 行 |
| 浮动项因此全部按 0 参与推算 | `PayrollEngine.estimate` 里 `benefitBonusCents = 0L` 等默认零 |
| 推算结果**永不落库** | `PayrollDao` 注释明确"不碰 `monthly_salaries` / `work_records`" |
| DB 版本 v12，16 实体 / 12 DAO | 迁移链 1→12 完整 |
| 计薪参数分段常量已就绪 | `pay_rate_segments`：基本/岗位 1–6月 vs 2026-07 两段，工龄 2026-02 起 50 |

**结论：第一步"把实际数据录准"是从零开始** —— 现在只有 9 个到手总额，连"应发"都没入库。

---

## 1. 与现有实现的冲突点（必须先解决）

| # | 冲突 | 现状 | 需要怎么改 |
|---|---|---|---|
| C1 | **未填写 vs 明确为零无法区分** | 实体全是 `Long = 0L`（非空） | 分项金额改 `Long?`，`null` = 未填写；录入 UI 三态（未填 / 明确 0 / 金额） |
| C2 | **未填写浮动项被当 0 参与合计** | 引擎直接加 `0L` | 预测层改为"分项带来源"，合计分「确定 / 估计 / 未确定」三部分，未确定**不进合计**而是单列 |
| C3 | **绩效系数未知静默按 1.0** | `inputs.perfCoefficient ?: BigDecimal.ONE` | 输出 `coefficientAssumed = true`，UI 明写「系数按 1.0 假设（未填写）」 |
| C4 | **预测不留证据** | 永不落库 | 新增 `forecast_snapshots`（**只插不改**），带 `asOf` 与 `algorithmVersion` |
| C5 | **实发录入即覆盖预测** | 已录入 → 一律显示录入值（doc §3） | 保留录入值为主显示；**预测快照并行保留**，对账页并列展示，互不覆盖 |
| C6 | **"病假"是收入还是扣款无裁决** | 引擎把 `sickPayCents` 加进 gross（依 01 月反推） | 分项带 `stage` 字段 + `needsReview` 标记；**不自动猜**，由用户裁决一次后固化为该 key 的属性 |
| C7 | **应发/实发会被直接比较** | 月卡同时显示"推算应发"与"预计到手" | 对账页按**分段校验**：`Σ收入 − Σ收入侧扣减 = 应发`，`应发 − Σ应发后扣减 = 实发`；两条独立比对 |
| C8 | **两套口径（公式法 / 单价法）定位不明** | v5.1 两者并列 | 见 §6.3：分项对账只用公式法；单价法只对"到手总额"做旁证 |
| C9 | **"全部未记录日期按上班"** | v5.1 已按 11h 补，但假设不可调 | 假设抽成 `Assumptions`（预计休息日 / 预计夜班数 / 标准工时），落进快照 + 允许用户调整 |
| C10 | **固定月薪可能被重复计算** | 累计区与整月预测区都含基本/岗位 | 界面分区约定：**固定月薪只在"整月预测"出现一次**；"截至今天"只显示工时/出勤/夜班与浮动项进度 |

---

## 2. 数据模型（DB v13 → v14，全新表，**不动** `monthly_salaries` / `work_records`）

> 用户要求的三类持久化 → 落地为 **4 张表**（第 1 类拆成主表 + 分项表，因为要按项目聚合做学习和回测）。

### 2.1 `salary_slips`（工资条主表）

```kotlin
@Entity(tableName = "salary_slips")
data class SalarySlipEntity(
    /** 计薪月 `YYYY-MM`，与 monthly_salaries.payrollMonth 同口径 */
    @PrimaryKey val payrollMonth: String,
    /** 实际发薪日期 `YYYY-MM-DD` */
    val paymentDate: String,
    /** 总状态：DRAFT / PENDING_REVIEW / CONFIRMED */
    val status: String,
    /** 工资条自己印的"计薪出勤天数"（用于与本机工时记录对账） */
    val slipAttendDays: Int? = null,
    /** 工资条自己印的"计薪夜班数" */
    val slipNightShifts: Int? = null,
    /** 条上「应发工资」（分，null = 未填写） */
    val declaredGrossCents: Long? = null,
    /** 条上「实发工资」（分） */
    val declaredNetCents: Long? = null,
    /** 确认时间（epoch millis）。null = 未确认 → 不参与学习 */
    val confirmedAt: Long? = null,
    /** 修订号：已确认后每次改动 +1，快照按 (payrollMonth, revision) 引用 */
    val revision: Int = 1,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
```

### 2.2 `salary_slip_items`（分项，**核心表**）

```kotlin
/** 分项所属阶段 —— 决定它进哪一条校验式 */
enum class SlipItemStage {
    INCOME,             // 收入：进 应发
    DEDUCT_PRE_GROSS,   // 收入侧扣减：事假 / 迟到 / 绩效扣款 → 也进 应发（减项）
    DEDUCT_POST_GROSS,  // 应发后扣减：社保 / 公积金 / 个税 / 其他扣款 → 只影响实发
}

/** 分项性质 —— 决定是否参与学习、是否延续 */
enum class SlipItemNature {
    FIXED,     // 基本工资、岗位津贴、工龄、全勤、加班（包干 36h）、社保、公积金
    FLOATING,  // 绩效工资、效益奖金、夜班津贴、高温补贴、病假工资 → 参与学习
    ONE_TIME,  // 补发、其他加项（一次性奖励 / 报销）→ 默认不延续
}

@Entity(tableName = "salary_slip_items", primaryKeys = ["payrollMonth", "itemKey"])
data class SalarySlipItemEntity(
    val payrollMonth: String,
    /** 稳定 key，见 SlipItemKey（含 12 项收入 + 扣款 + 预留项） */
    val itemKey: String,
    /** 条上印的名称（原样保留，便于对照纸质条） */
    val rawLabel: String? = null,
    /**
     * 金额（分）。**null = 未填写；0 = 明确为零**（C1 的关键）
     * 用 `filled` 布尔无法表达"未填写但键存在"，所以用可空。
     */
    val amountCents: Long? = null,
    val stage: SlipItemStage,
    val nature: SlipItemNature,
    /** 原始输入的展示文本（如 `331.03` / `—`），用于"保留原始数字，不自动修正" */
    val rawText: String? = null,
    /** 含义待用户裁决（如"病假"是收入还是扣款） */
    val needsReview: Boolean = false,
    val note: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)
```

### 2.3 `forecast_snapshots`（预测快照，**只插不改**）

```kotlin
@Entity(tableName = "forecast_snapshots", indices = [Index("targetPayrollMonth")])
data class ForecastSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val targetPayrollMonth: String,
    /** 预测时点（epoch millis）—— 回测只允许用 `confirmedAt <= asOf` 的数据 */
    val asOf: Long,
    /** 口径：FORMULA（分项公式法） / UNIT_PRICE（工时×到手单价法） */
    val caliber: String,
    /** 算法版本，如 `forecast-1.0`；改算法后回测能分组比较 */
    val algorithmVersion: String,
    /** 输入假设：出勤天数、夜班数、标准工时、预计休息日、调薪分组…  */
    val assumptionsJson: String,
    /** 分项结果：[{itemKey, cents?, source, method, sampleMonths[], low?, high?}] */
    val itemsJson: String,
    /** 确定项合计 / 估计项合计 / 未确定项（不进合计） */
    val deterministicCents: Long,
    val estimatedCents: Long,
    val unknownItemKeys: String,
    val grossCents: Long?,
    val netCents: Long?,
    /** 参考范围的来源说明（**不宣称统计保证**） */
    val rangeBasisText: String,
    val createdAt: Long = System.currentTimeMillis(),
)
```

### 2.4 `reconciliations`（对账记录）

```kotlin
@Entity(tableName = "reconciliations", indices = [Index("payrollMonth")])
data class ReconciliationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val payrollMonth: String,
    val forecastSnapshotId: Long,
    /** 对账时引用的工资条修订号（改工资条不会回头改已生成的对账快照） */
    val slipRevision: Int,
    /** 逐项：[{itemKey, forecastCents?, actualCents?, diffCents?, action}] */
    val itemsJson: String,
    /** 差额分类：SIZE（金额规模）/ TIMING（补发跨月）/ ONE_TIME / NIGHT_COUNT / UNEXPLAINED */
    val varianceKinds: String,
    /** 应发差额 / 到手差额 分开记（C7：不把应发与实发直接比） */
    val grossDiffCents: Long?,
    val netDiffCents: Long?,
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
)
```

### 2.5 迁移策略

- **`MIGRATION_12_13`**：建 `salary_slips` + `salary_slip_items`（+ 索引）+ **灌历史草稿种子**（见 §7 第一步）。
- **`MIGRATION_13_14`**：建 `forecast_snapshots` + `reconciliations`。
- 迁移**只建新表**，`work_records` / `monthly_salaries` / `manual_override` / `pay_rate_segments` / `monthly_pay_params` 一律不碰（验收标准最后一条）。
- 迁移测试：沿用现有 `Migration*Test` 模式（androidTest），断言"迁移后 9 行 `monthly_salaries` 与工时记录条数不变"。
- ⚠️ 记得 `git add app/schemas/.../13.json`、`14.json`。

---

## 3. 模块拆分（**不往 `WorkTimeViewModel` 继续堆**）

```
domain/payroll/
  PayrollEngine.kt              # 保留：基础分项计算器（不动公式）
  SlipReconciler.kt             # 新增①工资条核对器（纯函数）
  FloatingItemEstimator.kt      # 新增②浮动项估计器（纯函数，三种方法）
  MonthForecastService.kt       # 新增③整月预测服务（组装 + 产快照）
  ForecastBacktest.kt           # 新增④历史回测与误差统计（纯函数）

ui/app/forecast/
  ForecastViewModel.kt          # 工资条 / 预测 / 对账的界面状态（独立 VM）
  ForecastModels.kt             # UI 模型

ui/screens/
  SlipEntry.kt                  # 工资条录入 + 合计核对 + 异常标记
  ForecastDetail.kt             # 当前累计 / 剩余假设 / 整月预计应发 / 整月预计到手
  Reconciliation.kt             # 预测对账页
```

- `WorkTimeViewModel` **只加一条**：暴露 `forecastViewModel` 所需的最小只读数据（工时/出勤/夜班/记录），其余全部收敛到 `ForecastViewModel`。
- `PayrollEngine` **签名不改**（现有 502 例测试保持全绿）；学习结果通过 `PayrollInputs` 注入（`perfAmountCents` / `benefitBonusCents` …），即"学出来 → 喂引擎 → 算个税 → 得手"。

---

## 4. 工资条核对器 `SlipReconciler`

### 4.1 两条校验关系（对应验收"不把应发与实发直接比较"）

```
校验①  Σ(stage=INCOME) − Σ(stage=DEDUCT_PRE_GROSS)  ==  declaredGrossCents
校验②  declaredGrossCents − Σ(stage=DEDUCT_POST_GROSS) == declaredNetCents
```

- 两条**分别**报差额，绝不把 `declaredGrossCents` 与 `declaredNetCents` 直接相减当误差。
- 差额非 0 时**不改数据**，只产出 `CheckIssue(kind, diffCents, hint)`：
  - `MISSING_ITEM`（差额可能是一项没录 → 提示"可能还差一项 ¥x"）
  - `IMPLAUSIBLE`（单项金额为负 / 与历史量级差 >5 倍）
  - `AMBIGUOUS_SIGN`（`needsReview` 的分项）
- **未填写（null）与 0 的差异**：只要 `amountCents == null` 就计入"未填写项清单"并**从合计里排除**，而不是当 0；`amountCents == 0L` 才参与合计。

### 4.2 与 `monthly_salaries` 的兼容（只读校验）

- 工资条 `declaredNetCents` 与 `monthly_salaries.netSalaryCents` 不一致时 → **只报提示**，`monthly_salaries` 不动。
- 不自动把工资条实发写回 `monthly_salaries`（用户："之前已经录入的工资数据不允许改"）。

---

## 5. 浮动项估计器 `FloatingItemEstimator`

### 5.1 三种方法（第一版不引入复杂 AI）

| 方法 | 定义 | 适用 |
|---|---|---|
| `MEDIAN` | 最近有效月份的**中位数** | 抗离群，样本少也稳 |
| `WEIGHTED_RECENT` | 最近月份权重更高的加权平均（`w = decay^(月距)`，默认 decay 0.7，取最近 6 月） | 近期趋势变了 |
| `ATTEND_SCALED` | 先算 `单项 ÷ 工资条计薪出勤天数` 的单位值中位数，再 × 目标月预计出勤天数 | 与出勤强相关的项 |

### 5.2 处理原则（逐条对应你的要求）

| 要求 | 实现 |
|---|---|
| 只用预测时点之前、**已确认**的数据 | 过滤 `confirmedAt != null && confirmedAt <= asOf && nature == FLOATING && amountCents != null` |
| 一次性补发、错误数据不参与 | `nature == ONE_TIME` 直接排除；`needsReview == true` 或 `CheckIssue` 命中的月份**排除并列出** |
| 调薪/计薪制度变化前后**分组** | 用 `pay_rate_segments` 找出目标月与候选月之间是否存在 `BASIC_SALARY`/`PERF_BASE`/`SOCIAL_INSURANCE` 的新分段；有切断则只用切点之后的分组 |
| 数据少时**保守基线** + 显示"样本不足" | 样本 < 3 → 取 `min(MEDIAN, WEIGHTED_RECENT)` 并向"不利方向"取整；输出 `confidence = INSUFFICIENT` |
| **不因一个月误差就永久调高** | 方法选择基于滚动 MAE（见 5.3），**没有任何"上调幅度"自由度**；单月误差只影响 `UNEXPLAINED` 标记 |
| 绩效系数未知要标记 | 输出 `coefficientAssumed=true`，UI 明写假设，不静默 |

### 5.3 方法选择（"新方法若不优于简单基线就保留基线"）

- **基线** = `MEDIAN`。
- 选择规则：在**同一调薪分组**内做 walk-forward 回测，算每个方法的 MAE；选 MAE 最小者。
- **门槛**：候选方法 MAE 必须**优于基线 ≥ 10%** 才切换，否则保留基线并显示"沿用基线（新方法未更优）"。
- 输出 `Estimate(valueCents, method, sampleMonths, confidence, lowCents, highCents, rangeBasisText)`。
- `low/high` 用样本的 min/max 或分位数，文案固定为**"参考范围（据历史 N 个月；不构成统计保证）"**。

### 5.4 哪些项参与学习

| 分项 | nature | 学习 |
|---|---|---|
| 基本工资 / 岗位 / 工龄 / 全勤 / 加班（包干） / 社保 / 公积金 | FIXED | **不学**，走 `pay_rate_segments` 分段常量 |
| 绩效工资 | FLOATING | 学金额（方法 1/2/3）；保留 `perfCoefficient` 手填入口 |
| 效益奖金 | FLOATING | 学金额（方法 1/2/3） |
| 夜班津贴 | FLOATING（但公式已知：45×天数） | **学"天数"**，金额=单价×学出的天数；对账时核对工资条计薪夜班数 |
| 高温补贴 | FLOATING | 学金额，但按**月份**分组（季节性），不跨季学 |
| 病假工资 | FLOATING（`needsReview`） | 用户裁决收入/扣款后才学 |
| 补发 / 其他加项 | ONE_TIME | **不学、不延续**，只进"整月预计"当明确录入时 |

---

## 6. 界面

### 6.1 「当前累计」与「整月预测」严格分开（对应第 4 点）

| 区 | 内容 | 数据来源 |
|---|---|---|
| **① 截至今天** | 已记出勤天数、已记夜班天数、已记工时 | 本机 `work_records` |
| **② 剩余日期假设** | 预计休息日、预计夜班数、标准工时 —— **可调整** | 用户设置 / 排班 / v5.1 的 `DayKind` 规则 |
| **③ 整月预计应发** | 确定项 + 估计项 + 未确定项（单列） | `MonthForecastService` |
| **④ 整月预计到手** | 应发 − 社保 − 公积金 − 个税（个税由§3的引擎派生） | 同上 |

- **固定月薪（基本/岗位/工龄/全勤）只在 ③ 出现一次**，① 区不再重复显示金额 → 防重复计算（C10）。
- 假设改动 → **产生新快照**（`asOf` 不同），旧快照保留。

### 6.2 预测卡片必需展示（对应第 5 点）

- 哪些金额**已确定**（FIXED，走分段常量）
- 哪些金额**来自估计**（FLOATING，附方法名）
- 使用了**哪些历史月份**（`sampleMonths`）
- **参考范围及其依据**（`rangeBasisText`，并注明"不构成统计保证"）

### 6.3 两套口径的定位（决策项 D2）

- **分项对账只用公式法**（`caliber=FORMULA`）—— 单价法没有分项，无法逐项比对。
- 单价法（v5.1）保留为"**到手总额旁证**"：在 ④ 下面加一行 `整月预估（单价法）≈ ¥X（按已录实发校准）`，与公式法并列但**不参与差额归因**。
- 好处：v5.1 的功能不丢，也不产生"分项差额算不清"的歧义。

### 6.4 对账页（发薪后）

| 项目 | 发薪前预测 | 工资条实际 | 差额 | 处理 |
|---|---|---|---|---|
| 绩效 | 快照值 | 条上值 | Δ | `更新绩效估计` |
| 效益奖金 | 快照值 | 条上值 | Δ | `更新奖金估计` |
| 夜班津贴 | 45×学出天数 | 条上值 | Δ | `核对计薪夜班数` |
| 一次性补发 | — | 条上值 | — | `不延续` |
| **预计应发** | … | … | Δ | — |
| **预计到手** | … | … | Δ | `记录总误差` |

- "处理"按钮的语义 = **把该月标为已确认**（供学习）+ 记录处置分类；**绝不回写快照**。
- 差额分类枚举见 `reconciliations.varianceKinds`（SIZE / TIMING / ONE_TIME / NIGHT_COUNT / UNEXPLAINED）。

---

## 7. 实施顺序（4 步 → 4 个提交，每步可独立验收）

### 第一步：把实际数据录准（DB v13）
- `salary_slips` + `salary_slip_items` + `SlipReconciler` + 录入 UI（三态输入 + 双校验 + 异常标记）+ `monthly_salaries` 只读兼容校验。
- **历史草稿种子**（`MIGRATION_12_13` 内灌，全部 `DRAFT` / `PENDING_REVIEW`）：
  - 8–9 个计薪月的 `declaredNetCents` ← 直接取 `monthly_salaries` 现值（不改）；`declaredGrossCents` ← 口径文档 §4 对账表的"实际应发"。
  - 能由分段常量**确定**的项（基本/岗位/工龄/全勤/加班/社保/公积金）照填。
  - **无法确定的浮动项（绩效/效益/高温/病假/夜班天数）保持 `null` = 未填写** → 由 `SlipReconciler` 报出差额，差额即"待补的浮动项合计"。
  - 你点名的三处按**工单**标 `needsReview=true` 且**保留原始数字、不自动修正**：
    | 计薪月 | 项 | 处理 |
    |---|---|---|
    | 2026-05 | 夜班津贴 | 待核对（06 月条少打一项 360 的记录） |
    | 2026-06 | 工龄工资 = 30（疑应 50）、病假 = 331.03（疑应 0） | 按原始值存 + `needsReview` |
    | 2026-07 | 工龄工资 | 待核对（8 月条新冒出的差异） |
  - 07 月「应发 451」笔误：`declaredGrossCents` 存原始 451（或 `null` 并写 note），**不自动改成 8711.83**；由校验①报出巨大差额 → 让你裁决。

### 第二步：保留预测证据（DB v14）
- `forecast_snapshots` + `reconciliations` + 对账页 + 假设可调。
- 解决"事后看不出当时预测了什么"。

### 第三步：分项学习
- `FloatingItemEstimator`：先效益奖金、绩效，再夜班天数、扣款估计。
- 预测卡片显示方法 / 样本月份 / 参考范围 / "样本不足"。

### 第四步：回测后切换默认
- `ForecastBacktest`：严格 `asOf` 截断；产出 MAE、持续高估/低估、最差月份误差。
- 新旧并列显示；**仅当新法优于基线才换默认**，否则保留基线。

---

## 8. 验收标准 → 落地映射

| 验收标准 | 落地点 | 护栏测试 |
|---|---|---|
| 未填写与零金额明确区分 | `amountCents: Long?`（null vs 0） | `SlipReconcilerTest`：null 不进合计、0 进合计 |
| 不把应发与实发直接比较 | 两条独立校验式 | `SlipReconcilerTest`：分别报差额 |
| 一次性补发不抬高后续预测 | `nature=ONE_TIME` 排除 | `FloatingItemEstimatorTest`：混入补发后估计值不变 |
| 修改实际工资不覆盖历史预测 | 快照只插不改 + `revision` | `ForecastSnapshotTest`：改工资条后旧快照逐字段不变 |
| 回测只用当时已知数据 | `asOf` + `confirmedAt` 过滤 | `ForecastBacktestTest`：晚于 asOf 确认的数据不参与 |
| 展示 MAE / 持续高估低估 / 最差月 | `BacktestReport` | `ForecastBacktestTest` |
| 新法不优于基线就保留基线 | MAE 门槛 ≥10% | `ForecastBacktestTest`：劣化时 `chosen == MEDIAN` |
| 迁移后原数据完整 | `MIGRATION_12_13` / `13_14` 只建表 | `Migration12To13Test`（androidTest） |

---

## 9. 决策记录（2026-09-15 已确认）

| 编号 | 决策 | 确认结果 |
|---|---|---|
| D1 | 历史草稿种子的范围与"未知项留空"的做法 | ✅ **全量种子 + 未填写留空** —— 按 §7 第一步灌 8–9 个计薪月草稿；确定项照填，浮动项留 `null`（未填写）由 `SlipReconciler` 报差额=待补项合计；三处疑点标 `needsReview` 且**保留原始数字** |
| D2 | 两套口径（公式法 / 单价法）在新体系的关系 | ✅ **公式法对账 + 单价法旁证** —— 分项对账只用 `caliber=FORMULA`；单价法保留为「整月预计到手」下方一行旁证（见 §6.3），**不参与差额归因**；v5.1 功能保留 |
| D3 | 绩效 / 效益奖金学习的**目标量** | ✅ **学金额** —— 直接学「绩效工资」「效益奖金」金额（`MEDIAN` / `WEIGHTED_RECENT` / `ATTEND_SCALED` 三选一）；出勤影响交给 `ATTEND_SCALED` 单独处理；保留绩效系数手填入口 |
| D4 | 已确认工资条的**修订策略** | ✅ **可改，`revision` 递增** —— 每次改动 `revision + 1` 并刷新 `confirmedAt`；已生成的 `forecast_snapshots` / `reconciliations` **按旧 revision 引用、绝不回写** |

**因此 §6.3 / §5.4 / §2.2 中的相关设计保持不变，实施时按上述确认执行。**

