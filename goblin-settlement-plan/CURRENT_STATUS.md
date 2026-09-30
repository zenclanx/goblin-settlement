# 当前状态：哥布林模组接续入口

更新日期：2026-09-30 13:55 +08:00（**第七十七轮：音效接入——已实现，24 项独立检查全绿，待游戏验证**）。详细历史仅追加到 UpdateLog.md。

## 接续须知（先读这段）

- **分支**：所有工作直接提交到 `main`（第四十二轮起因用户要求不再另开分支）。第五十七～六十二轮的逐一提交指针见 `UpdateLog.md` 各轮段（**不必逐条背**）。**第七十六轮的实质提交是 `bbf8147`（设计）、`064f0a0`（实现计划）与实现（9 个任务 + 逐任务审查修复，`a7dc836`…`00beea1`，含若干只改计划/文档的提交）**，逐条说明见 `UpdateLog.md` 本轮续段（不在此逐个背哈希）。**第七十七轮（音效）的提交依次为 `5ba4440`（设计）、`5b107fe`（计划）、`6837479`（开工前修掉计划里两处缺陷）、`4e0bb68`（资源/`ModSounds`/字幕/`soundsCheck`）、`ea87eb3`（按实现者发现改计划：检查不与注册表为敌）、`496e3c5`（`ModSounds` 拆成"造对象"与"注册"两步，含把反射 hack 删掉）、`c4344ef`（更正控制器自己两处判断）、`6c20410`（一次性音效挂点）、`49e772f`（傀儡核心客户端循环）、`9864bc9`（碰面招呼）、`56ac644`（更正计划里写错的一步名）**，逐条见 `UpdateLog.md` 本轮续段。**`main` 与 `origin/main` 同步、工作树干净；准确 HEAD 以 `git log -1` 为准。****第六十七～七十五轮的提交依次为**：`af572a7`/`b7d7dbc`（派工顺序修复）、`73c8406`（第 1 组日志）、`9d43ac6`（性能脚手架）、`1031857`（施工释放空闲工人）、`1ceec1d`（死亡归还 + 熔炼炉）、`13eadac`/`e56b7b1`（box-UV 修复 + 实机确认）、`f395b2c`（傀儡转铁 + 路巡检）、`8684cd7`（审计收口文档）、`dab6a6d`（女哨卫）——**逐一说明见 `UpdateLog.md` 各轮段，不必背**。更早各轮的代码收尾提交同样见 `UpdateLog.md`。**本机到 github 的网络目前是通的（直连即可，不必配代理）**，每轮提交后随即 `git push origin main`。远程仍留有 `claude/settlement-first-pass`（停在旧的 `a20e217`，本机已无同名本地分支）与并行线 `codex/settlement-v1`（本地 `08ddc4c` / 远程 `8b353f4`），两者都与当前主线无关。`main` 上依次叠着三批工作（职业系统、道路桥梁自主立项、住宅两轴升级链）及其后的逐轮改动。
- **这条分支上叠着的工作**：职业系统（12 任务）、道路桥梁自主立项（5 任务）、住宅两轴升级链（7 任务）——这三块各经过逐任务审查 + 全范围审查 + 修复；**「美术接入」**（第六十一～六十五轮 + 第七十五轮：成年男女 → 农民 → 其余六职业 → 儿童 → 五款傀儡 → **女哨卫**）；以及**「验证与修复轮」**（第六十七～七十四轮：本分支**第一次真的进游戏**，抓到并修掉两个静态检查看不见的缺陷，做了阶段 7 的性能脚手架与跨存储一致性审计）。
- **验证状态：24 项独立检查全绿；测试清单第 0 组与第 10 组已通过、第 1 组**只覆盖了渲染管线**，第 2–9 组与第 11 组未测。** 证据是编译（`./gradlew build --offline --no-daemon`）＋纯函数检查＋有限的实机复核。历次全范围审查确实抓到过多处**静态可见**的真缺陷（派工/执行裂脑、河岸长草导致架不成桥、无法服务的目标冻结扩地、建成房屋永久绑定工人、户级生育门死锁、傀儡等级未同步导致的客户端崩溃），都已修复。**但真正的教训是"静态检查看不见"的那一类**：第六十七轮第一次进游戏就抓到施工/交通/住宅三处的派工"先登记、再拿这条登记问自己是否空闲"，必然自我否决并回滚——**整条施工/交通/住宅派工链一次都没跑通过**；第七十二轮又抓到 box-UV 按减半尺寸展开，**22 个模型全部采错区域**。**两个都不是审查能发现的，是在游戏里、或把烘出的 UV 与美术图集对着看才暴露的。** **⚠️ 在跑完 `TEST_CHECKLIST.md` 之前不要把这一分支当作可用版本。**
- **下一步的落点（新对话从这里接）**：**第七十七轮「音效接入」已实现、待游戏验证（24 项检查全绿）——见下条第 0 项**；公告牌（第七十六轮）已实机验收通过（第 10 组 11/11）。其余几条路仍未开工，各自开工前仍应向用户确认。美术侧**除「特殊表情动作」（用户已要求暂停）外，模型与音效已全部接入运行时**——成年男女（`ART_INTEGRATION_DESIGN.md` §10）、七职业（`PROFESSION_ART_DESIGN.md` §7）、儿童（第六十四轮）、五款傀儡（第六十五轮）、**女哨卫**（第七十五轮）、**40 段音效 + 20 个事件/字幕**（第七十七轮）；占位模型 `GoblinModel`/旧 `GOLEM_LAYER`/`goblin_golem.png` **已退役**。剩下几条路：
  0. **音效接入——第七十七轮已实现、待游戏验证**：设计 [AUDIO_DESIGN.md](AUDIO_DESIGN.md) 与计划 [AUDIO_PLAN.md](AUDIO_PLAN.md)（**5 个任务**）均已提交；**5 个任务全部实现，完整构建通过、24 项独立检查全绿**（比上轮多一项 `SoundsCheck`：20 个 id 不漂移、音档存在、字幕键中英全覆盖无孤儿、碰面纯规则）。本模组**第一次有声音**（此前全仓 `playSound`/`SoundEvent` 零处命中）：40 个音档与 `sounds.json` 原样接入、**20 个 `SoundEvent`** 注册，受击/死亡/工作号子挂居民、五阶移动音挂傀儡、**傀儡核心走客户端 `SoundInstance` 无缝循环**（零网络、实体消失即停，不是服务端每 4 秒重发），并补上 GAME_DESIGN §11 早有设计但未做的**「碰面」**（3 格内相遇，id 小者招呼、另一人 12 tick 后应答）。**⚠️ 未做任何游戏内验证**（未启动游戏、未跑服务端、未碰存档），已通过的只有编译、24 项纯函数检查与审查者读码。**只能进游戏听、本轮未验的**（已收进 `TEST_CHECKLIST.md` **第 11 组**）：核心有没有接缝、傀儡被移除/死亡（含区块卸载）时是否真的停、招呼会不会太频繁、五阶音认不认得出、整体会不会太吵、男女声听不听得出区别；**外加一条注册冒烟**——24 项检查**没有一条覆盖"注册本身"**，删掉 `ModSounds.initialize()` 检查仍全绿，只有真机放一次音才验得了。**计划与控制器各自错过，均已更正并记入 `UpdateLog.md`**：计划的 `SoundsCheck` 按原样跑不起来（`bootStrap()` 冻结注册表），实现者用反射清 `frozen` 绕过、**被控制器否决**，最终删掉冗余断言并**把 `ModSounds` 拆成"造对象（`delivered`）"与"注册（`initialize`）"两步**；工作号子冷却原写成被节流 tick 递减的倒数、**会把 60 秒拖成十分钟**（控制器开工前扫描抓到）；计划 Task 4 Step 5 的 Gradle 任务名写错（`noticeboardCheck`→`soundsCheck`）、断言块漏一个 import（两处都由实现者抓到）；**控制器自己还错了两回**——先断言 `ModSounds` 不必改（实为必须改）、后又断言检查不再需要 `bootStrap()`（实为仍需要，断言走 `GolemTier` 会拉进原版 `Items`）。
  1. **公告牌方块——第七十六轮，已实现并完成实机验收（`TEST_CHECKLIST.md` 第 10 组 11/11 通过）**：设计 [NOTICEBOARD_DESIGN.md](NOTICEBOARD_DESIGN.md)（§0 范围、§6 方块、§7 营地、§10 测试策略）与计划 [NOTICEBOARD_PLAN.md](NOTICEBOARD_PLAN.md)（**8 个任务**，原分 9 个、开工前把"建形状"与"接线"合并了）均已提交；**8 个任务全部实现，完整构建通过、23 项独立检查全绿**。这是本模组**第一个自定义方块、第一个物品、第一条网络通道**（此前 `onInitialize` 只注册两种实体、全仓零处 `Registries.BLOCK` / `Registries.ITEM` / `CustomPacketPayload`）。用户已定六处：GUI 面板阅读、只做只读状态、创造物品 + 营地自动放、分区列表排版、食物区兼显示"可支撑时间"、诊断粒度与 `status` 同等。**✅ 已做游戏内验收（2026-09-30，本轮首次进游戏）——`TEST_CHECKLIST.md` 第 10 组 11 条全部通过**：放置朝向（正面正对玩家）、两个碰撞形状、创造栏条目与图标、末地无聚落不崩、面板六区与滚动、中英两套文案、库存"已知"标签、交通两行中文化、`status` 八行真机逐字核对（含 `No pending traffic target` 无前缀）、掉落表被认到、服务端启动无异常。**营地两条另起新世界补测**（`goblin-camp-verify`，已保留）：营地自动生成、**牌子出现在 `corner.offset(1,0,0)` 且 `adults=8`**（八人齐全，**实机坐实"初稿那个格子是生成位、会把营地卡在 7 人"**）、抹掉后 40 秒以上**不长回来**、用户判断**没挡住居民**。**验收中实机抓到并修掉一处真缺陷**：材料区显示未翻译的枚举名 `FOOD`（全范围审查曾把它列为"可留"，实机才暴露）——已改为传枚举 + 翻译键，`status` 输出逐字未变，并补断言 + 变异测试；提交 `e6afac5`。**唯一未确证**：面板下半部"淡重影"（源码上正文被 `enableScissor` 裁在框内、不可能溢出，用户两次复看未再报告，按半透明面板透出世界结案）。**四条约束未推翻**：①公告牌**不进** `layoutComplete` 的硬闸（放在首批补给那一步、共用一次性标志）；②**不放在通行路径上**（营地摆在西北角柱旁 `corner.offset(1,0,0)`、朝南，避开了 8 个居民生成位与 `z=3`/`z=4` 走道——**初稿给的 `corner.offset(0,0,4)` 正是生成位，已改**）；③`status` 的输出文本**逐字不变**（八行由构建期断言钉死，另补一条"无待办交通目标"空分支断言）；④面板**不自己算任何数字**，一律走共享的 `SettlementReport`。**全范围审查后的修复波（同一轮，不改设计）**：面板补上设计 §9 要求的那句「蓝图数据不可用」（与 `status` 逐字同一句）；交通区不再直接塞 `status` 的成句，改由事实拼翻译键 + 原始参数（`TransportFacts.BrokenLink.blocked` 由中文字改成布尔，中英两套语言因此都能用）；库存五项标签补"已知"（数字是下界）；`SettlementText`/`TransportLinks` 此前无断言的分支（`incomplete`、蓝图句、`Homes: none`、`+N more`、`Trades: no adults`、无路、无链接、整条未连通含`受阻`/`缺口`）全部补上整行断言；`TransportLinks` 的两处列表改 `List.copyOf`（与 `SettlementReport` 一致）。**检查项仍 23 项**（扩充既有 `NoticeboardCheck`，不新增任务）。**全范围审查留下两条未采纳的建议（非缺陷，另排）**：①把"翻译键 ↔ 参数个数"的核对**做成一条检查**——Task 6 的审查是手工做的这件事（48 个键 × 两套语言 × 逐个占位符），一次就抓出该 presenter 的全部五处缺陷；机械化成检查可永久关掉这一整类 bug。②发 payload 前加 `ServerPlayNetworking.canSend(player, TYPE)` 守卫——本模组两端都有模组，今天不是活的缺陷，但一行即可挡住"客户端没声明该通道"的情况。
  2. **继续统一验证（推荐）**——第 0、1、10 组已过（**第 1 组只覆盖了渲染管线**：模型站地、职业可辨、傀儡发光不串皮；儿童与旧档 IRON 两项因无法构造而未覆盖），**接着跑第 2 组起**。清单：[TEST_CHECKLIST.md](TEST_CHECKLIST.md)。
  3. **阶段 7 的"可见性"两件——均已完成**：①**性能度量脚手架**（第六十九轮）——`diagnostics/SettlementProfiler`（无 MC 类型、静态、会话内、默认关闭且关闭时零成本）、`tickSettlement` 里 23 处协调器调用全部计时、`goblinsettlement profile on|off|report`、第 22 项检查 `profileCheck`；②**跨存储一致性审计**（第七十轮，只读）——列出的 **8 条已全部收口**（5 条修代码、3 条以文档结案，见第七十一～七十四轮）。**⚠️ 脚手架尚未在游戏里取数**，因此**未据此做任何优化**——"成熟城镇卡在哪一环"仍需实跑才答得出。**"为批量替换模型做接口"一项经用户判定搁置**（同名重做只需跑两条生成器命令、零处改代码）。
  4. **box-UV 缩放映射——已修并实机确认（第七十二轮）**：美术组两次点名复核的那条**成立**——生成器把 `addBox` 尺寸乘 0.5 而 `texOffs`/分辨率不变，而 Minecraft 的盒式展开**按 `addBox` 尺寸**铺开，于是**每个面只采到美术为它画的区域的左上角四分之一**（烘出实测头块 maxU `0.11 → 0.2188`，正是美术分配的 `56/256`）。**修法**：生成器**不再缩放**（与图集逐 texel 精确对应、偏移全整数），0.5 移到渲染期绕地面线施加（`GoblinBodyModel` 根 `PartPose`）；`ArtModelCheck` 新增一条根位姿断言。**⚠️ 第六十八轮"模型怪是美术的事、退回美术组重做"的判断据此作废——错在生成器，美术的图集一直是对的。** 已开游戏实机复核（19:12）：修前只是色块、修后近景哥布林五官齐备、傀儡显出护甲板与晶体切面。相关设计文档已在第七十四轮就地更正。
  5. **补功能缺口（与美术无关）**：**阶段 7 的成熟城镇性能方案与跨存储异常恢复方案**（完全未开工，是七阶段无法收尾的唯一原因）；交通的**成熟期多工程并行**（架构级，现有整套互斥链建立在"同时只推一项工程"上）；住宅的**公共设施、实体公告牌方块、历史保留**（公告牌美术外观已交，只剩代码侧：注册/状态/放置/交互/动态信息）。
  6. **外部纯函数**：F002、F003 **已验收**（快照在 `function-bank/accepted/`，**尚未接入运行时**，接入时填路径）；**F004、F005 验收未通过、经用户决定不再采用**（失败都在提交方自带的检查程序里，实现本身验过是对的）。
