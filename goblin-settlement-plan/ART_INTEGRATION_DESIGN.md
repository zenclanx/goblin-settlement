# 美术接入 设计（第一轮：成年男女）

更新日期：2026-09-28。依据：GAME_DESIGN 第 2 节「成年男女共用基础骨架，脸形、发型、声音和少量衣饰有所区别」；`Models/ART_ASSET_PRODUCTION_PLAN.md` 与各交付 README 的接入规格；CURRENT_STATUS「美术候选」段（候选模型尚未替换模组运行时资源）。

## 0. 范围

| | 内容 |
| --- | --- |
| **本轮做** | 成年男、女两套模型进游戏，走通「几何生成 → 客户端烘模型 → 按性别选模型与贴图 → 游戏内尺寸正确」整条管线 |
| **接受** | 七个职业的外观暂时一致（旧职业贴图映射不到新 UV，下一轮随可拆装备补回）。**这是本轮唯一可见的退步**，已由用户确认 |
| **明确不做** | 儿童、职业装备、五款傀儡、关键帧动画系统、实体碰撞箱调整、手持道具渲染 |
| **用户已定的三处** | ①程序化动画（不建关键帧系统）；②先只做成年男女；③碰撞箱维持 `0.6 × 1.45` |

## 1. 现状（已核对）

**模组侧**（`goblin-settlement-mod`）：

- 模型是**临时占位**，源码注释自己这么写（`client/GoblinModel.java:12`）。六个主分组 `head` / `body` / `left_arm` / `right_arm` / `left_leg` / `right_leg`，`LayerDefinition.create(mesh, 64, 64)`（`:44`）。
- 渲染器 `client/GoblinRenderer.java` 是 `MobRenderer<GoblinCitizenEntity, GoblinRenderState, GoblinModel>`；**职业只换贴图不换模型**——`getTextureLocation`（`:38-49`）按 `state.profession` 在 8 张常量里 switch，`UNASSIGNED` 用 `goblin.png`（`:9-16`）。
- 实体把职业同步给客户端：`GoblinCitizenEntity.DATA_PROFESSION`（`:67-68`），渲染侧读 `professionForRender()`（`:544-550`）。
- **没有任何性别或年龄同步**。`CHILD` 只是存档概念（`colony/ResidentRecord.java:16-17`），渲染器里没有 age/baby 分支，儿童与成人共用同一模型、同一缩放。
- 实体尺寸 `sized(0.6F, 1.45F)`（`citizen/ModEntities.java:23-24`），实体类不覆盖 `getDefaultDimensions`。
- 动画只有程序化摆动：`GoblinModel.setupAnim`（`:47-58`）读 `state.xRot/yRot/walkAnimationSpeed/walkAnimationPos`，无 `AnimationState`、无关键帧系统（全库无命中）。
- **`GoblinModel` 与 `goblin_golem.png` 仍被傀儡共用**：`defense/GolemRenderer.java` 用 `GoblinModel::createLayer` 与 `GOLEM_LAYER`，纹理是无条件的一张（类注释 `:13`）。**本轮不能删它们。**

**美术侧**（`Models/`）：

- 成年男 `goblin_male_a_final/`：108 立方体、六主分组、512×512 PNG（逻辑 UV 256×256）、`GoblinModelCandidate.java`（美术手工适配、已在本环境编译通过）、`animations.json`。
- 成年女 `goblin_female_a/`：129 立方体、同规格。
- 两者**脚底均为艺术网格 y=0、发顶 y=47**，接入缩放 0.5 → 最高约 1.47 格；耳朵与头发超出实体碰撞宽度（各 README 均注明需游戏内核对）。

**两条影响设计的关键事实（本轮实地验证）**：

