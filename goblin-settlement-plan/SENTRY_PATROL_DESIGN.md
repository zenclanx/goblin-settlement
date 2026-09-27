# 哨卫巡逻设计

更新日期：2026-09-27。依据：GAME_DESIGN.md 第 30 行（哨卫职责：巡逻、报警、引导避难、有限自卫）、第 132 行（优先保护居民，再保护关键设施）；TECH_DESIGN.md 第 27 行（`defense` 负责傀儡、警报、巡逻）；PROFESSION_DESIGN.md §2.3、§4.2（哨卫无工作时按通才对待的临时规则）、§12（哨卫的巡逻/警报工作）。

## 1. 目标与范围

**目标**：让哨卫真正有活干——按一条巡逻线在聚落里走动。这也是报警的前提（哨卫得先人在某处，才谈得上目击）。

**本轮做**：`WorkKind.PATROL`、航点序列、派工、实体的巡逻阶段。

**本轮不做**：
- **报警、引导避难、有限自卫**（GAME_DESIGN 的四项职责里另外三项）。前两项牵涉威胁判定与平民避难 AI，第三项还要模型能拿武器——而 `GoblinModel` 不是 `HumanoidModel`，PROFESSION_DESIGN §9.3 已把手持物件列为高风险。都留下一轮。
- 巡逻点方块（GAME_DESIGN 第 93 行把"巡逻点"列为待建设施之一）——本轮不需要新方块。
- 巡逻强度的调节（GAME_DESIGN 第 140 行提到盗窃会"加强巡逻"）——那是报警落地后的事。

## 2. `WorkKind.PATROL` 与它自动带来的连带

```java
    PATROL(Profession.SENTRY),
```

加进 `colony/WorkKind` 后，**预期的连带效果**（这正是本轮要的）：

- `WorkKind.employs(Profession.SENTRY)` 变 `true`；
- 于是 PROFESSION_DESIGN §4.2 那条"某职业若不对应任何 `WorkKind`，它对所有工作按未定职档处理"的临时规则**对哨卫自动失效**——哨卫在巡逻上拿对口档、基准速度（`workIntervalTicks` 的 0 档）；
- §2.3「哨卫本轮无工作，按通才对待」的存根随之撤销；
- `ProfessionRulesCheck` 里 `WorkKind.employs(Profession.SENTRY) == false, "sentry has no work kind yet"` 这条断言要改成 `true`——它就是"临时规则到期"的信号。

**其余职业不受影响**：哨卫只是从"无工作"变成"有工作"，其他职业的对口关系没动。

## 3. 航点序列（纯规则，可测）

从**存档里已有的设施点**取航点，不需要新方块：营地锚点 + 全部仓库 + 全部农田的作物位置（`SettlementSavedData` 已有这三者的查询）。

纯函数（`defense/PatrolRules`，与既有的 `GolemPopulationRules` 一样是 defense 包里的纯类）：

```java
/**
 * The anchor first, then the facilities nearest-first. Ties break by x then z so the order is stable
 * across saves. Identical points are folded into one -- a warehouse inside a farm would otherwise be
 * visited twice in a row.
 */
public static List<int[]> waypoints(List<int[]> facilities, int[] anchor)
```

- 距离用三维平方和（设施可能在不同高度）。
- 锚点距离 0，自然排在首位；**一个设施都没有时，路线退化为单点**（哨卫走到锚点就站着）——这是可接受的：没有设施就没什么可巡的。

## 4. 派工：一次一名

`defense/PatrolCoordinator`，**照搬采矿的单在途范式**：

