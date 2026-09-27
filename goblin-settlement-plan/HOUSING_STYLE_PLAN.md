# 住宅风格（更多蓝图）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让住宅数据文件容纳多种形制（一套风格 = 一整套五级链），`Home` 记住用了哪套，选择规则为"确定性偏好 + 放得下兜底"，并交付三套风格端到端可用。

**Architecture:** `BlueprintSet` 由"一条容量链 + 一条品质链"改为"一组风格"；每套风格各自持有一条容量链与一条品质链，四条既有校验逐套执行。风格下标随 `Home` 持久化（新字段，旧档默认 0 = 现有 cottage）。镜像（`variant`）机制完全不动，与风格正交。选择规则用锚点床坐标的显式哈希定"首选"，放不下再按数据序兜底。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [HOUSING_STYLE_DESIGN.md](HOUSING_STYLE_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。禁止引入其他 Minecraft 版本的 API 或示例。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **不做游戏内验证**：按用户约定，初版代码全部完成后才统一测试。本轮只做编译 + 独立检查。
- **cottage 的三级步数必须是 21 / 71 / 10**（累计 21 / 92 / 102）。这是"已建成的房子零行为变化"的证据；只要这三个数变了，就是现有几何被动过，必须回退。
- **风格 0 必须是 cottage**：旧档 `style` 缺省落到 0，只有 0 号是 cottage 才能保证旧存档里已建成的房子几何不变。
- **镜像机制一行不改**：`position()` 的 `variant == 2 ? -x : x` / `variant == 1 ? -z : z` 原样保留。
- 纯层（`BlueprintSet`）不得依赖 Minecraft 类型。
- 检查沿用项目既有写法：无 JUnit，`main` + `require`，成功打印 `XxxCheck passed`。`blueprintCheck` 任务已存在，本轮不改 `build.gradle`。
- 代码注释用英文（与现有源码一致）。
- `goblin-settlement-plan/` 下**可能有并行 agent 的未提交改动**（本仓库已发生过）：**在文档任务开始时**先跑 `git status`，发现不是自己改的就先单独提交它们并署名，再追加自己的。`UpdateLog.md` **只许在末尾追加**。

---

### Task 1: 数据模型换代、生成脚本与检查

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BlueprintSet.java`
- Modify: `goblin-settlement-mod/tools/generate_housing_blueprints.py`
- Modify: `goblin-settlement-mod/src/main/resources/data/goblin_settlement/housing_blueprints.json`（由脚本重生成）
- Test: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/BlueprintCheck.java`

**Interfaces:**
- Consumes: 无新外部依赖
- Produces:
  - `BlueprintSet.Style(String id, List<Stage> capacity, List<Stage> quality)`
  - `BlueprintSet(List<Style> styles, int reserveBasic, int reserveExpanded)`
  - `BlueprintSet.CODEC -> Codec<BlueprintSet>`
  - `BlueprintSet.steps(int styleIndex, int capacityTarget, int qualityTarget) -> List<HousingRules.Step>`
  - `BlueprintSet.stages(int styleIndex) -> List<List<HousingRules.Step>>`（容量级在前、品质级在后）
  - `BlueprintSet.validate(Set<String> knownBlocks) -> List<String>`
  - `BlueprintSet.entranceOpen(List<Stage>)`（包内可见，不变）

- [ ] **Step 1: 写失败检查（RED）**

在 `BlueprintCheck.java` 中把依赖旧形状的断言改掉，并新增风格相关的用例。

1a. `checkRealFile` 改为逐套校验，并断言至少一套、id 唯一：

```java
    private static void checkRealFile(BlueprintSet data) {
        var problems = data.validate(KNOWN);
        require(problems.isEmpty(), "the shipped blueprint file validates: " + problems);
        require(data.styles().size() >= 3,
                "the shipped file carries the cottage, the lean-to and the side porch");
        require(data.styles().get(0).id().equals("cottage"),
                "style 0 is the cottage, so old saves keep the geometry they were built with");
        require(data.styles().stream().map(BlueprintSet.Style::id).distinct().count()
                        == data.styles().size(),
                "style ids are unique");
    }
```

1b. `checkSteps` 改为对 cottage（下标 0）断言既有步数——**数值一字不改**：

```java
    private static void checkSteps(BlueprintSet data) {
        require(data.steps(0, 0, 0).size() == 21, "shelter only");
        require(data.steps(0, 1, 0).size() == 92, "shelter plus cabin");
        require(data.steps(0, 2, 0).size() == 102, "all three capacity stages");
        require(data.steps(0, 2, 1).size() == 111, "capacity plus the first quality stage");
        require(data.steps(0, 2, 2).size() == 121, "everything");
        require(data.steps(0, 1, 1).equals(data.steps(0, 1, 1)),
                "the same targets always compose the same list");
        for (var step : data.steps(0, 2, 2)) {
            require(step.x() >= -3 && step.x() <= 3 && step.z() >= -2 && step.z() <= 2
                    && step.y() >= 0 && step.y() <= 4, "every step stays inside the blueprint box");
            require(KNOWN.contains(step.block()), "every step names a known block");
        }
        boolean threw = false;
        try {
            data.steps(0, 3, 0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        require(threw, "targets beyond the ladder are rejected");
    }
```

1c. `checkFirstUnbuilt` 里的 `data.steps(a, b)` 全部改为 `data.steps(0, a, b)`。

1d. `checkCodecRoundTrip` 的 JSON 改为风格形状，并断言两套风格都能往返：

```java
    private static void checkCodecRoundTrip() {
        var json = JsonParser.parseString("""
                {"reserve_basic": 8, "reserve_expanded": 24,
                 "styles": [
                   {"id": "a", "capacity": [{"id": "s0", "steps": [{"x": 1, "y": 0, "z": -1, "block": "minecraft:oak_planks"}]}],
                              "quality": [{"id": "q0", "steps": []}]},
                   {"id": "b", "capacity": [{"id": "s0", "steps": []}],
                              "quality": [{"id": "q0", "steps": []}]}]}
                """);
        BlueprintSet parsed = BlueprintSet.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        require(parsed.styles().size() == 2, "every style decodes");
        require(parsed.styles().get(0).id().equals("a"), "style order is preserved");
        require(parsed.styles().get(0).capacity().get(0).requires().isEmpty(),
                "an absent requires decodes to empty");
        var reencoded = BlueprintSet.CODEC
                .encodeStart(JsonOps.INSTANCE, parsed).getOrThrow().getAsJsonObject();
        BlueprintSet again = BlueprintSet.CODEC.parse(JsonOps.INSTANCE, reencoded).getOrThrow();
        require(again.equals(parsed), "a round trip is lossless");
    }
```

1e. 新增一条**每套风格都必须合法**的用例（这是本轮 RED 的核心，因为新形制还没写、旧断言还会挂在旧模型上）：

```java
    private static void checkEveryStyleIsBuildable(BlueprintSet data) {
        for (int index = 0; index < data.styles().size(); index++) {
            var style = data.styles().get(index);
            require(style.capacity().size() == HousingRules.MAX_CAPACITY_TARGET + 1,
                    style.id() + " has a full capacity ladder");
            require(style.quality().size() == HousingRules.MAX_QUALITY_TARGET,
                    style.id() + " has a full quality ladder");
            require(!data.steps(index, 0, 0).isEmpty(), style.id() + " builds something at level 0");
            for (var step : data.steps(index, 2, 2)) {
                require(step.x() >= -3 && step.x() <= 3 && step.z() >= -2 && step.z() <= 2
                                && step.y() >= 0 && step.y() <= 4,
                        style.id() + " stays inside the blueprint box");
            }
            // No stage may place the same cell twice across the whole ladder: the levels are
            // additive, so a repeat would mean two stages fighting over one block.
            var seen = new HashSet<Long>();
            for (var step : data.steps(index, 2, 2)) {
                long key = ((long) (step.x() + 64) << 42) ^ ((long) (step.y() + 64) << 21)
                        ^ (step.z() + 64);
                require(seen.add(key), style.id() + " places " + step.x() + "," + step.y() + ","
                        + step.z() + " more than once across its ladder");
            }
        }
    }
```

并把 `checkEveryStyleIsBuildable(data);` 加进 `main`。

1f. `checkBounds` / `checkChainRules` / `checkEntrance` / `checkBlocksAreNotRestricted` 里的构造：`new BlueprintSet(capacity, qualities(), 8, 24)` 改为 `new BlueprintSet(List.of(new BlueprintSet.Style("probe", capacity, qualities())), 8, 24)`。用一个私有助手收口，避免散落：

```java
    /** Wraps a hand-made capacity chain into an otherwise valid single-style set. */
    private static BlueprintSet setOf(List<BlueprintSet.Stage> capacity) {
        return new BlueprintSet(
                List.of(new BlueprintSet.Style("probe", capacity, qualities())), 8, 24);
    }
```

此后 `valid(...)` / `problemsWithStage(...)` / `reserveProblem(...)` 都改为调用 `setOf(...)`，不再各自 new。

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew compileTestJava --offline --no-daemon`

Expected: 编译失败，报错形如 `找不到符号: 方法 styles()` / `类 Style`。失败原因必须是"新模型不存在"。

- [ ] **Step 3: 改数据模型（GREEN 的一半）**

`BlueprintSet` 中：

3a. 把 record 头与 `Stage` 换成：

```java
public record BlueprintSet(List<Style> styles, int reserveBasic, int reserveExpanded) {
    /** One complete house style: a full capacity ladder plus a full quality ladder. */
    public record Style(String id, List<Stage> capacity, List<Stage> quality) {
    }

    /** {@code requires} is empty for a chain head, otherwise the id of the previous stage. */
    public record Stage(String id, String requires, List<HousingRules.Step> steps) {
    }
```

3b. `CODEC` 换成：

```java
    public static final Codec<Style> STYLE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Style::id),
            STAGE_CODEC.listOf().fieldOf("capacity").forGetter(Style::capacity),
            STAGE_CODEC.listOf().fieldOf("quality").forGetter(Style::quality)
    ).apply(instance, Style::new));

    public static final Codec<BlueprintSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            STYLE_CODEC.listOf().fieldOf("styles").forGetter(BlueprintSet::styles),
            Codec.INT.fieldOf("reserve_basic").forGetter(BlueprintSet::reserveBasic),
            Codec.INT.fieldOf("reserve_expanded").forGetter(BlueprintSet::reserveExpanded)
    ).apply(instance, BlueprintSet::new));
