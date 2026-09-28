# 职业美术接入 Implementation Plan（第一轮：农民）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让农民这一职业在游戏里有自己的外观（草帽、围裙），并把"职业 × 性别 → 选模型与贴图"这条逻辑建起来，好让其余六职业将来只是往一张表里加行。

**Architecture:** 一张静态表 `GoblinBodies` 声明客户端能渲染的每一具身体（两条无职业的基础身体 + 农民男女），每行给出裁剪件名、贴图名、烘制工厂与构造工厂。**渲染器、注册处、独立检查三者都从这一张表派生**——加一个职业只改一处。模型用美术已装配好的**组合工程**，现有生成器一行算法不改。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API / Java 21 / Gradle 9.2.1（离线）/ Python 3（生成器）。

**设计依据：** [PROFESSION_ART_DESIGN.md](PROFESSION_ART_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21。构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行（25–80 秒，Bash 超时给 300000 ms）。
- **生成器的算法一行不改**：只往 `MODELS` 表加两行。裁剪格式、拒绝规则、变换规则都不动。
- **生成物不许手改**：`GoblinFarmerMaleModel.java` / `GoblinFarmerFemaleModel.java` 由 `tools/generate_models.py` 产出。
- **`Models/` 不在版本控制里**，所以裁剪件与贴图都必须复制进仓。
- **不改存档结构**，**不动职业同步链路**（`ResidentRecord.profession`、`DATA_PROFESSION`、`GoblinRenderState.profession` 全部原样）。
- **不动傀儡线**：`GoblinModel`（傀儡占位）与 `GOLEM_LAYER` 一律不碰。
- **退回基础身体是刻意的**：没有职业外观的职业必须仍能正常渲染，不许退化成缺贴图。
- 检查项数**仍为 21**（新断言加进既有的 `artModelCheck`，不新增检查任务）。
- 不做游戏内验证（按用户约定）。帽子在头上转动的效果、1024 贴图的观感与显存、职业辨识度、多人同屏性能都**不可纯测**，写进日志与状态文件。
- `goblin-settlement-plan/` 下可能有并行 agent 的未提交改动：文档任务先跑 `git status`。`UpdateLog.md` **只许在末尾追加**。
- 提交到 `main`，本轮结束推送。

---

### Task 1: 一具身体一张表，农民进仓

**Files:**
- Create: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/model/GoblinBodies.java`
- Modify: `goblin-settlement-mod/tools/generate_models.py`（只加 `MODELS` 表两项）
- Create: `goblin-settlement-mod/tools/models/goblin_farmer_{male,female}_p01.json`（`--crop` 输出）
- Create: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/model/GoblinFarmer{Male,Female}Model.java`（生成物）
- Create: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/entity/goblin_farmer_{male,female}.png`（1024×1024）
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/client/ArtModelCheck.java`

**Interfaces:**
- Produces: `GoblinBodies.Body`、`GoblinBodies.BODIES`（Task 2 的注册与渲染、`artModelCheck` 都从这里读）
- Consumes: 既有的 `GoblinMaleModel` / `GoblinFemaleModel` 与其 `createLayer()`（上一轮）

- [ ] **Step 1: 生成器加两行**

在 `tools/generate_models.py` 的 `MODELS` 字典里加两项（**这是本任务对生成器的全部改动**）：

```python
    "goblin_farmer_male_p01": ("goblin_professions_a/goblin_farmer_male_p01.bbmodel", "GoblinFarmerMaleModel"),
    "goblin_farmer_female_p01": ("goblin_professions_a/goblin_farmer_female_p01.bbmodel", "GoblinFarmerFemaleModel"),
```

- [ ] **Step 2: 裁剪与生成**

```bash
cd goblin-settlement-mod
python tools/generate_models.py --crop
python tools/generate_models.py
```

Expected: 四行 `cropped ...`（基础两套 108/129 元素、农民两套 130/152），四行 `generated ...`。

**若农民那两套报错**：那不是"美术的数据有问题"，先看是不是生成器的某条拒绝规则被触发了。设计 §1 已核对过这两份工程没有逐面 UV、没有多轴旋转、没有缺 `uv_offset`、顶层恰好六个分组——**若报的正是这几条之一，说明核对结论有误，停下来回报**，不要放宽规则。

- [ ] **Step 3: 复制贴图**

```bash
cd "D:/MC/.minecraft/versions/1.21.11-Fabric 0.19.2"
E=goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/entity
cp Models/goblin_professions_a/goblin_farmer_male_p01.png   "$E/goblin_farmer_male.png"
cp Models/goblin_professions_a/goblin_farmer_female_p01.png "$E/goblin_farmer_female.png"
ls -l "$E"
```

Expected: 五张图，其中 `goblin_farmer_male.png` 与 `goblin_farmer_female.png` 均为 **1024×1024**。

- [ ] **Step 4: 写那张表**

创建 `src/client/java/dev/local/goblinsettlement/client/model/GoblinBodies.java`：

```java
package dev.local.goblinsettlement.client.model;

import dev.local.goblinsettlement.client.GoblinRenderState;
import dev.local.goblinsettlement.colony.Profession;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;

/**
 * Every goblin body the client can render, in one table.
 *
 * Three readers share it, which is the point: {@code GoblinSettlementClient} registers a model layer
 * per row, {@code GoblinRenderer} bakes and picks a row per resident, and {@code ArtModelCheck}
 * asserts each row's geometry crop and texture are actually committed. Adding a trade's outfit is one
 * row here and nothing else -- and because the check reads the same rows, a row whose art never made
 * it into the repository fails the build instead of showing up in game as a missing texture.
 */
public final class GoblinBodies {
    /** One renderable body. {@code profession} is empty for the two undressed base bodies. */
    public record Body(boolean female, Optional<Profession> profession, String crop, String texture,
                       Supplier<LayerDefinition> layer,
                       Function<ModelPart, EntityModel<GoblinRenderState>> model) {
    }

    public static final List<Body> BODIES = List.of(
            new Body(false, Optional.empty(), "goblin_male_a", "goblin_male",
                    GoblinMaleModel::createLayer, GoblinMaleModel::new),
            new Body(true, Optional.empty(), "goblin_female_a", "goblin_female",
                    GoblinFemaleModel::createLayer, GoblinFemaleModel::new),
            new Body(false, Optional.of(Profession.FARMER), "goblin_farmer_male_p01", "goblin_farmer_male",
                    GoblinFarmerMaleModel::createLayer, GoblinFarmerMaleModel::new),
            new Body(true, Optional.of(Profession.FARMER), "goblin_farmer_female_p01", "goblin_farmer_female",
                    GoblinFarmerFemaleModel::createLayer, GoblinFarmerFemaleModel::new));

    private GoblinBodies() {
    }
}
```

（`GoblinMaleModel::new` 这类方法引用指向生成了 `public X(ModelPart)` 的构造器；`Function<ModelPart, EntityModel<GoblinRenderState>>` 的目标类型允许返回类型是子类型，所以不需要转型。）

- [ ] **Step 5: 让检查从这张表派生，并断言表本身自洽**

`ArtModelCheck.java` 现在有两处硬编码：烘制那一半的 crop → 模型类映射，以及贴图名的来源。**把那份映射换成从 `GoblinBodies.BODIES` 派生**，并在 `main` 里加一条表自洽断言。

**先读一遍现有的 `ArtModelCheck.java`**，把这两处改成派生（其余断言一条都不许丢）：

```java
    /** The bodies the client can render, keyed by the crop the check validates them from. */
    private static final Map<String, GoblinBodies.Body> BODIES = GoblinBodies.BODIES.stream()
            .collect(Collectors.toUnmodifiableMap(GoblinBodies.Body::crop, body -> body));
```

并把原来用 `BAKED.get(name)` 取"模型类/贴图名"的地方改为从 `BODIES.get(name)` 取 `layer()` 与 `texture()`。**烘制那一半的既有断言（根非空、负控、六个分组名、六个分组的初始姿势与裁剪件推出的期望值一致、网格带几何）一条都不能少。**

在 `main` 里、遍历裁剪件**之前**加一条自洽断言：

```java
        checkBodiesAgreeWithTheirCrops();
```

并在文件末尾加：

```java
    /**
     * The table itself must be sane before anything reads it: no two rows may serve the same
     * (profession, sex), and no row may claim a crop that is not committed. A duplicate row would
     * silently shadow another one, and the reader that loses would never notice.
     */
    private static void checkBodiesAgreeWithTheirCrops() throws IOException {
        Set<String> served = new HashSet<>();
        for (GoblinBodies.Body body : GoblinBodies.BODIES) {
            String key = body.profession().map(Enum::name).orElse("BASE") + "/" + body.female();
            require(served.add(key), "two bodies claim " + key);
            Path crop = Path.of("tools", "models", body.crop() + ".json");
            require(Files.isRegularFile(crop),
                    "the body " + body.crop() + " has a committed crop at " + crop);
        }
    }
```

（`Set` / `HashSet` / `Collectors` 若未 import 就补。）

- [ ] **Step 6: 跑检查并跑完整构建**

Run: `./gradlew artModelCheck --offline --no-daemon`

Expected: `ArtModelCheck passed (4 crops, 4 baked models)`（**4 条**：基础两 + 农民两）。

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**21 项**全部 `*Check passed`。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/tools/ \
        goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/model/ \
        goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/entity/ \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/client/ArtModelCheck.java
git commit -m "Give the farmer a body, and keep every body in one table"
```

---

### Task 2: 注册与渲染都走那张表

**Files:**
- Modify: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/GoblinSettlementClient.java`
- Modify: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/GoblinRenderer.java`

**Interfaces:**
- Consumes: `GoblinBodies.BODIES` / `GoblinBodies.Body`（Task 1）
- Produces: 农民在有 `FARMER` 职业时渲染成职业外观；其余职业回退到基础身体

- [ ] **Step 1: 注册处逐行注册**

把 `GoblinSettlementClient` 的 `onInitializeClient` 换成：

```java
    @Override
    public void onInitializeClient() {
        for (GoblinBodies.Body body : GoblinBodies.BODIES) {
            EntityModelLayerRegistry.registerModelLayer(layer(body.crop()), body.layer());
        }
        EntityRenderers.register(ModEntities.GOBLIN, GoblinRenderer::new);
        GolemRenderer.initializeClient();
    }

    /** The layer a body is baked under. The crop id is the geometry's identity, so it names the layer. */
    public static ModelLayerLocation layer(String crop) {
        return new ModelLayerLocation(
                Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, crop), "main");
    }
