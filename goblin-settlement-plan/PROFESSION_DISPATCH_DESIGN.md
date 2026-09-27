# 派工服务收口设计

更新日期：2026-09-27。依据：PROFESSION_DESIGN.md §12「把 9 个协调器重复的挑人代码重构成统一派工服务」；§5「派工偏好」；§13 风险「改动面宽而浅」。

## 1. 目标与范围

**目标**：把散在 8 个协调器里**逐字相同**的挑人代码收成一个服务，让"怎么挑工人"只有一个落点——这也是下一轮"职业名额"唯一的自然落点。

**现状（已逐处核对）**：10 处挑人代码里 **8 处完全同形**：

```java
level.getEntitiesOfClass(GoblinCitizenEntity.class, new AABB(anchor).inflate(16.0), <谓词>)
     .stream().min(Comparator
             .comparingInt((GoblinCitizenEntity goblin) ->
                     ProfessionRules.matchRank(kind, goblin.profession()))
             .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(anchor)))
```

只有三件事不同：`WorkKind`、锚点 `BlockPos`、以及**谓词**。

**本轮做**：新增 `colony/WorkerDispatch`，把 8 处规范形态改为调用它。

**本轮不做**：
- **两个异形不动**（见 §3）。
- **不修**谓词不一致（见 §4）——那是行为变化。
- 职业名额约束（下一轮；落点就是本服务）。

## 2. `colony/WorkerDispatch`

与 `WorkKind` / `ProfessionRules` 同包，因为它是招聘策略而非某一工种的实现。

```java
public final class WorkerDispatch {
    /** The search radius every caller already used. */
    public static final double SEARCH_RADIUS = 16.0;

    /**
     * A resident is eligible to be hired when they are free and allowed to change blocks where they
     * stand. Five of the eight callers check the second half; three do not (see the design's note on
     * that inconsistency) -- so it stays a predicate the caller chooses, not a hidden default.
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

**为什么谓词由调用方给而不是内置**：8 处里 5 处检查站位权限、3 处不检查。把权限检查设成默认会让 3 处行为变化；设成"必须显式传入"则把差异摆在每个调用点上，重构后一眼能看出哪几处没检查。这是**保持行为不变并且让既有不一致更显眼**两件事同时做到的唯一写法。

## 3. 两个异形：保留原代码

| 位置 | 为什么不入围 |
| --- | --- |
| `ConstructionCoordinator` 掉落物回收（约第 93 行） | 锚点是**掉落实体的坐标**（`item.position()`），度量是 `goblin.distanceToSqr(item)`——到实体连续坐标的距离，不是到方块整点坐标的距离。要纳入就得给服务加"锚点类型"与"度量函数"两个只在它身上有意义的参数。 |
| `ConstructionCoordinator` 施工派工（约第 123 行） | 在 `matchRank` **之前**多一个"优先续用上一个工人"的键（`plan.lastWorkerId()` 命中记 0，否则记 1）。这是**真实功能**（让同一栋楼的活尽量由同一个人接着干），不是巧合；要纳入同样得加一个只有它用的参数。 |

两处都保留原代码，并在原处加一行注释说明**为何不经过统一服务**，避免后来者以为是漏改。

## 4. 已知不一致：不修，但记录

8 处里 **5 处**（Forestry / Mining / Smelting / FoodCrafting / ToolCrafting）要求"工人站位允许改动方块"，**3 处**（Housing / Transport / Farming）不要求。

- 无法判断这是刻意还是遗漏：`WorkerModificationPermission.check` 的语义是"这个位置能不能被本聚落改动"，工人站在未申报区块里也能正常施工（施工位置另有检查），所以不检查**未必是错的**。
- 本轮**不改**任何一处——改了就是行为变化，而 10 处挑人行为**从未在游戏内验证过**，没有手段发现改坏。
- 重构后这处不一致从"埋在 8 段几乎相同的 lambda 里"变成"每个调用点显式二选一"，**更容易被发现**。这是重构的附带收益，不是修复。

## 5. 改造清单

| 文件 | WorkKind | 锚点 | 谓词 |
| --- | --- | --- | --- |
| `construction/transport/TransportCoordinator` | `TRANSPORT` | `warehouse` | `isAvailableForConstruction` |
| `farming/FarmingCoordinator` | `FARMING` | `crop` | `isAvailableForConstruction` |
| `housing/HousingCoordinator` | `HOUSING` | `warehouse.orElseThrow()` | `isAvailableForConstruction` |
| `forestry/ForestryCoordinator` | `FORESTRY` | `target` | `permitted(level, id)` |
| `mining/MiningCoordinator` | `MINING` | `warehouse` | `permitted(level, settlementId)` |
| `economy/smelting/SmeltingCoordinator` | `SMELTING` | `warehouse` | `permitted(level, settlementId)` |
| `economy/food/FoodCraftingCoordinator` | `FOOD_CRAFTING` | `warehouse` | `permitted(level, settlementId)` |
| `economy/tools/ToolCraftingCoordinator` | `TOOL_CRAFTING` | `warehouse` | `permitted(level, settlementId)` |

## 6. 验证方式：本轮没有新增检查

**必须说清楚**：这是一次纯重构，**不会新增任何独立检查**。

- `WorkerDispatch.nearest` 需要 `ServerLevel` 与真实实体，**纯函数层测不到**（项目的检查全是无 JUnit 的纯计算检查）。
- 10 处挑人行为**从未在游戏内验证过**；PROFESSION_DESIGN §13 已记「"职业偏好是否真的生效"没有自动化手段验证，只能靠代码审查」。

因此本轮的"零行为变化"**只能靠逐处对照代码确认**：改造前后的三段（盒子、谓词、比较器）必须逐字对应。日志里要写明这是"无新增覆盖的重构"，不得记成"已验证"。

验证手段：完整离线构建 + 既有 12 项独立检查全部通过（它们覆盖不到本次改动，但能保证没有编译级与跨模块的连带破坏）。

## 7. 风险

- **无自动证据**：见 §6。这是本轮最大的性质，不是缺陷但必须如实记录。
- **编译级连带**：8 个文件新增 `WorkerDispatch` 导入，且部分文件会因此不再使用 `java.util.Comparator`、`net.minecraft.world.phys.AABB`、`ProfessionRules`、`WorldModificationPermission` 中的若干个。Java 不会因未使用导入报错，所以必须**逐文件核对后手动删除**，否则留下死导入。
- **`HousingCoordinator` 的锚点是 `warehouse.orElseThrow()`**：它在 `if (warehouse.isPresent())` 块内，展开调用时若把 `orElseThrow()` 提到块外会改变语义（提前抛异常）。改造时必须保持它在原位置。
- **包依赖**：`colony.WorkerDispatch` 会导入 `citizen.GoblinCitizenEntity`，而 `citizen` 已经导入 `colony.SettlementSavedData`，形成包级循环。工程里已有同类循环（`housing` 与 `citizen` 互相导入），Java 允许，但这是既有的架构特征而非本服务引入的新问题——记录在案。
