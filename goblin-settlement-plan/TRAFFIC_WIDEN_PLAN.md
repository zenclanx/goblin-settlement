# 道路加宽 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把够格的已建成道路**真的加宽**（2 → 3 → 5），走既有的工人施工路径，一格不多改。

**Architecture:** 加宽写成**链式新计划**——只含新增车道格的 `ROAD` 计划，用 `widens_from` 指向被加宽的那条；路的宽度取链上最大值、通行量取链上计数之和，所以原计划的计数不清零、也不会反复触发加宽。几何由计划里新存的**中线**（`route`）精确算出：`planning/transport/RoadLayout` 成为"一条路的车道落在哪"的唯一出处，`startRoad` 与加宽共用它。判定沿用第一轮的 `RoadUpgradeRules.shouldUpgrade`；提案挂在既有的 `TrafficProposalCoordinator` 上，只在"没有待办新路且聚落没有更紧要的事"时才动手。施工零新增机械。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [TRAFFIC_WIDEN_DESIGN.md](TRAFFIC_WIDEN_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行（一次约 25–40 秒，Bash 超时给 300000 ms）。
- **不升存档 schema 版本**：`TransportPlan` 的新字段一律 `optionalFieldOf` 带默认值，旧档必须能打开；旧计划一律视为**两车道、无中线**。
- **两个发明值不改**：`RoadUpgradeRules.TRAFFIC_PER_LANE = 200`、`TrafficSampler.SAMPLE_INTERVAL_TICKS = 100`。注释与文档继续写明"没有依据、待按真实计数重定"。
- **已完成的计划永远保持完成**：加宽是新计划，绝不把已完成计划改回未完成（这是选链式而非"尾部追加步"的原因）。
- **`startRoad` 的行为必须**逐字不变**（除写入新的 `road` 字段外）**：同样的中线、同样的车道格顺序、同样的 `putIfAbsent` 去重、同一个拒绝理由字符串 `UNSAFE_TWO_LANE_FOOTPRINT`。
- **一份事实只留一处**：车道的落点只在 `RoadLayout`；宽度只在计划的 `road.lanes`；链的宽度与通行量口径只在 `TransportSavedData`；加宽是否够格只在 `RoadUpgradeRules.shouldUpgrade`。第一轮遗留的 `BUILT_ROAD_LANES` 常量与 `startRoad` 里字面写的两条车道在本轮**合并**。
- 检查项数由 **15 增至 16**（新增 `roadLayoutCheck`）。构建结束时 16 项必须全部 `*Check passed`。
- 不做游戏内验证（按用户约定）。MC 侧的加宽立项、安全判定、工人施工都**不可纯测**，会写进日志与状态文件。
- `goblin-settlement-plan/` 下**可能有并行 agent 的未提交改动**：文档任务先跑 `git status`，不是自己改的就先单独提交并署名。`UpdateLog.md` **只许在末尾追加**。
- 提交到 `main`，不要另开分支；本轮结束推送。

---

### Task 1: 纯层——车道几何与宽度的唯一出处

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/planning/transport/RoadLayout.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/planning/transport/RoadLayoutCheck.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/planning/transport/RoadUpgradeRules.java`（加 `ladder()` / `baseLanes()`，删 `BUILT_ROAD_LANES`）
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/planning/transport/RoadUpgradeRulesCheck.java`（两条断言随常量改名）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java`（`qualifies` 里的常量改名，仅此一处）
- Modify: `goblin-settlement-mod/build.gradle`（注册第 16 项）

**Interfaces:**
- Consumes: 无
- Produces:
  - `RoadLayout.laneOffsets(int lanes) -> int[]`
  - `RoadLayout.direction(List<BlockPos> route, int index) -> int[]`（`{dx, dz}`）
  - `RoadLayout.clockwise(int[] direction) -> int[]`
  - `RoadLayout.laneFeet(List<BlockPos> route, int lanes) -> List<BlockPos>`
  - `RoadLayout.newLaneFeet(List<BlockPos> route, int fromLanes, int toLanes) -> List<BlockPos>`
  - `RoadUpgradeRules.ladder() -> int[]`、`RoadUpgradeRules.baseLanes() -> int`

- [ ] **Step 1: 先写检查（RED）**

创建 `src/test/java/dev/local/goblinsettlement/planning/transport/RoadLayoutCheck.java`：

```java
package dev.local.goblinsettlement.planning.transport;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;

public final class RoadLayoutCheck {
    public static void main(String[] args) {
        checkDirectionAxes();
        checkClockwiseTurns();
        checkDirectionsRepeatAtTheEnd();
        checkLaneOffsetsPerRung();
        checkOffsetsMatchTheLadder();
        checkTrailLanesSitBesideTheCenterline();
        checkWideningToThreeAddsOneSide();
        checkWideningToFiveReCenters();
        checkBendsKeepTheWidenedShapeWhole();
        checkNoCellIsAddedTwice();
        checkSingleCellRoute();
        System.out.println("RoadLayoutCheck passed");
    }

    private static void checkDirectionAxes() {
        List<BlockPos> east = List.of(point(0, 64, 0), point(1, 64, 0));
        require(Arrays.equals(RoadLayout.direction(east, 0), new int[] {1, 0}), "east is +x");
        List<BlockPos> south = List.of(point(0, 64, 0), point(0, 64, 1));
        require(Arrays.equals(RoadLayout.direction(south, 0), new int[] {0, 1}), "south is +z");
    }

    private static void checkClockwiseTurns() {
        require(Arrays.equals(RoadLayout.clockwise(new int[] {0, -1}), new int[] {1, 0}),
                "north turns to east");
        require(Arrays.equals(RoadLayout.clockwise(new int[] {1, 0}), new int[] {0, 1}),
                "east turns to south");
        require(Arrays.equals(RoadLayout.clockwise(new int[] {0, 1}), new int[] {-1, 0}),
                "south turns to west");
        require(Arrays.equals(RoadLayout.clockwise(new int[] {-1, 0}), new int[] {0, -1}),
                "west turns to north");
    }

    private static void checkDirectionsRepeatAtTheEnd() {
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0), point(2, 64, 0));
        require(Arrays.equals(RoadLayout.direction(route, 2), new int[] {1, 0}),
                "the last cell repeats the previous step instead of looking past the end");
    }

    private static void checkLaneOffsetsPerRung() {
        require(RoadLayout.laneOffsets(2).length == 2, "a trail has two lanes");
        require(RoadLayout.laneOffsets(3).length == 3, "a road has three lanes");
        require(RoadLayout.laneOffsets(5).length == 5, "a main street has five lanes");
        require(Arrays.equals(RoadLayout.laneOffsets(2), new int[] {0, 1}), "the trail keeps the centerline");
        require(Arrays.equals(RoadLayout.laneOffsets(5), new int[] {-1, 0, 1, 2, 3}),
                "the street is re-centered around the original pair");
    }

    /** The offsets table and the widening ladder are separate facts; this is the tie between them. */
    private static void checkOffsetsMatchTheLadder() {
        int[] ladder = RoadUpgradeRules.ladder();
        require(Arrays.equals(ladder, new int[] {2, 3, 5}), "the ladder is the three documented widths");
        for (int rung : ladder) {
            require(RoadLayout.laneOffsets(rung).length == rung,
                    "a road of " + rung + " lanes has " + rung + " offsets");
        }
        require(RoadUpgradeRules.baseLanes() == ladder[0], "the built width is the first rung");
        require(RoadLayout.laneOffsets(4).length == 3, "a width between rungs takes the rung below it");
        require(RoadLayout.laneOffsets(0).length == 2, "a nonsense width still lands on a real shape");
    }

    private static void checkTrailLanesSitBesideTheCenterline() {
        // Eastward centerline: the clockwise side is south, so the lanes sit at z and z+1.
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0));
        List<BlockPos> feet = RoadLayout.laneFeet(route, 2);
        require(feet.equals(List.of(point(0, 64, 0), point(0, 64, 1), point(1, 64, 0), point(1, 64, 1))),
                "each centerline cell is paired with its clockwise neighbour, in order");
    }

    private static void checkWideningToThreeAddsOneSide() {
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0));
        List<BlockPos> added = RoadLayout.newLaneFeet(route, 2, 3);
        require(added.equals(List.of(point(0, 64, 2), point(1, 64, 2))),
                "two to three lanes adds exactly one lane, on the offset-two side");
    }

    private static void checkWideningToFiveReCenters() {
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0));
        List<BlockPos> added = RoadLayout.newLaneFeet(route, 3, 5);
        require(added.equals(List.of(point(0, 64, -1), point(1, 64, -1),
                        point(0, 64, 3), point(1, 64, 3))),
                "three to five lanes adds one lane on each side");
    }

    private static void checkBendsKeepTheWidenedShapeWhole() {
        // East for two cells, then south for two: the corner rotates the lane pair by a quarter turn.
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0),
                point(2, 64, 0), point(2, 64, 1), point(2, 64, 2));
        List<BlockPos> narrow = RoadLayout.laneFeet(route, 2);
        List<BlockPos> wide = RoadLayout.laneFeet(route, 3);
        List<BlockPos> added = RoadLayout.newLaneFeet(route, 2, 3);
        require(new HashSet<>(wide).containsAll(narrow), "the wider shape is a superset of the narrower");
        require(new HashSet<>(wide).equals(union(narrow, added)),
                "the wider shape is exactly the narrower shape plus the added cells");
        require(new HashSet<>(wide).size() == wide.size(), "the wider shape has no duplicate cells");
        require(added.size() < route.size(),
                "the corner already covers part of the wider shape, so fewer cells are added "
                        + "than the one-cell-per-centerline-cell a naive widening would place");
    }

    private static void checkNoCellIsAddedTwice() {
        // A centerline that walks back over itself must not list the same new cell twice.
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0), point(1, 64, 1));
        List<BlockPos> added = RoadLayout.newLaneFeet(route, 2, 3);
        require(new HashSet<>(added).size() == added.size(), "an added cell appears once");
    }

    private static void checkSingleCellRoute() {
        List<BlockPos> route = List.of(point(5, 64, 5));
        require(Arrays.equals(RoadLayout.direction(route, 0), new int[] {0, -1}),
                "a single cell has no direction, so north is used");
        require(RoadLayout.laneFeet(route, 2).size() == 2, "it still yields its two lanes");
        require(RoadLayout.newLaneFeet(route, 2, 3).size() == 1, "and widening it adds the third");
    }

    private static HashSet<BlockPos> union(List<BlockPos> left, List<BlockPos> right) {
        var union = new HashSet<>(left);
        union.addAll(right);
        return union;
    }

    private static BlockPos point(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

该文件的 import 恰好是这四条，按此顺序：

```java
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
```

（不要 `java.util.ArrayList`：本文件里集合都用 `var` 构造，加进去会是一个未使用的 import。）

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew compileTestJava --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 类 RoadLayout` / `方法 ladder()`。

- [ ] **Step 3: 写 `RoadLayout`**

创建 `src/main/java/dev/local/goblinsettlement/planning/transport/RoadLayout.java`：

```java
package dev.local.goblinsettlement.planning.transport;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Where a road's lanes sit relative to its centerline, and which cells a widening adds. This is the
 * one authority on that question: the initial build and every widening ask here, so two callers can
 * never disagree about which cell a lane occupies.
 */
public final class RoadLayout {
    private static final int TRAIL_LANES = 2;
    private static final int ROAD_LANES = 3;
    private static final int STREET_LANES = 5;
    private static final int[] TRAIL_OFFSETS = {0, 1};
    private static final int[] ROAD_OFFSETS = {0, 1, 2};
    private static final int[] STREET_OFFSETS = {-1, 0, 1, 2, 3};

    private RoadLayout() {
    }

    /**
     * The lane offsets of a road this wide, in centerline-relative steps along the clockwise side. A
     * width between rungs (a hand-edited save, or a width the ladder skips) takes the rung below it.
     */
    public static int[] laneOffsets(int lanes) {
        if (lanes >= STREET_LANES) {
            return STREET_OFFSETS.clone();
        }
        if (lanes >= ROAD_LANES) {
            return ROAD_OFFSETS.clone();
        }
        return TRAIL_OFFSETS.clone();
    }

    /** The step from one centerline cell to the next; the last cell repeats the previous step. */
    public static int[] direction(List<BlockPos> route, int index) {
        BlockPos from = route.get(index == route.size() - 1 && index > 0 ? index - 1 : index);
        BlockPos to = route.get(index == route.size() - 1 && index > 0
                ? index : Math.min(index + 1, route.size() - 1));
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        if (dx == 0 && dz == 0) {
            return new int[] {0, -1};
        }
        return new int[] {dx, dz};
    }

    /** A quarter turn clockwise: Direction.getClockWise() is (dx, dz) -> (-dz, dx). */
    public static int[] clockwise(int[] direction) {
        return new int[] {-direction[1], direction[0]};
    }

    /** Every walking cell of this road, in centerline order, each cell's offsets in table order. */
    public static List<BlockPos> laneFeet(List<BlockPos> route, int lanes) {
        int[] offsets = laneOffsets(lanes);
        var feet = new ArrayList<BlockPos>(route.size() * offsets.length);
        for (int index = 0; index < route.size(); index++) {
            int[] side = clockwise(direction(route, index));
            for (int offset : offsets) {
                feet.add(route.get(index).offset(side[0] * offset, 0, side[1] * offset));
            }
        }
        return List.copyOf(feet);
    }

    /**
     * The cells a widening from fromLanes to toLanes adds: the lanes the wider shape has and the
     * narrower one does not. Cells the corner already covered are not added twice, so the widened road
     * is whole without the added list being one lane per centerline cell all the way along.
     */
    public static List<BlockPos> newLaneFeet(List<BlockPos> route, int fromLanes, int toLanes) {
        var existing = new LinkedHashSet<>(laneFeet(route, fromLanes));
        var added = new LinkedHashSet<BlockPos>();
        for (BlockPos foot : laneFeet(route, toLanes)) {
            if (!existing.contains(foot)) {
                added.add(foot);
            }
        }
        return List.copyOf(added);
    }
}
```

- [ ] **Step 4: 宽度来源改名（`RoadUpgradeRules`）**

4a. 把 `RoadUpgradeRules.java` 里这一行

```java
    public static final int BUILT_ROAD_LANES = LADDER[0];
```

连同它上方那段 javadoc 一起替换为：

```java
    /** The ladder itself, as a copy: geometry that has to agree with the rungs pins itself to this. */
    public static int[] ladder() {
        return LADDER.clone();
    }

    /** The width every road is built at, and the width every plan written before widening is assumed. */
    public static int baseLanes() {
        return LADDER[0];
    }
```

4b. `TransportCommands.trafficLine` 的私有 `qualifies` 里，把 `RoadUpgradeRules.BUILT_ROAD_LANES` 改成 `RoadUpgradeRules.baseLanes()`（**只改这一处，`trafficLine` 其余部分本轮 Task 5 再改**）。

4c. `RoadUpgradeRulesCheck.java`：把 `main` 里的 `checkBuiltWidthIsOnTheLadder();` 改成 `checkBaseWidthIsOnTheLadder();`，并把该方法整个替换为：

```java
    private static void checkBaseWidthIsOnTheLadder() {
        require(RoadUpgradeRules.baseLanes() == 2, "roads are built two lanes wide today");
        require(RoadUpgradeRules.baseLanes() == RoadUpgradeRules.ladder()[0],
                "the built width is the ladder's first rung");
        require(RoadUpgradeRules.nextLanes(RoadUpgradeRules.baseLanes()) == 3,
                "so the first widening step goes to three");
    }
```

- [ ] **Step 5: 注册第 16 项检查**

在 `build.gradle` 里 `roadUpgradeRulesCheck` 任务块**之后**、`tasks.named('check')` **之前**插入：

```gradle
tasks.register('roadLayoutCheck', JavaExec) {
    group = 'verification'
    description = 'Checks where a road lanes sit and which cells a widening adds.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.planning.transport.RoadLayoutCheck'
}
```

并在 `tasks.named('check')` 的 `dependsOn` 列表末尾（`dependsOn tasks.named('roadUpgradeRulesCheck')` 之后）追加：

```gradle
    dependsOn tasks.named('roadLayoutCheck')
```

- [ ] **Step 6: 跑完整构建，确认 16 项**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**16 项**检查全部 `*Check passed`（含新的 `RoadLayoutCheck passed`），无编译警告。

- [ ] **Step 7: 核对接线**

Run: `grep -rn "BUILT_ROAD_LANES" src/`

Expected: **一处不剩**（定义与三处引用都已改名）。

- [ ] **Step 8: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/planning/transport/RoadLayout.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/planning/transport/RoadLayoutCheck.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/planning/transport/RoadUpgradeRules.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/planning/transport/RoadUpgradeRulesCheck.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java \
        goblin-settlement-mod/build.gradle
git commit -m "Put a road's lane geometry and its width in one place each"
```

---

### Task 2: 数据层——链、宽度与交通量口径

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportPlan.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportSavedData.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/transport/TransportSavedDataCheck.java`

**Interfaces:**
- Consumes: `RoadLayout.newLaneFeet`、`RoadUpgradeRules.baseLanes()`（Task 1）
- Produces:
  - `TransportPlan.Road(Optional<String> widensFrom, int lanes, List<BlockPos> route)`
  - `TransportPlan.lanes() -> int`、`TransportPlan.route() -> List<BlockPos>`、`TransportPlan.widensFrom() -> Optional<String>`
  - `TransportSavedData.roadWidth(String planId) -> int`
  - `TransportSavedData.roadTraffic(String planId) -> int`
  - `TransportSavedData.roads() -> List<TransportPlan>`
  - `TransportSavedData.wideningCandidates() -> List<TransportPlan>`

- [ ] **Step 1: 先写检查（RED）**

在 `TransportSavedDataCheck.java` 里，`System.out.println("TransportSavedDataCheck passed");` **之前**插入：

```java
        var chained = new TransportSavedData();
        TransportPlan base = roadPlan("chain-base", facility, 1, false, 2, "chain-base");
        require(chained.add(base), "the base road is accepted");
        chained.recordTraffic(Map.of("chain-base", 300));
        require(chained.roadWidth("chain-base") == 2, "a lone road is as wide as it was built");
        require(chained.roadTraffic("chain-base") == 300, "a lone road reports its own samples");
        require(chained.wideningCandidates().size() == 1, "and it qualifies for its first widening");

        TransportPlan widened = roadPlan("chain-widened", facility, 1, false, 3, "chain-base");
        require(chained.add(widened), "the widening plan is accepted");
        chained.recordTraffic(Map.of("chain-widened", 90));
        require(chained.roadWidth("chain-base") == 3, "the chain reports its widest member");
        require(chained.roadWidth("chain-widened") == 3, "from either end of the chain");
        require(chained.roadTraffic("chain-base") == 390, "the chain sums every member's samples");
        require(chained.roadTraffic("chain-widened") == 390, "so a widening never resets the road's traffic");
        require(chained.wideningCandidates().isEmpty(),
                "three lanes and 390 samples is short of the two shares a road needs");
        require(chained.roads().size() == 1, "the chain is one road, not two");
        require(chained.roads().get(0).id().equals("chain-widened"),
                "and the widest member represents it");

        chained.recordTraffic(Map.of("chain-base", 200));
        require(chained.wideningCandidates().size() == 1, "past two shares it qualifies again");
        TransportPlan street = roadPlan("chain-street", facility, 1, false, 5, "chain-widened");
        require(chained.add(street), "the second widening is accepted");
        chained.recordTraffic(Map.of("chain-street", 4000));
        require(chained.roadWidth("chain-base") == 5, "the widest street is the chain's width");
        require(chained.wideningCandidates().isEmpty(), "and the widest street never widens again");

        var missingRoute = new TransportSavedData();
        require(missingRoute.add(roadPlan("no-route", facility, 1, false)), "an old-style road loads");
        require(missingRoute.plan("no-route").orElseThrow().lanes() == 2,
                "a plan written before widening is two lanes");
        require(missingRoute.plan("no-route").orElseThrow().route().isEmpty(),
                "and has no centerline, so it can never be widened");
        require(missingRoute.plan("no-route").orElseThrow().widensFrom().isEmpty(),
                "and widens nothing");

        var chainedJson = TransportSavedData.CODEC.encodeStart(JsonOps.INSTANCE, chained).getOrThrow();
        var chainedReload = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, chainedJson).getOrThrow();
        require(chainedReload.roadWidth("chain-base") == 5, "chain widths survive reload");
        require(chainedReload.roadTraffic("chain-base") == 300 + 200 + 90 + 4000,
                "chain traffic survives reload");
        require(chainedReload.plan("chain-street").orElseThrow().route()
                        .equals(chained.plan("chain-street").orElseThrow().route()),
                "the centerline survives reload");
        JsonObject withoutRoad = chainedJson.getAsJsonObject().deepCopy();
        withoutRoad.getAsJsonArray("plans").forEach(element ->
                element.getAsJsonObject().remove("road"));
        var earlier = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, withoutRoad).getOrThrow();
        require(earlier.roadWidth("chain-base") == 2, "a save without the road field falls back to two lanes");
        require(earlier.roadTraffic("chain-base") == 0, "and has no chain to sum");

        var retirable = new TransportSavedData();
        for (int index = 0; index < 40; index++) {
            String id = "plain-" + index;
            require(retirable.add(roadPlan(id, facility, 1, false)), "plain road " + index + " accepted");
        }
        require(retirable.plan("plain-0").isEmpty(),
                "plain roads are still retired once there are too many");
        var protectedChain = new TransportSavedData();
        require(protectedChain.add(roadPlan("keep-base", facility, 1, false, 2, "keep-base")),
                "chain base accepted");
        require(protectedChain.add(roadPlan("keep-child", facility, 1, false, 3, "keep-base")),
                "chain child accepted");
        for (int index = 0; index < 40; index++) {
            String id = "filler-" + index;
            require(protectedChain.add(roadPlan(id, facility, 1, false)), "filler " + index + " accepted");
        }
        require(protectedChain.plan("keep-base").isPresent(), "a widened road is never retired");
        require(protectedChain.plan("keep-child").isPresent(), "nor is the widening that widened it");