1. **`Models/` 不在版本控制里。** 根 `.gitignore` 是 `/*`，只放行 `.gitignore`、`README.md`、`goblin-settlement-mod/`、`goblin-settlement-plan/`——`git ls-files Models` 为空。它是美术组的活工作区、随时会变，**构建不能依赖它**。
2. **渲染路径直接读 `model` 字段，不走 `getModel()`。** 反编译字节码确认：字段是 `protected M model`（`LivingEntityRenderer` 上），而**提交入口是 `public void submit(S, PoseStack, SubmitNodeCollector, CameraRenderState)`**——这个版本已经没有旧的 `render` 方法了。`getfield model` 出现在 `submit` 里 `submitModel` 与 `setupAnim` 两处之前；`getModel()` 只是 `return model` 的公开读取口（`RenderLayerParent` 接口要求）。**所以切换模型的正确挂点是把选中的模型赋给受保护的 `model` 字段**（渲染在 `submit` 里发生，子类可以改这个字段），覆写 `getModel()` 本身不会改变渲染结果。

## 2. 数据流与组件

```
Models/<工程>.bbmodel
      │  ①裁剪（只留 elements / groups / outliner / resolution）
      ▼
goblin-settlement-mod/tools/models/<name>.json          [提交进仓：模组自己的几何来源]
      │  ②生成（tools/generate_models.py，与 tools/generate_housing_blueprints.py 同一手法）
      ▼
src/client/java/.../client/model/<Name>Model.java        [提交进仓：随构建编译的真实产物]
      │  ③注册（GoblinSettlementClient）
      ▼
ModelLayerLocation goblin_male#main / goblin_female#main
      │  ④选择（GoblinRenderer 按同步的性别把选中的模型赋给 model 字段）
      ▼
屏幕上那一只哥布林
```

**为什么必须提交裁剪件**：`Models/` 进不了仓（见 §1 事实 1）。只提交生成的 Java 就没人能复现它——换台机器、或者美术组改了工程，产物就无从对照。裁剪件（去掉 UUID 引用之外的编辑器状态、贴图内嵌数据、动画）才是模组自己的、可复现的几何来源。

## 3. 几何生成器

### 3.1 变换规则（已实地验证，不是猜的）

生成器的核心是把 Blockbench 的几何翻译成 Minecraft 的 `CubeListBuilder` / `PartPose`。两条规则已在成年男模型上**逐字对上美术自己手工适配的 `GoblinModelCandidate.java`**：

```text
分组轴心：PartPose.offset(-origin.x, 24 - origin.y, origin.z)
立方体：  texOffs(uv_offset.x, uv_offset.y)
          .addBox( -(from.x - origin.x) - (to.x - from.x),
                   -(to.y - origin.y),
                    (from.z - origin.z),
                   to.x - from.x, to.y - from.y, to.z - from.z )
```

**验证证据**（`goblin_male_a_final.bbmodel` → `GoblinModelCandidate.java`）：

- 六条轴心全部命中：`head [0,29,0] → (0,-5,0)`、`body [0,16,0] → (0,8,0)`、`left_arm [-10,29,0] → (10,-5,0)`、`right_arm [10,29,0] → (-10,-5,0)`、`left_leg [-4,16,0] → (4,8,0)`、`right_leg [4,16,0] → (-4,8,0)`。**x 取负**、**y 是 `24 - origin.y`**、z 不变。
- 立方体在 `left_arm` 上验证（该组 `origin.x ≠ 0`，能暴露 x 的符号）：艺术元素 `left_sleeve from[-14,23,-3] to[-9,30,4]` → `(-1,-1,-3, 5,7,7)`，与候选的 `texOffs(152,115).addBox(-1,-1,-3, 5,7,7)` **逐字一致**，同组另两条（`177,115` / `208,115`）同样命中。头部因 `origin.x = 0` 看不出 x 的符号，所以这条**必须在侧边分组上验**。
- 分组名在 `groups` 里按 UUID 查（`outliner` 的节点自带 `uuid`），六条 `groups` 条目的 `name` 就是六个主分组名。嵌套子组（`top_lock_*_r1` 挂在 `head` 下、`*_strap_r1` 挂在 `body` 下）用 `PartPose.offsetAndRotation`，其**旋转符号同样以交叉校验为准**，不另立假设。

### 3.2 缩放与脚底对齐（已算清，不是待定值）

