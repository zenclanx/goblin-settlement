# 派工服务收口 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 8 个协调器里逐字相同的挑人代码收成一个 `WorkerDispatch` 服务，让"怎么挑工人"只有一个落点，为下一轮的职业名额留出唯一接口。

**Architecture:** 新增 `colony/WorkerDispatch`，内部就是那 8 处逐字相同的三段（`new AABB(anchor).inflate(16.0)` → 按谓词过滤 → `min(matchRank(kind) then blockPosition().distSqr(anchor))`）。谓词由调用方显式给定，因为它在本就存在的两派写法之间二选一；把它设成默认会让 3 处行为变化。两个异形保留原代码并加注释。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [PROFESSION_DISPATCH_DESIGN.md](PROFESSION_DISPATCH_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **本轮是零行为变化的纯重构**：改造前后的**盒子构造、谓词、比较器三段必须逐字对应**。任何一处"顺手改进"都是违规——10 处挑人行为从未在游戏内验证过，改坏了没有手段发现。
- **本轮不新增任何独立检查**，因为 `nearest` 需要 `ServerLevel`，纯函数层测不到。这是本轮的性质，日志里必须写明，不得记成"已验证"。
- **两个异形不动**：`ConstructionCoordinator` 的掉落物回收（约第 93 行）与施工派工（约第 123 行）。
- **不改谓词不一致**：Housing/Transport/Farming 不检查站位权限，其余五处检查；本轮保持原样（§4）。
- `HousingCoordinator` 的锚点是 `warehouse.orElseThrow()`，位于 `if (warehouse.isPresent())` 块**内**；改造后必须仍在块内，不得提前求值。
- Java 不因未使用的导入报错，所以**每个文件的导入必须逐一核对后手动清理**，否则留下死导入。
- 不做游戏内验证（按用户约定）。
- `goblin-settlement-plan/` 下**可能有并行 agent 的未提交改动**：**在文档任务开始时**先跑 `git status`，发现不是自己改的就**先单独提交它们并署名**，再追加自己的。`UpdateLog.md` **只许在末尾追加**。

---

### Task 1: 新增 `WorkerDispatch` 并改造 8 处调用点

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/WorkerDispatch.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/farming/FarmingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/forestry/ForestryCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/mining/MiningCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/smelting/SmeltingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/food/FoodCraftingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/tools/ToolCraftingCoordinator.java`

**Interfaces:**
- Consumes: `ProfessionRules.matchRank(WorkKind, Profession)`、`GoblinCitizenEntity.isAvailableForConstruction()`、`WorldModificationPermission.check(ServerLevel, String, BlockPos)`、`WorkKind`
- Produces:
  - `WorkerDispatch.SEARCH_RADIUS`（`double`，= 16.0）
  - `WorkerDispatch.permitted(ServerLevel level, String settlementId) -> Predicate<GoblinCitizenEntity>`
  - `WorkerDispatch.nearest(ServerLevel level, WorkKind kind, BlockPos anchor, Predicate<GoblinCitizenEntity> eligible) -> Optional<GoblinCitizenEntity>`

- [ ] **Step 1: 创建服务**

```java
package dev.local.goblinsettlement.colony;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** The one place that decides which resident gets a piece of work. */
public final class WorkerDispatch {
    /** The search radius every caller already used. */
    public static final double SEARCH_RADIUS = 16.0;

    private WorkerDispatch() {
    }

    /**
     * A resident is eligible to be hired when they are free and allowed to change blocks where they
     * stand. Five of the eight callers check the second half and three do not, so it stays a
     * predicate the caller chooses -- making it a hidden default would change those three.
     */
    public static Predicate<GoblinCitizenEntity> permitted(ServerLevel level, String settlementId) {
        return goblin -> goblin.isAvailableForConstruction()
                && WorldModificationPermission.check(level, settlementId, goblin.blockPosition())
                        == WorldModificationPermission.Decision.ALLOWED;
    }

    /** The shared hiring rule: profession fit first, then distance to the work. */
    public static Optional<GoblinCitizenEntity> nearest(ServerLevel level, WorkKind kind, BlockPos anchor,
                                                        Predicate<GoblinCitizenEntity> eligible) {
        return level.getEntitiesOfClass(GoblinCitizenEntity.class,
                        new AABB(anchor).inflate(SEARCH_RADIUS), eligible)
                .stream().min(Comparator
                        .comparingInt((GoblinCitizenEntity goblin) ->
                                ProfessionRules.matchRank(kind, goblin.profession()))
                        .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(anchor)));
    }
}
```

- [ ] **Step 2: 改造 `TransportCoordinator`**

把

```java
            var worker = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                            new AABB(warehouse).inflate(16.0), GoblinCitizenEntity::isAvailableForConstruction)
                    .stream().min(Comparator
                            .comparingInt((GoblinCitizenEntity goblin) ->
                                    ProfessionRules.matchRank(WorkKind.TRANSPORT, goblin.profession()))
                            .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(warehouse)));
