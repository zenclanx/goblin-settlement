package dev.local.goblinsettlement.defense;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure patrol route rules: which points a sentry walks, and in what order. */
public final class PatrolRules {
    private PatrolRules() {
    }

    /**
     * The anchor first, then the facilities nearest-first. Ties break by x then z so the order is
     * stable across saves, and identical points are folded into one -- a warehouse inside a farm
     * would otherwise be visited twice in a row.
     */
    public static List<int[]> waypoints(List<int[]> facilities, int[] anchor) {
        Set<String> seen = new LinkedHashSet<>();
        List<int[]> route = new ArrayList<>();
        List<int[]> candidates = new ArrayList<>();
        candidates.add(anchor);
        candidates.addAll(facilities);
        for (int[] point : candidates) {
            if (seen.add(point[0] + ":" + point[1] + ":" + point[2])) {
                route.add(point);
            }
        }
        route.sort((first, second) -> {
            int byDistance = Integer.compare(distanceSquared(first, anchor),
                    distanceSquared(second, anchor));
            if (byDistance != 0) {
                return byDistance;
            }
            int byX = Integer.compare(first[0], second[0]);
            return byX != 0 ? byX : Integer.compare(first[2], second[2]);
        });
        return List.copyOf(route);
    }

    private static int distanceSquared(int[] point, int[] anchor) {
        int dx = point[0] - anchor[0];
        int dy = point[1] - anchor[1];
        int dz = point[2] - anchor[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
