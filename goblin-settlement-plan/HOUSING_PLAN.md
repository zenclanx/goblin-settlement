# 哥布林住宅升级链（两轴）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把住宅从"单轴、无条件升级"改成"容量轴与品质轴分开推进"，并让床位增长真正受房屋容量约束。

**Architecture:** 新增纯规则类 `HousingRules`（两轴步骤合成、扩建/提品质判定、床位与容量换算），几何从 `HousingCoordinator` 搬进去。`Home` 由单轴 `stage` 改为两条轴的目标值，**不再保存构建游标**——进度每次由"哪些格已经是木板"重新推导，从而天然容忍目标变化。新建 `BedCensus` 作为床位口径的唯一来源。`SettlementDemand` 新增 `HOUSING` 档；`BedProvisioningCoordinator` 放床时要求落在有容量空位的房子附近，但保留两个逃生口以避免死锁。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [HOUSING_DESIGN.md](HOUSING_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。禁止引入其他 Minecraft 版本的 API 或示例。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **不做游戏内验证**：按用户约定，初版代码全部完成后才统一测试。本轮只做编译 + 独立检查。
- **纯规则不得依赖 Minecraft 类型**（`Level`、`BlockPos`、`Item` 等）——`HousingRules` 只吃 `int`、自身定义的 record/enum 与 `java.util` 集合。
- **不保存构建游标**：进度一律由世界方块推导（§3.1）。这是本轮的核心设计决定，不要退回"存一个 step 整数"。
- 存档兼容：新增字段用 `optionalFieldOf`，**不提升 schema 版本**（`HousingSavedData` 的私有构造器严格校验版本，升版本会让旧存档加载失败）。
- **品质轴不得增加任何床位**——这是"人口满额后房子变漂亮但不突破人口上限"的落点。
- 代码注释用英文（与现有源码一致）。只在违反直觉处写注释。
- 不修改 `ai-chat-mod`，不动常用存档。
- **`goblin-settlement-plan/` 下可能有另一个 agent 未提交的改动**：提交时只暂存你自己改的文件，不要用 `git add -A` / `git add .`。

---

### Task 1: 纯规则类 `HousingRules` 与几何迁移

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingRules.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/HousingRulesCheck.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`（删除 `Step` record、`blueprints()`、`STAGES`；`position()` 改同包可见）
- Modify: `goblin-settlement-mod/build.gradle`（注册检查任务）

**Interfaces:**
- Consumes: 无
- Produces:
  - `HousingRules.HomeAction { EXPAND_CAPACITY, IMPROVE_QUALITY, NONE }`
  - `HousingRules.Step(int x, int y, int z)`
  - `HousingRules.MAX_CAPACITY_TARGET = 2`、`MAX_QUALITY_TARGET = 2`、`BIND_RADIUS = 8`、`RESERVE_EXPANDED = 24`、`RESERVE_BASIC = 8`
  - `HousingRules.decide(int beds, int occupiedSlots, int capacityTarget, int qualityTarget) -> HomeAction`
  - `HousingRules.needsCapacity(int beds, int occupiedSlots) -> boolean`
  - `HousingRules.canGainCapacity(int usedBeds, int capacityTarget) -> boolean`
  - `HousingRules.steps(int capacityTarget, int qualityTarget) -> List<Step>`
  - `HousingRules.firstUnbuilt(List<Step> steps, Set<Step> built) -> OptionalInt`
  - `HousingRules.bedsNear(List<BlockPos> heads, BlockPos anchor, int radius) -> int`
  - `HousingRules.builtCapacity(int capacityTarget, boolean stage1Built, boolean stage2Built) -> int`
  - `HousingRules.stages() -> List<List<Step>>`
  - `HousingCoordinator.position(ServerLevel, BlockPos, int, Step) -> BlockPos`（由 private 改同包可见）

- [ ] **Step 1: 写失败的检查**

新建 `HousingRulesCheck.java`（项目既有写法：无 JUnit、`main`、私有 `require` 抛 `AssertionError`、成功打印 `HousingRulesCheck passed`）：

```java
package dev.local.goblinsettlement.housing;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HousingRulesCheck {
    public static void main(String[] args) {
        checkDecide();
        checkSteps();
        checkFirstUnbuilt();
        checkCounts();
        System.out.println("HousingRulesCheck passed");
    }

    private static void checkDecide() {
        require(HousingRules.decide(8, 8, 0, 0) == HousingRules.HomeAction.EXPAND_CAPACITY,
                "a bed shortage expands capacity first");
        require(HousingRules.decide(8, 8, 1, 0) == HousingRules.HomeAction.EXPAND_CAPACITY,
                "a shortage keeps expanding while capacity remains");
        require(HousingRules.decide(8, 8, 2, 0) == HousingRules.HomeAction.IMPROVE_QUALITY,
                "a shortage with capacity maxed turns to quality instead of stalling");
        require(HousingRules.decide(8, 8, 2, 2) == HousingRules.HomeAction.NONE,
                "nothing left to raise");
        require(HousingRules.decide(9, 8, 0, 0) == HousingRules.HomeAction.IMPROVE_QUALITY,
                "surplus beds never expand capacity");
        require(HousingRules.decide(9, 8, 2, 2) == HousingRules.HomeAction.NONE, "fully upgraded");
        boolean threw = false;
        try {
            HousingRules.decide(-1, 0, 0, 0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        require(threw, "negative counts are rejected");
    }

    private static void checkSteps() {
        require(HousingRules.steps(0, 0).size() == 21, "shelter only");
        require(HousingRules.steps(1, 0).size() == 92, "shelter plus cabin");
        require(HousingRules.steps(2, 0).size() == 102, "all three capacity stages");
        require(HousingRules.steps(2, 1).size() == 111, "capacity plus the first quality stage");
        require(HousingRules.steps(2, 2).size() == 121, "everything");
        require(HousingRules.steps(1, 1).equals(HousingRules.steps(1, 1)),
                "the same targets always compose the same list");
        for (var step : HousingRules.steps(2, 2)) {
            require(step.x() >= -3 && step.x() <= 3 && step.z() >= -2 && step.z() <= 2
                    && step.y() >= 0 && step.y() <= 4, "every step stays inside the blueprint box");
        }
    }

    private static void checkFirstUnbuilt() {
        List<HousingRules.Step> shelter = HousingRules.steps(0, 0);
        require(HousingRules.firstUnbuilt(shelter, Set.of()).orElseThrow() == 0,
                "an untouched home starts at the first step");
        Set<HousingRules.Step> half = new HashSet<>(shelter.subList(0, 5));
        require(HousingRules.firstUnbuilt(shelter, half).orElseThrow() == 5,
                "built leading steps are skipped");
        require(HousingRules.firstUnbuilt(shelter, new HashSet<>(shelter)).isEmpty(),
                "a fully built home has nothing left");
        // The regression this design exists for: quality steps are built first, then the capacity
        // target rises. The newly inserted capacity steps sit BEFORE the built quality ones, so a
        // stored cursor would skip them; deriving from the world finds them.
        Set<HousingRules.Step> builtEarly = new HashSet<>(HousingRules.steps(1, 1));
        int next = HousingRules.firstUnbuilt(HousingRules.steps(2, 1), builtEarly).orElseThrow();
        require(next == 92, "raising the capacity target after quality work finds the inserted steps");
        require(HousingRules.steps(2, 1).get(next) == HousingRules.steps(2, 0).get(21 + 71),
                "the step found is the first expanded-stage step");
    }

    private static void checkCounts() {
        require(HousingRules.needsCapacity(8, 8), "eight beds cannot seat eight residents plus a spare");
        require(!HousingRules.needsCapacity(9, 8), "a spare bed ends the shortage");
        require(HousingRules.canGainCapacity(1, 0), "a shelter can grow");
        require(HousingRules.canGainCapacity(2, 1), "a cabin can grow");
        require(!HousingRules.canGainCapacity(3, 1), "a cabin already full cannot grow");
        require(!HousingRules.canGainCapacity(2, 2), "a maxed home cannot grow");
        require(!HousingRules.canGainCapacity(8, 0), "a camp with eight beds near one anchor cannot grow");
        require(HousingRules.builtCapacity(0, false, false) == 1, "a shelter hosts one bed");
        require(HousingRules.builtCapacity(1, true, false) == 2, "a finished cabin hosts two");
        require(HousingRules.builtCapacity(2, true, true) == 3, "a finished extension hosts three");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

- [ ] **Step 2: 运行检查，确认失败**

Run: `cd goblin-settlement-mod && ./gradlew compileTestJava --offline --no-daemon`
Expected: 编译失败，`cannot find symbol: class HousingRules`。

- [ ] **Step 3: 实现 `HousingRules`**

新建 `HousingRules.java`，把 `HousingCoordinator.blueprints()`（现 `:224-260`）的五个 `List<Step>` 搬入（shelter 21 / cabin 71 / expanded 10 / quality 9 / mature 10），并把 `Step` record 一并搬来。

**一处例外**：原品质级的列表里有 **10 条、但只有 9 个不同坐标**——中心块 `(0,4,0)` 在横排与竖排两个循环里各出现一次。搬入时**去掉这条重复**，只保留 9 个不同块。这在世界里不改变任何结果（重复条目第二次访问时该格已经是木板，原本就会被跳过），但能让步骤数、断言与"前缀闭合"性质都自洽。搬入处的代码里写注释说明这一点。

```java
package dev.local.goblinsettlement.housing;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/** Pure two-axis housing rules. World state and inventory checks belong to the coordinators. */
public final class HousingRules {
    public static final int MAX_CAPACITY_TARGET = 2;
    public static final int MAX_QUALITY_TARGET = 2;
    /** A new bed binds to a home whose anchor bed is within this Chebyshev x/z radius. */
    public static final int BIND_RADIUS = 8;
    public static final int RESERVE_EXPANDED = 24;
    public static final int RESERVE_BASIC = 8;

    private HousingRules() {
    }

    public enum HomeAction { EXPAND_CAPACITY, IMPROVE_QUALITY, NONE }

    public record Step(int x, int y, int z) {
    }

    private static final List<List<Step>> STAGES = blueprints();

    public static List<List<Step>> stages() {
        return STAGES;
    }

    /** A bed shortage is the only thing that justifies spending planks on capacity. */
    public static HomeAction decide(int beds, int occupiedSlots, int capacityTarget, int qualityTarget) {
        if (beds < 0 || occupiedSlots < 0) {
            throw new IllegalArgumentException("Bed and occupied slot counts cannot be negative");
        }
        validate(capacityTarget, qualityTarget);
        if (needsCapacity(beds, occupiedSlots) && capacityTarget < MAX_CAPACITY_TARGET) {
            return HomeAction.EXPAND_CAPACITY;
        }
        return qualityTarget < MAX_QUALITY_TARGET ? HomeAction.IMPROVE_QUALITY : HomeAction.NONE;
    }

    public static boolean needsCapacity(int beds, int occupiedSlots) {
        return beds < occupiedSlots + 1;
    }

    /** Raising capacityTarget once would let this home host at least one more bed. */
    public static boolean canGainCapacity(int usedBeds, int capacityTarget) {
        return capacityTarget < MAX_CAPACITY_TARGET && usedBeds < capacityTarget + 2;
    }

    /** The capacity axis builds its stages first, then the quality axis. Order never varies. */
    public static List<Step> steps(int capacityTarget, int qualityTarget) {
        validate(capacityTarget, qualityTarget);
        List<Step> result = new ArrayList<>();
        for (int capacity = 0; capacity <= capacityTarget; capacity++) {
            result.addAll(STAGES.get(capacity));
        }
        for (int quality = 1; quality <= qualityTarget; quality++) {
            result.addAll(STAGES.get(MAX_CAPACITY_TARGET + quality));
        }
        return List.copyOf(result);
    }

    public static OptionalInt firstUnbuilt(List<Step> steps, Set<Step> built) {
        for (int index = 0; index < steps.size(); index++) {
            if (!built.contains(steps.get(index))) {
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }

    /** Beds within the same Chebyshev window the placement exclusion uses. */
    public static int bedsNear(List<net.minecraft.core.BlockPos> heads,
                               net.minecraft.core.BlockPos anchor, int radius) {
        int count = 0;
        for (var head : heads) {
            if (Math.abs(head.getX() - anchor.getX()) <= radius
                    && Math.abs(head.getZ() - anchor.getZ()) <= radius
                    && Math.abs(head.getY() - anchor.getY()) <= 4) {
                count++;
            }
        }
        return count;
    }

    /** Beds a home can host given the capacity geometry actually finished. */
    public static int builtCapacity(int capacityTarget, boolean stage1Built, boolean stage2Built) {
        int extra = 0;
        if (capacityTarget >= 1 && stage1Built) extra++;
        if (capacityTarget >= 2 && stage2Built) extra++;
        return 1 + extra;
    }

    private static void validate(int capacityTarget, int qualityTarget) {
        if (capacityTarget < 0 || capacityTarget > MAX_CAPACITY_TARGET
                || qualityTarget < 0 || qualityTarget > MAX_QUALITY_TARGET) {
            throw new IllegalArgumentException("Housing targets out of range");
        }
    }

    private static List<List<Step>> blueprints() {
        // Moved verbatim from HousingCoordinator.blueprints(): shelter, cabin, expanded, quality, mature.
    }
}
```

**注意**：`bedsNear` 为了判据统一而接收 `BlockPos`，这与"纯规则不依赖 Minecraft 类型"的约束冲突。既然它只做整数比较，**请改为接收三个 int 数组或一组坐标元组**，由调用方转换——即签名改成 `bedsNear(List<int[]> heads, int ax, int ay, int az, int radius)` 之类，保持 `net.minecraft` 不出现在本文件。**实现时以不引入 Minecraft import 为准**，上面的签名只是示意。

- [ ] **Step 4: `HousingCoordinator` 去掉重复定义**

删除 `HousingCoordinator` 的 `Step` record、`blueprints()` 与 `STAGES`；所有 `STAGES` 引用改为 `HousingRules.stages()`。`position()` 去掉 `private`（同包可见）。

- [ ] **Step 5: 注册检查任务并运行**

`build.gradle` 在既有检查任务之后注册：

```groovy
tasks.register('housingRulesCheck', JavaExec) {
    group = 'verification'
    description = 'Runs the standalone two-axis housing rule assertions.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.housing.HousingRulesCheck'
}
```

并在 `tasks.named('check')` 块里加 `dependsOn tasks.named('housingRulesCheck')`。

Run: `cd goblin-settlement-mod && ./gradlew housingRulesCheck --offline --no-daemon`
Expected: `HousingRulesCheck passed`，`BUILD SUCCESSFUL`。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingRules.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/HousingRulesCheck.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java \
        goblin-settlement-mod/build.gradle
git commit -m "Add the pure two-axis housing rules"
```

---

### Task 2: `BedCensus` 床位普查

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BedCensus.java`

**Interfaces:**
- Consumes: Task 1 的 `HousingRules`
- Produces:
  - `BedCensus.heads(ServerLevel, SettlementSavedData) -> List<BlockPos>`
  - `BedCensus.count(ServerLevel, SettlementSavedData) -> int`
  - `BedCensus.shortage(ServerLevel, SettlementSavedData) -> boolean`

- [ ] **Step 1: 实现 `BedCensus`**

口径必须与 `FamilyCoordinator.validBed` 一致：claimed plot 内的床方块、`PART == HEAD`、三格权限 ALLOWED、上方两格空气。每 game tick 至多扫一次，按 gameTime 缓存：

```java
package dev.local.goblinsettlement.housing;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** The single authority on how many beds the settlement really has. */
public final class BedCensus {
    private static final WeakHashMap<ServerLevel, Snapshot> SNAPSHOTS = new WeakHashMap<>();

    private record Snapshot(long tick, List<BlockPos> heads) {
    }

    private BedCensus() {
    }

    public static List<BlockPos> heads(ServerLevel level, SettlementSavedData data) {
        Snapshot snapshot = SNAPSHOTS.get(level);
        if (snapshot == null || snapshot.tick() != level.getGameTime()) {
            snapshot = new Snapshot(level.getGameTime(), scan(level, data));
            SNAPSHOTS.put(level, snapshot);
        }
        return snapshot.heads();
    }

    public static int count(ServerLevel level, SettlementSavedData data) {
        return heads(level, data).size();
    }

    public static boolean shortage(ServerLevel level, SettlementSavedData data) {
        return HousingRules.needsCapacity(heads(level, data).size(), data.occupiedPopulationSlots());
    }

    private static List<BlockPos> scan(ServerLevel level, SettlementSavedData data) {
        // Walk every claimed plot's 8x8x(anchorY-2..+5) window, keeping bed heads that are
        // loaded, permitted and open above, exactly as FamilyCoordinator.validBed decides.
    }
}
```

`scan` 的实现请**逐条对齐 `FamilyCoordinator.validBed` 的判据**（读该方法的源码照做），不要另立一套。

- [ ] **Step 2: 编译**

Run: `cd goblin-settlement-mod && ./gradlew compileJava --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BedCensus.java
git commit -m "Add the bed census as the single bed-count authority"
```

---

### Task 3: `Home` 改两轴 + `HousingCoordinator` 改造

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingSavedData.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/HousingRulesCheck.java`（补 codec 用例）

**Interfaces:**
- Consumes: Task 1 的 `HousingRules`；Task 2 的 `BedCensus`
- Produces:
  - `Home(BlockPos bed, int variant, int capacityTarget, int qualityTarget, Optional<String> workerId)`，方法 `withWorker(Optional<String>)`、`withCapacityTarget(int)`、`withQualityTarget(int)`
  - `HousingCoordinator.stageFullyBuilt(ServerLevel, HousingSavedData.Home, int) -> boolean`（包内可见）
  - `HousingCoordinator.hasCapacityGain(ServerLevel, SettlementSavedData) -> boolean`

**为什么这两个文件在同一任务里**：`Home` 去掉 `stage()`/`step()` 之后，`HousingCoordinator` 里对它们的所有调用会**立刻编译不过**。记录与它唯一的构造者是同一次改动，拆开就没有任何一半能单独编译。

- [ ] **Step 1: 写失败的检查**

在 `HousingRulesCheck` 的 `main` 里加 `checkHomeCodec();`，并新增该私有方法。JSON 手法照抄 `TransportSavedDataCheck` 的 `deepCopy` + 增删字段写法：

```java
    private static void checkHomeCodec() {
        var bed = new net.minecraft.core.BlockPos(10, 64, 10);
        var modern = new HousingSavedData.Home(bed, 1, 2, 1, java.util.Optional.empty());
        var json = HousingSavedData.Home.CODEC
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, modern).getOrThrow();
        require(!json.getAsJsonObject().has("stage"),
                "the legacy stage key is never written back");
        require(!json.getAsJsonObject().has("step"),
                "the dropped cursor is never written back");
        var reloaded = HousingSavedData.Home.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow();
        require(reloaded.capacityTarget() == 2 && reloaded.qualityTarget() == 1,
                "two-axis targets survive a round trip");

        int[][] expected = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}, {2, 2}};
        for (int stage = 0; stage <= 5; stage++) {
            var legacy = json.getAsJsonObject().deepCopy();
            legacy.remove("capacity_target");
            legacy.remove("quality_target");
            legacy.addProperty("stage", stage);
            legacy.addProperty("step", 7);
            var migrated = HousingSavedData.Home.CODEC
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, legacy).getOrThrow();
            require(migrated.capacityTarget() == expected[stage][0]
                            && migrated.qualityTarget() == expected[stage][1],
                    "legacy stage " + stage + " maps onto the two axes");
        }
    }
```

- [ ] **Step 2: 运行检查，确认失败**

Run: `cd goblin-settlement-mod && ./gradlew housingRulesCheck --offline --no-daemon`
Expected: 编译失败（`Home` 还没有两轴分量、`CODEC` 还不是包可见）。

- [ ] **Step 3: 改 `Home`**

```java
    /** Durable two-axis housing targets. Progress itself is derived from world blocks. */
    public record Home(BlockPos bed, int variant, int capacityTarget, int qualityTarget,
                       Optional<String> workerId) {
        static final Codec<Home> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("bed").forGetter(Home::bed),
                Codec.INT.fieldOf("blueprint").forGetter(Home::variant),
                Codec.INT.optionalFieldOf("capacity_target").forGetter(home -> Optional.of(home.capacityTarget())),
                Codec.INT.optionalFieldOf("quality_target").forGetter(home -> Optional.of(home.qualityTarget())),
                Codec.INT.optionalFieldOf("stage").forGetter(home -> Optional.<Integer>empty()),
                Codec.INT.optionalFieldOf("step").forGetter(home -> Optional.<Integer>empty()),
                Codec.STRING.optionalFieldOf("worker_id").forGetter(Home::workerId)
        ).apply(instance, Home::migrate));

        public Home(BlockPos bed, int variant, int capacityTarget, int qualityTarget) {
            this(bed, variant, capacityTarget, qualityTarget, Optional.empty());
        }

        public Home {
            bed = bed.immutable();
            workerId = java.util.Objects.requireNonNull(workerId, "workerId");
            if (variant < 0 || variant > 2
                    || capacityTarget < 0 || capacityTarget > HousingRules.MAX_CAPACITY_TARGET
                    || qualityTarget < 0 || qualityTarget > HousingRules.MAX_QUALITY_TARGET) {
                throw new IllegalArgumentException("Invalid housing targets");
            }
        }

        /**
         * Legacy saves carried a single stage and a build cursor. Stage 5 was reachable (the old
         * advance loop incremented unconditionally and the validator allowed it) and is identical
         * to stage 4 in geometry. The cursor is dropped: progress is derived from the world.
         */
        private static Home migrate(BlockPos bed, int variant, Optional<Integer> capacityTarget,
                                    Optional<Integer> qualityTarget, Optional<Integer> legacyStage,
                                    Optional<Integer> legacyStep, Optional<String> workerId) {
            if (legacyStage.isPresent()) {
                int stage = legacyStage.orElseThrow();
                if (stage < 0 || stage > 5) {
                    throw new IllegalArgumentException("Invalid legacy housing stage: " + stage);
                }
                return new Home(bed, variant, Math.min(stage, 2), Math.min(2, Math.max(0, stage - 2)), workerId);
            }
            return new Home(bed, variant, capacityTarget.orElse(0), qualityTarget.orElse(0), workerId);
        }

        public Home withWorker(Optional<String> value) {
            return new Home(bed, variant, capacityTarget, qualityTarget, value);
        }

        public Home withCapacityTarget(int value) {
            return new Home(bed, variant, value, qualityTarget, workerId);
        }

        public Home withQualityTarget(int value) {
            return new Home(bed, variant, capacityTarget, value, workerId);
        }
    }
