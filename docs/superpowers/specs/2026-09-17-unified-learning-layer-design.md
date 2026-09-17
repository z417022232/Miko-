# 算法层重构：统一学习层与七阶段路线

> 状态：**阶段 1（收尾）+ 学习层地基 + 阶段 2（位置闭环）已落地**（v9.0 / code 28 / DB v14，commit `0152639`）；
> **影子验证强化已落地**（v9.1 / code 29 / DB v15）；
> **阶段 2 收口已落地**（v9.2 / code 30 / DB v16）：NULL 语义修正、10 米边界等号统一、
> 迁移校验器入库（`tools/verify_room_migration.py`）、学习状态展示 + 粘性回退入口。
> 阶段 3~7 **未做**，但阶段 3 的**规格已完整冻结**（边界 §5.1、三层职责 §5.1.1、接口 §5.2、
> 采样 §5.3、完成标准 §5.4、**影子对照 §5.5**）—— 开工时照此实现，不边写边改契约。
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

## 5. 阶段 3：行程状态机 + 候选事件 + 自适应采样（**规格已冻结**，实现未做）

> **冻结声明（v9.2 / 2026-09-17）**：本节的接口、状态数、门槛方向、完成标准已冻结。
> 阶段 3 开工时**照此实现**，不得边写边改契约 —— 契约改了，影子对照（§5.5）的
> 「新旧逐事件比对」就没有基线可对，等于把唯一的验收手段废掉。
> 要改契约，先改本节 + 对应的影子对照口径，再动代码。

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

### 5.1.1 三层分离：谁算什么（冻结的职责边界）

阶段 3 落成**三个各自可单测的单元**，不允许合并、也不允许互相调用：

| 单元 | 位置 | 职责 | 硬约束 |
|---|---|---|---|
| `JourneyEngine` | `domain/journey/` | **纯算法**：`JourneyInput` → `JourneyDecision` | 纯函数、无 Room/Context/时间源；同输入同输出 |
| `AdaptiveSamplingPolicy` | `domain/journey/` | **纯映射**：`JourneyPhase` + 证据健康 → `SamplingTier` | 只做 `urgency → tier` 的查表/分段，不含状态判断 |
| `JourneyCoordinator` | `location/service/` | **编排**：读库/读状态机产物、调 `JourneyEngine`、落 `SamplingTier`、写诊断日志 | 只做 IO 与转发，**不许在里面写判定 `if`** |

为什么把采样单独拆出来而不是塞进状态机：
**"判定"与"为了判定而多花多少电"是两个可以分别出错的东西**。
合在一起时，一次"状态判错了"会顺带把采样档也带错，而采样档错了会反过来让状态更难判对 ——
两个错误互相掩盖。拆开后可以单独回答"这一分钟为什么加密采样"，
且 `SamplingTier` 的档位边界能逐档断言（§5.4 第 6 条）。

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

