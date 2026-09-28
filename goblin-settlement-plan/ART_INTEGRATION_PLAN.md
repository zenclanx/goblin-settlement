# 美术接入 Implementation Plan（第一轮：成年男女）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把美术交付的成年男、女两套模型接进游戏：`.bbmodel` 生成可编译的模型类，客户端按居民的性别选模型与贴图，游戏内尺寸与美术规格一致。

**Architecture:** 一个 Python 生成器把 Blockbench 工程翻成 Minecraft 的 `CubeListBuilder`/`PartPose` 调用（变换规则已用美术自己的 Java 候选逐条验证）；生成物与裁剪后的几何副本都提交进仓（`Models/` 不在版本控制里）。客户端烘两份模型层，渲染器按同步过来的性别把选中的那份赋给 `model` 字段。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API / Java 21 / Gradle 9.2.1（离线）/ Python 3（生成器）。

**设计依据：** [ART_INTEGRATION_DESIGN.md](ART_INTEGRATION_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21。构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行（25–80 秒，Bash 超时给 300000 ms）。
- **`Models/` 不在版本控制里**（根 `.gitignore` 是 `/*`，只放行 `.gitignore`、`README.md`、`goblin-settlement-mod/`、`goblin-settlement-plan/`）。生成器**必须能从提交进仓的裁剪件单独运行**；只有 `--crop` 那一步需要 `Models/`。
- **生成物不许手改**：`src/client/java/dev/local/goblinsettlement/client/model/Goblin{Male,Female}Model.java` 由生成器产出。要改模型就重跑生成器，不是改 Java。
- **变换规则以交叉校验为准**：`SCALE = 1` 时生成器必须逐条重现 `Models/goblin_male_a_final/GoblinModelCandidate.java`（美术手工适配过、已在本环境编译通过）。这是整条管线唯一可自证的环节。
- **不支持的形状必须明确失败**，不许静默画错：非盒式 UV（`box_uv` 为假或元素带 `faces`）、多于一个非零旋转轴、顶层分组名不是规定的六个、缺 `resolution`。
- **缩放 0.5 只在一处施加**（生成器里），渲染器里不留缩放魔数。
- **不新增持久字段**：性别借用 `ResidentRecord.effectiveReproductiveRole()`（设计 §4.1）。
- **占位模型不许删**：`GoblinModel` 与 `goblin_golem.png` 仍是傀儡的模型与贴图，`defense/GolemRenderer.java` 在自己的 `GOLEM_LAYER` 下烘它们。
  **（实现期更正）** 本条原文还写了"`GOBLIN_LAYER` 不许删"，理由是"傀儡复用"——**那条理由不成立**：傀儡一直用的是自己的 `GOLEM_LAYER`，`GOBLIN_LAYER` 在本轮换成 `GOBLIN_MALE_LAYER` / `GOBLIN_FEMALE_LAYER` 之后就没有任何地方烘它了，已是死代码，**已在 Task 4 的修复轮删除**。设计 §1/§5 本来就写对了（点名 `GOLEM_LAYER`），是计划摘要时写岔的。
- 检查项数由 **20 增至 21**（新增 `artModelCheck`）。构建结束时 21 项必须全部 `*Check passed`。
- 不做游戏内验证（按用户约定）。模型观感、光照、缩放与脚底是否真的对、穿墙程度都**不可纯测**，写进日志与状态文件。
- `goblin-settlement-plan/` 下可能有并行 agent 的未提交改动：文档任务先跑 `git status`。`UpdateLog.md` **只许在末尾追加**。
- 提交到 `main`，本轮结束推送。

---

### Task 1: 生成器与成年男模型

**Files:**
- Create: `goblin-settlement-mod/tools/generate_models.py`
- Create: `goblin-settlement-mod/tools/models/goblin_male_a.json`（`--crop` 输出，提交）
- Create: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/model/GoblinBodyModel.java`
- Create: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/model/GoblinMaleModel.java`（生成物，提交）
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/client/ArtModelCheck.java`
- Modify: `goblin-settlement-mod/build.gradle`

**Interfaces:**
- Produces: `GoblinBodyModel`（抽象基类，持有 `head`/`leftArm`/`rightArm`/`leftLeg`/`rightLeg` 与共用的 `setupAnim`）；`GoblinMaleModel(ModelPart)` 与其 `createLayer()`；`ArtModelCheck` 与 `artModelCheck` Gradle 任务

- [ ] **Step 1: 写生成器（完整脚本）**

创建 `goblin-settlement-mod/tools/generate_models.py`：

```python
#!/usr/bin/env python3
"""Turn a Blockbench Modded Entity project into a Minecraft model class.

Two deliberately separate steps:

    python tools/generate_models.py --crop    Models/<project>.bbmodel -> tools/models/<name>.json
    python tools/generate_models.py           tools/models/<name>.json -> the Java model class

They are separate because Models/ is the art team's live workspace and is NOT in version control
(the repository root .gitignore allows only .gitignore, README.md, goblin-settlement-mod/ and
goblin-settlement-plan/). The committed crop is what this module's geometry is regenerated from.

The transform is not a guess. At SCALE = 1 the forms below reproduce, number for number, the Java
candidate the art team adapted by hand (Models/goblin_male_a_final/GoblinModelCandidate.java):

  * a part's model-space position is (-origin.x, GROUND - origin.y, origin.z) -- x mirrored, y
    measured downward from the ground line at GROUND;
  * a cube spans, relative to its own origin, x from -(from.x - origin.x) - width (mirrored),
    y from -(to.y - origin.y) (flipped), z from from.z - origin.z;
  * a nested part's PartPose is relative to its parent's model-space position;
  * a single-axis Blockbench rotation keeps its sign, converted to radians.

Scaling happens here and only here: it is a scaling about the ground line, so the feet stay put.

See ART_INTEGRATION_DESIGN.md sections 3.1-3.3 before changing any of it.
"""
import argparse
import json
import math
import os
import re
import sys

SCALE = 0.5     # the art is built on a 2x grid; see the delivery READMEs
GROUND = 24.0   # the model-space y the renderer stands the entity's feet on
REQUIRED_GROUPS = ["head", "body", "left_arm", "right_arm", "left_leg", "right_leg"]

MOD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(os.path.dirname(MOD), "Models")
CROPS = os.path.join(MOD, "tools", "models")
OUT = os.path.join(MOD, "src", "client", "java", "dev", "local", "goblinsettlement", "client", "model")

MODELS = {
    "goblin_male_a": ("goblin_male_a_final/goblin_male_a_final.bbmodel", "GoblinMaleModel"),
    "goblin_female_a": ("goblin_female_a/goblin_female_a.bbmodel", "GoblinFemaleModel"),
}


def fail(message):
    raise SystemExit("generate_models: " + message)


def number(value):
    """A Java float literal that survives a round trip."""
    text = "%.4f" % value
    text = text.rstrip("0").rstrip(".")
    if text in ("", "-", "-0"):
        text = "0"
    return text + "F"


def java_name(raw):
    cleaned = re.sub(r"[^0-9A-Za-z_]", "_", raw or "part")
    if not cleaned or not cleaned[0].isalpha():
        cleaned = "part_" + cleaned
    return cleaned


def crop(project, source):
    path = os.path.join(ART, source)
    if not os.path.isfile(path):
        fail("no such project: " + path)
    raw = json.load(open(path, encoding="utf-8"))
    if not raw.get("resolution"):
        fail("%s: no resolution" % project)
    if not raw.get("meta", {}).get("box_uv"):
        fail("%s: not a box-uv project" % project)
    elements = []
    for element in raw.get("elements", []):
        if element.get("type") != "cube":
            fail("%s: element %s is not a cube" % (project, element.get("name")))
        if element.get("faces"):
            fail("%s: element %s uses per-face uv" % (project, element["name"]))
        elements.append({key: element.get(key) for key in
                         ("name", "uuid", "from", "to", "origin", "rotation", "uv_offset", "box_uv")})
    trimmed = {
        "name": raw.get("name", project),
        "resolution": raw["resolution"],
        "elements": elements,
        "groups": [{"name": g["name"], "uuid": g["uuid"], "origin": g["origin"]}
                   for g in raw.get("groups", [])],
        "outliner": raw.get("outliner", []),
    }
    os.makedirs(CROPS, exist_ok=True)
    target = os.path.join(CROPS, project + ".json")
    with open(target, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(trimmed, handle, ensure_ascii=False, indent=1, sort_keys=True)
        handle.write("\n")
    print("cropped %s -> %s (%d elements)" % (path, target, len(elements)))


def model_position(origin):
    """Art coordinates (y up from the feet) -> model space (y down, feet on GROUND, x mirrored)."""
    return (-origin[0], GROUND - origin[1], origin[2])


def cube_box(element, origin):
    fx, fy, fz = element["from"]
    tx, ty, tz = element["to"]
    width, height, depth = tx - fx, ty - fy, tz - fz
    return ((-(fx - origin[0]) - width) * SCALE,
            (-(ty - origin[1])) * SCALE,
            (fz - origin[2]) * SCALE,
            width * SCALE, height * SCALE, depth * SCALE)


def cube_chain(elements, origin, indent):
    chain = []
    for element in elements:
        x, y, z, w, h, d = cube_box(element, origin)
        chain.append("\n%s        .texOffs(%d, %d).addBox(%s, %s, %s, %s, %s, %s)"
                     % (indent, element["uv_offset"][0], element["uv_offset"][1],
                        number(x), number(y), number(z), number(w), number(h), number(d)))
    return "".join(chain)


def rotations(element):
    """Blockbench degrees -> radians, same sign. More than one non-zero axis is refused: the two
    systems compose rotation axes in different orders, so a multi-axis part needs its own
    derivation before it can be trusted."""
    rx, ry, rz = element.get("rotation", [0, 0, 0])
    nonzero = [axis for axis, value in zip("xyz", (rx, ry, rz)) if value]
    if len(nonzero) > 1:
        fail("element %s rotates on %s axes; only single-axis rotation is derived"
             % (element["name"], "".join(nonzero)))
    return (math.radians(rx), math.radians(ry), math.radians(rz))


def pose(absolute, parent, rotation=None):
    x = (absolute[0] - parent[0]) * SCALE
    y = (absolute[1] - parent[1]) * SCALE
    z = (absolute[2] - parent[2]) * SCALE
    if rotation is None:
        return "PartPose.offset(%s, %s, %s)" % (number(x), number(y), number(z))
    return "PartPose.offsetAndRotation(%s, %s, %s, %s, %s, %s)" % (
        number(x), number(y), number(z),
        number(rotation[0]), number(rotation[1]), number(rotation[2]))


class Emitter:
    """Walks one project's outliner: unrotated elements of a group share that group's single cube
    list; an element that carries a rotation becomes its own child part named <element>_r1, which is
    what Blockbench's own exporter does."""

    def __init__(self, lines, groups, elements):
        self.lines = lines
        self.groups = groups
        self.elements = elements
        self.used = set()

    def declare(self, indent, feed, raw_name, cubes, part_pose):
        variable = java_name(raw_name)
        if variable in self.used:
            fail("two parts would be called %s" % variable)
        self.used.add(variable)
        self.lines.append(
            "%sPartDefinition %s = %s.addOrReplaceChild(\"%s\", CubeListBuilder.create()%s, %s);"
            % (indent, variable, feed, raw_name, cubes, part_pose))
        return variable

    def walk(self, node, feed, parent, indent):
        group = self.groups.get(node.get("uuid"))
        if group is None:
            fail("outliner node has no matching entry in groups")
        origin = group["origin"]
        absolute = model_position(origin)
        flat, turned, nested = [], [], []
        for child in node.get("children", []):
            if isinstance(child, dict):
                nested.append(child)
                continue
            element = self.elements.get(child)
            if element is None:
                fail("outliner leaf %s is not an element" % child)
            rx, ry, rz = rotations(element)
            (turned if (rx or ry or rz) else flat).append(element)
        variable = self.declare(indent, feed, group["name"], cube_chain(flat, origin, indent),
                                pose(absolute, parent))
        for element in turned:
            rx, ry, rz = rotations(element)
            self.declare(indent + "    ", variable, element["name"] + "_r1",
                         cube_chain([element], element["origin"], indent + "    "),
                         pose(model_position(element["origin"]), absolute, (rx, ry, rz)))
        for child in nested:
            self.walk(child, variable, absolute, indent + "    ")


def generate(project, class_name):
    path = os.path.join(CROPS, project + ".json")
    if not os.path.isfile(path):
        fail("no crop at %s -- run --crop first, on a machine that has Models/" % path)
    data = json.load(open(path, encoding="utf-8"))
    groups = {g["uuid"]: g for g in data["groups"]}
    elements = {e["uuid"]: e for e in data["elements"]}
    roots = [node for node in data["outliner"] if isinstance(node, dict)]
    names = sorted(groups[node["uuid"]]["name"] for node in roots if node.get("uuid") in groups)
    if names != sorted(REQUIRED_GROUPS):
        fail("%s: top-level groups are %s, expected %s" % (project, names, REQUIRED_GROUPS))

    lines = [
        "package dev.local.goblinsettlement.client.model;",
        "",
        "import net.minecraft.client.model.geom.ModelPart;",
        "import net.minecraft.client.model.geom.PartPose;",
        "import net.minecraft.client.model.geom.builders.CubeListBuilder;",
        "import net.minecraft.client.model.geom.builders.LayerDefinition;",
        "import net.minecraft.client.model.geom.builders.MeshDefinition;",
        "import net.minecraft.client.model.geom.builders.PartDefinition;",
        "",
        "/**",
        " * Generated by tools/generate_models.py from tools/models/%s.json -- do not hand-edit;" % project,
        " * re-run the generator instead. See ART_INTEGRATION_DESIGN.md section 3.",
        " */",
        "public final class %s extends GoblinBodyModel {" % class_name,
        "    public %s(ModelPart root) {" % class_name,
        "        super(root);",
        "    }",
        "",
        "    public static LayerDefinition createLayer() {",
        "        MeshDefinition mesh = new MeshDefinition();",
        "        PartDefinition root = mesh.getRoot();",
    ]
    emitter = Emitter(lines, groups, elements)
    for node in roots:
        emitter.walk(node, "root", (0, 0, 0), "        ")
    lines.append("        return LayerDefinition.create(mesh, %d, %d);"
                 % (data["resolution"]["width"], data["resolution"]["height"]))
    lines.append("    }")
    lines.append("}")

    os.makedirs(OUT, exist_ok=True)
    target = os.path.join(OUT, class_name + ".java")
    with open(target, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")
    print("generated %s -> %s" % (path, target))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--crop", action="store_true",
                        help="read the art workspace and refresh tools/models/ (needs Models/)")
    args = parser.parse_args()
    for project, (source, class_name) in sorted(MODELS.items()):
        if args.crop:
            crop(project, source)
        else:
            generate(project, class_name)


if __name__ == "__main__":
    main()
```

- [ ] **Step 2: 写基类**

创建 `src/client/java/dev/local/goblinsettlement/client/model/GoblinBodyModel.java`：

```java
package dev.local.goblinsettlement.client.model;

import dev.local.goblinsettlement.client.GoblinRenderState;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * The six-part goblin body every art variant shares. The generated meshes differ only in geometry, so
 * the walk and look animation lives here exactly once.
 */
public abstract class GoblinBodyModel extends EntityModel<GoblinRenderState> {
    private final ModelPart head;
    private final ModelPart leftArm;
    private final ModelPart rightArm;
    private final ModelPart leftLeg;
    private final ModelPart rightLeg;

    protected GoblinBodyModel(ModelPart root) {
        super(root);
        head = root.getChild("head");
        leftArm = root.getChild("left_arm");
        rightArm = root.getChild("right_arm");
        leftLeg = root.getChild("left_leg");
        rightLeg = root.getChild("right_leg");
    }

    @Override
    public void setupAnim(GoblinRenderState state) {
        super.setupAnim(state);
        head.xRot = state.xRot * Mth.DEG_TO_RAD;
        head.yRot = state.yRot * Mth.DEG_TO_RAD;
        float swing = state.walkAnimationSpeed * 1.2F;
        float phase = state.walkAnimationPos * 0.65F;
        leftLeg.xRot = Mth.cos(phase) * swing;
        rightLeg.xRot = Mth.cos(phase + Mth.PI) * swing;
        leftArm.xRot = rightLeg.xRot * 0.6F;
        rightArm.xRot = leftLeg.xRot * 0.6F;
    }
}
```

（`setupAnim` 的算式**逐字**来自现有 `client/GoblinModel.java:47-58`——它是对的，不要顺手改。）

- [ ] **Step 3: 裁剪与生成**

```bash
cd goblin-settlement-mod
python tools/generate_models.py --crop
python tools/generate_models.py
```

Expected: 先打印两行 `cropped ... -> .../tools/models/<name>.json (... elements)`（男性 108、女性 129），再打印两行 `generated ...`。

本任务只验收**男性**的产物（`GoblinMaleModel.java`）；女性由 Task 2 验收，此时它也已生成，不必删。

- [ ] **Step 4: 交叉校验（`SCALE = 1`）**

把 `generate_models.py` 里的 `SCALE = 0.5` 临时改成 `1.0`，重跑 `python tools/generate_models.py`，然后比对：

```bash
cd ..
python - <<'PY'
import re

NUM = r'(-?\d+(?:\.\d+)?)F?'
BOX = re.compile(r'addBox\(\s*' + r',\s*'.join([NUM] * 6))
POSE6 = re.compile(r'PartPose\.offsetAndRotation\(\s*' + r',\s*'.join([NUM] * 6))
POSE3 = re.compile(r'PartPose\.offset\(\s*' + r',\s*'.join([NUM] * 3))

def boxes(path):
    text = open(path, encoding='utf-8').read()
    return sorted(tuple(round(float(v), 4) for v in m) for m in BOX.findall(text))

def poses(path):
    text = open(path, encoding='utf-8').read()
    found = [tuple(round(float(v), 4) for v in m) for m in POSE6.findall(text)]
    found += [tuple(round(float(v), 4) for v in m) for m in POSE3.findall(text)]
    return sorted(found)

gen = 'goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/model/GoblinMaleModel.java'
ref = 'Models/goblin_male_a_final/GoblinModelCandidate.java'
a, b = boxes(gen), boxes(ref)
print('generated boxes:', len(a), ' candidate boxes:', len(b))
print('in generated only:', [x for x in a if x not in b][:3])
print('in candidate only:', [x for x in b if x not in a][:3])
pa, pb = poses(gen), poses(ref)
print('poses identical:', pa == pb, len(pa), len(pb))
print('pose diff:', [x for x in pa if x not in pb][:3], [x for x in pb if x not in pa][:3])
PY
```

**这个比对必须按数值做，不能按字符串**：生成器写出 `-8F`，而美术候选是 `-8.0F`——文本比对会假失败。

Expected: 两个 `in ... only:` 都是空列表，`poses identical: True`。

**若对不上**：不要放宽比对，也不要改候选。把差异原样贴出来**回报**——差异的形状会直接指出四条规则里哪一条错了。

**验完把 `SCALE` 改回 `0.5` 并重新生成。**

- [ ] **Step 5: 写检查并注册**

创建 `src/test/java/dev/local/goblinsettlement/client/ArtModelCheck.java`：

```java
package dev.local.goblinsettlement.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Checks the committed art crops before the client ever bakes them: the six required groups, the feet
 * on the art ground line, every cube inside the declared texture, and box uv only.
 *
 * The crop is what this module's geometry is regenerated from -- the art workspace is not in version
 * control -- so this is the one place a bad model is caught before it reaches a screen.
 */
public final class ArtModelCheck {
    private static final Set<String> REQUIRED = Set.of(
            "head", "body", "left_arm", "right_arm", "left_leg", "right_leg");

    public static void main(String[] args) throws IOException {
        Path dir = Path.of("tools", "models");
        require(Files.isDirectory(dir), "the crop directory exists: " + dir.toAbsolutePath());
        List<Path> crops = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted()
                    .forEach(crops::add);
        }
        require(!crops.isEmpty(), "at least one cropped model is committed");
        for (Path crop : crops) {
            checkOne(crop);
        }
        System.out.println("ArtModelCheck passed (" + crops.size() + " models)");
    }

    private static void checkOne(Path path) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .getAsJsonObject();
        String name = path.getFileName().toString();
        require(root.has("resolution"), name + ": declares a resolution");
        JsonObject resolution = root.getAsJsonObject("resolution");
        int u = resolution.get("width").getAsInt();
        int v = resolution.get("height").getAsInt();

        Set<String> groups = new TreeSet<>();
        for (var group : root.getAsJsonArray("groups")) {
            groups.add(group.getAsJsonObject().get("name").getAsString());
        }
        require(groups.equals(new TreeSet<>(REQUIRED)),
                name + ": groups are " + groups + ", expected " + new TreeSet<>(REQUIRED));

        JsonArray elements = root.getAsJsonArray("elements");
        require(elements.size() > 0, name + ": has elements");
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (var entry : elements) {
            JsonObject element = entry.getAsJsonObject();
            require(element.get("box_uv").getAsBoolean(), name + ": every element uses box uv");
            require(!element.has("faces"), name + ": no element carries per-face uv");
            var from = element.getAsJsonArray("from");
            var to = element.getAsJsonArray("to");
            for (int axis = 0; axis < 3; axis++) {
                int lo = from.get(axis).getAsInt();
                int hi = to.get(axis).getAsInt();
                require(lo <= hi, name + ": a cube has an inverted extent on axis " + axis);
                if (axis != 1) {
                    require(lo >= 0 && hi <= (axis == 0 ? u : v),
                            name + ": a cube leaves the texture on axis " + axis);
                }
            }
            lowest = Math.min(lowest, from.get(1).getAsInt());
            highest = Math.max(highest, to.get(1).getAsInt());
        }
        require(lowest == 0, name + ": the feet sit on the art ground line (lowest y is " + lowest + ")");
        require(highest <= 64, name + ": the model is not absurdly tall (" + highest + " art units)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("ArtModelCheck failed: " + message);
        }
    }
}
```

在 `build.gradle` 的 `bridgeMaterialsCheck` 注册块**之后**加：

```groovy
tasks.register('artModelCheck', JavaExec) {
    group = 'verification'
    description = 'Checks the committed art crops: groups, ground line, texture bounds, box uv.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.client.ArtModelCheck'
}
```

并在 `tasks.named('check')` 末尾（`dependsOn tasks.named('housingAssignmentCheck')` 之后）加：

```groovy
    dependsOn tasks.named('artModelCheck')