```

要点：codec key 保持 `"blueprint"`（改名只动 Java 分量名，旧存档照读）；`SCHEMA_VERSION` 与 `MAX_HOMES` 不动；4 参便捷构造器签名不变，所以 `HousingCoordinator` 里 `new Home(bed, variant, 0, 0)` 文本不变、语义由 (stage,step) 变为 (capacity,quality)。

- [ ] **Step 4: `tick` 接入决策**

把 `tick` 里"`home.stage() < STAGES.size()` 才 advance"的写法改为：对每栋 home 先问 `HousingRules.decide(beds, occupied, home.capacityTarget(), home.qualityTarget())`；返回 `EXPAND_CAPACITY` 或 `IMPROVE_QUALITY` 就**把对应目标 +1 并 `housing.replace` 后 return**（每 tick 只提一格）；返回 `NONE` 才调 `advance`。`beds` 来自 Task 3 的 `BedCensus.count(level, data)`，`occupied` 来自 `data.occupiedPopulationSlots()`。

`tick` 开头新增的两行：

```java
        int occupied = data.occupiedPopulationSlots();
        int beds = BedCensus.count(level, data);
```


- [ ] **Step 5: `advance` 改为进度推导**

把 `advance` 里"取 `steps.get(home.step())`"与"该格已是木板就 `step+1`"整段，换成：

```java
        BlockPos site = nextSite(level, home);
        if (site == null) {
            releaseWorker(level, housing, home);   // 必须先释放，见下
            return false;
        }
