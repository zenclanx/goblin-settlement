package dev.local.goblinsettlement.construction;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ProfessionRules;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.colony.WorkKind;
import dev.local.goblinsettlement.economy.DroppedMaterialLookup;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Visits each small project once per second; all projects share real warehouse containers. */
public final class ConstructionCoordinator {
    private ConstructionCoordinator() {
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
        for (var plan : data.plans()) {
            if (!plan.isComplete()) {
                tickPlan(level, data, settlement.get().id(), plan);
            }
        }
    }

    private static void tickPlan(ServerLevel level, SettlementSavedData data,
                                 String settlementId, ConstructionPlan plan) {
        BlockPos site = plan.site();
        if (WorldModificationPermission.check(level, settlementId, site)
                != WorldModificationPermission.Decision.ALLOWED) {
            return;
        }
        if (plan.workerId().isPresent()) {
            String workerId = plan.workerId().orElseThrow();
            try {
                var entity = level.getEntity(UUID.fromString(workerId));
                if (entity instanceof GoblinCitizenEntity goblin
                        && goblin.completedConstruction(settlementId, site)
                        && level.getBlockState(site).is(Blocks.OAK_PLANKS)) {
                    data.finishStep(workerId, site);
                } else if (entity instanceof GoblinCitizenEntity goblin && goblin.recoveryEnded(settlementId)) {
                    if (goblin.recoverySucceeded()) {
                        data.finishRecovery(workerId);
                    } else {
                        data.abandonRecovery(workerId);
                    }
                    goblin.acknowledgeRecovery();
                }
            } catch (IllegalArgumentException ignored) {
                data.releaseWorker(workerId);
            }
            return;
        }
        if (plan.recoveryDrop().isPresent()) {
            var drop = plan.recoveryDrop().orElseThrow();
            if (!level.shouldTickBlocksAt(drop.pos())) {
                return;
            }
            var found = DroppedMaterialLookup.find(level, drop.itemId(), drop.pos());
            if (found.isEmpty()) {
                data.clearMissingRecoveryDrop(plan.start());
                return;
            }
            var item = found.get();
            if (WorldModificationPermission.check(level, settlementId, item.blockPosition())
                    != WorldModificationPermission.Decision.ALLOWED) {
                return;
            }
            data.retargetRecoveryDrop(drop.itemId(), item.getUUID().toString(), item.blockPosition());
            var warehouse = PublicWarehouseInventory.firstAccessible(level, data);
            if (warehouse.isEmpty()) {
                return;
            }
            var resident = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                            new AABB(item.position(), item.position()).inflate(16.0),
                            GoblinCitizenEntity::isAvailableForConstruction)
                    .stream().min(Comparator
                            .comparingInt((GoblinCitizenEntity goblin) ->
                                    ProfessionRules.matchRank(WorkKind.RECOVERY, goblin.profession()))
                            .thenComparingDouble(goblin -> goblin.distanceToSqr(item)));
            if (resident.isPresent()) {
                var goblin = resident.get();
                String workerId = goblin.getUUID().toString();
                if (data.assignWorker(plan.start(), workerId)
                        && !goblin.assignRecovery(settlementId, warehouse.get(),
                        drop.itemId(), item.blockPosition())) {
                    data.releaseWorker(workerId);
                }
            }
            return;
        }
        if (!level.getBlockState(site).isAir()) {
            return;
        }
        BlockPos below = site.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            return;
        }
        var supply = PublicWarehouseInventory.firstWithOakPlank(level, data);
        if (supply.isEmpty()) {
            return;
        }
        var resident = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                        new AABB(supply.get()).inflate(16.0), GoblinCitizenEntity::isAvailableForConstruction)
                .stream().min(Comparator
                        .comparingInt((GoblinCitizenEntity goblin) ->
                                plan.lastWorkerId().equals(Optional.of(goblin.getUUID().toString())) ? 0 : 1)
                        .thenComparingInt(goblin ->
                                ProfessionRules.matchRank(WorkKind.CONSTRUCTION, goblin.profession()))
                        .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(supply.get())));
        if (resident.isPresent()) {
            var goblin = resident.get();
            String workerId = goblin.getUUID().toString();
            if (data.assignWorker(plan.start(), workerId)
                    && !goblin.assignConstruction(settlementId, supply.get(), site)) {
                data.releaseWorker(workerId);
            }
        }
    }
}