```

`STEP_CODEC` 与 `STAGE_CODEC`（含 `requires` 的 `optionalFieldOf`）**保持不变**。

3c. `steps` / `stages` 带上下标：

```java
    public List<HousingRules.Step> steps(int styleIndex, int capacityTarget, int qualityTarget) {
        Style style = styleAt(styleIndex);
        if (capacityTarget < 0 || capacityTarget >= style.capacity().size()
                || qualityTarget < 0 || qualityTarget > style.quality().size()) {
            throw new IllegalArgumentException("Blueprint targets out of range");
        }
        List<HousingRules.Step> result = new ArrayList<>();
        for (int index = 0; index <= capacityTarget; index++) {
            result.addAll(style.capacity().get(index).steps());
        }
        for (int index = 1; index <= qualityTarget; index++) {
            result.addAll(style.quality().get(index - 1).steps());
        }
        return List.copyOf(result);
    }

    /** Every stage of one style in the order the coordinator indexes them: capacity, then quality. */
    public List<List<HousingRules.Step>> stages(int styleIndex) {
        Style style = styleAt(styleIndex);
        List<List<HousingRules.Step>> result = new ArrayList<>();
        for (Stage stage : style.capacity()) {
            result.add(List.copyOf(stage.steps()));
        }
        for (Stage stage : style.quality()) {
            result.add(List.copyOf(stage.steps()));
        }
        return List.copyOf(result);
    }

    public int styleCount() {
        return styles.size();
    }

    /** An out-of-range index degrades to the cottage rather than failing a world load. */
    public Style styleAt(int styleIndex) {
        if (styleIndex < 0 || styleIndex >= styles.size()) {
            return styles.get(0);
        }
        return styles.get(styleIndex);
    }
