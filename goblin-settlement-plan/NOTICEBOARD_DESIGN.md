# 公告牌方块 设计

更新日期：2026-09-29。依据：[GAME_DESIGN.md](GAME_DESIGN.md) 第 11 节「公告牌显示成年与儿童数量、待出生名额、食物可支撑时间、住房空位、工程进度、缺少的材料、土地额度与扩张方向」；[TECH_DESIGN.md](TECH_DESIGN.md) 第 2 节的模块边界（`client` 负责显示、不决定世界状态）；2026-09-29 美术交付包 `Models/handoff/art_handoff_20260929.zip` 的公告牌资产；以及本轮 brainstorming 中用户已定的六处取舍。

范围与六处取舍已由用户定下，本设计把它们展开成可实现的规则。

## 0. 范围

| | 内容 |
| --- | --- |
| **本轮做** | ①**方块与物品**：注册 `noticeboard`，四向 `facing`，自定义碰撞形状，可放置、可破坏、进创造物品栏；②**唯一计算处** `SettlementReport`：把 `status` 命令今天现算的那份聚落状态抽成不可变快照，`status` 与面板**两个 presenter 共用**；③**第一条网络通道**：一条 S2C payload 承载快照；④**面板** `NoticeboardScreen`：分区列表，只排版不计算；⑤**营地自动摆一块**；⑥**食物可支撑时间**：从既有用餐模型推出，不新增发明常量 |
| **明确不做** | 规划预览（GAME_DESIGN §11 的"规划预览展示拟建房屋、道路和桥梁"）；玩家提议入口（同节的"玩家可以提出修桥或选农田的建议"）；C2S 通道与刷新按钮；方块实体；多聚落（当前每维度一个聚落） |
| 不新增 | 不新增方块实体、不新增存档 schema 版本、不新增持久字段、不新增发明常量 |

## 1. 现状（已核对）

- **本模组目前没有任何方块、任何物品、任何网络通道。** `GoblinSettlement.onInitialize`（`GoblinSettlement.java:69-71`）只调 `ModEntities.initialize()` 与 `GolemEntities.initialize()`；全仓 `Registries.BLOCK` / `Registries.ITEM` 各零处注册；`CustomPayload` / `ServerPlayNetworking` / `ClientPlayNetworking` 全部零处使用。**公告牌因此是本模组第一个方块、第一个物品、第一条 payload。**
- **`status` 今天现算现打印 8 行**，全部内联在 `GoblinSettlement.java:91-156` 的 lambda 里；住房逐栋那行另由 `housingReportLine`（`GoblinSettlement.java:305-362`）产出。交通与连通两行不是它自己渲染的，而是调 `TransportCommands.trafficLine`（`TransportCommands.java:141`）与 `TransportCommands.connectivityLine`（`TransportCommands.java:181`）。
- **这 8 行已经覆盖 GAME_DESIGN §11 要求的几乎每一项**：成年/儿童数、人口名额、`plots=x/max`、`player areas`、食物与种子 `x/target`、工具数、容器数、`stock complete/incomplete`、`next priority`、床位/已占/空位/无房、逐栋住几人/容量与卡住原因、下一交通目标、通行量、连通（缺口/受阻/未加载）、各职业人数。**本轮不缺数据，缺的是"端到玩家面前"。**
- **用餐模型是现成的、精确的**：`MealCoordinator` 的类注释写明「Eats one item per living resident after one active Minecraft day.」（`MealCoordinator.java:13`），`MealClock.MEAL_INTERVAL_TICKS = 24000`（`MealClock.java:11`），开饭时每名居民扣 1 份（`MealCoordinator.java:34,43-56`）。**"一个活动游戏日"= 24000 tick = 20 分钟，无需发明任何常量。**
- **营地生成有一道硬闸**：`CampGenerationCoordinator.tick` 的 `if (!camp.layoutComplete()) return;`（`CampGenerationCoordinator.java:62`）排在 8 名居民生成（`:72`）**之前**。`layoutComplete`（`:191`）是严格全等判定 —— 任何一处不符即整座营地不成立。**这条决定了公告牌怎么放，见 §7。**
- **美术交付的公告牌资产已完整**：`assets/goblin_settlement/` 下的 `blockstates/noticeboard.json`、`models/block/noticeboard.json`、`models/item/noticeboard.json`、`items/noticeboard.json`、`textures/block/noticeboard.png`（32×32）。blockstate 声明**恰好四向 `facing`**（north/east/south/west 各带 y 旋转），没有多余属性。
- **模型量出的实际尺寸**：`elements` 九个部件的并集是 **x 0.5–15.5、y 0–16、z 6.5–9.5**（单位 1/16 格）。即一块 3/16 厚的薄板、立在两根柱子上、顶帽几乎横满整格。**x 与 z 两个方向都以方块中心（8）对称** —— 这一条让四向旋转不改变碰撞形状，见 §6。
- **资源目录现状**：`src/main/resources/assets/goblin_settlement/` 下**只有** `lang/` 与 `textures/entity/`；`models/`、`blockstates/`、`items/`、`textures/block/` 四个目录都还不存在。`lang/en_us.json` 目前只有一条 `entity.goblin_settlement.goblin`。
- **注册的既有写法**：`ModEntities`（`ModEntities.java:19-27`）用 `Registry.register(BuiltInRegistries.ENTITY_TYPE, key, ...)`，`key` 由 `Identifier.fromNamespaceAndPath(MOD_ID, ...)` 造。方块的注册照这个形状，不另立风格。

