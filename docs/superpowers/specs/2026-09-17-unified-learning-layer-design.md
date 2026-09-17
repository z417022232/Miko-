# 算法层重构：统一学习层与七阶段路线

> 状态：**阶段 1（收尾）+ 学习层地基 + 阶段 2（位置闭环）已落地**（v9.0 / code 28 / DB v14，commit `0152639`）；
> **影子验证强化已落地**（v9.1 / code 29 / DB v15）；
> **阶段 2 收口已落地**（v9.2 / code 30 / DB v16）：NULL 语义修正、10 米边界等号统一、
> 迁移校验器入库（`tools/verify_room_migration.py`）、学习状态展示 + 粘性回退入口。
> 阶段 3~7 **未做**。阶段 3 规格**v1 冻结后经两轮复查**（第一轮 4 P0 / 4 P1 / 1 P2，
> 第二轮 4 P0 / 2 P1，见 §5 修订记录）**已修订为 v3 并重新冻结**
> （§5.1 边界、§5.1.1 三层职责、§5.2 Reducer 接口、§5.3 采样、§5.4 完成标准、
> §5.5 影子对照、§5.6 持久化、§5.7 功耗验收、§5.8 实现顺序）——
> **修订全部发生在写代码之前，零返工成本；开工时照 v3 实现，不再做第三轮整体架构评审**。
> 本文是该路线在仓库里的**唯一源头** —— 之前只存在于对话里。
> 契约细节见 `.workbuddy/memory/CONTRACTS.md`；操作流程见 skill `android-worktracker-delivery`。

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

### 2.1.2 DB v16 追加（v9.2）：用户偏好独立成表

| 表 / 改动 | 主键 | 作用 |
|---|---|---|
| `place_learning_preferences`（新表） | `placeId`(= `sites.id`) | 用户是否允许学习锚点参与自动校准 |

**只建表、不写任何数据。** 因为「**缺行 = 允许**」（`PlaceLearningPreference.DEFAULT_AUTO_APPLY_ENABLED`），
老库升上来天然是全启用，零回归是**结构上**成立的 —— 迁移跑完表是空的，
`withLearnedAnchors` 拿到的偏好列表为空，取锚点逻辑与 v15 逐字节相同。

刻意不写「给每个已有地点补一行 `autoApplyEnabled = 1`」：那会在迁移里制造一批
**用户从未表过态**的记录，之后无法区分「用户明确开过」和「迁移顺手写的」。

**为什么必须独立成表（本阶段最容易写歪的一处）。** 三件语义完全不同的事：

| 层 | 存在哪 | 回答的问题 | 谁改它 |
|---|---|---|---|
| 模型是否有效 | `learning_model_meta.status` | 这个版本被作废了吗 | **算法**（`RETIRED` / `INVALID`） |
| 算法是否通过 | `learned_place_models.autoApplied` | 影子验证通过了吗 | **算法**（每轮学习重算） |
| 用户是否允许用 | `place_learning_preferences.autoApplyEnabled` | 我愿意让它自动校准吗 | **只有用户** |

混层的后果是**谎话**：用户按了暂停，模型却被标成 `RETIRED`。
之后无法区分「模型坏了」与「用户关了」，而这两件事该做的处理完全不同
（前者要重训，后者只需等用户开回来）。

由此推出两条**刻意不对称**的规则（见 `PlaceLearningPreferencePolicy`）：

- **停用只写偏好**：`modelAutoAppliedAfter = null`（不动模型），`reopenShadowWindow = false`。
  用户偏好没有资格改写「算法是否通过」这个事实；停用表现在**取用时**
  （`PlaceModelResolver.resolve` 第 0 步直接回落配置锚点）。学习照常观察、照常落候选、照常升版本。
- **重新开启要求重新验证**：吊销 `autoApplied`（`false`）**并且**重开前向影子窗口。
  两条缺一不可 —— 只吊销 `autoApplied` 时，窗口里已攒够 7 天，下一轮学习立刻重新判通过；
  只重开窗口时，模型带着旧的 `autoApplied = true` 直接生效。
  `PlaceLearningPreferencePolicyTest.theTwoRevocationStepsAreBothRequiredAndNeitherIsRedundant`
  就是钉这一条的。

**粘性停用 + 立刻生效**：用户停用后判定必须**马上**回到用户设置。检测路径的生效地点集合有
60 秒缓存，所以 UI 改开关时通过 `ServiceRecovery.invalidateSiteCache()`
（`ACTION_INVALIDATE_SITE_CACHE`）显式失效缓存 —— 且**只在服务已在运行**时下发
（判据是 12 分钟心跳窗口）。否则 `startForegroundService` 会把整个定位服务拉起来：
「点了一个复选框 → 定位被打开」是比缓存过期严重得多的副作用。

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

### 4.5 阶段 2 收口（v9.2 / code 30 / DB v16）

这一轮**没有新算法**，全部是"把已有的东西写准"。四处修正 + 一处补齐：

| # | 问题 | 处理 |
|---|---|---|
| ① | `ShadowValidation.latestSpreadP90Meters` 是对外快照，仍被 `?: 0.0` 压成"零米完美集中" | 改回 `Double?`；空窗口/无读数 = `null`（未知）。判定本来就对，泄漏的是**输出层语义** |
| ② | 10 米漂移门槛写成 `>=`：9.999m 通过、10.0m 失败，与常量名和文案（"≤10 允许"）相反 | 改为 `> 失败`、`<= 允许`，抽出纯函数 `ShadowValidator.driftExceedsLimit` 作为**唯一边界执行点**；连带 `AnchorLearningService` 的同候选比较由 `<` 改 `<=` |
| ③ | 迁移校验脚本只在 `diagnostics/`（gitignore）里，换机器即失传 | 通用化为 `tools/verify_room_migration.py` 并入仓库，支持 `--old-schema/--new-schema/--migration-source/--from/--to` 与多跳链 |
| ④ | 学习在跑，但用户看不到、也关不掉 | 地点管理页补**最小学习状态**（阶段/训练样本/前向验证进度/候选偏移/中心漂移/环境来源/离散度 + "当前判定使用你设置的位置"）与 `[停用学习校准] / [重新开启自动校准]` |
| ⑤ | 用户开关需要一个**粘性**落点 | 新增 `place_learning_preferences`（DB v16），见 §2.1.2 |

**④ 的一个必须说清的取舍**：界面上多出一个 `PENDING_APPLY`（"验证已通过，尚未生效"）阶段。
原因是"算法的动作"与"实际取用"**可能不一致**：`AnchorUpdatePolicy` 说可以自动平滑，
而 `PlaceModelResolver` 还要再看一眼置信度（≥0.70）——观察满 8 天、环境 2 类但样本不多时
置信度只有 0.64，此时算法动作是 `AUTO_SMOOTH` 而锚点其实没被取用。
不显式表达这一格，界面就会照着算法动作显示"已启用"而判定没变 —— 正是"界面说的和做的不一样"。

**"当前用的是哪个锚点"的真值来源**：`PlaceModelResolver.resolve(...)` 返回 `EffectiveAnchor(point, source)`，
展示层直接读 `source`，**不许**拿取到的点和 `learnedAnchor` 比较。
两个锚点重合时那种猜法会错，而且错得看不出来 —— 直到某天两者不相等才以
"界面说 A、实际用 B"的形式爆掉。为此把 `effectiveAnchor` 的两个重载都收敛到 `resolve` 一份实现。

## 5. 阶段 3：行程状态机 + 候选事件 + 自适应采样（**规格 v3 已重冻**，实现未做）

