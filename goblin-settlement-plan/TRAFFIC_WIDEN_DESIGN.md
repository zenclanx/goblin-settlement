# 道路加宽（通行量驱动的升级 · 第二轮）设计

更新日期：2026-09-27。依据：GAME_DESIGN.md 第 7 节「先有安全通行路线，再根据频率与搬运耗时铺装和升级。建议通行净宽：初期步道 2 格，普通道路 3 格，成熟主街 5 格」；[TRAFFIC_UPGRADE_DESIGN.md](TRAFFIC_UPGRADE_DESIGN.md) §5（本轮要解的三个硬点）与 §9（第一轮落地结果）；CURRENT_STATUS 交通剩余第 1 项。

## 0. 范围

| | 内容 |
| --- | --- |
| **本轮做** | 把够格的路**真的加宽**：2 → 3 → 5，走既有工人路径铺 |
| 不做 | 石桥与更长跨度、成熟期多工程并行、道路连通性验收、新材质（石板/主街铺装）、土路（`DIRT_PATH`）的加宽、逐条路的在线阈值命令 |
| 沿用不改 | 第一轮的两个发明值 `TRAFFIC_PER_LANE = 200` 与 `TrafficSampler.SAMPLE_INTERVAL_TICKS = 100`（用户 2026-09-27 决定先把机制做完；两者仍标记**没有依据、待按真实计数一起重定**） |

## 1. 现状（已核对）

- **铺法**：`TransportCoordinator.startRoad` 沿 `RoadPlanner` 的中线逐格铺 `List.of(foot, foot.relative(side))`，其中 `side = forward.getClockWise()`；`site = lane.below()`；步是 `SURFACE / OAK_PLANKS / ROAD_GROUND`；`sites` 是按地面格去重的 `LinkedHashMap`（`putIfAbsent`）。
- **中线不入档**，而且从步列表重推是残缺的：去重（`putIfAbsent`）正好发生在弯角，也就是方向最要紧的地方。
- `TransportCoordinator.roadDirection(route, index)` 是**私有**的，`safeRoadFoot` / `safeRoadGround` / `conflictsWithExistingWork` 同样私有。
- `TransportSavedData.MAX_COMPLETED_ROADS = 32`；`trimCompletedRoads` 淘汰最早的已完成 ROAD，它的计数由同一次 `replace` 里的 `pruneTraffic` 一并清掉。
- `RoadUpgradeRules`：`LADDER = {2, 3, 5}`、`BUILT_ROAD_LANES = 2`、`TRAFFIC_PER_LANE = 200`、`nextLanes`、`shouldUpgrade`。**全仓没有"某条路现在多宽"的持久概念**——`BUILT_ROAD_LANES` 常量与 `startRoad` 里字面写的两条车道，就是第一轮留下的两处同源事实（第五十四轮终审建议本轮合并）。
- `TrafficSampler` 的索引把「已完成 ROAD 计划的 SURFACE **地面格**」映射到计划 id，按 `(实例, revision)` 缓存。
- `TransportSavedData.add` 在任何计划未完成时拒绝——加宽因此天然受**单在途门禁**。
- `TransportPlan` 校验：`ROAD` 不得携带桥梁闭合状态（`barrierFeet` / `closedFootprint` 必须为空、`open` 必须为假）。只含 SURFACE 步的加宽计划满足。
- `TrafficProposalCoordinator`：1200 tick 节拍 + 单在途 + 材料门禁 + 持久化延期名单（第一轮为"无法服务的目标"加的）。

**三个硬点的答案**（设计 §5）：硬点一（步列表游标错位）与硬点二（推不出路线方向）由 §2 的链 + §3 的中线共同解决；硬点三（加宽后旧车道的样本算谁的）由 §2 的"按链求和"解决，且**不需要改采样器**。

## 2. 数据模型：加宽链

`TransportPlan` 加三个字段，全部 `optionalFieldOf`、**不升 schema 版本**：

| 字段 | 用途 | 旧档缺省 |
| --- | --- | --- |
| `route: List<BlockPos>` | **中线格序列**，每级路线一项；加宽几何的唯一依据 | 空 → 该路不加宽 |
| `widens_from: Optional<String>` | 指向被它加宽的那条路；同时是"链成员"的标记 | 空 = 原路 |
| `lanes: int` | 这条计划建完后该路的**总宽** | 2 |