## 2. 架构与数据流

| 组件 | 端 | 职责 |
| --- | --- | --- |
| `NoticeboardBlock` | main | 普通方块。四向 `facing`、自定义形状、`useWithoutItem` 触发面板。**没有方块实体。** |
| `SettlementReport` | main | 聚落状态的**唯一计算处**。不可变快照，持事实而非成品文本。 |
| `NoticeboardPayload` | main | 一条 S2C payload，承载快照。 |
| `NoticeboardScreen` | client | **只排版，不计算。** |
| `GoblinSettlementClient` | client | 注册 payload 类型与接收器。 |

数据流：

```
玩家右键
  → NoticeboardBlock.useWithoutItem（服务端）
  → SettlementReport.snapshot(level)         ← 全部世界读取与区块闸都在这里
  → ServerPlayNetworking.send(player, payload)
  → 客户端接收器 → Minecraft.getInstance().setScreen(new NoticeboardScreen(report))
  → Screen 只画
```

**全链路单向，没有 C2S。** 右键本来就发生在服务端，服务端不需要向客户端问任何东西，所以不需要请求-响应协议。**"刷新"就是关掉重开。**

### 2.1 为什么不做方块实体

牌子**不持有任何状态** —— 它不记"我属于哪个聚落"，因为状态全在 `SettlementSavedData` 里，且当前每维度一个聚落（`SettlementSavedData.get(level)` 的既有口径）。加一个方块实体只会多一层客户端同步、多一处卸载恢复要照顾，换不来任何东西。**这也意味着同一维度的所有公告牌显示同一份状态** —— 当前是刻意的，不是遗漏。

### 2.2 权限

显示是只读的，任何能右键的玩家都能看，**不做权限判定**。这与 `goblinsettlement status` 一致（那条命令也没有 `.requires(...)`）。需要权限的是 `found` / `assign` / `protect` 那几条写命令，本轮一条都不碰。

## 3. `SettlementReport`：一个计算处，两个 presenter

### 3.1 形状

`SettlementReport` 是**不可变快照，持事实而非成品文本**：

| 组 | 字段 |
| --- | --- |
| header | `id`、`anchor`、`adults`、`children`、`occupiedSlots`、`maxResidents`、`plots`、`maxPlots`、`playerAreas` |
| stock | `food`、`foodTarget`、`seeds`、`seedTarget`、`hoes`、`axes`、`pickaxes`、`containers`、`complete`、`priority` |
| housing | `beds`、`occupied`、`spare`、`homeless`、逐栋 `HomeRow(bed, occupancy, capacity, Optional<String> reason)`、`notLoaded` |
| traffic | `Optional<BlockPos> nextTarget`、各路 `RoadRow(id, lanes, count, eligible)`、`Links(connected, gaps, blocked, notLoaded, total)` |
| trades | `Map<Profession, Integer>`（含 `UNASSIGNED`） |

外加一个**派生的纯值** `foodMinutes`（§5）。

**"无聚落"是一种正常状态而非错误**：`Optional<Header>` 为空时其余组一律不填，两个 presenter 各自渲染成"本维度没有聚落"。

### 3.2 `status` 改成从报告渲染（本轮最需要控风险的一处）

