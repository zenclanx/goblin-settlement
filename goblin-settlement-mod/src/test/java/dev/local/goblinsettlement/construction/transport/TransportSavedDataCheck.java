package dev.local.goblinsettlement.construction.transport;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import java.util.List;
import java.util.Map;
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

        var closing = new TransportSavedData();
        TransportPlan readyBridge = bridgePlan("bridge-2", 1, false);
        require(closing.add(readyBridge), "a fully built but unopened bridge loads");
        require(closing.replace(readyBridge.withOpen(true)), "opening completes the bridge");
        require(closing.servedFacilities().isEmpty(), "a targetless bridge completion registers nothing");

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
        require(old.deferredFacilities(0L).isEmpty(), "old saves load without invented deferrals");

        require(road.advance().targetFacility().equals(Optional.of(facility)), "advance keeps the target");
        require(road.rewind(0).targetFacility().equals(Optional.of(facility)), "rewind keeps the target");
        require(road.withWorker(Optional.of("w")).targetFacility().equals(Optional.of(facility)),
                "worker handoff keeps the target");
        require(road.withOpen(false).targetFacility().equals(Optional.of(facility)),
                "closure updates keep the target");

        var repeat = new TransportSavedData();
        require(repeat.add(roadPlan("sel-1", facility, 0, false)), "repeat fixture accepted");
        TransportPlan repeatComplete = roadPlan("sel-1", facility, 1, false);
        require(repeat.replace(repeatComplete), "repeat fixture completes");
        require(repeat.replace(repeatComplete), "repeat completion accepted");
        require(repeat.servedFacilities().size() == 1, "repeat completion does not duplicate the entry");
        require(repeat.replace(repeatComplete.rewind(0)), "a rewind reopens the completed road");
        require(repeat.replace(repeatComplete), "re-completing registers the same target again");
        require(repeat.servedFacilities().size() == 1, "re-completion deduplicates the served entry");

        var settlement = new SettlementSavedData();
        require(settlement.found(new BlockPos(0, 70, 0)) == SettlementSavedData.FoundResult.FOUNDED,
                "query fixture founded");
        require(settlement.registerWarehouse(new BlockPos(20, 70, 0)), "query fixture warehouse");
        require(settlement.registerFarmSite(new BlockPos(15, 71, 5)), "query fixture farm");
        var fresh = new TransportSavedData();
        require(TrafficProposalCoordinator.nearestUnservedFacility(settlement, fresh, 0L)
                        .orElseThrow().equals(new BlockPos(15, 71, 5)),
                "nearest unserved facility shared query");
        var nearSettlement = new SettlementSavedData();
        require(nearSettlement.found(new BlockPos(0, 70, 0)) == SettlementSavedData.FoundResult.FOUNDED,
                "near fixture founded");
        require(nearSettlement.registerWarehouse(new BlockPos(5, 70, 0)), "near fixture warehouse");
        require(!TrafficProposalCoordinator.hasPendingTarget(nearSettlement, fresh, 0L),
                "a warehouse next to the anchor is not a paving target");

        var parked = new TransportSavedData();
        parked.defer(facility, 24000L);
        require(parked.isDeferred(facility, 23999L), "a facility is deferred until its unlock tick");
        require(!parked.isDeferred(facility, 24000L), "the unlock tick itself ends the deferral");
        require(parked.deferredFacilities(23999L).equals(List.of(facility)),
                "the still-deferred facility is listed before expiry");
        require(parked.deferredFacilities(24000L).isEmpty(),
                "an expired entry is pruned by the read");
        require(!parked.isDeferred(facility, 24000L), "pruning ends the deferral window");
        parked.defer(facility, 24000L);
        parked.defer(facility, 48000L);
        require(parked.deferredFacilities(23000L).equals(List.of(facility)),
                "re-deferral replaces the earlier unlock tick");

        var deferralJson = TransportSavedData.CODEC.encodeStart(JsonOps.INSTANCE, parked).getOrThrow();
        var deferralReload = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, deferralJson).getOrThrow();
        require(deferralReload.isDeferred(facility, 47999L), "deferral entries survive reload");
        require(!deferralReload.isDeferred(facility, 48000L), "reloaded entries keep their unlock tick");

        var parkedTarget = new TransportSavedData();
        parkedTarget.defer(new BlockPos(15, 71, 5), 24000L);
        require(TrafficProposalCoordinator.nearestUnservedFacility(settlement, parkedTarget, 23999L)
                        .orElseThrow().equals(new BlockPos(20, 70, 0)),
                "a deferred facility yields to the next unserved candidate");
        require(TrafficProposalCoordinator.nearestUnservedFacility(settlement, parkedTarget, 24000L)
                        .orElseThrow().equals(new BlockPos(15, 71, 5)),
                "an expired deferral returns the nearest facility to candidacy");
        var allParked = new TransportSavedData();
        allParked.defer(new BlockPos(15, 71, 5), 24000L);
        allParked.defer(new BlockPos(20, 70, 0), 24000L);
        require(!TrafficProposalCoordinator.hasPendingTarget(settlement, allParked, 23999L),
                "deferred facilities are not pending targets");
        require(TrafficProposalCoordinator.hasPendingTarget(settlement, allParked, 24000L),
                "expired deferrals restore pending targets");
        var counted = new TransportSavedData();
        require(counted.add(roadPlan("traffic-1", facility, 0, false)), "traffic fixture accepted");
        require(counted.replace(roadPlan("traffic-1", facility, 1, false)), "traffic fixture completes");
        counted.recordTraffic(Map.of("traffic-1", 5));
        counted.recordTraffic(Map.of("traffic-1", 2));
        require(counted.traffic().get("traffic-1") == 7, "sample hits accumulate");
        counted.recordTraffic(Map.of("traffic-1", 0));
        require(counted.traffic().get("traffic-1") == 7, "a zero-hit round changes nothing");

        var countedJson = TransportSavedData.CODEC.encodeStart(JsonOps.INSTANCE, counted).getOrThrow();
        var countedReload = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, countedJson).getOrThrow();
        require(countedReload.traffic().get("traffic-1") == 7, "counts survive reload");
        JsonObject withoutTraffic = countedJson.getAsJsonObject().deepCopy();
        withoutTraffic.remove("traffic");
        require(TransportSavedData.CODEC.parse(JsonOps.INSTANCE, withoutTraffic).getOrThrow()
                .traffic().isEmpty(), "an old save loads without invented counts");
        JsonObject orphaned = countedJson.getAsJsonObject().deepCopy();
        orphaned.getAsJsonObject("traffic").addProperty("gone-1", 9);
        var orphanedReload = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, orphaned).getOrThrow();
        require(!orphanedReload.traffic().containsKey("gone-1"),
                "a counter with no matching plan is pruned on load");
        require(orphanedReload.traffic().containsKey("traffic-1"),
                "while the counter whose plan is known stays");

        var retired = new TransportSavedData();
        for (int index = 0; index < 33; index++) {
            String id = "road-" + index;
            require(retired.add(roadPlan(id, facility, 0, false)), "road " + index + " accepted");
            require(retired.replace(roadPlan(id, facility, 1, false)), "road " + index + " completed");
            retired.recordTraffic(Map.of(id, index + 1));
        }
        require(retired.plans().size() == 32, "completed roads are capped at 32");
        require(!retired.traffic().containsKey("road-0"), "a retired road's counter is pruned");
        require(retired.traffic().containsKey("road-32"), "the newest road keeps its counter");

        System.out.println("TransportSavedDataCheck passed");
    }

    private static TransportPlan roadPlan(String id, BlockPos target, int completedSteps, boolean open) {
        var step = new TransportPlan.Step(TransportPlan.Phase.SURFACE,
                new BlockPos(10, 64, 10), TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.ROAD_GROUND);
        return new TransportPlan(id, "settlement-1", TransportPlan.Kind.ROAD, List.of(step),
                completedSteps, open, List.of(), List.of(), List.of(), Optional.empty(), Optional.of(target));
    }

    private static TransportPlan bridgePlan(String id, boolean open) {
        return bridgePlan(id, open ? 1 : 0, open);
    }

    private static TransportPlan bridgePlan(String id, int completedSteps, boolean open) {
        var step = new TransportPlan.Step(TransportPlan.Phase.SURFACE,
                new BlockPos(10, 64, 10), TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.AIR_OR_WATER);
        return new TransportPlan(id, "settlement-1", TransportPlan.Kind.WOOD_BRIDGE, List.of(step),
                completedSteps, open,
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
