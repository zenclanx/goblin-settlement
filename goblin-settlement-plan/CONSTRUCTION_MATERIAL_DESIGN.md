# 施工路径的材料泛化 设计

更新日期：2026-09-28。依据：CURRENT_STATUS「住宅剩余」里明确未做的那一项（第四十四轮估过约 10 处，住宅部分已在第四十五轮做完），以及 GAME_DESIGN 第 6/7 节「真实库存、工具与生产闭环」「建筑由基础结构与可组合部件构成」。第五十五轮终审也点名过这处：`BUILT_ROAD_LANES` 那类"同一个事实有两处"的问题在本轮同属一类。

## 0. 范围

| | 内容 |
| --- | --- |
| **本轮做** | 让施工路径（命令驱动的 `ConstructionPlan` 那条线）**按计划声明的建筑件施工**，而不是写死橡木木板；顺带修掉由此暴露的回收匹配缺陷 |
| 不做 | 计划的**形状**数据化（长度仍是 2 格直线；那等于另造一套住宅蓝图体系）；新增材质种类；林业/工具/交易/营地赠礼/拓地成本/傀儡成本里**语义确实是橡木**的地方 |
| 沿用不改 | 出生/人口/住宅/道路/桥梁各条线；`ConstructionPlan.LENGTH = 2`；既有的 `MAX_ACTIVE_PLANS = 8`、重叠校验与取消路径 |

## 1. 现状（已核对）

- **这条线是命令驱动的脚手架**：`ConstructionPlan` 只由 `SettlementSavedData.planTwoPlanks(start)` 创建，唯一调用点是 `ConstructionCommands` 的 `plan` 子命令；形状写死为"从 `start` 向东 `LENGTH = 2` 格"（`site() = start.east(completed)`）。
- **计划里没有"我要建什么"**：`ConstructionPlan` 只有 `start` / `completed` / 工人与回收字段，**没有任何材质或方块声明**——这就是缺口。
- **三处橡木写死在施工路径上**：
  - `GoblinCitizenEntity.fetchMaterial`：`takeItems(container, Items.OAK_PLANKS, 1)`，等待理由 `"oak planks missing"`；
  - `GoblinCitizenEntity.placeMaterial`：`carried.is(Items.OAK_PLANKS)` 与 `setBlock(buildPos, Blocks.OAK_PLANKS...)`；
  - `ConstructionCoordinator.tickPlan`：进度判定 `getBlockState(site).is(Blocks.OAK_PLANKS)` 与取仓 `PublicWarehouseInventory.firstWithOakPlank`。
- **回收匹配是写死的橡木**：`economy/DroppedMaterialLookup.find(level, itemId, lastPos)` 的两个分支都判 `Items.OAK_PLANKS`（第 20、27 行），而第二个参数其实是**掉落物的实体 id**（调用点传的是 `RecoveryDrop.itemId()`）。所以一旦能用别的材质施工，**材料丢了以后的回收会去找橡木木板**——这是本轮必须一并修的缺陷，不是可选项。
- **另外两条线早已泛化**：住宅（第四十五轮 `HousingCoordinator.stepAt` + `PublicWarehouseInventory.firstHolding`）与道路桥梁（`TransportPlan.Step.material()` + `TransportCoordinator.itemFor/blockFor`）。本轮是把最后一条线追平。
- **材质的唯一现成出处**：`TransportPlan.Material`（`OAK_PLANKS / OAK_LOG / OAK_FENCE / TORCH`）配 `TransportCoordinator` 里的私有 `itemFor`/`blockFor` 两个 switch。**它就是"一种建筑件对应哪个方块与物品"的既有一份事实**，但今天只服务交通线，且映射是私有的。
- **显示层是按木材汇总的**：`ConstructionCommands` 的 `project` 子命令把"公共箱子库存 / 工人携带 / 还缺多少"都按橡木木板算一个数（`countOakPlanks` + `carriedOakPlanks`）。

## 2. 材料的唯一出处

把"一种建筑件"的枚举从交通计划里提出来，放到**两者共同的父包**：

- 新 `construction/BuildMaterial`：常量与 `TransportPlan.Material` **逐个同名**（`OAK_PLANKS / OAK_LOG / OAK_FENCE / TORCH`），并带上今天散在 `TransportCoordinator` 里的两张表——`block()` 与 `item()`。
- `TransportPlan` 的 `material` 字段改用 `BuildMaterial`；**存档里的取值是常量名**（既有 codec 就是 `Codec.STRING.xmap(Material::valueOf, Material::name)`），所以**旧档一字不改仍能读**，`TransportSavedDataCheck` 里既有的 codec 往返断言继续钉住这一点。
- `TransportCoordinator.itemFor` / `blockFor` 删除，调用点改走 `BuildMaterial.block()` / `BuildMaterial.item()`。

