# 当前状态：哥布林模组接续入口

更新日期：2026-09-27 16:18 +08:00。详细历史仅追加到 UpdateLog.md。

## 接续须知（先读这段）

- **分支**：`main` 与 `claude/settlement-first-pass` 现指向同一提交 `a20e217`，三批工作与文档均已在其中。自第四十二轮起按用户要求直接在 `main` 上开发，不再另开分支。另存在并行分支 `codex/settlement-v1`（领先 2 个提交），与本轮无关。
- **该分支上叠了三批工作**，各自都经过了逐任务审查 + 全范围审查 + 修复：职业系统（12 任务）、道路桥梁自主立项（5 任务）、住宅两轴升级链（7 任务）。
- **全部三批都没有做过游戏内验证**——只有编译通过（`./gradlew build --offline --no-daemon`）与**独立检查**（第四十四轮起为 **12 项**）的证据。三轮的全范围审查各自抓到过静态可见的真缺陷（派工/执行裂脑、河岸长草导致架不成桥、无法服务的目标冻结扩地、建成房屋永久绑定工人），都已修复；但**运行时行为从未被观察过**。
- **下一步的两条路**：①按原约定继续补七阶段剩余缺口（见下"尚需实现"）；②先把手上的东西拿去专用测试世界跑一遍统一验证。**在验证之前不要把这一分支当作可用版本**。
- 开发方式沿用既有约定：先出设计文档（`*_DESIGN.md`），再出实现计划（`*_PLAN.md`），再按计划逐任务实现并逐任务审查。

## 本轮执行约定

先完成七阶段计划的测试前初版，全部计划内容的代码完成后再统一测试；后续提交到 main。第四十二轮起因用户指定直接在 `main` 上开发。验证深度为编译 + 项目自带独立检查，不启动游戏、不跑专用服务端、不动常用存档。职业系统（任务 1–11 的代码 + 完整构建收尾）、道路桥梁自主立项（交通 5 任务）与住宅两轴升级链（住宅 7 任务）均已接入候选；第四十二轮收敛了床位判据的重复实现，第四十三轮细化了住宅决策规则（缺床时饱和房子不动），第四十四轮把住宅五级几何迁到受校验的数据文件，第四十五轮完成材料泛化（工人按每步声明的物品施工），第四十六轮让数据文件带三套风格（更多蓝图），第四十七轮收口了派工服务。

## 阶段定位

- 阶段 1—2：独立工程、基本存档权限、单居民从真实箱子取料施工，代码与较早游戏验证均已有。
- 阶段 3：多工地与死亡/取消/卸载恢复有较早验证；取消/死亡后计划永久占用工人的卡死修复有纯函数检查覆盖，未在游戏中复验。
- 阶段 4：农业、食物与木工具、林业、家庭和人口约束扩地、采矿井与冶炼调度、职业系统有候选代码。职业系统已完成完整构建 + 独立检查；第四十七轮把 8 处重复的挑人代码收进 `colony/WorkerDispatch`；名额约束、哨卫工作与外观项仍缺。未做游戏内验证。
- 阶段 5：道路桥梁自主立项（已服务设施登记、TRANSPORT 需求档与扩地互斥、修路/架桥纯判定、有界只读直线探测、提案协调器）有候选代码，完整构建 + 10 项独立检查通过；居民实际搬运施工已接入候选（立项后由 TransportCoordinator 既有路径施工）。未做游戏内验证。
- 阶段 6：简易床位与顶棚、六个傀儡数值等级、关系处理、原版铁傀儡接入与赠予交易命令有候选代码；住宅两轴升级链（容量轴 × 品质轴）已接入候选，第四十四轮起五级几何与每步方块来自受校验的数据文件，第四十五轮起工人按每步声明的物品取料施工，第四十六轮起数据文件带三套风格（cottage / lean_to / side_porch）并按确定性偏好逐栋选择（完整构建 + 12 项独立检查通过）；**能力已就绪，但三套风格仍全部只用橡木木板**——没有真实蓝图验证过多材质。
- 阶段 7：部分扫描上限、区块门控与两张原创贴图已做；成熟城镇性能和跨存储异常恢复方案未完成。七阶段不能标记为已实现。

## 本轮接入的内容（道路桥梁自主立项）