```

3d. `validate` 改为逐套 + 外层规则：

```java
    public List<String> validate(Set<String> knownBlocks) {
        List<String> problems = new ArrayList<>();
        if (styles.isEmpty()) {
            problems.add("at least one style is required");
        }
        if (reserveBasic <= 0) {
            problems.add("reserve_basic must be positive");
        }
        if (reserveExpanded <= 0) {
            problems.add("reserve_expanded must be positive");
        }
        Set<String> ids = new HashSet<>();
        for (Style style : styles) {
            if (style.id() == null || style.id().isEmpty()) {
                problems.add("a style has an empty id");
            } else if (!ids.add(style.id())) {
                problems.add("duplicate style id " + style.id());
            }
            if (style.capacity().size() != HousingRules.MAX_CAPACITY_TARGET + 1) {
                problems.add(style.id() + " needs " + (HousingRules.MAX_CAPACITY_TARGET + 1)
                        + " capacity stages, found " + style.capacity().size());
            }
            if (style.quality().size() != HousingRules.MAX_QUALITY_TARGET) {
                problems.add(style.id() + " needs " + HousingRules.MAX_QUALITY_TARGET
                        + " quality stages, found " + style.quality().size());
            }
            validateChain(style.id() + "/capacity", style.capacity(), knownBlocks, problems);
            validateChain(style.id() + "/quality", style.quality(), knownBlocks, problems);
        }
        return List.copyOf(problems);
    }
