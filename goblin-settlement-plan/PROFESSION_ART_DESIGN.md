# 职业美术接入 设计（第一轮：农民）

更新日期：2026-09-28。依据：GAME_DESIGN 第 3 节职业表（各职业有识别外观：农民＝草帽、锄头；林工＝斧头、肩带……）；`Models/goblin_professions_a/README.md` 与各职业 README 的接入规格；CURRENT_STATUS「美术候选」段。

上一轮（`ART_INTEGRATION_DESIGN.md`，第六十一轮）接入的是**成年男女的基础身体**，并把八个职业的旧 64×64 贴图删掉，导致**七个职业暂时长得一模一样**——那是上一轮唯一可见的退步，且已由用户确认。本轮开始把它补回来。

## 0. 范围

| | 内容 |
| --- | --- |
| **本轮做** | **农民**一套（男、女）的职业外观进游戏，跑通「职业 × 性别 → 选模型与贴图」这条本轮真正的新逻辑 |
| **用户已定的三处** | ①先只做农民一套（男女），管线确认后再铺开；②**只接外观**，手持锄头留到"工具成为物品"那一轮；③用**组合模型**（下节 §2），不做基础 + 装备分层 |
| **明确不做** | 其余六职业（**含尚未经用户审阅的哨卫 P07**）、手持道具、儿童、五款傀儡、音效、公告牌方块 |
| **不新增** | 不改生成器的算法、不改存档结构、不动实体的职业同步链路 |

## 1. 现状（已核对）

**美术侧交付**（`Models/goblin_professions_a/`）：七职业 × 男女各有**组合工程**（`goblin_<职业>_<性别>_pNN.bbmodel` + 1024×1024 PNG）、**独立装备工程**、**带手持道具的检查副本**，以及每职业一份 `manifest.json`。P01 农民至 P06 工匠已获用户认可；**P07 哨卫待用户审阅**。

**组合工程的形状（本轮实地核对，两套农民工程）**：

| | 基础（上一轮） | 农民组合 |
| --- | --- | --- |
| 顶层分组 | 六个 | **六个**（`head` / `body` / `left_arm` / `right_arm` / `left_leg` / `right_leg`） |
| `meta.box_uv` | 真 | 真 |
| 元素带 `faces` | 无 | **无** |
| 多轴旋转元素 | 无 | **无** |
| 缺 `uv_offset` 的元素 | 无 | **无** |
| `resolution` | 256×256 | **512×512** |
| PNG | 512×512 | **1024×1024** |
| 立方体数 | 108 / 129 | 130 / 152 |

**结论：现有生成器一行不改就能吃下组合工程。** 上面每一行都正好落在 §3.4 的"支持"一侧；`LayerDefinition.create(mesh, u, v)` 的 `u`/`v` 由生成器读 `resolution` 得出，所以 512 是自动的。

**`manifest.json` 给了什么**：基础工程路径与其 sha256、组合中被**移除**的基础部件名（斜肩带、腰扣那一批）、被**内缩**以避免共面闪纹的部件名、装备工程名、组合工程名，以及**装备部件 uuid → 骨骼名**的映射。**按方案 A 这些都不需要读**——美术已经在组合工程里装配完毕——但它是"组合里发生过什么"的记录，将来改用分层式时会用上。

**代码侧现状**：

- `client/GoblinRenderer.java` 是"二选一"：`this.model = state.female ? female : male`，贴图 `TEXTURE_MALE` / `TEXTURE_FEMALE`。
- `client/GoblinRenderState` 已有 `profession` 与 `female` 两个字段（上一轮建立），**本轮的输入已经齐了，不需要新增同步字段**。
- `colony/Profession` 是 `UNASSIGNED` + 七职业的枚举。
- `SettlementSavedData.assignProfession` 是**一次性**的（只给当前 `UNASSIGNED` 的成人赋值，其余一律返回 false），所以今天**居民不会转岗**——外观烧进网格这件事在今天的规则下不会出问题。
- 生成器的 `MODELS` 表把"项目 id → 工程路径 + 生成的类名"列在一起；`artModelCheck` 上一轮已改成**由裁剪件清单驱动**，并要求每个裁剪件都有对应的模型映射（缺映射直接报错）。