```

- [ ] **Step 6: 跑检查并跑完整构建**

Run: `./gradlew artModelCheck --offline --no-daemon`

Expected: `ArtModelCheck passed (2 models)`。

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**21 项**检查全部 `*Check passed`。

**若 `GoblinMaleModel` 编译失败**：先看是不是 `number()` 产出的字面量不合法。**不要手改生成物**——改生成器再重跑。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/tools/ \
        goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/model/ \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/client/ArtModelCheck.java \
        goblin-settlement-mod/build.gradle
git commit -m "Generate the adult goblin meshes from the art projects"
```

---

### Task 2: 成年女模型

**Files:**
- Create: `goblin-settlement-mod/tools/models/goblin_female_a.json`（Task 1 的 `--crop` 已写出）
- Create: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/model/GoblinFemaleModel.java`（Task 1 的生成已写出）

**Interfaces:**
- Consumes: Task 1 的 `tools/generate_models.py`、`GoblinBodyModel`
- Produces: `GoblinFemaleModel(ModelPart)` 与其 `createLayer()`

- [ ] **Step 1: 重新生成并确认两个模型都在**

```bash
cd goblin-settlement-mod
python tools/generate_models.py
ls -l tools/models/ src/client/java/dev/local/goblinsettlement/client/model/
```

Expected: `tools/models/` 下两个 JSON，model 目录下 `GoblinBodyModel.java`、`GoblinMaleModel.java`、`GoblinFemaleModel.java`。

- [ ] **Step 2: 检查女性模型的结构**

女性模型与男性**不是同一套分组布局**（129 个立方体、有裙片与辫发）。生成器若对它有假设，这里会暴露。

Run: `./gradlew artModelCheck --offline --no-daemon`

Expected: `ArtModelCheck passed (2 models)`。

**若失败**：错误信息会指出是哪个模型、哪条不变量、哪个立方体。**不要放宽检查**——先判断是生成器错了还是数据真的违反不变量；是前者就修生成器并重跑。**把判断结论写进报告**。

- [ ] **Step 3: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，21 项全部 `*Check passed`。

- [ ] **Step 4: 提交**

```bash
git add goblin-settlement-mod/tools/models/ \
        goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/model/GoblinFemaleModel.java