```

**⚠️ 顺序有讲究，不要写反**：如果像早先那样把 `return false` 直接放在最前面，**一栋房子全部建成后它的最后一名工人会永久保留绑定**，而 `GoblinCitizenEntity.isAvailableForConstruction` 会拒绝任何 UUID 匹配某栋房子 `workerId` 的居民——于是每建成一栋房子就有一个居民永久退出全部建设工作（房屋上限 48，最多泄漏 48 人）。这是审查确认过的真实回归（旧代码的终态房屋携带空 `workerId`，并不会这样），不要在实现时退化回去。

把工人回收那段**提出来**成为独立方法（或直接在 `site == null` 分支里做等价的完整释放），确保释放同时覆盖 SavedData 的 `workerId` 与实体侧的工作状态。**验收标准**：房子建成后，最后一名工人必须重新可被 `isAvailableForConstruction` 挑中。

新增：

```java
    /** First step whose site is not yet oak planks; null when fully built or the bed lost its facing. */
    private static BlockPos nextSite(ServerLevel level, HousingSavedData.Home home) {
        for (HousingRules.Step step : HousingRules.steps(home.capacityTarget(), home.qualityTarget())) {
            BlockPos candidate = position(level, home.bed(), home.variant(), step);
            if (candidate != null && level.getBlockState(candidate).is(Blocks.OAK_PLANKS)) {
                continue;
            }
            return candidate;
        }
        return null;
    }
