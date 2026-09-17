# 算法层重构：统一学习层与七阶段路线

> 状态：**阶段 1（收尾）+ 学习层地基 + 阶段 2（位置闭环）已落地**（v9.0 / code 28 / DB v14，commit `0152639`）。
> 阶段 3~7 **未做**。本文是该路线在仓库里的**唯一源头** —— 之前只存在于对话里。
> 契约细节见 `.workbuddy/memory/CONTRACTS.md`「v9.0」一节；操作流程见 skill `android-worktracker-delivery`。

## 0. 为什么重构

现有算法的判定质量已经够用（真机 189 条记录、30 天轨迹验证过），问题出在**学习能力**：

1. **没有"事实/推算"的结构性区分**。`WorkHourCalculator` 早把 `finalMinutes` 的来源写进 `ruleTrace`，
   但那是**瞬时字符串**，落库后只剩一个数字。等要学「真实出勤时长」时，
   分不清某天的 660 分钟是真干了 11 小时，还是被 R9 直接填的固定工时 —— 前者能进训练集，后者**不能**。
2. **没有"候选/确认"的时间语义**。`work_state` 里的候选时刻会因断流、重启而推迟，
   库里没有字段保留"最早那条可靠证据的时刻"。
3. **"学习"只是散落的即时策略**。环境指纹有一套自己的 30 天窗口逻辑，
   但它没有版本、没有可解释性、没有回滚路径，也不会被别人复用。
   没有任何地方能回答「现在是第几版模型、基于什么训出来的、要不要退回去」。
4. **地点锚点全靠用户手输**。用户搬了工位、换了车间，只能自己发现并手动改坐标 ——
   App 明明每天在同一个地方采到上千个高精度点。

## 1. 三条不可让步的原则

| # | 原则 | 落地方式（不是口号） |
|---|---|---|
| 1 | **用户手动设置和修改的结果优先级最高** | `PlaceModelResolver.effectiveAnchor` 是**唯一执行点**；学习锚点只有 `autoApplied && 置信度 ≥0.70 && 偏移 ≤30m` 才被取用，否则一律回落配置锚点 |
| 2 | **OCR / 未确认数据不参与学习** | `LocationEvidence.learnable`（推算/回放/粗定位/非核心区一律 false）+ `FinalMinutesSource.isLearnable`（只有 `ACTUAL`/`MANUAL` 算事实） |
| 3 | **推算结果永不落库** | 学习产物只写**独立表**（`learned_place_models` / `place_anchor_candidates` / `learning_model_meta`）；`work_records` / `monthly_salaries` 的既有行一个字段都不动 |

补充约束：**每次预测必须能解释依据**（`AnchorUpdatePolicy.explain()` 输出的是"对用户说的话"，
不是日志）；**模型必须能从原始数据全量重建**（所以回滚是置 `RETIRED` 而**不删行**）。

## 2. 数据层

### 2.1 DB v14 新增（只加不改）

| 表 | 主键 | 作用 | 关键约束 |
|---|---|---|---|
| `learning_model_meta` | `modelType` + `modelVersion` | 模型版本元数据 | 回滚靠 `status` 迁移，**不删行**；`trainedThrough` / `sampleCount` 记录它基于什么训的 |
| `learned_place_models` | `placeId`(= `sites.id`) | 地点学习模型 | `configuredLat/Lng` 与 `learnedLat/Lng` **两列并存、永不互相覆盖** |
| `place_anchor_candidates` | `id` 自增 | 锚点候选（观测日志） | **判定永不读它** —— 影子验证的语义靠这条保证 |

`work_records` 加两列：`finalMinutesSource`（来源分离）、`firstObservedAt`（候选/确认时刻分离）。
两列**可空无默认值** → 老记录保持 `NULL` = 来源未知，**不做任何回填猜测**
（既可能固定也可能实际，猜错就污染了整个训练集）。

三张新表**不灌任何种子数据**：第一个版本必须由真实训练产出。

