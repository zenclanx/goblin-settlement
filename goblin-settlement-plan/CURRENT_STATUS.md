# 当前状态：哥布林模组接续入口

更新日期：2026-09-27 01:03 +08:00。详细历史仅追加到 UpdateLog.md。

## 本轮执行约定

先完成七阶段计划的测试前初版，全部计划内容的代码完成后再统一测试；后续提交到 main。当前工作在独立分支 `claude/settlement-first-pass`，验证深度为编译 + 项目自带独立检查，不启动游戏、不跑专用服务端、不动常用存档。职业系统（任务 1–11 的代码 + 本轮完整构建收尾）已接入候选。

## 阶段定位

- 阶段 1—2：独立工程、基本存档权限、单居民从真实箱子取料施工，代码与较早游戏验证均已有。
- 阶段 3：多工地与死亡/取消/卸载恢复有较早验证；取消/死亡后计划永久占用工人的卡死修复有纯函数检查覆盖，未在游戏中复验。
- 阶段 4：农业、食物与木工具、林业、家庭和人口约束扩地、采矿井与冶炼调度、职业系统有候选代码。职业系统已完成完整构建 + 7 项独立检查，但未做游戏内验证。
- 阶段 5：道路桥梁规划及服务端逐块施工有候选代码，但居民实际搬运施工仍主要由管理员命令立项。
- 阶段 6：简易床位与顶棚、六个傀儡数值等级、关系处理、原版铁傀儡接入与赠予交易命令有候选代码；完整住宅升级链与多蓝图仍缺。
- 阶段 7：部分扫描上限、区块门控与两张原创贴图已做；成熟城镇性能和跨存储异常恢复方案未完成。七阶段不能标记为已实现。

## 本轮接入的内容（职业系统）

- 纯规则：`colony/Profession`（UNASSIGNED + 7 职业）、`colony/WorkKind`、`colony/ProfessionRules`（matchRank 对口/通才/拉离三档、workIntervalTicks 10/20/30、scarcest），以及纳入 check 聚合的 `professionRulesCheck`。
- 数据与分配：`ResidentRecord` 持久化 profession 字段并兼容旧存档；`SettlementSavedData` 职业查询与指派；`ProfessionCoordinator` 每 200 tick 为未定职成人补稀缺职业，接入主循环。
- 派工与速度：9 个协调器的选人比较器统一按 matchRank 优先对口职业；工作推进按职业三档节流；顺带修复 `ConstructionCoordinator` 既有倒置比较器（上一工人原先反而排在后面）。全分支审查后修复一处派工/执行裂脑：`GoblinCitizenEntity.workKind` 的 `RETURNING`/`RECOVERING` 原映射到 `CONSTRUCTION`，已改映射到 `RECOVERY`（与掉落物回收路径的派工口径一致）。
- 表现：`work`/`status` 命令显示职业；实体同步 `DATA_PROFESSION`；渲染器按职业选贴图；新增 7 张原创职业贴图（textures/entity 下 goblin*.png 共 9 张）。

## 本轮验证进展

- 固定 Gradle 9.2.1 / Java 21 离线完整构建成功（`./gradlew build --offline --no-daemon`），7 项独立检查全部通过：PlotCoordinates、PopulationRules、ProfessionRules、ProtectedRectangle、SettlementDemand、SettlementSavedData、WorkerAssignmentRules。
- 全分支审查修复后复跑 `./gradlew check --offline --no-daemon`：BUILD SUCCESSFUL，7 项独立检查全部打印 `*Check passed`。修复内容：RECOVERY 映射拆行、assign 命令旁路说明、渲染器贴图常量化、设计文档时间口径（详见 UpdateLog.md 第三十七轮补记）。
- `goblin-settlement-0.1.0.jar` 重新生成（371476 字节，2026-09-27 00:00），已核对含 4 个新类与 9 张贴图。
- 未做游戏内验证：未启动游戏、未运行专用服务端、未触碰常用存档。

## 已验证的基线

截至 2026-09-25 17:53 的旧版：固定 Java 21 与 Gradle 9.2.1 离线构建、五项独立检查、两居民多工地取料施工与死亡/取消/卸载恢复、一格小麦播种收获并补种归仓在专用世界通过。对应证据见 UpdateLog.md。此结果不自动覆盖之后的候选代码。

## 尚需实现与统一验收

1. 职业系统剩余：职业熟练度、职业名额、派工服务收口、哨卫工作、儿童外观、真实手持工具。
2. 七阶段其他缺口：道路桥梁的居民实际搬运施工、完整住宅升级链与更多蓝图、阶段 7 成熟城镇性能与跨存储异常恢复。
3. 采矿、冶炼调度、工人释放修复与职业系统**均未在游戏中验证**，只有编译与纯函数证据。
4. 突然断电时实体、方块、箱子与 Saved Data 的跨存储一致性尚无保证；死亡掉落实体创建被游戏规则阻止时也可能最终失物。
5. 保守取物目击只覆盖单箱单槽场景。道路/桥梁的非活动区块恢复、分仓与复杂地形仍需统一验证。

## 外部纯函数任务

F001 主线已有同等实现并完成较早验证，外部 r1 不重复接入。F002、F003 的 r1 已收到并完成初步静态审查，但按当前测试后置约定尚未由主程序独立复验或正式验收；外部 NOTES 自报通过不能代替验收。F004（跨仓库取料方案）与 F005（建筑地块排序）的 r1 已到并做静态初审；F005 检查程序的一条反向断言已修正并在 REVIEW.md 记录。两项均未编译或运行检查，未正式验收、未接入。详见 function-bank/README.md。

## 边界

哥布林代码在 goblin-settlement-mod；不要改 ai-chat-mod 或常用存档。纯函数开发前读 function-bank/README.md，外部成果需审查和验证后才接入。GitHub origin 指向 zenclanx/goblin-settlement。本轮工作在分支 `claude/settlement-first-pass`，main 未被改动。