```

`validateChain` / `validateSteps` / `entranceOpen` / `seed` / `key2` / `key3` **不变**（它们已经只依赖一条链）。

- [ ] **Step 4: 生成脚本改为产出三套风格**

把 `tools/generate_housing_blueprints.py` 的 `main()` 与 `stage()` 改为产出 `styles` 结构；`shelter()`/`cabin()`/`expanded()`/`quality()`/`mature()` 保留为 **cottage** 的几何（**一个字都不改**，它们是"已建成房子零变化"的证据）。新增：

```python
def lean_to_shed():
    steps = []
    for x in (-1, 1):
        for z in (-2, 2):
            for y in range(0, 3):
                steps.append((x, y, z))
    for x in range(-1, 2):
        for z in range(-2, 3):
            steps.append((x, 3, z))
    return steps


def lean_to_walled():
    steps = []
    for x in (-1, 1):
        for z in range(-1, 2):
            for y in range(0, 3):
                steps.append((x, y, z))
    for y in range(0, 3):
        steps.append((0, y, 2))
    # Two-high doorway on the -z side: only the block above it is placed.
    steps.append((0, 2, -2))
    return steps


def lean_to_annex():
    steps = [(2, y, z) for y in range(0, 3) for z in (-1, 1)]
    steps += [(2, 3, z) for z in range(-1, 2)]
    return steps


def lean_to_ridge():
    return [(x, 4, 0) for x in range(-1, 2)] + [(0, 4, z) for z in (-1, 1)]


def lean_to_eaves():
    return [(x, 4, -2) for x in range(-1, 2)] + [(x, 4, 2) for x in range(-1, 2)]


def side_porch_shed():
    steps = []
    for x in (-2, 2):
        for z in (-1, 1):
            for y in range(0, 3):
                steps.append((x, y, z))
    for x in range(-2, 3):
        for z in range(-1, 2):
            steps.append((x, 3, z))
    for x in (-2, 0, 2):
        for y in range(0, 3):
            steps.append((x, y, 2))
    for x in range(-2, 3):
        steps.append((x, 3, 2))
    return steps


def side_porch_walled():
    steps = []
    for x in (-2, 2):
        for y in range(0, 3):
            steps.append((x, y, 0))
    for z in (-1, 1):
        for x in (-1, 0, 1):
            for y in range(0, 3):
                steps.append((x, y, z))
    # Two-high doorway in the -z wall.
    steps = [s for s in steps if not (s[0] == 0 and s[2] == -1 and s[1] in (0, 1))]
    return steps


def side_porch_upper():
    return [(x, 4, 0) for x in range(-2, 3)]


def side_porch_eaves_a():
    return [(x, 4, 1) for x in range(-2, 3)] + [(x, 4, -1) for x in range(-2, 3)]


def side_porch_eaves_b():
    return [(x, 4, 2) for x in range(-2, 3)] + [(x, 4, -2) for x in range(-2, 3)]
```

并把 `main()` 改为：

```python
def main():
    cottage = {
        "id": "cottage",
        "capacity": [stage("shelter", shelter()),
                     stage("cabin", cabin(), "shelter"),
                     stage("expanded", expanded(), "cabin")],
        "quality": [stage("quality", quality()),
                    stage("mature", mature(), "quality")],
    }
    lean_to = {
        "id": "lean_to",
        "capacity": [stage("shed", lean_to_shed()),
                     stage("walled", lean_to_walled(), "shed"),
                     stage("annex", lean_to_annex(), "walled")],
        "quality": [stage("ridge", lean_to_ridge()),
                    stage("eaves", lean_to_eaves(), "ridge")],
    }
    side_porch = {
        "id": "side_porch",
        "capacity": [stage("shed", side_porch_shed()),
                     stage("walled", side_porch_walled(), "shed"),
                     stage("upper", side_porch_upper(), "walled")],
        "quality": [stage("eaves_a", side_porch_eaves_a()),
                    stage("eaves_b", side_porch_eaves_b(), "eaves_a")],
    }
    payload = {"reserve_basic": 8, "reserve_expanded": 24,
               "styles": [cottage, lean_to, side_porch]}
    ...  # 写文件与打印同前
