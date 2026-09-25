package dev.local.goblinsettlement.defense;

/** Quotas use adult residents only; the caller supplies an authoritative count of all golems. */
public final class GolemPopulationRules {
    private GolemPopulationRules() {
    }

    public static int maximumGolems(int adults) {
        if (adults < 0) {
            throw new IllegalArgumentException("Adult count cannot be negative");
        }
        return Math.max(1, Math.min(8, adults / 8));
    }

    public static boolean mayCreate(int adults, int existingGolems, int obsidianGolems) {
        if (existingGolems < 0 || obsidianGolems < 0 || obsidianGolems > existingGolems) {
            throw new IllegalArgumentException("Invalid golem counts");
        }
        return existingGolems < maximumGolems(adults);
    }

    public static boolean mayUpgradeTo(GolemTier target, int obsidianGolems) {
        if (target == null || obsidianGolems < 0) {
            throw new IllegalArgumentException("Invalid golem tier or count");
        }
        return target != GolemTier.OBSIDIAN || obsidianGolems == 0;
    }
}
