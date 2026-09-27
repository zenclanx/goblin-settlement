package dev.local.goblinsettlement.defense;

import java.util.List;

/** Pure shelter selection: which refuge a resident runs to. */
public final class ShelterRules {
    private ShelterRules() {
    }

    /**
     * The nearest shelter's index, or -1 when there are none. Coordinates are plain ints so the pure
     * rules stay free of Minecraft types; callers convert their positions.
     */
    public static int nearest(List<int[]> shelters, int[] from) {
        int best = -1;
        for (int index = 0; index < shelters.size(); index++) {
            if (best < 0 || isCloser(shelters.get(index), shelters.get(best), from)) {
                best = index;
            }
        }
        return best;
    }

    /** Nearer wins; an exact tie goes to the smaller x, then the smaller z, so the choice repeats. */
    private static boolean isCloser(int[] candidate, int[] incumbent, int[] from) {
        int candidateDistance = distanceSquared(candidate, from);
        int incumbentDistance = distanceSquared(incumbent, from);
        if (candidateDistance != incumbentDistance) {
            return candidateDistance < incumbentDistance;
        }
        int byX = Integer.compare(candidate[0], incumbent[0]);
        return byX != 0 ? byX < 0 : candidate[2] < incumbent[2];
    }

    private static int distanceSquared(int[] point, int[] from) {
        int dx = point[0] - from[0];
        int dy = point[1] - from[1];
        int dz = point[2] - from[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