**为什么不是给施工线自己再写一个枚举**：那会把"OAK_PLANKS 是哪个方块与哪个物品"变成第二份事实——正是第四十二（床位判据）、第四十七（挑人）、第五十五（路宽）三轮各自收敛掉的那类重复。

## 3. 计划声明建筑件

`ConstructionPlan` 加一个字段 `material: BuildMaterial`：

- codec：`Codec.STRING.xmap(BuildMaterial::valueOf, BuildMaterial::name).optionalFieldOf("material", BuildMaterial.OAK_PLANKS)`——**默认值就是橡木木板**，所以本轮之前写下的计划（与第一轮那批旧档）行为一字不变。
- `planTwoPlanks(start)` 改名并加参：`planStructure(start, material)`；命令侧传入选定的材料。其余校验（`MAX_ACTIVE_PLANS`、重叠、农田冲突）原样保留。
- 五个 `with*` / `finishStep` 复制方法把 `material` 一并带上（与 `TransportPlan` 的 `Road` 字段同样的处理方式）。

## 4. 施工路径改读声明

**工人侧**（`GoblinCitizenEntity`）：材质**每次从计划重推**，不在实体上新增持久字段（与第四十五轮住宅路径同一取舍）。

- 新增一个按工人查计划的入口：`SettlementSavedData.materialFor(String workerId) -> Optional<BuildMaterial>`（该工人名下计划的声明建筑件；没有计划时为空）。它复用既有的"按工人找计划"那条路，成为"这个工人要铺什么"的唯一出处。
- `fetchMaterial`：取 `material.item()` 的那一格，等待理由用**枚举名小写**（`oak_planks missing` / `oak_log missing` …）——不用注册表查询，也与存档里的取值写法一致。
- `placeMaterial`：`carried.is(material.item())` 与 `setBlock(buildPos, material.block().defaultBlockState(), 3)`。
- `carriedOakPlanks()` → `carried(Item)`（返回携带的该物品数量）。

**协调器侧**（`ConstructionCoordinator.tickPlan`）：

- 进度判定改 `getBlockState(site).is(material.block())`；
- 取仓改 `PublicWarehouseInventory.firstHolding(level, data, material.item())`（第四十五轮已有的通用方法）；
- 站点可用性（空气 + 下方结实）与权限判定不动。

**回收侧**（`DroppedMaterialLookup`）：签名改成 `find(ServerLevel level, String entityId, Item item, BlockPos lastPos)`，两个分支都改用 `item` 匹配。**调用点有两处**：协调器的回收分支（传 `plan.material().item()`）与工人自己那条回收走（`GoblinCitizenEntity.recoverDroppedItem`——它与取料/放置一样，经 `materialFor(workerId)` 取同一份声明；取不到声明就按既有的 `ABORTED` 口径结束）。顺带把 `RecoveryDrop.itemId` 与 `retargetRecoveryDrop` 的 `oldItemId`/`newItemId` 这些**名字**与它们的真实含义（掉落物实体 id）对齐——只改名，**不改 JSON 键**。

## 5. 命令与显示

- `plan` 子命令加一个材料参数（一个词，取 `BuildMaterial` 的常量名、大小写不敏感，形如 `oak_planks`，与 `traffic bridge <pos> <direction>` 既有的写法一致）；缺参或不认识的值都给出明确失败信息，不猜、不默认。
- `project` 子命令的显示**按材料分组**，因为"还缺多少"只对某一种材料有意义：
  - 摘要行按材料各报一次：该材料的 `need`（未完成步数）、`stock`（该物品在公共箱子的数量，用既有的 `countOf`）、`carried`（在岗工人携带的该物品数）；
  - 每个计划行补上它声明的建筑件；
  - 不可加载的仓库/工人仍按既有的 ">= …"/"unknown" 口径处理。

## 6. 验证

- **新增第 18 项独立检查 `constructionMaterialCheck`**（`ConstructionMaterialCheck`，纯层）：
  - **每个 `BuildMaterial` 常量都有非空的方块与物品**（这条断言是"两张表不会漏一格"的守门人）；
  - `ConstructionPlan` 的 codec 往返；**缺 `material` 字段的旧档落到橡木木板**（与 `TransportPlan.Road` 的旧档兜底同一套断言）；
  - `BuildMaterial` 的常量名集合与 **第五十五轮以来的存档取值一致**（把四名字面钉死，防止改名悄悄毁存档）。
- 既有的 `transportSavedDataCheck` 继续覆盖交通侧 codec 往返（枚举换包后必须仍然通过）。
- 完整离线构建 + 全部独立检查（**18 项**）。
- **不可纯测**（会写进日志）：工人按声明取料/放置、协调器按声明找料、回收按声明匹配、命令的两种显示，都要真实世界与容器。

## 7. 风险与已知边界

