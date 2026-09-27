# 哥布林交通自主立项 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让聚落自己决定该给哪个设施修路、该不该架桥，并调用既有的立项入口开工。

**Architecture:** 施工侧已完整，本计划只新增一层自主提案。新建一个与 `TransportCoordinator` 同包的协调器，以 `SettlementDemand` 新增的 `TRANSPORT` 档为经济闸，用一份持久化的"已服务设施"登记决定该连谁，用一次有界的直线地形探测在"修路"与"架桥"之间二选一，然后调用既有的 `startRoad` / `startWoodBridge`。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [TRAFFIC_DESIGN.md](TRAFFIC_DESIGN.md)（缩写为 §N 的引用均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。禁止引入其他 Minecraft 版本的 API 或示例。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **不做游戏内验证**：按用户约定，初版代码全部完成后才统一测试。本轮只做编译 + 独立检查。
- **纯函数不得依赖 Minecraft 类型**（`Level`、`BlockPos`、`Item` 等一律不得出现在纯规则类里）。碰 `ServerLevel` 的代码单独放在适配层。
- **协调器只立项、不改世界**：世界改动一律经既有 `TransportCoordinator` 路径（它已有权限入口、禁行、缺料停工、结构复检等不变量）。新协调器内不得出现 `setBlock` 或任何方块写入。
- **不新增 `WorkKind`**：施工派工沿用既有的 `WorkKind.TRANSPORT`。
- 存档兼容：新字段一律用 `optionalFieldOf`，不提升任何 schema 版本。
- 代码注释用英文（与现有全部源码一致）。只在违反直觉处写注释。
- 不修改 `ai-chat-mod`，不动常用存档。

---

# 哥布林交通自主立项：实现方案

## 0. 事实核验结论

你给的事实全部属实，补充如下关键确认（方案依赖这些细节）：

- `TransportSavedData.add()` 硬门禁在 `TransportSavedData.java:87`（`!incompletePlans.isEmpty()` 或 id 重复即拒）。
- `TransportSavedData` **没有 schema 版本字段**（`TransportSavedData.java:22-23` 的 `SavedDataType` 迁移 codec 为 `null`），因此"不提升 schema 版本"是天然成立的——新增字段用 `optionalFieldOf` 即可。`SettlementSavedData.SCHEMA_VERSION = 1` 完全不受影响。
- `TransportPlan` 的构造点全工程共 6 处：`TransportCoordinator.startRoad`（:86-88）、`startWoodBridge`（:171-173）、`TransportPlan.advance/rewind/withOpen/withWorker`（:105/:113/:118/:123）。**`advance/rewind/withOpen/withWorker` 重建 record 时必须透传新字段**，否则完工瞬间 `replace()` 会看到空目标——这是本方案最易漏的 bug 点。
- 道路/桥梁的完工转移**全部**经过 `TransportSavedData.replace()`（`TransportCoordinator.java:238-239` 桥开放、`:270-273` 已放置补进、`:333` 居民放置），所以"完工登记"只需挂在 `replace()` 一个咽喉点。
- `Priority` 消费者只有 4 个调用点 + `SettlementDemandCheck`，没有 `switch` 消费，插档安全。
- `RoadPlanner` A* 对已完成桥面（橡木板，sturdy 且不在危险/粗糙列表）判为 NORMAL，`TransportCoordinator.buildableGround`（:397-404）含 `OAK_PLANKS`——**道路可以铺过已完工的桥面**。这支撑了下文"桥完工 → 级联修路 → 道路完工才登记"的设计。
- 检查的既有写法（`SettlementSavedDataCheck`）证明：无 `ServerLevel` 也能构造 `SettlementSavedData`（`found/registerWarehouse/registerFarmSite` 都是纯存档操作）与 `TransportSavedData`（`add/replace`），共享查询方法可端到端写进独立检查。

---

## 1. 新增/修改文件清单

**新增（主源码 4 个）**

| 文件 | 内容 |
|---|---|
| `src/main/java/dev/local/goblinsettlement/planning/transport/TrafficTargetRules.java` | 纯函数：最近未服务设施（无 Minecraft 类型） |
| `src/main/java/dev/local/goblinsettlement/planning/transport/TrafficDecision.java` | 纯函数：修路/架桥判定（无 Minecraft 类型） |
| `src/main/java/dev/local/goblinsettlement/planning/transport/StraightLineProbe.java` | 有界直线地形采样适配器（读 `ServerLevel`） |
| `src/main/java/dev/local/goblinsettlement/construction/transport/TrafficProposalCoordinator.java` | 自主提案协调器 + 共享查询 |

**新增（检查 3 个，注册进 `check` 聚合）**

| 文件 | 对应 JavaExec 任务 |
|---|---|
| `src/test/java/dev/local/goblinsettlement/planning/transport/TrafficTargetRulesCheck.java` | `trafficTargetRulesCheck` |
| `src/test/java/dev/local/goblinsettlement/planning/transport/TrafficDecisionCheck.java` | `trafficDecisionCheck` |
| `src/test/java/dev/local/goblinsettlement/construction/transport/TransportSavedDataCheck.java` | `transportSavedDataCheck`（同包以便访问 Codec） |

**修改（8 个主源码 + 2 个测试 + build.gradle）**：`TransportPlan`、`TransportSavedData`、`TransportCoordinator`、`SettlementDemand`、`ExpansionCoordinator`、`FoodCraftingCoordinator`、`FarmingCoordinator`、`GoblinSettlement`、`TransportCommands`、`PublicWarehouseInventory`、`SettlementDemandCheck`、`build.gradle`。

**包位置确认**：协调器放 `construction/transport/`（同意设计文档：与 `TransportCoordinator` 同包，直接调 `startRoad/startWoodBridge`；先例 `farming/FarmDiscoveryCoordinator` 住领域包）。两个纯函数与探测适配器放**新包 `planning/transport/`**——按 TECH_DESIGN 第 2 节，"地形分析、只提出方案"属 `planning` 职责（`RoadPlanner/BridgePlanner` 已在此）；`construction → planning` 的调用方向在 `TransportCoordinator → RoadPlanner/BridgePlanner` 已有先例。备选是把纯函数也放 `construction/transport/`，代价是让 construction 包承担地形规则，不推荐。

---

## 2. 分步实施（每步含代码与验证命令）

验证命令全部在 `D:/MC/.minecraft/versions/1.21.11-Fabric 0.19.2/goblin-settlement-mod` 下执行。按项目约定"先写检查（红）→ 实现（绿）"：每步先建检查文件与 gradle 任务，运行得到编译失败/断言失败，再实现。

### Task 1: 数据层——`target_facility`、已服务登记、共享查询骨架

#### 1a. `TransportPlan.java` 加可选字段

record 头（:11-15）末尾追加：

```java
public record TransportPlan(
        String id, String settlementId, Kind kind, List<Step> steps,
        int completedSteps, boolean open, List<BlockPos> barrierFeet,
        List<BlockPos> closedFootprint, List<BlockPos> foundationBases,
        Optional<String> workerId, Optional<BlockPos> targetFacility) {
```