- **缩放 0.5 在生成时施加**，渲染器里不留魔数。
- **脚底对齐不是另算一个补偿量，而是"绕地面线缩放"**。两套成年模型的艺术 y 范围都是 **0 .. 47**（脚底在艺术 y=0、发顶 y=47），而 Minecraft 模型空间里地面线是 `y = 24`。因此对模型空间的每个 y 做**绕 `y = 24` 的缩放**：

  ```text
  y_scaled = 24 + s * (y_unscaled - 24)        s = 0.5
  ```

  它把脚底（未缩放时的 y=24）钉在原地，顶端落到 `24 - 0.5*47 = 0.5`，模型高 **23.5 模型单位 = 1.47 格**（与两份 README 的数字一致）。渲染器里已有的实体碰撞高是 1.45，两者几乎相等——**这正是"碰撞箱不动"这个决定成立的原因**。
- 落到生成器上就是两条闭式公式（`s`、`GROUND = 24`）：

  ```text
  轴心：(-origin.x * s,  GROUND - origin.y * s,  origin.z * s)
  盒子：x0 = ( -(from.x - origin.x) - (to.x - from.x) ) * s
        y0 = ( -(to.y - origin.y) ) * s
        z0 = ( from.z - origin.z ) * s
        w  = (to.x - from.x) * s      h = (to.y - from.y) * s      d = (to.z - from.z) * s
  ```

  x 与 z 绕模型竖轴缩放，所以轴心的 x/z 与盒子的相对 x/z 同乘 `s`；y 的"绕地面线"由轴心式里的 `GROUND - origin.y * s` 承担，盒子的 y 只乘 `s`。
- **`s = 1` 时这两条公式退化成 §3.1 那两条**，因此交叉校验（§3.3）在 `s = 1` 下做。

### 3.3 交叉校验（本轮的硬证据）

成年男性同时有 `.bbmodel` 与美术手工适配的 `GoblinModelCandidate.java`。**生成器必须能在 `scale = 1` 下重现那份候选**（逐条比对轴心与每个立方体的 `texOffs`/`addBox` 参数）。这是整条管线上唯一可自证的环节，实现时做一次，结果记入 UpdateLog。

### 3.4 裁剪件的形状与拒绝规则

裁剪件保留 `elements`（`name`、`from`、`to`、`origin`、`rotation`、`uv_offset`、`box_uv`）、`groups`、`outliner`、`resolution`、`name`。**遇到生成器不支持的形状必须明确失败，不许静默画错**：非盒式 UV（`box_uv: false` 或元素带 `faces`）、非零 `rotation` 之外未处理的元素类型、缺失的 `resolution`、顶层分组名不是规定的六个——一律报错并指出是哪个模型哪个元素。

## 4. 客户端的模型与贴图选择

### 4.1 性别从哪来

**借用存档里已有的事实，不新增持久字段**：`ResidentRecord.effectiveReproductiveRole()`（`colony/ResidentRecord.java:87-94`）给出 `MOTHER` / `FATHER`，而它对旧档的 `UNSPECIFIED` 有一条**稳定的 id 哈希回退**——也就是说**每个居民都已经有一个确定的性别**。

- `MOTHER` → 女，`FATHER` → 男。
- 实体新增一个同步布尔 `DATA_FEMALE`（与既有 `DATA_PROFESSION` 走同一条从名册推导、写进 `SynchedEntityData` 的路径）。
- **为什么不另存一个 `sex` 字段**：那会让"这个居民是男是女"变成两处真相，而繁殖角色里已经含有这个事实、且已经是稳定的。**风险已记入 §8**。

### 4.2 切换挂点

