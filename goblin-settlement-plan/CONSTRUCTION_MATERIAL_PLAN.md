# 施工路径的材料泛化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让施工路径按**计划声明的建筑件**取料、携带、放置与回收，而不是写死橡木木板；同时把"一种建筑件对应哪个方块与物品"收敛成**唯一一份事实**。

**Architecture:** 把材质枚举从 `TransportPlan` 提到两者共同的父包成为 `construction/BuildMaterial`（常量同名、带上 `block()` / `item()` 两张表），交通线换用它（存档取值是常量名，**旧档一字不改仍能读**）；`ConstructionPlan` 增一个 `material` 字段（`optionalFieldOf` 默认橡木木板 ⇒ 旧档行为不变）；工人路径每次从计划重推材质（**不在实体上新增持久字段**），协调器改读同一份声明；`DroppedMaterialLookup` 由"写死橡木"改为按声明的物品匹配。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [CONSTRUCTION_MATERIAL_DESIGN.md](CONSTRUCTION_MATERIAL_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行（一次 25–40 秒，Bash 超时给 300000 ms）。
- **存档可见面不许变**：`BuildMaterial` 的四个常量名必须逐字保持 `OAK_PLANKS / OAK_LOG / OAK_FENCE / TORCH`；`TransportPlan` 与 `ConstructionPlan` 的 JSON **键名**（`material`、`item_id` 等）与取值写法不变；新字段一律 `optionalFieldOf` 带默认值，**旧档必须能打开且行为不变**。
- **一份事实只留一处**：方块/物品的对应只在 `BuildMaterial.block()` / `BuildMaterial.item()`；"这个工人要铺什么"只在 `SettlementSavedData.materialFor(workerId)`；"计划建的是什么"只在 `ConstructionPlan.material()`。
- **形状与材料种类都不动**：`ConstructionPlan.LENGTH = 2` 保持；`BuildMaterial` 仍是四种；`MAX_ACTIVE_PLANS = 8`、重叠校验、取消路径、站点可用性与权限判定都不改。
- 工人侧**不新增持久字段**，材质每次从计划重推（与第四十五轮住宅路径同一取舍）。
- 检查项数由 **17 增至 18**（新增 `constructionMaterialCheck`）。构建结束时 18 项必须全部 `*Check passed`。
- **每个任务都必须让整棵树可编译**：改签名时同步改调用点（下一任务再把它做完整）。
- 不做游戏内验证（按用户约定）。工人取料/放置、协调器找料、回收匹配、命令显示都**不可纯测**，会写进日志与状态文件。
- `goblin-settlement-plan/` 下可能有并行 agent 的未提交改动：文档任务先跑 `git status`，不是自己改的就先单独提交并署名。`UpdateLog.md` **只许在末尾追加**。
- 提交到 `main`，不要另开分支；本轮结束推送。

---

### Task 1: 材质的唯一出处，交通线换用

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/BuildMaterial.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/ConstructionMaterialCheck.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportPlan.java`（删内部 `Material` 枚举，`Step.material()` 改类型）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java`（删 `itemFor`/`blockFor`，改所有引用）
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/transport/TransportSavedDataCheck.java`（夹具里的枚举引用）
- Modify: `goblin-settlement-mod/build.gradle`（注册第 18 项）

**Interfaces:**
- Consumes: 无
- Produces: `BuildMaterial`（`OAK_PLANKS` / `OAK_LOG` / `OAK_FENCE` / `TORCH`，带 `block()` / `item()`）

- [ ] **Step 1: 先写检查（RED）**

创建 `src/test/java/dev/local/goblinsettlement/construction/ConstructionMaterialCheck.java`（**开头必须引导一次 Minecraft**：`BuildMaterial` 的静态字段持有 `Blocks.*` / `Items.*`，那些是注册表对象，不引导会直接抛 `Not bootstrapped`。这与 `BlueprintCheck` 那类只碰数据模型的检查不同，是本轮新增的代价——**凡是要碰 `BuildMaterial` 的独立检查都要引这两行**）：

```java
package dev.local.goblinsettlement.construction;

import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class ConstructionMaterialCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkEveryMaterialIsBuildable();
        checkStoredNamesAreStable();
        System.out.println("ConstructionMaterialCheck passed");
    }

    /** A material with no block or no item could never be fetched or placed. */
    private static void checkEveryMaterialIsBuildable() {
        for (BuildMaterial material : BuildMaterial.values()) {
            require(material.block() != null, material + " names a block");
            require(material.item() != null, material + " names an item");
        }
    }

    /**
     * These names are what saves already hold. Renaming a constant would silently change what every
     * finished road, bridge and pending project is made of, so they are pinned here.
     */
    private static void checkStoredNamesAreStable() {
        List<String> names = Arrays.stream(BuildMaterial.values()).map(Enum::name).toList();
        require(names.equals(List.of("OAK_PLANKS", "OAK_LOG", "OAK_FENCE", "TORCH")),
                "the stored material names are the ones already in saves");
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

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 类 BuildMaterial`。

- [ ] **Step 3: 写 `BuildMaterial`**

创建 `src/main/java/dev/local/goblinsettlement/construction/BuildMaterial.java`：

```java
package dev.local.goblinsettlement.construction;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * What a build can be made of. One authority: the road and bridge line and the small construction line
 * both ask here, so neither can hold a second opinion about which block an oak plank is.
 */
public enum BuildMaterial {
    OAK_PLANKS(Blocks.OAK_PLANKS, Items.OAK_PLANKS),
    OAK_LOG(Blocks.OAK_LOG, Items.OAK_LOG),
    OAK_FENCE(Blocks.OAK_FENCE, Items.OAK_FENCE),
    TORCH(Blocks.TORCH, Items.TORCH);

    private final Block block;
    private final Item item;

    BuildMaterial(Block block, Item item) {
        this.block = block;
        this.item = item;
    }

    /** The block a resident places for this material. */
    public Block block() {
        return block;
    }

    /** The item a resident withdraws from a warehouse for this material. */
    public Item item() {
        return item;
    }
}
```

- [ ] **Step 4: 交通线改用共享枚举**

4a. `TransportPlan.java`：删掉整个 `public enum Material { OAK_PLANKS, OAK_LOG, OAK_FENCE, TORCH }` 与 `Step` 里那条 `MATERIAL_CODEC`，把 `Step` 的 `material` 分量改类型并加 import：

```java
    public record Step(Phase phase, BlockPos site, BuildMaterial material, Rule rule) {
```
`Step.CODEC` 里那条改成：

```java
                Codec.STRING.xmap(BuildMaterial::valueOf, BuildMaterial::name).fieldOf("material")
                        .forGetter(Step::material),
```
并加 `import dev.local.goblinsettlement.construction.BuildMaterial;`。

4b. `TransportCoordinator.java`：把 **9 处** `TransportPlan.Material.X` 改成 `BuildMaterial.X`（第 78、120、132、146、152、156、160、212 行与第 697 行的 `step.material() == TransportPlan.Material.TORCH`），把 `materialItem(TransportPlan.Material)` 的形参类型改为 `BuildMaterial` 并让方法体直接返回 `material.item()`，**删掉私有的 `itemFor` 与 `blockFor`**，把它们原来的调用点（同文件内若干处）改为 `step.material().item()` / `step.material().block()`。

4c. `TransportSavedDataCheck.java`：夹具里的 `TransportPlan.Material.OAK_PLANKS` 改为 `BuildMaterial.OAK_PLANKS`（两处），加 import，**并在 `main` 开头补上** `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();`——它现在会碰到 `BuildMaterial` 的注册表字段。**既有的断言一行都不许改**：那批断言里 codec 往返的那几条，正是"换包没有改坏存档格式"的证据。

- [ ] **Step 5: 注册第 18 项检查**

在 `build.gradle` 里 `transportConnectivityCheck` 任务块**之后**、`tasks.named('check')` **之前**插入：

```gradle
tasks.register('constructionMaterialCheck', JavaExec) {
    group = 'verification'
    description = 'Checks the shared building materials and the names saves already hold.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.construction.ConstructionMaterialCheck'
}
```

并在 `tasks.named('check')` 的 `dependsOn` 列表末尾追加：

```gradle
    dependsOn tasks.named('constructionMaterialCheck')
```

- [ ] **Step 6: 跑完整构建，确认 18 项**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**18 项**检查全部 `*Check passed`（含 `ConstructionMaterialCheck passed`、以及**换包后仍然通过的** `TransportSavedDataCheck passed`），无编译警告。

- [ ] **Step 7: 核对没有第二份事实**

Run: `grep -rn "TransportPlan.Material\|private static Item itemFor\|private static Block blockFor" src/main/java/dev/local/goblinsettlement/construction/transport/`

Expected: **一条都没有**——交通线不再自己**把材料映射到方块与物品**，也没了那个内部枚举。

再 Run: `grep -rn "Items.OAK_PLANKS\|Blocks.OAK_PLANKS" src/main/java/dev/local/goblinsettlement/construction/transport/`

Expected: **还有几处是允许的、不要动**——`TrafficProposalCoordinator` 的立项门禁（它说的是"修路/架桥这条线规定用木板/原木/栅栏/火把"，属该线自己的用料规则）与 `TransportCoordinator.buildableGround` 的"可铺地面"判定（说的是"什么样的地面能铺路"）。**本条判据是"不该有映射"，不是"不该提材料名"**。

- [ ] **Step 8: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/BuildMaterial.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/ConstructionMaterialCheck.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportPlan.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/transport/TransportSavedDataCheck.java \
        goblin-settlement-mod/build.gradle
git commit -m "Give every build one shared answer to what it is made of"
```

---

### Task 2: 计划声明建筑件

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionPlan.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementSavedData.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionCommands.java`（**仅**最小改动以保持可编译，见 Step 4）
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/SettlementSavedDataCheck.java`（**`planTwoPlanks` 的调用点这里还有 8 处**，且它现在会经 codec 碰到 `BuildMaterial`，需要加引导两行）
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/ConstructionMaterialCheck.java`

**Interfaces:**
- Consumes: `BuildMaterial`（Task 1）
- Produces:
  - `ConstructionPlan.Material` 分量 `material()`（`BuildMaterial`）
  - `SettlementSavedData.planStructure(BlockPos start, BuildMaterial material) -> boolean`

- [ ] **Step 1: 先写检查（RED）**

在 `ConstructionMaterialCheck.java` 的 `main` 里加两行调用 `checkPlanRoundTrip();` 与 `checkOldPlanDefaultsToOak();`（放在现有两行之后），并在文件末尾（`require` 之前）加：

```java
    private static void checkPlanRoundTrip() {
        var plan = new ConstructionPlan(new BlockPos(10, 64, 10), 1, Optional.of("worker"),
                Optional.empty(), Optional.of("last"), BuildMaterial.OAK_LOG);
        var json = ConstructionPlan.CODEC.encodeStart(JsonOps.INSTANCE, plan).getOrThrow();
        var reloaded = ConstructionPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        require(reloaded.material() == BuildMaterial.OAK_LOG, "a plan keeps the material it was made of");
        require(reloaded.equals(plan), "and survives the round trip whole");
    }

    private static void checkOldPlanDefaultsToOak() {
        var plan = new ConstructionPlan(new BlockPos(0, 70, 0), 0, Optional.empty(),
                Optional.empty(), Optional.empty(), BuildMaterial.OAK_PLANKS);
        var json = ConstructionPlan.CODEC.encodeStart(JsonOps.INSTANCE, plan).getOrThrow()
                .getAsJsonObject().deepCopy();
        json.remove("material");
        require(ConstructionPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().material()
                        == BuildMaterial.OAK_PLANKS,
                "a project written before this round still builds oak planks");
    }
```

并在 import 区补上：

```java
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
```

**（控制器补充，来自 Task 1 的评审）**：该检查目前只钉住常量名与"非空"，一个常量若被换成**别的**方块或物品不会触发任何断言。同时把映射也钉住——在 `main` 里加上 `checkEachMaterialMapsToItsOwnBlockAndItem();`，并加这个方法：

```java
    /** Each material must keep naming its own block and item, not merely some block and some item. */
    private static void checkEachMaterialMapsToItsOwnBlockAndItem() {
        require(BuildMaterial.OAK_PLANKS.block() == Blocks.OAK_PLANKS, "oak planks build oak planks");
        require(BuildMaterial.OAK_LOG.block() == Blocks.OAK_LOG, "oak logs build oak logs");
        require(BuildMaterial.OAK_FENCE.block() == Blocks.OAK_FENCE, "oak fences build oak fences");
        require(BuildMaterial.TORCH.block() == Blocks.TORCH, "torches build torches");
        require(BuildMaterial.OAK_PLANKS.item() == Items.OAK_PLANKS, "oak planks are fetched as oak planks");
        require(BuildMaterial.OAK_LOG.item() == Items.OAK_LOG, "oak logs are fetched as oak logs");
        require(BuildMaterial.OAK_FENCE.item() == Items.OAK_FENCE, "oak fences are fetched as oak fences");
        require(BuildMaterial.TORCH.item() == Items.TORCH, "torches are fetched as torches");
    }
```

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew compileTestJava --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 方法 material()` / `构造器 ConstructionPlan 无法应用于给定类型`。

- [ ] **Step 3: `ConstructionPlan` 加字段**

3a. 记录头加第 6 个分量：

```java
public record ConstructionPlan(BlockPos start, int completed, Optional<String> workerId,
                               Optional<RecoveryDrop> recoveryDrop, Optional<String> lastWorkerId,
                               BuildMaterial material) {
```

3b. `CODEC` 在 `last_worker_id` 之后加一条：

```java
            Codec.STRING.xmap(BuildMaterial::valueOf, BuildMaterial::name)
                    .optionalFieldOf("material", BuildMaterial.OAK_PLANKS)
                    .forGetter(ConstructionPlan::material)
```

3c. 紧凑构造器里加一行：

```java
        Objects.requireNonNull(material, "material");
```

3d. 五个复制方法（`withWorker` / `finishStep` / `withDroppedItem` / `retargetDrop` / `clearRecovery`）各自把 `material` 作为最后一个实参传下去（**语义不动**，只传原值）。

- [ ] **Step 4: 让建造侧跟着编译**

4a. `SettlementSavedData`：

- `planTwoPlanks(BlockPos start)` 改名为**公开**的 `planStructure(BlockPos start, BuildMaterial material)`，其内部构造改为 `new ConstructionPlan(start, 0, Optional.empty(), Optional.empty(), Optional.empty(), material)`；
- `releaseWorker` 里那句内联构造补上 `current.material()`。

4b. **`planTwoPlanks` 的调用点有两类，都要跟着改**（原稿只说了命令那处，漏了检查里那 8 处——**先 `grep -rn "planTwoPlanks" src/` 自己数一遍**，不要只搜 `src/main`）：

- `ConstructionCommands`：把 `data.planTwoPlanks(start)` 改成 `data.planStructure(start, BuildMaterial.OAK_PLANKS)`（行为与今天一致；材料参数在 Task 5 接）。
- `SettlementSavedDataCheck`：8 处同样加上 `BuildMaterial.OAK_PLANKS`，**断言一行都不改**（它们钉的是计划的重叠/上限/取消/农田冲突，与材料无关）；并在该检查 `main` 开头补 `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();`——它的 codec 现在会碰到 `BuildMaterial` 的注册表字段。

- [ ] **Step 5: 跑检查并跑完整构建**

Run: `./gradlew constructionMaterialCheck --offline --no-daemon`

Expected: `ConstructionMaterialCheck passed`。

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，18 项全部 `*Check passed`。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionPlan.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementSavedData.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionCommands.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/SettlementSavedDataCheck.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/construction/ConstructionMaterialCheck.java
git commit -m "Let a project say what it is built from"
```

---

### Task 3: 工人与协调器按声明施工

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementSavedData.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionCoordinator.java`

**Interfaces:**
- Consumes: `ConstructionPlan.material()`（Task 2）
- Produces:
  - `SettlementSavedData.materialFor(String workerId) -> Optional<BuildMaterial>`
  - `GoblinCitizenEntity.carried(Item item) -> int`

- [ ] **Step 1: 加"这个工人要铺什么"**

在 `SettlementSavedData` 里 `releaseWorker` **之后**加：

```java
    /** What the project this worker holds is built from; empty when the worker holds no project. */
    public Optional<BuildMaterial> materialFor(String workerId) {
        return plans.stream()
                .filter(candidate -> candidate.workerId().equals(Optional.of(workerId)))
                .map(ConstructionPlan::material)
                .findFirst();
    }
```

（按工人的查找口径与同文件既有的 `finishStep` / `releaseWorker` 一致；需要 import `BuildMaterial`。）

- [ ] **Step 2: 工人按声明取料与放置**

2a. `fetchMaterial` 整个替换为：

```java
    private void fetchMaterial(ServerLevel level) {
        if (!carried.isEmpty()) {
            workStage = WorkStage.DELIVERING;
            return;
        }
        var material = SettlementSavedData.get(level).materialFor(getUUID().toString());
        if (material.isEmpty()) {
            waitReason = "no project for this worker";
            return;
        }
        BuildMaterial building = material.orElseThrow();
        if (!(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "supply container missing";
            return;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(building.item())) {
                ItemStack removed = container.removeItem(slot, 1);
                if (!removed.isEmpty()) {
                    carried = removed;
                    container.setChanged();
                    workStage = WorkStage.DELIVERING;
                    waitReason = "";
                }
                return;
            }
        }
        waitReason = building.name().toLowerCase(Locale.ROOT) + " missing";
    }
```

（若本文件还没有 `import java.util.Locale;` 与 `import dev.local.goblinsettlement.construction.BuildMaterial;` 就补上。）

2b. `placeMaterial` 整个替换为（它与 `fetchMaterial` 共用同一条"从计划重推"的路）：

```java
    private void placeMaterial(ServerLevel level) {
        var planned = SettlementSavedData.get(level).materialFor(getUUID().toString());
        if (planned.isEmpty()) {
            workStage = WorkStage.FETCHING;
            waitReason = "no project for this worker";
            return;
        }
        BuildMaterial building = planned.orElseThrow();
        if (!carried.is(building.item())) {
            workStage = WorkStage.FETCHING;
            waitReason = "material lost";
            return;
        }
        if (!level.getBlockState(buildPos).isAir()) {
            waitReason = "site occupied";
            return;
        }
        BlockPos below = buildPos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            waitReason = "no solid foundation";
            return;
        }
        if (WorldModificationPermission.check(level, settlementId, buildPos)
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "land permission changed";
            return;
        }
        if (level.setBlock(buildPos, building.block().defaultBlockState(), 3)) {
            carried = ItemStack.EMPTY;
            workStage = WorkStage.COMPLETE;
            waitReason = "";
            SettlementSavedData.get(level).finishStep(getUUID().toString(), buildPos);
        } else {
            waitReason = "placement failed";
        }
    }
```

2c. `carriedOakPlanks()` 替换为：

```java
    /** How many of this item the resident is carrying, for a project's shortage report. */
    public int carried(Item item) {
        return carried.is(item) ? carried.getCount() : 0;
    }
```

- [ ] **Step 3: 协调器按声明判进度与找料**

`ConstructionCoordinator.tickPlan` 里改两处：

- 进度判定 `&& level.getBlockState(site).is(Blocks.OAK_PLANKS)` → `&& level.getBlockState(site).is(plan.material().block())`；
- 取仓 `PublicWarehouseInventory.firstWithOakPlank(level, data)` → `PublicWarehouseInventory.firstHolding(level, data, plan.material().item())`。

并把该文件里因此不再需要的 `Blocks` import 去掉（**先编译再决定**）。

- [ ] **Step 4: 让显示侧跟着编译**

`ConstructionCommands.carriedBy(level, workerId)` 里那句 `goblin.carriedOakPlanks()` 改为按声明取物品：

```java
            var material = SettlementSavedData.get(level).materialFor(workerId);
            return entity instanceof GoblinCitizenEntity goblin && material.isPresent()
                    ? Optional.of(goblin.carried(material.orElseThrow().item())) : Optional.empty();
```

（Task 5 会把这一整块改成按材料分组显示；本步只保证语义正确且可编译。）

- [ ] **Step 5: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，18 项全部 `*Check passed`，无编译警告。

**本任务不可纯测**（要真实世界、真实容器与真实工人）：证据只有编译与代码审查。请在报告里如实写明。

- [ ] **Step 6: 核对无残留**

Run: `grep -rn "carriedOakPlanks\|firstWithOakPlank" src/`

Expected: **一条都没有**——施工路径的橡木专用入口已全部消失。

再 Run: `grep -rn "OAK_PLANKS" src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java src/main/java/dev/local/goblinsettlement/construction/ConstructionCoordinator.java`

Expected: `ConstructionCoordinator` **清空**；`GoblinCitizenEntity` 里**还会剩下几处，都是允许的**——运输路径"这格是不是已经铺好了"的四材料并列判定（`:855-858` 一列 `OAK_PLANKS/OAK_LOG/OAK_FENCE/TORCH`，属该线自己的用料枚举）、林业与工具配方的木料资源、以及回收路径（Task 4 的领地）。**判据是"施工路径的写死没了"，不是"整个实体里不许出现橡木"**。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementSavedData.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionCommands.java
git commit -m "Fetch, carry and place whatever the project declares"
```

---

### Task 4: 回收按声明的物品匹配

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/DroppedMaterialLookup.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionPlan.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementSavedData.java`（只改 `retargetRecoveryDrop` 的两个形参名）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`（**回收走的第二个调用点，见 Step 2b/2c**）
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/SettlementSavedDataCheck.java`（一处夹具措辞，见 Step 4）

**Interfaces:**
- Consumes: `ConstructionPlan.material()`（Task 2）
- Produces:
  - `DroppedMaterialLookup.find(ServerLevel level, String entityId, Item item, BlockPos lastPos) -> Optional<ItemEntity>`
  - `ConstructionPlan.RecoveryDrop.entityId()`（原 `itemId()`，**JSON 键仍是 `item_id`**）

- [ ] **Step 1: 改回收查找**

把 `DroppedMaterialLookup` 整个替换为：

```java
package dev.local.goblinsettlement.economy;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

/** Finds a worker's dropped material after vanilla item entities merge into a nearby stack. */
public final class DroppedMaterialLookup {
    private DroppedMaterialLookup() {
    }

    /**
     * The stack the worker lost: first the entity it remembers, then any nearby stack of the same item.
     * The item is the one the project declared -- matching a fixed item here would recover the wrong
     * material the moment a project is made of anything else.
     */
    public static Optional<ItemEntity> find(ServerLevel level, String entityId, Item item, BlockPos lastPos) {
        try {
            if (level.getEntity(UUID.fromString(entityId)) instanceof ItemEntity exact
                    && exact.getItem().is(item)) {
                return Optional.of(exact);
            }
        } catch (IllegalArgumentException ignored) {
            // Older or damaged records can still be located by the recorded drop position.
        }
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(lastPos).inflate(2.5),
                        found -> found.getItem().is(item) && found.getItem().getCount() > 1)
                .stream().min(Comparator.comparingDouble(found -> found.blockPosition().distSqr(lastPos)));
    }
}
```

- [ ] **Step 2: 两个调用点都传声明的物品**

原稿说"唯一调用点"，**是错的**——`grep -rn "DroppedMaterialLookup.find" src/` 会给出**两处**：协调器的回收分支，以及工人自己那条回收走的 `recoverDroppedItem`。两处都要改。

2a. `ConstructionCoordinator` 的回收分支里

```java
            var found = DroppedMaterialLookup.find(level, drop.itemId(), drop.pos());
```

改为

```java
            var found = DroppedMaterialLookup.find(level, drop.entityId(), plan.material().item(), drop.pos());
```

2b. `GoblinCitizenEntity.recoverDroppedItem` 里（`pickupItemId` 是掉落物的实体 id），把

```java
        var found = DroppedMaterialLookup.find(level, pickupItemId, buildPos);
```

替换为（材质取自 Task 3 建立的同一个出处；取不到就按既有的 `ABORTED` 口径结束，不硬撑）：

```java
        var planned = SettlementSavedData.get(level).materialFor(getUUID().toString());
        if (planned.isEmpty()) {
            workStage = WorkStage.ABORTED;
            waitReason = "no project for this worker";
            return;
        }
        BuildMaterial building = planned.orElseThrow();
        var found = DroppedMaterialLookup.find(level, pickupItemId, building.item(), buildPos);
```

2c. 同一方法里 **`:2016` 那处写死的橡木**（Task 3 的 brief 把它留给了本任务，原稿没接）：

```java
        if (!item.getItem().is(Items.OAK_PLANKS)) {
```

改为用同一个 `building.item()`：`if (!item.getItem().is(building.item())) {`——`ABORTED` 与 `"dropped item changed"` 的行为不变，只换它比的物品。

- [ ] **Step 3: `RecoveryDrop` 的分量改名（JSON 键不动）**

`ConstructionPlan.RecoveryDrop` 的 `itemId` 分量改名为 `entityId`（它与掉落物**实体 id** 对齐，不再冒充物品名），codec 的键**仍是 `"item_id"`**：

```java
    public record RecoveryDrop(String entityId, BlockPos pos) {
        public static final Codec<RecoveryDrop> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("item_id").forGetter(RecoveryDrop::entityId),
                BlockPos.CODEC.fieldOf("pos").forGetter(RecoveryDrop::pos)
        ).apply(instance, RecoveryDrop::new));
    }
```

并把 `ConstructionPlan` 内 `withDroppedItem` / `retargetDrop` 的形参名与 `new RecoveryDrop(...)` 的实参同步；`SettlementSavedData.recordRecoverableDrop(workerId, itemId, pos)` 的形参名也一并改为 `entityId`（**只改名**）。

- [ ] **Step 4: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，18 项全部 `*Check passed`。

Step 3 的改名若让既有断言失败，**只改夹具的写法，不改断言的意图**（`TransportSavedDataCheck` 与 `ConstructionMaterialCheck` 都不读 `RecoveryDrop` 的分量名，预期不受影响）。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/DroppedMaterialLookup.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionPlan.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementSavedData.java
git commit -m "Recover the material the project actually declares"
```

---

### Task 5: 命令的材料参数与按材料显示

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionCommands.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/PublicWarehouseInventory.java`（删掉两个已经没人用的橡木包装，见 Step 3）

**Interfaces:**
- Consumes: `BuildMaterial`（Task 1）、`SettlementSavedData.materialFor` / `planStructure`（Tasks 2–3）
- Produces: `plan` 子命令接受一个材料词；`project` 显示按材料分组

- [ ] **Step 1: `plan` 子命令接材料参数**

把 `plan` 子命令里

```java
                                    if (!data.planTwoPlanks(start)) {
                                        source.sendFailure(Component.literal(
                                                "Project overlaps active work or the eight-project limit is reached"));
                                        return 0;
                                    }
                                    source.sendSuccess(() -> Component.literal("Two-block project recorded at "
                                            + start.toShortString()), true);
```

改为（**命令树同时改成两个必需参数**：`Commands.literal("plan")` → `.then(Commands.argument("start", BlockPosArgument.blockPos()).then(Commands.argument("material", StringArgumentType.word()).executes(context -> { … })))`——即把既有那个 `executes` 从 `start` 参数下**挪到最内层** `material` 参数下，Brigadier 只允许最内层可执行）：

```java
                                    var material = materialNamed(StringArgumentType.getString(context, "material"));
                                    if (material.isEmpty()) {
                                        source.sendFailure(Component.literal(
                                                "Unknown material; try one of "
                                                        + Arrays.toString(BuildMaterial.values())));
                                        return 0;
                                    }
                                    if (!data.planStructure(start, material.orElseThrow())) {
                                        source.sendFailure(Component.literal(
                                                "Project overlaps active work or the eight-project limit is reached"));
                                        return 0;
                                    }
                                    source.sendSuccess(() -> Component.literal("Two-block "
                                            + material.orElseThrow().name().toLowerCase(Locale.ROOT)
                                            + " project recorded at " + start.toShortString()), true);
```

并加一个私有解析助手（放在 `carriedBy` 附近）：

```java
    /** The material a command word names, or empty when it names nothing this mod can build with. */
    private static Optional<BuildMaterial> materialNamed(String word) {
        for (BuildMaterial material : BuildMaterial.values()) {
            if (material.name().equalsIgnoreCase(word)) {
                return Optional.of(material);
            }
        }
        return Optional.empty();
    }
```

（需要 `import java.util.Arrays;`、`import java.util.Locale;`、`import java.util.Optional;` 与 `BuildMaterial`、`StringArgumentType`（若未 import）——按既有的 `traffic bridge` 命令的用法照抄。）

- [ ] **Step 2: `project` 显示按材料分组**

把 `showStatus` 里从 `int stock = PublicWarehouseInventory.countOakPlanks(...)` 到那两条 `sendSuccess` 的整段，替换为：

```java
        var active = plans.stream().filter(plan -> !plan.isComplete()).toList();
        boolean stockIncomplete = !PublicWarehouseInventory.snapshot(level, data).complete();
        for (BuildMaterial material : BuildMaterial.values()) {
            int remaining = active.stream().filter(plan -> plan.material() == material)
                    .mapToInt(plan -> ConstructionPlan.LENGTH - plan.completed()).sum();
            if (remaining == 0) {
                continue;
            }
            int stock = PublicWarehouseInventory.countOf(level, data, material.item());
            int carried = 0;
            boolean workersIncomplete = false;
            for (var plan : active) {
                if (plan.material() != material || plan.workerId().isEmpty()) {
                    continue;
                }
                var loadedCarried = carriedBy(level, plan.workerId().orElseThrow());
                if (loadedCarried.isPresent()) {
                    carried += loadedCarried.orElseThrow();
                } else {
                    workersIncomplete = true;
                }
            }
            int shortage = Math.max(0, remaining - stock - carried);
            String stockText = stockIncomplete
                    ? ">=" + stock + " (some warehouses unavailable)" : String.valueOf(stock);
            String carriedText = workersIncomplete
                    ? ">=" + carried + " (some workers unloaded)" : String.valueOf(carried);
            String shortageText = stockIncomplete || workersIncomplete
                    ? "unknown (at most " + shortage + ")" : String.valueOf(shortage);
            String materialName = material.name().toLowerCase(Locale.ROOT);
            source.sendSuccess(() -> Component.literal(materialName + ": still needed=" + shortageText
                    + ", public chest stock=" + stockText + ", worker carrying=" + carriedText), false);
        }
        if (active.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No unfinished construction project"), false);
        }
```

每行计划行（原有的 `"Project at " + …`）补上它声明的材料：

```java
            source.sendSuccess(() -> Component.literal("Project at " + plan.start().toShortString()
                    + " " + plan.completed() + "/" + ConstructionPlan.LENGTH
                    + " " + plan.material().name().toLowerCase(Locale.ROOT)
                    + ", dropped material=" + (plan.recoveryDrop().isPresent() ? "tracked" : "none")
                    + ", worker=" + plan.workerId().orElse("none")
                    + ", last worker=" + plan.lastWorkerId().orElse("none")), false);
```

- [ ] **Step 3: 两个已经没人用的橡木包装要删掉**

Step 2 之后，`PublicWarehouseInventory` 的两个橡木专用包装就没有调用者了（`firstWithOakPlank` 在 Task 3 就空了，`countOakPlanks` 的最后一个调用点正是刚被你改掉的显示行）。项目不要向后兼容的壳子：

Run: `grep -rn "firstWithOakPlank\|countOakPlanks" src/`

Expected: **除两者的定义之外没有别的行**（若还有调用者，**不要删**，回报而不是就地改）。

确认没有调用者后，把 `PublicWarehouseInventory` 里 `firstWithOakPlank` 与 `countOakPlanks` 两个方法整个删掉（它们各自的通用版本 `firstHolding` 与 `countOf` 保留，且仍是唯一出处）。

- [ ] **Step 4: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，18 项全部 `*Check passed`，无编译警告。

- [ ] **Step 5: 核对显示层只调共用方法**

Run: `grep -rn "OakPlanks\|countOakPlanks\|firstWithOakPlank" src/main/java/dev/local/goblinsettlement/construction/`

Expected: **一条都没有**。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionCommands.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/PublicWarehouseInventory.java
git commit -m "Let the player pick what a project is built from, and report per material"
```

---

### Task 6: 文档与收尾

**Files:**
- Modify: `goblin-settlement-plan/CONSTRUCTION_MATERIAL_DESIGN.md`（加 §8 落地结果）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Tasks 1–5 的改动与构建结果（**含 `build/libs/goblin-settlement-0.1.0.jar` 的实际字节数、构建秒数、每个提交的哈希**）
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：先单独提交它们并署名，然后再做本任务的文档改动。

- [ ] **Step 2: 设计文档记录落地结果**

在 `CONSTRUCTION_MATERIAL_DESIGN.md` 末尾追加：

```markdown
## 8. 落地结果（实现后补记）

- **材料的唯一出处**：新增 `construction/BuildMaterial`（四个常量逐字保持 `OAK_PLANKS / OAK_LOG / OAK_FENCE / TORCH`，带 `block()` / `item()` 两张表）；`TransportPlan` 删掉自己的 `Material` 枚举改用共享的那个，`TransportCoordinator` 的私有 `itemFor` / `blockFor` 删除。**JSON 取值是常量名，因此旧档一字不改仍能读**，既有 `transportSavedDataCheck` 的往返断言继续钉住它。
- **计划声明建筑件**：`ConstructionPlan` 增 `material` 分量（`optionalFieldOf("material", OAK_PLANKS)`）⇒ 本轮之前的计划仍铺橡木木板，行为不变；`planTwoPlanks` 改名 `planStructure(start, material)`。
- **工人路径不新增持久字段**：新增 `SettlementSavedData.materialFor(workerId)` 作为"这个工人要铺什么"的唯一出处，`fetchMaterial` / `placeMaterial` 每次从计划重推；`carriedOakPlanks()` 改为 `carried(Item)`；等待理由改成枚举名小写（`oak_planks missing`）。
- **协调器**：进度判定改 `plan.material().block()`，取仓改 `firstHolding(..., plan.material().item())`。
- **回收缺陷已修**：`DroppedMaterialLookup.find` 原先两个分支都写死橡木木板，泛化后**会捡错材料**；现在按项目声明的物品匹配，`RecoveryDrop.itemId` 这个错名也改成 `entityId`（**JSON 键仍是 `item_id`**）。
- **命令**：`plan` 增加材料词参数（大小写不敏感，不认识就明确失败并列出可选值）；`project` 的短缺报告改成**按材料各一行**，每行计划行带上它的材料。
- **仍未做**：形状仍是 2 格直线（`LENGTH = 2`）；材料种类仍是四种；等待理由的旧字面量若在别处被引用需另行确认。
```

- [ ] **Step 3: 追加 UpdateLog**

在 `UpdateLog.md` **末尾**追加（`<开始>`/`<结束>`/`<时间>`/`<字节数>`/`<秒数>`/`<哈希>` 换成真实值，**逐条写真实时间，不要留占位**——第四十八、五十、五十五轮都漏填过）：

```markdown
## [<开始> – <结束>] 第五十七轮：施工路径的材料泛化

- [<时间>] 按 CONSTRUCTION_MATERIAL_DESIGN.md 与 CONSTRUCTION_MATERIAL_PLAN.md 执行：把最后一条还写死橡木木板的施工路径（命令驱动的 `ConstructionPlan` 线）改成按计划声明的建筑件施工。
- [<时间>] **材料的唯一出处**：新增 `construction/BuildMaterial`（`OAK_PLANKS / OAK_LOG / OAK_FENCE / TORCH`，带 `block()` / `item()`）；`TransportPlan` 删掉自己的 `Material` 枚举改用共享的这个，`TransportCoordinator` 的私有 `itemFor` / `blockFor` 删除。**不是给施工线另写一个枚举**——那会让"OAK_PLANKS 是哪个方块"变成第二份事实，正是第四十二/四十七/五十五轮各自收敛掉的那类重复。**存档取值是常量名，旧档一字不改仍能读**，由既有 `transportSavedDataCheck` 的往返断言与新增的"四名字面钉死"断言双重把关。
- [<时间>] `ConstructionPlan` 增 `material`（`optionalFieldOf` 默认橡木木板）⇒ 本轮前排队与第一轮的旧计划行为一字不变；`planTwoPlanks` 改名 `planStructure(start, material)`。
- [<时间>] 工人路径**不新增持久字段**：新增 `SettlementSavedData.materialFor(workerId)` 作为唯一出处，`fetchMaterial` / `placeMaterial` 每次从计划重推；`carriedOakPlanks()` → `carried(Item)`；等待理由改成枚举名小写（`oak_planks missing` / `oak_log missing` …），不再写死一种材料的说法。
- [<时间>] 协调器进度判定改 `plan.material().block()`、取仓改 `firstHolding(..., plan.material().item())`。
- [<时间>] **顺带修掉一个真缺陷**：`DroppedMaterialLookup.find` 的第二个参数其实是掉落物的**实体 id**，而物品类型两个分支都写死 `Items.OAK_PLANKS`——一旦能用别的材料施工，材料丢了以后的回收会去找橡木木板。现在按计划声明的物品匹配；`RecoveryDrop.itemId` 这个错名改成 `entityId`（JSON 键仍是 `item_id`，旧档可读）。
- [<时间>] 命令：`plan` 增加材料词参数（大小写不敏感，不认识就明确失败并列出可选值）；`project` 的短缺报告改成**按材料各一行**（还缺多少只对某一种材料有意义），每行计划行带上它的材料。
- [<时间>] 验证：完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**18 项**独立检查全部 *Check passed（新增第 18 项 `constructionMaterialCheck`：每个常量都有方块与物品、四个常量名钉死、`ConstructionPlan` codec 往返、缺 `material` 的旧档落到橡木木板）。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>，耗时 <秒数>。提交 <哈希>。
- [<时间>] 未完成：不做玩法验收；**工人取料/放置、协调器找料、回收匹配、命令的两种显示全部不可纯测**（只有编译与代码审查）。形状仍是 2 格直线、材料种类仍是四种——要建别的形状或加石头类材质是另一轮（后者还要保证经济能产出它）。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「接续须知」的**分支指针**改成本轮收尾的提交；**下一步落点**改写：住宅剩余的"通用施工路径的材料泛化"**已完成**，落点在 `CONSTRUCTION_MATERIAL_DESIGN.md` §8；住宅剩下的只有改建安全、公共设施、实体公告牌方块、住户分配、历史保留。
- 「阶段定位」阶段 6（或阶段 4/5 里提到建筑与库存的那句）补上"第五十七轮把通用施工路径的材料也泛化了"。
- 「本轮接入的内容」新增一节「第五十七轮：施工路径的材料泛化」。
- 「住宅剩余」里那句"**明确未做：通用施工路径的材料泛化**——`GoblinCitizenEntity.fetchMaterial`/`placeMaterial`/`carriedOakPlanks` 与道路交付仍是橡木木板专用"整段改写为**已完成**并写明落点；其余项（改建安全、公共设施、实体公告牌方块、住户分配、历史保留）不动。
- 「本轮验证进展」替换为第五十七轮：完整构建、18 项检查、产物字节数与耗时、提交；写明本轮**新增**第 18 项检查；把"独立检查覆盖不到"的清单换成本轮的（工人按声明取料/放置、协调器按声明找料、回收按声明匹配、命令显示、以及"旧档默认橡木"在真实存档里的表现）。
- 别处提到"17 项"的地方（若有）一并改为 18 项。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the construction material round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。若网络不通，本机需要代理：`git -c http.proxy=http://127.0.0.1:7890 push origin main`。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 材料的唯一出处（枚举提升、两张表、存档取值不变）→ Task 1。
- 设计 §3 计划声明建筑件（codec 默认、旧档兼容、`planStructure`）→ Task 2。
- 设计 §4 三条线改读声明（工人、协调器、回收）→ Task 3（工人 + 协调器）与 Task 4（回收 + 错名）。
- 设计 §5 命令与按材料显示 → Task 5。
- 设计 §6 第 18 项检查（每常量有方块物品、名字钉死、计划 codec 往返、旧档默认）→ Task 1 Step 1（前两条）+ Task 2 Step 1（后两条）。
- 设计 §7 风险 → 旧档默认（Task 2 的断言 + Task 6 文档）、形状与材料种类不变（Global Constraints + Task 6）、等待理由字面量（Task 3 Step 2a 换成枚举名 + Task 6 文档）、枚举改名风险（Task 1 的钉死断言 + Task 6）。

**2. 占位符扫描**

- 无 TBD/TODO。两个新文件、`TransportPlan`/`TransportCoordinator`/`ConstructionPlan`/`SettlementSavedData`/`GoblinCitizenEntity`/`ConstructionCoordinator`/`DroppedMaterialLookup`/`ConstructionCommands` 的改动都给了可粘贴全文或成对替换。
- Task 3 Step 3 与 Step 4 的"先编译再决定 import"是**有判据的条件动作**（`Blocks` 是否仍被该文件使用），不是含糊指令。
- Task 5 Step 1 明确要求"在命令树里插入 `material` 参数"，并给出参数解析与失败信息全文；命令树的接线方式照抄既有的 `traffic bridge`。
- Task 6 的 `<开始>`/`<结束>`/`<时间>`/`<字节数>`/`<秒数>`/`<哈希>` 是模板占位，正文已要求逐条替换为真实值。

**3. 类型一致性**

- `BuildMaterial` 的 `block()` / `item()` 在 Task 1 定义，在 Task 1（交通线）、Task 3（工人/协调器）、Task 4（回收）、Task 5（命令与显示）使用，返回类型一致。
- `ConstructionPlan` 由 5 个分量变 6 个：**全部 7 个构造点**都已列出——record 自己的 `withWorker` / `finishStep` / `withDroppedItem` / `retargetDrop` / `clearRecovery`（Task 2 Step 3d），`SettlementSavedData.planStructure`（原 `planTwoPlanks`）与 `releaseWorker`（Task 2 Step 4a）。**这两个外部构造点必须跟字段一起改**，否则 Task 2 编不过。
- `planTwoPlanks` → `planStructure(BlockPos, BuildMaterial)` 的调用点有**两类**：`ConstructionCommands`（1 处，Task 2 Step 4b 做最小改动，Task 5 再补参数解析）与 `SettlementSavedDataCheck`（**8 处**，Task 2 Step 4b 一并改）——**每个任务结束时树都必须可编译**。原稿只搜了 `src/main` 因而漏了检查里那 8 处；今后找调用点一律 `grep -rn … src/`。
- `DroppedMaterialLookup.find` 由 3 参变 4 参（多一个 `Item`），唯一调用点在 Task 4 Step 2 同步；`RecoveryDrop` 的分量改名后，`ConstructionPlan` 内部两处 `new RecoveryDrop(...)` 与 `SettlementSavedData.recordRecoverableDrop` 的形参名同步，**JSON 键不变**。
- `carriedOakPlanks()` → `carried(Item)` 的调用点（`ConstructionCommands.carriedBy`）在 Task 3 Step 4 同步。
- `materialFor(String) -> Optional<BuildMaterial>` 在 Task 3 定义，在 Task 3（entity + `carriedBy`）与 Task 5（显示分组）使用，返回类型一致。
