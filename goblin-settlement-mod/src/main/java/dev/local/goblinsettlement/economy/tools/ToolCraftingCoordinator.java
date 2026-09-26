package dev.local.goblinsettlement.economy.tools;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ProfessionRules;
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

/** Starts one real-material wooden-tool job when the live warehouse stock needs it. */
public final class ToolCraftingCoordinator {
    private ToolCraftingCoordinator() {
    }

    /** Register this with END_WORLD_TICK after farming and before construction scheduling. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return;
        }
        String settlementId = settlement.get().id();
        WarehouseSupply stock = PublicWarehouseInventory.snapshot(level, data);
        if (!stock.complete() || stock.accessibleContainers() == 0 || data.adultCount() == 0) {
            return;
        }
        // Loaded workers retain their assignment on the entity. Do not start another
        // tool job while one is still fetching, crafting, or returning its output.
        if (ResidentWorkLookup.anyLoaded(level, data, goblin -> goblin.hasToolWork(settlementId))) {
            return;
        }
        for (WoodToolKind kind : WoodToolKind.values()) {
            if (inStock(stock, kind)) {
                continue;
            }
            for (BlockPos warehouse : data.warehouses()) {
                if (WorldModificationPermission.check(level, settlementId, warehouse)
                        != WorldModificationPermission.Decision.ALLOWED
                        || !(level.getBlockEntity(warehouse) instanceof Container container)
                        || !hasIngredients(container, kind)) {
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
                                        ProfessionRules.matchRank(WorkKind.TOOL_CRAFTING, goblin.profession()))
                                .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(warehouse)));
                if (worker.isPresent() && worker.get().assignToolCrafting(settlementId, warehouse,
                        table.get(), kind)) {
                    return;
                }
            }
        }
    }

    public static boolean inStock(WarehouseSupply stock, WoodToolKind kind) {
        return switch (kind) {
            case HOE -> stock.hoes() > 0;
            case AXE -> stock.axes() > 0;
            case PICKAXE -> stock.pickaxes() > 0;
        };
    }

    public static boolean hasIngredients(Container container, WoodToolKind kind) {
        int planks = 0;
        int sticks = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            var stack = container.getItem(slot);
            if (stack.is(Items.OAK_PLANKS)) {
                planks += stack.getCount();
            } else if (stack.is(Items.STICK)) {
                sticks += stack.getCount();
            }
        }
        return planks >= kind.planks() + (sticks >= 2 ? 0 : 2);
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
