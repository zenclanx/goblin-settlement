# 公告牌方块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让聚落状态第一次有一个世界里看得见的入口：一块可放置、可破坏、进创造栏的方块，右键打开一个分区信息面板，而面板里的每个数字都由**与 `goblinsettlement status` 完全同一份计算**得出。

**Architecture:** 先把聚落状态的聚合从 `status` 命令里抽成不可变的 `SettlementReport` 快照（事实，不是成品文本），并让 `status` 与面板成为同一报告的两个 presenter；交通与连通那两行先被结构化，否则它们会是报告里唯一的"文本事实"。然后开三条本模组从未有过的线：方块与物品注册、一条 S2C payload、一个客户端 Screen。方块本身是普通的 `Block`（**没有方块实体**），右键时服务端算快照、发 payload，客户端只排版。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [NOTICEBOARD_DESIGN.md](NOTICEBOARD_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行（完整构建 25–70 秒，Bash 超时给 300000 ms）。
- **本模组此前没有任何方块、物品、网络通道**（`onInitialize` 只注册两种实体，全仓零处 `Registries.BLOCK` / `Registries.ITEM` / `PayloadTypeRegistry`）。本轮开的每一条线都是第一次，**风格照 `ModEntities`**（`Registry.register(BuiltInRegistries.X, key, value)` + `Identifier.fromNamespaceAndPath(MOD_ID, name)`），不另立写法。
- **`status` 的输出文本逐字不变**（设计 §3.2）。任何改动 `status` 的任务，完成前必须用定向 diff 证明 presenter 只是搬了位置。
- **一处真相**：面板**不得**自己算任何数字。住房的容量仍只来自 `HousingCoordinator.homeCapacity`，住户数仍只来自名册，连通判定仍只来自 `TransportCoordinator.roadConnects` / `bridgeConnects`（设计 §3）。
- **不新增持久字段、不升 schema 版本、不新增发明常量**（设计 §0）。食物分钟数由 `MealClock.MEAL_INTERVAL_TICKS` 推出（设计 §5）。
- **公告牌不得进 `CampGenerationCoordinator` 的 `layoutComplete` 硬闸**，**不得放在通行路径上**（设计 §7.1）。
- 已核实的版本差异（写代码时按这个来，不要照旧版本记忆写）：`Identifier`（不是 `ResourceLocation`）；`net.minecraft.network.protocol.common.custom.CustomPacketPayload`（不是 `CustomPayload`）；`Screen.keyPressed(KeyEvent)`、`GuiEventListener.mouseClicked(MouseButtonEvent, boolean)`、`mouseScrolled(double,double,double,double)`；`data/` 下目录名是 `loot_table` 与 `recipe`（都是单数）；`items/noticeboard.json` 是 1.21.11 的物品定义目录。
- 检查项数由 **22 增至 23**（新增 `noticeboardCheck`）。构建结束时 23 项必须全部 `*Check passed`。
- 不做游戏内验证（按用户约定）。方块外观、朝向、面板排版、营地那块挡不挡路**都不可纯测**，会写进日志与状态文件。
- `goblin-settlement-plan/` 下可能有并行 agent 的未提交改动：文档任务先跑 `git status`。`UpdateLog.md` **只许在末尾追加**。
- 提交到 `main`，本轮结束推送。若连不上远程，留在本地并如实报告，**不算任务失败**。
- 美术资产来自 `../Models/handoff/art_handoff_20260929.zip`，**只读**，不要修改 `Models/` 下的任何东西。

---

### Task 1: 食物可支撑时间与新检查任务

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/food/FoodForecast.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/noticeboard/NoticeboardCheck.java`
- Modify: `goblin-settlement-mod/build.gradle`

**Interfaces:**
- Produces: `FoodForecast.minutes(long food, int diners) -> java.util.OptionalInt`；`NoticeboardCheck`（后续任务往同一个 `main` 里追加断言）；Gradle 任务 `noticeboardCheck`

- [ ] **Step 1: 先写检查（RED）**

新建 `src/test/java/dev/local/goblinsettlement/noticeboard/NoticeboardCheck.java`：

```java
package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.economy.food.FoodForecast;

/** Standalone checks for the noticeboard: food forecast, snapshot text, and the panel sections. */
public final class NoticeboardCheck {
    public static void main(String[] args) {
        // Food: one item per resident per active Minecraft day (MealClock.MEAL_INTERVAL_TICKS = 24000
        // = twenty minutes), so whole meals times twenty.
        check(FoodForecast.minutes(0, 0).isEmpty(), "nobody eats means no answer, not zero");
        check(FoodForecast.minutes(40, 4).orElseThrow() == 200, "ten meals at twenty minutes each");
        check(FoodForecast.minutes(39, 4).orElseThrow() == 180, "a partial meal does not count");
        check(FoodForecast.minutes(3, 4).orElseThrow() == 0, "less than one meal is zero minutes");
        check(FoodForecast.minutes(0, 4).orElseThrow() == 0, "no food is zero minutes");
        check(FoodForecast.minutes(4, 4).orElseThrow() == 20, "exactly one meal");
        check(FoodForecast.minutes(Long.MAX_VALUE, 1).orElseThrow() == Integer.MAX_VALUE,
                "an absurd stock clamps instead of overflowing");
        check(negativeDinersRejected(), "negative diners are rejected");
        System.out.println("NoticeboardCheck passed");
    }

    private static boolean negativeDinersRejected() {
        try {
            FoodForecast.minutes(10, -1);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

- [ ] **Step 2: 注册 Gradle 任务**

在 `build.gradle` 里、`tasks.register('profileCheck', JavaExec) { ... }` 那块**之后**加：

```groovy
tasks.register('noticeboardCheck', JavaExec) {
    group = 'verification'
    description = 'Checks the food forecast, the settlement snapshot text and the noticeboard panel sections.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.noticeboard.NoticeboardCheck'
}
```

并在 `tasks.named('check') { ... }` 块里、`dependsOn tasks.named('profileCheck')` 的**下一行**加：

```groovy
    dependsOn tasks.named('noticeboardCheck')
```

- [ ] **Step 3: 跑一次，确认它失败**

Run: `./gradlew noticeboardCheck --offline --no-daemon`
Expected: **BUILD FAILED**，编译错 `找不到符号: FoodForecast`（文件还不存在）。这就是 RED。

- [ ] **Step 4: 写最小实现**

新建 `src/main/java/dev/local/goblinsettlement/economy/food/FoodForecast.java`：

```java
package dev.local.goblinsettlement.economy.food;

import java.util.OptionalInt;

/**
 * How long the known public food lasts, in active simulation minutes.
 *
 * <p>Derived, not invented: MealCoordinator eats one item per living resident every
 * MealClock.MEAL_INTERVAL_TICKS, so whole meals times that interval is the answer. The interval is
 * read from MealClock rather than restated here, because a second copy of 24000 would be a second
 * truth.
 */
public final class FoodForecast {
    /** Minecraft runs at this many ticks per minute, so the meal interval divides into minutes. */
    public static final int TICKS_PER_MINUTE = 1200;

    private FoodForecast() {
    }

    /**
     * Whole meals still in stock times the meal interval. Empty when nobody eats: the question has no
     * answer rather than the answer zero, and a panel that says "0 minutes" would be lying.
     */
    public static OptionalInt minutes(long food, int diners) {
        if (food < 0 || diners < 0) {
            throw new IllegalArgumentException("Food and diners cannot be negative");
        }
        if (diners == 0) {
            return OptionalInt.empty();
        }
        // Clamp on the meal count before multiplying: meals * 20 can overflow a long for an absurd
        // stock, and an overflowed negative would slip past the Math.min below.
        long minutesPerMeal = MealClock.MEAL_INTERVAL_TICKS / TICKS_PER_MINUTE;
        long meals = food / diners;
        if (meals > Integer.MAX_VALUE / minutesPerMeal) {
            return OptionalInt.of(Integer.MAX_VALUE);
        }
        return OptionalInt.of((int) Math.min(Integer.MAX_VALUE, meals * minutesPerMeal));
    }
}
```

**这段方法是 Task 1 实现时更正过的**：初稿写的是先乘再钳（`(food / diners) * 20` 后 `Math.min`），而 `Long.MAX_VALUE` 下这个乘法会溢出成负数、**反而绕过了钳制**——与它自己上面那条"钳到 `Integer.MAX_VALUE`"的断言直接矛盾。**先钳餐数再乘**才是对的（`Integer.MAX_VALUE / 20` 这道闸保证乘积落在 int 内）。实现者发现了并报了上来，已按此更正。

- [ ] **Step 5: 跑一次，确认它通过**

Run: `./gradlew noticeboardCheck --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，末尾 `NoticeboardCheck passed`。

- [ ] **Step 6: 确认聚合检查仍是绿的**

Run: `./gradlew check --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，共 **23** 项 `*Check passed`（原 22 项 + `NoticeboardCheck passed`）。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/build.gradle \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/food/FoodForecast.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/noticeboard/NoticeboardCheck.java
git commit -m "Derive how long the food lasts from the meal clock, not a new constant"
```

---

### Task 2: 交通与连通两行结构化（无行为变更）

这两行今天是 `TransportCommands.trafficLine(TransportSavedData)` 与 `connectivityLine(ServerLevel)`，直接读世界、直接拼字符串。报告要持事实，所以先把"读世界"与"拼文本"分开：读取进 `inspect`，拼文本变纯函数。**输出文本一字不变**，唯一调用点是 `GoblinSettlement.status`。

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportFacts.java`
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportLinks.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCommands.java`（删 `trafficLine` / `connectivityLine` 与只服务它们的五个私有助手）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java:134-137`

**Interfaces:**
- Consumes: `TransportSavedData.roads()`、`roadTraffic(String)`、`roadWidth(String)`、`chainOf(String)`、`plans()`；`TransportCoordinator.roadConnects(ServerLevel, TransportSavedData, TransportPlan, BlockPos)`、`bridgeConnects(ServerLevel, TransportPlan, BlockPos)`、`structurallyComplete(ServerLevel, TransportPlan)`；`RoadUpgradeRules.shouldUpgrade(int, int)`
- Produces: `TransportFacts(boolean hasSettlement, List<RoadRow> roads, Links links)`；`TransportFacts.RoadRow(String id, int traffic, int lanes, boolean wideningReady)`；`TransportFacts.Links(int loaded, int skipped, List<BrokenLink> brokenLinks)`；`TransportFacts.BrokenLink(String id, boolean bridge, String blocked)`；`TransportLinks.inspect(ServerLevel, TransportSavedData, Optional<BlockPos>) -> TransportFacts`；`TransportLinks.trafficLine(TransportFacts) -> String`；`TransportLinks.connectivityLine(TransportFacts) -> String`

- [ ] **Step 1: 先记下今天的输出，作为搬迁的对照**

Run: `./gradlew build --offline --no-daemon` 后，用专用服务端跑一次 `goblinsettlement status`（或直接对照 `TransportCommands.java` 里那两个方法的字符串拼接）。**把 `Road traffic:` 与 `Links:` 两行的拼接逐段抄进 Step 4 的实现里**，不要凭印象重写。

- [ ] **Step 2: 写事实类型**

新建 `src/main/java/dev/local/goblinsettlement/construction/transport/TransportFacts.java`：

```java
package dev.local.goblinsettlement.construction.transport;

import java.util.List;

/**
 * The traffic and link facts one inspection produces, with no text and no world access, so the
 * command and the noticeboard render the same computation twice rather than computing twice.
 */
public record TransportFacts(boolean hasSettlement, List<TransportFacts.RoadRow> roads,
                            TransportFacts.Links links) {

    /** One finished road, already sorted busiest first as the status line shows it. */
    public record RoadRow(String id, int traffic, int lanes, boolean wideningReady) {
    }

    /** Every finished link's verdict. A link in an inactive chunk is counted, never judged. */
    public record Links(int loaded, int skipped, List<TransportFacts.BrokenLink> brokenLinks) {
    }

    /** A link that is not connected, and which of the two reasons it is. */
    public record BrokenLink(String id, boolean bridge, String blocked) {
    }
}
```

- [ ] **Step 3: 把读取搬进 `TransportLinks.inspect`**

新建 `src/main/java/dev/local/goblinsettlement/construction/transport/TransportLinks.java`。**逐字搬** `TransportCommands` 里 `trafficLine`、`connectivityLine` 以及 `loaded`、`blockedWord`、`chainIntact`、`qualifies`、`shortId` 五个私有助手的**判断与顺序**，只把"拼字符串"与"读世界"分开：

```java
package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Reads the traffic and link facts out of the world, and renders them. Read-only, never loads a chunk. */
public final class TransportLinks {
    private static final int STATUS_ROAD_LIMIT = 8;
    private static final int STATUS_LINK_LIMIT = 8;

    private TransportLinks() {
    }

    /** One read of everything both status lines need. */
    public static TransportFacts inspect(ServerLevel level, TransportSavedData traffic,
                                         Optional<BlockPos> settlementAnchor) {
        var rows = new ArrayList<TransportFacts.RoadRow>();
        for (TransportPlan road : traffic.roads()) {
            rows.add(new TransportFacts.RoadRow(road.id(), traffic.roadTraffic(road.id()),
                    traffic.roadWidth(road.id()), qualifies(traffic, road)));
        }
        rows.sort((left, right) -> {
            int byCount = Integer.compare(right.traffic(), left.traffic());
            return byCount != 0 ? byCount : left.id().compareTo(right.id());
        });
        return new TransportFacts(settlementAnchor.isPresent(), rows,
                links(level, traffic, settlementAnchor));
    }

    private static TransportFacts.Links links(ServerLevel level, TransportSavedData traffic,
                                              Optional<BlockPos> settlementAnchor) {
        if (settlementAnchor.isEmpty()) {
            return new TransportFacts.Links(0, 0, List.of());
        }
        BlockPos anchor = settlementAnchor.get();
        var broken = new ArrayList<TransportFacts.BrokenLink>();
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
                broken.add(new TransportFacts.BrokenLink(road.id(), false,
                        blockedWord(level, traffic, road)));
            }
        }
        for (TransportPlan plan : traffic.plans()) {
            if (!plan.isBridge() || plan.completedSteps() != plan.steps().size()) {
                continue;
            }
            if (!loaded(level, List.of(plan))) {
                skipped++;
                continue;
            }
            loaded++;
            if (!TransportCoordinator.bridgeConnects(level, plan, anchor)) {
                broken.add(new TransportFacts.BrokenLink(plan.id(), true,
                        blockedWord(level, traffic, plan)));
            }
        }
        broken.sort((left, right) -> left.id().compareTo(right.id()));
        return new TransportFacts.Links(loaded, skipped, broken);
    }

    /** Whether every declared walking cell of these plans sits in a ticking chunk. */
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

    private static boolean qualifies(TransportSavedData traffic, TransportPlan road) {
        return RoadUpgradeRules.shouldUpgrade(traffic.roadWidth(road.id()),
                traffic.roadTraffic(road.id()));
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
```

- [ ] **Step 4: 把拼文本变成纯函数**

在 `TransportLinks` 里继续加两个方法。**字符串必须与 `TransportCommands` 原文逐字一致**，只把取值改成读 `facts`：

```java
    /**
     * Every finished road's measured traffic, busiest first, with a star on the ones that have earned a
     * widening and the road's current width after its samples.
     */
    public static String trafficLine(TransportFacts facts) {
        var roads = facts.roads();
        if (roads.isEmpty()) {
            return "Road traffic: no completed roads";
        }
        long ready = roads.stream().filter(TransportFacts.RoadRow::wideningReady).count();
        var builder = new StringBuilder("Road traffic: ").append(roads.size())
                .append(" road(s), ").append(ready).append(" ready to widen (*); ");
        int shown = Math.min(STATUS_ROAD_LIMIT, roads.size());
        for (int index = 0; index < shown; index++) {
            TransportFacts.RoadRow road = roads.get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(shortId(road.id()))
                    .append('=').append(road.traffic())
                    .append(" L").append(road.lanes());
            if (road.wideningReady()) {
                builder.append('*');
            }
        }
        if (roads.size() > shown) {
            builder.append(", +").append(roads.size() - shown).append(" more");
        }
        return builder.toString();
    }

    /**
     * Every finished link's connectivity verdict: a road must reach the facility its chain was built
     * for, a bridge must be crossable.
     */
    public static String connectivityLine(TransportFacts facts) {
        if (!facts.hasSettlement()) {
            return "Links: no settlement in this dimension";
        }
        var links = facts.links();
        String unloaded = links.skipped() == 0 ? "" : ", " + links.skipped() + " not loaded";
        if (links.loaded() == 0 && links.skipped() == 0) {
            return "Links: no finished links yet";
        }
        if (links.brokenLinks().isEmpty()) {
            return "Links: all " + links.loaded() + " loaded verified" + unloaded;
        }
        var builder = new StringBuilder("Links: ").append(links.brokenLinks().size())
                .append(" of ").append(links.loaded()).append(" not connected: ");
        int shown = Math.min(STATUS_LINK_LIMIT, links.brokenLinks().size());
        for (int index = 0; index < shown; index++) {
            TransportFacts.BrokenLink link = links.brokenLinks().get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(shortId(link.id())).append(' ')
                    .append(link.bridge() ? "bridge" : "road")
                    .append('(').append(link.blocked()).append(')');
        }
        if (links.brokenLinks().size() > shown) {
            builder.append(", +").append(links.brokenLinks().size() - shown).append(" more");
        }
        return builder.toString() + unloaded;
    }
```

**注意 `shortId` 现在被上面两个 `public static` 方法用到，保持 `private static` 即可。** `TransportFacts.RoadRow::wideningReady` 是本记录的方法引用。

- [ ] **Step 5: 删掉 `TransportCommands` 里的旧实现**

在 `TransportCommands.java` 中删除：`STATUS_ROAD_LIMIT`、`trafficLine`、`STATUS_LINK_LIMIT`、`connectivityLine`、`loaded`、`blockedWord`、`chainIntact`、`qualifies`、`shortId`（这九个只服务那两行，删除后文件必须仍能编译）。若编译报某个助手还有别的使用者，**保留它并只删没被用到的**，不要为了凑数硬删。

- [ ] **Step 6: 改唯一调用点**

`GoblinSettlement.java` 的两行（原 `:134-137`）：

```java
                                context.getSource().sendSuccess(() -> Component.literal(
                                        TransportCommands.trafficLine(TransportSavedData.get(level))), false);
                                context.getSource().sendSuccess(() -> Component.literal(
                                        TransportCommands.connectivityLine(level)), false);
```

改成先用一次 `inspect`，两行都从事实渲染：

```java
                                var transportFacts = TransportLinks.inspect(level,
                                        TransportSavedData.get(level), settlement.map(s -> s.anchor()));
                                context.getSource().sendSuccess(() -> Component.literal(
                                        TransportLinks.trafficLine(transportFacts)), false);
                                context.getSource().sendSuccess(() -> Component.literal(
                                        TransportLinks.connectivityLine(transportFacts)), false);
```

并在文件顶部把 `import ...construction.transport.TransportCommands;` 之外补上 `import ...construction.transport.TransportLinks;`（`TransportCommands` 若不再被本文件用到则一并删掉该 import）。

- [ ] **Step 7: 构建 + 全部检查**

Run: `./gradlew build --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，23 项 `*Check passed`。

- [ ] **Step 8: 定向 diff 证明是搬迁**

Run: `git diff -- goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/`
人工核对：`TransportLinks` 里的判断语句与顺序要与被删的原文**逐句对应**，字符串字面量**逐个相同**（`"Road traffic: "`、`" road(s), "`、`" ready to widen (*); "`、`" L"`、`" not loaded"`、`"Links: no finished links yet"`、`" of "`、`" not connected: "`、`" more"`、`' '`、`'('`、`')'`）。**发现任何一处字面量变了就改回去。**

- [ ] **Step 9: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/ \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Split reading the traffic facts from rendering them, so two callers share one read"
```

---

### Task 3: `SettlementReport`、状态文本 presenter 与 `status` 改造

把 `status` 今天现算的那份聚落状态定义成一个不可变快照，并写出**纯**的文本 presenter。本任务**不读世界、不改 `status`**——只建立形状并用检查把八行文本钉死。

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/SettlementReport.java`
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/SettlementText.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/noticeboard/NoticeboardCheck.java`

**Interfaces:**
- Consumes: Task 2 的 `TransportFacts`
- Produces: `SettlementReport`（含 `Header`、`Stock`、`Housing`、`HomeRow`）；`SettlementText.lines(Optional<SettlementReport>) -> List<String>`

- [ ] **Step 1: 写事实类型**

新建 `src/main/java/dev/local/goblinsettlement/noticeboard/SettlementReport.java`：

```java
package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.colony.Profession;
import dev.local.goblinsettlement.construction.transport.TransportFacts;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/**
 * One settlement's state, as facts rather than finished text. Two presenters render it -- the status
 * command and the noticeboard panel -- so a new figure is added once and both show it.
 */
public record SettlementReport(Header header, Stock stock, Housing housing, TransportFacts traffic,
                               Optional<BlockPos> nextTarget, Map<Profession, Integer> trades) {

    /** Who lives here and how much room is claimed for them. */
    public record Header(String id, BlockPos anchor, int adults, int children, int occupiedSlots,
                         int maxResidents, int plots, int maxPlots, int playerAreas) {
    }

    /** The known public stock. foodMinutes is empty when nobody eats; the counts are a lower bound. */
    public record Stock(int food, int foodTarget, java.util.OptionalInt foodMinutes, int seeds,
                        int seedTarget, int hoes, int axes, int pickaxes, int containers,
                        boolean complete, String priority) {
    }

    /** Beds, occupancy, and one row per judged home with the reason it cannot advance. */
    public record Housing(int beds, int occupied, int spare, int homeless, List<HomeRow> homes,
                          int notLoaded, boolean blueprintUnavailable) {
    }

    public record HomeRow(BlockPos bed, int occupancy, int capacity, Optional<String> reason) {
    }
}
```

- [ ] **Step 2: 写文本 presenter**

新建 `src/main/java/dev/local/goblinsettlement/noticeboard/SettlementText.java`。**每一行的拼接方式与 `GoblinSettlement.status` 原文逐字一致**（对照 `GoblinSettlement.java:91-156` 与 `:305-362`）：

```java
package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.colony.Profession;
import dev.local.goblinsettlement.construction.transport.TransportLinks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Renders a settlement report the way the status command prints it. Pure: no world, no level. */
public final class SettlementText {
    private static final int HOUSING_REPORT_LIMIT = 8;

    private SettlementText() {
    }

    public static List<String> lines(Optional<SettlementReport> report) {
        if (report.isEmpty()) {
            return List.of("No settlement in this dimension");
        }
        SettlementReport value = report.get();
        var lines = new ArrayList<String>();
        var header = value.header();
        lines.add("Settlement " + header.id() + " at " + header.anchor().toShortString()
                + ", adults=" + header.adults()
                + ", children=" + header.children()
                + ", population slots=" + header.occupiedSlots()
                + "/" + header.maxResidents()
                + ", plots=" + header.plots()
                + "/" + header.maxPlots()
                + ", player areas=" + header.playerAreas());
        var stock = value.stock();
        lines.add("Known public stock: food=" + stock.food() + "/" + stock.foodTarget()
                + ", wheat seeds=" + stock.seeds() + "/" + stock.seedTarget()
                + ", hoes/axes/pickaxes=" + stock.hoes() + "/" + stock.axes() + "/" + stock.pickaxes()
                + ", containers=" + stock.containers()
                + ", stock " + (stock.complete() ? "complete" : "incomplete")
                + ", next priority=" + stock.priority());
        var housing = value.housing();
        lines.add("Housing: beds=" + housing.beds()
                + ", occupied slots=" + housing.occupied()
                + ", spare=" + housing.spare()
                + ", homeless=" + housing.homeless());
        lines.add(homesLine(housing));
        lines.add(value.nextTarget()
                .map(pos -> "Next traffic target: " + pos.toShortString())
                .orElse("No pending traffic target"));
        lines.add(TransportLinks.trafficLine(value.traffic()));
        lines.add(TransportLinks.connectivityLine(value.traffic()));
        lines.add("Trades: " + tradesLine(value.trades()));
        return lines;
    }

    private static String homesLine(SettlementReport.Housing housing) {
        if (housing.blueprintUnavailable()) {
            return "Homes: nothing can start, the blueprint data is unavailable";
        }
        String unloaded = housing.notLoaded() == 0 ? "" : ", " + housing.notLoaded() + " not loaded";
        if (housing.homes().isEmpty()) {
            return "Homes: none" + unloaded;
        }
        var entries = new ArrayList<String>();
        for (SettlementReport.HomeRow home : housing.homes()) {
            entries.add(home.bed().toShortString() + " " + home.occupancy() + "/" + home.capacity()
                    + (home.reason().isPresent() ? " [" + home.reason().orElseThrow() + "]" : ""));
        }
        var builder = new StringBuilder("Homes: ");
        int shown = Math.min(HOUSING_REPORT_LIMIT, entries.size());
        for (int index = 0; index < shown; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(entries.get(index));
        }
        if (entries.size() > shown) {
            builder.append(", +").append(entries.size() - shown).append(" more");
        }
        return builder.toString() + unloaded;
    }

    private static String tradesLine(java.util.Map<Profession, Integer> trades) {
        var builder = new StringBuilder();
        for (Profession profession : Profession.values()) {
            if (profession == Profession.UNASSIGNED) {
                continue;
            }
            long held = trades.getOrDefault(profession, 0);
            if (held == 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(profession.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(held);
        }
        long unassigned = trades.getOrDefault(Profession.UNASSIGNED, 0);
        if (unassigned > 0) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("unassigned=").append(unassigned);
        }
        return builder.length() == 0 ? "no adults" : builder.toString();
    }
}
```

**`nextTarget` 是 `SettlementReport` 的一个真实分量**（Task 3 Step 1 的 record 声明里已有），不是便捷方法——因为它源自 `TrafficProposalCoordinator.nearestUnservedFacility` 的一次单独查询，与 `TransportFacts` 无关。`SettlementText` 顶部补 `import net.minecraft.core.BlockPos;`。

- [ ] **Step 3: 在检查里钉死八行文本**

在 `NoticeboardCheck.main` 的 `System.out.println` **之前**加。这是"搬迁无损"的构建期证据：

```java
        // The status lines, pinned. A refactor that changes any character of any line fails here.
        check(SettlementText.lines(Optional.empty()).equals(List.of("No settlement in this dimension")),
                "no settlement is one line");
        var sample = new SettlementReport(
                new SettlementReport.Header("s1", new BlockPos(8, 70, -30), 14, 5, 19, 64, 3, 20, 1),
                new SettlementReport.Stock(32, 48, java.util.OptionalInt.of(40), 12, 25, 2, 2, 2, 4,
                        true, "READY"),
                new SettlementReport.Housing(18, 19, -1, 1,
                        List.of(new SettlementReport.HomeRow(new BlockPos(10, 64, 10), 2, 3,
                                        Optional.of("oak_planks missing"))),
                        2, false),
                new TransportFacts(true,
                        List.of(new TransportFacts.RoadRow("1a2b3c4d5e", 512, 3, true)),
                        new TransportFacts.Links(3, 1, List.of())),
                Optional.of(new BlockPos(12, 64, -30)),
                java.util.Map.of(Profession.FARMER, 3, Profession.UNASSIGNED, 2));
        var lines = SettlementText.lines(Optional.of(sample));
        check(lines.get(0).equals("Settlement s1 at 8, 70, -30, adults=14, children=5, population slots=19/64"
                + ", plots=3/20, player areas=1"), "header line, got: " + lines.get(0));
        check(lines.get(1).equals("Known public stock: food=32/48, wheat seeds=12/25, hoes/axes/pickaxes=2/2/2"
                + ", containers=4, stock complete, next priority=READY"), "stock line, got: " + lines.get(1));
        check(lines.get(2).equals("Housing: beds=18, occupied slots=19, spare=-1, homeless=1"),
                "housing line, got: " + lines.get(2));
        check(lines.get(3).equals("Homes: 10, 64, 10 2/3 [oak_planks missing], 2 not loaded"),
                "homes line, got: " + lines.get(3));
        check(lines.get(4).equals("Next traffic target: 12, 64, -30"),
                "next target line, got: " + lines.get(4));
        // The empty case is its own assertion. Without it the prefix could sit outside the map and
        // render "Next traffic target: No pending traffic target" — which is exactly the bug this
        // task's first implementation shipped, because this assertion was missing.
        var noTarget = new SettlementReport(sample.header(), sample.stock(), sample.housing(),
                sample.traffic(), Optional.empty(), sample.trades());
        check(SettlementText.lines(Optional.of(noTarget)).get(4).equals("No pending traffic target"),
                "an absent target prints only the sentence, got: "
                        + SettlementText.lines(Optional.of(noTarget)).get(4));
        check(lines.get(5).equals("Road traffic: 1 road(s), 1 ready to widen (*); 1a2b3c4d=512 L3*"),
                "traffic line, got: " + lines.get(5));
        check(lines.get(6).equals("Links: all 3 loaded verified, 1 not loaded"),
                "connectivity line, got: " + lines.get(6));
        check(lines.get(7).equals("Trades: farmer=3, unassigned=2"), "trades line, got: " + lines.get(7));
```

顶部补 import：`dev.local.goblinsettlement.colony.Profession`、`dev.local.goblinsettlement.noticeboard.SettlementReport`、`dev.local.goblinsettlement.noticeboard.SettlementText`、`dev.local.goblinsettlement.construction.transport.TransportFacts`、`net.minecraft.core.BlockPos`、`java.util.List`、`java.util.Optional`。

**若某条断言的期望值与实现不符，先判断是哪一侧错**：`BlockPos.toShortString()` 的确切格式、以及 `SettlementReport` 里各字段的取值，都要以**现有 `status` 的真实输出**为准，不要为了让断言通过而改实现。

- [ ] **Step 4: 跑检查**

Run: `./gradlew noticeboardCheck --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，`NoticeboardCheck passed`。失败则按提示的 `got:` 校正——**先确认错的是断言还是实现**。

- [ ] **Step 5: 全量构建**

Run: `./gradlew build --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，23 项 `*Check passed`。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/ \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/noticeboard/NoticeboardCheck.java
git commit -m "Give the settlement state a shape both the command and the panel can render"
```

---

**同一个任务的后半：把聚合搬进快照，并让 `status` 从报告渲染。** 输出文本必须与今天逐字相同——由 Step 3 钉死的断言保证。**这正是文本断言要排在接线之前的原因**：先有断言，搬迁才有证据，而不是靠人眼看 diff。

**Files（后半）：**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/SettlementReport.java`（加 `snapshot` 与 `CODEC` 的**静态**部分推迟到 Task 5，本任务只加 `snapshot`）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`（`status` 分支 + 删 `housingReportLine` 与 `HOUSING_REPORT_LIMIT`）

**Interfaces:**
- Consumes: `SettlementSavedData.get/adultCount/childCount/occupiedPopulationSlots/claimedPlots/playerAreas/plans/assignedProfessions/settlement`；`PopulationRules.MAX_RESIDENTS` / `maximumPlots(int)`；`PublicWarehouseInventory.snapshot(ServerLevel, SettlementSavedData) -> WarehouseSupply`；`SettlementDemand.assess(int, int, WarehouseSupply, boolean, boolean, boolean) -> Assessment`；`BedCensus.count/shortage`；`HousingAssignmentCoordinator.homeless/canJudge/occupancy`；`HousingCoordinator.homeCapacity/blockedReason`；`HousingSavedData.get(level).homes(String)`；`HousingBlueprints.available()`；`TrafficProposalCoordinator.nearestUnservedFacility(SettlementSavedData, TransportSavedData, long)`；`TransportLinks.inspect`；`FoodForecast.minutes`
- Produces: `SettlementReport.snapshot(ServerLevel) -> Optional<SettlementReport>`

- [ ] **Step 7: 写 `snapshot`**

在 `SettlementReport` 里加（**逐条对照 `GoblinSettlement.java:91-156` 与 `:305-362`，把每一处取值搬过来，不改算法**）：

```java
    /** One read of everything both presenters need. Empty means this dimension has no settlement. */
    public static Optional<SettlementReport> snapshot(net.minecraft.server.level.ServerLevel level) {
        var data = dev.local.goblinsettlement.colony.SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return Optional.empty();
        }
        var value = settlement.get();
        var supply = dev.local.goblinsettlement.economy.PublicWarehouseInventory.snapshot(level, data);
        var trafficData = dev.local.goblinsettlement.construction.transport.TransportSavedData.get(level);
        var demand = dev.local.goblinsettlement.colony.SettlementDemand.assess(
                data.adultCount(), data.childCount(), supply,
                data.plans().stream().anyMatch(plan -> !plan.isComplete()),
                dev.local.goblinsettlement.construction.transport.TrafficProposalCoordinator
                        .hasPendingTarget(data, trafficData, level.getGameTime()),
                dev.local.goblinsettlement.housing.BedCensus.shortage(level, data));
        int diners = data.adultCount() + data.childCount();
        var header = new Header(value.id(), value.anchor(), data.adultCount(), data.childCount(),
                data.occupiedPopulationSlots(),
                dev.local.goblinsettlement.colony.PopulationRules.MAX_RESIDENTS,
                data.claimedPlots().size(),
                dev.local.goblinsettlement.colony.PopulationRules.maximumPlots(data.adultCount()),
                data.playerAreas().size());
        var stock = new Stock(supply.food(), (int) demand.foodTarget(),
                dev.local.goblinsettlement.economy.food.FoodForecast.minutes(supply.food(), diners),
                supply.wheatSeeds(), (int) demand.seedTarget(), supply.hoes(), supply.axes(),
                supply.pickaxes(), supply.accessibleContainers(), supply.complete(),
                demand.priority().name());
        var nextTarget = dev.local.goblinsettlement.construction.transport.TrafficProposalCoordinator
                .nearestUnservedFacility(data, trafficData, level.getGameTime());
        var traffic = dev.local.goblinsettlement.construction.transport.TransportLinks.inspect(
                level, trafficData, Optional.of(value.anchor()));
        var trades = new java.util.LinkedHashMap<Profession, Integer>();
        for (Profession profession : Profession.values()) {
            trades.put(profession, 0);
        }
        for (Profession profession : data.assignedProfessions()) {
            trades.merge(profession, 1, Integer::sum);
        }
        return Optional.of(new SettlementReport(header, stock, housing(level, data, value.id()),
                traffic, nextTarget, java.util.Map.copyOf(trades)));
    }

    private static Housing housing(net.minecraft.server.level.ServerLevel level,
                                   dev.local.goblinsettlement.colony.SettlementSavedData data,
                                   String id) {
        int beds = dev.local.goblinsettlement.housing.BedCensus.count(level, data);
        int occupied = data.occupiedPopulationSlots();
        boolean unavailable = !dev.local.goblinsettlement.housing.HousingBlueprints.available();
        var rows = new java.util.ArrayList<HomeRow>();
        int notLoaded = 0;
        if (!unavailable) {
            var homes = new java.util.ArrayList<>(
                    dev.local.goblinsettlement.housing.HousingSavedData.get(level).homes(id));
            homes.sort((left, right) -> {
                int byX = Integer.compare(left.bed().getX(), right.bed().getX());
                if (byX != 0) {
                    return byX;
                }
                int byZ = Integer.compare(left.bed().getZ(), right.bed().getZ());
                return byZ != 0 ? byZ : Integer.compare(left.bed().getY(), right.bed().getY());
            });
            for (var home : homes) {
                if (!dev.local.goblinsettlement.housing.HousingAssignmentCoordinator
                        .canJudge(level, home.bed())) {
                    notLoaded++;
                    continue;
                }
                rows.add(new HomeRow(home.bed(),
                        dev.local.goblinsettlement.housing.HousingAssignmentCoordinator
                                .occupancy(data, home.bed()),
                        dev.local.goblinsettlement.housing.HousingCoordinator.homeCapacity(level, home),
                        dev.local.goblinsettlement.housing.HousingCoordinator
                                .blockedReason(level, data, home, id)));
            }
        }
        int homeless = dev.local.goblinsettlement.housing.HousingAssignmentCoordinator
                .homeless(level, id, data);
        return new Housing(beds, occupied, beds - occupied, homeless, java.util.List.copyOf(rows),
                notLoaded, unavailable);
    }
```

写完后**把这些全限定名整理成正常 import**（本节为了让你能一眼对上"哪一处取值来自哪个现有类"才写成全限定；提交前统一成 import，保持与仓库其余文件同一风格）。

- [ ] **Step 8: 把 `status` 改成渲染报告**

把 `GoblinSettlement.java` 里 `Commands.literal("status").executes(...)` 的整个 lambda 体，连同 `housingReportLine` 方法与 `HOUSING_REPORT_LIMIT` 常量，替换为：

```java
                        .then(Commands.literal("status").executes(context -> {
                            ServerLevel level = context.getSource().getLevel();
                            var report = SettlementReport.snapshot(level);
                            for (String line : SettlementText.lines(report)) {
                                context.getSource().sendSuccess(() -> Component.literal(line), false);
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
```

删掉随之不再使用的 import（`BedCensus`、`HousingAssignmentCoordinator`、`HousingBlueprints`、`HousingCoordinator`、`HousingSavedData`、`PublicWarehouseInventory`、`SettlementDemand`、`TrafficProposalCoordinator` 若在别处不用就一并删）。补 `import ...noticeboard.SettlementReport;` 与 `import ...noticeboard.SettlementText;`。**`PopulationRules`、`Profession`、`TransportSavedData` 等若仍有其他使用者则保留。**

- [ ] **Step 9: 全量构建与检查**

Run: `./gradlew build --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，23 项 `*Check passed`（`NoticeboardCheck` 里 Task 3 钉死的八行断言仍然通过——**这就是"文本逐字未变"的证据**）。

- [ ] **Step 10: 定向 diff 人工复核**

Run: `git diff -- goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`
核对：被删的每一处取值都在 `snapshot` 里有对应，且**没有任何一处改了算法**（尤其是 `Housing` 的排序（x→z→y）、`homes` 的上限 8、`notLoaded` 的跳过口径、`occupiedSlots`/`spare` 的算式）。`status` 现在不再直接调用任何协调器。

- [ ] **Step 11: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/ \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Have status render the shared report, so the command and the board cannot drift"
```

---

### Task 4: 方块、物品、资源与创造栏

本模组的第一个方块与第一个物品。资源全部来自美术交付包，**只复制不修改**。

**Files:**
- Create: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/blockstates/noticeboard.json`（来自交付包）
- Create: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/models/block/noticeboard.json`（来自交付包）
- Create: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/models/item/noticeboard.json`（来自交付包）
- Create: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/items/noticeboard.json`（来自交付包）
- Create: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/block/noticeboard.png`（来自交付包）
- Create: `goblin-settlement-mod/src/main/resources/data/goblin_settlement/loot_table/blocks/noticeboard.json`
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/NoticeboardBlock.java`
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/ModBlocks.java`
- Modify: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/lang/en_us.json`
- Modify: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/lang/zh_cn.json`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`（`onInitialize` 加一行）

**Interfaces:**
- Produces: `ModBlocks.NOTICEBOARD`（`Block`）、`ModBlocks.NOTICEBOARD_BLOCK`（`BlockItem`）、`ModBlocks.initialize()`；`NoticeboardBlock`（四向 `facing`、两个碰撞形状）

- [ ] **Step 1: 复制美术资源**

在 `D:/MC/.minecraft/versions/1.21.11-Fabric 0.19.2/` 下执行：

```bash
python - <<'PYEOF'
import zipfile, os
z = zipfile.ZipFile('Models/handoff/art_handoff_20260929.zip')
dest = 'goblin-settlement-mod/src/main/resources/assets/goblin_settlement'
for name in ['blockstates/noticeboard.json', 'models/block/noticeboard.json',
             'models/item/noticeboard.json', 'items/noticeboard.json',
             'textures/block/noticeboard.png']:
    source = 'assets/goblin_settlement/' + name
    target = os.path.join(dest, name)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, 'wb') as handle:
        handle.write(z.read(source))
    print('wrote', target, os.path.getsize(target), 'bytes')
PYEOF
```

Expected: 五行 `wrote ...`，其中 `textures/block/noticeboard.png` 是 **424** 字节（32×32 PNG）。

- [ ] **Step 2: 写掉落表**

没有它，方块被挖掉**什么都不掉**。新建 `src/main/resources/data/goblin_settlement/loot_table/blocks/noticeboard.json`：

```json
{
  "type": "minecraft:block",
  "pools": [
    {
      "rolls": 1,
      "bonus_rolls": 0,
      "entries": [
        {
          "type": "minecraft:item",
          "name": "goblin_settlement:noticeboard"
        }
      ],
      "conditions": [
        {
          "condition": "minecraft:survives_explosion"
        }
      ]
    }
  ]
}
```

- [ ] **Step 3: 写方块类**

新建 `src/main/java/dev/local/goblinsettlement/noticeboard/NoticeboardBlock.java`：

```java
package dev.local.goblinsettlement.noticeboard;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A board on two posts, standing in the middle of one block. Both shapes are the union of the art
 * team's nine elements, measured off the delivered model: fifteen units wide and three deep, so the
 * two axes are not interchangeable and each needs its own box.
 */
public final class NoticeboardBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<NoticeboardBlock> CODEC = simpleCodec(NoticeboardBlock::new);
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Wide in x, thin in z: the board faces north or south. */
    private static final VoxelShape SHAPE_NS =
            Shapes.box(0.5 / 16.0, 0.0, 6.5 / 16.0, 15.5 / 16.0, 1.0, 9.5 / 16.0);
    /** The same board turned a quarter turn: wide in z, thin in x. */
    private static final VoxelShape SHAPE_EW =
            Shapes.box(6.5 / 16.0, 0.0, 0.5 / 16.0, 9.5 / 16.0, 1.0, 15.5 / 16.0);

    public NoticeboardBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends NoticeboardBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return shape(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        return shape(state);
    }

    private static VoxelShape shape(BlockState state) {
        return state.getValue(FACING).getAxis() == Direction.Axis.Z ? SHAPE_NS : SHAPE_EW;
    }
}
```

**`getShape` / `getCollisionShape` 的确切签名要对着本版本核实**：先写 `protected`，构建报"无法覆盖"或"参数不匹配"时按编译器提示调整（本版本的 `BlockBehaviour` 里这两个方法的参数与上面一致，但若形参名或访问修饰符不符，以编译器为准）。**不要在 `codec()` 里返回父类的 `CODEC`** —— 那会让反序列化得到普通 `Block`。

- [ ] **Step 4: 写注册类**

新建 `src/main/java/dev/local/goblinsettlement/noticeboard/ModBlocks.java`：

```java
package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.GoblinSettlement;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** The mod's blocks and their items. The first ones; nothing here existed before the noticeboard. */
public final class ModBlocks {
    private static final ResourceKey<Block> NOTICEBOARD_KEY = ResourceKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "noticeboard"));

    public static final NoticeboardBlock NOTICEBOARD = Registry.register(BuiltInRegistries.BLOCK,
            NOTICEBOARD_KEY,
            new NoticeboardBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(1.0F)
                    .sound(SoundType.WOOD)));

    public static final BlockItem NOTICEBOARD_ITEM = Registry.register(BuiltInRegistries.ITEM,
            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "noticeboard"),
            new BlockItem(NOTICEBOARD, new Item.Properties()
                    .useBlockDescriptionPrefix()
                    .setId(ResourceKey.create(Registries.ITEM,
                            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "noticeboard")))));

    private ModBlocks() {
    }

    /**
     * The vanilla tab's key is private in this version, so it is rebuilt here exactly the way
     * CreativeModeTabs builds it (verified by decompiling its own createKey).
     */
    private static final ResourceKey<CreativeModeTab> FUNCTIONAL_BLOCKS =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    Identifier.withDefaultNamespace("functional_blocks"));

    public static void initialize() {
        ItemGroupEvents.modifyEntriesEvent(FUNCTIONAL_BLOCKS)
                .register(entries -> entries.accept(NOTICEBOARD_ITEM));
    }
}
```

**这一段的三处 API 已对着 1.21.11 的 jar 逐个核实过，照抄即可**：`Item$Properties.setId(ResourceKey<Item>)` 与 `useBlockDescriptionPrefix()` 都存在（物品 id 在本版本是必需的）；`FabricItemGroupEntries` 继承的 `CreativeModeTab.Output.accept(ItemLike)` 存在，`BlockItem` 是 `ItemLike`；**`CreativeModeTabs.FUNCTIONAL_BLOCKS` 是 `private static final`、模组代码够不到**——所以上面自己造 key，命名空间用 `Identifier.withDefaultNamespace("functional_blocks")`（tab 的真实 id 就是从字节码常量池里读出来的这个字符串）。**验收目标：构建通过，且创造模式「功能方块」页里能拿到它。**

- [ ] **Step 5: 接进主类**

`GoblinSettlement.onInitialize` 里、`GolemEntities.initialize();` 之后加：

```java
        ModBlocks.initialize();