- **路宽与通行量都按链算**：链宽 = 链上 `lanes` 的最大值；链通行量 = 链上各计划计数**之和**。所以加宽既不会把一条路的通行量"归零"，也不会因为旧车道继续被采样而反复满足条件（判据要求随宽度翻倍，见 §4）。
- **加宽计划只含新增车道的格**，原计划一字不动——已完成的计划永远保持完成态（这是选链式而非"在既有计划尾部追加"的理由）。
- 加宽计划 `target_facility` **留空**：加宽不是在服务某个设施，留空比继承父计划的目标更诚实（`replace` 里"完成即登记已服务设施"的分支因此不会被触发）。
- 加宽计划的 `route` **逐字复制父计划的中线**：这样每条成员都自描述（"这条路的中线在哪"），链上任取一条都能回答；重复的是几十个坐标，代价可接受。
- 链的查找（`chain(planId)`）放在 `TransportSavedData`：**先沿 `widens_from` 向上走到根**（`widens_from` 为空的那条），**再收集所有"根相同"的计划**（对每条计划各自向上找根来比对）。这样从链上任意一条出发都能得到完整的链，且不需要额外维护子表。`TransportSavedDataCheck` 直接构造该对象，所以**链语义可纯测**。
- `RoadUpgradeRules` 保持只吃 `int`（不依赖 `TransportPlan`）；链的求和与取最大在调用方完成。

## 3. 几何：完全纯函数

车道用**相对中线的索引**表示，与梯子一一对应：

| 总宽 | 车道索引 | 相对第一轮新增 |
| --- | --- | --- |
| 2 | `{0, 1}` | ——（`startRoad` 铺的就是 `foot` 与 `foot + side`，即索引 0 与 1） |
| 3 | `{0, 1, 2}` | `+2` |
| 5 | `{-1, 0, 1, 2, 3}` | `-1` 与 `+3` |

- **2 → 3 只补一侧**（`foot + side*2`）：宽度上加"半条车道"不存在，所以这一步必然偏向一侧，这是数学上的选择而非偏好。
- **3 → 5 两侧各补一条**（`foot - side` 与 `foot + side*3`）：对原中线重新居中对齐。
- 新格 = `(route[i] + side_i * k).below()`——`site` 仍是**走路格下方的地面**，与第一轮一致，所以采样索引自动认得新车道，`TrafficSampler` 一行不用改。

**抽出 `planning/transport/RoadLayout`**，成为"一条路的车道落在哪"的唯一出处：

```java
/** 每级路线的前进方向；末级与前一级相同（沿用既有的端点处理）。 */
public static int[] direction(List<BlockPos> route, int index)   // {dx, dz}

/** 顺时针旋转：Direction.getClockWise() 在坐标上就是 (dx, dz) -> (-dz, dx)。 */
public static int[] clockwise(int[] direction)

/** 该宽度下、该级路线的一格车道格（走路格）。 */
public static BlockPos lane(List<BlockPos> route, int index, int laneOffset)

/** 从 fromLanes 加宽到 toLanes 需要新增的车道格，按路线顺序。 */
public static List<BlockPos> newLaneFeet(List<BlockPos> route, int fromLanes, int toLanes)
```

- 顺时针公式 `(dx, dz) -> (-dz, dx)`：北 `(0,-1)` → 东 `(1,0)`；东 `(1,0)` → 南 `(0,1)`。与 `Direction.getClockWise()` 一致。
- `RoadLayout` 只依赖 `BlockPos` 与整数方向对，**不引入 `Direction`**，与 `RoadUpgradeRules` / `TrafficDecision` 一样保持可独立检查。
- `TransportCoordinator.startRoad` 改走 `RoadLayout.lane(...)` 与 `lanes = 2`（**行为不变**：仍然按地面格 `putIfAbsent` 去重，仍然逐级同时铺两条车道），`roadDirection` 从 `TransportCoordinator` 删除。**第一轮那两处同源事实（`BUILT_ROAD_LANES` 常量、`startRoad` 里的字面两条车道）至此合并**：宽度改由每条计划的 `lanes` 字段承载。`BUILT_ROAD_LANES` 随之删除，**第一轮的 `RoadUpgradeRulesCheck` 里针对它的两条断言（`BUILT_ROAD_LANES == 2`、`nextLanes(BUILT_ROAD_LANES) == 3`）要一并改掉**，`status` 显示改用计划字段。

