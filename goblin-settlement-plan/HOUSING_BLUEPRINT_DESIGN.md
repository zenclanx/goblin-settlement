# 住宅蓝图数据化（几何迁到受校验的数据文件）设计

更新日期：2026-09-27。依据：TECH_DESIGN.md 第 6 节「内容数据与校验」、第 9 节；HOUSING_DESIGN.md 第 3.1 节（进度由世界推导）、§9（本项原列范围外）。

## 1. 目标与范围

**目标**：把住宅五级几何（现为 `HousingRules.blueprints()` 里 121 步的循环硬编码）迁到一份受校验的数据文件；数据里每一步自带方块标识；进度判定与站点检查改读该数据，从而**换形状不再需要改代码**——这是 HOUSING_DESIGN §9「更多蓝图」的前置。

**本轮做**：
- 数据模型、数据文件、四条校验、加载与失败策略。
- `HousingRules.Step` 带上方块标识，`blueprints()`/`STAGES` 删除。
- `HousingCoordinator` 中三处硬编码 `Blocks.OAK_PLANKS`（进度判定、阶段完成判定、站点占用判定）改读该步声明的方块。

**本轮不做**（明确切分，见 §8）：

- **材料泛化**：工人的取料/携带/交付/放置路径（`GoblinCitizenEntity` 约 10 处硬编码橡木木板）与仓库材料闸保持不动。本轮数据因此受一条**过渡约束**：只允许 `minecraft:oak_planks`，其他材质在校验时判非法。下一轮泛化材料时删除该约束。
- 其他内容数据化（傀儡参数、职业倾向、名字、对话、可调平衡数值的其余部分）。
- 数据包重载（`/reload`）：本轮只支持随 jar 打包、服务端启动时读一次。

## 2. 数据模型（纯层）

新增 `housing/BlueprintSet.java`，只含 record 与纯逻辑，不吃 Minecraft 类型：

```java
// 复用 HousingRules.Step（本轮为它加上 block 字段，见 §6），
// 不另造一个字段完全相同的步骤类型——那正是本项目刚在床位判据上修掉的分叉。
public record Stage(String id, String requires, List<HousingRules.Step> steps) { }
public record BlueprintSet(List<Stage> capacity, List<Stage> quality,
                           int reserveBasic, int reserveExpanded) { }
```

`Stage` 嵌套在 `BlueprintSet` 内（`BlueprintSet.Stage`），与 `HousingRules.Step` 一样是纯 record。

- 每个 record 配 `Codec`，与工程既有写法一致（`com.mojang.serialization` + `JsonOps`；`HousingSavedData.Home` 是同类先例）。
- `requires` 是**同链内显式的上一级 id**：容量链 `shelter` 为空、`cabin`→`shelter`、`expanded`→`cabin`；品质链 `quality` 为空、`mature`→`quality`。两链各有各的链首——**跨链引用非法**（§4.2）。它的唯一目的是让「升级链无环」**可校验**——纯有序数组天然无环，校验会变成空转。
- `reserveBasic`/`reserveExpanded` 从 `HousingRules` 常量迁入数据（TECH_DESIGN 第 6 节把「可调平衡数值」列入数据）。

**阶梯长度本轮仍是代码常量**：`capacity.size()` 必须等于 `HousingRules.MAX_CAPACITY_TARGET + 1`（3），`quality.size()` 必须等于 `MAX_QUALITY_TARGET`（2），由 §4.4 校验钉死。理由：这两个常量被存档校验（`Home` 的 codec）与旧档迁移表（HOUSING_DESIGN §3.2）引用，把长度也数据化会让旧存档的合法性依赖内容文件。**这是刻意的边界**，不是遗漏。

## 3. 数据文件

- 位置：`goblin-settlement-mod/src/main/resources/data/goblin_settlement/housing_blueprints.json`
  （`data/<modid>/` 是 Minecraft 约定；本工程 modid 为 `goblin_settlement`，见 `fabric.mod.json`。本轮**不走数据包系统**，该路径只是随 jar 打包的资源。）
- 形态：

```json
{
  "reserve_basic": 8,
  "reserve_expanded": 24,
  "capacity": [
    { "id": "shelter", "steps": [ { "x": -1, "y": 0, "z": -1, "block": "minecraft:oak_planks" }, ... ] },
    { "id": "cabin", "requires": "shelter", "steps": [ ... ] },
    { "id": "expanded", "requires": "cabin", "steps": [ ... ] }
  ],
  "quality": [
    { "id": "quality", "steps": [ ... ] },
    { "id": "mature", "requires": "quality", "steps": [ ... ] }
  ]
}
```

- **由脚本生成，不手抄**：新增 `goblin-settlement-mod/tools/generate_housing_blueprints.py`，逐字复刻现有 `blueprints()` 的循环（含 `cabin` 在 `(0,0,2)`/`(0,1,2)` 留门洞的那处 `x != 0 || y == 2` 跳步），输出上述 JSON。工程已有 `tools/generate_entity_textures.py` 这一先例。
- **回归网**：生成后必须核对外层五级的步数仍为 `21 / 92 / 102 / 111 / 121`（即 `HousingRulesCheck.checkSteps` 现有的断言）。这五条断言**不改动**，它们就是"几何搬迁无损"的证据。