- 数据层与纯规则：`planning/transport/TrafficDecision`（修路/架桥纯判定，NONE/ROAD/BRIDGE + 首段水面与近远岸）、`planning/transport/TrafficTargetRules`（最近未服务设施选取，MIN_TARGET_DISTANCE_SQ=144 下限，确定性破序）、`TransportSavedData` 新增已服务设施登记与持久化延期名单。
- 需求与扩地：`SettlementDemand` 新增 `TRANSPORT` 档（工具不全 → BASIC_TOOLS → 待修交通 → TRANSPORT → 建造/就绪）；`ExpansionCoordinator` 在有待修交通或未完成交通工程时让位。
- 探测与协调器：`planning/transport/StraightLineProbe`（有界只读直线探测，上界 80 列，权限与区块门控，长草河岸/杂物的踩过分类）、`construction/transport/TrafficProposalCoordinator`（1200 tick 周期，单在途硬门禁 + 材料门禁，桥失败回退修路）。立项只写存档不动世界，施工仍走 TransportCoordinator 既有路径，派工沿用 WorkKind.TRANSPORT。
- 全范围审查修复两项：探测层把长草河岸判为阻挡导致对岸设施永远架不成桥（已修复并写入 TRAFFIC_DESIGN.md 设计规则）；无法服务的目标永久顶住需求档导致扩地被永久冻结（已加持久化延期名单修复，规则同步写入设计文档）。

## 本轮接入的内容（职业系统）

- 纯规则：`colony/Profession`（UNASSIGNED + 7 职业）、`colony/WorkKind`、`colony/ProfessionRules`（matchRank 对口/通才/拉离三档、workIntervalTicks 10/20/30、scarcest），以及纳入 check 聚合的 `professionRulesCheck`。
- 数据与分配：`ResidentRecord` 持久化 profession 字段并兼容旧存档；`SettlementSavedData` 职业查询与指派；`ProfessionCoordinator` 每 200 tick 为未定职成人补稀缺职业，接入主循环。
- 派工与速度：9 个协调器的选人比较器统一按 matchRank 优先对口职业；工作推进按职业三档节流；顺带修复 `ConstructionCoordinator` 既有倒置比较器（上一工人原先反而排在后面）。全分支审查后修复一处派工/执行裂脑：`GoblinCitizenEntity.workKind` 的 `RETURNING`/`RECOVERING` 原映射到 `CONSTRUCTION`，已改映射到 `RECOVERY`（与掉落物回收路径的派工口径一致）。
- 表现：`work`/`status` 命令显示职业；实体同步 `DATA_PROFESSION`；渲染器按职业选贴图；新增 7 张原创职业贴图（textures/entity 下 goblin*.png 共 9 张）。

## 本轮接入的内容（住宅两轴升级链）

- 两轴数据模型与旧档迁移：`HousingSavedData.Home` 由单轴 `stage`/`step` 改为容量轴 × 品质轴两个目标；迁移表含 stage 0–5 共六行（stage 5 是合法旧档状态：旧 `advance` 无条件 +1 且校验上限为 5），`housingRulesCheck` 用 codec 检查逐行钉死迁移表并覆盖旧档读入用例。
- 进度由世界推导：协调器不再保存构建游标；`HousingRules.steps` 按"先容量轴、后品质轴"合成步骤列表，`firstUnbuilt` 从世界已建成的几何推导下一步，目标变化不会重复扣料。
- 扩建/提品质规则：`HousingRules.decide` 只在床位短缺（`beds < occupiedSlots + 1`）且容量轴未满时扩建，否则优先提品质；容量按已建成的几何算（`builtCapacity`），不按目标算；住宅全建完时释放最后一名工人（修复全建完住宅永久绑走工人的泄漏）。
- 床位普查：新增 `housing/BedCensus` 作为床位唯一计数来源，逐条件对齐 `FamilyCoordinator.validBed` 口径。
- `HOUSING` 需求档与扩地例外：`SettlementDemand` 在 TRANSPORT 之后新增 `HOUSING` 档（缺床优先于建造/就绪）；`ExpansionCoordinator` 在"没有房子还能长容量"时放行扩地——否则初始营地（8 床登记为 1 个 home、地块 1 被 4 格互斥区饱和）会永久卡在 8 人。
- 床位与容量绑定含逃生口：新床位绑定到 8 格 Chebyshev 半径内还有容量空位的房子（半径由设计的 4 改为 8——半径 4 与既有的 4 格床位互斥区同中心、交集为空，第二张床数学上无解）；没有任何房子有空位时保留自由放床逃生口。
- 显示：`goblinsettlement status` 新增 `Housing: beds=…, occupied slots=…, spare=…` 行。