```

打印部分改为逐套逐级打印步数，并**特别打印 cottage 的三个数**：

```python
    for style in payload["styles"]:
        sizes = [len(s["steps"]) for s in style["capacity"]]
        print(style["id"], "capacity sizes:", sizes, "quality sizes:",
              [len(s["steps"]) for s in style["quality"]])
    print("COTTAGE MUST BE [21, 71, 10]:",
          [len(s["steps"]) for s in payload["styles"][0]["capacity"]])
```

- [ ] **Step 5: 生成并核对**

Run: `cd goblin-settlement-mod && python tools/generate_housing_blueprints.py`

Expected 末行：`COTTAGE MUST BE [21, 71, 10]: [21, 71, 10]`。若不是，**停下**——说明 cottage 的几何被动过。

- [ ] **Step 6: 运行检查，确认转绿**

Run: `./gradlew blueprintCheck --offline --no-daemon`

Expected: `BlueprintCheck passed`。

若失败在新形制上（入口被封死、坐标越出校验盒、某一格被两级重复投放），**按报错修正 Step 4 里那套形制的几何**，然后重跑。校验器就是本轮几何的裁判；新形制是开发方起草的，出错是预期内的迭代，但**绝不允许**为了让它过而放宽校验规则。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BlueprintSet.java \
        goblin-settlement-mod/tools/generate_housing_blueprints.py \
        "goblin-settlement-mod/src/main/resources/data/goblin_settlement/housing_blueprints.json" \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/BlueprintCheck.java
git commit -m "Give the blueprint data a style dimension"
```

---