- **美术侧已全部补齐、等代码侧接入**（2026-09-29 交付包 `Models/handoff/art_handoff_20260929.zip`，161 文件 + `handoff/README.md` 接入说明）：**女哨卫——✅ 已接入（第七十五轮）**：贴图 1024×1024 已进资源、`MODELS` 与 `GoblinBodies.BODIES` 各加一行、重跑裁剪与生成器；`ArtModelCheck` 由 17→**18** 份裁剪件、23 个烘制模型，且**通过了当初抓出该缺陷的那条 UV 断言**；既有 22 个生成类与 17 份裁剪件**逐字节未变**。**⚠️ 尚未开游戏看过**——测试世界里正好有一名女哨卫，此前回落基础女体型、现在应穿上哨卫装。**其余仍待接入（均为功能轮）**：**整套 40 段音效 + 20 个事件/字幕——✅ 已接入（第七十七轮）**（注册与选择在代码侧；傀儡核心是 4 秒**可循环**素材，由客户端 `SoundInstance` 循环——`sounds.json` **不会自动循环**；**未做游戏内验证**）、**九张物品图标 + 模型 + 显示入口**（ID `hoe/axe/pickaxe/builder_hammer/artisan_hammer/shield/baton/crate/wooden_rabbit`；**本批不擅自注册物品**，二维图标 ≠ 三维手持道具）、**七类设施标识**（仅贴图，**不等于七个方块已实现**）、**公告牌——✅ 已接入并通过实机验收（第七十六轮，`TEST_CHECKLIST.md` 第 10 组 11/11）**（资源 ID `noticeboard`、四向 `facing`、两个碰撞形状、右键面板与营地自动摆放均已实现并验过；模型正面朝北，**实机确认朝向与之一致**）。**美术侧另标一条高优先级复核**（生成器对 `addBox` 缩放而 texOffs 不变）——**已于第七十二轮查证并修掉**。
- **已知边界（测试时看到别误报）**：七职业无手持工具；儿童用成年声音、按性别（美术把儿童专属声音留到后续）；儿童碰撞箱仍是成人尺寸（有意）；成人/傀儡模型横向超出碰撞箱 0.23 格（有意）；组合式外观拆不下配饰；旧档 IRON 傀儡在迁移前**短暂不渲染**（约 5 秒，刻意：不画胜过崩、胜过穿错皮）；住户数与避难占用都**不预留**（同 tick 可能超员 1）。完整清单见 `TEST_CHECKLIST.md` 末节。
- **两个发明常量待实测重定**：`TRAFFIC_PER_LANE = 200` 与 `SAMPLE_INTERVAL_TICKS = 100`（设计只给道路宽度、不给数字；源码注释均已标注"没有依据、待实测重定"）。同类发明值还有石桥的圆石下限、哨卫牵引绳 8 格 / 攻击力 2.0 / 目击半径 12、住宅分配协调器 40 tick / 预算 8、**第七十七轮的五个音效发明值**（招呼半径 3 格、招呼节拍 100 tick、招呼冷却 600 tick、应答延迟 12 tick、工作号子冷却 1200 tick）。**统一收在 `TEST_CHECKLIST.md` 第 9 组。**
- **本地游戏验证通道（第七十轮建立，可直接复用）**：专用服务端 `./gradlew runServer`（工作目录 `run/`，**RCON 已开**，端口 25595，密码见 `run/server.properties`；**`online-mode=false`** 以允许离线客户端）；命令通道是 `run/rcon.py`（Python，**`run/` 已 gitignore、不进版本控制**）。客户端**不能**用 `runClient`（与正在跑的服务端争同一个 `run/` 与 Gradle 锁），要**直接用 java 起这个实例**：参数在 `.minecraft/client-args.txt`（**关键三坑**：本版本 `--server`/`--port` 已被忽略、要用 `--quickPlayMultiplayer 127.0.0.1:25595`；含空格的参数在 argfile 里必须加引号；`fabric-loader`/`intermediary`/`sponge-mixin` 在版本清单里没有 `downloads.artifact`，要按 Maven 坐标推路径）。**`JAVA_HOME` 是 JDK 21，PATH 里的 `java` 是 17、会误导**。**分工：逻辑类由我跑服务端，GUI 与观感判断归用户。**
- **开发方式约定**：先出设计文档（`*_DESIGN.md`）→ 再出实现计划（`*_PLAN.md`）→ 逐任务实现 + 逐任务审查（子代理逐任务）。**但对"照着上一轮重复"的轮次不必走全流程**——第六十三轮已验证：一个实现任务 + 一次审查即可（六职业约 14 分钟，对比首轮的约 50 分钟、8 次子代理调用）；文档收尾也可以并进代码轮，不必单开一轮。**经本轮验证的补充**：给子代理的规格**必须先把前提自己核实过**——第七十二与七十三轮各有一条回归是"规格写错、实现照做"；子代理**主动指出规格矛盾**时应先自查再发回改。

## 本轮执行约定