git commit -m "Generate the adult female goblin mesh"
```

---

### Task 3: 把性别同步到客户端

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ResidentRecord.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`
- Modify: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/GoblinRenderState.java`
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/SettlementSavedDataCheck.java`

**Interfaces:**
- Produces: `ResidentRecord.looksFemale() -> boolean`；`GoblinCitizenEntity.looksFemale()` / `femaleForRender() -> boolean`；`GoblinRenderState.female`

- [ ] **Step 1: 先写检查（RED）**

在 `SettlementSavedDataCheck` 的 `checkHomeSurvivesEveryRosterEdit();` 调用**之后**加：

```java
        checkAppearanceSex();
```

并在该文件末尾（`require` 之前）加：

```java
    /**
     * Appearance sex is borrowed from the reproductive role rather than stored again: the roster has no
     * separate sex field, and every resident -- legacy saves included -- already has a stable role.
     */
    private static void checkAppearanceSex() {
        var mother = new ResidentRecord("m1", ResidentRecord.LifeStage.ADULT,
                java.util.Optional.empty(), java.util.Optional.empty(),
                ResidentRecord.ReproductiveRole.MOTHER, Profession.UNASSIGNED, 0, 0,
                java.util.Optional.empty());
        require(mother.looksFemale(), "a mother looks female");
        var father = new ResidentRecord("f1", ResidentRecord.LifeStage.ADULT,
                java.util.Optional.empty(), java.util.Optional.empty(),
                ResidentRecord.ReproductiveRole.FATHER, Profession.UNASSIGNED, 0, 0,
                java.util.Optional.empty());
        require(!father.looksFemale(), "a father does not look female");
        var unset = new ResidentRecord("u1", ResidentRecord.LifeStage.ADULT,
                java.util.Optional.empty(), java.util.Optional.empty(),
                ResidentRecord.ReproductiveRole.UNSPECIFIED, Profession.UNASSIGNED, 0, 0,
                java.util.Optional.empty());
        require(unset.looksFemale() == unset.looksFemale(), "an unset role answers the same way twice");
        require(unset.looksFemale()
                        == unset.effectiveReproductiveRole().equals(ResidentRecord.ReproductiveRole.MOTHER),
                "an unset role follows the same stable fallback the rest of the code uses");
    }
```

