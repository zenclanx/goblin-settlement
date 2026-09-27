package dev.local.goblinsettlement.planning.transport;

import java.util.LinkedHashSet;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Where a road's lanes sit relative to its centerline, and which cells a widening adds. This is the
 * one authority on that question: the initial build and every widening ask here, so two callers can
 * never disagree about which cell a lane occupies.
 */
public final class RoadLayout {
    private static final int TRAIL_LANES = 2;
    private static final int ROAD_LANES = 3;
    private static final int STREET_LANES = 5;
    private static final int[] TRAIL_OFFSETS = {0, 1};
    private static final int[] ROAD_OFFSETS = {0, 1, 2};
    private static final int[] STREET_OFFSETS = {-1, 0, 1, 2, 3};

    private RoadLayout() {
    }

    /**
     * The lane offsets of a road this wide, in centerline-relative steps along the clockwise side. A
     * width between rungs (a hand-edited save, or a width the ladder skips) takes the rung below it.
     */
    public static int[] laneOffsets(int lanes) {
        if (lanes >= STREET_LANES) {
            return STREET_OFFSETS.clone();
        }
        if (lanes >= ROAD_LANES) {
            return ROAD_OFFSETS.clone();
        }
        return TRAIL_OFFSETS.clone();
    }

    /** The step from one centerline cell to the next; the last cell repeats the previous step. */
    public static int[] direction(List<BlockPos> route, int index) {
        BlockPos from = route.get(index == route.size() - 1 && index > 0 ? index - 1 : index);
        BlockPos to = route.get(index == route.size() - 1 && index > 0
                ? index : Math.min(index + 1, route.size() - 1));
        int dx = Integer.compare(to.getX(), from.getX());
        int dz = Integer.compare(to.getZ(), from.getZ());
        if (dx == 0 && dz == 0) {
            return new int[] {0, -1};
        }
        return new int[] {dx, dz};
    }

    /** A quarter turn clockwise: Direction.getClockWise() is (dx, dz) -> (-dz, dx). */
    public static int[] clockwise(int[] direction) {
        return new int[] {-direction[1], direction[0]};
    }

    /** Every walking cell of this road, in centerline order, each cell's offsets in table order. */
    public static List<BlockPos> laneFeet(List<BlockPos> route, int lanes) {
        int[] offsets = laneOffsets(lanes);
        var feet = new LinkedHashSet<BlockPos>();
        for (int index = 0; index < route.size(); index++) {
            int[] side = clockwise(direction(route, index));
            for (int offset : offsets) {
                feet.add(route.get(index).offset(side[0] * offset, 0, side[1] * offset));
            }
        }
        return List.copyOf(feet);
    }

    /**
     * The cells a widening from fromLanes to toLanes adds: the lanes the wider shape has and the
     * narrower one does not. Cells the corner already covered are not added twice, so the widened road
     * is whole without the added list being one lane per centerline cell all the way along.
     */
    public static List<BlockPos> newLaneFeet(List<BlockPos> route, int fromLanes, int toLanes) {
        var existing = new LinkedHashSet<>(laneFeet(route, fromLanes));
        var added = new LinkedHashSet<BlockPos>();
        for (BlockPos foot : laneFeet(route, toLanes)) {
            if (!existing.contains(foot)) {
                added.add(foot);
            }
        }
        return List.copyOf(added);
    }
}