CODEC（:56-67）最后加一组：

```java
            BlockPos.CODEC.optionalFieldOf("target_facility").forGetter(TransportPlan::targetFacility)
```

紧凑构造器（:69-91）加防御性不可变处理（Codec 不会传 null，防御即可）：

```java
        targetFacility = (targetFacility == null ? Optional.<BlockPos>empty() : targetFacility)
                .map(BlockPos::immutable);
```

四个重建方法（:105/:113/:118/:123）**构造函数末尾追加 `, targetFacility`**（透传，这是完工登记能否命中的关键）。

`TransportCoordinator` 两处构造调用同步改：

- `startRoad`（:86-88）：最后参数 `Optional.empty()` → `Optional.of(targetFacility)`（方法参数已 `requireNonNull`）。
- `startWoodBridge`（:171-173）：保持 `Optional.empty()`（桥不标记已服务，理由见 §4）。

#### 1b. `TransportSavedData.java` 加已服务集合

- CODEC（:19-21）改为两组（同时把 `private static final` 降为包可见，与 `SettlementSavedData.CODEC`（SettlementSavedData.java:66）一致，供检查访问）：

```java
    static final Codec<TransportSavedData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(TransportPlan.CODEC.listOf().optionalFieldOf("plans", List.of())
                            .forGetter(data -> data.plans),
                    BlockPos.CODEC.listOf().optionalFieldOf("served_facilities", List.of())
                            .forGetter(data -> data.servedFacilities))
                    .apply(instance, TransportSavedData::new));
```

- 字段区（:25-29）加 `private List<BlockPos> servedFacilities;`；无参构造（:31-33）改 `this(List.of(), List.of());`；私有构造（:35-40）改签名 `(List<TransportPlan> plans, List<BlockPos> servedFacilities)` 并 `this.servedFacilities = List.copyOf(servedFacilities);`。
- 新方法：

```java
    public List<BlockPos> servedFacilities() {
        return servedFacilities;
    }

    /** True while any plan is unfinished; the proposal and expansion gates share this. */
    public boolean hasIncomplete() {
        return !incompletePlans.isEmpty();
    }

    private void registerServed(BlockPos facility) {
        BlockPos immutable = facility.immutable();
        if (servedFacilities.contains(immutable)) {
            return;
        }
        var updated = new ArrayList<>(servedFacilities);
        updated.add(immutable);
        servedFacilities = List.copyOf(updated);
    }
```

（`incompletePlans` 是持久索引，构造时由 `index()` 重建、`add/replace` 维护，`hasIncomplete()` 正确。）

- `replace()`（:98-115）在 `setDirty()` 之前插入完工登记咽喉：

```java
        if (!old.isComplete() && replacement.isComplete()
                && replacement.targetFacility().isPresent()) {
            registerServed(replacement.targetFacility().orElseThrow());
        }
```

#### 1c. 纯函数 `TrafficTargetRules.java`（新文件，无 Minecraft 类型）

```java
package dev.local.goblinsettlement.planning.transport;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure facility selection: the nearest unserved facility beyond a minimum distance. */
public final class TrafficTargetRules {
    /** A facility position with no Minecraft dependency; y participates only in identity. */
    public record Facility(int x, int y, int z) {
    }

    private TrafficTargetRules() {
    }

    /** Nearest unserved facility with horizontal squared distance strictly above the floor.
     *  Ties break by x, then z, then y. Empty when no candidate qualifies. */
    public static Optional<Facility> nearestBeyond(Facility anchor, List<Facility> facilities,
                                                   List<Facility> served, long minDistanceSquared) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(facilities, "facilities");
        Objects.requireNonNull(served, "served");
        if (minDistanceSquared < 0) {
            throw new IllegalArgumentException("Minimum distance cannot be negative");
        }
        Facility best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Facility facility : facilities) {
            if (served.contains(facility)) {
                continue;
            }
            long dx = (long) facility.x() - anchor.x();
            long dz = (long) facility.z() - anchor.z();
            long distance = dx * dx + dz * dz;
            if (distance <= minDistanceSquared) {
                continue;
            }
            if (best == null || distance < bestDistance
                    || (distance == bestDistance && precedes(facility, best))) {
                best = facility;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean precedes(Facility left, Facility right) {
        return left.x() != right.x() ? left.x() < right.x()
                : left.z() != right.z() ? left.z() < right.z()
                : left.y() < right.y();
    }
}
```

#### 1d. `TrafficProposalCoordinator.java` 骨架（新文件）——本步只写共享查询两个静态方法，`tick` 在步骤 4 补

```java
package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.planning.transport.TrafficTargetRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Autonomous traffic proposals; see tick(). */
public final class TrafficProposalCoordinator {
    private static final long MIN_TARGET_DISTANCE_SQ = 12L * 12L;

    private TrafficProposalCoordinator() {
    }

    /** Shared by status display, expansion, and this coordinator: saved data only, no world probe. */
    public static boolean hasPendingTarget(SettlementSavedData settlement, TransportSavedData traffic) {
        return nearestUnservedFacility(settlement, traffic).isPresent();
    }

    public static Optional<BlockPos> nearestUnservedFacility(SettlementSavedData settlement,
                                                             TransportSavedData traffic) {
        var founded = settlement.settlement();
        if (founded.isEmpty()) {
            return Optional.empty();
        }
        BlockPos anchor = founded.orElseThrow().anchor();
        List<TrafficTargetRules.Facility> facilities = new ArrayList<>();
        facilities.add(new TrafficTargetRules.Facility(anchor.getX(), anchor.getY(), anchor.getZ()));
        for (BlockPos warehouse : settlement.warehouses()) {
            facilities.add(new TrafficTargetRules.Facility(warehouse.getX(), warehouse.getY(), warehouse.getZ()));
        }
        for (var site : settlement.farmSites()) {
            BlockPos crop = site.cropPos();
            facilities.add(new TrafficTargetRules.Facility(crop.getX(), crop.getY(), crop.getZ()));
        }
        List<TrafficTargetRules.Facility> served = new ArrayList<>();
        for (BlockPos pos : traffic.servedFacilities()) {
            served.add(new TrafficTargetRules.Facility(pos.getX(), pos.getY(), pos.getZ()));
        }
        return TrafficTargetRules.nearestBeyond(
                new TrafficTargetRules.Facility(anchor.getX(), anchor.getY(), anchor.getZ()),
                facilities, served, MIN_TARGET_DISTANCE_SQ)
                .map(facility -> new BlockPos(facility.x(), facility.y(), facility.z()));
    }
}
```

（锚点也进候选集，但距离 0 ≤ 下限，永远不会被选中——与设计文档 2.1"三类设施"口径一致。）

#### 1e. 检查 `TransportSavedDataCheck.java` + `TrafficTargetRulesCheck.java` + gradle 任务

