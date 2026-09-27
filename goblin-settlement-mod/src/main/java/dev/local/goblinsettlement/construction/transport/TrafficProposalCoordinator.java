package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.colony.SettlementDemand;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.housing.BedCensus;
import dev.local.goblinsettlement.planning.bridge.BridgePlanner;
import dev.local.goblinsettlement.planning.transport.StraightLineProbe;
import dev.local.goblinsettlement.planning.transport.TrafficDecision;
import dev.local.goblinsettlement.planning.transport.TrafficTargetRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;

/** Autonomous traffic proposals; see tick(). */
public final class TrafficProposalCoordinator {
    private static final long MIN_TARGET_DISTANCE_SQ = 12L * 12L;
    private static final long PROPOSAL_INTERVAL_TICKS = 1200; // 60 seconds
    private static final long DEFERRAL_TICKS = 24000; // 20 minutes, the expansion window's scale
    private static final int ROAD_MIN_PLANKS = 8;
    private static final int BRIDGE_MIN_PLANKS = 8;
    private static final int BRIDGE_MIN_LOGS = 4;
    private static final int BRIDGE_MIN_FENCES = 4;
    private static final int BRIDGE_MIN_TORCHES = 4;

    private TrafficProposalCoordinator() {
    }

    /** Shared by status display, expansion, and this coordinator: saved data only, no world probe. */
    public static boolean hasPendingTarget(SettlementSavedData settlement, TransportSavedData traffic,
                                           long nowTick) {
        return nearestUnservedFacility(settlement, traffic, nowTick).isPresent();
    }

    public static Optional<BlockPos> nearestUnservedFacility(SettlementSavedData settlement,
                                                             TransportSavedData traffic, long nowTick) {
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
        List<TrafficTargetRules.Facility> deferred = new ArrayList<>();
        for (BlockPos pos : traffic.deferredFacilities(nowTick)) {
            deferred.add(new TrafficTargetRules.Facility(pos.getX(), pos.getY(), pos.getZ()));
        }
        return TrafficTargetRules.nearestBeyond(
                new TrafficTargetRules.Facility(anchor.getX(), anchor.getY(), anchor.getZ()),
                facilities, served, deferred, MIN_TARGET_DISTANCE_SQ)
                .map(facility -> new BlockPos(facility.x(), facility.y(), facility.z()));
    }