### 2.2 后续阶段预计需要的表（设计中，尚未建）

阶段 3~7 会用到 §九 的其余表（行程状态、班次画像、工资参数分段、预测快照、回测结果）。
**建表时沿用同一纪律**：只加不改、不灌种子、「预测/推算」只落自己的表。

## 3. 学习层地基（已完成）

```
domain/learning/
  ModelVersion.kt      LearningModelType(PLACE_ANCHOR/FINGERPRINT/SHIFT_PROFILE/JOURNEY/PAYROLL)
                       ModelStatus(ACTIVE/SHADOW/RETIRED/INVALID) + ModelVersion(type, version)
  Confidence.kt        ConfidenceLevel(HIGH/MEDIUM/LOW/NONE) + 档位线（引用已有门槛，不另立魔数）
  LearningCoordinator.kt  统一入口接口 + LocationEvidence + NoopLearningCoordinator
domain/location/
  PlaceModel.kt        GeoPoint / PlaceType / LearnedPlaceModel / PlaceModelResolver
  AnchorLearner.kt     §3.2 门槛（一个不松）
  AnchorUpdatePolicy.kt 候选生命周期判定 + 指数平滑 + 置信度 + 人话解释
  AnchorSampleBuilder.kt 入核 / 连续 / 跨天三条口径
domain/model/
  FinalMinutesSource.kt  ACTUAL / DEFAULT / MANUAL / INFERRED
```

**编排与算法分离是硬纪律**：`AnchorLearningService`（`location/service`，碰 Room、吞异常、
记 `LEARNING` 日志）只做编排；上面这些全是**纯 Kotlin、零 Android 依赖、必须可单测**。
理由：算法一旦塞进编排器就再也测不动了（本项目已有教训）。

`LearningCoordinator` 目前**只有接口和空实现，没有接真实现** —— 它只是"缝"，
阶段 3~7 各自接自己的入口，不要重新设计接口。

## 4. 阶段 2：位置闭环（已完成）

```
location_logs ─▶ AnchorSampleBuilder ─▶ AnchorLearner ─▶ AnchorUpdatePolicy
                                                              │
                                        ┌─────────────────────┴─────────────────────┐
                                   AUTO_SMOOTH                          SHADOW / NEEDS_USER_CONFIRM
                                        │                                           │
                     learned_place_models(autoApplied=true)         learned_place_models(autoApplied=false)
                                        │                                           │
                                        ▼                                    （记录但不生效）
                     SitePoints.withLearnedAnchors() ─▶ 融合判定
```

`place_anchor_candidates` 两个分支都写 —— 它是**观测日志**。

### 4.1 门槛（一个都不许松）

| 条件 | 值 | 出处 |
|---|---|---|
| 单点精度 | ≤ 30 m | §三.2 |
| 有效点数 | ≥ 10 | §三.2 |
| 跨自然日 | ≥ 5 天 | §三.2 |
| 核心区连续稳定 | ≥ 20 分钟 | §三.2 |
| 环境来源类数 | ≥ 2（Wi-Fi/蓝牙/基站） | §三.2 |
| 离散度上限 | P90 ≤ 150 m | 复用 `LocationAnchorCalibration` 的实证值 |
| 平滑 | `new = old×0.8 + candidate×0.2` | §三.2 |
| 自动平滑档 | 偏移 ≤ 30 m | §三.2 |
| 影子档 | 30 m < 偏移 ≤ 100 m | §三.2 |
| 请用户确认 | 偏移 > 100 m | §三.2 |
| 影子期 | 7 个自然日 | §三.2 |

**`decide()` 的顺序不能调**：先看偏移（>100m 不是校准问题，是搬家），再看影子期（没熬够一律不许生效）。

### 4.2 零回归是结构性的，不靠自觉

- `learned_place_models` **为空 → `withLearnedAnchors` 原样返回同一个列表实例**
  （新装 / 刚升级的库逐字节不变）；