## 2. 数据流与新增产物

```text
Models/goblin_professions_a/goblin_farmer_{male,female}_p01.bbmodel
   │  python tools/generate_models.py --crop
   ▼
goblin-settlement-mod/tools/models/goblin_farmer_{male,female}_p01.json     [进仓]
   │  python tools/generate_models.py
   ▼
client/model/GoblinFarmer{Male,Female}Model.java                            [进仓，生成物]
   │  LayerDefinition.create(mesh, 512, 512)   ← 由 resolution 自动得出
   ▼
ModelLayerLocation goblin_farmer_male / goblin_farmer_female
   │
   ▼
渲染器按 (职业, 性别) 选中
```

贴图：`Models/goblin_professions_a/goblin_farmer_{male,female}_p01.png`（1024×1024）复制到
`src/main/resources/assets/goblin_settlement/textures/entity/goblin_farmer_{male,female}.png`。

**生成器只加两行**（`MODELS` 表的两项）。算法、裁剪格式、拒绝规则一律不动。

## 3 客户端怎么选（本轮的核心）

现在是"二选一"，而七职业做完就是**十六选一**（2 基础 + 7×2 职业）。所以不写成一堆字段，写成**按性别分组的小结构**：

```java
/** 某一性别可用的身体：基础一套，外加每个已有美术的职业一套。 */
private record Bodies(EntityModel<GoblinRenderState> base,
                      Map<Profession, EntityModel<GoblinRenderState>> outfits) {
    EntityModel<GoblinRenderState> forProfession(Profession profession) {
        return outfits.getOrDefault(profession, base);
    }
}
```

- 渲染器持有 `Bodies male` 与 `Bodies female`；`submit` 里
  `this.model = (state.female ? female : male).forProfession(state.profession);`
- 贴图同构：每性别一份 `Map<Profession, Identifier>`，`getTextureLocation` 走同一次查表，缺省回基础贴图。
- **退回基础身体是刻意的**：其余六职业的美术虽已交付但本轮不接，退回基础身体保证它们仍正常渲染，而不是变成缺贴图。**这也是本轮唯一的行为变化**——除了农民，其他职业与上一轮完全一样。
- 登记处：`GoblinSettlementClient` 多注册两个模型层；`GoblinModel`（傀儡占位）与 `GOLEM_LAYER` 一律不动。

## 4 验证

- **已有检查自动扩展**：`artModelCheck` 已由裁剪件清单驱动，加两个裁剪件即自动纳入——验六个分组名、脚底在艺术地面线、盒式 UV 不越出贴图、**PNG 像素恰为 `resolution` 的两倍**（512 → 1024），以及烘制后六个分组的位置。**若忘记给新裁剪件登记模型映射，它会直接报错**（这是上一轮补上的那条）。
- **新增一条覆盖性断言**：设计声称"农民有职业外观"。要在构建期钉住的是——**每个在代码里登记了职业外观的 `(职业, 性别)`，在裁剪件清单里都有对应项**。防的是"注册了但资源没进仓"：那种情况在游戏里只表现为缺贴图占位，是**纯运行时故障，构建期看不见**。
  **默认做法**：并入既有的 `artModelCheck`，**检查总数保持 21 项不变**。只有当实现时确认它既不需要客户端类、又与裁剪件校验毫无关系时，才单开一项，并在日志里说明为什么不能并。
- 完整离线构建 + 全部检查。
- **不可纯测**（照例写进日志与状态文件）：帽子在头上转动的实际效果、1024 贴图的观感与显存占用、职业外观在缩放/远距离下是否仍可辨认、多人同屏性能。

## 5 风险与已知边界

- **1024 贴图配 512 逻辑 UV** 是**所有职业共用的新拓扑**，理解错就整体纹理错位：靠"先只做农民一套"与 PNG 尺寸断言兜住。
- **外观烧进网格**：组合式做不到"拆掉配饰恢复基础角色"。今天不需要（居民不转岗，`assignProfession` 一次性），但**若将来要"职业装备可掉落/可换"，得改架构**。这条边界要写进状态文件。
- **模型数与贴图数随职业线性增长**：七职业全做完是 14 个模型类 + 14 张 1024 贴图。可接受，但值得记一笔，作为"何时该改分层"的判据。
- **哨卫 P07 未经审阅**：本轮不接它，所以它的造型改动不影响本轮；接它之前应先取得用户认可。
- **其余六职业退回基础身体**：这是本轮期间的可见中间态（比上一轮好一点——至少农民不再与它们相同）。
- **仍未做游戏内验证**：与整支分支一样。