`status` 是**测试清单第 2 组已经点名描述过**的命令，所以改它要控风险，控制办法是两条硬要求：

1. **输出文本逐字不变。** `status` 的 presenter 用报告的字段重现今天那 8 行，一个字符都不改。
2. **审查时用定向 diff 证明 presenter 只是搬了位置。** 与第六十二／六十五轮对 `checkCrop` / `checkTexture` / `checkBaked` 的做法相同。

代价说在明处：这要动一个已经被测试清单描述过的命令。**回报是 `status` 与面板结构上不可能漂移** —— 以后加一个指标只加一处。

### 3.3 交通与连通两行的归属

那两行的格式化今天住在 `TransportCommands.trafficLine` / `connectivityLine`。**原则是"文本只渲染一次"**：报告持结构化事实（`RoadRow` 列表、`Links` 计数），两个 presenter 都从事实渲染；`connectivityLine` 今天需要 `ServerLevel` 才能读方块，**搬迁后它的输入变成报告里的已算好的事实** —— 顺带让连通行的格式化变成纯函数、可独立检查。具体怎么挪是计划层的事，方向在此定死。

## 4. 面板内容（客户端排版）

六个分区、约 20 行，超出则滚动。**行的顺序与分组是客户端的排版职责，服务端只给事实。**

| 分区 | 行 |
| --- | --- |
| 人口与名额 | 成人 / 儿童 / 名额 `used/max` / 地块 `used/max` / 玩家区域 |
| 食物 | 食物 `food/target` / **可支撑** `约 N 分钟` / 种子 `seeds/target` |
| 住房 | 床位 / 已占 / 空位 / 无房 ／ 逐栋 `<床头坐标> 住/容 [卡住原因]`，上限 8，超出 `+N more`，不可 tick 的计 `N not loaded` |
| 交通与工程 | 下一目标（坐标或"无"） / 路通行量 / 连通（缺口·受阻·未加载） |
| 材料 | 锄·斧·镐 / 容器数 / 已知库存 `完整` 或 `不完整` / 下一优先 |
| 职业 | 各职业 `= 人数`，含未定职 |

**诊断粒度与 `status` 同等**：住房区逐栋带床头坐标与卡住原因，交通区带缺口/受阻/未加载计数，上限同为 8。理由是不产生第二套口径 —— 玩家排障时不必再去敲命令。

### 4.1 文本走翻译键，不走硬编码

payload 里传的是**翻译键 + 原始参数**（数字、坐标、枚举名），客户端用 `Component.translatable(key, args)` 现渲染。这样中英两套语言文件都能用，且**服务端不知道也不需要知道该显示哪种语言**。`lang/en_us.json` 与 `lang/zh_cn.json` 各追加方块名与全部标签键。

这与既有代码的实际状态一致：`status` 的文本是硬编码英文（命令不面向玩家界面），但 `lang/` 文件本来就是为这类界面文案准备的，目前只有一条实体名。

## 5. 食物可支撑时间

### 5.1 算式

```
整餐数 = 食物 ÷ (成人 + 儿童)     向下取整
分钟   = 整餐数 × 20
```

`20` 来自 `MEAL_INTERVAL_TICKS = 24000` ÷ 1200，即"一个**活动**游戏日"。**这是从既有模型推出来的，不是新发明的常量** —— 项目里那批"发明值"（`TRAFFIC_PER_LANE`、牵引绳 8 格等）多一条都要还债，这条不还。

### 5.2 措辞必须带三个限定

显示为 `约 N 分钟（活动时间）`。三个限定缺一不可：

- **"约"与"已知"**：库存只算**已知公共库存**（`PublicWarehouseInventory.snapshot` 的既有口径 —— 未加载或不可访问的仓库不算），所以这是**下界**而非真值。
- **"活动时间"**：`MealClock` 只累加**活动 tick**（`MealCoordinator.tick` 在聚落区块不可 tick 或居民不活跃时直接返回，`MealCoordinator.java:26-33`），所以这是模拟内的时间，不是墙钟时间。聚落停一天，食物不会少。
- **人口会变**：分母是当下的居民数，人一多这个数就掉。

### 5.3 边界

居民数为 0 → 显示 `—`（没人吃，算式无意义，**不是 0 分钟也不是无穷**）；食物为 0 → `约 0 分钟`；除不尽按整餐向下取整（半顿饭不算能撑）。

