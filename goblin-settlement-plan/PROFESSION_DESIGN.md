# 哥布林职业系统：设计

记录日期：2026-09-26
状态：设计稿，已由用户确认方向。数值为**建议初值**，需试玩调整。

本设计落实 [GAME_DESIGN.md](GAME_DESIGN.md) 第 3 节的职业内容，并遵循 [TECH_DESIGN.md](TECH_DESIGN.md) 第 2 节的模块边界。当前进展见 [CURRENT_STATUS.md](CURRENT_STATUS.md)。

## 1. 目标

让居民拥有**持久职业身份**，职业影响两件事：他们被派活的**优先次序**，以及他们干活的**速度**。职业也决定**外观**，让玩家一眼看出谁是谁。

核心约束（用户明确要求）：**任何活都不能因为缺某个职业而完全停摆。** 不对口的居民照样能干，只是慢。

## 2. 设计决定

### 2.1 不做门控，只做速度与偏好

不采用"只有对口职业才能干某活"的硬门控。理由是硬门控会在某职业空缺时让那类活彻底停摆，违背上面的核心约束。

取而代之两件事：

1. **选取偏好**：多个候选居民时，职业对口者优先被选中（同距离下）。
2. **动作速度**：不对口的居民完成同一份工作更慢。

于是分工是**涌现**出来的：人口少时大家自然身兼数职，人口增长后对口的人越来越频繁地被选中，专业化自行浮现。这正好对应 GAME_DESIGN 的"初期允许兼职，人口、工坊和需求增加后再专业化"，且不需要任何人口阈值开关。

### 2.2 `UNASSIGNED` 就是通用人

用户提出的"通用人种"不需要新增实体类型。`UNASSIGNED` 承担这个角色：

- **未定职 = 通才**：什么都会一点，速度居中，被选中的优先级居中。
- **定职但不对口 = 专才走错行**：最慢，优先级最低。

这样未定职有了明确身份，也避免了新增类型带来的实体、贴图、存档全套改动。

### 2.3 哨卫本轮无工作，按通才对待

防御协调器只操作傀儡，不派居民。因此 `SENTRY` 这一轮没有任何可派的工作：

- 哨卫**可以是居民的职业**，有对应贴图。
- 定职为哨卫的居民照常接其他活，且**干活速度与未定职相同**（理由见 4.2 的"无工作职业按通才对待"规则）。
- 等将来实现巡逻与警报工作时，该规则自动失效，哨卫回到对口档。

## 3. 数据模型

### 3.1 `colony/Profession`（新增）

```java
public enum Profession { UNASSIGNED, FARMER, FORESTER, MINER, BUILDER, HAULER, ARTISAN, SENTRY }
```

`UNASSIGNED` 必须在首位，作为默认值。

### 3.2 `ResidentRecord` 增加 `profession` 分量

职业存放在**名册**（`colony`）而非实体 NBT，理由与 `ResidentRecord` 现有注释一致：身份信息在居民卸载时仍须权威。`citizen` 只读取，不复制。

Codec 改法**照抄现有 `reproductive_role` 的模式**：

```java
PROFESSION_CODEC.optionalFieldOf("profession", Profession.UNASSIGNED)
        .forGetter(ResidentRecord::profession)
```

`PROFESSION_CODEC` 用 `Codec.STRING.comapFlatMap`，与 `STAGE_CODEC`、`ROLE_CODEC` 同型。

**`SCHEMA_VERSION` 保持 1，不提升。** `SettlementSavedData` 的构造器对版本做严格相等校验，升到 2 会让所有旧存档加载失败。新增可选字段配默认值即可无痛兼容：旧存档缺该字段时反序列化为 `UNASSIGNED`。

需同步更新的构造点：紧凑构造器的空值校验、`adult()`、`child()`、`withReproductiveRole`、`advanceFamilyTime`、`withPostBirthRest`、`deceased()`。新增 `withProfession(Profession)`。

### 3.3 `isAvailableForConstruction()` 不改

这是 9 个协调器共用的唯一过滤器。本设计**不修改它的签名，也不修改 12 处 `assignX()` 内部守卫**——职业不再是资格问题，只是偏好与速度问题。这是本设计相比硬门控方案最大的改动面缩减。

## 4. 工作种类与所需职业

### 4.1 `colony/WorkKind`（新增）

把"某种工作"与"对应职业"的映射集中成一张表，替代散落在各协调器里的隐式知识：

| WorkKind | 所需职业 | 对应协调器 |
| --- | --- | --- |
| `FARMING` | `FARMER` | FarmingCoordinator |
| `FOOD_CRAFTING` | `FARMER` | FoodCraftingCoordinator |
| `FORESTRY` | `FORESTER` | ForestryCoordinator |
| `MINING` | `MINER` | MiningCoordinator |
| `CONSTRUCTION` | `BUILDER` | ConstructionCoordinator |
| `HOUSING` | `BUILDER` | HousingCoordinator（仅建造步） |
| `TRANSPORT` | `HAULER` | TransportCoordinator |
| `RECOVERY` | `HAULER` | ConstructionCoordinator 的掉落物回收路径 |
| `TOOL_CRAFTING` | `ARTISAN` | ToolCraftingCoordinator |
| `SMELTING` | `ARTISAN` | SmeltingCoordinator |

