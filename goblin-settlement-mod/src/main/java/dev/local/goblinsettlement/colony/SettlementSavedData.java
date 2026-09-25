package dev.local.goblinsettlement.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.local.goblinsettlement.construction.ConstructionPlan;
import dev.local.goblinsettlement.interaction.ProtectedRectangle;
import dev.local.goblinsettlement.planning.math.PlotCoordinates;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** One provisional settlement per dimension. The record is owned by the server level. */
public final class SettlementSavedData extends SavedData {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_ACTIVE_PLANS = 8;
    public static final int MAX_FARM_SITES = 16;

    public enum FoundResult {
        FOUNDED, ALREADY_EXISTS, PLAYER_AREA_CONFLICT
    }

    public record Settlement(String id, BlockPos anchor) {
        public static final Codec<Settlement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(Settlement::id),
                BlockPos.CODEC.fieldOf("anchor").forGetter(Settlement::anchor)
        ).apply(instance, Settlement::new));
    }

    public record Plot(int x, int z) {
        public static final Codec<Plot> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("x").forGetter(Plot::x),
                Codec.INT.fieldOf("z").forGetter(Plot::z)
        ).apply(instance, Plot::new));

        public static Plot at(BlockPos pos) {
            var position = PlotCoordinates.toPlot(pos.getX(), pos.getZ());
            return new Plot(position.plotX(), position.plotZ());
        }
    }

    public record PlayerArea(String owner, ProtectedRectangle rectangle) {
        private static final Codec<ProtectedRectangle> RECTANGLE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("min_x").forGetter(ProtectedRectangle::minX),
                Codec.INT.fieldOf("min_z").forGetter(ProtectedRectangle::minZ),
                Codec.INT.fieldOf("max_x").forGetter(ProtectedRectangle::maxX),
                Codec.INT.fieldOf("max_z").forGetter(ProtectedRectangle::maxZ)
        ).apply(instance, ProtectedRectangle::new));

        public static final Codec<PlayerArea> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("owner").forGetter(PlayerArea::owner),
                RECTANGLE_CODEC.fieldOf("rectangle").forGetter(PlayerArea::rectangle)
        ).apply(instance, PlayerArea::new));
    }

    static final Codec<SettlementSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("schema_version").forGetter(data -> data.schemaVersion),
            Settlement.CODEC.optionalFieldOf("settlement").forGetter(data -> data.settlement),
            Plot.CODEC.listOf().optionalFieldOf("claimed_plots", List.of()).forGetter(data -> data.claimedPlots),
            PlayerArea.CODEC.listOf().optionalFieldOf("player_areas", List.of()).forGetter(data -> data.playerAreas),
            BlockPos.CODEC.listOf().optionalFieldOf("warehouses", List.of()).forGetter(data -> data.warehouses),
            ConstructionPlan.CODEC.optionalFieldOf("plan")
                    .forGetter(data -> Optional.<ConstructionPlan>empty()),
            ConstructionPlan.CODEC.listOf().optionalFieldOf("plans", List.of())
                    .forGetter(data -> data.plans),
            Codec.STRING.listOf().optionalFieldOf("cancelled_workers", List.of())
                    .forGetter(data -> data.cancelledWorkers),
            ResidentRecord.CODEC.listOf().optionalFieldOf("residents", List.of())
                    .forGetter(data -> data.residents),
            FarmSite.CODEC.listOf().optionalFieldOf("farm_sites", List.of())
                    .forGetter(data -> data.farmSites)
    ).apply(instance, SettlementSavedData::new));

    private static final SavedDataType<SettlementSavedData> TYPE = new SavedDataType<>(
            "goblin_settlement", SettlementSavedData::new, CODEC, null);

    private final int schemaVersion;
    private Optional<Settlement> settlement;
    private List<Plot> claimedPlots;
    private List<PlayerArea> playerAreas;
    private List<BlockPos> warehouses;
    private List<ConstructionPlan> plans;
    private List<String> cancelledWorkers;
    private List<ResidentRecord> residents;
    private List<FarmSite> farmSites;

    public SettlementSavedData() {
        this(SCHEMA_VERSION, Optional.empty(), List.of(), List.of(), List.of(),
                Optional.empty(), List.of(), List.of(), List.of(), List.of());
    }

    private SettlementSavedData(int schemaVersion, Optional<Settlement> settlement,
                                List<Plot> claimedPlots, List<PlayerArea> playerAreas,
                                List<BlockPos> warehouses, Optional<ConstructionPlan> legacyPlan,
                                List<ConstructionPlan> plans,
                                List<String> cancelledWorkers, List<ResidentRecord> residents, List<FarmSite> farmSites) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported settlement data version: " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        this.settlement = settlement;
        this.claimedPlots = List.copyOf(claimedPlots);
        this.playerAreas = List.copyOf(playerAreas);
        this.warehouses = List.copyOf(warehouses);
        this.plans = plans.isEmpty() && legacyPlan.isPresent()
                ? List.of(legacyPlan.get()) : List.copyOf(plans);
        this.cancelledWorkers = List.copyOf(cancelledWorkers);
        this.residents = List.copyOf(residents);
        this.farmSites = List.copyOf(farmSites);
    }

    public static SettlementSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<Settlement> settlement() {
        return settlement;
    }

    public List<Plot> claimedPlots() {
        return claimedPlots;
    }

    public List<PlayerArea> playerAreas() {
        return playerAreas;
    }

    public List<BlockPos> warehouses() {
        return warehouses;
    }

    public List<ResidentRecord> residents() {
        return residents;
    }

    public List<FarmSite> farmSites() {
        return farmSites;
    }

    public boolean registerFarmSite(BlockPos cropPos) {
        if (settlement.isEmpty() || farmSites.size() >= MAX_FARM_SITES
                || farmSites.stream().anyMatch(site -> site.cropPos().equals(cropPos))
                || plans.stream().anyMatch(plan -> !plan.isComplete()
                        && overlaps(plan.start(), cropPos))) {
            return false;
        }
        var updated = new ArrayList<>(farmSites);
        updated.add(new FarmSite(cropPos, Optional.empty()));
        farmSites = List.copyOf(updated);
        setDirty();
        return true;
    }

    public boolean assignFarmWorker(BlockPos cropPos, String workerId) {
        if (farmSites.stream().anyMatch(site -> site.workerId().equals(Optional.of(workerId)))
                || plans.stream().anyMatch(plan -> plan.workerId().equals(Optional.of(workerId)))) {
            return false;
        }
        for (int index = 0; index < farmSites.size(); index++) {
            var site = farmSites.get(index);
            if (site.cropPos().equals(cropPos) && site.workerId().isEmpty()) {
                var updated = new ArrayList<>(farmSites);
                updated.set(index, site.withWorker(workerId));
                farmSites = List.copyOf(updated);
                setDirty();
                return true;
            }
        }
        return false;
    }

    public boolean releaseFarmWorker(String workerId) {
        for (int index = 0; index < farmSites.size(); index++) {
            var site = farmSites.get(index);
            if (site.workerId().equals(Optional.of(workerId))) {
                var updated = new ArrayList<>(farmSites);
                updated.set(index, site.withoutWorker());
                farmSites = List.copyOf(updated);
                setDirty();
                return true;
            }
        }
        return false;
    }

    public boolean isFarmWorker(String workerId) {
        return farmSites.stream().anyMatch(site -> site.workerId().equals(Optional.of(workerId)));
    }

    public int adultCount() {
        return (int) residents.stream().filter(record -> record.stage() == ResidentRecord.LifeStage.ADULT).count();
    }

    public int childCount() {
        return (int) residents.stream().filter(record -> record.stage() == ResidentRecord.LifeStage.CHILD).count();
    }

    public int occupiedPopulationSlots() {
        return PopulationRules.occupiedSlots(adultCount(), childCount(), 0);
    }

    public boolean registerAdult(String residentId) {
        if (settlement.isEmpty() || residents.stream().anyMatch(record -> record.id().equals(residentId))) {
            return false;
        }
        var updated = new ArrayList<>(residents);
        updated.add(ResidentRecord.adult(residentId));
        residents = List.copyOf(updated);
        setDirty();
        return true;
    }

    public boolean markResidentDead(String residentId) {
        for (int index = 0; index < residents.size(); index++) {
            var current = residents.get(index);
            if (current.id().equals(residentId) && current.stage() != ResidentRecord.LifeStage.DECEASED) {
                var updated = new ArrayList<>(residents);
                updated.set(index, current.deceased());
                residents = List.copyOf(updated);
                setDirty();
                return true;
            }
        }
        return false;
    }

    public Optional<ConstructionPlan> plan() {
        return plans.stream().findFirst();
    }

    public Optional<ConstructionPlan> plan(BlockPos start) {
        return plans.stream().filter(candidate -> candidate.start().equals(start)).findFirst();
    }

    public List<ConstructionPlan> plans() {
        return plans;
    }

    public boolean isCancelledWorker(String workerId) {
        return cancelledWorkers.contains(workerId);
    }

    public boolean acknowledgeCancelledWorker(String workerId) {
        if (!cancelledWorkers.contains(workerId)) {
            return false;
        }
        var updated = new ArrayList<>(cancelledWorkers);
        updated.remove(workerId);
        cancelledWorkers = List.copyOf(updated);
        setDirty();
        return true;
    }

    public boolean cancelPlan() {
        var active = plans.stream().filter(candidate -> !candidate.isComplete()).toList();
        return active.size() == 1 && cancelPlan(active.getFirst().start());
    }

    public boolean cancelPlan(BlockPos start) {
        var target = plan(start);
        if (target.isEmpty() || target.get().isComplete()) {
            return false;
        }
        target.get().workerId().ifPresent(workerId -> {
            if (!cancelledWorkers.contains(workerId)) {
                var updated = new ArrayList<>(cancelledWorkers);
                updated.add(workerId);
                cancelledWorkers = List.copyOf(updated);
            }
        });
        var updated = new ArrayList<>(plans);
        updated.remove(target.get());
        plans = List.copyOf(updated);
        setDirty();
        return true;
    }

    public boolean registerWarehouse(BlockPos pos) {
        if (settlement.isEmpty() || warehouses.contains(pos)) {
            return false;
        }
        var updated = new ArrayList<>(warehouses);
        updated.add(pos.immutable());
        warehouses = List.copyOf(updated);
        setDirty();
        return true;
    }

    public boolean planTwoPlanks(BlockPos start) {
        var active = plans.stream().filter(candidate -> !candidate.isComplete()).toList();
        if (settlement.isEmpty() || active.size() >= MAX_ACTIVE_PLANS
                || active.stream().anyMatch(candidate -> overlaps(candidate.start(), start))
                || farmSites.stream().anyMatch(site -> overlaps(start, site.cropPos()))) {
            return false;
        }
        var updated = new ArrayList<>(active);
        updated.add(new ConstructionPlan(start, 0, Optional.empty(), Optional.empty(), Optional.empty()));
        plans = List.copyOf(updated);
        setDirty();
        return true;
    }

    public boolean assignWorker(String workerId) {
        var active = plans.stream().filter(candidate -> !candidate.isComplete()).toList();
        return active.size() == 1 && assignWorker(active.getFirst().start(), workerId);
    }

    public boolean assignWorker(BlockPos start, String workerId) {
        var target = plan(start);
        if (target.isEmpty() || target.get().isComplete() || target.get().workerId().isPresent()
                || isCancelledWorker(workerId) || isFarmWorker(workerId)
                || plans.stream().anyMatch(candidate -> candidate.workerId().equals(Optional.of(workerId)))) {
            return false;
        }
        return replacePlan(target.get(), target.get().withWorker(workerId));
    }

    public boolean finishStep(String workerId, BlockPos site) {
        var target = plans.stream().filter(candidate -> !candidate.isComplete()
                && candidate.recoveryDrop().isEmpty()
                && candidate.workerId().equals(Optional.of(workerId))
                && candidate.site().equals(site)).findFirst();
        if (target.isEmpty()) {
            return false;
        }
        return replacePlan(target.get(), target.get().finishStep(workerId));
    }

    public boolean releaseWorker(String workerId) {
        var target = plans.stream().filter(candidate -> candidate.workerId()
                .equals(Optional.of(workerId))).findFirst();
        if (target.isEmpty()) {
            return false;
        }
        var current = target.get();
        return replacePlan(current, new ConstructionPlan(current.start(), current.completed(),
                Optional.empty(), current.recoveryDrop(), current.lastWorkerId()));
    }

    public boolean recordRecoverableDrop(String workerId, String itemId, BlockPos pos) {
        var target = plans.stream().filter(candidate -> candidate.workerId()
                .equals(Optional.of(workerId))).findFirst();
        if (target.isEmpty()) {
            return false;
        }
        return replacePlan(target.get(), target.get().withDroppedItem(itemId, pos));
    }

    public boolean retargetRecoveryDrop(String oldItemId, String newItemId, BlockPos pos) {
        var target = plans.stream().filter(candidate -> candidate.recoveryDrop()
                .map(drop -> drop.itemId().equals(oldItemId)).orElse(false)).findFirst();
        if (target.isEmpty()) {
            return false;
        }
        var current = target.get().recoveryDrop().orElseThrow();
        if (!current.itemId().equals(newItemId) || !current.pos().equals(pos)) {
            replacePlan(target.get(), target.get().retargetDrop(newItemId, pos));
        }
        return true;
    }

    public boolean finishRecovery(String workerId) {
        var target = plans.stream().filter(candidate -> candidate.recoveryDrop().isPresent()
                && candidate.workerId().equals(Optional.of(workerId))).findFirst();
        if (target.isEmpty()) {
            return false;
        }
        return replacePlan(target.get(), target.get().clearRecovery());
    }

    public boolean abandonRecovery(String workerId) {
        return finishRecovery(workerId);
    }

    public boolean clearMissingRecoveryDrop() {
        var active = plans.stream().filter(candidate -> !candidate.isComplete()).toList();
        return active.size() == 1 && clearMissingRecoveryDrop(active.getFirst().start());
    }

    public boolean clearMissingRecoveryDrop(BlockPos start) {
        var target = plan(start);
        if (target.isEmpty() || target.get().recoveryDrop().isEmpty()
                || target.get().workerId().isPresent()) {
            return false;
        }
        return replacePlan(target.get(), target.get().clearRecovery());
    }

    private boolean replacePlan(ConstructionPlan original, ConstructionPlan replacement) {
        var updated = new ArrayList<>(plans);
        int index = updated.indexOf(original);
        if (index < 0) {
            return false;
        }
        updated.set(index, replacement);
        plans = List.copyOf(updated);
        setDirty();
        return true;
    }

    private static boolean overlaps(BlockPos left, BlockPos right) {
        for (int first = 0; first < ConstructionPlan.LENGTH; first++) {
            for (int second = 0; second < ConstructionPlan.LENGTH; second++) {
                if (left.east(first).equals(right.east(second))) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isProtected(BlockPos pos) {
        return playerAreas.stream().anyMatch(area -> area.rectangle().contains(pos.getX(), pos.getZ()));
    }

    public boolean isClaimed(BlockPos pos) {
        return claimedPlots.contains(Plot.at(pos));
    }

    public FoundResult found(BlockPos anchor) {
        if (settlement.isPresent()) {
            return FoundResult.ALREADY_EXISTS;
        }
        Plot plot = Plot.at(anchor);
        if (overlapsPlayerArea(plot)) {
            return FoundResult.PLAYER_AREA_CONFLICT;
        }
        settlement = Optional.of(new Settlement(UUID.randomUUID().toString(), anchor.immutable()));
        claimedPlots = List.of(plot);
        setDirty();
        return FoundResult.FOUNDED;
    }

    public boolean protect(String owner, ProtectedRectangle rectangle) {
        PlayerArea area = new PlayerArea(owner, rectangle);
        if (playerAreas.contains(area)) {
            return false;
        }
        var updated = new ArrayList<>(playerAreas);
        updated.add(area);
        playerAreas = List.copyOf(updated);
        setDirty();
        return true;
    }

    private boolean overlapsPlayerArea(Plot plot) {
        long minX = 8L * plot.x();
        long minZ = 8L * plot.z();
        return playerAreas.stream().anyMatch(area -> area.rectangle().intersects(
                minX, minZ, minX + 7, minZ + 7));
    }
}