```

换成

```java
            var worker = WorkerDispatch.nearest(level, WorkKind.TRANSPORT, warehouse,
                    GoblinCitizenEntity::isAvailableForConstruction);
```

- [ ] **Step 3: 改造 `FarmingCoordinator`**

把

```java
            var resident = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                            new AABB(crop).inflate(16.0), GoblinCitizenEntity::isAvailableForConstruction)
                    .stream().min(Comparator
                            .comparingInt((GoblinCitizenEntity goblin) ->
                                    ProfessionRules.matchRank(WorkKind.FARMING, goblin.profession()))
                            .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(crop)));
```

换成

```java
            var resident = WorkerDispatch.nearest(level, WorkKind.FARMING, crop,
                    GoblinCitizenEntity::isAvailableForConstruction);
```

- [ ] **Step 4: 改造 `HousingCoordinator`**

把

```java
            var worker = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                            new AABB(warehouse.orElseThrow()).inflate(16.0),
                            GoblinCitizenEntity::isAvailableForConstruction)
                    .stream().min(Comparator
                            .comparingInt((GoblinCitizenEntity goblin) ->
                                    ProfessionRules.matchRank(WorkKind.HOUSING, goblin.profession()))
                            .thenComparingDouble(goblin ->
                                    goblin.blockPosition().distSqr(warehouse.orElseThrow())));
```

换成（**保持在 `if (warehouse.isPresent())` 块内**，`orElseThrow()` 不得提到块外）

```java
            var worker = WorkerDispatch.nearest(level, WorkKind.HOUSING, warehouse.orElseThrow(),
                    GoblinCitizenEntity::isAvailableForConstruction);
```

- [ ] **Step 5: 改造 `ForestryCoordinator`**

把

```java
        return level.getEntitiesOfClass(GoblinCitizenEntity.class,
                        new AABB(target).inflate(16.0),
                        goblin -> goblin.isAvailableForConstruction()
                                && WorldModificationPermission.check(level, id, goblin.blockPosition())
                                == WorldModificationPermission.Decision.ALLOWED)
                .stream().min(Comparator
                        .comparingInt((GoblinCitizenEntity goblin) ->
                                ProfessionRules.matchRank(WorkKind.FORESTRY, goblin.profession()))
                        .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(target)));
```

换成

```java
        return WorkerDispatch.nearest(level, WorkKind.FORESTRY, target,
                WorkerDispatch.permitted(level, id));
```

- [ ] **Step 6: 改造 `MiningCoordinator`**

把

```java
            var worker = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                            new AABB(warehouse).inflate(16.0),
                            goblin -> goblin.isAvailableForConstruction()
                                    && WorldModificationPermission.check(level, settlementId,
                                            goblin.blockPosition()) == WorldModificationPermission.Decision.ALLOWED)
                    .stream().min(Comparator
                            .comparingInt((GoblinCitizenEntity goblin) ->
                                    ProfessionRules.matchRank(WorkKind.MINING, goblin.profession()))
                            .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(warehouse)));