- [ ] **Step 2: 跑检查，确认按预期失败**

Run: `./gradlew settlementSavedDataCheck --offline --no-daemon`

Expected: `:compileTestJava FAILED`，报错形如 `找不到符号: 方法 looksFemale()`。

- [ ] **Step 3: 加纯判据**

在 `ResidentRecord` 的 `effectiveReproductiveRole()` **之后**加：

```java
    /**
     * Which body this resident wears. Borrowed from the reproductive role rather than stored on its own:
     * the roster has no separate sex field, and the role -- with its stable fallback for old saves -- is
     * already a fact every resident carries. Do not read this as biology; it is the one stable bit the
     * save holds that the renderer can key off.
     */
    public boolean looksFemale() {
        return effectiveReproductiveRole() == ReproductiveRole.MOTHER;
    }
```

- [ ] **Step 4: 实体同步**

在 `GoblinCitizenEntity` 里：

① `DATA_PROFESSION` 声明（`:67-68`）**之后**加：

```java
    private static final EntityDataAccessor<Boolean> DATA_FEMALE =
            SynchedEntityData.defineId(GoblinCitizenEntity.class, EntityDataSerializers.BOOLEAN);
```

② `defineSynchedData` 里 `builder.define(DATA_PROFESSION, ...)`（`:134`）**之后**加：

