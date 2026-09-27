package dev.local.goblinsettlement.construction.transport;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.local.goblinsettlement.planning.transport.RoadUpgradeRules;
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
                            .forGetter(data -> data.deferredTargets),
                    Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("traffic", Map.of())
                            .forGetter(data -> data.traffic))
                    .apply(instance, TransportSavedData::new));
    private static final SavedDataType<TransportSavedData> TYPE =
            new SavedDataType<>("goblin_transport", TransportSavedData::new, CODEC, null);

    private List<TransportPlan> plans;
    private List<BlockPos> servedFacilities;
    private List<DeferredTarget> deferredTargets;
    private Map<String, Integer> traffic;
    private int revision;
    private final List<TransportPlan> openBridges = new ArrayList<>();
    private final Map<BlockPos, Integer> closedFeet = new HashMap<>();
    private final Map<String, TransportPlan> incompletePlans = new LinkedHashMap<>();
    private int inspectionCursor;

    public TransportSavedData() {
        this(List.of(), List.of(), List.of(), Map.of());
    }

    private TransportSavedData(List<TransportPlan> plans, List<BlockPos> servedFacilities,
                               List<DeferredTarget> deferredTargets, Map<String, Integer> traffic) {
        this.plans = List.copyOf(plans);
        this.servedFacilities = List.copyOf(servedFacilities);
        this.deferredTargets = List.copyOf(deferredTargets);
        this.traffic = Map.copyOf(traffic);
        for (TransportPlan plan : this.plans) {
            index(plan);
        }
        pruneTraffic();
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

    /** Sample hits per road plan id. Only finished roads are sampled; see TrafficSampler. */
    public Map<String, Integer> traffic() {
        return traffic;
    }

    /** Bumped whenever the plan list changes, so a derived index can tell when it has gone stale. */
    public int revision() {
        return revision;
    }

    /** Add one sample round's hits: one per resident seen standing on that road. */
    public void recordTraffic(Map<String, Integer> hits) {
        if (hits.isEmpty()) {
            return;
        }
        var updated = new HashMap<>(traffic);
        boolean changed = false;
        for (var entry : hits.entrySet()) {
            Integer count = entry.getValue();
            if (count == null || count <= 0) {
                continue;
            }
            updated.merge(entry.getKey(), count, Integer::sum);
            changed = true;
        }
        if (changed) {
            traffic = Map.copyOf(updated);
            setDirty();
        }
    }

    /** The finished road's width: the widest completed member of the widening chain it belongs to. */
    public int roadWidth(String planId) {
        int widest = RoadUpgradeRules.baseLanes();
        for (TransportPlan plan : chainOf(planId)) {
            if (!plan.isComplete()) {
                continue;
            }
            widest = Math.max(widest, plan.lanes());
        }
        return widest;
    }

    /** The finished road's traffic: every member of its chain contributes the samples it caught. */
    public int roadTraffic(String planId) {
        int total = 0;
        for (TransportPlan plan : chainOf(planId)) {
            total += traffic.getOrDefault(plan.id(), 0);
        }
        return total;
    }

    /**
     * The facility this road was built to reach. The chain's root registered it; a widening plan
     * deliberately carries none of its own, so the answer comes from whichever member has one.
     */
    public Optional<BlockPos> roadTarget(String planId) {
        for (TransportPlan plan : chainOf(planId)) {
            var target = plan.targetFacility();
            if (target.isPresent()) {
                return target;
            }
        }
        return Optional.empty();
    }

    /**
     * One entry per finished road, in the order the roads were built: the chain's widest member, the
     * smaller id breaking a tie. A widening is folded into the road it widens rather than listed twice.
     */
    public List<TransportPlan> roads() {
        var representatives = new ArrayList<TransportPlan>();
        for (TransportPlan plan : plans) {
            if (plan.kind() != TransportPlan.Kind.ROAD || !plan.isComplete()) {
                continue;
            }
            if (plan.widensFrom().filter(parent -> plan(parent).isPresent()).isPresent()) {
                continue;
            }
            representatives.add(widestOf(chainOf(plan.id()), plan));
        }
        return List.copyOf(representatives);
    }

    /** The finished roads whose measured traffic has earned a widening, heaviest first. */
    public List<TransportPlan> wideningCandidates() {
        var candidates = new ArrayList<TransportPlan>();
        for (TransportPlan road : roads()) {
            if (RoadUpgradeRules.shouldUpgrade(roadWidth(road.id()), roadTraffic(road.id()))) {
                candidates.add(road);
            }
        }
        candidates.sort((left, right) -> {
            int byTraffic = Integer.compare(roadTraffic(right.id()), roadTraffic(left.id()));
            return byTraffic != 0 ? byTraffic : left.id().compareTo(right.id());
        });
        return List.copyOf(candidates);
    }

    /**
     * The chain a plan belongs to: the plan that widened nothing, plus every plan widened from it. A
     * plan whose parent is missing (a hand-edited save) is its own chain, so nothing is lost silently.
     * Public so the assembly layer can take one whole road; the chain's rules still live only here.
     */
    public List<TransportPlan> chainOf(String planId) {
        String root = rootOf(planId);
        var found = new ArrayList<TransportPlan>();
        for (TransportPlan plan : plans) {
            if (root.equals(rootOf(plan.id()))) {
                found.add(plan);
            }
        }
        return List.copyOf(found);
    }

    private String rootOf(String planId) {
        String current = planId;
        for (int step = 0; step <= plans.size(); step++) {
            var found = plan(current);
            if (found.isEmpty()) {
                return planId;
            }
            var parent = found.orElseThrow().widensFrom();
            if (parent.isEmpty()) {
                return current;
            }
            current = parent.orElseThrow();
        }
        return planId; // a cycle cannot be written here, but a hand-edited save must not hang the server
    }

    private static TransportPlan widestOf(List<TransportPlan> chain, TransportPlan fallback) {
        TransportPlan widest = fallback;
        for (TransportPlan plan : chain) {
            if (!plan.isComplete()) {
                continue;
            }
            if (plan.lanes() > widest.lanes()
                    || (plan.lanes() == widest.lanes() && plan.id().compareTo(widest.id()) < 0)) {
                widest = plan;
            }
        }
        return widest;
    }

    /**
     * Drop counters whose road is gone. The map is persisted and keyed by plan id, so without this it
     * would grow without bound as MAX_COMPLETED_ROADS retires the oldest finished roads.
     */
    private void pruneTraffic() {
        if (traffic.isEmpty()) {
            return;
        }
        var retained = new HashMap<String, Integer>();
        for (var entry : traffic.entrySet()) {
            if (plan(entry.getKey()).isPresent()) {
                retained.put(entry.getKey(), entry.getValue());
            }
        }
        if (retained.size() != traffic.size()) {
            traffic = Map.copyOf(retained);
            setDirty();
        }
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
        revision++;
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
                revision++;
                pruneTraffic();
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

    /**
     * Retire the oldest finished roads once there are more than MAX_COMPLETED_ROADS of them. Roads on a
     * widening chain are kept: the busiest roads are also the oldest, so retiring one would drop both
     * its samples and its only chance of ever being widened.
     */
    private static void trimCompletedRoads(List<TransportPlan> updated) {
        long retirable = updated.stream().filter(plan -> isRetirable(plan, updated)).count();
        if (retirable <= MAX_COMPLETED_ROADS) {
            return;
        }
        var iterator = updated.iterator();
        while (iterator.hasNext() && retirable > MAX_COMPLETED_ROADS) {
            TransportPlan plan = iterator.next();
            if (isRetirable(plan, updated)) {
                iterator.remove();
                retirable--;
            }
        }
    }

    /** A finished road on no widening chain -- the only kind the trim may retire. */
    private static boolean isRetirable(TransportPlan plan, List<TransportPlan> plans) {
        if (plan.kind() != TransportPlan.Kind.ROAD || !plan.isComplete()) {
            return false;
        }
        if (plan.widensFrom().isPresent()) {
            return false;
        }
        return plans.stream().noneMatch(other -> other.widensFrom().equals(Optional.of(plan.id())));
    }
}