```

工人回收（`WorkerAssignmentRules.decide` 那一段）、`permitted` 检查、`!isAir` 检查、仓库快照与 `reserve`、按 `WorkKind.HOUSING` 挑人、`assignHousing` 与 `replace(withWorker)`——**全部保留原样**。`reserve` 的判据由旧的 `stage >= 2` 改为 `home.capacityTarget() >= 2 ? HousingRules.RESERVE_EXPANDED : HousingRules.RESERVE_BASIC`（迁移映射下语义精确等价）。

- [ ] **Step 6: `assignedSite`、`placeByResident` 与 `stageFullyBuilt`**

`assignedSite` 的判据由"该格等于按 `home.step()` 算出的位置"改为"该格等于 `nextSite(level, home)`"。

`placeByResident` 里 `setBlock` 成功后**删除**"找到 home 并 `replace(new Home(..., step + 1))`"那几行，只保留 `return true`——进度由下一次审查从世界重新推导。

新增供 Task 6 使用的 helper：

```java
    static boolean stageFullyBuilt(ServerLevel level, HousingSavedData.Home home, int stageIndex) {
        for (HousingRules.Step step : HousingRules.stages().get(stageIndex)) {
            BlockPos site = position(level, home.bed(), home.variant(), step);
            if (site == null || !level.getBlockState(site).is(Blocks.OAK_PLANKS)) {
                return false;
            }
        }
        return true;
    }