// domain/journey/JourneyDecision.kt —— JourneyEngine 的唯一产物（冻结）
data class JourneyDecision(
    /** 当前状态（含候选期的中间态） */
    val phase: JourneyPhase,
    /** 是否有在此刻**确认**的事件（null = 这一拍只更新状态，不确认任何事件） */
    val confirmedPhase: JourneyPhase?,
    /** 事件正式时刻 = 候选的 firstObservedAt（**不是**确认时刻） */
    val occurredAt: Long?,
    /** 确认时刻 = 支持链命中门槛的那一拍的 now。**只用于计时/诊断，绝不进工资** */
    val confirmedAt: Long?,
    /** 采样档（由 AdaptiveSamplingPolicy 依据 phase + 证据健康映射，不由引擎自己算） */
    val samplingTier: SamplingTier,
    /** 人话原因（方案 §一 原则 6：每次判定必须能解释依据） */
    val reason: String
)
```

**`occurredAt` 与 `confirmedAt` 的分工是硬约束**：

| 字段 | 语义 | 用途 |
|---|---|---|
| `occurredAt` | 候选的 `firstObservedAt`：**最早**支持该状态的证据时刻 | **事件正式时刻取它**；与 `work_records.firstObservedAt` 同口径 |
| `confirmedAt` | 支持链命中门槛的那一拍 `now` | **只用于诊断/延迟统计**（"这次迟到几拍才确认"） |

为什么不用确认时刻当事件时刻：候选会因断流、重启而推迟确认，
用确认时刻会让"到岗 08:40"记成"到岗 09:12"，**误差直接进工资计算**。

`confirmedPhase == null` 而 `phase` 变了是**合法且常见**的：候选期内的中间态
（`LEAVING_*` / `ARRIVING_*`）就是这种形态 —— 状态已经变了，但还没到"确认事件"的那一刻。

**11 个状态**（**已冻结**，自阶段 3 开工生效）：

| 组 | 状态 | 说明 |
|---|---|---|
| 家 | `AT_HOME` / `LEAVING_HOME` / `ARRIVING_HOME` | 在宅 / 离宅候选期 / 归宅候选期 |
| 通勤 | `COMMUTING_TO_WORK` / `COMMUTING_HOME` | 单向通勤中 |
| 在岗 | `AT_WORK` / `LEAVING_WORK` / `ARRIVING_WORK` | 在岗 / 离岗候选期 / 到岗候选期 |
| 其他 | `AWAY` / `UNKNOWN` / `STALE` | 在别处 / 无法判定 / 断流（有明确原因，不是兜底） |

`UNKNOWN` 与 `STALE` 必须分开：前者是"证据矛盾、判不出来"，后者是"压根没有证据"。
混成一个的话，诊断页上看不出是数据缺失还是算法失灵。

三个候选期（`LEAVING_*` / `ARRIVING_*`）**不是**"过渡态"这种含糊说法，
它们各自有明确语义：**已观察到离开该地点的证据，但支持链尚未达到确认门槛**。
确认门槛与迟滞一并由 `JourneyEngine` 持有，`JourneyDecision.reason` 必须能说出"还差几拍"。

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

1. `JourneyInput` / `JourneyPhase` / `JourneyCandidate` / `JourneyDecision` / `SamplingTier` 均为 `domain/` 下的**纯 Kotlin**，零 Android 依赖；
2. `JourneyEngine` 是**纯函数**（同输入同输出），可脱离 Room / Context 单测；三层职责边界按 §5.1.1，编排层里**不得出现判定 `if`**；
3. 状态变迁必须带**迟滞**，不允许在阈值附近抖动；
4. 事件正式时刻取 `occurredAt`（= 候选 `firstObservedAt`），**不是** `confirmedAt`；`confirmedAt` 只进诊断；
5. `UNKNOWN` 与 `STALE` 分开，且各自有可解释的原因；
6. `SamplingTier` 的 `urgency → tier` 映射有测试，且**边界值逐档断言**（每个档的上下边界各一条）；
7. `urgency` 只增不减（相对兜底下限），有测试钉住；
8. 状态机**不读** `ShiftProfile` / 学习表（依赖方向单向）；
9. 断流期间状态**不许凭空跳变**（只能进 `STALE`），恢复后能接回原状态；
10. 采样档位变化必须落**诊断日志**（`LEARNING`/`JOURNEY` 类型），可在诊断页看到"为什么这一分钟采样加密了"；
11. 状态机**失败不影响定位主链路**（吞异常 + 回落 `SamplingTuning`）；
12. 真机跑满一个完整工作日，状态变迁序列可解释、无误跳变，**并通过 §5.5 的影子对照**，
    且功耗不高于 v9.2。

### 5.5 影子对照：新旧状态机**逐事件**比对（阶段 3 的验收手段）

阶段 3 改了"状态怎么判"，所以唯一的验收方式是**让新旧两套并行跑、逐事件比对**。
沿用阶段 2 影子验证的纪律：**新状态机先只写日志，不影响任何判定**，
等比对结果全部解释得通，再切换。

**做法**：每一个 `JourneyInput` 拍子同时喂给旧路径（`TrajectoryAnchorEngine` + `SamplingTuning`）
与新 `JourneyEngine`，把两者的产物按同一拍子配对，逐条比对下面 **9 项**。

| # | 比对项 | 不一致时必须能回答 |
|---|---|---|
| 1 | 状态/阶段 | 新状态是更早还是更晚？差在哪条证据上？ |
| 2 | 事件是否确认（`confirmedPhase` 是否非空） | 是新机确认了旧机没确认，还是反过来？ |
| 3 | 事件**正式时刻**（`occurredAt`） | 差几秒/几分？是不是旧机用了确认时刻？ |
| 4 | 采样档（`SamplingTier`） | 新档更密还是更省？会不会丢证据？ |
| 5 | 断流判定（是否进 `STALE`） | `secondsSinceFix` 门槛是否一致？ |
| 6 | `UNKNOWN` vs `STALE` 的归因 | 是"判不出来"还是"没证据"？ |
| 7 | 迟滞是否生效（阈值附近有无抖动） | 同一输入连续拍的状态是否稳定？ |
| 8 | 候选期长度（`LEAVING_*` / `ARRIVING_*` 停留拍数） | 确认门槛是否过松/过紧？ |
| 9 | 理由文案（`reason`） | 能否独立解释这条不一致？答不上来就是缺证据记录 |

**比对纪律**：

- 一次不一致**不算问题**，**无解释的不一致**才算问题。允许"新机更晚确认"（更保守），
  不允许"新机凭空确认"（旧机没有任何支持证据而新机确认了）。
- 方向性判据：新机允许**更保守**，不允许**更激进** —— 与阶段 2「宁可多走影子」同一条原则。
  唯一例外是 `occurredAt`：新机取 `firstObservedAt`，天然**更早**于旧机的确认时刻，
  这属于预期修正，比对时要按"是否更接近真实到离岗"来判断，而不是按先后。
- 比对结论必须**逐条落到设计稿或 issue**，不允许"看下来差不多"。
- 影子期长度：至少覆盖 **2 个完整工作日 + 1 个休息日**
  （休息日专门验证"不该出勤"这条不会因为状态机改动而误报）。

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
