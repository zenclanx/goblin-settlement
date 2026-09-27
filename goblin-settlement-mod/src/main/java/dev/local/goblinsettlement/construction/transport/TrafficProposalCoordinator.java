package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.colony.SettlementDemand;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
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
    private static final int ROAD_MIN_PLANKS = 8;
    private static final int BRIDGE_MIN_PLANKS = 8;
    private static final int BRIDGE_MIN_LOGS = 4;
    private static final int BRIDGE_MIN_FENCES = 4;
    private static final int BRIDGE_MIN_TORCHES = 4;

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

    /** Register with END_WORLD_TICK, before TransportCoordinator and ExpansionCoordinator. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % PROPOSAL_INTERVAL_TICKS != 0) {
            return;
        }
        var settlement = SettlementSavedData.get(level);
        var traffic = TransportSavedData.get(level);
        if (settlement.settlement().isEmpty() || traffic.hasIncomplete()) {
            return;                                        // gate 2: one in-flight traffic plan
        }
        if (settlement.plans().stream().anyMatch(plan -> !plan.isComplete())) {
            return;                                        // 3.1a symmetry: yield to active building work
        }
        String settlementId = settlement.settlement().orElseThrow().id();
        var supply = PublicWarehouseInventory.snapshot(level, settlement);
        var demand = SettlementDemand.assess(settlement.adultCount(), settlement.childCount(),
                supply, false, hasPendingTarget(settlement, traffic));
        if (demand.priority() != SettlementDemand.Priority.TRANSPORT) {
            return;                                        // gate 1: demand tier
        }
        var target = nearestUnservedFacility(settlement, traffic);
        if (target.isEmpty()) {
            return;
        }
        BlockPos anchor = settlement.settlement().orElseThrow().anchor();
        var sample = StraightLineProbe.sample(level, settlementId, anchor, target.orElseThrow());
        var decision = TrafficDecision.decide(sample.columns(), sample.targetIndex(),
                BridgePlanner.MIN_WOOD_SPAN, BridgePlanner.MAX_WOOD_SPAN);
        switch (decision.kind()) {
            case BRIDGE -> proposeBridge(level, settlement, settlementId, anchor,
                    target.orElseThrow(), sample, decision);
            case ROAD -> {
                if (PublicWarehouseInventory.countOf(level, settlement, Items.OAK_PLANKS)
                        >= ROAD_MIN_PLANKS) {              // gate 3: real materials
                    TransportCoordinator.startRoad(level, settlementId, target.orElseThrow());
                }
            }
            case NONE -> { }
        }
    }

    private static void proposeBridge(ServerLevel level, SettlementSavedData settlement,
                                      String settlementId, BlockPos anchor, BlockPos target,
                                      StraightLineProbe.Sample sample, TrafficDecision.Decision decision) {
        if (PublicWarehouseInventory.countOf(level, settlement, Items.OAK_PLANKS) < BRIDGE_MIN_PLANKS
                || PublicWarehouseInventory.countOf(level, settlement, Items.OAK_LOG) < BRIDGE_MIN_LOGS
                || PublicWarehouseInventory.countOf(level, settlement, Items.OAK_FENCE) < BRIDGE_MIN_FENCES
                || PublicWarehouseInventory.countOf(level, settlement, Items.TORCH) < BRIDGE_MIN_TORCHES) {
            return;
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
            TransportCoordinator.startRoad(level, settlementId, target);
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