- 两个 `ModelLayerLocation`：`goblin_settlement:goblin_male#main`、`goblin_settlement:goblin_female#main`，各自 `createLayer`。
- `GoblinRenderer` 持有两份烘好的模型，**按渲染状态里同步过来的性别，把选中的那一份赋给受保护的 `model` 字段**（§1 事实 2：提交路径 `submit(...)` 读字段而不是读 `getModel()`）。覆写 `submit(...)` 只为赋值（选择完再交给 `super`）。**不覆写 `getModel()`**：继承的 `LivingEntityRenderer.getModel()` 本来就 `return model`，覆写与它逐字等价；它虽是 `RenderLayerParent` 要求的公开读取口，但继承的那个已经满足这个要求。（修复波删去了最初那次多余的覆写，见 §10.12。）
- 贴图随性别走：`textures/entity/goblin_male.png` / `goblin_female.png`。
- **渲染状态要带着性别**：`GoblinRenderState` 现在只有一个 `profession` 字段，需要再加一个。它是渲染状态的天然归属（与 `profession` 同源）。

## 5. 资源布局与旧资源处置

- 新贴图放 `assets/goblin_settlement/textures/entity/`，512×512 PNG，`LayerDefinition.create(mesh, 256, 256)`（逻辑 UV 256、位图两倍像素密度，Minecraft 原生支持）。
- **删除**：8 张旧职业贴图与 `goblin.png`（64×64，映射不到新 UV，留着就是死资源），以及 `GoblinRenderer` 里那 8 个纹理常量与按职业 switch 的逻辑。
- **保留**：`goblin_golem.png`、`GoblinModel`、`GOLEM_LAYER`——傀儡本轮不动，`GolemRenderer` 正复用它们（§1）。**`GoblinModel` 这个名字此后只服务傀儡**，这是本轮的一个命名瑕疵，已记入 §8；改名不在本轮（会牵动傀儡那条线）。

## 6. 动画

程序化，沿用现有 `setupAnim` 的形状（转头 + 摆腿 + 摆臂）。**新模型的六个主分组名与旧模型完全相同**，所以这套逻辑一行不用改就能搬到新模型上。

**明确不做关键帧系统**：美术交的 Blockbench 动画是造型与可动性的检查姿势，不是游戏动作定义；程序化是用连续量（走路相位、摆幅）算出来的，读不到关键帧。美术已交付的**姿势预览**（`bb_*_walk.png` / `bb_*_pose.png` / `arm_attack_check`）才是本轮调曲线的参照。

## 7. 验证

- **新增第 21 项独立检查 `artModelCheck`**，两部分：
  1. **验提交进仓的裁剪件**（不依赖任何客户端类）：逐模型断言顶层恰好是规定的六个分组名、脚底基准为 0（艺术网格）、全部为盒式 UV，以及**每个立方体的盒式 UV 展开矩形不越出贴图**（自 `uv_offset` 起算、`2*(w+d)` × `(h+d)`）。
     **不是**拿立方体的**空间**坐标去比贴图尺寸——那在任何一个居中的模型上都恒假（设计期原文写的就是那一句，实现时由审查纠正，见 §10）。
  2. **验真实几何**：把客户端 source set 的输出并进检查任务的 classpath，调 `createLayer()`，遍历 `PartDefinition.getChildren()`（`getChildren()` 返回 `Set<Map.Entry<String, PartDefinition>>`，可按名断言）确认六个分组真的在，并核对脚底基准。`LayerDefinition.bakeRoot()` 给出烘好的 `ModelPart`，必要时用它验位置。
     **实现时确认** loom 的 `sourceSets.client` 能否直接并进 `classpath`；若不能，这一半退化为只验裁剪件，并在日志与状态文件里如实说明，不许含糊过去。
- **生成器交叉校验**（§3.3）：`scale = 1` 下逐条重现美术候选，一次性、记入日志。
- 完整离线构建 + 全部检查（20 → 21 项）。
- **不可纯测**（照例写进日志与状态文件）：游戏内观感、光照、缩放与脚底是否真的对、耳朵/头发相对碰撞箱的实际穿墙程度、多人同屏性能。

## 8. 风险与已知边界