`TransportSavedDataCheck`（包 `dev.local.goblinsettlement.construction.transport`，覆盖：完工登记、桥不登记、advance/rewind/withWorker/withOpen 透传、Codec 往返、旧存档缺字段加载、重复完工不重复登记、共享查询在纯存档上的端到端行为）：

```java
package dev.local.goblinsettlement.construction.transport;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

public final class TransportSavedDataCheck {
    public static void main(String[] args) {
        BlockPos facility = new BlockPos(30, 64, 10);
        TransportPlan road = roadPlan("road-1", facility, 0, false);
        var data = new TransportSavedData();
        require(data.add(road), "road plan accepted");
        require(data.hasIncomplete(), "incomplete plan blocks new proposals");
        require(!data.add(roadPlan("road-2", facility.east(), 0, false)), "one in-flight plan at a time");
        require(data.servedFacilities().isEmpty(), "nothing served before completion");
        require(data.replace(road.advance()), "final step completes the road");
        require(!data.hasIncomplete(), "completed plan leaves the active index");
        require(data.servedFacilities().equals(List.of(facility)), "road completion registers its target");

        var bridge = new TransportSavedData();
        TransportPlan completedBridge = bridgePlan("bridge-1", true);
        require(bridge.add(completedBridge), "an open bridge plan loads");
        require(bridge.replace(completedBridge), "replacing a complete plan is idempotent");
        require(bridge.servedFacilities().isEmpty(), "a targetless bridge registers nothing");

        var json = TransportSavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        var reloaded = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        require(reloaded.servedFacilities().equals(data.servedFacilities()), "served facilities survive reload");
        require(reloaded.plans().get(0).targetFacility().equals(Optional.of(facility)),
                "target facility survives reload");
        JsonObject legacy = json.getAsJsonObject().deepCopy();
        legacy.remove("served_facilities");
        legacy.getAsJsonArray("plans").get(0).getAsJsonObject().remove("target_facility");
        var old = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, legacy).getOrThrow();
        require(old.servedFacilities().isEmpty(), "old saves load without invented served facilities");
        require(old.plans().get(0).targetFacility().isEmpty(), "old plans load without an invented target");

        require(road.advance().targetFacility().equals(Optional.of(facility)), "advance keeps the target");
        require(road.rewind(0).targetFacility().equals(Optional.of(facility)), "rewind keeps the target");
        require(road.withWorker(Optional.of("w")).targetFacility().equals(Optional.of(facility)),
                "worker handoff keeps the target");
        require(road.withOpen(false).targetFacility().equals(Optional.of(facility)),
                "closure updates keep the target");

        var repeat = new TransportSavedData();
        require(repeat.add(roadPlan("sel-1", facility, 0, false)), "repeat fixture accepted");
        require(repeat.replace(roadPlan("sel-1", facility, 1, false)), "repeat fixture completes");
        require(repeat.replace(roadPlan("sel-1", facility, 1, false)), "repeat completion accepted");
        require(repeat.servedFacilities().size() == 1, "repeat completion does not duplicate the entry");

        var settlement = new SettlementSavedData();
        require(settlement.found(new BlockPos(0, 70, 0)) == SettlementSavedData.FoundResult.FOUNDED,
                "query fixture founded");
        require(settlement.registerWarehouse(new BlockPos(20, 70, 0)), "query fixture warehouse");
        require(settlement.registerFarmSite(new BlockPos(15, 71, 5)), "query fixture farm");
        var fresh = new TransportSavedData();
        require(TrafficProposalCoordinator.nearestUnservedFacility(settlement, fresh)
                        .orElseThrow().equals(new BlockPos(15, 71, 5)),
                "nearest unserved facility shared query");
        var nearSettlement = new SettlementSavedData();
        require(nearSettlement.found(new BlockPos(0, 70, 0)) == SettlementSavedData.FoundResult.FOUNDED,
                "near fixture founded");
        require(nearSettlement.registerWarehouse(new BlockPos(5, 70, 0)), "near fixture warehouse");
        require(!TrafficProposalCoordinator.hasPendingTarget(nearSettlement, fresh),
                "a warehouse next to the anchor is not a paving target");
        System.out.println("TransportSavedDataCheck passed");
    }

    private static TransportPlan roadPlan(String id, BlockPos target, int completedSteps, boolean open) {
        var step = new TransportPlan.Step(TransportPlan.Phase.SURFACE,
                new BlockPos(10, 64, 10), TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.ROAD_GROUND);
        return new TransportPlan(id, "settlement-1", TransportPlan.Kind.ROAD, List.of(step),
                completedSteps, open, List.of(), List.of(), List.of(), Optional.empty(), Optional.of(target));
    }

    private static TransportPlan bridgePlan(String id, boolean open) {
        var step = new TransportPlan.Step(TransportPlan.Phase.SURFACE,
                new BlockPos(10, 64, 10), TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.AIR_OR_WATER);
        return new TransportPlan(id, "settlement-1", TransportPlan.Kind.WOOD_BRIDGE, List.of(step),
                open ? 1 : 0, open,
                List.of(new BlockPos(1, 64, 1), new BlockPos(2, 64, 1),
                        new BlockPos(3, 64, 1), new BlockPos(4, 64, 1)),
                List.of(new BlockPos(5, 65, 1)), List.of(), Optional.empty(), Optional.empty());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

`TrafficTargetRulesCheck`（包 `dev.local.goblinsettlement.planning.transport`）：

```java
package dev.local.goblinsettlement.planning.transport;

import java.util.List;

