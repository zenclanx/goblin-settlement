# 哨卫报警 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让巡逻中的哨卫在目击敌对生物时惊醒附近傀儡，把防卫提前到威胁动手之前。

**Architecture:** 复用既有的傀儡警戒链路：新增一个"没有受害者"的入口 `GoblinGolemEntity.alertToSighting`，守卫与既有 `alertToResidentAttack` 逐字对应（只去掉与受害者有关的三条）；`DefenseCoordinator.reportSighting` 负责"找最近的可见 `Monster`"与"通知周围哪些傀儡"；实体在自己的巡逻 tick 里调用它，并把结果写进 `waitReason` 以便观察。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [SENTRY_ALERT_DESIGN.md](SENTRY_ALERT_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **玩家不算威胁**：哨卫不得因看见玩家而报警（GAME_DESIGN 第 132 行点名禁止）。玩家只在**确实攻击**时经由既有的 `onResidentAttack` 路径进入防线——那条路径本轮一行不改。
- **目击半径 12 必须小于 `DEFENSE_RADIUS`（24）**：哨卫只报看得见的，是否出动由傀儡自己按 24 格规则决定。
- **不惊动原版铁傀儡**：`VanillaIronGolemBridge.alert` 需要一个受害者，目击场景没有。这是已知边界，不要为此改它的签名。
- **不改 `onResidentAttack` 与 `alertToResidentAttack` 的行为**：新入口与它们成对，不是替代。
- **本轮不新增独立检查**（纯函数层无从下手）。日志里必须写明，不得记成已验证。
- 不做游戏内验证（按用户约定）。
- `goblin-settlement-plan/` 下**可能有并行 agent 的未提交改动**：**在文档任务开始时**先跑 `git status`，发现不是自己改的就**先单独提交它们并署名**，再追加自己的。`UpdateLog.md` **只许在末尾追加**。

---

### Task 1: 目击链路与实体接线

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/GoblinGolemEntity.java`（`isPermittedAttacker` 改可见性；新增 `alertToSighting`）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/DefenseCoordinator.java`（新增 `SIGHTING_RADIUS` 与 `reportSighting`）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`（`tickPatrolWork` 里调用）

**Interfaces:**
- Consumes: `GoblinGolemEntity.DEFENSE_RADIUS`、`GoblinGolemEntity.settlementId()`、既有的 `setTarget`/`alertTicks`/`isPermittedAttacker`
- Produces:
  - `GoblinGolemEntity.alertToSighting(LivingEntity threat) -> boolean`
  - `DefenseCoordinator.reportSighting(ServerLevel level, GoblinCitizenEntity sentry) -> boolean`

- [ ] **Step 1: 傀儡加"无受害者的警报"入口**

1a. 把 `GoblinGolemEntity` 里

```java
    private static boolean isPermittedAttacker(LivingEntity entity) {
```

改成包内可见（去掉 `private`）：

```java
    static boolean isPermittedAttacker(LivingEntity entity) {
```

（`DefenseCoordinator` 与它在同一个包；这样"谁算威胁"只有一个定义，不复制第二份。）

1b. 在 `alertToResidentAttack(GoblinCitizenEntity victim, Entity cause)` 之后加：

```java
    /**
     * Call when a settlement sentry reports a hostile it can see. Paired with alertToResidentAttack:
     * the guards below are the same ones, minus the three that need a victim -- a sighting has none.
     * Changing one of the two means checking the other.
     */
    public boolean alertToSighting(LivingEntity threat) {
        if (threat == null || !(level() instanceof ServerLevel level) || settlementId.isBlank()
                || !threat.isAlive() || threat.level() != level
                || !isPermittedAttacker(threat)
                || distanceToSqr(threat) > DEFENSE_RADIUS * DEFENSE_RADIUS
                || threat.distanceToSqr(home.getX() + 0.5, home.getY() + 0.5, home.getZ() + 0.5)
                        > DEFENSE_RADIUS * DEFENSE_RADIUS) {
            return false;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        if (data.settlement().map(value -> !value.id().equals(settlementId)).orElse(true)) {
            return false;
        }
        setTarget(threat);
        alertTicks = ALERT_TICKS;
        return true;
    }
```

**与 `alertToResidentAttack` 的差异只有三处，且都是因为"没有受害者"**：不查受害者存活、不查受害者是本聚落居民、不查受害者与傀儡的距离。其余逐字相同。

- [ ] **Step 2: 协调器加目击上报**

在 `DefenseCoordinator` 里，`onResidentAttack` 方法之后加：

```java
    /** How far a sentry can pick out a hostile. Deliberately half a golem's own defence radius. */
    private static final double SIGHTING_RADIUS = 12.0;

    /**
     * A patrolling sentry reports what it can see. Only monsters count: GAME_DESIGN is explicit that a
     * player who merely carries a weapon or walks past is not an enemy, and an attack on a resident
     * already reaches the golems through onResidentAttack. Returns true when at least one golem took
     * the alert, so the caller can show that in its status line.
     */
    public static boolean reportSighting(ServerLevel level, GoblinCitizenEntity sentry) {
        if (level == null || sentry == null || sentry.level() != level) {
            return false;
        }
        LivingEntity threat = level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(sentry.blockPosition()).inflate(SIGHTING_RADIUS),
                        entity -> entity instanceof Monster && entity.isAlive())
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(sentry)))
                .orElse(null);
        if (threat == null) {
            return false;
        }
        boolean alerted = false;
        for (GoblinGolemEntity golem : level.getEntitiesOfClass(GoblinGolemEntity.class,
                new AABB(sentry.blockPosition()).inflate(GoblinGolemEntity.DEFENSE_RADIUS),
                golem -> golem.isAlive() && !golem.settlementId().isBlank())) {
            alerted |= golem.alertToSighting(threat);
        }
        // Vanilla iron golems are left out on purpose: their bridge takes a victim, and a sighting has
        // none. They still defend themselves when something actually hits them.
        return alerted;
    }
```

需要的 import（**按编译提示补，不要预先猜**）：`java.util.Comparator`、`net.minecraft.world.entity.LivingEntity`、`net.minecraft.world.entity.monster.Monster`。`net.minecraft.world.phys.AABB` 与 `net.minecraft.server.level.ServerLevel` 该文件已用。

- [ ] **Step 3: 实体在巡逻 tick 里上报**

把 `tickPatrolWork` 整个方法替换为：

```java
    private void tickPatrolWork(ServerLevel level) {
        boolean alerting = DefenseCoordinator.reportSighting(level, this);
        var target = PatrolCoordinator.waypointFor(level, settlementId, patrolIndex);
        if (target.isEmpty()) {
            waitReason = "no patrol route";
            getNavigation().stop();
            return;
        }
        BlockPos destination = target.get();
        if (distanceToSqr(destination.getCenter()) <= PATROL_ARRIVE_DISTANCE_SQ) {
            patrolIndex++;
            waitReason = alerting ? "spotted a hostile" : "";
            return;
        }
        waitReason = alerting ? "spotted a hostile" : "patrolling";
        getNavigation().moveTo(destination.getX() + 0.5, destination.getY(),
                destination.getZ() + 0.5, 1.0);
    }
```

`GoblinCitizenEntity` 需要 `import dev.local.goblinsettlement.defense.DefenseCoordinator;`（它已 import `defense.PatrolCoordinator`，照那行排；`DefenseCoordinator` 字母序在前）。

**为什么把上报放在最前**：到达航点时会提前 `return`，若把上报放在后面，哨卫停在航点上时就不再看四周了——站在路口守望恰恰是它最该看的时刻。

- [ ] **Step 4: 编译并跑全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**13 项**检查全部 `*Check passed`，无编译警告。

若报 `isPermittedAttacker` 不可见，说明 Step 1a 没做；若报 `alertToSighting` 找不到，说明 Step 1b 没做。

- [ ] **Step 5: 核对接线**

Run: `grep -n "alertToSighting\|reportSighting\|SIGHTING_RADIUS\|static boolean isPermittedAttacker" src/main/java/dev/local/goblinsettlement/defense/GoblinGolemEntity.java src/main/java/dev/local/goblinsettlement/defense/DefenseCoordinator.java src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`

Expected: 傀儡里能看到放开的 `isPermittedAttacker` 与 `alertToSighting`；协调器里能看到 `SIGHTING_RADIUS` 与 `reportSighting`；实体里能看到调用。

- [ ] **Step 6: 核对没有改到既有路径**

Run: `git diff --stat` 与 `git diff -- goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/GoblinGolemEntity.java`

Expected: `alertToResidentAttack` 的方法体**一行未动**；`isPermittedAttacker` 只动了可见性。`RelationshipCoordinator` 不在改动文件之列。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/GoblinGolemEntity.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/DefenseCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java
git commit -m "Let a patrolling sentry raise the alarm"
```

---

### Task 2: 完整构建收尾与项目文档

**Files:**
- Modify: `goblin-settlement-plan/PROFESSION_DESIGN.md`（§12 勾掉报警那条）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1 的改动与构建结果
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：**先单独提交它们**并署名，**然后再**做本任务的文档改动。

- [ ] **Step 2: 更新 PROFESSION_DESIGN §12**

把「哨卫的**报警**工作。**仍未做**，与引导避难、有限自卫一并留下一轮。」改成已完成并指向 `SENTRY_ALERT_DESIGN.md`。同时写明进度：GAME_DESIGN 第 30 行给哨卫四项职责（巡逻、报警、引导避难、有限自卫），**至此完成两项**，避难与自卫仍未做——不要让"报警已完成"被读成"哨卫已全部到位"。

- [ ] **Step 3: 追加 UpdateLog**

在末尾追加（时间换成实际操作时刻；**逐条写真实时间**）：

```markdown
## [<开始> – <结束>] 第五十轮：哨卫报警

- [<时间>] 按 SENTRY_ALERT_DESIGN.md 与 SENTRY_ALERT_PLAN.md 执行，落实 PROFESSION_DESIGN §12 的「哨卫的报警工作」。这是 GAME_DESIGN 第 30 行四项职责里的第二项（巡逻已完成）。
- [<时间>] 增量在哪：既有链路只在**居民已经挨打**时才惊动傀儡（`RelationshipCoordinator` → `DefenseCoordinator.onResidentAttack`，其注释写着"Call only from an event confirming this attacker actually attacked this resident"）。哨卫的报警是**看见就报**——威胁还在村外时防卫就开始。这是巡逻这项工作的价值兑现。
- [<时间>] 新增 `GoblinGolemEntity.alertToSighting(LivingEntity)`，与既有 `alertToResidentAttack` **同一组守卫**、只去掉需要受害者的三条（受害者存活、是本聚落居民、与傀儡的距离）；`isPermittedAttacker` 由 private 改包内可见以便复用。新增 `DefenseCoordinator.reportSighting(level, sentry)`：找**最近的**可见 `Monster`（取最近是为了让"报谁"有唯一确定答案，`getEntitiesOfClass` 的顺序不作保证），对哨卫周围 DEFENSE_RADIUS 内的傀儡逐个上报。实体在 `tickPatrolWork` 最前调用（放在最前是因为到达航点会提前 return，而站在路口守望恰恰最该看四周），并把结果写进 `waitReason`，让 `/goblinsettlement work` 看得见。
- [<时间>] **边界一：只有 Monster 算威胁，玩家不算。** GAME_DESIGN 第 132 行点名「玩家携带武器、路过仓库或住在旁边不会自动成为敌人」；玩家只在确实攻击时经既有事件路径进入防线，那条路径本轮一行未改。
- [<时间>] **边界二：目击半径 12，刻意是傀儡防卫半径 24 的一半。** 哨卫只报看得见的，**是否出动仍由傀儡自己按 24 格、归属与存活规则决定**。这压低（不消除）"路过一只僵尸就全村出动"的概率；两者的警戒都只维持 ALERT_TICKS（15 秒）后自动解除。
- [<时间>] **边界三：不惊动原版铁傀儡。** `VanillaIronGolemBridge.alert` 的签名需要一个受害者，目击场景没有。原版铁傀儡被攻击时本来就会自己还手。这是已知边界，不是遗漏。
- [<时间>] 验证：完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**13 项**独立检查全部 *Check passed（既有检查覆盖不到本次改动）。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>。另核对 `alertToResidentAttack` 的方法体一行未动、`isPermittedAttacker` 只改了可见性、`RelationshipCoordinator` 不在改动之列。
- [<时间>] **本轮没有新增独立检查**：涉及的全是 ServerLevel、实体查询与 `instanceof Monster`，纯函数层无从下手（与第四十七轮同类）。所以"哨卫真的会报警、且不会因玩家路过而报警"**只有代码审查与游戏内观察**，不得记成已验证。
- [<时间>] 未完成：不构成玩法验收；引导避难与有限自卫未做（四项职责完成两项）；误报未消除；报警覆盖面随"一次一名巡逻"只有一条线，聚落变大也不会增长；`alertToSighting` 与 `alertToResidentAttack` 是成对的守卫副本，改一处要看另一处（已在源码注释里写明）。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「职业系统剩余」：把「哨卫的报警」记为已完成并写明四项职责完成两项（巡逻、报警），避难与自卫仍未做。
- 「本轮接入的内容」新增一节「第五十轮：哨卫报警」。
- 「本轮验证进展」替换为本轮构建结果，并**显式写明本轮无新增检查**。
- 「阶段定位」阶段 4：把哨卫那句补上"报警"。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the sentry alert round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 增量在哪 → Task 2 Step 3 的日志条目。
- 设计 §3.1 只有 Monster 算威胁 → Task 1 Step 2 的过滤条件与 Global Constraints。
- 设计 §3.2 半径 12 < 24 → Task 1 Step 2 的常量与注释。
- 设计 §3.3 只报最近 → Task 1 Step 2 的 `min(comparingDouble(...))`。
- 设计 §4 复用而非新建 → Task 1 Step 1（含"与谁成对"的注释）。
- 设计 §5 实体接线 → Task 1 Step 3。
- 设计 §6 无新检查 → Global Constraints + Task 2 Step 3/4。
- 设计 §7 风险 → Task 2 Step 3 的"边界一/二/三"与"未完成"条。

**2. 占位符扫描**

- 无 TBD/TODO/待填。三个改动点的代码都给了可粘贴全文。
- Task 1 Step 2 明说 import"按编译提示补，不要预先猜"——这是**条件性动作**，给了判据（哪些类用到）而非留空。
- Task 2 Step 3 的 `<开始>`/`<结束>`/`<时间>`/`<字节数>` 是模板占位，正文已要求逐条写真实时间（第四十八轮漏填过一次）。

**3. 类型一致性**

- `alertToSighting(LivingEntity) -> boolean` 与 `alertToResidentAttack(GoblinCitizenEntity, Entity) -> boolean` 的返回类型一致，因此 `alerted |= golem.alertToSighting(threat);` 可直接用。
- `reportSighting` 返回 `boolean`（是否惊动了至少一只），实体侧用 `boolean alerting = ...` 接收，与三处 `waitReason` 的三元表达式匹配。
- `isPermittedAttacker(LivingEntity)` 参数类型不变，只改可见性；`alertToSighting` 的 `threat` 是 `LivingEntity`，可传入。
- `Comparator.comparingDouble(entity -> entity.distanceToSqr(sentry))` 里 `entity` 由 `getEntitiesOfClass(LivingEntity.class, ...)` 推出为 `LivingEntity`，`distanceToSqr(Entity)` 存在。
- `DefenseCoordinator` 与 `GoblinGolemEntity` 同在 `defense` 包，因此包内可见的 `isPermittedAttacker` 不需要 import；`GoblinGolemEntity` 在本文件里也不需要 import。
- 实体侧新增 `DefenseCoordinator` 的 import：字母序上 `DefenseCoordinator` 在 `PatrolCoordinator` 之前，若该文件已有 `defense` 分组的 import，插在它前面。