```

并补 `import dev.local.goblinsettlement.noticeboard.ModBlocks;`。

- [ ] **Step 6: 加语言条目**

`lang/en_us.json` 改成：

```json
{
  "entity.goblin_settlement.goblin": "Goblin Resident",
  "block.goblin_settlement.noticeboard": "Settlement Noticeboard"
}
```

`lang/zh_cn.json` 改成：

```json
{
  "entity.goblin_settlement.goblin": "哥布林居民",
  "block.goblin_settlement.noticeboard": "聚落公告牌"
}
```

（面板用到的其余翻译键在 Task 6 一并加。）

- [ ] **Step 7: 构建**

Run: `./gradlew build --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，23 项 `*Check passed`。资源文件不参与检查，但它们会进 JAR——构建后可以 `unzip -l build/libs/goblin-settlement-0.1.0.jar | grep noticeboard` 确认五份资源与 `loot_table` 都在包里。

- [ ] **Step 8: 提交**

```bash
git add goblin-settlement-mod/src/main/resources/ \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/ \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Register the noticeboard, the mod's first block and first item"
```

---

### Task 5: 打通通道（快照 → payload → 空面板）

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/SettlementReport.java`（加 `CODEC` 与编解码）
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/NoticeboardPayload.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/NoticeboardBlock.java`（加 `useWithoutItem`）
- Create: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/noticeboard/NoticeboardScreen.java`
- Modify: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/GoblinSettlementClient.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/noticeboard/NoticeboardCheck.java`