## 6. 方块本身

- **`NoticeboardBlock extends Block`**，`Blocks` 风格的 `Properties`：`noOcclusion()`（非满方块，不能挡邻居面）、`strength(1.0F)`、`sound(SoundType.WOOD)`。
- **必须覆写 `codec()`** 返回 `simpleCodec(NoticeboardBlock::new)`。不覆写的话 `defaultBlockState().getBlock().codec()` 会随基类 `Block.CODEC` 反序列化成一个**普通 `Block` 实例**而非公告牌，是静默的错误。
- **四向 `facing`**：`BlockStateProperties.HORIZONTAL_FACING`，放置时按玩家水平朝向（像箱子／告示牌），与美术 blockstate 声明的四个变体一一对应。**刻意不加"上半/下半"** —— 美术的 blockstate 里没有这个属性，多声明一个会让所有四个变体失配。
- **碰撞与轮廓需要两个形状，按 `facing.getAxis()` 选**（单位 1/16 格，取自 §1 量出的并集）：

  | `facing` | 形状 | 说明 |
  | --- | --- | --- |
  | north / south | `box(0.5, 0, 6.5, 15.5, 16, 9.5)` | 板在 x 方向宽、z 方向只有 3/16 厚 |
  | east / west | `box(6.5, 0, 0.5, 9.5, 16, 15.5)` | 宽窄互换 |

  **为什么是两而不是四**：`facing=south` 是 north 的 180° 旋转，而该盒在 x 与 z 两个方向上都以方块中心（8）对称，180° 旋转把它映射到自己；east/west 同理共用另一个盒。**必须有这两个**——板在 x 上宽 15 格、在 z 上只有 3 格，90° 旋转会让宽窄互换，四向共用一个盒是错的。

- **`useWithoutItem`**（`BlockBehaviour.useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult)`，已对本版本核实）：`level.isClientSide()` 为假时算快照、发 payload；两侧都返回 `InteractionResult.SUCCESS`（"这个方块处理了这次交互"）。
- **不覆写 `useItemOn`**：`BlockBehaviour` 的默认实现只有一条指令 —— 返回 `InteractionResult.TRY_WITH_EMPTY_HAND`（**已用 `javap -c` 反编译核实**），其语义就是"交给空手交互"，所以手持任何物品右键都会走到 `useWithoutItem`，不需要自己接线。
- **破坏**：走常规掉落（§8 的 loot table）。**不做掉落物特殊处理。**
- **寻路**：碰撞形状非空，原版 `WalkNodeEvaluator` 判为阻挡 —— **这是要的**，公告牌是实体障碍物。副作用是它不能放在通行路径上，见 §7。

## 7. 营地自动摆放

### 7.1 两条硬约束

**约束一：不能进 `layoutComplete` 硬闸。** `CampGenerationCoordinator.tick` 的 `if (!camp.layoutComplete()) return;`（`:62`）排在居民生成（`:72`）之前。把公告牌算进 `layoutComplete`（`:191` 的严格全等判定），只要有玩家方块占了那个格子，**整座营地将永远停在 0 名居民**。这与第六十七轮抓到的"派工链自我否决"是同一类事故：**把可选物件塞进必经全等判定**。

**约束二：不能挡住通行。** 板子只有 3/16 厚，但碰撞箱非空（§6）。原版寻路判它为阻挡；本项目的 `TransportConnectivity.connects` 也用"该处是不是空气"判可行走。**放在走道正中会切断营地**。

### 7.2 做法

放在硬闸**通过之后**，与"首批补给"共用同一个一次性标志 `starterGrantStarted`（`CampGenerationCoordinator.java:64-66`）—— 那个标志的语义本来就是"营地刚建好"，复用它**不新增持久字段**，且带来一个正确的副作用：**玩家拆掉后不会自己长回来**。

位置与朝向由计划给出具体格子，**验收时必须实机确认不挡路**（列在 §10 的游戏内项里）。约束是：贴在营地边柱一侧、不落在走道正中、正面朝营地内。

## 8. 资源、注册与本地化清单

美术交付直接落位（四个目录都是**新建**）：

