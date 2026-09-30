package dev.local.goblinsettlement.social;

import java.util.List;
import java.util.Optional;

/**
 * Who greets whom when two residents meet. Pure: ints, doubles and ids only, so the choice can be
 * pinned by a check instead of described in a comment.
 */
public final class GreetingRules {
    /** An invented value: the art brief asked for a greeting and gave no cadence. Re-tune in game. */
    public static final int GREETING_COOLDOWN_TICKS = 600;

    /** One resident as the rule sees them: where they are, and when they last greeted anyone. */
    public record Resident(String id, double x, double z, long lastGreetTick) {
    }

    /** Who speaks first and who answers. */
    public record Pair(String greeterId, String answererId) {
    }

    private GreetingRules() {
    }

    /**
     * The first pair that may greet, or empty.
     *
     * <p>"First" is roster order, not id order: the walk takes the first ready resident that has a ready
     * resident in reach, and the first such neighbour, so a differently ordered roster can pick a
     * different pair when three or more residents stand within one radius. The ids decide only which of
     * the two speaks -- the lower one greets. Both halves are pinned by SoundsCheck. In the settlement
     * the roster arrives in a fixed order (the saved resident list), so the same crowd still answers the
     * same way every visit.
     */
    public static Optional<Pair> pick(List<Resident> residents, double radius, long nowTick) {
        Resident greeter = null;
        Resident answerer = null;
        for (Resident candidate : residents) {
            if (!ready(candidate, nowTick)) {
                continue;
            }
            for (Resident other : residents) {
                if (other == candidate || !ready(other, nowTick) || distance(candidate, other) > radius) {
                    continue;
                }
                if (candidate.id().compareTo(other.id()) < 0) {
                    greeter = candidate;
                    answerer = other;
                } else {
                    greeter = other;
                    answerer = candidate;
                }
                break;
            }
            if (greeter != null) {
                break;
            }
        }
        return greeter == null ? Optional.empty()
                : Optional.of(new Pair(greeter.id(), answerer.id()));
    }

    private static boolean ready(Resident resident, long nowTick) {
        return nowTick - resident.lastGreetTick() >= GREETING_COOLDOWN_TICKS;
    }

    /** Horizontal distance: y is ignored, so a resident upstairs meets one standing on the ground below. */
    private static double distance(Resident left, Resident right) {
        double dx = left.x() - right.x();
        double dz = left.z() - right.z();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