**Interfaces:**
- Produces: `SettlementReport.CODEC`；`NoticeboardPayload(SettlementReport)` + `NoticeboardPayload.TYPE` / `.CODEC`；`NoticeboardScreen(Optional<SettlementReport>)`

- [ ] **Step 1: 先写编解码的往返断言（RED）**

在 `NoticeboardCheck.main` 的 `System.out.println` 之前加：

```java
        // The payload codec: write the same sample the text assertions use into a real buffer, read it
        // back, compare. A field written but never read, or read in the wrong order, shows up here.
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        SettlementReport.CODEC.encode(buffer, sample);
        check(SettlementReport.CODEC.decode(buffer).equals(sample),
                "a report survives the wire round trip");
```

**注意顺序**：`sample` 的定义必须在这条之前（把 Task 3 里 `var sample = ...` 保持在上方即可）。这条现在**编译不过**（`CODEC` 还不存在），这就是 RED。

- [ ] **Step 2: 写编解码**

在 `SettlementReport` 里加。**手写编解码，不用 `StreamCodec.composite`**：`composite` 支持到十二个分量、本记录够用，但 `Housing.homes`、`TransportFacts.roads`、`brokenLinks` 三张列表和两处 `Optional` 各要一层自己的编解码，逐字段写反而更短也更好核对——**顺序写错或漏读一个字段，往返断言会当场抓到**。

