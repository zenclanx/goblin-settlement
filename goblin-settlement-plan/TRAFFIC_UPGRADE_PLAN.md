# 通行量驱动的道路升级（第一轮：量）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把每条已建成道路的**通行量**量出来并留下：采样居民脚下的铺面、按计划 id 持久化命中计数、用纯判据判断"是否够格加宽"、在 `goblinsettlement status` 里把数字摊开。**本轮不加宽任何一格。**

**Architecture:** 纯层新增 `planning/transport/RoadUpgradeRules`（档位推进 + 是否够格），配第 15 项独立检查 `RoadUpgradeRulesCheck`；`TransportSavedData` 加一个"计划 id → 命中次数"的持久映射与一个**计划版本号**（供派生索引判新旧）、一个 `revision` 自增点、一处随计划增删的清理；MC 侧新增 `construction/transport/TrafficSampler`（按节拍遍历已加载居民 → 查"铺面 → 计划 id"表 → 累加 → 落盘），表按版本号缓存；显示由 `TransportCommands.trafficLine` 生成，`goblinsettlement status` 加一行调用它。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [TRAFFIC_UPGRADE_DESIGN.md](TRAFFIC_UPGRADE_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **本轮不写任何方块、不加宽任何道路**。采样只读世界、只写存档。
- **不升存档 schema 版本**：`TransportSavedData` 的新字段一律 `optionalFieldOf` 带默认值，旧档必须能打开。
- **阈值是发明值**：`TRAFFIC_PER_LANE` 与 `SAMPLE_INTERVAL_TICKS` 都**没有出处**，必须在源码注释里写明"没有依据、要按实测数据重定"，且两者必须互相引用（采样节拍就是判据的一半）。
- **不把"脚下是铺面"当作"在走路"**：站岗、避难、打架的居民一样会被计到。这是设计 §8 明写的**近似频率**，本轮不做 `workStage` 过滤，也不要为此加字段。
- 检查项数由 **14 增至 15**（新增 `roadUpgradeRulesCheck`）。
- 不做游戏内验证（按用户约定）。产物只证明编译与纯判据，**不构成玩法验收**。
- `goblin-settlement-plan/` 下**可能有并行 agent 的未提交改动**：文档任务开始时先跑 `git status`，发现不是自己改的就**先单独提交它们并署名**，再追加自己的。`UpdateLog.md` **只许在末尾追加**。
- 提交到 `main`（第四十二轮起的约定），不要另开分支。

---

### Task 1: 纯判据 `RoadUpgradeRules` 与第 15 项检查

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/planning/transport/RoadUpgradeRules.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/planning/transport/RoadUpgradeRulesCheck.java`
- Modify: `goblin-settlement-mod/build.gradle`（注册任务 + 挂进 `check`）

**Interfaces:**
- Consumes: 无（纯层，不依赖任何既有代码）
- Produces:
  - `RoadUpgradeRules.nextLanes(int lanes) -> int`
  - `RoadUpgradeRules.shouldUpgrade(int lanes, int traffic) -> boolean`
  - `RoadUpgradeRules.BUILT_ROAD_LANES -> int`（值为 2，全部建成道路的当前宽度）
  - `RoadUpgradeRules.TRAFFIC_PER_LANE -> int`（值为 200，发明阈值）

- [ ] **Step 1: 先写检查（RED）**

创建 `src/test/java/dev/local/goblinsettlement/planning/transport/RoadUpgradeRulesCheck.java`：

```java
package dev.local.goblinsettlement.planning.transport;

public final class RoadUpgradeRulesCheck {
    public static void main(String[] args) {
        checkLadderAdvances();
        checkLadderStopsAtTheWidest();
        checkThresholdBoundary();
        checkWiderRoadsAskForMore();
        checkTheWidestStreetNeverUpgrades();
        checkNonWidthsNeverUpgrade();
        checkBuiltWidthIsOnTheLadder();
        System.out.println("RoadUpgradeRulesCheck passed");
    }

    private static void checkLadderAdvances() {
        require(RoadUpgradeRules.nextLanes(2) == 3, "a trail widens to a road");
        require(RoadUpgradeRules.nextLanes(3) == 5, "a road widens to a main street");
        require(RoadUpgradeRules.nextLanes(1) == 2, "anything narrower lands on the first rung");
        require(RoadUpgradeRules.nextLanes(4) == 5, "a width between rungs lands on the next one up");
    }

    private static void checkLadderStopsAtTheWidest() {
        require(RoadUpgradeRules.nextLanes(5) == 5, "the widest street has nothing above it");
        require(RoadUpgradeRules.nextLanes(9) == 5, "a wider width clamps to the widest");
    }

    private static void checkThresholdBoundary() {
        int required = RoadUpgradeRules.TRAFFIC_PER_LANE;
        require(RoadUpgradeRules.shouldUpgrade(2, required), "exactly the threshold is enough");
        require(!RoadUpgradeRules.shouldUpgrade(2, required - 1), "one sample short is not enough");
    }

    private static void checkWiderRoadsAskForMore() {
        int required = RoadUpgradeRules.TRAFFIC_PER_LANE;
        require(RoadUpgradeRules.shouldUpgrade(3, required * 2), "a three-lane road asks for two shares");
        require(!RoadUpgradeRules.shouldUpgrade(3, required * 2 - 1), "and not one sample less");
        require(RoadUpgradeRules.shouldUpgrade(1, required), "a one-lane trail still asks for one share");
        require(!RoadUpgradeRules.shouldUpgrade(1, required - 1), "never less than one share");
    }

    private static void checkTheWidestStreetNeverUpgrades() {
        require(!RoadUpgradeRules.shouldUpgrade(5, Integer.MAX_VALUE), "the widest street is final");
    }

    private static void checkNonWidthsNeverUpgrade() {
        require(!RoadUpgradeRules.shouldUpgrade(0, Integer.MAX_VALUE), "zero lanes is not a road");
        require(!RoadUpgradeRules.shouldUpgrade(-1, Integer.MAX_VALUE), "a negative width is not a road");
    }

    private static void checkBuiltWidthIsOnTheLadder() {
        require(RoadUpgradeRules.BUILT_ROAD_LANES == 2, "roads are built two lanes wide today");
        require(RoadUpgradeRules.nextLanes(RoadUpgradeRules.BUILT_ROAD_LANES) == 3,
                "so the first widening step goes to three");
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

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号` / `程序包 dev.local.goblinsettlement.planning.transport 不存在`（`RoadUpgradeRules` 尚不存在）。

- [ ] **Step 3: 写纯判据**

创建 `src/main/java/dev/local/goblinsettlement/planning/transport/RoadUpgradeRules.java`：

```java
package dev.local.goblinsettlement.planning.transport;

/**
 * Pure rules for widening a road once it has carried enough traffic. GAME_DESIGN section 7 gives the
 * widths but no traffic numbers, so this round only judges and reports; the widening that follows the
 * judgement is a later round.
 */
public final class RoadUpgradeRules {
    /** Net walking widths from GAME_DESIGN section 7, narrowest first. */
    private static final int[] LADDER = {2, 3, 5};

    /**
     * The width every road is built at today: TransportCoordinator.startRoad lays the foot cell and its
     * clockwise neighbour. Widening is a later round, and it replaces this constant with a per-road
     * measured width.
     */
    public static final int BUILT_ROAD_LANES = LADDER[0];

    /**
     * Sample hits a road needs before a widening is worth it, per lane it already has. INVENTED: the
     * design document names widths but no numbers. This has no source and must be re-chosen once this
     * round has produced real counts -- and it must be re-chosen together with
     * TrafficSampler.SAMPLE_INTERVAL_TICKS, because hits are counted per sample and the interval is
     * therefore half of this judgement (TRAFFIC_UPGRADE_DESIGN section 2).
     */
    public static final int TRAFFIC_PER_LANE = 200;

    private RoadUpgradeRules() {
    }

    /** The next width up the ladder, or the widest width when there is nothing above. */
    public static int nextLanes(int lanes) {
        for (int candidate : LADDER) {
            if (candidate > lanes) {
                return candidate;
            }
        }
        return LADDER[LADDER.length - 1];
    }

    /**
     * Whether a road this wide, with this many traffic samples, has earned a widening. The widest
     * street never upgrades again, a road needs at least one full share, and a wider road always asks
     * for at least as much as a narrower one.
     */
    public static boolean shouldUpgrade(int lanes, int traffic) {
        if (lanes <= 0 || lanes >= LADDER[LADDER.length - 1]) {
            return false;
        }
        return traffic >= TRAFFIC_PER_LANE * Math.max(1, lanes - 1);
    }
}
```

- [ ] **Step 4: 把检查挂进构建**

在 `build.gradle` 里，`shelterRulesCheck` 任务块**之后**、`tasks.named('check')` **之前**插入：

```gradle
tasks.register('roadUpgradeRulesCheck', JavaExec) {
    group = 'verification'
    description = 'Checks the pure road widening thresholds.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.planning.transport.RoadUpgradeRulesCheck'
}
```

并在 `tasks.named('check')` 的 `dependsOn` 列表末尾（`dependsOn tasks.named('shelterRulesCheck')` 之后）追加一行：

```gradle
    dependsOn tasks.named('roadUpgradeRulesCheck')
```

- [ ] **Step 5: 跑完整构建，确认 15 项检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**15 项**检查全部 `*Check passed`（新增的 `RoadUpgradeRulesCheck passed` 在列），无编译警告。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/planning/transport/RoadUpgradeRules.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/planning/transport/RoadUpgradeRulesCheck.java \
        goblin-settlement-mod/build.gradle
git commit -m "Judge when a road has earned a widening, and check the ladder"
```

---

### Task 2: 采样、计数持久化与显示

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportSavedData.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/transport/TransportSavedDataCheck.java`
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TrafficSampler.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java`（一行注释）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java`（加 `trafficLine`）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`（接采样节拍 + status 一行）

**Interfaces:**
- Consumes: `RoadUpgradeRules.nextLanes` / `shouldUpgrade` / `BUILT_ROAD_LANES`（Task 1）；`ResidentWorkLookup.loaded(ServerLevel, SettlementSavedData)`（第五十三轮已有）；`TransportPlan.Kind` / `Phase` / `steps()` / `id()` / `isComplete()`
- Produces:
  - `TransportSavedData.traffic() -> Map<String, Integer>`（计划 id → 命中次数，只读）
  - `TransportSavedData.revision() -> int`（计划列表每次变化自增）
  - `TransportSavedData.recordTraffic(Map<String, Integer> hits) -> void`
  - `TrafficSampler.tick(ServerLevel level) -> void`
  - `TransportCommands.trafficLine(TransportSavedData traffic) -> String`

- [ ] **Step 1: 先写检查（RED）**

在 `TransportSavedDataCheck.java` 里，加 `import java.util.Map;`（放在既有的 `import java.util.List;` 之后），并在 `System.out.println("TransportSavedDataCheck passed");` **之前**插入：

```java
        var counted = new TransportSavedData();
        require(counted.add(roadPlan("traffic-1", facility, 0, false)), "traffic fixture accepted");
        require(counted.replace(roadPlan("traffic-1", facility, 1, false)), "traffic fixture completes");
        counted.recordTraffic(Map.of("traffic-1", 5));
        counted.recordTraffic(Map.of("traffic-1", 2));
        require(counted.traffic().get("traffic-1") == 7, "sample hits accumulate");
        counted.recordTraffic(Map.of("traffic-1", 0));
        require(counted.traffic().get("traffic-1") == 7, "a zero-hit round changes nothing");

        var countedJson = TransportSavedData.CODEC.encodeStart(JsonOps.INSTANCE, counted).getOrThrow();
        var countedReload = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, countedJson).getOrThrow();
        require(countedReload.traffic().get("traffic-1") == 7, "counts survive reload");
        JsonObject withoutTraffic = countedJson.getAsJsonObject().deepCopy();
        withoutTraffic.remove("traffic");
        require(TransportSavedData.CODEC.parse(JsonOps.INSTANCE, withoutTraffic).getOrThrow()
                .traffic().isEmpty(), "an old save loads without invented counts");
        JsonObject orphaned = countedJson.getAsJsonObject().deepCopy();
        orphaned.getAsJsonObject("traffic").addProperty("gone-1", 9);
        var orphanedReload = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, orphaned).getOrThrow();
        require(!orphanedReload.traffic().containsKey("gone-1"),
                "a counter with no matching plan is pruned on load");
        require(orphanedReload.traffic().containsKey("traffic-1"),
                "while the counter whose plan is known stays");

        var retired = new TransportSavedData();
        for (int index = 0; index < 33; index++) {
            String id = "road-" + index;
            require(retired.add(roadPlan(id, facility, 0, false)), "road " + index + " accepted");
            require(retired.replace(roadPlan(id, facility, 1, false)), "road " + index + " completed");
            retired.recordTraffic(Map.of(id, index + 1));
        }
        require(retired.plans().size() == 32, "completed roads are capped at 32");
        require(!retired.traffic().containsKey("road-0"), "a retired road's counter is pruned");
        require(retired.traffic().containsKey("road-32"), "the newest road keeps its counter");
```

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew transportSavedDataCheck --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 方法 recordTraffic(...)` / `方法 traffic()`。

- [ ] **Step 3: `TransportSavedData` 加计数、版本号与清理**

3a. 把 `CODEC` 换成四个字段（新增 `traffic`）：

```java
    static final Codec<TransportSavedData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(TransportPlan.CODEC.listOf().optionalFieldOf("plans", List.of())
                            .forGetter(data -> data.plans),
                    BlockPos.CODEC.listOf().optionalFieldOf("served_facilities", List.of())
                            .forGetter(data -> data.servedFacilities),
                    DeferredTarget.CODEC.listOf().optionalFieldOf("deferred_targets", List.of())
                            .forGetter(data -> data.deferredTargets),
                    Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("traffic", Map.of())
                            .forGetter(data -> data.traffic))
                    .apply(instance, TransportSavedData::new));
```

（若 `Map.of()` 的类型推断报错，写成 `Map.<String, Integer>of()`。）

3b. 字段区加两个字段（`deferredTargets` 之后）：

```java
    private Map<String, Integer> traffic;
    private int revision;
```

3c. 两个构造器换成：

```java
    public TransportSavedData() {
        this(List.of(), List.of(), List.of(), Map.of());
    }

    private TransportSavedData(List<TransportPlan> plans, List<BlockPos> servedFacilities,
                               List<DeferredTarget> deferredTargets, Map<String, Integer> traffic) {
        this.plans = List.copyOf(plans);
        this.servedFacilities = List.copyOf(servedFacilities);
        this.deferredTargets = List.copyOf(deferredTargets);
        this.traffic = Map.copyOf(traffic);
        for (TransportPlan plan : this.plans) {
            index(plan);
        }
        pruneTraffic();
    }
```

3d. 在 `plan(String id)` 之后加：

```java
    /** Sample hits per road plan id. Only finished roads are sampled; see TrafficSampler. */
    public Map<String, Integer> traffic() {
        return traffic;
    }

    /** Bumped whenever the plan list changes, so a derived index can tell when it has gone stale. */
    public int revision() {
        return revision;
    }

    /** Add one sample round's hits: one per resident seen standing on that road. */
    public void recordTraffic(Map<String, Integer> hits) {
        if (hits.isEmpty()) {
            return;
        }
        var updated = new HashMap<>(traffic);
        boolean changed = false;
        for (var entry : hits.entrySet()) {
            Integer count = entry.getValue();
            if (count == null || count <= 0) {
                continue;
            }
            updated.merge(entry.getKey(), count, Integer::sum);
            changed = true;
        }
        if (changed) {
            traffic = Map.copyOf(updated);
            setDirty();
        }
    }

    /**
     * Drop counters whose road is gone. The map is persisted and keyed by plan id, so without this it
     * would grow without bound as MAX_COMPLETED_ROADS retires the oldest finished roads.
     */
    private void pruneTraffic() {
        if (traffic.isEmpty()) {
            return;
        }
        var retained = new HashMap<String, Integer>();
        for (var entry : traffic.entrySet()) {
            if (plan(entry.getKey()).isPresent()) {
                retained.put(entry.getKey(), entry.getValue());
            }
        }
        if (retained.size() != traffic.size()) {
            traffic = Map.copyOf(retained);
            setDirty();
        }
    }
```

3e. `add(TransportPlan plan)` 里在 `plans = List.copyOf(updated);` 之后、`index(plan);` 之前加一行：

```java
        revision++;
```

3f. `replace(TransportPlan replacement)` 里把

```java
                plans = List.copyOf(updated);
                if (!old.isComplete() && replacement.isComplete()
```

改成

```java
                plans = List.copyOf(updated);
                revision++;
                pruneTraffic();
                if (!old.isComplete() && replacement.isComplete()
```

- [ ] **Step 4: 跑计数检查**

Run: `./gradlew transportSavedDataCheck --offline --no-daemon`

Expected: `TransportSavedDataCheck passed`。

- [ ] **Step 5: 新增采样器**

创建 `src/main/java/dev/local/goblinsettlement/construction/transport/TrafficSampler.java`：

```java
package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.colony.ResidentWorkLookup;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Counts how often residents walk the settlement's finished roads. The counts are what the widening
 * round will set its thresholds from, so this only measures and persists: it changes no block.
 */
public final class TrafficSampler {
    /**
     * Ticks between samples. INVENTED, and it is half of the judgement: RoadUpgradeRules compares
     * sample hits against a threshold, so changing this changes what that threshold means. Re-choose
     * both together (TRAFFIC_UPGRADE_DESIGN section 2).
     */
    private static final int SAMPLE_INTERVAL_TICKS = 100;

    private static final WeakHashMap<ServerLevel, Snapshot> SNAPSHOTS = new WeakHashMap<>();

    /** Road surface blocks for one owner and one revision of the plan list. */
    private record Snapshot(TransportSavedData owner, int revision, Map<BlockPos, String> roadSurface) {
    }

    private TrafficSampler() {
    }

    /** Register on END_WORLD_TICK, inside the settlement gate. Never loads a chunk. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % SAMPLE_INTERVAL_TICKS != 0) {
            return;
        }
        TransportSavedData traffic = TransportSavedData.get(level);
        Map<BlockPos, String> roadSurface = surfaceIndex(traffic);
        if (roadSurface.isEmpty()) {
            return;
        }
        var hits = new HashMap<String, Integer>();
        for (GoblinCitizenEntity goblin
                : ResidentWorkLookup.loaded(level, SettlementSavedData.get(level))) {
            String planId = roadSurface.get(goblin.blockPosition().below());
            if (planId != null) {
                hits.merge(planId, 1, Integer::sum);
            }
        }
        traffic.recordTraffic(hits);
    }

    /**
     * The SURFACE cell of every finished road, keyed by the block a walker stands on. Rebuilt only when
     * the plan list changes; the owner check also covers a reload, which hands out a new instance whose
     * revision counter has started over.
     */
    private static Map<BlockPos, String> surfaceIndex(TransportSavedData traffic) {
        Snapshot snapshot = SNAPSHOTS.get(traffic);
        if (snapshot != null && snapshot.owner() == traffic && snapshot.revision() == traffic.revision()) {
            return snapshot.roadSurface();
        }
        var index = new HashMap<BlockPos, String>();
        for (TransportPlan plan : traffic.plans()) {
            if (plan.kind() != TransportPlan.Kind.ROAD || !plan.isComplete()) {
                continue;
            }
            for (TransportPlan.Step step : plan.steps()) {
                if (step.phase() == TransportPlan.Phase.SURFACE) {
                    index.put(step.site(), plan.id());
                }
            }
        }
        var rebuilt = new Snapshot(traffic, traffic.revision(), Map.copyOf(index));
        SNAPSHOTS.put(traffic, rebuilt);
        return rebuilt.roadSurface();
    }
}
```

**注意 `SNAPSHOTS` 的键是 `TransportSavedData`，不是 `ServerLevel`**：一个维度只有一份 `TransportSavedData`，而它在重载时会换新实例；用 `ServerLevel` 作键会在"同一进程内重新加载维度"时命中上一份的旧快照。`WeakHashMap` 让实例被回收时条目自动消失。

- [ ] **Step 6: 接采样节拍**

在 `GoblinSettlement.tickSettlement` 里，`TransportCoordinator.tick(level);` 之后加一行：

```java
        TransportCoordinator.tick(level);
        TrafficSampler.tick(level);
```

并确认 `GoblinSettlement.java` 已有 `import dev.local.goblinsettlement.construction.transport.TrafficSampler;`（没有就加，按既有 import 的字母序放在 `TransportCoordinator` 之前）。

- [ ] **Step 7: 加显示行**

7a. 在 `TransportCommands.java` 的 `showStatus` 之后加（只需补 `import dev.local.goblinsettlement.planning.transport.RoadUpgradeRules;`；`ArrayList` 在代码里写全名，不再多一个 import）：

```java
    private static final int STATUS_ROAD_LIMIT = 8;

    /**
     * Every finished road's measured traffic, busiest first, with a star on the ones that have earned a
     * widening. This is the round's deliverable: the thresholds cannot be chosen until these numbers
     * exist, so they are read off the status command.
     */
    public static String trafficLine(TransportSavedData traffic) {
        var roads = new java.util.ArrayList<TransportPlan>();
        for (TransportPlan plan : traffic.plans()) {
            if (plan.kind() == TransportPlan.Kind.ROAD && plan.isComplete()) {
                roads.add(plan);
            }
        }
        if (roads.isEmpty()) {
            return "Road traffic: no completed roads";
        }
        roads.sort((left, right) -> {
            int byCount = Integer.compare(traffic.traffic().getOrDefault(right.id(), 0),
                    traffic.traffic().getOrDefault(left.id(), 0));
            return byCount != 0 ? byCount : left.id().compareTo(right.id());
        });
        long ready = roads.stream().filter(plan -> qualifies(traffic, plan)).count();
        var builder = new StringBuilder("Road traffic: ").append(roads.size())
                .append(" road(s), ").append(ready).append(" ready to widen (*); ");
        int shown = Math.min(STATUS_ROAD_LIMIT, roads.size());
        for (int index = 0; index < shown; index++) {
            TransportPlan plan = roads.get(index);
            int count = traffic.traffic().getOrDefault(plan.id(), 0);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(shortId(plan.id())).append('=').append(count);
            if (qualifies(traffic, plan)) {
                builder.append('*');
            }
        }
        if (roads.size() > shown) {
            builder.append(", +").append(roads.size() - shown).append(" more");
        }
        return builder.toString();
    }

    private static boolean qualifies(TransportSavedData traffic, TransportPlan plan) {
        return RoadUpgradeRules.shouldUpgrade(RoadUpgradeRules.BUILT_ROAD_LANES,
                traffic.traffic().getOrDefault(plan.id(), 0));
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
```

7b. 在 `GoblinSettlement` 的 `status` 子命令里，"Next traffic target" 那条 `sendSuccess` **之后**加：

```java
                                context.getSource().sendSuccess(() -> Component.literal(
                                        TransportCommands.trafficLine(TransportSavedData.get(level))), false);
```

（`TransportCommands` 与 `TransportSavedData` 都已在 `GoblinSettlement` 里被引用；`TransportCommands` 只在第 78 行以 `TransportCommands.register` 出现，已在 import 内。）

- [ ] **Step 8: 在道路规划处留下常量的指针**

在 `TransportCoordinator.startRoad` 的

```java
            for (BlockPos lane : List.of(foot, foot.relative(side))) {
```

**之前**加一行注释（不改代码）：

```java
            // Two lanes, which is what RoadUpgradeRules.BUILT_ROAD_LANES describes.
```

不把常量搬进这个循环：加宽轮次会重写这段取车道的方式，届时由它来接管这个数。

- [ ] **Step 9: 跑完整构建与全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**15 项**检查全部 `*Check passed`，无编译警告。

- [ ] **Step 10: 核对接线**

Run: `grep -rn "recordTraffic\|trafficLine\|TrafficSampler\|pruneTraffic\|shouldUpgrade\|SAMPLE_INTERVAL_TICKS" src/`

Expected:
- `recordTraffic`：`TransportSavedData` 一处定义、`TrafficSampler` 一处调用；
- `trafficLine`：`TransportCommands` 一处定义、`GoblinSettlement` 一处调用；
- `TrafficSampler`：一处定义、`GoblinSettlement` 一处 `tick` 调用；
- `pruneTraffic`：一处定义、构造器与 `replace` 各一处调用；
- `shouldUpgrade`：`RoadUpgradeRules` 一处定义、检查与 `TransportCommands` 两处调用；
- `SAMPLE_INTERVAL_TICKS`：一处定义、`tick` 一处使用。

- [ ] **Step 11: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportSavedData.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/transport/TransportSavedDataCheck.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TrafficSampler.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Measure road traffic and show which roads have earned a widening"
```

---

### Task 3: 文档与收尾

**Files:**
- Modify: `goblin-settlement-plan/TRAFFIC_UPGRADE_DESIGN.md`
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1/2 的改动与构建结果（**含 `build/libs/goblin-settlement-0.1.0.jar` 的实际字节数与构建秒数**）
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：**先单独提交它们**并署名，**然后再**做本任务的文档改动。

- [ ] **Step 2: 设计文档记录落地结果**

在 `TRAFFIC_UPGRADE_DESIGN.md` 末尾追加一节，写实际落地的形状（**逐字记录代码里的常量与公式，不要概括**）：

```markdown
## 9. 第一轮落地结果（实现后补记）

- `planning/transport/RoadUpgradeRules`：`LADDER = {2, 3, 5}`；`nextLanes` 取梯子上第一个大于当前宽度的档，**没有更大的档时返回 5**（所以 1 → 2、4 → 5、9 → 5）；`shouldUpgrade(lanes, traffic)` 为 `lanes <= 0` 或 `lanes >= 5` 时为假，否则 `traffic >= TRAFFIC_PER_LANE * max(1, lanes - 1)`——"至少一整份"这个下限让 1 车道的路也要 200 次命中，而不是 0 次。
- **两个发明值**：`TRAFFIC_PER_LANE = 200`、`TrafficSampler.SAMPLE_INTERVAL_TICKS = 100`（5 秒一采样）。两者互相引用并写明**没有依据、必须在看过真实计数后一起重定**——命中数是按次累计的，所以采样节拍本身就是判据的一半。
- `BUILT_ROAD_LANES = 2` 是"全部建成道路当前宽度"的唯一出处；`TransportCoordinator.startRoad` 的取车道循环只加了一行指向它的注释。加宽轮次会把这个常量换成按路实测的宽度。
- 计数键是计划 id、值是**命中次数**（一次采样里一名居民站在该路的一格上记 1 次）。`TransportSavedData` 用 `optionalFieldOf("traffic", Map.of())` 落盘，**未升 schema 版本**；`pruneTraffic` 在构造（读档）与 `replace`（含 `MAX_COMPLETED_ROADS` 淘汰）两处清掉没有对应计划的键。
- **计数是累计量、不按经过的采样数归一**：一条路建得越早、存在越久，它的数就越大。第二轮定阈值时必须把这一点算进去（要么接受"先建的先加宽"，要么改成按单位时间归一）。
- 显示：`goblinsettlement status` 多一行 `Road traffic: …`，按命中数降序、并列按 id 升序，最多列 8 条，达加宽条件的带 `*`。
- **本轮不加宽**：路会被判为"够格"但宽度不变。这是与用户约定好的中间态，**不要读成功能已完整**。
```

并把 §7「验证」里的「届时 **15 项**」改为已达成（`（已达成：15 项）`），把 §4 末尾"具体形状实现时定死并写进检查"改为**已定死**并指向 §9。

- [ ] **Step 3: 追加 UpdateLog**

在 `UpdateLog.md` **末尾**追加（`<开始>`/`<结束>`/`<时间>` 换成实际操作时刻，**逐条写真实时间，不要留占位**——第四十八、五十轮各漏填过一次）：

```markdown
## [<开始> – <结束>] 第五十四轮：通行量采样（量）

- [<时间>] 按 TRAFFIC_UPGRADE_DESIGN.md 与 TRAFFIC_UPGRADE_PLAN.md 执行"先量再决定"的第一轮。本轮**只量、只存、只显示**，不加宽任何道路。
- [<时间>] 新增纯判据 `planning/transport/RoadUpgradeRules`：档位梯子 2/3/5，`nextLanes` 对梯子上没有的宽度取上一档（1→2、4→5、9→5），`shouldUpgrade` 满足"满档永假、越宽要得越多、至少一整份"三条。GAME_DESIGN 第 7 节只给宽度不给数字，因此 `TRAFFIC_PER_LANE = 200` 是**发明值**，源码注释写明没有依据、须按实测重定。
- [<时间>] 新增第 15 项独立检查 `RoadUpgradeRulesCheck`：档位推进、到顶不再变、阈值边界（刚好到 / 差一）、越宽要求越多、满档与非法宽度永不升级、`BUILT_ROAD_LANES` 在梯子上。
- [<时间>] `TransportSavedData` 加持久映射"计划 id → 命中次数"（`Codec.unboundedMap`，`optionalFieldOf`，**不升 schema 版本**）、加 `revision` 版本号（`add`/`replace` 各一处自增）、加 `pruneTraffic`（构造与 `replace` 两处清理）。落在 `replace` 是因为 `MAX_COMPLETED_ROADS` 的淘汰正在那里发生——不清理的话映射会随计划增删无界增长（设计 §3 点名）。
- [<时间>] 新增 `construction/transport/TrafficSampler`：每 `SAMPLE_INTERVAL_TICKS = 100`（5 秒）遍历已加载居民（上限 64），取脚下方块在"铺面 → 计划 id"表里查，命中即给那条路记 1 次。表由**已完成 ROAD 计划的 SURFACE 步**织出，按 `(instance, revision)` 缓存——键用 `TransportSavedData` 而非 `ServerLevel`，因为同一进程内重新加载维度会换一个新实例而 `revision` 从 0 重来。
- [<时间>] **采样节拍是判据的一半**：命中数按次累计，所以调 `SAMPLE_INTERVAL_TICKS` 就改变了阈值的含义。两个常量在注释里互相引用。设计 §2 的要求。
- [<时间>] 显示：`goblinsettlement status` 加一行 `Road traffic: n road(s), k ready to widen (*); <id8>=<count>[ *]…`（命中数降序、并列按 id、最多 8 条、其余折叠为 `+N more`）。设计 §6 的理由是"本轮价值一半在把数据摊开"——否则调阈值仍是盲调。
- [<时间>] `TransportCoordinator.startRoad` 的取车道循环**一行未改**，只加了一行指向 `RoadUpgradeRules.BUILT_ROAD_LANES` 的注释：不把常量搬进循环，因为加宽轮次会重写这段。
- [<时间>] **近似而非精确流量**（设计 §8）：站岗、避难、打架的居民也会被计到；本轮不按 `workStage` 过滤，不加字段。计数**不按经过的采样数归一**，建得早的路数更大——第二轮定阈值必须把这一点算进去。
- [<时间>] 验证：完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**15 项**独立检查全部 *Check passed（新增第 15 项 `RoadUpgradeRulesCheck`）。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>，耗时 <秒数>。
- [<时间>] 未完成：不做玩法验收；**采样、计数持久化、显示三样都不可纯测**（只有编译与代码审查）；阈值与节拍都是发明值；加宽本身留下一轮，本轮结束后路会被判为"够格"但宽度不变。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「接续须知」的**下一步落点**整段改写：第一轮（量）**已完成**并已提交，`TRAFFIC_UPGRADE_DESIGN.md` §9 记了落地的常量与公式；**下一轮是加宽（2 → 3 → 5）**，且动手前要先拿真实计数把 `TRAFFIC_PER_LANE` 与 `SAMPLE_INTERVAL_TICKS` 重定；加宽要处理设计 §5 的三个硬点（步列表游标错位、从步列表推不出路线方向、加宽后旧车道样本算谁的）。删掉"实现计划尚未写"这句。
- 「阶段定位」阶段 5 一句补上"第五十四轮把通行量量了出来（纯判据 + 采样 + 持久计数 + status 显示），未加宽"。
- 「本轮接入的内容」新增一节「第五十四轮：通行量采样（量）」，写：`RoadUpgradeRules` 的两条规则与两个发明常量的来由、第 15 项检查、`TransportSavedData` 的 traffic/revision/pruneTraffic、`TrafficSampler` 的节拍与缓存键、status 那行、以及"只量不加宽"的中间态。
- 「交通剩余」第 1 项改写：**第一轮已完成**（写明现成落点与两个待重定的常量），**第二轮（加宽）待做**并列出设计 §5 的三个硬点；其余三项（成熟期多工程并行、道路连通性验收、石桥与更长跨度）不动。
- 「本轮验证进展」替换为第五十四轮：完整构建、15 项检查、产物字节数与耗时；写明本轮**新增**了第 15 项检查（不是改写）；并把"独立检查覆盖不到"的清单替换成本轮的（`TrafficSampler.tick` 的采样与表缓存、`pruneTraffic` 在真实存档淘汰路径上的表现、status 那行的可读性、以及累计计数不归一对阈值选择的影响）。
- 别处提到"14 项"的地方（若有）一并改为 15 项。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the traffic measurement round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。若网络不通，本机需要代理（`git -c http.proxy=http://127.0.0.1:7890 push origin main`）。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 采样（每 N tick 遍历已加载居民、取脚下方块、查表、缓存 + 版本号）→ Task 2 Step 5/6；"节拍是判据的一部分" → Step 5 的 `SAMPLE_INTERVAL_TICKS` 注释与 Global Constraints。
- 设计 §3 计数持久化（`Codec.unboundedMap`、计划 id → 次数、`optionalFieldOf`、不升版本、键的生命周期清理）→ Task 2 Step 3a/3d/3f，清理的行为由 Step 1 的三条断言钉死（读档剪枝、淘汰剪枝、旧档不发明计数）。
- 设计 §4 判据（`nextLanes` 2→3→5 到顶不再变 + `shouldUpgrade` 单调、满档永假、阈值是发明值）→ Task 1 全文。
- 设计 §6 显示（`goblinsettlement status` 一行，各条路的计数与是否够格）→ Task 2 Step 7。
- 设计 §7 验证（新增 `RoadUpgradeRulesCheck`、完整构建、**15 项**、三样不可纯测要写进日志）→ Task 1 Step 5、Task 2 Step 9、Task 3 Step 3 的"未完成"条。
- 设计 §8 风险（阈值发明值、采样成本、脚下是铺面 ≠ 在走路、映射无界增长、两轮之间状态不对称）→ 前四条分别落在 Task 1 常量注释、Task 2 Step 5 的节拍注释与"不按 `workStage` 过滤"、Step 3d/3f 的 `pruneTraffic`；第五条落在 Task 3 Step 2/3/4 的中间态声明。
- 设计 §5（加宽留下一轮的两个硬点）→ **不需要任务**，它是下一轮的设计输入；本轮只把"下一轮要从哪里开始"写进 Task 3 Step 4。

**2. 占位符扫描**

- 无 TBD/TODO。三个新文件、`TransportSavedData` 全部改动、显示与接线都给了可粘贴全文。
- Task 2 Step 3 是按位置插入的一组精确改动，每处都给了锚点（"在 X 之后加"）。
- Task 2 Step 3a 的 `Map.<String, Integer>of()` 是**条件性备选**，触发条件明写（类型推断报错）。
- Task 3 Step 3 的 `<开始>`/`<结束>`/`<时间>`/`<字节数>`/`<秒数>` 是模板占位，正文已要求逐条替换为真实值——**第四十八、五十轮各漏填过一次**。

**3. 类型一致性**

- `RoadUpgradeRules.shouldUpgrade(int, int) -> boolean` 在 Task 1 的定义、Task 1 的检查、Task 2 Step 7a 的 `qualifies` 三处一致；`BUILT_ROAD_LANES` 是 `int`，两处调用都直接传。
- `TransportSavedData.traffic()` 返回 `Map<String, Integer>`，`getOrDefault(String, int)` 在 `trafficLine` 里用的是 `Integer` 装箱后的默认值 `0`，与 `Map.of()` 的静态类型一致。
- `recordTraffic(Map<String, Integer>)`：`TrafficSampler` 传的是 `HashMap<String, Integer>`，`TransportSavedDataCheck` 传的是 `Map.of(...)`，都满足签名。
- `Snapshot` 在 Task 2 Step 5 里键是 `TransportSavedData`（不是 `ServerLevel`），`SNAPSHOTS` 的泛型声明与 `put`/`get` 的实参一致；这与 `BedCensus` 用 `ServerLevel` 作键的做法**故意不同**，理由写在紧邻的实现说明里。
- `TransportCommands.trafficLine(TransportSavedData)` 是 `public static`，在 `GoblinSettlement` 里以 `TransportCommands.trafficLine(TransportSavedData.get(level))` 调用，实参类型一致。
- `TransportSavedData` 的私有构造器由 3 参变 4 参后，`SavedDataType<>(..., TransportSavedData::new, ...)` 仍指向无参构造器（未改），`TransportSavedDataCheck` 与 `TransportCoordinator` 用的都是无参构造器（未改）。
- `revision()` 的调用方只有 `TrafficSampler`（读）与 `TransportSavedData` 自己（写），`pruneTraffic` 在构造器中被调用时 `plans` 已完成赋值、`plan(String)` 可用。