> **修订记录**：
>
> **第一轮（2026-09-17 上午，v1 → v2）**：v1 冻结后未写一行阶段 3 代码即复查，
> 发现以下契约矛盾，全部修正后重新冻结。修订发生在开工前，零返工成本。
>
> | 级 | 问题 | v1 的错误 | v2 的修正 |
> |---|---|---|---|
> | P0-1 | 纯状态机缺「上一状态」 | `JourneyInput` 只有一拍观测，却要求引擎实现候选累计、迟滞、还差几拍、断流恢复、重启接回 | 改为 **Reducer**：`reduce(previous: JourneySnapshot, observation, config)`（§5.2） |
> | P0-2 | 产物归属冲突 | `JourneyDecision` 内含 `samplingTier`，与「三单元互不调用」矛盾 | 拆为 **状态产物 `JourneyTransition`** + **采样产物 `SamplingDecision`**，Coordinator 只组合（§5.1.1/§5.2.3） |
> | P0-3 | 缺临时离岗 | 11 状态无 `TEMP_LEAVE`/`OTHER_STOP`，完成目标「临时离岗与正式下班可区分」无载体 | **13 状态**；且查明**旧机本就有 `TEMP_LEAVE`**（下班确认候选期），v1 丢了它是回归（§5.2.4） |
> | P0-4 | 地点输入丢决策等级 | 只传 `place + confidence`，`CONFIRMED HOME` 与 `MAINTAINED HOME` 无法区分，弱维持可能错误推进状态 | 输入增加 **`placeDecision: FusedDecision`**，`MAINTAINED` 只许维持、不许推进（§5.2.1） |
> | P1-1 | 候选信息不足 | 三字段候选撑不起「还差几拍」与迟滞解释 | 候选扩展 **supportCount / accumulatedStableMillis / evidenceSources / strongestDecision**；**门槛用时长、拍数只做诊断**（§5.2.5） |
> | P1-2 | 重启恢复无持久化契约 | 完成标准要求「重启后候选不重置」，但没定义快照存哪、时间回拨怎么办 | **`journey_shadow_state` 独立表（DB v17）**，含时间回拨与版本不匹配的重置规则（§5.6） |
> | P1-3 | CRITICAL 无限时退避 | 「最高频且强制获取」在定位关闭时可无限高频 | **CRITICAL ≤10 分钟**（复用 `HARD_BURST_CAP_MINUTES`）+ 冷却 + 指数退避 + 权限关闭不强制（§5.3） |
> | P1-4 | 影子对比缺映射 | 新旧两套枚举非一一对应，逐字段直比会产生大量无意义差异 | 冻结**新旧状态映射表** + 影子日志必含 **`correlationId`**（§5.5） |
> | P2 | 功耗验收不可证 | 「不高于 v9.2」只靠电量百分比，受网络/屏幕/其他 App 干扰 | 改为 **9 项可计数指标 + 3 条判据**，电量仅辅助（§5.7） |
>
> **第二轮（2026-09-17 下午，v2 → v3）**：v2 仍不可开工 —— 两个会丢真机状态
>（P0-1/P0-2）、一个会漏记事件（P0-3）、一个缺真实数据来源（P0-4）。全部修正：
>
> | 级 | 问题 | v2 的错误 | v3 的修正 |
> |---|---|---|---|
> | P0-1 | `activeWorkSession` 放错层 | 作为**外部事实**却放在状态机自己的记忆 `JourneySnapshot` 里。影子期正式会话由旧机维护，新机快照里的该字段不会自动跟随旧机 —— Coordinator 每拍强行改 Snapshot 又违反「不修改纯单元产物」；切正式机后还会形成循环（状态机靠它判下班，它又等状态机确认下班才结束） | 移入 **`JourneyObservation.hasActiveWorkSession`**（Coordinator 每拍从权威工作会话读取再构造观察）；**从 Snapshot 删除**。Observation=外部事实、Snapshot=内部记忆，职责才完整（§5.2.1） |
> | P0-2 | DB v17 字段不完整 | 实体缺 `lastConfirmedPhase` 列 —— 恢复规则要求 STALE 恢复/时间回拨接回该字段，库里却存不下；候选的解释字段（evidenceSources/strongestDecision/confidence）不持久化，恢复时**伪造** `MAINTAINED`+空集 —— 违反「不把未知伪装成确定值」，重启前后同一候选的解释会变 | 实体补 **`lastConfirmedPhase`** + **完整持久化候选解释三字段**（evidenceSources 按稳定顺序序列化如 `GNSS,WIFI,CELL`，数据量极小，不为省三列牺牲恢复一致性）（§5.6） |
> | P0-3 | 单 `confirmedEvent` 丢事件 | 旧机**同一拍可产多个事件**（实测 `updateTemporaryLeave`：确认下班时同拍先 `CompanyDeparture` 后 `HomeArrival`；断流后「公司在→家」一拍也是两事件）。单可空字段只能留一个 = 回归 | 改为 **`confirmedEvents: List<JourneyEvent>`** + 排序硬约束（`occurredAt` 不倒序、同类型不重复、后件不早于前件）；影子对比从「事件是否非空」升级为**事件序列逐项比**（§5.2.3/§5.5） |
> | P0-4 | `MotionPhase` 无真实数据来源 | v2 定义「静止/步行/车载」，但采集层实测只有 SignificantMotion 触发 + 加速度阈值（`MotionEvidenceController`），只能证明「发生了明显运动」，**分不出步行/车载**；且回调时间是 `SystemClock.elapsedRealtime()`（单调钟），不是业务 epoch | 第一版收敛为 **`STATIONARY / MOVING / UNKNOWN`**；输入增 **`motionObservedAt: Long?`** 并冻结时钟域规则（业务时间一律 epoch millis，elapsedRealtime 只做进程内超时，**两钟不许互转互比**）（§5.2.1）。将来要步/车再单建运动分类器（Activity Recognition / 定位速度 / 多源联合），**没有分类器前枚举不得表达超出证据能力的事实** |
> | P1-1 | TEMP_LEAVE 规则循环 | v2 用 `activeWorkSession 已结束 → COMMUTING_HOME` 判正式下班，但正式会话正**等待** `CompanyDeparture` 事件才结束 —— 前置条件依赖尚未发生的产物 | 正式下班改由**路径 + 时长 + 到家证据共同确认**（持续远离 / 到家 / 超 `tempLeaveMaxMillis` → 确认 `CompanyDeparture` → 会话结束 → `COMMUTING_HOME`/`ARRIVING_HOME`）；`hasActiveWorkSession` 只用于区分 `AWAY`（休息日外出）与 `OTHER_STOP`（工作期间外出），**不单独决定 TEMP_LEAVE vs 正式下班**（§5.2.4） |
> | P1-2 | Coordinator 约束表述过严 | 「不许出现任何 `if`」字面遵守会把普通 IO 分支（row 为 null 取初始快照 / 版本不符重置 / 异常回落）伪装成难懂的表达式 | 改为「**不得包含地点、状态转换、候选确认、采样档位等业务判定；允许数据存在性、版本、错误恢复和 IO 分支**」（§5.1.1） |
>
> 附带修正（v1 稿自身笔误，第一轮复查时实测核对仓库发现）：
> v1 的 `place: PlaceType?` 引用了**语义错位**的类型（`PlaceType` 真实存在于
> `domain/location/PlaceModel.kt`，但它是 `WORK/NON_WORK` 的**站点类型**，不是判定地点 ——
> 判定地点的正确类型是融合层的 `ResolvedPlace`）；`MotionState` 则**在仓库中不存在**
> （运动与地点证据已解耦、归采集层，v2 新建 `MotionPhase`）。冻结稿引用错位/不存在的类型，
> 契约从第一行就落不了地。
>
> **第三轮（2026-09-17 晚，v3 → v3.2）**：第 3 步（`AdaptiveSamplingPolicy`）**开工预检**
> 报回三处"规格给了要求、没给数值/接口"的缺口。三处都**不补就没法写对**，
> 补法与全部冻结数值见 §5.3.1：
>
> | 级 | 问题 | 不补会怎样 |
> |---|---|---|
> | 阻塞 1 | `RetryState` 只有一个 `lastAttemptAt`，却被要求实现"单次 CRITICAL 最长 10 分钟" | 每次重试刷新起点 ⇒ **永远到不了上限**；不报错、日志里也看不出来（静默失效） |
> | 阻塞 2 | `SamplingDecision` 只回一个 `retryAttempt: Int` | 调用方要自己拼 `currentCriticalStartedAt`/`lastAttemptAt`/`lastCriticalEndedAt` ⇒ 重试业务逻辑漏进编排层（违反 §5.1.1） |
> | 阻塞 3 | 只说"urgency 0..1 映射五档""1/2/4 分钟…"，**没有数值、没有封顶** | 实现者只能现场造魔数；逐档断言无从下手；`1 shl attempt` 还会在 attempt≥31 时溢出成负数 |
>
> 补丁内容：`RetryState` 增 `currentCriticalStartedAt`；`SamplingDecision.retryAttempt` 换成
> `nextRetryState: RetryState`（不留冗余平行字段）；五档等距边界 + 状态基础紧迫度 +
> 冷却封顶 16 分钟全部冻结进 `SamplingContract` 并由 `SamplingContractTest` 逐条钉住。

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

⚠️ **边界澄清（v3）**：状态机**可以读「是否存在活动工作会话」**（当天已确认到岗、
未确认下班 —— `WorkdayClock.ONGOING_STATES` 的口径），作为**观察事实**
（`JourneyObservation.hasActiveWorkSession`，二轮 P0-1）。它不是学习先验，
但注意它的用途边界（§5.2.4）：**只用于区分「休息日外出 vs 工作期间外出」**
（`AWAY` vs `OTHER_STOP`），**不单独决定临时离岗 vs 正式下班** ——
正式下班由路径、时长、到家证据共同确认，拿会话结束当前置条件会成循环依赖。
读事实 ≠ 读画像：画像回答"**应该**几点"，事实回答"**已经**发生了什么"。

