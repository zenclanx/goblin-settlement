# 住宅材料泛化（工人按每步声明的物品施工）设计

更新日期：2026-09-27。依据：TECH_DESIGN.md 第 6 节；HOUSING_BLUEPRINT_DESIGN.md §8 第 1 条（本轮即该项）；HOUSING_DESIGN.md §3.1（进度由世界推导，不存游标）。

## 1. 目标与范围

**目标**：让住宅工人按蓝图每步声明的方块/物品取料、交付、放置，使第四十四轮留下的**过渡约束**（数据只许 `minecraft:oak_planks`）可以删除。做完这项，"材料清单迁到受校验的数据文件"才算真正完成。

**本轮做**：
- 删除纯层的过渡约束及其检查用例。
- 仓库层新增按物品通用取仓的方法。
- 协调器层：按位置把"这一步"找回来，用它决定领料物品、仓库选取、领料闸与放置方块。
- 实体层：`HOUSING_FETCHING` / `HOUSING_DELIVERING` 两个阶段的橡木木板假设改为按步骤声明的物品。

**本轮不做**（明确边界，避免误记为已完成）：
- **通用施工路径**：`GoblinCitizenEntity.fetchMaterial` / `placeMaterial`（WorkStage FETCHING/DELIVERING/COMPLETE，第 1899/1914/1933 行附近）与 `carriedOakPlanks()`（供 `ConstructionCommands` 显示）仍是橡木木板专用。它们服务 `ConstructionCoordinator` 那条线，不在住宅切片内。
- **道路交付与归还**：第 723 行（`TRANSPORT_DELIVERING` 认木板/原木/栅栏/火把）与第 1874 行（归还合并）不在本轮改写之列；其中第 1874 行位于**住宅与道路共用**的 `returnCarriedToSupply`，本轮会改它，但改的是"合并条件"这一处（见 §5），不改道路的材料模型。
- **现有几何的材质不变**：五级蓝图仍全部使用橡木木板。本轮交付的是**能力**，不是改材质；真实数据仍只覆盖单一材质。
- 数据包重载、其余内容数据化。

## 2. 纯层：删除过渡约束

`BlueprintSet` 中删除：

- 常量 `TRANSITIONAL_BLOCK`；
- `validateSteps` 里 `else if (!TRANSITIONAL_BLOCK.equals(step.block()))` 那条分支及其文案。

保留的分支只有"该方块标识不在已知集合内 → 非法"。**过渡约束的消失不放松任何实质校验**：标识仍必须在真实注册表里，且其物品形态必须存在（这条判定在加载器里，见 HOUSING_BLUEPRINT_DESIGN §4.1）。

`BlueprintCheck` 中删除 `checkTransitionalBlockRule()` 及其调用，新增两条：一个**已知但不是橡木木板**的方块被接受（这正是本轮 TDD 的 RED）；一个**不在已知集合**的标识仍被拒绝。

`BlueprintCheck.checkSteps` 里原本断言"真实数据每一步都是 `minecraft:oak_planks`"的那行，放宽为"每一步的方块都在已知集合内"——真实数据仍是橡木木板，但**不再被要求必须是**。

## 3. 仓库层：按物品通用取仓

`PublicWarehouseInventory` 新增：

```java
/** The first accessible warehouse holding at least one of the given item. */
public static Optional<BlockPos> firstHolding(ServerLevel level, SettlementSavedData data, Item item) {
    return data.warehouses().stream().filter(pos -> countAt(level, data, pos, item) > 0).findFirst();
}
```

`firstWithOakPlank` 改为 `return firstHolding(level, data, Items.OAK_PLANKS);`，既有调用方（`ConstructionCoordinator`、尚未改到的住宅路径）行为不变。**不新增第二个计数实现**：`countOf` / `countAt` 已有通用版本，直接用。

## 4. 协调器层：把"这一步"找回来

`HousingCoordinator` 新增两个方法：

```java
/** The step this home expects at this site, matched by position; empty when nothing matches. */
static Optional<HousingBlueprints.ResolvedStep> stepAt(ServerLevel level, HousingSavedData.Home home,
                                                       BlockPos site)

/** The step the worker holding this bed and site was sent to build; empty when the assignment is stale. */
public static Optional<HousingBlueprints.ResolvedStep> assignedStep(ServerLevel level, BlockPos bed,
                                                                    BlockPos site)
```

`stepAt` 遍历 `HousingBlueprints.steps(home.capacityTarget(), home.qualityTarget())`，用既有的 `position(level, home.bed(), home.variant(), step)` 比对坐标。**按位置匹配而不是复用 `nextSite`**：工人手里的 `buildPos` 是派工时定下的，中途若被别的工人建掉或目标变化，`nextSite` 会指向别处，而"我这一步是什么"必须仍按**我的位置**回答；匹配不上就说明派工已失效，工人应归还材料而不是拿旧物品硬干。

`assignedStep` 按床找到 home 后委托 `stepAt`。

三处改动：

