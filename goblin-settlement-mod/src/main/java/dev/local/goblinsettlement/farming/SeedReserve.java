package dev.local.goblinsettlement.farming;

/** Keeps warehouse seed stock for later planting while allowing one bootstrap crop. */
public final class SeedReserve {
    private SeedReserve() {
    }

    public static boolean canAssignPlanting(int warehouseSeeds, int assignedPlantings, long target,
                                            boolean hasGrowingCrop, boolean stockComplete) {
        if (warehouseSeeds < 0 || assignedPlantings < 0 || target < 0) {
            throw new IllegalArgumentException("Seed counts and target cannot be negative");
        }
        if (!stockComplete) {
            return false;
        }
        long uncommitted = (long) warehouseSeeds - assignedPlantings;
        if (uncommitted <= 0) {
            return false;
        }
        if (uncommitted > target) {
            return true;
        }
        return !hasGrowingCrop && assignedPlantings == 0;
    }
}
