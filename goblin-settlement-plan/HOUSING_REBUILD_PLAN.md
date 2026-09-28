# 改建安全 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让床不再站到蓝图格里（那会让那栋房的升级永久卡住），并让"这栋房为什么开不了工"在 `status` 里说得出来。

**Architecture:** `BlueprintSet` 加一条只吃坐标的纯判据"这个格被这套风格的任何一级用到吗"；`BedProvisioningCoordinator` 在扫描开始时**按有空位的锚点**预先算出一组"被蓝图预留的世界格"，加进放床候选格的排除项；`HousingCoordinator` 把 `advance` 里那几道门禁提成一个只读的"为什么开不了工"判据，派工与 `status` 都问它；`BlueprintCheck` 把"床头与它上方两格永不被蓝图占用"这条**目前只靠人工核对**的性质变成随构建跑的断言。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [HOUSING_REBUILD_DESIGN.md](HOUSING_REBUILD_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行（25–70 秒，Bash 超时给 300000 ms）。
- **不做拆除**：本轮不新增任何"拆掉已建成方块"的能力，也不清理玩家放的方块——放不下**只报告**（设计 §4）。
- **不新增持久字段**：诊断是按需只读重算的（设计 §3）；床的排除项是每次扫描现算的。
- **一份事实只留一处**：蓝图格的判据只在 `BlueprintSet.reserved`；"为什么开不了工"只在 `HousingCoordinator` 那个提取出来的判据里（派工与 `status` 都调它，**不许把门禁条件抄第二遍**）。
- **已有行为不许变**：`advance` 对"能开工"的那些情形结论必须与改前逐字相同；`siteSuitable` 除新增一条排除项外一字不改；蓝图数据文件与生成脚本不动。
- 检查项数**仍为 19**（断言加进既有的 `blueprintCheck`，**不新增检查任务**）。构建结束时 19 项必须全部 `*Check passed`。
- 不做游戏内验证（按用户约定）。放床的新排除项、`status` 那一行、扫描的代价都**不可纯测**，会写进日志与状态文件。
- `goblin-settlement-plan/` 下可能有并行 agent 的未提交改动：文档任务先跑 `git status`。`UpdateLog.md` **只许在末尾追加**。
- 提交到 `main`，本轮结束推送（若本机代理未运行导致连不上，留在本地并如实报告，不算任务失败）。

---

### Task 1: 纯判据与蓝图净空断言

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BlueprintSet.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/BlueprintCheck.java`

**Interfaces:**
- Consumes: `BlueprintSet.stages(int styleIndex)`（既有）
- Produces: `BlueprintSet.reserved(int styleIndex, int x, int y, int z) -> boolean`

- [ ] **Step 1: 先写检查（RED）**

在 `BlueprintCheck` 的 `main` 里（`checkBlocksAreNotRestricted(data);` 之后）加两行调用：

```java
        checkCellsUsedByABlueprint(data);
        checkBedHeadroomStaysFree(data);
```

并在文件末尾（`require` 之前）加：

```java
    /** The rule the bed placement asks: does any stage of this style put a block on this cell? */
    private static void checkCellsUsedByABlueprint(BlueprintSet data) {
        for (int style = 0; style < data.styleCount(); style++) {
            var first = data.stages(style).get(0).get(0);
            require(data.reserved(style, first.x(), first.y(), first.z()),
                    "a cell the blueprint builds on is reserved (style " + style + ")");
            require(!data.reserved(style, 0, 99, 0),
                    "a cell far above the box is not reserved (style " + style + ")");
            require(!data.reserved(style, 12, 0, 12),
                    "a cell far outside the box is not reserved (style " + style + ")");
        }
        require(data.reserved(999, 0, 0, 0) == data.reserved(0, 0, 0, 0),
                "an out-of-range style degrades to the first style rather than throwing");
    }

    /**
     * The bed stands on the anchor, so the anchor's own headroom must stay clear: a block there would
     * make that first bed unusable, and a stage may never build it. Hand-checked when the styles were
     * written; pinned here so changing the geometry cannot quietly break the bed.
     */
    private static void checkBedHeadroomStaysFree(BlueprintSet data) {
        for (int style = 0; style < data.styleCount(); style++) {
            require(!data.reserved(style, 0, 1, 0), "the cell above the bed head stays clear (style " + style + ")");
            require(!data.reserved(style, 0, 2, 0), "and the one above it too (style " + style + ")");
        }
    }
```

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew blueprintCheck --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 方法 reserved(int,int,int,int)`。

- [ ] **Step 3: 写纯判据**

在 `BlueprintSet` 的 `stages(int)` **之后**加：

```java
    /**
     * Whether any stage of this style puts a block on the cell at this offset from the home's bed. A bed
     * standing here would sit where a later step must go, and this line only ever adds blocks -- the
     * step could then never be placed and the home would stall for good.
     */
    public boolean reserved(int styleIndex, int x, int y, int z) {
        for (List<HousingRules.Step> stage : stages(styleIndex)) {
            for (HousingRules.Step step : stage) {
                if (step.x() == x && step.y() == y && step.z() == z) {
                    return true;
                }
            }
        }
        return false;
    }
```

- [ ] **Step 4: 跑检查并跑完整构建**

Run: `./gradlew blueprintCheck --offline --no-daemon`

Expected: `BlueprintCheck passed`。

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**19 项**检查全部 `*Check passed`（本任务不新增检查任务）。

**若 `checkBedHeadroomStaysFree` 失败**：说明发布的数据与设计 §1 的人工核对结论不符——**回报这件事**，不要放宽断言，也不要改蓝图数据（改数据要同时改生成脚本，属另一轮）。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BlueprintSet.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/BlueprintCheck.java
git commit -m "Ask the blueprint whether a cell is spoken for, and pin the bed's headroom"
```

---

### Task 2: 放床时排除被蓝图预留的格

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BedProvisioningCoordinator.java`

**Interfaces:**
- Consumes: `BlueprintSet.reserved`（Task 1）、`HousingBlueprints.stages(int)` / `available()`（既有）、`HousingSavedData.Home.style()` / `bed()`（既有）
- Produces: `BedProvisioningCoordinator.reservedCells(ServerLevel, String id, List<BlockPos> spareAnchors) -> Set<BlockPos>`（私有）

- [ ] **Step 1: 预先算出预留格集合**

在 `BedProvisioningCoordinator` 的 `spareCapacityAnchors` **之后**加：

```java
    /**
     * Every world cell the spare homes' own blueprints will need, at any stage. A bed placed there would
     * stand where a later step must go; this line only ever adds blocks, so that step could never be
     * placed and the home would stall for good. Only homes with a spare slot can be bound to, so only
     * their blueprints are collected -- the union over every style would rule out positions that are
     * actually fine.
     */
    private static Set<BlockPos> reservedCells(ServerLevel level, String id, List<BlockPos> spareAnchors) {
        if (spareAnchors.isEmpty() || !HousingBlueprints.available()) {
            return Set.of();
        }
        var reserved = new HashSet<BlockPos>();
        for (var home : HousingSavedData.get(level).homes(id)) {
            if (!spareAnchors.contains(home.bed())) {
                continue;
            }
            for (List<HousingBlueprints.ResolvedStep> stage : HousingBlueprints.stages(home.style())) {
                for (HousingBlueprints.ResolvedStep step : stage) {
                    reserved.add(home.bed().offset(step.x(), step.y(), step.z()));
                }
            }
        }
        return Set.copyOf(reserved);
    }
```

（`BlockPos.offset(int,int,int)` 是本项目已在用写法；`List`/`Set`/`HashSet` 若未 import 就补。）

- [ ] **Step 2: 在扫描里缓存它**

`Scan` 的字段区（`private List<BlockPos> spareAnchors = List.of();` 之后）加：

```java
        private Set<BlockPos> reserved = Set.of();
```

并在**两处**给 `spareAnchors` 赋值的地方（`scan.spareAnchors = spareCapacityAnchors(level, id, scan.heads);`）紧接着各加一行：

```java
                scan.reserved = reservedCells(level, id, scan.spareAnchors);
```

（缩进按所在分支对齐；两处都要加，否则某一相位的复查会拿着空的预留集放床。）

- [ ] **Step 3: 把预留格加进候选格排除项**

`siteSuitable` 的签名加最后一个参数：

```java
    private static boolean siteSuitable(ServerLevel level, SettlementSavedData data, String id,
                                        BlockPos foot, BlockPos head, List<BlockPos> beds,
                                        List<BlockPos> spareAnchors, Set<BlockPos> reserved) {
```

并在它内部那个 `for (BlockPos pos : List.of(foot, head))` 的排除条件里、`nearBed(beds, pos)` **之后**加一项：

```java
                    || reserved.contains(pos)
```

两个调用点（`siteSuitable(level, data, id, scan.site.foot(), scan.site.head(), scan.beds, scan.spareAnchors)` 与 `siteSuitable(level, data, scan.id, foot, head, scan.beds, scan.spareAnchors)`）各补最后一个实参 `scan.reserved`。

（那条排除项旁边**加一行注释**说明为什么：这一格被蓝图预留，床站上去会让那一步永远放不下去。）

- [ ] **Step 4: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，19 项全部 `*Check passed`，无编译警告。

**本任务不可纯测**（要真实世界与候选格扫描）：证据只有编译、grep 与代码审查。请在报告里如实写明。

- [ ] **Step 5: 核对没有第二份判据**

Run: `grep -rn "stages(\|BlueprintSet\|reserved" src/main/java/dev/local/goblinsettlement/housing/BedProvisioningCoordinator.java`

Expected: 该文件只**读蓝图已有的级表**（`HousingBlueprints.stages(home.style())`）并把每一级的相对坐标**加上锚点**换算成世界格——**不自己判断"哪一层会用到哪些格"**。（它不直接调 `BlueprintSet.reserved` 是对的：那是一个按坐标的成员判定，而这里要的是**整套格子**；两者都从同一份级表派生，`reserved` 与 `reservedCells` 是这个事实的两种查询形状。）

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BedProvisioningCoordinator.java
git commit -m "Keep new beds off the cells the blueprints will need"
```

---

### Task 3: 为什么开不了工（诊断）

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`

**Interfaces:**
- Consumes: `HousingCoordinator.nextSite` / `stepAt` / `permitted`（既有私有）、`HousingBlueprints`、`PublicWarehouseInventory`（既有）
- Produces: `HousingCoordinator.blockedReason(...) -> Optional<String>`（**必须 `public`**：调用方 `GoblinSettlement` 在另一个包里）

- [ ] **Step 1: 把门禁提成一个判据**

在 `HousingCoordinator` 的 `advance` **之后**加：

```java
    /**
     * Why this home cannot start its next step right now, or empty when it can (or is already finished).
     * One authority: dispatch and the status line both ask here, so the reason shown can never drift
     * from the rule that actually blocks the work. Read-only -- it decides nothing and changes nothing.
     */
    public static Optional<String> blockedReason(ServerLevel level, SettlementSavedData data,
                                                HousingSavedData.Home home, String id) {
        if (!HousingBlueprints.available()) {
            return Optional.of("the blueprint data is unavailable");
        }
        BlockPos site = nextSite(level, home);
        if (site == null) {
            // Either everything is built, or the anchor is no longer a bed head -- and the second case
            // is a stall nobody would otherwise see, so it must not read as "fine".
            return isBedHead(level, id, home.bed())
                    ? Optional.empty() : Optional.of("the anchor is no longer a bed head");
        }
        if (!permitted(level, id, site)) {
            return Optional.of("land permission changed");
        }
        if (!level.getBlockState(site).isAir()) {
            return Optional.of("the next cell is occupied");
        }
        var step = stepAt(level, home, site);
        if (step.isEmpty()) {
            return Optional.of("the next step is not in the blueprint");
        }
        var stock = PublicWarehouseInventory.snapshot(level, data);
        int reserve = home.capacityTarget() >= 2
                ? HousingBlueprints.reserveExpanded() : HousingBlueprints.reserveBasic();
        if (!stock.complete()) {
            return Optional.of("some warehouses are unavailable");
        }
        if (PublicWarehouseInventory.countOf(level, data, step.get().item()) <= reserve) {
            return Optional.of("waiting for " + step.get().item());
        }
        if (PublicWarehouseInventory.firstHolding(level, data, step.get().item()).isEmpty()) {
            return Optional.of("no warehouse holds " + step.get().item());
        }
        return Optional.empty();
    }
```

- [ ] **Step 2: `advance` 改问它**

把 `advance` 里从 `BlockPos site = nextSite(level, home);` 到 `if (warehouse.isPresent()) {` **之前**的那一段（现在的 `nextSite` / `permitted` / `isAir` / `stepAt` / `stock` / `reserve` / `countOf` 七道判断）替换为：

```java
        BlockPos site = nextSite(level, home);
        if (site == null) {
            return false;
        }
        if (blockedReason(level, data, home, id).isPresent()) {
            return false;
        }
        var step = stepAt(level, home, site);
        if (step.isEmpty()) {
            return false;
        }
        var warehouse = PublicWarehouseInventory.firstHolding(level, data, step.get().item());
```

**`advance` 的结论必须与改前逐字相同**：能开工的那些情形仍然开工，不能开工的仍然返回 false。`site == null` 与 `step.isEmpty()` 这两支要保留（判据内部也算过，但这里需要 `site`/`step` 的值继续往下走）。

- [ ] **Step 3: status 加一行**

在 `GoblinSettlement` 的 `status` 子命令里、`Housing: beds=…` 那条 `sendSuccess` **之后**加：

```java
                                context.getSource().sendSuccess(() -> Component.literal(
                                        housingReportLine(level, data)), false);
```

并在 `construction/transport/TransportCommands` 那种"显示助手"的同一风格下，于 `GoblinSettlement` 里加一个私有助手（**只读、不派工、不改存档**）：

```java
    private static final int HOUSING_REPORT_LIMIT = 8;

    /** Which unfinished homes cannot start their next step, and why. Read-only. */
    private static String housingReportLine(ServerLevel level, SettlementSavedData data) {
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return "Housing work: no settlement in this dimension";
        }
        String id = settlement.get().id();
        var stalled = new java.util.ArrayList<String>();
        for (var home : HousingSavedData.get(level).homes(id)) {
            var reason = HousingCoordinator.blockedReason(level, data, home, id);
            if (reason.isPresent()) {
                stalled.add(home.bed().toShortString() + "(" + reason.orElseThrow() + ")");
            }
        }
        if (stalled.isEmpty()) {
            return "Housing work: every home can start its next step";
        }
        var builder = new StringBuilder("Housing work: ").append(stalled.size()).append(" stalled: ");
        for (int index = 0; index < Math.min(HOUSING_REPORT_LIMIT, stalled.size()); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(stalled.get(index));
        }
        if (stalled.size() > HOUSING_REPORT_LIMIT) {
            builder.append(", +").append(stalled.size() - HOUSING_REPORT_LIMIT).append(" more");
        }
        return builder.toString();
    }
```

（**不新建类**，助手就放在 `GoblinSettlement` 里；`HousingSavedData` 与 `HousingCoordinator` 若未 import 就补。）

- [ ] **Step 4: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，19 项全部 `*Check passed`，无编译警告。

- [ ] **Step 5: 核对判据只有一处、显示层只调它**

Run: `grep -rn "countOf\|firstHolding\|isAir()\|permitted(" src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`

Expected: **新加的 `housingReportLine` 里一条都没有**——它不算材料、不查方块、不判权限，全部来自 `HousingCoordinator.blockedReason`。（该文件别处本来就有的公共库存那几行不属本轮范围，不要动。）

再 Run: `grep -n "blockedReason" src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`

Expected: 一处定义、`advance` 一处调用。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Say why a home cannot start its next step"
```

---

### Task 4: 文档与收尾

**Files:**
- Modify: `goblin-settlement-plan/HOUSING_REBUILD_DESIGN.md`（加 §7 落地结果）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Tasks 1–3 的改动与构建结果（**含 `build/libs/goblin-settlement-0.1.0.jar` 的实际字节数、构建秒数、每个提交的哈希**）
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：先单独提交它们并署名，然后再做本任务的文档改动。

- [ ] **Step 2: 设计文档记录落地结果**

在 `HOUSING_REBUILD_DESIGN.md` 末尾追加：

```markdown
## 7. 落地结果（实现后补记）

- **纯判据**：`BlueprintSet.reserved(styleIndex, x, y, z)` —— 遍历 `stages(styleIndex)` 回答"这套风格的任何一级用不用这一格"。坐标是相对床头锚点的偏移，不碰世界、不碰存档。
- **预防接线**：`BedProvisioningCoordinator` 在扫描进入 FIND 相之前，按**有空位的锚点**算出 `reservedCells`（每个可绑定的房子、它自己风格的**全部级别**、偏移到世界坐标），缓存进 `Scan`；`siteSuitable` 的候选格排除项里加一条 `reserved.contains(pos)`（foot 与 head 两格都过）。**只收可绑定房子的蓝图**——并集全部风格会把本来可用的位置也挡掉。
- **诊断**：`HousingCoordinator.blockedReason(...)` 把 `advance` 原本内联的七道门禁提成一个只读判据，`advance` 与 `GoblinSettlement` 的 status 行都问它；显示层因此不算材料、不查方块、不判权限。**不新增持久字段**。
- **把人工核对变成断言**：`BlueprintCheck` 新增两条——`reserved` 判据本身的正反例（逐套风格），以及**发布数据里没有任何一级占用床头或它上方两格**。后者原先只是设计期人工算过一遍的性质，现在随每次构建跑。检查总数仍是 19（加进既有检查，不新增任务）。
- **仍未做**：不拆除（既没有可拆的对象，本轮也不新造这个能力）；不搬迁/回收床；不清理玩家放在蓝图格里的方块——只报告。已经站在蓝图格上的床仍会让那栋房卡住，靠 status 那一行说出来。
```

- [ ] **Step 3: 追加 UpdateLog**

在 `UpdateLog.md` **末尾**追加（`<...>` 换成真实值，**逐条写真实时间，不要留占位**——第四十八、五十、五十五、五十七轮都漏填过）：

```markdown
## [<开始> – <结束>] 第五十九轮：改建安全（预防与诊断）

- [<时间>] 按 HOUSING_REBUILD_DESIGN.md 与 HOUSING_REBUILD_PLAN.md 执行。**核对后缩小了范围**：设计期先确认了三件事——①升级链**从不拆方块**（全 housing/ 目录里唯一的 `Blocks.AIR` 是放床失败的自回滚），所以"拆前确认材料与临时住处"在现有能力下没有可拆的对象；②"确认材料"**已经是现状**（`HousingCoordinator.advance` 在挑人前就要求仓库快照完整、该步物品**高于**保留量、且真有一个仓位持有它）；③三套风格五条链十五级全算过，**没有任何一步落在床头或它上方两格**。因此本轮**不重做**②③，只补真正的缺口。
- [<时间>] **真正的缺口**：床由 `BedProvisioningCoordinator` 单独放进屋里，避让只看"离别的床/设施远不远""是否落在有空位房子的绑定半径内"，**不看蓝图**。而 `lean_to/walled` 与 `side_porch/walled` 在 0 层会用到 `(-1,0,0)` 与 `(1,0,0)`——床占两格，其中一格可能正是这些蓝图格；撞上那一步就永远放不下去（格子里有床），**那栋房的升级永久停住**，且现有代码既不解除也不说明。
- [<时间>] **预防**：`BlueprintSet` 加纯判据 `reserved(styleIndex, x, y, z)`（该风格任何一级用不用这一格，坐标相对床头锚点）；`BedProvisioningCoordinator` 在扫描里按**可绑定的房子**（有空位的锚点）预先算出 `reservedCells` 并缓存进 `Scan`，`siteSuitable` 的候选格排除项加一条 `reserved.contains(pos)`，foot 与 head 两格都过。**只收可绑定房子的蓝图**，不用全部风格的并集（那会过宽）。
- [<时间>] **诊断**：`HousingCoordinator.blockedReason(...)` 把 `advance` 内联的七道门禁提成一个只读判据，派工与 status 都问它——**不新增持久字段、不抄第二遍门禁**；`status` 加一行列出卡住的房子与第一个原因（按床短坐标、上限 8、其余折叠）。显示层因此不算材料、不查方块、不判权限。
- [<时间>] **把人工核对变成断言**：`BlueprintCheck` 新增 `checkCellsUsedByABlueprint`（判据正反例，逐套风格，含越界风格退化）与 `checkBedHeadroomStaysFree`（**发布数据里没有任何一级占用床头或它上方两格**——原先只是设计期手算过一遍的性质，现在是随构建跑的守门人）。**检查总数仍 19**，是加进既有检查而非新增任务。
- [<时间>] 验证：完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**19 项**独立检查全部 *Check passed。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>，耗时 <秒数>。提交 <哈希>。
- [<时间>] 未完成：不做玩法验收；**放床的新排除项、status 那一行、以及"床撞蓝图格会卡死"在游戏里的真实发生率全部不可纯测**（只有编译与代码审查）。**不拆除、不搬床、不清理玩家方块**：已经站在蓝图格上的床仍会让那栋房卡住，只能靠 status 说出来。逐段施工与材料确认本就是既有行为，本轮未改。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「接续须知」的**分支指针**改成本轮收尾提交；**下一步落点**改写：住宅剩余里的「改建安全（拆前确认材料与临时住处）」**已完成**（范围经核对缩小为"预防 + 诊断"，落点在 `HOUSING_REBUILD_DESIGN.md` §7）；住宅只剩公共设施、实体公告牌方块、住户分配、历史保留。
- 「阶段定位」阶段 6（或提到住宅的那句）补上"第五十九轮补上改建安全（床不再占蓝图格 + 卡住时说得出来）"。
- 「本轮接入的内容」新增一节「第五十九轮：改建安全」。
- 「住宅剩余」里那句"其余：改建安全（拆前确认材料与临时住处）、公共设施、实体公告牌方块、住户分配、历史保留"改写为：改建安全**已完成**并写明落点与"范围为何缩小"，其余四项不动。
- 「本轮验证进展」替换为第五十九轮：完整构建、19 项检查（**未新增检查任务，是在既有的 `blueprintCheck` 里补断言**）、产物字节数与耗时、提交；把"独立检查覆盖不到"的清单换成本轮的（放床的新排除项、status 那一行、扫描的代价、以及床撞格的真实发生率）。
- 别处提到检查项数的地方保持 19 ✓（本轮不变）。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the rebuild safety round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。若本机代理未运行导致连不上 github，提交留在本地并如实报告，不算任务失败。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 预防（纯判据 + 接线 + "任何一级" + "只问可绑定的房子"）→ Task 1（判据）与 Task 2（接线与缓存）。
- 设计 §3 诊断（只读、按需、不新增字段、门禁只留一处）→ Task 3（提取判据 + `advance` 改问它 + status 行）。
- 设计 §4 "明确不改的三件事" → Global Constraints + Task 4 的文档段落。
- 设计 §5 验证（判据正反例逐套风格 + 发布数据的净空不变式 + 仍 19 项）→ Task 1 Step 1/4。
- 设计 §6 风险（只预防将来、不搬床、不清理玩家方块、诊断只报第一个原因）→ Global Constraints 与 Task 4 Step 2/3 的"仍未做"段。

**2. 占位符扫描**

- 无 TBD/TODO。`reservedCells`、`blockedReason`、status 助手都给了全文；`siteSuitable` 的签名改动与两个调用点都点名。
- 设计 §5 那条"数据不变式若失败要回报、不要放宽断言、不要改数据"是**有判据的条件动作**（Task 1 Step 4），不是含糊指令。
- Task 4 的 `<...>` 是模板占位，正文要求逐条替换为真实值。

**3. 类型一致性**

- `BlueprintSet.reserved(int, int, int, int) -> boolean` 在 Task 1 定义、检查与 Task 2（经 `reservedCells`）使用，签名一致。
- `reservedCells(ServerLevel, String, List<BlockPos>) -> Set<BlockPos>` 在 Task 2 定义、两处扫描赋值点调用；`siteSuitable` 因此多一个 `Set<BlockPos>` 形参，两个调用点在同一任务里同步补上（**否则编不过**）。
- `blockedReason(ServerLevel, SettlementSavedData, HousingSavedData.Home, String) -> Optional<String>` 在 Task 3 定义、`advance` 与 `GoblinSettlement` 两处调用；`advance` 里 `site`/`step` 的局部变量保留，因为后续还要用它们的值。它内部用的 `isBedHead(level, id, bed)` 是 `HousingCoordinator` 既有的私有判据（Task 3 只在同类里调用它，不新写一份"这是不是床头"）。
- `HousingBlueprints.stages(int) -> List<List<ResolvedStep>>` 与 `HousingBlueprints.available()` 是既有签名（Task 2 消费）；`ResolvedStep` 的 `x()/y()/z()` 与 `BlueprintSet` 的 `HousingRules.Step` 同名同义，两者的偏移换算写法一致。
- `BlueprintSet.stages(int)` 返回 `List<List<HousingRules.Step>>`（既有），Task 1 的判据只读它的 `x()/y()/z()`。