- **生成器写错就是两个模型一起错**：靠成年男那份交叉校验兜住（§3.3），且第一轮只做两个模型，出错面最小。
- **512×512 贴图配 256 逻辑 UV** 的组合如果理解错，会整体纹理错位：靠"先只做一个模型、先在游戏里看"兜住。
- **借用 `effectiveReproductiveRole()` 当性别**：它现在是稳定的（`withReproductiveRole` 只赋值一次），但它的**语义是繁殖角色、不是外观性别**。若将来出现"角色可变"或"外观性别与繁殖角色分离"的需求，这条要重看。**本轮不新增字段是有意的取舍**，记在此处。
- **`GoblinModel` 名字此后只服务傀儡**：本轮不改名（会牵动傀儡线），但读代码的人会疑惑。已记在案。
- **旧职业贴图删掉后**，本轮必须能编译能跑通，否则职业外观会缺失——所以删除动作与模型接入在同一个任务里完成，不留半成品状态。
- **实体碰撞箱不动**（用户已定）：耳朵与头发**各在两侧超出碰撞箱 0.23 格**——两套模型的模型空间 x 跨度都是 **−8.5 .. 8.5 模型单位 = 1.06 格**，而碰撞箱宽 0.6 格（实测见 §10.12）。与原版同源（村民的鼻子、玩家的手臂都超出碰撞箱），但这是**有意的**，不是遗漏。
- **裁剪件是美术数据的副本**：美术组改了工程，必须重跑生成器并提交新的裁剪件，否则两者会漂。检查只验裁剪件自洽，**不验它与 `Models/` 是否同步**（做不到——`Models/` 进不了仓）。这条边界要写进状态文件。
- **仍未做游戏内验证**：与本分支既有全部工作一样。

## 9. 后续轮次的落点

1. **职业装备**：成年男女的基础模型之上接可拆装备（美术已按职业交付组合工程与 `manifest.json`）。届时需要美术组补**各套骨架的手持道具挂点偏移**（现在只有成年男性写了锄头的轴心与平移）。
2. **儿童**：男孩/女孩模型 + 玩具；实体需要更小的尺寸与缩放，客户端需要知道谁是儿童（本轮没有引入年龄同步，届时新增）。
3. **五款傀儡**：五个模型 + 等级同步（`GoblinGolemEntity.tier` 目前**没有同步到客户端**，所以五级共用一张贴图）+ 核心发光的独立渲染层（发光蒙版美术已交付）。
4. **手持道具渲染**：把职业工具作为挂在手臂上的 3D 道具渲染；若工具还要成为**物品**，则需要美术组补 16×16 物品图标。

## 10. 落地结果（实现后补记）

### 10.1 生成器的最终形态

`goblin-settlement-mod/tools/generate_models.py`，两个入口，**刻意分开**：

- `--crop`：读 `Models/` 下的 `.bbmodel` 工程（如 `Models/goblin_male_a_final/goblin_male_a_final.bbmodel`），只留 `elements` / `groups` / `outliner` / `resolution` / `name`，写到 `goblin-settlement-mod/tools/models/`（如 `goblin_male_a.json`）。
- 默认运行：读那份裁剪件，产出 Java。

**为什么必须分两步**：`Models/` 不在版本控制里（根 `.gitignore` 是 `/*`，只放行 `.gitignore` / `README.md` / `goblin-settlement-mod/` / `goblin-settlement-plan/`；`git ls-files Models` 为空）。它是美术组的活工作区、随时会变，构建不能依赖它。所以**进仓的裁剪件才是本模组几何的唯一可复现来源**——这是结构性约束，不是取舍。

生成并提交：`GoblinMaleModel.java`、`GoblinFemaleModel.java`，两者都继承**手写**的 `GoblinBodyModel`，后者持有唯一一份共享的程序化 `setupAnim`（从那套占位 `GoblinModel` 逐字搬来）。六个必需分组、公开的 `(ModelPart)` 构造器、`createLayer()` 都由生成器产出。

### 10.2 变换规则是实地验证出来的（不是猜的）

四条规则，**逐条**对上美术自己手工适配的 `GoblinModelCandidate.java`：

1. 分组轴心 = `(-origin.x, 24 - origin.y, origin.z)`；
2. 立方体的 x 绕其轴心**镜像**，y **翻转**；
3. 嵌套子组的 `PartPose` 是**相对父级位置**的（"绝对减父级"）；
4. 单轴 Blockbench 旋转**同号**、以弧度记。

