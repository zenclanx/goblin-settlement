package dev.local.goblinsettlement.planning.transport;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;

public final class RoadLayoutCheck {
    public static void main(String[] args) {
        checkDirectionAxes();
        checkClockwiseTurns();
        checkDirectionsRepeatAtTheEnd();
        checkLaneOffsetsPerRung();
        checkOffsetsMatchTheLadder();
        checkTrailLanesSitBesideTheCenterline();
        checkWideningToThreeAddsOneSide();
        checkWideningToFiveReCenters();
        checkBendsKeepTheWidenedShapeWhole();
        checkNoCellIsAddedTwice();
        checkSingleCellRoute();
        System.out.println("RoadLayoutCheck passed");
    }

    private static void checkDirectionAxes() {
        List<BlockPos> east = List.of(point(0, 64, 0), point(1, 64, 0));
        require(Arrays.equals(RoadLayout.direction(east, 0), new int[] {1, 0}), "east is +x");
        List<BlockPos> south = List.of(point(0, 64, 0), point(0, 64, 1));
        require(Arrays.equals(RoadLayout.direction(south, 0), new int[] {0, 1}), "south is +z");
    }

    private static void checkClockwiseTurns() {
        require(Arrays.equals(RoadLayout.clockwise(new int[] {0, -1}), new int[] {1, 0}),
                "north turns to east");
        require(Arrays.equals(RoadLayout.clockwise(new int[] {1, 0}), new int[] {0, 1}),
                "east turns to south");
        require(Arrays.equals(RoadLayout.clockwise(new int[] {0, 1}), new int[] {-1, 0}),
                "south turns to west");
        require(Arrays.equals(RoadLayout.clockwise(new int[] {-1, 0}), new int[] {0, -1}),
                "west turns to north");
    }

    private static void checkDirectionsRepeatAtTheEnd() {
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0), point(2, 64, 0));
        require(Arrays.equals(RoadLayout.direction(route, 2), new int[] {1, 0}),
                "the last cell repeats the previous step instead of looking past the end");
    }

    private static void checkLaneOffsetsPerRung() {
        require(RoadLayout.laneOffsets(2).length == 2, "a trail has two lanes");
        require(RoadLayout.laneOffsets(3).length == 3, "a road has three lanes");
        require(RoadLayout.laneOffsets(5).length == 5, "a main street has five lanes");
        require(Arrays.equals(RoadLayout.laneOffsets(2), new int[] {0, 1}), "the trail keeps the centerline");
        require(Arrays.equals(RoadLayout.laneOffsets(5), new int[] {-1, 0, 1, 2, 3}),
                "the street is re-centered around the original pair");
    }

    /** The offsets table and the widening ladder are separate facts; this is the tie between them. */
    private static void checkOffsetsMatchTheLadder() {
        int[] ladder = RoadUpgradeRules.ladder();
        require(Arrays.equals(ladder, new int[] {2, 3, 5}), "the ladder is the three documented widths");
        for (int rung : ladder) {
            require(RoadLayout.laneOffsets(rung).length == rung,
                    "a road of " + rung + " lanes has " + rung + " offsets");
        }
        require(RoadUpgradeRules.baseLanes() == ladder[0], "the built width is the first rung");
        require(RoadLayout.laneOffsets(4).length == 3, "a width between rungs takes the rung below it");
        require(RoadLayout.laneOffsets(0).length == 2, "a nonsense width still lands on a real shape");
    }

    private static void checkTrailLanesSitBesideTheCenterline() {
        // Eastward centerline: the clockwise side is south, so the lanes sit at z and z+1.
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0));
        List<BlockPos> feet = RoadLayout.laneFeet(route, 2);
        require(feet.equals(List.of(point(0, 64, 0), point(0, 64, 1), point(1, 64, 0), point(1, 64, 1))),
                "each centerline cell is paired with its clockwise neighbour, in order");
    }

    private static void checkWideningToThreeAddsOneSide() {
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0));
        List<BlockPos> added = RoadLayout.newLaneFeet(route, 2, 3);
        require(added.equals(List.of(point(0, 64, 2), point(1, 64, 2))),
                "two to three lanes adds exactly one lane, on the offset-two side");
    }

    private static void checkWideningToFiveReCenters() {
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0));
        List<BlockPos> added = RoadLayout.newLaneFeet(route, 3, 5);
        require(added.equals(List.of(point(0, 64, -1), point(0, 64, 3),
                        point(1, 64, -1), point(1, 64, 3))),
                "three to five lanes adds one lane on each side");
    }

    private static void checkBendsKeepTheWidenedShapeWhole() {
        // East for two cells, then south for two: the corner rotates the lane pair by a quarter turn.
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0),
                point(2, 64, 0), point(2, 64, 1), point(2, 64, 2));
        List<BlockPos> narrow = RoadLayout.laneFeet(route, 2);
        List<BlockPos> wide = RoadLayout.laneFeet(route, 3);
        List<BlockPos> added = RoadLayout.newLaneFeet(route, 2, 3);
        require(new HashSet<>(wide).containsAll(narrow), "the wider shape is a superset of the narrower");
        require(new HashSet<>(wide).equals(union(narrow, added)),
                "the wider shape is exactly the narrower shape plus the added cells");
        require(new HashSet<>(wide).size() == wide.size(), "the wider shape has no duplicate cells");
        require(added.size() < route.size(),
                "the corner already covers part of the wider shape, so fewer cells are added "
                        + "than the one-cell-per-centerline-cell a naive widening would place");
    }

    private static void checkNoCellIsAddedTwice() {
        // A centerline that walks back over itself must not list the same new cell twice.
        List<BlockPos> route = List.of(point(0, 64, 0), point(1, 64, 0), point(1, 64, 1));
        List<BlockPos> added = RoadLayout.newLaneFeet(route, 2, 3);
        require(new HashSet<>(added).size() == added.size(), "an added cell appears once");
    }

    private static void checkSingleCellRoute() {
        List<BlockPos> route = List.of(point(5, 64, 5));
        require(Arrays.equals(RoadLayout.direction(route, 0), new int[] {0, -1}),
                "a single cell has no direction, so north is used");
        require(RoadLayout.laneFeet(route, 2).size() == 2, "it still yields its two lanes");
        require(RoadLayout.newLaneFeet(route, 2, 3).size() == 1, "and widening it adds the third");
    }

    private static HashSet<BlockPos> union(List<BlockPos> left, List<BlockPos> right) {
        var union = new HashSet<>(left);
        union.addAll(right);
        return union;
    }

    private static BlockPos point(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