### 5.1.1 三层分离：谁算什么（冻结的职责边界）

阶段 3 落成**三个各自可单测的单元**，不允许合并、也不允许互相调用：

| 单元 | 位置 | 职责（**v2 修正后的签名**） | 硬约束 |
|---|---|---|---|
| `JourneyEngine` | `domain/journey/` | **纯 Reducer**：`reduce(previous: JourneySnapshot, observation: JourneyObservation, config: JourneyConfig): JourneyTransition` | 纯函数、无 Room/Context/时间源；同输入同输出 |
| `AdaptiveSamplingPolicy` | `domain/journey/` | **纯映射**：`decide(nextSnapshot: JourneySnapshot, health: EvidenceHealth, now: Long, retry: RetryState, fallbackTier: SamplingTier): SamplingDecision` | 只做 `状态+健康+重试 → 档位` 的查表/分段，**不含状态判断** |
| `JourneyCoordinator` | `location/service/` | **编排**：读库恢复快照、喂观察给引擎、喂快照给采样策略、把两个产物**组合**成 `JourneyRuntimeDecision`、持久化影子快照、写对比日志 | 只做 IO 与转发；**不得包含地点、状态转换、候选确认、采样档位等业务判定**；允许数据存在性、版本、错误恢复和 IO 分支（`row == null` 取初始快照、`modelVersion != CURRENT` 重置、异常回落兜底都是合法 IO 分支，不许为了字面遵守契约把它们伪装成难懂的表达式）；**不许修改两个纯单元的产物内容**，只能组合 |

v1 的 `JourneyDecision`（内含 `samplingTier`）已废弃 —— 它迫使三选一：
要么引擎调采样（违反互不调用）、要么引擎自己算档（违反职责）、要么 Coordinator 改产物（违反只组合）。
v2 把产物拆开，三种违规都不再可能：

```kotlin
/** Coordinator 组合后的运行时快照 —— 两个纯单元的产物原样并排，不改内容 */
data class JourneyRuntimeDecision(
    val transition: JourneyTransition,   // 状态机产物
    val sampling: SamplingDecision       // 采样策略产物
)
```

为什么把采样单独拆出来而不是塞进状态机：
**"判定"与"为了判定而多花多少电"是两个可以分别出错的东西**。
合在一起时，一次"状态判错了"会顺带把采样档也带错，而采样档错了会反过来让状态更难判对 ——
两个错误互相掩盖。拆开后可以单独回答"这一分钟为什么加密采样"，
且 `SamplingTier` 的档位边界能逐档断言（§5.4 第 7 条）。

### 5.2 接口契约（v3）

#### 5.2.1 输入三件套：观察 / 快照 / 配置

```kotlin
// domain/journey/JourneyObservation.kt —— 一拍的**纯观测**，只描述外部事实，不含状态、不含阈值
data class JourneyObservation(
    val now: Long,
    val place: ResolvedPlace,            // 融合层判定地点（HOME/COMPANY/OTHER/MOVING/UNKNOWN）
    val placeDecision: FusedDecision,    // ⭐ 一轮P0-4：CONFIRMED / MAINTAINED / UNKNOWN，不许省
    val confidence: Double,              // 融合层原始置信分值（0..1）
    val motion: MotionPhase,             // ⭐ 二轮P0-4：STATIONARY / MOVING / UNKNOWN（见下注）
    val motionObservedAt: Long?,         // ⭐ 二轮P0-4：最近运动判定的 epoch 时刻（时钟域见下注）
    val secondsSinceFix: Long,           // 距最近一次有效定位的秒数（断流检测）
    val hasActiveWorkSession: Boolean,   // ⭐ 二轮P0-1：当天已确认到岗且未确认下班 —— 外部事实，归观察
    val distanceToHomeMeters: Double?,   // 与旧 Fix.homeDistanceMeters 同源
    val distanceToWorkMeters: Double?    // 与旧 Fix.companyDistanceMeters 同源
)
```

- `ResolvedPlace` / `FusedDecision` 是 `domain/evidence/EvidenceModels.kt` 的**既有类型**，直接复用。
- `confidence` 用**原始分值**而非 UI 档位（`ConfidenceLevel` 是呈现层从同一分值派生的，
  引擎门槛要精确值，不依赖 UI 枚举 —— 档位线改了不该影响状态机）。

**`hasActiveWorkSession` 为什么在 Observation 而不在 Snapshot（二轮 P0-1 的落点）**：
它是「当天已确认到岗且未确认下班」的**当前事实**，权威在旧机/工作会话一侧。
放进 Snapshot（状态机自己的记忆）会产生两个真实故障：
影子期正式会话由旧机维护，新机快照里的该字段**不会自动跟随旧机**，Coordinator
每拍强行改 Snapshot 又违反「不修改纯单元产物」；切换正式机后还会形成**循环依赖** ——
状态机靠它判「正式下班」，它又等状态机确认 `CompanyDeparture` 才结束。
正确分工：**Observation = 外部事实（Coordinator 每拍从权威工作会话读取后构造），
Snapshot = 新状态机自己的内部记忆**。

**`MotionPhase` 的证据边界（二轮 P0-4 的落点，硬约束）**：

```kotlin
enum class MotionPhase { STATIONARY, MOVING, UNKNOWN }
```

采集层实测只有 **SignificantMotion 触发 + 加速度阈值**（`MotionEvidenceController`），
它们只能证明「发生了明显运动」，**分不出步行和车载**。在补上真正的运动分类器
（Activity Recognition / 定位速度 + 稳定时长 / 多源联合）之前，
**枚举不得表达超出证据能力的事实** —— 第一版只有三档，将来确有分类器再扩。
`UNKNOWN` = 尚无运动判定或判定已过期（`motionObservedAt` 距 `now` 超时）。

**时钟域规则（硬约束）**：
- 状态机业务时间**一律 epoch millis**（`JourneyObservation.now` / 所有事件时刻 / 持久化列）；
- `SystemClock.elapsedRealtime()`（单调钟，采集层回调现用）**只用于进程内超时计算**；
- **两种时钟不许互相比较、不许换算成同一事件时间**。编排层负责在构造 Observation 前
  把运动判定换算到 epoch 域（如 `System.currentTimeMillis() - (elapsedNow - elapsedAt)` 的
  现场换算，误差毫秒级、只影响「运动判定是否过期」的判断，不进事件时刻）。

**`placeDecision` 的推进规则（一轮 P0-4 的落点，硬约束）**：

| 决策 | 允许 | 禁止 |
|---|---|---|
| `CONFIRMED` | 推进状态转换（含候选累计） | — |
| `MAINTAINED` | **只维持**上一状态 | ❌ 推进任何转换（否则弱维持可把 `COMMUTING_HOME` 错推成 `ARRIVING_HOME`） |
| `UNKNOWN` | 维持 + 降置信 | ❌ 推进；连续 `UNKNOWN` 超时进 `STALE` |

这与融合引擎既有语义同源（`CONFIRMED_GNSS` 单次即确认、环境证据需连续 2 次）——
新状态机不另立证据规则，只是把等级显式带进输入。

```kotlin
// domain/journey/JourneySnapshot.kt —— 状态机的全部记忆（纯内部状态），可整体持久化/恢复（§5.6）
data class JourneySnapshot(
    val phase: JourneyPhase,             // 当前状态
    val candidate: JourneyCandidate?,    // 进行中的候选（含累计，见 §5.2.5）
    val lastConfirmedPhase: JourneyPhase?, // 上一个**已确认**的状态（STALE 恢复后接回这里；二轮P0-2：必须持久化）
    val lastTransitionAt: Long           // 上次状态变迁时刻（迟滞用）
    // ⚠️ 二轮P0-1：activeWorkSession 已移入 JourneyObservation —— 外部事实不进内部记忆
)
```

```kotlin
// domain/journey/JourneyConfig.kt —— 全部阈值集中在此，引擎内部零魔数
data class JourneyConfig(
    val staleAfterSeconds: Long,          // 断流进 STALE 的门槛（对齐旧 EvidenceContinuityPolicy 窗口）
    val arrivalRequiredMillis: Long,      // 到岗候选确认所需累计稳定**时长**（不是拍数，见 §5.2.5）
    val departureRequiredMillis: Long,    // 离岗候选确认所需累计稳定时长
    val candidateExpiryMillis: Long,      // 候选过期（对齐旧 CANDIDATE_EXPIRE_MILLIS）
    val tempLeaveMaxMillis: Long,         // TEMP_LEAVE 超时上限：仍未归则视为正式下班（见 §5.2.4）
    val motionExpirySeconds: Long         // ⭐ 二轮P0-4：运动判定过期门槛（超过即 MotionPhase=UNKNOWN）
)
```