**缓冲区类型用 `FriendlyByteBuf` 而不是 `RegistryFriendlyByteBuf`**：`PayloadTypeRegistry.playS2C()` 要的是 `StreamCodec<? super RegistryFriendlyByteBuf, T>`，而 `RegistryFriendlyByteBuf extends FriendlyByteBuf`，所以前者满足它；选后者的实际好处是——独立检查里可以直接 `new FriendlyByteBuf(Unpooled.buffer())` 造一个缓冲区来验往返，`RegistryFriendlyByteBuf` 的构造器还要一个 `RegistryAccess`，在检查里造不出来。

```java
    public static final net.minecraft.network.codec.StreamCodec<
            net.minecraft.network.FriendlyByteBuf, SettlementReport> CODEC =
            net.minecraft.network.codec.StreamCodec.of(SettlementReport::encode, SettlementReport::decode);

    /**
     * The payload carries an Optional, because "this dimension has no settlement" is a real answer the
     * panel has to be able to show, not an error. One boolean in front of the same encoding.
     */
    public static final net.minecraft.network.codec.StreamCodec<
            net.minecraft.network.FriendlyByteBuf, java.util.Optional<SettlementReport>> OPTIONAL_CODEC =
            net.minecraft.network.codec.StreamCodec.of(
                    (buf, value) -> {
                        buf.writeBoolean(value.isPresent());
                        value.ifPresent(present -> encode(buf, present));
                    },
                    buf -> buf.readBoolean() ? Optional.of(decode(buf)) : Optional.empty());

    private static void encode(net.minecraft.network.FriendlyByteBuf buf, SettlementReport report) {
        buf.writeUtf(report.header().id());
        buf.writeVarInt(report.header().anchor().getX());
        buf.writeVarInt(report.header().anchor().getY());
        buf.writeVarInt(report.header().anchor().getZ());
        buf.writeVarInt(report.header().adults());
        buf.writeVarInt(report.header().children());
        buf.writeVarInt(report.header().occupiedSlots());
        buf.writeVarInt(report.header().maxResidents());
        buf.writeVarInt(report.header().plots());
        buf.writeVarInt(report.header().maxPlots());
        buf.writeVarInt(report.header().playerAreas());
        var stock = report.stock();
        buf.writeVarInt(stock.food());
        buf.writeVarInt(stock.foodTarget());
        buf.writeVarInt(stock.foodMinutes().orElse(-1));
        buf.writeVarInt(stock.seeds());
        buf.writeVarInt(stock.seedTarget());
        buf.writeVarInt(stock.hoes());
        buf.writeVarInt(stock.axes());
        buf.writeVarInt(stock.pickaxes());
        buf.writeVarInt(stock.containers());
        buf.writeBoolean(stock.complete());
        buf.writeUtf(stock.priority());
        var housing = report.housing();
        buf.writeVarInt(housing.beds());
        buf.writeVarInt(housing.occupied());
        buf.writeVarInt(housing.spare());
        buf.writeVarInt(housing.homeless());
        buf.writeVarInt(housing.notLoaded());
        buf.writeBoolean(housing.blueprintUnavailable());
        buf.writeVarInt(housing.homes().size());
        for (HomeRow home : housing.homes()) {
            buf.writeVarInt(home.bed().getX());
            buf.writeVarInt(home.bed().getY());
            buf.writeVarInt(home.bed().getZ());
            buf.writeVarInt(home.occupancy());
            buf.writeVarInt(home.capacity());
            buf.writeBoolean(home.reason().isPresent());
            home.reason().ifPresent(buf::writeUtf);
        }
        var traffic = report.traffic();
        buf.writeBoolean(traffic.hasSettlement());
        buf.writeVarInt(traffic.roads().size());
        for (var road : traffic.roads()) {
            buf.writeUtf(road.id());
            buf.writeVarInt(road.traffic());
            buf.writeVarInt(road.lanes());
            buf.writeBoolean(road.wideningReady());
        }
        buf.writeVarInt(traffic.links().loaded());
        buf.writeVarInt(traffic.links().skipped());
        buf.writeVarInt(traffic.links().brokenLinks().size());
        for (var link : traffic.links().brokenLinks()) {
            buf.writeUtf(link.id());
            buf.writeBoolean(link.bridge());
            buf.writeUtf(link.blocked());
        }
        buf.writeBoolean(report.nextTarget().isPresent());
        report.nextTarget().ifPresent(pos -> {
            buf.writeVarInt(pos.getX());
            buf.writeVarInt(pos.getY());
            buf.writeVarInt(pos.getZ());
        });
        buf.writeVarInt(report.trades().size());
        for (var entry : report.trades().entrySet()) {
            buf.writeUtf(entry.getKey().name());
            buf.writeVarInt(entry.getValue());
        }
    }

    private static SettlementReport decode(net.minecraft.network.FriendlyByteBuf buf) {
        var header = new Header(buf.readUtf(), new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt());
        int food = buf.readVarInt();
        int foodTarget = buf.readVarInt();
        int foodMinutes = buf.readVarInt();
        var stock = new Stock(food, foodTarget,
                foodMinutes < 0 ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(foodMinutes),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readBoolean(), buf.readUtf());
        int beds = buf.readVarInt();
        int occupied = buf.readVarInt();
        int spare = buf.readVarInt();
        int homeless = buf.readVarInt();
        int notLoaded = buf.readVarInt();
        boolean unavailable = buf.readBoolean();
        int homeCount = buf.readVarInt();
        var homes = new java.util.ArrayList<HomeRow>(homeCount);
        for (int index = 0; index < homeCount; index++) {
            var bed = new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
            int occupancy = buf.readVarInt();
            int capacity = buf.readVarInt();
            var reason = buf.readBoolean() ? Optional.of(buf.readUtf()) : Optional.<String>empty();
            homes.add(new HomeRow(bed, occupancy, capacity, reason));
        }
        boolean hasSettlement = buf.readBoolean();
        int roadCount = buf.readVarInt();
        var roads = new java.util.ArrayList<dev.local.goblinsettlement.construction.transport
                .TransportFacts.RoadRow>(roadCount);
        for (int index = 0; index < roadCount; index++) {
            roads.add(new dev.local.goblinsettlement.construction.transport.TransportFacts.RoadRow(
                    buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        }
        int loaded = buf.readVarInt();
        int skipped = buf.readVarInt();
        int brokenCount = buf.readVarInt();
        var broken = new java.util.ArrayList<dev.local.goblinsettlement.construction.transport
                .TransportFacts.BrokenLink>(brokenCount);
        for (int index = 0; index < brokenCount; index++) {
            broken.add(new dev.local.goblinsettlement.construction.transport.TransportFacts.BrokenLink(
                    buf.readUtf(), buf.readBoolean(), buf.readUtf()));
        }
        var traffic = new dev.local.goblinsettlement.construction.transport.TransportFacts(
                hasSettlement, roads,
                new dev.local.goblinsettlement.construction.transport.TransportFacts.Links(
                        loaded, skipped, broken));
        var nextTarget = buf.readBoolean()
                ? Optional.of(new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()))
                : Optional.<BlockPos>empty();
        int tradeCount = buf.readVarInt();
        var trades = new java.util.LinkedHashMap<Profession, Integer>();
        for (int index = 0; index < tradeCount; index++) {
            trades.put(Profession.valueOf(buf.readUtf()), buf.readVarInt());
        }
        return new SettlementReport(header, stock,
                new Housing(beds, occupied, spare, homeless, homes, notLoaded, unavailable),
                traffic, nextTarget, java.util.Map.copyOf(trades));
    }
```