## 6 后续轮次的落点

1. **其余六职业**（林工、矿工、建筑工、搬运员、工匠，以及待审的哨卫）：同一管线铺开，每加一个职业就是 `MODELS` 表两行 + 两张贴图 + 登记两处。
2. **手持道具**：等"工具成为物品"那一轮，用 `manifest.json` 里记的挂点偏移，做成挂在手臂上的渲染层。
3. **儿童**：模型 + 更小的实体尺寸与缩放 + 年龄同步（本轮仍未引入年龄同步）。
4. **五款傀儡**：五个模型 + 等级同步（`GoblinGolemEntity.tier` 至今**没有同步到客户端**）+ 核心发光的独立渲染层。
5. **音效与公告牌**：美术的首批样板（4 段样音、公告牌外观）**尚未经用户审阅**，且整套音效与物品/设施图标要等审阅后才扩量；公告牌是一个功能方块（注册、方块状态、放置、交互、动态信息都在代码侧），属功能轮而非美术轮。

## 7 落地结果（实现后补记）

（本节写于实现之后，2026-09-28。落点提交：`160954f` 设计 / `b754278` 计划 / `784fa34` 一具身体一张表 + 农民 / `0f8483f` 注册与渲染都走表。）

### 7.1 生成器只加了两行

§1 的核对结论成立。两套农民组合工程正好落在生成器**已支持**的一侧——顶层六个分组、盒式 UV、无逐面 UV、无多轴旋转、无缺 `uv_offset` 的元素；`resolution` 是 512×512，生成器读它，所以 `LayerDefinition.create(mesh, 512, 512)` 自动得出。因此 `tools/generate_models.py` **只多了 `MODELS` 表的两项**（`goblin_farmer_male_p01` / `goblin_farmer_female_p01`），算法、裁剪格式、拒绝规则与变换一行未改。§2 的数据流（`--crop` → 裁剪件 → 生成类 → 贴图 → 注册）全流程跑通。

### 7.2 一张表，三个读者

新增 `client/model/GoblinBodies`，其 `BODIES` 列出客户端能渲染的每一具身体：两具未着装的基础身体（男女）加农民两套（男女）。每行带几何裁剪件名、贴图基名、`createLayer` 工厂与 `ModelPart → 模型` 构造器。三个读者共用它：

- `GoblinSettlementClient` 按行注册一个模型层；
- `GoblinRenderer` 烘制并按行取用；
- `artModelCheck` 验证每行的裁剪件、贴图尺寸与烘出的几何。

层 id 由裁剪件名经**唯一函数** `GoblinSettlementClient.layer(String)` 派生，注册与烘焙共用它——两处不可能对不上；**加一个职业只改这一处**。

### 7.3 渲染器的选择与刻意的退路

§3 写的"渲染器持有 `Bodies male` / `Bodies female`"落地时**形状略有不同，就地更正**：`Bodies` 把模型与贴图**合在一个 record** 里（`base` + `baseTexture` + 两张并列的 map `outfits` / `textures`），查表方法是两个（`body(Profession)` / `texture(Profession)`），不是设计里的单个 `forProfession()`。`submit` 与 `getTextureLocation` 读**同一行**，所以农民不可能用别的职业的贴图。

其余六职业与 `UNASSIGNED` 回退到基础身体与基础贴图——**这是刻意的**，让未着装的职业仍然可渲染，而不是显示缺贴图。这也是本轮**唯一的行为变化**。

### 7.4 检查：从表派生，断言一条没少

