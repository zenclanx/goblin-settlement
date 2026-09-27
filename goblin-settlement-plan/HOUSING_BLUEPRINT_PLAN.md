# 住宅蓝图数据化（几何迁到受校验的数据文件）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把住宅五级几何（现为 `HousingRules.blueprints()` 中 121 步的循环硬编码）迁到受校验的 JSON 数据文件，令换形状不再需要改代码。

**Architecture:** 三层单向依赖。**纯层** `BlueprintSet` 持有数据模型、codec、四条校验与步骤合成，只依赖 `HousingRules.Step`；**MC 层** `HousingBlueprints` 在服务端启动时读 jar 内资源、按注册表解析标识、跑校验，把每步一次性解析成 `ResolvedStep(Block, Item)` 并缓存；**调用层** `HousingCoordinator` 改读 `HousingBlueprints`。`HousingRules` 不反向依赖 `BlueprintSet`，也不持有可变静态状态。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [HOUSING_BLUEPRINT_DESIGN.md](HOUSING_BLUEPRINT_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。禁止引入其他 Minecraft 版本的 API 或示例。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **不做游戏内验证**：按用户约定，初版代码全部完成后才统一测试。本轮只做编译 + 独立检查。
- **纯层不得依赖 Minecraft 类型**：`BlueprintSet` 只吃 `int`、`String`、record 与 `java.util` 集合（`com.mojang.serialization` 的 Codec 不算 Minecraft 世界类型，`HousingSavedData.Home` 是既有先例）。
- **本版本用 `net.minecraft.resources.Identifier`，不是 `ResourceLocation`**（工程既有源码已如此）。为避开字符串解析 API 的不确定性，**本计划不使用任何 `Identifier.parse`**：一律遍历注册表建 `id 字符串 → 对象` 映射（§Task 3 Step 1）。
- **工人路径不动**：`GoblinCitizenEntity` 中约 10 处硬编码橡木木板（458/723/796/798/808/1874/1899/1914/1933）本轮**一个都不改**。本轮的过渡约束（数据只许橡木木板）正是为了与此一致。
- **`HousingRulesCheck.checkSteps` 与 `checkFirstUnbuilt` 迁到 `BlueprintCheck`，断言值一字不改**（`21/92/102/111/121` 与 firstUnbuilt 的既有用例）。它们是几何搬迁无损的证据。
- 检查沿用项目既有写法：无 JUnit，`main` + `require`，成功打印 `XxxCheck passed`，注册进 `build.gradle` 的 `check` 聚合。
- 代码注释用英文（与现有源码一致）。只在违反直觉处写注释。
- 不修改 `ai-chat-mod`，不动常用存档。
- `goblin-settlement-plan/` 下可能有另一个 agent 未提交的改动：提交时只暂存你自己改的文件，不要 `git add -A` / `git add .`。`UpdateLog.md` **只许在末尾追加**。

---

### Task 1: 生成器脚本与数据文件

**Files:**
- Create: `goblin-settlement-mod/tools/generate_housing_blueprints.py`
- Create: `goblin-settlement-mod/src/main/resources/data/goblin_settlement/housing_blueprints.json`

**Interfaces:**
- Consumes: 无（脚本逐字复刻现有 `HousingRules.blueprints()` 的循环）
- Produces: 数据文件，供 Task 2 的 `BlueprintCheck` 与 Task 3 的加载器读取。JSON 形状：

```json
{
  "reserve_basic": 8,
  "reserve_expanded": 24,
  "capacity": [
    { "id": "shelter", "steps": [ { "x": -1, "y": 0, "z": -1, "block": "minecraft:oak_planks" }, ... ] },
    { "id": "cabin", "requires": "shelter", "steps": [ ... ] },
    { "id": "expanded", "requires": "cabin", "steps": [ ... ] }
  ],
  "quality": [
    { "id": "quality", "steps": [ ... ] },
    { "id": "mature", "requires": "quality", "steps": [ ... ] }
  ]
}
```

- [ ] **Step 1: 写生成器脚本**

创建 `goblin-settlement-mod/tools/generate_housing_blueprints.py`。五段循环必须与 `HousingRules.blueprints()` **逐字对应**，尤其 `cabin` 那处留门洞的 `x != 0 or y == 2`：

```python
#!/usr/bin/env python3
"""Emit the housing blueprint data file from the geometry that used to live in
HousingRules.blueprints(). Kept as a script so the 121 offsets are never hand-copied.

Run from the repo root:  python tools/generate_housing_blueprints.py
"""
import json
import os

BLOCK = "minecraft:oak_planks"
OUT = os.path.join("src", "main", "resources", "data", "goblin_settlement",
                   "housing_blueprints.json")


def shelter():
    steps = []
    for x in (-1, 1):
        for z in (-1, 1):
            for y in range(0, 3):
                steps.append((x, y, z))
    for x in range(-1, 2):
        for z in range(-1, 2):
            steps.append((x, 3, z))
    return steps


def cabin():
    steps = []
    for y in range(0, 3):
        for x in range(-2, 3):
            steps.append((x, y, -2))
            # Leaves the two-high doorway at (0, y, 2) for y < 2.
            if x != 0 or y == 2:
                steps.append((x, y, 2))
        for z in range(-1, 2):
            steps.append((-2, y, z))
            steps.append((2, y, z))
    for x in range(-2, 3):
        for z in range(-2, 3):
            steps.append((x, 3, z))
    return steps


def expanded():
    steps = []
    for z in range(-1, 2):
        steps.append((3, 0, z))
        steps.append((3, 3, z))
    for y in range(1, 3):
        steps.append((3, y, -1))
        steps.append((3, y, 1))
    return steps


def quality():
    steps = []
    for x in range(-2, 3):
        steps.append((x, 4, 0))
    for z in range(-2, 3):
        # The cross shares its centre block (0,4,0), already added above.
        if z != 0:
            steps.append((0, 4, z))
    return steps


def mature():
    steps = []
    for z in range(-2, 3):
        steps.append((-3, 0, z))
    for x in range(-2, 3):
        steps.append((x, 4, -2))
    return steps


def stage(stage_id, steps, requires=None):
    entry = {"id": stage_id}
    if requires is not None:
        entry["requires"] = requires
    entry["steps"] = [{"x": x, "y": y, "z": z, "block": BLOCK} for (x, y, z) in steps]
    return entry


def main():
    capacity = [stage("shelter", shelter()),
                stage("cabin", cabin(), "shelter"),
                stage("expanded", expanded(), "cabin")]
    quality_chain = [stage("quality", quality()),
                     stage("mature", mature(), "quality")]
    payload = {
        "reserve_basic": 8,
        "reserve_expanded": 24,
        "capacity": capacity,
        "quality": quality_chain,
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, indent=2)
        handle.write("\n")

    # The step counts the old HousingRulesCheck asserted. They are the proof that the
    # migration lost no geometry.
    sizes = [len(s["steps"]) for s in capacity]
    print("capacity sizes:", sizes)
    print("quality sizes:", [len(s["steps"]) for s in quality_chain])
    running = 0
    for size in sizes:
        running += size
        print("capacity cumulative:", running)
    total = running
    for entry in quality_chain:
        total += len(entry["steps"])
        print("with quality cumulative:", total)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 运行脚本并核对步数**

Run: `cd goblin-settlement-mod && python tools/generate_housing_blueprints.py`

Expected 输出（与 `HousingRulesCheck` 既有断言逐一对应，`quality` 链的累计值即 `capacity 全满 + 品质 i 级`）：

```
capacity sizes: [21, 71, 10]
quality sizes: [9, 10]
capacity cumulative: 21
capacity cumulative: 92
capacity cumulative: 102
with quality cumulative: 111
with quality cumulative: 121
```

若数字不符，**不要手改 JSON**——是脚本的循环与 `HousingRules.blueprints()` 不一致，回去逐行比对。

- [ ] **Step 3: 检查数据文件形态**

Run: `python -c "import json;d=json.load(open('src/main/resources/data/goblin_settlement/housing_blueprints.json',encoding='utf-8'));print(len(d['capacity']),len(d['quality']),d['reserve_basic'],d['reserve_expanded'],d['capacity'][1]['requires'],d['quality'][0].get('requires'))"`

Expected: `3 2 8 24 shelter None`

- [ ] **Step 4: 提交**

```bash
git add goblin-settlement-mod/tools/generate_housing_blueprints.py \
        "goblin-settlement-mod/src/main/resources/data/goblin_settlement/housing_blueprints.json"
git commit -m "Generate the housing blueprint data file"
```

---

### Task 2: 纯模型 `BlueprintSet` 与 `BlueprintCheck`

本任务**纯新增**，不动任何既有代码，因此完成时既有 11 项检查必须全部照旧通过。

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BlueprintSet.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/BlueprintCheck.java`
- Modify: `goblin-settlement-mod/build.gradle`（注册 `blueprintCheck` 任务并加进 `check` 聚合）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingRules.java`（**仅**给 `Step` 加 `String block` 字段）
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/HousingRulesCheck.java`（**仅**把 `checkSteps` 与 `checkFirstUnbuilt` 两个方法与它们的调用行删掉，迁移到 `BlueprintCheck`；断言值一字不改）

**Interfaces:**
- Consumes: `HousingRules.Step`、`HousingRules.MAX_CAPACITY_TARGET`、`HousingRules.MAX_QUALITY_TARGET`
- Produces:
  - `BlueprintSet.Stage(String id, String requires, List<HousingRules.Step> steps)`（嵌套 record；步骤类型**复用 `HousingRules.Step`**，不另造近乎相同的第二个类型）
  - `BlueprintSet(List<Stage> capacity, List<Stage> quality, int reserveBasic, int reserveExpanded)`
  - `BlueprintSet.CODEC -> Codec<BlueprintSet>`
  - `BlueprintSet.steps(int capacityTarget, int qualityTarget) -> List<HousingRules.Step>`
  - `BlueprintSet.stages() -> List<List<HousingRules.Step>>`（容量级在前、品质级在后，与旧 `HousingRules.stages()` 的索引布局一致）
  - `BlueprintSet.validate(Set<String> knownBlocks) -> List<String>`（返回全部问题，空表表示通过）
  - `BlueprintSet.entranceOpen(List<Stage> chain) -> boolean`（**包内可见**，供 `BlueprintCheck` 直接测这条规则）

- [ ] **Step 1: 决定 `Step` 的归属并只加一个字段**

设计文档 §6 要求 `HousingRules.Step` 带上方块标识。本轮**不新增第二个 step record**：直接把 `HousingRules.Step` 扩成四字段，`BlueprintSet` 复用它，避免两个近乎相同的类型在包内并存。

在 `HousingRules.java` 中把

```java
    public record Step(int x, int y, int z) {
    }
```

改成

```java
    public record Step(int x, int y, int z, String block) {
    }
```

**这一步会让主代码编译失败**（`blueprints()` 里的 `new Step(x, y, z)` 少一个参数、`HousingCoordinator` 消费 `step.y()` 的地方不受影响）。为避免一个任务内同时改签名与接线，**在 Step 1 里先只改 `HousingRules.blueprints()` 的构造点**，全部补上 `"minecraft:oak_planks"`：把 `HousingRules.java` 里 `blueprints()` 方法内所有 `new Step(` 形式的构造改为 `new Step(` 原三参 `, "minecraft:oak_planks")`。用一次替换完成：

Run: `cd goblin-settlement-mod && sed -i 's/new Step(\([^)]*\))/new Step(\1, "minecraft:oak_planks")/g' src/main/java/dev/local/goblinsettlement/housing/HousingRules.java`

然后 Run: `./gradlew compileJava --offline --no-daemon` → Expected: `BUILD SUCCESSFUL`。

（`blueprints()` 整个方法会在 Task 3 删除，这里补参数只是为了让 Task 2 独立可编译、可审查。）

- [ ] **Step 2: 写失败检查（RED）**

创建 `BlueprintCheck.java`。它先测 `BlueprintSet` 尚不存在的 API，因此必定编译失败——这就是本任务的 RED。

```java
package dev.local.goblinsettlement.housing;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BlueprintCheck {
    private static final String RESOURCE =
            "/data/goblin_settlement/housing_blueprints.json";

    public static void main(String[] args) throws Exception {
        BlueprintSet data = loadRealFile();
        checkRealFile(data);
        checkSteps(data);
        checkFirstUnbuilt(data);
        checkCodecRoundTrip();
        checkChainRules();
        checkEntrance();
        checkBounds();
        checkTransitionalBlockRule();
        System.out.println("BlueprintCheck passed");
    }

    /** The shipped data file must decode and validate; this is the "validated data file" point. */
    private static BlueprintSet loadRealFile() throws Exception {
        InputStream stream = BlueprintCheck.class.getResourceAsStream(RESOURCE);
        require(stream != null, "the blueprint resource is on the classpath");
        String text;
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            text = new String(reader.readAllBytes(), StandardCharsets.UTF_8);
        }
        return BlueprintSet.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .getOrThrow();
    }

    private static void checkRealFile(BlueprintSet data) {
        var problems = data.validate(Set.of("minecraft:oak_planks"));
        require(problems.isEmpty(), "the shipped blueprint file validates: " + problems);
    }

    private static void checkSteps(BlueprintSet data) {
        require(data.steps(0, 0).size() == 21, "shelter only");
        require(data.steps(1, 0).size() == 92, "shelter plus cabin");
        require(data.steps(2, 0).size() == 102, "all three capacity stages");
        require(data.steps(2, 1).size() == 111, "capacity plus the first quality stage");
        require(data.steps(2, 2).size() == 121, "everything");
        require(data.steps(1, 1).equals(data.steps(1, 1)),
                "the same targets always compose the same list");
        for (var step : data.steps(2, 2)) {
            require(step.x() >= -3 && step.x() <= 3 && step.z() >= -2 && step.z() <= 2
                    && step.y() >= 0 && step.y() <= 4, "every step stays inside the blueprint box");
            require(step.block().equals("minecraft:oak_planks"), "the transitional block rule holds");
        }
        boolean threw = false;
        try {
            data.steps(3, 0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        require(threw, "targets beyond the ladder are rejected");
    }

    private static void checkFirstUnbuilt(BlueprintSet data) {
        List<HousingRules.Step> shelter = data.steps(0, 0);
        require(HousingRules.firstUnbuilt(shelter, Set.of()).orElseThrow() == 0,
                "an untouched home starts at the first step");
        Set<HousingRules.Step> half = new HashSet<>(shelter.subList(0, 5));
        require(HousingRules.firstUnbuilt(shelter, half).orElseThrow() == 5,
                "built leading steps are skipped");
        require(HousingRules.firstUnbuilt(shelter, new HashSet<>(shelter)).isEmpty(),
                "a fully built home has nothing left");
        Set<HousingRules.Step> builtEarly = new HashSet<>(data.steps(1, 1));
        int next = HousingRules.firstUnbuilt(data.steps(2, 1), builtEarly).orElseThrow();
        require(next == 92, "raising the capacity target after quality work finds the inserted steps");
        require(data.steps(2, 1).get(next).equals(data.steps(2, 0).get(21 + 71)),
                "the step found is the first expanded-stage step");
    }

    private static void checkCodecRoundTrip() {
        var json = JsonParser.parseString("""
                {"reserve_basic": 8, "reserve_expanded": 24,
                 "capacity": [{"id": "a", "steps": [{"x": 1, "y": 0, "z": -1, "block": "minecraft:oak_planks"}]}],
                 "quality": [{"id": "b", "requires": "a", "steps": []}]}
                """);
        BlueprintSet parsed = BlueprintSet.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        require(parsed.capacity().size() == 1 && parsed.quality().size() == 1,
                "both chains decode");
        require(parsed.capacity().get(0).requires().isEmpty(), "an absent requires decodes to empty");
        require(parsed.quality().get(0).requires().equals("a"), "a present requires survives");
        var reencoded = BlueprintSet.CODEC
                .encodeStart(JsonOps.INSTANCE, parsed).getOrThrow().getAsJsonObject();
        BlueprintSet again = BlueprintSet.CODEC.parse(JsonOps.INSTANCE, reencoded).getOrThrow();
        require(again.equals(parsed), "a round trip is lossless");
    }

    private static void checkChainRules() {
        require(valid(List.of(cap("a", ""), cap("b", "a"), cap("c", "b"))).isEmpty(),
                "a linear chain is legal");
        require(valid(List.of(cap("a", "a"), cap("b", "a"), cap("c", "b"))).size() == 1,
                "a chain head that requires something is rejected");
        require(valid(List.of(cap("a", ""), cap("b", "c"), cap("c", "b"))).size() == 1,
                "a backwards reference is rejected (this is what keeps the chain acyclic)");
        require(valid(List.of(cap("a", ""), cap("b", "missing"), cap("c", "b"))).size() == 1,
                "a dangling reference is rejected");
        require(valid(List.of(cap("a", ""), cap("b", "a"), cap("b", "b"))).size() == 1,
                "a duplicate id is rejected");
        require(valid(List.of(cap("a", ""), cap("b", ""), cap("c", "b"))).size() == 1,
                "a second chain head is rejected");
    }

    private static void checkEntrance() {
        // A walled ring with no gap seals the anchor: every orthogonal neighbour is blocked.
        require(!BlueprintSet.entranceOpen(List.of(stageOf(
                step(1, 0, 0), step(1, 1, 0), step(-1, 0, 0), step(-1, 1, 0),
                step(0, 0, 1), step(0, 1, 1), step(0, 0, -1), step(0, 1, -1)))),
                "a sealed ring has no entrance");

        // The same ring with one column left open is enterable.
        require(BlueprintSet.entranceOpen(List.of(stageOf(
                step(1, 0, 0), step(1, 1, 0), step(-1, 0, 0), step(-1, 1, 0),
                step(0, 0, 1), step(0, 1, 1)))),
                "a two-block-high doorway makes the ring enterable");

        // A column blocked only at y=1 still seals it: a doorway has to be two blocks high.
        require(!BlueprintSet.entranceOpen(List.of(stageOf(
                step(1, 0, 0), step(1, 1, 0), step(-1, 0, 0), step(-1, 1, 0),
                step(0, 0, -1), step(0, 1, -1), step(0, 1, 1)))),
                "a column blocked only at y=1 still seals the ring");

        // Nothing at ground level at all is trivially enterable (the shelter stage).
        require(BlueprintSet.entranceOpen(List.of(stageOf(step(1, 3, 1)))),
                "a wall-less stage is enterable");
    }

    private static void checkBounds() {
        require(problemsWithStage(step(4, 0, 0)) == 1, "x beyond the box is rejected");
        require(problemsWithStage(step(0, 5, 0)) == 1, "y beyond the box is rejected");
        require(problemsWithStage(step(0, -1, 0)) == 1, "y below the box is rejected");
        require(problemsWithStage(step(0, 0, 3)) == 1, "z beyond the box is rejected");
        require(problemsWithStage(step(1, 0, 1), step(1, 0, 1)) == 1,
                "a duplicated coordinate inside one stage is rejected");
        require(reserveProblem(0, 24) == 1, "a non-positive reserve_basic is rejected");
        require(reserveProblem(8, 0) == 1, "a non-positive reserve_expanded is rejected");
        require(reserveProblem(8, 24) == 0, "positive reserves pass");
    }

    private static void checkTransitionalBlockRule() {
        var stone = new HousingRules.Step(0, 3, 0, "minecraft:stone");
        var capacity = List.of(new BlueprintSet.Stage("a", "", List.of(stone)),
                cap("b", "a"), cap("c", "b"));
        var quality = List.of(cap("q0", ""), cap("q1", "q0"));
        var problems = new BlueprintSet(capacity, quality, 8, 24).validate(KNOWN);
        require(problems.size() == 1,
                "a known block that is not oak planks is rejected by the transitional rule: "
                        + problems);

        var unknown = new HousingRules.Step(0, 3, 0, "minecraft:not_a_block");
        var otherCapacity = List.of(new BlueprintSet.Stage("a", "", List.of(unknown)),
                cap("b", "a"), cap("c", "b"));
        require(new BlueprintSet(otherCapacity, quality, 8, 24).validate(KNOWN).size() == 1,
                "an id outside the known set is rejected");
    }

    private static final Set<String> KNOWN = Set.of("minecraft:oak_planks");

    private static BlueprintSet.Stage cap(String id, String requires) {
        return new BlueprintSet.Stage(id, requires, List.of(step(0, 3, 0)));
    }

    private static BlueprintSet.Stage stageOf(HousingRules.Step... steps) {
        return new BlueprintSet.Stage("probe", "", List.of(steps));
    }

    private static HousingRules.Step step(int x, int y, int z) {
        return new HousingRules.Step(x, y, z, "minecraft:oak_planks");
    }

    private static List<BlueprintSet.Stage> qualities() {
        return List.of(cap("q0", ""), cap("q1", "q0"));
    }

    /** Puts a hand-made capacity chain into an otherwise valid set and returns its problems. */
    private static List<String> valid(List<BlueprintSet.Stage> capacity) {
        return new BlueprintSet(capacity, qualities(), 8, 24).validate(KNOWN);
    }

    /** Problem count for a set whose first capacity stage carries exactly the given steps. */
    private static int problemsWithStage(HousingRules.Step... steps) {
        var capacity = List.of(new BlueprintSet.Stage("a", "", List.of(steps)),
                cap("b", "a"), cap("c", "b"));
        return new BlueprintSet(capacity, qualities(), 8, 24).validate(KNOWN).size();
    }

    private static int reserveProblem(int basic, int expanded) {
        var capacity = List.of(cap("a", ""), cap("b", "a"), cap("c", "b"));
        return new BlueprintSet(capacity, qualities(), basic, expanded).validate(KNOWN).size();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

> **关于 `entranceOpen` 的可见性**：上面直接调用 `BlueprintSet.entranceOpen(...)`，因此 Task 2 Step 4 里该方法要声明为**包内可见**（去掉 `private`），而**不是** `private`。这是刻意的：直接测规则本身比断言日志文案更精确，也不会因文案改动而失效。
>
> **关于阶梯长度**：`cap(...)` 造出的阶段数与 `qualities()` 的两级，使 `valid` / `problemsWithStage` / `reserveProblem` 造出的集合**阶梯长度始终合法**，因此断言到的"1 个问题"确实来自被测规则，而不是长度不符的噪音。唯一例外是 `checkTransitionalBlockRule` 那两处，它们同样用了合法的 3 + 2 结构。

- [ ] **Step 3: 运行检查，确认按预期失败**

Run: `./gradlew blueprintCheck --offline --no-daemon`

（此时 `build.gradle` 还没注册该任务，先直接编译测试源验证失败原因。）

Run: `./gradlew compileTestJava --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错为 `找不到符号: 类 BlueprintSet` / `程序包 dev.local.goblinsettlement.housing 中找不到类 BlueprintSet`。失败原因必须是"类型不存在"。

- [ ] **Step 4: 实现 `BlueprintSet`（GREEN）**

创建 `BlueprintSet.java`：

```java
package dev.local.goblinsettlement.housing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure blueprint data: geometry, upgrade chain and the rules that keep it honest. */
public record BlueprintSet(List<Stage> capacity, List<Stage> quality,
                           int reserveBasic, int reserveExpanded) {
    /** The blueprint box every coordinate must stay inside. */
    public static final int MIN_X = -3;
    public static final int MAX_X = 3;
    public static final int MIN_Y = 0;
    public static final int MAX_Y = 4;
    public static final int MIN_Z = -2;
    public static final int MAX_Z = 2;

    /** The only block the data may name until the worker path learns other materials. */
    public static final String TRANSITIONAL_BLOCK = "minecraft:oak_planks";

    public record Stage(String id, String requires, List<HousingRules.Step> steps) {
    }

    public static final Codec<HousingRules.Step> STEP_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("x").forGetter(HousingRules.Step::x),
                    Codec.INT.fieldOf("y").forGetter(HousingRules.Step::y),
                    Codec.INT.fieldOf("z").forGetter(HousingRules.Step::z),
                    Codec.STRING.fieldOf("block").forGetter(HousingRules.Step::block)
            ).apply(instance, HousingRules.Step::new));

    public static final Codec<Stage> STAGE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Stage::id),
            Codec.STRING.optionalFieldOf("requires", "").forGetter(Stage::requires),
            STEP_CODEC.listOf().fieldOf("steps").forGetter(Stage::steps)
    ).apply(instance, Stage::new));

    public static final Codec<BlueprintSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            STAGE_CODEC.listOf().fieldOf("capacity").forGetter(BlueprintSet::capacity),
            STAGE_CODEC.listOf().fieldOf("quality").forGetter(BlueprintSet::quality),
            Codec.INT.fieldOf("reserve_basic").forGetter(BlueprintSet::reserveBasic),
            Codec.INT.fieldOf("reserve_expanded").forGetter(BlueprintSet::reserveExpanded)
    ).apply(instance, BlueprintSet::new));

    /** The capacity stages first, then the quality stages, exactly as the two-axis design orders them. */
    public List<HousingRules.Step> steps(int capacityTarget, int qualityTarget) {
        if (capacityTarget < 0 || capacityTarget >= capacity.size()
                || qualityTarget < 0 || qualityTarget > quality.size()) {
            throw new IllegalArgumentException("Blueprint targets out of range");
        }
        List<HousingRules.Step> result = new ArrayList<>();
        for (int index = 0; index <= capacityTarget; index++) {
            append(result, capacity.get(index));
        }
        for (int index = 1; index <= qualityTarget; index++) {
            append(result, quality.get(index - 1));
        }
        return List.copyOf(result);
    }

    /** Every stage in the order the coordinator indexes them: capacity 0..n, then quality 0..m. */
    public List<List<HousingRules.Step>> stages() {
        List<List<HousingRules.Step>> result = new ArrayList<>();
        for (Stage stage : capacity) {
            List<HousingRules.Step> branch = new ArrayList<>();
            append(branch, stage);
            result.add(List.copyOf(branch));
        }
        for (Stage stage : quality) {
            List<HousingRules.Step> branch = new ArrayList<>();
            append(branch, stage);
            result.add(List.copyOf(branch));
        }
        return List.copyOf(result);
    }

    private static void append(List<HousingRules.Step> target, Stage stage) {
        target.addAll(stage.steps());
    }

    /** All problems found, empty when the data is sound. */
    public List<String> validate(Set<String> knownBlocks) {
        List<String> problems = new ArrayList<>();
        if (capacity.size() != HousingRules.MAX_CAPACITY_TARGET + 1) {
            problems.add("capacity needs " + (HousingRules.MAX_CAPACITY_TARGET + 1)
                    + " stages, found " + capacity.size());
        }
        if (quality.size() != HousingRules.MAX_QUALITY_TARGET) {
            problems.add("quality needs " + HousingRules.MAX_QUALITY_TARGET
                    + " stages, found " + quality.size());
        }
        if (reserveBasic <= 0) {
            problems.add("reserve_basic must be positive");
        }
        if (reserveExpanded <= 0) {
            problems.add("reserve_expanded must be positive");
        }
        validateChain("capacity", capacity, knownBlocks, problems);
        validateChain("quality", quality, knownBlocks, problems);
        return List.copyOf(problems);
    }

    private static void validateChain(String name, List<Stage> chain, Set<String> knownBlocks,
                                      List<String> problems) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < chain.size(); index++) {
            Stage stage = chain.get(index);
            if (!seen.add(stage.id())) {
                problems.add(name + ": duplicate stage id " + stage.id());
            }
            if (index == 0) {
                if (!stage.requires().isEmpty()) {
                    problems.add(name + ": the first stage must not require anything");
                }
            } else if (stage.requires().isEmpty()) {
                problems.add(name + ": " + stage.id() + " is a second chain head");
            } else if (!chain.subList(0, index).stream()
                    .anyMatch(earlier -> earlier.id().equals(stage.requires()))) {
                // Referring strictly backwards is what makes the chain acyclic by construction.
                problems.add(name + ": " + stage.id() + " requires " + stage.requires()
                        + ", which is not an earlier stage of the same chain");
            }
            validateSteps(name, stage, knownBlocks, problems);
        }
        if (!entranceOpen(chain)) {
            problems.add(name + ": the chain seals the anchor with no legal entrance");
        }
    }

    private static void validateSteps(String name, Stage stage, Set<String> knownBlocks,
                                      List<String> problems) {
        Set<Long> coordinates = new HashSet<>();
        for (HousingRules.Step step : stage.steps()) {
            if (step.x() < MIN_X || step.x() > MAX_X || step.y() < MIN_Y || step.y() > MAX_Y
                    || step.z() < MIN_Z || step.z() > MAX_Z) {
                problems.add(name + "/" + stage.id() + ": step outside the blueprint box at "
                        + step.x() + "," + step.y() + "," + step.z());
            }
            if (!coordinates.add(key(step.x(), step.z(), step.y()))) {
                problems.add(name + "/" + stage.id() + ": duplicated step at "
                        + step.x() + "," + step.y() + "," + step.z());
            }
            if (!knownBlocks.contains(step.block())) {
                problems.add(name + "/" + stage.id() + ": unknown block " + step.block());
            } else if (!TRANSITIONAL_BLOCK.equals(step.block())) {
                problems.add(name + "/" + stage.id() + ": " + step.block()
                        + " is not yet supported -- until the worker path carries other materials,"
                        + " the data may only use " + TRANSITIONAL_BLOCK);
            }
        }
    }

    /**
     * A chain is enterable when the anchor column is reachable from outside the footprint, walking
     * only through columns that are free at both y=0 and y=1. A two-block-high opening anywhere in
     * the walls therefore keeps the blueprint legal, while a sealed ring is rejected. The walk runs
     * on the cumulative shape: a house grows stage by stage and only the total encloses anything.
     */
    static boolean entranceOpen(List<Stage> chain) {
        Set<Long> blocked = new HashSet<>();
        int minX = 0;
        int maxX = 0;
        int minZ = 0;
        int maxZ = 0;
        for (Stage stage : chain) {
            for (HousingRules.Step step : stage.steps()) {
                minX = Math.min(minX, step.x());
                maxX = Math.max(maxX, step.x());
                minZ = Math.min(minZ, step.z());
                maxZ = Math.max(maxZ, step.z());
                if (step.y() == 0 || step.y() == 1) {
                    blocked.add(key(step.x(), step.z(), 0));
                }
            }
        }
        minX--;
        maxX++;
        minZ--;
        maxZ++;
        Set<Long> reached = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = minX; x <= maxX; x++) {
            seed(queue, reached, blocked, x, minZ);
            seed(queue, reached, blocked, x, maxZ);
        }
        for (int z = minZ; z <= maxZ; z++) {
            seed(queue, reached, blocked, minX, z);
            seed(queue, reached, blocked, maxX, z);
        }
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            for (int[] delta : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                seed(queue, reached, blocked, cell[0] + delta[0], cell[1] + delta[1]);
            }
        }
        return reached.contains(key(0, 0, 0));
    }

    private static void seed(ArrayDeque<int[]> queue, Set<Long> reached, Set<Long> blocked,
                             int x, int z) {
        if (x < MIN_X - 1 || x > MAX_X + 1 || z < MIN_Z - 1 || z > MAX_Z + 1) {
            return;
        }
        long cell = key(x, z, 0);
        if (blocked.contains(cell) || !reached.add(cell)) {
            return;
        }
        queue.add(new int[] {x, z});
    }

    private static long key(int x, int z, int y) {
        return ((long) (x + 64) << 32) ^ ((long) (z + 64) << 16) ^ (y + 64);
    }
}
```

- [ ] **Step 5: 运行检查，确认转绿**

Run: `./gradlew compileTestJava --offline --no-daemon`，然后手工跑检查类。

先注册任务（这一步同时属于本任务，见 Step 6），再 Run: `./gradlew blueprintCheck --offline --no-daemon`

Expected: `BlueprintCheck passed`。

- [ ] **Step 6: 注册检查任务**

在 `build.gradle` 的 `housingRulesCheck` 任务块之后加：

```groovy
tasks.register('blueprintCheck', JavaExec) {
    group = 'verification'
    description = 'Checks the housing blueprint data model, its rules and the shipped data file.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.housing.BlueprintCheck'
}
```

并在末尾的 `tasks.named('check')` 块里加一行：

```groovy
    dependsOn tasks.named('blueprintCheck')
```

- [ ] **Step 7: 把 `checkSteps` 与 `checkFirstUnbuilt` 从 `HousingRulesCheck` 删除**

`HousingRulesCheck.java` 中：删掉 `main` 里的 `checkSteps();` 与 `checkFirstUnbuilt();` 两行调用，删掉这两个方法的完整定义。**其余方法（`checkDecide`/`checkCounts`/`checkUsableBedHead`/`checkBedsNear`/`checkHomeCodec`）一概不动。** 被删掉的断言已一字不改地出现在 `BlueprintCheck.checkSteps` / `checkFirstUnbuilt` 中。

同时删掉 `HousingRulesCheck` 里因这两个方法而变得未使用的 import（`java.util.HashSet`，以及若 `List`/`Set` 不再被使用则一并删除）。判断方法：Run `./gradlew compileTestJava --offline --no-daemon`，若报"未使用的导入"没有警告就按实际引用删。

- [ ] **Step 8: 跑完整构建与全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，12 项检查全部 `*Check passed`：Blueprint、HousingRules、PlotCoordinates、PopulationRules、ProfessionRules、ProtectedRectangle、SettlementDemand、SettlementSavedData、TrafficDecision、TrafficTargetRules、TransportSavedData、WorkerAssignmentRules。

- [ ] **Step 9: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/BlueprintSet.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingRules.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/BlueprintCheck.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/HousingRulesCheck.java \
        goblin-settlement-mod/build.gradle
git commit -m "Add the validated housing blueprint model"
```

---

### Task 3: 加载器与接线

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingBlueprints.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingRules.java`（删除 `blueprints()`、`STAGES`、`stages()`、`RESERVE_BASIC`、`RESERVE_EXPANDED`）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java`（4 处）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`（注册服务端启动钩子）
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/BlueprintCheck.java`（若 Step 2 用了临时桩函数，这里把它们换成真实构造）

**Interfaces:**
- Consumes: Task 2 的 `BlueprintSet`、`BlueprintSet.CODEC`、`BlueprintSet.validate`、`BlueprintSet.steps`、`BlueprintSet.stages`、`BlueprintSet.reserveBasic()`、`BlueprintSet.reserveExpanded()`
- Produces:
  - `HousingBlueprints.ResolvedStep(int x, int y, int z, Block block, Item item)`
  - `HousingBlueprints.load()` — 读资源、解码、校验、解析
  - `HousingBlueprints.available() -> boolean`
  - `HousingBlueprints.steps(int capacityTarget, int qualityTarget) -> List<ResolvedStep>`
  - `HousingBlueprints.stages() -> List<List<ResolvedStep>>`
  - `HousingBlueprints.reserveBasic() / reserveExpanded() -> int`

- [ ] **Step 1: 写加载器**

创建 `HousingBlueprints.java`。要点：**不使用任何 `Identifier.parse`**，改为遍历注册表建 `id 字符串 → 对象` 映射，从而不依赖这个版本的字符串解析 API 名。

```java
package dev.local.goblinsettlement.housing;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * Loads the housing blueprint data file once, on server start, and resolves every block id against
 * the registries. When the file is missing or invalid the settlement builds no houses at all: the
 * hardcoded geometry it replaced is gone, so falling back would only hide the problem.
 */
public final class HousingBlueprints {
    private static final String RESOURCE =
            "/data/goblin_settlement/housing_blueprints.json";

    public record ResolvedStep(int x, int y, int z, Block block, Item item) {
    }

    private static List<List<ResolvedStep>> stages = List.of();
    private static int reserveBasic = HousingRules.RESERVE_FALLBACK_BASIC;
    private static int reserveExpanded = HousingRules.RESERVE_FALLBACK_EXPANDED;
    private static boolean available;

    private HousingBlueprints() {
    }

    public static boolean available() {
        return available;
    }

    public static List<List<ResolvedStep>> stages() {
        return stages;
    }

    public static int reserveBasic() {
        return reserveBasic;
    }

    public static int reserveExpanded() {
        return reserveExpanded;
    }

    /** Capacity stages first, then quality stages, exactly as the two-axis design orders them. */
    public static List<ResolvedStep> steps(int capacityTarget, int qualityTarget) {
        List<ResolvedStep> result = new ArrayList<>();
        for (int index = 0; index <= capacityTarget; index++) {
            result.addAll(stages.get(index));
        }
        for (int index = 1; index <= qualityTarget; index++) {
            result.addAll(stages.get(HousingRules.MAX_CAPACITY_TARGET + index));
        }
        return List.copyOf(result);
    }

    public static void load() {
        available = false;
        try {
            BlueprintSet data = decode();
            if (data == null) {
                return;
            }
            Map<String, Block> blocks = new HashMap<>();
            BuiltInRegistries.BLOCK.forEach(block ->
                    blocks.put(BuiltInRegistries.BLOCK.getKey(block).toString(), block));
            Set<String> knownBlocks = Set.copyOf(blocks.keySet());
            List<String> problems = data.validate(knownBlocks);
            if (!problems.isEmpty()) {
                GoblinSettlement.LOGGER.error(
                        "Housing blueprints are invalid, housing construction is stopped: {}",
                        problems);
                return;
            }
            List<List<ResolvedStep>> resolved = new ArrayList<>();
            for (List<HousingRules.Step> branch : data.stages()) {
                List<ResolvedStep> converted = new ArrayList<>();
                for (HousingRules.Step step : branch) {
                    Block block = blocks.get(step.block());
                    Item item = block.asItem();
                    if (item == net.minecraft.world.item.Items.AIR) {
                        GoblinSettlement.LOGGER.error(
                                "Housing blueprint block {} has no item form, housing construction"
                                        + " is stopped", step.block());
                        return;
                    }
                    converted.add(new ResolvedStep(step.x(), step.y(), step.z(), block, item));
                }
                resolved.add(List.copyOf(converted));
            }
            stages = List.copyOf(resolved);
            reserveBasic = data.reserveBasic();
            reserveExpanded = data.reserveExpanded();
            available = true;
        } catch (RuntimeException failure) {
            GoblinSettlement.LOGGER.error(
                    "Housing blueprints failed to load, housing construction is stopped", failure);
        }
    }

    private static BlueprintSet decode() {
        try (InputStream stream = HousingBlueprints.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                GoblinSettlement.LOGGER.error(
                        "Housing blueprint resource {} is missing, housing construction is stopped",
                        RESOURCE);
                return null;
            }
            String text;
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                text = new String(reader.readAllBytes(), StandardCharsets.UTF_8);
            }
            return BlueprintSet.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                    .getOrThrow();
        } catch (java.io.IOException failure) {
            GoblinSettlement.LOGGER.error(
                    "Housing blueprint resource could not be read, housing construction is stopped",
                    failure);
            return null;
        }
    }
}
```

- [ ] **Step 2: 在 `HousingRules` 里补两个兜底常量并删除硬编码几何**

`HousingBlueprints` 在加载成功前需要一组数值兜底（此时 `available()` 为假，住宅建造本来就不进行，数值只是占位）。把 `HousingRules` 的

```java
    public static final int RESERVE_EXPANDED = 24;
    public static final int RESERVE_BASIC = 8;