## 4. 校验规则

校验器是纯逻辑：`BlueprintSet.validate(Set<String> knownBlocks)` 收下一份"已知方块标识集合"再判，从而自己不吃 Minecraft 类型；该集合由加载器遍历 `BuiltInRegistries.BLOCK` 建出（**不用 `Identifier.parse`**，规避本版本字符串解析 API 的不确定性）。

### 4.1 资源标识存在

- 每个 `block` 语法合法（`namespace:path`），且属于 `knownBlocks`；
- 该方块的**物品形态必须存在**（工人搬的是物品）——这项判定需要注册表（`block.asItem() != Items.AIR`），因此落在加载器里，不进纯校验器。

### 4.2 升级链无环

- 同一链内 `id` 唯一；
- `requires` 为空者恰好一个（链首）；非空者必须指向**同一链内更早出现**的某一级 id——跨链引用（容量级依赖品质级）判非法，否则两条轴的构建顺序会出现歧义；
- 自链首沿 `requires` 回溯，用已访问集合检环与悬空引用。

### 4.3 蓝图有合法入口

规则（纯 BFS，可独立测）：

1. 取该级**及其 `requires` 前序在该链上的所有步骤**（房子是逐级长出来的，所以要按累计形状判，不是只看本级）；
2. 把其中 `y ∈ {0,1}` 的步骤按 `(x,z)` 记为"阻挡格"；
3. 在"包围盒外扩一圈"的网格上，从边界起做 4-邻接洪水填充，只走非阻挡格；
4. **锚点列 `(0,0)` 不可达 → 判"封死"，非法。**

这条同时覆盖三种情形：`shelter` 无墙（通达）、`cabin` 在 `(0,·,2)` 留两格门洞（通达）、人为砌死（不通达）。品质级的累计集合里没有 `y ∈ {0,1}` 的步骤（`quality` 全是 `y=4`；`mature` 只有 `x=-3` 与 `y=4` 两段，不封口），因此**天然通过**——不做特例分支。

### 4.4 数值不越界

- `capacity.size() == MAX_CAPACITY_TARGET + 1`，`quality.size() == MAX_QUALITY_TARGET`；
- 每个坐标落在蓝图的**声明盒**内：`-3 <= x <= 3`、`0 <= y <= 4`、`-2 <= z <= 2`（覆盖现有全部 121 步的实际范围，作为上限常量写在校验器里）；
- **同一级内坐标不重复**（现有 `quality` 级特意跳过重复的中心格 `(0,4,0)`，数据里也必须只有一个）；
- `reserveBasic`、`reserveExpanded` 为正整数。

### 4.5 过渡约束（本轮专用，下一轮删除）

所有 `block` 必须是 `minecraft:oak_planks`。这条**不是**设计意图，而是本轮与"工人路径仍写死橡木木板"保持一致的安全带：数据一旦写了别的材质，工人放不下对应的物品，会出现"目标方块与携带物品不符"的隐性错配。校验器报错文案要写清这是过渡约束、以及下一轮会解除。**下一轮材料泛化时，这条连同它的检查用例一起删除。**

## 5. 加载与失败策略

- 新增 `housing/HousingBlueprints.java`（MC 层）：在 `GoblinSettlement.onInitialize()` 注册 `ServerLifecycleEvents.SERVER_STARTING`（Fabric API；本工程目前无任何服务端生命周期钩子），在服务端启动时：
  1. 从 classpath 读 `data/goblin_settlement/housing_blueprints.json`；
  2. 用 `JsonOps` + `BlueprintSet.CODEC` 解码；
  3. 从注册表取两份已知标识集合，跑 §4 全部校验；
  4. 把每步的字符串标识**一次性解析**成运行时表示 `ResolvedStep(int x, int y, int z, Block block, Item item)`，缓存起来——避免每 tick 每步做一次注册表查询。
- **失败策略**：任一步失败 → 记一条含具体原因的清晰错误日志，令 `HousingBlueprints.available()` 为假。`HousingCoordinator.tick` 在不可用时直接返回，**住宅建造整体停摆**；不崩服，也**不回退到硬编码几何**（硬编码已删，回退会掩盖错误）。其余系统照常运行。符合 TECH_DESIGN 第 40 行「失败后暂停或重新规划，不重复执行已有成果」。
- `available()` 为假时，`goblinsettlement status` 增加一行提示蓝图不可用，便于玩家与开发者在游戏内直接看到原因。

## 6. 与既有代码的接线