先完成七阶段计划的测试前初版，全部计划内容的代码完成后再统一测试；后续提交到 main。第四十二轮起因用户指定直接在 `main` 上开发。验证深度为编译 + 项目自带独立检查，不启动游戏、不跑专用服务端、不动常用存档。职业系统（任务 1–11 的代码 + 完整构建收尾）、道路桥梁自主立项（交通 5 任务）与住宅两轴升级链（住宅 7 任务）均已接入候选；第四十二轮收敛了床位判据的重复实现，第四十三轮细化了住宅决策规则（缺床时饱和房子不动），第四十四轮把住宅五级几何迁到受校验的数据文件，第四十五轮完成材料泛化（工人按每步声明的物品施工），第四十六轮让数据文件带三套风格（更多蓝图），第四十七轮收口了派工服务，第四十八轮给哨卫加了名额上限，第四十九轮哨卫有了巡逻工作，第五十轮哨卫能在巡逻中报警，第五十一轮哨卫能有限自卫，第五十二轮完成引导避难（哨卫四项职责全部落地），第五十三轮补上避难所的有墙与容量判定，第五十四轮把道路的通行量量了出来（不加宽），第五十五轮把够格的路真的加宽（2 → 3 → 5），第五十六轮补上道路与桥梁的连通验收（桥开通前必须可跨越、路必须真的接上设施），第五十七轮把通用施工路径的材料也泛化（住宅与那条线共用一份 `BuildMaterial`），第五十八轮加了石桥（圆石结构、跨度 13–24）并把桥种判据收成一个 `isBridge()`，第五十九轮补上改建安全（卡住时说得出为什么 + 床头净空断言；"新床不占蓝图格"一项经终审认定为恒不生效的冗余保险、并非补上的缺口）。**第六十轮把住户归属落到居民名册**（`ResidentRecord.home`，旧档读作无房、不升 schema）、**按一条纯规则分配**（`HousingAssignment`，本轮第 20 项独立检查）、**把生育门落到"聚落里还有登记的房有空位"**（无可判定的房子时回落聚落级判据；全范围终审发现初版的"母亲那栋房"会死锁、经用户裁决改为聚落级），并让 `status` **逐栋显示住几人/容量与无房人数**。**第六十一轮把美术已完成的三维模型接进运行时**（成年男女两套：生成器 `tools/generate_models.py` + 进仓裁剪件 + 生成的 `GoblinMaleModel`/`GoblinFemaleModel` + 共享手写 `GoblinBodyModel` + 第 21 项独立检查 `artModelCheck` + 按性别选模型与贴图 + 删掉八张旧 64×64 贴图），七职业外观暂时一致（本轮唯一可见退步，用户已确认）。**第六十二轮把第一套职业外观接进运行时**（农民男女：`MODELS` 表两行 + 两张 1024×1024 贴图 + **一张表 `GoblinBodies.BODIES` 同时驱动注册处/渲染器/独立检查**，其余六职业与 `UNASSIGNED` 刻意回退基础身体）。

## 阶段定位

- 阶段 1—2：独立工程、基本存档权限、单居民从真实箱子取料施工，代码与较早游戏验证均已有。
- 阶段 3：多工地与死亡/取消/卸载恢复有较早验证；取消/死亡后计划永久占用工人的卡死修复有纯函数检查覆盖，未在游戏中复验。
- 阶段 4：农业、食物与木工具、林业、家庭和人口约束扩地、采矿井与冶炼调度、职业系统有候选代码。职业系统已完成完整构建 + 独立检查；第四十七轮把 8 处重复的挑人代码收进 `colony/WorkerDispatch`；第四十八轮给哨卫加了名额上限（每 12 成人 1 名、最多 4 名）；**第四十九轮哨卫有了巡逻工作**（`WorkKind.PATROL`，一次一名沿设施点巡逻），"无工作职业按通才对待"那条临时规则随之到期；**第五十轮哨卫能在巡逻中报警**（目击 `Monster` 即惊动附近傀儡，目击半径 12 是傀儡防卫半径 24 的一半）；**第五十一轮哨卫能有限自卫**（被攻击时只打打它的那个目标、只在 8 格内、只持续 15 秒）；**第五十二轮完成引导避难**（聚落级警戒状态 + 非战斗居民与儿童躲进最近的已登记房屋，哨卫守位）——**GAME_DESIGN 给哨卫的四项职责至此全部落地**，但避难不含逐户护送、容量与出入判定、以及避难所是否真有墙。**其余职业的名额上限仍缺**。未做游戏内验证。
- 阶段 5：道路桥梁自主立项（已服务设施登记、TRANSPORT 需求档与扩地互斥、修路/架桥纯判定、有界只读直线探测、提案协调器）有候选代码，完整构建 + 10 项独立检查通过；居民实际搬运施工已接入候选（立项后由 TransportCoordinator 既有路径施工）。未做游戏内验证。第五十四轮把通行量量了出来（纯判据 + 采样 + 持久计数 + status 显示），未加宽。第五十五轮把够格的路真的加宽了（链式加宽计划 + 中线入档 + 淘汰豁免），未做游戏内验证。第五十六轮补上连通验收（桥开通前必须可跨越、路必须真的接上设施，`status` 一行显示），未做游戏内验证。**第五十八轮加了石桥（圆石结构、跨度 13–24）并把桥种判据收成一个 `isBridge()`**，未做游戏内验证。
- 阶段 6：简易床位与顶棚、六个傀儡数值等级、关系处理、原版铁傀儡接入与赠予交易命令有候选代码；住宅两轴升级链（容量轴 × 品质轴）已接入候选，第四十四轮起五级几何与每步方块来自受校验的数据文件，第四十五轮起工人按每步声明的物品取料施工，第四十六轮起数据文件带三套风格（cottage / lean_to / side_porch）并按确定性偏好逐栋选择（完整构建 + 独立检查通过）；**能力已就绪，但三套风格仍全部只用橡木木板**——没有真实蓝图验证过多材质；第五十七轮把**通用施工路径**（命令驱动的 `ConstructionPlan` 线）的材料也泛化了，住宅路径与那条线现在共用一份 `BuildMaterial`；**第五十九轮补上改建安全**（"卡住时说得出为什么" + 床头净空断言；"新床不占蓝图格"一项经终审认定为**恒不生效的冗余保险、并非补上的缺口**），未做游戏内验证。**第六十轮给每栋房落下住户**（`ResidentRecord.home` + 一条纯规则分配 + 生育门落到聚落有空位；终审后由"母亲那栋房"改为聚落级），并把 `status` 改成逐栋显示住几人/容量与无房人数，未做游戏内验证。**第六十一轮起接入美术成品（成年男女两套）**：模型不再是占位、改由美术工程经生成器产出，客户端按同步的性别选模型与贴图，碰撞箱维持 `0.6 × 1.45`；七职业外观暂时一致、儿童与五款傀儡仍未接入，未做游戏内验证。**第六十二轮起按职业接美术外观（农民先行，一张表驱动）**：农民男女两套职业外观进运行时，`GoblinBodies.BODIES` 一张表被注册处、渲染器、独立检查三个读者共用，其余六职业回退基础身体，未做游戏内验证。
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

## 本轮接入的内容（第四十八轮：职业名额约束）

- **修正的偏差**：`ProfessionRules.scarcest` 每次选"当前人数最少"的职业，7 个职业因此被强制摊平，**第 7 个成人就会当上哨卫**；而 GAME_DESIGN 第 128 行要求哨卫"约每 12 名成年人 1 名、最多 4 名"。
- **名额表**：`ProfessionRules.ceiling(profession, adults)`——哨卫 `min(4, adults / 12)`，其余职业 `Integer.MAX_VALUE`。**只填文档写明的数**；给其余六个职业发明上限会是没有依据的玩法数值。
- **`scarcest` 改签名**：增加 `adults` 参数、跳过已达上限的职业、返回 `Optional<Profession>`（"所有职业都满"是名额表制度上可能的状态，只是今天表里只有哨卫有上限）。`ProfessionCoordinator` 传 `data.adultCount()`，为空时留作未定职（通才），下次 200 tick 再试。
- **名额只影响新分配**：不转岗（PROFESSION_DESIGN §7.1 与 §12 的既有决定）。所以人口降到 12 以下时已有哨卫**不会退出**，哨卫数可能长期高于当前名额。
- **可见的行为变化**：初始 8 人聚落改前会出现 1 名哨卫，改后 `ceiling(SENTRY, 8) == 0`，要等 12 名成人才放开第一个名额。其余职业不受影响。

## 本轮接入的内容（第四十九轮：哨卫巡逻）

- **`WorkKind.PATROL(Profession.SENTRY)`**：加进枚举后 `WorkKind.employs(SENTRY)` 变 true，PROFESSION_DESIGN §2.3/§4.2 那条"无工作职业按通才对待"的临时规则**自动到期**（设计当初就写明它会在巡逻落地时失效）。
- **到期的连带行为变化**：`WorkKind` 现已覆盖全部 7 个职业，那条规则对谁都不适用；哨卫做非本行的活（如农活）从"通才档"变为"离开本行的专才档"，**排在未定职之后**。`ProfessionRulesCheck` 的断言已从"哨卫是通才"改写为新真相，并补了一条 `matchRank(PATROL, SENTRY) == 0`。
- **纯层 `defense/PatrolRules.waypoints`**：锚点在前、设施按距锚点由近及远、并列按 x 再按 z、同坐标折叠；配 4 组独立断言与新的 `patrolRulesCheck` 任务。
- **`defense/PatrolCoordinator`**：单在途门禁（照采矿）+ `WorkerDispatch.nearest` 派工 + 每 tick 提供当前航点。**一次只有一名居民巡逻**；不做门控，任何可用居民都能被选中，哨卫只因匹配度高而优先。
- **实体**：新增 `WorkStage.PATROL_WALKING`、`workKind` 映射、`patrolIndex`（**瞬态、不入存档**）、`assignPatrol`/`hasPatrolWork`、`tickPatrolWork`。**巡逻不碰背包**（无取料/携带/交付/归还）。
- **航点来自存档里已有的设施点**（锚点 + 仓库 + 农田作物位置），**不需要新方块**；GAME_DESIGN 第 93 行的"巡逻点"方块不在本轮。
- **顺带更正**：PROFESSION_DESIGN §13 那条"`WorkStage -> WorkKind` 映射易漏、漏登记会静默按对口处理"**已不成立**——`workKind` 是穷尽 switch 表达式、无 `default`，漏登记会编译报错。本轮实测确认。

## 本轮接入的内容（第五十轮：哨卫报警）

- **增量**：既有链路只在**居民已经挨打**时才惊动傀儡（`RelationshipCoordinator` → `DefenseCoordinator.onResidentAttack`）。哨卫的报警是**看见就报**——威胁还在村外时防卫就开始。这是巡逻这项工作的价值兑现。
- **`GoblinGolemEntity.alertToSighting(LivingEntity)`**：与既有 `alertToResidentAttack` **同一组守卫**，只去掉需要受害者的三条（受害者存活、是本聚落居民、与傀儡的距离）。`isPermittedAttacker` 由 `private` 改**包内可见**以便同包复用，不复制第二份"谁算威胁"。两个方法成对，源码注释里写明改一处要看另一处。
- **`DefenseCoordinator.reportSighting(level, sentry)`**：找**最近的**可见 `Monster`（取最近是为了让"报谁"有唯一确定答案），对哨卫周围 `DEFENSE_RADIUS` 内的傀儡逐个上报，返回"是否惊动了至少一只"。
- **实体**：`tickPatrolWork` **最前**调用它（放在最前是因为到达航点会提前 return，而站在路口守望恰恰最该看四周），并把结果写进 `waitReason`——`/goblinsettlement work` 因此看得见，**不加新命令、不加新同步字段**。
- **边界一：只有 `Monster` 算威胁，玩家不算。** GAME_DESIGN 第 132 行点名「玩家携带武器、路过仓库或住在旁边不会自动成为敌人」；玩家只在确实攻击时经既有事件路径进入防线，那条路径**一行未改**（已用 git diff 核对）。
- **边界二：目击半径 12 = 傀儡防卫半径 24 的一半。** 哨卫只报看得见的，**是否出动仍由傀儡自己按 24 格、归属与存活规则决定**。这压低（不消除）"路过一只僵尸就全村出动"的概率；两者都只维持 15 秒后自动解除。
- **边界三：不惊动原版铁傀儡**——`VanillaIronGolemBridge.alert` 的签名需要一个受害者，目击场景没有。原版铁傀儡被攻击时本来就会自己还手。这是已知边界，不是遗漏。

