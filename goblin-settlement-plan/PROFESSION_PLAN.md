# 哥布林职业系统 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给哥布林居民加上持久职业身份，让职业影响派活优先次序与干活速度，并用贴图把职业显示出来。

**Architecture:** 职业是名册（`colony`）上的持久字段，不是门控条件。`ProfessionRules` 是唯一的纯规则来源：`matchRank` 决定谁被优先选中，`workIntervalTicks` 决定干活快慢。9 个协调器只改挑人的比较器（不碰资格判定），实体只改工作推进的节流间隔。外观通过实体同步字段 + 按职业切换贴图实现。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Loom 1.14.10 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [PROFESSION_DESIGN.md](PROFESSION_DESIGN.md)。数值有分歧时以本文为准，本文与设计文档冲突时以设计文档为准并回来修本文。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。禁止引入其他 Minecraft 版本的 API 或示例。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`（在 `goblin-settlement-mod/` 下执行）。本机无外网依赖缓存以外的网络需求。
- **不做游戏内验证。** 按用户约定：初版代码全部完成后才统一测试。本轮只做编译 + 独立检查。
- **职业不是门控。** 任何居民都能接任何工作；职业只影响被选中的优先级与推进速度。禁止在 `isAvailableForConstruction()` 或任何 `assignX()` 里加入"职业不符则拒绝"的判断。
- 实际物品只存在于箱子、居民背包与世界中。库存记录不得产生物品。
- 存档兼容：`SettlementSavedData` 的 `SCHEMA_VERSION` 保持 1，不得提升。新字段一律用 `optionalFieldOf(名字, 默认值)`。
- 服务端权威；客户端只显示。`colony` 管名册，`citizen` 只读取职业、不复制。
- 不修改相邻的 `ai-chat-mod`，不动常用存档。
- 代码注释用英文（与现有全部源码一致）。不写解释"这段代码做什么"的注释，只在违反直觉之处说明原因。

---

### Task 1: 职业与工作种类枚举，以及纯规则函数

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/Profession.java`
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/WorkKind.java`
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ProfessionRules.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/ProfessionRulesCheck.java`
- Modify: `goblin-settlement-mod/build.gradle`（注册 JavaExec 任务并纳入 `check`）

**Interfaces:**
- Consumes: 无
- Produces:
  - `enum Profession { UNASSIGNED, FARMER, FORESTER, MINER, BUILDER, HAULER, ARTISAN, SENTRY }`
  - `enum WorkKind`，方法 `Profession required()`、静态 `boolean employs(Profession)`
  - `ProfessionRules.matchRank(WorkKind, Profession) -> int`
  - `ProfessionRules.workIntervalTicks(WorkKind, Profession) -> int`
  - `ProfessionRules.scarcest(List<Profession>) -> Profession`

- [ ] **Step 1: 写失败的检查**

创建 `ProfessionRulesCheck.java`（沿用工程现有检查的写法：无 JUnit，`main` + `check`，成功打印 `XxxCheck passed`）：

```java
package dev.local.goblinsettlement.colony;

import java.util.List;

/** Standalone checks runnable with the JDK while Fabric dependencies are unavailable. */
public final class ProfessionRulesCheck {
    public static void main(String[] args) {
        check(ProfessionRules.matchRank(WorkKind.FARMING, Profession.FARMER) == 0,
                "a matching specialist ranks first");
        check(ProfessionRules.matchRank(WorkKind.FARMING, Profession.MINER)
                > ProfessionRules.matchRank(WorkKind.FARMING, Profession.UNASSIGNED),
                "a mismatched specialist ranks behind an unassigned generalist");
        check(ProfessionRules.matchRank(WorkKind.FARMING, Profession.SENTRY)
                == ProfessionRules.matchRank(WorkKind.FARMING, Profession.UNASSIGNED),
                "a profession with no work of its own is a generalist, not a mismatch");
        check(ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.FARMER) == 10,
                "a matching specialist works at the baseline pace");
        check(ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.UNASSIGNED)
                > ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.FARMER),
                "a generalist works slower than a specialist");
        check(ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.MINER)
                > ProfessionRules.workIntervalTicks(WorkKind.FARMING, Profession.UNASSIGNED),
                "a mismatched specialist works slowest");
        check(WorkKind.employs(Profession.SENTRY) == false, "sentry has no work kind yet");
        check(WorkKind.employs(Profession.FARMER), "farmer is employed by farming work");
        check(ProfessionRules.scarcest(List.of()) == Profession.FARMER,
                "an empty roster starts from the first profession");
        check(ProfessionRules.scarcest(List.of(Profession.FARMER, Profession.FARMER))
                == Profession.FORESTER, "the fewest-staffed profession wins");
        check(ProfessionRules.scarcest(List.of(Profession.FARMER, Profession.FORESTER))
                == Profession.FARMER, "ties fall back to enum order");
        System.out.println("ProfessionRulesCheck passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

- [ ] **Step 2: 写桩实现，让检查可编译但断言必失败**

`Profession` 是纯数据，直接写成品：

```java
package dev.local.goblinsettlement.colony;

/** A resident's persisted trade. UNASSIGNED is the generalist default and must stay first. */
public enum Profession {
    UNASSIGNED, FARMER, FORESTER, MINER, BUILDER, HAULER, ARTISAN, SENTRY
}
```

`WorkKind` 与 `ProfessionRules` 先写**故意错误的桩**，使 `professionRulesCheck` 能编译但断言失败：

```java
package dev.local.goblinsettlement.colony;