```

- [ ] **Step 7: 加 `hasCapacityGain`**

```java
    /** True while some home can still grow capacity toward hosting another bed. */
    public static boolean hasCapacityGain(ServerLevel level, SettlementSavedData data) {
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return false;
        }
        var heads = BedCensus.heads(level, data);
        for (var home : HousingSavedData.get(level).homes(settlement.orElseThrow().id())) {
            int used = HousingRules.bedsNear(/* heads 转成纯坐标 */, home.bed(), HousingRules.BIND_RADIUS);
            if (HousingRules.canGainCapacity(used, home.capacityTarget())) {
                return true;
            }
        }
        return false;
    }
```

- [ ] **Step 8: 运行检查并编译**

Run: `cd goblin-settlement-mod && ./gradlew housingRulesCheck compileJava --offline --no-daemon`
Expected: `HousingRulesCheck passed`，`BUILD SUCCESSFUL`。

- [ ] **Step 9: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingSavedData.java         goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java         goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/HousingRulesCheck.java
git commit -m "Store housing as two axes and derive build progress from the world"
```

---

### Task 4: `SettlementDemand` 加 `HOUSING` 档与扩地闸门

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementDemand.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ExpansionCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TrafficProposalCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/farming/FarmingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/food/FoodCraftingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`（`needsBread` 的两个调用点）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`（status 行的 `assess` 调用）
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/SettlementDemandCheck.java`

**Interfaces:**
- Consumes: Task 2 的 `BedCensus.shortage`；Task 3 的 `HousingCoordinator.hasCapacityGain`
- Produces: `SettlementDemand.assess(int, int, WarehouseSupply, boolean, boolean, boolean)`（末参 `housingShortage`）；`Priority.HOUSING`

- [ ] **Step 1: 写失败的检查**

`SettlementDemandCheck` 的辅助函数加第 6 参；既有断言全部补实参。新增 4 条：

```java
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), false, false, true)
                == SettlementDemand.Priority.HOUSING, "a bed shortage precedes readiness");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), true, false, true)
                == SettlementDemand.Priority.HOUSING, "a bed shortage precedes construction");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), false, true, true)
                == SettlementDemand.Priority.TRANSPORT, "pending traffic precedes housing");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 0, 1, 0, 1, true), false, false, true)
                == SettlementDemand.Priority.BASIC_TOOLS, "basic tools precede housing");
