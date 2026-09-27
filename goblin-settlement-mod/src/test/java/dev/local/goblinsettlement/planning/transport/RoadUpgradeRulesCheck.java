package dev.local.goblinsettlement.planning.transport;

public final class RoadUpgradeRulesCheck {
    public static void main(String[] args) {
        checkLadderAdvances();
        checkLadderStopsAtTheWidest();
        checkThresholdBoundary();
        checkWiderRoadsAskForMore();
        checkTheWidestStreetNeverUpgrades();
        checkNonWidthsNeverUpgrade();
        checkBuiltWidthIsOnTheLadder();
        System.out.println("RoadUpgradeRulesCheck passed");
    }

    private static void checkLadderAdvances() {
        require(RoadUpgradeRules.nextLanes(2) == 3, "a trail widens to a road");
        require(RoadUpgradeRules.nextLanes(3) == 5, "a road widens to a main street");
        require(RoadUpgradeRules.nextLanes(1) == 2, "anything narrower lands on the first rung");
        require(RoadUpgradeRules.nextLanes(4) == 5, "a width between rungs lands on the next one up");
    }

    private static void checkLadderStopsAtTheWidest() {
        require(RoadUpgradeRules.nextLanes(5) == 5, "the widest street has nothing above it");
        require(RoadUpgradeRules.nextLanes(9) == 5, "a wider width clamps to the widest");
    }

    private static void checkThresholdBoundary() {
        int required = RoadUpgradeRules.TRAFFIC_PER_LANE;
        require(RoadUpgradeRules.shouldUpgrade(2, required), "exactly the threshold is enough");
        require(!RoadUpgradeRules.shouldUpgrade(2, required - 1), "one sample short is not enough");
    }

    private static void checkWiderRoadsAskForMore() {
        int required = RoadUpgradeRules.TRAFFIC_PER_LANE;
        require(RoadUpgradeRules.shouldUpgrade(3, required * 2), "a three-lane road asks for two shares");
        require(!RoadUpgradeRules.shouldUpgrade(3, required * 2 - 1), "and not one sample less");
        require(RoadUpgradeRules.shouldUpgrade(1, required), "a one-lane trail still asks for one share");
        require(!RoadUpgradeRules.shouldUpgrade(1, required - 1), "never less than one share");
    }

    private static void checkTheWidestStreetNeverUpgrades() {
        require(!RoadUpgradeRules.shouldUpgrade(5, Integer.MAX_VALUE), "the widest street is final");
    }

    private static void checkNonWidthsNeverUpgrade() {
        require(!RoadUpgradeRules.shouldUpgrade(0, Integer.MAX_VALUE), "zero lanes is not a road");
        require(!RoadUpgradeRules.shouldUpgrade(-1, Integer.MAX_VALUE), "a negative width is not a road");
    }

    private static void checkBuiltWidthIsOnTheLadder() {
        require(RoadUpgradeRules.BUILT_ROAD_LANES == 2, "roads are built two lanes wide today");
        require(RoadUpgradeRules.nextLanes(RoadUpgradeRules.BUILT_ROAD_LANES) == 3,
                "so the first widening step goes to three");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
