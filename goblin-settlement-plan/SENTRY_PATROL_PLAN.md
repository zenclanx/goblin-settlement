# 哨卫巡逻 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给哨卫一条真正的巡逻线——一次一名居民沿聚落设施走动，并让 PROFESSION_DESIGN §2.3/§4.2 那条"哨卫无工作、按通才对待"的临时规则自动到期。

**Architecture:** 纯层 `defense/PatrolRules.waypoints` 把"锚点 + 设施点"排成确定性航点序列；`defense/PatrolCoordinator` 用第四十七轮的 `WorkerDispatch` 单在途派工，并每 tick 按序号给出当前航点；实体新增 `WorkStage.PATROL_WALKING`，到了就换下一个。`WorkKind.PATROL(Profession.SENTRY)` 一加，哨卫在 `matchRank` 上回到对口档——这是设计预期的连带，不是副作用。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [SENTRY_PATROL_DESIGN.md](SENTRY_PATROL_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **纯层不得依赖 Minecraft 类型**：`PatrolRules` 只吃 `int[]`、`List`、`java.util`。这是它能被独立检查覆盖的前提。
- **一次只有一名居民巡逻**（单在途门禁，照采矿）。**不做门控**：任何可用居民都能被选中，哨卫只因 `matchRank` 优先而在有空的哨卫时胜出。
- **不给哨卫发明额外规则**：不设"必须哨卫"、不设分段、不做轮换、不做巡逻强度调节。
- **巡逻不碰背包**：没有取料/携带/交付/归还四段，**不要**照搬 `HOUSING_*` 那套。
- **`patrolIndex` 是瞬态字段，不入存档**：与采矿"不保留持久预约、每 tick 从世界重推"一致。
- 不做游戏内验证（按用户约定）。
- `goblin-settlement-plan/` 下**可能有并行 agent 的未提交改动**：**在文档任务开始时**先跑 `git status`，发现不是自己改的就**先单独提交它们并署名**，再追加自己的。`UpdateLog.md` **只许在末尾追加**。

---

### Task 1: 纯层航点序列、`WorkKind.PATROL` 与断言翻转

本任务**不动任何既有行为**，只新增纯层与一个枚举常量、并翻转一条已到期的断言。

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/PatrolRules.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/defense/PatrolRulesCheck.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/WorkKind.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/ProfessionRulesCheck.java`
- Modify: `goblin-settlement-mod/build.gradle`（注册 `patrolRulesCheck` 并加进 `check` 聚合）

**Interfaces:**
- Consumes: 无
- Produces:
  - `PatrolRules.waypoints(List<int[]> facilities, int[] anchor) -> List<int[]>`
  - `WorkKind.PATROL`（`required()` 为 `Profession.SENTRY`）

- [ ] **Step 1: 写失败检查（RED）**

创建 `PatrolRulesCheck.java`：

```java
package dev.local.goblinsettlement.defense;

import java.util.Arrays;
import java.util.List;

public final class PatrolRulesCheck {
    public static void main(String[] args) {
        checkAnchorComesFirst();
        checkNearestFirstWithStableTies();
        checkDuplicatesAreFolded();
        checkEmptyFacilitiesLeavesTheAnchor();
        System.out.println("PatrolRulesCheck passed");
    }

    private static void checkAnchorComesFirst() {
        var route = PatrolRules.waypoints(List.of(point(10, 64, 10)), point(0, 64, 0));
        require(route.size() == 2, "the anchor and the one facility");
        require(Arrays.equals(route.get(0), point(0, 64, 0)), "the anchor is walked first");
    }

    private static void checkNearestFirstWithStableTies() {
        var route = PatrolRules.waypoints(
                List.of(point(5, 64, 0), point(-3, 64, 0), point(1, 64, 0), point(-1, 64, 0)),
                point(0, 64, 0));
        // Distances squared from the anchor: 1, 1, 9, 25. The equal pair breaks by x, so -1 precedes 1.
        require(Arrays.equals(route.get(1), point(-1, 64, 0)), "the nearest, left of the anchor, is second");
        require(Arrays.equals(route.get(2), point(1, 64, 0)), "an equal distance breaks by x");
        require(Arrays.equals(route.get(3), point(-3, 64, 0)), "then the next nearest");
        require(Arrays.equals(route.get(4), point(5, 64, 0)), "then the farthest");
    }

    private static void checkDuplicatesAreFolded() {
        var route = PatrolRules.waypoints(
                List.of(point(0, 64, 0), point(3, 64, 0), point(0, 64, 0)), point(0, 64, 0));
        require(route.size() == 2, "a facility on the anchor is not walked twice");
    }

    private static void checkEmptyFacilitiesLeavesTheAnchor() {
        var route = PatrolRules.waypoints(List.of(), point(4, 70, -2));
        require(route.size() == 1 && Arrays.equals(route.get(0), point(4, 70, -2)),
                "with no facilities the route is just the anchor");
    }

    private static int[] point(int x, int y, int z) {
        return new int[] {x, y, z};
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew compileTestJava --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错为 `找不到符号: 类 PatrolRules`。失败原因必须是"类型不存在"。

- [ ] **Step 3: 实现纯规则（GREEN）**

创建 `PatrolRules.java`：

```java
package dev.local.goblinsettlement.defense;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure patrol route rules: which points a sentry walks, and in what order. */
public final class PatrolRules {
    private PatrolRules() {
    }

    /**
     * The anchor first, then the facilities nearest-first. Ties break by x then z so the order is stable
     * across saves, and identical points are folded into one -- a warehouse inside a farm would
     * otherwise be visited twice in a row.
     */
    public static List<int[]> waypoints(List<int[]> facilities, int[] anchor) {
        Set<String> seen = new LinkedHashSet<>();
        List<int[]> route = new ArrayList<>();
        List<int[]> candidates = new ArrayList<>();
        candidates.add(anchor);
        candidates.addAll(facilities);
        for (int[] point : candidates) {
            if (seen.add(point[0] + ":" + point[1] + ":" + point[2])) {
                route.add(point);
            }
        }
        route.sort((first, second) -> {
            int byDistance = Integer.compare(distanceSquared(first, anchor),
                    distanceSquared(second, anchor));
            if (byDistance != 0) {
                return byDistance;
            }
            int byX = Integer.compare(first[0], second[0]);
            return byX != 0 ? byX : Integer.compare(first[2], second[2]);
        });
        return List.copyOf(route);
    }

    private static int distanceSquared(int[] point, int[] anchor) {
        int dx = point[0] - anchor[0];
        int dy = point[1] - anchor[1];
        int dz = point[2] - anchor[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
```

- [ ] **Step 4: 注册检查任务**

在 `build.gradle` 的 `blueprintCheck` 任务块之后加：

```groovy
tasks.register('patrolRulesCheck', JavaExec) {
    group = 'verification'
    description = 'Checks the pure patrol route rules.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.defense.PatrolRulesCheck'
}
```

并在末尾的 `tasks.named('check')` 块里加一行：

```groovy
    dependsOn tasks.named('patrolRulesCheck')
```

- [ ] **Step 5: 运行新检查，确认通过**

Run: `./gradlew patrolRulesCheck --offline --no-daemon`

Expected: `PatrolRulesCheck passed`。

- [ ] **Step 6: 加 `WorkKind.PATROL` 并翻转已到期的断言**

6a. 在 `WorkKind.java` 的枚举里，`SMELTING(Profession.ARTISAN);` 之前插入一行（注意把分号移到新行）：

```java
    SMELTING(Profession.ARTISAN),
    PATROL(Profession.SENTRY);
```

6b. 在 `ProfessionRulesCheck.java` 里把

```java
        check(WorkKind.employs(Profession.SENTRY) == false, "sentry has no work kind yet");
```

改成

```java
        check(WorkKind.employs(Profession.SENTRY), "the sentry now has patrol work");
```

**这是临时规则到期的信号，不是随便改断言**：PROFESSION_DESIGN §2.3 原本写"等将来实现巡逻与警报工作时，该规则自动失效，哨卫回到对口档"——本轮实现巡逻，该断言必须翻转，否则它会挡住 §2.3 的预期行为。

- [ ] **Step 7: 跑完整构建与全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**13 项**检查全部 `*Check passed`（新增 PatrolRules）。

- [ ] **Step 8: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/PatrolRules.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/defense/PatrolRulesCheck.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/WorkKind.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/ProfessionRulesCheck.java \
        goblin-settlement-mod/build.gradle
git commit -m "Add the patrol route rules and the sentry work kind"
```

---

### Task 2: 协调器与实体阶段

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/PatrolCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`（5 处）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`（注册）

**Interfaces:**
- Consumes: Task 1 的 `PatrolRules.waypoints`、`WorkKind.PATROL`；`WorkerDispatch.nearest`、`ResidentWorkLookup.anyLoaded`、`SettlementSavedData.warehouses()/farmSites()/settlement()`
- Produces:
  - `PatrolCoordinator.tick(ServerLevel)`
  - `PatrolCoordinator.waypointFor(ServerLevel level, String settlementId, int index) -> Optional<BlockPos>`
  - `GoblinCitizenEntity.assignPatrol(String settlementId) -> boolean`
  - `GoblinCitizenEntity.hasPatrolWork(String settlementId) -> boolean`

- [ ] **Step 1: 创建协调器**

```java
package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ResidentWorkLookup;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.colony.WorkKind;
import dev.local.goblinsettlement.colony.WorkerDispatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Sends one resident at a time around the settlement's facilities. */
public final class PatrolCoordinator {
    private static final int INTERVAL_TICKS = 40;

    private PatrolCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL_TICKS != 0) {
            return;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return;
        }
        String settlementId = settlement.get().id();
        // One patrol at a time: the route is the same for everyone, so a second patroller would only
        // trail the first. The resident clears its own work when it dies, unloads or is cancelled.
        if (ResidentWorkLookup.anyLoaded(level, data, goblin -> goblin.hasPatrolWork(settlementId))) {
            return;
        }
        // Every resident may patrol; a sentry wins on profession fit rather than by being required.
        WorkerDispatch.nearest(level, WorkKind.PATROL, settlement.get().anchor(),
                        GoblinCitizenEntity::isAvailableForConstruction)
                .ifPresent(goblin -> goblin.assignPatrol(settlementId));
    }

    /** The waypoint at this index, wrapping. Empty when the settlement is gone or has changed hands. */
    public static Optional<BlockPos> waypointFor(ServerLevel level, String settlementId, int index) {
        SettlementSavedData data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || !settlement.get().id().equals(settlementId)) {
            return Optional.empty();
        }
        List<int[]> facilities = new ArrayList<>();
        for (BlockPos warehouse : data.warehouses()) {
            facilities.add(new int[] {warehouse.getX(), warehouse.getY(), warehouse.getZ()});
        }
        for (var farm : data.farmSites()) {
            BlockPos crop = farm.cropPos();
            facilities.add(new int[] {crop.getX(), crop.getY(), crop.getZ()});
        }
        BlockPos anchor = settlement.get().anchor();
        List<int[]> route = PatrolRules.waypoints(facilities,
                new int[] {anchor.getX(), anchor.getY(), anchor.getZ()});
        int[] point = route.get(Math.floorMod(index, route.size()));
        return Optional.of(new BlockPos(point[0], point[1], point[2]));
    }
}
```

- [ ] **Step 2: 实体加字段、阶段与映射**

2a. 在 `GoblinCitizenEntity` 的 `private String waitReason = "";` 之后加：

```java
    /** Index into the patrol route. Deliberately not persisted: the route is re-derived from the world. */
    private int patrolIndex;
