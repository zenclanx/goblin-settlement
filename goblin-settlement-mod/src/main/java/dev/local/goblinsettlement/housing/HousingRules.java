package dev.local.goblinsettlement.housing;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/** Pure two-axis housing rules. World state and inventory checks belong to the coordinators. */
public final class HousingRules {
    public static final int MAX_CAPACITY_TARGET = 2;
    public static final int MAX_QUALITY_TARGET = 2;
    /** A new bed binds to a home whose anchor bed is within this Chebyshev x/z radius. */
    public static final int BIND_RADIUS = 8;
    public static final int RESERVE_EXPANDED = 24;
    public static final int RESERVE_BASIC = 8;

    private HousingRules() {
    }

    public enum HomeAction { EXPAND_CAPACITY, IMPROVE_QUALITY, NONE }

    public record Step(int x, int y, int z) {
    }

    private static final List<List<Step>> STAGES = blueprints();

    public static List<List<Step>> stages() {
        return STAGES;
    }

    /** A bed shortage is the only thing that justifies spending planks on capacity. */
    public static HomeAction decide(int beds, int occupiedSlots, int capacityTarget, int qualityTarget) {
        if (beds < 0 || occupiedSlots < 0) {
            throw new IllegalArgumentException("Bed and occupied slot counts cannot be negative");
        }
        validate(capacityTarget, qualityTarget);
        if (needsCapacity(beds, occupiedSlots) && capacityTarget < MAX_CAPACITY_TARGET) {
            return HomeAction.EXPAND_CAPACITY;
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

    /** The capacity axis builds its stages first, then the quality axis. Order never varies. */
    public static List<Step> steps(int capacityTarget, int qualityTarget) {
        validate(capacityTarget, qualityTarget);
        List<Step> result = new ArrayList<>();
        for (int capacity = 0; capacity <= capacityTarget; capacity++) {
            result.addAll(STAGES.get(capacity));
        }
        for (int quality = 1; quality <= qualityTarget; quality++) {
            result.addAll(STAGES.get(MAX_CAPACITY_TARGET + quality));
        }
        return List.copyOf(result);
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

    private static List<List<Step>> blueprints() {
        // Moved from HousingCoordinator.blueprints(): shelter, cabin, expanded, quality, mature.
        var shelter = new ArrayList<Step>();
        for (int x : new int[] {-1, 1}) for (int z : new int[] {-1, 1})
            for (int y = 0; y <= 2; y++) shelter.add(new Step(x, y, z));
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            shelter.add(new Step(x, 3, z));
        var cabin = new ArrayList<Step>();
        for (int y = 0; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                cabin.add(new Step(x, y, -2));
                if (x != 0 || y == 2) cabin.add(new Step(x, y, 2));
            }
            for (int z = -1; z <= 1; z++) {
                cabin.add(new Step(-2, y, z));
                cabin.add(new Step(2, y, z));
            }
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            cabin.add(new Step(x, 3, z));
        var expanded = new ArrayList<Step>();
        for (int z = -1; z <= 1; z++) {
            expanded.add(new Step(3, 0, z));
            expanded.add(new Step(3, 3, z));
        }
        for (int y = 1; y <= 2; y++) {
            expanded.add(new Step(3, y, -1));
            expanded.add(new Step(3, y, 1));
        }
        var quality = new ArrayList<Step>();
        for (int x = -2; x <= 2; x++) quality.add(new Step(x, 4, 0));
        for (int z = -2; z <= 2; z++) {
            // The cross shares its center block (0,4,0), already added above; skip the duplicate so
            // the stage holds exactly the nine quality blocks the design counts.
            if (z != 0) quality.add(new Step(0, 4, z));
        }
        var mature = new ArrayList<Step>();
        for (int z = -2; z <= 2; z++) mature.add(new Step(-3, 0, z));
        for (int x = -2; x <= 2; x++) mature.add(new Step(x, 4, -2));
        return List.of(List.copyOf(shelter), List.copyOf(cabin), List.copyOf(expanded),
                List.copyOf(quality), List.copyOf(mature));
    }
}
