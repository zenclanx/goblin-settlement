package dev.local.goblinsettlement.colony;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.local.goblinsettlement.interaction.ProtectedRectangle;
import net.minecraft.core.BlockPos;

public final class SettlementSavedDataCheck {
    public static void main(String[] args) {
        var data = new SettlementSavedData();
        var home = ProtectedRectangle.fromCorners(-16, -16, -9, -9);
        require(data.protect("player-1", home), "first protection registered");
        require(!data.protect("player-1", home), "duplicate protection rejected");
        require(data.found(new BlockPos(-8, 64, -8)) == SettlementSavedData.FoundResult.PLAYER_AREA_CONFLICT,
                "founding plot respects buffer");
        require(data.found(new BlockPos(8, 70, 8)) == SettlementSavedData.FoundResult.FOUNDED,
                "settlement founded outside protection");
        require(data.isClaimed(new BlockPos(15, -64, 15)), "starting plot covers every height");
        require(!data.isClaimed(new BlockPos(16, 70, 15)), "adjacent plot remains unclaimed");
        require(data.found(new BlockPos(24, 70, 24)) == SettlementSavedData.FoundResult.ALREADY_EXISTS,
                "founding twice preserves identity");
        require(data.registerAdult("resident-adult"), "resident joins the founded settlement");
        require(!data.registerAdult("resident-adult"), "repeated entity ticks do not duplicate residents");
        require(data.adultCount() == 1 && data.childCount() == 0, "only living adults count for land");
        var rosterJson = SettlementSavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        var rosterReloaded = SettlementSavedData.CODEC.parse(JsonOps.INSTANCE, rosterJson).getOrThrow();
        require(rosterReloaded.residents().equals(data.residents()), "unloaded roster survives reload");
        require(data.markResidentDead("resident-adult"), "death updates the resident record");
        require(!data.markResidentDead("resident-adult"), "repeat death does not change population twice");
        require(data.adultCount() == 0 && data.residents().size() == 1,
                "deceased resident remains in family history without granting land");

        BlockPos chest = new BlockPos(9, 71, 9);
        BlockPos site = new BlockPos(11, 71, 11);
        require(data.registerWarehouse(chest), "public chest registered");
        require(!data.registerWarehouse(chest), "duplicate public chest rejected");
        require(data.planTwoPlanks(site), "two-block project accepted");
        require(!data.planTwoPlanks(site), "active project not replaced");
        require(data.plan().orElseThrow().site().equals(site), "first project site");
        require(data.assignWorker("resident-1"), "worker reserved for first site");
        require(!data.assignWorker("resident-2"), "one worker owns active site");
        require(!data.finishStep("resident-2", site), "wrong worker cannot complete site");
        require(!data.finishStep("resident-1", site.east()), "wrong site cannot claim progress");
        require(data.finishStep("resident-1", site), "first site completed");
        require(data.plan().orElseThrow().site().equals(site.east()), "second site follows first");
        require(data.plan().orElseThrow().lastWorkerId().equals(java.util.Optional.of("resident-1")),
                "completed step records its worker for the next assignment");
        var handoffJson = SettlementSavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        var handoffLoaded = SettlementSavedData.CODEC.parse(JsonOps.INSTANCE, handoffJson).getOrThrow();
        require(handoffLoaded.plan().orElseThrow().lastWorkerId().equals(java.util.Optional.of("resident-1")),
                "last worker survives reload before handoff");
        require(data.assignWorker("resident-2"), "second site assigned");
        BlockPos dropPos = new BlockPos(10, 71, 10);
        require(!data.recordRecoverableDrop("resident-3", "dropped-plank", dropPos),
                "other resident cannot attach a dropped item");
        require(data.recordRecoverableDrop("resident-2", "dropped-plank", dropPos),
                "dead worker records the real dropped item and releases site");
        require(data.plan().orElseThrow().recoveryDrop().orElseThrow().pos().equals(dropPos),
                "drop location retained");
        var recoveryJson = SettlementSavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        var recoveryLoaded = SettlementSavedData.CODEC.parse(JsonOps.INSTANCE, recoveryJson).getOrThrow();
        require(recoveryLoaded.plan().orElseThrow().recoveryDrop().equals(data.plan().orElseThrow().recoveryDrop()),
                "dropped item identity survives reload");
        require(data.retargetRecoveryDrop("dropped-plank", "merged-stack", dropPos.east()),
                "merged item receives a new tracked identity");
        require(data.plan().orElseThrow().recoveryDrop().orElseThrow().itemId().equals("merged-stack"),
                "recovery follows merged stack");
        require(!data.retargetRecoveryDrop("dropped-plank", "other-stack", dropPos),
                "stale identity cannot overwrite recovery target");
        require(data.assignWorker("resident-3"), "recovery task reserves a resident");
        require(!data.finishRecovery("resident-4"), "other resident cannot finish recovery");
        require(data.finishRecovery("resident-3"), "recovery clears the item reference and worker");
        require(data.plan().orElseThrow().recoveryDrop().isEmpty(), "recovered drop no longer pending");
        require(data.assignWorker("resident-4"), "unfinished site can be reassigned");
        require(data.releaseWorker("resident-4"), "worker release preserves unfinished site");
        require(data.plan().orElseThrow().completed() == 1, "recovery does not claim construction progress");
        require(data.plan().orElseThrow().workerId().isEmpty(), "unfinished site available again");

        String id = data.settlement().orElseThrow().id();
        var json = SettlementSavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
        var restored = SettlementSavedData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        require(restored.settlement().orElseThrow().id().equals(id), "stable settlement ID round trip");
        require(restored.settlement().orElseThrow().anchor().equals(new BlockPos(8, 70, 8)),
                "anchor round trip");
        require(restored.claimedPlots().equals(data.claimedPlots()), "claims round trip");
        require(restored.playerAreas().equals(data.playerAreas()), "player areas round trip");
        require(restored.warehouses().equals(data.warehouses()), "public chest round trip");
        require(restored.plan().equals(data.plan()), "project progress round trip");

        JsonObject oldSchema = json.getAsJsonObject().deepCopy();
        oldSchema.remove("claimed_plots");
        oldSchema.remove("player_areas");
        oldSchema.remove("warehouses");
        oldSchema.remove("plan");
        oldSchema.remove("plans");
        var loadedOld = SettlementSavedData.CODEC.parse(JsonOps.INSTANCE, oldSchema).getOrThrow();
        require(loadedOld.settlement().orElseThrow().id().equals(id), "older version-one data loads");
        require(loadedOld.claimedPlots().isEmpty(), "old data does not gain automatic claims");
        require(loadedOld.warehouses().isEmpty(), "old data has no registered public chest");
        require(loadedOld.plan().isEmpty(), "old data has no project");
        oldSchema.remove("residents");
        var beforeRoster = SettlementSavedData.CODEC.parse(JsonOps.INSTANCE, oldSchema).getOrThrow();
        require(beforeRoster.residents().isEmpty(), "pre-roster saves load without invented residents");

        var cancellation = new SettlementSavedData();
        require(cancellation.found(new BlockPos(0, 70, 0)) == SettlementSavedData.FoundResult.FOUNDED,
                "cancellation fixture founded");
        require(cancellation.planTwoPlanks(new BlockPos(1, 71, 1)), "cancellable project recorded");
        require(cancellation.assignWorker("missing-resident"), "missing resident reserved");
        require(cancellation.cancelPlan(), "active project can be cancelled");
        require(cancellation.plan().isEmpty(), "cancelled project no longer blocks new plans");
        require(cancellation.isCancelledWorker("missing-resident"), "unloaded worker has a persisted cancellation");
        var cancelledJson = SettlementSavedData.CODEC.encodeStart(JsonOps.INSTANCE, cancellation).getOrThrow();
        var cancelledReloaded = SettlementSavedData.CODEC.parse(JsonOps.INSTANCE, cancelledJson).getOrThrow();
        require(cancelledReloaded.isCancelledWorker("missing-resident"), "cancellation survives save reload");
        require(cancelledReloaded.acknowledgeCancelledWorker("missing-resident"),
                "worker can acknowledge cancellation after loading");
        require(!cancelledReloaded.isCancelledWorker("missing-resident"), "acknowledged worker is no longer blocked");
        require(cancelledReloaded.planTwoPlanks(new BlockPos(2, 71, 1)), "new project can follow cancellation");

        var parallel = new SettlementSavedData();
        require(parallel.found(new BlockPos(0, 70, 0)) == SettlementSavedData.FoundResult.FOUNDED,
                "parallel fixture founded");
        BlockPos firstStart = new BlockPos(1, 71, 1);
        BlockPos secondStart = new BlockPos(1, 71, 4);
        require(parallel.planTwoPlanks(firstStart), "first parallel project recorded");
        require(parallel.planTwoPlanks(secondStart), "second parallel project recorded");
        require(!parallel.planTwoPlanks(firstStart.east()), "overlapping project rejected");
        require(parallel.plans().size() == 2, "both parallel projects retained");
        require(parallel.assignWorker(firstStart, "builder-a"), "first project reserves first worker");
        require(!parallel.assignWorker(secondStart, "builder-a"), "worker cannot own two projects");
        require(parallel.assignWorker(secondStart, "builder-b"), "second project reserves another worker");
        require(parallel.finishStep("builder-a", firstStart), "first project advances independently");
        require(parallel.plan(secondStart).orElseThrow().completed() == 0,
                "second project remains untouched by first completion");
        require(parallel.cancelPlan(secondStart), "specific second project cancelled");
        require(parallel.plan(firstStart).orElseThrow().completed() == 1,
                "cancelling second project preserves first");
        require(parallel.isCancelledWorker("builder-b"), "only second worker receives cancellation");
        var parallelJson = SettlementSavedData.CODEC.encodeStart(JsonOps.INSTANCE, parallel).getOrThrow();
        var parallelReloaded = SettlementSavedData.CODEC.parse(JsonOps.INSTANCE, parallelJson).getOrThrow();
        require(parallelReloaded.plans().equals(parallel.plans()), "multiple plans survive reload");

        var farm = new SettlementSavedData();
        require(farm.found(new BlockPos(0, 70, 0)) == SettlementSavedData.FoundResult.FOUNDED,
                "farm fixture founded");
        BlockPos crop = new BlockPos(1, 71, 1);
        require(farm.registerFarmSite(crop), "wheat cell registered");
        require(!farm.registerFarmSite(crop), "duplicate wheat cell rejected");
        require(!farm.planTwoPlanks(crop), "construction cannot overlap a farm cell");
        require(farm.assignFarmWorker(crop, "farmer"), "farm worker reserved");
        require(!farm.assignFarmWorker(crop, "other"), "farm cell cannot have two workers");
        require(farm.isFarmWorker("farmer"), "farm reservation visible to other work");
        var farmJson = SettlementSavedData.CODEC.encodeStart(JsonOps.INSTANCE, farm).getOrThrow();
        var farmReloaded = SettlementSavedData.CODEC.parse(JsonOps.INSTANCE, farmJson).getOrThrow();
        require(farmReloaded.farmSites().equals(farm.farmSites()), "farm and worker survive reload");
        require(farmReloaded.releaseFarmWorker("farmer"), "worker released after farm task");
        require(!farmReloaded.isFarmWorker("farmer"), "farm worker free for another task");
        var beforeFarms = farmJson.getAsJsonObject().deepCopy();
        beforeFarms.remove("farm_sites");
        require(SettlementSavedData.CODEC.parse(JsonOps.INSTANCE, beforeFarms).getOrThrow().farmSites().isEmpty(),
                "old saves load without invented farm cells");
        var legacyPlanJson = json.getAsJsonObject().deepCopy();
        legacyPlanJson.remove("plans");
        legacyPlanJson.add("plan", dev.local.goblinsettlement.construction.ConstructionPlan.CODEC
                .encodeStart(JsonOps.INSTANCE, data.plan().orElseThrow()).getOrThrow());
        var migrated = SettlementSavedData.CODEC.parse(JsonOps.INSTANCE, legacyPlanJson).getOrThrow();
        require(migrated.plans().size() == 1 && migrated.plans().getFirst().equals(data.plan().orElseThrow()),
                "legacy single-plan field migrates into the project list");
        System.out.println("SettlementSavedDataCheck passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