```java
        builder.define(DATA_FEMALE, false);
```

③ `profession()` **之后**加：

```java
    /** This resident's body, read from the roster for the same reason the trade is. */
    public boolean looksFemale() {
        if (level() instanceof ServerLevel serverLevel) {
            return SettlementSavedData.get(serverLevel).resident(getUUID().toString())
                    .map(ResidentRecord::looksFemale)
                    .orElse(false);
        }
        return false;
    }

    /** The synced body, safe to call on either side. */
    public boolean femaleForRender() {
        return getEntityData().get(DATA_FEMALE);
    }
```

④ `getEntityData().set(DATA_PROFESSION, profession().name());`（`:665`）**之后**加：

```java
        getEntityData().set(DATA_FEMALE, looksFemale());
```

（`EntityDataSerializers` 若未 import 就补。）

- [ ] **Step 5: 渲染状态带性别**

把 `GoblinRenderState` 换成：

```java
package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.colony.Profession;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

public final class GoblinRenderState extends LivingEntityRenderState {
    public Profession profession = Profession.UNASSIGNED;
    public boolean female;
}
```

（`profession` 本轮**仍然保留**：贴图不再按职业选，但职业是实体的既有同步事实，清掉它属于职业外观那一轮。）

- [ ] **Step 6: 跑检查并跑完整构建**