- 门禁：`ResidentWorkLookup.anyLoaded(level, data, goblin -> goblin.hasPatrolWork(settlementId))` 为真则返回——**同一时刻只有一名居民在巡逻**。
- 选人：`WorkerDispatch.nearest(level, WorkKind.PATROL, anchor, eligible)`（第四十七轮的服务）。哨卫在 `matchRank` 上是 0 档，所以**有空的哨卫会胜出**；但没有哨卫空闲时，任何可用居民都能巡逻——符合 PROFESSION_DESIGN §2.1「**不做门控，只做速度与偏好**」。（Housing 那处用的是 `isAvailableForConstruction` 这种不加权限检查的谓词；巡逻在聚落内部走，沿用同一选择。）
- 派工：`goblin.assignPatrol(settlementId)`，并把 `patrolIndex` 归零。

**为什么不分段**：一条线一次只由一人走，所以不需要给每人分配不同区段，也不需要错开起点。

**代价，写明白**：聚落再大也只有**一条**巡逻线。第四十八轮的配额因此**不影响巡逻**——它只决定"村里挂了多少个哨卫头衔"。要让巡逻覆盖随人口增长，得等报警与多巡逻区落地。

## 5. 实体侧

- 新增 `WorkStage.PATROL_WALKING`；`workKind` 映射里加 `case PATROL_WALKING -> WorkKind.PATROL;`。
- 新增 `assignPatrol(String id)` 与 `hasPatrolWork(String id)`，照 `assignMining` / `hasMiningWork` 写。
- 新增 `tickPatrolWork(ServerLevel)`：取当前航点 → 若已在到达半径内则 `patrolIndex++` → 否则导航过去。
- `patrolIndex` 是**瞬态字段、不入存档**——与采矿"不保留持久预约、每次从世界重推"一致。实体卸载再加载后由协调器重新派工，序号归零即可。
- **不碰背包**：巡逻没有材料，因此没有取料、携带、交付、归还这四段，`placeByResident` 那类路径一概不涉及。
- 巡逻**不会自己结束**（是循环），只在被取消或居民死亡/卸载时停止；`applyProjectCancellation` 的通用路径已能把它清回 `IDLE`。

## 6. 验证

**本轮有新增覆盖**：`PatrolRules.waypoints` 是纯函数，写独立检查（注册进 `build.gradle` 的 `check` 聚合，与既有 12 项并列）：

- 锚点排第一；
- 设施按距锚点由近及远；距离相同时按 x、再按 z；
- 同坐标的点被折叠成一个；
- 没有设施时只剩锚点；
- 同一组输入两次得到同一份列表（确定性）。

另加 `ProfessionRulesCheck` 的断言翻转（`employs(SENTRY)` 由 false 改 true）。

实体行为（真的走过去、到了就换点）**不可纯测**，只能靠编译与代码审查——这一条要写进日志与状态文档。

完整离线构建 + 全部独立检查。**不做游戏内验证**（按用户约定）。

## 7. 风险

- **实体侧改动面宽**：`WorkStage` 枚举、`workKind` 映射、主 tick 的分发链、`assignPatrol`/`hasPatrolWork`、`tickPatrolWork`。其中"新增工作阶段忘了登记 `workKind`"这条风险**已经不存在**：`workKind` 是**穷尽 switch 表达式**（无 `default`），漏登记会**编译报错**而不是静默按对口处理。PROFESSION_DESIGN §13 那条旧风险描述已过时，本轮一并更正。
- **导航可能到不了**：航点在仓库/农田中心，而 `getNavigation().moveTo` 走到的是方块中心；若落脚点被占（箱子本身、作物），寻路可能失败。本轮的处理是"到不了就一直重试"（每 tick 重新下发目标，与既有的 `moveTo` 用法一致），不额外做可达性判定。**这可能表现为哨卫在某个设施旁反复晃动**——统一测试时要注意。
- **巡逻永不结束**：选中谁，谁就一直巡逻下去，直到死亡/卸载/被取消。本轮不做轮换。
- **只有一条线**：见 §4 末。配额与巡逻覆盖面脱钩。
- **`SettlementSavedData` 的设施查询在 tick 热路径上**：`waypointFor` 每 tick 调用一次（航点数量是常数级），与采矿每 tick 重推工地的既有做法同量级；若将来设施数量大增，这里需要改成缓存。