## 本轮接入的内容（第五十一轮：哨卫有限自卫）

- **"有限"落在三条上**：**只打打它的那个目标**（不索敌、不换目标——看见怪物就冲上去是报警的职责）、**只打近的**（牵引绳 8 格，傀儡的 `DEFENSE_RADIUS` 是 24）、**只打一会儿**（15 秒，与傀儡的 `ALERT_TICKS` 同值，不是另造的数）。
- **目标来源**：`DefenseCoordinator.onResidentAttack` 已在居民受击时被调用，加一次调用把攻击者交给受害者自己决定——**要不要还击是居民自己的政策**（它知道自己的职业），协调器只负责"有人被打"这个事件。既有傀儡警戒逻辑一行未动。
- **居民加了 `ATTACK_DAMAGE` 与 `MeleeAttackGoal`**（并照傀儡的优先级重排：melee 1 / lookAtPlayer 2 / randomLook 3）。**这两样加在实体类上，所以每个居民都有**；但 `MeleeAttackGoal` 在没有目标时惰性，而**只有哨卫会被赋目标**（`retaliateAgainst` 里判职业）。
- **"谁算威胁"从傀儡搬到 `DefenseCoordinator` 并设为 public**，让警戒守卫与自卫判定共用一份，不再有第二个副本。已 diff 核对傀儡侧判定逐字等价。
- **脱战与让位**：居民 `customServerAiStep` 里维护目标生命周期（死亡/超距/超时→清目标），并**在打架期间跳过工作推进**——否则巡逻每 tick 的 `moveTo` 会和 `MeleeAttackGoal` 抢方向盘。跳过点在"取消检查之后、工作推进之前"，打架期间登记与职业同步照常。
- **两个数值是发明，源码注释里已标注**：牵引绳 8 格、攻击力 2.0（傀儡最低档 4.0 的一半）。15 秒沿用傀儡既有常量。

## 本轮接入的内容（第五十二轮：引导避难）

- **聚落级警戒状态**：`DefenseSavedData` 加 `alertTicks`（**剩余 tick**）与 `lastThreat`，都用 `optionalFieldOf`、**不升 schema 版本**。置位来自"有人挨打"与"哨卫目击且真的惊动了傀儡"两处，每次置位刷新成 15 秒（复用傀儡既有的 `ALERT_TICKS`）。
- **为什么存剩余 tick 而不是解除时刻**：存绝对时刻的话，玩家把该维度时间倒退（新世界、`/time set`）会让判定长期为真、**居民永久避难**。递减挂在 `tickSettlement` 的最前面、settlement 闸门之前，否则锚点区块未加载时警戒会冻结。
- **避难所 = 最近的已登记房屋**（锚点床位置），语义对、数据现成、不需要新方块。**选择规则是纯函数** `ShelterRules.nearest`（最近者胜、并列按 x 再按 z、无避难所返回 -1），**本轮唯一有独立检查的部分**。
- **避难行为**：居民用**独立的 `sheltering` 状态**让出导航而不动 `workStage`——警报解除后工作原地恢复，正在搬东西的居民带着材料去躲而材料不丢。**哨卫不避难**；**结算不到避难所就连活也不躲**（总得有人干活，无处可去时乱跑更糟）。
- **哨卫的"守位"**：警戒期间不走航点，改朝 `lastThreat` 移动并守住，**不新增寻敌**。这是"引导"的落点：站到威胁与村子之间，而不是逐户带路。
- **范围外（明确未做）**：逐户护送（用户否掉）、避难所容量与出入判定、避难所是否真有墙的判定（登记房屋在"棚"阶段只有柱子与顶）、儿童专属行为。

## 本轮接入的内容（第五十三轮：避难所质量）

- **补掉上一轮自己标出的两个缺口**：此前把"已登记房屋"当避难所（可能是只有柱子和顶的棚），且没有容量上限。
- **候选收窄**：房屋必须**容量轴 1 级几何已建成**（四壁 + 门洞）才算避难所。`HousingCoordinator` 新增 public 的 `shelterCapacity(level, home)`，一次答完"有墙吗 + 能躲几人"，**返回 0 即"不是避难所"**——调用方不必知道"第几级建完了"这种 housing 内部概念。
- **容量 = 已建成的床位数**（`HousingRules.builtCapacity`，1–3）。**借用而非发明**：同一个数既决定能住几人、也决定能躲几人。
- **选择规则吸收容量**：`ShelterRules.nearest` 升级为 `choose`（`Shelter` 记录带 used/capacity），**取代而非并存**——"最近"与"有没有空位"是同一个问题。最近的满了就让给次近，全满则返回 -1。
- **占用数**：实体加只读的 `shelterTarget()`（**只在真的在避难时返回位置**，否则默认值会被数成"瞄向原点那栋房"）；`ShelterCoordinator` 扫一遍名册数占用，**每次选择只算一次**。
- **名册遍历只留一份**：`ResidentWorkLookup` 加 `loaded(...)`，`anyLoaded` 改为复用同一段遍历（代价是不再短路；名册上限 64，可接受）。
- **明确未做**：占用**预留**（同 tick 两人可能选到同一间房而超员 1——已知竞态）；逐户护送（用户已否）。

## 本轮接入的内容（第五十四轮：通行量采样）

- 纯判据 `planning/transport/RoadUpgradeRules`：`LADDER = {2, 3, 5}`，`nextLanes` 取梯子上第一个更大的档、没有更大的档返回 5；`shouldUpgrade` 满档或非法宽度永假、越宽要得越多、至少一整份（`traffic >= TRAFFIC_PER_LANE * max(1, lanes - 1)`）。`TRAFFIC_PER_LANE = 200` 与 `SAMPLE_INTERVAL_TICKS = 100` 是**两个发明值**（GAME_DESIGN 第 7 节只给宽度不给数字），源码注释互相引用并写明没有依据、须按实测重定。
- 第 15 项独立检查 `RoadUpgradeRulesCheck`：档位推进、到顶不再变、阈值边界、越宽要求越多、满档与非法宽度永不升级、`BUILT_ROAD_LANES` 在梯子上。**（第五十五轮起该常量已由 `RoadUpgradeRules.baseLanes()` 与 `ladder()` 取代，检查里的两条对应断言随之改名——这一行描述的是第四十二轮…第五十四轮时的形状。）**
- `TransportSavedData` 加 traffic（计划 id → 命中次数，`optionalFieldOf`、不升 schema）、`revision` 版本号、`pruneTraffic`（构造与 `replace` 两处清理）——清理落在 `replace` 是因为 `MAX_COMPLETED_ROADS` 的淘汰在那里。
- `TrafficSampler` 每 `SAMPLE_INTERVAL_TICKS = 100` tick（5 秒）遍历已加载居民（上限 64），脚下铺面查表命中即计 1 次；表由已完成 ROAD 计划的 SURFACE 步织出，按 `(instance, revision)` 缓存（键用 `TransportSavedData` 而非 `ServerLevel`）。
- 显示：`goblinsettlement status` 加一行 `Road traffic: …`（命中数降序、并列按 id、最多 8 条、达加宽条件带 `*`）。
- **只量不加宽**：路会被判为"够格"但宽度不变——与用户约定好的中间态，不要读成功能已完整。

## 本轮接入的内容（第五十五轮：道路加宽）

- `RoadLayout` 与两处同源事实的合并：`planning/transport/RoadLayout` 成为"一条路的车道落在哪"的唯一出处（`laneOffsets` / `direction` / `clockwise` / `laneFeet` / `newLaneFeet`）；第一轮的 `BUILT_ROAD_LANES` 常量与 `startRoad` 里字面写的两条车道合并为 `RoadUpgradeRules.baseLanes()` + 每条计划的 `lanes`，`TransportCoordinator.roadDirection` 删除。
- `TransportPlan.road` 与链口径：`TransportPlan` 新增 `road` 字段（`widens_from` / `lanes` / `route`，`optionalFieldOf`、不升 schema），**中线入档**；链口径集中在 `TransportSavedData`（`roadWidth` / `roadTraffic` / `roads` / `wideningCandidates`），显示与提案都只调这四个。旧档无 `route` → 该路不加宽。
- 淘汰豁免与其上界：`trimCompletedRoads` 只退不在链上的已完道路（`isRetirable` 唯一判据），上界由 32 变成 `32 + 3×加宽过的路数`。
- READY 档提案与"不进需求档"：加宽提案只在需求档 `READY` 时动手，按 `wideningCandidates()` 顺序取第一个通过安全判定与冲突判定的立项；**加宽不进需求档**，被永久挡住的加宽不会冻结扩地，因此无需延期名单。
- 显示：`Road traffic:` 行按链显示 `id8=链计数和 L<宽度>[ *]`，一条加宽过的路只出现一次。
- 第 16 项检查：新增 `roadLayoutCheck`（`RoadLayoutCheck`），覆盖方向与顺时针、三档偏移表、直路 2→3/3→5、弯角超集、中线去重与退化行为；`TransportSavedDataCheck` 补链语义与 codec 往返。

## 本轮接入的内容（第五十六轮：连通验收）

- 纯判据 `planning/transport/TransportConnectivity.connects(walkable, from, goals)`：水平四邻（**对角不算**）+ **允许上下差 1 格**（台阶、坡都算一步，也让判据对地形更稳健），访问集把代价限在集合大小；`from` 不在集合、空集合、空目标均为假。第 17 项独立检查 `transportConnectivityCheck`。
- 存档层：`TransportSavedData.chain` 公开为 `chainOf`（链规则仍只在这一个类里），新增 `roadTarget(planId)`——沿链找第一个带 `targetFacility` 的成员，加宽计划自身不带目标、须按链回答。
- 装配（`TransportCoordinator`）：`bridgeConnects`（走格 = 桥的 `SURFACE`/`APPROACHES` 走格；近岸 = 四个 `barrierFeet` 中离锚点最近者、对岸 = 离它最远的两个）、`roadConnects`（走格 = **整条链**的 `ROAD_GROUND` 走格；目标 = 与链根 `targetFacility` 四邻含 y±1 的走格）、`structurallyComplete`（既有 `firstMissingStructuralStep` 的取反，不重写判据）。可行走 = 该处是空气，或该格是 `barrierFeet` 之一且那里是本方 `OAK_FENCE`——施工栅栏不算地形，判定因此只读、能排在清栅栏之前。
- 桥的开通闸：`tickPlan` 完成分支里 `firstMissingStructuralStep` 之后、`clearFinishedBarriers` 之前判连通，不连通就返回（不置 `open=true`、不清栅栏，`closedFeet` 继续挡人）。
- 路的连通只报告：`status` 新增 `Links: …` 行，统计已建成的路（按链）+ 所有步已铺完的桥（含未开通），后缀 `缺口`（结构性缺格，走既有 rewind）与 `受阻`（走格被外来方块占住，不重修）。不新增持久字段、不新增巡检节拍。
- **状态判定的区块闸**：判定读方块状态，每条链接判定前先过 `level.shouldTickBlocksAt(...)`，声明走格落在不可 tick 区块的链接跳过判定、单独计为 `n not loaded`——既不加载区块，也绝不把未判定的链接算进"已连通"。