```

2b. `WorkStage` 枚举末尾（`SMELT_FEEDING, SMELT_WAITING, SMELT_COLLECTING }`）改成：

```java
        MINING_DIGGING, SMELT_FEEDING, SMELT_WAITING, SMELT_COLLECTING, PATROL_WALKING }
```

2c. `workKind` 的 switch 里，`case SMELT_FEEDING, SMELT_WAITING, SMELT_COLLECTING -> WorkKind.SMELTING;` 之后加：

```java
            case PATROL_WALKING -> WorkKind.PATROL;
```

（该 switch 是**穷尽表达式、无 `default`**：漏加这一行会编译报错，不会静默降级。PROFESSION_DESIGN §13 那条"忘了登记映射会静默按对口处理"的旧描述已过时，Task 3 会一并更正。）

- [ ] **Step 3: 实体加派工与查询方法**

在 `hasMiningWork` 方法之后加：

```java
    public boolean assignPatrol(String id) {
        if (!isAvailableForConstruction()) {
            return false;
        }
        settlementId = id;
        patrolIndex = 0;
        workStage = WorkStage.PATROL_WALKING;
        waitReason = "";
        return true;
    }

    public boolean hasPatrolWork(String id) {
        return settlementId.equals(id) && workStage == WorkStage.PATROL_WALKING;
    }