#### 5.2.2 引擎：纯 Reducer

```kotlin
// domain/journey/JourneyEngine.kt
fun reduce(
    previous: JourneySnapshot,
    observation: JourneyObservation,
    config: JourneyConfig
): JourneyTransition
```

**同样的（快照, 观察, 配置）三元组永远产生同样的结果 —— 这仍然是纯函数**。
把上一状态放进输入不会破坏纯度，反而是纯状态机的标准形式（v1 只传一拍观测，
却要求引擎实现候选累计/迟滞/还差几拍/断流恢复/重启接回 —— 单凭一拍在数学上做不到，P0-1）。

#### 5.2.3 状态产物：`JourneyTransition`

```kotlin
// domain/journey/JourneyTransition.kt —— JourneyEngine 的唯一产物（冻结）
data class JourneyTransition(
    val snapshot: JourneySnapshot,            // 下一拍快照（含更新后的候选累计）
    val confirmedEvents: List<JourneyEvent>,  // ⭐ 二轮P0-3：事件列表；空 = 这一拍只更新状态，不确认任何事件
    val reasonCodes: Set<JourneyReason>,      // 机器可断言的原因码（测试与影子对比用）
    val explanation: String                   // 人话原因（方案 §一 原则 6：每次判定必须能解释依据）
)
```

**为什么必须是列表（二轮 P0-3 的落点）**：旧机**同一拍可产多个事件** ——
实测 `TrajectoryAnchorEngine.updateTemporaryLeave` 在确认下班的那一拍同时发出
`CompanyDeparture` + `HomeArrival`（在公司后定位断流、下一条可靠定位直接在家时，
一拍需要把「离岗」和「到家」一起补记）；「离家后长断流、下一拍直接到公司」同理。
单可空字段只能留一个 = **事件漏记**，是对旧机的回归。

**事件列表的三条排序硬约束（测试逐条钉住）**：

1. 列表内事件按 `occurredAt` **升序**，后一个事件的 `occurredAt` **不得早于**前一个；
2. **同类型事件不得在同一拍重复**（同一拍两个 `HomeArrival` 是 bug）；
3. `HomeArrival.occurredAt >= CompanyDeparture.occurredAt` 这类**顺序规则**由引擎保证
   （与旧机「到家时刻不得早于离岗时刻，否则拒绝该到家事件」同口径）。

```kotlin
// domain/journey/JourneyEvent.kt —— 与旧机 4 事件对齐 + 临时离岗 2 个新事件
sealed class JourneyEvent(
    open val occurredAt: Long,
    open val confirmedAt: Long
) {
    data class HomeDeparture(override val occurredAt: Long, override val confirmedAt: Long) : JourneyEvent(occurredAt, confirmedAt)
    data class CompanyArrival(override val occurredAt: Long, override val confirmedAt: Long) : JourneyEvent(occurredAt, confirmedAt)
    data class CompanyDeparture(override val occurredAt: Long, override val confirmedAt: Long) : JourneyEvent(occurredAt, confirmedAt)
    data class HomeArrival(override val occurredAt: Long, override val confirmedAt: Long) : JourneyEvent(occurredAt, confirmedAt)
    // ⭐ 新：临时离岗的起止 —— 「临时离岗与正式下班可区分」的直接载体
    data class TempLeaveStart(override val occurredAt: Long, override val confirmedAt: Long) : JourneyEvent(occurredAt, confirmedAt)
    data class TempLeaveEnd(override val occurredAt: Long, override val confirmedAt: Long) : JourneyEvent(occurredAt, confirmedAt)
}
```

**`occurredAt` 与 `confirmedAt` 的分工是硬约束**（沿用 v1，不变）：

| 字段 | 语义 | 用途 |
|---|---|---|
| `occurredAt` | 候选的 `firstObservedAt`：**最早**支持该状态的证据时刻 | **事件正式时刻取它**；与 `work_records.firstObservedAt` 同口径 |
| `confirmedAt` | 支持链命中门槛的那一拍 `now` | **只用于诊断/延迟统计**（"这次迟到几拍才确认"） |

为什么不用确认时刻当事件时刻：候选会因断流、重启而推迟确认，
用确认时刻会让"到岗 08:40"记成"到岗 09:12"，**误差直接进工资计算**。

`confirmedEvents` 为空而 `snapshot.phase` 变了是**合法且常见**的：候选期内的中间态
（`LEAVING_*` / `ARRIVING_*`）就是这种形态 —— 状态已经变了，但还没到"确认事件"的那一刻。

#### 5.2.4 十三个状态（v2：11 → 13，补 `TEMP_LEAVE` / `OTHER_STOP`）

| 组 | 状态 | 说明 |
|---|---|---|
| 家 | `AT_HOME` / `LEAVING_HOME` / `ARRIVING_HOME` | 在宅 / 离宅候选期 / 归宅候选期 |
| 通勤 | `COMMUTING_TO_WORK` / `COMMUTING_HOME` | 单向通勤中 |
| 在岗 | `AT_WORK` / `LEAVING_WORK` / `ARRIVING_WORK` | 在岗 / 离岗候选期 / 到岗候选期 |
| 中间 | `TEMP_LEAVE` / `OTHER_STOP` | **确认的临时离岗（会回来）** / 活动会话期间在别处停留 |
| 其他 | `AWAY` / `UNKNOWN` / `STALE` | 在别处 / 无法判定 / 断流（有明确原因，不是兜底） |

**为什么必须加 `TEMP_LEAVE`（一轮 P0-3）**：旧机 `TrajectoryAnchorEngine` 本就有 `"TEMP_LEAVE"`
状态，但它实际是**下班确认的候选期** —— 回公司→`WORKING`、离够久/到家→`FINISHED`，
「临时离开」和「正式下班」**共用同一个状态的两个出口，从未真正区分**。v1 的 11 状态把
它整个丢了，是回归。v3 把这条糊涂账拆开：

```
离开公司 → LEAVING_WORK（离岗候选期）
  ├─ 短期在外停留、尚未到家 ────────────→ TEMP_LEAVE（临时离岗，会话未结束）
  │     ├─ 重新回公司：TempLeaveStart + TempLeaveEnd 闭环 → ARRIVING_WORK → AT_WORK（会话继续）
  │     └─ 持续远离 / 到家 / 超 tempLeaveMaxMillis ↓（与右侧汇合）
  └─ 持续远离公司 / 到达家庭 / 超 tempLeaveMaxMillis
        → 确认 CompanyDeparture → 工作会话结束 → COMMUTING_HOME → ARRIVING_HOME → AT_HOME
```

**判定规则（二轮 P1-1 的落点，硬约束）**：

1. **正式下班由「路径 + 时长 + 到家证据」共同确认**（持续远离公司 / 合格到家证据 /
   离开时长超 `tempLeaveMaxMillis`，任一成立 → 确认 `CompanyDeparture`），
   **不是**由 `hasActiveWorkSession` 是否结束来前置判断 ——
   工作会话正**等待** `CompanyDeparture` 才结束，拿它当离岗判据是
   「会话结束依赖事件、事件又依赖会话结束」的**循环**；
2. `hasActiveWorkSession` 的**唯一用途**是区分休息日外出与工作期间外出：
   无会话的在别处 = `AWAY`，有会话的在别处停留 = `OTHER_STOP`；
3. 短期在外、既未到家也未持续远离 = `TEMP_LEAVE`；重新回公司 → `TempLeaveStart` +
   `TempLeaveEnd` 闭环（`occurredAt` 分别取离岗候选的 `firstObservedAt` 与回归候选的
   `firstObservedAt`），工作会话**继续**；
4. **到家永远优先判 `ARRIVING_HOME`**，与 `TEMP_LEAVE` 不竞争（回家吃饭 = 到家 +
   之后 `LEAVING_HOME` → `COMMUTING_TO_WORK`，不算临时离岗）。

`OTHER_STOP` 与 `AWAY` 的分工：前者 = **活动会话期间**在别处停留（去银行/送货，预期回）；
后者 = 无活动会话时的在别处（休息日外出）。判定唯一差别就是 `hasActiveWorkSession`（观察事实）。

`UNKNOWN` 与 `STALE` 必须分开：前者是"证据矛盾、判不出来"，后者是"压根没有证据"。
混成一个的话，诊断页上看不出是数据缺失还是算法失灵。

三个候选期（`LEAVING_*` / `ARRIVING_*`）**不是**"过渡态"这种含糊说法，
它们各自有明确语义：**已观察到离开该地点的证据，但支持链尚未达到确认门槛**。
确认门槛与迟滞一并由 `JourneyEngine` 持有（集中在 `JourneyConfig`），
`JourneyTransition.explanation` 必须能说出"还差几拍 / 还差多久"。

