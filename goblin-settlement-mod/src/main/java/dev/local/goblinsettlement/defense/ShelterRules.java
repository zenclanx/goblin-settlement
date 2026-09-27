package dev.local.goblinsettlement.defense;

import java.util.List;

/** Pure shelter selection: which refuge a resident runs to. */
public final class ShelterRules {
    private ShelterRules() {
    }

    /** A candidate refuge: where it is, how many are already heading there, and how many it holds. */
    public record Shelter(int x, int y, int z, int used, int capacity) {
    }

    /**
     * The nearest refuge that still has room, or -1 when none has. Capacity is part of this rule rather
     * than a filter around it: "which refuge" and "is there space in it" are one question.
     */
    public static int choose(List<Shelter> shelters, int[] from) {
        int best = -1;
        for (int index = 0; index < shelters.size(); index++) {
            Shelter candidate = shelters.get(index);
            if (candidate.used() >= candidate.capacity()) {
                continue;
            }
            if (best < 0 || isCloser(candidate, shelters.get(best), from)) {
                best = index;
            }
        }
        return best;
    }

    /** Nearer wins; an exact tie goes to the smaller x, then the smaller z, so the choice repeats. */
    private static boolean isCloser(Shelter candidate, Shelter incumbent, int[] from) {
        int candidateDistance = distanceSquared(candidate, from);
        int incumbentDistance = distanceSquared(incumbent, from);
        if (candidateDistance != incumbentDistance) {
            return candidateDistance < incumbentDistance;
        }
        int byX = Integer.compare(candidate.x(), incumbent.x());
        return byX != 0 ? byX < 0 : candidate.z() < incumbent.z();
    }

    private static int distanceSquared(Shelter shelter, int[] from) {
        int dx = shelter.x() - from[0];
        int dy = shelter.y() - from[1];
        int dz = shelter.z() - from[2];
        return dx * dx + dy * dy + dz * dz;
    }
}