- **枚举换包是存档可见面的改动**：取值虽是常量名不变，但仍然要由 `transportSavedDataCheck` 的既有往返断言与新的"四名字面钉死"断言双重把关。**不要顺手给常量改名**——那会静默毁掉已建成的桥与路。
- **形状仍是 2 格直线**：本轮的"泛化"只到材料这一层。想建别的形状要先做形状数据化，那是另一轮。
- **材料种类没有增加**：只有既有的四种（木板/原木/栅栏/火把）。要加石头类材质，等于同时要保证经济能产出它——不在本轮。
- **等待理由变成带物品名**：既有依赖 `"oak planks missing"` 这个字面量的地方（若有测试或文档）需要同步，实现时先 grep 确认。
- **旧档默认橡木木板**：本轮之前排队的计划仍会铺橡木木板，这是刻意的兼容选择，不是遗漏。
- **未做游戏内验证**：与本分支既有全部工作一样，本轮只有编译与独立检查的证据。

## 8. 落地结果（实现后补记）

- **材料的唯一出处**：新增 `construction/BuildMaterial`（四个常量逐字保持 `OAK_PLANKS / OAK_LOG / OAK_FENCE / TORCH`，带 `block()` / `item()` 两张表）；`TransportPlan` 删掉自己的 `Material` 枚举改用共享的那个，`TransportCoordinator` 的私有 `itemFor` / `blockFor` 删除。**JSON 取值是常量名，因此旧档一字不改仍能读**，既有 `transportSavedDataCheck` 的往返断言继续钉住它。
- **计划声明建筑件**：`ConstructionPlan` 增 `material` 分量（`optionalFieldOf("material", OAK_PLANKS)`）⇒ 本轮之前的计划仍铺橡木木板，行为不变；`planTwoPlanks` 改名 `planStructure(start, material)`。
- **工人路径不新增持久字段**：新增 `SettlementSavedData.materialFor(workerId)` 作为"这个工人要铺什么"的唯一出处，`fetchMaterial` / `placeMaterial` 每次从计划重推；`carriedOakPlanks()` 改为 `carried(Item)`；等待理由改成枚举名小写（`oak_planks missing`）。
- **协调器**：进度判定改 `plan.material().block()`，取仓改 `firstHolding(..., plan.material().item())`。
- **回收缺陷已修**：`DroppedMaterialLookup.find` 原先两个分支都写死橡木木板，泛化后**会捡错材料**；现在按项目声明的物品匹配，`RecoveryDrop.itemId` 这个错名也改成 `entityId`（**JSON 键仍是 `item_id`**）。
- **命令**：`plan` 增加材料词参数（大小写不敏感，不认识就明确失败并列出可选值）；`project` 的短缺报告改成**按材料各一行**，每行计划行带上它的材料。
- **§4 的调用点数目当场更正**：设计 §4 与计划原写回收查找只有协调器一个调用点，实现时发现是**两处**——`ConstructionCoordinator` 的回收分支，与 `GoblinCitizenEntity` 的回收走（它与取料/放置一样经 `materialFor(workerId)` 读同一份声明，取不到声明就按既有的 `ABORTED` 口径结束）。两处都改按声明匹配；设计 §4 与计划已在同轮补记（提交 `e640782` / `11600a4`），在此留一句以免"只有一处"的旧说法继续流传。
- **两个仓库包装方法删除**：`PublicWarehouseInventory.firstWithOakPlank` 与 `countOakPlanks` 在最后一个调用点消失后删除；取仓与计数的唯一权威现在是 `firstHolding(level, data, Item)` 与 `countOf(level, data, Item)`（提交 `647d3f7`）。
- **新的固定成本：`BuildMaterial` 把注册表对象带进了枚举**。`block()` / `item()` 持有 `Blocks.*` / `Items.*`，所以任何触碰 `BuildMaterial` 的独立检查都必须先 `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();`。本轮有三个检查这么做（`ConstructionMaterialCheck`、`TransportSavedDataCheck`、`SettlementSavedDataCheck`），而在此之前本项目的独立检查**一个都没有引导注册表**。这是把映射放在枚举上的真实代价，**新写检查的人必须知道**。
- **仍未做**：形状仍是 2 格直线（`LENGTH = 2`）；材料种类仍是四种；等待理由的旧字面量若在别处被引用需另行确认。
- **无游戏内验证**：与本分支既有全部工作一样，本轮只有编译与独立检查的证据。**独立检查覆盖不到、只在游戏内才会真正跑到的面**：工人按声明取料/放置（`fetchMaterial` / `placeMaterial`）、协调器按声明找料（`firstHolding(..., plan.material().item())`）、回收按声明匹配（`DroppedMaterialLookup.find` 的两处调用点）、以及命令的两条显示路径（`plan` 的材料参数解析与 `project` 的按材料分组）。