#### 5.2.5 候选对象（v2：三字段 → 八字段）

```kotlin
// domain/journey/JourneyCandidate.kt
data class JourneyCandidate(
    val targetPhase: JourneyPhase,          // 候选指向的目标状态（v1 叫 phase，语义模糊，改名）
    val firstObservedAt: Long,              // 最早支持该状态的那条可靠证据的时刻 —— **事件确认后取它作为正式时刻**
    val lastSupportedAt: Long,              // 最近一条仍支持该状态的证据的时刻 —— 只用于判断"支持是否已经消失"
    val supportCount: Int,                  // ⭐ 支持拍数 —— **只做诊断与解释**（"还差几拍"）
    val accumulatedStableMillis: Long,      // ⭐ 累计稳定**时长** —— **确认门槛只看它**
    val evidenceSources: Set<EvidenceSource>, // ⭐ 支持链的证据来源（解释用）
    val strongestDecision: FusedDecision,   // ⭐ 支持链中出现过的最强决策等级
    val confidence: Double                  // ⭐ 支持链最强置信
)
```

**拍数与时长同时保留、各司其职（P1-1 的落点）**：
1 分钟采样和 30 秒采样的「三拍」不是同一时长 —— **确认门槛必须用 `accumulatedStableMillis`**，
拍数只进诊断文案与影子对比。只用拍数会随采样档漂移：CRITICAL 档下三拍 = 90 秒，
STABLE 档下三拍 = 30 分钟，同一个门槛横跨两个数量级。

**v3.1 实现补记（第 2 步写 `JourneyEngine` 时发现，三处契约补丁）**：

| # | 缺口 | 补法 |
|---|---|---|
| 1 | 八字段**区分不出**「连续两拍支持」与「中间断过一拍又支持」—— 两者的 `firstObservedAt` / `lastSupportedAt` / `supportCount` 可以完全相同，于是空窗时长会被当成稳定时长累计（一次断流就能把候选泡到门槛） | 候选补第九字段 **`lastUnsupportedAt: Long?`**（null = 支持链连续；非 null = 最近一次支持中断的时刻）。过期判定仍看 `lastSupportedAt`，所以「保留最早证据」与「空窗不计入稳定时长」可同时成立。⚠️ 第 5 步 DB v17 必须加列 `candidateLastUnsupportedAt`，否则重启后第一拍会把空窗当稳定时长 |
| 2 | `JourneyCandidate.evidenceSources` **没有数据来源**：观测不带来源，候选的来源字段只能永远为空（"解释"是假的），§5.6 要求持久化的三字段里有一个无从填写 | `JourneyObservation` 补 **`evidenceSources: Set<EvidenceSource>`**（融合层说这一拍靠哪些源定的，状态机就记哪些源，**不做推断**） |
| 3 | `TempLeaveStart` 的发出时刻未定：§5.2.4 的图把「重新回公司」整条路径标成 `TempLeaveStart + TempLeaveEnd`，字面上会读成两个事件都在回司那一拍发出 | 明确为：**进入 `TEMP_LEAVE` 那一拍发 `TempLeaveStart`**（`occurredAt` = 离岗候选 `firstObservedAt`），**回司确认那一拍发 `TempLeaveEnd`**（`occurredAt` = 回归候选 `firstObservedAt`）。理由：`TEMP_LEAVE` 的语义是「**已确认**的临时离岗」，进入它却没有事件 = 状态与事件对不上；且分开发能保证 `occurredAt` 升序 |

配套新增三个原因码（`JourneyReason`）：`CANDIDATE_REVERSED`（候选被反向证据取消）、
`CLOCK_ROLLED_BACK`（时间回拨，落点=丢弃候选 + 保留 `lastConfirmedPhase` + 置 UNKNOWN）、
`INVALID_INPUT`（观测值越界，一律按保守侧清洗：负秒数按 0、坏置信按 0）。

### 5.3 自适应采样（v2：补 CRITICAL 限时 / 冷却 / 退避契约）

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

```kotlin
// domain/journey/SamplingDecision.kt —— AdaptiveSamplingPolicy 的唯一产物（v3.2 扩展）
data class SamplingDecision(
    val tier: SamplingTier,
    val urgency: Double,               // 清洗后的值（NaN 由策略回落到兜底档，不会出现在这里）
    val reasonCodes: Set<SamplingReason>,
    val expiresAt: Long?,              // ⭐ 该档位的失效时刻（到期回落一档，防「卡在高档」）
    val cooldownUntil: Long?,          // ⭐ P1-3：冷却截止（CRITICAL 结束后进入）
    val nextRetryState: RetryState,    // ⭐ v3.2：**下一拍的完整重试状态**（取代原 retryAttempt）
    val fallbackApplied: Boolean       // 是否兜底（状态机失败回落 SamplingTuning 时为 true）
)
```

#### 5.3.1 v3.2 冻结：三个「实现前必须定死」的数值与语义

开工 `AdaptiveSamplingPolicy` 前的预检发现三处**规格给了要求、却没给数值/接口**的缺口。
缺口不补就实现，实现者只能现场造魔数 —— 那正是"虚假的统计精度"。冻结如下。

**① `RetryState` 必须有自己的 CRITICAL 起点**（原缺口：只有一个 `lastAttemptAt`）

```kotlin
data class RetryState(
    val attempt: Int = 0,
    val lastAttemptAt: Long? = null,              // 最近一次尝试（无论成败）—— 每次重试都刷新
    val currentCriticalStartedAt: Long? = null,   // ⭐ 本轮 CRITICAL 起点 —— 内部重试不得刷新
    val lastCriticalEndedAt: Long? = null         // 上一轮 CRITICAL 结束时刻（冷却起点）
)
```

| 动作 | 写什么 | 不写什么 |
|---|---|---|
| 进入 CRITICAL | `currentCriticalStartedAt = now` | — |
| CRITICAL 内部重试 | 只写 `lastAttemptAt` | ❌ **不得刷新 `currentCriticalStartedAt`** |
| 取得 `CONFIRMED`（即时退出） | `currentCriticalStartedAt = null`、`lastCriticalEndedAt = now`、`attempt = 0` | — |
| 达到 10 分钟上限 | `currentCriticalStartedAt = null`、`lastCriticalEndedAt = now`、`attempt += 1` | — → 进冷却 |

用 `lastAttemptAt` 兼作起点会让**每次重试都重新获得 10 分钟**：上限永远到不了，
且不报错、日志里也看不出来（静默失效）。

**② `SamplingDecision` 返回完整的 `nextRetryState`**（原缺口：只回一个 `retryAttempt`）

进入/退出 CRITICAL、累加失败次数、起算冷却、成功清零，**全是策略的职责**（下面四条契约）。
只回失败次数的话，调用方得自己拼出 `currentCriticalStartedAt`/`lastAttemptAt`/`lastCriticalEndedAt`
—— 等于把重试语义搬进编排层，违反 §5.1.1。
**刻意不保留冗余的 `retryAttempt` 展示字段**：失败次数只有一处真相 `nextRetryState.attempt`；
"两个字段说同一件事"必然有一天不一致。

**③ `urgency → tier` 五档等距边界**（原缺口：只说"0..1 映射五档"，没有实际数值）

| urgency | tier |
|---|---|
| `0.0 ≤ u < 0.2` | `STABLE` |
| `0.2 ≤ u < 0.4` | `NORMAL` |
| `0.4 ≤ u < 0.6` | `WATCH` |
| `0.6 ≤ u < 0.8` | `TRANSITION` |
| `0.8 ≤ u ≤ 1.0` | `CRITICAL`（最高档上边界闭区间） |

非法输入一律取保守侧：`NaN → null`（**回落兜底档**；不许当 0 或 1 ——
`NaN` 与任何数比较都是 false，直接套分段写法会掉进最后一个 `else` = CRITICAL，
把"算不出来"静默翻译成"最高频采样"）、负数 → 0、大于 1 → 1。

**状态基础紧迫度**（`SamplingContract.baseUrgencyOf`）：

| 状态 | 基础 urgency |
|---|---|
| `AT_HOME` / `AT_WORK` | 0.1 |
| `AWAY` | 0.3 |
| `LEAVING_*` / `ARRIVING_*` | 0.5 |
| `TEMP_LEAVE` / `OTHER_STOP` | 0.5 |
| `COMMUTING_*` | 0.7 |
| `UNKNOWN` / `STALE` | 0.9 |

`EvidenceHealth` **只能提高**紧迫度，**不得压低**状态基础值。

