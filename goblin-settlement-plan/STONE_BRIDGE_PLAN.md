# 石桥与更长跨度 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 加第二种桥（石桥，圆石结构），把可跨跨度从 12 格扩到 24 格，并让"桥种"这件事在代码里只有一个判据。

**Architecture:** `TransportPlan.Kind` 增 `STONE_BRIDGE` 与 `isBridge()`；`BridgePlanner` 的勘察由写死上限改为**按区间参数**，新增石桥的档位常量；"桥种 → 用什么材料"收进新的 `construction/transport/BridgeMaterials`；决策把既有的纯判据 `TrafficDecision.decide` **按两个区间各调一次**（纯层一行不改）；`startWoodBridge` 泛化为 `startBridge(kind)`；命令加一个桥种词参数。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [STONE_BRIDGE_DESIGN.md](STONE_BRIDGE_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行（25–70 秒，Bash 超时给 300000 ms）。
- **存档可见面**：`TransportPlan.Kind` 的取值是持久化的常量名，新增 `STONE_BRIDGE` 不破坏旧档；**已有的 `ROAD` / `WOOD_BRIDGE` 名字不许改**。`BuildMaterial` 只许**新增** `COBBLESTONE`，不许改删已有的四个。
- **一份事实只留一处**：桥种的判据只在 `TransportPlan.isBridge()`；跨度上下限的**数字**只在 `BridgePlanner` 的常量；"哪个桥种用哪些材料、各要多少"只在 `BridgeMaterials`；"这座桥用什么建"只由 `BridgeMaterials` 回答。
- **纯层引用方向**：`planning.*` **不许**依赖 `construction.*`（否则成环）。所以 `BridgeMaterials` 放在 `construction/transport/`，而 `BridgePlanner` 只收 `minSpan`/`maxSpan` 参数、不认识桥种。**设计文档 §3 把它写在 `planning/bridge/` 是错的，本轮按此纠正**，Task 6 要记录。
- **材料与几何不变**：桥仍是四列、走格几何不变、勘察规则不变、深度上限不变（浅水 4 / 峡谷 8）、临时栅栏两种桥都是橡木栅栏、护栏两种桥都是橡木栅栏。**连通验收与 `Links:` 行随 `isBridge()` 自动覆盖石桥**，不新增判据。
- 检查项数由 **18 增至 19**（新增 `bridgeMaterialsCheck`）。构建结束时 19 项必须全部 `*Check passed`。
- **每个任务都必须让整棵树可编译**：改签名/删方法时同步改调用点（下一任务再做完整）。找调用点一律 `grep -rn … src/`（**含测试树**）。
- 不做游戏内验证（按用户约定）。勘察、施工、长跨度稳定性、材料门禁手感都**不可纯测**，会写进日志与状态文件。
- `goblin-settlement-plan/` 下可能有并行 agent 的未提交改动：文档任务先跑 `git status`。`UpdateLog.md` **只许在末尾追加**。
- 提交到 `main`，本轮结束推送（**推送可能因本机代理未运行而失败——那不是任务失败**，如实报告即可）。

---

### Task 1: 桥种、档位与材料清单（纯层）

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportPlan.java`（Kind 增一员 + `isBridge()`）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/planning/bridge/BridgePlanner.java`（按区间勘察 + 石桥档位常量）
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/BridgeMaterials.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/BuildMaterial.java`（增 `COBBLESTONE`）
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/ConstructionMaterialCheck.java`（名字与映射断言扩到五个）
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/transport/BridgeMaterialsCheck.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/planning/transport/TrafficDecisionCheck.java`（档位边界）
- Modify: `goblin-settlement-mod/build.gradle`（注册第 19 项）

**Interfaces:**
- Consumes: 无
- Produces:
  - `TransportPlan.Kind.STONE_BRIDGE`、`TransportPlan.Kind.isBridge() -> boolean`（权威）与委托它的 `TransportPlan.isBridge()`
  - `BridgePlanner.MIN_STONE_SPAN = 13`、`BridgePlanner.MAX_STONE_SPAN = 24`
  - `BridgePlanner.planBridge(ServerLevel, String, BlockPos, Direction, int minSpan, int maxSpan) -> Result`（**取代 `planWoodBridge`**）
  - `BuildMaterial.COBBLESTONE`
  - `BridgeMaterials.deck/support/railing/lighting/barrier(Kind) -> BuildMaterial`、`BridgeMaterials.required(Kind) -> Map<BuildMaterial, Integer>`、`BridgeMaterials.minSpan/maxSpan(Kind) -> int`

- [ ] **Step 1: 先写检查（RED）**

1a. 创建 `src/test/java/dev/local/goblinsettlement/construction/transport/BridgeMaterialsCheck.java`：

```java
package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.construction.BuildMaterial;
import dev.local.goblinsettlement.planning.bridge.BridgePlanner;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class BridgeMaterialsCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkWoodBridgeMaterials();
        checkStoneBridgeMaterials();
        checkTheTwoListsDifferOnlyWhereTheyShould();
        checkKindsAreGuarded();
        checkOnlyOnePlaceAnswersWhatABridgeIs();
        checkSpanLadderMatchesTheDesign();
        System.out.println("BridgeMaterialsCheck passed");
    }

    private static void checkWoodBridgeMaterials() {
        var kind = TransportPlan.Kind.WOOD_BRIDGE;
        require(BridgeMaterials.deck(kind) == BuildMaterial.OAK_PLANKS, "a wooden deck is planks");
        require(BridgeMaterials.support(kind) == BuildMaterial.OAK_LOG, "wooden supports are logs");
        require(BridgeMaterials.railing(kind) == BuildMaterial.OAK_FENCE, "wooden railings are fences");
        require(BridgeMaterials.lighting(kind) == BuildMaterial.TORCH, "lighting is torches");
        require(BridgeMaterials.barrier(kind) == BuildMaterial.OAK_FENCE,
                "the temporary construction barriers are fences");
    }

    private static void checkStoneBridgeMaterials() {
        var kind = TransportPlan.Kind.STONE_BRIDGE;
        require(BridgeMaterials.deck(kind) == BuildMaterial.COBBLESTONE, "a stone deck is cobblestone");
        require(BridgeMaterials.support(kind) == BuildMaterial.COBBLESTONE, "stone supports are cobblestone");
        require(BridgeMaterials.railing(kind) == BuildMaterial.OAK_FENCE,
                "a stone bridge still uses wooden railings: the economy produces no stone railing");
        require(BridgeMaterials.lighting(kind) == BuildMaterial.TORCH, "lighting is still torches");
    }

    /** The two kinds differ in exactly one thing: what the deck and the supports are made of. */
    private static void checkTheTwoListsDifferOnlyWhereTheyShould() {
        var wood = BridgeMaterials.required(TransportPlan.Kind.WOOD_BRIDGE);
        var stone = BridgeMaterials.required(TransportPlan.Kind.STONE_BRIDGE);
        require(wood.containsKey(BuildMaterial.OAK_PLANKS) && wood.containsKey(BuildMaterial.OAK_LOG),
                "the wooden kind needs planks and logs");
        require(!stone.containsKey(BuildMaterial.OAK_PLANKS) && !stone.containsKey(BuildMaterial.OAK_LOG),
                "the stone kind uses no wood for its deck or its supports");
        require(stone.containsKey(BuildMaterial.COBBLESTONE), "the stone kind needs cobblestone");
        for (BuildMaterial shared : List.of(BuildMaterial.OAK_FENCE, BuildMaterial.TORCH)) {
            require(wood.containsKey(shared) && stone.containsKey(shared),
                    shared + " is needed by both kinds");
        }
        for (int floor : wood.values()) {
            require(floor > 0, "every material has a positive start floor");
        }
        for (int floor : stone.values()) {
            require(floor > 0, "every material has a positive start floor");
        }
    }

    private static void checkKindsAreGuarded() {
        require(TransportPlan.Kind.WOOD_BRIDGE.isBridge(), "a wooden bridge is a bridge");
        require(TransportPlan.Kind.STONE_BRIDGE.isBridge(), "a stone bridge is a bridge");
        require(!TransportPlan.Kind.ROAD.isBridge(), "a road is not a bridge");
        require(List.of(TransportPlan.Kind.values()).contains(TransportPlan.Kind.STONE_BRIDGE),
                "the stone kind is persisted under this name");
    }

    private static void checkOnlyOnePlaceAnswersWhatABridgeIs() {
        require(BridgeMaterials.minSpan(TransportPlan.Kind.WOOD_BRIDGE) == BridgePlanner.MIN_WOOD_SPAN,
                "the wooden kind starts at the wooden rung");
        require(BridgeMaterials.maxSpan(TransportPlan.Kind.WOOD_BRIDGE) == BridgePlanner.MAX_WOOD_SPAN,
                "and ends at it");
        require(BridgeMaterials.minSpan(TransportPlan.Kind.STONE_BRIDGE) == BridgePlanner.MIN_STONE_SPAN,
                "the stone kind starts at the stone rung");
        require(BridgeMaterials.maxSpan(TransportPlan.Kind.STONE_BRIDGE) == BridgePlanner.MAX_STONE_SPAN,
                "and ends at it");
    }

    private static void checkSpanLadderMatchesTheDesign() {
        require(BridgePlanner.MIN_WOOD_SPAN == 4 && BridgePlanner.MAX_WOOD_SPAN == 12,
                "the wooden span is the documented 4 to 12");
        require(BridgePlanner.MIN_STONE_SPAN == 13 && BridgePlanner.MAX_STONE_SPAN == 24,
                "the stone span picks up at 13 and reaches the documented 24");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

1b. 在 `TrafficDecisionCheck` 里加档位边界断言。先读该文件现有的 `main` 与辅助方法（它已经按列构造用例），把下面这个方法加进去、并在 `main` 里调用它（**辅助构造方式照抄同文件既有的写法**）：

```java
    /**
     * The two bridge rungs must partition the water widths without overlapping: a 12-wide run is the
     * wooden bridge's last, and a 13-wide run is the stone bridge's first.
     */
    private static void checkSpanRungsPartitionTheWater() {
        require(TrafficDecision.decide(waterRun(12), 13, 4, 12).kind() == TrafficDecision.Kind.BRIDGE,
                "twelve columns is a wooden bridge");
        require(TrafficDecision.decide(waterRun(12), 13, 13, 24).kind() == TrafficDecision.Kind.ROAD,
                "and not a stone one");
        require(TrafficDecision.decide(waterRun(13), 14, 4, 12).kind() == TrafficDecision.Kind.ROAD,
                "thirteen columns is past the wooden rung");
        require(TrafficDecision.decide(waterRun(13), 14, 13, 24).kind() == TrafficDecision.Kind.BRIDGE,
                "and is the stone bridge's first");
        require(TrafficDecision.decide(waterRun(24), 25, 13, 24).kind() == TrafficDecision.Kind.BRIDGE,
                "twenty-four columns is still a stone bridge");
        require(TrafficDecision.decide(waterRun(25), 26, 13, 24).kind() == TrafficDecision.Kind.ROAD,
                "twenty-five is beyond both rungs, so the corridor falls back to the road rule");
    }

    /** A straight-line sample: land, then this many water columns, then land, with the target on land. */
    private static List<TrafficDecision.ColumnKind> waterRun(int water) {
        var columns = new java.util.ArrayList<TrafficDecision.ColumnKind>();
        columns.add(TrafficDecision.ColumnKind.LAND);
        for (int index = 0; index < water; index++) {
            columns.add(TrafficDecision.ColumnKind.WATER);
        }
        columns.add(TrafficDecision.ColumnKind.LAND);
        return columns;
    }
```

（目标下标要落在最后一格陆地上：宽度为 `w` 时列数是 `w + 2`，目标下标是 `w + 1` ✓ 与上面各行的实参一致。）

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew compileTestJava --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 变量 STONE_BRIDGE` / `类 BridgeMaterials`。

- [ ] **Step 3: `TransportPlan` 增桥种与判据**

3a. 枚举加一员（**放在末尾**，不改已有名字）：

```java
    public enum Kind {
        ROAD, WOOD_BRIDGE, STONE_BRIDGE
    }
```

3b. 紧凑构造器里那句桥的校验（`kind == Kind.WOOD_BRIDGE && ...`）改为用新判据，并在**枚举上**加权威方法、在记录上留一个委托：

`Kind` 枚举里加：

```java
        /** True for every bridge kind; a new kind is taught to the whole line by this one answer. */
        public boolean isBridge() {
            return this == WOOD_BRIDGE || this == STONE_BRIDGE;
        }
```

记录上加一个委托（供手里拿着一个计划的调用方，例如 `TransportSavedData.index`）：

```java
    /** True for every bridge kind; a new kind is taught to the whole line by this one answer. */
    public boolean isBridge() {
        return kind.isBridge();
    }
```

校验那句改为：

```java
        if (kind.isBridge() && (barrierFeet.size() != 4 || closedFootprint.isEmpty())) {
            throw new IllegalArgumentException("Bridge needs four barriers and a closed footprint");
        }
```

（**权威在枚举上**、记录只委托：第一步的检查就是在 `Kind` 的常量上调用 `isBridge()` 的。）

- [ ] **Step 4: `BridgePlanner` 按区间勘察**

4a. 常量区（`MIN_WOOD_SPAN` / `MAX_WOOD_SPAN` 之后）加：

```java
    public static final int MIN_STONE_SPAN = 13;
    public static final int MAX_STONE_SPAN = 24;
```

4b. `planWoodBridge(ServerLevel, String, BlockPos, Direction)` 改名为 `planBridge(..., int minSpan, int maxSpan)`，并把方法体里三处**写死的上限**换成参数：

- 循环上界 `for (int step = 1; step <= MAX_WOOD_SPAN + 1; step++)` → `step <= maxSpan + 1`；
- 太短判定 `if (span < MIN_WOOD_SPAN)` → `if (span < minSpan)`；
- 太长判定 `if (step > MAX_WOOD_SPAN)` → `if (step > maxSpan)`；

三个列表的初始容量可用 `maxSpan`（纯优化）。方法 javadoc 里"wooden bridge"的说法改成中性的"a bridge"，并写明区间由调用方给。

- [ ] **Step 5: `BridgeMaterials`**

创建 `src/main/java/dev/local/goblinsettlement/construction/transport/BridgeMaterials.java`：

```java
package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.construction.BuildMaterial;
import dev.local.goblinsettlement.planning.bridge.BridgePlanner;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What each kind of bridge is built from, and how much of it must be on hand to start one. One
 * authority: the builder's every step, the proposal's material gate and the command all ask here, so a
 * new kind cannot be half-taught.
 *
 * <p>The temporary construction barriers and the railings stay wooden on both kinds. The barriers are
 * not part of the finished crossing, and a stone railing would need the economy to produce one first.
 */
public final class BridgeMaterials {
    /** A coarse "there is enough to begin" floor per material; the real cost is the structure itself. */
    private static final int PLANK_FLOOR = 8;
    private static final int LOG_FLOOR = 4;
    private static final int FENCE_FLOOR = 4;
    private static final int TORCH_FLOOR = 4;
    /**
     * INVENTED: nothing in the design document gives a stone floor. It is the same shape as the wooden
     * plank floor -- enough to lay a few columns -- and must be re-chosen once a stone bridge has been
     * built and watched.
     */
    private static final int COBBLESTONE_FLOOR = 8;

    private BridgeMaterials() {
    }

    public static BuildMaterial deck(TransportPlan.Kind kind) {
        return kind == TransportPlan.Kind.STONE_BRIDGE
                ? BuildMaterial.COBBLESTONE : BuildMaterial.OAK_PLANKS;
    }

    public static BuildMaterial support(TransportPlan.Kind kind) {
        return kind == TransportPlan.Kind.STONE_BRIDGE
                ? BuildMaterial.COBBLESTONE : BuildMaterial.OAK_LOG;
    }

    public static BuildMaterial railing(TransportPlan.Kind kind) {
        return BuildMaterial.OAK_FENCE;
    }

    public static BuildMaterial lighting(TransportPlan.Kind kind) {
        return BuildMaterial.TORCH;
    }

    public static BuildMaterial barrier(TransportPlan.Kind kind) {
        return BuildMaterial.OAK_FENCE;
    }

    /** The span this kind is surveyed for; the numbers live in {@link BridgePlanner}. */
    public static int minSpan(TransportPlan.Kind kind) {
        return kind == TransportPlan.Kind.STONE_BRIDGE
                ? BridgePlanner.MIN_STONE_SPAN : BridgePlanner.MIN_WOOD_SPAN;
    }

    public static int maxSpan(TransportPlan.Kind kind) {
        return kind == TransportPlan.Kind.STONE_BRIDGE
                ? BridgePlanner.MAX_STONE_SPAN : BridgePlanner.MAX_WOOD_SPAN;
    }

    /** The materials this kind needs before it can start, each with the floor it is checked against. */
    public static Map<BuildMaterial, Integer> required(TransportPlan.Kind kind) {
        var required = new LinkedHashMap<BuildMaterial, Integer>();
        required.put(deck(kind), kind == TransportPlan.Kind.STONE_BRIDGE
                ? COBBLESTONE_FLOOR : PLANK_FLOOR);
        required.put(support(kind), kind == TransportPlan.Kind.STONE_BRIDGE
                ? COBBLESTONE_FLOOR : LOG_FLOOR);
        required.put(railing(kind), FENCE_FLOOR);
        required.put(lighting(kind), TORCH_FLOOR);
        return Map.copyOf(required);
    }
}
```

（`deck` 与 `support` 在石桥下都是 `COBBLESTONE`，`LinkedHashMap` 的 `put` 因此只会留一个键——**石桥需要三种材料，木桥需要四种**，两者的差别只在"桥面/支撑用什么"。检查里断言的是这条语义，不是"两边键数相同"。）

- [ ] **Step 6: `BuildMaterial` 增 `COBBLESTONE` 并更新既有的名字/映射断言**

6a. `BuildMaterial` 枚举末尾加：

```java
    COBBLESTONE(Blocks.COBBLESTONE, Items.COBBLESTONE);
```

6b. `ConstructionMaterialCheck.checkStoredNamesAreStable` 的期望列表改为五个名字（**顺序与枚举一致**）：

```java
        require(names.equals(List.of("OAK_PLANKS", "OAK_LOG", "OAK_FENCE", "TORCH", "COBBLESTONE")),
                "the stored material names are the ones already in saves");
```

6c. `checkEachMaterialMapsToItsOwnBlockAndItem` 里补两行：

```java
        require(BuildMaterial.COBBLESTONE.block() == Blocks.COBBLESTONE, "cobblestone builds cobblestone");
        require(BuildMaterial.COBBLESTONE.item() == Items.COBBLESTONE, "cobblestone is fetched as cobblestone");
```

- [ ] **Step 7: 注册第 19 项检查**

在 `build.gradle` 里 `constructionMaterialCheck` 任务块**之后**插入：

```gradle
tasks.register('bridgeMaterialsCheck', JavaExec) {
    group = 'verification'
    description = 'Checks which materials each bridge kind is built from, and the span rungs.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.construction.transport.BridgeMaterialsCheck'
}
```

并在 `tasks.named('check')` 的 `dependsOn` 列表末尾追加：

```gradle
    dependsOn tasks.named('bridgeMaterialsCheck')
```

- [ ] **Step 8: 让调用点跟着编译**

`BridgePlanner.planWoodBridge` 改名后，唯一调用点 `TransportCoordinator.startWoodBridge` 里的

```java
        BridgePlanner.Result result = BridgePlanner.planWoodBridge(
                level, settlementId, nearBankFoot, direction);
```

改为（**本任务只做这一步，`startBridge` 的重构留给 Task 3**）：

```java
        BridgePlanner.Result result = BridgePlanner.planBridge(
                level, settlementId, nearBankFoot, direction,
                BridgePlanner.MIN_WOOD_SPAN, BridgePlanner.MAX_WOOD_SPAN);
```

- [ ] **Step 9: 跑完整构建，确认 19 项**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**19 项**检查全部 `*Check passed`（含新的 `BridgeMaterialsCheck passed`）。

- [ ] **Step 10: 核对没有第二份事实**

Run: `grep -rn "planWoodBridge\|MAX_WOOD_SPAN\|MIN_WOOD_SPAN" src/`

Expected: 常量只在 `BridgePlanner` 定义、在 `BridgeMaterials` 使用；**`TrafficProposalCoordinator` 里那处把 `MIN_WOOD_SPAN/MAX_WOOD_SPAN` 传给 `TrafficDecision.decide` 的调用是既有的**（Task 4 才会把它换成两档），本任务不要动它；`planWoodBridge` **一处不剩**。

- [ ] **Step 11: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportPlan.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/BridgeMaterials.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/planning/bridge/BridgePlanner.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/BuildMaterial.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/transport/BridgeMaterialsCheck.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/ConstructionMaterialCheck.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/planning/transport/TrafficDecisionCheck.java \
        goblin-settlement-mod/build.gradle
git commit -m "Add a stone bridge kind, its materials and its span rung"
```

---

### Task 2: 桥种判据收口（9 处）

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportSavedData.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java`

**Interfaces:**
- Consumes: `TransportPlan.isBridge()`（Task 1）
- Produces: 全仓不再有 `kind == Kind.WOOD_BRIDGE` 这类**逐枚举**判断（`TransportPlan` 自己的 `isBridgeKind` 除外）

- [ ] **Step 1: 先数清楚**

Run: `grep -rn "WOOD_BRIDGE" src/`

Expected: 逐条判断每一处该不该改。**要改成 `isBridge()` 的**：`TransportSavedData` 的 `index`/`unindex`（openBridges / closedFeet 两支）、`TransportCoordinator` 的 `tickPlan`（栅栏复查、开通分支、连通闸）与 `TransportCommands` 的桥筛选、以及任何 `kind != Kind.WOOD_BRIDGE` 形式的判断。**不要改的**：`TransportPlan` 内部那两条（枚举定义与 `isBridgeKind`）、以及测试夹具里"造一座木桥"的地方（那里就该指名 `WOOD_BRIDGE`）。

**这一步的判断依据是**：凡是"这在问'这是不是一座桥'"的地方收口；凡是"这在指明要造哪种桥"的地方保持指名。

- [ ] **Step 2: 逐个改判据**

把每一处"是不是桥"的判断改成 `plan.isBridge()`（或 `kind.isBridge()`，视变量在手的是哪个），**语义一行不变**。每改一处都在脑子里跑一遍：木桥的结论必须与改前完全一致；石桥因此自动获得同样的对待（封闭区挡人、开通要过连通闸、进 `Links:` 行、进 openBridges 巡检）。

- [ ] **Step 3: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，19 项全部 `*Check passed`。

- [ ] **Step 4: 核对收口**

Run: `grep -rn "kind() [!=]= Kind\.\(WOOD\|STONE\)_BRIDGE\|kind [!=]= Kind\.\(WOOD\|STONE\)_BRIDGE" src/main/java/`

Expected: **一条都没有**——"这是不是一座桥"已全部走 `isBridge()`。

再 Run: `grep -rn "WOOD_BRIDGE" src/main/java/`

Expected: 剩下的**都是"指名某种桥"**：`TransportPlan` 的枚举定义与 `isBridge()` 权威、以及 `startWoodBridge` 里构造计划时写下的那一个（它就是在指明自己造的是木桥，Task 3 会把它换成 `kind`）。`BridgeMaterials` 里没有这个字面量是对的——它用"不是石桥就是木桥"的回落写法。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportSavedData.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java
git commit -m "Ask whether a plan is a bridge instead of naming one kind"
```

---

### Task 3: 按桥种建桥 + 命令的桥种参数

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TrafficProposalCoordinator.java`（**仅**让调用点编译，见 Step 3）

**Interfaces:**
- Consumes: `BridgeMaterials`（Task 1）、`TransportPlan.isBridge()`（Task 2）
- Produces: `TransportCoordinator.startBridge(ServerLevel, String settlementId, BlockPos nearBankFoot, Direction direction, TransportPlan.Kind kind) -> StartResult`（**取代 `startWoodBridge`**）

- [ ] **Step 1: `startWoodBridge` 泛化为 `startBridge`**

把 `public static StartResult startWoodBridge(...)` 的方法头改为：

```java
    /** Enqueue a four-column bridge of this kind toward the far bank. */
    public static StartResult startBridge(ServerLevel level, String settlementId,
                                          BlockPos nearBankFoot, Direction direction,
                                          TransportPlan.Kind kind) {
```

方法体里六处材料改为按桥种（`BridgeMaterials`）：

| 相位 | 原来 | 改为 |
| --- | --- | --- |
| `BARRIERS` | `BuildMaterial.OAK_FENCE` | `BridgeMaterials.barrier(kind)` |
| `APPROACHES` | `BuildMaterial.OAK_PLANKS` | `BridgeMaterials.deck(kind)` |
| `SUPPORTS` | `BuildMaterial.OAK_LOG` | `BridgeMaterials.support(kind)` |
| `SURFACE` | `BuildMaterial.OAK_PLANKS` | `BridgeMaterials.deck(kind)` |
| `RAILINGS` | `BuildMaterial.OAK_FENCE` | `BridgeMaterials.railing(kind)` |
| `LIGHTING` | `BuildMaterial.TORCH` | `BridgeMaterials.lighting(kind)` |

勘察那一步改为按桥种的区间：

```java
        BridgePlanner.Result result = BridgePlanner.planBridge(
                level, settlementId, nearBankFoot, direction,
                BridgeMaterials.minSpan(kind), BridgeMaterials.maxSpan(kind));
```

计划构造里 `TransportPlan.Kind.WOOD_BRIDGE` 改为 `kind`。**其余一律不动**：拒绝理由字符串、`closedFootprint` 的构成、`conflictsWithExistingWork` 那一遍检查、四列几何、`barriers` 的顺序。

- [ ] **Step 2: 命令加桥种词参数**

`traffic bridge` 现在有两段参数，把第三段接成可选的材料词（**照抄 `plan` 子命令的接法**：`executes` 必须在最内层）。命令树变成：

```
literal("bridge") → requires(moderator)
    → argument("near_bank_foot", blockPos())
        → then(argument("direction", word())
            → then(argument("kind", word()) → executes(...)))     // 显式给了桥种
        → executes(...)                                            // 没给，默认木桥
```

两端 `executes` 共用同一个私有助手：

```java
    /** Start a bridge of the named kind, defaulting to the wooden one when the word is absent. */
    private static int startBridgeCommand(CommandSourceStack source, com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                          String kindWord) {
        String id = settlementId(source.getLevel());
        if (id == null) {
            source.sendFailure(Component.literal("No settlement in this dimension"));
            return 0;
        }
        Direction direction = horizontalDirection(StringArgumentType.getString(context, "direction"));
        if (direction == null) {
            source.sendFailure(Component.literal("Use north, east, south, or west"));
            return 0;
        }
        TransportPlan.Kind kind = TransportPlan.Kind.WOOD_BRIDGE;
        if (kindWord != null) {
            kind = switch (kindWord.toLowerCase(Locale.ROOT)) {
                case "wood" -> TransportPlan.Kind.WOOD_BRIDGE;
                case "stone" -> TransportPlan.Kind.STONE_BRIDGE;
                default -> null;
            };
            if (kind == null) {
                source.sendFailure(Component.literal("Use wood or stone"));
                return 0;
            }
        }
        return report(source, TransportCoordinator.startBridge(source.getLevel(), id,
                BlockPosArgument.getBlockPos(context, "near_bank_foot"), direction, kind));
    }
```

（`kindWord` 由参数写成 `StringArgumentType.getString(context, "kind")`，默认那支传 `null`。原有的 `startWoodBridge` 调用整段删除。）

- [ ] **Step 3: 让提案侧跟着编译（最小改动）**

`TrafficProposalCoordinator.proposeBridge` 里那句 `TransportCoordinator.startWoodBridge(...)` 改为 `TransportCoordinator.startBridge(..., TransportPlan.Kind.WOOD_BRIDGE)`——**行为与今天一致**；按桥种选档位与按桥种的材料门禁留给 Task 4。

- [ ] **Step 4: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，19 项全部 `*Check passed`。

**本任务不可纯测**（要真实世界与真实地形）：证据只有编译、grep 与代码审查。请在报告里如实写明。

- [ ] **Step 5: 核对材料只有一个出处**

Run: `grep -rn "BuildMaterial\.\(OAK_PLANKS\|OAK_LOG\|OAK_FENCE\|TORCH\|COBBLESTONE\)" src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java`

Expected: **一条都没有**——桥的用料全部经 `BridgeMaterials`（同一文件里 `startRoad` 的道路用料仍直接指名 `OAK_PLANKS`，那是**道路**的用料规则，属它自己，不在本轮范围）。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TrafficProposalCoordinator.java
git commit -m "Build whichever kind of bridge was asked for"
```

---

### Task 4: 按档位选桥 + 按桥种的材料门禁

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TrafficProposalCoordinator.java`

**Interfaces:**
- Consumes: `BridgeMaterials`（Task 1）、`startBridge`（Task 3）、`TrafficDecision`（既有）
- Produces: 提案按 4–12 / 13–24 两档选桥；`proposeBridge` 的门禁按桥种的材料表

- [ ] **Step 1: 两档判定**

把 `TrafficProposalCoordinator.tick` 里

```java
        var decision = TrafficDecision.decide(sample.columns(), sample.targetIndex(),
                BridgePlanner.MIN_WOOD_SPAN, BridgePlanner.MAX_WOOD_SPAN);
        switch (decision.kind()) {
            case BRIDGE -> proposeBridge(level, settlement, traffic, settlementId, anchor,
                    targetPos, decision);
```

替换为（**先窄后宽**，两次调用同一个纯判据；两次都不成时按既有语义落到 ROAD/NONE 那两支）：

```java
        var decision = TrafficDecision.decide(sample.columns(), sample.targetIndex(),
                BridgePlanner.MIN_WOOD_SPAN, BridgePlanner.MAX_WOOD_SPAN);
        TransportPlan.Kind kind = decision.kind() == TrafficDecision.Kind.BRIDGE
                ? TransportPlan.Kind.WOOD_BRIDGE : null;
        if (kind == null) {
            decision = TrafficDecision.decide(sample.columns(), sample.targetIndex(),
                    BridgePlanner.MIN_STONE_SPAN, BridgePlanner.MAX_STONE_SPAN);
            if (decision.kind() == TrafficDecision.Kind.BRIDGE) {
                kind = TransportPlan.Kind.STONE_BRIDGE;
            }
        }
        switch (kind == null ? decision.kind() : TrafficDecision.Kind.BRIDGE) {
            case BRIDGE -> proposeBridge(level, settlement, traffic, settlementId, anchor,
                    targetPos, decision, kind);
```

（`ROAD` / `NONE` 两支的代码**一个字不改**——宽度超过 24 时两次判定都给 `ROAD`，于是照旧去试修路、失败则延期。）

- [ ] **Step 2: `proposeBridge` 按桥种取材料**

`proposeBridge` 的签名加最后一个参数 `TransportPlan.Kind kind`，方法体开头那串四材料门禁替换为：

```java
        for (var entry : BridgeMaterials.required(kind).entrySet()) {
            if (PublicWarehouseInventory.countOf(level, settlement, entry.getKey().item())
                    < entry.getValue()) {
                return; // material shortage is not a proposal failure; supplies will catch up
            }
        }
```

并把 `TransportCoordinator.startWoodBridge(...)` 改为 `TransportCoordinator.startBridge(..., kind)`；桥失败回退修路那一段**不动**。原来那四个 `BRIDGE_MIN_*` 常量若因此没有别的使用者，**删掉**（项目不留没有调用者的常量）。

- [ ] **Step 3: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，19 项全部 `*Check passed`。

- [ ] **Step 4: 核对档位与门禁只有一处**

Run: `grep -rn "MIN_STONE_SPAN\|MAX_STONE_SPAN\|BridgeMaterials" src/main/java/`

Expected: 常量只在 `BridgePlanner` 定义；`BridgeMaterials` 的调用者只有 `TransportCoordinator.startBridge`（用料与区间）与 `TrafficProposalCoordinator.proposeBridge`（门禁）。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TrafficProposalCoordinator.java
git commit -m "Choose the bridge rung by water width and gate by its materials"
```

---

### Task 5: 文档与收尾

**Files:**
- Modify: `goblin-settlement-plan/STONE_BRIDGE_DESIGN.md`（加 §8 落地结果 + 纠正 §3 的放置）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Tasks 1–4 的改动与构建结果（**含 `build/libs/goblin-settlement-0.1.0.jar` 的实际字节数、构建秒数、每个提交的哈希**）
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：先单独提交它们并署名，然后再做本任务的文档改动。

- [ ] **Step 2: 设计文档记录落地结果**

2a. 纠正 §3 里那句 `planning/bridge/BridgeMaterials`：实际落在 **`construction/transport/BridgeMaterials`**，因为 `planning.*` 不许依赖 `construction.*`（`TransportPlan.Kind` 在 construction 下），否则成环。**这是设计文档写错、实现纠正**，改在设计文档里并说明理由。

2b. 末尾追加：

```markdown
## 8. 落地结果（实现后补记）

- **桥种**：`TransportPlan.Kind` 增 `STONE_BRIDGE`，并加 `isBridge()` 作为"这是不是一座桥"的唯一判据；全仓原先 9 处逐枚举判断全部改走它（主代码里 `WOOD_BRIDGE` 只剩枚举定义与 `isBridgeKind` 两处）。
- **档位**：`BridgePlanner` 增 `MIN_STONE_SPAN = 13` / `MAX_STONE_SPAN = 24`，勘察由写死上限改为**按区间参数**（`planWoodBridge` → `planBridge(..., minSpan, maxSpan)`），木石共用一套勘察。决策是**把既有的纯判据 `TrafficDecision.decide` 按两个区间各调一次**——`TrafficDecision` 一行未改，只加了边界断言（12／13／24／25 两侧）。
- **材料**：`BuildMaterial` 增 `COBBLESTONE`（只许新增；四个已有名字仍是存档取值）；`construction/transport/BridgeMaterials` 成为"哪个桥种用什么材料、各要多少才开工"的唯一出处（**注意它落在 construction 而不是设计稿写的 planning**——`planning.*` 不许依赖 `construction.*`）。石桥的桥面/支撑/引道用圆石，护栏与临时栅栏仍是橡木栅栏（理由见 §3）。
- **材料门禁**：`proposeBridge` 改为遍历 `BridgeMaterials.required(kind)`，每个材料各自一个"够开工"的下限；原先四个 `BRIDGE_MIN_*` 常量随之搬进 `BridgeMaterials`（`COBBLESTONE` 的下限是**发明值**，源码注释里已标）。
- **命令**：`traffic bridge` 增加可选的桥种词（`wood` / `stone`，缺省木桥，不认识则明确失败）。
- **连通验收自动覆盖石桥**：第五十六轮的判定与 `Links:` 行走的是 `isBridge()`，本轮没有新增任何连通判据。
- **检查**：新增第 19 项 `bridgeMaterialsCheck`（桥种判据、两套材料清单只差桥面/支撑、区间常量与设计一致）；`TrafficDecisionCheck` 补档位边界；`ConstructionMaterialCheck` 的名字与映射断言扩到五个。**检查总数 19**。
- **仍未做**：圆石墙护栏（需经济先能产出）；更深的河床与峡谷（深度上限未动）；>24 格水面仍不可跨（两次判定都出界 → 试修路 → 延期）；长桥更贵更慢且仍占唯一一个交通工程名额。
```

- [ ] **Step 3: 追加 UpdateLog**

在 `UpdateLog.md` **末尾**追加（`<...>` 换成真实值，**逐条写真实时间，不要留占位**——第四十八、五十、五十五、五十七轮都漏填过）：

```markdown
## [<开始> – <结束>] 第五十八轮：石桥与更长跨度

- [<时间>] 按 STONE_BRIDGE_DESIGN.md 与 STONE_BRIDGE_PLAN.md 执行：加第二种桥（石桥，圆石结构），把可跨跨度从 12 格扩到 24 格。GAME_DESIGN 第 7 节给的就是"木桥约 4～12、石桥约 12～24"。
- [<时间>] **桥种收口**：`TransportPlan` 增 `STONE_BRIDGE` 与 `isBridge()`；全仓原先 9 处 `kind == WOOD_BRIDGE` 改走它，主代码里只剩枚举定义与 `isBridgeKind` 两处。第五十六轮的连通验收与 `Links:` 行因此自动覆盖石桥（走格几何相同，没有新增判据）。
- [<时间>] **档位**：`BridgePlanner` 增 `MIN_STONE_SPAN = 13` / `MAX_STONE_SPAN = 24`，勘察改为**按区间参数**（`planWoodBridge` → `planBridge`），木石共用一套。决策是**把既有纯判据 `TrafficDecision.decide` 按两个区间各调一次**——先 4–12 判木桥、不成再 13–24 判石桥；**纯层一行未改**，只补了 12／13／24／25 的边界断言。超过 24 格的两次都出界，按既有语义回落成"试修路 → 失败 → 目标延期"。
- [<时间>] **材料**：`BuildMaterial` 增 `COBBLESTONE`（挖矿本就产出圆石，不需新经济）；新增 `construction/transport/BridgeMaterials` 作为"哪个桥种用什么材料、各要多少才开工"的唯一出处。**它落在 construction 而不是设计稿写的 planning**——`planning.*` 不许依赖 `construction.*`，否则成环；设计文档 §3 已按实现纠正。石桥的桥面/支撑/引道用圆石，**护栏与临时栅栏仍是橡木栅栏**：临时栅栏不是成品（开通时清掉，且清栅栏那段逻辑写死了橡木栅栏），而圆石墙护栏需要经济先能产出圆石墙——这是有意的边界，不是遗漏。
- [<时间>] **材料门禁**：`proposeBridge` 改为遍历 `BridgeMaterials.required(kind)`，每个材料各自一个"够开工"的下限；原四个 `BRIDGE_MIN_*` 常量搬进 `BridgeMaterials`。**圆石的下限是发明值**（文档没给），源码注释已标明没有依据、需在真建过一座石桥后重定。
- [<时间>] 命令：`traffic bridge` 增加可选桥种词（`wood` / `stone`，缺省木桥，不认识则明确失败并列出可选值）。
- [<时间>] 验证：完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**19 项**独立检查全部 *Check passed（新增第 19 项 `bridgeMaterialsCheck`；`TrafficDecisionCheck` 与 `ConstructionMaterialCheck` 各补断言）。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>，耗时 <秒数>。提交 <哈希>。
- [<时间>] 未完成：不做玩法验收；**勘察、施工、长跨度稳定性、材料门禁手感全部不可纯测**（只有编译与代码审查）。深度上限未动（浅水 4 / 峡谷 8），所以又宽又深的峡谷仍会按既有深度口径失败；>24 格的水面仍不可跨；石桥的护栏是木头的；长桥更贵更慢且仍占唯一一个交通工程名额。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「接续须知」的**下一步落点**改写：交通剩余里「石桥与更长跨度」**已完成**，落点在 `STONE_BRIDGE_DESIGN.md` §8；交通只剩**成熟期多工程并行**（架构级）一项。
- 「阶段定位」阶段 5 一句补上"第五十八轮加了石桥（圆石结构、跨度 13–24）并把桥种判据收成一个 `isBridge()`"。
- 「本轮接入的内容」新增一节「第五十八轮：石桥与更长跨度」。
- 「交通剩余」把「石桥与更长跨度」标为已完成并写明落点；只留「成熟期多工程并行」。
- 「本轮验证进展」替换为第五十八轮：完整构建、**19 项**检查、产物字节数与耗时、提交；写明本轮新增第 19 项检查、另两处补断言；把"独立检查覆盖不到"的清单换成本轮的（石桥勘察与施工、长跨度稳定性、按桥种的材料门禁、以及 13–24 格水面在真实地形里能否勘察出来）。
- 别处提到"18 项"的地方（若有）一并改为 19 项。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the stone bridge round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。**若本机代理未运行导致连不上 github，提交留在本地并如实报告，不算任务失败。**

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 档位与两次调用 → Task 1 Step 1b（边界断言）+ Task 4 Step 1（调用点）。
- 设计 §3 桥种、材料清单、深度不变 → Task 1（`STONE_BRIDGE`、`BridgeMaterials`、`COBBLESTONE`、勘察参数化）+ Global Constraints。
- 设计 §4 集成收口（`isBridge()`、校验与索引、连通验收自动覆盖、材料门禁按桥种）→ Task 2（收口）+ Task 3 Step 1（校验与计划构造）+ Task 4 Step 2（门禁）。
- 设计 §5 命令 → Task 3 Step 2。
- 设计 §6 验证（边界、材料清单、`BuildMaterial` 名字、`isBridge`）→ Task 1 Step 1/6/7。
- 设计 §7 风险 → Global Constraints（材料与几何不变）+ Task 5 Step 2/3 的"仍未做"段。
- **设计稿的一处错误**（`BridgeMaterials` 写在 `planning/` 会成环）→ Global Constraints 写明纠正，Task 5 Step 2a 把它记进设计文档。

**2. 占位符扫描**

- 无 TBD/TODO。两个新文件给了全文，其余是带锚点的成对替换。
- Task 3 Step 2 的 `startBridgeCommand` 助手给了全文，并写明两端 `executes` 各传什么（`getString(...)` / `null`）。
- Task 1 Step 1b 依赖 `TrafficDecisionCheck` 既有的用例构造方式（"照抄同文件既有的写法"），并给了 `waterRun` 的全文与目标下标的关系——**不是**含糊指令。
- Task 5 的 `<...>` 是模板占位，正文要求逐条替换为真实值。

**3. 类型一致性**

- `TransportPlan.Kind.STONE_BRIDGE` 与 `isBridge()` 在 Task 1 定义，Task 2 全仓使用，Task 3（`startBridge` 的 `kind` 形参、计划的 `Kind`）、Task 4（选档、门禁）一致。
- `BridgePlanner.planBridge(..., int minSpan, int maxSpan)` 的签名在 Task 1 定义与改名、Task 1 Step 8（临时调用点）与 Task 3 Step 1（按桥种区间）一致；`planWoodBridge` 在 Task 1 后全仓不存。
- `BridgeMaterials.deck/support/railing/lighting/barrier(Kind)`、`required(Kind) -> Map<BuildMaterial, Integer>`、`minSpan/maxSpan(Kind) -> int` 在 Task 1 定义，Task 3（六处用料 + 勘察区间）与 Task 4（门禁遍历 `entrySet()` 取 `getKey().item()` 与 `getValue()`）使用，类型一致。
- `startBridge(ServerLevel, String, BlockPos, Direction, TransportPlan.Kind)` 在 Task 3 定义；调用点三处：命令（Task 3 Step 2，按词选 kind）、`proposeBridge`（Task 3 Step 3 先写死木桥保证可编译，Task 4 Step 2 换成按 `kind`）。
- `proposeBridge` 加第 8 个参数 `TransportPlan.Kind kind`，唯一调用点在 Task 4 Step 1 同步。
- `required(...)` 在石桥下 `deck` 与 `support` 同值，`LinkedHashMap` 只留一个键 ⇒ **石桥三种材料、木桥四种**；Task 1 Step 5 已把这条语义写进说明，并指明若断言因此失败应当**回报而不是改断言**。