```

- [ ] **Step 4: 实体加 tick 分发与巡逻行为**

4a. 在主 tick 的分发链里，SMELT 那一段之后加：

```java
        if (workStage == WorkStage.PATROL_WALKING) {
            tickPatrolWork(level);
            return;
        }
```

4b. 在 `tickMiningWork` 附近加：

```java
    /** Two blocks is close enough to call a waypoint reached; the route only needs the sentry to pass by. */
    private static final double PATROL_ARRIVE_DISTANCE_SQ = 4.0;

    private void tickPatrolWork(ServerLevel level) {
        var target = PatrolCoordinator.waypointFor(level, settlementId, patrolIndex);
        if (target.isEmpty()) {
            waitReason = "no patrol route";
            getNavigation().stop();
            return;
        }
        BlockPos destination = target.get();
        if (distanceToSqr(destination.getCenter()) <= PATROL_ARRIVE_DISTANCE_SQ) {
            patrolIndex++;
            waitReason = "";
            return;
        }
        waitReason = "patrolling";
        getNavigation().moveTo(destination.getX() + 0.5, destination.getY(),
                destination.getZ() + 0.5, 1.0);
    }
```

**注意**：巡逻**没有**取料/携带/交付/归还，所以不要动背包、不要写 `carried`、不要调用 `placeByResident`。

- [ ] **Step 5: 注册协调器**

在 `GoblinSettlement.tickSettlement` 的 `DefenseCoordinator.tick(level);` 之前加：

```java
        PatrolCoordinator.tick(level);
