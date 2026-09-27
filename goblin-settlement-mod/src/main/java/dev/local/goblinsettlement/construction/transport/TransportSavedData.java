package dev.local.goblinsettlement.construction.transport;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Per-dimension traffic plans, with transient indexes rebuilt after loading. */
public final class TransportSavedData extends SavedData {
    private static final int MAX_COMPLETED_ROADS = 32;

    /** A facility parked until a tick because every proposal for it failed once. */
    public record DeferredTarget(BlockPos facility, long untilTick) {
        static final Codec<DeferredTarget> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(BlockPos.CODEC.fieldOf("facility").forGetter(DeferredTarget::facility),
                        Codec.LONG.fieldOf("until_tick").forGetter(DeferredTarget::untilTick))
                        .apply(instance, DeferredTarget::new));

        public DeferredTarget {
            facility = facility.immutable();
        }
    }

    static final Codec<TransportSavedData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(TransportPlan.CODEC.listOf().optionalFieldOf("plans", List.of())
                            .forGetter(data -> data.plans),
                    BlockPos.CODEC.listOf().optionalFieldOf("served_facilities", List.of())
                            .forGetter(data -> data.servedFacilities),
                    DeferredTarget.CODEC.listOf().optionalFieldOf("deferred_targets", List.of())
                            .forGetter(data -> data.deferredTargets))
                    .apply(instance, TransportSavedData::new));
    private static final SavedDataType<TransportSavedData> TYPE =
            new SavedDataType<>("goblin_transport", TransportSavedData::new, CODEC, null);

    private List<TransportPlan> plans;
    private List<BlockPos> servedFacilities;
    private List<DeferredTarget> deferredTargets;
    private final List<TransportPlan> openBridges = new ArrayList<>();
    private final Map<BlockPos, Integer> closedFeet = new HashMap<>();
    private final Map<String, TransportPlan> incompletePlans = new LinkedHashMap<>();
    private int inspectionCursor;

    public TransportSavedData() {
        this(List.of(), List.of(), List.of());
    }

    private TransportSavedData(List<TransportPlan> plans, List<BlockPos> servedFacilities,
                               List<DeferredTarget> deferredTargets) {
        this.plans = List.copyOf(plans);
        this.servedFacilities = List.copyOf(servedFacilities);
        this.deferredTargets = List.copyOf(deferredTargets);
        for (TransportPlan plan : this.plans) {
            index(plan);
        }
    }

    public static TransportSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<TransportPlan> plans() {
        return plans;
    }

    public Optional<TransportPlan> plan(String id) {
        return plans.stream().filter(plan -> plan.id().equals(id)).findFirst();
    }

    public List<BlockPos> servedFacilities() {
        return servedFacilities;
    }

    /** Park a facility until untilTick after every proposal for it failed once;
     *  re-deferring the same facility replaces its earlier unlock tick. */
    public void defer(BlockPos facility, long untilTick) {
        BlockPos immutable = facility.immutable();
        var updated = new ArrayList<>(deferredTargets);
        updated.removeIf(entry -> entry.facility().equals(immutable));
        updated.add(new DeferredTarget(immutable, untilTick));
        deferredTargets = List.copyOf(updated);
        setDirty();
    }

    /**
     * Facilities still inside their deferral window at nowTick. Expired entries are
     * pruned here so the list cannot grow without bound; the saved data has no clock
     * of its own, so pruning rides these periodic reads.
     */
    public List<BlockPos> deferredFacilities(long nowTick) {
        var retained = new ArrayList<DeferredTarget>(deferredTargets.size());
        boolean pruned = false;
        for (DeferredTarget entry : deferredTargets) {
            if (entry.untilTick() > nowTick) {
                retained.add(entry);
            } else {
                pruned = true;
            }
        }
        if (pruned) {
            deferredTargets = List.copyOf(retained);
            setDirty();
        }
        return retained.stream().map(DeferredTarget::facility).toList();
    }

    /** True while the facility is inside an unexpired deferral window. */
    public boolean isDeferred(BlockPos facility, long nowTick) {
        for (DeferredTarget entry : deferredTargets) {
            if (entry.facility().equals(facility) && entry.untilTick() > nowTick) {
                return true;
            }
        }
        return false;
    }

    /** True while any plan is unfinished; the proposal and expansion gates share this. */
    public boolean hasIncomplete() {
        return !incompletePlans.isEmpty();
    }

    private void registerServed(BlockPos facility) {
        BlockPos immutable = facility.immutable();
        if (servedFacilities.contains(immutable)) {
            return;
        }
        var updated = new ArrayList<>(servedFacilities);
        updated.add(immutable);
        servedFacilities = List.copyOf(updated);
    }

    /** Rotate repair and new-work candidates so a blocked bridge cannot starve other work. */
    public Optional<TransportPlan> nextActivePlan() {
        var iterator = incompletePlans.entrySet().iterator();
        if (!iterator.hasNext()) {
            return Optional.empty();
        }
        var entry = iterator.next();
        String id = entry.getKey();
        TransportPlan plan = entry.getValue();
        iterator.remove();
        incompletePlans.put(id, plan);
        return Optional.of(plan);
    }

    /**
     * Inspect at most limit completed bridges in saved order. The cursor is
     * transient: a reload starts another full rotation without loading chunks.
     */
    public List<TransportPlan> nextOpenBridgeInspections(int limit) {
        if (limit <= 0 || openBridges.isEmpty()) {
            return List.of();
        }
        int count = Math.min(limit, openBridges.size());
        List<TransportPlan> batch = new ArrayList<>(count);
        for (int offset = 0; offset < count; offset++) {
            batch.add(openBridges.get((inspectionCursor + offset) % openBridges.size()));
        }
        inspectionCursor = (inspectionCursor + count) % openBridges.size();
        return batch;
    }

    /** A newly requested project waits until all construction and repairs finish. */
    public boolean add(TransportPlan plan) {
        if (!incompletePlans.isEmpty() || plans.stream().anyMatch(existing -> existing.id().equals(plan.id()))) {
            return false;
        }
        var updated = new ArrayList<>(plans);
        updated.add(plan);
        plans = List.copyOf(updated);
        index(plan);
        setDirty();
        return true;
    }

    public boolean replace(TransportPlan replacement) {
        for (int index = 0; index < plans.size(); index++) {
            TransportPlan old = plans.get(index);
            if (old.id().equals(replacement.id())) {
                var updated = new ArrayList<>(plans);
                updated.set(index, replacement);
                unindex(old);
                index(replacement);
                if (replacement.kind() == TransportPlan.Kind.ROAD && replacement.isComplete()) {
                    trimCompletedRoads(updated);
                }
                plans = List.copyOf(updated);
                if (!old.isComplete() && replacement.isComplete()
                        && replacement.targetFacility().isPresent()) {
                    registerServed(replacement.targetFacility().orElseThrow());
                }
                setDirty();
                return true;
            }
        }
        return false;
    }

    /** Navigation checks this at the intended walking-foot position. */
    public boolean isBridgeClosedAt(BlockPos foot) {
        return closedFeet.containsKey(foot);
    }

    private void index(TransportPlan plan) {
        if (!plan.isComplete()) {
            incompletePlans.put(plan.id(), plan);
        }
        if (plan.kind() != TransportPlan.Kind.WOOD_BRIDGE) {
            return;
        }
        if (plan.open()) {
            openBridges.add(plan);
        } else {
            for (BlockPos foot : plan.closedFootprint()) {
                closedFeet.merge(foot, 1, Integer::sum);
            }
        }
    }

    private void unindex(TransportPlan plan) {
        incompletePlans.remove(plan.id());
        if (plan.kind() != TransportPlan.Kind.WOOD_BRIDGE) {
            return;
        }
        if (plan.open()) {
            int removedAt = openBridges.indexOf(plan);
            if (removedAt >= 0) {
                openBridges.remove(removedAt);
                if (removedAt < inspectionCursor) {
                    inspectionCursor--;
                }
                if (inspectionCursor >= openBridges.size()) {
                    inspectionCursor = 0;
                }
            }
        } else {
            for (BlockPos foot : plan.closedFootprint()) {
                closedFeet.computeIfPresent(foot, (key, count) -> count == 1 ? null : count - 1);
            }
        }
    }

    private static void trimCompletedRoads(List<TransportPlan> updated) {
        long completedRoads = updated.stream().filter(plan ->
                plan.kind() == TransportPlan.Kind.ROAD && plan.isComplete()).count();
        if (completedRoads <= MAX_COMPLETED_ROADS) {
            return;
        }
        var iterator = updated.iterator();
        while (iterator.hasNext() && completedRoads > MAX_COMPLETED_ROADS) {
            TransportPlan plan = iterator.next();
            if (plan.kind() == TransportPlan.Kind.ROAD && plan.isComplete()) {
                iterator.remove();
                completedRoads--;
            }
        }
    }
}