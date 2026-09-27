# 住宅决策规则细化（缺床时饱和房子不动）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `HousingRules.decide` 在聚落缺床时，只对"还能多放一张床"的房子扩容量；放不下的房子保持不动，不再白花一整套容量阶段的木板（约 81 块）。

**Architecture:** 纯规则 `decide` 增加一个入参 `usedBedsNear`（该房锚点 `BIND_RADIUS` 半径内已用的床位数），并把"还能不能扩容"交给既有的 `canGainCapacity` 判定——它已经是扩地闸（`hasCapacityGain`）与放床绑定（`BedProvisioningCoordinator`）共用的判据，让 `decide` 也走它，"还能不能扩容"在三条路径上只有一个答案。`HousingCoordinator.tick` 已有每 tick 缓存的 `BedCensus.heads`，只需把"床轴列表"的构造抽成私有助手供 `tick` 与 `hasCapacityGain` 共用。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [HOUSING_DESIGN.md](HOUSING_DESIGN.md) §4 与 §4.1（本轮修订）、§6/§6a/§6.3（逃生路径，本轮不动）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。禁止引入其他 Minecraft 版本的 API 或示例。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **不做游戏内验证**：按用户约定，初版代码全部完成后才统一测试。本轮只做编译 + 独立检查。
- **纯规则不得依赖 Minecraft 类型**——`HousingRules` 只吃 `int`、自身定义的 record/enum 与 `java.util` 集合。`usedBedsNear` 是 `int`，合规。
- **`capacityTarget < MAX_CAPACITY_TARGET` 不再单独判**：已由 `canGainCapacity` 涵盖，两处判据会分叉。
- 检查沿用项目既有写法：无 JUnit，`main` + `require`，成功打印 `XxxCheck passed`，注册在 `build.gradle` 的 `check` 聚合里。`housingRulesCheck` 任务已存在，本轮不改 `build.gradle`。
- 代码注释用英文（与现有源码一致）。只在违反直觉处写注释。
- 不修改 `ai-chat-mod`，不动常用存档。
- **`goblin-settlement-plan/` 下可能有另一个 agent 未提交的改动**：提交时只暂存你自己改的文件，不要用 `git add -A` / `git add .`。
- `UpdateLog.md` **只许在末尾追加**，不要修改、删除或重排已有内容。

---

