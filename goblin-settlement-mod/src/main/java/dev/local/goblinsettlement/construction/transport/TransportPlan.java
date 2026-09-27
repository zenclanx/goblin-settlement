package dev.local.goblinsettlement.construction.transport;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.local.goblinsettlement.planning.transport.RoadUpgradeRules;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** Persisted block intent. Actual materials remain in registered containers. */
public record TransportPlan(
        String id, String settlementId, Kind kind, List<Step> steps,
        int completedSteps, boolean open, List<BlockPos> barrierFeet,
        List<BlockPos> closedFootprint, List<BlockPos> foundationBases,
        Optional<String> workerId, Optional<BlockPos> targetFacility,
        Optional<Road> road) {
    public enum Kind {
        ROAD, WOOD_BRIDGE
    }

    /**
     * The road this plan builds: its centerline, how wide it ends up, and the plan it widens. Empty for
     * bridges, and empty for every plan written before the widening round -- such a road is two lanes
     * and has no centerline, so it can never be widened.
     */
    public record Road(Optional<String> widensFrom, int lanes, List<BlockPos> route) {
        static final Codec<Road> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("widens_from").forGetter(Road::widensFrom),
                Codec.INT.optionalFieldOf("lanes", RoadUpgradeRules.baseLanes()).forGetter(Road::lanes),
                BlockPos.CODEC.listOf().optionalFieldOf("route", List.of()).forGetter(Road::route)
        ).apply(instance, Road::new));

        public Road {
            Objects.requireNonNull(widensFrom, "widensFrom");
            if (lanes < 1) {
                throw new IllegalArgumentException("A road needs at least one lane");
            }
            route = immutablePositions(route);
        }
    }

    public enum Phase {
        BARRIERS, APPROACHES, SUPPORTS, SURFACE, RAILINGS, LIGHTING
    }

    public enum Material {
        OAK_PLANKS, OAK_LOG, OAK_FENCE, TORCH
    }

    public enum Rule {
        ROAD_GROUND, APPROACH_GROUND, AIR_OR_WATER, SUPPORT, AIR
    }

    public record Step(Phase phase, BlockPos site, Material material, Rule rule) {
        private static final Codec<Phase> PHASE_CODEC =
                Codec.STRING.xmap(Phase::valueOf, Phase::name);
        private static final Codec<Material> MATERIAL_CODEC =
                Codec.STRING.xmap(Material::valueOf, Material::name);
        private static final Codec<Rule> RULE_CODEC =
                Codec.STRING.xmap(Rule::valueOf, Rule::name);
        public static final Codec<Step> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                PHASE_CODEC.fieldOf("phase").forGetter(Step::phase),
                BlockPos.CODEC.fieldOf("site").forGetter(Step::site),
                MATERIAL_CODEC.fieldOf("material").forGetter(Step::material),
                RULE_CODEC.fieldOf("rule").forGetter(Step::rule)
        ).apply(instance, Step::new));

        public Step {
            Objects.requireNonNull(phase, "phase");
            site = Objects.requireNonNull(site, "site").immutable();
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(rule, "rule");
        }
    }

    private static final Codec<Kind> KIND_CODEC =
            Codec.STRING.xmap(Kind::valueOf, Kind::name);
    public static final Codec<TransportPlan> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(TransportPlan::id),
            Codec.STRING.fieldOf("settlement_id").forGetter(TransportPlan::settlementId),
            KIND_CODEC.fieldOf("kind").forGetter(TransportPlan::kind),
            Step.CODEC.listOf().fieldOf("steps").forGetter(TransportPlan::steps),
            Codec.INT.fieldOf("completed_steps").forGetter(TransportPlan::completedSteps),
            Codec.BOOL.fieldOf("open").forGetter(TransportPlan::open),
            BlockPos.CODEC.listOf().fieldOf("barrier_feet").forGetter(TransportPlan::barrierFeet),
            BlockPos.CODEC.listOf().fieldOf("closed_footprint").forGetter(TransportPlan::closedFootprint),
            BlockPos.CODEC.listOf().fieldOf("foundation_bases").forGetter(TransportPlan::foundationBases),
            Codec.STRING.optionalFieldOf("worker_id").forGetter(TransportPlan::workerId),
            BlockPos.CODEC.optionalFieldOf("target_facility").forGetter(TransportPlan::targetFacility),
            Road.CODEC.optionalFieldOf("road").forGetter(TransportPlan::road)
    ).apply(instance, TransportPlan::new));

    public TransportPlan {
        if (id == null || id.isBlank() || settlementId == null || settlementId.isBlank()) {
            throw new IllegalArgumentException("Transport plan IDs must be present");
        }
        Objects.requireNonNull(kind, "kind");
        steps = List.copyOf(steps);
        barrierFeet = immutablePositions(barrierFeet);
        closedFootprint = immutablePositions(closedFootprint);
        foundationBases = immutablePositions(foundationBases);
        workerId = Objects.requireNonNull(workerId, "workerId");
        targetFacility = (targetFacility == null ? Optional.<BlockPos>empty() : targetFacility)
                .map(BlockPos::immutable);
        road = Objects.requireNonNull(road, "road");
        if (kind != Kind.ROAD && road.isPresent()) {
            throw new IllegalArgumentException("Only roads carry a road shape");
        }
        if (completedSteps < 0 || completedSteps > steps.size()) {
            throw new IllegalArgumentException("Invalid transport progress");
        }
        if (kind == Kind.ROAD && (!barrierFeet.isEmpty() || !closedFootprint.isEmpty() || open)) {
            throw new IllegalArgumentException("Roads cannot carry bridge closure state");
        }
        if (kind == Kind.WOOD_BRIDGE && (barrierFeet.size() != 4 || closedFootprint.isEmpty())) {
            throw new IllegalArgumentException("Bridge needs four barriers and a closed footprint");
        }
        if (open && completedSteps != steps.size()) {
            throw new IllegalArgumentException("An unfinished bridge cannot be open");
        }
    }

    private static List<BlockPos> immutablePositions(List<BlockPos> positions) {
        return positions.stream().map(pos -> Objects.requireNonNull(pos, "position").immutable()).toList();
    }

    /** The road's width; a plan written before the widening round is two lanes. */
    public int lanes() {
        return road.map(Road::lanes).orElse(RoadUpgradeRules.baseLanes());
    }

    /** The road's centerline, empty when this plan predates the widening round. */
    public List<BlockPos> route() {
        return road.map(Road::route).orElse(List.of());
    }

    /** The plan this one widens, empty when it widens nothing. */
    public Optional<String> widensFrom() {
        return road.flatMap(Road::widensFrom);
    }

    public boolean isComplete() {
        return kind == Kind.ROAD ? completedSteps == steps.size() : open;
    }

    public TransportPlan advance() {
        if (completedSteps == steps.size()) {
            return this;
        }
        return new TransportPlan(id, settlementId, kind, steps, completedSteps + 1,
                false, barrierFeet, closedFootprint, foundationBases, Optional.empty(), targetFacility, road);
    }

    public TransportPlan rewind(int stepIndex) {
        if (stepIndex < 0 || stepIndex > completedSteps) {
            throw new IllegalArgumentException("Invalid rewind index");
        }
        return new TransportPlan(id, settlementId, kind, steps, stepIndex,
                false, barrierFeet, closedFootprint, foundationBases, Optional.empty(), targetFacility, road);
    }

    public TransportPlan withOpen(boolean value) {
        return new TransportPlan(id, settlementId, kind, steps, completedSteps,
                value, barrierFeet, closedFootprint, foundationBases, Optional.empty(), targetFacility, road);
    }

    public TransportPlan withWorker(Optional<String> value) {
        return new TransportPlan(id, settlementId, kind, steps, completedSteps,
                open, barrierFeet, closedFootprint, foundationBases, value, targetFacility, road);
    }
}
