package dev.local.goblinsettlement.colony;

/** Pure settlement rules. World state and inventory checks belong to their respective services. */
public final class PopulationRules {
    public static final int MAX_RESIDENTS = 64;

    private PopulationRules() {
    }

    /** Children and pregnancies occupy slots, but only adults grant land. */
    public static int occupiedSlots(int adults, int children, int pregnancies) {
        requireNonNegative(adults, children, pregnancies);
        return Math.addExact(Math.addExact(adults, children), pregnancies);
    }

    public static boolean hasBirthSlot(int adults, int children, int pregnancies) {
        return occupiedSlots(adults, children, pregnancies) < MAX_RESIDENTS;
    }

    /** Maximum number of 8x8 planning plots, including plots already developed. */
    public static int maximumPlots(int adults) {
        requireNonNegative(adults);
        return Math.addExact(4, Math.multiplyExact(2, adults));
    }

    /** Maximum new plots in one 20-minute expansion window. */
    public static int expansionBudget(int adults, int occupiedPlots) {
        requireNonNegative(adults, occupiedPlots);
        return Math.min(Math.min(adults / 8, 4), Math.max(0, maximumPlots(adults) - occupiedPlots));
    }

    /** Ideal-condition progress per active simulation minute; 1.0 completes one pregnancy opportunity. */
    public static double breedingProgressPerMinute(int eligibleAdults, int occupiedSlots, double livingConditions) {
        requireNonNegative(eligibleAdults, occupiedSlots);
        if (!Double.isFinite(livingConditions) || livingConditions < 0.0 || livingConditions > 1.0) {
            throw new IllegalArgumentException("livingConditions must be finite and between 0 and 1");
        }
        if (occupiedSlots >= MAX_RESIDENTS) {
            return 0.0;
        }
        return 0.01 * eligibleAdults * (1.0 - (double) occupiedSlots / MAX_RESIDENTS) * livingConditions;
    }

    private static void requireNonNegative(int... values) {
        for (int value : values) {
            if (value < 0) {
                throw new IllegalArgumentException("Population and plot counts cannot be negative");
            }
        }
    }
}
