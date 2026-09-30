# 音效接入 设计

更新日期：2026-09-30。依据：美术 2026-09-29 交付包 `Models/handoff/art_handoff_20260929.zip` 的 `sounds/`、`sounds.json` 与 `merge/zh_cn_subtitles.json`；`Models/ART_SOUND_TEXTURE_PLAN.md` 的声音规格（「成年男女五类各 2 变体，共 20 段；五种傀儡各 3 移动＋1 核心循环」）；[GAME_DESIGN.md](GAME_DESIGN.md) §2「成年男女…声音…有所区别」与 §11「日常包含进食、工作、休息和**在公共场所碰面**」；以及 2026-09-30 brainstorming 中用户已定的三处取舍。

**本轮只做音效。** 美术同批交付的另外两件（九张物品图标、七类设施标识）是**独立的两轮**，见 §8。

## 0. 范围

| | 内容 |
| --- | --- |
| **本轮做** | ①**资源**：40 个 ogg 进模组、`sounds.json` 原样接入；②**注册**：20 个 `SoundEvent`；③**挂点**：受击/死亡/工作号子/傀儡移动一次性音效走服务端；④**傀儡核心循环**：客户端 `SoundInstance`，无缝、随实体消失而停；⑤**「碰面」行为**：居民相遇时一人招呼、另一人应答（补上 GAME_DESIGN §11 已设计但未做的"在公共场所碰面"）；⑥**字幕**：20 条，中英两套 |
| **明确不做** | 儿童专属声音（美术留待后续）；原版铁傀儡的声音（美术明确"沿用原版"）；表情动作（用户已暂停）；台词/对话（这些是**无语言的短声**，`ai-chat-mod` 是另一个项目）；按职业分声；音量与混音选项（用原版设置）；九张物品图标与七类设施标识（见 §8） |
| 不新增 | 不新增方块、不新增物品、不升 schema 版本 |

## 1. 现状（已核对）

- **模组目前零音频基础设施**：`src/main/resources/assets/goblin_settlement/` 下**没有** `sounds.json`、**没有** `sounds/` 目录；全仓 grep `playSound` / `SoundEvent` **0 处命中**。所以"注册声音"这条线是本轮第一次开。
- **两个实体都没有覆写任何原版声音钩子**：`GoblinCitizenEntity` 与 `GoblinGolemEntity` 里 `getHurtSound` / `getDeathSound` / `getAmbientSound` 一个都没有（grep 0 处）。受击与死亡因此走的是原版默认逻辑。
- **不存在任何"碰面/招呼"行为**。`social/` 包里是 `RelationshipCoordinator`（关系）、`GiftTradeCommands`（赠礼交易）、`WarehouseWithdrawalObserver`（取物目击），都不涉及居民相遇。
- **交付的资源结构**：40 个 ogg = 哥布林 2 性别 × 5 类 × 2 变体（`sounds/goblin/{male,female}/{greeting,response,work,hurt,death}_{01,02}.ogg`）+ 五阶傀儡 × （3 移动 + 1 核心）（`sounds/golem/{wood,stone,gold,diamond,obsidian}/{move_01..03,core_01}.ogg`）。**逐个核对过**：`sounds.json` 里每一条 `goblin_settlement:…` 引用都与实际文件名一一对应，导入时不需要改它。
- **字幕**：20 条，键形如 `subtitles.goblin_settlement.entity.goblin.male.greeting`。**交付包只给了中文**（`merge/zh_cn_subtitles.json`），英文要由代码侧补写。
- **可用钩子（本轮要用到的）**：`GoblinCitizenEntity.DATA_FEMALE`（同步到客户端的性别，`:69`）、`isChild()`（`:603`）、`femaleForRender()`（`:613`）；`GoblinGolemEntity.tier()`（服务端，`:73`）与 `tierForRender()`（客户端镜像，`:92`）；`ResidentWorkLookup.loaded(ServerLevel, SettlementSavedData)`（`:16`，既有的名册遍历口径）；`GoblinSettlement.tickSettlement` 里 `SettlementProfiler.run("名字", () -> 协调器.tick(level))` 的接线形状（`:220`、`:244`）。
- **已对本版本核实的 API**（写代码时照这个，不要按旧版本记忆写）：`SoundEvent.createVariableRangeEvent(Identifier)`；`Registries.SOUND_EVENT`；`AbstractTickableSoundInstance(SoundEvent, SoundSource, RandomSource)` 带 `isStopped()` 与 `stop()`，其父类 `AbstractSoundInstance` 有 `protected boolean looping`；`SoundManager.play(SoundInstance)` 与 `stop(SoundInstance)`；`ClientTickEvents.END_CLIENT_TICK`（`fabric-lifecycle-events-v1` 已在依赖里）。

## 2. 资源与注册

**资源**：40 个 ogg 按交付包原路径复制进 `src/main/resources/assets/goblin_settlement/sounds/`；`sounds.json` 原样复制到 `assets/goblin_settlement/sounds.json`。

