# 配置一致性、数据保留与首次建站重构设计

日期：2026-09-19  
状态：待用户审核  
适用仓库：WorkTimeTracker / Miko-

## 1. 背景与目标

本轮处理三类已经确认的问题：

1. 用户修改地点半径或工资数据后，运行时仍可能继续使用旧快照或旧缓存；
2. 仓库残留零调用 Repository、Manifest 权限和 ViewModel 接口；
3. 日志、通知和首次引导仍缺少完整的产品闭环：日志会持续增长、事件通知 ID 无界增长、首次引导只写旧坐标而不创建真实站点。

目标是让“用户刚保存的配置”成为所有判定链的唯一输入，并保证每项新增能力都具备数据库、业务逻辑、UI、交互和测试，不再出现只有字段或后台逻辑、界面却不可操作的情况。

## 2. 不可破坏的约束

- `SiteEntity.radiusMeters` 是地点半径的唯一权威来源；学习算法只校准圆心，不得擅自扩大或缩小用户半径。
- 用户录入的工资、工资条和历史工时不因缓存刷新、日志裁剪或迁移而被改写。
- “永久保留”只关闭自动裁剪；用户主动执行“清空本地记录”仍然有效。
- 新建站点后必须同步刷新定位服务缓存和围栏，不能等重启应用。
- 迁移必须保留现有 DB v17 数据，升级到 DB v18；禁止 destructive migration。
- connected tests 不使用正式应用包名，不接触真机正式数据。

## 3. 地点半径唯一事实源

### 3.1 权威关系

`sites.radiusMeters` 是检测、采样和围栏的唯一半径。以下路径必须读取同一个值：

- `SiteResolver.matching` 的进圈判断；
- `ForegroundLocationService.siteFences` 的采样策略；
- `GeofenceRecovery` 的 Google Geofence；
- `GeofenceRecovery` 的 platform proximity fallback；
- `AnchorSampleBuilder` 的学习样本入核判断。

`learned_place_models.coreRadiusMeters` 与 `transitionRadiusMeters` 保留为模型诊断快照，不作为覆盖用户配置的输入。地点半径修改后，同步更新该地点模型快照：

- `coreRadiusMeters = sites.radiusMeters`；
- `transitionRadiusMeters = sites.radiusMeters × TRANSITION_RADIUS_FACTOR`。

### 3.2 保存后的即时生效

`WorkTimeViewModel.saveSite` 成功后必须执行统一的地点配置变更通知：

1. 失效前台服务的站点缓存；
2. 同步模型半径快照；
3. 串行重新注册全部有效围栏；
4. 刷新地点与学习状态 UI。

删除、启用、停用地点也走同一刷新入口。

## 4. 工资缓存统一失效

新增单一的 `refreshPayrollDerivedState()`，按顺序执行：

1. 重新读取费率分段；
2. 重新读取月度参数；
3. 从月度实发与对应工时重新计算 `_payBaseline`；
4. 重新计算当前月工资估算；
5. 重新计算整月预测；
6. 让首页当日工资读取新基准。

以下写路径成功后必须调用该入口：

- 当前月实发工资保存；
- 指定月份实发工资保存；
- 工资条 OCR 确认保存；
- 费率分段新增、修改、删除；
- 月度计薪参数修改或删除；
- 备份恢复；
- 工资数据清理。

禁止各写路径分别维护 `_payBaseline`、`_monthPayroll` 或 `_monthProjection`，避免部分刷新。

## 5. 删除确认无调用的接口

删除三个零调用 Repository：

- `WorkRecordRepository`；
- `SettingsRepository`；
- `LocationRepository`。

删除 Manifest 中未使用的 `android.permission.SYSTEM_ALERT_WINDOW`。

删除七个零调用 ViewModel 方法：

- `saveLocations`；
- `searchPlaceAndSet`；
- `prepareCompanyCalibration`；
- `acceptCompanyCalibration`；
- `cancelCompanyCalibration`；
- `monthlyPayParams`；
- `sourcesForSite`。

同时删除仅被这些接口占用、确认无其他调用的状态、导入和旧校准对象。删除前后都用全仓符号扫描和编译测试确认，不按文件名猜测。

## 6. 日志裁剪与永久保留

### 6.1 DB v18

`user_settings` 新增：

```text
logRetentionMode TEXT NOT NULL DEFAULT 'STANDARD'
```

合法值：

- `STANDARD`：标准保留；
- `FOREVER`：永久保留。

未知值按 `STANDARD` 处理。

### 6.2 标准保留规则