    /** Called from tickSettlement before TransportCoordinator.tick; proposes at most
     *  one project per PROPOSAL_INTERVAL_TICKS and never modifies the world itself. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % PROPOSAL_INTERVAL_TICKS != 0) {
            return;
        }
        var settlement = SettlementSavedData.get(level);
        var traffic = TransportSavedData.get(level);
        if (settlement.settlement().isEmpty() || traffic.hasIncomplete()) {
            return;                                        // gate 1: one in-flight traffic plan
        }
        if (settlement.plans().stream().anyMatch(plan -> !plan.isComplete())) {
            return;                                        // gate 2 (3.1a symmetry): yield to active building work
        }
        String settlementId = settlement.settlement().orElseThrow().id();
        var supply = PublicWarehouseInventory.snapshot(level, settlement);
        var demand = SettlementDemand.assess(settlement.adultCount(), settlement.childCount(),
                supply, false, hasPendingTarget(settlement, traffic, level.getGameTime()),
                BedCensus.shortage(level, settlement));
        if (demand.priority() != SettlementDemand.Priority.TRANSPORT) {
            // Nothing left to connect and nothing more urgent to do: widen what carries the traffic.
            // READY is the only tier that can be reached here -- this call passes activeConstruction
            // false, so a busy building site never reaches this point either.
            if (demand.priority() == SettlementDemand.Priority.READY) {
                proposeWidening(level, settlement, traffic, settlementId);
            }
            return;                                        // gate 3: demand tier
        }
        var target = nearestUnservedFacility(settlement, traffic, level.getGameTime());
        if (target.isEmpty()) {
            return;
        }
        BlockPos targetPos = target.orElseThrow();
        BlockPos anchor = settlement.settlement().orElseThrow().anchor();
        var sample = StraightLineProbe.sample(level, settlementId, anchor, targetPos);
        var decision = TrafficDecision.decide(sample.columns(), sample.targetIndex(),
                BridgePlanner.MIN_WOOD_SPAN, BridgePlanner.MAX_WOOD_SPAN);
        switch (decision.kind()) {
            case BRIDGE -> proposeBridge(level, settlement, traffic, settlementId, anchor,
                    targetPos, decision);
            case ROAD -> {
                // Material shortage is not a proposal failure; supplies will catch up.
                if (PublicWarehouseInventory.countOf(level, settlement, Items.OAK_PLANKS)
                        >= ROAD_MIN_PLANKS) {              // gate 4: real materials
                    var result = TransportCoordinator.startRoad(level, settlementId, targetPos);
                    if (!result.accepted()) {
                        // No route, unsafe footprint, or a work conflict: park the target
                        // so the demand tier falls back to READY and expansion resumes.
                        traffic.defer(targetPos, level.getGameTime() + DEFERRAL_TICKS);
                    }
                }
            }
            case NONE -> traffic.defer(targetPos, level.getGameTime() + DEFERRAL_TICKS);
        }
    }

    private static void proposeBridge(ServerLevel level, SettlementSavedData settlement,
                                      TransportSavedData traffic, String settlementId,
                                      BlockPos anchor, BlockPos target,
                                      TrafficDecision.Decision decision) {
        if (PublicWarehouseInventory.countOf(level, settlement, Items.OAK_PLANKS) < BRIDGE_MIN_PLANKS
                || PublicWarehouseInventory.countOf(level, settlement, Items.OAK_LOG) < BRIDGE_MIN_LOGS
                || PublicWarehouseInventory.countOf(level, settlement, Items.OAK_FENCE) < BRIDGE_MIN_FENCES
                || PublicWarehouseInventory.countOf(level, settlement, Items.TORCH) < BRIDGE_MIN_TORCHES) {
            return; // material shortage is not a proposal failure; supplies will catch up
        }
        BlockPos nearBankColumn = StraightLineProbe.columnAt(anchor, target,
                decision.gapStartInclusive() - 1);
        BlockPos nearBankFoot = StraightLineProbe.surfaceFoot(level, settlementId,
                nearBankColumn, anchor.getY());
        Direction direction = dominantDirection(anchor, target);
        var result = TransportCoordinator.startWoodBridge(level, settlementId, nearBankFoot, direction);
        if (!result.accepted()) {
            // BridgePlanner's strict survey rejected the crossing; fall back to a road,
            // exactly as the design's risk note prescribes.
            var fallback = TransportCoordinator.startRoad(level, settlementId, target);
            if (!fallback.accepted()) {
                // Neither corridor works; park the target so the demand tier falls back
                // to READY and expansion can claim land toward it.
                traffic.defer(target, level.getGameTime() + DEFERRAL_TICKS);
            }
        }
    }

    /**
     * Widen the heaviest finished road that qualifies and whose added cells are all safe. A rejection
     * is not a failure: the next cycle tries the next candidate, and nothing is parked because a
     * widening touches no facility. Material shortage is not a failure either -- supplies catch up.
     */
    private static void proposeWidening(ServerLevel level, SettlementSavedData settlement,
                                        TransportSavedData traffic, String settlementId) {
        if (PublicWarehouseInventory.countOf(level, settlement, Items.OAK_PLANKS) < ROAD_MIN_PLANKS) {
            return;
        }
        for (TransportPlan road : traffic.wideningCandidates()) {
            if (TransportCoordinator.startWidening(level, settlementId, road).accepted()) {
                return;
            }
        }
    }

    private static Direction dominantDirection(BlockPos anchor, BlockPos target) {
        int dx = target.getX() - anchor.getX();
        int dz = target.getZ() - anchor.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