```

改成

```java
    /** Used only before the blueprint file loads; construction does not run while it has not. */
    public static final int RESERVE_FALLBACK_BASIC = 8;
    public static final int RESERVE_FALLBACK_EXPANDED = 24;
```

然后从 `HousingRules.java` 中**删除**：`STAGES` 字段、`stages()` 方法、`blueprints()` 方法。删除后 Run `./gradlew compileJava --offline --no-daemon` 应报出 `HousingCoordinator` 中引用 `HousingRules.stages()` 的两处错误——这正是 Step 3 要修的。

- [ ] **Step 3: 接线 `HousingCoordinator` 的四处**

3a. `nextSite` 改为读解析后的方块：

```java
    /** First step whose site is not yet the declared block; null when fully built or the bed lost its facing. */
    private static BlockPos nextSite(ServerLevel level, HousingSavedData.Home home) {
        for (HousingBlueprints.ResolvedStep step
                : HousingBlueprints.steps(home.capacityTarget(), home.qualityTarget())) {
            BlockPos candidate = position(level, home.bed(), home.variant(), step);
            if (candidate != null && level.getBlockState(candidate).is(step.block())) {
                continue;
            }
            return candidate;
        }
        return null;
    }
```

3b. `stageFullyBuilt`：

```java
    static boolean stageFullyBuilt(ServerLevel level, HousingSavedData.Home home, int stageIndex) {
        for (HousingBlueprints.ResolvedStep step : HousingBlueprints.stages().get(stageIndex)) {
            BlockPos site = position(level, home.bed(), home.variant(), step);
            if (site == null || !level.getBlockState(site).is(step.block())) {
                return false;
            }
        }
        return true;
    }