```

换成

```java
            var worker = WorkerDispatch.nearest(level, WorkKind.MINING, warehouse,
                    WorkerDispatch.permitted(level, settlementId));
```

- [ ] **Step 7: 改造 `SmeltingCoordinator`**

把

```java
        var worker = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                        new AABB(warehouse).inflate(16.0),
                        goblin -> goblin.isAvailableForConstruction()
                                && WorldModificationPermission.check(level, settlementId,
                                        goblin.blockPosition()) == WorldModificationPermission.Decision.ALLOWED)
                .stream().min(Comparator
                        .comparingInt((GoblinCitizenEntity goblin) ->
                                ProfessionRules.matchRank(WorkKind.SMELTING, goblin.profession()))
                        .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(warehouse)));
```

换成

```java
        var worker = WorkerDispatch.nearest(level, WorkKind.SMELTING, warehouse,
                WorkerDispatch.permitted(level, settlementId));
```

（紧随其后的 `return worker.isPresent() && worker.orElseThrow().assignSmelting(...)` 保持不变。）

- [ ] **Step 8: 改造 `FoodCraftingCoordinator`**

把

```java
            var worker = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                            new AABB(warehouse).inflate(16.0),
                            goblin -> goblin.isAvailableForConstruction()
                                    && WorldModificationPermission.check(level, settlementId,
                                            goblin.blockPosition()) == WorldModificationPermission.Decision.ALLOWED)
                    .stream().min(Comparator
                            .comparingInt((GoblinCitizenEntity goblin) ->
                                    ProfessionRules.matchRank(WorkKind.FOOD_CRAFTING, goblin.profession()))
                            .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(warehouse)));
```

换成

```java
            var worker = WorkerDispatch.nearest(level, WorkKind.FOOD_CRAFTING, warehouse,
                    WorkerDispatch.permitted(level, settlementId));
```

- [ ] **Step 9: 改造 `ToolCraftingCoordinator`**

把

```java
                var worker = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                                new AABB(warehouse).inflate(16.0),
                                goblin -> goblin.isAvailableForConstruction()
                                        && WorldModificationPermission.check(level, settlementId,
                                                goblin.blockPosition()) == WorldModificationPermission.Decision.ALLOWED)
                        .stream().min(Comparator
                                .comparingInt((GoblinCitizenEntity goblin) ->
                                        ProfessionRules.matchRank(WorkKind.TOOL_CRAFTING, goblin.profession()))
                                .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(warehouse)));
```

换成

```java
                var worker = WorkerDispatch.nearest(level, WorkKind.TOOL_CRAFTING, warehouse,
                        WorkerDispatch.permitted(level, settlementId));
```

- [ ] **Step 10: 给两个异形加注释**

在 `ConstructionCoordinator` 掉落物回收那段挑人代码之前加：

```java
            // Deliberately not WorkerDispatch: this hire is measured from the dropped stack's own
            // coordinates, not from a block, so the distance metric differs from every other site.
```

在施工派工那段挑人代码之前加：

```java
            // Deliberately not WorkerDispatch: this one ranks the plan's previous worker ahead of
            // profession fit, a rule no other hire needs.
