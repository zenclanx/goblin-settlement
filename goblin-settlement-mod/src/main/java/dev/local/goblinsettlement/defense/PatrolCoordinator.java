package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ResidentWorkLookup;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.colony.WorkKind;
import dev.local.goblinsettlement.colony.WorkerDispatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Sends one resident at a time around the settlement's facilities. */
public final class PatrolCoordinator {
    private static final int INTERVAL_TICKS = 40;

    private PatrolCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL_TICKS != 0) {
            return;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return;
        }
        String settlementId = settlement.get().id();
        // One patrol at a time: the route is the same for everyone, so a second patroller would only
        // trail the first. The resident clears its own work when it dies, unloads or is cancelled.
        if (ResidentWorkLookup.anyLoaded(level, data, goblin -> goblin.hasPatrolWork(settlementId))) {
            return;
        }
        // Every resident may patrol; a sentry wins on profession fit rather than by being required.
        WorkerDispatch.nearest(level, WorkKind.PATROL, settlement.get().anchor(),
                        GoblinCitizenEntity::isAvailableForConstruction)
                .ifPresent(goblin -> goblin.assignPatrol(settlementId));
    }

    /** The waypoint at this index, wrapping. Empty when the settlement is gone or has changed hands. */
    public static Optional<BlockPos> waypointFor(ServerLevel level, String settlementId, int index) {
        SettlementSavedData data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || !settlement.get().id().equals(settlementId)) {
            return Optional.empty();
        }
        List<int[]> facilities = new ArrayList<>();
        for (BlockPos warehouse : data.warehouses()) {
            facilities.add(new int[] {warehouse.getX(), warehouse.getY(), warehouse.getZ()});
        }
        for (var farm : data.farmSites()) {
            BlockPos crop = farm.cropPos();
            facilities.add(new int[] {crop.getX(), crop.getY(), crop.getZ()});
        }
        BlockPos anchor = settlement.get().anchor();
        List<int[]> route = PatrolRules.waypoints(facilities,
                new int[] {anchor.getX(), anchor.getY(), anchor.getZ()});
        int[] point = route.get(Math.floorMod(index, route.size()));
        return Optional.of(new BlockPos(point[0], point[1], point[2]));
    }
}
