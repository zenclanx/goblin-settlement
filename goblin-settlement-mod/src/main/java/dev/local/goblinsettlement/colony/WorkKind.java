package dev.local.goblinsettlement.colony;

/**
 * A job a coordinator can dispatch. The mapping to the profession that does it best lives here so the
 * knowledge is not repeated in every coordinator.
 */
public enum WorkKind {
    FARMING(Profession.FARMER),
    FOOD_CRAFTING(Profession.FARMER),
    FORESTRY(Profession.FORESTER),
    MINING(Profession.MINER),
    CONSTRUCTION(Profession.BUILDER),
    HOUSING(Profession.BUILDER),
    TRANSPORT(Profession.HAULER),
    RECOVERY(Profession.HAULER),
    TOOL_CRAFTING(Profession.ARTISAN),
    SMELTING(Profession.ARTISAN);

    private final Profession required;

    WorkKind(Profession required) {
        this.required = required;
    }

    public Profession required() {
        return required;
    }

    /** True when some work kind is best done by this profession. A profession with none is a generalist. */
    public static boolean employs(Profession profession) {
        for (WorkKind kind : values()) {
            if (kind.required == profession) {
                return true;
            }
        }
        return false;
    }
}
