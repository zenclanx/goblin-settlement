# 哨卫报警设计

更新日期：2026-09-27。依据：GAME_DESIGN.md 第 30 行（哨卫职责含报警）、第 132 行（哨卫报警；玩家路过不算敌人；明确的攻击触发防卫）、第 151 行（居民简短状态展示正在做什么）；PROFESSION_DESIGN.md §12（哨卫的报警工作）；SENTRY_PATROL_DESIGN.md（巡逻是报警的前提）。

## 1. 目标与范围

**目标**：让巡逻中的哨卫在**目击**敌对生物时报警，复用既有的傀儡警戒链路——把防卫提前到威胁动手**之前**。这是巡逻这项工作的价值兑现：只有巡逻，哨卫才会"在某处看见"。

**本轮做**：哨卫目击 `Monster` → 惊动附近傀儡；实体状态里能看出它在报警。

**本轮不做**：
- **引导避难**与**有限自卫**（GAME_DESIGN 四项职责里另外两项）：前者要平民避难 AI 与避难所，后者要哨卫能打。
- **惊动原版铁傀儡**：见 §4。
- 任何**持久的警戒状态**：本轮只在事件当下惊动傀儡，不落盘、不供后续消费。要做状态就得连"引导避难"一起设计，单独先造接口是本项目一直在避免的事。

## 2. 现状：只有"被打"才会报警

`RelationshipCoordinator` 在**居民受到伤害**时调用 `DefenseCoordinator.onResidentAttack`，后者惊动受害者附近的傀儡；`GoblinGolemEntity.alertToResidentAttack` 的注释写着「Call only from an event confirming this attacker actually attacked this resident」。

所以现有链路的触发条件是**已经有人挨打**。哨卫的增量在于**看见就报**——威胁还站在村外时，防卫就已经开始。

## 3. 触发与边界

### 3.1 只有 `Monster` 算威胁

**玩家不算。** GAME_DESIGN 第 132 行写得很明确：「玩家携带武器、路过仓库或住在旁边不会自动成为敌人。明确的攻击和严重破坏触发防卫。」

这与既有设计一致：玩家只有在**确实动手**时才经由 `onResidentAttack` 那条事件进入防线。哨卫**不能**因为看见玩家就报警——那正是 GAME_DESIGN 点名禁止的那种误判。

实现上直接复用 `GoblinGolemEntity.isPermittedAttacker`：它判「`Monster`，或非创造/非旁观玩家」。哨卫侧只取其中的 `Monster` 分支；玩家分支留给"确实打了人"的事件路径。

### 3.2 目击半径 12，小于傀儡的防卫半径 24

`GoblinGolemEntity.DEFENSE_RADIUS = 24`。新引入 `DefenseCoordinator.SIGHTING_RADIUS = 12`，**刻意减半**：

- 哨卫只报"它看得见的东西"，不替傀儡做决定；
- **是否出动仍由傀儡自己按 24 格、归属与存活规则判定**（`alertToSighting` 与既有的 `alertToResidentAttack` 共用同一组守卫）；
- 半径减半压低"路过一只僵尸就全村出动"的概率。

**这不消除误报，只是压低**：聚落旁有黑暗刷怪区时，傀儡仍会频繁出动。两者的警戒都只维持 `ALERT_TICKS`（15 秒）后自动解除（既有逻辑），所以不会永久牵制。

### 3.3 只报最近的那一只

`getEntitiesOfClass` 的返回顺序不作保证。取**距哨卫最近**的一只，让"报谁"有唯一确定的答案，而不是随实体遍历顺序变化。

## 4. 复用而非新建

- **`GoblinGolemEntity.alertToSighting(LivingEntity threat)`**：与既有的 `alertToResidentAttack` **同一组守卫**，只去掉与受害者有关的三条（受害者存活、受害者是本聚落居民、受害者距离）。目击场景**没有受害者**，其余规则（攻击者类型、存活、与傀儡与家的距离、聚落归属）逐字保留。
- **`isPermittedAttacker` 由 `private` 改包内可见**：`DefenseCoordinator` 与它在同一个包，复用它而不是复制一份"谁算威胁"的判定。
- **`DefenseCoordinator.reportSighting(ServerLevel, GoblinCitizenEntity)`**：找最近的可见 `Monster`，对哨卫周围 `DEFENSE_RADIUS` 内的傀儡逐个调用 `alertToSighting`，返回"是否真的惊动了至少一只"。
- **不惊动原版铁傀儡**：`VanillaIronGolemBridge.alert(level, victim, attacker)` 的签名**需要一个受害者**，目击场景没有。原版铁傀儡被攻击时本来就会自己还手。**这是本轮的已知边界**，不是遗漏。

## 5. 实体接线

`tickPatrolWork` 每 tick（哨卫按对口档为 10 tick 一次）先调 `reportSighting`，再走原来的移动逻辑；有警报时把 `waitReason` 写成"spotted a hostile"，让 `/goblinsettlement work` 看得见。

理由：GAME_DESIGN 第 151 行要求「居民简短状态展示正在做什么」，而 `waitReason` 就是那个字段。**不加新命令、不加新同步字段**——复用既有显示路径。

## 6. 验证：本轮没有新增独立检查

**必须说清楚**：本轮**不会新增任何独立检查**。

涉及的全部是 `ServerLevel`、实体查询与 `instanceof Monster`——**纯函数层无从下手**（项目的检查全是无 JUnit 的纯计算检查）。这与第四十七轮的派工收口同类。

因此本轮的证据只有：完整离线构建 + 既有 **13 项**检查全部通过（它们覆盖不到本次改动，但能保证没有编译级与跨模块的连带破坏）。**"哨卫真的会报警、且不会因为玩家路过就报警"只能靠代码审查与游戏内观察**，日志里要如实写明，不得记成已验证。

## 7. 风险

- **误报**：见 §3.2。已用半径减半压低，未消除。统一测试时要观察傀儡是否被长期牵制在追击上。
- **无新检查**：见 §6。这是本轮最大的性质。
- **铁傀儡不在目击链路**：见 §4。原版铁傀儡只会被动还手。
- **覆盖面只有一条线**：第四十九轮的巡逻是"一次一名"，所以报警的覆盖也随那一条巡逻线。聚落变大而巡逻线不变，报警覆盖不会增长——要改就得先改巡逻形态。
- **实体改动**：`tickPatrolWork` 增加一次实体查询（每 10 tick、半径 12 的 AABB）。只有一名哨卫在巡逻，量可接受；若将来巡逻线增多，这里要重新算代价。
- **依赖既有守卫的语义**：`alertToSighting` 的正确性完全建立在"复用的那组守卫仍然正确"上。若将来有人改了 `alertToResidentAttack` 的守卫而漏改 `alertToSighting`，两者会分叉——这与本项目在床位判据上修过的问题同类。**本轮不抽取共用守卫**（抽取会把受害者相关的三条也参数化，反而更难读），但要在 `alertToSighting` 的注释里写明它与谁成对、改一处要看另一处。