**交叉校验（§3.3）**：`SCALE = 1` 下生成器**逐字重现**那份候选——**108/108 个盒子、16/16 个姿势完全一致，两条差异清单均为空**。

### 10.3 缩放绕地面线，脚底不需要额外补偿量

艺术 y 范围 **0 .. 47**，模型空间地面线 `y = 24`；缩放 0.5 绕地面线做（`y_scaled = 24 + 0.5*(y - 24)`）。脚底（未缩放时的 y=24）因此**钉在原地**、顶端落到 0.5，模型高 **23.5 模型单位 = 1.47 格**，与实体碰撞高 **1.45** 几乎相等——这正是"碰撞箱不动"这个决定成立的原因，不需要另算一个补偿量。

### 10.4 `artModelCheck` 的实际形状

本轮新增的**第 21 项**独立检查，两半：

1. **验提交进仓的裁剪件**（不依赖任何客户端类）：顶层恰好是规定的六个分组名、脚底基准为 0（艺术网格，最低 y == 0）、逐立方体断言其**盒式 UV 展开矩形不越出贴图**（自 `uv_offset` 起算、`2*(w+d)` × `(h+d)`）、以及全部为盒式 UV（不带 `faces`）。
2. **验真实几何**：调 `createLayer().bakeRoot()` 得到烘好的 `ModelPart`，按名断言六个分组真的在（`root.hasChild(name)`），并带**一条负控**（`!root.hasChild("no_such_part")`），使一个"对什么都答 true 的 `hasChild`"无法让循环变成空转；另断言网格确实带着立方体几何。

**设计 §7.2 的悬而未决项已定**：loom 的 `sourceSets.client` 输出**确实并进了检查任务的 classpath**（`build.gradle` 里 `ArtModelCheck` 那段），所以第二半真的跑起来了，**没有退化成只验裁剪件**。

### 10.5 渲染器切换的最终签名

- `GoblinRenderer` 持有两份烘好的模型；`submit(...)` 在**调 `super.submit(...)` 之前**把选中的那一份赋给受保护的 `model` 字段（`this.model = state.female ? female : male;`）——因为这个版本的提交路径**直接读 `model` 字段、不走 `getModel()`**。最初一并覆写了 `getModel()` 以"保持一致"，修复波核对发现**它与继承实现逐字等价**（继承的 `LivingEntityRenderer.getModel()` 本就 `return model`），已删除（见 §4.2、§10.12）。除此之外**与设计 §4.2 一致**。
- 贴图随性别走：`goblin_male.png` / `goblin_female.png`，均 512×512，模型 `LayerDefinition.create(mesh, 256, 256)`。

### 10.6 实现期与设计/计划不符之处

1. **计划自带的生成器 `pose()` 绕 `y = 0` 缩放，而不是绕地面线**——这会让每个模型**上浮 0.75 格**；而交叉校验对它**结构性失明**，因为 `SCALE = 1` 时两种写法重合。已更正为绕 `GROUND` 缩放。
2. **`ArtModelCheck` 原先把立方体的*空间* x/z 与贴图分辨率相比**——这在任何一个居中的模型上都恒假（它**判错了 108 个正确立方体里的 103 个**）。已换成盒式 UV 展开矩形不越界的判据，审查认定它**强于**被替换的那条。
3. **设计 §7 第一部分自己写错了同一条不变量**（"所有立方体在 `[0, resolution]` 内"），已就地更正（提交 `5c8be01`）。
4. **计划提供的一条注释事实错误**：它称渲染器的双重构造"只花一个外壳、不是第二次烘"（`costs a shell, not a second bake`）。追客户端 jar 确认 `bakeLayer` 里**没有任何缓存**，所以那**确实是第二次烘**（在第一次 `submit` 时被丢弃）。**交付源码里的注释已按事实更正**（提交 `15c32e5`）；**计划 Task 4 的代码片段里仍留着旧的错误措辞**，本次收尾未动它。
5. **计划里一条约束建立在假前提上**：它要求保留 `GOBLIN_LAYER`，理由是"傀儡复用"——**傀儡从来烘的是自己的 `GOLEM_LAYER`**，`GOBLIN_LAYER` 已是死代码，**已删除**（提交 `15c32e5`）。**设计 §1/§5 本来就写对了**，是计划摘要时写岔的；计划那一条已就地更正（提交 `6dfc9f6`）。
6. **本设计 §7.2 要求烘制那一半"并核对脚底基准"，实现时 `checkBaked` 只断言了六个分组名存在、一条负控与"网格带几何"，没有任何位置断言**。这正是让上面第 1 条（绕 `y = 0` 缩放）**结构性漏过**的原因——`SCALE = 1` 交叉校验对那条规则失明，而烘制检查又不看位置，于是"生成器写错 → 两个模型一起错"这个本轮声明的**头号风险其实无人把守**。终审用一份改过六个顶层 `PartPose.offset` y 值的 `GoblinMaleModel`（放在 classpath 前面）证明了 `artModelCheck` **照样通过**。**已在收尾修复波补上**（见 §10.10、§10.12）。

