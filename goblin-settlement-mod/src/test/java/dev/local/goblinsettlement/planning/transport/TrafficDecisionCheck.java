package dev.local.goblinsettlement.planning.transport;

import java.util.ArrayList;
import java.util.List;

public final class TrafficDecisionCheck {
    private static final int MIN_SPAN = 4;
    private static final int MAX_SPAN = 12;

    public static void main(String[] args) {
        require(decide(line("L L L L L"), 4).kind() == TrafficDecision.Kind.ROAD,
                "clear land needs a road, not a bridge");
        require(decide(line("L L W W W W W W L L L"), 10).kind() == TrafficDecision.Kind.BRIDGE,
                "a six-column water gap proposes a bridge");
        var bridge = decide(line("L L W W W W W W L L L"), 10);
        require(bridge.gapStartInclusive() == 2 && bridge.gapEndInclusive() == 7,
                "gap indexes cover exactly the water columns");
        require(decide(line("L L W W W L"), 5).kind() == TrafficDecision.Kind.ROAD,
                "a three-column gap is too short for a bridge");
        require(decide(line("L W W W W L"), 5).kind() == TrafficDecision.Kind.BRIDGE,
                "the four-column lower bound still bridges");
        require(decide(line("L W W W W W W W W W W W W L"), 13).kind() == TrafficDecision.Kind.BRIDGE,
                "the twelve-column upper bound still bridges");
        require(decide(line("L W W W W W W W W W W W W W L"), 14).kind() == TrafficDecision.Kind.ROAD,
                "a thirteen-column gap exceeds the wooden bridge");
        require(decide(line("W W W L L"), 4).kind() == TrafficDecision.Kind.ROAD,
                "water at the anchor has no near bank");
        require(decide(line("L W W W W W W"), 6).kind() == TrafficDecision.Kind.ROAD,
                "a target standing in water cannot be served by the corridor");
        require(decide(line("L L B L L"), 4).kind() == TrafficDecision.Kind.ROAD,
                "an obstructed target column delegates to the road planner");
        require(decide(line("B W W W W W L"), 6).kind() == TrafficDecision.Kind.ROAD,
                "a blocked near bank cannot host a landing");
        require(decide(line("L W W W W W B L"), 7).kind() == TrafficDecision.Kind.ROAD,
                "a blocked far bank cannot host a landing");
        require(decide(line("L W W W L W W W W W W L"), 11).kind() == TrafficDecision.Kind.ROAD,
                "only the first water run decides: a narrow run does not bridge later runs");
        require(decide(line("L W W W W W L W W W W W L"), 12).kind() == TrafficDecision.Kind.BRIDGE,
                "the first bridgeable run wins when several cross the line");
        require(decide(List.of(), 0).kind() == TrafficDecision.Kind.NONE,
                "an empty sample carries no decision");
        require(decide(line("L L L"), 5).kind() == TrafficDecision.Kind.NONE,
                "an out-of-range target index carries no decision");
        checkSpanRungsPartitionTheWater();
        System.out.println("TrafficDecisionCheck passed");
    }

    /**
     * The two bridge rungs must partition the water widths without overlapping: a 12-wide run is the
     * wooden bridge's last, and a 13-wide run is the stone bridge's first.
     */
    private static void checkSpanRungsPartitionTheWater() {
        require(TrafficDecision.decide(waterRun(12), 13, 4, 12).kind() == TrafficDecision.Kind.BRIDGE,
                "twelve columns is a wooden bridge");
        require(TrafficDecision.decide(waterRun(12), 13, 13, 24).kind() == TrafficDecision.Kind.ROAD,
                "and not a stone one");
        require(TrafficDecision.decide(waterRun(13), 14, 4, 12).kind() == TrafficDecision.Kind.ROAD,
                "thirteen columns is past the wooden rung");
        require(TrafficDecision.decide(waterRun(13), 14, 13, 24).kind() == TrafficDecision.Kind.BRIDGE,
                "and is the stone bridge's first");
        require(TrafficDecision.decide(waterRun(24), 25, 13, 24).kind() == TrafficDecision.Kind.BRIDGE,
                "twenty-four columns is still a stone bridge");
        require(TrafficDecision.decide(waterRun(25), 26, 13, 24).kind() == TrafficDecision.Kind.ROAD,
                "twenty-five is beyond both rungs, so the corridor falls back to the road rule");
    }

    /** A straight-line sample: land, then this many water columns, then land, with the target on land. */
    private static List<TrafficDecision.ColumnKind> waterRun(int water) {
        return line("L " + "W ".repeat(water) + "L");
    }

    private static TrafficDecision.Decision decide(List<TrafficDecision.ColumnKind> columns, int targetIndex) {
        return TrafficDecision.decide(columns, targetIndex, MIN_SPAN, MAX_SPAN);
    }

    private static List<TrafficDecision.ColumnKind> line(String spec) {
        var columns = new ArrayList<TrafficDecision.ColumnKind>();
        for (String token : spec.split(" ")) {
            columns.add(switch (token) {
                case "L" -> TrafficDecision.ColumnKind.LAND;
                case "W" -> TrafficDecision.ColumnKind.WATER;
                case "B" -> TrafficDecision.ColumnKind.BLOCKED;
                default -> throw new IllegalArgumentException("Unknown column kind: " + token);
            });
        }
        return columns;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
