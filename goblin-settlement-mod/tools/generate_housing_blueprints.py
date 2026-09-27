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
