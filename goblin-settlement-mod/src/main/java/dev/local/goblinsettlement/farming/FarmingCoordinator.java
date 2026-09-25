package dev.local.goblinsettlement.farming;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementDemand;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;

/** Assigns one resident to each registered wheat cell, preserving reservations across unloads. */
public final class FarmingCoordinator {
    private FarmingCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return;
        }

        var supply = PublicWarehouseInventory.snapshot(level, data);
        long seedTarget = SettlementDemand.assess(data.adultCount(), data.childCount(), supply, false).seedTarget();
        boolean allFarmSitesKnown = true;
        boolean hasGrowingCrop = false;
        int assignedPlantings = 0;
        for (var site : data.farmSites()) {
            BlockPos crop = site.cropPos();
            if (!level.shouldTickBlocksAt(crop)) {
                allFarmSitesKnown = false;
                continue;
            }
            var state = level.getBlockState(crop);
            if (state.is(Blocks.WHEAT)
                    && WorldModificationPermission.check(level, settlement.get().id(), crop)
                    == WorldModificationPermission.Decision.ALLOWED
                    && WorldModificationPermission.check(level, settlement.get().id(), crop.below())
                    == WorldModificationPermission.Decision.ALLOWED) {
                hasGrowingCrop = true;
            }
            if (state.isAir() && site.workerId().isPresent()) {
                assignedPlantings++;
            }
        }

        for (var site : data.farmSites()) {
            BlockPos crop = site.cropPos();
            if (site.workerId().isPresent()) {
                String id = site.workerId().orElseThrow();
                try {
                    var entity = level.getEntity(UUID.fromString(id));
                    if (entity instanceof GoblinCitizenEntity goblin
                            && goblin.farmWorkComplete(settlement.get().id(), crop)) {
                        data.releaseFarmWorker(id);
                        goblin.acknowledgeFarmWork();
                    }
                } catch (IllegalArgumentException ignored) {
                    data.releaseFarmWorker(id);
                }
                continue;
            }
            if (WorldModificationPermission.check(level, settlement.get().id(), crop)
                    != WorldModificationPermission.Decision.ALLOWED
                    || WorldModificationPermission.check(level, settlement.get().id(), crop.below())
                    != WorldModificationPermission.Decision.ALLOWED
                    || !level.getBlockState(crop.below()).is(Blocks.FARMLAND)) {
                continue;
            }
            if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
                continue;
            }
            var state = level.getBlockState(crop);
            boolean plant = state.isAir();
            boolean harvest = state.is(Blocks.WHEAT)
                    && ((CropBlock) Blocks.WHEAT).isMaxAge(state);
            if (!plant && !harvest) {
                continue;
            }
            if (plant && !SeedReserve.canAssignPlanting(supply.wheatSeeds(), assignedPlantings,
                    seedTarget, hasGrowingCrop, supply.complete() && allFarmSitesKnown)) {
                continue;
            }
            var warehouse = plant
                    ? PublicWarehouseInventory.firstWithWheatSeeds(level, data)
                    : PublicWarehouseInventory.firstAccessible(level, data);
            if (warehouse.isEmpty()) {
                continue;
            }
            var resident = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                            new AABB(crop).inflate(16.0), GoblinCitizenEntity::isAvailableForConstruction)
                    .stream().min(Comparator.comparingDouble(goblin -> goblin.blockPosition().distSqr(crop)));
            if (resident.isEmpty()) {
                continue;
            }
            var goblin = resident.get();
            String workerId = goblin.getUUID().toString();
            boolean assigned = plant
                    ? goblin.assignFarmPlanting(settlement.get().id(), warehouse.get(), crop)
                    : goblin.assignFarmHarvest(settlement.get().id(), warehouse.get(), crop);
            if (assigned) {
                if (data.assignFarmWorker(crop, workerId)) {
                    if (plant) {
                        assignedPlantings++;
                    }
                } else {
                    goblin.cancelUnreservedFarmWork();
                }
            }
        }
    }
}
