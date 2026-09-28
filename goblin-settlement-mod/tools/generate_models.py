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


def scaled(point):
    """Unscaled model space -> rendered model space. A scaling about the ground line, so a point on
    the ground line (y == GROUND) stays there and the feet do not move."""
    return (point[0] * SCALE, GROUND + (point[1] - GROUND) * SCALE, point[2] * SCALE)


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
    """A part's PartPose, from its unscaled model-space position and a parent that is already in
    rendered model space. The mesh root of a Minecraft model is (0, 0, 0) and the ground line lives
    at y = GROUND inside it, so a top-level part has to carry the whole ground-line offset itself;
    below that, only the difference matters, and the difference of two scaled points is SCALE times
    the difference of the unscaled ones."""
    point = scaled(absolute)
    x = point[0] - parent[0]
    y = point[1] - parent[1]
    z = point[2] - parent[2]
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
        anchor = scaled(absolute)
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
                         pose(model_position(element["origin"]), anchor, (rx, ry, rz)))
        for child in nested:
            self.walk(child, variable, anchor, indent + "    ")


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