**冷却退避封顶 16 分钟**：`cooldownMinutes(attempt) = 1 shl clamp(attempt, 0, 4)`
→ `1 / 2 / 4 / 8 / 16`，超过 4 次一律 16 分钟。
⚠️ **绝不直接 `1 shl attempt`**：`attempt` 只增不减，`1 shl 31` 溢出成负数，
冷却期被算成"过去"—— 不但不冷却，还会立刻再进 CRITICAL。

以上数值与查表集中在 `domain/journey/SamplingContract.kt`（**只有查表与清洗，没有策略**），
由 `SamplingContractTest` 逐档钉住（含 §5.4 第 7 条要求的"每档上下边界各一条"）。
⚠️ `CRITICAL_MAX_MINUTES = 10` 与 `SamplingTuning.HARD_BURST_CAP_MINUTES` 同源，
但 domain **不许反向 import** `location/service`，所以同源只能靠护栏测试守
（`criticalCapStaysInSyncWithSamplingTuning`）。

**两条硬覆盖（策略里实现，不在查表内）**：

1. `health.locationAvailable == false` → **不进入 CRITICAL**，回落 `fallbackTier` 并记
   `LOCATION_UNAVAILABLE`（此时高频请求什么都换不来）；
2. `health.placeDecision == CONFIRMED`（取得新可靠点）→ **立即退出 CRITICAL**，
   回到当前状态的基础档，并写 `lastCriticalEndedAt`。

**CRITICAL 的四条硬契约（P1-3 的落点，一个都不许缺）**：

1. **限时**：单次 CRITICAL 最长 **10 分钟**（`SamplingContract.CRITICAL_MAX_MINUTES`，
   同源于 `SamplingTuning.HARD_BURST_CAP_MINUTES`），起点取 `RetryState.currentCriticalStartedAt`；
2. **即时退出**：成功取得**可靠证据**（`placeDecision == CONFIRMED`）后立即退出 CRITICAL；
3. **冷却 + 指数退避**：达到时长上限仍未取得 → 进冷却；连续失败按
   `SamplingContract.cooldownMinutes(attempt)`（1/2/4/8/16 分钟，封顶 16）；
   冷却期内**不得**再次进入 CRITICAL；
4. **权限/开关关闭不强制**：定位权限被撤或系统定位开关关闭时，**不重复强制请求**，
   直接回落兜底档并记 `SamplingReason` —— 此时高频请求只是白耗电，什么都换不来。

**反证它能失败**（§7 第 7 条的纪律）：什么输入能让 CRITICAL 被拦下？
答：`now < cooldownUntil` 时的 CRITICAL 触发条件 —— 必须被冷却挡住、给出
`reasonCodes` 含 COOLDOWN 的 `NORMAL`/兜底档。写不出这条测试 = 契约没落地。

### 5.4 完成标准（v3：十七条，全部可逐条验收）

阶段 3 只有**全部**满足才算完成：

1. `JourneyObservation` / `JourneySnapshot` / `JourneyConfig` / `JourneyTransition` /
   `JourneyEvent` / `JourneyCandidate` / `RetryState` / `SamplingDecision` / `SamplingTier` /
   `SamplingContract` 均为
   `domain/journey/` 下的**纯 Kotlin**，零 Android 依赖，引用的类型全部真实存在；
2. `JourneyEngine` 是**纯 Reducer**（同（快照,观察,配置）同输出），可脱离 Room / Context 单测；
   三层职责边界按 §5.1.1 —— 编排层**不得包含业务判定**（地点/状态转换/候选确认/采样档位），
   允许数据存在性/版本/错误恢复/IO 分支，也不得修改纯单元产物内容；
3. 状态变迁必须带**迟滞**，不允许在阈值附近抖动；
4. 事件正式时刻取 `occurredAt`（= 候选 `firstObservedAt`），**不是** `confirmedAt`；`confirmedAt` 只进诊断；
5. `UNKNOWN` 与 `STALE` 分开，且各自有可解释的原因；断流期间状态**不许凭空跳变**
   （只能进 `STALE`），恢复后接回 `lastConfirmedPhase`；
6. `placeDecision` 推进规则有测试钉死：`MAINTAINED` **不允许**推进任何转换（一轮 P0-4）；
7. `SamplingTier` 的 `urgency → tier` 映射有测试，且**边界值逐档断言**（每个档的上下边界各一条）；
8. `urgency` 只增不减（相对兜底下限），有测试钉住；
9. CRITICAL 四条契约（限时/即时退出/冷却退避/权限关闭不强制）各有独立测试（§5.3）；
10. **确认门槛用 `accumulatedStableMillis`**，不是拍数；`supportCount` 只出现在诊断与对比日志（§5.2.5）；
11. 状态机**不读** `ShiftProfile` / 学习表（依赖方向单向）；但**必须**正确消费
    `hasActiveWorkSession` 观察事实（二轮 P0-1：它在 Observation，不在 Snapshot），
    且**不单独用它决定 TEMP_LEAVE vs 正式下班**（§5.2.4 判定规则）；
12. **临时离岗与正式下班可区分**：`TempLeaveStart` / `TempLeaveEnd` 事件在「离开又回司」场景触发，
    「离开到家」场景触发 `CompanyDeparture` / `HomeArrival` —— 两条路径各有真机影子日志佐证；
13. **同拍多事件不丢**（二轮 P0-3）：「公司在→断流→下一拍在家」一拍产出
    `CompanyDeparture` + `HomeArrival` 两个事件，事件列表三条排序硬约束
    （升序 / 同类型不重复 / 到家不早于离岗）各有测试；
14. `MotionPhase` 第一版只有 `STATIONARY / MOVING / UNKNOWN`，**不出现步行/车载**
    （二轮 P0-4：采集层分不出来）；时钟域规则（epoch vs elapsedRealtime 不互转互比）有测试钉住；
15. 采样档位变化必须落**诊断日志**（`LEARNING`/`JOURNEY` 类型），可在诊断页看到"为什么这一分钟采样加密了"；
16. 状态机**失败不影响定位主链路**（吞异常 + 回落 `SamplingTuning`，`fallbackApplied = true`）；
    **重启后候选不重置、不推迟、解释字段不伪造**（§5.6 的持久化与恢复有测试：杀进程 → 恢复 →
    候选累计**与解释字段**原样，`lastConfirmedPhase` 可恢复，二轮 P0-2）；
17. 真机跑满一个完整工作日，状态变迁序列可解释、无误跳变，**并通过 §5.5 的影子对照**
    （事件序列逐项比、含映射归一，`correlationId` 可配对），且**满足 §5.7 的功耗量化判据**。

### 5.5 影子对照：新旧状态机**逐事件**比对（阶段 3 的验收手段）

阶段 3 改了"状态怎么判"，所以唯一的验收方式是**让新旧两套并行跑、逐事件比对**。
沿用阶段 2 影子验证的纪律：**新状态机先只写日志，不影响任何判定**，
等比对结果全部解释得通，再切换。

**做法**：每一个 `JourneyObservation` 拍子同时喂给旧路径（`TrajectoryAnchorEngine` + `SamplingTuning`）
与新 `JourneyEngine`，把两者的产物按同一拍子配对，逐条比对下面 **9 项**。

| # | 比对项 | 不一致时必须能回答 |
|---|---|---|
| 1 | 状态/阶段（**按 §5.5.1 映射归一后比**） | 新状态是更早还是更晚？差在哪条证据上？ |
| 2 | **事件序列**（`confirmedEvents` 的类型、数量、各自 `occurredAt`/`confirmedAt` 逐项比，二轮 P0-3） | 是新机确认了旧机没确认，还是反过来？同拍多事件是否两边都齐？ |
| 3 | 事件**正式时刻**（`occurredAt`） | 差几秒/几分？是不是旧机用了确认时刻？ |
| 4 | 采样档（`SamplingTier` vs 旧 `SamplingTuning` 档位） | 新档更密还是更省？会不会丢证据？ |
| 5 | 断流判定（是否进 `STALE`） | `secondsSinceFix` 门槛是否一致？ |
| 6 | `UNKNOWN` vs `STALE` 的归因 | 是"判不出来"还是"没证据"？ |
| 7 | 迟滞是否生效（阈值附近有无抖动） | 同一输入连续拍的状态是否稳定？ |
| 8 | 候选期长度（`LEAVING_*` / `ARRIVING_*` 停留**时长**，v2 不再比拍数） | 确认门槛是否过松/过紧？ |
| 9 | 理由文案（`explanation`） | 能否独立解释这条不一致？答不上来就是缺证据记录 |

#### 5.5.1 新旧状态映射（P1-4：先归一，再比对）

旧机状态串全集（实测 `TrajectoryAnchorEngine`，共 6 个）与新 13 状态**非一一对应**，
逐字段直比会产生大量无意义差异。**比对前必须按此表归一**：