## 4. 判定与提案

- **够格**：`RoadUpgradeRules.shouldUpgrade(链宽, 链计数和)`。满档 5 永远为假，所以不会无限加宽；`nextLanes` 给出目标宽度（2 → 3，3 → 5）。
- **提案落点**：既有的 `TrafficProposalCoordinator`（它已有 1200 tick 节拍与两道门禁），新增一个判断点：**先服务没有路可达的设施；没有待办新路时才考虑加宽**。依据是 GAME_DESIGN §7「先有安全通行路线，再根据频率与搬运耗时铺装和升级」——连通性优先于容量。
- **失败回退（无新增持久状态）**：先按链把够格的**路**列出来——**每条链只算一次**（用该链的当前代表 = `lanes` 最大的成员，并列时取 id 较小者，保证确定），再按链通行量**降序**排列，取第一条"新增格**全部**通过既有安全判定与冲突判定"的路立项；全都不通过则本轮不立项，下个节拍重试。这样最繁忙的路被建筑挡住时不会永久顶住其他路。**不给加宽做延期名单**——第一轮的延期名单是为"无法服务的目标"加的，加宽每 1200 tick 重试一次的代价可以接受，而多一份持久状态要多一份清理与迁移责任。
- **安全判定复用**：新增格必须过 `TransportCoordinator` 里那套（`safeRoadFoot`：走路格与头顶两格为空、下方是可铺地面、三格权限均 ALLOWED），且不得与**未完成的**其他工程或农田同列（`conflictsWithExistingWork`）。这两个私有方法提为**包内可见的共用助手**，不复制第二份。
- **整体成功或整体放弃**：只要有**一格**新车道不通过，这条路的加宽就整体不做（不做"半条路加宽"）。理由是部分加宽会让路的宽度随地形参差，而判据与显示都按整条路算宽度。
- **材料**：新增车道一律 `OAK_PLANKS`（同一条路同一材质），不引入新材质。
- **施工零新增机械**：加宽计划就是一条只含 `SURFACE / OAK_PLANKS / ROAD_GROUND` 步的 `ROAD` 计划，工人取料、放置、单在途门禁、材料门禁全部沿用第一轮的既有路径。

## 5. 保留与显示

- **淘汰豁免**：`trimCompletedRoads` 跳过链成员（自己带 `widens_from`，或被别的计划指为父）。不这么做的话，最繁忙的路恰恰建得最早、最先被 32 条上限淘汰、计数也被 `pruneTraffic` 清掉——加宽会**在最需要它的成熟聚落里彻底不发生**。上界因此从 32 变成 `32 + 3 × 加宽过的路数`；路数本身受设施数限制，不是无界。
- **显示**：`goblinsettlement status` 的 `Road traffic:` 行补上宽度与链口径，例如
  `Road traffic: 3 road(s), 1 ready to widen (*); 1a2b3c4d=412 L3* , 5e6f7a8b=88 L2, …`
  （`L<n>` = 该路当前宽度，`*` = 够格；排序与 8 条上限不变，计数改用**链计数和**）。

## 6. 验证

- **新增第 16 项独立检查 `RoadLayoutCheck`**（纯层，覆盖 `RoadLayout` 与加宽几何）：
  - 直路：2 → 3 只多一条、3 → 5 两侧各多一条，且索引与上表逐条对齐；
  - 带弯角的阶梯路线：每个弯角处新车道跟着车道对一起旋转（这正是"纯局部规则"做不到的）；
  - 末级路线（端点处理）与单格路线（退化输入）；
  - 中线含重复格 / 相邻格重合时按格去重，不重复出新格；
  - 顺时针公式四个方向各断言一次。
- **`TransportSavedDataCheck` 补链语义**：链宽取最大、链计数求和、淘汰豁免（链成员不被退掉、非链成员照旧退掉）、旧档缺 `route` 时 `lanes` 落到 2 且不加宽、codec 往返。
- **第 16 项使检查总数 16**。
- **不可纯测（会写进日志与状态文件）**：新增格的安全判定、工人真的去铺、加宽后采样是否真的记在新车道上、游戏内观感（3 格与 5 格走起来如何）、以及"半条路加宽"的整体放弃在实际地形下的表现。

