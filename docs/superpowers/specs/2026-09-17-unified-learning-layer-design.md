# 算法层重构：统一学习层与七阶段路线

> 状态：**阶段 1（收尾）+ 学习层地基 + 阶段 2（位置闭环）已落地**（v9.0 / code 28 / DB v14，commit `0152639`）；
> **影子验证强化已落地**（v9.1 / code 29 / DB v15）。
> 阶段 3~7 **未做**，但阶段 3 的**边界与接口已冻结**（见 §5.1）。
> 本文是该路线在仓库里的**唯一源头** —— 之前只存在于对话里。
> 契约细节见 `.workbuddy/memory/CONTRACTS.md`「v9.0 / v9.1」一节；操作流程见 skill `android-worktracker-delivery`。

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

### 2.1.1 DB v15 追加（v9.1）

| 改动 | 说明 |
|---|---|
| `place_anchor_candidates` 加 `spreadP90Meters`（可空） | 影子验证第 6 条（离散度恶化）的基线；`NULL` = **未知**，**不许当 0** |

为什么 `spreadP90Meters` 可空：v15 之前的行确实没记过这个读数，而 `0` 的含义是"完美集中"。
把"未知"写成 `0` 会让老数据被当成"离散度不可能恶化"而放行 —— 与 `finalMinutesSource`
保持 `NULL` 是同一条纪律：**未知就是未知，不猜**。

`ShadowValidator` 遇到 `latestP90 == null` 直接判**不通过**（`"缺少离散度读数，无法确认是否恶化"`），
宁可让老库多观察一轮，也不拿未知当合格。

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
| 影子期 | **六个条件同时成立**（见 §4.4） | §三.2 + v9.1 强化 |

**`decide()` 的顺序不能调**：先看偏移（>100m 不是校准问题，是搬家），再看影子验证（没过一律不许生效）。
顺序在 v9.1 之后更重要了：一个 260 米的候选如果先判影子，就会显示成「还在观察中」，
把一个显然需要用户处理的问题**藏起来**。

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
>
> **明确否决**：不用历史 `distinctDayCount` 回填影子天数。理由是**训练集 / 验证集不能同源** ——
> | 数据 | 承担什么 | 判据 |
> |---|---|---|
> | 历史 30 天轨迹 | **训练证明**：这个候选中心值得形成 | `AnchorLearner` 的五道门槛 |
> | 未来 7 天影子 | **上线验证**：候选是否**继续**稳定 | `ShadowValidator` 六条件 |
>
> 同一批 30 天数据既用来「证明中心值得形成」又用来「证明它继续稳定」，验证就被训练污染了。
> 顺序反过来也不行（先观察 7 天再训练）—— 那 7 天没有任何门槛过滤，等于把未验证的观测当基线。

### 4.4 影子验证：从「等 7 天」升级成六条件（v9.1）

只看 `elapsedDays` 的影子期本质上只是**等待**：一个已经漂了 200 米的候选，熬够 7 天照样会写进
`learned_place_models`。而影子期的**唯一目的**是回答「在不参与判定的情况下，这个候选是否继续稳定」，
那它就必须能观测到「不稳定」长什么样。

`ShadowValidator`（纯函数，`domain/location/ShadowValidation.kt`）判定六个条件，**全部成立才 `passed`**：

| # | 条件 | 常量 | 依据 |
|---|---|---|---|
| 1 | 连续观察 ≥ 7 个自然日 | `MIN_ELAPSED_DAYS = AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS` | 用户指定 |
| 2 | 候选中心漂移始终 < 10 m | `MAX_CENTER_DRIFT_METERS = 10.0` | 用户指定 |
| 3 | 期间**每天**都有有效样本 | `observedDays >= elapsedDays + 1` | 用户指定 |
| 4 | 环境来源持续 ≥ 2 类 | `MIN_AMBIENT_SOURCES = AnchorLearner.MIN_AMBIENT_SOURCES` | 用户指定（复用训练门槛，不另立数字） |
| 5 | 没有出现家庭/公司指纹冲突 | `MAX_CONFLICTS = 0` | 用户指定 |
| 6 | P90 离散度没有明显恶化 | `MAX_SPREAD_GROWTH_RATIO = 1.5` + `MIN_SPREAD_CEILING_METERS = 15.0` | ⚠️ **原文只说「没有明显恶化」，系数是本实现取的值** |

关于第 3 条的口径：`observedDays >= elapsedDays + 1` ⟺ 窗口内每一天都有样本（含起始日与今天）。
写成 `>= elapsedDays` 会放过「最后一天断档」—— 而断档恰恰是最需要被发现的情况。
关于第 4 条：看的是窗口内的**最小值**，不是最后一次的值 —— 中途掉到 1 类就该被发现。

