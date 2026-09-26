package dev.local.goblinsettlement.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.local.goblinsettlement.construction.ConstructionPlan;
import dev.local.goblinsettlement.colony.family.FamilyConditions;
import dev.local.goblinsettlement.colony.family.KinshipRules;
import dev.local.goblinsettlement.colony.family.PregnancyRecord;
import dev.local.goblinsettlement.interaction.ProtectedRectangle;
import dev.local.goblinsettlement.planning.math.PlotCoordinates;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
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
    public static final int MAX_WAREHOUSES = 16;

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
                    .forGetter(data -> data.farmSites),
            PregnancyRecord.CODEC.listOf().optionalFieldOf("pregnancies", List.of())
                    .forGetter(data -> data.pregnancies),
            Codec.STRING.listOf().optionalFieldOf("pending_newborns", List.of())
                    .forGetter(data -> data.pendingNewborns),
            Codec.DOUBLE.optionalFieldOf("breeding_progress", 0.0)
                    .forGetter(data -> data.breedingProgress)
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
    private List<PregnancyRecord> pregnancies;
    private List<String> pendingNewborns;
    private double breedingProgress;

    public SettlementSavedData() {
        this(SCHEMA_VERSION, Optional.empty(), List.of(), List.of(), List.of(),
                Optional.empty(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), 0.0);
    }

    private SettlementSavedData(int schemaVersion, Optional<Settlement> settlement,
                                List<Plot> claimedPlots, List<PlayerArea> playerAreas,
                                List<BlockPos> warehouses, Optional<ConstructionPlan> legacyPlan,
                                List<ConstructionPlan> plans,
                                List<String> cancelledWorkers, List<ResidentRecord> residents, List<FarmSite> farmSites,
                                List<PregnancyRecord> pregnancies, List<String> pendingNewborns,
                                double breedingProgress) {
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
        this.pregnancies = List.copyOf(pregnancies);
        this.pendingNewborns = List.copyOf(pendingNewborns);
        if (!Double.isFinite(breedingProgress) || breedingProgress < 0 || breedingProgress > 1) {
            throw new IllegalArgumentException("Invalid breeding progress");
        }
        this.breedingProgress = breedingProgress;
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

    public List<PregnancyRecord> pregnancies() {
        return pregnancies;
    }

    public List<String> pendingNewbornIds() {
        return pendingNewborns;
    }

    public double breedingProgress() {
        return breedingProgress;
    }

    public int pregnancyCount() {
        return pregnancies.size();
    }

    public Optional<ResidentRecord> resident(String id) {
        return residents.stream().filter(record -> record.id().equals(id)).findFirst();
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

    /** Retire only idle cells; an assigned worker must release its reservation first. */
    public boolean removeIdleFarmSite(BlockPos cropPos) {
        var site = farmSites.stream().filter(candidate -> candidate.cropPos().equals(cropPos)
                && candidate.workerId().isEmpty()).findFirst();
        if (site.isEmpty()) {
            return false;
        }
        var updated = new ArrayList<>(farmSites);
        updated.remove(site.get());
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
        return PopulationRules.occupiedSlots(adultCount(), childCount(), pregnancyCount());
    }

    public boolean registerAdult(String residentId) {
        return registerAdult(residentId, ResidentRecord.ReproductiveRole.UNSPECIFIED);
    }

    public boolean registerAdult(String residentId, ResidentRecord.ReproductiveRole role) {
        if (settlement.isEmpty() || role == null || residentId == null || residentId.isBlank()
                || occupiedPopulationSlots() >= PopulationRules.MAX_RESIDENTS
                || resident(residentId).isPresent()
                || pregnancies.stream().anyMatch(pregnancy -> pregnancy.childId().equals(residentId))) {
            return false;
        }
        ResidentRecord record = ResidentRecord.adult(residentId);
        if (role != ResidentRecord.ReproductiveRole.UNSPECIFIED) {
            record = record.withReproductiveRole(role);
        }
        var updated = new ArrayList<>(residents);
        updated.add(record);
        residents = List.copyOf(updated);
        setDirty();
        return true;
    }

    public boolean assignResidentRole(String residentId, ResidentRecord.ReproductiveRole role) {
        if (role == null || role == ResidentRecord.ReproductiveRole.UNSPECIFIED) {
            return false;
        }
        for (int index = 0; index < residents.size(); index++) {
            ResidentRecord current = residents.get(index);
            if (current.id().equals(residentId)
                    && current.stage() != ResidentRecord.LifeStage.DECEASED
                    && current.reproductiveRole() == ResidentRecord.ReproductiveRole.UNSPECIFIED) {
                var updated = new ArrayList<>(residents);
                updated.set(index, current.withReproductiveRole(role));
                residents = List.copyOf(updated);
                setDirty();
                return true;
            }
        }
        return false;
    }

    /** Gives an unassigned adult a trade. Reassigning an existing trade is deliberately not supported yet. */
    public boolean assignProfession(String residentId, Profession profession) {
        if (profession == null || profession == Profession.UNASSIGNED) {
            return false;
        }
        for (int index = 0; index < residents.size(); index++) {
            ResidentRecord current = residents.get(index);
            if (current.id().equals(residentId)
                    && current.stage() == ResidentRecord.LifeStage.ADULT
                    && current.profession() == Profession.UNASSIGNED) {
                var updated = new ArrayList<>(residents);
                updated.set(index, current.withProfession(profession));
                residents = List.copyOf(updated);
                setDirty();
                return true;
            }
        }
        return false;
    }

    public List<String> unassignedAdultIds() {
        return residents.stream()
                .filter(record -> record.stage() == ResidentRecord.LifeStage.ADULT)
                .filter(record -> record.profession() == Profession.UNASSIGNED)
                .map(ResidentRecord::id)
                .toList();
    }

    /** The trades held by living adults, used to pick the scarcest one. */
    public List<Profession> assignedProfessions() {
        return residents.stream()
                .filter(record -> record.stage() == ResidentRecord.LifeStage.ADULT)
                .map(ResidentRecord::profession)
                .toList();
    }

    public boolean markResidentDead(String residentId) {
        for (int index = 0; index < residents.size(); index++) {
            var current = residents.get(index);
            if (current.id().equals(residentId) && current.stage() != ResidentRecord.LifeStage.DECEASED) {
                var updated = new ArrayList<>(residents);
                updated.set(index, current.deceased());
                residents = List.copyOf(updated);
                if (pregnancies.stream().anyMatch(pregnancy -> pregnancy.motherId().equals(residentId))) {
                    pregnancies = pregnancies.stream()
                            .filter(pregnancy -> !pregnancy.motherId().equals(residentId)).toList();
                }
                if (pendingNewborns.contains(residentId)) {
                    pendingNewborns = pendingNewborns.stream()
                            .filter(id -> !id.equals(residentId)).toList();
                }
                setDirty();
                return true;
            }
        }
        return false;
    }

    /**
     * Advance only with ticks actually simulated while the settlement is active. Do not pass
     * wall-clock time, a game-time jump, or a saved last-tick difference.
     */
    public List<String> advanceFamilyTime(long activeTicks) {
        if (activeTicks < 0) {
            throw new IllegalArgumentException("Active ticks cannot be negative");
        }
        if (activeTicks == 0) {
            return List.of();
        }
        var updatedResidents = new ArrayList<ResidentRecord>(residents.size());
        var matured = new ArrayList<String>();
        boolean changed = false;
        for (ResidentRecord current : residents) {
            ResidentRecord next = current.advanceFamilyTime(activeTicks);
            updatedResidents.add(next);
            if (current.stage() == ResidentRecord.LifeStage.CHILD
                    && next.stage() == ResidentRecord.LifeStage.ADULT) {
                matured.add(current.id());
            }
            changed |= !current.equals(next);
        }
        var updatedPregnancies = new ArrayList<PregnancyRecord>(pregnancies.size());
        for (PregnancyRecord current : pregnancies) {
            PregnancyRecord next = current.advance(activeTicks);
            updatedPregnancies.add(next);
            changed |= !current.equals(next);
        }
        if (changed) {
            residents = List.copyOf(updatedResidents);
            pregnancies = List.copyOf(updatedPregnancies);
            setDirty();
        }
        return List.copyOf(matured);
    }

    /**
     * Living conditions must come from a fresh housing, food, and health assessment.
     * A value of zero means those facts are unknown or insufficient.
     */
    public double advanceBreedingProgress(int eligibleAdults, double livingConditions, long activeTicks) {
        if (activeTicks < 0 || eligibleAdults > adultCount()) {
            throw new IllegalArgumentException("Invalid breeding time or eligible adult count");
        }
        double rate = PopulationRules.breedingProgressPerMinute(
                eligibleAdults, occupiedPopulationSlots(), livingConditions);
        if (activeTicks > 0 && rate > 0 && breedingProgress < 1) {
            double next = Math.min(1.0, breedingProgress + rate * (activeTicks / 1200.0));
            if (next != breedingProgress) {
                breedingProgress = next;
                setDirty();
            }
        }
        return breedingProgress;
    }

    /** Checks local family facts; the caller must separately check world conditions. */
    public boolean canPair(String motherId, String fatherId) {
        if (motherId == null || fatherId == null || motherId.equals(fatherId)) {
            return false;
        }
        Optional<ResidentRecord> mother = resident(motherId);
        Optional<ResidentRecord> father = resident(fatherId);
        if (mother.isEmpty() || father.isEmpty()
                || mother.get().stage() != ResidentRecord.LifeStage.ADULT
                || father.get().stage() != ResidentRecord.LifeStage.ADULT
                || mother.get().effectiveReproductiveRole() != ResidentRecord.ReproductiveRole.MOTHER
                || father.get().effectiveReproductiveRole() != ResidentRecord.ReproductiveRole.FATHER
                || mother.get().restTicks() > 0 || father.get().restTicks() > 0
                || pregnancies.stream().anyMatch(pregnancy -> pregnancy.motherId().equals(motherId)
                        || pregnancy.fatherId().equals(motherId)
                        || pregnancy.motherId().equals(fatherId)
                        || pregnancy.fatherId().equals(fatherId))) {
            return false;
        }
        Map<String, ResidentRecord> byId = new HashMap<>();
        for (ResidentRecord record : residents) {
            byId.put(record.id(), record);
        }
        return !KinshipRules.areCloseKin(mother.get(), father.get(), byId);
    }

    /** Reserves the child UUID and one population slot before gestation can start. */
    public boolean startPregnancy(String motherId, String fatherId, String childId,
                                  FamilyConditions conditions) {
        if (settlement.isEmpty() || conditions == null || !conditions.permitsConception()
                || childId == null || childId.isBlank() || breedingProgress < 1
                || !PopulationRules.hasBirthSlot(adultCount(), childCount(), pregnancyCount())
                || resident(childId).isPresent() || pendingNewborns.contains(childId)
                || pregnancies.stream().anyMatch(pregnancy -> pregnancy.childId().equals(childId))
                || !canPair(motherId, fatherId)
                || childId.equals(motherId) || childId.equals(fatherId)) {
            return false;
        }
        var updated = new ArrayList<>(pregnancies);
        updated.add(new PregnancyRecord(childId, motherId, fatherId, 0));
        pregnancies = List.copyOf(updated);
        breedingProgress = 0;
        setDirty();
        return true;
    }

    public List<PregnancyRecord> readyBirths() {
        return pregnancies.stream().filter(PregnancyRecord::isReady).toList();
    }

    /**
     * Converts one reserved slot into a child and a durable spawn request. Repeating the call
     * for the same child UUID has no effect. The world must spawn exactly that UUID, then ack.
     */
    public boolean commitBirth(String childId, FamilyConditions conditions) {
        if (conditions == null || !conditions.permitsBirth()) {
            return false;
        }
        Optional<PregnancyRecord> found = pregnancies.stream()
                .filter(pregnancy -> pregnancy.childId().equals(childId) && pregnancy.isReady())
                .findFirst();
        if (found.isEmpty() || resident(childId).isPresent()
                || occupiedPopulationSlots() > PopulationRules.MAX_RESIDENTS
                || resident(found.get().motherId())
                        .map(record -> record.stage() == ResidentRecord.LifeStage.ADULT).orElse(false) == false) {
            return false;
        }
        PregnancyRecord pregnancy = found.get();
        var updatedResidents = new ArrayList<>(residents);
        updatedResidents.add(ResidentRecord.child(childId, pregnancy.motherId(), pregnancy.fatherId()));
        for (int index = 0; index < updatedResidents.size(); index++) {
            ResidentRecord record = updatedResidents.get(index);
            if (record.id().equals(pregnancy.motherId()) || record.id().equals(pregnancy.fatherId())) {
                updatedResidents.set(index, record.withPostBirthRest());
            }
        }
        residents = List.copyOf(updatedResidents);
        pregnancies = pregnancies.stream().filter(candidate -> !candidate.childId().equals(childId)).toList();
        var updatedPending = new ArrayList<>(pendingNewborns);
        updatedPending.add(childId);
        pendingNewborns = List.copyOf(updatedPending);
        setDirty();
        return true;
    }

    /** Call only after the entity with the reserved UUID exists in the world. */
    public boolean acknowledgeNewbornSpawn(String childId) {
        if (!pendingNewborns.contains(childId)) {
            return false;
        }
        pendingNewborns = pendingNewborns.stream().filter(id -> !id.equals(childId)).toList();
        setDirty();
        return true;
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
        if (settlement.isEmpty() || warehouses.contains(pos) || warehouses.size() >= MAX_WAREHOUSES) {
            return false;
        }
        var updated = new ArrayList<>(warehouses);
        updated.add(pos.immutable());
        warehouses = List.copyOf(updated);
        setDirty();
        return true;
    }

    public boolean unregisterWarehouse(BlockPos pos) {
        if (!warehouses.contains(pos)) {
            return false;
        }
        var updated = new ArrayList<>(warehouses);
        updated.remove(pos);
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

    public boolean claimPlot(Plot plot) {
        if (plot == null || settlement.isEmpty() || claimedPlots.contains(plot)
                || claimedPlots.size() >= PopulationRules.maximumPlots(adultCount())
                || overlapsPlayerArea(plot)) {
            return false;
        }
        var updated = new ArrayList<>(claimedPlots);
        updated.add(plot);
        claimedPlots = List.copyOf(updated);
        setDirty();
        return true;
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
