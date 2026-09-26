package dev.local.goblinsettlement.economy.smelting;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ResidentWorkLookup;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.economy.WarehouseSupply;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Runs one real furnace next to a registered warehouse. The module never places the furnace itself,
 * matching how crafting tables and farmland are player-provided facilities that the settlement operates.
 */
public final class SmeltingCoordinator {
    private SmeltingCoordinator() {
    }

    /** Register this with END_WORLD_TICK after mining supplies the raw ore and before defense consumes ingots. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || data.adultCount() == 0) {
            return;
        }
        String settlementId = settlement.get().id();
        WarehouseSupply stock = PublicWarehouseInventory.snapshot(level, data);
        if (!stock.complete() || stock.accessibleContainers() == 0) {
            return;
        }
        SmeltingSavedData saved = SmeltingSavedData.get(level);
        Optional<SmeltingSavedData.Batch> pending = saved.batch();
        if (pending.isPresent()) {
            SmeltingSavedData.Batch batch = pending.orElseThrow();
            if (!batch.settlementId().equals(settlementId)) {
                return;
            }
            // A furnace that was emptied or broken must be reconciled before the batch is resumed.
            FurnaceWorksite.forgetLostBatch(level);
            if (saved.batch().isEmpty()) {
                return;
            }
            if (WorldModificationPermission.check(level, settlementId, batch.furnace())
                    != WorldModificationPermission.Decision.ALLOWED
                    || !level.shouldTickBlocksAt(batch.furnace())
                    || ResidentWorkLookup.anyLoaded(level, data,
                            goblin -> goblin.hasSmeltingWork(settlementId))) {
                return;
            }
            // Reloaded or replaced worker: hand the surviving batch back to a new resident.
            assignWorker(level, settlementId, batch.warehouse(), batch.furnace(), batch.ore());
            return;
        }
        if (ResidentWorkLookup.anyLoaded(level, data, goblin -> goblin.hasSmeltingWork(settlementId))) {
            return;
        }
        for (BlockPos warehouse : data.warehouses()) {
            if (WorldModificationPermission.check(level, settlementId, warehouse)
                    != WorldModificationPermission.Decision.ALLOWED
                    || !(level.getBlockEntity(warehouse) instanceof Container)) {
                continue;
            }
            Optional<BlockPos> furnace = nearbyFurnace(level, settlementId, warehouse);
            if (furnace.isEmpty()) {
                continue;
            }
            Optional<FurnaceWorksite.Ore> ore = FurnaceWorksite.firstFeedable(level, settlementId, warehouse);
            if (ore.isEmpty()) {
                continue;
            }
            if (assignWorker(level, settlementId, warehouse, furnace.orElseThrow(), ore.orElseThrow())) {
                return;
            }
        }
    }

    private static boolean assignWorker(ServerLevel level, String settlementId,
                                        BlockPos warehouse, BlockPos furnace, FurnaceWorksite.Ore ore) {
        var worker = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                        new AABB(warehouse).inflate(16.0),
                        goblin -> goblin.isAvailableForConstruction()
                                && WorldModificationPermission.check(level, settlementId,
                                        goblin.blockPosition()) == WorldModificationPermission.Decision.ALLOWED)
                .stream().min(Comparator.comparingDouble(
                        goblin -> goblin.blockPosition().distSqr(warehouse)));
        return worker.isPresent() && worker.orElseThrow().assignSmelting(settlementId, warehouse, furnace, ore);
    }

    /** Bounded scan for a real furnace close enough for both feeding and collecting. */
    private static Optional<BlockPos> nearbyFurnace(ServerLevel level, String settlementId, BlockPos warehouse) {
        for (BlockPos candidate : BlockPos.betweenClosed(
                warehouse.offset(-4, -2, -4), warehouse.offset(4, 2, 4))) {
            if (candidate.distSqr(warehouse) > 16.0) {
                continue;
            }
            if (WorldModificationPermission.check(level, settlementId, candidate)
                    != WorldModificationPermission.Decision.ALLOWED
                    || !level.shouldTickBlocksAt(candidate)) {
                continue;
            }
            if (level.getBlockState(candidate).is(Blocks.FURNACE)) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }
}