```

- [ ] **Step 2: 运行检查，确认失败**

Run: `cd goblin-settlement-mod && ./gradlew settlementDemandCheck --offline --no-daemon`
Expected: 编译失败（`assess` / `priority` 还没有第 6 参、`Priority.HOUSING` 不存在）。

- [ ] **Step 3: 改 `SettlementDemand`**

枚举在 `TRANSPORT` 与 `CONSTRUCTION` 之间插入 `HOUSING`；`assess` 加末参 `boolean housingShortage`，在 `trafficPending` 判定之后、`activeConstruction` 三元之前插入：

```java
        if (housingShortage) {
            return new Assessment(Priority.HOUSING, foodTarget, seedTarget);
        }
```

- [ ] **Step 4: 改 5 个调用点**

| 文件 | 改动 |
| --- | --- |
| `GoblinSettlement.java` status 行 | 末参传 `BedCensus.shortage(level, data)`（`BedCensus` 在 Task 3）|
| `ExpansionCoordinator.java` | 末参传 `BedCensus.shortage(level, data)`；**并把闸门改为**（见下） |
| `TrafficProposalCoordinator.java` | 末参传 `BedCensus.shortage(level, settlement)`（它原有的 `!= TRANSPORT` 门禁不动，`HOUSING` 档会自然压住交通立项） |
| `FarmingCoordinator.java` | 末参传 `BedCensus.shortage(level, data)` |
| `FoodCraftingCoordinator.java` + `GoblinCitizenEntity` 两处 | `needsBread` 加参数 `boolean housingShortage`，三处调用传 `BedCensus.shortage(level, data)`；`priority() == FOOD` 判据不变 |

**扩地闸门**（设计 §6a 的例外，必须实现）：

```java
        boolean ready = demand.priority() == SettlementDemand.Priority.READY
                || (demand.priority() == SettlementDemand.Priority.HOUSING
                    && !HousingCoordinator.hasCapacityGain(level, data));
        if (!ready || supply.oakPlanks() < 2) {
            return;
        }