## 本轮接入的内容（第五十七轮：施工路径的材料泛化）

- **材料的唯一出处 `construction/BuildMaterial`**：`OAK_PLANKS / OAK_LOG / OAK_FENCE / TORCH` 四个常量带 `block()` / `item()` 两张表；`TransportPlan` 删掉自己的 `Material` 枚举改用共享的这个，`TransportCoordinator` 的私有 `itemFor` / `blockFor` 删除。**不是给施工线另写一份枚举**——那会让"OAK_PLANKS 是哪个方块"变成第二份事实（第四十二/四十七/五十五轮各自收敛掉的那类重复）。**存档取值是常量名，旧档一字不改仍能读**。
- **计划声明建筑件**：`ConstructionPlan` 增 `material`（`optionalFieldOf` 默认橡木木板）⇒ 本轮前排队与第一轮的旧计划行为一字不变；`planTwoPlanks` 改名 `planStructure(start, material)`。
- **工人路径不新增持久字段**：`SettlementSavedData.materialFor(workerId)` 是"这个工人要铺什么"的唯一出处，`fetchMaterial` / `placeMaterial` 每次从计划重推；`carriedOakPlanks()` → `carried(Item)`；等待理由改成枚举名小写（`oak_planks missing`）。
- **协调器**：进度判定改 `plan.material().block()`，取仓改 `firstHolding(..., plan.material().item())`。
- **回收缺陷已修**：`DroppedMaterialLookup.find` 原先两个分支都写死橡木木板，泛化后会捡错材料；现在按计划声明的物品匹配，`RecoveryDrop.itemId` 改成 `entityId`（JSON 键仍是 `item_id`）。**调用点两处**：协调器的回收分支与 `GoblinCitizenEntity` 的回收走（经 `materialFor` 读同一份声明）——设计 §4 原写只有一处，同轮已更正。
- **命令**：`plan` 加材料词参数（大小写不敏感，不认识就明确失败并列出可选值）；`project` 的短缺报告改成**按材料各一行**。
- **两个仓库包装方法删除**：`PublicWarehouseInventory.firstWithOakPlank` 与 `countOakPlanks` 删除，取仓与计数只剩 `firstHolding` / `countOf` 两个权威。
- **新的固定成本**：`BuildMaterial` 把注册表对象（`Blocks.*` / `Items.*`）带进枚举，触碰它的独立检查必须先 `SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();`——本轮三个检查（`ConstructionMaterialCheck` / `TransportSavedDataCheck` / `SettlementSavedDataCheck`）开始这么做，此前本项目一个都没有引导注册表。
- **明确未做**：形状仍是 2 格直线（`LENGTH = 2`）；材料种类仍是四种；未做游戏内验证。

## 本轮接入的内容（第五十八轮：石桥与更长跨度）

- **桥种与唯一判据**：`TransportPlan.Kind` 增 `STONE_BRIDGE`，并加 `isBridge()` 作为"这是不是一座桥"的唯一出处；全仓原先 9 处逐枚举判断全部改走它（收口后 `src/main/java` 里 `WOOD_BRIDGE` 出现在**五行**上——枚举声明、`isBridge()` 方法体、提案的木桥档、命令的缺省值与 `wood` 词，**全是在命名桥种、没有一处是在判定桥种**；原写的 `isBridgeKind` 这个名字从未存在，实际名字是 `isBridge()`）。**因此①开通闸、②`Links:` 行、③导航闭包（未开通桥的 `closedFootprint` 并进 `closedFeet`，寻路经 `isBridgeClosedAt` 查询）、④开通桥巡检（`openBridges`）四处一处判据都没新写就自动认得了石桥**（石桥与木桥走格几何相同，没有新增任何连通判据）。
- **档位与勘察参数化**：`BridgePlanner` 增 `MIN_STONE_SPAN = 13` / `MAX_STONE_SPAN = 24`，勘察从写死上限改为**按区间参数**（`planWoodBridge` → `planBridge(..., minSpan, maxSpan)`），木石共用一套勘察。决策是**把既有纯判据 `TrafficDecision.decide` 按两个区间各调一次**（先 4–12 判木桥、不成再 13–24 判石桥）——`TrafficDecision` **一行未改**，只补了 12／13／24／25 的边界断言。>24 格的两次都出界，按既有语义回落成"试修路 → 失败 → 目标延期"。
- **材料与门禁**：`BuildMaterial` 增 `COBBLESTONE`（挖矿本就产出圆石，不需新经济；**只许新增**，四个已有名字仍是存档取值）。新增 `construction/transport/BridgeMaterials` 作为"哪个桥种用什么材料、各要多少才开工"的唯一出处——**落在 construction 而非设计稿写的 planning**：`planning.*` 不许依赖 `construction.*`（`TransportPlan.Kind` 在 construction 下），否则成环，设计文档 §3 已就地纠正。石桥的桥面/支撑/引道用圆石，**护栏与临时栅栏仍是橡木栅栏**（临时栅栏不是成品、清栅栏逻辑写死橡木栅栏；圆石墙护栏需经济先能产出圆石墙——有意的边界，不是遗漏）。`proposeBridge` 改遍历 `BridgeMaterials.required(kind)` 各自设下限，原四个 `BRIDGE_MIN_*` 搬进该类；**圆石下限是发明值**，注释已标明待重定。
- **命令**：`traffic bridge` 增可选桥种词（`wood` / `stone`，缺省木桥，不认识则明确失败并列出可选值）。
- **第 19 项检查**：新增 `bridgeMaterialsCheck`（桥种判据、两套材料清单只差桥面/支撑、区间常量与设计一致）；`TrafficDecisionCheck` 补档位边界、`ConstructionMaterialCheck` 的名字与映射断言扩到五个。
- **明确未做**：圆石墙护栏（需经济先能产出）；更深的河床与峡谷（浅水 4 / 峡谷 8 深度上限未动，宽且深的峡谷仍按既有深度口径失败）；>24 格水面仍不可跨；石桥护栏是木头的；长桥更贵更慢且仍占唯一一个交通工程名额；未做游戏内验证。

## 本轮接入的内容（第五十九轮：改建安全）

- **纯判据**：`BlueprintSet.reserved(styleIndex, x, y, z)`——遍历 `stages(styleIndex)` 回答"这套风格的任何一级用不用这一格"，坐标是相对床头锚点的偏移，**不碰世界、不碰存档**。
- **预防接线**：`BedProvisioningCoordinator` 在扫描进入 FIND 相之前，按**有空位的锚点**算出 `reservedCells`（每个可绑定的房子、它自己风格的**全部级别**，经唯一换算权威 `HousingCoordinator.position(level, home.bed(), home.variant(), step)` 得到世界格——朝向与镜像都在那里进入，锚点不是床头则跳过），缓存进 `Scan`；`siteSuitable` 的候选格排除项加一条 `reserved.contains(pos)`（foot 与 head 两格都过）。**只收可绑定房子的蓝图**——并集全部风格会把本来可用的位置也挡掉。**（终审更正）这条排除项今天恒不生效**：既有 `nearBed`（x/y/z 各 4 格）已覆盖整个蓝图盒，且每个预留格都来自锚点床在同一床表里的房子，候选格早在 `nearBed` 就被排除——它是**冗余保险**，不是补上的缺口。
- **诊断**：`HousingCoordinator.blockedReason(...)` 把 `advance` 原本内联的七道门禁提成一个只读判据，`advance` 与 `GoblinSettlement` 的 status 行都问它；显示层因此不算材料、不查方块、不判权限。**不新增持久字段、不抄第二遍门禁**。
- **把人工核对变成断言**：`BlueprintCheck` 新增 `checkCellsUsedByABlueprint`（`reserved` 判据的正反例，逐套风格）与 `checkBedHeadroomStaysFree`（**发布数据里没有任何一级占用床头或它上方两格**——设计期手算一遍的性质，现在随每次构建跑）。**检查总数仍 19**，是加进既有检查、不新增检查任务。
- **本轮是预防而非修复**：不引入拆除/替换能力、不搬床、不回收床、不清理玩家放在蓝图格里的方块——**只有玩家放置的方块或床**会让一栋房卡住（聚落自己放的床已被 `nearBed` 排除在蓝图盒外），本轮只靠 status 那一行把它说出来。
- **终审后修复波（代码提交 `9dc88bc`）**：① `reservedCells` 原先用裸平移（`home.bed().offset(...)`）枚举、漏掉朝向与镜像，已改为经 `HousingCoordinator.position` 派生每个格；② `status` 行补上 ticking 区块闸（不可 tick 的房跳过并计为 `n not loaded`）、蓝图数据不可用时单独一行说明、以及承诺的按床坐标排序；③ `BlueprintCheck` 的两条探针加强（遍历每套风格的每条链，越界风格改用 style 0 里确被占用的格来比较）；④ `advance` 里两条恒不能成立的分支加注说明（只用于取值、非第二道闸）。
- **范围为何缩小**（详见设计 §1）：升级链从不拆方块，所以"拆前确认材料"在现有能力下没有可拆的对象；"确认材料"本身已是 `advance` 的现状；发布数据本就床净空安全。因此本轮只做"预防 + 诊断"。

## 本轮接入的内容（第六十轮：住宅住户分配）