分配依据 GAME_DESIGN 第 3 节的职业职责表（农民兼基础烹饪、搬运员管仓储与工地供料、工匠管工具与冶炼）。

不派居民的工作（`MealCoordinator`、`BedProvisioningCoordinator`、`ExpansionCoordinator`、`DefenseCoordinator`、`FarmDiscoveryCoordinator`）没有对应 WorkKind。

### 4.2 `colony/ProfessionRules`（新增，纯函数）

只含纯计算，无 Minecraft 依赖，可独立写检查：

```
matchRank(WorkKind kind, Profession resident) -> int
    对口                        -> 0
    未定职                      -> 1
    该职业没有任何对应工作      -> 1    （见下方"无工作职业按通才对待"）
    不对口                      -> 2

workIntervalTicks(WorkKind kind, Profession resident) -> int
    对口                        -> 10
    未定职                      -> 20
    该职业没有任何对应工作      -> 20
    不对口                      -> 30
```

两张表都是建议初值。`matchRank` 越小越优先；`workIntervalTicks` 越小越快。

**无工作职业按通才对待**：若某职业在 4.1 的映射表里不对应任何 `WorkKind`，它对所有工作都按"未定职"档处理，而不是"不对口"档。当前只有 `SENTRY` 命中这条（本轮哨卫无工作），语义与 2.3 一致；将来哨卫有了对应工作，本规则对它自动失效。

**为什么不用 `mayTake` 这类布尔判定**：本设计不存在"能不能"，只存在"多优先、多快"，所以返回序数与间隔比返回布尔更贴合语义。

## 5. 派工偏好

9 个协调器挑居民的排序键从

```java
.min(Comparator.comparingDouble(g -> g.blockPosition().distSqr(anchor)))
```

改为

```java
.min(Comparator.comparingInt(g -> ProfessionRules.matchRank(kind, professionOf(g)))
        .thenComparingDouble(g -> g.blockPosition().distSqr(anchor)))
```

即**职业契合度优先，同档内取最近**。

两处例外保持原样：

- `ConstructionCoordinator` 正常建造路径原本就有"优先复用 `lastWorkerId`"的双键比较器，职业档插在它与距离之间。
- `/goblinsettlement assign` 是绕过一切过滤的管理员直控命令，**保持直控语义**，不加职业偏好，并在其输出里说明它是管理员旁路。

## 6. 工作速度

### 6.1 落点

`GoblinCitizenEntity.customServerAiStep` 目前用 `if (tickCount % 10 != 0) return;` 同时管住登记、取消处理和工作推进。改造为**分离两级**：

- 登记与取消处理保持固定 10 tick 节流（它们与职业无关，且取消处理不应被拖慢）。
- 工作推进改用职业相关的间隔：`if (tickCount % ProfessionRules.workIntervalTicks(kind, profession) != 0) return;` 这一步只作用于"正在执行某项工作"的阶段（`workStage` 属于某类工作时）；`IDLE` 等非工作阶段不经过该节流。

工作种类由当前 `workStage` 推出（实体内部做一次 `WorkStage -> WorkKind` 的映射）。映射不到的阶段按"对口"处理，保证保守——即新工作阶段即使漏登记映射，也只是不减速，不会误伤。

### 6.2 已知局限

速度只作用于**动作**，不作用于**走路**。居民走到目标点由导航系统驱动，不受该间隔影响。

后果：以"走过去、做一个动作"为主的活（采矿、采树苗）速度差会被走路时间稀释；以"反复来回搬运"为主的活（运输、施工）差异明显。

这是有意接受的：走同样快、只是干活手法生疏，符合直觉。若试玩后觉得差异不足，调大不对口档的间隔即可。

## 7. 职业分配

### 7.1 `colony/ProfessionCoordinator`（新增）

周期性运行，只做一件事：**给 `UNASSIGNED` 的成年居民补职业**，分配到当前人手最少的职业；并列时按 `Profession` 枚举序，保证结果确定、可复现。

只补 `UNASSIGNED`，**不把已定职的居民转岗**。若某职业彻底空缺，下一批未定职的居民会补上；已定职者不动，符合 GAME_DESIGN 的"转岗不剥夺基本工作能力"（转岗本身留给以后单独设计）。

**为什么只用一个分配点**：初始 8 人、新出生的孩子成年后、以及旧存档里的全部现存居民，都会被这同一个循环兜住，无需在营地生成、出生、登记三条路径各写一遍。

### 7.2 初始 8 人不做固定配比

`CampGenerationCoordinator.spawnAdults` 不动。首次 tick 时 `ProfessionCoordinator` 按"最缺"依次补齐，结果确定。

