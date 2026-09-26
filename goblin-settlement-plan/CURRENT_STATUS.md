# 当前状态：哥布林模组接续入口

更新日期：2026-09-26 22:02 +08:00。详细历史仅追加到 UpdateLog.md。

## 本轮执行约定

用户要求先完成七阶段计划的测试前初版，全部计划内容的代码完成后再统一测试；后续提交到 main。2026-09-26 12:51 曾纠正一次"把候选代码当作初版完成"的误判。本轮用户改指定：先把工作区未提交的候选收口，提交到**独立分支**（`claude/settlement-first-pass`）而非 main；验证深度限定为**编译 + 运行项目自带独立检查**，仍不启动游戏、不跑专用服务端、不动常用存档。收口已完成，七阶段剩余缺口尚未开始补。

## 阶段定位

- 阶段 1—2：独立工程、基本存档权限、单居民从真实箱子取料施工，代码与较早游戏验证均已有。
- 阶段 3：多工地与死亡/取消/卸载恢复有较早验证。本轮修复了取消/死亡后计划永久占用工人导致卡死的问题，并新增纯函数检查覆盖该真值表；未在游戏中复验。
- 阶段 4：农业、食物与木工具、林业、家庭和人口约束扩地有候选代码。本轮新增采矿井与冶炼调度，**职业系统仍缺**。
- 阶段 5：道路桥梁规划及服务端逐块施工有候选代码，但居民实际搬运施工仍主要由管理员命令立项。
- 阶段 6：简易床位与顶棚、六个傀儡数值等级、关系处理、原版铁傀儡接入与赠予交易命令有候选代码；完整住宅升级链与多蓝图仍缺。
- 阶段 7：部分扫描上限、区块门控与两张原创贴图已做；成熟城镇性能和跨存储异常恢复方案未完成。七阶段不能标记为已实现。

## 本轮收口的内容

- 修正 3 处编译错误（`GameRules` 与 `IronGolem` 的 1.21.11 包路径、`Item.getDescription()` 不存在），共 6 个文件。这批 13:03—13:09 写入的候选此前从未编译过。
- 新增纯函数 `colony/WorkerAssignmentRules`：把"该不该释放工人"（名册阶段 × 实体是否加载 × 是否仍持有该工作）固化为真值表，并把释放检查接进 TransportCoordinator 与 HousingCoordinator。`GoblinCitizenEntity.releasePersistedWork` 在死亡与取消时释放农田/工程/交通/住房四处 workerId。
- 新增 `mining/MiningCoordinator` 与 `economy/smelting/SmeltingCoordinator`，把此前无调用方的 `MiningWorksite`、`FurnaceWorksite` 接上主循环；`GoblinCitizenEntity` 增加 `MINING_DIGGING` 与 `SMELT_*` 阶段及状态机。冶炼不自行放置熔炉，只在管理员已提供熔炉时工作。
- 注册此前从未被调用的 `GiftTradeCommands`；`DefenseSavedData` 增加 `backfillHome`，修复旧存档铁傀儡 home 为世界原点的问题。

## 本轮验证进展

- 固定 Gradle 9.2.1 / Java 21 离线完整构建成功。
- 6 项独立检查全部通过：原有 PlotCoordinates、PopulationRules、ProtectedRectangle、SettlementDemand、SettlementSavedData，加新增 WorkerAssignmentRulesCheck（先写失败、再实现转绿）。
- 生成 `goblin-settlement-0.1.0.jar`（355930 字节），已确认含 camp、mining、economy.smelting 等全部新类。
- 未启动游戏、未运行专用服务端、未触碰常用存档。本轮无游戏内证据。

## 已验证的基线

截至 2026-09-25 17:53 的旧版：固定 Java 21 与 Gradle 9.2.1 离线构建、五项独立检查、两居民多工地取料施工与死亡/取消/卸载恢复、一格小麦播种收获并补种归仓在专用世界通过。对应证据见 UpdateLog.md。此结果不自动覆盖之后的候选代码。

## 尚需实现与统一验收

1. 七阶段剩余缺口：职业系统（GAME_DESIGN §3 的 7 种职业）、道路桥梁的居民实际搬运施工、完整住宅升级链与更多蓝图、阶段 7 成熟城镇性能与跨存储异常恢复。
2. 本轮接入的采矿、冶炼调度与工人释放修复**均未在游戏中验证**，只有编译与纯函数证据。
3. 突然断电时实体、方块、箱子与 Saved Data 的跨存储一致性尚无保证；死亡掉落实体创建被游戏规则阻止时也可能最终失物。
4. 保守取物目击只覆盖单箱单槽场景。道路/桥梁的非活动区块恢复、分仓与复杂地形仍需统一验证。

## 外部纯函数任务

F001 主线已有同等实现并完成较早验证，外部 r1 不重复接入。F002、F003 的 r1 已收到并完成初步静态审查，但按当前测试后置约定尚未由主程序独立复验或正式验收；外部 NOTES 自报通过不能代替验收。F004（跨仓库取料方案）与 F005（建筑地块排序）的 r1 已到并做静态初审；F005 检查程序的一条反向断言已修正并在 REVIEW.md 记录。两项均未编译或运行检查，未正式验收、未接入。详见 function-bank/README.md。

## 边界

哥布林代码在 goblin-settlement-mod；不要改 ai-chat-mod 或常用存档。纯函数开发前读 function-bank/README.md，外部成果需审查和验证后才接入。GitHub origin 指向 zenclanx/goblin-settlement。本轮工作在分支 `claude/settlement-first-pass`，main 未被改动。
