package dev.local.goblinsettlement.defense;

import java.util.List;

public final class ShelterRulesCheck {
    public static void main(String[] args) {
        checkNearestWithRoomWins();
        checkFullShelterIsPassedOver();
        checkAllFull();
        checkTiesBreakByXThenZ();
        checkNoShelters();
        System.out.println("ShelterRulesCheck passed");
    }

    private static void checkNearestWithRoomWins() {
        var shelters = List.of(shelter(20, 64, 0, 0, 2), shelter(3, 64, 0, 0, 2), shelter(-9, 64, 0, 0, 2));
        require(ShelterRules.choose(shelters, point(0, 64, 0)) == 1, "the closest refuge wins");
    }

    private static void checkFullShelterIsPassedOver() {
        // The closest is full, so the next one out takes the resident.
        var shelters = List.of(shelter(3, 64, 0, 2, 2), shelter(9, 64, 0, 0, 2));
        require(ShelterRules.choose(shelters, point(0, 64, 0)) == 1, "a full refuge is skipped");
    }

    private static void checkAllFull() {
        var shelters = List.of(shelter(3, 64, 0, 1, 1), shelter(9, 64, 0, 3, 3));
        require(ShelterRules.choose(shelters, point(0, 64, 0)) == -1, "no room anywhere means no choice");
    }

    private static void checkTiesBreakByXThenZ() {
        var byX = List.of(shelter(4, 64, 0, 0, 1), shelter(-4, 64, 0, 0, 1));
        require(ShelterRules.choose(byX, point(0, 64, 0)) == 1, "an equal distance goes to the smaller x");
        var byZ = List.of(shelter(0, 64, 4, 0, 1), shelter(0, 64, -4, 0, 1));
        require(ShelterRules.choose(byZ, point(0, 64, 0)) == 1, "then to the smaller z");
    }

    private static void checkNoShelters() {
        require(ShelterRules.choose(List.of(), point(0, 64, 0)) == -1, "no refuges means no choice");
        require(ShelterRules.choose(List.of(shelter(1, 64, 1, 0, 1)), point(0, 64, 0))
                        == ShelterRules.choose(List.of(shelter(1, 64, 1, 0, 1)), point(0, 64, 0)),
                "the same inputs always give the same answer");
    }

    private static ShelterRules.Shelter shelter(int x, int y, int z, int used, int capacity) {
        return new ShelterRules.Shelter(x, y, z, used, capacity);
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
