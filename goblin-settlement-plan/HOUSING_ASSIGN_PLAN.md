# 住户分配 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让"谁住哪栋房"成为一份真实、可读、可约束的数据：居民名册记住归属，一栋房的住户数由名册数出来，新居民只进还有空位的房子，生育门从"全聚落有床位"落到"母亲所在房子确实还有空位"。

**Architecture:** `ResidentRecord` 新增 `Optional<BlockPos> home`（指向那栋房的床头坐标）；纯层 `HousingAssignment.plan(...)` 一次算出"谁该住哪"的完整目标状态，只输出变化项；`HousingAssignmentCoordinator` 按节拍把世界读成它的输入、把结果写回存档；`HousingCoordinator` 提供"这栋房能住几人"与"现在住着几人"的唯一读法；`FamilyCoordinator` 的生育门与 `status` 的显示行都问它。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [HOUSING_ASSIGN_DESIGN.md](HOUSING_ASSIGN_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行（25–70 秒，Bash 超时给 300000 ms）。
- **不升 schema 版本**：`ResidentRecord.home` 走 `optionalFieldOf`，旧档读入即"无房"（设计 §2）。
- **一处真相**：房子**不存**住户名单，住户数一律由名册数出来（设计 §2.1）；"这栋房能住几人"只有 `HousingCoordinator.homeCapacity` 一处（设计 §1 的 `builtCapacity` 是它的算式）。
- **不新增需求档、不新增方块**：住不下就是"无房"，由既有的 `HOUSING` 档与扩地链路去追（设计 §5）。
- **绝不把未判定的东西当成已判定**：不可 tick 的房子进 `unjudged`，既不作为分配目标、也不解除其住户（设计 §3.4）。
- **不按距离就近**：分配一律按床位坐标序 + 居民 id 序，纯层不碰世界（设计 §3.3）。
- 检查项数由 **19 增至 20**（新增 `housingAssignmentCheck`）。构建结束时 20 项必须全部 `*Check passed`。
- 不做游戏内验证（按用户约定）。协调器读世界的整条路径、生育门的真实效果、`status` 那两行都**不可纯测**，会写进日志与状态文件。
- `goblin-settlement-plan/` 下可能有并行 agent 的未提交改动：文档任务先跑 `git status`。`UpdateLog.md` **只许在末尾追加**。
- 提交到 `main`，本轮结束推送（若本机代理未运行导致连不上，留在本地并如实报告，不算任务失败）。

---

### Task 1: 居民身上的归属字段

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ResidentRecord.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementSavedData.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/SettlementSavedDataCheck.java`

**Interfaces:**
- Produces: `ResidentRecord.home() -> Optional<BlockPos>`；`ResidentRecord.withHome(Optional<BlockPos>) -> ResidentRecord`；`SettlementSavedData.assignHome(String residentId, Optional<BlockPos> home) -> boolean`

- [ ] **Step 1: 先写检查（RED）**

在 `SettlementSavedDataCheck` 的 `main` 里、`require(data.markResidentDead("resident-adult"), ...)` **之前**加：

```java
        require(data.assignHome("resident-adult", java.util.Optional.of(new BlockPos(12, 64, 12))),
                "a living resident can be given a home");
        require(data.resident("resident-adult").orElseThrow().home()
                        .equals(java.util.Optional.of(new BlockPos(12, 64, 12))),
                "the home is recorded on the roster");
        require(data.assignHome("resident-adult", java.util.Optional.of(new BlockPos(20, 64, 20))),
                "a home can be changed, unlike a trade or a role");
        require(data.assignHome("resident-adult", java.util.Optional.empty()),
                "and it can be cleared");
        require(data.resident("resident-adult").orElseThrow().home().isEmpty(),
                "clearing leaves no home behind");
        require(data.assignHome("resident-adult", java.util.Optional.of(new BlockPos(12, 64, 12))),
                "and set again");
        checkHomeSurvivesEveryRosterEdit();
```

并在该文件末尾（`require` 之前）加一个助手。它**逐条走遍 `ResidentRecord` 的全部 8 个构造点**，每一处都断言 `home` 被带过去了：

```java
    /**
     * Every path a resident can take through the family code rebuilds the record, and each one has to
     * carry the home along: a missed constructor argument would drop it silently, and only at runtime --
     * advanceFamilyTime runs every tick, so the home would vanish within a tick of being assigned.
     */
    private static void checkHomeSurvivesEveryRosterEdit() {
        var home = java.util.Optional.of(new BlockPos(12, 64, 12));
        var idle = new ResidentRecord("r1", ResidentRecord.LifeStage.ADULT,
                java.util.Optional.empty(), java.util.Optional.empty(),
                ResidentRecord.ReproductiveRole.MOTHER, Profession.UNASSIGNED, 0, 0, home);
        require(idle.withPostBirthRest().home().equals(home), "rest keeps the home");
        require(idle.deceased().home().equals(home), "death keeps the home");
        require(idle.withProfession(Profession.FARMER).home().equals(home), "taking a trade keeps the home");

        var roleless = new ResidentRecord("r2", ResidentRecord.LifeStage.ADULT,
                java.util.Optional.empty(), java.util.Optional.empty(),
                ResidentRecord.ReproductiveRole.UNSPECIFIED, Profession.UNASSIGNED, 0, 0, home);
        require(roleless.withReproductiveRole(ResidentRecord.ReproductiveRole.FATHER).home().equals(home),
                "taking a role keeps the home");

        var resting = new ResidentRecord("r3", ResidentRecord.LifeStage.ADULT,
                java.util.Optional.empty(), java.util.Optional.empty(),
                ResidentRecord.ReproductiveRole.UNSPECIFIED, Profession.UNASSIGNED, 0, 100, home);
        require(resting.advanceFamilyTime(20).home().equals(home), "an adult's rest clock keeps the home");

        var growing = new ResidentRecord("c1", ResidentRecord.LifeStage.CHILD,
                java.util.Optional.of("m"), java.util.Optional.of("f"),
                ResidentRecord.ReproductiveRole.UNSPECIFIED, Profession.UNASSIGNED, 0, 0, home);
        require(growing.advanceFamilyTime(20).home().equals(home), "growing up keeps the home");

        require(ResidentRecord.adult("a").home().isEmpty(), "a registered adult starts with no home");
        require(ResidentRecord.child("c2", "m", "f").home().isEmpty(),
                "a newborn starts with no home until one is assigned");
    }
```

（两条守卫决定了为什么要造三条记录：`withProfession` 只接受 `ADULT` 且 `profession == UNASSIGNED`；`withReproductiveRole` 只接受 `reproductiveRole == UNSPECIFIED`。`advanceFamilyTime` 的两条分支也各要一条——`idle` 的 `restTicks` 是 0，走的是 `return this`，构造点要由 `resting` 与 `growing` 覆盖。）

- [ ] **Step 2: 跑检查，确认按预期失败**

Run: `./gradlew settlementSavedDataCheck --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 方法 assignHome(String,BlockPos)`；`ResidentRecord` 的构造参数个数也对不上。

- [ ] **Step 3: 加字段与取值变换**

`ResidentRecord` 的 record 头加最后一个分量（并 import `net.minecraft.core.BlockPos`）：

```java
public record ResidentRecord(String id, LifeStage stage, Optional<String> motherId, Optional<String> fatherId,
                             ReproductiveRole reproductiveRole, Profession profession,
                             long childActiveTicks, long restTicks, Optional<BlockPos> home) {
```

紧凑构造器的空值校验（`:61-62`）加上 `home`：

```java
        if (id == null || id.isBlank() || stage == null || motherId == null || fatherId == null
                || reproductiveRole == null || profession == null || home == null) {
```

codec 的 `instance.group(...)` 末尾（`rest_ticks` 那行之后）加：

```java
            BlockPos.CODEC.optionalFieldOf("home").forGetter(ResidentRecord::home)
```

加取值变换（放在 `withProfession` **之后**）：

```java
    /**
     * Where this resident lives, or nothing. Unlike a trade and a role this is not a one-off: a resident
     * is unbound when its home is gone or overfull and bound again later, so there is no guard here.
     */
    public ResidentRecord withHome(Optional<BlockPos> value) {
        if (value == null) {
            throw new IllegalArgumentException("A home reference cannot be null");
        }
        return new ResidentRecord(id, stage, motherId, fatherId, reproductiveRole, profession,
                childActiveTicks, restTicks, value);
    }
```

**然后把剩下 7 处构造点全部补上 `home`**（一处都不能漏，漏了就是把归属静默清空）：`adult`（`:78-79`，补 `Optional.empty()`）、`child`（`:83-84`，补 `Optional.empty()`）、`withReproductiveRole`（`:101`，补 `home`）、`withProfession`（`:109-110`，补 `home`）、`advanceFamilyTime`（`:119-120` 与 `:123-124` 两处，补 `home`）、`withPostBirthRest`（`:130-131`，补 `home`）、`deceased`（`:135-136`，补 `home`）。

- [ ] **Step 4: 加写入方法**

在 `SettlementSavedData` 的 `assignProfession` **之后**加：

```java
    /**
     * Records where a resident lives, or clears it. Reassignable by design -- unlike a trade or a role,
     * a home is unbound and bound again as houses appear, fill up and are emptied.
     */
    public boolean assignHome(String residentId, Optional<BlockPos> home) {
        if (home == null) {
            return false;
        }
        for (int index = 0; index < residents.size(); index++) {
            ResidentRecord current = residents.get(index);
            if (current.id().equals(residentId)
                    && current.stage() != ResidentRecord.LifeStage.DECEASED
                    && !current.home().equals(home)) {
                var updated = new ArrayList<>(residents);
                updated.set(index, current.withHome(home));
                residents = List.copyOf(updated);
                setDirty();
                return true;
            }
        }
        return false;
    }
```

（`BlockPos` 与 `Optional` 在本文件已 import；`ArrayList` 若未 import 就补。）

- [ ] **Step 5: 跑检查并跑完整构建**

Run: `./gradlew settlementSavedDataCheck --offline --no-daemon`

Expected: `SettlementSavedDataCheck passed`。

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，19 项检查全部 `*Check passed`（本任务**不新增**检查任务）。

- [ ] **Step 6: 核对没有漏串的构造点**

Run: `grep -n "new ResidentRecord" src/main/java/dev/local/goblinsettlement/colony/ResidentRecord.java`

Expected: **9 处**——本任务前原有的 **8 处**（`adult` / `child` 两个工厂，以及 `withReproductiveRole` / `withProfession` / `advanceFamilyTime` 两分支 / `withPostBirthRest` / `deceased`），加上本任务新写的 `withHome` 自身那一处。逐一核对：两个工厂的末参是 `Optional.empty()`，其余七处的末参是 `home`（`withHome` 是 `value`）。**数一数，不要靠看**——`advanceFamilyTime` 每 tick 都跑，漏一处就是把归属静默清空。

再 Run: `grep -rn "new ResidentRecord" src/main/java/`

Expected: **只有 `ResidentRecord.java` 里那 9 处**——`SettlementSavedData` 造居民走 `adult` / `child` 工厂，本轮不需要改它那两行。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ResidentRecord.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementSavedData.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/SettlementSavedDataCheck.java
git commit -m "Let the roster remember where each resident lives"
```

---

### Task 2: 纯分配规则与第 20 项检查

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingAssignment.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/HousingAssignmentCheck.java`
- Modify: `goblin-settlement-mod/build.gradle`

**Interfaces:**
- Produces: `HousingAssignment.BedKey(int x, int y, int z)`、`HousingAssignment.HomeSlot(BedKey bed, int capacity)`、`HousingAssignment.ResidentSlot(String id, Optional<BedKey> home)`、`HousingAssignment.Change(String residentId, Optional<BedKey> home)`、`HousingAssignment.occupancy(List<ResidentSlot>, BedKey) -> int`、`HousingAssignment.plan(List<HomeSlot>, Set<BedKey>, List<ResidentSlot>) -> List<Change>`
- Consumes: 无（纯层，不依赖任何 Minecraft 类型）

- [ ] **Step 1: 先写检查（RED）**

创建 `src/test/java/dev/local/goblinsettlement/housing/HousingAssignmentCheck.java`：

```java
package dev.local.goblinsettlement.housing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Standalone assertions for the pure home assignment rule. */
public final class HousingAssignmentCheck {

    public static void main(String[] args) {
        checkHomelessFillHomesInBedOrder();
        checkFullHomesLeaveTheHomelessWhereTheyAre();
        checkAVanishedHomeIsDropped();
        checkAnOverfullHomeKeepsItsLowestIds();
        checkInputOrderDoesNotMatter();
        checkUnjudgedHomesAreLeftAlone();
        checkNothingToDoProducesNothing();
        checkApplyingThePlanIsIdempotent();
        checkOccupancyCountsTheResidentsPointingHere();
        System.out.println("HousingAssignmentCheck passed");
    }

    private static void checkHomelessFillHomesInBedOrder() {
        var homes = List.of(home(20, 0, 1), home(10, 0, 2));
        var roster = List.of(resident("a", null), resident("b", null), resident("c", null));
        var changes = HousingAssignment.plan(homes, Set.of(), roster);
        require(changes.size() == 3, "every homeless resident is placed");
        require(changes.get(0).residentId().equals("a")
                        && changes.get(0).home().equals(Optional.of(bed(10, 0))),
                "the first home by bed order fills first");
        require(changes.get(1).residentId().equals("b")
                        && changes.get(1).home().equals(Optional.of(bed(10, 0))),
                "and takes the second resident too");
        require(changes.get(2).residentId().equals("c")
                        && changes.get(2).home().equals(Optional.of(bed(20, 0))),
                "the next home only starts once the first is full");
    }

    private static void checkFullHomesLeaveTheHomelessWhereTheyAre() {
        var homes = List.of(home(10, 0, 1));
        var roster = List.of(resident("a", bed(10, 0)), resident("b", null));
        require(HousingAssignment.plan(homes, Set.of(), roster).isEmpty(),
                "a homeless resident stays homeless when every home is full");
    }

    private static void checkAVanishedHomeIsDropped() {
        // The vanished resident is treated as homeless, so it is either moved straight into a home with
        // room (one edit, not an unbind followed by an assign) or, when nothing has room, unbound.
        var homes = List.of(home(10, 0, 1));
        var roster = List.of(resident("a", bed(99, 0)), resident("b", bed(10, 0)));
        var changes = HousingAssignment.plan(homes, Set.of(), roster);
        require(changes.size() == 1 && changes.get(0).residentId().equals("a")
                        && changes.get(0).home().isEmpty(),
                "a home that no longer exists unbinds its resident when nothing has room");

        var roomy = HousingAssignment.plan(List.of(home(10, 0, 2)), Set.of(),
                List.of(resident("a", bed(99, 0))));
        require(roomy.size() == 1 && roomy.get(0).residentId().equals("a")
                        && roomy.get(0).home().equals(Optional.of(bed(10, 0))),
                "and moves straight into a home that has room");
    }

    private static void checkAnOverfullHomeKeepsItsLowestIds() {
        var homes = List.of(home(10, 0, 1));
        var roster = List.of(resident("b", bed(10, 0)), resident("a", bed(10, 0)),
                resident("c", bed(10, 0)));
        var changes = HousingAssignment.plan(homes, Set.of(), roster);
        require(changes.size() == 2, "two of the three are unbound");
        require(changes.stream().noneMatch(change -> change.residentId().equals("a")),
                "the lowest id keeps the home");
        require(changes.stream().allMatch(change -> change.home().isEmpty()),
                "and the others are unbound, since the home is full");
    }

    private static void checkInputOrderDoesNotMatter() {
        var one = HousingAssignment.plan(List.of(home(20, 0, 1), home(10, 0, 2)), Set.of(),
                List.of(resident("c", null), resident("a", null), resident("b", null)));
        var other = HousingAssignment.plan(List.of(home(10, 0, 2), home(20, 0, 1)), Set.of(),
                List.of(resident("a", null), resident("b", null), resident("c", null)));
        require(one.equals(other), "the order the inputs arrive in does not change the outcome");
    }

    private static void checkUnjudgedHomesAreLeftAlone() {
        var homes = List.of(home(10, 0, 1));
        var unknown = bed(30, 0);
        var roster = List.of(resident("a", unknown), resident("b", null));
        var changes = HousingAssignment.plan(homes, Set.of(unknown), roster);
        require(changes.stream().noneMatch(change -> change.residentId().equals("a")),
                "a resident of a home we cannot judge is left alone");
        require(changes.size() == 1 && changes.get(0).residentId().equals("b")
                        && changes.get(0).home().equals(Optional.of(bed(10, 0))),
                "and that home is not offered to anyone else");
    }

    private static void checkNothingToDoProducesNothing() {
        require(HousingAssignment.plan(List.of(home(10, 0, 1)), Set.of(),
                        List.of(resident("a", bed(10, 0)))).isEmpty(),
                "an assignment that already holds produces no edits");
        require(HousingAssignment.plan(List.of(), Set.of(), List.of(resident("a", null))).isEmpty(),
                "with no homes at all nobody can be placed");
    }

    private static void checkApplyingThePlanIsIdempotent() {
        var homes = List.of(home(10, 0, 2), home(20, 0, 1));
        var roster = List.of(resident("a", null), resident("b", bed(20, 0)), resident("c", null));
        var first = HousingAssignment.plan(homes, Set.of(), roster);
        var after = new ArrayList<HousingAssignment.ResidentSlot>();
        for (var resident : roster) {
            Optional<HousingAssignment.BedKey> target = resident.home();
            for (var change : first) {
                if (change.residentId().equals(resident.id())) {
                    target = change.home();
                }
            }
            after.add(new HousingAssignment.ResidentSlot(resident.id(), target));
        }
        require(HousingAssignment.plan(homes, Set.of(), after).isEmpty(),
                "applying the plan leaves nothing left to do");
    }

    private static void checkOccupancyCountsTheResidentsPointingHere() {
        var roster = List.of(resident("a", bed(10, 0)), resident("b", bed(10, 0)),
                resident("c", bed(20, 0)));
        require(HousingAssignment.occupancy(roster, bed(10, 0)) == 2,
                "occupancy counts the residents pointing at this bed");
        require(HousingAssignment.occupancy(roster, bed(20, 0)) == 1, "and only those");
        require(HousingAssignment.occupancy(roster, bed(99, 0)) == 0, "an unoccupied bed counts nobody");
    }

    private static HousingAssignment.BedKey bed(int x, int z) {
        return new HousingAssignment.BedKey(x, 64, z);
    }

    private static HousingAssignment.HomeSlot home(int x, int z, int capacity) {
        return new HousingAssignment.HomeSlot(bed(x, z), capacity);
    }

    private static HousingAssignment.ResidentSlot resident(String id, HousingAssignment.BedKey at) {
        return new HousingAssignment.ResidentSlot(id, Optional.ofNullable(at));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("HousingAssignmentCheck failed: " + message);
        }
    }
}
```

- [ ] **Step 2: 跑检查，确认按预期失败**

Run: `./gradlew housingAssignmentCheck --offline --no-daemon`

Expected: `Task 'housingAssignmentCheck' not found`（build.gradle 里还没注册）。**先做 Step 3 注册，再回到这里看编译失败**（`找不到符号: 类 HousingAssignment`）。

- [ ] **Step 3: 注册检查任务**

在 `build.gradle` 的 `bridgeMaterialsCheck` 注册块 **之后**加：

```groovy
tasks.register('housingAssignmentCheck', JavaExec) {
    group = 'verification'
    description = 'Checks who lives in which home, and that the rule is deterministic.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.housing.HousingAssignmentCheck'
}
```

并在 `tasks.named('check')` 的块末尾（`dependsOn tasks.named('bridgeMaterialsCheck')` 之后）加：

```groovy
    dependsOn tasks.named('housingAssignmentCheck')
```

- [ ] **Step 4: 写纯规则**

创建 `src/main/java/dev/local/goblinsettlement/housing/HousingAssignment.java`：

```java
package dev.local.goblinsettlement.housing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who lives where. Pure: it reads coordinates and ids, decides, and never touches the world.
 *
 * A home keeps no list of its residents -- the roster is the one place a home is written down -- so a
 * home's occupancy is counted from the residents that point at it. Everything here derives from that.
 */
public final class HousingAssignment {

    /** A home is identified by its bed: every resident of it points at this coordinate. */
    public record BedKey(int x, int y, int z) { }

    /** A home whose capacity could be read. */
    public record HomeSlot(BedKey bed, int capacity) { }

    /** A living resident, and where it lives now if anywhere. */
    public record ResidentSlot(String id, Optional<BedKey> home) { }

    /** One edit to apply: this resident's new home, or empty to unbind it. */
    public record Change(String residentId, Optional<BedKey> home) { }

    /** Homes are walked in bed order: x, then z, then y -- the order the home scan itself uses. */
    private static final Comparator<BedKey> BED_ORDER = Comparator.comparingInt(BedKey::x)
            .thenComparingInt(BedKey::z).thenComparingInt(BedKey::y);

    private HousingAssignment() {
    }

    /** How many of these residents call this bed their home. */
    public static int occupancy(List<ResidentSlot> residents, BedKey bed) {
        int count = 0;
        for (ResidentSlot resident : residents) {
            if (resident.home().filter(bed::equals).isPresent()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Where everyone should live, as the edits that get them there. Empty when nothing needs to change.
     *
     * A home we cannot judge (its chunk is not ticking, so its capacity is unknowable) takes no part in
     * the assignment, and the residents living in it are left exactly as they are: reading an unloaded
     * home as full would evict people from a house that is perfectly fine.
     */
    public static List<Change> plan(List<HomeSlot> homes, Set<BedKey> unjudged,
                                    List<ResidentSlot> residents) {
        List<HomeSlot> ordered = new ArrayList<>(homes);
        ordered.sort(Comparator.comparing(HomeSlot::bed, BED_ORDER));
        List<ResidentSlot> roster = new ArrayList<>(residents);
        roster.sort(Comparator.comparing(ResidentSlot::id));

        Map<String, BedKey> wanted = new LinkedHashMap<>();
        // Residents of a home we cannot judge keep it; no edit is emitted for them.
        for (ResidentSlot resident : roster) {
            if (resident.home().filter(unjudged::contains).isPresent()) {
                wanted.put(resident.id(), resident.home().orElseThrow());
            }
        }
        // Each judged home keeps its lowest ids, up to its capacity; the rest fall through.
        for (HomeSlot home : ordered) {
            int left = home.capacity();
            for (ResidentSlot resident : roster) {
                if (left <= 0) {
                    break;
                }
                if (resident.home().filter(home.bed()::equals).isPresent()
                        && !wanted.containsKey(resident.id())) {
                    wanted.put(resident.id(), home.bed());
                    left--;
                }
            }
        }
        // Whoever is left has no home we can honour: a vanished one, an overfull one, or none at all.
        for (ResidentSlot resident : roster) {
            if (wanted.containsKey(resident.id())) {
                continue;
            }
            for (HomeSlot home : ordered) {
                if (occupancyOf(wanted, home.bed()) < home.capacity()) {
                    wanted.put(resident.id(), home.bed());
                    break;
                }
            }
        }

        Map<String, Optional<BedKey>> current = new HashMap<>();
        for (ResidentSlot resident : roster) {
            current.put(resident.id(), resident.home());
        }
        List<Change> edits = new ArrayList<>();
        for (ResidentSlot resident : roster) {
            Optional<BedKey> target = Optional.ofNullable(wanted.get(resident.id()));
            if (!target.equals(resident.home())) {
                edits.add(new Change(resident.id(), target));
            }
        }
        // A decided order, so a budgeted pass always lands the same edits first. An edit always has a bed
        // to sort by: either the one it moves to, or the one it leaves.
        edits.sort((left, right) -> {
            int byBed = BED_ORDER.compare(bedFor(left, current), bedFor(right, current));
            return byBed != 0 ? byBed : left.residentId().compareTo(right.residentId());
        });
        return edits;
    }

    private static BedKey bedFor(Change edit, Map<String, Optional<BedKey>> current) {
        return edit.home().or(() -> current.get(edit.residentId())).orElseThrow();
    }

    private static int occupancyOf(Map<String, BedKey> wanted, BedKey bed) {
        int count = 0;
        for (BedKey taken : wanted.values()) {
            if (taken.equals(bed)) {
                count++;
            }
        }
        return count;
    }
}
```

（三段顺序就是设计 §3.2 的规则，**不需要再来一个"这张床位在不在册"的集合**：第 0 步把不可判定房子的住户留在了 `wanted`，第 1 步把每栋房按容量能留的都留了，于是第 2 步处理的天然就是"归属指向已消失房子的、被挤出超容房子的、以及本来就无房的"三者之并。多一个集合只会是第二处判据。）

- [ ] **Step 5: 跑检查并跑完整构建**

Run: `./gradlew housingAssignmentCheck --offline --no-daemon`

Expected: `HousingAssignmentCheck passed`。

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**20 项**检查全部 `*Check passed`。

- [ ] **Step 6: 核对纯层没碰 Minecraft**

Run: `grep -n "^import" src/main/java/dev/local/goblinsettlement/housing/HousingAssignment.java`

Expected: **只有 `java.util.*`**，没有任何 `net.minecraft.*` / `dev.local.goblinsettlement.*`。（对照：`HousingRules` 与 `ShelterRules` 也是这个形状。）

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingAssignment.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/HousingAssignmentCheck.java \
        goblin-settlement-mod/build.gradle
git commit -m "Decide who lives where as a pure rule"
```

---

### Task 3: 房子容量的唯一算式

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`

**Interfaces:**
- Produces: `HousingCoordinator.homeCapacity(ServerLevel, HousingSavedData.Home) -> int`（**必须 `public`**：`HousingAssignmentCoordinator` 在同一个包内，但 `FamilyCoordinator` 与 `GoblinSettlement` 在别的包）
- Consumes: `HousingRules.builtCapacity(int, boolean, boolean)`、`HousingCoordinator.stageFullyBuilt(...)`（既有私有）

- [ ] **Step 1: 加 `homeCapacity`**

在 `HousingCoordinator` 的 `shelterCapacity` **之前**加：

```java
    /**
     * How many residents this home holds: its bed, plus one for each capacity stage actually built. The
     * target alone is not enough -- a raised target whose geometry has not been placed adds no room, so
     * the household count must follow the world. Read-only.
     */
    public static int homeCapacity(ServerLevel level, HousingSavedData.Home home) {
        return HousingRules.builtCapacity(home.capacityTarget(), stageFullyBuilt(level, home, 1),
                stageFullyBuilt(level, home, 2));
    }
```

- [ ] **Step 2: 让 `shelterCapacity` 走它**

把 `shelterCapacity` 的体换成：

```java
    public static int shelterCapacity(ServerLevel level, HousingSavedData.Home home) {
        return stageFullyBuilt(level, home, 1) ? homeCapacity(level, home) : 0;
    }
```

**这是逐字等价**：原式在"1 级已建成"时才计算 `builtCapacity(target, true, stage2Built)`，而那一支里 `true` 与 `stageFullyBuilt(level, home, 1)` 同值。所以 `shelterCapacity` 的返回值对每一组合都不变——只把"能住几人"这个算式收成一处。

- [ ] **Step 3: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，20 项全部 `*Check passed`，无编译警告。

**本任务没有新断言**：等价性是构造出来的（委派式与原式在同一个分支里逐字相同），写一条断言自己证明自己没有意义；`housingRulesCheck` 里既有的 `builtCapacity` 两条腿断言继续覆盖那个算式。证据是编译 + 这两条既有断言 + 代码审查，请如实报告。

- [ ] **Step 4: 核对 `shelterCapacity` 只有一个调用口径没有分叉**

Run: `grep -rn "shelterCapacity\|homeCapacity" src/main/java/`

Expected: `homeCapacity` 一处定义、被 `shelterCapacity` 与下一个任务的协调器使用；`shelterCapacity` 一处定义、被 `ShelterCoordinator` 使用。**没有任何地方自己写 `1 + 建成级数`。**

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java
git commit -m "Keep one sum for how many a home holds"
```

---

### Task 4: 分配协调器与三个只读助手

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingAssignmentCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`

**Interfaces:**
- Consumes: `HousingAssignment.plan / occupancy / *Slot / Change`（Task 2）、`HousingCoordinator.homeCapacity`（Task 3）、`SettlementSavedData.assignHome`（Task 1）、`HousingSavedData.homes(String)`（既有）
- Produces:
  - `HousingAssignmentCoordinator.tick(ServerLevel)`
  - `HousingAssignmentCoordinator.occupancy(SettlementSavedData, BlockPos) -> int`
  - `HousingAssignmentCoordinator.homeless(ServerLevel, String id, SettlementSavedData) -> int`
  - `HousingAssignmentCoordinator.roomAt(ServerLevel, String id, SettlementSavedData, BlockPos) -> Optional<Boolean>`

- [ ] **Step 1: 写协调器**

创建 `src/main/java/dev/local/goblinsettlement/housing/HousingAssignmentCoordinator.java`：

```java
package dev.local.goblinsettlement.housing;

import dev.local.goblinsettlement.colony.ResidentRecord;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Keeps the roster's home field in step with the homes that exist, one bounded batch at a time.
 *
 * The decision itself is pure (HousingAssignment); this class only reads the world into its inputs and
 * writes the edits back, so nothing here decides who lives where.
 */
public final class HousingAssignmentCoordinator {
    private static final int INTERVAL_TICKS = 40;
    // Invented, like TRAFFIC_PER_LANE and SAMPLE_INTERVAL_TICKS: the roster is at most 64 long, so a
    // whole pass would fit in one tick. The budget exists to bound the edits per tick and to make the
    // order they land in a decided thing rather than an accident.
    private static final int BUDGET = 8;

    private HousingAssignmentCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL_TICKS != 0) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return;
        }
        String id = settlement.get().id();
        var judged = new ArrayList<HousingAssignment.HomeSlot>();
        var unjudged = new HashSet<HousingAssignment.BedKey>();
        for (var home : HousingSavedData.get(level).homes(id)) {
            var bed = key(home.bed());
            if (level.shouldTickBlocksAt(home.bed())) {
                judged.add(new HousingAssignment.HomeSlot(bed, HousingCoordinator.homeCapacity(level, home)));
            } else {
                // Its capacity would read as the air of an unloaded chunk, so it takes no part at all.
                unjudged.add(bed);
            }
        }
        int applied = 0;
        for (var edit : HousingAssignment.plan(judged, Set.copyOf(unjudged), roster(data))) {
            if (applied >= BUDGET) {
                break;
            }
            if (data.assignHome(edit.residentId(), edit.home().map(HousingAssignmentCoordinator::pos))) {
                applied++;
            }
        }
    }

    /** How many living residents call this bed their home. Counted from the roster, never stored. */
    public static int occupancy(SettlementSavedData data, BlockPos bed) {
        int count = 0;
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.DECEASED) {
                continue;
            }
            if (record.home().filter(bed::equals).isPresent()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Living residents the roster gives no home at all. A resident whose home merely cannot be judged
     * right now is not counted: we do not know that it is homeless, only that we cannot look.
     */
    public static int homeless(ServerLevel level, String id, SettlementSavedData data) {
        Set<HousingAssignment.BedKey> known = new HashSet<>();
        for (var home : HousingSavedData.get(level).homes(id)) {
            // Judged or not, the bed is registered -- either way this resident is not homeless.
            known.add(key(home.bed()));
        }
        int count = 0;
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.DECEASED) {
                continue;
            }
            if (record.home().map(HousingAssignmentCoordinator::key).filter(known::contains).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether this home can take one more resident, or empty when it cannot be judged: the bed is not a
     * registered home, or it sits in a chunk that is not ticking. Callers fall back to their own rule on
     * empty rather than reading it as "full".
     */
    public static Optional<Boolean> roomAt(ServerLevel level, String id, SettlementSavedData data,
                                           BlockPos bed) {
        if (!level.shouldTickBlocksAt(bed)) {
            return Optional.empty();
        }
        var home = HousingSavedData.get(level).homes(id).stream()
                .filter(candidate -> candidate.bed().equals(bed))
                .findFirst();
        return home.map(found -> occupancy(data, bed) < HousingCoordinator.homeCapacity(level, found));
    }

    private static List<HousingAssignment.ResidentSlot> roster(SettlementSavedData data) {
        var slots = new ArrayList<HousingAssignment.ResidentSlot>();
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.DECEASED) {
                continue;
            }
            slots.add(new HousingAssignment.ResidentSlot(record.id(),
                    record.home().map(HousingAssignmentCoordinator::key)));
        }
        return slots;
    }

    private static HousingAssignment.BedKey key(BlockPos bed) {
        return new HousingAssignment.BedKey(bed.getX(), bed.getY(), bed.getZ());
    }

    private static BlockPos pos(HousingAssignment.BedKey bed) {
        return new BlockPos(bed.x(), bed.y(), bed.z());
    }
}
```

- [ ] **Step 2: 接进主循环**

在 `GoblinSettlement.tickSettlement` 里，`BedProvisioningCoordinator.tick(level);` **与** `PatrolCoordinator.tick(level);` 之间加一行：

```java
        HousingAssignmentCoordinator.tick(level);
```

（位置是刻意的：床先落地，归属才有的可指；归属先算好，同一 tick 里后跑的 `FamilyCoordinator` 才读到新鲜的。`FamilyCoordinator.tick(level)` 在本方法靠后，见 Task 5。）

`GoblinSettlement` 需要 `import dev.local.goblinsettlement.housing.HousingAssignmentCoordinator;`。

- [ ] **Step 3: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，20 项全部 `*Check passed`，无编译警告。

**本任务不可纯测**：协调器读世界的整条路径（`shouldTickBlocksAt` 闸、逐房读容量、预算截断）只有编译与代码审查。三个只读助手同样如此。请如实报告。

- [ ] **Step 4: 核对口径只有一处**

Run: `grep -rn "shouldTickBlocksAt\|DECEASED" src/main/java/dev/local/goblinsettlement/housing/HousingAssignmentCoordinator.java`

Expected: `shouldTickBlocksAt` 在**两处**（`tick` 的分流、`roomAt` 的闸）；`DECEASED` 在**三处**且都只是 `continue`——住户数、无房数、名册映射各一处。**没有第四处副本。**

Run: `grep -rn "1 + \|builtCapacity" src/main/java/dev/local/goblinsettlement/housing/HousingAssignmentCoordinator.java`

Expected: **一条都没有**——"能住几人"只来自 `HousingCoordinator.homeCapacity`。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingAssignmentCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Apply the roster's home assignments on a bounded tick"
```

---

### Task 5: 生育门落到母亲的房子

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/family/FamilyCoordinator.java`

**Interfaces:**
- Consumes: `HousingAssignmentCoordinator.roomAt(...)`（Task 4）、`SettlementSavedData.resident(String)`（既有）、`ResidentRecord.home()`（Task 1）
- Produces: `FamilyCoordinator.roomForMother(...)`（私有）

- [ ] **Step 1: 换掉传给 `conditions` 的那个布尔量**

把 `FamilyCoordinator.tick` 里从 `boolean foodForBirth = ...` 到 `for` 循环结束的那一段，改成：

```java
        boolean foodForBirth = supply.complete() && supply.food() >= 2 * occupied;
        boolean spareBed = housing.count() >= occupied;
        for (var pregnancy : data.readyBirths()) {
            FamilyConditions conditions = conditions(level, pregnancy.motherId(), pregnancy.fatherId(),
                    roomForMother(level, data, settlement.get().id(), pregnancy.motherId(), spareBed),
                    foodForBirth);
            if (!housing.beds().isEmpty() && data.commitBirth(pregnancy.childId(), conditions)) {
                placePendingNewborns(level, data, settlement.get().id(), settlement.get().anchor());
            }
        }
```

（`conditions(...)` 的第四个形参现名 `housingAvailable`，**名字不用改**。`!housing.beds().isEmpty()` 那条闸**保留**：新生儿要在某张真实可用床的上方生成，没有可用床就没有生成点。）

- [ ] **Step 2: 加判据**

在 `FamilyCoordinator` 的 `conditions(...)` **之后**加：

```java
    /**
     * The bed test GAME_DESIGN section 4 asks for, applied to the mother's own home: her house must
     * actually hold one more. A mother the roster gives no home, whose home is gone, or whose home we
     * cannot read right now falls back to the settlement-wide test -- without that fallback the opening
     * camp, which registers no homes at all, would never see another birth.
     */
    private static boolean roomForMother(ServerLevel level, SettlementSavedData data, String id,
                                         String motherId, boolean settlementHasSpareBed) {
        return data.resident(motherId)
                .flatMap(ResidentRecord::home)
                .flatMap(bed -> HousingAssignmentCoordinator.roomAt(level, id, data, bed))
                .orElse(settlementHasSpareBed);
    }
```

（`Optional.orElse` 正是这条规则：`roomAt` 给出"有空位 / 已满"，给不出（无归属、房子不在册、不可 tick）时才落到旧判据。`import dev.local.goblinsettlement.housing.HousingAssignmentCoordinator;` 要加。）

- [ ] **Step 3: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，20 项全部 `*Check passed`，无编译警告。

**本任务不可纯测**：真实家庭上的生育率变化只有游戏内才看得到。请如实报告。

- [ ] **Step 4: 核对旧判据没有被删掉，只是挪了位置**

Run: `grep -n "housing.count() >= occupied\|spareBed\|roomForMother\|housing.beds().isEmpty()" src/main/java/dev/local/goblinsettlement/colony/family/FamilyCoordinator.java`

Expected: `spareBed` 一处赋值、一处作为 `roomForMother` 的末参；`roomForMother` 一处定义、一处调用；`housing.beds().isEmpty()` 仍在。**`housing.count() >= occupied` 不应再直接出现在 `conditions(...)` 的实参里。**

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/family/FamilyCoordinator.java
git commit -m "Ask the mother's own house whether it can take one more"
```

---

### Task 6: `status` 逐栋显示住几人

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`

**Interfaces:**
- Consumes: `HousingAssignmentCoordinator.occupancy / homeless`（Task 4）、`HousingCoordinator.homeCapacity`（Task 3）、`HousingCoordinator.blockedReason`（既有）
- Produces: `housingReportLine(...)` 的新输出形状（私有）

- [ ] **Step 1: 汇总行加无房人数**

把 status 里的 `Housing: beds=…` 那条 `sendSuccess` 换成：

```java
                                context.getSource().sendSuccess(() -> Component.literal("Housing: beds=" + beds
                                        + ", occupied slots=" + occupied + ", spare=" + (beds - occupied)
                                        + ", homeless=" + HousingAssignmentCoordinator.homeless(level,
                                                settlement.get().id(), data)), false);
```

（`beds` / `occupied` 那两个局部变量保持不动。）

- [ ] **Step 2: 逐栋行改成带住户数**

把 `housingReportLine` 里从 `var stalled = new java.util.ArrayList<String>();` 到方法末尾，换成：

```java
        var entries = new java.util.ArrayList<String>();
        int notLoaded = 0;
        for (var home : homes) {
            if (!level.shouldTickBlocksAt(home.bed())) {
                notLoaded++;
                continue;
            }
            var reason = HousingCoordinator.blockedReason(level, data, home, id);
            entries.add(home.bed().toShortString() + " "
                    + HousingAssignmentCoordinator.occupancy(data, home.bed()) + "/"
                    + HousingCoordinator.homeCapacity(level, home)
                    + (reason.isPresent() ? " [" + reason.orElseThrow() + "]" : ""));
        }
        String unloaded = notLoaded == 0 ? "" : ", " + notLoaded + " not loaded";
        if (entries.isEmpty()) {
            return "Homes: none" + unloaded;
        }
        var builder = new StringBuilder("Homes: ");
        for (int index = 0; index < Math.min(HOUSING_REPORT_LIMIT, entries.size()); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(entries.get(index));
        }
        if (entries.size() > HOUSING_REPORT_LIMIT) {
            builder.append(", +").append(entries.size() - HOUSING_REPORT_LIMIT).append(" more");
        }
        return builder.toString() + unloaded;
```

并把该方法里两条早退的文案前缀一并改掉：

- `"Housing work: no settlement in this dimension"` → `"Homes: no settlement in this dimension"`
- `"Housing work: nothing can start, the blueprint data is unavailable"` → `"Homes: nothing can start, the blueprint data is unavailable"`

**保留那个蓝图不可用的早退分支**：那时 `blockedReason` 对每栋房都会回同一句话，逐栋重复没有信息量；数据文件坏了本身就是更该说出来的事实。代价是那一种状态下不显示住户数——**有意的**。

`GoblinSettlement` 需要 `import dev.local.goblinsettlement.housing.HousingAssignmentCoordinator;`（`HousingCoordinator` 与 `HousingSavedData` 若未 import 就补）。

- [ ] **Step 3: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，20 项全部 `*Check passed`，无编译警告。

**本任务不可纯测**：两行文案与排序只有在游戏里打 `status` 才看得见。请如实报告。

- [ ] **Step 4: 核对显示层仍不自己判任何东西**

Run: `grep -n "countOf\|firstHolding\|isAir()\|permitted(" src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`

Expected: **新增的几行里一条都没有**——住户数来自 `HousingAssignmentCoordinator.occupancy`，容量来自 `HousingCoordinator.homeCapacity`，原因来自既有的 `blockedReason`。（该文件别处本来就有的公共库存那几行不属本轮范围，不要动。）

Run: `grep -n "Housing work" src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`

Expected: **无匹配**——前缀已全部改为 `Homes:`。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Show how full each home is, and how many have none"
```

---

### Task 7: 文档与收尾

**Files:**
- Modify: `goblin-settlement-plan/HOUSING_ASSIGN_DESIGN.md`（把 §10 落地结果写实）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Tasks 1–6 的改动与构建结果（**含 `build/libs/goblin-settlement-0.1.0.jar` 的实际字节数、构建秒数、每个提交的哈希**）
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：先单独提交它们并署名，然后再做本任务的文档改动。

- [ ] **Step 2: 设计文档记录落地结果**

把 `HOUSING_ASSIGN_DESIGN.md` 末尾的 `## 10. 落地结果（实现后补记）` 一节（现为"（待本轮实现后补写。）"）替换为真实内容，至少覆盖：

- 纯规则的最终形状（`HousingAssignment` 的四个 record 与 `plan` 的三段），以及**实现期若有与设计不符的地方就地更正**（尤其 §3.2 的三段顺序、§3.3 的输出排序）。
- `homeCapacity` 与 `shelterCapacity` 的合并方式。
- 协调器的两个发明值最终取值。
- `status` 两行的最终文案。
- **仍未做**：§9 的全部边界。

- [ ] **Step 3: 追加 UpdateLog**

在 `UpdateLog.md` **末尾**追加（`<...>` 换成真实值，**逐条写真实时间，不要留占位**——第四十八、五十、五十五、五十七轮都漏填过）：

```markdown
## [<开始> – <结束>] 第六十轮：住宅住户分配

- [<时间>] 按 HOUSING_ASSIGN_DESIGN.md 与 HOUSING_ASSIGN_PLAN.md 执行。范围由用户在上一轮结束时就定下（用途 = 显示 + 容量硬约束），本轮不重做 brainstorming；实现前用户另定死了两处设计覆盖不到的取舍——**无房者回落旧判据**（否则初始营地一栋 home 都注册不了、开局 8 人会完全不繁衍，而 GAME_DESIGN 第 11 节的营地阶段本就写着 8～12 人），以及**生育门只看母亲所在的那栋房**（伴侣同住既已不做，双方各自有房）。
- [<时间>] `ResidentRecord` 新增 `Optional<BlockPos> home`（`optionalFieldOf`，**不升 schema**，旧档读入即无房）；`withHome` 是**无条件**的取值变换——与 `withProfession` / `withReproductiveRole` 的一次性语义刻意不同，归属会反复解除与重绑；写入走 `SettlementSavedData.assignHome`。
- [<时间>] **本轮最容易漏的真缺陷在这里**：record 加一个分量就多一个构造参数，`ResidentRecord.java` 里那 **8 处**构造点（两个工厂 + `withReproductiveRole` / `withProfession` / `advanceFamilyTime` 两分支 / `withPostBirthRest` / `deceased`）必须逐处串上 `home`——其中 `advanceFamilyTime` **每 tick 都跑**，漏一处就是归属被静默清空。检查里有一条专门钉这件事。
- [<时间>] 纯层 `housing/HousingAssignment`：三段——①不可判定的房子里的住户原位不动；②每栋可判定的房子按 id 序收编到容量为止；③剩下的无房者按 id 序进"第一栋还有空位的房子"。**不按距离就近**（纯层不许碰世界），顺序全部由床位坐标 x→z→y 与居民 id 决定，输出只含真正的变化。第 **20** 项独立检查 `housingAssignmentCheck`。
- [<时间>] `HousingCoordinator.homeCapacity` 成为"这栋房能住几人"的唯一算式（= `builtCapacity(目标, 1级建成, 2级建成)`）；既有 `shelterCapacity` 改为 `1级建成 ? homeCapacity : 0`，**逐字等价**故不新增断言。
- [<时间>] `housing/HousingAssignmentCoordinator`：每 40 tick（发明值，源码注明待重定）读一次世界进纯规则，按 8 条的预算（同为发明值）写回存档；不可 tick 的房子进 `unjudged`，**既不作为分配目标、也不解除其住户**（读未加载的区块会把三人房读成容量 1，把好端端的住户挤出去）。挂进 `tickSettlement`，位置在 `BedProvisioningCoordinator` 之后、`FamilyCoordinator` 之前。
- [<时间>] 生育门：`FamilyCoordinator` 传给 `conditions` 的第四个布尔量改由 `roomForMother` 算出——母亲有归属且那栋房已判定就问她、否则回落 `housing.count() >= occupied`；`!housing.beds().isEmpty()` 保留（新生儿要有真实生成点）。
- [<时间>] 显示：`Housing:` 行加 `homeless=`；既有 `Housing work:` 行改为逐栋的 `Homes:` 行（`坐标 住几人/容量`，卡住的在原位带原因），排序 / 上限 8 / `+N more` / `n not loaded` 全部沿用。**那一列的容量用的是容量（与约束同一个数），不是实际床位数**——设计 §7 已写明这是需要另行确认的口径。
- [<时间>] 验证：完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**20 项**独立检查全部 *Check passed（新增第 20 项 housingAssignmentCheck）。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>，耗时 <秒数>。提交 <哈希>。
- [<时间>] 未完成：不做玩法验收；**协调器读世界的整条路径（shouldTickBlocksAt 闸、逐房读容量、预算截断）、生育门在真实家庭上的效果、status 那两行、无房人数在真实聚落里的走势全部不可纯测**（只有编译与代码审查）。`DECEASED` 的过滤刻意留在 MC 层的名册映射里（纯规则按契约只收存活居民），因此由审查而非独立检查保证。住户数**不预留**：同一拍两个无房居民可能都看中同一个空位而超员 1，下一拍由规则挤出（与第五十三轮避难占用的取舍同源）。分配**不按距离**，超容挤出会让人"搬家"。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「接续须知」的**分支指针**改成本轮收尾提交；**下一步落点**改写：住宅的「住户分配」**已完成**，落点在 `HOUSING_ASSIGN_DESIGN.md` §10；住宅只剩公共设施、实体公告牌方块、历史保留。把"已核对、不必重推的现状"那几条（`ResidentRecord` 无住处字段等）**替换为本轮之后的现状**（`ResidentRecord.home` 已存在、住户数由名册推导）。
- 「阶段定位」阶段 6 补上"第六十轮补上住户分配（归属入档、生育门落到母亲的房子、status 逐栋显示住几人）"。
- 「本轮接入的内容」新增一节「第六十轮：住宅住户分配」。
- 「住宅剩余」那句把住户分配标为**已完成**并写明落点与两条已定取舍，其余三项不动。
- 「本轮验证进展」替换为第六十轮：完整构建、**20 项**检查（**新增第 20 项 `housingAssignmentCheck`**）、产物字节数与耗时、提交；把"独立检查覆盖不到"的清单换成本轮的。
- 别处提到检查项数的地方（阶段描述、验证段）一律由 19 改为 **20**。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the resident-to-home assignment round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。若本机代理未运行导致连不上 github，提交留在本地并如实报告，不算任务失败。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 归属只存居民侧、§2.1 住户数扫名册、§2.2 非一次性、§2.3 构造点陷阱 → Task 1（字段、`withHome`、`assignHome`、全构造点 + 断言）。
- 设计 §3 纯规则（三段）、§3.3 确定性、§3.4 `unjudged`、§3.5 幂等 → Task 2（`HousingAssignment` + 九组断言，逐条对应设计 §8 的九项）。
- 设计 §1 的 `builtCapacity` 唯一算式 → Task 3。
- 设计 §6 协调器与两个发明值 → Task 4。
- 设计 §4 生育门（含两条已定取舍与回落）→ Task 5。
- 设计 §7 显示 → Task 6。
- 设计 §5 "不新增机制"由 Task 4 的分配本身兑现（住不下 = 留作无房，不改需求档、不改扩地）。
- 设计 §9 风险与 §0 的"明确不做" → Global Constraints + Task 7 的"未完成"段。

**2. 占位符扫描**

- 无 TBD/TODO。纯规则、协调器、`roomForMother`、`roomAt`、`housingReportLine` 都给了全文；`ResidentRecord` 的八个构造点逐个点名并给了核对命令。
- Task 2 的三段**刻意不引入"这张床位在不在册"的集合**：第 0 步留住不可判定房子的住户、第 1 步按容量收编，剩下的天然就是"归属已消失 / 被挤出超容 / 本来就无房"三者之并。计划正文写明了这一点，免得实现时"顺手"补一个第二处判据。
- Task 7 的 `<...>` 是模板占位，正文要求逐条替换为真实值。

**3. 类型一致性**

- `HousingAssignment.BedKey / HomeSlot / ResidentSlot / Change` 在 Task 2 定义；Task 4 的协调器构造它们、Task 2 的检查构造它们，四个 record 的形状在两处一致。
- `HousingAssignment.plan(List<HomeSlot>, Set<BedKey>, List<ResidentSlot>) -> List<Change>`：Task 4 以 `(judged, Set.copyOf(unjudged), roster(data))` 调用，类型对上。
- `HousingCoordinator.homeCapacity(ServerLevel, HousingSavedData.Home) -> int`：Task 3 定义，Task 4（装配容量）与 Task 6（显示）调用；Task 3 的 `shelterCapacity` 参数表不变，`ShelterCoordinator` 的既有调用点不受影响。
- `HousingAssignmentCoordinator.roomAt(ServerLevel, String, SettlementSavedData, BlockPos) -> Optional<Boolean>`：Task 4 定义，Task 5 的 `roomForMother` 以 `flatMap` 消费——`Optional<Boolean>.flatMap` 返回 `Optional<Boolean>`，再 `.orElse(settlementHasSpareBed)` 得 `boolean`，与 `conditions(...)` 的第四个形参一致。
- `SettlementSavedData.assignHome(String, Optional<BlockPos>) -> boolean`：Task 1 定义，Task 4 以 `edit.home().map(HousingAssignmentCoordinator::pos)` 调用（`Optional<BedKey> -> Optional<BlockPos>`）。
- `HousingAssignmentCoordinator.occupancy(SettlementSavedData, BlockPos) -> int` 与 `homeless(ServerLevel, String, SettlementSavedData) -> int`：Task 4 定义，Task 6 调用，实参顺序按定义。