| 旧状态（+条件） | 新状态候选 |
|---|---|
| `REST` 且 homeStable | `AT_HOME` |
| `REST` 且非 homeStable（休息日外出） | `AWAY` |
| `LEAVING_HOME` | `LEAVING_HOME`，若持续移动则 `COMMUTING_TO_WORK` |
| `NEAR_COMPANY` | `ARRIVING_WORK` |
| `WORKING` | `AT_WORK` |
| `TEMP_LEAVE` 且后续回公司 | `LEAVING_WORK` → `TEMP_LEAVE` → `ARRIVING_WORK` |
| `TEMP_LEAVE` 且确认下班（旧机的唯一出口语义） | `LEAVING_WORK` → `COMMUTING_HOME`（→ `ARRIVING_HOME`） |
| `FINISHED` 且移动 | `COMMUTING_HOME` |
| `FINISHED` 且 homeStable | `AT_HOME`（`HomeArrival` 已确认） |

⚠️ 旧机的 `TEMP_LEAVE` 覆盖了新机三个状态（`LEAVING_WORK`/`TEMP_LEAVE`/`COMMUTING_HOME`
的候选期），**这是预期差异、不是 bug** —— 旧机从未区分临时/正式，影子期的新机应当
在这条路径上**更精确**。比对报告要把这类差异单列为「预期分化」，不与错误混计。

#### 5.5.2 影子对比日志的字段（P1-4：关联键是硬约束）

每条对比日志**必含**以下字段，缺一即无法可靠配对：

```
correlationId        // 同一拍新旧两条记录共享的关联键（UUID，Coordinator 在喂入前生成）
inputEventTime       // 该拍的 observation.now
oldDecision          // 旧机产物（WorkStateEntity 快照 + 事件 + SamplingTuning 档）
newDecision          // 新机产物（JourneyTransition + SamplingDecision）
normalizedOldPhase   // 按 §5.5.1 映射归一后的旧状态（未映射前不许进差异统计）
differenceType       // NONE / EXPECTED_SPLIT（预期分化）/ TIMING / TIER / MISSING_OLD / MISSING_NEW
```

**比对纪律**：

- 一次不一致**不算问题**，**无解释的不一致**才算问题。允许"新机更晚确认"（更保守），
  不允许"新机凭空确认"（旧机没有任何支持证据而新机确认了）。
- 方向性判据：新机允许**更保守**，不允许**更激进** —— 与阶段 2「宁可多走影子」同一条原则。
  唯一例外是 `occurredAt`：新机取 `firstObservedAt`，天然**更早**于旧机的确认时刻，
  这属于预期修正，比对时要按"是否更接近真实到离岗"来判断，而不是按先后。
- 比对结论必须**逐条落到设计稿或 issue**，不允许"看下来差不多"。
- 影子期长度：至少覆盖 **2 个完整工作日 + 1 个休息日**
  （休息日专门验证"不该出勤"这条不会因为状态机改动而误报）。

### 5.6 影子状态持久化与重启恢复（一轮 P1-2 + 二轮 P0-2：DB v17）

完成标准要求「重启后候选不被重置或推迟」，这**必须**有持久化契约支撑，否则纯靠进程内存，
重启即归零。**影子阶段绝不复用 `work_state` 表** —— 新状态机虽然不写工时，
写同一张表仍可能污染旧状态机（列语义、写入时序、迁移风险全纠缠在一起）。

```kotlin
// data/entity/JourneyShadowStateEntity.kt —— 独立单行表（DB v17）
@Entity(tableName = "journey_shadow_state")
data class JourneyShadowStateEntity(
    @PrimaryKey val id: Int = 1,               // 单行设计：影子机全局只有一个快照
    val phase: String,                          // JourneyPhase.name
    val candidatePhase: String?,               // JourneyCandidate.targetPhase.name
    val firstObservedAt: Long?,
    val lastSupportedAt: Long?,
    val candidateLastUnsupportedAt: Long?,        // 支持链中断标记，防重启后把空窗累计进稳定时长
    val supportCount: Int,
    val accumulatedStableMillis: Long,
    val candidateEvidenceSources: String?,     // ⭐ 二轮P0-2：按稳定顺序序列化（如 "GNSS,WIFI,CELL"）
    val candidateStrongestDecision: String?,   // ⭐ 二轮P0-2：FusedDecision.name
    val candidateConfidence: Double?,          // ⭐ 二轮P0-2：候选最强置信
    val lastConfirmedPhase: String?,           // ⭐ 二轮P0-2：STALE 恢复/时间回拨要接回的字段，必须持久化
    val lastTransitionAt: Long,
    val samplingAttempt: Int,                     // CRITICAL 退避状态也必须跨重启保持
    val samplingLastAttemptAt: Long?,
    val samplingCriticalStartedAt: Long?,
    val samplingLastCriticalEndedAt: Long?,
    val modelVersion: Long,                     // 状态机结构版本（枚举/字段变更时 +1）
    val updatedAt: Long
    // ⚠️ 二轮P0-1：activeWorkSession 不持久化 —— 它是外部事实（Observation），不是状态机记忆
)
```

**为什么完整持久化候选解释字段（二轮 P0-2 的落点）**：v2 曾打算不存
`evidenceSources` / `strongestDecision` / `confidence`，恢复时按保守档伪造
（`MAINTAINED` + 空集）—— 这违反「**不把未知伪装成确定值**」：
重启前后对同一候选的解释会变，影子对比日志也随之漂移。
`evidenceSources` 按稳定顺序序列化（枚举 ordinal 或固定字典序），数据量极小，
**不为省三列牺牲恢复一致性**。同理 `lastConfirmedPhase` 不持久化的话，
恢复规则里的「STALE 恢复后接回」根本无从执行。

恢复与重置规则（每条都有测试）：

1. **正常恢复**：启动时读单行 → 重建**完整** `JourneySnapshot`（含候选累计与全部解释字段，
   原值原样、零伪造）→ 喂给引擎继续。恢复后对同一候选的解释与重启前一致；
2. **时间回拨 / 重置**：`updatedAt > now`（设备时间回拨或换机恢复备份）→ **丢弃候选、
   保留 `lastConfirmedPhase`、状态置 `UNKNOWN` 重新观察**（保守方向，绝不拿未来数据继续推）；
3. **版本不匹配**：`modelVersion` 与当前代码不符 → 整行重置为初始快照
   （枚举改名/字段语义变化后，旧快照的字符串解不回来，宁弃勿猜 —— 与「老数据不猜来源」同一原则）；
4. **写入频率**：每拍 Reducer 之后写一次（影子机唯一写者），崩溃至多丢一拍 ——
   一拍在候选期内的代价是 `firstObservedAt` 不变、`accumulatedStableMillis` 少一拍增量，
   **不会推迟事件正式时刻**（`occurredAt` 取最早证据，丢了中间一拍不影响它）。

DB v17 迁移照 §7.1 流程：`tools/verify_room_migration.py --old-schema .../16.json --new-schema .../17.json`。

### 5.7 功耗验收：可计数指标（P2：电量百分比不作唯一结论）

「功耗不高于 v9.2」必须用**计数器**证，不能只看系统电量 —— 一天内网络、屏幕、
其他 App 的耗电都会污染电量读数。阶段 3 影子期必须**持续累计**以下指标
（JourneyCoordinator 落库，诊断页可查）：

| # | 指标 | 说明 |
|---|---|---|
| 1 | 定位请求次数 | 分 GPS / network |
| 2 | 高频采样累计分钟 | `WATCH` 及以上档位的累计时长 |
| 3 | CRITICAL / Burst 累计分钟 | 两者同口径合并统计（上限同为 10 分钟） |
| 4 | Wi-Fi 扫描次数 | |
| 5 | 蓝牙扫描次数 | |
| 6 | 基站快照次数 | |
| 7 | 成功定位数 / 超时数 | 分母齐了才算得出「无结果请求率」 |
| 8 | 状态转换数 | 过多 = 迟滞失效或采样过密 |
| 9 | 每次确认的平均延迟 | `confirmedAt − occurredAt` 的均值/分位 |

**验收判据（三条全过才算达标）**：

1. 新状态机**不得**使全天高频采样时间超过基线（v9.2 同期）**10%**；
2. **不得降低关键事件捕获率**（到岗/离岗/到家事件的捕获数不少于旧机）；
3. **不得增加无结果扫描**（扫描次数上升的同时成功定位数不升 = 白扫）。

真机电量只作**辅助指标**（同机型同充电习惯下的大幅劣化仍要解释），不作唯一结论。