## 本轮接入的内容（第四十二轮：床位判据收敛）

- 无行为变更的去重重构，落实全分支审查指定的下轮第一优先：床位判据原先散在三处（`BedCensus` 私有 `validBed`、`FamilyCoordinator` 私有 `validBed`、`BedProvisioningCoordinator.countBeds` 内联条件），今日语义一致但任改一处即分叉。
- 现在唯一权威是 `BedCensus.usableBedHead(ServerLevel, String, BlockPos)`：它采集"床头那一半 / 头顶两格净空 / 三格均 ALLOWED"三项事实，委托纯判据 `HousingRules.usableBedHead(headHalf, headroomClear, columnPermitted)`；`FamilyCoordinator` 两处调用与 `BedProvisioningCoordinator.countBeds` 均已改走它。
- 各自的扫描策略原样保留，未混入判据：`BedCensus` 遇不可 tick 位置 `continue` 跳过，`BedProvisioningCoordinator` 遇之 `return false` 中止整轮复查；`countBeds` 仍收集全部 BEDS 方块（含床脚）供 `nearBed` 邻避。
- 新增独立检查 `HousingRulesCheck.checkUsableBedHead`，逐条断言判据的三条腿各自独立决定结果。
- 明确不合并的近似副本：`HousingCoordinator.isBedHead`（识别锚点床，故意不含净空）、`camp/CampGenerationCoordinator.bedPartMatches`（校验刚放置白床的部件与朝向）——已在源码加注说明。

## 本轮接入的内容（第四十三轮：住宅决策规则细化）

- `HousingRules.decide` 增参 `usedBedsNear`（该房锚点 `BIND_RADIUS` 半径内已用的床位数）。缺床时改问既有的 `canGainCapacity(usedBedsNear, capacityTarget)`：能多放一张床才扩容量，否则返回 **NONE**（不装修、不花木板，也不再占用每 40 tick 一次的决策名额）。原先"`capacityTarget < MAX_CAPACITY_TARGET` 就扩容量"的单独判断删除——已由 `canGainCapacity` 涵盖，留着就是两处判据。
- `HousingCoordinator` 抽出 `bedHeadAxes`/`usedBedsNear` 两个私有助手，`tick` 与 `hasCapacityGain` 共用（后者原是内联的重复构造）；`decide` 的调用点同步补第 5 参。
- **设计取舍**：缺床期间不装修。这推翻了实现期 `HousingRulesCheck` 的断言"缺床且容量到顶转提品质"（改为期望 NONE），依据是 HOUSING_DESIGN.md §4 正文"缺房时不会去给房子加装饰"——两者此前互相矛盾，现以设计原文为准，详见该文档新增的 §4.1。
- 逃生路径未动：全聚落无处可扩容时 `hasCapacityGain` 为假 → 扩地放行 + 自由放床。

## 本轮接入的内容（第四十四轮：住宅蓝图数据化）

- **几何迁到数据文件**：`src/main/resources/data/goblin_settlement/housing_blueprints.json`（由 `tools/generate_housing_blueprints.py` 从原硬编码循环生成，121 步，不手抄）。`HousingRules` 的 `blueprints()`/`STAGES`/`stages()`/`steps()` 与 reserve 常量全部删除。
- **纯层 `housing/BlueprintSet`**：数据模型（`Stage(id, requires, steps)`，步骤复用 `HousingRules.Step` 并为其新增 `block` 字段）、codec、步骤合成、以及四类校验——资源标识存在、升级链无环（`requires` 必须指向同链更早一级，向后引用即保证无环）、蓝图有合法入口（累计形状上按 y∈{0,1} 判阻挡做 2D 洪水填充，锚点列不可达即封死）、数值不越界（阶梯长度、坐标盒、级内去重、reserve 为正）。
- **MC 层 `housing/HousingBlueprints`**：服务端启动时读一次、解码、遍历方块注册表解析标识（**不用 `Identifier.parse`**，规避本版本 API 不确定性）、校验、把每步解析成 `ResolvedStep(Block, Item)` 缓存（避免每 tick 查注册表）。失败则记错误日志并**停用住宅建造**——不崩服，也不回退硬编码（硬编码已删，回退只会掩盖错误）。
- **接线**：`HousingCoordinator` 的进度判定、阶段完成判定、站点占用判定、`position` 签名、领料 reserve 五处改读数据；`tick` 开头加可用性闸；`GoblinSettlement` 注册 `ServerLifecycleEvents.SERVER_STARTING`。
- **过渡约束（本轮专用）**：数据只许 `minecraft:oak_planks`，其他材质判非法——因为工人的取料/携带/放置路径仍硬编码橡木木板且从未游戏内验证过。**下一轮泛化材料时删除此约束**，见「尚需实现」第 3 条。
- **新增检查** `BlueprintCheck`：codec 往返、四类校验的正反例、入口规则的直接测试，并**直接解码校验随 jar 发布的真实数据文件**（每次构建都验）。`checkSteps` 与 `checkFirstUnbuilt` 原样从 `HousingRulesCheck` 迁入，断言值未改——它们与生成脚本的步数输出共同证明几何搬迁无损。