`artModelCheck` 原先硬编码的那份 crop → 模型类映射被删除，改为从 `GoblinBodies.BODIES` 派生。审查用**定向 diff** 确认 `checkCrop` / `checkTexture` / `checkBaked` **没有任何 hunk**——这三处里既有断言全部原样保留、强度未减，本轮 19 条既有断言一条不少（逐条点名）：`checkCrop` 十条（resolution 存在 / 分组不带 rotation / 分组恰为六个必需名 / 有元素 / 全部盒式 UV / 三条 extent 不反向 / uv 展开在 u 内 / 在 v 内 / 脚底落在艺术地面线 / 高度上限），`checkTexture` 三条（贴图存在 / PNG 头完整 / 像素恰为逻辑 UV 的两倍），`checkBaked` 五条（`bakeRoot` 非空 / `hasChild` 负控能分辨 / 六个分组都在 / 六个分组的位姿与裁剪件轴心一致 / 烘出的网格真带几何），另加 main 里那条"裁剪件必须有可渲染身体映射"。**检查总数仍是 21 项，不新增检查任务。**

新增 `checkBodiesAgreeWithTheirCrops` 守住表本身：同一 `(职业, 性别)` 不得被两行认领、每行的裁剪件必须进仓。**覆盖性质的两个方向都是闭合的**：登记了却没有裁剪件会失败，验证了裁剪件却没有登记（main 的 `body != null`）也会失败。

### 7.5 一处必要的实现期偏离（已对着 Fabric API 核实）

计划逐字给的 `registerModelLayer(layer, body.layer())` 在**本版本 Fabric 不能编译**——`EntityModelLayerRegistry.registerModelLayer` 收的是 `TexturedModelDataProvider`，不是 `Supplier<LayerDefinition>`。实现在**唯一调用点**改成 `body.layer()::get`，共享的表一字未动。审查对着 Fabric API 源码确认了这条诊断。

### 7.6 §4 第二条是"有意收紧"，不是偏离

§4 第二条要的是"每个在代码里**登记了**职业外观的 `(职业, 性别)`，在裁剪件清单里都有对应项"。计划（自查 §2）把它实现成**更强的形式**：登记不再散落在渲染器里，只有 `GoblinBodies.BODIES` 一张表，注册处、渲染器、检查三者都从它读。于是"登记了但资源没进仓"这类错误**从结构上消失**——检查遍历的就是登记本身；换来一条**真会失败**的新断言（同一 `(职业, 性别)` 不得被两行认领）；代价是检查里那份硬编码映射被换成从表派生。**这不是丢掉了 §4 的措辞，是把它的目标换了个更难写错的形状。**

### 7.7 刻意不修的审查发现（parked，记录不改）

- `Bodies` 带两张**并列 map**（`outfits` / `textures`），同一次填充。今天按构造成立，但将来改一处而忘另一处，就会**重新引入跨职业贴图错配**——本轮存在的意义正是防它，值得尽早收敛（后两轮还要再加十二行）。
- `checkCrop` 用 `getAsInt()` 读元素坐标，而职业裁剪件带**小数**，所以裁剪那一半的 uv 展开与最低/最高检查跑在**截断后的整数**上（本轮没咬到——分组轴心是整数，位姿断言仍然精确——但上一轮日志已经承诺"职业到了就改 `getAsDouble()`"）。
- 贴图命名空间写死 `"goblin_settlement"` 字面量，与 `GoblinSettlement.MOD_ID` 并存。

### 7.8 未做 / 未验证

- **不做玩法验收**：帽子随头部转动的实际效果、1024 贴图的观感与显存、职业外观在缩放/远距离下的辨识度、多人同屏性能，**全部不可纯测**（只有编译与独立检查）。
- **组合式做不到"拆掉配饰恢复基础角色"**：今天不需要（居民不转岗，`assignProfession` 一次性），但将来要"职业装备可掉落/可换"就得改架构。
- **其余六职业未接**，含**尚未经用户审阅的哨卫 P07**；儿童、五款傀儡、音效、公告板方块仍未引入（**年龄同步与傀儡等级同步也未引入**）。
- **音效与公告板是美术交付的首批样板、待用户审阅**；物品图标与设施图标目录因此仍为空。

### 7.9 终审修复波（第六十二轮收尾，全分支终审后）

终审只报一项 Important（**不是 bug，当前无错**）：「安全网看不见一行指向哪个网格类」——四具身体（两具基础 + 农民男女）烘出的顶层轴心完全相同，所以"裁剪件对、类错"的一行能同时通过 `checkBaked` 与 `checkTexture`。四条修复：