1. **`placeByResident`**：目前硬校验 `carried.is(Items.OAK_PLANKS)` 并放置 `Blocks.OAK_PLANKS`。改为先用 `stepAt` 取期望步骤；步骤不存在（派工失效）或 `!carried.is(step.item())` 即拒绝；放置 `step.block().defaultBlockState()`，并仍以 `level.getBlockState(site).is(step.block())` 复核。其余守卫（`assignedSite`、`carried.getCount() == 1`、距离、`MOB_GRIEFING`、两处权限、`site` 为空气）一律保留。
2. **`advance` 的领料闸**：`stock.oakPlanks() <= reserve` 改为 `PublicWarehouseInventory.countOf(level, data, step.get().item()) <= reserve`，`stock.complete()` 保留（它表达"仓库快照完整"，与物品无关）。`step` 由 `stepAt(level, home, site)` 取得，取不到则直接不派工。
3. **仓库选取**：`firstWithOakPlank(level, data)` 改为 `firstHolding(level, data, step.get().item())`。

## 5. 实体层：工人按声明的物品施工

`GoblinCitizenEntity` 的住宅分支（`HOUSING_FETCHING` / `HOUSING_DELIVERING`，约第 786–816 行）改为：

- **`HOUSING_FETCHING`**：先 `HousingCoordinator.assignedStep(level, housingBed, buildPos)`。为空 → 转 `HOUSING_RETURNING`（带着的东西还回去），`waitReason` 写"派工已变"。否则在 `supplyPos` 里找 `step.item()` 的槽位取 1 件；原来找 `Items.OAK_PLANKS` 的那两处判断改为按该物品判断。找不到时的 `waitReason` 保持原文案不变（不拼接物品名——`Item` 的 `toString` 在本映射下不保证是可读的资源名）。
- **`HOUSING_DELIVERING`**：先 `assignedStep`；为空 → 转 `HOUSING_RETURNING`。`level.getBlockState(buildPos).is(Blocks.OAK_PLANKS)` 改为 `.is(step.block())`。放置仍走 `placeByResident`（它现在自己也校验物品与方块，形成双保险）。
- **`returnCarriedToSupply`**（住宅与道路共用）：合并条件 `existing.is(Items.OAK_PLANKS)` 改为 `existing.is(carried.getItem())`。工人一次只带 1 件，放空槽本来也能work，但原写法对**非橡木材质**会绕过合并分支、并在"仓库只剩同类物品的未满堆"时表现得像仓库满。这条对道路路径（会带原木/栅栏/火把）同样是更正。

**不新增持久字段**：物品每次由 `assignedStep` 从世界重推，与 HOUSING_DESIGN §3.1「不保存游标」同源。实体卸载再加载后无需恢复任何额外状态。

## 6. 为什么按位置匹配是对的

`assignedSite`（既有）也是"重推 `nextSite` 再比对"，但它回答的是"我是否仍合法持有这步"。本轮需要的是"**我这一步要什么材料**"，两者的问题不同：前者在目标前进后会失效，后者必须锚定工人已经站到的那个坐标。因此 `stepAt` 独立于 `nextSite`，只在坐标上匹配该房子当前两轴目标下的步骤集合。

**边界情形**：某一步的坐标可能同时属于不同阶段（例如 `quality` 级与 `mature` 级都在 y=4 上放东西，但坐标不同）。若两级的步骤坐标真的重复，`stepAt` 返回**先出现的那个**（容量级优先，与 `steps()` 的合成顺序一致）——与"先建容量、后提品质"的顺序一致，且当两级坐标重复时两者的方块来自同一份数据，不会出现歧义。

## 7. 验证方式

- **TDD 的 RED**：`BlueprintCheck` 新增"一个已知但非橡木木板的方块被接受"，在删除过渡约束前必然失败于该约束；删除后转绿。
- 既有 12 项独立检查全部保持通过；`checkSteps` 的步数断言（21/92/102/111/121）与生成脚本输出不变——**本轮不改几何**。
- 完整离线构建 + 全部独立检查。**不做游戏内验证**（按用户约定）。
- 工人路径（实体层的三段改动）**没有纯函数可测**，只能靠编译与既有检查；这是本轮最大的验证空白，必须写进状态文档。

## 8. 风险

- **改动全部落在从未游戏内验证过的工人路径上**：`HOUSING_FETCHING` / `HOUSING_DELIVERING` / `returnCarriedToSupply` 是居民实际干活的地方，编译通过与纯函数检查**不能**证明它在游戏里跑得对。这是本轮最需要在统一测试时优先观察的部分。
- **能力未被真实数据检验**：现有几何仍是清一色橡木木板，所以"能放别的材质"只被 `BlueprintCheck` 的合成用例覆盖，没有真实蓝图验证过。要真正验证，需要一份使用第二种材质的蓝图——本轮不提供（改几何材质是玩法决定，不是本项要求）。
- **`assignedStep` 是每 tick 的扫描**：每栋房子十几到二十几步，且每次调用按床线性找房子。工人数量与房子数量都有限，开销可忽略；但若将来蓝图规模大幅增长，这里是需要重新审视的热点。
- **过渡约束删除后，错误材质的失败方式变了**：以前是启动时校验直接拒绝，现在会进入运行时——仓库没有该物品时工人只会反复 `waitReason`，不会崩。这是正确的"失败后暂停"行为，但意味着**材质写错在游戏里表现为卡住而不是报错**，排查需要看 `waitReason`。