**注册**：新增 `dev.local.goblinsettlement.sound.ModSounds`，20 个 `SoundEvent` 常量，写法照 `ModBlocks` 的既有形状（`Registry.register(BuiltInRegistries.SOUND_EVENT, key, SoundEvent.createVariableRangeEvent(id))`，`key` 由 `Identifier.fromNamespaceAndPath(MOD_ID, …)` 造）。事件 id 与 `sounds.json` 的键**逐字一致**（`entity.goblin.male.greeting` 等）。

**为什么注册 20 个常量而不是按需现造**：`sounds.json` 已经是权威清单，把它的 20 个键照抄成常量，让"有哪些事件"只有一个出处；`SoundsCheck`（§7）再把这个一致性钉住。

## 3. 声音的挂点

| 声音 | 挂在哪 | 谁播 | 说明 |
| --- | --- | --- | --- |
| 哥布林 受击 | 覆写 `getHurtSound`，按 `DATA_FEMALE` 选男女 | 原版 | 原版在受伤时自动调用 |
| 哥布林 死亡 | 覆写 `getDeathSound`，同上 | 原版 | 同上 |
| 哥布林 工作号子 | 居民真正推进工作之后，本人冷却 `1200` tick 已过则响一次 | 服务端 | 变体由 MC 在 `sounds.json` 的两个音里自选，不额外掷骰 |
| 哥布林 招呼 / 应答 | §5 的碰面协调器 | 服务端 | |
| 傀儡 移动 | 迈步时按 `GolemTier` 选该阶 | 服务端 | 五阶各 3 变体，MC 自选 |
| 傀儡 核心 | §4 的客户端循环 | 客户端 | 按 `DATA_TIER` 选 |
| 原版铁傀儡 | —— | —— | **不动**，沿用原版 |
| 儿童 | 用成年声音、按 `DATA_FEMALE` | —— | 美术把儿童专属声音留到后续 |

**一次性音效一律走服务端 `level.playSound(...)`**，因为服务端会把它广播给范围内所有玩家——这样"谁在什么时候出了什么声"对所有人是同一件事，是原版的模型。只有核心循环走客户端（§4）。

**为什么工作号子挂在"真正推进了工作"之后**：居民的工作推进已经有三档节流（职业系统那轮定的 10/20/30 tick），挂在它下游就不用自己再造一个节拍；被节流掉的 tick 也就不会响。

## 4. 傀儡核心循环（客户端）

**做法**：客户端每 tick 遍历当前世界里**已加载的自制傀儡**（`ClientLevel.entitiesForRendering()`，已核实存在），为每一只维护一个 tickable `SoundInstance`；实例的 `looping` 置真，音按 `tierForRender()` 选该阶的核心音；`isStopped()` 在实体被移除、死亡或不再加载时返回真，让声音引擎自己卸载它。每 tick 也顺手清掉实例表里那些实体已经不在了的条目，免得表随着傀儡生死无限增长。

**为什么不用服务端每 4 秒重发一次**：

| | 客户端循环（选它） | 服务端重发 |
| --- | --- | --- |
| 接缝 | 原生循环，听不出 | 4 秒一次，接缝明显 |
| 停止 | 实体消失即停 | 要额外发停止包，否则一直响 |
| 网络 | 零 | 每只傀儡每 4 秒一个包，永久 |

**生命周期交给声音引擎而不是自己调度**：核心音是"活着就一直响"（用户 2026-09-30 定），所以不存在"什么时候开始/结束"的玩法判断，只有"实体在不在"——那正是 `isStopped()` 能回答的。代码里因此**没有**一个自己的循环定时器。

**为什么放在客户端而不放进实体类**：`SoundInstance` 是纯客户端类型，而 `GoblinGolemEntity` 在 `src/main`、要跑在专用服务端上。放进实体类就得把客户端类型引到服务端代码里。所以循环由一个**客户端侧的 tick 钩子**驱动，按实体 id 管理——这也与既有做法一致（模组的客户端代码都在 `src/client` 下）。

## 5. 碰面行为

**新增 `dev.local.goblinsettlement.social.GreetingCoordinator`**，接线照 `tickSettlement` 里既有的形状（`SettlementProfiler.run("greeting", () -> GreetingCoordinator.tick(level))`）。

**行为**：每 `GREETING_INTERVAL_TICKS = 100`（5 秒）扫一遍**已加载居民**（走 `ResidentWorkLookup.loaded`，与避难、巡逻同一口径），在 `GREETING_RADIUS = 3.0` 格内找**第一对双方都不在冷却里**的居民，按 id 序取第一对；**id 小的一方招呼**，另一方在 `RESPONSE_DELAY_TICKS = 12`（约 0.6 秒）后**应答**。每人招呼后进入 `GREETING_COOLDOWN_TICKS = 600`（30 秒）冷却。