Run: `./gradlew settlementSavedDataCheck --offline --no-daemon`

Expected: `SettlementSavedDataCheck passed`。

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，21 项全部 `*Check passed`。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ResidentRecord.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java \
        goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/GoblinRenderState.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/SettlementSavedDataCheck.java
git commit -m "Sync which body each resident wears to the client"
```

---

### Task 4: 注册两个模型层、按性别渲染、换贴图

**Files:**
- Modify: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/GoblinSettlementClient.java`
- Modify: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/GoblinRenderer.java`
- Create: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/entity/goblin_male.png`
- Create: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/entity/goblin_female.png`
- Delete: 8 张旧哥布林贴图（`goblin.png` 与 7 张职业贴图）

**Interfaces:**
- Consumes: `GoblinMaleModel` / `GoblinFemaleModel`（Task 1/2）、`GoblinRenderState.female`（Task 3）
- Produces: `GoblinSettlementClient.GOBLIN_MALE_LAYER` / `GOBLIN_FEMALE_LAYER`

- [ ] **Step 1: 复制新贴图**

```bash
cd "D:/MC/.minecraft/versions/1.21.11-Fabric 0.19.2"
cp Models/goblin_male_a_final/goblin_male_a_final.png \
   goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/entity/goblin_male.png
cp Models/goblin_female_a/goblin_female_a.png \
   goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/entity/goblin_female.png
ls -l goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/entity/
```

Expected: `goblin_male.png` 与 `goblin_female.png` 都是 512×512。

- [ ] **Step 2: 删旧贴图**

```bash
cd goblin-settlement-mod
git rm src/main/resources/assets/goblin_settlement/textures/entity/goblin.png \
       src/main/resources/assets/goblin_settlement/textures/entity/goblin_farmer.png \
       src/main/resources/assets/goblin_settlement/textures/entity/goblin_forester.png \
       src/main/resources/assets/goblin_settlement/textures/entity/goblin_miner.png \
       src/main/resources/assets/goblin_settlement/textures/entity/goblin_builder.png \
       src/main/resources/assets/goblin_settlement/textures/entity/goblin_hauler.png \
       src/main/resources/assets/goblin_settlement/textures/entity/goblin_artisan.png \
       src/main/resources/assets/goblin_settlement/textures/entity/goblin_sentry.png
```

**`goblin_golem.png` 留着**——傀儡本轮仍用它。

- [ ] **Step 3: 注册三个模型层**

把 `GoblinSettlementClient` 换成：

```java
package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.GoblinSettlement;
import dev.local.goblinsettlement.citizen.ModEntities;
import dev.local.goblinsettlement.client.model.GoblinFemaleModel;
import dev.local.goblinsettlement.client.model.GoblinMaleModel;
import dev.local.goblinsettlement.defense.GolemRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.resources.Identifier;