### 7.3 儿童

儿童在名册里 `stage == CHILD`、`profession == UNASSIGNED`。成年后才被 `ProfessionCoordinator` 分配职业。儿童外观本轮不处理（见第 9 节范围外）。

## 8. 显示

- `GoblinCitizenEntity.workSummary()` 前缀职业名，`/goblinsettlement work` 与 `assign` 自动带出。
- `/goblinsettlement status` 增加一行按职业统计（新增 `SettlementSavedData` 上的聚合方法，仿现有 `adultCount()` 的写法）。

实体侧读职业需查名册（`SettlementSavedData.get(level).resident(uuid)`），与 `isAvailableForConstruction()` 已有的路径一致。

## 9. 职业外观

### 9.1 同步

实体当前**零 `SynchedEntityData`**，客户端拿不到服务端名册。因此：

- `GoblinCitizenEntity.defineSynchedData` 注册一个 `Profession` 同步项，职业变更时写回。
- 用 `EntityDataSerializers.STRING` 传枚举名而非序号——与项目存档一律用名字的既有习惯一致，且不怕枚举顺序变化。

### 9.2 渲染

- `GoblinRenderState`（现为空壳）增加 `profession` 字段。
- `GoblinRenderer` 实现 `extractRenderState` 把职业拷进 state，`getTextureLocation` 按职业分流。
- **`GolemRenderer` 复用同一个 `GoblinRenderState` 与 `GoblinModel`，必须一并检查**：傀儡不设职业，渲染必须保持现状不受影响。

### 9.3 贴图

`tools/generate_entity_textures.py` 的 `goblin()` 参数化，按配件绘制并批量产出：

| 文件 | 职业 | 配件（画进现有 UV 区域） |
| --- | --- | --- |
| `goblin.png` | 未定职 | 保持现状，不改 |
| `goblin_farmer.png` | 农民 | 草帽 |
| `goblin_forester.png` | 林工 | 肩带 |
| `goblin_miner.png` | 矿工 | 头灯 |
| `goblin_builder.png` | 建筑工 | 工具腰带 |
| `goblin_hauler.png` | 搬运员 | 背包（画在身体背面 UV 区） |
| `goblin_artisan.png` | 工匠 | 围裙、护目镜 |
| `goblin_sentry.png` | 哨卫 | 头盔 |

全部为 64×64 原创像素画，风格与现有两张一致。

**手持工具不做**：草帽、头灯、护目镜、头盔、围裙、肩带、工具腰带、背包都能画进现有 UV 区域，但"手持的锄/斧/镐/盾"靠贴图做不出体积感。真实手持物件需要给模型加手持姿态度（`GoblinModel` 不是 `HumanoidModel`），风险高，列入范围外。

## 10. 兼容性

- 旧存档：`profession` 字段缺失即 `UNASSIGNED`，`SCHEMA_VERSION` 不升。
- 旧居民：加载后由 `ProfessionCoordinator` 在一秒内补上职业，之前按未定职（通才，速度居中）运行。
- 傀儡：不设职业，渲染与行为均不受影响。
- 实体同步项新增不会影响存档格式。

## 11. 验证

- `ProfessionRules` 的 `matchRank` 与 `workIntervalTicks` 先写失败的独立检查再实现（项目现有 5 个检查的写法：无 JUnit，`main` + `require`，成功打印 `XxxCheck passed`，并在 `build.gradle` 注册 JavaExec 任务、纳入 `check`）。
- `SettlementSavedDataCheck` 增加一条：缺 `profession` 字段的旧存档 JSON 反序列化为 `UNASSIGNED`。
- 完整离线构建 + 全部独立检查通过。
- **不做游戏内验证**：按用户约定，初版代码全部完成后再统一测试。

## 12. 范围外（本轮不做）

- 职业熟练度与效率成长（用户明确排除）。
- 职业名额约束（哨卫每 12 成人 1 名等）。
- 把 9 个协调器重复的挑人代码重构成统一派工服务。
- 儿童体型与动作差异。
- 真实手持工具与盾牌。
- 哨卫的巡逻 / 警报工作。
- 已定职居民的强制转岗。

## 13. 风险

- **同步机制是新增的**：实体此前无任何 `SynchedEntityData`，需确认注册与写入时机正确，且不干扰傀儡渲染。
- **改动面宽而浅**：9 个协调器的比较器是机械改动，编译与现有检查能覆盖大部分，但"职业偏好是否真的生效"没有自动化手段验证，只能靠代码审查。
- **`WorkStage -> WorkKind` 映射易漏**：新增工作阶段时忘了登记映射会静默按"对口"处理（保守但静默）。实现时应在映射函数处注释说明。
- **速度档稀释**：见 6.2，采矿类工作的体感差异可能不明显。
- **`workSummary()` 读名册**：实体侧多一次名册读取，需注意只在命令输出路径调用，不进每 tick 热路径。