### 10.7 性别来源

`ResidentRecord.looksFemale()` 借用既有的 `effectiveReproductiveRole()`，**不新增持久字段**；实体侧 `DATA_FEMALE` 同步标志在与职业同一条周期块里刷新，`GoblinRenderState.female` 把它带到客户端。**与设计 §4.1 一致。**

### 10.8 旧资源处置与遗留物

- **删除**：8 张旧 64×64 贴图（`goblin.png` 与七张职业贴图）以及 `GoblinRenderer` 里按职业切贴图的逻辑。
- **保留**：`GoblinModel`、`goblin_golem.png`（傀儡线仍用）。`GoblinModel` 这个名字**此后只服务傀儡**（§8 已记的命名瑕疵，未改名）。
- **遗留物（值得记）**：`goblin-settlement-mod/tools/generate_entity_textures.py` 是**半活跃**的——它写九张 64×64 文件，即本轮退役的那八个名字**加上 `goblin_golem.png`**；而 `goblin_golem.png` 是**它唯一生成、且仍在使用**的产物。重跑它会**重建本轮已退役的资源**，因此**刻意未动**。

### 10.9 未完成与未验证

- **完全没有游戏内验证**：模型观感、光照、缩放与脚底是否真的对、耳朵/头发相对碰撞箱的穿墙程度、多人同屏渲染性能——**全部不可纯测**。
- **七职业外观现在暂时一致**（退役的职业贴图映射不到 256 逻辑 UV 的模型上）——**本轮唯一可见的退步**，已由用户在开工前确认。
- **`SCALE = 1` 交叉校验只覆盖成年男**：成年女没有参照候选，她的几何只能依赖生成器本身是对的。

### 10.10 审查遗留项

1. ~~裁剪件里一个"含 `rotation` 字段但其值为 null"的元素会抛**裸 `TypeError`**，而不是走生成器的 `fail(...)`~~——**已在收尾修复波修掉**：`rotations()` 把 null 当作"无旋转"、`crop()` 不再写出 null（见 §10.12）。
2. ~~`ArtModelCheck.checkBaked` **写死了两个模型类**，所以将来加第三份裁剪件会**跳过烘制那一半**~~——**已在收尾修复波修掉**：烘制那一半改由裁剪件清单驱动、缺映射即报错，并补上了位置断言（见 §10.12）。
3. `SettlementSavedDataCheck.checkAppearanceSex` 里有一条 `require(x == x)` 式断言，是**恒真**的（**本轮仍不修**）；
4. `GoblinRenderState.profession` 现在**只写不读**（渲染器不再按职业选贴图）（**本轮仍不修**）。

### 10.11 验证

完整离线构建 `./gradlew clean build --offline --no-daemon` → **BUILD SUCCESSFUL**，**21 项**独立检查全部 `*Check passed`，**无编译警告**，耗时 **59 秒**。产物 `build/libs/goblin-settlement-0.1.0.jar` = **561852** 字节（上轮 469445：两张 512×512 贴图合计约 92 KB，抵掉删掉的八张 64×64）。

