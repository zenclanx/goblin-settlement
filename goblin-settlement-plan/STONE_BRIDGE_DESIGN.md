# 石桥与更长跨度 设计

更新日期：2026-09-28。依据：GAME_DESIGN.md 第 7 节「桥梁是独立工程……建议木桥支持约 4～12 格跨度，改进的石桥支持约 **12～24** 格，具体以可验证结构和地形为准」；CURRENT_STATUS「交通剩余」里的「石桥与更长跨度」。

## 0. 范围

| | 内容 |
| --- | --- |
| **本轮做** | 加第二种桥：**石桥**（圆石结构），把可跨跨度从 12 格扩到 24 格；决策按档位在木桥与石桥之间选 |
| 不做 | 圆石墙/石砖类护栏（需要经济先能产出那些物品，属另一轮）；更深的河床与峡谷（深度上限不变）；桥梁的宽度（仍是四列）；成熟期多工程并行 |
| 沿用不改 | 桥的走格几何与四列形制、勘察规则、临时栅栏与开通顺序、第五十六轮的**连通验收**、单在途与材料门禁、既有的"立项失败→延期/改线" |

## 1. 现状（已核对）

- **只有一种桥**：`TransportPlan.Kind` 是 `{ROAD, WOOD_BRIDGE}`；`BridgePlanner.MIN_WOOD_SPAN = 4`、`MAX_WOOD_SPAN = 12` 是**写死的上下限**，勘察循环的上界就是 `MAX_WOOD_SPAN + 1`，超出即 `SPAN_TOO_LONG`，不足即 `SPAN_TOO_SHORT`。`BridgePlanner.BridgeKind` 只是**地形分类**（`SHALLOW_WATER` / `SMALL_RAVINE`），与材料无关。
- **决策是纯判据**：`TrafficDecision.decide(columns, targetIndex, minSpan, maxSpan)` 返回 `NONE` / `ROAD` / `BRIDGE`。**关键细节**：水面宽度**落在区间之外时返回的是 `ROAD` 而不是 `NONE`**——语义是"这条直线走廊用不了，绕行交给 `RoadPlanner` 的 A\*"。所以宽于上限的水面今天会去试修路，而路跨不过水，`startRoad` 随即失败，目标按既有口径**进延期名单**。
- **桥种的判断散在 9 处**（`grep -rn "WOOD_BRIDGE" src/`，含测试夹具）：`TransportPlan`（枚举与校验：桥必须有四个 barrier 且 closedFootprint 非空）、`TransportSavedData`（`index`/`unindex` 里把未开通桥的 closedFootprint 并入 `closedFeet`、把开通桥放进 `openBridges`）、`TransportCoordinator`（三处：建桥入口、tickPlan 的栅栏与开通分支、第五十六轮的连通判定）、`TransportCommands`（`Links:` 行的桥筛选）、以及 `TransportSavedDataCheck` 的夹具。
- **施工用料今天全写死在 `startWoodBridge` 里**：临时栅栏 `OAK_FENCE`、引道与桥面 `OAK_PLANKS`、支撑柱 `OAK_LOG`（每三帧一根）、护栏 `OAK_FENCE`、照明 `TORCH`。**清栅栏那段逻辑写死了 `Blocks.OAK_FENCE`**。
- **经济能产出圆石**：`MiningWorksite.Deposit.STONE` 用木镐挖石头/深板岩，产出 `Items.COBBLESTONE` 入公共箱子。所以石桥不缺供给来源，不需要发明新的生产环节。
- **第五十六轮的连通验收**对桥的判据与材料无关（只看走格几何），石桥沿用同一形制即可自动被它覆盖。

## 2. 档位与决策

| 水面宽度 | 结果 |
| --- | --- |
| 4–12 | **木桥**（现状不变） |
| 13–24 | **石桥** |
| < 4 或 > 24 | 两次判定都落在区间外 → 按既有语义给 `ROAD` → `startRoad` 失败 → **目标延期/改线**（现状不变） |

实现方式是**把既有的纯判据调用两次**，先窄后宽：

```
decide(columns, target, MIN_WOOD_SPAN, MAX_WOOD_SPAN)  → BRIDGE 则木桥
否则 decide(columns, target, MIN_STONE_SPAN, MAX_STONE_SPAN) → BRIDGE 则石桥
```

`TrafficDecision` **一行不用改**，它的独立检查也不动——新档位只是新的调用参数。`MIN_STONE_SPAN = 13`（12 格由木桥承包，与 GAME_DESIGN 的两段"4～12 / 12～24"在边界上不冲突）。

## 3. 桥种与材料

- `TransportPlan.Kind` 增 `STONE_BRIDGE`。
- `BuildMaterial` 增 **`COBBLESTONE`**（`Blocks.COBBLESTONE` / `Items.COBBLESTONE`）。**这是对存档有意的兼容变更**：新增名字不破坏任何旧档（旧档里不可能出现它），而新增的检查会把"已存在的名字"钉到五个——**改或删名字仍然是被禁止的**。
- **按桥种的材料清单**：新增纯层 `planning/bridge/BridgeMaterials`（桥种 → 桥面/支撑/护栏/照明四种材料），成为"这座桥用什么建"的唯一出处；`startBridge` 的每一步、材料门禁与命令都问它。表：
| 用途 | 木桥 | 石桥 |
| --- | --- | --- |
| 桥面与引道 | `OAK_PLANKS` | `COBBLESTONE` |
| 支撑柱 | `OAK_LOG` | `COBBLESTONE` |
| 护栏 | `OAK_FENCE` | `OAK_FENCE` |
| 照明 | `TORCH` | `TORCH` |
| 临时施工栅栏 | `OAK_FENCE` | `OAK_FENCE` |

  护栏与临时栅栏**两种桥都用木栅栏**：临时栅栏本来就不是成品（开通时清掉），而且清栅栏那段逻辑写死了橡木栅栏——用它就不必动那段；而圆石墙护栏需要经济能产出圆石墙，那是另一轮。**这是有意的边界，不是遗漏**，与"石桥"这个名字的落差写在 §7。