- `location_logs`：保留最近 180 天；
- `app_logs`：保留最近 90 天；
- `app_logs`：时间裁剪后仍超过 10,000 条时，只保留最新 10,000 条；
- 自动裁剪每个本地自然日最多执行一次；
- 裁剪异常只写受限诊断，不影响定位与工时主链路。

### 6.3 永久保留规则

`FOREVER` 模式不执行自动删除。切换回 `STANDARD` 后，下次维护立即按标准规则裁剪。

### 6.4 设置 UI

设置页“数据与诊断”增加“日志保留方式”：

- 标准保留：定位180天、诊断90天，诊断最多10,000条；
- 永久保留：不自动清理，存储占用会持续增加。

选择后立即保存并显示当前状态，不需要重启。

## 7. 通知 ID 池

通知按语义分组：

- `1001`：前台常驻服务；
- `2002`：系统定位与服务恢复状态，同类状态覆盖更新；
- 工时完成、异常、人工确认等事件通知使用固定大小循环池。

事件池采用稳定区间和原子递增槽位，进程重启后从持久化槽位继续。池满后覆盖最旧槽位，避免以时间戳生成无限通知 ID。通知内容、渠道重要度和点击目标保持各自语义。

## 8. 首次引导建立真实站点

引导页的“设为公司”和“设为家庭”不再只写 `user_settings.companyLat/homeLat`。

### 8.1 数据写入

使用最近一次可靠定位创建或更新：

- 公司：`SiteEntity.TYPE_WORK`，并设为主工作地点；
- 家庭：`SiteEntity.TYPE_NON_WORK`；
- 初始半径使用 `SiteEntity.DEFAULT_RADIUS_METERS`；
- 若对应类型已有迁移站点则更新该站点，避免重复创建；
- 同步旧坐标字段，用于备份兼容和极端兜底，但 `sites` 始终权威。

### 8.2 联动恢复链

站点写入成功后：

1. 刷新站点列表；
2. 失效定位服务站点缓存；
3. 调用串行化后的 `GeofenceRecovery.register`；
4. UI 显示“公司已设置”或“家庭已设置”；
5. 围栏失败时保留站点并显示可恢复提示，不能回滚用户数据。

完成引导时若公司或家庭缺失，显示明确提示，但允许“稍后设置”。

## 9. 错误处理与并发

- 地点保存与模型半径同步在 Room transaction 中完成；围栏注册属于事务后的外部副作用。
- 围栏注册继续使用现有 `Mutex`，Application、BootReceiver、引导页和地点管理并发触发时串行执行。
- 工资缓存刷新在 ViewModel 协程中串行完成；较旧刷新不得覆盖较新写入结果。
- 日志裁剪使用单一维护锁和“最近执行自然日”记录，避免 Application 与健康任务重复裁剪。
- 所有外部副作用失败均记录原因并保留数据库事实，不反向删除用户刚保存的配置。

## 10. 测试与验收

### 10.1 单元测试

- 修改站点半径后，判定、采样围栏和 Geofence 目标半径一致；
- 学习圆心生效时半径仍取用户配置；
- 每条工资写路径都会刷新基准、月估算和整月预测；
- DB v17→v18 默认 `STANDARD`，原数据逐项保留；
- `STANDARD` 的180天、90天、10,000条边界；
- `FOREVER` 零删除；
- 通知 ID 池有界、循环且保留固定 ID；
- 引导公司/家庭创建真实站点，重复操作不生成重复站点；
- 站点写入后触发缓存失效与围栏注册；
- 删除接口后全仓零引用。

### 10.2 构建与迁移

- `clean testDebugUnitTest assembleDebug`；
- 导出的 DB v17 schema 与 v18 schema 逐列、索引和默认值比对；
- APK 版本号递增；
- 不执行正式包名 connected tests。

### 10.3 真机验收

- 修改地点半径后无需重启即可影响进圈/出圈；
- 新录入工资后首页当日工资和整月预测立即更新；
- 标准与永久日志模式可在设置页切换；
- 通知数量保持有界；
- 新安装在引导页设置公司/家庭后，地点管理中立即可见，重启后围栏仍存在；
- vivo 恢复通知、系统定位设置跳转和前台服务保持正常。

## 11. 实施阶段

1. 半径事实源与地点变更刷新；
2. 工资派生缓存统一刷新；
3. 删除死代码与权限；
4. DB v18、日志裁剪和设置 UI；
5. 通知 ID 池；
6. 引导页真实站点与围栏联动；
7. 全量回归、迁移验证、APK 构建与真机验收准备。