```

并把该文件末尾的两个构造器助手改成带 `road` 参数的版本。**四参数版表示"本轮之前写下的计划"（没有 `road` 字段），六参数版才是带中线的路**：

```java
    private static TransportPlan roadPlan(String id, BlockPos target, int completedSteps, boolean open) {
        return plan(id, target, completedSteps, open, Optional.empty());
    }

    private static TransportPlan roadPlan(String id, BlockPos target, int completedSteps, boolean open,
                                          int lanes, String widensFrom) {
        var route = List.of(new BlockPos(10, 64, 10), new BlockPos(11, 64, 10));
        return plan(id, target, completedSteps, open, Optional.of(new TransportPlan.Road(
                widensFrom.equals(id) ? Optional.empty() : Optional.of(widensFrom), lanes, route)));
    }

    private static TransportPlan plan(String id, BlockPos target, int completedSteps, boolean open,
                                      Optional<TransportPlan.Road> road) {
        var step = new TransportPlan.Step(TransportPlan.Phase.SURFACE,
                new BlockPos(10, 64, 10), TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.ROAD_GROUND);
        return new TransportPlan(id, "settlement-1", TransportPlan.Kind.ROAD, List.of(step),
                completedSteps, open, List.of(), List.of(), List.of(), Optional.empty(), Optional.of(target),
                road);
    }

    private static TransportPlan bridgePlan(String id, boolean open) {
        return bridgePlan(id, open ? 1 : 0, open);
    }

    private static TransportPlan bridgePlan(String id, int completedSteps, boolean open) {
        var step = new TransportPlan.Step(TransportPlan.Phase.SURFACE,
                new BlockPos(10, 64, 10), TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.AIR_OR_WATER);
        return new TransportPlan(id, "settlement-1", TransportPlan.Kind.WOOD_BRIDGE, List.of(step),
                completedSteps, open,
                List.of(new BlockPos(1, 64, 1), new BlockPos(2, 64, 1),
                        new BlockPos(3, 64, 1), new BlockPos(4, 64, 1)),
                List.of(new BlockPos(5, 65, 1)), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }
```

（`widensFrom.equals(id)` 这个约定是给夹具用的：传自己的 id 表示"这是原路"。**既有夹具一律用四参数版**，于是它们自动变成"本轮之前的计划"，第一轮那些断言的含义与结论都不变。）

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew transportSavedDataCheck --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 方法 roadWidth(...)` / `类 Road`。

- [ ] **Step 3: `TransportPlan` 加 `Road`**

3a. 在 `Kind` 枚举**之后**加嵌套记录：

```java
    /**
     * The road this plan builds: its centerline, how wide it ends up, and the plan it widens. Empty for
     * bridges, and empty for every plan written before the widening round -- such a road is two lanes
     * and has no centerline, so it can never be widened.
     */
    public record Road(Optional<String> widensFrom, int lanes, List<BlockPos> route) {
        static final Codec<Road> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("widens_from").forGetter(Road::widensFrom),
                Codec.INT.optionalFieldOf("lanes", RoadUpgradeRules.baseLanes()).forGetter(Road::lanes),
                BlockPos.CODEC.listOf().optionalFieldOf("route", List.of()).forGetter(Road::route)
        ).apply(instance, Road::new));

        public Road {
            Objects.requireNonNull(widensFrom, "widensFrom");
            if (lanes < 1) {
                throw new IllegalArgumentException("A road needs at least one lane");
            }
            route = immutablePositions(route);
        }
    }
```

（`optionalFieldOf("lanes", RoadUpgradeRules.baseLanes())` 让"旧档算几车道"直接取宽度规则层的权威值，而不是再写一个 `2`。`TransportPlan` 因此要加 `import dev.local.goblinsettlement.planning.transport.RoadUpgradeRules;`；方向是 construction → planning，与 `TransportCoordinator` 既有的一致，没有循环。）

3b. 记录头加第 12 个分量：

```java
public record TransportPlan(
        String id, String settlementId, Kind kind, List<Step> steps,
        int completedSteps, boolean open, List<BlockPos> barrierFeet,
        List<BlockPos> closedFootprint, List<BlockPos> foundationBases,
        Optional<String> workerId, Optional<BlockPos> targetFacility,
        Optional<Road> road) {
```

3c. `CODEC` 加一个字段（放在 `target_facility` 之后）：

```java
            BlockPos.CODEC.optionalFieldOf("target_facility").forGetter(TransportPlan::targetFacility),
            Road.CODEC.optionalFieldOf("road").forGetter(TransportPlan::road)
    ).apply(instance, TransportPlan::new));
```

3d. 紧凑构造器里，`targetFacility` 那段**之后**加：

```java
        road = Objects.requireNonNull(road, "road");
        if (kind != Kind.ROAD && road.isPresent()) {
            throw new IllegalArgumentException("Only roads carry a road shape");
        }
```

3e. 加三个访问器（放在 `isComplete()` **之前**）：

```java
    /** The road's width; a plan written before the widening round is two lanes. */
    public int lanes() {
        return road.map(Road::lanes).orElse(RoadUpgradeRules.baseLanes());
    }

    /** The road's centerline, empty when this plan predates the widening round. */
    public List<BlockPos> route() {
        return road.map(Road::route).orElse(List.of());
    }

    /** The plan this one widens, empty when it widens nothing. */
    public Optional<String> widensFrom() {
        return road.flatMap(Road::widensFrom);
    }
```

3f. `advance()` / `rewind(int)` / `withOpen(boolean)` / `withWorker(Optional<String>)` 四个方法里，把最后的 `targetFacility)` 改成 `targetFacility, road)`（四处各加一个 `road` 实参；其余参数不动）。

- [ ] **Step 4: `TransportSavedData` 加链口径**

4a. 在 `recordTraffic` **之后**加：

```java
    /** The finished road's width: the widest member of the widening chain it belongs to. */
    public int roadWidth(String planId) {
        int widest = RoadUpgradeRules.baseLanes();
        for (TransportPlan plan : chain(planId)) {
            widest = Math.max(widest, plan.lanes());
        }
        return widest;
    }

    /** The finished road's traffic: every member of its chain contributes the samples it caught. */
    public int roadTraffic(String planId) {
        int total = 0;
        for (TransportPlan plan : chain(planId)) {
            total += traffic.getOrDefault(plan.id(), 0);
        }
        return total;
    }

    /**
     * One entry per finished road, in the order the roads were built: the chain's widest member, the
     * smaller id breaking a tie. A widening is folded into the road it widens rather than listed twice.
     */
    public List<TransportPlan> roads() {
        var representatives = new ArrayList<TransportPlan>();
        for (TransportPlan plan : plans) {
            if (plan.kind() != TransportPlan.Kind.ROAD || !plan.isComplete()) {
                continue;
            }
            if (plan.widensFrom().filter(parent -> plan(parent).isPresent()).isPresent()) {
                continue;
            }
            representatives.add(widestOf(chain(plan.id()), plan));
        }
        return List.copyOf(representatives);
    }

    /** The finished roads whose measured traffic has earned a widening, heaviest first. */
    public List<TransportPlan> wideningCandidates() {
        var candidates = new ArrayList<TransportPlan>();
        for (TransportPlan road : roads()) {
            if (RoadUpgradeRules.shouldUpgrade(roadWidth(road.id()), roadTraffic(road.id()))) {
                candidates.add(road);
            }
        }
        candidates.sort((left, right) -> {
            int byTraffic = Integer.compare(roadTraffic(right.id()), roadTraffic(left.id()));
            return byTraffic != 0 ? byTraffic : left.id().compareTo(right.id());
        });
        return List.copyOf(candidates);
    }

    /**
     * The chain a plan belongs to: the plan that widened nothing, plus every plan widened from it. A
     * plan whose parent is missing (a hand-edited save) is its own chain, so nothing is lost silently.
     */
    private List<TransportPlan> chain(String planId) {
        String root = rootOf(planId);
        var found = new ArrayList<TransportPlan>();
        for (TransportPlan plan : plans) {
            if (root.equals(rootOf(plan.id()))) {
                found.add(plan);
            }
        }
        return List.copyOf(found);
    }

    private String rootOf(String planId) {
        String current = planId;
        for (int step = 0; step <= plans.size(); step++) {
            var found = plan(current);
            if (found.isEmpty()) {
                return planId;
            }
            var parent = found.orElseThrow().widensFrom();
            if (parent.isEmpty()) {
                return current;
            }
            current = parent.orElseThrow();
        }
        return planId; // a cycle cannot be written here, but a hand-edited save must not hang the server
    }

    private static TransportPlan widestOf(List<TransportPlan> chain, TransportPlan fallback) {
        TransportPlan widest = fallback;
        for (TransportPlan plan : chain) {
            if (plan.lanes() > widest.lanes()
                    || (plan.lanes() == widest.lanes() && plan.id().compareTo(widest.id()) < 0)) {
                widest = plan;
            }
        }
        return widest;
    }
```

4b. `trimCompletedRoads` 整个替换为：

```java
    /**
     * Retire the oldest finished roads once there are more than MAX_COMPLETED_ROADS of them. Roads on a
     * widening chain are kept: the busiest roads are also the oldest, so retiring one would drop both
     * its samples and its only chance of ever being widened.
     */
    private static void trimCompletedRoads(List<TransportPlan> updated) {
        long retirable = updated.stream().filter(plan -> isRetirable(plan, updated)).count();
        if (retirable <= MAX_COMPLETED_ROADS) {
            return;
        }
        var iterator = updated.iterator();
        while (iterator.hasNext() && retirable > MAX_COMPLETED_ROADS) {
            TransportPlan plan = iterator.next();
            if (isRetirable(plan, updated)) {
                iterator.remove();
                retirable--;
            }
        }
    }

    /** A finished road on no widening chain -- the only kind the trim may retire. */
    private static boolean isRetirable(TransportPlan plan, List<TransportPlan> plans) {
        if (plan.kind() != TransportPlan.Kind.ROAD || !plan.isComplete()) {
            return false;
        }
        if (plan.widensFrom().isPresent()) {
            return false;
        }
        return plans.stream().noneMatch(other -> other.widensFrom().equals(Optional.of(plan.id())));
    }
```

4c. 加 `import dev.local.goblinsettlement.planning.transport.RoadUpgradeRules;`（按既有 import 的字母序放在 `net.minecraft` 之前）。

- [ ] **Step 5: 跑检查并修正夹具**

Run: `./gradlew transportSavedDataCheck --offline --no-daemon`

Expected: `TransportSavedDataCheck passed`。

若既有断言因夹具改动而失败（例如 33 条路那一组），**只改夹具的构造方式，不改断言的含义**——那些断言钉的是第一轮的行为，必须继续成立。

- [ ] **Step 6: 跑完整构建，确认 16 项**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，16 项全部 `*Check passed`。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportPlan.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportSavedData.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/transport/TransportSavedDataCheck.java
git commit -m "Give a road a chain, a width and a centerline"
```

---

### Task 3: 初始铺路改走 `RoadLayout` 并记下中线

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java`

**Interfaces:**
- Consumes: `RoadLayout.laneFeet`（Task 1）、`TransportPlan.Road`（Task 2）
- Produces: `startRoad` 写出的计划带 `Road(empty, 2, route)`；`TransportCoordinator.roadDirection` 删除

- [ ] **Step 1: `startRoad` 的车道循环改走 `RoadLayout`**

把 `TransportCoordinator.startRoad` 里从 `Direction forward = roadDirection(route, index);` 开始的整个循环

```java
        for (int index = 0; index < route.size(); index++) {
            BlockPos foot = route.get(index);
            Direction forward = roadDirection(route, index);
            Direction side = forward.getClockWise();
            // Two lanes, which is what RoadUpgradeRules.BUILT_ROAD_LANES describes.
            for (BlockPos lane : List.of(foot, foot.relative(side))) {
                if (!safeRoadFoot(level, settlementId, lane)) {
                    return rejected("UNSAFE_TWO_LANE_FOOTPRINT");
                }
                BlockPos ground = lane.below().immutable();
                sites.putIfAbsent(ground, new TransportPlan.Step(
                        TransportPlan.Phase.SURFACE, ground,
                        TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.ROAD_GROUND));
            }
        }
```

替换为：

```java
        for (BlockPos foot : RoadLayout.laneFeet(route, RoadUpgradeRules.baseLanes())) {
            if (!safeRoadFoot(level, settlementId, foot)) {
                return rejected("UNSAFE_TWO_LANE_FOOTPRINT");
            }
            BlockPos ground = foot.below().immutable();
            sites.putIfAbsent(ground, new TransportPlan.Step(
                    TransportPlan.Phase.SURFACE, ground,
                    TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.ROAD_GROUND));
        }
```

**`laneFeet` 的输出顺序与旧循环逐格相同**（每个中线格先偏移 0 后偏移 1），所以 `sites` 的插入顺序与最终步列表一字不变。

- [ ] **Step 2: `startRoad` 的计划带上中线**

把 `startRoad` 里的

```java
        TransportPlan plan = new TransportPlan(
                UUID.randomUUID().toString(), settlementId, TransportPlan.Kind.ROAD,
                List.copyOf(sites.values()), 0, false, List.of(), List.of(), List.of(),
                Optional.empty(), Optional.of(targetFacility));
```

替换为：

```java
        TransportPlan plan = new TransportPlan(
                UUID.randomUUID().toString(), settlementId, TransportPlan.Kind.ROAD,
                List.copyOf(sites.values()), 0, false, List.of(), List.of(), List.of(),
                Optional.empty(), Optional.of(targetFacility),
                Optional.of(new TransportPlan.Road(Optional.empty(), RoadUpgradeRules.baseLanes(), route)));
```

- [ ] **Step 3: 桥梁计划补上空的 `road`**

把 `startWoodBridge` 里的

```java
        TransportPlan plan = new TransportPlan(
                UUID.randomUUID().toString(), settlementId, TransportPlan.Kind.WOOD_BRIDGE,
                steps, 0, false, barriers, List.copyOf(closure), candidate.surveyedBases(),
                Optional.empty(), Optional.empty());
```

替换为（**只加最后一个实参**）：

```java
        TransportPlan plan = new TransportPlan(
                UUID.randomUUID().toString(), settlementId, TransportPlan.Kind.WOOD_BRIDGE,
                steps, 0, false, barriers, List.copyOf(closure), candidate.surveyedBases(),
                Optional.empty(), Optional.empty(), Optional.empty());
```

- [ ] **Step 4: 删除 `roadDirection`**

删掉 `TransportCoordinator` 里整个 `private static Direction roadDirection(List<BlockPos> route, int index)` 方法（它的唯一调用点是刚改掉的循环；逻辑已归 `RoadLayout.direction`）。若 `Direction` 因此不再被本文件使用，把 `import net.minecraft.core.Direction;` 一并删掉——**先编译再决定**，`Direction` 还在别处用于水面/结实的判定。

- [ ] **Step 5: 加 import**

在 `TransportCoordinator` 里加 `import dev.local.goblinsettlement.planning.transport.RoadLayout;`（按字母序放在 `RoadPlanner` 之前）。

- [ ] **Step 6: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，16 项全部 `*Check passed`，无编译警告。

- [ ] **Step 7: 核对行为未变**

Run: `grep -rn "roadDirection" src/`

Expected: **一处不剩**。

并逐字对照本步骤改动的两段：拒绝理由仍是 `UNSAFE_TWO_LANE_FOOTPRINT`，`sites` 仍是 `LinkedHashMap` + `putIfAbsent`，步的相位/材质/规则仍是 `SURFACE / OAK_PLANKS / ROAD_GROUND`。

- [ ] **Step 8: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java
git commit -m "Lay the first road through the shared lane geometry and record its centerline"
```

---

### Task 4: 加宽立项

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java`

**Interfaces:**
- Consumes: `RoadLayout.newLaneFeet`、`TransportPlan.Road`、`TransportSavedData.roadWidth`（Tasks 1–3）
- Produces: `TransportCoordinator.startWidening(ServerLevel level, String settlementId, TransportPlan road) -> StartResult`

- [ ] **Step 1: 写 `startWidening`**

在 `TransportCoordinator.startWoodBridge` **之后**、`tick` **之前**插入：

```java
    /**
     * Widen a finished road by one rung of the ladder: a new plan holding only the cells the wider
     * shape adds, so the road that is already built is never touched or reopened. Refuses when the
     * road has no recorded centerline, is already the widest, or has any added cell that is not safe
     * to pave -- a widening is all or nothing, because the road's width is judged as a whole.
     */
    public static StartResult startWidening(ServerLevel level, String settlementId, TransportPlan road) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(road, "road");
        TransportSavedData traffic = TransportSavedData.get(level);
        if (hasActivePlan(traffic)) {
            return rejected("TRAFFIC_WORK_ACTIVE");
        }
        List<BlockPos> route = road.route();
        if (road.kind() != TransportPlan.Kind.ROAD || !road.isComplete() || route.isEmpty()) {
            return rejected("NO_ROAD_SHAPE");
        }
        int fromLanes = traffic.roadWidth(road.id());
        int targetLanes = RoadUpgradeRules.nextLanes(fromLanes);
        if (targetLanes == fromLanes) {
            return rejected("ALREADY_WIDEST");
        }
        Map<BlockPos, TransportPlan.Step> sites = new LinkedHashMap<>();
        for (BlockPos foot : RoadLayout.newLaneFeet(route, fromLanes, targetLanes)) {
            if (!safeRoadFoot(level, settlementId, foot)) {
                return rejected("UNSAFE_WIDENING_FOOTPRINT");
            }
            BlockPos ground = foot.below().immutable();
            sites.putIfAbsent(ground, new TransportPlan.Step(
                    TransportPlan.Phase.SURFACE, ground,
                    TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.ROAD_GROUND));
        }
        if (sites.isEmpty() || conflictsWithExistingWork(
                SettlementSavedData.get(level), sites.keySet())) {
            return rejected("NO_SITES_OR_WORK_CONFLICT");
        }
        TransportPlan plan = new TransportPlan(
                UUID.randomUUID().toString(), settlementId, TransportPlan.Kind.ROAD,
                List.copyOf(sites.values()), 0, false, List.of(), List.of(), List.of(),
                Optional.empty(), Optional.empty(),
                Optional.of(new TransportPlan.Road(Optional.of(road.id()), targetLanes, route)));
        if (!traffic.add(plan)) {
            return rejected("TRAFFIC_WORK_ACTIVE_OR_LIMIT");
        }
        return new StartResult(true, "PLANNED", Optional.of(plan));
    }
```

- [ ] **Step 2: 加 import**

`RoadUpgradeRules` 与 `RoadLayout` 若尚未 import 就补上（`dev.local.goblinsettlement.planning.transport.*`，按字母序）。

- [ ] **Step 3: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，16 项全部 `*Check passed`。

**这一任务没有独立检查可以覆盖**（它要真实世界、真实权限与真实方块）：它的正确性只有编译、类型与代码审查的证据。请在提交信息与报告中如实写明这一点。

- [ ] **Step 4: 核对接线与复用**

Run: `grep -rn "safeRoadFoot\|conflictsWithExistingWork\|roadWidth" src/main/java/dev/local/goblinsettlement/construction/transport/`

Expected: `safeRoadFoot` 一处定义两处调用（`startRoad`、`startWidening`）；`conflictsWithExistingWork` 一处定义两处调用；`roadWidth` 一处定义、`startWidening` 一处调用（显示与提案在 Task 5）。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java
git commit -m "Propose a widening as a new plan over the cells the wider shape adds"
```

---

### Task 5: 提案（READY 档）与显示

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TrafficProposalCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java`

**Interfaces:**
- Consumes: `TransportCoordinator.startWidening`（Task 4）、`TransportSavedData.wideningCandidates` / `roads` / `roadWidth` / `roadTraffic`（Task 2）
- Produces: `TrafficProposalCoordinator` 在没有待办新路且需求档为 `READY` 时提案加宽；`trafficLine` 按链显示 `L<n>`

- [ ] **Step 1: 提案分支**

把 `TrafficProposalCoordinator.tick` 里的

```java
        if (demand.priority() != SettlementDemand.Priority.TRANSPORT) {
            return;                                        // gate 3: demand tier
        }
```

替换为：

```java
        if (demand.priority() != SettlementDemand.Priority.TRANSPORT) {
            // Nothing left to connect and nothing more urgent to do: widen what carries the traffic.
            // READY is the only tier that can be reached here -- this call passes activeConstruction
            // false, so a busy building site never reaches this point either.
            if (demand.priority() == SettlementDemand.Priority.READY) {
                proposeWidening(level, settlement, traffic, settlementId);
            }
            return;                                        // gate 3: demand tier
        }
```

- [ ] **Step 2: `proposeWidening`**

在 `TrafficProposalCoordinator.dominantDirection` **之前**插入：

```java
    /**
     * Widen the heaviest finished road that qualifies and whose added cells are all safe. A rejection
     * is not a failure: the next cycle tries the next candidate, and nothing is parked because a
     * widening touches no facility. Material shortage is not a failure either -- supplies catch up.
     */
    private static void proposeWidening(ServerLevel level, SettlementSavedData settlement,
                                        TransportSavedData traffic, String settlementId) {
        if (PublicWarehouseInventory.countOf(level, settlement, Items.OAK_PLANKS) < ROAD_MIN_PLANKS) {
            return;
        }
        for (TransportPlan road : traffic.wideningCandidates()) {
            if (TransportCoordinator.startWidening(level, settlementId, road).accepted()) {
                return;
            }
        }
    }
```

- [ ] **Step 3: 显示改用链口径**

把 `TransportCommands.trafficLine` 与它的私有 `qualifies` 整个替换为：

```java
    /**
     * Every finished road's measured traffic, busiest first, with a star on the ones that have earned a
     * widening and the road's current width after its samples. A widened road is one entry, not one per
     * plan: the chain's samples belong to the road, not to the plan that laid a lane.
     */
    public static String trafficLine(TransportSavedData traffic) {
        var roads = traffic.roads();
        if (roads.isEmpty()) {
            return "Road traffic: no completed roads";
        }
        var sorted = new java.util.ArrayList<>(roads);
        sorted.sort((left, right) -> {
            int byCount = Integer.compare(traffic.roadTraffic(right.id()),
                    traffic.roadTraffic(left.id()));
            return byCount != 0 ? byCount : left.id().compareTo(right.id());
        });
        long ready = sorted.stream().filter(road -> qualifies(traffic, road)).count();
        var builder = new StringBuilder("Road traffic: ").append(sorted.size())
                .append(" road(s), ").append(ready).append(" ready to widen (*); ");
        int shown = Math.min(STATUS_ROAD_LIMIT, sorted.size());
        for (int index = 0; index < shown; index++) {
            TransportPlan road = sorted.get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(shortId(road.id()))
                    .append('=').append(traffic.roadTraffic(road.id()))
                    .append(" L").append(traffic.roadWidth(road.id()));
            if (qualifies(traffic, road)) {
                builder.append('*');
            }
        }
        if (sorted.size() > shown) {
            builder.append(", +").append(sorted.size() - shown).append(" more");
        }
        return builder.toString();
    }

    private static boolean qualifies(TransportSavedData traffic, TransportPlan road) {
        return RoadUpgradeRules.shouldUpgrade(traffic.roadWidth(road.id()),
                traffic.roadTraffic(road.id()));
    }
```

- [ ] **Step 4: 加 import**

`TrafficProposalCoordinator` 需要 `dev.local.goblinsettlement.construction.transport` 之外的 `TransportPlan`——它在**同一个包**里，不需要 import。若 `RoadUpgradeRules` 在 `TransportCommands` 里已被 Task 1 引用则已在 import 中。

- [ ] **Step 5: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，16 项全部 `*Check passed`。

- [ ] **Step 6: 核对接线与判定唯一性**

Run: `grep -rn "shouldUpgrade" src/main/java/`

Expected: 三处——`RoadUpgradeRules` 的定义、`TransportSavedData.wideningCandidates` 的判定、`TransportCommands.qualifies` 的判定。两处调用都只是调它，**没有第二份阈值或形状判断**。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TrafficProposalCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java
git commit -m "Widen a busy road when there is nothing else to do, and show each road's width"
```

---

### Task 6: 文档与收尾

**Files:**
- Modify: `goblin-settlement-plan/TRAFFIC_WIDEN_DESIGN.md`（加 §8 落地结果）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Tasks 1–5 的改动与构建结果（**含 `build/libs/goblin-settlement-0.1.0.jar` 的实际字节数、构建秒数、以及每个提交的哈希**）
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：先单独提交它们并署名，然后再做本任务的文档改动。

- [ ] **Step 2: 设计文档记录落地结果**

在 `TRAFFIC_WIDEN_DESIGN.md` 末尾追加：

```markdown
## 8. 落地结果（实现后补记）

- **数据**：`TransportPlan` 新增一个 `road` 字段（嵌套 `Road(widens_from, lanes, route)`，全部 `optionalFieldOf`，未升 schema）。旧档读入后：`lanes() == 2`、`route()` 空、`widensFrom()` 空，**因此旧路不加宽**（设计 §7 已预先声明）。
- **链口径的唯一出处**：`TransportSavedData.roadWidth`（链上最大宽度）、`roadTraffic`（链上计数之和）、`roads()`（每条路取最宽成员当代表）、`wideningCandidates()`（够格的、最重的在前）。显示与提案都只调这四个，没有第二份判定。
- **淘汰豁免**：`trimCompletedRoads` 只退"不在链上的"已完道路，`isRetirable` 是唯一判据。上界 `32 + 3×加宽过的路数`。
- **几何唯一出处**：`planning/transport/RoadLayout`（`laneOffsets` / `direction` / `clockwise` / `laneFeet` / `newLaneFeet`），`startRoad` 与本轮新增的加宽共用它；第一轮的 `BUILT_ROAD_LANES` 常量与 `startRoad` 里字面写的两条车道**已合并**为 `RoadUpgradeRules.baseLanes()` + 每条计划的 `lanes`。`TransportCoordinator.roadDirection` 已删除。
- **提案落点**：`TrafficProposalCoordinator` 只在需求档为 `READY`（即没有待办新路、且食物/种子/工具/住房都不缺）时提案加宽；按 `wideningCandidates()` 的顺序逐个尝试，第一个通过安全判定与冲突判定的立项。**加宽不进需求档**——所以一条被建筑永久挡住的加宽不会像第一轮的"无法服务的目标"那样冻结扩地。
- **加宽计划**：只含新增车道格（`SURFACE / OAK_PLANKS / ROAD_GROUND`），`target_facility` 留空，`route` 复制父计划的中线，施工走既有工人路径与两道门禁。
- **显示**：`Road traffic:` 行改成按链显示 `id8=链计数和 L<宽度>[ *]`，一条加宽过的路只出现一次。
- **检查**：新增第 16 项 `roadLayoutCheck`（`RoadLayoutCheck`）。
- **阈值仍未定**：`TRAFFIC_PER_LANE = 200` 与 `SAMPLE_INTERVAL_TICKS = 100` 原样沿用，注释与本文档继续写明"没有依据、待按真实计数一起重定"。
```

- [ ] **Step 3: 追加 UpdateLog**

在 `UpdateLog.md` **末尾**追加（`<开始>`/`<结束>`/`<时间>`/`<字节数>`/`<秒数>`/`<哈希>` 换成真实值，**逐条写真实时间，不要留占位**——第四十八、五十轮都漏填过）：

```markdown
## [<开始> – <结束>] 第五十五轮：道路加宽

- [<时间>] 按 TRAFFIC_WIDEN_DESIGN.md 与 TRAFFIC_WIDEN_PLAN.md 执行"先量再决定"的第二轮：把够格的路真的加宽（2 → 3 → 5）。用户 2026-09-27 决定**沿用两个发明值**先把机制做完，因此阈值与采样节拍本轮不改。
- [<时间>] 新增纯层 `planning/transport/RoadLayout`：`laneOffsets`（2 = {0,1}、3 = {0,1,2}、5 = {-1,0,1,2,3}）、`direction`、`clockwise`（`(dx,dz) -> (-dz,dx)`，与 `Direction.getClockWise()` 一致）、`laneFeet`、`newLaneFeet`（= 宽形状的车道减去窄形状的车道）。**"一条路的车道落在哪"至此只有这一处**。
- [<时间>] `startRoad` 改走 `RoadLayout.laneFeet(route, baseLanes())`：输出顺序与旧的车道循环逐格相同（每格先偏移 0 后 1），`sites` 的 `putIfAbsent` 去重与拒绝理由 `UNSAFE_TWO_LANE_FOOTPRINT` 均未变。**第一轮遗留的两处同源事实合并**：`BUILT_ROAD_LANES` 常量与 `startRoad` 里字面写的两条车道，改为 `RoadUpgradeRules.baseLanes()` + 每条计划的 `lanes` 字段；`RoadUpgradeRules.ladder()` 供几何层对齐档位。`TransportCoordinator.roadDirection` 删除。
- [<时间>] `TransportPlan` 新增 `road` 字段（嵌套 `Road(widens_from, lanes, route)`，`optionalFieldOf`、**不升 schema**）。**中线进存档**是必需的：A* 的路线 4 连通、转弯极多，而 `startRoad` 的 `putIfAbsent` 去重正好发生在弯角，所以从步列表重推方向是残缺的（第一轮设计 §5 硬点二）。旧档无中线 → 该路不加宽。
- [<时间>] 加宽 = **链式新计划**，只含新增车道格，原计划一字不动（已完成即终态不回退，这是不选"尾部追加步"的原因）。链口径集中在 `TransportSavedData`：`roadWidth`（最大宽度）、`roadTraffic`（计数之和）、`roads`（每链一个代表）、`wideningCandidates`（够格的按重量降序）。**跨级加宽因此不会把路的通行量归零**，也不会因为旧车道仍在被采样而反复触发。
- [<时间>] 淘汰豁免：`trimCompletedRoads` 只退不在链上的已完道路。不加这条的话，最繁忙的路恰恰建得最早、最先被 32 条上限淘汰、计数也被 `pruneTraffic` 清掉——加宽会在最需要它的成熟聚落里彻底不发生。上界由 32 变成 `32 + 3×加宽过的路数`。
- [<时间>] 提案落在 `TrafficProposalCoordinator`，且**只在需求档为 `READY`** 时动手（没有待办新路，且食物/种子/工具/住房都不缺）。按 `wideningCandidates()` 逐个尝试，第一个"新增格全部通过 `safeRoadFoot` 且不与未完成工程/农田同列"的立项。**加宽不进需求档**：一条被永久挡住的加宽不会像第一轮"无法服务的目标"那样冻结扩地，因此本轮**不需要延期名单**。材料沿用橡木木板与既有的 `ROAD_MIN_PLANKS` 门禁。
- [<时间>] 显示：`Road traffic:` 行按链显示 `id8=链计数和 L<宽度>[ *]`，一条加宽过的路只出现一次（原来会按计划出现两次）。
- [<时间>] 新增第 16 项独立检查 `RoadLayoutCheck`：方向与顺时针四向、末格复读、三档偏移表与 `RoadUpgradeRules.ladder()` 互相钉死、直路 2→3 恰好一侧一条、3→5 两侧各一条、**弯角处宽形状是窄形状的超集且新增格只补缺**、中线自交不重复出格、单格路线的退化行为。`TransportSavedDataCheck` 补链语义（求和、取最大、代表选取、淘汰豁免、旧档无 `road` 字段的兜底）与 codec 往返。
- [<时间>] 验证：完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**16 项**独立检查全部 *Check passed（新增第 16 项）。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>，耗时 <秒数>。提交 <哈希>。
- [<时间>] 未完成：不做玩法验收；**加宽立项、安全判定、工人施工、加宽后采样是否真的记在新车道上、以及 3 格/5 格的观感全部不可纯测**（只有编译与代码审查）。2 → 3 只补一侧是宽度取整的必然；计数仍是累计量、不按经过采样数归一，加宽判据因此偏向先建的路（第一轮 §9 的同一局限，本轮未处理）。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「接续须知」的**下一步落点**整段改写：加宽**已完成并已提交**，落点在 `TRAFFIC_WIDEN_DESIGN.md` §8；下一步是**拿真实计数重定 `TRAFFIC_PER_LANE` 与 `SAMPLE_INTERVAL_TICKS`**（两个发明值从未验证），或按原约定把手上的东西拿去专用测试世界统一验证。删掉"下一轮是加宽"那段。
- 「阶段定位」阶段 5 一句补上"第五十五轮把够格的路真的加宽了（链式加宽计划 + 中线入档 + 淘汰豁免），未做游戏内验证"。
- 「本轮接入的内容」新增一节「第五十五轮：道路加宽」，写：`RoadLayout` 与两处同源事实的合并、`TransportPlan.road` 与链口径、淘汰豁免与其上界、READY 档提案与"不进需求档"的理由、显示改动、第 16 项检查。
- 「交通剩余」第 1 项改写为**已完成**（两个发明值仍待重定，指向设计 §8），其余三项（成熟期多工程并行、道路连通性验收、石桥与更长跨度）不动。
- 「本轮验证进展」替换为第五十五轮：完整构建、16 项检查、产物字节数与耗时、每个提交；写明本轮**新增**了第 16 项检查；把"独立检查覆盖不到"的清单替换为本轮的（加宽立项与安全判定、工人施工、加宽后采样的归属、3/5 格观感、旧档无中线时静默跳过加宽这件事在真实存档里是否会被察觉）。
- 别处提到"15 项"的地方（若有）一并改为 16 项。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the road widening round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。若网络不通，本机需要代理：`git -c http.proxy=http://127.0.0.1:7890 push origin main`。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 三个字段 + 链 → Task 2（`TransportPlan.Road` 把三个事实收成一个嵌套字段，构造器只多一个实参；链口径收在 `TransportSavedData` 的四个方法里）。
- 设计 §2「加宽计划 `target_facility` 留空」→ Task 4 Step 1 的 `Optional.empty()`。
- 设计 §3 车道索引表与 `RoadLayout` → Task 1（含 `laneOffsets` 三档表、`clockwise` 公式、`laneFeet`/`newLaneFeet`），并与 `RoadUpgradeRules.ladder()` 用检查互相钉死。
- 设计 §3「`startRoad` 改走 `RoadLayout`、删 `roadDirection`、合并两处同源事实」→ Task 1 Step 4（宽度改名）+ Task 3。
- 设计 §4 判定、READY 档提案、失败回退、整体成功或放弃、材料沿用、施工零新增机械 → Task 4 + Task 5 Step 1/2。
- 设计 §5 淘汰豁免与显示 → Task 2 Step 4b + Task 5 Step 3。
- 设计 §6 第 16 项检查与不可纯测面 → Task 1 Step 1/5、Task 4 Step 3 的说明、Task 6 Step 3。
- 设计 §7 风险 → 旧档无中线（Task 2 Step 1 断言 + Task 6 文档）、阈值未定（Global Constraints + Task 6）、2→3 偏一侧（Task 6）、计数不归一（Task 6）、链悬空（Task 2 的 `rootOf`/`roads()` 兜底 + 设计 §7）、相邻路并排（沿用 `buildableGround` 含 `OAK_PLANKS` 与 `tickPlan` 的已建即 `advance`，本轮未改）。

**2. 占位符扫描**

- 无 TBD/TODO。两个新文件、`TransportPlan`/`TransportSavedData`/`TransportCoordinator`/`TrafficProposalCoordinator`/`TransportCommands` 的改动都给了可粘贴全文或带锚点的成对替换。
- “旧的计划算几车道”只有一个出处：`RoadUpgradeRules.baseLanes()`，`Road.CODEC` 的默认值与 `TransportPlan.lanes()` 的兜底都取自它，没有第二处字面量 `2`。
- Task 3 Step 4 的"先编译再决定"是**有判据的条件动作**（`Direction` 是否仍被本文件使用），不是含糊指令。
- Task 6 的 `<开始>`/`<结束>`/`<时间>`/`<字节数>`/`<秒数>`/`<哈希>` 是模板占位；正文已要求逐条替换为真实值。

**3. 类型一致性**

- `RoadLayout` 五个方法的签名在 Task 1 的定义、`RoadLayoutCheck` 的调用、Task 3/4 的使用三处一致；`direction` 返回 `int[]{dx,dz}`（不是 `Direction`），`clockwise` 吃 `int[]`。
- `TransportPlan.Road(Optional<String>, int, List<BlockPos>)` 的实参顺序在 Task 2 的 codec、`roads()` 的判定、Task 3 Step 2、Task 4 Step 1 四处一致（`widensFrom` 在前）。
- `TransportPlan` 由 11 个分量变 12 个：**全部 8 个构造点**都已列出——`TransportPlan` 自己的 `advance`/`rewind`/`withOpen`/`withWorker`（Task 2 Step 3f），`TransportCoordinator` 的 `startRoad`（Task 3 Step 2）与 `startWoodBridge`（Task 3 Step 3），`TransportSavedDataCheck` 的 `roadPlan` 与 `bridgePlan`（Task 2 Step 1）。
- `roadWidth`/`roadTraffic`/`roads`/`wideningCandidates` 在 Task 2 定义、Task 4（`roadWidth`）、Task 5（四个都用到）、Task 2 的检查里调用，名字与返回类型一致。
- `startWidening` 的签名 `(ServerLevel, String, TransportPlan) -> StartResult` 在 Task 4 定义、Task 5 调用处一致；`StartResult` 是既有的记录（`accepted`/`reason`/`plan`）。
- `qualifies` 从吃"计划自己的计数"改成吃"链的口径"，唯一调用点（`trafficLine`）在同一任务里同步改动。
