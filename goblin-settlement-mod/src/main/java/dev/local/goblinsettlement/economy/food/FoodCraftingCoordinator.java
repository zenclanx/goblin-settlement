package dev.local.goblinsettlement.economy.food;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ProfessionRules;
import dev.local.goblinsettlement.colony.SettlementDemand;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.colony.ResidentWorkLookup;
import dev.local.goblinsettlement.colony.WorkKind;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.economy.WarehouseSupply;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Starts one bread job when live public food is below the settlement target. */
public final class FoodCraftingCoordinator {
    private FoodCraftingCoordinator() {
    }

    /** Register with END_WORLD_TICK before tool and construction coordinators. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return;
        }
        WarehouseSupply stock = PublicWarehouseInventory.snapshot(level, data);
        if (!needsBread(stock, data)) {
            return;
        }
        String settlementId = settlement.get().id();
        if (ResidentWorkLookup.anyLoaded(level, data, goblin -> goblin.hasFoodWork(settlementId))) {
            return;
        }
        for (BlockPos warehouse : data.warehouses()) {
            if (WorldModificationPermission.check(level, settlementId, warehouse)
                    != WorldModificationPermission.Decision.ALLOWED
                    || !(level.getBlockEntity(warehouse) instanceof Container container)
                    || !hasWheat(container)) {
                continue;
            }
            Optional<BlockPos> table = nearbyTable(level, settlementId, warehouse);
            if (table.isEmpty()) {
                continue;
            }
            var worker = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                            new AABB(warehouse).inflate(16.0),
                            goblin -> goblin.isAvailableForConstruction()
                                    && WorldModificationPermission.check(level, settlementId,
                                            goblin.blockPosition()) == WorldModificationPermission.Decision.ALLOWED)
                    .stream().min(Comparator
                            .comparingInt((GoblinCitizenEntity goblin) ->
                                    ProfessionRules.matchRank(WorkKind.FOOD_CRAFTING, goblin.profession()))
                            .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(warehouse)));
            if (worker.isPresent() && worker.get().assignFoodCrafting(settlementId, warehouse, table.get())) {
                return;
            }
        }
    }

    public static boolean needsBread(WarehouseSupply stock, SettlementSavedData data) {
        return SettlementDemand.assess(data.adultCount(), data.childCount(), stock, false).priority()
                == SettlementDemand.Priority.FOOD;
    }

    private static boolean hasWheat(Container container) {
        int wheat = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(Items.WHEAT)) {
                wheat += container.getItem(slot).getCount();
            }
        }
        return wheat >= 3;
    }

    private static Optional<BlockPos> nearbyTable(ServerLevel level, String settlementId, BlockPos warehouse) {
        for (BlockPos candidate : BlockPos.betweenClosed(
                warehouse.offset(-3, -1, -3), warehouse.offset(3, 1, 3))) {
            if (WorldModificationPermission.check(level, settlementId, candidate)
                    == WorldModificationPermission.Decision.ALLOWED
                    && level.getBlockState(candidate).is(Blocks.CRAFTING_TABLE)) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }
}