```

删掉原来的 `GOBLIN_MALE_LAYER` / `GOBLIN_FEMALE_LAYER` 两个常量与它们的注册行——**层 id 现在由裁剪件名统一决定**。**`GOBLIN_LAYER` 上一轮已经删了，不要找它**；`GoblinModel`（傀儡占位）与 `GolemRenderer` 那条线不动。

- [ ] **Step 2: 渲染器按表烘焙与选择**

把 `GoblinRenderer` 换成：

```java
package dev.local.goblinsettlement.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.client.model.GoblinBodies;
import dev.local.goblinsettlement.colony.Profession;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.Identifier;

public final class GoblinRenderer
        extends MobRenderer<GoblinCitizenEntity, GoblinRenderState, EntityModel<GoblinRenderState>> {
    /**
     * One sex's bodies: the undressed base plus one outfit per trade that has art. A trade with no art
     * falls back to the base, which is why every lookup takes a default rather than returning null --
     * six of the seven trades are still undressed.
     */
    private record Bodies(EntityModel<GoblinRenderState> base, Identifier baseTexture,
                          Map<Profession, EntityModel<GoblinRenderState>> outfits,
                          Map<Profession, Identifier> textures) {
        EntityModel<GoblinRenderState> body(Profession profession) {
            return outfits.getOrDefault(profession, base);
        }

        Identifier texture(Profession profession) {
            return textures.getOrDefault(profession, baseTexture);
        }
    }

    private final Bodies male;
    private final Bodies female;

    public GoblinRenderer(EntityRendererProvider.Context context) {
        this(context, bake(context, false), bake(context, true));
    }

    private GoblinRenderer(EntityRendererProvider.Context context, Bodies male, Bodies female) {
        // super(...) must be the first statement, so it cannot take the field below -- this bakes the
        // male base twice and throws one tree away on the first submit. Cheap, and the alternative
        // (a shared holder) buys nothing here.
        super(context, male.base(), 0.3F);
        this.male = male;
        this.female = female;
    }

    /** Bakes every body of one sex from the table. The base is the row whose profession is empty. */
    private static Bodies bake(EntityRendererProvider.Context context, boolean female) {
        EntityModel<GoblinRenderState> base = null;
        Identifier baseTexture = null;
        Map<Profession, EntityModel<GoblinRenderState>> outfits = new EnumMap<>(Profession.class);
        Map<Profession, Identifier> textures = new EnumMap<>(Profession.class);
        for (GoblinBodies.Body body : GoblinBodies.BODIES) {
            if (body.female() != female) {
                continue;
            }
            var model = body.model().apply(context.bakeLayer(GoblinSettlementClient.layer(body.crop())));
            var texture = Identifier.fromNamespaceAndPath("goblin_settlement",
                    "textures/entity/" + body.texture() + ".png");
            if (body.profession().isEmpty()) {
                base = model;
                baseTexture = texture;
            } else {
                outfits.put(body.profession().orElseThrow(), model);
                textures.put(body.profession().orElseThrow(), texture);
            }
        }
        if (base == null) {
            throw new IllegalStateException("no base body for female=" + female + " in GoblinBodies.BODIES");
        }
        return new Bodies(base, baseTexture, Map.copyOf(outfits), Map.copyOf(textures));
    }

    @Override
    public GoblinRenderState createRenderState() {
        return new GoblinRenderState();
    }

    @Override
    public void extractRenderState(GoblinCitizenEntity entity, GoblinRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.profession = entity.professionForRender();
        state.female = entity.femaleForRender();
    }

    /**
     * The submit path reads the {@code model} field rather than calling {@code getModel()}, so choosing
     * a body means assigning that field before the superclass submits.
     */
    @Override
    public void submit(GoblinRenderState state, PoseStack pose, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        this.model = (state.female ? female : male).body(state.profession);
        super.submit(state, pose, collector, camera);
    }

    @Override
    public Identifier getTextureLocation(GoblinRenderState state) {
        return (state.female ? female : male).texture(state.profession);
    }
}
```

**注意 `submit` 里取的是 `.body(...)`（不是整条 `Bodies`）**——`model` 字段的类型是 `EntityModel<GoblinRenderState>`。

- [ ] **Step 3: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，21 项全部 `*Check passed`，**无编译警告**。

**若 `bake(...)` 里的局部变量在 lambda 里不可用**（`base` 被重新赋值、无法在 lambda 捕获）：本方法里没有任何 lambda 捕获它，只用了方法引用与普通循环；若编译器仍报"effectively final"，把 `base`/`baseTexture` 改成在循环后一次性判定，或用一个单元素数组承接。**以编译器的实际报错为准，不要靠猜。**

- [ ] **Step 4: 核对没有残留的旧层常量**

Run: `grep -rn "GOBLIN_MALE_LAYER\|GOBLIN_FEMALE_LAYER\|GOBLIN_LAYER" src/`

Expected: **无匹配**（层 id 现在全部由 `GoblinBodies` 的 crop 名派生）。

Run: `grep -rn "GoblinModel" src/client/java/ | grep -v "GoblinRenderer\|GoblinModelCandidate"`

Expected: 只剩 `defense/GolemRenderer.java`（傀儡那条线）与 `client/GoblinModel.java` 自身。**若 `GoblinModel` 出现在渲染器或模型目录之外的地方，回报。**

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/
git commit -m "Pick the body and texture from the one table at render time"
```