- **归属只存在居民侧**：`ResidentRecord` 新增 `Optional<BlockPos> home`（`BlockPos.CODEC.optionalFieldOf("home")`，**不升 schema 版本**、旧档读作无房），指向那栋房的**床头坐标**（房子的标识，不是某一张床）。`withHome` 是**无条件**取值变换（归属会反复变，不能照抄 `withProfession` 的一次性形状），`SettlementSavedData.assignHome(String, Optional<BlockPos>)` 只做"找到 id、替换、标脏"。**陷阱**：`ResidentRecord.java` 内 8 处既有的 `new ResidentRecord(...)` 每处都要串上 `home`——`advanceFamilyTime` 每 tick 都跑，漏一处会**静默清空**归属。
- **纯分配规则**：`housing/HousingAssignment` 只吃 int 与 record、不碰 MC 类型，三趟推导出完整目标状态（超容按 id 保留前 capacity、消失/不可判定解除、其余无房者进第一栋有空位的房子），输出**净变化**因而幂等。**本轮第 20 项独立检查 `housingAssignmentCheck`**（覆盖入房、全满留作无房、消失解除、超容、确定性、输出顺序、`unjudged`、只含真变化、幂等）。
- **容量的唯一算式**：`HousingCoordinator.homeCapacity` 成为"这栋房能住几人"的唯一算式，`shelterCapacity` 改为 `stage1Built ? homeCapacity : 0`（逐字等价的委派）；`BedProvisioningCoordinator` 内联的那份也并了过来（此前"一份算式"并未真的成立，见 UpdateLog 更正条目）。
- **分配协调器**：`housing/HousingAssignmentCoordinator` 每 `INTERVAL_TICKS = 40` tick 读世界（扫名册 + 逐房过 `shouldTickBlocksAt` 闸 + 读容量）→ 调纯规则 → 按 `BUDGET = 8` 截断 → 逐条 `assignHome`；接入 `tickSettlement`，排在 `BedProvisioningCoordinator` 之后、`PatrolCoordinator` 之前。**两个常量都是发明值**，注释标明无量测依据、须按实测重定。
- **生育门落到"聚落里还有登记的房有空位"**：`FamilyCoordinator` 的生育门从聚落级 `housing.count() >= occupied` 起手，初版曾改为先问**母亲那栋房**的 `roomForMother`；**全范围终审发现户级门会死锁**——分配从不重平衡（`plan` 只放无房者、只挤超容者），按床位坐标序填房的默认稳态就是"后排房空着、前排住满"，于是满房里的母亲**永远生不了**，而 `BedCensus.shortage` 在死锁态恰为假、`HOUSING` 档不会开，没有出口。**用户裁决改为聚落级**：`HousingAssignmentCoordinator.hasRoomAnywhere` 只看"是否还有**任意一栋已判定的房**有空位"，**无可判定的房子时回落**聚落级床位判据；只为户级门存在的 `roomAt` 随之删除。`!housing.beds().isEmpty()` 保留。新生儿不继承母亲的房子（不保证同房，是"孩子随父母的房"被排除的直接后果）。**受孕侧 `housingForConception` 仍是聚落级的**——用户的批准只覆盖出生条件，刻意未动。
- **显示**：`status` 的 `Housing:` 行末尾加 `homeless=K`；`Housing work:` 行改成逐栋的 `Homes:` 行，显示 `住几人/容量`、卡住的栋原位带原因，沿用现成的 `housingReportLine` 门禁；一栋 home 都没有时是 `Homes: none`。
- **明确未做**：伴侣同住、孩子随父母的房、睡觉/寻床、房子拆除与改建、住户数预留、按距离就近入房；实体公告牌方块仍是另一项。

## 本轮接入的内容（第六十三轮：其余六职业 + 收上一轮的债）

- **提速执行**：本轮**不写设计文档、不写计划文档、不逐任务拆**——六职业本质是照已跑通的管线重复录入（每职业 = `MODELS` 表两行 + 两张贴图 + 表里一行），所以派**一个**实现任务 + **一次**审查。对比第六十二轮（手写约 150 行代码、约 50 分钟、**8 次**子代理调用），本轮手写量更小、**2 次**调用、**14 分钟**。
- **先收债（截止线就是本轮）**：`generate_models.py` 在每个生成类上多输出一个返回**自己那一行**的工厂 `public static GoblinBodies.Body body(boolean female, Optional<Profession> profession, String texture)`，`BODIES` 的每一行改为 `GoblinForesterMaleModel.body(false, Optional.of(Profession.FORESTER), "goblin_forester_male")` 这种形状——**一行只点名一个类**，裁剪件、层工厂、模型工厂全部由那个类供给。上一轮那条残留（留着对的 `Xxx.CROP`、只把两个工厂换成别的类仍能通过全部检查）**就此结构性关闭**：`src/` 里再无 `.CROP` 引用、再无生成类之外的 `new GoblinBodies.Body`。**生成器算法一行未动**（diff 只有三处：11 条 `MODELS` 项、两行生成的 import、追加的工厂输出），**四份既有裁剪件逐字节不变**。
- **补一条会失效的断言**：`require(body.female() == body.crop().contains("female"), ...)`——它约束的是一条**命名约定**，但那正是裁剪件如此命名的原因，且这是唯一能构建期挡住"把男行复制成女行、忘了翻转标志"这类错误的地方（那种错会让一整个性别渲染错身体且检查看不见）。
- **六职业接入 11 套**：林工 p02 / 矿工 p03 / 建筑工 p04 / 搬运员 p05 / 工匠 p06 / 哨卫 p07 的男女外观，各加 `MODELS` 项、裁剪件、生成类、1024×1024 贴图（去掉 `_pNN` 后缀）、表里一行。**渲染器与客户端注册处一行未改**——两者本来就读那张表，审查者读代码确认而非假定。检查报 `ArtModelCheck passed (15 crops, 15 baked models)`。
- **新断言第一次上场就抓到一个真实美术缺陷**：`goblin_sentry_female_p07` 被**扣下未提交**（详见下面「美术候选」段）。**不放宽断言、不改美术工作区、不提交该模型**——女哨卫回落基础女体型（`outfits.getOrDefault(...)` 天然覆盖，不崩）。
- **明确未做**：女哨卫外观（待美术）、儿童、五款傀儡、音效、公告牌方块、手持道具；未做游戏内验证。

## 本轮接入的内容（第六十二轮：职业美术接入（第一轮：农民））

- **生成器只加两行**：`tools/generate_models.py` 的 `MODELS` 表增 `goblin_farmer_male_p01` / `goblin_farmer_female_p01` 两项。算法、裁剪格式、拒绝规则与变换一行未改——组合工程正好落在生成器已支持的形状一侧（顶层六个分组、盒式 UV、无逐面 UV、无多轴旋转、无缺 `uv_offset`），`resolution` 512 让 `LayerDefinition.create(mesh, 512, 512)` 自动得出。
- **一张表，三个读者**：新增 `client/model/GoblinBodies`，`BODIES` 列出客户端能渲染的每一具身体（两具基础 + 农民两套），每行带裁剪件名、贴图基名、`createLayer` 工厂与 `ModelPart → 模型` 构造器。`GoblinSettlementClient` 按行注册模型层、`GoblinRenderer` 烘制并按行取用、`artModelCheck` 验证每行的裁剪件与贴图。层 id 由裁剪件名经**唯一函数** `GoblinSettlementClient.layer(String)` 派生，注册与烘焙共用它。
- **渲染器查表 + 刻意退路**：`submit` 与 `getTextureLocation` 读**同一行**，农民不可能用别的职业的贴图；其余六职业与 `UNASSIGNED` 回退基础身体与基础贴图（**刻意**，保证未着装职业仍可渲染而非缺贴图）。这是本轮唯一的行为变化。
- **检查从表派生、断言一条没少**：删掉 `artModelCheck` 硬编码的 crop → 模型映射，改为从 `BODIES` 派生；审查用**定向 diff** 确认 `checkCrop` / `checkTexture` / `checkBaked` 没有任何 hunk，19 条既有断言原样保留。新增 `checkBodiesAgreeWithTheirCrops`（同一 `(职业, 性别)` 不得被两行认领、每行裁剪件必须进仓）。**检查总数仍是 21 项，不新增检查任务**。
- **一处必要偏离**：计划的 `registerModelLayer(layer, body.layer())` 在本版本 Fabric 不编译（要 `TexturedModelDataProvider` 而非 `Supplier<LayerDefinition>`），在**唯一调用点**改成 `body.layer()::get`，共享表未动，已对着 Fabric API 核实。
- **落点**：`PROFESSION_ART_DESIGN.md` §7。**明确未做**：其余六职业（含**待审的哨卫 P07**）、手持道具、儿童、五款傀儡、音效、公告板方块；未做游戏内验证。

## 本轮验证进展