public final class GoblinSettlementClient implements ClientModInitializer {
    /** The golem still uses the placeholder model; it gets its own art in a later round. */
    public static final ModelLayerLocation GOBLIN_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "goblin"), "main");
    public static final ModelLayerLocation GOBLIN_MALE_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "goblin_male"), "main");
    public static final ModelLayerLocation GOBLIN_FEMALE_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "goblin_female"), "main");

    @Override
    public void onInitializeClient() {
        EntityModelLayerRegistry.registerModelLayer(GOBLIN_LAYER, GoblinModel::createLayer);
        EntityModelLayerRegistry.registerModelLayer(GOBLIN_MALE_LAYER, GoblinMaleModel::createLayer);
        EntityModelLayerRegistry.registerModelLayer(GOBLIN_FEMALE_LAYER, GoblinFemaleModel::createLayer);
        EntityRenderers.register(ModEntities.GOBLIN, GoblinRenderer::new);
        GolemRenderer.initializeClient();
    }
}
```

- [ ] **Step 4: 渲染器按性别选模型与贴图**

把 `GoblinRenderer` 整个换成：

```java
package dev.local.goblinsettlement.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.client.model.GoblinFemaleModel;
import dev.local.goblinsettlement.client.model.GoblinMaleModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.Identifier;

public final class GoblinRenderer
        extends MobRenderer<GoblinCitizenEntity, GoblinRenderState, EntityModel<GoblinRenderState>> {
    private static final Identifier TEXTURE_MALE = texture("goblin_male");
    private static final Identifier TEXTURE_FEMALE = texture("goblin_female");

    private final EntityModel<GoblinRenderState> male;
    private final EntityModel<GoblinRenderState> female;

    public GoblinRenderer(EntityRendererProvider.Context context) {
        // super(...) needs a model; the two below are the ones we actually swap between. They wrap the
        // same baked ModelPart the layer registry produced, so this costs a shell, not a second bake.
        super(context, new GoblinMaleModel(context.bakeLayer(GoblinSettlementClient.GOBLIN_MALE_LAYER)), 0.3F);
        male = new GoblinMaleModel(context.bakeLayer(GoblinSettlementClient.GOBLIN_MALE_LAYER));
        female = new GoblinFemaleModel(context.bakeLayer(GoblinSettlementClient.GOBLIN_FEMALE_LAYER));
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath("goblin_settlement", "textures/entity/" + name + ".png");
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
        this.model = state.female ? female : male;
        super.submit(state, pose, collector, camera);
    }

    @Override
    public EntityModel<GoblinRenderState> getModel() {
        return model;
    }

    @Override
    public Identifier getTextureLocation(GoblinRenderState state) {
        return state.female ? TEXTURE_FEMALE : TEXTURE_MALE;
    }
}
```

**若 `submit` 的签名或 `model` 的可见性与本机不符**：以反编译为准，不要猜。

```bash
javap -classpath ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-clientonly/1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2/minecraft-clientonly-1.21.11-loom.mappings.1_21_11.layered+hash.2198-v2.jar \
      net.minecraft.client.renderer.entity.LivingEntityRenderer | grep -E "submit|model|getModel"
```

把输出贴进报告。

- [ ] **Step 5: 跑完整构建**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，21 项全部 `*Check passed`，**无编译警告**。

- [ ] **Step 6: 核对没有残留**

Run: `grep -rn "goblin_farmer\|goblin_forester\|goblin_miner\|goblin_builder\|goblin_hauler\|goblin_artisan\|goblin_sentry\|TEXTURE_" src/client/java/`

Expected: **无匹配**。

Run: `ls src/main/resources/assets/goblin_settlement/textures/entity/`

Expected: 只剩 `goblin_golem.png`、`goblin_female.png`、`goblin_male.png`。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/ \
        goblin-settlement-mod/src/main/resources/assets/goblin_settlement/textures/entity/
git commit -m "Show the adult art in game, one body per sex"
```

---

### Task 5: 文档与收尾

**Files:**
- Modify: `goblin-settlement-plan/ART_INTEGRATION_DESIGN.md`（§10 落地结果）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Tasks 1–4 的改动与构建结果（**含 jar 实际字节数、构建秒数、每个提交的哈希**）

- [ ] **Step 1: 先查并行改动**

Run: `git status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：先单独提交它们并署名，再做本任务的文档改动。

- [ ] **Step 2: 设计文档记录落地结果**

把 `ART_INTEGRATION_DESIGN.md` 末尾的 `## 10. 落地结果（实现后补记）` 替换为真实内容，至少覆盖：生成器的最终形态与 `SCALE = 1` 交叉校验的**实测差异条数**、脚底对齐为何不需要额外补偿量（绕地面线缩放）、`artModelCheck` 的实际形状、渲染器切换的最终签名（若与计划不同，就地更正）、以及实现期与设计不符之处。

- [ ] **Step 3: 追加 UpdateLog**

在 `UpdateLog.md` **末尾**追加第六十一轮（美术接入第一轮）的段落。`<...>` 换成真实值，**逐条写真实时间**（本项目多次因留占位符或写错时间而返工）。必须包含：

