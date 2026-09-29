package dev.local.goblinsettlement.construction;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ProfessionRules;
import dev.local.goblinsettlement.colony.ResidentRecord;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.colony.WorkKind;
import dev.local.goblinsettlement.colony.WorkerAssignmentRules;
import dev.local.goblinsettlement.economy.DroppedMaterialLookup;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
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
            GoblinCitizenEntity goblin = null;
            try {
                if (level.getEntity(UUID.fromString(workerId)) instanceof GoblinCitizenEntity loaded) {
                    goblin = loaded;
                }
            } catch (IllegalArgumentException exception) {
                goblin = null; // An unusable id is not in the roster either, so the rule below releases it.
            }
            if (goblin != null && goblin.completedConstruction(settlementId, site)
                    && level.getBlockState(site).is(plan.material().block())) {
                data.finishStep(workerId, site);
            } else if (goblin != null && goblin.recoveryEnded(settlementId)) {
                if (goblin.recoverySucceeded()) {
                    data.finishRecovery(workerId);
                } else {
                    data.abandonRecovery(workerId);
                }
                goblin.acknowledgeRecovery();
            } else if (WorkerAssignmentRules.decide(
                    data.resident(workerId).map(ResidentRecord::stage),
                    goblin != null,
                    goblin != null && goblin.hasConstructionWork(settlementId, site))
                    == WorkerAssignmentRules.Decision.RELEASE) {
                data.releaseWorker(workerId);
            }
            return;
        }
        if (plan.recoveryDrop().isPresent()) {
            var drop = plan.recoveryDrop().orElseThrow();
            if (!level.shouldTickBlocksAt(drop.pos())) {
                return;
            }
            var found = DroppedMaterialLookup.find(level, drop.entityId(), plan.material().item(), drop.pos());
            if (found.isEmpty()) {
                data.clearMissingRecoveryDrop(plan.start());
                return;
            }
            var item = found.get();
            if (WorldModificationPermission.check(level, settlementId, item.blockPosition())
                    != WorldModificationPermission.Decision.ALLOWED) {
                return;
            }
            data.retargetRecoveryDrop(drop.entityId(), item.getUUID().toString(), item.blockPosition());
            var warehouse = PublicWarehouseInventory.firstAccessible(level, data);
            if (warehouse.isEmpty()) {
                return;
            }
            // Deliberately not WorkerDispatch: this hire is measured from the dropped stack's own
            // coordinates, not from a block, so its box and distance metric differ from every other
            // site. Only this one caller takes its anchor from an entity.
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
                if (data.assignWorker(plan.start(), workerId)) {
                    goblin.beginRecovery(settlementId, warehouse.get(),
                            drop.entityId(), item.blockPosition());
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
        var supply = PublicWarehouseInventory.firstHolding(level, data, plan.material().item());
        if (supply.isEmpty()) {
            return;
        }
        // Deliberately not WorkerDispatch: this one ranks the plan's previous worker ahead of
        // profession fit, a rule no other hire needs. The rest of the recipe is the same.
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
            if (data.assignWorker(plan.start(), workerId)) {
                goblin.beginConstruction(settlementId, supply.get(), site);
            }
        }
    }
}