**提交前把全限定名整理成 import。** 另外 `StreamCodec.encode(T)` / `StreamCodec.decode(B)` 这两个便捷方法若本版本不存在，改成 `CODEC.encode(buf, value)` / `CODEC.decode(buf)`——按编译器提示走。

- [ ] **Step 3: 写 payload**

新建 `src/main/java/dev/local/goblinsettlement/noticeboard/NoticeboardPayload.java`：

```java
package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.GoblinSettlement;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One settlement snapshot, sent by the server that computed it to the client that will draw it.
 * Empty is a real payload, not an error: a dimension with no settlement still opens a panel that
 * says so.
 */
public record NoticeboardPayload(Optional<SettlementReport> report) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<NoticeboardPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "noticeboard"));
    public static final StreamCodec<FriendlyByteBuf, NoticeboardPayload> CODEC =
            SettlementReport.OPTIONAL_CODEC.map(NoticeboardPayload::new, NoticeboardPayload::report);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
```

**不要用 `CustomPacketPayload.createType(String)`**——它把标识放进 `minecraft:` 命名空间。必须自己 `new Type<>(Identifier.fromNamespaceAndPath(MOD_ID, ...))`。

- [ ] **Step 4: 注册 payload 与接收器**

`GoblinSettlement.onInitialize` 里加一行（放在最前，payload 类型必须在任何发送之前注册）：