## 本轮接入的内容（第四十五轮：住宅材料泛化）

- **工人不再假设橡木木板**：新增 `HousingCoordinator.stepAt(level, home, site)`（按**工人已知的坐标**从世界重推出这一步）与 `assignedStep(level, bed, site)`。`placeByResident` 改用它声明的物品与方块；`HOUSING_FETCHING`/`HOUSING_DELIVERING` 改为先问 `assignedStep`，为空一律转 `HOUSING_RETURNING`（不拿旧物品硬干）。
- **材料按物品通用**：`PublicWarehouseInventory.firstHolding(level, data, Item)` 是通用取仓方法，`firstWithOakPlank` 改为委托它；领料闸改走既有的 `countOf(level, data, step.item())`，仓库选取改走 `firstHolding`。
- **过渡约束已删除**：`BlueprintSet` 不再限制材质，数据可以描述任何"在方块注册表内且物品形态存在"的方块。**至此"材料清单迁到受校验的数据文件"完成**。
- **不新增持久字段**：物品每次从世界重推，`assignHousing` 签名与所有存档 codec 未改，与 HOUSING_DESIGN §3.1「不保存游标」同源。
- **归还合并条件更正**：住宅与道路共用的 `returnCarriedToSupply` 由"合并进已有的橡木木板堆"改为"合并进已有的同类物品堆"，对道路所带的原木/栅栏/火把同样是更正。
- **相邻项、明确未做**：`fetchMaterial`/`placeMaterial`/`carriedOakPlanks` 与道路交付仍是橡木木板专用，服务 `ConstructionCoordinator` 那条线，不在住宅切片内。

## 本轮接入的内容（第四十六轮：住宅风格）

- **数据文件按风格组织**：`BlueprintSet` 由「一条容量链 + 一条品质链」改为「一组 `Style`」，每套风格各持一条容量链与一条品质链；四条校验逐套执行，外层新增"至少一套、id 唯一、每套阶梯长度正确"。**选整栋风格而非每级多套**：各级是累加的，每级多套会要求下级形制与上级几何逐级接缝，组合爆炸且无简单规则可保证。
- **三套风格**：`cottage`（现有那套，几何**逐字未改**）、`lean_to`（紧凑单坡，门开在 -z 侧）、`side_porch`（带侧廊）。后两套由开发方起草，经校验器的入口洪水填充/坐标盒/级内去重/同格同料四条约束裁定。脚本末行打印 `COTTAGE MUST BE [21, 71, 10]` 并在每次生成时核对。
- **`Home` 新增 `style` 字段**：JSON 字段 `style`，`optionalFieldOf` 缺省 0；**0 必须是 cottage**，这样旧存档里已建成的房子几何一字不变。codec 往返与"无 style 字段的旧档落到 0"都有检查覆盖。
- **选择规则**：`preferredStyle(bed, styleCount) = floorMod(x*31 + z*17, 套数)`——显式算式，**不依赖 `BlockPos.hashCode()`**（其契约不保证跨版本稳定，而这个值要进存档）。注册时按「首选风格 → 其余按数据序」×「镜像 0/1/2」取第一个放得下的组合；全放不下则该地块不注册房子。**镜像机制一行未改。**
- **越界钳制**：风格下标越界一律钳到 0，避免删风格导致存档打不开——代价见「尚需实现」第 3 条的警告。
- **实现期更正了设计里的一个错误假设**：设计原写新形制必须"跨级不重复投放同一格"，实际 **cottage 自己就违反**（棚顶在容量 0 级与 1 级都覆盖），而 `nextSite` 会跳过已建格，所以重复投放无害。真正的不变量是**同格同料**——同格两级声明不同方块会让后一级永远放不下去。设计与检查均已按此更正。

