package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.colony.ResidentWorkLookup;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Counts how often residents walk the settlement's finished roads. The counts are what the widening
 * round will set its thresholds from, so this only measures and persists: it changes no block.
 */
public final class TrafficSampler {
    /**
     * Ticks between samples. INVENTED, and it is half of the judgement: RoadUpgradeRules compares
     * sample hits against a threshold, so changing this changes what that threshold means. Re-choose
     * both together (TRAFFIC_UPGRADE_DESIGN section 2).
     */
    private static final int SAMPLE_INTERVAL_TICKS = 100;

    private static final WeakHashMap<TransportSavedData, Snapshot> SNAPSHOTS = new WeakHashMap<>();

    /** Road surface blocks for one owner and one revision of the plan list. */
    private record Snapshot(TransportSavedData owner, int revision, Map<BlockPos, String> roadSurface) {
    }

    private TrafficSampler() {
    }

    /** Register on END_WORLD_TICK, inside the settlement gate. Never loads a chunk. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % SAMPLE_INTERVAL_TICKS != 0) {
            return;
        }
        TransportSavedData traffic = TransportSavedData.get(level);
        Map<BlockPos, String> roadSurface = surfaceIndex(traffic);
        if (roadSurface.isEmpty()) {
            return;
        }
        var hits = new HashMap<String, Integer>();
        for (GoblinCitizenEntity goblin
                : ResidentWorkLookup.loaded(level, SettlementSavedData.get(level))) {
            String planId = roadSurface.get(goblin.blockPosition().below());
            if (planId != null) {
                hits.merge(planId, 1, Integer::sum);
            }
        }
        traffic.recordTraffic(hits);
    }

    /**
     * The SURFACE cell of every finished road, keyed by the block a walker stands on. Rebuilt only when
     * the plan list changes; the owner check also covers a reload, which hands out a new instance whose
     * revision counter has started over.
     */
    private static Map<BlockPos, String> surfaceIndex(TransportSavedData traffic) {
        Snapshot snapshot = SNAPSHOTS.get(traffic);
        if (snapshot != null && snapshot.owner() == traffic && snapshot.revision() == traffic.revision()) {
            return snapshot.roadSurface();
        }
        var index = new HashMap<BlockPos, String>();
        for (TransportPlan plan : traffic.plans()) {
            if (plan.kind() != TransportPlan.Kind.ROAD || !plan.isComplete()) {
                continue;
            }
            for (TransportPlan.Step step : plan.steps()) {
                if (step.phase() == TransportPlan.Phase.SURFACE) {
                    index.put(step.site(), plan.id());
                }
            }
        }
        var rebuilt = new Snapshot(traffic, traffic.revision(), Map.copyOf(index));
        SNAPSHOTS.put(traffic, rebuilt);
        return rebuilt.roadSurface();
    }
}