```

- [ ] **Step 11: 逐文件清理死导入**

对 §5 表里的 8 个文件，逐个运行（把 `<FILE>` 换成本地路径）：

```bash
grep -n "Comparator\|AABB\|ProfessionRules\|WorldModificationPermission\|getEntitiesOfClass" <FILE>
```

**判据**：某个符号若只剩 import 行本身、正文里再无使用，就删掉那一行 import。逐个文件做，不要一把梭。

典型结果（**要按实际 grep 结果确认，不要照抄**）：用 `permitted` 的文件仍需要 `WorldModificationPermission`？**不需要**——权限检查已经搬进 `WorkerDispatch`，正文里不再出现；用 `GoblinCitizenEntity::isAvailableForConstruction` 的文件仍需要 `GoblinCitizenEntity`。

- [ ] **Step 12: 编译并跑全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，12 项检查全部 `*Check passed`，无编译警告。

若失败，最可能是导入残留（找不到符号）或漏改的调用点（参数不匹配）。编译错误会给出确切文件与行号。

- [ ] **Step 13: 核对结果是零行为变化**

Run: `grep -rn "matchRank" src/main/java/`

Expected: 只剩三处——`ProfessionRules` 自身的定义与 `workIntervalTicks` 内部的使用，以及 `WorkerDispatch.nearest` 内部那一次。**其余 10 处调用点都不应再直接出现 `matchRank`**（8 处已收进服务，2 处异形本来就不含 `matchRank`……**但施工派工那处含**：`ConstructionCoordinator` 第 123 行附近仍有 `matchRank(WorkKind.CONSTRUCTION, ...)`，因为它是异形。所以预期是**两处正文调用**：`WorkerDispatch` 内一次、`ConstructionCoordinator` 施工派工一次）。

Run: `grep -rn "new AABB(" src/main/java/dev/local/goblinsettlement/ | grep -i "inflate(16"`

Expected: 只剩 `WorkerDispatch.nearest` 一次，以及 `ConstructionCoordinator` 掉落物回收那处的 `new AABB(item.position(), item.position()).inflate(16.0)`。

- [ ] **Step 14: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/WorkerDispatch.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/farming/FarmingCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/forestry/ForestryCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/mining/MiningCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/smelting/SmeltingCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/food/FoodCraftingCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/tools/ToolCraftingCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionCoordinator.java
git commit -m "Hire workers through one dispatch rule"
```

---

### Task 2: 完整构建收尾与项目文档

**Files:**
- Modify: `goblin-settlement-plan/PROFESSION_DESIGN.md`（§12 勾掉派工服务收口那条）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1 的改动与构建结果
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：**先单独提交它们**，提交说明里写明来源，**然后再**做本任务的文档改动。

- [ ] **Step 2: 修正 CURRENT_STATUS 与 PROFESSION_DESIGN 的矛盾**

`PROFESSION_DESIGN.md` §12 第一条是「职业熟练度与效率成长（**用户明确排除**）」，而 `CURRENT_STATUS.md` 把它列进了「职业系统剩余」。**在 CURRENT_STATUS 里删掉"职业熟练度"这一项**，并注明它是用户明确排除、不是待做——避免后来者照单执行一个被排除的需求。

同时把 §12 的「把 9 个协调器重复的挑人代码重构成统一派工服务」那条标记为已完成并指向 `PROFESSION_DISPATCH_DESIGN.md`。

- [ ] **Step 3: 追加 UpdateLog**

在末尾追加（时间换成实际操作时刻）：