**基线怎么取**：影子期第一天**新旧同时跑但采样仍由旧机驱动**（新机只记录它*会*请求什么，
不真请求），此后按 §5.5 正常并行。这样能同时拿到「旧机实测基线」与「新机虚拟请求量」，
切换前就能预判功耗差异。

### 5.8 实现顺序（固定，不按此序 = 自找返工）

```
① 纯 domain 类型（Observation/Snapshot/Config/Transition/Event/Candidate/SamplingDecision）
② JourneyEngine reducer
③ 引擎单元测试（§5.4 第 2~6、10~11、13~14 条全在这一步钉死）
④ AdaptiveSamplingPolicy（含 §5.3 CRITICAL 四契约测试）
⑤ DB v17 影子快照（entity + migration + verify_room_migration.py 本地证死）
⑥ JourneyCoordinator（IO 编排 + 快照持久化/恢复 + 兜底回落）
⑦ 新旧状态映射 + 影子对比日志（correlationId 配对，EXPECTED_SPLIT 单列）
⑧ 功耗计数器（§5.7 九项指标落库）
⑨ 全量单测回归（837 基线上一把过）
⑩ 真机影子运行（≥ 2 个完整工作日 + 1 个休息日，首日新机只记录不请求）
```

**为什么事件列表（②③）和快照持久化（⑤⑥）排最前且不许后补**：这两项如果实现后再改，
会再次触发数据库迁移和大面积测试重写 —— 二轮 P0-2/P0-3 专门修的就是它们，
v3 冻结的意义就是让它们一次成型。

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
   ✅ 校验器**已入库**：`tools/verify_room_migration.py`（v9.2 起）。
   ```bash
   python tools/verify_room_migration.py \
     --old-schema app/schemas/com.example.worktimetracker.data.database.AppDatabase/15.json \
     --new-schema app/schemas/com.example.worktimetracker.data.database.AppDatabase/16.json \
     --migration-source app/src/main/java/com/example/worktimetracker/WorkTimeApplication.kt \
     --from 15 --to 16
   ```
   退出码 `0` 通过 / `1` 失败 / `2` 用法错误。`--from/--to` 可以**跨多跳**（如 14→16），
   链条会连着跑。`diagnostics/` 仍被 gitignore，但**通用校验器在 `tools/` 下，不在忽略范围内**。
2. **动门槛** → 先看 `CONTRACTS.md` 的冻结项，确认它不属于「算法冻结项（勿改）」。
3. **新增取用/优先级逻辑** → 必须落在 `PlaceModelResolver`，不要在服务里另写 `if`。
   要「显示当前用的是哪个锚点」时用 `PlaceModelResolver.resolve(...)` 的
   `EffectiveAnchorSource`，**不要**拿取到的点和 `learnedAnchor` 比 —— 两个锚点重合时那种猜法会给出错误答案。
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
   ⚠️ 光判对不够：**输出快照也要保留 `null`**。v9.1 收口时 `ShadowValidation.latestSpreadP90Meters`
   判定是对的（null 不满足门槛 → 失败），但对外快照被 `?: 0.0` 压成了"零米完美集中" ——
   判定正确不能成为输出层丢语义的理由。
9. **上限类门槛的等号归"允许"侧** → `≤ 30`、`≤ 10`、`≤ 100`、`≥ 2` 一律
   上限含等号。且边界必须抽成**可精确断言的纯函数**（如 `ShadowValidator.driftExceedsLimit`），
   不要写成一个带浮点几何夹具的比较 —— `distanceMeters` 的往返误差会让"正好 10 米"
   落在 `10.0±1e-13`，等号归哪侧在测试里既不可控也不可断言。
10. **常量同源** → 同一个数字只允许有一个定义处（如 `MIN_ELAPSED_DAYS = AnchorUpdatePolicy.SHADOW_VALIDATION_DAYS`、
   `CANDIDATE_DEDUP_METERS = ShadowValidator.MAX_CENTER_DRIFT_METERS`），
   并且同值关系要**写成测试**，不能只写在注释里。
11. **用户偏好 / 算法事实 / 模型状态三层不许混** → 用户的开关只写
   `place_learning_preferences`。**停用一律不动 `autoApplied` / `status`**：
   用户按了暂停却把模型标成 `RETIRED`，等于把"用户关了"记成"模型坏了"，
   之后无法归因（见 §2.1.2）。
12. **用户改了会影响判定的开关** → 必须同时**让检测路径立刻看到**。
   生效地点集合有 60 秒缓存，别让界面说"已停用"而判定还在用学习锚点；
   失效缓存走 `ServiceRecovery.invalidateSiteCache()`，且**只在服务已在运行时下发**
   （不能因为改一个复选框而把定位服务拉起来）。
13. **冻结稿/接口引用的类型必须实测核对** → 写契约时逐个 `grep` 仓库确认，
   **搜索结果不许截断**（`| head` 会把关键匹配切掉 —— 本条修订时第一次 grep 就因截断
   把真实存在的 `PlaceType` 漏成了"不存在"，差点把错误结论写进契约）。
   阶段 3 规格 v1 就把 `PlaceType`（实际是 `WORK/NON_WORK` **站点类型**）错位用作判定地点
   （正确类型是 `ResolvedPlace`），还引用了不存在的 `MotionState` —— 契约从第一行就落不了地。
   同理：引用旧系统符号做映射（如影子对照的新旧状态表）时，枚举全集要**实测源码**
   （`grep -o 'currentState = "'`），不要凭记忆写 —— 旧机 `TEMP_LEAVE` 的真实语义
   就和记忆版差了十万八千里。

### 7.1 迁移怎么在本地证死（不需要 instrumentation）

**校验器已入库：`tools/verify_room_migration.py`**（v9.2 起；此前散在 `diagnostics/tools/` 下，
而 `diagnostics/` 被 gitignore，换机器就得重写 —— 所以搬进仓库并通用化）。
`diagnostics/` 仍然忽略，但**通用校验器不在忽略范围内**。

它做的事（也就是"证死"的定义）：

1. 用 `app/schemas/.../<旧版本>.json` 里的 `createSql`（把 `${TABLE_NAME}` 替换成 `tableName`，
   索引同样处理）在**内存 SQLite** 里搭出旧版本的真实结构；
2. 用正则从 `WorkTimeApplication.kt` 里把整条 `MIGRATION_*` 链的 `db.execSQL(...)` 字符串参数
   抠出来、拼回完整语句，**按版本顺序**执行（一条链要连着跑，别只跑最后一条）；
   同时校验链的**连续性**：断裂、重复、越界都直接报错并退出 `2`；
3. 与 KSP 新导出的 `<新版本>.json` **全表**比对列名 / 列序 / 类型 affinity / NOT NULL / 默认值 /
   主键顺序 / AUTOINCREMENT / 索引名+唯一性+列序；
4. 每张旧表**埋一行探针**，迁移后逐列比对，证明旧数据原样保留（不只看"建表成功"）；
5. 模拟 Android 升级后的 `PRAGMA user_version` 写入，确认它是 `--to`。

**它必须能失败**（这是它存在的意义，已自证）：断链 → 退出 `2`；schema 版本不自洽 → `FAIL`；
篡改结构（改列/删索引/改主键）→ 逐项被抓；删行 / 改数据 / 删表 → 逐项被抓；干净库 → 不误报。

坑（踩过三次）：

- Room 的主键在 JSON 里是 `entity.primaryKey.columnNames`，**不是**逐字段的 `primaryKeyPosition`；
- `PRAGMA table_info` 的 `pk` 列是**位次不是布尔**，要按它排序才是声明顺序；
- 索引在 `indices[].columnNames`，但复合主键 / UNIQUE 会产生 `sqlite_autoindex_*` **隐式索引**，
  必须按前缀过滤掉，否则每张复合主键表都会报一条假的不一致（v9.2 首次运行就这样误报了 9 条）；
- `CREATE TABLE IF NOT EXISTS` 会让「表已存在」静默通过 —— 所以第 3 步一定要比对**列集合**，不能只看建表成功。

真机侧只需验证 `PRAGMA user_version` 从旧值跳到新值、表数 +1（v16 时 21 → 22）、老表行数一行不差。

### 7.2 本地 shell 的一个坑（Windows）

本机 Bash shim 的 `PATH` 会缺 coreutils（`dirname` / `ls` / `tail` / `uname` / `xargs` 全部找不到），
表现是 `./gradlew` 直接死在 `uname: command not found`。
解决办法是在命令前补一行：

```bash
export PATH="/c/Users/Administrator/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:$PATH"
```

不要用 `python -c` 去绕 `mkdir`/`shutil` —— 补 `PATH` 之后常规工具都能用，别在脚本里绕。
真机侧只需验证 `PRAGMA user_version` 从旧值跳到新值、老表行数一行不差。
