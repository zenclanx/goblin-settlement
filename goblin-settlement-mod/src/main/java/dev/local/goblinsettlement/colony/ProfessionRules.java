package dev.local.goblinsettlement.colony;

import java.util.List;

/** Pure dispatch rules: how strongly a profession matches a kind of work, and how fast it works. */
public final class ProfessionRules {
    private static final int MATCHING_INTERVAL_TICKS = 10;
    private static final int GENERALIST_INTERVAL_TICKS = 20;
    private static final int MISMATCHED_INTERVAL_TICKS = 30;

    private ProfessionRules() {
    }

    /**
     * Lower sorts first. A matching specialist beats a generalist, and a generalist beats a specialist
     * dragged away from their own trade. SENTRY currently has no work of its own, so it stays a generalist
     * until sentry jobs exist.
     */
    public static int matchRank(WorkKind kind, Profession resident) {
        if (resident == kind.required()) {
            return 0;
        }
        return WorkKind.employs(resident) ? 2 : 1;
    }

    /** Ticks between work steps. All values are multiples of the entity's 10-tick registration cadence. */
    public static int workIntervalTicks(WorkKind kind, Profession resident) {
        return switch (matchRank(kind, resident)) {
            case 0 -> MATCHING_INTERVAL_TICKS;
            case 1 -> GENERALIST_INTERVAL_TICKS;
            default -> MISMATCHED_INTERVAL_TICKS;
        };
    }

    /** The profession the fewest adults currently hold. Ties fall back to enum order so results repeat. */
    public static Profession scarcest(List<Profession> assignedAdults) {
        Profession best = Profession.FARMER;
        int fewest = Integer.MAX_VALUE;
        for (Profession candidate : Profession.values()) {
            if (candidate == Profession.UNASSIGNED) {
                continue;
            }
            int count = 0;
            for (Profession assigned : assignedAdults) {
                if (assigned == candidate) {
                    count++;
                }
            }
            if (count < fewest) {
                fewest = count;
                best = candidate;
            }
        }
        return best;
    }
}