```

并加 import：

```java
import dev.local.goblinsettlement.defense.PatrolCoordinator;
```

（`defense.DefenseCoordinator` 的 import 已在该文件里，照它的位置排。）

- [ ] **Step 6: 编译并跑全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，13 项检查全部 `*Check passed`，无编译警告。

- [ ] **Step 7: 核对接线无遗漏**

Run: `grep -n "PATROL_WALKING\|assignPatrol\|hasPatrolWork\|PatrolCoordinator" src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`

Expected: 实体里能看到枚举常量、`workKind` 的 case、字段、两个方法、分发与 `tickPatrolWork`；`GoblinSettlement` 里能看到注册与 import。**缺 `workKind` 的 case 会编译失败**，所以这条核对主要看方法是否都到位。

- [ ] **Step 8: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/PatrolCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Send a resident around the settlement on patrol"
```

---

### Task 3: 完整构建收尾与项目文档

**Files:**
- Modify: `goblin-settlement-plan/PROFESSION_DESIGN.md`（§2.3、§4.2、§12、§13 四处）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1–2 的改动与构建结果
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：**先单独提交它们**并署名，**然后再**做本任务的文档改动。

- [ ] **Step 2: 更新 PROFESSION_DESIGN 四处**

- **§2.3**「哨卫本轮无工作，按通才对待」：改标题与正文，写明**巡逻已实现**，该临时规则**已到期**；哨卫在巡逻上回到对口档。报警/避难/自卫仍未做，所以"无工作"只对这三项成立——**不要**把它读成"哨卫已经全部到位"。
- **§4.2** 的"无工作职业按通才对待"段落：写明当前**已无任何职业命中**这条规则（哨卫是最后一个），因此它是保留给未来可能新增职业的兜底，而非现状描述。
- **§12**「哨卫的巡逻 / 警报工作」：拆成两条——巡逻**已完成**（指向 `SENTRY_PATROL_DESIGN.md`），警报仍未做。
- **§13** 风险「`WorkStage -> WorkKind` 映射易漏」：**更正为已不成立**——`workKind` 是穷尽 switch 表达式、无 `default`，漏登记会**编译报错**而不是静默降级。（这是本轮实际核对到的，不是推测。）