```java
        net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry.playS2C()
                .register(NoticeboardPayload.TYPE, NoticeboardPayload.CODEC);
```

`GoblinSettlementClient.onInitializeClient` 里加：

```java
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(
                NoticeboardPayload.TYPE, (payload, context) -> context.client().execute(
                        () -> context.client().setScreen(new NoticeboardScreen(payload.report()))));
```

并补 import。

- [ ] **Step 5: 写一个只有标题的空面板**

新建 `src/client/java/dev/local/goblinsettlement/client/noticeboard/NoticeboardScreen.java`：

```java
package dev.local.goblinsettlement.client.noticeboard;

import dev.local.goblinsettlement.noticeboard.SettlementReport;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Draws the settlement snapshot. Layout only: every number here was computed on the server. */
public final class NoticeboardScreen extends Screen {
    private final Optional<SettlementReport> report;

    public NoticeboardScreen(Optional<SettlementReport> report) {
        super(Component.translatable("block.goblin_settlement.noticeboard"));
        this.report = report;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
```

- [ ] **Step 6: 方块右键发快照**

在 `NoticeboardBlock` 里加（并在顶部补 `import net.minecraft.world.InteractionResult;`、`net.minecraft.world.entity.player.Player;`、`net.minecraft.world.level.Level;`、`net.minecraft.world.phys.BlockHitResult;`）：

```java
    /**
     * The server computes the snapshot and pushes it; the client only opens the screen when it arrives.
     * There is no client-to-server request, because the click already happened on the server and there
     * is nothing to ask for.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer server) {
            var report = SettlementReport.snapshot((net.minecraft.server.level.ServerLevel) level);
            net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(server,
                    new NoticeboardPayload(report.orElse(null)));
        }
        return InteractionResult.SUCCESS;
    }
```

**`SettlementReport.OPTIONAL_CODEC` 在 Task 5 Step 2 里一并定义**（见那一节末尾），这里直接用。整条链路的分量类型是 `Optional<SettlementReport>`：`snapshot` 返回它、payload 承载它、Screen 接收它。**"这个维度没有聚落"是一个正常的载荷，不是错误**——面板要能打开并说明这一点（设计 §9）。

- [ ] **Step 7: 构建**

Run: `./gradlew build --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，23 项 `*Check passed`（含 `a report survives the wire round trip`）。

- [ ] **Step 8: 提交**

```bash
git add goblin-settlement-mod/src/
git commit -m "Open the mod's first network channel, server snapshot to client screen"
```

---

### Task 6: 面板分区 presenter 与 Screen 排版

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/noticeboard/NoticeboardText.java`
- Modify: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/noticeboard/NoticeboardScreen.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/noticeboard/NoticeboardCheck.java`
- Modify: `src/main/resources/assets/goblin_settlement/lang/en_us.json` 与 `zh_cn.json`

**Interfaces:**
- Produces: `NoticeboardText.sections(Optional<SettlementReport>) -> List<NoticeboardText.Section>`；`NoticeboardText.Section(String titleKey, List<NoticeboardText.Row> rows)`；`NoticeboardText.Row(String labelKey, String valueKey, List<String> args)`

- [ ] **Step 1: 先写分区断言（RED）**

在 `NoticeboardCheck.main` 里加：

```java
        var sections = NoticeboardText.sections(Optional.of(sample));
        check(sections.size() == 6, "six sections, got " + sections.size());
        check(sections.get(0).titleKey().equals("noticeboard.goblin_settlement.section.population"),
                "population comes first");
        check(sections.get(0).rows().size() == 5, "adults, children, slots, plots, player areas");
        check(sections.get(1).titleKey().equals("noticeboard.goblin_settlement.section.food"), "food second");
        check(sections.get(1).rows().get(1).valueKey().equals("noticeboard.goblin_settlement.value.minutes")
                && sections.get(1).rows().get(1).args().equals(List.of("40")),
                "the food forecast is the second row of food");
        check(sections.get(2).rows().size() == 5, "beds, occupied, spare, homeless, one home row");
        check(sections.get(2).rows().get(4).args().contains("oak_planks missing"), "the stuck reason is carried");
        check(sections.get(3).titleKey().equals("noticeboard.goblin_settlement.section.traffic")
                && sections.get(3).rows().size() == 3, "next target, traffic, links");
        check(sections.get(5).titleKey().equals("noticeboard.goblin_settlement.section.trades"),
                "trades come last");