```

3c. `siteSuitable` 里遍历全部阶段的循环：

```java
        for (var stage : HousingBlueprints.stages()) for (var step : stage) {
            BlockPos site = position(level, bed, variant, step);
            if (site == null || !permitted(level, id, site)) return false;
            var state = level.getBlockState(site);
            if (!state.isAir() && !state.is(step.block())) return false;
            if (step.y() == 0) {
                BlockPos ground = site.below();
                if (!permitted(level, id, ground)
                        || !level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) return false;
            }
        }
```

3d. `position` 的签名改成收 `ResolvedStep`（它只用到三个坐标，但改为同一类型可省掉两次转换）：

```java
    static BlockPos position(ServerLevel level, BlockPos bed, int variant,
                            HousingBlueprints.ResolvedStep step) {
```

3e. 领料闸的 reserve 常量：

```java
        int reserve = home.capacityTarget() >= 2
                ? HousingBlueprints.reserveExpanded() : HousingBlueprints.reserveBasic();
```

- [ ] **Step 4: 让住宅建造在蓝图不可用时停摆**

在 `HousingCoordinator.tick` 的开头，紧跟 `INTERVAL_TICKS` 判断之后加：

```java
        if (!HousingBlueprints.available()) return;
```

- [ ] **Step 5: 注册服务端启动钩子**

在 `GoblinSettlement.onInitialize()` 末尾加：

```java
        ServerLifecycleEvents.SERVER_STARTING.register(server -> HousingBlueprints.load());
```

并加 import：

```java
import dev.local.goblinsettlement.housing.HousingBlueprints;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
```

（`GoblinSettlement.java` 第 37 行已有 `housing.BedCensus` 的 import，照它的位置排。）

- [ ] **Step 6: 编译并跑全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，12 项检查全部 `*Check passed`。若有编译错误，优先核对：`ServerLifecycleEvents` 的包名是否如注释所述（用 `find` 在 Fabric API 的 jar 里确认）；`BuiltInRegistries.BLOCK.forEach` 与 `getKey` 的可用性（工程既有 `ModEntities` 用了 `Registry.register(BuiltInRegistries.ENTITY_TYPE, ...)`，同一注册表机制）。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingBlueprints.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingRules.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/housing/HousingCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/housing/BlueprintCheck.java
git commit -m "Read housing geometry from the blueprint data file"
```

---

### Task 4: 完整构建收尾与项目文档

**Files:**
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1–3 的全部改动与构建结果
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 确认工作区状态**

Run: `git -C .. status --short`

Expected: 只有你自己改的文件。**若出现 `goblin-settlement-plan/` 下你没改过的文件（另一个 agent 的并行改动），只暂存你自己写的，不要动它们的。**

- [ ] **Step 2: 追加 UpdateLog**

在末尾追加（时间换成实际操作时刻，格式 `YYYY-MM-DD HH:MM:SS +08:00`）：

```markdown
## [<开始> – <结束>] 第四十四轮：住宅蓝图数据化（几何迁到受校验的数据文件）

- [<时间>] 按 HOUSING_BLUEPRINT_DESIGN.md 执行。范围切分为两轮：本轮只做几何，材料泛化留下一轮——因为工人取料/交付/放置路径（GoblinCitizenEntity 约 10 处）硬编码橡木木板，且该路径从未在游戏内验证过，不宜与本轮混在一起改。
- [<时间>] 新增 tools/generate_housing_blueprints.py，逐字复刻 HousingRules.blueprints() 的循环输出数据文件，避免手抄 121 个偏移；生成后核对外层五级步数 21/71/10 与品质 9/10，累计 21/92/102/111/121，与 HousingRulesCheck 既有断言一致。
- [<时间>] 新增纯层 BlueprintSet（数据模型 + codec + 四类校验：资源标识存在、升级链无环、蓝图有合法入口、数值不越界），以及过渡约束"本轮数据只许橡木木板"。入口校验用累计形状上的 2D 洪水填充：y∈{0,1} 有步骤的格视为阻挡，锚点列不可达即判封死。新增 BlueprintCheck 并注册进 check 聚合；checkSteps 与 checkFirstUnbuilt 原样迁入（断言值未改）。
- [<时间>] 新增 MC 层 HousingBlueprints，服务端启动时读一次、解码、校验、把每步解析成 ResolvedStep(Block, Item) 缓存；失败则记错误日志并停用住宅建造（不崩服、不回退硬编码）。HousingRules 的 blueprints()/STAGES/stages()/RESERVE_* 删除，HousingCoordinator 四处硬编码 OAK_PLANKS 改读数据。
- [<时间>] 验证：./gradlew build --offline --no-daemon BUILD SUCCESSFUL，12 项独立检查全部 *Check passed。未启动游戏、未运行专用服务端、未触碰常用存档。
- [<时间>] 未完成：不构成玩法验收；材料的泛化（含删除过渡约束）、其余内容数据化（傀儡参数、职业倾向、名字、对话）、数据包重载均未做；HousingRules.firstUnbuilt 仍是只在检查里被使用的冗余；蓝图数据若与已建成房子的实际方块不符，本轮不做迁移。
```

- [ ] **Step 3: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 第 3 条「住宅剩余」：把已完成项加上本轮（几何数据化，材料泛化列为下一轮第一优先），并写明过渡约束存在、解除条件是什么。
- 「本轮接入的内容」新增一节「第四十四轮：住宅蓝图数据化」。
- 「本轮验证进展」替换为本轮构建与检查结果（含 12 项检查名与 jar 字节数）。

- [ ] **Step 4: 提交并推送**

```bash
git add goblin-settlement-plan/UpdateLog.md goblin-settlement-plan/CURRENT_STATUS.md
git commit -m "Record the housing blueprint migration round"
git push origin main
```

Expected: 推送成功（本机 `http.proxy=http://127.0.0.1:7890` 已配置）。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 数据模型 → Task 2 Step 4（`BlueprintSet` 及三个 record 与 `CODEC`）。
- 设计 §3 数据文件与脚本 → Task 1。
- 设计 §4.1 资源标识存在 → `validate(knownBlocks)` + `validateSteps` 的 `knownBlocks.contains`；"物品形态存在"落在加载器 `block.asItem() == Items.AIR` 判定（MC 层，因需注册表）。
- 设计 §4.2 升级链无环 → `validateChain` 的"必须指向同链更早一级"规则（严格向后引用即 DAG，无环由构造保证），外加链首唯一、id 唯一、悬空引用。
- 设计 §4.3 合法入口 → `entranceOpen` 的累计形状 2D 洪水填充。
- 设计 §4.4 数值不越界 → 阶梯长度、坐标盒、级内坐标去重、reserve 为正。
- 设计 §4.5 过渡约束 → `validateSteps` 里 `TRANSITIONAL_BLOCK` 分支，附明确文案。
- 设计 §5 加载与失败策略 → Task 3 Step 1/4/5（启动读一次、`available()`、日志、停摆）。
- 设计 §6 接线表 → Task 3 Step 2/3（含 reserve 与 position 签名）。
- 设计 §7 风险（进度基准、firstUnbuilt 死代码、占地盒近似、过渡约束、失败停摆）→ 分别落在 Task 4 Step 2 的记录与 Task 1 Step 2 的核对步骤；`firstUnbuilt` 按设计**不清理**。
- 设计 §9 验证方式 → Task 2 的 `BlueprintCheck`，含"直接读真实数据文件跑完整校验"这一条。

**2. 占位符扫描**

- 计划中**没有** TBD / TODO / "待填" / "稍后处理"。每个步骤都是可直接粘贴的最终代码或可直接执行的命令，测试文件里也不再有桩函数。
- Task 3 Step 6 提到两处需按编译结果核对的 API（`ServerLifecycleEvents` 的包名、`BuiltInRegistries` 的遍历方式），并给了核对方法。这两处**无法离线确证**（本机 `Identifier` 类未出现在可查的映射 jar 中），属于明示的已知不确定点；计划已通过"遍历注册表建映射、不用 `Identifier.parse`"的写法把最大的一处不确定性消掉。
- 检查里 `require` 的消息文本会被打印，但不被断言依赖：`entranceOpen` 以包内可见的纯方法直接受测，而不是靠匹配日志文案。

**3. 类型一致性**

- `HousingRules.Step(int,int,int,String)`（Task 2 Step 1）在 Task 2 的 `BlueprintSet.append`、`steps`、`stages` 与 Task 3 的 `HousingBlueprints.steps/stages` 中一致使用四字段。
- **只有一个步骤类型**：`HousingRules.Step(int,int,int,String)`。`BlueprintSet` 的 codec 与 `Stage` 直接用它，**不定义** `BlueprintSet.Step`；检查用例里的 `step(...)` 助手也返回它。任何地方出现 `new BlueprintSet.Step` 都是错的。
- `HousingBlueprints.stages()` 的索引布局与旧 `HousingRules.stages()` 一致（容量 0..2 在前、品质在后），因此 `stageFullyBuilt(level, home, 1)` / `(…, 2)` 两个既有调用点的语义不变。
- `steps(capacityTarget, qualityTarget)` 的品质索引用 `MAX_CAPACITY_TARGET + index` 映射到 `stages` 的下标，与旧 `STAGES.get(MAX_CAPACITY_TARGET + quality)` 等价。
- `position` 改收 `ResolvedStep` 后，`nextSite`/`stageFullyBuilt`/`siteSuitable` 三处传入的都是 `ResolvedStep`，无遗漏。