public final class TrafficTargetRulesCheck {
    public static void main(String[] args) {
        var anchor = new TrafficTargetRules.Facility(0, 70, 0);
        var near = new TrafficTargetRules.Facility(5, 70, 0);       // 25, inside the floor
        var farm = new TrafficTargetRules.Facility(15, 71, 5);      // 250
        var warehouse = new TrafficTargetRules.Facility(20, 70, 0); // 400
        long floor = 12L * 12L;
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(anchor, farm, warehouse), List.of(), floor)
                .orElseThrow().equals(farm), "the nearest qualifying facility wins");
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(near), List.of(), floor).isEmpty(),
                "facilities inside the minimum distance are not paved for");
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(farm, warehouse), List.of(farm), floor)
                .orElseThrow().equals(warehouse), "a served facility is skipped");
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(anchor), List.of(), floor).isEmpty(),
                "the settlement anchor never paves itself");
        var tied = new TrafficTargetRules.Facility(10, 70, 4);
        var tie = new TrafficTargetRules.Facility(4, 70, 10);
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(tied, tie), List.of(), floor)
                .orElseThrow().equals(tie), "equal distances break by x, then z, then y");
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(), List.of(), floor).isEmpty(),
                "no facilities means no target");
        System.out.println("TrafficTargetRulesCheck passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

`build.gradle` 注册两个 JavaExec 任务（样式复制 :64-118 的既有任务）并挂入 `check`（:120-128）：

```groovy
tasks.register('trafficTargetRulesCheck', JavaExec) {
    group = 'verification'
    description = 'Runs the standalone traffic target selection assertions.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.planning.transport.TrafficTargetRulesCheck'
}

tasks.register('transportSavedDataCheck', JavaExec) {
    group = 'verification'
    description = 'Checks traffic saved data, served facilities, and codec round trips.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.construction.transport.TransportSavedDataCheck'
}
// check 聚合内加两行 dependsOn
```

**验证**：`./gradlew transportSavedDataCheck trafficTargetRulesCheck --offline --no-daemon` → 两个 `*Check passed`；`./gradlew compileJava --offline --no-daemon` 确认主源码编译通过。

### Task 2: `SettlementDemand.TRANSPORT` + 4 调用点 + 扩地门禁

#### 2a. `SettlementDemand.java`

枚举（:7-9）：

```java
    public enum Priority {
        NO_WORKFORCE, STOCK_UNKNOWN, WAREHOUSE_REQUIRED, FOOD, SEEDS, BASIC_TOOLS, TRANSPORT, CONSTRUCTION, READY
    }
```

签名（:17）与判定（在 :40 的 BASIC_TOOLS 之后、:41 之前插入）：

```java
    public static Assessment assess(int adults, int children, WarehouseSupply supply,
                                    boolean activeConstruction, boolean trafficPending) {
        ...
        if (supply.hoes() == 0 || supply.axes() == 0 || supply.pickaxes() == 0) {
            return new Assessment(Priority.BASIC_TOOLS, foodTarget, seedTarget);
        }
        if (trafficPending) {
            return new Assessment(Priority.TRANSPORT, foodTarget, seedTarget);
        }
        return new Assessment(activeConstruction ? Priority.CONSTRUCTION : Priority.READY,
                foodTarget, seedTarget);
    }
```

#### 2b. 四个调用点逐一更新

**`ExpansionCoordinator.java`**（import 加 `TransportSavedData`、`TrafficProposalCoordinator`）：

- 门禁（:35-38）加在途交通检查（设计 3.2）：

```java
        int adults = data.adultCount();
        if (PopulationRules.expansionBudget(adults, data.claimedPlots().size()) == 0
                || data.plans().stream().anyMatch(plan -> !plan.isComplete())
                || TransportSavedData.get(level).hasIncomplete()) {
            return;
        }
```

- assess 调用（:40-41）传**真实**待办值（见 §7 对设计文档 3.1 歧义的更正）：

```java
        var demand = SettlementDemand.assess(adults, data.childCount(), supply, false,
                TrafficProposalCoordinator.hasPendingTarget(data, TransportSavedData.get(level)));
```

**`FoodCraftingCoordinator.java:71`** 与 **`FarmingCoordinator.java:35`**：第五个实参补 `false`。

**`GoblinSettlement.java`**（import 加 `TransportSavedData`、`TrafficProposalCoordinator`）：

- :94-95 的 assess 补第五个实参（真实值，状态行能看到 TRANSPORT 档）：

```java
                                var demand = SettlementDemand.assess(data.adultCount(), data.childCount(),
                                        supply, data.plans().stream().anyMatch(plan -> !plan.isComplete()),
                                        TrafficProposalCoordinator.hasPendingTarget(
                                                data, TransportSavedData.get(level)));
```

- 在 :103 的需求行之后追加一行显示（命令与协调器共用同一查询）：

```java
                                context.getSource().sendSuccess(() -> Component.literal(
                                        TrafficProposalCoordinator.nearestUnservedFacility(
                                                data, TransportSavedData.get(level))
                                                .map(pos -> "Next traffic target: " + pos.toShortString())
                                                .orElse("No pending traffic target")), false);
```

#### 2c. `SettlementDemandCheck.java`

辅助函数改 5 参（:27-30），全部 8 个 `require` 补 `false`，第 22 行直接调用补 `false`，并新增用例：

```java
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), false, true)
                == SettlementDemand.Priority.TRANSPORT, "pending traffic precedes readiness");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), true, true)
                == SettlementDemand.Priority.TRANSPORT, "pending traffic precedes construction");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 0, 1, 0, 1, true), false, true)
                == SettlementDemand.Priority.BASIC_TOOLS, "basic tools precede transport");
        require(priority(8, 0, new WarehouseSupply(15, 8, 1, 1, 1, 0, 1, true), false, true)
                == SettlementDemand.Priority.FOOD, "food precedes transport");
```

**验证**：`./gradlew settlementDemandCheck --offline --no-daemon` → `SettlementDemandCheck passed`；`./gradlew compileJava --offline --no-daemon`。

### Task 3: 修路/架桥判定纯函数

#### 3a. 检查 `TrafficDecisionCheck.java`（先写，运行红）

```java
package dev.local.goblinsettlement.planning.transport;

import java.util.ArrayList;
import java.util.List;

public final class TrafficDecisionCheck {
    private static final int MIN_SPAN = 4;
    private static final int MAX_SPAN = 12;

    public static void main(String[] args) {
        require(decide(line("L L L L L"), 4).kind() == TrafficDecision.Kind.ROAD,
                "clear land needs a road, not a bridge");
        require(decide(line("L L W W W W W W L L L"), 10).kind() == TrafficDecision.Kind.BRIDGE,
                "a six-column water gap proposes a bridge");
        var bridge = decide(line("L L W W W W W W L L L"), 10);
        require(bridge.gapStartInclusive() == 2 && bridge.gapEndInclusive() == 7,
                "gap indexes cover exactly the water columns");
        require(decide(line("L L W W W L"), 5).kind() == TrafficDecision.Kind.ROAD,
                "a three-column gap is too short for a bridge");
        require(decide(line("L W W W W L"), 5).kind() == TrafficDecision.Kind.BRIDGE,
                "the four-column lower bound still bridges");
        require(decide(line("L W W W W W W W W W W W W L"), 13).kind() == TrafficDecision.Kind.BRIDGE,
                "the twelve-column upper bound still bridges");
        require(decide(line("L W W W W W W W W W W W W W L"), 14).kind() == TrafficDecision.Kind.ROAD,
                "a thirteen-column gap exceeds the wooden bridge");
        require(decide(line("W W W L L"), 4).kind() == TrafficDecision.Kind.ROAD,
                "water at the anchor has no near bank");
        require(decide(line("L W W W W W W"), 6).kind() == TrafficDecision.Kind.ROAD,
                "a target standing in water cannot be served by the corridor");
        require(decide(line("L L B L L"), 4).kind() == TrafficDecision.Kind.ROAD,
                "an obstructed target column delegates to the road planner");
        require(decide(line("B W W W W W L"), 6).kind() == TrafficDecision.Kind.ROAD,
                "a blocked near bank cannot host a landing");
        require(decide(line("L W W W W W B L"), 7).kind() == TrafficDecision.Kind.ROAD,
                "a blocked far bank cannot host a landing");
        require(decide(line("L W W W L W W W W W W L"), 11).kind() == TrafficDecision.Kind.ROAD,
                "only the first water run decides: a narrow run does not bridge later runs");
        require(decide(line("L W W W W W L W W W W W L"), 12).kind() == TrafficDecision.Kind.BRIDGE,
                "the first bridgeable run wins when several cross the line");
        require(decide(List.of(), 0).kind() == TrafficDecision.Kind.NONE,
                "an empty sample carries no decision");
        require(decide(line("L L L"), 5).kind() == TrafficDecision.Kind.NONE,
                "an out-of-range target index carries no decision");
        System.out.println("TrafficDecisionCheck passed");
    }

    private static TrafficDecision.Decision decide(List<TrafficDecision.ColumnKind> columns, int targetIndex) {
        return TrafficDecision.decide(columns, targetIndex, MIN_SPAN, MAX_SPAN);
    }

    private static List<TrafficDecision.ColumnKind> line(String spec) {
        var columns = new ArrayList<TrafficDecision.ColumnKind>();
        for (String token : spec.split(" ")) {
            columns.add(switch (token) {
                case "L" -> TrafficDecision.ColumnKind.LAND;
                case "W" -> TrafficDecision.ColumnKind.WATER;
                case "B" -> TrafficDecision.ColumnKind.BLOCKED;
                default -> throw new IllegalArgumentException("Unknown column kind: " + token);
            });
        }
        return columns;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

#### 3b. 实现 `TrafficDecision.java`（纯函数，无 Minecraft 类型）

```java
package dev.local.goblinsettlement.planning.transport;

import java.util.List;
import java.util.Objects;

/**
 * Pure bridge-or-road rule over straight-line column facts. The first water run
 * decides: roads cannot cross any water, so a run that is not bridgeable means
 * the direct corridor is unusable and RoadPlanner's A* owns whatever detour it
 * can find.
 */
public final class TrafficDecision {
    public enum ColumnKind { LAND, WATER, BLOCKED }
    public enum Kind { NONE, ROAD, BRIDGE }

    /** For BRIDGE the gap occupies columns [gapStartInclusive, gapEndInclusive];
     *  the near bank is gapStart-1 and the far bank is gapEnd+1 (both LAND). */
    public record Decision(Kind kind, int gapStartInclusive, int gapEndInclusive) {
        public Decision {
            Objects.requireNonNull(kind, "kind");
        }
    }

    private TrafficDecision() {
    }

    public static Decision decide(List<ColumnKind> columns, int targetIndex, int minSpan, int maxSpan) {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty() || targetIndex < 0 || targetIndex >= columns.size()
                || minSpan < 1 || maxSpan < minSpan) {
            return new Decision(Kind.NONE, -1, -1);
        }
        if (columns.get(targetIndex) != ColumnKind.LAND) {
            // The probe cannot confirm the target stands on land at line height;
            // RoadPlanner owns target accessibility and fails cleanly on its own.
            return new Decision(Kind.ROAD, -1, -1);
        }
        int runStart = -1;
        for (int index = 0; index <= targetIndex; index++) {
            if (columns.get(index) == ColumnKind.WATER) {
                runStart = index;
                break;
            }
        }
        if (runStart < 0) {
            return new Decision(Kind.ROAD, -1, -1);
        }
        if (runStart == 0 || columns.get(runStart - 1) != ColumnKind.LAND) {
            return new Decision(Kind.ROAD, -1, -1);   // no buildable near bank
        }
        int runEnd = runStart;
        while (runEnd + 1 < targetIndex && columns.get(runEnd + 1) == ColumnKind.WATER) {
            runEnd++;
        }
        if (columns.get(runEnd + 1) != ColumnKind.LAND) {
            return new Decision(Kind.ROAD, -1, -1);   // no buildable far bank
        }
        int width = runEnd - runStart + 1;
        if (width < minSpan || width > maxSpan) {
            return new Decision(Kind.ROAD, -1, -1);
        }
        return new Decision(Kind.BRIDGE, runStart, runEnd);
    }
}
```

**边界语义（可独立写检查的核心）**：输入是沿线逐列采样的三类事实 `LAND/WATER/BLOCKED` + 目标列下标 + 跨度上下限（由 `BridgePlanner.MIN/MAX_WOOD_SPAN` 传入）；输出 `NONE/ROAD/BRIDGE`，BRIDGE 附带水面段首尾列下标。规则：只认**第一个**水面段（道路不能跨任何水，首段不可桥则整个直线走廊对"桥"无意义，A* 绕行才是答案）；要求近岸、远岸都是 LAND 且远岸在目标列之前或就是目标列；跨度 [4,12] 闭区间。目标列本身非 LAND 时返回 ROAD 委托 `RoadPlanner`（它有自己的 `TARGET_UNAVAILABLE` 口径），避免探测的采样高度误差造成永久停摆。

`build.gradle` 注册 `trafficDecisionCheck` 任务（同 §1e 样式，`mainClass = 'dev.local.goblinsettlement.planning.transport.TrafficDecisionCheck'`）并挂入 `check`。

**验证**：`./gradlew trafficDecisionCheck --offline --no-daemon` → `TrafficDecisionCheck passed`。

### Task 4: 直线探测适配器 + 协调器 `tick` + 材料门禁 + 接线

#### 4a. `StraightLineProbe.java`（新文件，读世界、只读不改）

```java
package dev.local.goblinsettlement.planning.transport;