public enum WorkKind {
    FARMING, FOOD_CRAFTING, FORESTRY, MINING, CONSTRUCTION, HOUSING,
    TRANSPORT, RECOVERY, TOOL_CRAFTING, SMELTING;

    public Profession required() {
        return Profession.UNASSIGNED;
    }

    public static boolean employs(Profession profession) {
        return false;
    }
}
```

```java
package dev.local.goblinsettlement.colony;

import java.util.List;

public final class ProfessionRules {
    private ProfessionRules() {
    }

    public static int matchRank(WorkKind kind, Profession resident) {
        return 0;
    }

    public static int workIntervalTicks(WorkKind kind, Profession resident) {
        return 10;
    }

    public static Profession scarcest(List<Profession> assignedAdults) {
        return Profession.UNASSIGNED;
    }
}
```

- [ ] **Step 3: 注册检查任务并运行，确认它因断言失败**

在 `build.gradle` 的 `settlementDemandCheck` 任务之后加入：

```groovy
tasks.register('professionRulesCheck', JavaExec) {
    group = 'verification'
    description = 'Checks the pure profession dispatch rules.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.colony.ProfessionRulesCheck'
}
```

并在 `tasks.named('check')` 块里追加一行 `dependsOn tasks.named('professionRulesCheck')`。

Run: `cd goblin-settlement-mod && ./gradlew professionRulesCheck --offline --no-daemon`
Expected: **编译通过，但抛 `AssertionError`**，消息形如 `a mismatched specialist ranks behind an unassigned generalist`。必须是断言失败而不是编译错误——编译错误只说明符号不存在，证明不了断言能捕捉错误行为。

- [ ] **Step 4: 实现 `WorkKind`（替换桩）**

```java
package dev.local.goblinsettlement.colony;

/**
 * A job a coordinator can dispatch. The mapping to the profession that does it best lives here so the
 * knowledge is not repeated in every coordinator.
 */
public enum WorkKind {
    FARMING(Profession.FARMER),
    FOOD_CRAFTING(Profession.FARMER),
    FORESTRY(Profession.FORESTER),
    MINING(Profession.MINER),
    CONSTRUCTION(Profession.BUILDER),
    HOUSING(Profession.BUILDER),
    TRANSPORT(Profession.HAULER),
    RECOVERY(Profession.HAULER),
    TOOL_CRAFTING(Profession.ARTISAN),
    SMELTING(Profession.ARTISAN);

    private final Profession required;

    WorkKind(Profession required) {
        this.required = required;
    }

    public Profession required() {
        return required;
    }

    /** True when some work kind is best done by this profession. A profession with none is a generalist. */
    public static boolean employs(Profession profession) {
        for (WorkKind kind : values()) {
            if (kind.required == profession) {
                return true;
            }
        }
        return false;
    }
}
```

- [ ] **Step 5: 实现 `ProfessionRules`（替换桩）**

```java
package dev.local.goblinsettlement.colony;

import java.util.List;

/** Pure dispatch rules: how strongly a profession matches a kind of work, and how fast it works. */
public final class ProfessionRules {
    private static final int MATCHING_INTERVAL_TICKS = 10;
    private static final int GENERALIST_INTERVAL_TICKS = 20;
    private static final int MISMATCHED_INTERVAL_TICKS = 30;

    private ProfessionRules() {
    }

    /**
     * Lower sorts first. A matching specialist beats a generalist, and a generalist beats a specialist
     * dragged away from their own trade. SENTRY currently has no work of its own, so it stays a generalist
     * until sentry jobs exist.
     */
    public static int matchRank(WorkKind kind, Profession resident) {
        if (resident == kind.required()) {
            return 0;
        }
        return WorkKind.employs(resident) ? 2 : 1;
    }

    /** Ticks between work steps. All values are multiples of the entity's 10-tick registration cadence. */
    public static int workIntervalTicks(WorkKind kind, Profession resident) {
        return switch (matchRank(kind, resident)) {
            case 0 -> MATCHING_INTERVAL_TICKS;
            case 1 -> GENERALIST_INTERVAL_TICKS;
            default -> MISMATCHED_INTERVAL_TICKS;
        };
    }

    /** The profession the fewest adults currently hold. Ties fall back to enum order so results repeat. */
    public static Profession scarcest(List<Profession> assignedAdults) {
        Profession best = Profession.FARMER;
        int fewest = Integer.MAX_VALUE;
        for (Profession candidate : Profession.values()) {
            if (candidate == Profession.UNASSIGNED) {
                continue;
            }
            int count = 0;
            for (Profession assigned : assignedAdults) {
                if (assigned == candidate) {
                    count++;
                }
            }
            if (count < fewest) {
                fewest = count;
                best = candidate;
            }
        }
        return best;
    }
}
```

- [ ] **Step 6: 运行检查，确认通过**

Run: `cd goblin-settlement-mod && ./gradlew professionRulesCheck --offline --no-daemon`
Expected: 打印 `ProfessionRulesCheck passed`，`BUILD SUCCESSFUL`。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/Profession.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/WorkKind.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ProfessionRules.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/ProfessionRulesCheck.java \
        goblin-settlement-mod/build.gradle
git commit -m "Add pure profession dispatch rules and their standalone check"
```

---

