# 避难所质量（有墙与容量）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让避难所必须是**有墙的房屋**，并按**已建成的床位数**限制每栋房能容纳多少避难者；选择规则改成"最近且有空位者胜"。

**Architecture:** 纯层把 `ShelterRules.nearest` 升级为 `choose`（带 `used`/`capacity` 的 `Shelter` 记录）；`HousingCoordinator` 加一个语义明确的公开方法 `shelterCapacity`，一次答完"有墙吗 + 能躲几人"；`ShelterCoordinator` 扫名册数占用后调用选择规则；实体的 `shelterTarget` 加只读访问器。**纯规则与其唯一调用方必须一次改完**——`nearest` 被取代会让 `ShelterCoordinator` 编译不过。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [SENTRY_SHELTER_QUALITY_DESIGN.md](SENTRY_SHELTER_QUALITY_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **`nearest` 由 `choose` 取代，不并存**：容量判定属于"选哪个避难所"这条规则本身。
- **不发明容量数**：用 `HousingRules.builtCapacity`（1–3，已建成的床位数）。
- **不做占用预留**：两个居民可能同 tick 选到同一间房而超员 1。这是已知竞态，不要为此加锁或落盘。
- **名册遍历只留一份**：`ResidentWorkLookup.anyLoaded` 改为复用新的 `loaded(...)`。
- 本轮**不新增检查项**（14 项不变），是**改写**上一轮的 `ShelterRulesCheck`。
- 不做游戏内验证（按用户约定）。
- `goblin-settlement-plan/` 下**可能有并行 agent 的未提交改动**：**在文档任务开始时**先跑 `git status`，发现不是自己改的就**先单独提交它们并署名**，再追加自己的。`UpdateLog.md` **只许在末尾追加**。

---

### Task 1: 选择规则升级与全部接线

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/ShelterRules.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/defense/ShelterRulesCheck.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`（加 `shelterCapacity`）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ResidentWorkLookup.java`（加 `loaded`，`anyLoaded` 改为复用）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/ShelterCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`（加 `shelterTarget()`；调用点补参数）

**Interfaces:**
- Consumes: `HousingRules.builtCapacity`、`HousingCoordinator.stageFullyBuilt`、`HousingSavedData.homes`
- Produces:
  - `ShelterRules.Shelter(int x, int y, int z, int used, int capacity)`
  - `ShelterRules.choose(List<Shelter> shelters, int[] from) -> int`（**取代 `nearest`**）
  - `HousingCoordinator.shelterCapacity(ServerLevel level, HousingSavedData.Home home) -> int`
  - `ResidentWorkLookup.loaded(ServerLevel level, SettlementSavedData data) -> List<GoblinCitizenEntity>`
  - `ShelterCoordinator.nearestShelter(ServerLevel level, String settlementId, BlockPos from, GoblinCitizenEntity self) -> Optional<BlockPos>`（**多一个参数**）
  - `GoblinCitizenEntity.shelterTarget() -> Optional<BlockPos>`

- [ ] **Step 1: 改检查（RED）**

把 `ShelterRulesCheck.java` 整个替换为：

```java
package dev.local.goblinsettlement.defense;

import java.util.List;

public final class ShelterRulesCheck {
    public static void main(String[] args) {
        checkNearestWithRoomWins();
        checkFullShelterIsPassedOver();
        checkAllFull();
        checkTiesBreakByXThenZ();
        checkNoShelters();
        System.out.println("ShelterRulesCheck passed");
    }

    private static void checkNearestWithRoomWins() {
        var shelters = List.of(shelter(20, 64, 0, 0, 2), shelter(3, 64, 0, 0, 2), shelter(-9, 64, 0, 0, 2));
        require(ShelterRules.choose(shelters, point(0, 64, 0)) == 1, "the closest refuge wins");
    }

    private static void checkFullShelterIsPassedOver() {
        // The closest is full, so the next one out takes the resident.
        var shelters = List.of(shelter(3, 64, 0, 2, 2), shelter(9, 64, 0, 0, 2));
        require(ShelterRules.choose(shelters, point(0, 64, 0)) == 1, "a full refuge is skipped");
    }

    private static void checkAllFull() {
        var shelters = List.of(shelter(3, 64, 0, 1, 1), shelter(9, 64, 0, 3, 3));
        require(ShelterRules.choose(shelters, point(0, 64, 0)) == -1, "no room anywhere means no choice");
    }

    private static void checkTiesBreakByXThenZ() {
        var byX = List.of(shelter(4, 64, 0, 0, 1), shelter(-4, 64, 0, 0, 1));
        require(ShelterRules.choose(byX, point(0, 64, 0)) == 1, "an equal distance goes to the smaller x");
        var byZ = List.of(shelter(0, 64, 4, 0, 1), shelter(0, 64, -4, 0, 1));
        require(ShelterRules.choose(byZ, point(0, 64, 0)) == 1, "then to the smaller z");
    }

    private static void checkNoShelters() {
        require(ShelterRules.choose(List.of(), point(0, 64, 0)) == -1, "no refuges means no choice");
        require(ShelterRules.choose(List.of(shelter(1, 64, 1, 0, 1)), point(0, 64, 0))
                        == ShelterRules.choose(List.of(shelter(1, 64, 1, 0, 1)), point(0, 64, 0)),
                "the same inputs always give the same answer");
    }

    private static ShelterRules.Shelter shelter(int x, int y, int z, int used, int capacity) {
        return new ShelterRules.Shelter(x, y, z, used, capacity);
    }

    private static int[] point(int x, int y, int z) {
        return new int[] {x, y, z};
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

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 方法 choose(...)` / `类 Shelter`。

- [ ] **Step 3: 升级纯规则（GREEN 的一半）**

把 `ShelterRules.java` 整个替换为：

```java
package dev.local.goblinsettlement.defense;

import java.util.List;

/** Pure shelter selection: which refuge a resident runs to. */
public final class ShelterRules {
    private ShelterRules() {
    }

    /** A candidate refuge: where it is, how many are already heading there, and how many it holds. */
    public record Shelter(int x, int y, int z, int used, int capacity) {
    }

    /**
     * The nearest refuge that still has room, or -1 when none has. Capacity is part of this rule rather
     * than a filter around it: "which refuge" and "is there space in it" are one question.
     */
    public static int choose(List<Shelter> shelters, int[] from) {
        int best = -1;
        for (int index = 0; index < shelters.size(); index++) {
            Shelter candidate = shelters.get(index);
            if (candidate.used() >= candidate.capacity()) {
                continue;
            }
            if (best < 0 || isCloser(candidate, shelters.get(best), from)) {
                best = index;
            }
        }
        return best;
    }

    /** Nearer wins; an exact tie goes to the smaller x, then the smaller z, so the choice repeats. */
    private static boolean isCloser(Shelter candidate, Shelter incumbent, int[] from) {
        int candidateDistance = distanceSquared(candidate, from);
        int incumbentDistance = distanceSquared(incumbent, from);
        if (candidateDistance != incumbentDistance) {
            return candidateDistance < incumbentDistance;
        }
        int byX = Integer.compare(candidate.x(), incumbent.x());
        return byX != 0 ? byX < 0 : candidate.z() < incumbent.z();
    }

    private static int distanceSquared(Shelter shelter, int[] from) {
        int dx = shelter.x() - from[0];
        int dy = shelter.y() - from[1];
        int dz = shelter.z() - from[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
```

- [ ] **Step 4: `HousingCoordinator` 加"这栋房能躲几人"**

在 `stageFullyBuilt` 附近加（**public**）：

```java
    /**
     * Beds this home actually provides, or 0 when even its walls are not up yet. Callers ask how many
     * can shelter here; "which stage is built" is an implementation detail of this answer.
     */
    public static int shelterCapacity(ServerLevel level, HousingSavedData.Home home) {
        if (!stageFullyBuilt(level, home, 1)) {
            return 0;
        }
        return HousingRules.builtCapacity(home.capacityTarget(), true,
                stageFullyBuilt(level, home, 2));
    }
```

- [ ] **Step 5: `ResidentWorkLookup` 加列表查询并让 `anyLoaded` 复用**

把 `anyLoaded` 整个替换为下面两个方法：

```java
    /** Every loaded, living resident, for callers that need to look at more than one. */
    public static List<GoblinCitizenEntity> loaded(ServerLevel level, SettlementSavedData data) {
        List<GoblinCitizenEntity> found = new ArrayList<>();
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.DECEASED) {
                continue;
            }
            try {
                if (level.getEntity(UUID.fromString(record.id())) instanceof GoblinCitizenEntity goblin
                        && goblin.isAlive()) {
                    found.add(goblin);
                }
            } catch (IllegalArgumentException ignored) {
                // A malformed or legacy roster ID cannot identify a loaded worker.
            }
        }
        return found;
    }

    public static boolean anyLoaded(ServerLevel level, SettlementSavedData data,
                                    Predicate<GoblinCitizenEntity> hasWork) {
        return loaded(level, data).stream().anyMatch(hasWork);
    }
```

需要 `import java.util.ArrayList;` 与 `import java.util.List;`。

**这是有意的取舍**：`anyLoaded` 不再短路，名册遍历只剩一份。名册上限 64、调用方都在审查节拍上，代价可接受。

- [ ] **Step 6: `ShelterCoordinator` 改写**

把 `nearestShelter` 整个替换为：

```java
    /**
     * The nearest home with both walls and a free slot, or empty when there is nowhere to go. A home
     * counts only once its capacity axis has walls up -- a pergola is not a refuge -- and it holds at
     * most the beds its built geometry actually provides.
     */
    public static Optional<BlockPos> nearestShelter(ServerLevel level, String settlementId,
                                                    BlockPos from, GoblinCitizenEntity self) {
        var homes = HousingSavedData.get(level).homes(settlementId);
        if (homes.isEmpty()) {
            return Optional.empty();
        }
        // Counted, not reserved: two residents choosing in the same tick can both see the same free
        // slot and overfill one home by one. A reservation would need to be persisted or locked.
        List<BlockPos> taken = new ArrayList<>();
        for (GoblinCitizenEntity other
                : ResidentWorkLookup.loaded(level, SettlementSavedData.get(level))) {
            if (other == self) {
                continue;
            }
            other.shelterTarget().ifPresent(taken::add);
        }
        List<BlockPos> beds = new ArrayList<>();
        List<ShelterRules.Shelter> shelters = new ArrayList<>();
        for (var home : homes) {
            int capacity = HousingCoordinator.shelterCapacity(level, home);
            if (capacity <= 0) {
                continue;
            }
            BlockPos bed = home.bed();
            int used = 0;
            for (BlockPos target : taken) {
                if (target.equals(bed)) {
                    used++;
                }
            }
            beds.add(bed);
            shelters.add(new ShelterRules.Shelter(bed.getX(), bed.getY(), bed.getZ(), used, capacity));
        }
        int index = ShelterRules.choose(shelters, new int[] {from.getX(), from.getY(), from.getZ()});
        return index < 0 ? Optional.empty() : Optional.of(beds.get(index));
    }
```

需要 `import dev.local.goblinsettlement.colony.ResidentWorkLookup;` 与 `import dev.local.goblinsettlement.colony.SettlementSavedData;` 与 `import dev.local.goblinsettlement.housing.HousingCoordinator;`。

- [ ] **Step 7: 实体加访问器并改调用点**

7a. 在 `hasPatrolWork` 附近加：

```java
    /** Where this resident is hiding, while it is hiding. Empty when it is not sheltering. */
    public Optional<BlockPos> shelterTarget() {
        return sheltering ? Optional.of(shelterTarget) : Optional.empty();
    }
```

（`Optional` 若未 import 就加 `import java.util.Optional;`。）

**为什么只在避难时返回位置**：`shelterTarget` 的默认值是 `BlockPos.ZERO`，若无条件返回，所有不避难的居民都会被数成"瞄向了原点那栋房"。

7b. `maintainSheltering` 里把

```java
            var shelter = ShelterCoordinator.nearestShelter(level, settlementId, blockPosition());
```

改成

```java
            var shelter = ShelterCoordinator.nearestShelter(level, settlementId, blockPosition(), this);
```

- [ ] **Step 8: 编译并跑全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**14 项**检查全部 `*Check passed`，无编译警告。

- [ ] **Step 9: 核对接线**

Run: `grep -rn "ShelterRules.nearest\|ShelterRules.choose\|shelterCapacity\|ResidentWorkLookup.loaded\|shelterTarget()" src/`

Expected: `ShelterRules.nearest` **一处不剩**；`choose` 有定义 + 检查里的调用 + 协调器里的调用；`shelterCapacity` 一处定义一处调用；`loaded` 一处定义两处调用（`anyLoaded` 与 `ShelterCoordinator`）；`shelterTarget()` 一处定义一处调用。

- [ ] **Step 10: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/ShelterRules.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/defense/ShelterRulesCheck.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/ShelterCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ResidentWorkLookup.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java
git commit -m "Only walled homes shelter, and only up to their beds"
```

---

### Task 2: 完整构建收尾与项目文档

**Files:**
- Modify: `goblin-settlement-plan/SENTRY_SHELTER_DESIGN.md`（§3/§7 的两个缺口标注为已补）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1 的改动与构建结果
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：**先单独提交它们**并署名，**然后再**做本任务的文档改动。

- [ ] **Step 2: 更新 SENTRY_SHELTER_DESIGN.md**

- §3「避难所 = 已登记房屋」→ 标注**已被第五十二轮之一（本轮的上一轮）之后的细化取代**：候选收窄为有墙的房屋，并有容量上限；指向 `SENTRY_SHELTER_QUALITY_DESIGN.md`。
- §7 风险里"避难所可能是没有墙的棚"与"房屋容量未考虑"两条 → 标注**已补**，并写明补救方式与仍然存在的边界（早期人多房少、占用未预留）。

- [ ] **Step 3: 追加 UpdateLog**

在末尾追加（时间换成实际操作时刻；**逐条写真实时间，不要留占位**）：

```markdown
## [<开始> – <结束>] 第五十三轮：避难所质量（有墙与容量）

- [<时间>] 按 SENTRY_SHELTER_QUALITY_DESIGN.md 与 SENTRY_SHELTER_QUALITY_PLAN.md 执行，补掉上一轮自己在 SENTRY_SHELTER_DESIGN §7 标出的两个缺口。
- [<时间>] 缺口一：上一轮把"已登记房屋"当避难所，而登记房屋在容量轴 0 级时**只有四角柱与顶**，躲进去等于没躲。现在候选收窄为**容量轴 1 级几何已建成**（四壁 + 门洞）的房屋。
- [<时间>] 缺口二：上一轮没有容量判定，理论上全村可以挤进一间小屋。现在每栋房最多容纳**它已建成的床位数**（HousingRules.builtCapacity，1–3）——**借用而非发明**：同一个数既决定能住几人、也决定能躲几人。
- [<时间>] `HousingCoordinator` 加了 public 的 `shelterCapacity(level, home)`：一次答完"有墙吗 + 能躲几人"，返回 0 即"不能当避难所"。**没有直接放开包内的 `stageFullyBuilt`**——那会让 defense 依赖"第几级"这种几何概念，而调用方想问的是"这栋房能躲几个人"。
- [<时间>] `ShelterRules.nearest` 升级为 `choose(List<Shelter>, int[])`，`Shelter` 记录带 used/capacity；**取代而非并存**——"最近"与"有没有空位"是同一个问题。检查用例改写为新签名后保留并扩写（最近的满了让给次近、全满返回 -1）。
- [<时间>] 实体加只读的 `shelterTarget()`，**只在真的在避难时才返回位置**——否则默认值 BlockPos.ZERO 会让所有不避难的居民被数成"瞄向了原点那栋房"。占用数由 ShelterCoordinator 扫一遍名册得到，**每次选择只算一次**。
- [<时间>] `ResidentWorkLookup` 加 `loaded(...)` 列表查询，`anyLoaded` 改为复用**同一段名册遍历**——不再有两个副本。代价是 `anyLoaded` 不再短路；名册上限 64、调用方都在审查节拍上，可接受。
- [<时间>] **已知竞态，本轮不做预留**：占用是扫描得到的、不是预留的，两个居民在同一 tick 各自选房时会看到同一份快照，可能选到同一间而**超员 1**。预留要么落盘要么加锁，代价远超收益。
- [<时间>] **早期一定会有居民找不到避难所**：床位上限要到 MAX_HOMES（48）× 3 = 144 才够 64 人的居民上限，早期房子少时远不够。找不到的**照常干活**（沿用上一轮的兜底，从"一栋房都没有"扩展到"没有一栋还有空位"）。这是有意的取舍：没有空屋就无处可躲，乱挤在门口并不比干活安全。
- [<时间>] 验证：完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**14 项**独立检查全部 *Check passed（本次是改写 ShelterRulesCheck，未新增检查项）。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>。
- [<时间>] 未完成：不构成玩法验收；**"有墙"与"占用"两段判定在 MC 侧，不可纯测**——纯层只覆盖选择规则；占用未预留（见上）；"能住几人 = 能躲几人"是借用而非定义，若试玩后别扭需单独设计避难容量；每栋房要扫一遍 stageFullyBuilt（只在选择时发生，房子多时会变慢）。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「职业系统剩余」：把「避难所容量与出入判定」「避难所是否真有墙的判定」两项移出未做项、记为已完成；保留「哨卫逐户护送」（用户已否）。
- 「本轮接入的内容」新增一节「第五十三轮：避难所质量」。
- 「本轮验证进展」替换为本轮构建结果；写明本轮是**改写**检查而非新增。
- 「阶段定位」与检查项数处不需要改（仍 14）。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the shelter quality round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 候选收窄 + `shelterCapacity` → Task 1 Step 3（纯层）与 Step 4（housing 侧）。
- 设计 §3 容量 = 已建成床位数 → Task 1 Step 4；后果（早期找不到）→ Task 2 Step 3。
- 设计 §4 `choose` 取代 `nearest` → Task 1 Step 1/3。
- 设计 §5 占用来源 + `loaded` 复用 → Task 1 Step 5/6/7。
- 设计 §6 验证 → Task 1 Step 1/2/8；不可纯测的界 → Task 2 Step 3。
- 设计 §7 风险 → Task 2 Step 3 的"未完成"条与 Step 2 对文档旧风险的标注。

**2. 占位符扫描**

- 无 TBD/TODO/待填。纯规则、检查、四个协调器/存档侧的改动都给了可粘贴全文。
- Task 1 Step 7a 明说"`Optional` 若未 import 就加"——条件性动作，判据明确。
- Task 2 Step 3 的 `<开始>`/`<时间>`/`<字节数>` 是模板占位；正文已要求逐条写真实时间——**第四十八、五十轮各漏填过一次**，执行时务必替换。

**3. 类型一致性**

- `ShelterRules.choose(List<Shelter>, int[]) -> int` 在 Task 1 的检查、Step 3 的实现、Step 6 的调用三处一致；`-1` 的分支在调用侧被显式处理（`index < 0 ? empty : ...`）。
- `ShelterRules.Shelter` 是纯 record，`used`/`capacity` 用访问器方法（`used()`/`capacity()`）取，与 record 的既有写法一致。
- `nearestShelter` 多了第 4 个参数 `GoblinCitizenEntity self`，**唯一调用点在 `maintainSheltering`**（Task 1 Step 7b 同步改了）；参数类型与实体的 `this` 一致。
- `shelterCapacity` 返回 `int`，`<= 0` 即"不是避难所"；调用侧用它同时完成"筛掉无墙的房"与"取容量"两件事，不再单独调 `stageFullyBuilt`。
- `ResidentWorkLookup.loaded` 返回 `List<GoblinCitizenEntity>`，`ShelterCoordinator` 直接 for-each；`anyLoaded` 的签名与返回类型不变，因此它的既有调用方（各协调器）无需改动。
- `shelterTarget()` 返回 `Optional<BlockPos>`，与 `ShelterCoordinator` 的 `ifPresent(taken::add)` 匹配。
