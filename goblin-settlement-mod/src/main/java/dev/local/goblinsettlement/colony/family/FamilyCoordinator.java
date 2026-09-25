package dev.local.goblinsettlement.colony.family;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.citizen.ModEntities;
import dev.local.goblinsettlement.colony.ResidentRecord;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;

/** Advances family clocks only in an active settlement and rechecks real conditions at birth. */
public final class FamilyCoordinator {
    private static final int STEP_TICKS = 20;
    private static final int BREEDING_REVIEW_TICKS = 1200;
    private static final int PLOTS_PER_STEP = 2;
    private static final WeakHashMap<ServerLevel, Integer> ACTIVE_TICKS = new WeakHashMap<>();
    private static final WeakHashMap<ServerLevel, BedScan> BED_SCANS = new WeakHashMap<>();

    private FamilyCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % STEP_TICKS != 0) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || !level.shouldTickBlocksAt(settlement.get().anchor())) {
            return;
        }
        scanBeds(level, data, settlement.get().id(), settlement.get().anchor());
        placePendingNewborns(level, data, settlement.get().id(), settlement.get().anchor());
        if (!data.pendingNewbornIds().isEmpty() || !familyMembersLoaded(level, data)) {
            return;
        }

        data.advanceFamilyTime(STEP_TICKS);

        int active = ACTIVE_TICKS.getOrDefault(level, 0) + STEP_TICKS;
        boolean breedingReview = active >= BREEDING_REVIEW_TICKS;
        ACTIVE_TICKS.put(level, breedingReview ? active - BREEDING_REVIEW_TICKS : active);
        if (!breedingReview && data.readyBirths().isEmpty()) {
            return;
        }

        var supply = PublicWarehouseInventory.snapshot(level, data);
        int occupied = data.occupiedPopulationSlots();
        Housing housing = housing(level, data, settlement.get().id(), settlement.get().anchor(),
                occupied + 1);
        boolean foodForBirth = supply.complete() && supply.food() >= 2 * occupied;
        for (var pregnancy : data.readyBirths()) {
            FamilyConditions conditions = conditions(level, pregnancy.motherId(), pregnancy.fatherId(),
                    housing.count() >= occupied, foodForBirth);
            if (!housing.beds().isEmpty() && data.commitBirth(pregnancy.childId(), conditions)) {
                placePendingNewborns(level, data, settlement.get().id(), settlement.get().anchor());
            }
        }

        if (!breedingReview) {
            return;
        }
        boolean foodForConception = supply.complete() && supply.food() >= 2 * (occupied + 1);
        boolean housingForConception = housing.count() >= occupied + 1;
        List<ResidentRecord> loadedAdults = new ArrayList<>();
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.ADULT && healthy(entity(level, record.id()))) {
                loadedAdults.add(record);
            }
        }
        loadedAdults.sort(Comparator.comparing(ResidentRecord::id));
        Optional<Pair> pair = firstPair(data, loadedAdults);
        double livingConditions = housingForConception && foodForConception && pair.isPresent() ? 1.0 : 0.0;
        double progress = data.advanceBreedingProgress(loadedAdults.size(), livingConditions,
                BREEDING_REVIEW_TICKS);
        if (progress >= 1.0 && pair.isPresent()) {
            Pair parents = pair.orElseThrow();
            FamilyConditions conditions = conditions(level, parents.motherId(), parents.fatherId(),
                    housingForConception, foodForConception);
            data.startPregnancy(parents.motherId(), parents.fatherId(),
                    UUID.randomUUID().toString(), conditions);
        }
    }

    private static boolean familyMembersLoaded(ServerLevel level, SettlementSavedData data) {
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.CHILD
                    && !data.pendingNewbornIds().contains(record.id())
                    && entity(level, record.id()) == null) {
                return false;
            }
        }
        for (var pregnancy : data.pregnancies()) {
            if (entity(level, pregnancy.motherId()) == null) {
                return false;
            }
        }
        return true;
    }

    private static Optional<Pair> firstPair(SettlementSavedData data, List<ResidentRecord> adults) {
        for (var mother : adults) {
            for (var father : adults) {
                if (data.canPair(mother.id(), father.id())) {
                    return Optional.of(new Pair(mother.id(), father.id()));
                }
            }
        }
        return Optional.empty();
    }

    private static FamilyConditions conditions(ServerLevel level, String motherId, String fatherId,
                                               boolean housingAvailable, boolean foodAvailable) {
        return new FamilyConditions(housingAvailable, foodAvailable,
                healthy(entity(level, motherId)), healthy(entity(level, fatherId)));
    }

    private static GoblinCitizenEntity entity(ServerLevel level, String id) {
        try {
            var found = level.getEntity(UUID.fromString(id));
            return found instanceof GoblinCitizenEntity goblin ? goblin : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean healthy(GoblinCitizenEntity goblin) {
        return goblin != null && goblin.isAlive() && goblin.getHealth() >= goblin.getMaxHealth() * 0.75F;
    }

    /** Scan two plots per active second, then publish a complete candidate list. */
    private static void scanBeds(ServerLevel level, SettlementSavedData data, String settlementId,
                                 BlockPos anchor) {
        BedScan scan = BED_SCANS.get(level);
        if (scan == null || !scan.settlementId.equals(settlementId) || !scan.anchor.equals(anchor)
                || !scan.plots.equals(data.claimedPlots())) {
            scan = new BedScan(settlementId, anchor.immutable(), List.copyOf(data.claimedPlots()));
            BED_SCANS.put(level, scan);
        }
        for (int reviewed = 0; reviewed < PLOTS_PER_STEP && scan.next < scan.plots.size(); reviewed++) {
            var plot = scan.plots.get(scan.next++);
            for (int x = plot.x() * 8; x < plot.x() * 8 + 8; x++) {
                for (int z = plot.z() * 8; z < plot.z() * 8 + 8; z++) {
                    for (int y = anchor.getY() - 2; y <= anchor.getY() + 5; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.shouldTickBlocksAt(pos)) continue;
                        var state = level.getBlockState(pos);
                        if (state.is(BlockTags.BEDS) && state.hasProperty(BedBlock.PART)
                                && state.getValue(BedBlock.PART) == BedPart.HEAD) {
                            scan.found.add(pos);
                        }
                    }
                }
            }
        }
        if (scan.next == scan.plots.size()) {
            scan.beds = List.copyOf(scan.found);
            scan.found.clear();
            scan.next = 0;
        }
    }

    /** Recheck the real blocks and permissions immediately before conception or birth. */
    private static Housing housing(ServerLevel level, SettlementSavedData data, String settlementId,
                                   BlockPos anchor, int enough) {
        BedScan scan = BED_SCANS.get(level);
        if (scan == null || !scan.settlementId.equals(settlementId) || !scan.anchor.equals(anchor)
                || !scan.plots.equals(data.claimedPlots())) {
            return new Housing(0, List.of());
        }
        var usable = new ArrayList<BlockPos>();
        for (BlockPos bed : scan.beds) {
            if (validBed(level, settlementId, bed)) {
                usable.add(bed);
                if (usable.size() >= enough) break;
            }
        }
        return new Housing(usable.size(), List.copyOf(usable));
    }

    private static boolean validBed(ServerLevel level, String settlementId, BlockPos bed) {
        if (WorldModificationPermission.check(level, settlementId, bed)
                    != WorldModificationPermission.Decision.ALLOWED
                || WorldModificationPermission.check(level, settlementId, bed.above())
                    != WorldModificationPermission.Decision.ALLOWED
                || WorldModificationPermission.check(level, settlementId, bed.above(2))
                    != WorldModificationPermission.Decision.ALLOWED) {
            return false;
        }
        var state = level.getBlockState(bed);
        return state.is(BlockTags.BEDS) && state.hasProperty(BedBlock.PART)
                && state.getValue(BedBlock.PART) == BedPart.HEAD
                && level.getBlockState(bed.above()).isAir()
                && level.getBlockState(bed.above(2)).isAir();
    }

    private static void placePendingNewborns(ServerLevel level, SettlementSavedData data,
                                             String settlementId, BlockPos anchor) {
        if (data.pendingNewbornIds().isEmpty()) return;
        Housing housing = housing(level, data, settlementId, anchor, data.occupiedPopulationSlots());
        if (housing.beds().isEmpty()) return;
        for (String childId : data.pendingNewbornIds()) {
            UUID uuid;
            try {
                uuid = UUID.fromString(childId);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (level.getEntity(uuid) instanceof GoblinCitizenEntity) {
                data.acknowledgeNewbornSpawn(childId);
                continue;
            }
            GoblinCitizenEntity child = ModEntities.GOBLIN.create(level, EntitySpawnReason.BREEDING);
            if (child == null) continue;
            child.setUUID(uuid);
            for (BlockPos bed : housing.beds()) {
                if (!validBed(level, settlementId, bed)) continue;
                BlockPos spawn = bed.above();
                child.snapTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
                if (!level.noCollision(child) || !level.getEntitiesOfClass(LivingEntity.class,
                        child.getBoundingBox(), LivingEntity::isAlive).isEmpty()) continue;
                if (level.addFreshEntity(child)) data.acknowledgeNewbornSpawn(childId);
                break;
            }
        }
    }

    private record Pair(String motherId, String fatherId) {
    }

    private record Housing(int count, List<BlockPos> beds) {
    }

    private static final class BedScan {
        private final String settlementId;
        private final BlockPos anchor;
        private final List<SettlementSavedData.Plot> plots;
        private final List<BlockPos> found = new ArrayList<>();
        private List<BlockPos> beds = List.of();
        private int next;

        private BedScan(String settlementId, BlockPos anchor, List<SettlementSavedData.Plot> plots) {
            this.settlementId = settlementId;
            this.anchor = anchor;
            this.plots = plots;
        }
    }
}