- **不看工作状态**：戴着东西路过打个招呼是正常的，而且刻意**不去碰施工/派工那条链**（项目在那上面栽过两次）。招呼只发声，不动导航、不动工作阶段。
- **应答需要一点延迟**：两声同一 tick 起会听着像互相抢话。协调器为此记住"谁该在 12 tick 后应一声"。这份表是**会话内内存态、不落盘**（与 `SettlementProfiler`、取物目击观察表同类）。
- **选对规则是纯函数**：`GreetingRules.pick(...)` 吃 `(id, x, z, 上次招呼时刻)` 列表，输出该由谁招呼、谁应答，**不碰世界、不碰存档**，与 `HousingAssignment` 同形，因此可独立检查（§7）。
- **三处并列都要确定性破序**（按 id），否则同一份输入会给出不同结果，检查就钉不住。

**上面四个数字都是发明值**：美术只给了"约"与"可连续循环"，没给频率、半径或冷却。源码注释会照项目惯例标注"没有依据、待实测重定"，并收进 `TEST_CHECKLIST.md` 第 9 组。

**应答待办表的边界**（不落盘的代价，要在实现里逐条处理，不是"不会发生"）：

- **应答者在这 12 tick 里死了、被移除了、或走到远处**：跳过这一声，不补发、不报错。
- **应答者掉线再重连**：表是会话内的，条目按实体 id 记，重连拿到的是新 id，旧条目自然失效并被清理。
- **服务端重载/退出**：表随进程消失，最坏结果是少听一声应答。
- **一次只处理一对**：同一轮里不做多对，避免一屏居民同时开口。

## 6. 字幕与语言

`sounds.json` 的每条事件都带一个 `subtitles.goblin_settlement.*` 键，共 20 个。

- **中文 20 条**照 `merge/zh_cn_subtitles.json` 并入现有 `zh_cn.json`（**保留现有条目**，只追加）。
- **英文 20 条由本轮补写**，与中文一一对应。不写英文的话，英文客户端在字幕里看到的是**原始键名** —— 就是公告牌那轮 `FOOD` 的同一类毛病，而这次有机械检查兜住（§7）。

## 7. 测试策略

### 7.1 可纯测（新增一项独立检查 `soundsCheck`，检查项 23 → 24）

1. **清单声明的 20 个事件正好是二十个，且每个都取得到**：断言 `sounds.json` 的键数，并断言**查找辅助方法能取到的 id 集合与清单一模一样**（多一个少一个都失败）。**这条不碰 Minecraft 注册表的写路径**——`Bootstrap.bootStrap()` 会**冻结** `BuiltInRegistries`，注册只能发生在**模组初始化**时（读注册表可以，注册不行；`ConstructionMaterialCheck` 只读，也照样 bootStrap）。为此 `ModSounds` 把**造对象**（`delivered(...)`，不碰注册表）与**注册**（`initialize()`，模组初始化时调一次）**拆成两步**——类的初始化因此不碰注册表，独立检查才加载得动它。能在这里钉住的是**id 不许漂移**，而辅助方法只可能返回注册产出的对象，所以 id 一漂它就失败。
2. **资源与清单一致**：`sounds.json` 里引用的每一个音档路径，在模组资源里都存在。这条读的是**随 jar 发布的真实资源**（与 `BlueprintCheck` 直接解码发布数据文件同形）。
3. **字幕键全覆盖、且无多余**：`sounds.json` 里每个 `subtitles.*` 键，**中英两个语言文件里都必须有**；反过来，语言文件里也不该有 `subtitles.goblin_settlement.*` 的孤儿键。**这条是本轮最有价值的** —— 它是"翻译缺口在构建期就报错"的第一条机械检查，而上一次同类毛病（`FOOD`）是只能在游戏里被用户看到。
4. **`GreetingRules.pick`**：冷却内不选、距离外不选、并列时按 id 确定性破序、无人合格时返回空。

### 7.2 只能进游戏听

- 核心循环**有没有接缝**、傀儡被移除/死亡时**是否真的停**。
- 招呼**是不是太频繁**（30 秒冷却、3 格半径够不够）。
- 五阶移动音**认不认得出是不同阶**。
- 整体**会不会太吵**（8 只傀儡一直在循环 + 18 名居民打招呼）。
- 男女声音**听得出区别**（美术要求"样音必须试听确认，不以频谱代替听感"）。

## 8. 明确未做（看到别当缺陷）

- **儿童专属声音**：美术留待后续；儿童用成年声音按性别。
- **原版铁傀儡**：一行不动。
- **表情动作**：用户已暂停。
- **台词/对话**：这些是**无语言的短声**，不是语音内容；`ai-chat-mod` 是另一个项目，不碰。
- **按职业分声**：七个职业共用同一套性别声音。
- **音量/混音选项**：用原版设置。
- **九张物品图标与三维手持道具**：独立一轮。美术明确"二维图标 ≠ 三维手持道具"，且"七职业无手持工具"是文档里记了很久的边界，那一轮要决定"换不换原版工具"。
- **七类设施标识**：独立一轮，**而且那一轮之前得先决定它贴在什么上** —— 美术的原话是"按标识放在标牌、装饰面或界面；不等于七个设施方块已经实现"。在决定落点之前接了也只是把七张 PNG 放进目录、没人看得见。