**第 6 条的系数为什么是这两个数**：`AnchorLearner` 的绝对上限是 P90 ≤ 150 m，而候选形成时 P90
通常只有几十米；1.5 倍能在「正常采样噪声」与「地点本身变模糊了」之间留出余量。
但纯粹按倍数算会在 `firstP90` 很小时过分敏感（窗口初 1 米、后来 3 米＝「涨了 3 倍」却完全无害），
所以给一个 15 米的**绝对下限**兜住（固定点 GPS 的 P90 通常在 10 米内）。上限取
`max(firstP90 × 1.5, 15.0)`。

> ⚠️ 早期实现把下限写成了 `AnchorLearner.MAX_SPREAD_P90_METERS × 0.5 = 75 m`，
> 导致一个初值 5 m 的候选可以涨到 75 m 还判通过 —— 这条门槛**结构上几乎不可能失败**，
> 等于没做。任何「下限」都必须小到能让正常的恶化触发它。

#### 影子窗口的存储：一个窗口每天一行

`place_anchor_candidates` 自 DB v15 起是「一个影子窗口**每天一行**」：

- 同窗口同一天 → `updateCandidateObservation` 原地刷新（不插新行）；
- 跨天且候选没变 → 插新行，沿用同一个 `firstSeenAt`；
- 候选移动 ≥ `CANDIDATE_DEDUP_METERS`(=10 m) 或状态变化 → `firstSeenAt = now`（**重开窗口**）。

于是六个条件**全部可以从这组行重建**（`LearningModelDao.candidatesInWindow`），
不需要任何额外的累积状态 —— 这就是「模型必须能从原始数据全量重建」在候选层的落法。
候选表**仍然不参与任何实时判定**（`SHADOW` 不等于「半生效」）。

`CANDIDATE_DEDUP_METERS` 与 `MAX_CENTER_DRIFT_METERS` **故意同值 10 m**：
于是「候选漂移 ≥10 米」表现为**重开窗口**而不是判失败 —— 候选一旦移动，旧的验证成果作废，
必须重新观察 7 天，**比判失败更严**。改动任一常量必须一起改，否则会出现「结构上不可能失败的条件」。

#### v9.1 保留的四条设计

1. **配置锚点与学习锚点并存**，学习结果**绝不写回 `sites`**（用户配置永久权威）；
2. **候选表不参与实时判定**（`SHADOW` 是纯观察档，不是「半生效」）；
3. **老数据不猜来源**：`finalMinutesSource` 保持 `NULL` = 未知；
4. **学习失败不许带崩定位主链路**：异常只能「记日志 / 模型降级 / 回落既有算法」。

## 5. 阶段 3：行程状态机 + 候选事件 + 自适应采样（边界已冻结，实现未做）

### 5.1 边界：**只做这三件，不多做**

| 做 | 不做 |
|---|---|
| ① 行程状态机（家/通勤/在岗/离岗的**显式状态**） | ❌ **不接管班次画像**（`ShiftProfile`） |
| ② 候选事件（行程状态变迁的候选/确认语义） | ❌ 不改工资链路一个字段 |
| ③ 自适应采样（`SamplingTier` 随状态变） | ❌ 不做预测/回测（那是阶段 6） |

**为什么明确不接管班次画像**：班次先验（"今天该几点到"）一旦和行程状态机耦合，
两种错误会互相污染 —— 状态机误判"还没出发"会让班次先验偏移，班次先验偏移又会反过来
把状态机拉向"应该已经到了"。**两类错误必须能分开定位**，否则真机出问题时无法归因。
阶段 4 单独做班次画像，通过 `ShiftProfile` 注入，**状态机不读它**。

### 5.2 接口契约

```kotlin
// domain/journey/JourneyInput.kt —— 纯输入，包含判定所需的全部信息，不碰 Room
data class JourneyInput(
    val now: Long,
    val place: PlaceType?,          // 当前判定地点（来自 PlaceModelResolver，含"无"）
    val confidence: ConfidenceLevel, // 地点置信档位
    val motion: MotionState,        // 静止/步行/车载（已与定位解耦）
    val lastTransitionAt: Long,     // 上次状态变迁时刻（迟滞用）
    val secondsSinceFix: Long       // 距最近一次有效定位的秒数（断流检测）
)
```

`JourneyInput` **只描述事实**（时间、地点、运动、断流），**不含任何阈值或状态**。
理由：把状态塞进输入会让状态机变成"读自己写的值再写回去"，无法单测、无法回放。