```

- [ ] **Step 5: 运行检查，确认通过**

Run: `cd goblin-settlement-mod && ./gradlew settlementDemandCheck --offline --no-daemon`
Expected: `SettlementDemandCheck passed`。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/ \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/SettlementDemandCheck.java
git commit -m "Add the housing demand tier and let expansion resume when capacity cannot grow"
```

---

### Task 5: 床位与房屋容量绑定

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BedProvisioningCoordinator.java`

**Interfaces:**
- Consumes: Task 1 的 `HousingRules.bedsNear` / `builtCapacity` / `BIND_RADIUS`；Task 3 的 `HousingCoordinator.stageFullyBuilt`
- Produces: 无新公开接口

- [ ] **Step 1: 实现绑定**

在 `Scan` 上加两个字段：`List<BlockPos> heads`（在 `countBeds` 收床头的同一分支里同时收集）与 `List<BlockPos> spareAnchors`。新增：

```java
    private static List<BlockPos> spareCapacityAnchors(ServerLevel level, String id, List<BlockPos> heads) {
        List<BlockPos> result = new ArrayList<>();
        for (var home : HousingSavedData.get(level).homes(id)) {
            int used = HousingRules.bedsNear(heads, home.bed(), HousingRules.BIND_RADIUS);
            int capacity = HousingRules.builtCapacity(home.capacityTarget(),
                    HousingCoordinator.stageFullyBuilt(level, home, 1),
                    HousingCoordinator.stageFullyBuilt(level, home, 2));
            if (used < capacity) {
                result.add(home.bed());
            }
        }
        return result;
    }