import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;

/** Bounded, read-only straight-line terrain probe between anchor and target facility. */
public final class StraightLineProbe {
    public static final int MAX_PROBE_COLUMNS = 80;
    private static final int SURFACE_SCAN_UP = 6;
    private static final int SURFACE_SCAN_DOWN = 10;

    public record Sample(List<TrafficDecision.ColumnKind> columns, int targetIndex) {
        public Sample {
            columns = List.copyOf(columns);
        }
    }

    private StraightLineProbe() {
    }

    public static Sample sample(ServerLevel level, String settlementId, BlockPos anchor, BlockPos target) {
        int dx = target.getX() - anchor.getX();
        int dz = target.getZ() - anchor.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps <= 0 || steps > MAX_PROBE_COLUMNS) {
            return new Sample(List.of(), -1);
        }
        List<TrafficDecision.ColumnKind> columns = new ArrayList<>(steps + 1);
        for (int index = 0; index <= steps; index++) {
            BlockPos column = columnAt(anchor, target, index);
            columns.add(classify(level, settlementId, column, anchor.getY()));
        }
        return new Sample(columns, steps);
    }

    /** Shared DDA column mapping; the bridge proposal reuses it to find the near bank. */
    public static BlockPos columnAt(BlockPos anchor, BlockPos target, int index) {
        int dx = target.getX() - anchor.getX();
        int dz = target.getZ() - anchor.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            return anchor.immutable();
        }
        int x = anchor.getX() + (int) Math.round((double) index * dx / steps);
        int z = anchor.getZ() + (int) Math.round((double) index * dz / steps);
        return new BlockPos(x, anchor.getY(), z).immutable();
    }

    /** Re-finds the walkable foot cell of a column classified LAND. */
    public static BlockPos surfaceFoot(ServerLevel level, String settlementId, BlockPos column, int referenceY) {
        for (int y = referenceY + SURFACE_SCAN_UP; y >= referenceY - SURFACE_SCAN_DOWN; y--) {
            BlockPos probe = new BlockPos(column.getX(), y, column.getZ());
            var state = level.getBlockState(probe);
            if (state.isAir() || state.getFluidState().is(FluidTags.WATER)) {
                continue;
            }
            if (state.isFaceSturdy(level, probe, Direction.UP) || state.is(Blocks.DIRT_PATH)) {
                return probe.above().immutable();
            }
            return column.immutable();
        }
        return column.immutable();
    }

    private static TrafficDecision.ColumnKind classify(ServerLevel level, String settlementId,
                                                       BlockPos column, int referenceY) {
        if (WorldModificationPermission.check(level, settlementId, column)
                != WorldModificationPermission.Decision.ALLOWED) {
            return TrafficDecision.ColumnKind.BLOCKED; // inactive, unclaimed, or protected columns
        }
        for (int y = referenceY + SURFACE_SCAN_UP; y >= referenceY - SURFACE_SCAN_DOWN; y--) {
            BlockPos probe = new BlockPos(column.getX(), y, column.getZ());
            var state = level.getBlockState(probe);
            if (state.isAir()) {
                continue;
            }
            if (state.getFluidState().is(FluidTags.WATER)) {
                return TrafficDecision.ColumnKind.WATER;
            }
            return state.isFaceSturdy(level, probe, Direction.UP) || state.is(Blocks.DIRT_PATH)
                    ? TrafficDecision.ColumnKind.LAND : TrafficDecision.ColumnKind.BLOCKED;
        }
        return TrafficDecision.ColumnKind.BLOCKED;
    }
}
```

采样上界 80 列 ≥ `RoadPlanner` 的曼哈顿上限 64（直线列数 = max(|dx|,|dz|) ≤ 曼哈顿距离），DDA 用 `Math.round` 的 double 除法（数值 ≤ 80×80，精度无虞）。权限门与 `RoadPlanner.inspect` 同口径：非 ALLOWED 一律 BLOCKED。

#### 4b. `PublicWarehouseInventory.java` 加通用计数（在 :39 后）

```java
    /** Counts one item kind across accessible, permitted public warehouses. */
    public static int countOf(ServerLevel level, SettlementSavedData data, Item item) {
        int total = 0;
        for (BlockPos pos : data.warehouses()) {
            if (!isAccessible(level, data, pos)) {
                continue;
            }
            Container container = (Container) level.getBlockEntity(pos);
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).is(item)) {
                    total = Math.addExact(total, container.getItem(slot).getCount());
                }
            }
        }
        return total;
    }