---

### Task 3: 文档与收尾

**Files:**
- Modify: `goblin-settlement-plan/PROFESSION_ART_DESIGN.md`（§7 落地结果）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Tasks 1–2 的改动与构建结果（**含 jar 实际字节数、构建秒数、每个提交的哈希**）

- [ ] **Step 1: 先查并行改动**

Run: `git status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：先单独提交它们并署名，再做本任务的文档改动。

- [ ] **Step 2: 设计文档记录落地结果**

把 `PROFESSION_ART_DESIGN.md` 末尾的 `## 7. 落地结果（实现后补记）` 替换为真实内容：生成器只改了两行、`GoblinBodies` 表的最终形状与"三个读者共用一张表"这件事、`artModelCheck` 从表派生后**断言一条没少**（逐条点名）、以及**实现期与设计不符之处的就地更正**——设计 §3 写的是"渲染器持有 `Bodies male/female`"，实现若与它一致就照写，若形状不同就地更正。

- [ ] **Step 3: 追加 UpdateLog**

在 `UpdateLog.md` **末尾**追加第六十二轮（职业美术接入第一轮：农民）的段落。`<...>` 换成真实值，**逐条写真实时间**（本项目多次因留占位符或写错时间而返工）。必须包含：

- 本轮范围与三处用户已定（先只做农民一套 / 只接外观、手持道具往后 / 用组合模型）；
- **为什么组合模型成立**：组合工程正好落在生成器已支持的形状一侧（顶层六个分组、盒式 UV、无逐面 UV、无多轴旋转、无缺 `uv_offset`、`resolution` 512 自动变成 `LayerDefinition.create(mesh, 512, 512)`），所以**生成器算法一行未改**；
- **一张表三个读者**：`GoblinBodies.BODIES` 同时被注册处、渲染器、独立检查读取——加一个职业只改一处；
- **刻意保留的退路**：其余六职业回退到基础身体，不会退化成缺贴图；
- 验证：完整离线构建、21 项检查（仍 21 项，**不新增检查任务**）、产物字节数与耗时、提交清单；
- **未完成**：不做玩法验收；帽子随头部转动的实际效果、1024 贴图的观感与显存、职业外观在缩放/远距离下的辨识度、多人同屏性能**全部不可纯测**；**组合式做不到"拆掉配饰恢复基础角色"**（今天不需要，但将来要"装备可掉落/可换"就得改架构）；其余六职业（含**尚未经用户审阅的哨卫 P07**）未接；仍未引入年龄同步、傀儡等级同步。

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻；「接续须知」的分支指针改成本轮收尾提交；**下一步落点**改写为：职业美术接入第一轮（农民）已完成，落点在 `PROFESSION_ART_DESIGN.md` §7；下一轮候选是其余六职业铺开 / 儿童 / 傀儡。
- 「阶段定位」阶段 6 补上"第六十二轮起按职业接美术外观（农民先行，一张表驱动）"。
- 「本轮接入的内容」新增一节「第六十二轮：职业美术接入（农民）」。
- 「美术候选」一段改写：农民已接入运行时资源；其余六职业与哨卫、儿童、五款傀儡仍未接入；音效与公告牌为**首批样板、待用户审阅**。
- 「本轮验证进展」替换为第六十二轮（21 项检查、产物字节数与耗时、提交）。
- 别处提到检查项数的地方一律仍是 **21**（**grep 一遍，别靠看**）。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the profession art round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。若连不上 github，提交留在本地并如实报告。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 数据流（生成器加两行 → 裁剪件 → 生成类 → 贴图 → 注册）→ Task 1 全流程 + Task 2 Step 1。
- 设计 §3「按职业 × 性别选」与"退回基础身体是刻意的"→ Task 1 Step 4 的表 + Task 2 Step 2 的 `Bodies` 与两个 `getOrDefault`。
- 设计 §4 第一条（已有检查自动扩展）→ Task 1 Step 5 的派生与 Step 6 的「4 crops, 4 baked models」。
- 设计 §4 第二条（覆盖性断言）→ 见下方"与本设计的一处有意收紧"。
- 设计 §5 各条风险 → Global Constraints 与 Task 3 Step 3 的"未完成"段。
- 设计 §6 后续落点 → Task 3 Step 4 的下一步落点。

