package dev.local.goblinsettlement.construction.transport;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;

public final class TransportSavedDataCheck {
    public static void main(String[] args) {
        BlockPos facility = new BlockPos(30, 64, 10);
        TransportPlan road = roadPlan("road-1", facility, 0, false);
        var data = new TransportSavedData();
        require(data.add(road), "road plan accepted");
        require(data.hasIncomplete(), "incomplete plan blocks new proposals");
        require(!data.add(roadPlan("road-2", facility.east(), 0, false)), "one in-flight plan at a time");
        require(data.servedFacilities().isEmpty(), "nothing served before completion");
        require(data.replace(road.advance()), "final step completes the road");
        require(!data.hasIncomplete(), "completed plan leaves the active index");
        require(data.servedFacilities().equals(List.of(facility)), "road completion registers its target");

        var bridge = new TransportSavedData();
        TransportPlan completedBridge = bridgePlan("bridge-1", true);
        require(bridge.add(completedBridge), "an open bridge plan loads");
        require(bridge.replace(completedBridge), "replacing a complete plan is idempotent");
        require(bridge.servedFacilities().isEmpty(), "a targetless bridge registers nothing");

        var json = TransportSavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        var reloaded = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        require(reloaded.servedFacilities().equals(data.servedFacilities()), "served facilities survive reload");
        require(reloaded.plans().get(0).targetFacility().equals(Optional.of(facility)),
                "target facility survives reload");
        JsonObject legacy = json.getAsJsonObject().deepCopy();
        legacy.remove("served_facilities");
        legacy.getAsJsonArray("plans").get(0).getAsJsonObject().remove("target_facility");
        var old = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, legacy).getOrThrow();
        require(old.servedFacilities().isEmpty(), "old saves load without invented served facilities");
        require(old.plans().get(0).targetFacility().isEmpty(), "old plans load without an invented target");

        require(road.advance().targetFacility().equals(Optional.of(facility)), "advance keeps the target");
        require(road.rewind(0).targetFacility().equals(Optional.of(facility)), "rewind keeps the target");
        require(road.withWorker(Optional.of("w")).targetFacility().equals(Optional.of(facility)),
                "worker handoff keeps the target");
        require(road.withOpen(false).targetFacility().equals(Optional.of(facility)),
                "closure updates keep the target");

        var repeat = new TransportSavedData();
        require(repeat.add(roadPlan("sel-1", facility, 0, false)), "repeat fixture accepted");
        require(repeat.replace(roadPlan("sel-1", facility, 1, false)), "repeat fixture completes");
        require(repeat.replace(roadPlan("sel-1", facility, 1, false)), "repeat completion accepted");
        require(repeat.servedFacilities().size() == 1, "repeat completion does not duplicate the entry");

        var settlement = new SettlementSavedData();
        require(settlement.found(new BlockPos(0, 70, 0)) == SettlementSavedData.FoundResult.FOUNDED,
                "query fixture founded");
        require(settlement.registerWarehouse(new BlockPos(20, 70, 0)), "query fixture warehouse");
        require(settlement.registerFarmSite(new BlockPos(15, 71, 5)), "query fixture farm");
        var fresh = new TransportSavedData();
        require(TrafficProposalCoordinator.nearestUnservedFacility(settlement, fresh)
                        .orElseThrow().equals(new BlockPos(15, 71, 5)),
                "nearest unserved facility shared query");
        var nearSettlement = new SettlementSavedData();
        require(nearSettlement.found(new BlockPos(0, 70, 0)) == SettlementSavedData.FoundResult.FOUNDED,
                "near fixture founded");
        require(nearSettlement.registerWarehouse(new BlockPos(5, 70, 0)), "near fixture warehouse");
        require(!TrafficProposalCoordinator.hasPendingTarget(nearSettlement, fresh),
                "a warehouse next to the anchor is not a paving target");
        System.out.println("TransportSavedDataCheck passed");
    }

    private static TransportPlan roadPlan(String id, BlockPos target, int completedSteps, boolean open) {
        var step = new TransportPlan.Step(TransportPlan.Phase.SURFACE,
                new BlockPos(10, 64, 10), TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.ROAD_GROUND);
        return new TransportPlan(id, "settlement-1", TransportPlan.Kind.ROAD, List.of(step),
                completedSteps, open, List.of(), List.of(), List.of(), Optional.empty(), Optional.of(target));
    }

    private static TransportPlan bridgePlan(String id, boolean open) {
        var step = new TransportPlan.Step(TransportPlan.Phase.SURFACE,
                new BlockPos(10, 64, 10), TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.AIR_OR_WATER);
        return new TransportPlan(id, "settlement-1", TransportPlan.Kind.WOOD_BRIDGE, List.of(step),
                open ? 1 : 0, open,
                List.of(new BlockPos(1, 64, 1), new BlockPos(2, 64, 1),
                        new BlockPos(3, 64, 1), new BlockPos(4, 64, 1)),
                List.of(new BlockPos(5, 65, 1)), List.of(), Optional.empty(), Optional.empty());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