- 第六十五轮：完整离线构建 `./gradlew clean build --offline --no-daemon` **BUILD SUCCESSFUL**，**21 项**独立检查全部 `*Check passed`（`ArtModelCheck passed (17 goblin crops, 5 golem crops, 22 baked models)`，**不新增检查任务**），**无编译警告**，耗时 **60 秒**。产物 `build/libs/goblin-settlement-0.1.0.jar`：**2094151** 字节。**代码提交 `1c2e65d`，终审修复 `2c2f14f`。** 终审抓到一条 **Critical：本轮引入的客户端崩溃回归**——旧档 IRON 傀儡：`GolemTier.valueOf("IRON")` **是合法值、不抛**，于是被交给没有 IRON 行的渲染器并抛错，而上一版渲染器完全不看等级、不可能这样崩。修法：`hasCustomArt()` 成为唯一判据、读取侧返回 `Optional`、渲染器对无美术的阶**什么都不画**、对"表缺阶"仍响亮抛错，并加双向等式把读取侧的承诺变成构建期事实。**复审把整个客户端 jar（3128 个类）按擦除描述符追了一遍**，确认 `getTextureLocation` 全 jar 只有一处调用（在带守卫的 `submit` 之内），**崩溃是真被移除、不是挪了地方**。
- 第六十四轮：完整离线构建 `./gradlew clean build --offline --no-daemon` **BUILD SUCCESSFUL**，**21 项**独立检查全部 `*Check passed`（`ArtModelCheck passed (17 crops, 17 baked models)`），**无编译警告**，耗时 **57 秒**。产物 **1418540** 字节。**代码提交 `42d810d`。** 本轮收掉第六十三轮记的"贴图名手打"那条债（改由生成类供给 `TEXTURE`），并接入儿童男女两套（表新增 `child` 轴 + 年龄同步 + 儿童不得点名职业的断言）。**实现代理纠正了我写错的一条规格**：我写的"砍掉尾部 `_pNN`"对两具基础身体不成立（它们 `_a` 结尾）——那条规则照字面无法满足，它改成了能重现全部十六张贴图的批号标签规则。
- 第六十三轮：完整离线构建 `./gradlew clean build --offline --no-daemon` **BUILD SUCCESSFUL**，**21 项**独立检查全部 `*Check passed`（`artModelCheck passed (15 crops, 15 baked models)`，**不新增检查任务**——新断言加进既有 `artModelCheck`），**无编译警告**，耗时 **57 秒**。产物 `build/libs/goblin-settlement-0.1.0.jar`：**1357847** 字节（上轮 702463：十二张 1024×1024 职业贴图）。**代码收尾提交 `30808ac`**。**非空转证明**（临时副本上做完即还原、工作树干净）：翻转农民的 `female` 标志 → 新断言报错；把某行工厂指向别的类 → 裁剪件撞名、建表处直接失败。**残留（记档待办，不修）**：表里的**贴图名字仍是手打的**——林工女装的 UV 矩形落在其余 13 张贴图里 **12 张**的涂色区内，所以贴图名写错能通过构建、只表现为"穿错皮"；建议与儿童/傀儡那两轮一起收（收法同本轮：交给生成类供给）。**另：女哨卫未接入**（贴图差 1–2 行，待美术重导）。
- 第六十二轮：完整离线构建 `./gradlew clean build --offline --no-daemon` **BUILD SUCCESSFUL**，**21 项**独立检查全部 `*Check passed`（**仍 21 项，不新增检查任务**），**无编译警告**，耗时 **64 秒**。产物 `build/libs/goblin-settlement-0.1.0.jar`：**701173** 字节（上轮 561815：两张 1024×1024 农民贴图）。本轮提交，按序：`160954f`（设计）/ `b754278`（计划）/ `784fa34`（一具身体一张表 + 农民）/ `0f8483f`（注册与渲染都走表）。
- **第六十二轮收尾修复波**（全分支终审后，2026-09-28）：完整离线构建 `./gradlew clean build --offline --no-daemon` **BUILD SUCCESSFUL**，**21 项**独立检查全部 `*Check passed`（`artModelCheck passed (4 crops, 4 baked models)`，**不新增检查任务**），**无编译警告**，耗时 **52 秒**；`build/libs/goblin-settlement-0.1.0.jar`：**702463** 字节。修的是终审那条 Important（安全网看不见一行指向哪个网格类）：生成器给每个生成类加 `CROP` 常量、`GoblinBodies` 四行的裁剪件字面量改用它，`checkTexture` **新增"uv 矩形必须落在非透明像素内"的断言**，`checkCrop` 读元素坐标改用 `getAsDouble()`，`GoblinRenderer` 的两张并列 map 收成一个 `Outfit`、贴图命名空间改用 `GoblinSettlement.MOD_ID`。**非空转证明**（临时副本上做完即还原）：把农民男一行**连类**指向 `GoblinMaleModel` → 构建失败（`Duplicate key goblin_male_a`）；把一份裁剪件的 `uv_offset` 挪进未上色画布 → **新断言失败并点名元素**。**残留（待裁决）**：只把两个模型工厂换成别的类、而保留正确 `Xxx.CROP` 的一行**仍能通过全部检查**——详见 [PROFESSION_ART_DESIGN.md](PROFESSION_ART_DESIGN.md) §7.9。
- **一处必要的实现期偏离（已对着 Fabric API 核实）**：计划逐字给的 `registerModelLayer(layer, body.layer())` 在**本版本 Fabric 不能编译**——`EntityModelLayerRegistry.registerModelLayer` 收的是 `TexturedModelDataProvider`，不是 `Supplier<LayerDefinition>`；实现在**唯一调用点**改成 `body.layer()::get`，共享的表一字未动。
- **设计 §4 第二条是"有意收紧"而非偏离**：设计要的覆盖性断言（每个登记了职业外观的 `(职业, 性别)` 必有裁剪件）被实现成**更强的形式**——登记只有 `GoblinBodies.BODIES` 一张表，注册处、渲染器、检查三者都从它读，"登记了但资源没进仓"从结构上消失；已记入设计 §7.6。
- **断言一条没少（逐条点名）**：审查用**定向 diff** 确认 `checkCrop` / `checkTexture` / `checkBaked` **没有任何 hunk**，本轮 **19 条既有断言**原样保留、强度未减——`checkCrop` 十条、`checkTexture` 三条、`checkBaked` 五条，加 main 里那条"裁剪件必须有可渲染身体映射"；新增的只是表自洽断言（同一 `(职业, 性别)` 不得被两行认领、每行裁剪件必须进仓）与从表派生的映射。
- 未做游戏内验证：未启动游戏、未运行专用服务端、未触碰常用存档。构建与独立检查通过不代表玩法验收。**独立检查覆盖不到、只在游戏内才会真正跑到的部分（本轮清单）**：帽子随头部转动的实际效果、1024 贴图的观感与显存占用（**画布并未填满**：农民男只有 `510×642` 像素上色、**约 31%**，农民女 `508×666`、约 32%；真正的成本是**固定的图集尺寸**，与画了多少内容无关）、职业外观在缩放/远距离下的辨识度、多人同屏渲染性能。**组合式做不到"拆掉配饰恢复基础角色"**——今天不需要（居民不转岗），将来要"职业装备可掉落/可换"就得改架构。**其余六职业（含尚未经用户审阅的哨卫 P07）未接**，儿童与五款傀儡仍未接入（年龄同步与傀儡等级同步也未引入）。**音效与公告板是美术交付的首批样板、待用户审阅**；物品图标与设施图标目录因此仍为空。

## 美术候选（2026-09-28）

- **农民已接入运行时资源**（第六十二轮）：农民男、女两套职业外观由美术的**组合工程**（`Models/goblin_professions_a/goblin_farmer_{male,female}_p01.bbmodel` + 1024×1024 PNG）经 `goblin-settlement-mod/tools/generate_models.py` 产成，客户端按同步的 `(职业, 性别)` 从**一张表 `GoblinBodies.BODIES`** 选模型与贴图；其余六职业与 `UNASSIGNED` 回退基础身体。落点见 [PROFESSION_ART_DESIGN.md](PROFESSION_ART_DESIGN.md) §7。**尺寸可由裁剪件直接推出**（与第六十一轮同一套变换）：农民高 **26.25 模型单位 = 1.64 格**（对 `1.45` 高碰撞箱），帽顶比基础头顶高 **2.75 模型单位 = 0.17 格**；**x 跨度与基础身体完全相同**——**−8.5 .. 8.5 模型单位 = 1.06 格**，即对 `0.6` 宽碰撞箱**每侧超出 0.23 格**（碰撞箱不变，同第六十一轮那条）。
- **成年男女基础身体已接入**（第六十一轮）：两套模型客户端按同步的性别选 `goblin_male.png` / `goblin_female.png`（均 512×512、模型逻辑 UV 256），碰撞箱维持 `0.6 × 1.45`。耳朵与头发**各在两侧超出碰撞箱 0.23 格**（模型空间 x 跨度 **−8.5 .. 8.5 模型单位 = 1.06 格**，见 ART_INTEGRATION_DESIGN.md §10.12），**碰撞箱不改是有意决定、不是遗漏**。落点见 [ART_INTEGRATION_DESIGN.md](ART_INTEGRATION_DESIGN.md) §10。
- **职业外观已接入 15 套，剩女哨卫一套被扣下**（第六十三轮）：**七职业已全部获用户认可**（哨卫 P07 于 2026-09-28 通过；美术侧 `Models/CURRENT_STATUS.md` 当时仍记作「待审阅」，属美术侧没更新，不是未通过）。**女哨卫未接入**：元素 `sentry_bracer_right` 的尺寸是小数（5.5 × 4.5 × 8），盒式 UV 展开到**像素第 594–619 行**，而交付贴图只画到**第 617 行**（差 1–2 行；男哨卫同一部件展开到 611、男贴图画到 613，有三行余量）——**待美术重导贴图，不需改模型**，画满后重跑 `--crop` 与生成器并加回表里一行；在此之前她回落基础女体型。**儿童已接入**（第六十四轮）：男孩、女孩两套 + 表新增 `child` 轴 + 年龄从名册同步（儿童与职业**结构上双向不可能混**）；**五款傀儡已接入**（第六十五轮）：木 / 石 / 金 / 钻石 / 黑曜石五套 + 等级同步（此前 `tier` **根本没同步到客户端**）+ 独立发光蒙版（自写 `CoreGlowLayer`，用 `RenderTypes.eyes` 全亮绘制）；**占位模型 `GoblinModel`、旧 `GOLEM_LAYER` 与 `goblin_golem.png` 三样已退役**。**仍未接入**：**儿童玩具**（`wooden_rabbit_toy`，归"手持道具/物品"那一轮）、**手持道具**、**音效**、**公告牌方块**。
- **音效与公告牌已获用户通过（2026-09-28）**：美术交付的 4 段样音（男女招呼各一、木傀儡移动与核心各一）与公告牌外观**均已通过**。整套音效（40 段）与物品/设施图标按美术计划**在首批通过后才开工**，所以 `Models/textures/item/` 与 `Models/textures/block/facility_signs/` **此刻仍是空的**——是尚未生产，不是未通过。公告牌本身是一个功能方块（注册、方块状态、放置、交互、动态信息都在代码侧），属功能轮而非美术轮。美术组当前唯一在做的剩余项是**特殊表情动作**（挥手样板已出）。
- 参考图 A–E/F 与推测的 A 男侧、背视图存放在 `mods/goblin_settlement_reference_art/`；方向为 A 哥布林、F 傀儡。旧 `Models/goblin_male_a_v2.bbmodel`（未获认可的 24 方块粗模）仅保留对照。成年男样板 `Models/goblin_male_a_final/` 与成年女 `Models/goblin_female_a/` 是美术的可编辑 Blockbench 主工程。
- **一条要记住的边界**：`Models/` **不在版本控制里**（根 `.gitignore` 是 `/*`、只放行四个路径），进仓的裁剪件 `goblin-settlement-mod/tools/models/*.json` 才是模组几何的可复现来源。美术组改了工程必须**重跑 `--crop` 并提交新裁剪件**；`artModelCheck` 只验裁剪件自洽，**不验它与 `Models/` 是否同步**（做不到）。

## 已验证的基线

截至 2026-09-25 17:53 的旧版：固定 Java 21 与 Gradle 9.2.1 离线构建、五项独立检查、两居民多工地取料施工与死亡/取消/卸载恢复、一格小麦播种收获并补种归仓在专用世界通过。对应证据见 UpdateLog.md。此结果不自动覆盖之后的候选代码。

## 尚需实现与统一验收

