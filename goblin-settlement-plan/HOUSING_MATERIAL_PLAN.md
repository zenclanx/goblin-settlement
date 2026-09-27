# 住宅材料泛化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让住宅工人按蓝图每步声明的方块/物品取料、交付、放置，并删除第四十四轮留下的过渡约束（数据只许橡木木板）。

**Architecture:** 工人不保存"该搬什么"这个状态——每次由 `HousingCoordinator.assignedStep(level, bed, site)` 按**工人已知的坐标**从世界重推出那一步，与 HOUSING_DESIGN §3.1「进度由世界推导、不存游标」同源。协调器用同一个 `stepAt` 决定领料物品、仓库选取、领料闸与放置方块；仓库层复用既有的通用计数，只补一个按物品取仓的方法。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [HOUSING_MATERIAL_DESIGN.md](HOUSING_MATERIAL_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。禁止引入其他 Minecraft 版本的 API 或示例。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **不做游戏内验证**：按用户约定，初版代码全部完成后才统一测试。本轮只做编译 + 独立检查。
- **不改几何**：`BlueprintCheck.checkSteps` 的 `21/92/102/111/121` 与生成脚本输出必须保持不变。本轮只放宽"材质"这一条，不动坐标与步数。
- **不改通用施工路径**：`GoblinCitizenEntity.fetchMaterial` / `placeMaterial` / `carriedOakPlanks`，以及道路交付（约第 723 行）**一行都不改**。它们属 `ConstructionCoordinator` 那条线，不在住宅切片内。
- **不新增持久字段**：物品每次从世界重推。不改 `assignHousing` 签名，不改任何存档 codec。
- 纯层（`BlueprintSet`）不得依赖 Minecraft 类型。
- 检查沿用项目既有写法：无 JUnit，`main` + `require`，成功打印 `XxxCheck passed`。`blueprintCheck` 任务已存在，本轮不改 `build.gradle`。
- 代码注释用英文（与现有源码一致）。
- `goblin-settlement-plan/` 下可能有另一个 agent 未提交的改动：只暂存你自己改的文件，不要 `git add -A`。`UpdateLog.md` **只许在末尾追加**。

---

### Task 1: 删除过渡约束

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BlueprintSet.java`（删 `TRANSITIONAL_BLOCK` 与那条校验分支）
- Test: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/BlueprintCheck.java`

**Interfaces:**
- Consumes: 无新接口
- Produces: `BlueprintSet.validate` 不再有"只许橡木木板"这条规则；`KNOWN` 集合扩为两个 id

- [ ] **Step 1: 写失败检查（RED）**

在 `BlueprintCheck.java` 中做三处改动。

1a. 把 `KNOWN` 扩成两个 id：

```java
    private static final Set<String> KNOWN =
            Set.of("minecraft:oak_planks", "minecraft:stone");
```

1b. 把 `main` 里的 `checkTransitionalBlockRule();` 换成 `checkBlocksAreNotRestricted();`。

1c. 删掉 `checkTransitionalBlockRule()` 整个方法，换成：

```java
    private static void checkBlocksAreNotRestricted() {
        // Materials are general now: any known block with an item form is allowed.
        var stone = new HousingRules.Step(0, 3, 0, "minecraft:stone");
        var capacity = List.of(new BlueprintSet.Stage("a", "", List.of(stone)),
                cap("b", "a"), cap("c", "b"));
        require(valid(capacity).isEmpty(),
                "a known block other than oak planks is accepted: " + valid(capacity));

        var unknown = new HousingRules.Step(0, 3, 0, "minecraft:not_a_block");
        var otherCapacity = List.of(new BlueprintSet.Stage("a", "", List.of(unknown)),
                cap("b", "a"), cap("c", "b"));
        require(valid(otherCapacity).size() == 1, "an id outside the known set is still rejected");
    }
```

1d. 把 `checkSteps` 里对真实数据的材质断言从"必须是橡木木板"放宽为"必须在已知集合内"：

```java
            require(KNOWN.contains(step.block()), "every step names a known block");
```

（原来那行是 `require(step.block().equals("minecraft:oak_planks"), "the transitional block rule holds");`）

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew blueprintCheck --offline --no-daemon`

Expected: `AssertionError: a known block other than oak planks is accepted: [capacity/a: minecraft:stone is not yet supported -- until the worker path carries other materials, the data may only use minecraft:oak_planks]`，且 `BUILD FAILED`。失败原因必须是**过渡约束**，不是别的。

- [ ] **Step 3: 删除约束（GREEN）**

在 `BlueprintSet.java` 中：

3a. 删掉常量声明：

```java
    /** The only block the data may name until the worker path learns other materials. */
    public static final String TRANSITIONAL_BLOCK = "minecraft:oak_planks";
```

3b. 把 `validateSteps` 里这段

```java
            if (!knownBlocks.contains(step.block())) {
                problems.add(name + "/" + stage.id() + ": unknown block " + step.block());
            } else if (!TRANSITIONAL_BLOCK.equals(step.block())) {
                problems.add(name + "/" + stage.id() + ": " + step.block()
                        + " is not yet supported -- until the worker path carries other materials,"
                        + " the data may only use " + TRANSITIONAL_BLOCK);
            }
```

改成

```java
            if (!knownBlocks.contains(step.block())) {
                problems.add(name + "/" + stage.id() + ": unknown block " + step.block());
            }
```

- [ ] **Step 4: 运行检查，确认转绿**

Run: `./gradlew blueprintCheck --offline --no-daemon`

Expected: `BlueprintCheck passed`。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BlueprintSet.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/BlueprintCheck.java
git commit -m "Let the blueprint data name any block with an item form"
```

---

### Task 2: 仓库层与协调器层按物品通用

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/PublicWarehouseInventory.java`（新增 `firstHolding`）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`（新增 `stepAt`/`assignedStep`；改 `placeByResident`、领料闸、仓库选取）

**Interfaces:**
- Consumes: `HousingBlueprints.steps(int,int)`、`HousingBlueprints.ResolvedStep.block()/item()`、`PublicWarehouseInventory.countOf(ServerLevel, SettlementSavedData, Item)`（既有）、私有的 `countAt(ServerLevel, SettlementSavedData, BlockPos, Item)`（既有）
- Produces:
  - `PublicWarehouseInventory.firstHolding(ServerLevel, SettlementSavedData, Item) -> Optional<BlockPos>`
  - `HousingCoordinator.stepAt(ServerLevel, HousingSavedData.Home, BlockPos) -> Optional<HousingBlueprints.ResolvedStep>`（**包内可见**）
  - `HousingCoordinator.assignedStep(ServerLevel, BlockPos bed, BlockPos site) -> Optional<HousingBlueprints.ResolvedStep>`（public，供 `GoblinCitizenEntity` 用）

- [ ] **Step 1: 仓库层加通用取仓**

在 `PublicWarehouseInventory.java` 的 `firstWithOakPlank` 之后加：

```java
    /** The first accessible warehouse holding at least one of the given item. */
    public static Optional<BlockPos> firstHolding(ServerLevel level, SettlementSavedData data,
                                                  Item item) {
        return data.warehouses().stream()
                .filter(pos -> countAt(level, data, pos, item) > 0).findFirst();
    }
```

并把 `firstWithOakPlank` 的方法体改成委托，避免第二份实现：

```java
    public static Optional<BlockPos> firstWithOakPlank(ServerLevel level, SettlementSavedData data) {
        return firstHolding(level, data, Items.OAK_PLANKS);
    }
```

此时 `countAt(level, data, pos)` 那个只吃 `(level, data, pos)` 的私有重载若已无调用方，一并删除（它只是 `countAt(level, data, pos, Items.OAK_PLANKS)` 的包装）。删前先 Run: `grep -n "countAt(level, data, pos)" src/main/java/dev/local/goblinsettlement/economy/PublicWarehouseInventory.java` 确认只剩定义处。

- [ ] **Step 2: 协调器加"按位置找这一步"**

在 `HousingCoordinator.java` 中 `nextSite` 方法之后加：

```java
    /**
     * The step this home expects at this site, matched by position. Matching by the worker's own
     * coordinates rather than by nextSite is deliberate: the worker already stands at this site, and
     * if the target moved the assignment is stale -- the caller must return the material, not fetch
     * something else.
     */
    static Optional<HousingBlueprints.ResolvedStep> stepAt(ServerLevel level,
                                                           HousingSavedData.Home home,
                                                           BlockPos site) {
        for (HousingBlueprints.ResolvedStep step
                : HousingBlueprints.steps(home.capacityTarget(), home.qualityTarget())) {
            if (site.equals(position(level, home.bed(), home.variant(), step))) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    /** The step a worker holding this bed and site was sent to build; empty when the assignment is stale. */
    public static Optional<HousingBlueprints.ResolvedStep> assignedStep(ServerLevel level, BlockPos bed,
                                                                       BlockPos site) {
        var settlement = SettlementSavedData.get(level).settlement();
        if (settlement.isEmpty()) {
            return Optional.empty();
        }
        for (var home : HousingSavedData.get(level).homes(settlement.orElseThrow().id())) {
            if (home.bed().equals(bed)) {
                return stepAt(level, home, site);
            }
        }
        return Optional.empty();
    }
```

- [ ] **Step 3: 改造 `placeByResident`**

把 `placeByResident` 整个方法替换为：

```java
    public static boolean placeByResident(ServerLevel level, GoblinCitizenEntity worker,
                                          BlockPos bed, BlockPos site, ItemStack carried) {
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || !assignedSite(level, bed, worker.getUUID().toString(), site)
                || carried.getCount() != 1
                || worker.distanceToSqr(site.getCenter()) > 16.0
                || !level.getGameRules().get(GameRules.MOB_GRIEFING)
                || !permitted(level, settlement.orElseThrow().id(), worker.blockPosition())
                || !permitted(level, settlement.orElseThrow().id(), site)
                || !level.getBlockState(site).isAir()) return false;
        var step = stepAt(level, HousingSavedData.get(level)
                .homes(settlement.orElseThrow().id()).stream()
                .filter(home -> home.bed().equals(bed)).findFirst().orElse(null), site);
        if (step.isEmpty() || !carried.is(step.get().item())) return false;
        var placed = step.get().block().defaultBlockState();
        if (!level.setBlock(site, placed, 3)) return false;
        return level.getBlockState(site).is(step.get().block());
    }
```

注意：`stepAt(level, home, site)` 的 `home` 参数需要一个 `Home`。上面用流查找是为了避免把 `home` 传进来而改签名。若嫌这段内联太长，可改为在 `stepAt` 里接受 `bed` 并自行查 home —— 但**不要**改 `placeByResident` 的签名，它是 `GoblinCitizenEntity` 的调用契约。更清晰的做法是让 `assignedStep(level, bed, site)` 直接可用：

```java
        var step = assignedStep(level, bed, site);
        if (step.isEmpty() || !carried.is(step.get().item())) return false;
        var placed = step.get().block().defaultBlockState();
        if (!level.setBlock(site, placed, 3)) return false;
        return level.getBlockState(site).is(step.get().block());
```

**用这一段**（`assignedStep` 已按床找到 home 再委托 `stepAt`，语义完全相同且更短）。

- [ ] **Step 4: 改造领料闸与仓库选取**

在 `advance` 中，把

```java
        var stock = PublicWarehouseInventory.snapshot(level, data);
        int reserve = home.capacityTarget() >= 2
                ? HousingBlueprints.reserveExpanded() : HousingBlueprints.reserveBasic();
        if (!stock.complete() || stock.oakPlanks() <= reserve) return false;
        var warehouse = PublicWarehouseInventory.firstWithOakPlank(level, data);
```

替换为：

```java
        var step = stepAt(level, home, site);
        if (step.isEmpty()) return false;
        var stock = PublicWarehouseInventory.snapshot(level, data);
        int reserve = home.capacityTarget() >= 2
                ? HousingBlueprints.reserveExpanded() : HousingBlueprints.reserveBasic();
        if (!stock.complete()
                || PublicWarehouseInventory.countOf(level, data, step.get().item()) <= reserve) {
            return false;
        }
        var warehouse = PublicWarehouseInventory.firstHolding(level, data, step.get().item());
```

`stock.complete()` 保留——它表达"仓库快照完整"，与具体物品无关；`countOf` 在仓库不可访问时返回 0，因此这个组合与原来的 `stock.oakPlanks()` 语义一致。

- [ ] **Step 5: 编译并跑全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，12 项检查全部 `*Check passed`，无编译警告。

（此时数据仍是清一色橡木木板，`step.item()` 恒为 `Items.OAK_PLANKS`，行为与改动前等价——这正是本任务可以独立成立的原因。`GoblinCitizenEntity` 尚未调用 `assignedStep`，所以 `assignedStep` 目前只有 `placeByResident` 一个调用方，这是暂时的，Task 3 会补上。）

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/PublicWarehouseInventory.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java
git commit -m "Resolve the housing material per step instead of assuming planks"
```

---

### Task 3: 实体层按声明的物品施工

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`（`HOUSING_FETCHING` / `HOUSING_DELIVERING`，约第 786–816 行；`returnCarriedToSupply`，约第 1874 行）

**Interfaces:**
- Consumes: `HousingCoordinator.assignedStep(ServerLevel, BlockPos, BlockPos) -> Optional<HousingBlueprints.ResolvedStep>`（Task 2 产出）
- Produces: 无新接口；工人行为改为按步骤物品

- [ ] **Step 1: 改造 `HOUSING_FETCHING`**

把这段

```java
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.getItem(slot).is(Items.OAK_PLANKS)) continue;
                ItemStack withdrawn = container.removeItem(slot, 1);
                if (withdrawn.is(Items.OAK_PLANKS) && withdrawn.getCount() == 1) {
                    carried = withdrawn;
                    container.setChanged();
                    workStage = WorkStage.HOUSING_DELIVERING;
                    waitReason = "";
                }
                return;
            }
            waitReason = "housing planks missing";
```

替换为（在进入仓库循环前先问 `assignedStep`）：

```java
            var step = HousingCoordinator.assignedStep(level, housingBed, buildPos);
            if (step.isEmpty()) {
                workStage = WorkStage.HOUSING_RETURNING;
                waitReason = "housing assignment changed";
                return;
            }
            var wanted = step.get().item();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.getItem(slot).is(wanted)) continue;
                ItemStack withdrawn = container.removeItem(slot, 1);
                if (withdrawn.is(wanted) && withdrawn.getCount() == 1) {
                    carried = withdrawn;
                    container.setChanged();
                    workStage = WorkStage.HOUSING_DELIVERING;
                    waitReason = "";
                }
                return;
            }
            waitReason = "housing material missing";
```

注意：`var` 用于 `Optional` 与 `Item` 是为了避免新增 import（工程里 `Item` 未导入到本文件）。`waitReason` 不拼接物品名——`Item` 的 `toString` 在本映射下不保证是资源名。

同时把这段之前的"仓库容器缺失"守卫保留原样（`waitReason = "housing warehouse missing";`）。

- [ ] **Step 2: 改造 `HOUSING_DELIVERING`**

把

```java
        } else if (workStage == WorkStage.HOUSING_DELIVERING) {
            if (level.getBlockState(buildPos).is(Blocks.OAK_PLANKS)) {
                workStage = WorkStage.HOUSING_RETURNING;
                return;
            }
```

替换为

```java
        } else if (workStage == WorkStage.HOUSING_DELIVERING) {
            var step = HousingCoordinator.assignedStep(level, housingBed, buildPos);
            if (step.isEmpty()) {
                workStage = WorkStage.HOUSING_RETURNING;
                waitReason = "housing assignment changed";
                return;
            }
            if (level.getBlockState(buildPos).is(step.get().block())) {
                workStage = WorkStage.HOUSING_RETURNING;
                return;
            }
```

后面的 `HousingCoordinator.placeByResident(...)` 调用与 `HOUSING_COMPLETE` 分支保持不变。

- [ ] **Step 3: 修正归还时的合并条件**

在 `returnCarriedToSupply` 中把

```java
            } else if (existing.is(Items.OAK_PLANKS)
                    && existing.getCount() < Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing))) {
```

改成

```java
            } else if (existing.is(carried.getItem())
                    && existing.getCount() < Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing))) {
```

这是住宅与道路**共用**的方法。工人一次只带 1 件，放空槽本来也能work；但原写法对非橡木材质会绕过合并分支，并在"仓库只剩同类未满堆"时表现为仓库满。对道路路径（会带原木/栅栏/火把）同样是更正。

- [ ] **Step 4: 编译并跑全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，12 项检查全部 `*Check passed`，无编译警告。

- [ ] **Step 5: 核对没有误改通用施工路径**

Run: `grep -n "Items.OAK_PLANKS\|Blocks.OAK_PLANKS" src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`

Expected: 剩下的命中**只**出现在 `TRANSPORT_*`（约 723）、`fetchMaterial`、`placeMaterial`、`carriedOakPlanks` 这几处——即本轮明确不动的通用施工与道路路径。`HOUSING_*` 分支里不应再有 `OAK_PLANKS`。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java
git commit -m "Carry the material each housing step declares"
```

---

### Task 4: 完整构建收尾与项目文档

**Files:**
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1–3 的全部改动与构建结果
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 确认工作区状态**

Run: `git -C .. status --short`

Expected: 只有你自己改的文件。若出现 `goblin-settlement-plan/` 下你没改过的文件，只暂存你自己的，不要动它们的。

- [ ] **Step 2: 追加 UpdateLog**

在末尾追加（时间换成实际操作时刻，格式 `YYYY-MM-DD HH:MM:SS +08:00`）：

```markdown
## [<开始> – <结束>] 第四十五轮：住宅材料泛化（工人按每步声明的物品施工）

- [<时间>] 按 HOUSING_MATERIAL_DESIGN.md 执行，落实 HOUSING_BLUEPRINT_DESIGN §8 第 1 条，即第四十四轮留下的过渡约束的解除。
- [<时间>] **范围更正**：上一轮把工作量估成"GoblinCitizenEntity 约 10 处硬编码橡木木板"，细查后是把住宅路径与通用施工路径混在了一起。真正的住宅路径只有 3 处（HOUSING_FETCHING/HOUSING_DELIVERING，约 796/798/808 行）；723 属道路交付、1874/1899/1914/1933 属 ConstructionCoordinator 那条线。原写"assignHousing 签名带上目标物品"也不再需要——改为每次从世界重推。
- [<时间>] Task 1：删除 BlueprintSet 的 TRANSITIONAL_BLOCK 与其校验分支。TDD：BlueprintCheck 先新增"已知但非橡木木板的方块被接受"，在删约束前失败于该约束，删除后转绿；同时把真实数据的材质断言从"必须是橡木木板"放宽为"必须在已知集合内"。
- [<时间>] Task 2：PublicWarehouseInventory 新增 firstHolding(level, data, Item)，firstWithOakPlank 改为委托它（既有调用方行为不变）；HousingCoordinator 新增 stepAt/assignedStep（按工人已知坐标从世界重推这一步），placeByResident 改用步骤的物品与方块，领料闸改走 countOf(level, data, step.item())，仓库选取改走 firstHolding。
- [<时间>] Task 3：GoblinCitizenEntity 的 HOUSING_FETCHING/HOUSING_DELIVERING 改为先问 assignedStep，为空一律转 HOUSING_RETURNING（不拿旧物品硬干）；取料/判已建的橡木木板判断改为按步骤物品与方块；returnCarriedToSupply 的合并条件由"已有的橡木木板堆"改为"已有的同类物品堆"（住宅与道路共用，对道路也是更正）。通用施工路径（fetchMaterial/placeMaterial/carriedOakPlanks）与道路交付一行未改。
- [<时间>] 未新增任何持久字段：物品每次从世界重推，与 HOUSING_DESIGN §3.1「不保存游标」同源；assignHousing 签名与存档 codec 均未改动。
- [<时间>] 验证：./gradlew build --offline --no-daemon BUILD SUCCESSFUL，12 项独立检查全部 *Check passed；核对 HOUSING_* 分支内已无 OAK_PLANKS 残留，剩余命中只在明确不动的通用施工与道路路径。未启动游戏、未运行专用服务端、未触碰常用存档。
- [<时间>] 未完成：**改动全部落在从未游戏内验证过的工人路径上**，编译与纯函数检查不能证明它在游戏里跑得对，这是统一测试时要优先观察的部分；现有几何仍是清一色橡木木板，"能放别的材质"只被合成用例覆盖、没有真实蓝图验证；材质写错在游戏里的表现是工人反复 waitReason 卡住而不是报错，排查需看 waitReason；数据包重载、其余内容数据化、通用施工路径的材料泛化均未做。
```

- [ ] **Step 3: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 第 3 条「住宅剩余」：把材料泛化记为**已完成**（写明：过渡约束已删除、工人按步骤物品施工、不新增持久字段），并把"通用施工路径的材料泛化"作为新发现的、明确未做的项列进"其余"。
- 「本轮接入的内容」新增一节「第四十五轮：住宅材料泛化」。
- 「本轮验证进展」替换为本轮构建与检查结果。
- 「阶段定位」阶段 6：把"材料仍是单一材质"改为"能力已就绪、但现有几何仍只用橡木木板"。

- [ ] **Step 4: 提交并推送**

```bash
git add goblin-settlement-plan/UpdateLog.md goblin-settlement-plan/CURRENT_STATUS.md
git commit -m "Record the housing material round"
git push origin main
```

Expected: 推送成功（本机 `http.proxy=http://127.0.0.1:7890` 已配置）。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。

---

## 自查记录

**1. 规格覆盖**

- 设计 §1 范围（只做住宅；不做通用施工路径）→ Task 2/3 的文件清单与 Global Constraints 的"不改通用施工路径"，以及 Task 3 Step 5 的核对。
- 设计 §2 删除过渡约束 → Task 1 全部。
- 设计 §3 仓库层 → Task 2 Step 1。
- 设计 §4 `stepAt`/`assignedStep` → Task 2 Step 2；`placeByResident` → Step 3；领料闸与仓库选取 → Step 4。
- 设计 §5 实体三段 → Task 3 Step 1/2/3。
- 设计 §6 为什么按位置匹配 → `stepAt` 的 javadoc 原样写进 Step 2 的代码。
- 设计 §7 验证方式 → Task 1 的 RED 步骤、每个任务的完整构建步骤。
- 设计 §8 风险 → Task 4 Step 2/3 的记录条目逐条对应。

**2. 占位符扫描**

- 无 TBD/TODO/待填。每个步骤都是可粘贴的最终代码或可执行命令。
- Task 2 Step 3 先给出一版内联查 home 的写法、随即明确"用这一段"（`assignedStep` 版本）并说明前者的问题——这是**刻意的对照说明**，不是两个待选项；两段代码都给全了，不存在待填。
- Task 2 Step 1 提到删除一个可能失去调用方的私有重载，并给出**先 grep 确认**的具体命令；这是条件性删除，条件与判据都写明。

**3. 类型一致性**

- `assignedStep` 返回 `Optional<HousingBlueprints.ResolvedStep>`，Task 2 的 `placeByResident` 与 Task 3 的实体两处都用 `step.get().item()` / `step.get().block()`，与 `ResolvedStep(int x, int y, int z, Block block, Item item)` 的既有定义一致。
- `stepAt` 是**包内可见**（`static`，无修饰符），`assignedStep` 是 `public`——因为后者被 `citizen` 包调用，前者只在 `housing` 包内使用。
- `firstHolding` 返回 `Optional<BlockPos>`，与 `firstWithOakPlank` 的既有返回类型一致，因此 `advance` 里 `warehouse.isPresent()` 之后的代码无需改动。
- `countOf(ServerLevel, SettlementSavedData, Item)` 与 `countAt(ServerLevel, SettlementSavedData, BlockPos, Item)` 均为既有签名，本计划未改动它们。
- 实体侧 `var wanted = step.get().item();` 推出 `Item`，因此本文件不需要新增 `net.minecraft.world.item.Item` 的 import。
