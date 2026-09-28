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
- `GoblinRenderer` 持有两份烘好的模型，**按渲染状态里同步过来的性别，把选中的那一份赋给受保护的 `model` 字段**（§1 事实 2：提交路径 `submit(...)` 读字段而不是读 `getModel()`）。覆写 `submit(...)` 只为赋值（选择完再交给 `super`）；`getModel()` 一并覆写以保持一致——它是 `RenderLayerParent` 要求的公开读取口，外部代码会用它。
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
  1. **验提交进仓的裁剪件**（不依赖任何客户端类）：逐模型断言顶层恰好是规定的六个分组名、脚底基准为 0（艺术网格）、所有立方体落在 `[0, resolution]` 内、全部为盒式 UV。
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
- **实体碰撞箱不动**（用户已定）：耳朵与头发会轻微穿墙。与原版同源（村民的鼻子、玩家的手臂都超出碰撞箱），但这是**有意的**，不是遗漏。
- **裁剪件是美术数据的副本**：美术组改了工程，必须重跑生成器并提交新的裁剪件，否则两者会漂。检查只验裁剪件自洽，**不验它与 `Models/` 是否同步**（做不到——`Models/` 进不了仓）。这条边界要写进状态文件。
- **仍未做游戏内验证**：与本分支既有全部工作一样。

## 9. 后续轮次的落点

1. **职业装备**：成年男女的基础模型之上接可拆装备（美术已按职业交付组合工程与 `manifest.json`）。届时需要美术组补**各套骨架的手持道具挂点偏移**（现在只有成年男性写了锄头的轴心与平移）。
2. **儿童**：男孩/女孩模型 + 玩具；实体需要更小的尺寸与缩放，客户端需要知道谁是儿童（本轮没有引入年龄同步，届时新增）。
3. **五款傀儡**：五个模型 + 等级同步（`GoblinGolemEntity.tier` 目前**没有同步到客户端**，所以五级共用一张贴图）+ 核心发光的独立渲染层（发光蒙版美术已交付）。
4. **手持道具渲染**：把职业工具作为挂在手臂上的 3D 道具渲染；若工具还要成为**物品**，则需要美术组补 16×16 物品图标。

## 10. 落地结果（实现后补记）

（待本轮实现后补写。）