1. 职业系统剩余：儿童体型与动作差异、真实手持工具与盾牌、已定职居民的强制转岗、**其余职业的名额上限**、**哨卫逐户护送**（用户已否，非必须）。**已完成**：派工服务收口（第四十七轮，`colony/WorkerDispatch`）；**哨卫名额约束**（第四十八轮，`ProfessionRules.ceiling` 的 `min(4, 成人 / 12)`）；**哨卫巡逻**（第四十九轮，`WorkKind.PATROL`）；**哨卫报警**（第五十轮，目击 `Monster` 即经既有傀儡警戒链路报警；**只有怪物算威胁，玩家不算**）；**哨卫有限自卫**（第五十一轮，只打打它的那个目标、只在 8 格内、只持续 15 秒）；**引导避难**（第五十二轮，聚落级警戒状态 + 非战斗居民与儿童躲进最近的已登记房屋，哨卫守位）；**避难所质量**（第五十三轮，避难所必须是**有墙**的房屋、且最多容纳**已建成的床位数**；占用是扫描得到的、**未预留**，同 tick 两人可能超员 1；**早期人多房少时会有居民找不到避难所、照常干活**）。
   **巡逻带来的连带**：PROFESSION_DESIGN §2.3/§4.2 那条"无工作职业按通才对待"的临时规则到期——哨卫做非本行的活从"通才档"变为"专才离行档"，排在未定职**之后**（可见的行为变化，已写入该文档）。
   **哨卫职责进度**：GAME_DESIGN 第 30 行给哨卫四项职责（巡逻、报警、引导避难、有限自卫），**四项全部落地**——巡逻（第四十九轮）、报警（第五十轮）、有限自卫（第五十一轮）、引导避难（第五十二轮），避难所质量另于第五十三轮收紧。
   **「职业熟练度」不是待做项**：PROFESSION_DESIGN §12 把它记为"职业熟练度与效率成长（**用户明确排除**）"。本行此前把它列为剩余项，与设计文档矛盾，已更正——不要照旧单执行一个被排除的需求。
2. 交通剩余：**通行量驱动的道路升级（两轮已完成）**、**道路连通性验收（已完成）**、**石桥与更长跨度（已完成）**、成熟期多工程并行（出自 GAME_DESIGN 第 7/12 节——注意**不是**交通设计文档，它只有 7 节）。
   - **通行量驱动的道路升级**：**两轮均已完成**——第一轮（量）纯判据 `RoadUpgradeRules`、采样 `TrafficSampler`、持久计数 `TransportSavedData.traffic`、`status` 一行显示，落点见 [TRAFFIC_UPGRADE_DESIGN.md](TRAFFIC_UPGRADE_DESIGN.md) §9；第二轮（加宽，2 → 3 → 5）链式加宽计划 + 中线入档 + 淘汰豁免，落点见 [TRAFFIC_WIDEN_DESIGN.md](TRAFFIC_WIDEN_DESIGN.md) §8。两个发明常量 `TRAFFIC_PER_LANE = 200` 与 `SAMPLE_INTERVAL_TICKS = 100` 仍**待拿真实计数后一起重定**。
   - **成熟期多工程并行**（§12「初期 1 项、成熟期最多 3 项」）：**架构级改动**，现有整套互斥链都建立在"同时只推进一项工程"上。
   - **道路连通性验收**（§7 桥梁施工顺序末项）：**已完成**（第五十六轮）——桥开通前必须可跨越、路必须真的接上设施、`status` 一行显示，落点见 [TRAFFIC_CONNECTIVITY_DESIGN.md](TRAFFIC_CONNECTIVITY_DESIGN.md) §8。
   - **石桥与更长跨度**（§7 木桥 4–12 / 石桥 12–24 格）：**已完成**（第五十八轮）——`TransportPlan.Kind` 增 `STONE_BRIDGE` 与 `isBridge()`（桥种唯一判据），`BridgePlanner` 增 13–24 档、勘察按区间参数、`construction/transport/BridgeMaterials` 按桥种给材料、`traffic bridge` 增桥种词（`wood` / `stone`）。落点见 [STONE_BRIDGE_DESIGN.md](STONE_BRIDGE_DESIGN.md) §8。
3. 住宅剩余。**已完成五项**：床位判据收敛为单一权威（第四十二轮）；`HousingRules.decide` 的白花问题（第四十三轮）；五级几何迁到受校验的数据文件（第四十四轮）；材料的泛化（第四十五轮）；**更多蓝图（第四十六轮）**——数据文件按"风格"组织，三套风格可用，按床坐标的确定性偏好逐栋选择。**至此"材料清单迁到受校验的数据文件"与"更多蓝图"两项都完成**（TECH_DESIGN 第 6 节要求）。
   **⚠️ 删风格是不安全操作**：风格下标越界会被静默钳到 0（避免存档打不开），所以从数据文件里删掉某套风格，会让存档中用它建成的房子**静默变成 cottage 几何**、进度推导随之改变。要下线一套风格必须先做迁移，不能直接删。
   **通用施工路径的材料泛化：已完成（第五十七轮）**——`GoblinCitizenEntity.fetchMaterial`/`placeMaterial`/`carried(Item)` 与 `ConstructionCoordinator` 那条线现在按计划声明的 `BuildMaterial` 施工，落点见 [CONSTRUCTION_MATERIAL_DESIGN.md](CONSTRUCTION_MATERIAL_DESIGN.md) §8。第四十四轮把这项工作量估成"约 10 处"，正是把住宅路径与这条线混在了一起；住宅部分第四十五轮已做，这条线第五十七轮补齐。
   **改建安全：已完成（第五十九轮）**，范围经核对缩小为"预防 + 诊断"——升级链**从不拆方块**（所以"确认材料"已是现状、没有可拆的对象），发布数据本就床净空安全；本轮**真正的交付**是"卡住时说得出为什么"（`HousingCoordinator.blockedReason` + `status` 一行）与"床头净空断言"；"新床不占蓝图格"（`BlueprintSet.reserved` + `reservedCells`）经终审认定为**恒不生效的冗余保险、并非补上的缺口**（既有 `nearBed` 半径 4 已覆盖整个蓝图盒）。落点见 [HOUSING_REBUILD_DESIGN.md](HOUSING_REBUILD_DESIGN.md) §7。
   其余：公共设施、**实体公告牌方块**、历史保留。**住户分配：已完成（第六十轮）**——`ResidentRecord.home`（旧档读作无房、不升 schema）+ 纯规则 `housing/HousingAssignment`（本轮第 20 项独立检查）+ 生育门落到"聚落里还有登记的房有空位"（**终审后由户级"母亲那栋房"改为聚落级**，因户级会死锁；受孕侧仍是聚落级的已知非对称）+ `status` 的 `Housing:`/`Homes:` 两行（逐栋住几人/容量 + 无房人数），落点见 [HOUSING_ASSIGN_DESIGN.md](HOUSING_ASSIGN_DESIGN.md) §10。
4. 七阶段其他缺口：阶段 7 成熟城镇性能与跨存储异常恢复。
5. 交通自主立项、采矿、冶炼调度、工人释放修复、职业系统与住宅两轴升级链**均未在游戏中验证**，只有编译与纯函数证据。
6. 跨存储一致性：**第七十轮审计已完成**（结论与完整清单见 `UpdateLog.md` 该轮段）。**已修 5 条**：①施工不释放空闲工人（第七十轮）；②死亡掉落被拦下时携带物归还仓库（第七十一轮）；③非空炉子不再被选中（第七十一轮，"不虚构退款"政策未动）；**⑤傀儡转铁改用"由旧傀儡确定性派生 UUID"**，重载幂等收敛，不再白多一只香草铁（第七十三轮）；**⑥已建成的路纳入巡检**（与桥共用 4/tick 预算、复用同一判据），但**收益收窄**：只能自愈"被外来可建方块占住"的路格——**被挖成空气的格子按现有路面前置条件不可能重铺**，这正是 `TRAFFIC_CONNECTIVITY_DESIGN.md` 那条"玩家拆路会重修"长期没兑现的原因（第七十三轮）。**余 3 条经查证后不做代码改动、以文档收口（第七十四轮）**：④营地初始补给标志先落盘——**不存在既保"不补发"又消除该窗口的修法**（箱内容分不清"从未发放"与"被玩家取空"，正是"不虚构退款"所拒），且两写同 tick、按 §4 口径窗口不可达；⑧用餐部分消耗——同属**纯崩溃窗口**，而"把 `finishMeal` 提到扣粮之前"这个候选修法会把"重复吃"换成"**记了餐却没扣粮**"，即让聚落在崩溃后白得一餐，**与"不虚构"的既有取向相反**，故同样保留现状；⑦取物目击为**纯记录更正**（见下条）。**另记一笔不在这五条内**：付费转铁路径崩溃后重复扣料（⑤ 的审查确认"未变坏"，仍未修）。**以上多数需"重载实测"才能定性窗口宽窄**——那正是 §4 要求而项目尚未做过的那类测试；**第七十一与七十三轮修的四条同样尚未在游戏中验证**。
7. 取物目击的真实边界（**2026-09-29 更正**：旧文写"只覆盖单箱单槽"，**"单槽"不实**）：**排除双箱**（`CompoundContainer` 不算单方块实体），**同物品多槽、净额相等的情形是支持的**；真正的第二个限制是观察表**是进程内静态、不持久化**，跨重启看不见。道路/桥梁的非活动区块恢复、分仓与复杂地形仍需统一验证。

## 外部纯函数任务

**已于 2026-09-29 完成正式验收**：F001 主线已有同等实现并完成较早验证，外部 r1 不重复接入。**F002（材料缺口）与 F003（道路单步代价）的 r1 通过**——主程序在提交目录之外用 JDK 21 编译运行，两者的可算例与主程序另写的独立对抗套件（F002 70 条、F003 753 条）全部通过，并按契约逐条核对了签名、单位、边界、异常与纯度；两者都用**变异测试**证明了自己的套件不是一路绿。快照见 `function-bank/accepted/F00{2,3}/r1/`，各含 `REVIEW.md`（验收时间、命令与结果、已知限制）。**两者均尚未接入运行时**——接入时填运行时路径。
**F004（跨仓库取料方案）与 F005（建筑地块排序）验收未通过，经用户决定不再采用**：两者的**交付实现**都过了主程序的独立对抗测试（F004 63 条含全序确定性与 `Long.MAX_VALUE` 不溢出；F005 33 条含用 int 溢出陷阱识破），但**提交方自带的检查程序**有阻断缺陷——F004 的检查**编不过**（第 379 行 `List<Withdrawal>` 声明装 `List<Slot>`），F005 的检查有 **24 处**把 `Candidate` 与 `Optional<Candidate>` 直接 `equals`、**恒为假因而从未可能通过**。**另须记一笔**：F005 先前那份静态 `REVIEW.md` **不实**——它称检查程序只差一处期望值，实际那份程序根本跑不完；下次做静态初审时，**"检查程序能不能编译/跑完"本身就该先验**。提交目录原样保留作历史，未删除。详见 `function-bank/README.md`。

## 边界

哥布林代码在 goblin-settlement-mod；不要改 ai-chat-mod 或常用存档。纯函数开发前读 function-bank/README.md，外部成果需审查和验证后才接入。GitHub origin 指向 zenclanx/goblin-settlement。本轮工作直接在 `main` 上开发并推送到 origin/main。
