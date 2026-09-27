package dev.local.goblinsettlement.defense;

import java.util.Arrays;
import java.util.List;

public final class PatrolRulesCheck {
    public static void main(String[] args) {
        checkAnchorComesFirst();
        checkNearestFirstWithStableTies();
        checkDuplicatesAreFolded();
        checkEmptyFacilitiesLeavesTheAnchor();
        System.out.println("PatrolRulesCheck passed");
    }

    private static void checkAnchorComesFirst() {
        var route = PatrolRules.waypoints(List.of(point(10, 64, 10)), point(0, 64, 0));
        require(route.size() == 2, "the anchor and the one facility");
        require(Arrays.equals(route.get(0), point(0, 64, 0)), "the anchor is walked first");
    }

    private static void checkNearestFirstWithStableTies() {
        var route = PatrolRules.waypoints(
                List.of(point(5, 64, 0), point(-3, 64, 0), point(1, 64, 0), point(-1, 64, 0)),
                point(0, 64, 0));
        // Distances squared from the anchor: 1, 1, 9, 25. The equal pair breaks by x, so -1 precedes 1.
        require(Arrays.equals(route.get(1), point(-1, 64, 0)), "the nearest, left of the anchor, is second");
        require(Arrays.equals(route.get(2), point(1, 64, 0)), "an equal distance breaks by x");
        require(Arrays.equals(route.get(3), point(-3, 64, 0)), "then the next nearest");
        require(Arrays.equals(route.get(4), point(5, 64, 0)), "then the farthest");
    }

    private static void checkDuplicatesAreFolded() {
        var route = PatrolRules.waypoints(
                List.of(point(0, 64, 0), point(3, 64, 0), point(0, 64, 0)), point(0, 64, 0));
        require(route.size() == 2, "a facility on the anchor is not walked twice");
    }

    private static void checkEmptyFacilitiesLeavesTheAnchor() {
        var route = PatrolRules.waypoints(List.of(), point(4, 70, -2));
        require(route.size() == 1 && Arrays.equals(route.get(0), point(4, 70, -2)),
                "with no facilities the route is just the anchor");
    }

    private static int[] point(int x, int y, int z) {
        return new int[] {x, y, z};
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