```kotlin
// domain/journey/JourneyPhase.kt
enum class JourneyPhase { /* 见下方"11 个状态" */ }

// domain/journey/JourneyCandidate.kt
data class JourneyCandidate(
    val phase: JourneyPhase,
    /** 最早支持该状态的那条可靠证据的时刻 —— **事件确认后取它作为正式时刻** */
    val firstObservedAt: Long,
    /** 最近一条仍支持该状态的证据的时刻 —— 只用于判断"支持是否已经消失" */
    val lastSupportedAt: Long
)
```

**两个时刻的分工是硬约束**：

| 字段 | 语义 | 用途 |
|---|---|---|
| `firstObservedAt` | **最早**支持该状态的证据时刻 | 确认后**事件正式时刻取它**（不是确认时刻） |
| `lastSupportedAt` | **最近**仍支持该状态的证据时刻 | 判断支持链是否还在延续 |

为什么不用"确认时刻"当事件时刻：候选会因断流、重启而推迟确认，
用确认时刻会让"到岗 08:40"记成"到岗 09:12"，**误差直接进工资计算**。
所以候选一旦被确认，正式时刻回填到 `firstObservedAt`（与 `work_records.firstObservedAt` 同一口径）。

**11 个状态**（提案，阶段 3 开工时冻结）：

| 组 | 状态 | 说明 |
|---|---|---|
| 家 | `AT_HOME` / `LEAVING_HOME` / `ARRIVING_HOME` | 在宅 / 离宅候选期 / 归宅候选期 |
| 通勤 | `COMMUTING_TO_WORK` / `COMMUTING_HOME` | 单向通勤中 |
| 在岗 | `AT_WORK` / `LEAVING_WORK` / `ARRIVING_WORK` | 在岗 / 离岗候选期 / 到岗候选期 |
| 其他 | `AWAY` / `UNKNOWN` / `STALE` | 在别处 / 无法判定 / 断流（有明确原因，不是兜底） |

`UNKNOWN` 与 `STALE` 必须分开：前者是"证据矛盾、判不出来"，后者是"压根没有证据"。
混成一个的话，诊断页上看不出是数据缺失还是算法失灵。

### 5.3 自适应采样

`urgency` 分数（0..1）由状态驱动 → 映射到 `SamplingTier`：

| 档 | 触发 | 采样态度 |
|---|---|---|
| `STABLE` | 长时间在宅/在岗、置信高 | 拉到最省 |
| `NORMAL` | 常规在岗/在宅 | 基准 |
| `WATCH` | 候选期（`LEAVING_*` / `ARRIVING_*`） | 加密 |
| `TRANSITION` | 通勤中 | 最密 |
| `CRITICAL` | 断流中 / 首次到岗 / 置信骤降 | 最高频，且强制获取 |

**方向约束**：`urgency` 只能**加采样**，不能减到"低于兜底下限"——
省电绝不允许以丢掉状态变迁证据为代价。当前 `SamplingTuning` 的启发式**不删**，
降级为兜底：状态机给不出结论时回落到它（"学习失败不许带崩主链路"的同一原则）。

### 5.4 十二条完成标准

阶段 3 只有**全部**满足才算完成：

1. `JourneyInput` / `JourneyPhase` / `JourneyCandidate` / `SamplingTier` 均为 `domain/` 下的**纯 Kotlin**，零 Android 依赖；
2. 状态机是**纯函数**（同输入同输出），可脱离 Room / Context 单测；
3. 状态变迁必须带**迟滞**，不允许在阈值附近抖动；
4. 事件正式时刻取 `firstObservedAt`，**不是**确认时刻；
5. `UNKNOWN` 与 `STALE` 分开，且各自有可解释的原因；
6. `SamplingTier` 的 `urgency → tier` 映射有测试，且**边界值逐档断言**；
7. `urgency` 只增不减（相对兜底下限），有测试钉住；
8. 状态机**不读** `ShiftProfile` / 学习表（依赖方向单向）；
9. 断流期间状态**不许凭空跳变**（只能进 `STALE`），恢复后能接回原状态；
10. 采样档位变化必须落**诊断日志**（`LEARNING`/`JOURNEY` 类型），可在诊断页看到"为什么这一分钟采样加密了"；
11. 状态机**失败不影响定位主链路**（吞异常 + 回落 `SamplingTuning`）；
12. 真机跑满一个完整工作日，状态变迁序列可解释、无误跳变，且**功耗不高于 v9.1**。

## 6. 阶段 4~7（未做）