- **深度上限不变**：`MAX_WATER_BASE_DEPTH = 4`、`MAX_RAVINE_BASE_DEPTH = 8` 照旧。本轮的"改进"只体现在**跨度与结构材料**上；GAME_DESIGN 也没给石桥更深的数字。
- `BridgePlanner` 的勘察从"按写死的 `MAX_WOOD_SPAN`"改为**按区间参数**（`minSpan`/`maxSpan`），木桥与石桥共用同一套勘察（一处权威）；`MAX_WOOD_SPAN` / `MIN_WOOD_SPAN` 常量保留并被两个调用点使用。

## 4. 集成收口

- **一个桥种判据**：给 `TransportPlan` 加 `isBridge()`（`kind == WOOD_BRIDGE || kind == STONE_BRIDGE`），把那 9 处 `kind == WOOD_BRIDGE` 全部改走它。否则下一个桥种要把这 9 处再抄一遍——这正是第四十二（床位）、第四十七（挑人）、第五十五（路宽）三轮各自收敛掉的同类重复。
- **校验与索引跟着走**：`TransportPlan` 的紧凑构造器（桥必须有四个 barrier、closedFootprint 非空）与 `TransportSavedData.index`/`unindex`（closedFeet / openBridges）改用 `isBridge()`，语义不变。
- **连通验收自动覆盖**：`tickPlan` 的开通闸与 `Links:` 行的筛选改用 `isBridge()` 之后，石桥与木桥走同一套判定与显示；石桥的走格几何与木桥相同，**不需要新判据**。
- **材料门禁按桥种**：`TrafficProposalCoordinator.proposeBridge` 现在按木板/原木/栅栏/火把四种材料各自设下限；石桥要按 **§3 的表**里的材料各自设下限（圆石是新的那一项）。缺料的处理沿用既有口径：**材料不足不算立项失败，供应会追上**。

## 5. 命令

`goblinsettlement traffic bridge <near_bank_foot> <direction>` 增加一个可选的材料/桥种词参数（`wood` / `stone`，缺省 `wood`，大小写不敏感，不认识就明确失败并列出可选值）。这是管理员用来手工起桥的入口，与既有的 `traffic road` 同层。

## 6. 验证

- **可纯测的部分**（进独立检查，**分两处，不重复**）：
  - **档位边界** → 加进**既有的** `trafficDecisionCheck`：12 格两次判定的取舍（木桥赢）、13 格（石桥）、24 格（石桥）、25 格（两次都出界，给 `ROAD`）；边界两侧各断言一次。
  - **按桥种的材料清单 + `isBridge()`** → **新增第 19 项** `bridgeMaterialsCheck`（`BridgeMaterialsCheck`，纯层）：木桥与石桥各自的清单逐项钉死、两个清单**只差桥面/支撑那一项**（护栏与照明相同）、`isBridge()` 对两个桥种为真而 `ROAD` 为假。
  - **`BuildMaterial` 的名字**：把"已存在的名字"钉到五个（`OAK_PLANKS / OAK_LOG / OAK_FENCE / TORCH / COBBLESTONE`），并把 `COBBLESTONE` 映射到它自己的方块与物品——**改在既有的** `ConstructionMaterialCheck` 里（它的两条名字/映射断言本来就是干这个的）。
- 完整离线构建 + 全部独立检查（届时 **19 项**）。
- **不可纯测**（写进日志与状态文件）：石桥的勘察与施工、长跨度下的支撑与稳定性、材料门禁的实际手感、以及 24 格桥在真实地形里能不能勘察出来。

## 7. 风险与已知边界

- **石桥的护栏是木头的**：见 §3。观感上不完美，但换来的是不必给经济加圆石墙的制作路径；要改先做那条路径。
- **>24 格的水面仍然不可跨**：两次判定都出界 → 试修路 → 失败 → 目标延期。这是现状的延续，不是本轮的退步。
- **深度上限不变**：`MAX_WATER_BASE_DEPTH = 4` / `MAX_RAVINE_BASE_DEPTH = 8` 照旧。**跨度与深度是两个独立上限**，本轮只放宽跨度：一个 24 格宽**且**河床深于 8 格的峡谷，勘察仍会按既有的深度上限失败（走既有的 `*_BASE_TOO_DEEP` 一类状态），不会硬搭。这类地形在真实世界里的常见程度**未验证**。
- **长桥更贵更慢**：24 格四列的桥面本身就要近百个圆石，加上支撑；单在途门禁与材料门禁不变，所以它会更久地占住那唯一一个交通工程名额。这是有意的取舍（GAME_DESIGN §12 的"成熟期多工程并行"是另一轮）。
- **`BuildMaterial` 新增名字**：会同步改动"名字钉死"的那条断言。**只许新增，不许改删**——改删会让已建成的路/桥/工程变成另一种材料（甚至打不开存档）。
- **未做游戏内验证**：与本分支既有全部工作一样，本轮只有编译与独立检查的证据。
