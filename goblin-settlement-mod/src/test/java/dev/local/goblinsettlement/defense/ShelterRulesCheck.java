package dev.local.goblinsettlement.defense;

import java.util.List;

public final class ShelterRulesCheck {
    public static void main(String[] args) {
        checkNearestWins();
        checkTiesBreakByXThenZ();
        checkNoShelters();
        checkSingleShelter();
        System.out.println("ShelterRulesCheck passed");
    }

    private static void checkNearestWins() {
        var shelters = List.of(point(20, 64, 0), point(3, 64, 0), point(-9, 64, 0));
        require(ShelterRules.nearest(shelters, point(0, 64, 0)) == 1,
                "the closest refuge wins");
    }

    private static void checkTiesBreakByXThenZ() {
        // Equal distance from the origin; the smaller x must win regardless of list order.
        var byX = List.of(point(4, 64, 0), point(-4, 64, 0));
        require(ShelterRules.nearest(byX, point(0, 64, 0)) == 1, "an equal distance goes to the smaller x");
        var byZ = List.of(point(0, 64, 4), point(0, 64, -4));
        require(ShelterRules.nearest(byZ, point(0, 64, 0)) == 1, "then to the smaller z");
    }

    private static void checkNoShelters() {
        require(ShelterRules.nearest(List.of(), point(0, 64, 0)) == -1,
                "no refuges means no choice");
    }

    private static void checkSingleShelter() {
        var shelters = List.of(point(7, 64, 7));
        require(ShelterRules.nearest(shelters, point(0, 64, 0)) == 0, "one refuge is always the answer");
        require(ShelterRules.nearest(shelters, point(0, 64, 0))
                        == ShelterRules.nearest(shelters, point(0, 64, 0)),
                "the same inputs always give the same answer");
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
