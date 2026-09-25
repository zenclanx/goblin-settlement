package dev.local.goblinsettlement.colony;

/** Standalone checks runnable with the JDK while Fabric dependencies are unavailable. */
public final class PopulationRulesCheck {
    public static void main(String[] args) {
        check(PopulationRules.occupiedSlots(8, 2, 1) == 11, "pregnancy reserves a slot");
        check(PopulationRules.hasBirthSlot(8, 2, 1), "available birth slot");
        check(!PopulationRules.hasBirthSlot(32, 30, 2), "full settlement blocks birth");
        check(PopulationRules.maximumPlots(8) == 20, "adult plot cap");
        check(PopulationRules.expansionBudget(8, 18) == 1, "expansion window cap");
        check(PopulationRules.expansionBudget(8, 20) == 0, "no expansion above cap");
        check(PopulationRules.expansionBudget(4, 0) == 0, "small group does not expand yet");
        check(PopulationRules.breedingProgressPerMinute(8, 8, 1.0) > 0.0, "early breeding");
        check(PopulationRules.breedingProgressPerMinute(32, 32, 1.0)
                > PopulationRules.breedingProgressPerMinute(8, 8, 1.0), "mid-stage birth rate peaks later");
        check(PopulationRules.breedingProgressPerMinute(8, 64, 1.0) == 0.0, "full settlement stops breeding");
        check(PopulationRules.breedingProgressPerMinute(8, 8, 0.0) == 0.0, "poor conditions stop breeding");
        System.out.println("PopulationRulesCheck passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
