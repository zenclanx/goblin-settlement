# 道路与桥梁的连通验收 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让"建好了"等于"真的能过"——桥在开通前必须真的可跨越，路必须真的接上它服务的设施，两者的结论在 `goblinsettlement status` 里看得见。

**Architecture:** 纯层新增 `planning/transport/TransportConnectivity`（在给定的走格集合上做四邻 BFS，允许上下差 1 格）。MC 侧由 `TransportCoordinator` 把"声明走格 → 现场可行的走格"装配出来：桥取自己的 `SURFACE`/`APPROACHES` 走格与四个落地格，路取**整条链**的 `ROAD_GROUND` 走格与挨着目标设施的那些。桥的判定插在既有的开通分支里（不连通就不置 `open=true`、不清临时栅栏）；路只在 `status` 里按需判定并报告，不改存档。**不新增任何持久字段。**

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [TRAFFIC_CONNECTIVITY_DESIGN.md](TRAFFIC_CONNECTIVITY_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行（一次 25–40 秒，Bash 超时给 300000 ms）。
- **邻接规则**：水平四邻（**对角不算**），**允许上下差 1 格**（台阶/坡/桥面高于两岸都要算通），差 2 格不算。这条不能省——桥面比两岸高，只走同一层会把每座桥判成不通。
- **只收计划自己声明的走格**，不引入计划之外的格子；一格可行走 = 该处是空气，**或**该格是 `barrierFeet()` 之一且那里是我们自己的 `OAK_FENCE`（施工标记不是地形）。脚下那块是不是声明方块由既有的 `firstMissingStructuralStep` 负责，本判据不重做。
- **判定是只读的**：不放置/破坏方块、不清栅栏、不改存档。桥的判定因此排在 `clearFinishedBarriers` **之前**——不连通时栅栏留在桥上，看上去仍在施工。
- **判定前必须过 `shouldTickBlocksAt`**：`TransportCoordinator` 的两个判定会读方块，tick 路径上调用点已被 `footprintAccess` 挡过，但 `status` 是**只读命令**，绝不能因为判定而强制加载区块。所以显示层对每一条链接先检查"它的声明走格是否都在可 tick 的区块里"，不在的**跳过判定并单独计数**（`n not loaded`），不与"已判定为连通"混为一谈。
- **路的判定按链**：加宽计划自身没有 `targetFacility`、走格也只有新增的那一条，只有整条链才是"这条路"。
- **不新增持久字段、不升 schema**、不新增巡检节拍（桥的闸门在"要开通"那一刻天然只跑一次；路的结论命令触发时现算）。
- **一份事实只留一处**：连通判据只在 `TransportConnectivity.connects`；"哪些格是走格""近岸/对岸怎么定"只在 `TransportCoordinator` 的装配里；"结构是否完整"只由 `firstMissingStructuralStep` 回答。
- 检查项数由 **16 增至 17**（新增 `transportConnectivityCheck`）。构建结束时 17 项必须全部 `*Check passed`。
- 不做游戏内验证（按用户约定）。桥的开通闸、路的报告、`status` 那一行都**不可纯测**，会写进日志与状态文件。
- `goblin-settlement-plan/` 下可能有并行 agent 的未提交改动：文档任务先跑 `git status`，不是自己改的就先单独提交并署名。`UpdateLog.md` **只许在末尾追加**。
- 提交到 `main`，不要另开分支；本轮结束推送。

---

### Task 1: 纯层判据与第 17 项检查

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/planning/transport/TransportConnectivity.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/planning/transport/TransportConnectivityCheck.java`
- Modify: `goblin-settlement-mod/build.gradle`（注册第 17 项）

**Interfaces:**
- Consumes: 无
- Produces: `TransportConnectivity.connects(Set<BlockPos> walkable, BlockPos from, Set<BlockPos> goals) -> boolean`

- [ ] **Step 1: 先写检查（RED）**

创建 `src/test/java/dev/local/goblinsettlement/planning/transport/TransportConnectivityCheck.java`：

```java
package dev.local.goblinsettlement.planning.transport;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

public final class TransportConnectivityCheck {
    public static void main(String[] args) {
        checkStraightLine();
        checkOneBlockRiseAndDrop();
        checkTwoBlockRiseIsNotAWalk();
        checkDiagonalIsNotAWalk();
        checkWallBreaksTheLine();
        checkDetourRestoresIt();
        checkFromMustBeWalkable();
        checkEmptyInputs();
        checkUnreachableGoal();
        checkManyCellsTerminate();
        System.out.println("TransportConnectivityCheck passed");
    }

    private static void checkStraightLine() {
        var cells = set(point(0, 64, 0), point(1, 64, 0), point(2, 64, 0));
        require(TransportConnectivity.connects(cells, point(0, 64, 0), set(point(2, 64, 0))),
                "a straight run connects its two ends");
    }

    private static void checkOneBlockRiseAndDrop() {
        var rise = set(point(0, 64, 0), point(1, 64, 1), point(2, 64, 2));
        require(TransportConnectivity.connects(rise, point(0, 64, 0), set(point(2, 64, 2))),
                "a walker climbs one block per step, as a bridge deck above its bank needs");
        var drop = set(point(0, 64, 2), point(1, 64, 1), point(2, 64, 0));
        require(TransportConnectivity.connects(drop, point(0, 64, 2), set(point(2, 64, 0))),
                "and drops one block per step");
    }

    private static void checkTwoBlockRiseIsNotAWalk() {
        var cells = set(point(0, 64, 0), point(1, 64, 2));
        require(!TransportConnectivity.connects(cells, point(0, 64, 0), set(point(1, 64, 2))),
                "two blocks of height in one step is a jump, not a walk");
    }

    private static void checkDiagonalIsNotAWalk() {
        var cells = set(point(0, 64, 0), point(1, 64, 1));
        require(!TransportConnectivity.connects(cells, point(0, 64, 0), set(point(1, 64, 1))),
                "a diagonal gap is not a crossing");
    }

    private static void checkWallBreaksTheLine() {
        var cells = set(point(0, 64, 0), point(1, 64, 0), point(3, 64, 0), point(4, 64, 0));
        require(!TransportConnectivity.connects(cells, point(0, 64, 0), set(point(4, 64, 0))),
                "a missing cell in the middle breaks the run");
    }

    private static void checkDetourRestoresIt() {
        var cells = set(point(0, 64, 0), point(1, 64, 0), point(3, 64, 0), point(4, 64, 0),
                point(1, 64, 1), point(2, 64, 1), point(3, 64, 1));
        require(TransportConnectivity.connects(cells, point(0, 64, 0), set(point(4, 64, 0))),
                "a way around the missing cell reconnects the run");
    }

    private static void checkFromMustBeWalkable() {
        var cells = set(point(0, 64, 0), point(1, 64, 0));
        require(!TransportConnectivity.connects(cells, point(9, 64, 9), set(point(1, 64, 0))),
                "a walker who cannot stand on the start cell never sets off");
    }

    private static void checkEmptyInputs() {
        require(!TransportConnectivity.connects(Set.of(), point(0, 64, 0), set(point(1, 64, 0))),
                "no cells means no crossing");
        require(!TransportConnectivity.connects(set(point(0, 64, 0)), point(0, 64, 0), Set.of()),
                "no goal means nothing to reach");
    }

    private static void checkUnreachableGoal() {
        var cells = set(point(0, 64, 0), point(1, 64, 0));
        require(!TransportConnectivity.connects(cells, point(0, 64, 0), set(point(5, 64, 5))),
                "a goal outside the walkable cells is unreachable");
        require(TransportConnectivity.connects(cells, point(0, 64, 0), set(point(0, 64, 0))),
                "standing on the goal counts as reaching it");
    }

    /** One call over a road-sized set must finish; the visited set is what bounds it. */
    private static void checkManyCellsTerminate() {
        var cells = new HashSet<BlockPos>();
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 5; z++) {
                cells.add(point(x, 64, z));
            }
        }
        require(TransportConnectivity.connects(cells, point(0, 64, 0), set(point(39, 64, 4))),
                "a two-hundred-cell road is walked once and answers");
        require(!TransportConnectivity.connects(cells, point(0, 64, 0), set(point(0, 70, 0))),
                "and a goal far above it is still unreachable");
    }

    private static Set<BlockPos> set(BlockPos... cells) {
        return new HashSet<>(List.of(cells));
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

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew compileTestJava --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 类 TransportConnectivity`。

- [ ] **Step 3: 写纯判据**

创建 `src/main/java/dev/local/goblinsettlement/planning/transport/TransportConnectivity.java`：

```java
package dev.local.goblinsettlement.planning.transport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Whether a walker can get from one declared walking cell to another. The cells are the road's or the
 * bridge's own, so this answers "is what we built passable end to end" and says nothing about the
 * terrain around it, nor about how the vanilla pathfinder would score the trip.
 */
public final class TransportConnectivity {
    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private TransportConnectivity() {
    }

    /**
     * Walks from from to any of goals, stepping only between cells of walkable. Steps are four-way --
     * never diagonal -- and may rise or drop one block, which is what a walker does over a step, a
     * slope or a deck sitting above its bank. The visited set bounds the work to one visit per cell.
     */
    public static boolean connects(Set<BlockPos> walkable, BlockPos from, Set<BlockPos> goals) {
        if (walkable.isEmpty() || goals.isEmpty() || !walkable.contains(from)) {
            return false;
        }
        var visited = new HashSet<BlockPos>();
        var open = new ArrayDeque<BlockPos>();
        visited.add(from);
        open.add(from);
        while (!open.isEmpty()) {
            BlockPos current = open.poll();
            if (goals.contains(current)) {
                return true;
            }
            for (BlockPos next : neighbours(current)) {
                if (walkable.contains(next) && visited.add(next)) {
                    open.add(next);
                }
            }
        }
        return false;
    }

    private static List<BlockPos> neighbours(BlockPos cell) {
        var neighbours = new ArrayList<BlockPos>(HORIZONTAL.length * 3);
        for (int[] step : HORIZONTAL) {
            for (int drop = -1; drop <= 1; drop++) {
                neighbours.add(new BlockPos(
                        cell.getX() + step[0], cell.getY() + drop, cell.getZ() + step[1]));
            }
        }
        return neighbours;
    }
}
```

- [ ] **Step 4: 注册第 17 项检查**

在 `build.gradle` 里 `roadLayoutCheck` 任务块**之后**、`tasks.named('check')` **之前**插入：

```gradle
tasks.register('transportConnectivityCheck', JavaExec) {
    group = 'verification'
    description = 'Checks where a walker can get along a road or across a bridge.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.planning.transport.TransportConnectivityCheck'
}
```

并在 `tasks.named('check')` 的 `dependsOn` 列表末尾（`dependsOn tasks.named('roadLayoutCheck')` 之后）追加：

```gradle
    dependsOn tasks.named('transportConnectivityCheck')
```

- [ ] **Step 5: 跑完整构建，确认 17 项**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**17 项**检查全部 `*Check passed`（含新的 `TransportConnectivityCheck passed`），无编译警告。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/planning/transport/TransportConnectivity.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/planning/transport/TransportConnectivityCheck.java \
        goblin-settlement-mod/build.gradle
git commit -m "Tell whether a walker can get from one declared cell to another"
```

---

### Task 2: 链上的目标设施

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportSavedData.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/transport/TransportSavedDataCheck.java`

**Interfaces:**
- Consumes: 无（纯存档层）
- Produces:
  - `TransportSavedData.chainOf(String planId) -> List<TransportPlan>`（原私有 `chain` 公开改名）
  - `TransportSavedData.roadTarget(String planId) -> Optional<BlockPos>`

- [ ] **Step 1: 先写检查（RED）**

1a. 把 `TransportSavedDataCheck.java` 里**六参数**的 `roadPlan` 夹具改成"加宽计划不带目标设施"——这才和生产一致（`startWidening` 传的就是 `Optional.empty()`）：

```java
    private static TransportPlan roadPlan(String id, BlockPos target, int completedSteps, boolean open,
                                          int lanes, String widensFrom) {
        boolean widens = !widensFrom.equals(id);
        var route = List.of(new BlockPos(10, 64, 10), new BlockPos(11, 64, 10));
        return new TransportPlan(id, "settlement-1", TransportPlan.Kind.ROAD, List.of(step()),
                completedSteps, open, List.of(), List.of(), List.of(), Optional.empty(),
                widens ? Optional.<BlockPos>empty() : Optional.of(target),
                Optional.of(new TransportPlan.Road(widens ? Optional.of(widensFrom) : Optional.empty(),
                        lanes, route)));
    }
```

并把四参数版与 `plan(...)` 助手也改成共用一个 `step()`：

```java
    private static TransportPlan.Step step() {
        return new TransportPlan.Step(TransportPlan.Phase.SURFACE,
                new BlockPos(10, 64, 10), TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.ROAD_GROUND);
    }
```

（`plan(String id, BlockPos target, int completedSteps, boolean open, Optional<TransportPlan.Road> road)` 里的 `var step = new TransportPlan.Step(...)` 换成 `TransportPlan.Step step = step();`——避免局部变量与方法重名。）

1b. 在 `System.out.println("TransportSavedDataCheck passed");` **之前**插入：

```java
        var targets = new TransportSavedData();
        require(targets.add(roadPlan("target-base", facility, 1, false, 2, "target-base")),
                "the road with a target is accepted");
        require(targets.add(roadPlan("target-child", facility, 1, false, 3, "target-base")),
                "its widening is accepted");
        require(targets.plan("target-child").orElseThrow().targetFacility().isEmpty(),
                "a widening plan names no facility of its own");
        require(targets.roadTarget("target-base").orElseThrow().equals(facility),
                "the road reports the facility it was built for");
        require(targets.roadTarget("target-child").orElseThrow().equals(facility),
                "and its widening answers with the same facility, from the root");
        require(targets.chainOf("target-base").size() == 2, "the chain lists both members");
        require(targets.chainOf("target-child").size() == 2, "from either end");
        require(targets.chainOf("nobody").isEmpty(), "an unknown id belongs to no chain");
        require(targets.roadTarget("nobody").isEmpty(), "and reaches no facility");
```

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew transportSavedDataCheck --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 方法 roadTarget(...)` / `方法 chainOf(...)`。

- [ ] **Step 3: 公开链、加目标查询**

3a. 把 `TransportSavedData` 里的

```java
    private List<TransportPlan> chain(String planId) {
```

改成

```java
    public List<TransportPlan> chainOf(String planId) {
```

并把类内三处调用（`roadWidth`、`roadTraffic`、`roads` 里的 `chain(planId)`）一并改为 `chainOf(planId)`。方法上方的 javadoc 保留，另加一句：**"公开是为了让装配层能一次拿到整条路；链的规则仍然只在这里。"**

3b. 在 `roadTraffic(String planId)` **之后**加：

```java
    /**
     * The facility this road was built to reach. The chain's root registered it; a widening plan
     * deliberately carries none of its own, so the answer comes from whichever member has one.
     */
    public Optional<BlockPos> roadTarget(String planId) {
        for (TransportPlan plan : chainOf(planId)) {
            var target = plan.targetFacility();
            if (target.isPresent()) {
                return target;
            }
        }
        return Optional.empty();
    }
```

- [ ] **Step 4: 跑检查并修正夹具**

Run: `./gradlew transportSavedDataCheck --offline --no-daemon`

Expected: `TransportSavedDataCheck passed`。

若第五十五轮那批链断言因夹具改动而失败，**只改夹具的构造方式，不改断言的含义**。

- [ ] **Step 5: 跑完整构建，确认 17 项**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，17 项全部 `*Check passed`。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportSavedData.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/transport/TransportSavedDataCheck.java
git commit -m "Let a road say which facility its chain was built for"
```

---

### Task 3: 装配走格与桥的开通闸

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java`

**Interfaces:**
- Consumes: `TransportConnectivity.connects`（Task 1）、`TransportSavedData.chainOf` / `roadTarget`（Task 2）
- Produces:
  - `TransportCoordinator.bridgeConnects(ServerLevel level, TransportPlan plan, BlockPos anchor) -> boolean`
  - `TransportCoordinator.roadConnects(ServerLevel level, TransportSavedData traffic, TransportPlan road, BlockPos anchor) -> boolean`
  - `TransportCoordinator.structurallyComplete(ServerLevel level, TransportPlan plan) -> boolean`

- [ ] **Step 1: 加装配助手与两个判定**

在 `TransportCoordinator.clearFinishedBarriers` **之后**、`siteReady` **之前**插入：

```java
    /** The cell a walker stands on for this step: roads and approaches sit on the ground, decks are it. */
    private static BlockPos walkerCell(TransportPlan.Step step) {
        return step.site().above();
    }

    /**
     * Whether a declared walking cell can be walked through right now. Our own temporary barrier fence
     * is not an obstruction -- it is a construction marker, not terrain -- so a finished-but-unopened
     * bridge can be judged while its fences are still up, and a read-only command can judge it at all.
     */
    private static boolean passable(ServerLevel level, TransportPlan plan, BlockPos cell) {
        BlockState state = level.getBlockState(cell);
        if (state.isAir()) {
            return true;
        }
        return plan.barrierFeet().contains(cell) && state.is(Blocks.OAK_FENCE);
    }

    /** The cell nearest a point; ties break by x then z so the answer never wobbles. */
    private static BlockPos nearestOf(Collection<BlockPos> cells, BlockPos to) {
        BlockPos best = null;
        for (BlockPos cell : cells) {
            if (best == null || isCloser(cell, best, to)) {
                best = cell;
            }
        }
        return best;
    }

    private static boolean isCloser(BlockPos candidate, BlockPos incumbent, BlockPos to) {
        double candidateDistance = candidate.distSqr(to);
        double incumbentDistance = incumbent.distSqr(to);
        if (candidateDistance != incumbentDistance) {
            return candidateDistance < incumbentDistance;
        }
        int byX = Integer.compare(candidate.getX(), incumbent.getX());
        return byX != 0 ? byX < 0 : candidate.getZ() < incumbent.getZ();
    }

    /** True while every declared structural cell is in place; false means the plan can be rebuilt. */
    public static boolean structurallyComplete(ServerLevel level, TransportPlan plan) {
        return firstMissingStructuralStep(level, plan) < 0;
    }

    /**
     * Whether a finished bridge can be crossed: a walker on the landing nearest the settlement must
     * reach a landing on the far bank, using only the bridge's own built walking cells. The far bank is
     * the pair of landings farthest from the near one, so no stored landing order is relied on.
     */
    public static boolean bridgeConnects(ServerLevel level, TransportPlan plan, BlockPos anchor) {
        if (plan.barrierFeet().isEmpty()) {
            return false;
        }
        var walkable = new HashSet<BlockPos>();
        for (TransportPlan.Step step : plan.steps()) {
            if (step.phase() != TransportPlan.Phase.SURFACE
                    && step.phase() != TransportPlan.Phase.APPROACHES) {
                continue;
            }
            BlockPos cell = walkerCell(step);
            if (passable(level, plan, cell)) {
                walkable.add(cell);
            }
        }
        BlockPos from = nearestOf(plan.barrierFeet(), anchor);
        var landings = new ArrayList<>(plan.barrierFeet());
        landings.sort((left, right) -> isCloser(left, right, from) ? -1 : (isCloser(right, left, from) ? 1 : 0));
        var goals = new HashSet<>(landings.subList(landings.size() - 2, landings.size()));
        return TransportConnectivity.connects(walkable, from, goals);
    }

    /**
     * Whether a finished road actually reaches the facility its chain was built for: a walker on the
     * cell nearest the settlement must reach a cell beside that facility, using only the road's own
     * built walking cells. Every member of the chain counts -- a widening is part of the road.
     */
    public static boolean roadConnects(ServerLevel level, TransportSavedData traffic,
                                       TransportPlan road, BlockPos anchor) {
        var target = traffic.roadTarget(road.id());
        if (target.isEmpty()) {
            return true; // nothing was recorded to reach, so there is no claim to verify
        }
        BlockPos facility = target.orElseThrow();
        var walkable = new HashSet<BlockPos>();
        var goals = new HashSet<BlockPos>();
        for (TransportPlan member : traffic.chainOf(road.id())) {
            for (TransportPlan.Step step : member.steps()) {
                if (step.phase() != TransportPlan.Phase.SURFACE) {
                    continue;
                }
                BlockPos cell = walkerCell(step);
                if (!passable(level, member, cell)) {
                    continue;
                }
                walkable.add(cell);
                if (besideFacility(cell, facility)) {
                    goals.add(cell);
                }
            }
        }
        if (goals.isEmpty()) {
            return false;
        }
        return TransportConnectivity.connects(walkable, nearestOf(walkable, anchor), goals);
    }

    /** True when the cell is beside the facility: one step horizontally and at most one up or down. */
    private static boolean besideFacility(BlockPos cell, BlockPos facility) {
        int dx = Math.abs(cell.getX() - facility.getX());
        int dz = Math.abs(cell.getZ() - facility.getZ());
        return dx + dz == 1 && Math.abs(cell.getY() - facility.getY()) <= 1;
    }
```

- [ ] **Step 2: 加 import**

在 `TransportCoordinator` 里补上 `java.util.Collection` 与 `java.util.HashSet`，以及 `dev.local.goblinsettlement.planning.transport.TransportConnectivity`（按既有的字母序插入；`java.util.ArrayList` 已在）。

- [ ] **Step 3: 桥开通前判连通**

把 `tickPlan` 里的

```java
            if (plan.kind() == TransportPlan.Kind.WOOD_BRIDGE) {
                // Keep the saved closure until every temporary fence is gone.
                // A failed removal can be retried after a reload without opening early.
                if (clearFinishedBarriers(level, plan)) {
                    traffic.replace(plan.withOpen(true));
                }
            }
```

替换为：

```java
            if (plan.kind() == TransportPlan.Kind.WOOD_BRIDGE) {
                // A crossing nobody can walk is not opened. The saved closure keeps it impassable, and
                // the temporary fences stay up, so a bridge that is not yet crossable still looks like
                // a site rather than a finished bridge behind an invisible wall.
                if (!bridgeConnects(level, plan, settlement.settlement().orElseThrow().anchor())) {
                    return;
                }
                // Keep the saved closure until every temporary fence is gone.
                // A failed removal can be retried after a reload without opening early.
                if (clearFinishedBarriers(level, plan)) {
                    traffic.replace(plan.withOpen(true));
                }
            }
```

- [ ] **Step 4: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，17 项全部 `*Check passed`，无编译警告。

**这一步与 Step 1 的判定都不可纯测**（要真实世界、真实方块、真实权限）：证据只有编译与代码审查。请在报告里如实写明。

- [ ] **Step 5: 核对接线与复用**

Run: `grep -rn "firstMissingStructuralStep\|bridgeConnects\|roadConnects\|structurallyComplete" src/main/java/`

Expected: `firstMissingStructuralStep` 一处定义、两处调用（既有的 `tickPlan`、新的 `structurallyComplete`）；`bridgeConnects` 一处定义、一处调用（`tickPlan`）；`roadConnects` 一处定义（`status` 在 Task 4 调用）；`structurallyComplete` 一处定义（Task 4 调用）。**没有任何地方重新实现"缺格"或"连通"的判断。**

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java
git commit -m "Judge a bridge's crossing and a road's reach from the cells it built"
```

---

### Task 4: status 的连通一行

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`

**Interfaces:**
- Consumes: `TransportCoordinator.bridgeConnects` / `roadConnects` / `structurallyComplete`（Task 3）、`TransportSavedData.roads` / `chainOf`（Task 2 与第五十五轮）
- Produces: `TransportCommands.connectivityLine(ServerLevel level) -> String`

- [ ] **Step 1: 写显示行**

在 `TransportCommands.trafficLine` **之后**、`qualifies` **之前**插入：

```java
    private static final int STATUS_LINK_LIMIT = 8;

    /**
     * Every finished link's connectivity verdict: a road must reach the facility its chain was built
     * for, a bridge must be crossable. Read-only -- it never clears a fence or touches a plan -- and
     * computed on demand, so no inspection cadence and no stored verdict are needed.
     */
    public static String connectivityLine(ServerLevel level) {
        var settlement = SettlementSavedData.get(level);
        if (settlement.settlement().isEmpty()) {
            return "Links: no settlement in this dimension";
        }
        BlockPos anchor = settlement.settlement().orElseThrow().anchor();
        var traffic = TransportSavedData.get(level);
        var broken = new java.util.ArrayList<TransportPlan>();
        int loaded = 0;
        int skipped = 0;
        for (TransportPlan road : traffic.roads()) {
            if (!loaded(level, traffic.chainOf(road.id()))) {
                skipped++;
                continue;
            }
            loaded++;
            if (!TransportCoordinator.roadConnects(level, traffic, road, anchor)
                    || !chainIntact(level, traffic, road)) {
                broken.add(road);
            }
        }
        for (TransportPlan plan : traffic.plans()) {
            if (plan.kind() != TransportPlan.Kind.WOOD_BRIDGE
                    || plan.completedSteps() != plan.steps().size()) {
                continue;
            }
            if (!loaded(level, List.of(plan))) {
                skipped++;
                continue;
            }
            loaded++;
            if (!TransportCoordinator.bridgeConnects(level, plan, anchor)) {
                broken.add(plan);
            }
        }
        String unloaded = skipped == 0 ? "" : ", " + skipped + " not loaded";
        if (broken.isEmpty()) {
            return "Links: all " + loaded + " loaded verified" + unloaded;
        }
        broken.sort((left, right) -> left.id().compareTo(right.id()));
        var builder = new StringBuilder("Links: ").append(broken.size()).append(" of ").append(loaded)
                .append(" not connected: ");
        int shown = Math.min(STATUS_LINK_LIMIT, broken.size());
        for (int index = 0; index < shown; index++) {
            TransportPlan plan = broken.get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(shortId(plan.id())).append(' ')
                    .append(plan.kind() == TransportPlan.Kind.ROAD ? "road" : "bridge")
                    .append('(').append(blockedWord(level, traffic, plan)).append(')');
        }
        if (broken.size() > shown) {
            builder.append(", +").append(broken.size() - shown).append(" more");
        }
        return builder.toString() + unloaded;
    }

    /**
     * Whether every declared walking cell of these plans sits in a ticking chunk. A status command must
     * never force a chunk to load, so a link in an inactive area is counted and left unjudged rather
     * than silently judged from unloaded blocks.
     */
    private static boolean loaded(ServerLevel level, List<TransportPlan> plans) {
        for (TransportPlan plan : plans) {
            for (TransportPlan.Step step : plan.steps()) {
                if (step.phase() == TransportPlan.Phase.BARRIERS) {
                    continue;
                }
                if (!level.shouldTickBlocksAt(step.site().above())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 缺口 = a declared cell is missing and the rebuild path will fix it; 受阻 = something foreign is in the way. */
    private static String blockedWord(ServerLevel level, TransportSavedData traffic, TransportPlan plan) {
        return chainIntact(level, traffic, plan) ? "受阻" : "缺口";
    }

    /** Every member of the road must still be structurally whole; a chain is one road. */
    private static boolean chainIntact(ServerLevel level, TransportSavedData traffic, TransportPlan plan) {
        for (TransportPlan member : traffic.chainOf(plan.id())) {
            if (!TransportCoordinator.structurallyComplete(level, member)) {
                return false;
            }
        }
        return true;
    }
```

需要 `import dev.local.goblinsettlement.colony.SettlementSavedData;`（若尚无）与 `net.minecraft.core.BlockPos`（若尚无）。

- [ ] **Step 2: 接进 status**

在 `GoblinSettlement` 的 `status` 子命令里，`TransportCommands.trafficLine(...)` 那条 `sendSuccess` **之后**加：

```java
                                context.getSource().sendSuccess(() -> Component.literal(
                                        TransportCommands.connectivityLine(level)), false);
```

- [ ] **Step 3: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，17 项全部 `*Check passed`。

- [ ] **Step 4: 核对只读与判定唯一**

Run: `grep -rn "shouldUpgrade\|TransportConnectivity.connects\|setBlock" src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java`

Expected: **只有一处**，即第五十五轮 `trafficLine` 的私有 `qualifies` 调用 `RoadUpgradeRules.shouldUpgrade`（那是**调用**规则，不是重写阈值）。**本轮新增的 `connectivityLine` / `loaded` / `blockedWord` / `chainIntact` 一处都不应命中**——显示层不做连通 BFS、不碰世界、不重写任何判据。

再 Run: `grep -c "shouldTickBlocksAt" src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java`

Expected: **1**——只出现在 `loaded(...)` 里。这是"只读命令不加载区块"的唯一防线，不能少于它，也不该散落多处。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Show which links do not connect, and why"
```

---

### Task 5: 文档与收尾

**Files:**
- Modify: `goblin-settlement-plan/TRAFFIC_CONNECTIVITY_DESIGN.md`（加 §8 落地结果）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Tasks 1–4 的改动与构建结果（**含 `build/libs/goblin-settlement-0.1.0.jar` 的实际字节数、构建秒数、每个提交的哈希**）
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：先单独提交它们并署名，然后再做本任务的文档改动。

- [ ] **Step 2: 设计文档记录落地结果**

在 `TRAFFIC_CONNECTIVITY_DESIGN.md` 末尾追加：

```markdown
## 8. 落地结果（实现后补记）

- **设计期的一处事实修正（写进 §2/§3/§5/§7）**：原先只允许"同一层"的四邻步，那会把**每一座桥**都判成不通（桥面高于两岸）——改为**允许上下差 1 格**（等同台阶/坡），差 2 格仍不算。同时"目标格"改为**取自计划自己的声明走格**（`RoadPlanner` 的路线终点本来就是设施的相邻格），不再引入计划之外的格子。
- **纯判据**：`planning/transport/TransportConnectivity.connects(walkable, from, goals)`——水平四邻、允许上下差 1、访问集把代价限在集合大小。第 17 项独立检查 `transportConnectivityCheck` 覆盖直通/绕行/上下台阶/两格高差不算/对角不算/被墙隔断/起点不可站/空集合/目标不可达/两百格规模。
- **存档层**：`TransportSavedData.chain` 公开为 `chainOf`（链的规则仍只在这一个类里），新增 `roadTarget(planId)`——沿链找第一个带 `targetFacility` 的成员，因此加宽计划（自身不带目标）也能答出它服务的是哪个设施。
- **装配（`TransportCoordinator`）**：`bridgeConnects`（走格 = 自己的 `SURFACE`/`APPROACHES` 走格；近岸 = 四个 `barrierFeet` 中离锚点最近者；对岸 = 离它最远的两个）与 `roadConnects`（走格 = **整条链**的 `ROAD_GROUND` 走格；目标 = 与链上根计划的 `targetFacility` 四邻含 y±1 的走格）。**可行走 = 该处是空气，或该格是 `barrierFeet` 之一且那里是我们自己的 `OAK_FENCE`**——自己的施工栅栏不算地形，所以判定是只读的、能排在清栅栏之前。
- **桥的开通闸**：`tickPlan` 的完成分支里，`firstMissingStructuralStep` 之后、`clearFinishedBarriers` 之前插连通判定；不连通就 `return`——不置 `open=true`、**也不清栅栏**，`closedFootprint` 继续经 `closedFeet` 挡人。
- **路只报告**：`status` 新增一行 `Links: …`，按需现算（无巡检节拍、无持久结论）。统计范围 = 已建成的路（按链，一条一项）+ **所有步已铺完的桥**（含尚未开通的）。后缀 `缺口` = 链上有结构性声明格缺失（既有 `rewind` 会去修）；`受阻` = 结构都在但有走格被外来方块占住（不重修）。
- **明确未做**：外部阻挡不重铺；不判原版寻路的其余规则（跳跃、门、寻路惩罚）；不判"路通往的不是设施"。
```

- [ ] **Step 3: 追加 UpdateLog**

在 `UpdateLog.md` **末尾**追加（`<开始>`/`<结束>`/`<时间>`/`<字节数>`/`<秒数>`/`<哈希>` 换成真实值，**逐条写真实时间，不要留占位**——第四十八、五十、五十五轮都漏填过）：

```markdown
## [<开始> – <结束>] 第五十六轮：道路与桥梁的连通验收

- [<时间>] 按 TRAFFIC_CONNECTIVITY_DESIGN.md 与 TRAFFIC_CONNECTIVITY_PLAN.md 执行，补 GAME_DESIGN 第 7 节桥梁施工顺序的末项「连通验收」，也补上第五十四/五十五两轮留下的"建好了不等于能过"。
- [<时间>] 新增纯层 `planning/transport/TransportConnectivity`：水平四邻（**对角不算**）+ **允许上下差 1 格**（台阶、坡、桥面高于两岸都算一步），访问集把代价限在集合大小。第 17 项独立检查 `transportConnectivityCheck`。
- [<时间>] **设计期修正了一条会毁掉整个功能的事实**：最初写成"只走同一层"，而桥面本来就高于两岸——那样每一座桥都会被判成不通。改为允许上下差 1 格后写进设计 §2/§7。同理，"目标格"改为取自计划自己的声明走格（`RoadPlanner` 的路线终点本就是设施的相邻格），不再引入计划之外的格子。
- [<时间>] `TransportSavedData.chain` 公开为 `chainOf`（链规则仍只在这个类里），新增 `roadTarget(planId)`：沿链找第一个带 `targetFacility` 的成员——加宽计划自身刻意不带目标，所以必须按链回答。
- [<时间>] `TransportCoordinator` 加装配与两个判定：`bridgeConnects`（近岸 = 四个落地格中离锚点最近者，对岸 = 离它最远的两个，不依赖 `barrierFeet` 的存储顺序）、`roadConnects`（走格取**整条链**，目标 = 与链根设施四邻含 y±1 的走格）、`structurallyComplete`（就是既有 `firstMissingStructuralStep` 的取反，不重写判据）。
- [<时间>] **自己的施工栅栏不算地形**：`passable` 把"声明走格上的本方 `OAK_FENCE`"当作可行走。于是桥的判定可以**只读地**排在清栅栏之前——不连通时栅栏留在桥上，桥看上去仍在施工，而不是"建好了却被隐形墙挡着"；`status` 作为只读命令也因此能判定未开通的桥。
- [<时间>] 桥的开通闸：`tickPlan` 完成分支里 `firstMissingStructuralStep` 之后、`clearFinishedBarriers` 之前判连通，不连通就返回（不开通、不清栅栏）。路的连通**只报告**：`status` 加一行 `Links: …`，统计已建成的路（按链）+ 所有步已铺完的桥（含未开通），后缀 `缺口`（结构性缺格，会走既有 rewind）与 `受阻`（走格被外来方块占住，不重修）。**不新增持久字段、不新增巡检节拍。**
- [<时间>] 验证：完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**17 项**独立检查全部 *Check passed（新增第 17 项）。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>，耗时 <秒数>。提交 <哈希>。
- [<时间>] 未完成：不做玩法验收；**桥的开通闸、路的报告、status 那一行都不可纯测**（只有编译与代码审查）。判据只保证"走格在格子层面连得上"，**不承诺**原版寻路真能走过去（不判跳跃、门、寻路惩罚）；外部阻挡按约定不重铺，所以那种路会长期显示 `受阻`。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「接续须知」的**下一步落点**改写：交通剩余第 3 项（连通验收）**已完成**，落点在 `TRAFFIC_CONNECTIVITY_DESIGN.md` §8；交通只剩成熟期多工程并行（架构级）与石桥/更长跨度；下一步仍是"拿真实计数重定 `TRAFFIC_PER_LANE` 与 `SAMPLE_INTERVAL_TICKS`，或统一验证"。
- 「阶段定位」阶段 5 一句补上"第五十六轮补上连通验收（桥开通前必须可跨越、路必须真的接上设施，`status` 一行显示）"。
- 「本轮接入的内容」新增一节「第五十六轮：连通验收」。
- 「交通剩余」第 3 项标为已完成并写明落点；其余项（成熟期多工程并行、石桥与更长跨度）不动。
- 「本轮验证进展」替换为第五十六轮：完整构建、17 项检查、产物字节数与耗时、提交；写明本轮**新增**第 17 项检查；把"独立检查覆盖不到"的清单换成本轮的（桥的开通闸、路的报告、`缺口/受阻` 在真实世界里的区分是否好用）。
- 别处提到"16 项"的地方（若有）一并改为 17 项。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the connectivity acceptance round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。若网络不通，本机需要代理：`git -c http.proxy=http://127.0.0.1:7890 push origin main`。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 判据（四邻、允许上下差 1、访问集为代价上界、`from` 不在集合为假、空集合为假）→ Task 1。
- 设计 §3 可行走定义与两个形态（桥：落地格取 `barrierFeet` 的集合、近岸靠锚点、对岸为最远两个；路：按链、目标为链根设施的四邻走格）→ Task 2（`roadTarget`/`chainOf`）+ Task 3（装配）。
- 设计 §4 桥的开通闸（判在 `clearFinishedBarriers` 之前，不连通则不开通不清栅栏）→ Task 3 Step 3。
- 设计 §4 路只报告、不重铺外部阻挡 → Task 4（`status` 现算，不动存档）。
- 设计 §5 报告（统计范围 = 已建成的路按链 + 所有步铺完的桥；`缺口`/`受阻` 后缀；按 id、上限 8、`+N more`）→ Task 4 Step 1。
- 设计 §6 第 17 项检查与不可纯测面 → Task 1 Step 1/4、Task 3 Step 4 的说明、Task 5 Step 3。
- 设计 §7 风险 → 不判寻路（Task 5 文档）、外部阻挡长期显示（Task 5）、按链而非按计划（Task 3 的 `roadConnects` 与 Task 4 的 `chainIntact`）。

**2. 占位符扫描**

- 无 TBD/TODO。两个新文件、`TransportSavedData`/`TransportCoordinator`/`TransportCommands`/`GoblinSettlement` 的改动都给了可粘贴全文或成对替换。
- Task 2 Step 3a 的"公开链并改三处调用"给了明确的改法与调用点（`roadWidth`、`roadTraffic`、`roads`），不是含糊指令。
- Task 4 Step 1 里 `blockedWord` 与 `chainIntact` 的 javadoc 各带一行说明；`STOP`"缺口/受阻"的判据来自 `structurallyComplete`，不在显示层重写。
- Task 5 的 `<开始>`/`<结束>`/`<时间>`/`<字节数>`/`<秒数>`/`<哈希>` 是模板占位，正文已要求逐条替换为真实值。

**3. 类型一致性**

- `TransportConnectivity.connects(Set<BlockPos>, BlockPos, Set<BlockPos>)` 在 Task 1 的定义、检查、Task 3 的两处调用一致。
- `chainOf(String) -> List<TransportPlan>` 与 `roadTarget(String) -> Optional<BlockPos>` 在 Task 2 定义、Task 3（`roadTarget`/`chainOf`）、Task 4（`chainOf`/`roads`）调用，返回类型一致；Task 4 里 `traffic.roadTarget(...)` 先 `isEmpty()` 再 `orElseThrow()`。
- 三个新公开方法 `bridgeConnects` / `roadConnects` / `structurallyComplete` 的签名在 Task 3 定义、Task 4 调用一致；`roadConnects` 比另两个多一个 `TransportSavedData` 参数（它要按链取走格与目标）。
- `BlockPos` 参与集合运算的三处（`HashSet<BlockPos>`、`nearestOf(Collection<BlockPos>, …)`、`subList` 转 `HashSet`）都用不可变位置——计划里的 `Step.site()` 与 `barrierFeet()` 在 `TransportPlan` 构造时已 `.immutable()`。
- `landings.subList(landings.size() - 2, landings.size())` 要求 `barrierFeet` 至少 2 项；`TransportPlan` 的校验强制桥有**四个** barrier（`barrierFeet.size() != 4` 抛异常），而 `bridgeConnects` 先挡了空表，所以下界成立。