- [ ] **Step 3: 追加 UpdateLog**

在末尾追加（时间换成实际操作时刻；**逐条写真实时间，不要留 `<时间>` 占位**）：

```markdown
## [<开始> – <结束>] 第四十九轮：哨卫巡逻

- [<时间>] 按 SENTRY_PATROL_DESIGN.md 与 SENTRY_PATROL_PLAN.md 执行，落实 PROFESSION_DESIGN §12 的「哨卫的巡逻工作」。范围只做四项职责里的巡逻——报警、引导避难、有限自卫留下一轮（前两项牵涉威胁判定与平民避难 AI，第三项还要模型能拿武器，而 GoblinModel 不是 HumanoidModel）。
- [<时间>] 口径更正一条：口述设计时说过"约 8% 的防卫税"，那是按"全部哨卫都巡逻"估的。细看既有的单在途范式（采矿）与 §2.1「不做门控，只做速度与偏好」后改为**一次只有一名居民巡逻**，成本固定 1 人，配额不影响巡逻覆盖面。设计文档按后者写。
- [<时间>] Task 1：新增纯层 defense/PatrolRules.waypoints（锚点在前、设施按距锚点由近及远、并列按 x 再按 z、同坐标折叠）+ PatrolRulesCheck（5 条断言：锚点在前、最近优先与并列破序、去重、无设施时退化为锚点）+ 注册 patrolRulesCheck 任务。新增 WorkKind.PATROL(Profession.SENTRY)，并把 ProfessionRulesCheck 里 `employs(SENTRY) == false` 翻转为 `true` —— 这是 §2.3 那条临时规则到期的信号。
- [<时间>] Task 2：新增 defense/PatrolCoordinator（单在途门禁 + WorkerDispatch.nearest 派工 + waypointFor 提供当前航点）；实体加 WorkStage.PATROL_WALKING、workKind 映射、patrolIndex 瞬态字段、assignPatrol/hasPatrolWork、tickPatrolWork、主 tick 分发；GoblinSettlement 注册协调器。巡逻**不碰背包**（无取料/携带/交付/归还）。
- [<时间>] 航点取自存档里已有的设施点（锚点 + 仓库 + 农田作物位置），**不需要新方块**；巡逻点方块（GAME_DESIGN 第 93 行列为待建设施）不在本轮。
- [<时间>] 验证：TDD 先看 `:compileTestJava FAILED`（找不到 PatrolRules），实现后 PatrolRulesCheck passed。完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**13 项**独立检查全部 *Check passed。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>。
- [<时间>] 顺带更正设计文档一处过时风险：PROFESSION_DESIGN §13 写「WorkStage -> WorkKind 映射易漏，新增工作阶段时忘了登记会静默按对口处理」——实际 `workKind` 是穷尽 switch 表达式、无 default，漏登记会**编译报错**。已更正。
- [<时间>] 未完成：不构成玩法验收；**实体巡逻行为不可纯测**（真的走过去、到了换点），只有编译与代码审查；导航到不了时本轮的处理是"每 tick 重新下发目标、一直重试"，可能表现为哨卫在某个设施旁反复晃动（航点在仓库/农田中心，落脚点可能被占），统一测试时要注意；报警、引导避难、有限自卫未做；巡逻永不结束、不轮换；聚落再大也只有一条巡逻线；其余职业的名额上限仍未定。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「职业系统剩余」：把「哨卫的巡逻」记为已完成（写明只做了巡逻、报警/避难/自卫仍未做），保留其余项。
- 「本轮接入的内容」新增一节「第四十九轮：哨卫巡逻」。
- 「本轮验证进展」替换为本轮构建结果（**13 项**检查）。
- 「阶段定位」阶段 1—2 与阶段 4：职业系统那句补上"哨卫已能巡逻"。
- 「已验证的基线」段落里若提到检查项数，一并改为 13。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the sentry patrol round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 `WorkKind.PATROL` 与连带 → Task 1 Step 6（含断言翻转与理由）。
- 设计 §3 航点序列 → Task 1 Step 1/3（纯规则 + 5 条断言）。
- 设计 §4 单在途派工 → Task 2 Step 1。
- 设计 §5 实体侧 → Task 2 Step 2/3/4。
- 设计 §6 验证 → Task 1 Step 1–5、Step 7；无实体行为测试，Task 3 Step 3 的日志条目写明。
- 设计 §7 风险 → Global Constraints（不碰背包、瞬态字段、单在途）+ 日志的"未完成"条（导航晃动、永不结束、只有一条线）+ Task 3 Step 2 的 §13 更正。

**2. 占位符扫描**

- 无 TBD/TODO/待填。纯规则、检查、协调器、实体改动的代码都给了可粘贴全文。
- Task 3 Step 3 的 `<开始>`/`<结束>`/`<时间>` 是**模板占位**，并在正文里明确要求"逐条写真实时间，不要留占位"——第四十八轮漏填过一次，这里显式提醒。
- Task 3 Step 3 的 `<字节数>` 是测量值占位，实现时填。

**3. 类型一致性**

- `PatrolRules.waypoints(List<int[]>, int[]) -> List<int[]>` 在 Task 1 的检查与 Task 2 的调用两处一致；Task 2 内部 `route.get(Math.floorMod(index, route.size()))` 用 `Math.floorMod` 保证负索引也安全，并保证 `index` 超出路线长度时回绕（路线随设施增减而变长变短）。
- `waypointFor` 返回 `Optional<BlockPos>`；实体侧用 `var target` + `target.isEmpty()`/`target.get()`，与 `HousingCoordinator.assignedStep` 的既有写法一致。
- `assignPatrol(String)` / `hasPatrolWork(String)` 的签名与 `assignMining(String, BlockPos, BlockPos)` / `hasMiningWork(String)` 的 **`has*` 部分**对齐（`id` 参数名与 `settlementId.equals(id)` 的比较方式照抄）。
- `PATROL_ARRIVE_DISTANCE_SQ` 是 `private static final double`，与 `distanceToSqr` 的 `double` 返回类型匹配。
- `WorkKind.PATROL` 加在 `SMELTING` 之后、**分号随之移动**（Step 6a 特别注明），否则枚举声明语法错误。
- 实体侧不新增 import：`PatrolCoordinator` 与 `WorkKind` 都通过既有 import 可见（`citizen` 包已在用 `colony.SettlementSavedData` 等），`Map`/`Automation` 无需新增；**若编译提示缺 import，按其提示补**，不要预先添加。