### Task 2: 职业写入名册（兼容旧存档）

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ResidentRecord.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/SettlementSavedDataCheck.java`

**Interfaces:**
- Consumes: Task 1 的 `Profession`
- Produces: `ResidentRecord.profession() -> Profession`、`ResidentRecord.withProfession(Profession) -> ResidentRecord`

**字段顺序必须与 Codec 的 group 顺序一致。** 新分量插在 `reproductiveRole` 之后、`childActiveTicks` 之前。

- [ ] **Step 1: 写失败的检查**

该文件的断言辅助方法叫 `require(boolean, String)`（不是 `check`），Codec 惯例是 `CODEC.encodeStart(JsonOps.INSTANCE, value).getOrThrow()` 与 `CODEC.parse(JsonOps.INSTANCE, json).getOrThrow()`。在 `main` 内、`System.out.println("SettlementSavedDataCheck passed")` 之前追加：

```java
        JsonObject preProfession = new JsonObject();
        preProfession.addProperty("id", "legacy-1");
        preProfession.addProperty("stage", "ADULT");
        require(ResidentRecord.CODEC.parse(JsonOps.INSTANCE, preProfession).getOrThrow().profession()
                == Profession.UNASSIGNED, "a resident saved before professions loads as unassigned");

        var tradeJson = ResidentRecord.CODEC.encodeStart(JsonOps.INSTANCE,
                ResidentRecord.adult("traded-1").withProfession(Profession.MINER)).getOrThrow();
        require(ResidentRecord.CODEC.parse(JsonOps.INSTANCE, tradeJson).getOrThrow().profession()
                == Profession.MINER, "a trade survives a codec round trip");

        require(ResidentRecord.adult("fresh-1").profession() == Profession.UNASSIGNED,
                "a freshly registered adult starts with no trade");