## 本轮接入的内容（第四十七轮：派工服务收口）

- **新增 `colony/WorkerDispatch`**：`permitted(level, settlementId)` 谓词 + `nearest(level, kind, anchor, eligible)`，`SEARCH_RADIUS = 16.0` 具名化。8 处逐字同形的挑人代码（Transport / Farming / Housing / Forestry / Mining / Smelting / FoodCrafting / ToolCrafting）改为调用它。
- **谓词由调用方显式给，不设默认**：8 处里 5 处检查 "工人站位允许改动方块"、3 处不检查。设成默认会让那 3 处行为变化；显式传入则零变化，同时把那处**既有不一致从 lambda 深处提到调用点上**（本轮不改它）。
- **两个异形保留原代码并注明原因**：`ConstructionCoordinator` 的掉落物回收（锚在掉落实体坐标、度量是到实体连续坐标）与施工派工（在 `matchRank` 前多一个"优先续用上次工人"的真实功能键）。
- **管理员命令不算挑人代码**：`GoblinSettlement` 的 `assign`/`work` 也用同样的盒子找最近的哥布林，但不做职业排序，属管理员覆写与显示用途，既不在普查的 10 处之内，也不该收进服务。

## 本轮验证进展

- 第四十七轮：完整构建 `./gradlew build --offline --no-daemon` BUILD SUCCESSFUL（27 秒，无编译警告），**12 项**独立检查全部 `*Check passed`。核对残留：正文 `matchRank` 只剩 `ProfessionRules` 自身两处、`WorkerDispatch` 内一处、`ConstructionCoordinator` 两处（异形）；`WorkerDispatch.nearest` 调用点 8 个。
- **本轮没有新增任何检查，这是性质而非疏忽**：`nearest` 需要 `ServerLevel` 与真实实体，纯计算检查覆盖不到；10 处挑人行为也从未在游戏内验证过。**"零行为变化"是靠逐处对照三段（盒子/谓词/比较器）确认的，不是自动化证据**——既有 12 项检查能保证的只是没有编译级与跨模块的连带破坏。
- `goblin-settlement-0.1.0.jar` 重新生成（415975 字节，2026-09-27 16:16）。
- 第四十六轮 418424 字节、26 秒；第四十五轮 415472 字节、29 秒；第四十四轮 414916 字节、31 秒；第四十三轮 402519 字节、17 秒；第四十二轮 402322 字节、22 秒。
- 未做游戏内验证：未启动游戏、未运行专用服务端、未触碰常用存档。构建与检查通过不代表玩法验收。**独立检查覆盖不到、只在游戏内才会真正跑到的部分**：`HousingBlueprints.load()`（依赖服务端启动与真实注册表）；工人路径（`HOUSING_FETCHING`/`HOUSING_DELIVERING`/`returnCarriedToSupply`）；两套新形制的实际观感与可住性；以及**第四十七轮的派工结果本身**——谁被挑中去干哪件活，只有跑起来才知道。

## 美术候选（2026-09-27）

- 旧 `Models/goblin_male_a_v2.bbmodel` 是未获认可的 24 方块粗模，仅保留对照。参考图 A–E/F 与推测的 A 男侧、背视图仍存放在 `mods/goblin_settlement_reference_art/`；方向为 A 哥布林、F 傀儡。
- **A 版成年男三维样板已制作，整体造型获用户确认**：`Models/goblin_male_a_final/goblin_male_a_final.bbmodel` 是可编辑 Blockbench 主工程，不是单张预览图；同目录含独立贴图、正/斜/侧/背四视角与行走/转头抬手预览、源脚本、接口候选和说明。用户指出眼球凸出后，已把虹膜与瞳孔画到后退的眼白面上，由上眼睑遮挡；这次眼部细节仍待用户复核。现为 108 方块、六主分组、三段编辑器预览动作；贴图位图 512×512、逻辑 UV 256×256。
- **已验证**：修改后的工程由 Blockbench MCP 从磁盘重新载入并识别 108 方块/六主分组/一张贴图；重拍四视角和两张动作预览，重启编辑器再开工程后正面外观一致；内嵌 PNG 与独立 PNG 字节一致；动画引用与关键帧范围检查通过；更新后的 Java 几何候选在当前 Fabric 1.21.11 / Java 21 环境编译成功。
- **尚未验证/接入**：候选模型尚未替换模组运行时资源，游戏内动作、贴图衔接、缩放与脚底位置、碰撞及性能尚待专用测试世界验收。职业贴图仍是旧 64×64 UV，不能直接复用。F 傀儡及其他角色未开做。

