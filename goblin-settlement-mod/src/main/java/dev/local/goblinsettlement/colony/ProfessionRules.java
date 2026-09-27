package dev.local.goblinsettlement.colony;

import java.util.List;
import java.util.Optional;

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

    /**
     * How many adults may hold this trade. Only the sentry has a documented ceiling: GAME_DESIGN asks
     * for roughly one per twelve adults, at most four. Every other trade keeps its old behaviour --
     * fewest holders wins, with no ceiling -- because no ceiling for them is written down anywhere,
     * and inventing one here would be a gameplay number with no basis.
     */
    public static int ceiling(Profession profession, int adults) {
        return switch (profession) {
            case SENTRY -> Math.min(4, adults / 12);
            default -> Integer.MAX_VALUE;
        };
    }

    /**
     * The fewest-held trade still below its ceiling; empty when every trade is full. Ties fall back to
     * enum order so results repeat. {@code adults} is the settlement's adult count, not the roster
     * size -- a ceiling is a function of how big the settlement is.
     */
    public static Optional<Profession> scarcest(List<Profession> assignedAdults, int adults) {
        Profession best = null;
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
            if (count >= ceiling(candidate, adults)) {
                continue;
            }
            if (count < fewest) {
                fewest = count;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }
}
