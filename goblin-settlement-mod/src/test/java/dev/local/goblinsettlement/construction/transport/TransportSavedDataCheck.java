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

        var chained = new TransportSavedData();
        TransportPlan base = roadPlan("chain-base", facility, 1, false, 2, "chain-base");
        require(chained.add(base), "the base road is accepted");
        chained.recordTraffic(Map.of("chain-base", 300));
        require(chained.roadWidth("chain-base") == 2, "a lone road is as wide as it was built");
        require(chained.roadTraffic("chain-base") == 300, "a lone road reports its own samples");
        require(chained.wideningCandidates().size() == 1, "and it qualifies for its first widening");

        TransportPlan widened = roadPlan("chain-widened", facility, 1, false, 3, "chain-base");
        require(chained.add(widened), "the widening plan is accepted");
        chained.recordTraffic(Map.of("chain-widened", 90));
        require(chained.roadWidth("chain-base") == 3, "the chain reports its widest member");
        require(chained.roadWidth("chain-widened") == 3, "from either end of the chain");
        require(chained.roadTraffic("chain-base") == 390, "the chain sums every member's samples");
        require(chained.roadTraffic("chain-widened") == 390, "so a widening never resets the road's traffic");
        require(chained.wideningCandidates().isEmpty(),
                "three lanes and 390 samples is short of the two shares a road needs");
        require(chained.roads().size() == 1, "the chain is one road, not two");
        require(chained.roads().get(0).id().equals("chain-widened"),
                "and the widest member represents it");

        chained.recordTraffic(Map.of("chain-base", 200));
        require(chained.wideningCandidates().size() == 1, "past two shares it qualifies again");
        TransportPlan street = roadPlan("chain-street", facility, 1, false, 5, "chain-widened");
        require(chained.add(street), "the second widening is accepted");
        chained.recordTraffic(Map.of("chain-street", 4000));
        require(chained.roadWidth("chain-base") == 5, "the widest street is the chain's width");
        require(chained.wideningCandidates().isEmpty(), "and the widest street never widens again");

        var missingRoute = new TransportSavedData();
        require(missingRoute.add(roadPlan("no-route", facility, 1, false)), "an old-style road loads");
        require(missingRoute.plan("no-route").orElseThrow().lanes() == 2,
                "a plan written before widening is two lanes");
        require(missingRoute.plan("no-route").orElseThrow().route().isEmpty(),
                "and has no centerline, so it can never be widened");
        require(missingRoute.plan("no-route").orElseThrow().widensFrom().isEmpty(),
                "and widens nothing");

        var chainedJson = TransportSavedData.CODEC.encodeStart(JsonOps.INSTANCE, chained).getOrThrow();
        var chainedReload = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, chainedJson).getOrThrow();
        require(chainedReload.roadWidth("chain-base") == 5, "chain widths survive reload");
        require(chainedReload.roadTraffic("chain-base") == 300 + 200 + 90 + 4000,
                "chain traffic survives reload");
        require(chainedReload.plan("chain-street").orElseThrow().route()
                        .equals(chained.plan("chain-street").orElseThrow().route()),
                "the centerline survives reload");
        JsonObject withoutRoad = chainedJson.getAsJsonObject().deepCopy();
        withoutRoad.getAsJsonArray("plans").forEach(element ->
                element.getAsJsonObject().remove("road"));
        var earlier = TransportSavedData.CODEC.parse(JsonOps.INSTANCE, withoutRoad).getOrThrow();
        require(earlier.roadWidth("chain-base") == 2, "a save without the road field falls back to two lanes");
        require(earlier.roadTraffic("chain-base") == 500, "and without a chain it keeps its own samples");

        var wideningInProgress = new TransportSavedData();
        require(wideningInProgress.add(roadPlan("width-base", facility, 1, false, 2, "width-base")),
                "the finished base road is accepted");
        require(wideningInProgress.add(roadPlan("width-widened", facility, 0, false, 3, "width-base")),
                "the widening under construction is accepted");
        require(wideningInProgress.roadWidth("width-base") == 2,
                "an unfinished widening does not widen the road yet");
        require(wideningInProgress.roads().size() == 1,
                "the chain is still one road");
        require(wideningInProgress.roads().get(0).id().equals("width-base"),
                "and the finished base represents it");

        var retirable = new TransportSavedData();
        for (int index = 0; index < 40; index++) {
            String id = "plain-" + index;
            require(retirable.add(roadPlan(id, facility, 0, false)), "plain road " + index + " accepted");
            require(retirable.replace(roadPlan(id, facility, 1, false)), "plain road " + index + " completed");
        }
        require(retirable.plan("plain-0").isEmpty(),
                "plain roads are still retired once there are too many");
        var protectedChain = new TransportSavedData();
        require(protectedChain.add(roadPlan("keep-base", facility, 0, false, 2, "keep-base")),
                "chain base accepted");
        require(protectedChain.replace(roadPlan("keep-base", facility, 1, false, 2, "keep-base")),
                "chain base completed");
        require(protectedChain.add(roadPlan("keep-child", facility, 0, false, 3, "keep-base")),
                "chain child accepted");
        require(protectedChain.replace(roadPlan("keep-child", facility, 1, false, 3, "keep-base")),
                "chain child completed");
        for (int index = 0; index < 40; index++) {
            String id = "filler-" + index;
            require(protectedChain.add(roadPlan(id, facility, 0, false)), "filler " + index + " accepted");
            require(protectedChain.replace(roadPlan(id, facility, 1, false)), "filler " + index + " completed");
        }
        require(protectedChain.plan("keep-base").isPresent(), "a widened road is never retired");
        require(protectedChain.plan("keep-child").isPresent(), "nor is the widening that widened it");

        var targets = new TransportSavedData();
        require(targets.add(roadPlan("target-base", facility, 1, false, 2, "target-base")),
                "the road with a target is accepted");
        require(targets.add(roadPlan("target-child", facility, 1, false, 3, "target-base")),
                "its widening is accepted");
        require(targets.plan("target-child").orElseThrow().targetFacility().isEmpty(),
                "a widening plan names no facility of its own");
        require(targets.roadTarget("target-base").orElseThrow().equals(facility),
                "the road reports the facility it was built for");
        require(targets.roadTarget("target-child").orElseThrow().equals(facility),
                "and its widening answers with the same facility, from the root");
        require(targets.chainOf("target-base").size() == 2, "the chain lists both members");
        require(targets.chainOf("target-child").size() == 2, "from either end");
        require(targets.chainOf("nobody").isEmpty(), "an unknown id belongs to no chain");
        require(targets.roadTarget("nobody").isEmpty(), "and reaches no facility");

        System.out.println("TransportSavedDataCheck passed");
    }

    private static TransportPlan roadPlan(String id, BlockPos target, int completedSteps, boolean open) {
        return plan(id, target, completedSteps, open, Optional.empty());
    }

    private static TransportPlan roadPlan(String id, BlockPos target, int completedSteps, boolean open,
                                          int lanes, String widensFrom) {
        boolean widens = !widensFrom.equals(id);
        var route = List.of(new BlockPos(10, 64, 10), new BlockPos(11, 64, 10));
        return new TransportPlan(id, "settlement-1", TransportPlan.Kind.ROAD, List.of(step()),
                completedSteps, open, List.of(), List.of(), List.of(), Optional.empty(),
                widens ? Optional.<BlockPos>empty() : Optional.of(target),
                Optional.of(new TransportPlan.Road(widens ? Optional.of(widensFrom) : Optional.empty(),
                        lanes, route)));
    }

    private static TransportPlan plan(String id, BlockPos target, int completedSteps, boolean open,
                                      Optional<TransportPlan.Road> road) {
        TransportPlan.Step step = step();
        return new TransportPlan(id, "settlement-1", TransportPlan.Kind.ROAD, List.of(step),
                completedSteps, open, List.of(), List.of(), List.of(), Optional.empty(), Optional.of(target),
                road);
    }

    private static TransportPlan.Step step() {
        return new TransportPlan.Step(TransportPlan.Phase.SURFACE,
                new BlockPos(10, 64, 10), TransportPlan.Material.OAK_PLANKS, TransportPlan.Rule.ROAD_GROUND);
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
                List.of(new BlockPos(5, 65, 1)), List.of(), Optional.empty(), Optional.empty(),
                Optional.empty());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
