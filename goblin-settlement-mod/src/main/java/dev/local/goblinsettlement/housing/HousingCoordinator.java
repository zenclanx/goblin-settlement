package dev.local.goblinsettlement.housing;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ResidentRecord;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.colony.WorkerAssignmentRules;
import dev.local.goblinsettlement.colony.WorkKind;
import dev.local.goblinsettlement.colony.WorkerDispatch;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.item.ItemStack;

/** Each review raises one housing target or dispatches one site derived from the world. */
public final class HousingCoordinator {
    private static final int INTERVAL_TICKS = 40;

    private HousingCoordinator() { }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL_TICKS != 0
                || !level.getGameRules().get(GameRules.MOB_GRIEFING)) return;
        if (!HousingBlueprints.available()) return;
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || data.claimedPlots().isEmpty()
                || data.plans().stream().anyMatch(plan -> !plan.isComplete())) return;
        String id = settlement.get().id();
        var housing = HousingSavedData.get(level);
        housing.useSettlement(id);
        int occupied = data.occupiedPopulationSlots();
        int beds = BedCensus.count(level, data);
        List<int[]> heads = bedHeadAxes(level, data);
        for (var home : housing.homes(id)) {
            if (!level.shouldTickBlocksAt(home.bed())) continue;
            if (!isBedHead(level, id, home.bed())) {
                housing.remove(home.bed());
                return;
            }
            var action = HousingRules.decide(beds, occupied, home.capacityTarget(), home.qualityTarget(),
                    usedBedsNear(heads, home));
            if (action == HousingRules.HomeAction.EXPAND_CAPACITY) {
                housing.replace(home.withCapacityTarget(home.capacityTarget() + 1));
                return;
            }
            if (action == HousingRules.HomeAction.IMPROVE_QUALITY) {
                housing.replace(home.withQualityTarget(home.qualityTarget() + 1));
                return;
            }
            if (advance(level, data, housing, id, home)) return;
        }
        if (housing.homes(id).size() >= HousingSavedData.MAX_HOMES) return;

        // Review one claimed plot; a candidate reserves the entire eventual footprint.
        int index = (int) ((level.getGameTime() / INTERVAL_TICKS) % data.claimedPlots().size());
        var plot = data.claimedPlots().get(index);
        int anchorY = settlement.get().anchor().getY();
        for (int x = plot.x() * 8; x < plot.x() * 8 + 8; x++) {
            for (int z = plot.z() * 8; z < plot.z() * 8 + 8; z++) {
                for (int y = anchorY - 2; y <= anchorY + 5; y++) {
                    BlockPos bed = new BlockPos(x, y, z);
                    if (!isBedHead(level, id, bed)
                            || housing.homes(id).stream().anyMatch(home -> home.bed().equals(bed))) continue;
                    if (housing.homes(id).stream().anyMatch(home ->
                            Math.abs(home.bed().getX() - bed.getX()) <= 6
                                    && Math.abs(home.bed().getZ() - bed.getZ()) <= 6
                                    && Math.abs(home.bed().getY() - bed.getY()) <= 4)) continue;
                    int styleCount = HousingBlueprints.styleCount();
                    int preferred = preferredStyle(bed, styleCount);
                    for (int offset = 0; offset < styleCount; offset++) {
                        int style = (preferred + offset) % styleCount;
                        for (int variant = 0; variant < 3; variant++) {
                            if (siteSuitable(level, id, bed, variant, style)) {
                                housing.add(new HousingSavedData.Home(bed, variant, style, 0, 0));
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    private static boolean advance(ServerLevel level, SettlementSavedData data,
                                   HousingSavedData housing, String id, HousingSavedData.Home home) {
        // Release an unowned worker before the site check: a temporarily blocked site must never keep a
        // cancelled or dead resident bound to this home, which would deadlock the home permanently.
        // This must also run when the home is fully built, or the resident who placed the last plank
        // would stay bound forever and leave the labour pool.
        if (home.workerId().isPresent()) {
            String workerId = home.workerId().orElseThrow();
            GoblinCitizenEntity goblin = null;
            try {
                if (level.getEntity(UUID.fromString(workerId)) instanceof GoblinCitizenEntity loaded) {
                    goblin = loaded;
                }
            } catch (IllegalArgumentException exception) {
                goblin = null; // An unusable id is not in the roster either, so the rule below releases it.
            }
            var decision = WorkerAssignmentRules.decide(
                    data.resident(workerId).map(ResidentRecord::stage),
                    goblin != null,
                    goblin != null && goblin.hasHousingWork(home.bed()));
            if (decision == WorkerAssignmentRules.Decision.RELEASE) {
                housing.replace(home.withWorker(Optional.empty()));
                return true;
            }
            return false; // An unloaded worker retains its physical plank.
        }
        BlockPos site = nextSite(level, home);
        if (site == null) {
            return false;
        }
        if (!permitted(level, id, site)) return false;
        if (!level.getBlockState(site).isAir()) return false;
        var step = stepAt(level, home, site);
        if (step.isEmpty()) return false;
        var stock = PublicWarehouseInventory.snapshot(level, data);
        int reserve = home.capacityTarget() >= 2
                ? HousingBlueprints.reserveExpanded() : HousingBlueprints.reserveBasic();
        if (!stock.complete()
                || PublicWarehouseInventory.countOf(level, data, step.get().item()) <= reserve) {
            return false;
        }
        var warehouse = PublicWarehouseInventory.firstHolding(level, data, step.get().item());
        if (warehouse.isPresent()) {
            var worker = WorkerDispatch.nearest(level, WorkKind.HOUSING, warehouse.orElseThrow(),
                    GoblinCitizenEntity::isAvailableForConstruction);
            if (worker.isPresent()) {
                GoblinCitizenEntity goblin = worker.orElseThrow();
                housing.replace(home.withWorker(Optional.of(goblin.getUUID().toString())));
                if (!goblin.assignHousing(id, home.bed(), warehouse.orElseThrow(), site)) {
                    housing.replace(home);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * The style a home at this bed prefers. An explicit formula rather than BlockPos.hashCode(),
     * whose contract gives no stability guarantee across versions -- this value ends up in a save.
     */
    static int preferredStyle(BlockPos bed, int styleCount) {
        if (styleCount <= 0) {
            return 0;
        }
        return Math.floorMod(bed.getX() * 31 + bed.getZ() * 17, styleCount);
    }

    /** First step whose site is not yet the declared block; null when built or the bed lost its facing. */
    private static BlockPos nextSite(ServerLevel level, HousingSavedData.Home home) {
        for (HousingBlueprints.ResolvedStep step : HousingBlueprints.steps(
                home.style(), home.capacityTarget(), home.qualityTarget())) {
            BlockPos candidate = position(level, home.bed(), home.variant(), step);
            if (candidate != null && level.getBlockState(candidate).is(step.block())) {
                continue;
            }
            return candidate;
        }
        return null;
    }

    /**
     * The step this home expects at this site, matched by position. Matching the worker's own
     * coordinates rather than reusing nextSite is deliberate: if the target moved, this site is no
     * longer the next one to build, and the caller must return the material instead of fetching
     * something for a step that is no longer theirs.
     */
    static Optional<HousingBlueprints.ResolvedStep> stepAt(ServerLevel level,
                                                           HousingSavedData.Home home,
                                                           BlockPos site) {
        for (HousingBlueprints.ResolvedStep step : HousingBlueprints.steps(
                home.style(), home.capacityTarget(), home.qualityTarget())) {
            if (site.equals(position(level, home.bed(), home.variant(), step))) {
                return Optional.of(step);
            }
        }
        return Optional.empty();
    }

    /** The step a worker holding this bed and site was sent to build; empty when the assignment is stale. */
    public static Optional<HousingBlueprints.ResolvedStep> assignedStep(ServerLevel level, BlockPos bed,
                                                                       BlockPos site) {
        var settlement = SettlementSavedData.get(level).settlement();
        if (settlement.isEmpty()) {
            return Optional.empty();
        }
        for (var home : HousingSavedData.get(level).homes(settlement.orElseThrow().id())) {
            if (home.bed().equals(bed)) {
                return stepAt(level, home, site);
            }
        }
        return Optional.empty();
    }

    public static boolean assignedSite(ServerLevel level, BlockPos bed, String workerId, BlockPos site) {
        var settlement = SettlementSavedData.get(level).settlement();
        if (settlement.isEmpty()) return false;
        return HousingSavedData.get(level).homes(settlement.orElseThrow().id()).stream()
                .anyMatch(home -> home.bed().equals(bed) && home.workerId().orElse("").equals(workerId)
                        && site.equals(nextSite(level, home)));
    }

    public static boolean placeByResident(ServerLevel level, GoblinCitizenEntity worker,
                                          BlockPos bed, BlockPos site, ItemStack carried) {
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || !assignedSite(level, bed, worker.getUUID().toString(), site)
                || carried.getCount() != 1
                || worker.distanceToSqr(site.getCenter()) > 16.0
                || !level.getGameRules().get(GameRules.MOB_GRIEFING)
                || !permitted(level, settlement.orElseThrow().id(), worker.blockPosition())
                || !permitted(level, settlement.orElseThrow().id(), site)
                || !level.getBlockState(site).isAir()) return false;
        var step = assignedStep(level, bed, site);
        if (step.isEmpty() || !carried.is(step.get().item())) return false;
        var placed = step.get().block().defaultBlockState();
        if (!level.setBlock(site, placed, 3)) return false;
        return level.getBlockState(site).is(step.get().block());
    }

    static boolean stageFullyBuilt(ServerLevel level, HousingSavedData.Home home, int stageIndex) {
        for (HousingBlueprints.ResolvedStep step : HousingBlueprints.stages(home.style()).get(stageIndex)) {
            BlockPos site = position(level, home.bed(), home.variant(), step);
            if (site == null || !level.getBlockState(site).is(step.block())) {
                return false;
            }
        }
        return true;
    }

    /** True while some home can still grow capacity toward hosting another bed. */
    public static boolean hasCapacityGain(ServerLevel level, SettlementSavedData data) {
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return false;
        }
        List<int[]> heads = bedHeadAxes(level, data);
        for (var home : HousingSavedData.get(level).homes(settlement.orElseThrow().id())) {
            if (HousingRules.canGainCapacity(usedBedsNear(heads, home), home.capacityTarget())) {
                return true;
            }
        }
        return false;
    }

    private static List<int[]> bedHeadAxes(ServerLevel level, SettlementSavedData data) {
        List<int[]> heads = new ArrayList<int[]>();
        for (BlockPos head : BedCensus.heads(level, data)) {
            heads.add(new int[] {head.getX(), head.getY(), head.getZ()});
        }
        return heads;
    }

    /** Beds that count against this home: the census heads inside its binding radius. */
    private static int usedBedsNear(List<int[]> heads, HousingSavedData.Home home) {
        return HousingRules.bedsNear(heads, home.bed().getX(), home.bed().getY(), home.bed().getZ(),
                HousingRules.BIND_RADIUS);
    }

    private static boolean siteSuitable(ServerLevel level, String id, BlockPos bed, int variant,
                                        int style) {
        if (!level.getBlockState(bed).hasProperty(BedBlock.FACING)) return false;
        BlockPos foot = bed.relative(level.getBlockState(bed).getValue(BedBlock.FACING).getOpposite());
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos other = bed.offset(dx, 0, dz);
                if (!level.shouldTickBlocksAt(other)) return false;
                if (!other.equals(bed) && !other.equals(foot)
                        && level.getBlockState(other).is(BlockTags.BEDS)) return false;
            }
        }
        for (var stage : HousingBlueprints.stages(style)) for (var step : stage) {
            BlockPos site = position(level, bed, variant, step);
            if (site == null || !permitted(level, id, site)) return false;
            var state = level.getBlockState(site);
            if (!state.isAir() && !state.is(step.block())) return false;
            if (step.y() == 0) {
                BlockPos ground = site.below();
                if (!permitted(level, id, ground)
                        || !level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) return false;
            }
        }
        return true;
    }

    static BlockPos position(ServerLevel level, BlockPos bed, int variant,
                            HousingBlueprints.ResolvedStep step) {
        var state = level.getBlockState(bed);
        if (!state.hasProperty(BedBlock.FACING)) return null;
        Direction south = state.getValue(BedBlock.FACING).getOpposite();
        Direction east = south.getCounterClockWise();
        int x = variant == 2 ? -step.x() : step.x();
        int z = variant == 1 ? -step.z() : step.z();
        return bed.relative(east, x).relative(south, z).above(step.y());
    }

    // Identifies a home's anchor bed, so it deliberately omits the headroom rule: a walled-in bed
    // is still the same home and must be tracked. Usability is BedCensus.usableBedHead's question.
    private static boolean isBedHead(ServerLevel level, String id, BlockPos bed) {
        if (!permitted(level, id, bed)) return false;
        var state = level.getBlockState(bed);
        return state.is(BlockTags.BEDS) && state.hasProperty(BedBlock.PART)
                && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    private static boolean permitted(ServerLevel level, String id, BlockPos pos) {
        return WorldModificationPermission.check(level, id, pos)
                == WorldModificationPermission.Decision.ALLOWED;
    }
}