| 位置 | 现状 | 改为 |
| --- | --- | --- |
| `HousingRules.Step` | `record Step(int x, int y, int z)` | `record Step(int x, int y, int z, String block)` |
| `HousingRules.blueprints()` / `STAGES` / `stages()` | 硬编码五级几何 | **删除**；步骤合成移到 `BlueprintSet.steps(capacityTarget, qualityTarget)` |
| `HousingRules.RESERVE_BASIC` / `RESERVE_EXPANDED` | 常量 | **删除**，改读 `BlueprintSet` |
| `HousingCoordinator.nextSite` | `is(Blocks.OAK_PLANKS)` 判已建 | 读 `ResolvedStep.block()` |
| `HousingCoordinator.stageFullyBuilt` | 同上，遍历 `HousingRules.stages()` | 读 `HousingBlueprints.stages()` + `ResolvedStep.block()` |
| `HousingCoordinator.siteSuitable` | `state.is(Blocks.OAK_PLANKS)`；遍历 `HousingRules.stages()` | 同上 |
| `HousingCoordinator` 领料闸 `stock.oakPlanks() <= reserve` | 常量 reserve | 读 `BlueprintSet` 的 reserve 值（**物品仍限定橡木木板**，见 §4.5） |

依赖方向保持单向、无环：`BlueprintSet`（纯）→ 只依赖 `HousingRules.Step`；`HousingBlueprints`（MC）→ 依赖 `BlueprintSet` 与注册表；`HousingCoordinator` → 依赖 `HousingBlueprints`。`HousingRules` 不反向依赖 `BlueprintSet`，也不持有任何可变静态状态。

**`HousingRules.firstUnbuilt` 保持不动**：它只在 `HousingRulesCheck` 里被使用，主代码未使用（主代码的进度推导在 `HousingCoordinator.nextSite`）。这是既有冗余，本轮**不清理**以免扩大范围；记在 §7。

## 7. 风险

- **进度推导的基准中途改变**：进度靠"这格是不是对应方块"推导（HOUSING_DESIGN §3.1）。数据里换了方块或坐标，已有房子的"已建/未建"判定就会变，可能出现重复扣料或漏建。本轮靠两点收敛：数据由脚本从现有几何逐字生成、且 `checkSteps` 的步数断言不变；**但已建成房子的方块构成若与数据不符，本轮不做迁移**。改为启动时只读一次也降低了这项风险（同一会话内基准固定）。
- **`firstUnbuilt` 是死代码**：见 §6 末。既有冗余，已记，未处理。
- **占地盒是校验用的近似**：`-3..3 / 0..4 / -2..2` 取自现有几何的实际范围，不是从数据推导的。新增更大的蓝图会先撞上这条校验并被明确拒绝——这正是"数值不越界"的意图，但意味着扩大蓝图尺寸必须同时改这个常量。
- **过渡约束会掩盖真实需求**：§4.5 让本轮数据无法表达多材质，"材料清单数据化"只完成了一半。必须在下一轮解除，否则这项需求会被误记为已完成。
- **失败即停摆**：蓝图不可用时住宅建造整体停止。对随 jar 打包的数据文件而言，非法只可能是开发期错误，因此这是可接受的；但它意味着**一个笔误会让住宅系统静默停摆到有人看日志为止**，故 §5 增加了 `status` 行与启动错误日志两条可视路径。

## 8. 下一轮（明确不在本轮）

1. **材料泛化**：`GoblinCitizenEntity` 约 10 处硬编码橡木木板（458/723/796/798/808/1874/1899/1914/1933）改为按步骤声明的物品取料、携带、交付、放置；`assignHousing` 签名带上目标物品；`HousingCoordinator` 的仓库选取与材料闸改用已有的 `PublicWarehouseInventory.countOf(level, data, Item)` 与对应的通用仓库选取；删除 §4.5 过渡约束。
2. 其余内容数据化（傀儡参数、职业倾向、名字、对话）。
3. 若确有必要，再考虑数据包重载。

## 9. 验证方式

- 新增独立检查 `housing/BlueprintCheck.java`（无 JUnit，`main` + `require`，打印 `BlueprintCheck passed`，注册进 `build.gradle` 的 `check` 聚合）：
  - 模型 codec 往返（含 `requires` 缺省、可选字段）；
  - §4.1–§4.4 每条各写正例与反例；
  - §4.5 过渡约束的正反例（写明下一轮删除）；
  - 门洞 BFS 的边界情形：无墙、有门洞、砌死、门洞开在相邻两格之外、锚点被单面墙围死但另一面开口（应通过）。
- `checkSteps` 与 `checkFirstUnbuilt` **迁到 `BlueprintCheck`**（步骤合成与进度推导现在归 `BlueprintSet` 管），但**断言值一字不改**：`21/92/102/111/121` 与 `firstUnbuilt` 的既有用例原样保留，作为几何搬迁无损的证据。`HousingRulesCheck` 保留 decide / counts / usableBedHead / bedsNear / codec 那几组。
- **再加一条"数据文件本身"的检查**：`BlueprintCheck` 直接从 classpath 读真实数据文件并跑完整校验（`src/main/resources` 在测试运行期 classpath 上）。这样"受校验的数据文件"在每次构建时都被真正校验，而不是只测校验器。
- 完整离线构建 + 全部独立检查通过。**不做游戏内验证**（按用户约定，初版代码全部完成后才统一测试）。