## 7. 风险与已知边界

- **旧计划没有中线**：本轮之前建成的路 `route` 为空 → **跳过加宽**（不是报错，也不是回退到残缺推导）。目前没有真实存档，代价接近零；但要写进日志。
- **阈值仍是发明值**：见 §0。加宽机制本身不依赖它，改一行即可重定。
- **2 → 3 偏向一侧**：见 §3。这是宽度取整的必然结果，不是偏好；观感上 3 格路的中线会偏。
- **计数是累计量、不按经过的采样数归一**：一条路建得越早、存在越久，它的链计数和就越大。加宽判据因此偏向"先建的先加宽"。这与第一轮 §9 记的同一条局限，第二轮仍未处理（要处理得改成按单位时间归一，属于新的一轮）。
- **链的父被退掉**：豁免规则保证链成员不被 `trimCompletedRoads` 淘汰；但玩家用命令删计划（若有）或存档被手工编辑仍可能造成 `widens_from` 悬空。悬空时链退化为该成员自己，**不崩，不报错**。
- **两条路并排/交叉**：新增格若落在另一条路的已铺格上，`ROAD_GROUND` 的 `buildableGround` 包含 `OAK_PLANKS`，`tickPlan` 见到目标方块已在也会直接 `advance`——所以并入相邻路是幂等无害的，不是冲突。
- **未做游戏内验证**：与本分支既有全部工作一样，本轮只有编译与独立检查证据。**不要在统一验证之前把它当作可用版本。**

## 8. 落地结果（实现后补记）

- **数据**：`TransportPlan` 新增一个 `road` 字段（嵌套 `Road(widens_from, lanes, route)`，全部 `optionalFieldOf`，未升 schema）。旧档读入后：`lanes() == 2`、`route()` 空、`widensFrom()` 空，**因此旧路不加宽**（设计 §7 已预先声明）。
- **链口径的唯一出处**：`TransportSavedData.roadWidth`（链上最大宽度）、`roadTraffic`（链上计数之和）、`roads()`（每条路取最宽成员当代表）、`wideningCandidates()`（够格的、最重的在前）。显示与提案都只调这四个，没有第二份判定。链的父缺失（`widens_from` 悬空：玩家删计划或手工编辑存档）时链退化为该成员自己，**不崩、不报错**。
- **淘汰豁免**：`trimCompletedRoads` 只退"不在链上的"已完道路，`isRetirable` 是唯一判据。上界 `32 + 3×加宽过的路数`。
- **几何唯一出处**：`planning/transport/RoadLayout`（`laneOffsets` / `direction` / `clockwise` / `laneFeet` / `newLaneFeet`），`startRoad` 与本轮新增的加宽共用它；第一轮的 `BUILT_ROAD_LANES` 常量与 `startRoad` 里字面写的两条车道**已合并**为 `RoadUpgradeRules.baseLanes()` + 每条计划的 `lanes`。`TransportCoordinator.roadDirection` 已删除。
- **提案落点**：`TrafficProposalCoordinator` 只在需求档为 `READY`（即没有待办新路、且食物/种子/工具/住房都不缺）时提案加宽；按 `wideningCandidates()` 的顺序逐个尝试，第一个通过安全判定与冲突判定的立项。**加宽不进需求档**——所以一条被建筑永久挡住的加宽不会像第一轮的"无法服务的目标"那样冻结扩地。
- **加宽计划**：只含新增车道格（`SURFACE / OAK_PLANKS / ROAD_GROUND`），`target_facility` 留空，`route` 复制父计划的中线，施工走既有工人路径与两道门禁。
- **显示**：`Road traffic:` 行改成按链显示 `id8=链计数和 L<宽度>[ *]`，一条加宽过的路只出现一次。
- **检查**：新增第 16 项 `roadLayoutCheck`（`RoadLayoutCheck`）。
- **阈值仍未定**：`TRAFFIC_PER_LANE = 200` 与 `SAMPLE_INTERVAL_TICKS = 100` 原样沿用，注释与本文档继续写明"没有依据、待按真实计数一起重定"。