| 路径 | 来源 |
| --- | --- |
| `assets/goblin_settlement/blockstates/noticeboard.json` | 美术 |
| `assets/goblin_settlement/models/block/noticeboard.json` | 美术 |
| `assets/goblin_settlement/models/item/noticeboard.json` | 美术 |
| `assets/goblin_settlement/items/noticeboard.json` | 美术 |
| `assets/goblin_settlement/textures/block/noticeboard.png` | 美术（32×32） |

代码侧新增或追加：

| 路径 | 说明 |
| --- | --- |
| `data/goblin_settlement/loot_table/blocks/noticeboard.json` | **不加就挖掉什么都不掉**。目录名是 `loot_table`（单数）与 `recipe`（单数），已对 1.21.11 的 `minecraft-common` 核实。 |
| `lang/en_us.json`、`lang/zh_cn.json` | 追加方块名与全部标签键（§4.1） |
| 创造物品栏 | `ItemGroupEvents.modifyEntriesEvent(...)`，放进「功能方块」。`fabric-item-group-api-v1-4.2.36` 已在依赖里。 |

## 9. 错误与边界

- **本维度没有聚落** → 面板照常打开，只显示一行"本维度没有聚落"，其余区不渲染。**不报错、不开空壳面板。**
- **住宅蓝图数据不可用** → 住房区显示 `status` 今天那句原话（`HousingBlueprints.available()` 为假时的分支，`GoblinSettlement.java:320-323`），**不另造说法**。
- **不可 tick 的区块** → 沿用 `status` 的既有口径：跳过并计入 `not loaded`，**绝不按未加载的方块判定**（`GoblinSettlement.java:337`、`TransportCommands.connectivityLine` 的区块闸）。
- **数字是下界** → 库存类数字只算已知公共库存（§5.2），标签写明"已知"。
- **方块被玩家拆掉** → 正常掉落，营地不会重新放（§7.2）。
- **多人** → 每个玩家各自收到自己的一份 payload，客户端无共享状态。
- **不处理** → payload 版本不匹配（同一模组版本内不会发生）；不做"面板开着时状态变了"的实时刷新（关掉重开即刷新）。

## 10. 测试策略

### 10.1 可纯测（新增一项独立检查 `noticeboardCheck`，检查项 22 → 23）

1. **食物分钟算式**：居民数为 0 → 空；食物为 0 → 0 分钟；除不尽向下取整；恰好整除；巨大值不溢出。
2. **`NoticeboardPayload` codec 往返**：与 `SettlementSavedDataCheck` / `TransportSavedDataCheck` 同形的往返断言。
3. **分区 presenter**：给定一个手工构造的 `SettlementReport` → 期望的**行序列**（纯函数，不碰 MC 类型）。
4. **分区归属**：哪条事实进哪一区（例如"无房人数"属于住房而非人口）。

**`status` 文本逐字不变**（§3.2）也应钉成断言：把今天那 8 行的期望文本写进检查，这样"搬迁无损"有构建期证据，而不是只靠审查时看一眼 diff。

### 10.2 不可纯测，只能进游戏看

- 方块外观、四向朝向是否正确（美术模型"正面朝北"，放置后正面朝向玩家的反方向是否符合预期）
- 放置手感、碰撞形状是否贴合模型、**是否挡路**（§7.1 约束二）
- 面板的实际排版、字号、滚动、长行是否溢出
- 创造物品栏里找得到、物品图标正常
- **营地自动放的那块真的出现了，且没有挡住居民走位**
- 右键真的开面板、真机网络往返正常
- 挖掉方块正常掉落

**本轮同样不做游戏内验证**（除非用户当场要求），只有编译与独立检查的证据。测试项按项目规矩追加进 [TEST_CHECKLIST.md](TEST_CHECKLIST.md)。

## 11. 明确未做（看到别当缺陷）

- **规划预览**（拟建房屋/道路/桥梁的三维预览）—— GAME_DESIGN §11 有，本轮没有，是独立的一轮。
- **玩家提议入口**（提修桥或选农田，聚落回理由）—— 同节，需要写入路径与权限判定，是独立的一轮。
- **C2S 通道与刷新按钮** —— 见 §2，本轮无回程通道。
- **方块实体** —— 见 §2.1。
- **多聚落** —— 所有牌子显示本维度那一个聚落。
- **同一维度的多块牌子内容完全相同** —— 刻意。
- **不做权限分级** —— 见 §2.2。
- **不做物品的合成配方** —— 只进创造栏，营地自动放一块（用户已定）。