**2. 与本设计的一处有意收紧（实现前先说明，免得被当成偏离）**

设计 §4 第二条写的是"断言每个**在代码里登记了**职业外观的 `(职业, 性别)` 在裁剪件清单里有对应项"，默认并入 `artModelCheck`。**计划把它实现成更强的形式**：登记不再散落在渲染器里，而是**只有 `GoblinBodies.BODIES` 一张表**，注册处、渲染器、检查三者都从它读。于是：

- "登记了但没有资源"这一类错误**从结构上消失**——检查遍历的就是登记本身，每条都必须有裁剪件（`checkBodiesAgreeWithTheirCrops`），贴图存在性由既有的 PNG 尺寸断言覆盖；
- 换来的是一条新的、**真正会失败**的断言：**同一 `(职业, 性别)` 不得被两行认领**（重复行会让其中一具身体被静默遮蔽）；
- 代价是 `artModelCheck` 的那份硬编码映射被删掉、改为从表派生。**既有的每一条断言都必须原样保留**（Task 1 Step 5 点明了这一点），检查总数仍是 21。

这不是偏离设计意图，是把它的目标（构建期挡住"注册了但资源没进仓"）用一个更难写错的形状实现。

**3. 占位符扫描**

- 无 TBD/TODO。表、渲染器、注册处、检查的新增部分都给了全文。
- Task 2 Step 3 给了一条**有判据的条件动作**（编译器若报 effectively final 就怎么改），不是含糊指令。
- 生成物（`GoblinFarmer{Male,Female}Model.java`）**不逐行贴出**，因为由脚本确定性产出；计划给出脚本改动、命令与验收断言。
- Task 3 的 `<...>` 是模板占位，正文要求逐条替换为真实值。

**4. 类型一致性**

- `GoblinBodies.Body` 的六个分量（`female` / `profession` / `crop` / `texture` / `layer` / `model`）在 Task 1 Step 4 定义；Task 1 Step 5 的检查读 `crop()` 与 `profession()`，Task 2 Step 1 读 `crop()` / `layer()`，Step 2 读 `female()` / `profession()` / `model()` / `texture()` / `crop()`——全部一致。
- `GoblinSettlementClient.layer(String crop) -> ModelLayerLocation` 在 Task 2 Step 1 定义，Step 2 的 `bake(...)` 消费；**同一个函数**同时给注册与烘焙，所以层 id 不可能对不上。
- `Bodies.body(Profession)` / `Bodies.texture(Profession)` 在 Task 2 Step 2 定义并在同任务的 `submit` / `getTextureLocation` 消费。
- `bake(Context, boolean) -> Bodies` 的参数顺序在两处调用与定义一致。
- 生成的 `GoblinFarmer{Male,Female}Model` 都继承上一轮的 `GoblinBodyModel`（生成器的模板决定），所以 `Function<ModelPart, EntityModel<GoblinRenderState>>` 的目标类型成立。