- 本轮范围与三处用户已定（程序化动画 / 先只做成年男女 / 碰撞箱不动）；
- **变换规则是实地验证出来的**：轴心 `(-origin.x, 24-origin.y, origin.z)`、立方体 x 绕轴心镜像、嵌套子组偏移"绝对减父级"、单轴旋转同号——四条都在美术自己的 `GoblinModelCandidate.java` 上逐条对上，**并记录 `SCALE = 1` 交叉校验的实测差异条数**；
- **`Models/` 不在版本控制里**（根 `.gitignore` 只放行四个路径），所以裁剪件必须进仓——这是结构性约束，不是选择；
- **缩放是绕地面线做的**，所以脚底不需要额外补偿量；艺术 y 范围 0..47、缩放后模型高 23.5 模型单位 = 1.47 格，与碰撞高 1.45 几乎相等；
- **切换挂点**：这个版本的提交入口是 `submit(...)`（没有旧的 `render`），且**直接读 `model` 字段、不走 `getModel()`**；
- 性别借用 `effectiveReproductiveRole()`、**不新增持久字段**；
- 验证：完整离线构建、21 项检查、产物字节数与耗时、提交清单；
- **未完成**：不做玩法验收；模型观感、光照、缩放与脚底是否真的对、耳朵/头发相对碰撞箱的穿墙程度、多人同屏性能**全部不可纯测**；七职业外观**暂时一致**（本轮唯一可见的退步，已由用户确认）；`GoblinModel` 此后只服务傀儡。

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻；「接续须知」的分支指针改成本轮收尾提交；**下一步落点**改写为：美术接入第一轮（成年男女）已完成，落点在 `ART_INTEGRATION_DESIGN.md` §10；下一轮候选是职业装备 / 儿童 / 傀儡。
- 「阶段定位」阶段 6 补上"第六十一轮起接入美术成品（成年男女两套）"。
- 「本轮接入的内容」新增一节「第六十一轮：美术接入（成年男女）」。
- 「美术候选」一段改写：**成年男女已接入运行时资源**；仍未接入的是儿童、七职业装备、五款傀儡；职业贴图已删除、外观暂时一致。
- 「本轮验证进展」替换为第六十一轮（21 项检查、产物字节数与耗时、提交）。
- 别处提到检查项数的地方一律由 20 改为 **21**（**grep 一遍，别靠看**）。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the first art-integration round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。若连不上 github，提交留在本地并如实报告。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 数据流（裁剪 → 生成 → 注册 → 选择）→ Task 1（生成器与裁剪件）、Task 2（第二个数据点）、Task 4（注册与选择）。
- 设计 §3.1/§3.2 变换规则（含"绕地面线缩放"）→ Task 1 Step 1 的完整脚本 + Step 4 的交叉校验。
- 设计 §3.3 交叉校验 → Task 1 Step 4（结论写进 Task 5 的 UpdateLog）。
- 设计 §3.4 拒绝规则 → Task 1 Step 1 的 `fail(...)` 分支与 Step 5 的检查。
- 设计 §4.1 性别来源 → Task 3。设计 §4.2 切换挂点 → Task 4 Step 4。
- 设计 §5 资源布局与旧资源处置 → Task 4 Step 1/2（删旧贴图）与 Step 3（保留 `GoblinModel`/`GOBLIN_LAYER`）。
- 设计 §6 动画 → Task 1 Step 2 的 `GoblinBodyModel`（算式逐字沿用 `GoblinModel`）。
- 设计 §7 验证 → Task 1 Step 5 的 `artModelCheck` + 各任务的构建步骤。
- 设计 §8 风险 → Global Constraints 与 Task 5 Step 3 的"未完成"段。
- 设计 §9 后续落点 → Task 5 Step 4 的下一步落点。

**2. 占位符扫描**

- 无 TBD/TODO，无"稍后实现"。生成器给了**完整可运行**的脚本（含 `crop`/`generate` 两个入口与全部拒绝分支）。
- Task 4 Step 4 给了完整的渲染器；同时给出"若签名与本机不符"时用 `javap` 核对的确切命令——那是**有判据的条件动作**，不是含糊指令。
- Task 5 的 `<...>` 是模板占位，正文要求逐条替换为真实值。
- 生成物（`GoblinMaleModel.java` / `GoblinFemaleModel.java`）**不逐行贴出**，因为它们由脚本确定性产出；计划给出脚本、命令与验收断言。这是**生成物**，不是占位符。

**3. 类型一致性**

- `GoblinBodyModel(ModelPart root)` 在 Task 1 Step 2 定义；生成的 `GoblinMaleModel` / `GoblinFemaleModel` 都以它为父类并带**公开的** `(ModelPart)` 构造器（Task 1 Step 1 的模板里就是 `public X(ModelPart root)`）——Task 4 的 `new GoblinMaleModel(context.bakeLayer(...))` 依赖这一点。
- `GoblinMaleModel.createLayer()` / `GoblinFemaleModel.createLayer()` 由生成器产出，Task 4 Step 3 以方法引用注册。
- `ResidentRecord.looksFemale()` 在 Task 3 Step 3 定义、Step 1 的检查使用、Step 4 的实体方法消费。
- `GoblinCitizenEntity.femaleForRender() -> boolean` 在 Task 3 Step 4 定义，Task 4 Step 4 的 `extractRenderState` 消费。
- `GoblinRenderState.female` 在 Task 3 Step 5 定义，Task 4 Step 4 的 `submit` 与 `getTextureLocation` 消费。
- `GoblinSettlementClient.GOBLIN_MALE_LAYER` / `GOBLIN_FEMALE_LAYER` 在 Task 4 Step 3 定义、Step 4 消费。
- `artModelCheck` 在 Task 1 Step 5 注册，Step 6 与 Task 2 Step 2 运行。
- 生成器的 `Emitter.walk(node, feed, parent, indent)` 四个形参在三处调用点（根循环、`turned` 之后的 `nested` 递归、自身递归）一致。