```markdown
## [<开始> – <结束>] 第四十七轮：派工服务收口

- [<时间>] 按 PROFESSION_DISPATCH_DESIGN.md 与 PROFESSION_DISPATCH_PLAN.md 执行，落实 PROFESSION_DESIGN §12 的「统一派工服务」。
- [<时间>] 核对现状：10 处挑人代码里 **8 处逐字同形**（`new AABB(anchor).inflate(16.0)` + `matchRank` 再按 `blockPosition().distSqr(anchor)`），只有 WorkKind、锚点、谓词三者不同。两个异形保留原代码并加注释：掉落物回收的锚是掉落实体坐标、度量是到实体连续坐标；施工派工在 matchRank 前多一个"优先续用上次工人"的真实功能键。
- [<时间>] 新增 `colony/WorkerDispatch`：`permitted(level, settlementId)` 谓词 + `nearest(level, kind, anchor, eligible)`。谓词由调用方显式给，因为 8 处里 5 处检查站位权限、3 处不检查——设成默认会让那 3 处行为变化，设成显式传入则保持行为不变、同时把那处既有不一致从 lambda 深处提到调用点上。
- [<时间>] 8 处改为调用服务；逐文件清理因权限检查搬走而失去用处的导入。核对：正文里 `matchRank` 只剩 WorkerDispatch 内一次与施工派工（异形）一次；`inflate(16` 只剩 WorkerDispatch 内一次与掉落物回收（异形）一次。
- [<时间>] 验证：./gradlew build --offline --no-daemon BUILD SUCCESSFUL，12 项独立检查全部 *Check passed。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>。
- [<时间>] **本轮没有任何新增检查**：`WorkerDispatch.nearest` 需要 ServerLevel 与真实实体，项目那套纯计算检查覆盖不到；而 10 处挑人行为从未在游戏内验证过。所以"零行为变化"是靠逐处对照三段（盒子/谓词/比较器）确认的，**不是**自动化证据，不得记成已验证。
- [<时间>] 未完成：职业名额约束（下一轮的落点就是这个服务）、哨卫的巡逻与警报、儿童外观、真实手持工具、已定职居民的强制转岗均未做；Housing/Transport/Farming 三处缺站位权限检查的不一致**本轮刻意未修**，重构后更显眼但仍是既有行为。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「职业系统剩余」那处：删掉「职业熟练度」（用户明确排除），把「派工服务收口」记为已完成，保留其余项。
- 「本轮接入的内容」新增一节「第四十七轮：派工服务收口」。
- 「本轮验证进展」替换为本轮构建结果，并写明**本轮无新增检查**。
- 「阶段定位」阶段 4：把职业系统的描述补上"派工已收口、名额与哨卫工作仍缺"。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the dispatch consolidation round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。

（`git add goblin-settlement-plan/` 是可接受的：Step 1 已把并行改动先行单独提交，此处剩下的应只有本轮的文档。）

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 服务接口 → Task 1 Step 1。
- 设计 §3 两个异形保留 → Task 1 Step 10（注释）+ Step 13（核对它们仍在）。
- 设计 §4 不改谓词不一致 → Task 1 Step 2/3/4 用裸谓词、Step 5–9 用 `permitted`；Global Constraints 明确禁止"顺手改进"。
- 设计 §5 改造清单 → Task 1 Step 2–9，逐行对应表格里的 WorkKind/锚点/谓词。
- 设计 §6 无新增检查 → 无检查任务；Task 2 Step 3 的日志条目里显式写明。
- 设计 §7 风险（死导入、`orElseThrow()` 位置、包循环）→ Step 11（逐文件清理）、Step 4 的括注、以及 §7 已记录的包循环（本轮不处理）。
- 用户报告的文档矛盾（熟练度）→ Task 2 Step 2。

**2. 占位符扫描**

- 无 TBD/TODO/待填。8 处改造都给了改造前后的完整代码。
- Task 1 Step 11 明说"典型结果要按实际 grep 确认，不要照抄"——这是**条件性判断**，给了判据与命令，不是留空。
- Task 2 Step 3 的 jar 字节数用 `<字节数>` 占位，因为构建结果在实现时才知道；这是**测量值**而非设计留白，与"TBD"不同。

**3. 类型一致性**

- `WorkerDispatch.nearest` 的签名 `(ServerLevel, WorkKind, BlockPos, Predicate<GoblinCitizenEntity>)` 在 Step 1 定义，Step 2–9 的 8 处调用参数顺序一致；锚点一律是 `BlockPos`（`HousingCoordinator` 传 `warehouse.orElseThrow()`，类型为 `BlockPos`）。
- `permitted` 返回 `Predicate<GoblinCitizenEntity>`，与 `nearest` 的第 4 参类型一致；8 处里裸谓词用的是方法引用 `GoblinCitizenEntity::isAvailableForConstruction`，与既有写法相同。
- 服务内部保留的就是原来的三段，因此 `Optional<GoblinCitizenEntity>` 的返回类型与 8 处既有接收变量（`var worker` / `var resident`）完全兼容，调用点的后续代码（`warehouse.isPresent()`、`worker.orElseThrow()` 等）不需要改动。
- `SEARCH_RADIUS` 声明为 `public static final double`，与既有代码里字面量 `16.0` 的语义一致（原来各处硬编码的就是这个值）。
- 施工派工那处**保留 `matchRank`**，所以 `ConstructionCoordinator` 仍需 `ProfessionRules` 导入；Step 11 的判据里要把它算作"仍在使用"。