```

- [ ] **Step 2: 写 presenter**

新建 `src/main/java/dev/local/goblinsettlement/noticeboard/NoticeboardText.java`。**它只把事实翻成"翻译键 + 参数"，不拼任何人类可读的句子**——拼句子是客户端用语言文件做的事（设计 §4.1）：

```java
package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.colony.Profession;
import dev.local.goblinsettlement.construction.transport.TransportLinks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Turns a report into titled sections of translatable rows. Pure: no world, no language, no text. */
public final class NoticeboardText {
    private static final String ROOT = "noticeboard.goblin_settlement.";
    private static final int HOME_LIMIT = 8;

    public record Row(String labelKey, String valueKey, List<String> args) {
    }

    public record Section(String titleKey, List<Row> rows) {
    }

    private NoticeboardText() {
    }

    public static List<Section> sections(Optional<SettlementReport> report) {
        if (report.isEmpty()) {
            return List.of(new Section(ROOT + "title", List.of(
                    new Row(ROOT + "no_settlement", ROOT + "value.raw", List.of()))));
        }
        SettlementReport value = report.get();
        var header = value.header();
        var stock = value.stock();
        var housing = value.housing();
        var sections = new ArrayList<Section>();

        sections.add(new Section(ROOT + "section.population", List.of(
                raw(ROOT + "row.adults", String.valueOf(header.adults())),
                raw(ROOT + "row.children", String.valueOf(header.children())),
                pair(ROOT + "row.slots", String.valueOf(header.occupiedSlots()),
                        String.valueOf(header.maxResidents())),
                pair(ROOT + "row.plots", String.valueOf(header.plots()),
                        String.valueOf(header.maxPlots())),
                raw(ROOT + "row.player_areas", String.valueOf(header.playerAreas())))));

        var foodRows = new ArrayList<Row>();
        foodRows.add(pair(ROOT + "row.food", String.valueOf(stock.food()),
                String.valueOf(stock.foodTarget())));
        foodRows.add(new Row(ROOT + "row.food_lasts", ROOT + "value.minutes",
                stock.foodMinutes().isPresent()
                        ? List.of(String.valueOf(stock.foodMinutes().getAsInt()))
                        : List.of()));
        foodRows.add(pair(ROOT + "row.seeds", String.valueOf(stock.seeds()),
                String.valueOf(stock.seedTarget())));
        sections.add(new Section(ROOT + "section.food", List.copyOf(foodRows)));

        var homeRows = new ArrayList<Row>();
        homeRows.add(raw(ROOT + "row.beds", String.valueOf(housing.beds())));
        homeRows.add(raw(ROOT + "row.occupied", String.valueOf(housing.occupied())));
        homeRows.add(raw(ROOT + "row.spare", String.valueOf(housing.spare())));
        homeRows.add(raw(ROOT + "row.homeless", String.valueOf(housing.homeless())));
        for (int index = 0; index < Math.min(HOME_LIMIT, housing.homes().size()); index++) {
            SettlementReport.HomeRow home = housing.homes().get(index);
            var args = new ArrayList<String>();
            args.add(home.bed().toShortString());
            args.add(String.valueOf(home.occupancy()));
            args.add(String.valueOf(home.capacity()));
            home.reason().ifPresent(args::add);
            homeRows.add(new Row(ROOT + "row.home", ROOT + "value.home", List.copyOf(args)));
        }
        if (housing.homes().size() > HOME_LIMIT) {
            homeRows.add(raw(ROOT + "row.more_homes",
                    String.valueOf(housing.homes().size() - HOME_LIMIT)));
        }
        if (housing.notLoaded() > 0) {
            homeRows.add(raw(ROOT + "row.not_loaded", String.valueOf(housing.notLoaded())));
        }
        sections.add(new Section(ROOT + "section.housing", List.copyOf(homeRows)));

        Row nextTargetRow = value.nextTarget()
                .map(pos -> raw(ROOT + "row.next_target", pos.toShortString()))
                .orElseGet(() -> new Row(ROOT + "row.next_target", ROOT + "value.none", List.of()));
        sections.add(new Section(ROOT + "section.traffic", List.of(
                nextTargetRow,
                new Row(ROOT + "row.road_traffic", ROOT + "value.raw",
                        List.of(TransportLinks.trafficLine(value.traffic()))),
                new Row(ROOT + "row.links", ROOT + "value.raw",
                        List.of(TransportLinks.connectivityLine(value.traffic()))))));

        sections.add(new Section(ROOT + "section.materials", List.of(
                new Row(ROOT + "row.tools", ROOT + "value.tools", List.of(
                        String.valueOf(stock.hoes()), String.valueOf(stock.axes()),
                        String.valueOf(stock.pickaxes()))),
                raw(ROOT + "row.containers", String.valueOf(stock.containers())),
                raw(ROOT + "row.stock", stock.complete() ? ROOT + "value.complete"
                        : ROOT + "value.incomplete"),
                raw(ROOT + "row.priority", stock.priority()))));

        var tradeRows = new ArrayList<Row>();
        for (Profession profession : Profession.values()) {
            int held = value.trades().getOrDefault(profession, 0);
            if (held == 0) {
                continue;
            }
            tradeRows.add(raw(ROOT + "row.trade." + profession.name().toLowerCase(java.util.Locale.ROOT),
                    String.valueOf(held)));
        }
        sections.add(new Section(ROOT + "section.trades", List.copyOf(tradeRows)));
        return List.copyOf(sections);
    }

    private static Row raw(String labelKey, String value) {
        return new Row(labelKey, ROOT + "value.raw", List.of(value));
    }

    private static Row pair(String labelKey, String first, String second) {
        return new Row(labelKey, ROOT + "value.pair", List.of(first, second));
    }
}
```

**`value.none` 那条分支的 `args` 是空表**：`value.none` 是**取值键**，它的语言条目里没有占位符，所以不传参数。别把它当参数塞进 `value.raw`——那样面板上会直接显示键名。

- [ ] **Step 3: 跑检查**

Run: `./gradlew noticeboardCheck --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，`NoticeboardCheck passed`。

- [ ] **Step 4: 加语言条目**

`en_us.json`（在 `block.goblin_settlement.noticeboard` 之后逐个补，键名与 `NoticeboardText` 里的常量一一对应）：

```json
{
  "entity.goblin_settlement.goblin": "Goblin Resident",
  "block.goblin_settlement.noticeboard": "Settlement Noticeboard",
  "noticeboard.goblin_settlement.title": "Settlement Noticeboard",
  "noticeboard.goblin_settlement.no_settlement": "No settlement in this dimension",
  "noticeboard.goblin_settlement.value.raw": "%s",
  "noticeboard.goblin_settlement.value.pair": "%s / %s",
  "noticeboard.goblin_settlement.value.minutes": "about %s minutes (active time)",
  "noticeboard.goblin_settlement.value.none": "none",
  "noticeboard.goblin_settlement.value.tools": "%s / %s / %s",
  "noticeboard.goblin_settlement.value.complete": "complete",
  "noticeboard.goblin_settlement.value.incomplete": "incomplete",
  "noticeboard.goblin_settlement.value.home": "%s  %s/%s%s",
  "noticeboard.goblin_settlement.section.population": "Population",
  "noticeboard.goblin_settlement.section.food": "Food",
  "noticeboard.goblin_settlement.section.housing": "Housing",
  "noticeboard.goblin_settlement.section.traffic": "Works and traffic",
  "noticeboard.goblin_settlement.section.materials": "Materials",
  "noticeboard.goblin_settlement.section.trades": "Trades",
  "noticeboard.goblin_settlement.row.adults": "Adults",
  "noticeboard.goblin_settlement.row.children": "Children",
  "noticeboard.goblin_settlement.row.slots": "Population slots",
  "noticeboard.goblin_settlement.row.plots": "Plots",
  "noticeboard.goblin_settlement.row.player_areas": "Player areas",
  "noticeboard.goblin_settlement.row.food": "Food",
  "noticeboard.goblin_settlement.row.food_lasts": "Lasts",
  "noticeboard.goblin_settlement.row.seeds": "Wheat seeds",
  "noticeboard.goblin_settlement.row.beds": "Beds",
  "noticeboard.goblin_settlement.row.occupied": "Occupied",
  "noticeboard.goblin_settlement.row.spare": "Spare",
  "noticeboard.goblin_settlement.row.homeless": "Homeless",
  "noticeboard.goblin_settlement.row.home": "Home",
  "noticeboard.goblin_settlement.row.more_homes": "and %s more homes",
  "noticeboard.goblin_settlement.row.not_loaded": "%s homes not loaded",
  "noticeboard.goblin_settlement.row.next_target": "Next target",
  "noticeboard.goblin_settlement.row.road_traffic": "Road traffic",
  "noticeboard.goblin_settlement.row.links": "Links",
  "noticeboard.goblin_settlement.row.tools": "Hoes / axes / pickaxes",
  "noticeboard.goblin_settlement.row.containers": "Containers",
  "noticeboard.goblin_settlement.row.stock": "Stock",
  "noticeboard.goblin_settlement.row.priority": "Next priority",
  "noticeboard.goblin_settlement.row.trade.farmer": "Farmers",
  "noticeboard.goblin_settlement.row.trade.forester": "Foresters",
  "noticeboard.goblin_settlement.row.trade.miner": "Miners",
  "noticeboard.goblin_settlement.row.trade.builder": "Builders",
  "noticeboard.goblin_settlement.row.trade.hauler": "Haulers",
  "noticeboard.goblin_settlement.row.trade.artisan": "Artisans",
  "noticeboard.goblin_settlement.row.trade.sentry": "Sentries",
  "noticeboard.goblin_settlement.row.trade.unassigned": "Unassigned"
}
```

**七个职业的枚举名要以 `Profession.java` 里的实际常量为准**（`FARMER` / `FORESTER` / `MINER` / `BUILDER` / `HAULER` / `ARTISAN` / `SENTRY` / `UNASSIGNED`）——先打开该文件核对，名字对不上要改成一致的，否则运行时显示的是原始键名。`zh_cn.json` 同结构、换成中文，`value.minutes` 写成 `"约 %s 分钟（活动时间）"`。

