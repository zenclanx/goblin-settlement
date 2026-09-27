package dev.local.goblinsettlement.colony;

import dev.local.goblinsettlement.economy.WarehouseSupply;

/** Checks that uncertain stock never becomes a false shortage or a production order. */
public final class SettlementDemandCheck {
    public static void main(String[] args) {
        require(priority(8, 0, new WarehouseSupply(0, 0, 0, 0, 0, 0, 0, false), false, false, false)
                == SettlementDemand.Priority.STOCK_UNKNOWN, "inactive chest leaves stock unknown");
        require(priority(8, 0, new WarehouseSupply(0, 0, 0, 0, 0, 0, 0, true), false, false, false)
                == SettlementDemand.Priority.WAREHOUSE_REQUIRED, "missing accessible warehouse");
        require(priority(8, 0, new WarehouseSupply(15, 8, 1, 1, 1, 0, 1, true), true, false, false)
                == SettlementDemand.Priority.FOOD, "food takes priority over construction");
        require(priority(8, 0, new WarehouseSupply(16, 7, 1, 1, 1, 0, 1, true), true, false, false)
                == SettlementDemand.Priority.SEEDS, "replanting stock precedes construction");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 0, 1, 0, 1, true), true, false, false)
                == SettlementDemand.Priority.BASIC_TOOLS, "basic tools precede construction");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), true, false, false)
                == SettlementDemand.Priority.CONSTRUCTION, "construction after essentials");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), false, false, false)
                == SettlementDemand.Priority.READY, "settlement ready without active work");
        require(SettlementDemand.assess(0, 0, new WarehouseSupply(0, 0, 0, 0, 0, 0, 1, true), false, false, false)
                .priority() == SettlementDemand.Priority.NO_WORKFORCE, "no adult worker");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), false, true, false)
                == SettlementDemand.Priority.TRANSPORT, "pending traffic precedes readiness");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), true, true, false)
                == SettlementDemand.Priority.TRANSPORT, "pending traffic precedes construction");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 0, 1, 0, 1, true), false, true, false)
                == SettlementDemand.Priority.BASIC_TOOLS, "basic tools precede transport");
        require(priority(8, 0, new WarehouseSupply(15, 8, 1, 1, 1, 0, 1, true), false, true, false)
                == SettlementDemand.Priority.FOOD, "food precedes transport");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), false, false, true)
                == SettlementDemand.Priority.HOUSING, "a bed shortage precedes readiness");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), true, false, true)
                == SettlementDemand.Priority.HOUSING, "a bed shortage precedes construction");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 1, 1, 0, 1, true), false, true, true)
                == SettlementDemand.Priority.TRANSPORT, "pending traffic precedes housing");
        require(priority(8, 0, new WarehouseSupply(16, 8, 1, 0, 1, 0, 1, true), false, false, true)
                == SettlementDemand.Priority.BASIC_TOOLS, "basic tools precede housing");
        System.out.println("SettlementDemandCheck passed");
    }

    private static SettlementDemand.Priority priority(int adults, int children, WarehouseSupply supply,
                                                      boolean construction, boolean trafficPending,
                                                      boolean housingShortage) {
        return SettlementDemand.assess(adults, children, supply, construction, trafficPending,
                housingShortage).priority();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
