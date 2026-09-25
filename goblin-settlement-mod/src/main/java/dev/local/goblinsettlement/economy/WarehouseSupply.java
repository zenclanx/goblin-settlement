package dev.local.goblinsettlement.economy;

/** One read of currently accessible public containers; item counts are never cached. */
public record WarehouseSupply(int food, int wheatSeeds, int hoes, int axes, int pickaxes,
                              int oakPlanks, int accessibleContainers, boolean complete) {
    public WarehouseSupply {
        if (food < 0 || wheatSeeds < 0 || hoes < 0 || axes < 0 || pickaxes < 0
                || oakPlanks < 0 || accessibleContainers < 0) {
            throw new IllegalArgumentException("Warehouse counts cannot be negative");
        }
    }
}
