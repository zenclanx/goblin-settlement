# 哨卫引导避难 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立聚落级警戒状态，警报期间非战斗居民与儿童中断工作、前往最近的已建成房屋躲避；巡逻哨卫守到威胁与村子之间。

**Architecture:** 警戒状态持久化在 `DefenseSavedData`（存**剩余 tick**，不存绝对时刻），由 `DefenseCoordinator` 置位/续期/递减；避难所选择是纯函数 `ShelterRules.nearest`（有独立检查）；居民用**独立的 `sheltering` 状态**让出导航而不动 `workStage`，警报解除即原地复工；哨卫的"守位"落在 `tickPatrolWork` 里，不新增寻敌。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [SENTRY_SHELTER_DESIGN.md](SENTRY_SHELTER_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **警戒存"剩余 tick"，绝不存绝对时刻**：存绝对时刻会在时间倒退（新世界、`/time set`）时让居民永久避难。
- **不改 `workStage`**：避难是独立状态，警报解除后工作原地恢复。
- **哨卫不避难**：职业为 `SENTRY` 的居民跳过避难逻辑。
- **不新增寻敌**：哨卫守的是"最后被报告到的位置"，不是自己搜出来的目标。
- **不做容量与出入判定**：多个居民挤同一间小屋本轮不管。
- 纯层（`ShelterRules`）不得依赖 Minecraft 类型。
- 本轮**有**新增独立检查（只覆盖避难所选择规则）。避难行为与守位仍不可纯测，日志里必须写明这个界。
- 不做游戏内验证（按用户约定）。
- `goblin-settlement-plan/` 下**可能有并行 agent 的未提交改动**：**在文档任务开始时**先跑 `git status`，发现不是自己改的就**先单独提交它们并署名**，再追加自己的。`UpdateLog.md` **只许在末尾追加**。

---

### Task 1: 避难所选择的纯规则与检查

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/ShelterRules.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/defense/ShelterRulesCheck.java`
- Modify: `goblin-settlement-mod/build.gradle`（注册 `shelterRulesCheck` 并加进 `check` 聚合）

**Interfaces:**
- Consumes: 无
- Produces: `ShelterRules.nearest(List<int[]> shelters, int[] from) -> int`（无避难所时 `-1`）

- [ ] **Step 1: 写失败检查（RED）**

创建 `ShelterRulesCheck.java`：

```java
package dev.local.goblinsettlement.defense;

import java.util.List;

public final class ShelterRulesCheck {
    public static void main(String[] args) {
        checkNearestWins();
        checkTiesBreakByXThenZ();
        checkNoShelters();
        checkSingleShelter();
        System.out.println("ShelterRulesCheck passed");
    }

    private static void checkNearestWins() {
        var shelters = List.of(point(20, 64, 0), point(3, 64, 0), point(-9, 64, 0));
        require(ShelterRules.nearest(shelters, point(0, 64, 0)) == 1,
                "the closest refuge wins");
    }

    private static void checkTiesBreakByXThenZ() {
        // Equal distance from the origin; the smaller x must win regardless of list order.
        var byX = List.of(point(4, 64, 0), point(-4, 64, 0));
        require(ShelterRules.nearest(byX, point(0, 64, 0)) == 1, "an equal distance goes to the smaller x");
        var byZ = List.of(point(0, 64, 4), point(0, 64, -4));
        require(ShelterRules.nearest(byZ, point(0, 64, 0)) == 1, "then to the smaller z");
    }

    private static void checkNoShelters() {
        require(ShelterRules.nearest(List.of(), point(0, 64, 0)) == -1,
                "no refuges means no choice");
    }

    private static void checkSingleShelter() {
        var shelters = List.of(point(7, 64, 7));
        require(ShelterRules.nearest(shelters, point(0, 64, 0)) == 0, "one refuge is always the answer");
        require(ShelterRules.nearest(shelters, point(0, 64, 0))
                        == ShelterRules.nearest(shelters, point(0, 64, 0)),
                "the same inputs always give the same answer");
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

Expected: `:compileTestJava FAILED`，报错为 `找不到符号: 变量 ShelterRules`。

- [ ] **Step 3: 实现纯规则（GREEN）**

创建 `ShelterRules.java`：

```java
package dev.local.goblinsettlement.defense;

import java.util.List;

/** Pure shelter selection: which refuge a resident runs to. */
public final class ShelterRules {
    private ShelterRules() {
    }

    /**
     * The nearest shelter's index, or -1 when there are none. Coordinates are plain ints so the pure
     * rules stay free of Minecraft types; callers convert their positions.
     */
    public static int nearest(List<int[]> shelters, int[] from) {
        int best = -1;
        for (int index = 0; index < shelters.size(); index++) {
            if (best < 0 || isCloser(shelters.get(index), shelters.get(best), from)) {
                best = index;
            }
        }
        return best;
    }

    /** Nearer wins; an exact tie goes to the smaller x, then the smaller z, so the choice repeats. */
    private static boolean isCloser(int[] candidate, int[] incumbent, int[] from) {
        int candidateDistance = distanceSquared(candidate, from);
        int incumbentDistance = distanceSquared(incumbent, from);
        if (candidateDistance != incumbentDistance) {
            return candidateDistance < incumbentDistance;
        }
        int byX = Integer.compare(candidate[0], incumbent[0]);
        return byX != 0 ? byX < 0 : candidate[2] < incumbent[2];
    }

    private static int distanceSquared(int[] point, int[] from) {
        int dx = point[0] - from[0];
        int dy = point[1] - from[1];
        int dz = point[2] - from[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
```

- [ ] **Step 4: 注册检查任务**

在 `build.gradle` 的 `patrolRulesCheck` 任务块之后加：

```groovy
tasks.register('shelterRulesCheck', JavaExec) {
    group = 'verification'
    description = 'Checks the pure shelter selection rule.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.defense.ShelterRulesCheck'
}
```

并在 `tasks.named('check')` 块里加：

```groovy
    dependsOn tasks.named('shelterRulesCheck')
```

- [ ] **Step 5: 运行检查并提交**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**14 项**检查全部 `*Check passed`。

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/ShelterRules.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/defense/ShelterRulesCheck.java \
        goblin-settlement-mod/build.gradle
git commit -m "Add the shelter selection rule"
```

---

### Task 2: 警戒状态与避难所查询

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/DefenseSavedData.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/DefenseCoordinator.java`
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/ShelterCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`（每 tick 递减）

**Interfaces:**
- Consumes: Task 1 的 `ShelterRules.nearest`
- Produces:
  - `DefenseSavedData.alertTicks() -> int`、`lastThreat() -> Optional<BlockPos>`、`raiseAlert(BlockPos, int)`、`tickAlert() -> boolean`、`clearAlert()`
  - `DefenseCoordinator.isAlerted(ServerLevel) -> boolean`、`lastThreat(ServerLevel) -> Optional<BlockPos>`、`tickAlert(ServerLevel)`
  - `ShelterCoordinator.nearestShelter(ServerLevel, String settlementId, BlockPos from) -> Optional<BlockPos>`

- [ ] **Step 1: `DefenseSavedData` 加两个字段**

1a. codec 的 `group` 末尾（`golems` 之后）加：

```java
            Codec.INT.optionalFieldOf("alert_ticks", 0).forGetter(DefenseSavedData::alertTicks),
            BlockPos.CODEC.optionalFieldOf("last_threat").forGetter(DefenseSavedData::lastThreat)
```

1b. 字段与构造：

```java
    private final int schemaVersion;
    private List<GolemRecord> golems;
    private int alertTicks;
    private Optional<BlockPos> lastThreat;

    public DefenseSavedData() {
        this(SCHEMA_VERSION, List.of(), 0, Optional.empty());
    }

    private DefenseSavedData(int schemaVersion, List<GolemRecord> golems, int alertTicks,
                             Optional<BlockPos> lastThreat) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported defense data version: " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        this.golems = List.copyOf(golems);
        this.alertTicks = Math.max(0, alertTicks);
        this.lastThreat = lastThreat.map(BlockPos::immutable);
    }
```

**不升 `SCHEMA_VERSION`**：新字段都是 `optionalFieldOf`，旧档读入时取默认值。

1c. 访问器与变更：

```java
    public int alertTicks() {
        return alertTicks;
    }

    public Optional<BlockPos> lastThreat() {
        return lastThreat;
    }

    /** Raises the alert, or refreshes it if one is already running. */
    public void raiseAlert(BlockPos threat, int ticks) {
        alertTicks = Math.max(alertTicks, ticks);
        lastThreat = Optional.of(threat.immutable());
        setDirty();
    }

    /** Counts the alert down by one tick. Returns whether it is still running. */
    public boolean tickAlert() {
        if (alertTicks <= 0) {
            return false;
        }
        alertTicks--;
        setDirty();
        return alertTicks > 0;
    }
```

（`setDirty()` 是 `SavedData` 的方法。`clearAlert` 本轮不需要——归零即解除。）

需要 `import java.util.Optional;` 与 `import net.minecraft.core.BlockPos;`（`BlockPos` 该文件可能已用）。

- [ ] **Step 2: `DefenseCoordinator` 置位、查询与递减**

2a. 加常量与三个方法（放在 `SIGHTING_RADIUS` 附近）：

```java
    /** The settlement alert runs on the same rhythm as a golem's own. */
    private static final int SETTLEMENT_ALERT_TICKS = GoblinGolemEntity.ALERT_TICKS;

    /** True while the settlement is under alert and its non-combatants should be indoors. */
    public static boolean isAlerted(ServerLevel level) {
        return DefenseSavedData.get(level).alertTicks() > 0;
    }

    /** Where the last threat was reported, for a sentry to hold. */
    public static Optional<BlockPos> lastThreat(ServerLevel level) {
        return DefenseSavedData.get(level).lastThreat();
    }

    /** Counts the settlement alert down. Called once per world tick, not from the review loop. */
    public static void tickAlert(ServerLevel level) {
        DefenseSavedData.get(level).tickAlert();
    }
```

2b. `GoblinGolemEntity.ALERT_TICKS` 目前是 `private static final int`，改成**包内可见**（去掉 `private`），让协调器复用同一个节奏，而不是抄一个 300 进去。

2c. 在 `onResidentAttack` 里，紧接空值守卫之后加：

```java
        DefenseSavedData.get(level).raiseAlert(attacker.blockPosition(), SETTLEMENT_ALERT_TICKS);
```

2d. 在 `reportSighting` 里，`alerted` 为真时置位（在 `return alerted;` 之前）：

```java
        if (alerted) {
            DefenseSavedData.get(level).raiseAlert(threat.blockPosition(), SETTLEMENT_ALERT_TICKS);
        }
```

- [ ] **Step 3: `ShelterCoordinator` 查最近的房屋**

```java
package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.housing.HousingSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Finds the refuge a resident should run to. */
public final class ShelterCoordinator {
    private ShelterCoordinator() {
    }

    /** The nearest registered home's anchor bed, or empty when the settlement has no home at all. */
    public static Optional<BlockPos> nearestShelter(ServerLevel level, String settlementId,
                                                    BlockPos from) {
        var homes = HousingSavedData.get(level).homes(settlementId);
        if (homes.isEmpty()) {
            return Optional.empty();
        }
        List<int[]> shelters = new ArrayList<>();
        for (var home : homes) {
            BlockPos bed = home.bed();
            shelters.add(new int[] {bed.getX(), bed.getY(), bed.getZ()});
        }
        int index = ShelterRules.nearest(shelters, new int[] {from.getX(), from.getY(), from.getZ()});
        if (index < 0) {
            return Optional.empty();
        }
        int[] chosen = shelters.get(index);
        return Optional.of(new BlockPos(chosen[0], chosen[1], chosen[2]));
    }
}
```

- [ ] **Step 4: 每世界 tick 递减**

在 `GoblinSettlement.tickSettlement` 的**最前面**（任何协调器之前）加：

```java
        DefenseCoordinator.tickAlert(level);
```

**必须在这里**：`DefenseCoordinator.tick` 自己每 100 tick 才跑一次，挂在它下面会让 15 秒的警戒变成 25 分钟。

- [ ] **Step 5: 编译并跑全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，14 项检查全部 `*Check passed`。

（此时还没有人消费这些接口——居民与哨卫在 Task 3 接上。Task 2 单独可编译、可提交。）

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/DefenseSavedData.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/DefenseCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/ShelterCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/GoblinGolemEntity.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Track a settlement-wide alert"
```

---

### Task 3: 居民避难与哨卫守位

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`（字段、常量、一个方法、主循环守卫、`tickPatrolWork`）

**Interfaces:**
- Consumes: Task 2 的 `DefenseCoordinator.isAlerted` / `lastThreat`、`ShelterCoordinator.nearestShelter`
- Produces: `GoblinCitizenEntity.maintainSheltering(ServerLevel) -> boolean`（private）

- [ ] **Step 1: 加字段与常量**

1a. 在 `private int retaliationTicks;` 之后加：

```java
    private boolean sheltering;
    private boolean shelterReached;
    private BlockPos shelterTarget = BlockPos.ZERO;
```

1b. 在 `RETALIATION_ATTACK_DAMAGE` 之后加：

```java
    /** Four blocks is close enough to call the refuge reached. */
    private static final double SHELTER_ARRIVE_DISTANCE_SQ = 4.0;
```

- [ ] **Step 2: 加避难方法**

在 `maintainRetaliation` 之后加：

```java
    /**
     * Sends a non-combatant indoors while the settlement is alerted, and hands the navigation back
     * when it lifts. workStage is deliberately untouched, so the interrupted job simply resumes.
     * Returns true when this method owns the navigation for this tick.
     */
    private boolean maintainSheltering(ServerLevel level) {
        if (!DefenseCoordinator.isAlerted(level)) {
            if (sheltering) {
                sheltering = false;
                shelterReached = false;
                getNavigation().stop();
            }
            return false;
        }
        // Sentries keep watch; GAME_DESIGN's four duties for them never include hiding.
        if (profession() == Profession.SENTRY) {
            return false;
        }
        if (!sheltering) {
            var shelter = ShelterCoordinator.nearestShelter(level, settlementId, blockPosition());
            // Nowhere to go: keep working rather than milling about in the open.
            if (shelter.isEmpty()) {
                return false;
            }
            shelterTarget = shelter.get();
            sheltering = true;
            shelterReached = false;
        }
        waitReason = "sheltering";
        if (shelterReached) {
            return true;
        }
        if (distanceToSqr(shelterTarget.getCenter()) <= SHELTER_ARRIVE_DISTANCE_SQ) {
            shelterReached = true;
            getNavigation().stop();
            return true;
        }
        getNavigation().moveTo(shelterTarget.getX() + 0.5, shelterTarget.getY(),
                shelterTarget.getZ() + 0.5, 1.0);
        return true;
    }
```

需要 `import dev.local.goblinsettlement.defense.ShelterCoordinator;`（该文件已有 `defense.DefenseCoordinator`，照那行排）。

- [ ] **Step 3: 主循环里让出导航**

把

```java
        maintainRetaliation(level);
        if (getTarget() != null) {
            // The melee goal owns the navigation while a fight lasts; work must not wrestle it for the
            // wheel. Registration and cancellation above still run.
            waitReason = "fighting back";
            return;
        }
```

换成

```java
        maintainRetaliation(level);
        if (getTarget() != null) {
            // The melee goal owns the navigation while a fight lasts; work must not wrestle it for the
            // wheel. Registration and cancellation above still run.
            waitReason = "fighting back";
            return;
        }
        // The two navigation guards below never overlap in practice: a sentry fights but never hides,
        // and everyone else hides but never fights. Keep the order if you ever change that.
        if (maintainSheltering(level)) {
            return;
        }
```

- [ ] **Step 4: 哨卫守位**

把 `tickPatrolWork` 里 `reportSighting` 之后、`waypointFor` 之前插入：

```java
        // While the settlement is alerted, the patrolling sentry stands between the threat and the
        // village instead of walking its route. It guards the last reported position; it never
        // searches for a target of its own.
        if (DefenseCoordinator.isAlerted(level)) {
            var threat = DefenseCoordinator.lastThreat(level);
            if (threat.isPresent()) {
                BlockPos destination = threat.get();
                waitReason = "guarding";
                if (distanceToSqr(destination.getCenter()) <= PATROL_ARRIVE_DISTANCE_SQ) {
                    getNavigation().stop();
                } else {
                    getNavigation().moveTo(destination.getX() + 0.5, destination.getY(),
                            destination.getZ() + 0.5, 1.0);
                }
                return;
            }
        }
```

（`reportSighting` 仍留在最前——它让警戒在哨卫持续看见威胁时不断续期。）

- [ ] **Step 5: 编译并跑全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，14 项检查全部 `*Check passed`，无编译警告。

- [ ] **Step 6: 核对接线**

Run: `grep -n "maintainSheltering\|sheltering\|ShelterCoordinator\|isAlerted\|lastThreat\|\"guarding\"\|\"sheltering\"" src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`

Expected: 字段、常量、方法、主循环守卫、守位分支都在。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java
git commit -m "Send the non-combatants indoors during an alert"
```

---

### Task 4: 完整构建收尾与项目文档

**Files:**
- Modify: `goblin-settlement-plan/PROFESSION_DESIGN.md`（§12 勾掉引导避难；四项职责收尾）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1–3 的改动与构建结果
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：**先单独提交它们**并署名，**然后再**做本任务的文档改动。

- [ ] **Step 2: 更新 PROFESSION_DESIGN**

- §2.3「哨卫：巡逻已实现，其余三项待做」→ 改为**四项职责全部落地**（巡逻、报警、引导避难、有限自卫），但仍要点明**避难不含逐户护送、不含容量判定**。
- §12：把「哨卫的引导避难」标为已完成并指向 `SENTRY_SHELTER_DESIGN.md`；把那条"至此完成三项，只剩引导避难"的进度说明改为**四项完成**。

- [ ] **Step 3: 追加 UpdateLog**

在末尾追加（时间换成实际操作时刻；**逐条写真实时间，不要留占位**）：

```markdown
## [<开始> – <结束>] 第五十二轮：哨卫引导避难

- [<时间>] 按 SENTRY_SHELTER_DESIGN.md 与 SENTRY_SHELTER_PLAN.md 执行，落实 PROFESSION_DESIGN §12 的「引导避难」。GAME_DESIGN 第 30 行给哨卫的四项职责（巡逻、报警、引导避难、有限自卫）**至此全部有了落地**。
- [<时间>] Task 1：新增纯层 defense/ShelterRules.nearest（最近者胜、并列按 x 再按 z、无避难所返回 -1）+ ShelterRulesCheck + shelterRulesCheck 任务。**这是连续三轮无新覆盖（47/50/51）之后的第一个新增检查。**
- [<时间>] Task 2：DefenseSavedData 加 alertTicks（剩余 tick）与 lastThreat（Optional<BlockPos>），都用 optionalFieldOf、**不升 schema 版本**；DefenseCoordinator 加 isAlerted/lastThreat/tickAlert 并在 onResidentAttack 与 reportSighting（真的惊动了傀儡时）置位；新增 ShelterCoordinator 查最近的已登记房屋；GoblinSettlement 在每世界 tick 的最前面递减警戒。
- [<时间>] **警戒存"剩余 tick"而不是"解除时刻"**：存绝对时刻的话，玩家把该维度时间倒退（新世界、/time set）会让判定长期为真、居民永久避难。存余额由协调器每 tick 递减，只与"经过了多少时间"有关。代价是它只在有人加载该维度时才递减——与"区块不活动时任务暂停"的既有约定一致。
- [<时间>] Task 3：居民加独立的 sheltering 状态（**不动 workStage**，所以警报解除后工作原地恢复），在 customServerAiStep 里、工作推进之前让出导航；哨卫不避难；结算不到避难所就连活也不躲（总得有人干活，且无处可去时乱跑更糟）。哨卫的"守位"落在 tickPatrolWork：警戒期间不走航点，改逐朝 lastThreat 移动并守住，**不新增寻敌**。
- [<时间>] **范围说明**：本轮**不做逐户护送**（用户明确否掉，那要跟随/带队 AI）、**不做容量与出入判定**（多个居民挤同一间小屋、甚至被挤出墙外本轮不管）、**不做避难所是否真有墙的判定**（登记房屋在"棚"阶段只有柱子与顶，可能躲进四面透风处）、不做儿童专属行为。这些都写进了设计文档的范围外与风险节。
- [<时间>] 验证：完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**14 项**独立检查全部 *Check passed。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>。
- [<时间>] **新增的检查只覆盖避难所选择规则**：避难行为本身（真的中断工作、真的走过去、解除后真的回去）与哨卫守位仍不可纯测，只有编译与代码审查。**实体侧累积的未验证面又扩大一层**：实体行为在统一测试里的优先级仍然最高。
- [<时间>] 未完成：不构成玩法验收；避难不含护送与容量；避难所可能是无墙的棚；两个导航守卫（自卫、避难）实际不重叠（哨卫不避难、非哨卫不还击），但顺序约定是新的，改的人要知道；"躲进小屋反而被挤出墙外挨打"在理论上可能，统一测试时要观察。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「职业系统剩余」：把「哨卫的引导避难」记为已完成，写明**四项职责全部落地**，并列出本轮明确未做的边界（护送、容量、墙判定）。
- 「本轮接入的内容」新增一节「第五十二轮：哨卫引导避难」。
- 「本轮验证进展」替换为本轮构建结果（**14 项**）；写明新增检查只覆盖避难所选择；并保留"实体行为优先测试"的建议。
- 「阶段定位」阶段 4 与「接续须知」里的检查项数（若写了 13）一并改为 14。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the shelter round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 警戒状态 → Task 2 Step 1/2（含"剩余 tick"的理由）。
- 设计 §3 谁去避难、去哪 → Task 1（纯规则）+ Task 2 Step 3（房屋查询）+ Task 3 Step 2（哨卫跳过）。
- 设计 §4 避难行为 → Task 3 Step 1/2/3。
- 设计 §5 哨卫守位 → Task 3 Step 4。
- 设计 §6 验证 → Task 1（新增检查）+ Task 4 Step 3 的界说明。
- 设计 §7 风险 → Task 4 Step 3 的"范围说明"与"未完成"条。

**2. 占位符扫描**

- 无 TBD/TODO/待填。纯规则、检查、存档字段、协调器、实体改动都给了可粘贴全文。
- Task 2 Step 2b 要求把 `GoblinGolemEntity.ALERT_TICKS` 改包内可见以复用同一节奏——这是**条件性改动**，理由写明（不抄一个 300）。
- Task 4 Step 3 的 `<开始>`/`<时间>`/`<字节数>` 是模板占位；正文已要求逐条写真实时间——**第四十八、五十轮各漏填过一次**，执行时务必替换。

**3. 类型一致性**

- `ShelterRules.nearest(List<int[]>, int[]) -> int` 在 Task 1 的检查与 Task 2 Step 3 的调用两处一致；返回 `-1` 的分支在调用侧被显式处理。
- `DefenseSavedData` 的构造从 2 参变 4 参，**三处调用点**（`this(SCHEMA_VERSION, List.of(), 0, Optional.empty())`、codec 的 `apply(instance, DefenseSavedData::new)`）都要对齐；codec 的 `group` 顺序必须与构造参数顺序一致（schema_version、golems、alert_ticks、last_threat）。
- `alertTicks` 用 `Codec.INT`（不是 LONG）——它现在是余额而不是时刻，见设计 §2 的修正。
- `isAlerted(ServerLevel)` / `lastThreat(ServerLevel)` 在实体侧以 `var` 接收，`lastThreat` 返回 `Optional<BlockPos>`，与实体的 `threat.isEmpty()/get()` 用法一致。
- `maintainSheltering(ServerLevel) -> boolean` 返回"本 tick 是否由我掌管导航"，主循环据此 `return`；与 `maintainRetaliation`（返回 void，靠 `getTarget() != null` 判定）风格不同是**有意的**——避难的"是否接管"不能从实体状态反推。
- `SHELTER_ARRIVE_DISTANCE_SQ` 与 `PATROL_ARRIVE_DISTANCE_SQ` 都是 `4.0`，但语义不同（一个是到达避难所、一个是到达航点），**不合并**——合并会让后人以为改一个该改两个。
