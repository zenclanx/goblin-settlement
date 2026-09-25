package dev.local.goblinsettlement.colony;

import dev.local.goblinsettlement.economy.WarehouseSupply;

/** Advisory priority from a live supply snapshot; it never creates or consumes items. */
public final class SettlementDemand {
    public enum Priority {
        NO_WORKFORCE, STOCK_UNKNOWN, WAREHOUSE_REQUIRED, FOOD, SEEDS, BASIC_TOOLS, CONSTRUCTION, READY
    }

    public record Assessment(Priority priority, long foodTarget, long seedTarget) {
    }

    private SettlementDemand() {
    }

    public static Assessment assess(int adults, int children, WarehouseSupply supply, boolean activeConstruction) {
        if (adults < 0 || children < 0 || supply == null) {
            throw new IllegalArgumentException("Population and supply snapshot are required");
        }
        long foodTarget = 2L * ((long) adults + children);
        long seedTarget = adults == 0 ? 0 : Math.max(4L, adults);
        if (adults == 0) {
            return new Assessment(Priority.NO_WORKFORCE, foodTarget, seedTarget);
        }
        if (!supply.complete()) {
            return new Assessment(Priority.STOCK_UNKNOWN, foodTarget, seedTarget);
        }
        if (supply.accessibleContainers() == 0) {
            return new Assessment(Priority.WAREHOUSE_REQUIRED, foodTarget, seedTarget);
        }
        if (supply.food() < foodTarget) {
            return new Assessment(Priority.FOOD, foodTarget, seedTarget);
        }
        if (supply.wheatSeeds() < seedTarget) {
            return new Assessment(Priority.SEEDS, foodTarget, seedTarget);
        }
        if (supply.hoes() == 0 || supply.axes() == 0 || supply.pickaxes() == 0) {
            return new Assessment(Priority.BASIC_TOOLS, foodTarget, seedTarget);
        }
        return new Assessment(activeConstruction ? Priority.CONSTRUCTION : Priority.READY,
                foodTarget, seedTarget);
    }
}