### Task 1: `decide` 增参改规则并接线调用方

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingRules.java:31-41`（`decide`）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`（`tick` 第 44–52 行；`hasCapacityGain` 第 193–211 行；文件末尾加两个私有助手；imports 加 `java.util.List`）
- Test: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/HousingRulesCheck.java:18-37`（`checkDecide`）

**Interfaces:**
- Consumes:
  - `HousingRules.canGainCapacity(int usedBeds, int capacityTarget) -> boolean`（既有，本文件 57 行；`target < MAX_CAPACITY_TARGET && usedBeds < target + 2`）
  - `HousingRules.bedsNear(List<int[]> heads, int ax, int ay, int az, int radius) -> int`（既有）
  - `HousingRules.BIND_RADIUS`（既有常量 = 8）
  - `BedCensus.heads(ServerLevel, SettlementSavedData) -> List<BlockPos>`（既有，按 tick 缓存）
  - `HousingSavedData.Home.bed() -> BlockPos`、`capacityTarget() -> int`、`qualityTarget() -> int`（既有）
- Produces:
  - `HousingRules.decide(int beds, int occupiedSlots, int capacityTarget, int qualityTarget, int usedBedsNear) -> HousingRules.HomeAction`（**签名从 4 参变 5 参**，这是本轮唯一的对外接口变化）
  - `HousingCoordinator.bedHeadAxes(ServerLevel level, SettlementSavedData data) -> List<int[]>`（private）
  - `HousingCoordinator.usedBedsNear(List<int[]> heads, HousingSavedData.Home home) -> int`（private）

- [ ] **Step 1: 先改检查，写出失败用例**

把 `HousingRulesCheck.java` 的 `checkDecide()` 整个方法替换为下面这版。变化有三处：所有调用补第 5 个参数；原 `(8,8,2,0)` 的期望从 `IMPROVE_QUALITY` 改成 `NONE`（这是本轮要推翻的实现期断言）；新增 `(8,8,1,0,3)` 这条正是白花 81 块木板的情形。

```java
    private static void checkDecide() {
        require(HousingRules.decide(8, 8, 0, 0, 0) == HousingRules.HomeAction.EXPAND_CAPACITY,
                "a shortage expands a home that can still host another bed");
        require(HousingRules.decide(8, 8, 1, 0, 2) == HousingRules.HomeAction.EXPAND_CAPACITY,
                "a shortage keeps expanding while capacity remains");
        require(HousingRules.decide(8, 8, 1, 0, 3) == HousingRules.HomeAction.NONE,
                "a home walled in by neighbouring beds keeps its planks instead of raising a target");
        require(HousingRules.decide(8, 8, 2, 0, 0) == HousingRules.HomeAction.NONE,
                "a maxed home does not decorate while the settlement is short of beds");
        require(HousingRules.decide(8, 8, 2, 2, 0) == HousingRules.HomeAction.NONE,
                "nothing left to raise");
        require(HousingRules.decide(9, 8, 0, 0, 0) == HousingRules.HomeAction.IMPROVE_QUALITY,
                "surplus beds never expand capacity");
        require(HousingRules.decide(9, 8, 2, 2, 0) == HousingRules.HomeAction.NONE, "fully upgraded");
        boolean threw = false;
        try {
            HousingRules.decide(-1, 0, 0, 0, 0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        require(threw, "negative counts are rejected");
    }
```

- [ ] **Step 2: 运行检查，确认它按预期失败**

Run: `./gradlew housingRulesCheck --offline --no-daemon`

Expected: `> Task :compileTestJava FAILED`，报错形如 `符号: 方法 decide(int,int,int,int,int)` / `reason: actual and formal argument lists differ in length`。失败原因必须是"5 参方法不存在"，不是别的错误。

- [ ] **Step 3: 改纯规则，并同步修好调用方使其能编译**

3a. `HousingRules.java` 的 `decide` 整个方法替换为：

```java
    /**
     * A bed shortage is the only thing that justifies spending planks on capacity, and only for a
     * home that can actually host another bed. A home walled in by its neighbours' beds keeps its
     * planks: raising its target would build a stage no resident could ever sleep in, and decorating
     * while residents go without beds is not what "house them first" means.
     */
    public static HomeAction decide(int beds, int occupiedSlots, int capacityTarget, int qualityTarget,
                                    int usedBedsNear) {
        if (beds < 0 || occupiedSlots < 0 || usedBedsNear < 0) {
            throw new IllegalArgumentException(
                    "Bed, occupied slot and nearby bed counts cannot be negative");
        }
        validate(capacityTarget, qualityTarget);
        if (needsCapacity(beds, occupiedSlots)) {
            return canGainCapacity(usedBedsNear, capacityTarget)
                    ? HomeAction.EXPAND_CAPACITY : HomeAction.NONE;
        }
        return qualityTarget < MAX_QUALITY_TARGET ? HomeAction.IMPROVE_QUALITY : HomeAction.NONE;
    }
```

3b. `HousingCoordinator.java` 的 imports 里加一行（该文件当前没有 `java.util.List`）：

```java
import java.util.List;
```

3c. 同文件 `tick` 方法里，把这三行

```java
        int occupied = data.occupiedPopulationSlots();
        int beds = BedCensus.count(level, data);
        for (var home : housing.homes(id)) {
```

改成（多出的一行只构造一次床轴列表，`BedCensus.heads` 按 tick 缓存，重复调用不重复扫描）：

```java
        int occupied = data.occupiedPopulationSlots();
        int beds = BedCensus.count(level, data);
        List<int[]> heads = bedHeadAxes(level, data);
        for (var home : housing.homes(id)) {
```

3d. 同方法内，把

```java
            var action = HousingRules.decide(beds, occupied, home.capacityTarget(), home.qualityTarget());
```

改成

```java
            var action = HousingRules.decide(beds, occupied, home.capacityTarget(), home.qualityTarget(),
                    usedBedsNear(heads, home));
```

3e. 把 `hasCapacityGain` 整个方法替换为下面这版（它原先自己内联构造了一遍床轴列表，现在改为复用同一个助手）：

```java
    /** True while some home can still grow capacity toward hosting another bed. */
    public static boolean hasCapacityGain(ServerLevel level, SettlementSavedData data) {
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return false;
        }
        List<int[]> heads = bedHeadAxes(level, data);
        for (var home : HousingSavedData.get(level).homes(settlement.orElseThrow().id())) {
            if (HousingRules.canGainCapacity(usedBedsNear(heads, home), home.capacityTarget())) {
                return true;
            }
        }
        return false;
    }
```

3f. 在同文件末尾（最后一个 `}` 之前）加这两个私有助手：

```java
    private static List<int[]> bedHeadAxes(ServerLevel level, SettlementSavedData data) {
        List<int[]> heads = new ArrayList<int[]>();
        for (BlockPos head : BedCensus.heads(level, data)) {
            heads.add(new int[] {head.getX(), head.getY(), head.getZ()});
        }
        return heads;
    }

    /** Beds that count against this home: the census heads inside its binding radius. */
    private static int usedBedsNear(List<int[]> heads, HousingSavedData.Home home) {
        return HousingRules.bedsNear(heads, home.bed().getX(), home.bed().getY(), home.bed().getZ(),
                HousingRules.BIND_RADIUS);
    }
```

- [ ] **Step 4: 运行检查，确认转绿**

Run: `./gradlew housingRulesCheck --offline --no-daemon`

Expected: `HousingRulesCheck passed` 与 `BUILD SUCCESSFUL`。

- [ ] **Step 5: 跑完整构建与全部独立检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，且以下 11 项全部打印 `*Check passed`：HousingRules、PlotCoordinates、PopulationRules、ProfessionRules、ProtectedRectangle、SettlementDemand、SettlementSavedData、TrafficDecision、TrafficTargetRules、TransportSavedData、WorkerAssignmentRules。无编译警告。若 `SettlementDemandCheck` 或 `HousingRulesCheck` 之外有检查失败，说明 `decide` 的签名还有别的调用方未同步，用 `grep -rn "HousingRules.decide" src/` 找出来。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingRules.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/HousingRulesCheck.java
git commit -m "Stop expanding homes that cannot host another bed"
```

---

### Task 2: 完整构建收尾与项目文档

**Files:**
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1 的全部改动与它的构建结果
- Produces: 无代码接口；本轮的可追溯记录

- [ ] **Step 1: 确认工作区状态**

Run: `git -C .. status --short`（在 `goblin-settlement-mod/` 下执行）

Expected: 只有 Task 1 已提交后剩下的未跟踪/未提交项；**若出现你没改过的 `goblin-settlement-plan/` 文件（另一个 agent 的并行改动），只暂存你自己写的那些，不要动它们的。**

- [ ] **Step 2: 追加 UpdateLog 记录**

在 `goblin-settlement-plan/UpdateLog.md` **末尾**追加一段（照抄下面结构，把时间换成你实际操作的时刻，格式 `YYYY-MM-DD HH:MM:SS +08:00`）：

```markdown
## [<开始时间> – <结束时间>] 第四十三轮：住宅决策规则细化（缺床时饱和房子不动）

- [<时间>] 按 HOUSING_DESIGN.md §4 修订实现：`HousingRules.decide` 增参 `usedBedsNear`，缺床时改由既有 `canGainCapacity(usedBedsNear, capacityTarget)` 决定扩不扩容量，放不下则返回 NONE；`capacityTarget < MAX` 的单独判断删除（已由 `canGainCapacity` 涵盖）。`HousingCoordinator` 抽出 `bedHeadAxes`/`usedBedsNear` 两个私有助手，`tick` 与 `hasCapacityGain` 共用，`decide` 调用补第 5 参。
- [<时间>] 与实现期断言的冲突按设计原文了结：`HousingRulesCheck` 原断言"缺床且容量到顶转提品质"改为期望 NONE，并新增 `(8,8,1,0,3)→NONE` 覆盖白花 81 块木板的情形。
- [<时间>] 验证：`./gradlew build --offline --no-daemon` BUILD SUCCESSFUL，11 项独立检查全部 `*Check passed`。未启动游戏、未运行专用服务端、未触碰常用存档。
- [<时间>] 未完成：本轮仍只有编译与纯函数证据，不构成玩法验收；§10 记录的"绑定残余死角"会让那种房子反复扩容量（有界，上限 2 级）却仍放不下床，本轮不缓解；缺床期间不再装修是设计取舍，需在统一测试时观察。
```

- [ ] **Step 3: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 第 3 条「住宅剩余」里，把已完成的 `decide` 白花项从"下轮第一优先"改写为已完成（说明改成走 `canGainCapacity`、`decide` 签名变 5 参、检查断言随之变更），并把下一个第一优先改为该条里剩下的头一项。
- 「本轮接入的内容」新增一节「第四十三轮：住宅决策规则细化」，写清规则变化、`decide` 新签名、以及"缺床期间不装修"这条设计取舍。
- 「本轮验证进展」替换为第四十三轮的构建与检查结果。

- [ ] **Step 4: 提交并推送**

```bash
git add goblin-settlement-plan/UpdateLog.md goblin-settlement-plan/CURRENT_STATUS.md
git commit -m "Record the housing decision rule refinement"
git push origin main
```

Expected: 推送成功（本机 `http.proxy=http://127.0.0.1:7890` 已配置）。若推送被拒，先 `git pull --rebase origin main` 再推，**不要**强推。

---

## 自查记录

**1. 规格覆盖**

- §4「缺床 + 不能扩容 → 不动」→ Task 1 Step 3a。
- §4「`capacityTarget < MAX` 不再单独判」→ Task 1 Step 3a（`canGainCapacity` 涵盖）。
- §4「`usedBedsNear` 由 `bedsNear`、`BIND_RADIUS` 算得」→ Task 1 Step 3f。
- §4.1「与旧断言冲突，断言改为期望不动」→ Task 1 Step 1。
- §4.1「正向副作用：不再占用每 40 tick 的决策名额」→ 由 Task 1 Step 3c/3d 自然得到（返回 NONE 后 `advance` 无活可干，循环继续下一栋），无需额外代码。
- §4.1「逃生路径不变」→ 本轮刻意不动 `hasCapacityGain`、`SettlementDemand`、`BedProvisioningCoordinator`；Task 1 Step 3e 只是等价重构。
- §10「绑定残余死角与 4.1 的关系」→ Task 2 Step 3 记入状态文档；代码层明确不缓解。
- §8「先写失败再实现」→ Task 1 Step 1–2。

**2. 占位符扫描**：无 TBD/TODO；每个代码步骤都给了可整段粘贴的代码与预期输出。

**3. 类型一致性**

- `decide` 在本计划中始终是 5 参 `(int, int, int, int, int)`；Task 1 Step 1 的检查、Step 3a 的实现、Step 3d 的调用一致。
- `bedHeadAxes` 返回 `List<int[]>`，`usedBedsNear` 吃 `List<int[]>`——与 `HousingRules.bedsNear(List<int[]>, int, int, int, int)` 的既有签名一致。
- `HomeAction` 的枚举常量沿用既有 `EXPAND_CAPACITY` / `IMPROVE_QUALITY` / `NONE`，未新造。
- 注意 `HousingCoordinator` 当前**没有** `import java.util.List;`（只用过 `ArrayList` 配 `var`）——Step 3b 已显式列出，遗漏会编译失败。