- [ ] **Step 5: 把面板画出来**

重写 `NoticeboardScreen`：分区标题 + 「标签 值」行，超出用滚动。要点：`render` 里先 `super.render(...)`，用 `graphics.fill` 画底色，`graphics.drawString` 画每一行；`mouseScrolled` 调 `scroll` 偏移；用 `graphics.enableScissor(...)` / `disableScissor()` 把文字裁在面板内。行高用 `this.font.lineHeight + 2`，标签与值分两列（值右对齐或标签后固定列宽）。`init()` 里加一个 `Button.builder(CommonComponents.GUI_DONE, b -> this.onClose())`（`import net.minecraft.client.gui.components.Button;` 与 `net.minecraft.network.chat.CommonComponents;`）。

`Component.translatable(labelKey)` 取标签，`Component.translatable(valueKey, args.toArray())` 取值。**整份实现只读 `sections`，不得再碰 `SettlementReport` 的任何字段**——这是"客户端不计算"的落实点。

- [ ] **Step 6: 构建**

Run: `./gradlew build --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，23 项 `*Check passed`。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/
git commit -m "Lay the snapshot out in sections, with the client doing nothing but drawing"
```

---

### Task 7: 营地自动摆放

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/camp/CampGenerationCoordinator.java`

**Interfaces:**
- Consumes: Task 4 的 `ModBlocks.NOTICEBOARD`

- [ ] **Step 1: 先确认硬闸的位置**

打开 `CampGenerationCoordinator.java`，确认这两行的**先后**仍然是：`if (!camp.layoutComplete()) return;`（约 `:62`）在 `if (!camp.populationStarted())`（约 `:72`）**之前**。公告牌必须放在**前面那道闸通过之后**。

- [ ] **Step 2: 在一批补给那一步里补放**

`tick` 里 `starterGrantStarted` 那段（约 `:64-67`）改成：

```java
        if (!camp.starterGrantStarted() && level.getBlockState(chestPos).is(Blocks.CHEST)
                && level.getBlockEntity(chestPos) instanceof Container chest) {
            camp.markStarterGrantStarted();
            grantSupplies(chest);
            placeNoticeboard(level, id, corner);
        }
```

并加一个私有方法。**位置必须避开走道**：营地两条床排在 z=1 与 z=5，走道在 z=3 与 z=4；公告牌贴西侧边线、放在 z=4 那条开敞带上、正面朝营地内（east）。

```java
    /**
     * One board, next to the camp's west edge and off the walking lanes at z=3 and z=4's middle. The
     * board is three sixteenths thick but not empty, so vanilla pathfinding treats its cell as blocked;
     * standing it in a lane would cut the camp in two. Placed with the starter grant rather than with
     * the layout, because layoutComplete is a strict all-or-nothing gate that guards the eight
     * residents -- a board counted into it would wedge the camp at zero population if a player block
     * ever took its spot.
     */
    private static void placeNoticeboard(ServerLevel level, String id, BlockPos corner) {
        BlockPos pos = corner.offset(0, 0, 4);
        if (!canPlace(level, id, pos)) {
            return;
        }
        level.setBlock(pos, ModBlocks.NOTICEBOARD.defaultBlockState()
                .setValue(NoticeboardBlock.FACING, Direction.EAST), 3);
    }
```

补 import：`dev.local.goblinsettlement.noticeboard.ModBlocks` 与 `dev.local.goblinsettlement.noticeboard.NoticeboardBlock`。

- [ ] **Step 3: 构建**

Run: `./gradlew build --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，23 项 `*Check passed`。

- [ ] **Step 4: 人工核对不挡路**

对着 `placeCamp`（约 `:146-170`）逐格核对：`(0, 0, 4)` 相对角落 = 世界格 `corner.x + 0`、`corner.z + 4`。营地在 `z ∈ {1,2}` 与 `{5,6}` 铺床、`z=3` 有箱子(1)与营火(3)、`y=3` 的木板盖住 `z ∈ {1,2,5,6}`。**确认 `z=4` 那条不在木板覆盖内、也不与床重叠**。若发现 `(0,0,4)` 会挡路或与既有方块冲突，**换到同一列上确认空着的格子**（候选：`corner.offset(0, 0, 3)` 但必须避开箱子与营火），并在 UpdateLog 里写清换了哪个格与为什么。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/camp/CampGenerationCoordinator.java
git commit -m "Stand a noticeboard in the camp, after the gate that guards the residents"
```

---

### Task 8: 文档收尾

**Files:**
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`
- Modify: `goblin-settlement-plan/TEST_CHECKLIST.md`（**追加**，不改已有条目）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只许在末尾追加**）

- [ ] **Step 1: 先跑 `git status`**

计划文档阶段可能有并行 agent 的未提交改动。**发现不属于本轮的改动不要动它们**，只提交自己的文件。

- [ ] **Step 2: 追加测试清单条目**

在 `TEST_CHECKLIST.md` 的**第 2 组（命令与显示）末尾**追加一节，或新开一组，写清本轮**只能进游戏看**的项：

- [ ] 公告牌能放置、四向朝向与模型正面一致（美术称模型正面朝北）
- [ ] **两个碰撞形状都对**：南北向时板面横跨 x、东西向时横跨 z；走过去不会被看不见的方块挡住
- [ ] 挖掉方块掉一个公告牌
- [ ] 创造模式「功能方块」页里能找到它
- [ ] 右键打开面板；关掉重开是刷新
- [ ] 面板六个分区都在、长行不溢出、能滚动到底
- [ ] `goblinsettlement status` 的输出与改动前逐字相同（这是本轮最有价值的一条）
- [ ] 营地自动放的那块真的出现，**且没有挡住居民的走位**
- [ ] 没有聚落的维度右键：面板只显示一行说明，不崩

- [ ] **Step 3: 更新 `CURRENT_STATUS.md`**

更新日期行、HEAD 指针、以及「下一步的落点」第 0 条：把公告牌从"设计已提交"改写为"**已实现、待游戏验证**"，落点写 `NOTICEBOARD_DESIGN.md` §N 与 `NOTICEBOARD_PLAN.md`，并如实写明**未做游戏内验证**。保持简短。

- [ ] **Step 4: 追加 `UpdateLog.md`**

在**文件末尾**追加本轮段（`## [起 – 止] 第七十六轮：…` 的续段），每条带 `+08:00` 时间。如实记录：改了什么、构建与 23 项检查的结果、**未完成的是游戏内验证**、以及任何实现期偏离（规格写错、API 与本版本不符而改法、营地位置若更换要写清）。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the noticeboard round: what is built, what only the game can confirm"
git push origin main
```

推不上去就留在本地并如实报告。

---

## Self-Review

**1. Spec coverage**

| 设计节 | 落在哪个任务 |
| --- | --- |
| §0 范围（方块/物品/唯一计算处/payload/面板/营地/食物） | Task 1（食物）、2–3（唯一计算处）、4（方块物品）、5（通道）、6（面板）、7（营地） |
| §2 架构与数据流（单向 S2C、无方块实体、无权限判定） | Task 4（无 BE）、5（单向） |
| §3.1 报告形状 | Task 3 |
| §3.2 `status` 文本逐字不变 | Task 3（断言钉死 + 定向 diff，同一任务内） |
| §3.3 交通与连通结构化 | Task 2 |
| §4 面板六区 | Task 6 |
| §4.1 翻译键 | Task 6 Step 4 |
| §5 食物算式与三个限定 | Task 1、Task 6（`value.minutes` 的措辞） |
| §6 方块本身 | Task 4 |
| §7 营地两条约束 | Task 7 |
| §8 资源与注册清单 | Task 4 |
| §9 错误与边界 | Task 3（无聚落一行、蓝图不可用、不可 tick 沿用旧口径）、Task 6（无聚落分区） |
| §10.1 可纯测四项 | Task 1（食物）、Task 3（文本）、Task 5（codec 往返）、Task 6（分区） |
| §10.2 只能进游戏 | Task 8 Step 2 |
| §11 明确未做 | 不建任务（是"不做"清单） |

**2. Placeholder scan**

Task 6 Step 5（画面板）**是"按实现所见调整排版细节"，不是留空**——它写明了六个分区的内容、行高与两列布局的要求、滚动的做法与验收方式（长行不溢出、能滚到底），但没有逐行代码，因为一个 Screen 的排版要在真机上看着调，把坐标写死在计划里反而会误导。**这是全计划唯一一处没有可粘贴代码的步骤；其余每一步都有代码或命令。**（原先还有一处——Task 4 的创造栏 API——已在开工前的核查中逐条对着 jar 确认并写死，见该步。）

**3. Type consistency**

- `FoodForecast.minutes(long, int) -> OptionalInt`：Task 1 定义，Task 3 使用（`Stock.foodMinutes`），Task 6 读（`.getAsInt()`）——一致。
- `TransportFacts` / `RoadRow` / `Links` / `BrokenLink`：Task 2 定义并在 Task 3 的 `SettlementReport.traffic`、Task 5 的 codec、Task 6 的 presenter 中使用——字段名 `wideningReady` / `brokenLinks` / `blocked` 全文一致。
- `SettlementReport` 的六个分量：`header`、`stock`、`housing`、`traffic`、`nextTarget`、`trades`。Task 3 定义，Task 3 的 `snapshot` 构造、Task 5 的 codec、Task 6 的 presenter 全部按这六个写。
- 编解码的缓冲区类型是 `FriendlyByteBuf`（不是 `RegistryFriendlyByteBuf`）：`SettlementReport.CODEC` / `OPTIONAL_CODEC`、`NoticeboardPayload.CODEC`、`encode` / `decode` 的形参、以及 Task 5 Step 1 里 `new FriendlyByteBuf(Unpooled.buffer())` 的往返断言，四处一致。
- `SettlementReport.snapshot` 返回 `Optional<SettlementReport>`；`NoticeboardPayload` 的分量类型是 `Optional<SettlementReport>`，codec 走 `OPTIONAL_CODEC`——Task 5 已统一。
- `NoticeboardText.Section` / `Row` 的字段名 `titleKey` / `labelKey` / `valueKey` / `args` 在 Task 6 的断言、presenter 与 Screen 中一致。