### Task 2: 加载器与 `Home` 持久化风格

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingBlueprints.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingSavedData.java`

**Interfaces:**
- Consumes: Task 1 的 `BlueprintSet.Style` / `styleCount()` / `styleAt(int)` / `steps(int,int,int)` / `stages(int)`
- Produces:
  - `HousingBlueprints.ResolvedStep(int x, int y, int z, Block block, Item item)`（不变）
  - `HousingBlueprints.stages(int styleIndex) -> List<List<ResolvedStep>>`
  - `HousingBlueprints.steps(int styleIndex, int capacityTarget, int qualityTarget) -> List<ResolvedStep>`
  - `HousingBlueprints.styleCount() -> int`
  - `HousingBlueprints.reserveBasic()/reserveExpanded()`（不变）
  - `HousingSavedData.Home` 增 `style()`（`int`，JSON 字段 `style`，缺省 0）

- [ ] **Step 1: `Home` 加 `style` 字段**

在 `HousingSavedData.Home` 中：

1a. record 头加字段（`variant` 之后）：

```java
    public record Home(BlockPos bed, int variant, int style, int capacityTarget, int qualityTarget,
                       Optional<String> workerId) {
```

1b. codec 的 `group` 里，在 `"blueprint"` 那行之后加：

```java
                Codec.INT.optionalFieldOf("style", 0).forGetter(Home::style),
```

1c. 范围校验里加 `style < 0`（越界由 `BlueprintSet.styleAt` 在读取时钳到 0，不在这里拒绝——数据文件变化不该让存档打不开）：

```java
            if (variant < 0 || variant > 2 || capacityTarget < 0 || style < 0
                    || qualityTarget < 0 || capacityTarget > HousingRules.MAX_CAPACITY_TARGET
                    || qualityTarget > HousingRules.MAX_QUALITY_TARGET) {
                throw new IllegalArgumentException("Home fields out of range");
            }
```

（以该文件实际的校验语句为准改，保持原有上限判断不变，只补 `style < 0`。）

1d. 所有 `new Home(...)` 构造点补齐新参数：`withCapacityTarget`/`withQualityTarget`/`withWorker` 等 `with*` 方法把 `style` 原样带过去；`migrate` 方法里旧档的 `new Home(bed, variant, capacity, quality, workerId)` 改为 `new Home(bed, variant, 0, capacity, quality, workerId)`。

用 `grep -n "new Home(" src/main/java/dev/local/goblinsettlement/housing/HousingSavedData.java` 找出全部构造点，逐个补。

- [ ] **Step 2: `HousingBlueprints` 按风格解析**

2a. 缓存由 `List<List<ResolvedStep>> stages` 改为 `List<List<List<ResolvedStep>>> styles`（每个风格一份 `stages` 布局：容量级在前、品质级在后）。

2b. `load()` 里把 `data.stages()` 换成逐风格：

```java
            List<List<List<ResolvedStep>>> resolvedStyles = new ArrayList<>();
            for (int index = 0; index < data.styleCount(); index++) {
                List<List<ResolvedStep>> resolved = new ArrayList<>();
                for (List<HousingRules.Step> branch : data.stages(index)) {
                    ...  // 与现有解析循环逐字相同
                }
                resolvedStyles.add(List.copyOf(resolved));
            }
            styles = List.copyOf(resolvedStyles);
```

2c. 访问器改为：

```java
    public static int styleCount() {
        return styles.isEmpty() ? 0 : styles.size();
    }

    /** An out-of-range style degrades to the first one, so a removed style cannot break a world. */
    public static List<List<ResolvedStep>> stages(int styleIndex) {
        if (styles.isEmpty()) {
            return List.of();
        }
        if (styleIndex < 0 || styleIndex >= styles.size()) {
            return styles.get(0);
        }
        return styles.get(styleIndex);
    }

    public static List<ResolvedStep> steps(int styleIndex, int capacityTarget, int qualityTarget) {
        List<List<ResolvedStep>> style = stages(styleIndex);
        List<ResolvedStep> result = new ArrayList<>();
        for (int index = 0; index <= capacityTarget; index++) {
            result.addAll(style.get(index));
        }
        for (int index = 1; index <= qualityTarget; index++) {
            result.addAll(style.get(HousingRules.MAX_CAPACITY_TARGET + index));
        }
        return List.copyOf(result);
    }
```

`available()` / `reserveBasic()` / `reserveExpanded()` / `ResolvedStep` / `decode()` **不变**。

- [ ] **Step 3: 编译（此时协调器尚未改，必然报错）**

Run: `./gradlew compileJava --offline --no-daemon`

Expected: 失败，错误集中在 `HousingCoordinator` 对 `HousingBlueprints.stages()` / `steps(...)` 的调用。这是下一步要修的。

**不要**在这一步提交。

---

### Task 3: 协调器接线与选择规则

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BedProvisioningCoordinator.java`（若它引用了被改的访问器）

**Interfaces:**
- Consumes: Task 2 的 `HousingBlueprints.stages(int)` / `steps(int,int,int)` / `styleCount()`；`Home.style()`
- Produces:
  - `HousingCoordinator.preferredStyle(BlockPos bed, int styleCount) -> int`（包内可见，便于检查）
  - 其余方法签名不变（`stepAt` / `assignedStep` / `nextSite` / `stageFullyBuilt` / `siteSuitable` 都从 `home` 取风格）

- [ ] **Step 1: 加确定性偏好**

在 `HousingCoordinator` 中加：

```java
    /**
     * The style a home at this bed prefers. An explicit formula rather than BlockPos.hashCode(),
     * whose contract gives no stability guarantee across versions -- this value ends up in a save.
     */
    static int preferredStyle(BlockPos bed, int styleCount) {
        if (styleCount <= 0) {
            return 0;
        }
        return Math.floorMod(bed.getX() * 31 + bed.getZ() * 17, styleCount);
    }
```

- [ ] **Step 2: 注册房子时按"首选 → 其余"× 镜像选择**

把 `tick` 里这段

```java
                    for (int variant = 0; variant < 3; variant++) {
                        if (siteSuitable(level, id, bed, variant)) {
                            housing.add(new HousingSavedData.Home(bed, variant, 0, 0));
                            return;
                        }
                    }
```

替换为：

```java
                    int styleCount = HousingBlueprints.styleCount();
                    int preferred = preferredStyle(bed, styleCount);
                    for (int offset = 0; offset < styleCount; offset++) {
                        int style = (preferred + offset) % styleCount;
                        for (int variant = 0; variant < 3; variant++) {
                            if (siteSuitable(level, id, bed, variant, style)) {
                                housing.add(new HousingSavedData.Home(bed, variant, style, 0, 0));
                                return;
                            }
                        }
                    }
```

（`new Home(...)` 的参数个数以 Task 2 改完后的实际签名为准。）

- [ ] **Step 3: 其余调用点带上风格**

3a. `nextSite`：

```java
        for (HousingBlueprints.ResolvedStep step : HousingBlueprints.steps(
                home.style(), home.capacityTarget(), home.qualityTarget())) {
```

3b. `stepAt`：同上，用 `home.style()`。

3c. `stageFullyBuilt`：

```java
        for (HousingBlueprints.ResolvedStep step : HousingBlueprints.stages(home.style()).get(stageIndex)) {
```

3d. `siteSuitable` 加一个风格参数，并在内部用它取步骤列表：

```java
    private static boolean siteSuitable(ServerLevel level, String id, BlockPos bed, int variant,
                                        int style) {
        ...
        for (var stage : HousingBlueprints.stages(style)) for (var step : stage) {
            BlockPos site = position(level, bed, variant, step);
            ...
        }
        return true;
    }
```

（方法体其余部分逐字不动。）

- [ ] **Step 4: 检查其它引用点**

Run: `grep -rn "HousingBlueprints.stages()\|HousingBlueprints.steps(" src/main/java/`

Expected: 不应再有无参调用。`BedProvisioningCoordinator` 若通过 `HousingCoordinator.stageFullyBuilt(level, home, n)` 间接使用，则**无需改动**——它拿的是 `home`，风格由 `home` 决定。若有直接调用，补上风格参数。

- [ ] **Step 5: 编译并跑全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，12 项检查全部 `*Check passed`，无编译警告。

- [ ] **Step 6: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingBlueprints.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingSavedData.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java
git commit -m "Pick a house style per home and remember it"
```

（Task 2 与 Task 3 合为一次提交：Task 2 单独不编译，中间状态不应入库。）

---

### Task 4: 完整构建收尾与项目文档

**Files:**
- Modify: `goblin-settlement-plan/HOUSING_DESIGN.md`（§10「更多蓝图」那条改为已完成）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1–3 的全部改动与构建结果
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动（本仓库已发生过两次）**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：**先单独提交它们**，提交说明里写明来源（是哪条线、哪个 agent），**然后再**做本任务的文档改动。不要把自己的轮次记录与它们的改动混在一次提交里。

- [ ] **Step 2: 更新 HOUSING_DESIGN §10 的那一条**

把 `- **更多蓝图**：`Home.variant` 仍是朝向镜像，不是可选形制。` 改为说明已完成、并指向 `HOUSING_STYLE_DESIGN.md`。

- [ ] **Step 3: 追加 UpdateLog**

在末尾追加（时间换成实际操作时刻）：

```markdown
## [<开始> – <结束>] 第四十六轮：住宅风格（更多蓝图）

- [<时间>] 按 HOUSING_STYLE_DESIGN.md 与 HOUSING_STYLE_PLAN.md 执行。落实 HOUSING_DESIGN §10 的「更多蓝图」：数据文件引入风格维度（一套风格 = 一整套五级链）。
- [<时间>] **为什么是整栋风格而不是每级多套**：各级是累加的，每级多套会要求下级形制与上级几何逐级接缝，组合爆炸且无简单规则可保证；整栋风格天然自洽。
- [<时间>] Task 1：BlueprintSet 由「一条容量链 + 一条品质链」改为「一组 Style」；四条校验逐套执行，外层新增"至少一套、id 唯一、每套阶梯长度正确"。生成脚本改为产出三套风格，**cottage 的几何逐字保留**，脚本打印 `COTTAGE MUST BE [21, 71, 10]`。
- [<时间>] 新形制：lean_to（紧凑单坡，门开在 -z 侧）与 side_porch（带侧廊），几何由开发方起草，经校验器的入口洪水填充/坐标盒/级内去重/跨级不重投四条约束裁定；迭代过程见下方记录。
- [<时间>] Task 2/3：Home 新增可选字段 style（缺省 0 = cottage，旧档兼容）；HousingBlueprints 按风格解析缓存；HousingCoordinator 新增 preferredStyle（显式算式，不依赖 BlockPos.hashCode），注册时按「首选风格 → 其余按数据序」×「镜像 0/1/2」选第一个放得下的组合；nextSite/stepAt/stageFullyBuilt/siteSuitable 都从 home 取风格。**镜像机制一行未改。**
- [<时间>] 验证：./gradlew build --offline --no-daemon BUILD SUCCESSFUL，12 项独立检查全部 *Check passed；cottage 步数仍为 21/71/10（已建成房子零行为变化）。未启动游戏、未运行专用服务端、未触碰常用存档。
- [<时间>] 未完成：不构成玩法验收；**新形制的观感与可住性只有统一测试时才能判断**；风格下标越界被静默钳到 0，意味着**删掉某套风格会让存档里用它建成的房子静默变成 cottage 几何**，删风格是不安全操作（已写入设计 §9 与状态文档）；玩家自定义蓝图、数据包重载未做。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 第 3 条「住宅剩余」：把「更多蓝图」移出未做项，写明已完成与"删风格不安全"这条约束；保留其余项。
- 「本轮接入的内容」新增一节「第四十六轮：住宅风格」。
- 「本轮验证进展」替换为本轮构建与检查结果。
- 「阶段定位」阶段 6：把"更多蓝图仍缺"改为已接入三套风格。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the housing style round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。

（注意：这一步用 `git add goblin-settlement-plan/` 是因为 Step 1 已把并行改动先行提交，此处剩下的应只有本轮的文档。）

---

## 自查记录

**1. 规格覆盖**

- 设计 §2「整栋风格而不是每级多套」→ Task 1 Step 3a 的 `Style(id, capacity, quality)`。
- 设计 §3 数据模型与旧档兼容 → Task 1 Step 3a/3b、Task 2 Step 1。
- 设计 §4 校验（逐套四条 + 外层三条）→ Task 1 Step 3d 与 `checkEveryStyleIsBuildable`。
- 设计 §5 选择规则 → Task 3 Step 1/2。
- 设计 §6 接线 → Task 2 Step 2、Task 3 Step 3/4。
- 设计 §7 三套形制 → Task 1 Step 4（含 cottage 逐字保留与三套的落盒/入口/去重约束）。
- 设计 §8 验证方式 → Task 1 Step 1/5/6、Task 4 Step 3 的记录条目。
- 设计 §9 风险 → Task 4 Step 3/4 逐条对应（观感未验证、siteSuitable 成本、绑定半径、钳制掩盖越界）。

**2. 占位符扫描**

- 无 TBD/TODO/待填。生成脚本与数据模型的关键代码都给了可粘贴的全文；`...` 只出现在"与现有循环逐字相同"的注释处，且该处原文已在第 44 轮的代码里，指向明确。
- Task 2 Step 1c/1d 要求"以该文件实际的校验语句为准改"并给出 `grep` 命令——这是**条件性改动**（该文件的字段校验与 `new Home(` 构造点需按实际内容补齐参数），条件与判据都写明，不是待填。
- Task 1 Step 6 明说新形制几何可能需要迭代修正，并划定红线（不许放宽校验规则）。这是对"新起草的几何未必一次通过"的诚实交代，不是留空。

**3. 类型一致性**

- `BlueprintSet.steps(int styleIndex, int capacityTarget, int qualityTarget)` 与 `HousingBlueprints.steps(int styleIndex, int capacityTarget, int qualityTarget)` 参数顺序一致，Task 3 的三处调用（`nextSite`/`stepAt`/`siteSuitable`）都按此顺序传。
- `stages(int styleIndex)` 在两层的返回布局一致：容量级 0..2 在前、品质级在后，因此 `stageFullyBuilt(level, home, 1)` / `(…, 2)` 的既有语义不变。
- `Home` 的字段顺序在 Task 2 Step 1a 明确为 `(bed, variant, style, capacityTarget, qualityTarget, workerId)`，Task 3 Step 2 的 `new Home(bed, variant, style, 0, 0)` 与之对齐；所有 `with*` 方法必须把 `style` 原样传递，Task 2 Step 1d 已点名。
- `HousingCoordinator.preferredStyle` 是**包内可见**（无修饰符的 `static`），与 `stepAt` / `BlueprintSet.entranceOpen` 的既有可见性约定一致，便于检查直接调用。
- `HousingBlueprints.styleCount()` / `stages(int)` / `steps(int,int,int)` 与 `BlueprintSet.styleCount()` / `styles()` 的命名分工：外层用 `styleCount()`，纯层用 `styles().size()`。
