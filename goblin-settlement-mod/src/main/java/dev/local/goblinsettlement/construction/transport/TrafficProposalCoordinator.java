package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.planning.transport.TrafficTargetRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Autonomous traffic proposals; see tick(). */
public final class TrafficProposalCoordinator {
    private static final long MIN_TARGET_DISTANCE_SQ = 12L * 12L;

    private TrafficProposalCoordinator() {
    }

    /** Shared by status display, expansion, and this coordinator: saved data only, no world probe. */
    public static boolean hasPendingTarget(SettlementSavedData settlement, TransportSavedData traffic) {
        return nearestUnservedFacility(settlement, traffic).isPresent();
    }

    public static Optional<BlockPos> nearestUnservedFacility(SettlementSavedData settlement,
                                                             TransportSavedData traffic) {
        var founded = settlement.settlement();
        if (founded.isEmpty()) {
            return Optional.empty();
        }
        BlockPos anchor = founded.orElseThrow().anchor();
        List<TrafficTargetRules.Facility> facilities = new ArrayList<>();
        facilities.add(new TrafficTargetRules.Facility(anchor.getX(), anchor.getY(), anchor.getZ()));
        for (BlockPos warehouse : settlement.warehouses()) {
            facilities.add(new TrafficTargetRules.Facility(warehouse.getX(), warehouse.getY(), warehouse.getZ()));
        }
        for (var site : settlement.farmSites()) {
            BlockPos crop = site.cropPos();
            facilities.add(new TrafficTargetRules.Facility(crop.getX(), crop.getY(), crop.getZ()));
        }
        List<TrafficTargetRules.Facility> served = new ArrayList<>();
        for (BlockPos pos : traffic.servedFacilities()) {
            served.add(new TrafficTargetRules.Facility(pos.getX(), pos.getY(), pos.getZ()));
        }
        return TrafficTargetRules.nearestBeyond(
                new TrafficTargetRules.Facility(anchor.getX(), anchor.getY(), anchor.getZ()),
                facilities, served, MIN_TARGET_DISTANCE_SQ)
                .map(facility -> new BlockPos(facility.x(), facility.y(), facility.z()));
    }
}