- 只有 `autoApplied && 置信度 ≥ 0.70 && 偏移 ≤ 30 m` 才取学习锚点，否则回落配置锚点；
- **半径永不改动**（学习只挪圆心）—— 否则"校准"变成"悄悄放大判定范围"；
- `id <= 0` 的兜底虚拟站点（`SitePoint.SYNTHETIC_ID`）天然免疫（`sites.id` 是 AUTOINCREMENT，从 1 起）；
- 改动只落在两处：`ForegroundLocationService.effectiveSites` **一行** + `WorkTimeApplication` 启动跑一轮。

### 4.3 真机实测（v9.0，30 天轨迹）

| 地点 | 样本 | 跨天 | 环境来源 | 稳定停留 | 偏移 | 置信度 | 状态 |
|---|---|---|---|---|---|---|---|
| 公司 | 1479 | 30 | 3 类 | ≈2.9h | 7.4 m | 0.993 | SHADOW |
| 家 | 667 | 31 | 2 类 | ≈1.5h | 1.5 m | 0.932 | SHADOW |

两者 `autoApplied = 0` → 判定路径未受任何影响；`learning_model_meta` 0 行（没有 AUTO_SMOOTH 就不开版本）。

> ⚠️ **影子期 = 从「首次看到该候选的时刻」向前 7 个自然日，不追溯历史稳定性。**
> 所以上表这种情况是**设计的保守**，不是 bug：30 天历史已证明锚点很稳，仍要再连续 7 天才自动生效。
> 若嫌慢，可改用候选的 `distinctDayCount` 回填影子天数 —— 但那**等于取消影子验证**，需用户点头。

## 5. 阶段 3~7（未做）

| 阶段 | 目标 | 主要产物（预计） |
|---|---|---|
| 3 | **行程状态 + 自适应采样** | 行程状态机（在家/通勤/在岗/离岗的显式状态）、采样间隔随状态自适应（替代当前 `SamplingTuning` 的启发式） |
| 4 | **班次画像** | `ShiftProfile`：到离岗时刻的分位数、时长分布、通勤时长分布 —— 用于给"今天该几点到"提供先验 |
| 5 | **工资分项学习** | `PayrollComponentLearner`：从已确认工资条学分项规律（只采信 `SlipReconciler` 确认过的），输出经 `PayrollInputs` 注入，**`PayrollEngine` 签名不动** |
| 6 | **回测与预测** | `Backtest` / `Prediction`：历史窗口回放 + 预测落**独立表**（`modelVersion` + `trainedThrough` 必填） |
| 7 | **跨模型诊断** | 各模型相互佐证/矛盾时的诊断报告（"地点模型说在公司、指纹说在家"该信谁） |

**接续方式**：每个阶段都从 `LearningCoordinator` 的对应入口接进去，算法层放 `domain/` 纯函数，
编排放 `location/service/`（或阶段 5 的 `payroll/`）。别把算法写进编排器。

## 6. 改这个模块前的检查清单

1. **改 DB 版本** → 先跑 `diagnostics/tools/_mig14.py`（改版本号即可复用）做结构比对，
   **别上真机试**；新导出的 `app/schemas/*.json` 要一起提交。
2. **动门槛** → 先看 `CONTRACTS.md` 的冻结项，确认它不属于「算法冻结项（勿改）」。
3. **新增取用/优先级逻辑** → 必须落在 `PlaceModelResolver`，不要在服务里另写 `if`。
4. **新增准入判定** → 必须引 `AnchorLearner.MAX_ACCURACY_METERS` 这类**已有常量**，不要重写数字。
5. **枚举语义方向**（"取较保守/较小"）→ 必须写测试，且**正反两序都断言** ——
   `Confidence.conservative` 当初就是这么写反的，不报错、只静默放松门槛。
6. **学习链路允许失败** → 编排层必须吞异常并记日志，绝不能让学习把定位主链路带崩。
