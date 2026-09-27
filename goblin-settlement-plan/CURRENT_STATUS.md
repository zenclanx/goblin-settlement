# 当前状态：哥布林模组接续入口

更新日期：2026-09-27 13:48 +08:00。详细历史仅追加到 UpdateLog.md。

## 本轮执行约定

先完成七阶段计划的测试前初版，全部计划内容的代码完成后再统一测试；后续提交到 main。当前工作在独立分支 `claude/settlement-first-pass`，验证深度为编译 + 项目自带独立检查，不启动游戏、不跑专用服务端、不动常用存档。职业系统（任务 1–11 的代码 + 完整构建收尾）、道路桥梁自主立项（交通 5 任务）与住宅两轴升级链（住宅 7 任务）均已接入候选。

## 阶段定位

- 阶段 1—2：独立工程、基本存档权限、单居民从真实箱子取料施工，代码与较早游戏验证均已有。
- 阶段 3：多工地与死亡/取消/卸载恢复有较早验证；取消/死亡后计划永久占用工人的卡死修复有纯函数检查覆盖，未在游戏中复验。
- 阶段 4：农业、食物与木工具、林业、家庭和人口约束扩地、采矿井与冶炼调度、职业系统有候选代码。职业系统已完成完整构建 + 独立检查，但未做游戏内验证。
- 阶段 5：道路桥梁自主立项（已服务设施登记、TRANSPORT 需求档与扩地互斥、修路/架桥纯判定、有界只读直线探测、提案协调器）有候选代码，完整构建 + 10 项独立检查通过；居民实际搬运施工已接入候选（立项后由 TransportCoordinator 既有路径施工）。未做游戏内验证。
- 阶段 6：简易床位与顶棚、六个傀儡数值等级、关系处理、原版铁傀儡接入与赠予交易命令有候选代码；住宅两轴升级链（容量轴 × 品质轴）已接入候选，完整构建 + 11 项独立检查通过；更多蓝图仍缺。
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

## 本轮验证进展

- 固定 Gradle 9.2.1 / Java 21 离线完整构建成功（`./gradlew build --offline --no-daemon`，BUILD SUCCESSFUL，27 秒），11 项独立检查全部打印 `*Check passed`：HousingRules、PlotCoordinates、PopulationRules、ProfessionRules、ProtectedRectangle、SettlementDemand、SettlementSavedData、TrafficDecision、TrafficTargetRules、TransportSavedData、WorkerAssignmentRules。
- `goblin-settlement-0.1.0.jar` 重新生成（402420 字节，2026-09-27 13:47），已核对含本批住宅新类：`housing/HousingRules`、`housing/BedCensus`、`housing/BedProvisioningCoordinator`、`housing/HousingSavedData`、`housing/HousingCoordinator`。
- 未做游戏内验证：未启动游戏、未运行专用服务端、未触碰常用存档。构建与检查通过不代表玩法验收。

## 美术候选（2026-09-27）

- 实例根目录 `Models/` 的成年男哥布林 `.bbmodel`、128×128 贴图和预览是 24 方块粗模试作；用户认为与参考图 A 差距明显，未认可为正式造型。静态结构与图片可读性检查通过，但 Blockbench 页面加载超时，工程尚未在软件中打开，亦未接入模组或做游戏内验证。
- 已把 A–E 哥布林方案和 F 傀儡方案的 6 张原始参考图，以及新生成的 A 男右侧面、背面图，按统一名称保存在 `mods/goblin_settlement_reference_art/`，附目录说明。8 张 PNG 已验证可打开；新视图是参考图推测，不是同一 3D 模型的真实投影。选定方向仍为 A 哥布林与 F 傀儡，下一步需决定细化建模工具并重新制作男哥布林。

## 已验证的基线

截至 2026-09-25 17:53 的旧版：固定 Java 21 与 Gradle 9.2.1 离线构建、五项独立检查、两居民多工地取料施工与死亡/取消/卸载恢复、一格小麦播种收获并补种归仓在专用世界通过。对应证据见 UpdateLog.md。此结果不自动覆盖之后的候选代码。

## 尚需实现与统一验收

1. 职业系统剩余：职业熟练度、职业名额、派工服务收口、哨卫工作、儿童外观、真实手持工具。
2. 交通剩余：通行量驱动的道路升级、成熟期多工程并行、道路连通性验收、石桥与更长跨度（设计第 7/12 节要求，本轮未覆盖）。
3. 住宅剩余。**下轮第一优先（全分支审查指定）**：床位判据目前存在三份——`BedCensus`、`FamilyCoordinator.validBed`、以及 `BedProvisioningCoordinator.countBeds` 里的内联版本；今天三者逐条一致，但改动任何一处都会让"床够不够"的判定分叉，应抽成一个共用方法并让另两处改走它。其次：`HousingRules.decide` 在一栋房子已无扩容空间时仍会抬高它的容量目标，白花约 81 块木板（有界、会自解，但设计规则可细化）。其余：蓝图与材料清单迁到受校验的数据文件（几何仍硬编码在协调器里）、改建安全（拆前确认材料与临时住处）、公共设施、更多蓝图（`Home.variant` 仍是朝向镜像，不是可选形制）、实体公告牌方块、住户分配、历史保留。
4. 七阶段其他缺口：阶段 7 成熟城镇性能与跨存储异常恢复。
5. 交通自主立项、采矿、冶炼调度、工人释放修复、职业系统与住宅两轴升级链**均未在游戏中验证**，只有编译与纯函数证据。
6. 突然断电时实体、方块、箱子与 Saved Data 的跨存储一致性尚无保证；死亡掉落实体创建被游戏规则阻止时也可能最终失物。
7. 保守取物目击只覆盖单箱单槽场景。道路/桥梁的非活动区块恢复、分仓与复杂地形仍需统一验证。

## 外部纯函数任务

F001 主线已有同等实现并完成较早验证，外部 r1 不重复接入。F002、F003 的 r1 已收到并完成初步静态审查，但按当前测试后置约定尚未由主程序独立复验或正式验收；外部 NOTES 自报通过不能代替验收。F004（跨仓库取料方案）与 F005（建筑地块排序）的 r1 已到并做静态初审；F005 检查程序的一条反向断言已修正并在 REVIEW.md 记录。两项均未编译或运行检查，未正式验收、未接入。详见 function-bank/README.md。

## 边界

哥布林代码在 goblin-settlement-mod；不要改 ai-chat-mod 或常用存档。纯函数开发前读 function-bank/README.md，外部成果需审查和验证后才接入。GitHub origin 指向 zenclanx/goblin-settlement。本轮工作在分支 `claude/settlement-first-pass`，main 未被改动。
