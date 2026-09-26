package dev.local.goblinsettlement.mining;

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
import net.minecraft.world.phys.AABB;

/** Sends at most one resident at a time to mine a real, exposed block into the public warehouse. */
public final class MiningCoordinator {
    private MiningCoordinator() {
    }

    /** Register this with END_WORLD_TICK after the resource loops and before defense consumption. */
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
        // One miner at a time: the site lives on the entity, and a second miner would only race for the
        // same exposed blocks. The resident clears its own work when the block is gone.
        if (ResidentWorkLookup.anyLoaded(level, data, goblin -> goblin.hasMiningWork(settlementId))) {
            return;
        }
        for (BlockPos warehouse : data.warehouses()) {
            if (WorldModificationPermission.check(level, settlementId, warehouse)
                    != WorldModificationPermission.Decision.ALLOWED
                    || !(level.getBlockEntity(warehouse) instanceof Container)) {
                continue;
            }
            Optional<MiningWorksite.Site> site = MiningWorksite.survey(level, settlementId, warehouse);
            if (site.isEmpty()) {
                continue;
            }
            var worker = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                            new AABB(warehouse).inflate(16.0),
                            goblin -> goblin.isAvailableForConstruction()
                                    && WorldModificationPermission.check(level, settlementId,
                                            goblin.blockPosition()) == WorldModificationPermission.Decision.ALLOWED)
                    .stream().min(Comparator.comparingDouble(
                            goblin -> goblin.blockPosition().distSqr(warehouse)));
            if (worker.isPresent() && worker.get().assignMining(settlementId, warehouse, site.get().block())) {
                return;
            }
        }
    }
}
