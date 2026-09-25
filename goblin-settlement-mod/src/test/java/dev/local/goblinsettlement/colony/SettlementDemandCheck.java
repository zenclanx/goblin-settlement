package dev.local.goblinsettlement.colony;

import dev.local.goblinsettlement.economy.WarehouseSupply;

/** Checks that uncertain stock never becomes a false shortage or a production order. */
public final class SettlementDemandCheck {
    public static void main(String[] args) {
        require(priority(8, 0, new WarehouseSupply(0, 0, 0, 0, 0, 0, 0, false), false)
                == SettlementDemand.Priority.STOCK_UNKNOWN, "inactive chest leaves stock unknown");
        require(priority(8, 0, new WarehouseSupply(0, 0, 0, 0, 0, 0, 0, true), false)
                == SettlementDemand.Priority.WAREHOUSE_REQUIRED, "missing accessible warehouse");
        require(priority(8, 0, new WarehouseSupply(15, 8, 1, 1, 1, 0, 1, true), true)
                == SettlementDemand.Priority.FOOD, "food takes priority over construction");
        require(priority(8, 0, new WarehouseSupply(16, 7, 1, 1, 1, 0, 1, true), true)
                == SettlementDemand.Priority.SEEDS, "replanting stock precedes construction");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 0, 1, 0, 1, true), true)
                == SettlementDemand.Priority.BASIC_TOOLS, "basic tools precede construction");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), true)
                == SettlementDemand.Priority.CONSTRUCTION, "construction after essentials");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), false)
                == SettlementDemand.Priority.READY, "settlement ready without active work");
        require(SettlementDemand.assess(0, 0, new WarehouseSupply(0, 0, 0, 0, 0, 0, 1, true), false)
                .priority() == SettlementDemand.Priority.NO_WORKFORCE, "no adult worker");
        System.out.println("SettlementDemandCheck passed");
    }

    private static SettlementDemand.Priority priority(int adults, int children, WarehouseSupply supply,
                                                      boolean construction) {
        return SettlementDemand.assess(adults, children, supply, construction).priority();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
