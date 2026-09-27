package dev.local.goblinsettlement.housing;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class HousingRulesCheck {
    public static void main(String[] args) {
        checkDecide();
        checkSteps();
        checkFirstUnbuilt();
        checkCounts();
        checkUsableBedHead();
        checkBedsNear();
        checkHomeCodec();
        System.out.println("HousingRulesCheck passed");
    }

    private static void checkDecide() {
        require(HousingRules.decide(8, 8, 0, 0) == HousingRules.HomeAction.EXPAND_CAPACITY,
                "a bed shortage expands capacity first");
        require(HousingRules.decide(8, 8, 1, 0) == HousingRules.HomeAction.EXPAND_CAPACITY,
                "a shortage keeps expanding while capacity remains");
        require(HousingRules.decide(8, 8, 2, 0) == HousingRules.HomeAction.IMPROVE_QUALITY,
                "a shortage with capacity maxed turns to quality instead of stalling");
        require(HousingRules.decide(8, 8, 2, 2) == HousingRules.HomeAction.NONE,
                "nothing left to raise");
        require(HousingRules.decide(9, 8, 0, 0) == HousingRules.HomeAction.IMPROVE_QUALITY,
                "surplus beds never expand capacity");
        require(HousingRules.decide(9, 8, 2, 2) == HousingRules.HomeAction.NONE, "fully upgraded");
        boolean threw = false;
        try {
            HousingRules.decide(-1, 0, 0, 0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        require(threw, "negative counts are rejected");
    }

    private static void checkSteps() {
        require(HousingRules.steps(0, 0).size() == 21, "shelter only");
        require(HousingRules.steps(1, 0).size() == 92, "shelter plus cabin");
        require(HousingRules.steps(2, 0).size() == 102, "all three capacity stages");
        require(HousingRules.steps(2, 1).size() == 111, "capacity plus the first quality stage");
        require(HousingRules.steps(2, 2).size() == 121, "everything");
        require(HousingRules.steps(1, 1).equals(HousingRules.steps(1, 1)),
                "the same targets always compose the same list");
        for (var step : HousingRules.steps(2, 2)) {
            require(step.x() >= -3 && step.x() <= 3 && step.z() >= -2 && step.z() <= 2
                    && step.y() >= 0 && step.y() <= 4, "every step stays inside the blueprint box");
        }
    }

    private static void checkFirstUnbuilt() {
        List<HousingRules.Step> shelter = HousingRules.steps(0, 0);
        require(HousingRules.firstUnbuilt(shelter, Set.of()).orElseThrow() == 0,
                "an untouched home starts at the first step");
        Set<HousingRules.Step> half = new HashSet<>(shelter.subList(0, 5));
        require(HousingRules.firstUnbuilt(shelter, half).orElseThrow() == 5,
                "built leading steps are skipped");
        require(HousingRules.firstUnbuilt(shelter, new HashSet<>(shelter)).isEmpty(),
                "a fully built home has nothing left");
        // The regression this design exists for: quality steps are built first, then the capacity
        // target rises. The newly inserted capacity steps sit BEFORE the built quality ones, so a
        // stored cursor would skip them; deriving from the world finds them.
        Set<HousingRules.Step> builtEarly = new HashSet<>(HousingRules.steps(1, 1));
        int next = HousingRules.firstUnbuilt(HousingRules.steps(2, 1), builtEarly).orElseThrow();
        require(next == 92, "raising the capacity target after quality work finds the inserted steps");
        require(HousingRules.steps(2, 1).get(next) == HousingRules.steps(2, 0).get(21 + 71),
                "the step found is the first expanded-stage step");
    }

    private static void checkCounts() {
        require(HousingRules.needsCapacity(8, 8), "eight beds cannot seat eight residents plus a spare");
        require(!HousingRules.needsCapacity(9, 8), "a spare bed ends the shortage");
        require(HousingRules.canGainCapacity(1, 0), "a shelter can grow");
        require(HousingRules.canGainCapacity(2, 1), "a cabin can grow");
        require(!HousingRules.canGainCapacity(3, 1), "a cabin already full cannot grow");
        require(!HousingRules.canGainCapacity(2, 2), "a maxed home cannot grow");
        require(!HousingRules.canGainCapacity(8, 0), "a camp with eight beds near one anchor cannot grow");
        require(HousingRules.builtCapacity(0, false, false) == 1, "a shelter hosts one bed");
        require(HousingRules.builtCapacity(1, true, false) == 2, "a finished cabin hosts two");
        require(HousingRules.builtCapacity(2, true, true) == 3, "a finished extension hosts three");
    }

    private static void checkUsableBedHead() {
        // Every leg must be decisive on its own: dropping any one of them is exactly how the three
        // former copies of this rule would have drifted apart.
        require(HousingRules.usableBedHead(true, true, true),
                "a permitted bed head with two blocks of headroom is usable");
        require(!HousingRules.usableBedHead(false, true, true),
                "a bed half that is not the head is not usable");
        require(!HousingRules.usableBedHead(true, false, true),
                "a head without two air blocks above it is not usable");
        require(!HousingRules.usableBedHead(true, true, false),
                "a head the settlement may not modify is not usable");
    }

    private static void checkBedsNear() {
        require(HousingRules.bedsNear(List.of(
                new int[] {9, 0, 0}, new int[] {8, 0, 0}, new int[] {0, 8, 0},
                new int[] {0, 0, 4}, new int[] {0, 0, 5}, new int[] {2, 2, 2}), 0, 0, 0, 8) == 4,
                "only beds inside the Chebyshev x/z radius with |dy| <= 4 count");
        require(HousingRules.bedsNear(List.of(
                new int[] {18, 20, 30}, new int[] {19, 20, 30}, new int[] {10, 24, 30},
                new int[] {10, 25, 30}, new int[] {10, 20, 22}, new int[] {10, 20, 21}),
                10, 20, 30, 8) == 3, "the window follows the anchor, not the origin");
        require(HousingRules.bedsNear(List.of(), 0, 0, 0, 8) == 0, "no heads, no count");
    }

    private static void checkHomeCodec() {
        var bed = new net.minecraft.core.BlockPos(10, 64, 10);
        var modern = new HousingSavedData.Home(bed, 1, 2, 1, java.util.Optional.empty());
        var json = HousingSavedData.Home.CODEC
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, modern).getOrThrow();
        require(!json.getAsJsonObject().has("stage"),
                "the legacy stage key is never written back");
        require(!json.getAsJsonObject().has("step"),
                "the dropped cursor is never written back");
        var reloaded = HousingSavedData.Home.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, json).getOrThrow();
        require(reloaded.capacityTarget() == 2 && reloaded.qualityTarget() == 1,
                "two-axis targets survive a round trip");

        int[][] expected = {{0, 0}, {1, 0}, {2, 0}, {2, 1}, {2, 2}, {2, 2}};
        for (int stage = 0; stage <= 5; stage++) {
            var legacy = json.getAsJsonObject().deepCopy();
            legacy.remove("capacity_target");
            legacy.remove("quality_target");
            legacy.addProperty("stage", stage);
            legacy.addProperty("step", 7);
            var migrated = HousingSavedData.Home.CODEC
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, legacy).getOrThrow();
            require(migrated.capacityTarget() == expected[stage][0]
                            && migrated.qualityTarget() == expected[stage][1],
                    "legacy stage " + stage + " maps onto the two axes");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