本轮提交，按序：`3eec77a`（设计）· `50cd93f`（计划）· `e8619de`（生成网格）· `8998b84`（烘制检查 + 旋转组拒绝）· `5c8be01`（设计更正）· `5f33769`（性别同步）· `e113d4b`（注册与渲染）· `15c32e5`（删死层、修注释）· `6dfc9f6`（计划更正）。记录提交 `2d554ff`，其后另有 `d3409ff`、`416c040`（`416c040` 把计划 Task 4 片段里那句假注释也一并更正了——即 §10.6 第 4 条原先记为"本次收尾未动它"的那句；补记见 UpdateLog.md）。

### 10.12 收尾修复波（全分支终审后，2026-09-28）

终审只报一项 Important，但**证明过**：`artModelCheck` 的烘制那一半不验任何几何位置。审查把 `GoblinMaleModel` 的六个顶层 `PartPose.offset` y 改成"绕 `y = 0` 缩放"的值、放到 classpath 前面重编，`artModelCheck` **照样通过**（把同名的 `head` 改成 `heads` 才让它失败，证明被加载的确实是那份改过的类）。这正是 §10.6 第 1 条那个**已经发生过**的 0.75 格上浮能被整条放过的原因。修复：

1. **烘制那一半改由裁剪件清单驱动**：`tools/models/*.json` 逐个经一张 `crop -> (模型类, 贴图)` 映射表查类，**裁剪件没有映射就报错**——第三份裁剪件（下一轮的职业、儿童）不会再被作物那一半查过、被烘制那一半静默跳过。
2. **补上位置断言**：对六个必需分组，把烘好的位置（`getInitialPose()`，不被随后的 `setupAnim` 扰动）与裁剪件 `groups[].origin` 推出的期望值逐分量比对（`x = -0.5*origin.x`、`y = 24 - 0.5*origin.y`、`z = 0.5*origin.z`，容差 `1e-3`，失败信息点名分组）。这条断言**由裁剪件推出**，因此也把检查的两半钉在了一起。
3. **裁剪件那一半**：把**永不触发**的"元素不带 `faces`"换成**实会触发**的"分组不得带 `rotation`"（生成器在裁剪时拒绝旋转分组、发射器又不读分组旋转，一份手改或旧裁剪件会静默画错）；并加一条 **PNG 头断言**，要求每份裁剪件的贴图像素**恰好是其 `resolution` 的两倍**（256 逻辑 UV ↔ 512×512 位图，设计 §8 记的风险）。
4. **生成器**：`rotations()` 与 `crop()` 现在把"含 `rotation` 键但其值为 null"当作"无旋转"（§10.10 第 1 条）。
5. **渲染器**：删掉 `GoblinRenderer.getModel()` 那次**与继承实现逐字等价**的覆写——继承的 `LivingEntityRenderer.getModel()` 本来就 `return model`（反编译核对）。计划 Task 4 的片段已同步删掉那四行。

**非空转证明**：把 `GoblinMaleModel` 六个顶层 y 改成绕 `y = 0` 缩放的值后，`artModelCheck` **失败**，信息为 `ArtModelCheck failed: goblin_male_a: part body is at (0.0, 4.0, 0.0), the crop's pivot implies (-0.0, 16.0, 0.0)`；改回后通过。改的是**临时副本，未提交任何改动过的生成文件**。

**验证**：`./gradlew clean build --offline --no-daemon` → **BUILD SUCCESSFUL**，**21 项**独立检查全部 `*Check passed`（仍是第 21 项，**未新增项数**），**无编译警告**，耗时 **58 秒**。产物 `build/libs/goblin-settlement-0.1.0.jar` = **561815** 字节（比上一步小 37 字节：删掉那次 `getModel()` 覆写使 `GoblinRenderer` 的 class 略小；检查与生成器都在 test/tools 里、不进 jar）。