```

计算时机两处：`COUNT` 完成且判定要去找位置之前；`VERIFY` 第二次 `countBeds` 之后、最终 `siteSuitable` 之前（容量可能在两次计数之间被 `HousingCoordinator` 提升）。`scan.beds.clear()` 时同步 `scan.heads.clear()`。

`siteSuitable` 加参数 `List<BlockPos> spareAnchors`，在既有的 `nearBed` 排除之后追加一条：

```java
                || (!spareAnchors.isEmpty() && spareAnchors.stream()
                        .noneMatch(anchor -> near(foot, anchor, HousingRules.BIND_RADIUS)
                                || near(head, anchor, HousingRules.BIND_RADIUS)))
```

即：**有房子有空位时，新床必须落在某个有空的房子的绑定半径内；一栋空位房子都没有时（含 homes 为空），绑定不启用。**

- [ ] **Step 2: 编译**

Run: `cd goblin-settlement-mod && ./gradlew compileJava --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BedProvisioningCoordinator.java
git commit -m "Bind new beds to homes that still have capacity"
```

---

### Task 6: status 显示住房空位

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`

**Interfaces:**
- Consumes: Task 2 的 `BedCensus.count`
- Produces: 无

- [ ] **Step 1: 加一行输出**

在既有库存行之后：

```java
                            int beds = BedCensus.count(level, data);
                            int occupied = data.occupiedPopulationSlots();
                            context.getSource().sendSuccess(() -> Component.literal("Housing: beds=" + beds
                                    + ", occupied slots=" + occupied + ", spare=" + (beds - occupied)), false);
```

补 import `dev.local.goblinsettlement.housing.BedCensus`。

- [ ] **Step 2: 编译**

Run: `cd goblin-settlement-mod && ./gradlew compileJava --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Show housing capacity in the status command"
```

---

### Task 7: 完整构建与文档收尾

**Files:**
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）

**Interfaces:**
- Consumes: 前 7 个任务的全部产出
- Produces: 无

- [ ] **Step 1: 完整构建**

Run: `cd goblin-settlement-mod && ./gradlew build --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`；**11 项独立检查**全部打印 `*Check passed`（既有 10 项 + `housingRulesCheck`）。

- [ ] **Step 2: 更新 `CURRENT_STATUS.md`**

更新日期；把"完整住宅升级链与更多蓝图"从缺口里**部分移出**（两轴升级链已接入候选，**更多蓝图仍缺**）；写清本轮接入了什么（两轴数据模型与旧档迁移、进度由世界推导、扩建/提品质规则、`HOUSING` 需求档与扩地例外、床位与容量绑定、status 显示）；验证进展写明"构建 + 11 项检查通过，**未做游戏内验证**"；尚需实现里保留：蓝图与材料清单迁到受校验数据文件、改建安全（临时住处）、公共设施、更多蓝图、实体公告牌方块、住户分配、历史保留。

- [ ] **Step 3: 追加 `UpdateLog.md`**

按格式在**末尾追加**新一段（读文件末尾确认轮次号与格式），记录各任务改动、构建与检查结果、以及未完成事项。**不得修改、删除或重排既有内容。** 注意文件里可能有**另一个 agent** 写的轮次，不要动。

- [ ] **Step 4: 提交并推送**

```bash
git add goblin-settlement-plan/CURRENT_STATUS.md goblin-settlement-plan/UpdateLog.md
git commit -m "Record the housing upgrade round"
git push
```

推送若因网络失败，报告实际报错并停止，不要重试循环。

---

## 自查记录

**Spec 覆盖：** §2 两轴（Task 1 的 `steps`/`builtCapacity` + Task 2 的字段）、§3.1 进度推导（Task 1 的 `firstUnbuilt` + Task 3 的 `nextSite`）、§3.2 迁移含 stage 5（Task 2 的 `migrate` + 检查用例）、§4 决策规则（Task 1 的 `decide`）、§5 需求档（Task 5）、§6 床位绑定含半径 8 与逃生口（Task 6）、§6a 扩地例外（Task 5）、§7 显示（Task 7）、§8 验证（各任务检查 + Task 8）、§9 范围外（未建任何对应任务）。

**与设计文档的偏离（均由计划代理的分析证明为必要，已回写进 spec）：**
1. §6 的绑定半径由 4 改为 **8**——半径 4 与既有的 4 格床位互斥区同中心、交集为空，第二张床数学上无解。
2. §6 增加**逃生口**"没有任何房子有空位时自由放床"，§6a 增加扩地例外——否则初始营地会永久卡在 8 人（营地 8 床只注册成 1 个 home、地块 1 又被 4 格互斥饱和，此为现状固有性质）。
3. §3.2 的迁移表补 **stage 5**——旧 `advance` 无条件 +1 且校验上限为 5，stage 5 是合法旧档状态。

**风险（详见 spec §10）：** 绑定的残余死角、附近床数跨房子重复计数、`new Home(bed, variant, 0, 0)` 文本不变而语义改变。