## 已验证的基线

截至 2026-09-25 17:53 的旧版：固定 Java 21 与 Gradle 9.2.1 离线构建、五项独立检查、两居民多工地取料施工与死亡/取消/卸载恢复、一格小麦播种收获并补种归仓在专用世界通过。对应证据见 UpdateLog.md。此结果不自动覆盖之后的候选代码。

## 尚需实现与统一验收

1. 职业系统剩余：职业名额约束、哨卫的巡逻/警报工作、儿童体型与动作差异、真实手持工具与盾牌、已定职居民的强制转岗。**派工服务收口已于第四十七轮完成**（`colony/WorkerDispatch`，8 处规范形态收拢，2 个异形保留并注明原因）。
   **「职业熟练度」不是待做项**：PROFESSION_DESIGN §12 把它记为"职业熟练度与效率成长（**用户明确排除**）"。本行此前把它列为剩余项，与设计文档矛盾，已更正——不要照旧单执行一个被排除的需求。
2. 交通剩余：通行量驱动的道路升级、成熟期多工程并行、道路连通性验收、石桥与更长跨度（设计第 7/12 节要求，本轮未覆盖）。
3. 住宅剩余。**已完成五项**：床位判据收敛为单一权威（第四十二轮）；`HousingRules.decide` 的白花问题（第四十三轮）；五级几何迁到受校验的数据文件（第四十四轮）；材料的泛化（第四十五轮）；**更多蓝图（第四十六轮）**——数据文件按"风格"组织，三套风格可用，按床坐标的确定性偏好逐栋选择。**至此"材料清单迁到受校验的数据文件"与"更多蓝图"两项都完成**（TECH_DESIGN 第 6 节要求）。
   **⚠️ 删风格是不安全操作**：风格下标越界会被静默钳到 0（避免存档打不开），所以从数据文件里删掉某套风格，会让存档中用它建成的房子**静默变成 cottage 几何**、进度推导随之改变。要下线一套风格必须先做迁移，不能直接删。
   **明确未做：通用施工路径的材料泛化**——`GoblinCitizenEntity.fetchMaterial`/`placeMaterial`/`carriedOakPlanks` 与道路交付仍是橡木木板专用（服务 `ConstructionCoordinator` 那条线）。第四十四轮把这项工作量估成"约 10 处"，正是把住宅路径与这条线混在了一起；住宅部分已做，这条线未做。
   其余：改建安全（拆前确认材料与临时住处）、公共设施、实体公告牌方块、住户分配、历史保留。
4. 七阶段其他缺口：阶段 7 成熟城镇性能与跨存储异常恢复。
5. 交通自主立项、采矿、冶炼调度、工人释放修复、职业系统与住宅两轴升级链**均未在游戏中验证**，只有编译与纯函数证据。
6. 突然断电时实体、方块、箱子与 Saved Data 的跨存储一致性尚无保证；死亡掉落实体创建被游戏规则阻止时也可能最终失物。
7. 保守取物目击只覆盖单箱单槽场景。道路/桥梁的非活动区块恢复、分仓与复杂地形仍需统一验证。

## 外部纯函数任务

F001 主线已有同等实现并完成较早验证，外部 r1 不重复接入。F002、F003 的 r1 已收到并完成初步静态审查，但按当前测试后置约定尚未由主程序独立复验或正式验收；外部 NOTES 自报通过不能代替验收。F004（跨仓库取料方案）与 F005（建筑地块排序）的 r1 已到并做静态初审；F005 检查程序的一条反向断言已修正并在 REVIEW.md 记录。两项均未编译或运行检查，未正式验收、未接入。详见 function-bank/README.md。

## 边界

哥布林代码在 goblin-settlement-mod；不要改 ai-chat-mod 或常用存档。纯函数开发前读 function-bank/README.md，外部成果需审查和验证后才接入。GitHub origin 指向 zenclanx/goblin-settlement。本轮工作在分支 `claude/settlement-first-pass`，main 未被改动。