```

（import 补 `net.minecraft.world.item.Item`；既有 `countOakPlanks` 可留可委托，不建议本步顺手重构。）

#### 4c. `TrafficProposalCoordinator.java` 补全（`tick` 完整逻辑，见 §3 的完整代码块）

在步骤 1d 骨架上追加 import（`SettlementDemand`、`PublicWarehouseInventory`、`BridgePlanner`、`StraightLineProbe`、`TrafficDecision`、`ServerLevel`、`Direction`、`Items`）与以下内容：

```java
    private static final long PROPOSAL_INTERVAL_TICKS = 1200; // 60 seconds
    private static final int ROAD_MIN_PLANKS = 8;
    private static final int BRIDGE_MIN_PLANKS = 8;
    private static final int BRIDGE_MIN_LOGS = 4;
    private static final int BRIDGE_MIN_FENCES = 4;
    private static final int BRIDGE_MIN_TORCHES = 4;

    /** Register with END_WORLD_TICK, before TransportCoordinator and ExpansionCoordinator. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % PROPOSAL_INTERVAL_TICKS != 0) {
            return;
        }
        var settlement = SettlementSavedData.get(level);
        var traffic = TransportSavedData.get(level);
        if (settlement.settlement().isEmpty() || traffic.hasIncomplete()) {
            return;                                        // gate 2: one in-flight traffic plan
        }
        if (settlement.plans().stream().anyMatch(plan -> !plan.isComplete())) {
            return;                                        // 3.1a symmetry: yield to active building work
        }
        String settlementId = settlement.settlement().orElseThrow().id();
        var supply = PublicWarehouseInventory.snapshot(level, settlement);
        var demand = SettlementDemand.assess(settlement.adultCount(), settlement.childCount(),
                supply, false, hasPendingTarget(settlement, traffic));
        if (demand.priority() != SettlementDemand.Priority.TRANSPORT) {
            return;                                        // gate 1: demand tier
        }
        var target = nearestUnservedFacility(settlement, traffic);
        if (target.isEmpty()) {
            return;
        }
        BlockPos anchor = settlement.settlement().orElseThrow().anchor();
        var sample = StraightLineProbe.sample(level, settlementId, anchor, target.orElseThrow());
        var decision = TrafficDecision.decide(sample.columns(), sample.targetIndex(),
                BridgePlanner.MIN_WOOD_SPAN, BridgePlanner.MAX_WOOD_SPAN);
        switch (decision.kind()) {
            case BRIDGE -> proposeBridge(level, settlement, settlementId, anchor,
                    target.orElseThrow(), sample, decision);
            case ROAD -> {
                if (PublicWarehouseInventory.countOf(level, settlement, Items.OAK_PLANKS)
                        >= ROAD_MIN_PLANKS) {              // gate 3: real materials
                    TransportCoordinator.startRoad(level, settlementId, target.orElseThrow());
                }
            }
            case NONE -> { }
        }
    }

    private static void proposeBridge(ServerLevel level, SettlementSavedData settlement,
                                      String settlementId, BlockPos anchor, BlockPos target,
                                      StraightLineProbe.Sample sample, TrafficDecision.Decision decision) {
        if (PublicWarehouseInventory.countOf(level, settlement, Items.OAK_PLANKS) < BRIDGE_MIN_PLANKS
                || PublicWarehouseInventory.countOf(level, settlement, Items.OAK_LOG) < BRIDGE_MIN_LOGS
                || PublicWarehouseInventory.countOf(level, settlement, Items.OAK_FENCE) < BRIDGE_MIN_FENCES
                || PublicWarehouseInventory.countOf(level, settlement, Items.TORCH) < BRIDGE_MIN_TORCHES) {
            return;
        }
        BlockPos nearBankColumn = StraightLineProbe.columnAt(anchor, target,
                decision.gapStartInclusive() - 1);
        BlockPos nearBankFoot = StraightLineProbe.surfaceFoot(level, settlementId,
                nearBankColumn, anchor.getY());
        Direction direction = dominantDirection(anchor, target);
        var result = TransportCoordinator.startWoodBridge(level, settlementId, nearBankFoot, direction);
        if (!result.accepted()) {
            // BridgePlanner's strict survey rejected the crossing; fall back to a road,
            // exactly as the design's risk note prescribes.
            TransportCoordinator.startRoad(level, settlementId, target);
        }
    }

    private static Direction dominantDirection(BlockPos anchor, BlockPos target) {
        int dx = target.getX() - anchor.getX();
        int dz = target.getZ() - anchor.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
```

#### 4d. 主循环接线（`GoblinSettlement.java:251-252` 之间）

```java
        ConstructionCoordinator.tick(level);
        TrafficProposalCoordinator.tick(level);   // 新增：先立项
        TransportCoordinator.tick(level);         // 既有：后施工
```

（同 tick 内"提案 → 派工"成立：`startRoad/startWoodBridge` 落库后，随后的 `TransportCoordinator.tick` 立即开始找料派工。）

#### 4e. `TransportCommands.showStatus`（:95-108）追加显示

```java
        var traffic = TransportSavedData.get(source.getLevel());
        var settlement = SettlementSavedData.get(source.getLevel());
        source.sendSuccess(() -> Component.literal("Served facilities=" + traffic.servedFacilities().size()
                + ", " + TrafficProposalCoordinator.nearestUnservedFacility(settlement, traffic)
                        .map(pos -> "next target=" + pos.toShortString())
                        .orElse("no pending target")), false);
```

**验证**：`./gradlew compileJava compileTestJava --offline --no-daemon`。

### Task 5: 总验收与文档收尾

```bash
./gradlew check --offline --no-daemon
./gradlew build --offline --no-daemon
```

期望：BUILD SUCCESSFUL，10 个检查全部打印 `*Check passed`（7 旧 + `TransportSavedDataCheck`、`TrafficTargetRulesCheck`、`TrafficDecisionCheck`）。随后按 AGENTS.md 更新 `goblin-settlement-plan/CURRENT_STATUS.md` 并在 `UpdateLog.md` 末尾追加记录（含带时区时间与每条动作）。不做游戏内验证（用户约定统一后测）。

---

## 3. 新协调器完整设计

- **类名/包**：`TrafficProposalCoordinator`，`dev.local.goblinsettlement.construction.transport`。
- **周期常量**：`PROPOSAL_INTERVAL_TICKS = 1200`（60 秒，`level.getGameTime() % 1200 != 0` 即返，与 `ExpansionCoordinator.WINDOW_TICKS`（:18）同模式）。
- **`tick` 顺序（门禁从上到下）**：周期门 → 无聚落/有在途交通工程（`traffic.hasIncomplete()`，复用硬门禁同源索引）→ 无在途建造工程（3.1a 对称）→ `SettlementDemand` 档位必须为 `TRANSPORT` → 选最近未服务设施 → 有界直线探测 → 纯判定 → 按判定走材料门禁 → 调 `startWoodBridge`（失败回退 `startRoad`）或 `startRoad`。
- **与主循环的配合**：插在 `TransportCoordinator.tick` 之前、`ExpansionCoordinator.tick` 之前（§4d）。提案只写存档不动世界；真正施工仍完全由 `TransportCoordinator` 既有路径完成。派工沿用 `WorkKind.TRANSPORT`（`TransportCoordinator.tickPlan` :288-293 已按搬运员职业偏好选人），不新增 `WorkKind`。
- **材料门禁（初值）**：路 ≥ 8 木板；桥 ≥ 8 木板 + 4 原木 + 4 栅栏 + 4 火把。读取走 `PublicWarehouseInventory.countOf`（真实容器计数，chunk 不活跃即不计）。"工人足够"不设门禁——与既有 `TransportCoordinator` 语义一致（无可用工人就等），缺料中途停工保工地是设计已接受的机制。

## 4. "已服务设施登记"的落点

- **放 `TransportSavedData`**（不是 `SettlementSavedData`）：数据随交通域走，Codec 单文件改动，`construction` 包不扩散到 `colony`。持久化为 `BlockPos.CODEC.listOf().optionalFieldOf("served_facilities", List.of())`（§1b 代码）。**该 SavedData 本无 schema 版本**，无需任何版本动作；旧存档缺键按空集加载（检查覆盖）。
- **登记时机**：`TransportSavedData.replace()` 内检测"未完成 → 完成"转移且 `targetFacility` 存在（§1b）。全工程所有完工路径（居民放置 :333、已放置补进 :270-273、桥开闸 :238-239）都经此咽喉，天然不漏；重载后已完工计划不会再触发（无转移）。登记幂等（集合去重）。已完工道路被 `trimCompletedRoads` 裁掉不影响登记（登记先于裁剪、存于独立字段）。
- **`TransportPlan.target_facility`**：record 末尾追加 `Optional<BlockPos>`，`optionalFieldOf("target_facility")`，缺省 `Optional.empty()`；紧凑构造器做 `map(BlockPos::immutable)`。**桥梁计划不填**（`startWoodBridge` 传 `Optional.empty()`）：设计文档 2.1 明确"**一条路**完工时标记"；桥完工后桥面是 sturdy 木板，下一周期探测把水面段视作 LAND（水被桥面遮住）→ 判为修路 → `RoadPlanner` A* 可走桥面（:257-266 对木板为 NORMAL）、`startRoad` 的两车道足迹可铺（`buildableGround` 含 `OAK_PLANKS`）→ 道路完工才标记设施。此级联已核对可行；备选方案（桥完工即标记）会跳过连接道路，不取。

## 5. 修路/架桥判定纯函数设计（可独立检查）

见 §3b 完整实现。要点：

- **输入**：`List<ColumnKind>`（`LAND/WATER/BLOCKED` 三类列事实）+ `targetIndex` + `minSpan/maxSpan`（传 `BridgePlanner.MIN/MAX_WOOD_SPAN` = 4/12）。**不依赖任何 Minecraft 类型**；`StraightLineProbe.sample` 是唯一碰 `ServerLevel` 的适配层（权限门 → 从参考 Y 向下扫面 → 分类，上界 80 列）。
- **输出**：`Decision(NONE | ROAD | BRIDGE, gapStartInclusive, gapEndInclusive)`；BRIDGE 时近岸 = gapStart−1、远岸 = gapEnd+1（均保证 LAND）。
- **边界**：只认第一个水面段（理由见上）；跨度 [4,12] 闭区间；目标列非 LAND → ROAD 委托；空样本/下标越界 → NONE。误判代价被设计文档第 7 节风险条款覆盖：BRIDGE 误判由 `BridgePlanner` 严格勘测兜底，失败回退 `startRoad`；ROAD 误判由 A* 兜底（无路可走就是本轮不立项，下周期重试）。

## 6. "是否存在未服务设施"查询方法设计

- **签名与位置**：`TrafficProposalCoordinator.nearestUnservedFacility(SettlementSavedData, TransportSavedData) → Optional<BlockPos>` 与 `hasPendingTarget(SettlementSavedData, TransportSavedData) → boolean`（§1d）。只读两份存档（锚点、仓库、农田坐标 vs 已服务集），**不做世界探测**（直线探测只发生在真正提案时，符合设计 3.1）。
- **共用**：协调器门禁（传真实值）、`ExpansionCoordinator` 扩地闸（传真实值，让"待修交通"把档位顶到 TRANSPORT 从而自然让位）、`GoblinSettlement` status 行（显示 `Next traffic target: (x,y,z)`）、`TransportCommands.showStatus`（`Served facilities=N, next target=...`）。判据只有一套。
- **纯核心**：`TrafficTargetRules.nearestBeyond`（无 MC 类型），下限 `MIN_TARGET_DISTANCE_SQ = 144`（12 格欧氏距离平方，严格大于才算候选；锚点自身距离 0 永不入选），平手按 x→z→y 破序保证确定性。

## 7. `SettlementDemand` 修改要点

见 §2a/2c。判定顺序：工具不全 → `BASIC_TOOLS` → `trafficPending` → `TRANSPORT` → `activeConstruction ? CONSTRUCTION : READY`。**4 个调用点**：`ExpansionCoordinator:40` 与 `GoblinSettlement:94` 传真实值（`hasPendingTarget`），`FoodCraftingCoordinator:71`、`FarmingCoordinator:35` 传 `false`。检查新增 4 条用例：TRANSPORT 先于 READY、先于 CONSTRUCTION、后于 BASIC_TOOLS、后于 FOOD。

**注意（设计文档自身问题，需更正记录）**：TRAFFIC_DESIGN.md §3.1 写"只有交通相关的那一处传真实值，其余传 false"，但同节末段又要求"扩地自然让位"（这要求 `ExpansionCoordinator` 传真实值），两处矛盾。本方案取后者的意图：`ExpansionCoordinator`、status 行、新协调器三处传真实值，其余传 `false`。

## 8. `ExpansionCoordinator` 修改要点

见 §2b。新增一条门禁（与既有"无未完成建造计划"并列）：`TransportSavedData.get(level).hasIncomplete()` 即返——设计 3.2 要求的对称性。依赖方向 `colony → construction.transport` 是新出现的（反向依赖 `TransportCoordinator → SettlementSavedData` 已存在），可接受；若想收口可把查询挂到 `TransportCoordinator` 公开静态方法上，但会让 colony 依赖一个施工执行类，更不干净，不推荐。

## 9. 验证步骤汇总

| 步骤 | 命令（`goblin-settlement-mod/` 下） | 期望 |
|---|---|---|
| 1 | `./gradlew transportSavedDataCheck trafficTargetRulesCheck --offline --no-daemon` | `TransportSavedDataCheck passed`、`TrafficTargetRulesCheck passed` |
| 1 | `./gradlew compileJava --offline --no-daemon` | 编译通过 |
| 2 | `./gradlew settlementDemandCheck --offline --no-daemon` | `SettlementDemandCheck passed` |
| 3 | `./gradlew trafficDecisionCheck --offline --no-daemon` | `TrafficDecisionCheck passed` |
| 4 | `./gradlew compileJava compileTestJava --offline --no-daemon` | 编译通过 |
| 5 | `./gradlew check --offline --no-daemon` | BUILD SUCCESSFUL，10 项检查全部 passed |
| 5 | `./gradlew build --offline --no-daemon` | BUILD SUCCESSFUL，JAR 重生成 |

按项目约定每步先提交检查（跑出红）再实现（跑出绿）；不做游戏内验证。

## 10. 风险与不确定项

**设计文档自身的问题（已发现）**

1. §3.1 的"只有交通相关的那一处传真实值"与末段"扩地自然让位"矛盾（§7 已述），按后者实现。
2. §7 风险"动到存档结构…不提升 schema 版本"实为无版本可提：`TransportSavedData` 没有版本字段，改动零迁移成本。
3. §2.1"完工时据此标记"未明确桥梁是否标记——本方案定为**不标记**（"一条路完工时"），靠级联修路完成连接；这是对文档空白的补充决定，需确认。
4. §2.4.3 材料门禁无数量——本方案定初值（路 8 木板；桥 8 木板 + 4 原木 + 4 栅栏 + 4 火把），纯"存在≥1"是弱化备选。**单在途硬门禁 + 缺料停工的叠加**意味着立项后断料会停摆并挡住后续交通提案（直到补料），是设计已接受的语义但试玩时需留意。
5. §2.3"直线中途遇到水面"未定义多段水面——本方案固化"只认首段"（道路不能跨水，首段不可桥则该走廊对桥无意义）。

**不确定项（需进一步确认或试玩验证）**

1. **对角跨河**：桥只接受 cardinal `Direction`（`BridgePlanner:132` 拒绝非水平、勘测沿单一方向）。探测沿真实斜线命中水面后，协调器取主轴方向尝试；若 `BridgePlanner` 因两岸不齐拒绝则回退修路，而 A* 不能跨水——对角窄河可能永远建不成桥。备选：探测阶段对主轴两个方向分别采样。是否投入需确认。
2. **门禁与需求的语义**：`TRANSPORT` 档出现在 `BASIC_TOOLS` 之后，意味着缺任何一种工具时交通提案完全停摆（哪怕桥梁材料齐备）——与设计第 3 节默认优先级一致，但若希望"材料齐全时允许修桥"需调整。
3. **探测分类与 A* 口径的差异**：探测把树叶等"非 sturdy 非水"物列为 BLOCKED → 判 ROAD 并委托 A*，行为安全；但探测的采样高度窗（参考 Y +6/−10）之外的高台设施会被误判，同样走 ROAD 委托，代价只是每周期一次有界 A*（≤2048 节点），可接受。
4. **检查覆盖边界**：独立检查覆盖纯判定、目标选取、存档往返与完工登记；`ServerLevel` 相关路径（探测、材料门禁、桥回退）按项目约定留待统一测试，编译证据之外无运行证据。
5. **`hasPendingTarget` 的语义精度**：它不管材料是否足够，只要存在未服务且超下限的设施就置 TRANSPORT 档——档位是"该修交通"的显式状态，材料门禁在真正提案时才把关，两处分工符合设计，但状态行会显示"待修交通"而提案因缺料不动，属预期。

### Critical Files for Implementation

- D:/MC/.minecraft/versions/1.21.11-Fabric 0.19.2/goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportSavedData.java
- D:/MC/.minecraft/versions/1.21.11-Fabric 0.19.2/goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportPlan.java
- D:/MC/.minecraft/versions/1.21.11-Fabric 0.19.2/goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java
- D:/MC/.minecraft/versions/1.21.11-Fabric 0.19.2/goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementDemand.java
- D:/MC/.minecraft/versions/1.21.11-Fabric 0.19.2/goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ExpansionCoordinator.java
- D:/MC/.minecraft/versions/1.21.11-Fabric 0.19.2/goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java