1. **裁剪件 ↔ 类的链接：从"手打字符串"改成"写成一个记号"**。`generate_models.py` 在每个生成类的构造器旁多输出一行 `public static final String CROP = "<工程 id>";`（生成头注释与其余输出**逐字未动**；重跑生成器，四份裁剪件 `.json` 的 **sha256 逐字节不变**），`GoblinBodies` 四行的裁剪件字面量全部换成 `GoblinMaleModel.CROP` / `GoblinFemaleModel.CROP` / `GoblinFarmerMaleModel.CROP` / `GoblinFarmerFemaleModel.CROP`。**手打的裁剪件字符串从此不存在**：一行要写裁剪件，必须点名一个生成类（常量本身由**编译器**解析——名字写错编译不过），于是**裁剪件与类写成了同一个记号**，再也写不出一份"和它点名的类对不上"的裁剪件；若把类点成表里另一行已认领的类，那一行会**与那个类自己那一行撞车**（同一裁剪件被两行认领）而**构建失败**。这条链接因此从"人工核对"变成"**写不出不一致 + 点错即失败**"。
2. **贴图错位进构建**：`checkTexture` 在尺寸断言之后**解码 PNG**（`javax.imageio.ImageIO`）算出**非透明像素的外接矩形**，要求每个元素的盒式 UV 展开矩形（`u0 .. u0+2(w+d)`、`v0 .. v0+(h+d)`，像素 ×2、远边向下取整）落在其中，越界即**点名元素**失败。§5 第一条那条头号风险（1024 贴图配 512 逻辑 UV、整体纹理错位）从此在构建里可见。四具身体实测均有余量：男农民 bbox `(0,0,510,642)`、女农民 `(0,0,508,666)`。
3. **`getAsDouble()` 落到元素坐标**（关闭 §7.7 第 2 条）：`checkCrop` 读元素 `from`/`to` 与最低/最高改用 `getAsDouble()` + 既有 `1e-3` 容差；`resolution` 仍是整数，分组轴心仍是整数（四份裁剪件逐份核对过），位姿断言仍然精确。
4. **并行 map 收敛、命名空间归位**（关闭 §7.7 第 1、3 条）：`GoblinRenderer.Bodies` 的 `outfits` / `textures` 两张并列 map 收成一个 `Map<Profession, Outfit>`（`record Outfit(EntityModel<GoblinRenderState> model, Identifier texture)`——模型与贴图**按构造成对**，改一处忘另一处不可能再发生），行为不变；贴图命名空间改用 `GoblinSettlement.MOD_ID` 取代字面量。**§7.7 三条 parked 至此全部关闭。**

**验证**：`./gradlew clean build --offline --no-daemon` → **BUILD SUCCESSFUL**，**21 项**独立检查全部 `*Check passed`（`artModelCheck passed (4 crops, 4 baked models)`；**不新增检查任务**——新断言在 `artModelCheck` 内部），**无编译警告**，耗时 **52 秒**；`build/libs/goblin-settlement-0.1.0.jar` = **702463** 字节。**非空转证明**（均在临时副本上做完即还原、工作树干净）：把农民男那一行**连类一起**指向 `GoblinMaleModel` → `artModelCheck` 失败 `Duplicate key goblin_male_a`（**构建失败，不是 javac**）；把农民男裁剪件里 `farmer_hat_crown_top` 的 `uv_offset` 从 `[0,280]` 挪到 `[0,480]`（裁剪那一半仍通过）→ **新断言失败**并点名该元素。完整命令与输出见 `.superpowers/sdd/PROFESSION_ART_PLAN/final-fix-report.md`。

**残留（本轮未关，记档待裁决）**：第 1 条只把**裁剪件那一处**绑到类上；一行的 `createLayer` / 构造器仍是**另行书写的表达式**。因此"保留正确的 `Xxx.CROP`、只把这两个工厂换成别的类"（也就是终审那条 Important 的**另一半**：右裁剪件、错类）**仍能通过全部检查**——只是这样的行会把两个不同的类名并排写在同一行里，肉眼可见。若要彻底关掉，得让"类"在一行里只点名一次（例如由生成类同时给出裁剪件与两个工厂的**捆绑常量**），属结构性改动，本轮**未做**。
