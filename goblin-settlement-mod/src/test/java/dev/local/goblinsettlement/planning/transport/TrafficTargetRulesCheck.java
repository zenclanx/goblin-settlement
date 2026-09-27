package dev.local.goblinsettlement.planning.transport;

import java.util.List;

public final class TrafficTargetRulesCheck {
    public static void main(String[] args) {
        var anchor = new TrafficTargetRules.Facility(0, 70, 0);
        var near = new TrafficTargetRules.Facility(5, 70, 0);       // 25, inside the floor
        var farm = new TrafficTargetRules.Facility(15, 71, 5);      // 250
        var warehouse = new TrafficTargetRules.Facility(20, 70, 0); // 400
        long floor = 12L * 12L;
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(anchor, farm, warehouse), List.of(), floor)
                .orElseThrow().equals(farm), "the nearest qualifying facility wins");
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(near), List.of(), floor).isEmpty(),
                "facilities inside the minimum distance are not paved for");
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(farm, warehouse), List.of(farm), floor)
                .orElseThrow().equals(warehouse), "a served facility is skipped");
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(anchor), List.of(), floor).isEmpty(),
                "the settlement anchor never paves itself");
        var tied = new TrafficTargetRules.Facility(10, 70, 4);
        var tie = new TrafficTargetRules.Facility(4, 70, 10);
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(tied, tie), List.of(), floor)
                .orElseThrow().equals(tie), "equal distances break by x, then z, then y");
        require(TrafficTargetRules.nearestBeyond(anchor, List.of(), List.of(), floor).isEmpty(),
                "no facilities means no target");
        System.out.println("TrafficTargetRulesCheck passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