| 阶段 | 目标 | 主要产物（预计） |
|---|---|---|
| 4 | **班次画像** | `ShiftProfile`：到离岗时刻的分位数、时长分布、通勤时长分布 —— 用于给"今天该几点到"提供先验。**不与阶段 3 耦合**（见 §5.1） |
| 5 | **工资分项学习** | `PayrollComponentLearner`：从已确认工资条学分项规律（只采信 `SlipReconciler` 确认过的），输出经 `PayrollInputs` 注入，**`PayrollEngine` 签名不动** |
| 6 | **回测与预测** | `Backtest` / `Prediction`：历史窗口回放 + 预测落**独立表**（`modelVersion` + `trainedThrough` 必填） |
| 7 | **跨模型诊断** | 各模型相互佐证/矛盾时的诊断报告（"地点模型说在公司、指纹说在家"该信谁） |

**接续方式**：每个阶段都从 `LearningCoordinator` 的对应入口接进去，算法层放 `domain/` 纯函数，
编排放 `location/service/`（或阶段 5 的 `payroll/`）。别把算法写进编排器。

## 7. 改这个模块前的检查清单

1. **改 DB 版本** → 先在本地跑迁移链校验脚本做结构比对，**别上真机试**；新导出的
   `app/schemas/*.json` 要一起提交。
   ⚠️ 校验脚本在 `diagnostics/tools/` 下（`_mig14.py` / `_mig15.py`），而 **`diagnostics/` 被 `.gitignore` 排除**，
   所以它在仓库里**不存在**，换机器要重写。做法见 §6.1。
2. **动门槛** → 先看 `CONTRACTS.md` 的冻结项，确认它不属于「算法冻结项（勿改）」。
3. **新增取用/优先级逻辑** → 必须落在 `PlaceModelResolver`，不要在服务里另写 `if`。
4. **新增准入判定** → 必须引 `AnchorLearner.MAX_ACCURACY_METERS` 这类**已有常量**，不要重写数字。
5. **枚举语义方向**（"取较保守/较小"）→ 必须写测试，且**正反两序都断言** ——
   `Confidence.conservative` 当初就是这么写反的，不报错、只静默放松门槛。
6. **学习链路允许失败** → 编排层必须吞异常并记日志，绝不能让学习把定位主链路带崩。
7. **新增任何门槛/上限** → 必须**反证它能失败**：造一个刚好超标的输入，确认它被拦下。
   v9.1 的离散度门槛曾把下限写成 75 米，导致 P90 从 5 米涨到 75 米都判通过 ——
   这种"结构上不可能失败的门槛"写测试也发现不了（测试自己也是照同一套魔数写的），
   只能靠**问一句"什么输入能让它失败"**。答不上来就是没门槛。
8. **`NULL` 不等于零值** → `spreadP90Meters` / `finalMinutesSource` 这类可空列，
   读的时候必须区分"未知"与"明确为零"，且**未知一律按保守方向处理**。
9. **常量同源** → 同一个数字只允许有一个定义处（如 `MIN_ELAPSED_DAYS = AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS`、
   `CANDIDATE_DEDUP_METERS = ShadowValidator.MAX_CENTER_DRIFT_METERS`），
   并且同值关系要**写成测试**，不能只写在注释里。

### 6.1 迁移怎么在本地证死（不需要 instrumentation）

`diagnostics/` 被 gitignore，脚本不入库，所以把**做法**记在这里：

1. 用 `app/schemas/.../<旧版本>.json` 里的 `createSql`（把 `${TABLE_NAME}` 替换成 `tableName`，
   索引同样处理）在**内存 SQLite** 里搭出旧版本的真实结构；
2. 用正则从 `WorkTimeApplication.kt` 里把目标 `MIGRATION_x_y` 块的 `db.execSQL(...)` 字符串参数
   抠出来、拼回完整语句，**按版本顺序**执行（一条链要连着跑，别只跑最后一条）；
3. 与 KSP 新导出的 `<新版本>.json` **全表**比对列名 / 类型 affinity / NOT NULL / 默认值 /
   主键顺序 / AUTOINCREMENT / 索引。

坑（踩过两次）：

- Room 的主键在 JSON 里是 `entity.primaryKey.columnNames`，**不是**逐字段的 `primaryKeyPosition`；
- `PRAGMA table_info` 的 `pk` 列是**位次不是布尔**，要按它排序才是声明顺序；
- 索引在 `indices[].columnNames`；
- `CREATE TABLE IF NOT EXISTS` 会让「表已存在」静默通过 —— 所以第 3 步一定要比对**列集合**，不能只看建表成功。

真机侧只需验证 `PRAGMA user_version` 从旧值跳到新值、老表行数一行不差。
