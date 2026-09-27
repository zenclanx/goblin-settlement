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
                # Two-high doorway in the -z wall.
                if z == -1 and x == 0 and y in (0, 1):
                    continue
                steps.append((x, y, z))
    return steps


def side_porch_upper():
    return [(x, 4, 0) for x in range(-2, 3)]


def side_porch_eaves_a():
    return [(x, 4, 1) for x in range(-2, 3)] + [(x, 4, -1) for x in range(-2, 3)]


def side_porch_eaves_b():
    return [(x, 4, 2) for x in range(-2, 3)] + [(x, 4, -2) for x in range(-2, 3)]


def stage(stage_id, steps, requires=None):
    entry = {"id": stage_id}
    if requires is not None:
        entry["requires"] = requires
    entry["steps"] = [{"x": x, "y": y, "z": z, "block": BLOCK} for (x, y, z) in steps]
    return entry


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
    payload = {
        "reserve_basic": 8,
        "reserve_expanded": 24,
        "styles": [cottage, lean_to, side_porch],
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(payload, handle, indent=2)
        handle.write("\n")

    for style in payload["styles"]:
        print(style["id"], "capacity sizes:",
              [len(s["steps"]) for s in style["capacity"]],
              "quality sizes:", [len(s["steps"]) for s in style["quality"]])
    # The step counts the old HousingRulesCheck asserted. They are the proof that the
    # migration lost no geometry -- if these move, the cottage was edited by accident.
    print("COTTAGE MUST BE [21, 71, 10]:",
          [len(s["steps"]) for s in payload["styles"][0]["capacity"]])


if __name__ == "__main__":
    main()
