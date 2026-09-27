package dev.local.goblinsettlement.housing;

import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/** Pure two-axis housing rules. World state and inventory checks belong to the coordinators. */
public final class HousingRules {
    public static final int MAX_CAPACITY_TARGET = 2;
    public static final int MAX_QUALITY_TARGET = 2;
    /** A new bed binds to a home whose anchor bed is within this Chebyshev x/z radius. */
    public static final int BIND_RADIUS = 8;
    /** Used only before the blueprint file loads; construction does not run while it has not. */
    public static final int RESERVE_FALLBACK_BASIC = 8;
    public static final int RESERVE_FALLBACK_EXPANDED = 24;

    private HousingRules() {
    }

    public enum HomeAction { EXPAND_CAPACITY, IMPROVE_QUALITY, NONE }

    public record Step(int x, int y, int z, String block) {
    }

    /**
     * A bed shortage is the only thing that justifies spending planks on capacity, and only for a
     * home that can actually host another bed. A home walled in by its neighbours' beds keeps its
     * planks: raising its target would build a stage no resident could ever sleep in, and decorating
     * while residents go without beds is not what "house them first" means.
     */
    public static HomeAction decide(int beds, int occupiedSlots, int capacityTarget, int qualityTarget,
                                    int usedBedsNear) {
        if (beds < 0 || occupiedSlots < 0 || usedBedsNear < 0) {
            throw new IllegalArgumentException(
                    "Bed, occupied slot and nearby bed counts cannot be negative");
        }
        validate(capacityTarget, qualityTarget);
        if (needsCapacity(beds, occupiedSlots)) {
            return canGainCapacity(usedBedsNear, capacityTarget)
                    ? HomeAction.EXPAND_CAPACITY : HomeAction.NONE;
        }
        return qualityTarget < MAX_QUALITY_TARGET ? HomeAction.IMPROVE_QUALITY : HomeAction.NONE;
    }

    public static boolean needsCapacity(int beds, int occupiedSlots) {
        return beds < occupiedSlots + 1;
    }

    /**
     * The one definition of a usable bed: the head half, two air blocks of headroom above it, and
     * all three cells within reach of the settlement. Whoever counts beds gathers these three facts
     * and asks here, so the housing census, the family check and provisioning can never disagree.
     */
    public static boolean usableBedHead(boolean headHalf, boolean headroomClear, boolean columnPermitted) {
        return headHalf && headroomClear && columnPermitted;
    }

    /** Raising capacityTarget once would let this home host at least one more bed. */
    public static boolean canGainCapacity(int usedBeds, int capacityTarget) {
        return capacityTarget < MAX_CAPACITY_TARGET && usedBeds < capacityTarget + 2;
    }

    public static OptionalInt firstUnbuilt(List<Step> steps, Set<Step> built) {
        for (int index = 0; index < steps.size(); index++) {
            if (!built.contains(steps.get(index))) {
                return OptionalInt.of(index);
            }
        }
        return OptionalInt.empty();
    }

    /**
     * Beds within the same Chebyshev window the placement exclusion uses. Coordinates are plain
     * ints so the pure rules stay free of Minecraft types; callers convert their positions.
     */
    public static int bedsNear(List<int[]> heads, int ax, int ay, int az, int radius) {
        int count = 0;
        for (int[] head : heads) {
            if (Math.abs(head[0] - ax) <= radius
                    && Math.abs(head[2] - az) <= radius
                    && Math.abs(head[1] - ay) <= 4) {
                count++;
            }
        }
        return count;
    }

    /** Beds a home can host given the capacity geometry actually finished. */
    public static int builtCapacity(int capacityTarget, boolean stage1Built, boolean stage2Built) {
        int extra = 0;
        if (capacityTarget >= 1 && stage1Built) extra++;
        if (capacityTarget >= 2 && stage2Built) extra++;
        return 1 + extra;
    }

    private static void validate(int capacityTarget, int qualityTarget) {
        if (capacityTarget < 0 || capacityTarget > MAX_CAPACITY_TARGET
                || qualityTarget < 0 || qualityTarget > MAX_QUALITY_TARGET) {
            throw new IllegalArgumentException("Housing targets out of range");
        }
    }
}