```

`JsonOps` 与 `JsonObject` 该文件已 import；`Profession` 与 `ResidentRecord` 同包，无需 import。

- [ ] **Step 2: 运行检查，确认失败**

Run: `cd goblin-settlement-mod && ./gradlew settlementSavedDataCheck --offline --no-daemon`
Expected: 编译失败，`cannot find symbol: method profession()` / `withProfession(...)`。

- [ ] **Step 3: 给 `ResidentRecord` 加字段**

把 record 头改为（只列改动处）：

```java
public record ResidentRecord(String id, LifeStage stage, Optional<String> motherId, Optional<String> fatherId,
                             ReproductiveRole reproductiveRole, Profession profession,
                             long childActiveTicks, long restTicks) {
```

在 `ROLE_CODEC` 之后加入：

```java
    private static final Codec<Profession> PROFESSION_CODEC = Codec.STRING.comapFlatMap(value -> {
        try {
            return DataResult.success(Profession.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(() -> "Unknown profession: " + value);
        }
    }, Profession::name);
```

在 `CODEC` 的 group 里，`ROLE_CODEC.optionalFieldOf(...)` 那行之后插入：

```java
            PROFESSION_CODEC.optionalFieldOf("profession", Profession.UNASSIGNED)
                    .forGetter(ResidentRecord::profession),
```

- [ ] **Step 4: 更新所有构造点**

`ResidentRecord` 是 record，每个 `new ResidentRecord(...)` 都要补上新分量。改动清单：

- 紧凑构造器校验：把条件改为同时要求 `profession != null`。
- `adult(String id)`：传入 `Profession.UNASSIGNED`。
- `child(String id, String motherId, String fatherId)`：传入 `Profession.UNASSIGNED`。
- `withReproductiveRole(ReproductiveRole)`：原样带上 `profession`。
- `advanceFamilyTime(long)` 的两处 `new ResidentRecord(...)`：原样带上 `profession`。
- `withPostBirthRest()`、`deceased()`：原样带上 `profession`。

新增方法（放在 `withReproductiveRole` 之后，沿用其守卫风格）：

```java
    public ResidentRecord withProfession(Profession value) {
        if (value == null || value == Profession.UNASSIGNED || stage != LifeStage.ADULT
                || profession != Profession.UNASSIGNED) {
            throw new IllegalArgumentException("Only a living adult with no trade can be given one");
        }
        return new ResidentRecord(id, stage, motherId, fatherId, reproductiveRole, value,
                childActiveTicks, restTicks);
    }
```

- [ ] **Step 5: 运行检查，确认通过**

Run: `cd goblin-settlement-mod && ./gradlew settlementSavedDataCheck --offline --no-daemon`
Expected: 打印 `SettlementSavedDataCheck passed`（文件原有的全部断言也必须仍然通过）。

- [ ] **Step 6: 运行全部检查，确认没有连带破坏**

Run: `cd goblin-settlement-mod && ./gradlew check --offline --no-daemon`
Expected: 6 个 `*Check passed` 全部打印，`BUILD SUCCESSFUL`。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ResidentRecord.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/SettlementSavedDataCheck.java
git commit -m "Persist a profession on the resident roster"
```

---

### Task 3: 名册上的职业查询与指派

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementSavedData.java`

**Interfaces:**
- Consumes: Task 2 的 `ResidentRecord.withProfession`、`ResidentRecord.profession()`
- Produces:
  - `SettlementSavedData.assignProfession(String residentId, Profession profession) -> boolean`
  - `SettlementSavedData.unassignedAdultIds() -> List<String>`
  - `SettlementSavedData.assignedProfessions() -> List<Profession>`

- [ ] **Step 1: 实现三个方法**

放在 `assignResidentRole` 之后，照抄它的写法（遍历、`List.copyOf`、`setDirty`）：

```java
    /** Gives an unassigned adult a trade. Reassigning an existing trade is deliberately not supported yet. */
    public boolean assignProfession(String residentId, Profession profession) {
        if (profession == null || profession == Profession.UNASSIGNED) {
            return false;
        }
        for (int index = 0; index < residents.size(); index++) {
            ResidentRecord current = residents.get(index);
            if (current.id().equals(residentId)
                    && current.stage() == ResidentRecord.LifeStage.ADULT
                    && current.profession() == Profession.UNASSIGNED) {
                var updated = new ArrayList<>(residents);
                updated.set(index, current.withProfession(profession));
                residents = List.copyOf(updated);
                setDirty();
                return true;
            }
        }
        return false;
    }

    public List<String> unassignedAdultIds() {
        return residents.stream()
                .filter(record -> record.stage() == ResidentRecord.LifeStage.ADULT)
                .filter(record -> record.profession() == Profession.UNASSIGNED)
                .map(ResidentRecord::id)
                .toList();
    }

    /** The trades held by living adults, used to pick the scarcest one. */
    public List<Profession> assignedProfessions() {
        return residents.stream()
                .filter(record -> record.stage() == ResidentRecord.LifeStage.ADULT)
                .map(ResidentRecord::profession)
                .toList();
    }
```

确认 `SettlementSavedData` 已 import `java.util.List`（`adultCount` 等已在用，应当已存在）。

- [ ] **Step 2: 编译**

Run: `cd goblin-settlement-mod && ./gradlew compileJava --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/SettlementSavedData.java
git commit -m "Add roster queries and assignment for professions"
```

---

### Task 4: 职业分配协调器并接入主循环

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ProfessionCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`

**Interfaces:**
- Consumes: Task 1 的 `ProfessionRules.scarcest`、Task 3 的三个方法
- Produces: `ProfessionCoordinator.tick(ServerLevel)`

- [ ] **Step 1: 实现协调器**

```java
package dev.local.goblinsettlement.colony;

import net.minecraft.server.level.ServerLevel;

/**
 * Gives every unassigned adult the trade the settlement currently has fewest of. One pass covers the
 * initial settlers, newly grown adults and residents loaded from saves that predate professions.
 */
public final class ProfessionCoordinator {
    private static final int INTERVAL_TICKS = 200;

    private ProfessionCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL_TICKS != 0) {
            return;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        if (data.settlement().isEmpty()) {
            return;
        }
        for (String residentId : data.unassignedAdultIds()) {
            data.assignProfession(residentId, ProfessionRules.scarcest(data.assignedProfessions()));
        }
    }
}
```

注意：`scarcest` 必须在循环内每次重新求值——每分配一个人，各职业的计数就变了，下一次要按新计数挑最缺的。

- [ ] **Step 2: 接入主循环**

在 `GoblinSettlement.java` 的 import 区加入 `import dev.local.goblinsettlement.colony.ProfessionCoordinator;`。

在 `tickSettlement` 内，紧跟 `CampGenerationCoordinator.tick(level);` 之后插入：

```java
        ProfessionCoordinator.tick(level);
```

放在这里的理由：营地生成的初始 8 人由它紧接着补齐职业，且在下面"聚落锚点区块不活动就整体暂停"的守卫之前——职业分配不是世界操作，不该因为区块不活跃而停摆。

- [ ] **Step 3: 编译**

Run: `cd goblin-settlement-mod && ./gradlew compileJava --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ProfessionCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Fill unassigned residents with the scarcest trade"
```

---

### Task 5: 实体暴露自己的职业

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`

**Interfaces:**
- Consumes: Task 2 的 `ResidentRecord.profession()`、Task 3 无
- Produces: `GoblinCitizenEntity.profession() -> Profession`（服务端读名册）

- [ ] **Step 1: 加方法**

放在 `isAvailableForConstruction()` 附近：

```java
    /**
     * This resident's persisted trade. Read from the roster rather than cached on the entity so a
     * reassignment in the settlement is picked up without touching loaded entities.
     */
    public Profession profession() {
        if (level() instanceof ServerLevel serverLevel) {
            return SettlementSavedData.get(serverLevel).resident(getUUID().toString())
                    .map(ResidentRecord::profession)
                    .orElse(Profession.UNASSIGNED);
        }
        return Profession.UNASSIGNED;
    }
```

补 import `dev.local.goblinsettlement.colony.Profession`（`ResidentRecord`、`SettlementSavedData` 应已 import）。

**性能说明：** 这每次调用都会线性扫一遍名册。既有代码里同一批候选已经通过 `isAvailableForConstruction()` 做过多次同类扫描，因此量级一致，无需缓存。

- [ ] **Step 2: 编译**

Run: `cd goblin-settlement-mod && ./gradlew compileJava --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java
git commit -m "Expose a resident's profession for dispatch"
```

---

### Task 6: 九个协调器按职业偏好挑人

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/farming/FarmingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/forestry/ForestryCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/tools/ToolCraftingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/food/FoodCraftingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/ConstructionCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/construction/transport/TransportCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/mining/MiningCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/economy/smelting/SmeltingCoordinator.java`

**Interfaces:**
- Consumes: Task 1 的 `ProfessionRules.matchRank`、`WorkKind`；Task 5 的 `GoblinCitizenEntity.profession()`
- Produces: 无新接口（纯内部改动）

**统一改法。** 所有挑选点原本都是：

```java
    .stream().min(Comparator.comparingDouble(goblin -> goblin.blockPosition().distSqr(anchor)))
```

改成"职业契合度优先，同档取最近"。**必须给 lambda 显式标注类型**，否则 `comparingInt` 与 `thenComparingDouble` 的泛型推断会失败：

```java
    .stream().min(Comparator
            .comparingInt((GoblinCitizenEntity goblin) ->
                    ProfessionRules.matchRank(WorkKind.FARMING, goblin.profession()))
            .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(anchor)))
```

每个文件用的 `WorkKind` 如下表。**除比较器外，谓词（`isAvailableForConstruction`、权限检查）与锚点一律不动。**

| 文件 | 位置 | WorkKind |
| --- | --- | --- |
| `FarmingCoordinator` | 作物格附近的居民选取 | `FARMING` |
| `ForestryCoordinator` | 私有 helper `nearestWorker`（三个调用点共用） | `FORESTRY` |
| `ToolCraftingCoordinator` | 仓库附近的居民选取 | `TOOL_CRAFTING` |
| `FoodCraftingCoordinator` | 仓库附近的居民选取 | `FOOD_CRAFTING` |
| `ConstructionCoordinator` | 掉落物回收路径 | `RECOVERY` |
| `ConstructionCoordinator` | 正常建造路径 | `CONSTRUCTION` |
| `TransportCoordinator` | 仓库附近的搬运工选取 | `TRANSPORT` |
| `HousingCoordinator` | 建造步的居民选取 | `HOUSING` |
| `MiningCoordinator` | 仓库附近的矿工选取 | `MINING` |
| `SmeltingCoordinator` | 私有 helper `assignWorker` | `SMELTING` |

**`ConstructionCoordinator` 的正常建造路径例外**：它已有"优先复用 `lastWorkerId`"的比较器。职业档插在**它之后、距离之前**：

```java
    .stream().min(Comparator
            .comparing((GoblinCitizenEntity goblin) ->
                    plan.lastWorkerId().equals(Optional.of(goblin.getUUID().toString())))
            .thenComparingInt(goblin ->
                    ProfessionRules.matchRank(WorkKind.CONSTRUCTION, goblin.profession()))
            .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(supply.get())))
```

**不要碰** `/goblinsettlement assign` 命令（`GoblinSettlement.java`）。它是绕过全部过滤的管理员直控旁路，保持原样。

- [ ] **Step 1: 逐个文件改比较器**

按上表改完九个文件。每个文件的 import 区补 `dev.local.goblinsettlement.colony.ProfessionRules` 与 `dev.local.goblinsettlement.colony.WorkKind`（`GoblinCitizenEntity` 应已 import，未 import 则补）。

- [ ] **Step 2: 编译**

Run: `cd goblin-settlement-mod && ./gradlew compileJava --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`。若报泛型推断错误，说明漏了 lambda 上的显式类型标注。

- [ ] **Step 3: 静态核对没有漏改**

Run:
```bash
grep -rn "comparingDouble" goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/ | grep -v "thenComparingDouble"
```
Expected: 输出为空。任何残留都是漏改的挑选点（`SettlementDemand`、`PublicWarehouseInventory` 之类若出现在结果里，逐个确认它们不是"挑居民"）。

- [ ] **Step 4: 运行全部检查**

Run: `cd goblin-settlement-mod && ./gradlew check --offline --no-daemon`
Expected: 全部 `*Check passed`，`BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/
git commit -m "Prefer a matching trade when dispatching work"
```

---

### Task 7: 工作推进按职业分档

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`

**Interfaces:**
- Consumes: Task 1 的 `ProfessionRules.workIntervalTicks`、`WorkKind`；Task 5 的 `profession()`
- Produces: 无新公开接口

- [ ] **Step 1: 加 `WorkStage -> WorkKind` 映射**

用 **switch 表达式且不加 default**，这样将来新增 `WorkStage` 常量会直接编译失败，不会被静默漏掉：

```java
    /** The kind of work a stage performs, or null for stages that are not a job. */
    private static WorkKind workKind(WorkStage stage) {
        return switch (stage) {
            case IDLE -> null;
            case FETCHING, DELIVERING, RETURNING, RECOVERING, RECOVERED, ABORTED, COMPLETE ->
                    WorkKind.CONSTRUCTION;
            case FARM_FETCHING_SEED, FARM_PLANTING, FARM_HARVESTING, FARM_RETURNING, FARM_COMPLETE ->
                    WorkKind.FARMING;
            case TOOL_FETCHING, TOOL_CRAFTING, TOOL_RETURNING, TOOL_COMPLETE -> WorkKind.TOOL_CRAFTING;
            case FOOD_FETCHING, FOOD_CRAFTING, FOOD_RETURNING, FOOD_COMPLETE -> WorkKind.FOOD_CRAFTING;
            case FORESTRY_FETCHING, FORESTRY_FELLING, FORESTRY_LOG_RETURNING, FORESTRY_SAPLING_RECOVERING,
                 FORESTRY_PROCESS_FETCHING, FORESTRY_PROCESSING, FORESTRY_PLANK_RETURNING, FORESTRY_COMPLETE ->
                    WorkKind.FORESTRY;
            case TRANSPORT_FETCHING, TRANSPORT_DELIVERING, TRANSPORT_RETURNING, TRANSPORT_COMPLETE ->
                    WorkKind.TRANSPORT;
            case HOUSING_FETCHING, HOUSING_DELIVERING, HOUSING_RETURNING, HOUSING_COMPLETE ->
                    WorkKind.HOUSING;
            case MINING_DIGGING -> WorkKind.MINING;
            case SMELT_FEEDING, SMELT_WAITING, SMELT_COLLECTING -> WorkKind.SMELTING;
        };
    }
```

**以文件中 `WorkStage` 枚举的实际常量为准**——上面按当前枚举列的，若实际常量名有出入，按实际改。

- [ ] **Step 2: 拆开两级节流**

`customServerAiStep` 现在的开头是：

```java
        if (tickCount % 10 != 0) {
            return;
        }
```

改为：

```java
        if (tickCount % REGISTRATION_INTERVAL_TICKS != 0) {
            return;
        }
```

在类里加常量 `private static final int REGISTRATION_INTERVAL_TICKS = 10;`。

然后在同一方法里，**"登记 + 取消处理"这些段落之后、"工作分支"之前**（也就是现有 `if (workStage == WorkStage.IDLE || workStage == WorkStage.COMPLETE || workStage == WorkStage.RECOVERED || workStage == WorkStage.ABORTED) { return; }` 这一行之后）插入：

```java
        WorkKind kind = workKind(workStage);
        if (kind != null && tickCount % ProfessionRules.workIntervalTicks(kind, profession()) != 0) {
            return;
        }
```

所有间隔（10/20/30）都是登记节流 10 的倍数，因此"工作节流通过"必然同时满足"登记节流通过"，不会出现工作被跳过的情况。

- [ ] **Step 3: 编译**

Run: `cd goblin-settlement-mod && ./gradlew compileJava --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 运行全部检查**

Run: `cd goblin-settlement-mod && ./gradlew check --offline --no-daemon`
Expected: 全部 `*Check passed`，`BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java
git commit -m "Slow work that does not match a resident's trade"
```

---

### Task 8: 命令里显示职业

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`

**Interfaces:**
- Consumes: Task 3 的 `assignedProfessions()`、Task 5 的 `profession()`
- Produces: 无新公开接口

- [ ] **Step 1: `workSummary()` 前缀职业**

现有实现是：

```java
    public String workSummary() {
        int goods = ...;
        return workStage + (waitReason.isEmpty() ? "" : " (" + waitReason + ")")
                + ", carrying=" + (carried.getCount() + goods);
    }
```

把结尾那一行改为：

```java
        return profession() + " " + workStage + (waitReason.isEmpty() ? "" : " (" + waitReason + ")")
                + ", carrying=" + (carried.getCount() + goods);
```

这是命令输出路径，不在每 tick 热路径上，允许它读名册。

- [ ] **Step 2: `status` 加一行职业统计**

在 `GoblinSettlement.java` 的 `status` 命令里，第二行 `sendSuccess` 之后追加一个职业统计行。实现方式：先算 `var professions = data.assignedProfessions();`，再构造字符串：

```java
                            StringBuilder trades = new StringBuilder();
                            for (var profession : Profession.values()) {
                                if (profession == Profession.UNASSIGNED) continue;
                                long held = professions.stream().filter(value -> value == profession).count();
                                if (held == 0) continue;
                                if (trades.length() > 0) trades.append(", ");
                                trades.append(profession.name().toLowerCase(java.util.Locale.ROOT)).append('=').append(held);
                            }
                            long unassigned = professions.stream().filter(value -> value == Profession.UNASSIGNED).count();
                            if (unassigned > 0) {
                                if (trades.length() > 0) trades.append(", ");
                                trades.append("unassigned=").append(unassigned);
                            }
                            String summary = trades.length() == 0 ? "no adults" : trades.toString();
                            context.getSource().sendSuccess(() -> Component.literal("Trades: " + summary), false);
```

补 import `dev.local.goblinsettlement.colony.Profession`。

- [ ] **Step 3: 编译**

Run: `cd goblin-settlement-mod && ./gradlew compileJava --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java
git commit -m "Show trades in the work and status commands"
```

---

### Task 9: 实体同步职业给客户端

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`

**Interfaces:**
- Consumes: Task 5 的 `profession()`
- Produces: `GoblinCitizenEntity.professionForRender() -> Profession`（读同步值，客户端可用）

**API 已在 1.21.11 核实：** `protected void defineSynchedData(SynchedEntityData.Builder)`；`SynchedEntityData.defineId(Class<? extends SyncedDataHolder>, EntityDataSerializer<T>)`；`EntityDataSerializers.STRING` 存在。

- [ ] **Step 1: 加同步项**

在类的字段区加：

```java
    private static final EntityDataAccessor<String> DATA_PROFESSION =
            SynchedEntityData.defineId(GoblinCitizenEntity.class, EntityDataSerializers.STRING);
```

加覆写方法（**必须调用 `super`**，`LivingEntity`/`Mob` 自己也有同步项）：

```java
    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_PROFESSION, Profession.UNASSIGNED.name());
    }
```

加读写方法：

```java
    /** The synced trade, safe to call on either side. Unknown names fall back to unassigned. */
    public Profession professionForRender() {
        try {
            return Profession.valueOf(getEntityData().get(DATA_PROFESSION));
        } catch (IllegalArgumentException exception) {
            return Profession.UNASSIGNED;
        }
    }
```

- [ ] **Step 2: 保持同步值更新**

在 `customServerAiStep` 里已经有"居民站在已认领地块上就登记"的那一段（调用 `settlementData.registerAdult(...)`）。在它**之后**加一行，让同步值跟上名册：

```java
        getEntityData().set(DATA_PROFESSION, profession().name());
```

放在同一段节流内即可（每 10 tick 一次），不需要每 tick。

补 import `net.minecraft.network.syncher.EntityDataAccessor`、`net.minecraft.network.syncher.EntityDataSerializers`、`net.minecraft.network.syncher.SynchedEntityData`。

- [ ] **Step 3: 编译**

Run: `cd goblin-settlement-mod && ./gradlew compileJava --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`。若报 `defineSynchedData` 签名不符，核对 1.21.11 的 `Entity` 上该方法的参数类型为 `SynchedEntityData.Builder`。

- [ ] **Step 4: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java
git commit -m "Sync a resident's trade to the client"
```

---

### Task 10: 按职业切换贴图

**Files:**
- Modify: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/GoblinRenderState.java`
- Modify: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/GoblinRenderer.java`
- Verify: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/defense/GolemRenderer.java`（只读核对，预期无需改动）

**Interfaces:**
- Consumes: Task 9 的 `professionForRender()`
- Produces: 无新公开接口

- [ ] **Step 1: 填充 RenderState**

```java
package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.colony.Profession;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

public final class GoblinRenderState extends LivingEntityRenderState {
    public Profession profession = Profession.UNASSIGNED;
}
```

- [ ] **Step 2: 渲染器分流**

`GoblinRenderer` 改为：

```java
package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.Profession;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;

public final class GoblinRenderer extends MobRenderer<GoblinCitizenEntity, GoblinRenderState, GoblinModel> {
    private static final Identifier DEFAULT_TEXTURE = texture("goblin");

    public GoblinRenderer(EntityRendererProvider.Context context) {
        super(context, new GoblinModel(context.bakeLayer(GoblinSettlementClient.GOBLIN_LAYER)), 0.3F);
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath("goblin_settlement", "textures/entity/" + name + ".png");
    }

    @Override
    public GoblinRenderState createRenderState() {
        return new GoblinRenderState();
    }

    @Override
    public void extractRenderState(GoblinCitizenEntity entity, GoblinRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.profession = entity.professionForRender();
    }

    @Override
    public Identifier getTextureLocation(GoblinRenderState state) {
        return switch (state.profession) {
            case FARMER -> texture("goblin_farmer");
            case FORESTER -> texture("goblin_forester");
            case MINER -> texture("goblin_miner");
            case BUILDER -> texture("goblin_builder");
            case HAULER -> texture("goblin_hauler");
            case ARTISAN -> texture("goblin_artisan");
            case SENTRY -> texture("goblin_sentry");
            case UNASSIGNED -> DEFAULT_TEXTURE;
        };
    }
}
```

- [ ] **Step 3: 核对傀儡未被波及**

读 `GolemRenderer.java`，确认它没有覆写 `extractRenderState`（因此 `GoblinRenderState.profession` 会保持默认的 `UNASSIGNED`），且 `getTextureLocation` 无条件返回 `goblin_golem.png`。**不需要改动**——若发现它依赖了 `GoblinRenderState` 的其他内容，停止并报告。

- [ ] **Step 4: 编译（含客户端源码集）**

Run: `cd goblin-settlement-mod && ./gradlew compileClientJava --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`。若任务名不存在，用 `./gradlew classes --offline --no-daemon`。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/client/
git commit -m "Pick a goblin texture from the synced trade"
```

---

### Task 11: 生成七张职业贴图

**Files:**
- Modify: `goblin-settlement-mod/tools/generate_entity_textures.py`
- Create: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/entity/goblin_farmer.png`（及其余六张，由脚本生成）

**Interfaces:**
- Consumes: 无
- Produces: 七张 64×64 PNG，文件名与 Task 10 的 `texture(...)` 调用一致

**UV 速查（来自 `GoblinModel.createLayer()`，脚本的 `box()` 已按此展开）：**

| 部位 | box 参数 | 正面 | 顶面 | 背面 |
| --- | --- | --- | --- | --- |
| 头 | `(0, 0, 8, 8, 8)` | `x 8..15, y 8..15` | `x 8..15, y 0..7` | `x 24..31, y 8..15` |
| 身体 | `(16, 16, 6, 8, 4)` | `x 20..25, y 20..27` | `x 20..25, y 16..19` | `x 30..35, y 20..27` |
| 手臂 | `(40, 16, 3, 8, 3)` | `x 43..45, y 19..26` | — | — |

- [ ] **Step 1: 参数化贴图函数**

把 `goblin()` 改成接受一组配件绘制函数，并新增七个配件函数。基础身体**保持逐像素不变**（现有 `goblin()` 函数体原样保留为基底），只在最后叠加配件：

```python
def straw_hat(image):
    brim, crown = (214, 187, 108), (190, 158, 82)
    image.rect(8, 0, 8, 8, crown)
    image.rect(8, 8, 8, 2, brim)


def shoulder_strap(image):
    strap = (92, 63, 38)
    image.rect(20, 20, 6, 2, strap)
    image.rect(24, 22, 2, 6, strap)


def headlamp(image):
    band, lamp, glow = (58, 55, 52), (245, 218, 91), (255, 255, 210)
    image.rect(8, 8, 8, 1, band)
    image.rect(11, 9, 2, 2, lamp)
    image.rect(11, 9, 1, 1, glow)


def tool_belt(image):
    belt, buckle, head = (74, 48, 28), (198, 166, 74), (150, 152, 156)
    image.rect(20, 24, 6, 2, belt)
    image.rect(22, 24, 2, 2, buckle)
    image.rect(24, 26, 2, 2, head)


def backpack(image):
    pack, strap = (110, 82, 48), (74, 55, 32)
    image.rect(30, 20, 6, 8, pack)
    image.rect(30, 20, 6, 1, strap)
    image.rect(31, 24, 4, 3, strap)


def apron_and_goggles(image):
    apron, glass, frame = (58, 62, 70), (137, 199, 208), (54, 50, 46)
    image.rect(21, 22, 4, 6, apron)
    image.rect(20, 27, 6, 1, apron)
    image.rect(9, 10, 3, 2, frame)
    image.rect(13, 10, 3, 2, frame)
    image.rect(10, 10, 1, 1, glass)
    image.rect(14, 10, 1, 1, glass)


def helmet(image):
    metal, rim, crest = (146, 151, 158), (96, 100, 108), (156, 66, 60)
    image.rect(8, 0, 8, 8, metal)
    image.rect(8, 8, 8, 2, rim)
    image.rect(10, 0, 2, 2, crest)
```

把原 `goblin()` 改为 `def goblin(accents=()):`，函数体末尾 `return image` 之前插入：

```python
    for accent in accents:
        accent(image)
```

把 `__main__` 段改为：

```python
if __name__ == "__main__":
    goblin().save(ROOT / "goblin.png")
    goblin((straw_hat,)).save(ROOT / "goblin_farmer.png")
    goblin((shoulder_strap,)).save(ROOT / "goblin_forester.png")
    goblin((headlamp,)).save(ROOT / "goblin_miner.png")
    goblin((tool_belt,)).save(ROOT / "goblin_builder.png")
    goblin((backpack,)).save(ROOT / "goblin_hauler.png")
    goblin((apron_and_goggles,)).save(ROOT / "goblin_artisan.png")
    goblin((helmet,)).save(ROOT / "goblin_sentry.png")
    golem().save(ROOT / "goblin_golem.png")
```

- [ ] **Step 2: 运行脚本**

Run: `cd goblin-settlement-mod && python tools/generate_entity_textures.py`
Expected: 无输出，正常退出。`goblin.png` 与 `goblin_golem.png` 内容**不得变化**（基底未动），新增七张。

- [ ] **Step 3: 核对产物**

Run:
```bash
cd goblin-settlement-mod && for f in src/main/resources/assets/goblin_settlement/textures/entity/goblin_*.png; do
  python -c "import struct,sys;d=open(sys.argv[1],'rb').read();print(sys.argv[1],struct.unpack('>II',d[16:24]))" "$f"
done
```
Expected: 每张都打印 `(64, 64)`，且九个文件名齐备。

再确认 `git status` 中 `goblin.png` 与 `goblin_golem.png` 未出现在改动列表里；若出现，说明基底被误改，撤销重做。

- [ ] **Step 4: 提交**

```bash
git add goblin-settlement-mod/tools/generate_entity_textures.py \
        goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/entity/
git commit -m "Draw an original texture per trade"
```

---

### Task 12: 完整构建、全部检查与文档收尾

**Files:**
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只许在末尾追加**，不得改动、删除或重排既有内容）

**Interfaces:**
- Consumes: 前 11 个任务的全部产出
- Produces: 无

- [ ] **Step 1: 完整离线构建**

Run: `cd goblin-settlement-mod && ./gradlew build --offline --no-daemon`
Expected: `BUILD SUCCESSFUL`；7 个检查依次打印 `*Check passed`（原有 6 个 + `ProfessionRulesCheck`）；生成 `build/libs/goblin-settlement-0.1.0.jar`。

- [ ] **Step 2: 核对产物内容**

Run:
```bash
cd goblin-settlement-mod && unzip -l build/libs/goblin-settlement-0.1.0.jar | grep -E "colony/(Profession|WorkKind|ProfessionRules|ProfessionCoordinator)" && unzip -l build/libs/goblin-settlement-0.1.0.jar | grep -c "textures/entity/goblin"
```
Expected: 四个新类都在；贴图计数为 9。

- [ ] **Step 3: 更新 `CURRENT_STATUS.md`**

更新日期、阶段定位（阶段 4 的职业系统从"仍缺"改为已接入候选）、本轮接入内容、验证进展（构建 + 检查，**并明确写出未做游戏内验证**）、尚需实现（职业熟练度、名额、派工服务收口、哨卫工作、儿童外观、手持工具）。

- [ ] **Step 4: 追加 `UpdateLog.md`**

在文件**末尾追加**新段，标题格式为 `## [起止时间 +08:00] 第N轮：标题`，每条记录带 `[时间 +08:00]` 前缀，记录：设计文档落点、各任务改动、构建与检查结果、未完成事项（未做游戏验证）。不得触碰既有内容。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-plan/CURRENT_STATUS.md goblin-settlement-plan/UpdateLog.md
git commit -m "Record the profession system round"
```

- [ ] **Step 6: 推送**

```bash
git push
```
Expected: `claude/settlement-first-pass` 更新。若因网络失败，报告实际报错并停止——不要重试循环，也不要绕过任何审查。

---

## 自查记录

**Spec 覆盖：** 设计文档 13 节逐一对应——§2.1 偏好与速度（Task 6、7）、§2.2 未定职即通才（Task 1 的 `employs` 规则 + check）、§2.3 哨卫按通才对待（Task 1 的 check 覆盖）、§3 数据模型（Task 2、3）、§4 WorkKind 与 ProfessionRules（Task 1）、§5 派工偏好（Task 6）、§6 工作速度（Task 7）、§7 职业分配（Task 3、4）、§8 显示（Task 8）、§9 外观（Task 9、10、11）、§10 兼容性（Task 2 的旧存档用例 + `SCHEMA_VERSION` 约束）、§11 验证（各任务的 check + Task 12）、§12 范围外（未建任何对应任务）、§13 风险（见下）。

**与设计文档的两处偏离，均为改强：**
1. §6.1 原写"映射不到的阶段按对口处理"；计划改用**无 default 的 switch 表达式**，新增常量会编译失败而不是静默降级——比原设计更安全。
2. §4.2 的 `scarcest` 在计划里一并纳入 `ProfessionRules` 并写进检查，设计文档未单独列出，属同类纯规则。

**风险（设计文档 §13 的三条已在计划内处置）：**
- 实体同步是工程首次使用——Task 9 已核实 1.21.11 的确切 API 签名。
- 改动面宽而浅——Task 6 的 Step 3 用 grep 兜底漏改。
- 速度档被走路稀释——属设计取舍，已在设计文档 §6.2 记录，不在实现中处理。
