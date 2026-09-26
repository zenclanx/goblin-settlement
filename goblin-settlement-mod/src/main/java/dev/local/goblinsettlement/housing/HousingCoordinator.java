package dev.local.goblinsettlement.housing;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ProfessionRules;
import dev.local.goblinsettlement.colony.ResidentRecord;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.colony.WorkerAssignmentRules;
import dev.local.goblinsettlement.colony.WorkKind;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** One durable residential step per review. Beds, and therefore capacity, are managed separately. */
public final class HousingCoordinator {
    private static final int INTERVAL_TICKS = 40;
    private static final List<List<Step>> STAGES = blueprints();

    private HousingCoordinator() { }

    private record Step(int x, int y, int z) { }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL_TICKS != 0
                || !level.getGameRules().get(GameRules.MOB_GRIEFING)) return;
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || data.claimedPlots().isEmpty()
                || data.plans().stream().anyMatch(plan -> !plan.isComplete())) return;
        String id = settlement.get().id();
        var housing = HousingSavedData.get(level);
        housing.useSettlement(id);
        for (var home : housing.homes(id)) {
            if (!level.shouldTickBlocksAt(home.bed())) continue;
            if (!isBedHead(level, id, home.bed())) {
                housing.remove(home.bed());
                return;
            }
            if (home.stage() < STAGES.size()) {
                if (advance(level, data, housing, id, home)) return;
            }
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
                    for (int variant = 0; variant < 3; variant++) {
                        if (siteSuitable(level, id, bed, variant)) {
                            housing.add(new HousingSavedData.Home(bed, variant, 0, 0));
                            return;
                        }
                    }
                }
            }
        }
    }

    private static boolean advance(ServerLevel level, SettlementSavedData data,
                                   HousingSavedData housing, String id, HousingSavedData.Home home) {
        var steps = STAGES.get(home.stage());
        if (home.step() >= steps.size()) {
            housing.replace(new HousingSavedData.Home(home.bed(), home.blueprint(), home.stage() + 1, 0));
            return true;
        }
        BlockPos site = position(level, home.bed(), home.blueprint(), steps.get(home.step()));
        // Release an unowned worker before the site check: a temporarily blocked site must never keep a
        // cancelled or dead resident bound to this home, which would deadlock the home permanently.
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
        if (site == null || !permitted(level, id, site)) return false;
        if (level.getBlockState(site).is(Blocks.OAK_PLANKS)) {
            housing.replace(new HousingSavedData.Home(home.bed(), home.blueprint(), home.stage(), home.step() + 1));
            return true;
        }
        if (!level.getBlockState(site).isAir()) return false;
        var stock = PublicWarehouseInventory.snapshot(level, data);
        int reserve = home.stage() >= 2 ? 24 : 8;
        if (!stock.complete() || stock.oakPlanks() <= reserve) return false;
        var warehouse = PublicWarehouseInventory.firstWithOakPlank(level, data);
        if (warehouse.isPresent()) {
            var worker = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                            new AABB(warehouse.orElseThrow()).inflate(16.0),
                            GoblinCitizenEntity::isAvailableForConstruction)
                    .stream().min(Comparator
                            .comparingInt((GoblinCitizenEntity goblin) ->
                                    ProfessionRules.matchRank(WorkKind.HOUSING, goblin.profession()))
                            .thenComparingDouble(goblin ->
                                    goblin.blockPosition().distSqr(warehouse.orElseThrow())));
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

    public static boolean assignedSite(ServerLevel level, BlockPos bed, String workerId, BlockPos site) {
        var settlement = SettlementSavedData.get(level).settlement();
        if (settlement.isEmpty()) return false;
        return HousingSavedData.get(level).homes(settlement.orElseThrow().id()).stream()
                .anyMatch(home -> home.bed().equals(bed) && home.workerId().orElse("").equals(workerId)
                        && home.stage() < STAGES.size() && home.step() < STAGES.get(home.stage()).size()
                        && site.equals(position(level, bed, home.blueprint(),
                                STAGES.get(home.stage()).get(home.step()))));
    }

    public static boolean placeByResident(ServerLevel level, GoblinCitizenEntity worker,
                                          BlockPos bed, BlockPos site, ItemStack carried) {
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || !assignedSite(level, bed, worker.getUUID().toString(), site)
                || !carried.is(Items.OAK_PLANKS) || carried.getCount() != 1
                || worker.distanceToSqr(site.getCenter()) > 16.0
                || !level.getGameRules().get(GameRules.MOB_GRIEFING)
                || !permitted(level, settlement.orElseThrow().id(), worker.blockPosition())
                || !permitted(level, settlement.orElseThrow().id(), site)
                || !level.getBlockState(site).isAir()) return false;
        var housing = HousingSavedData.get(level);
        var home = housing.homes(settlement.orElseThrow().id()).stream()
                .filter(value -> value.bed().equals(bed)).findFirst().orElseThrow();
        if (!level.setBlock(site, Blocks.OAK_PLANKS.defaultBlockState(), 3)
                || !level.getBlockState(site).is(Blocks.OAK_PLANKS)) return false;
        housing.replace(new HousingSavedData.Home(home.bed(), home.blueprint(),
                home.stage(), home.step() + 1));
        return true;
    }

    private static boolean siteSuitable(ServerLevel level, String id, BlockPos bed, int variant) {
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
        for (var stage : STAGES) for (Step step : stage) {
            BlockPos site = position(level, bed, variant, step);
            if (site == null || !permitted(level, id, site)) return false;
            var state = level.getBlockState(site);
            if (!state.isAir() && !state.is(Blocks.OAK_PLANKS)) return false;
            if (step.y == 0) {
                BlockPos ground = site.below();
                if (!permitted(level, id, ground)
                        || !level.getBlockState(ground).isFaceSturdy(level, ground, Direction.UP)) return false;
            }
        }
        return true;
    }

    private static BlockPos position(ServerLevel level, BlockPos bed, int variant, Step step) {
        var state = level.getBlockState(bed);
        if (!state.hasProperty(BedBlock.FACING)) return null;
        Direction south = state.getValue(BedBlock.FACING).getOpposite();
        Direction east = south.getCounterClockWise();
        int x = variant == 2 ? -step.x : step.x;
        int z = variant == 1 ? -step.z : step.z;
        return bed.relative(east, x).relative(south, z).above(step.y);
    }

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

    private static List<List<Step>> blueprints() {
        var shelter = new ArrayList<Step>();
        for (int x : new int[] {-1, 1}) for (int z : new int[] {-1, 1})
            for (int y = 0; y <= 2; y++) shelter.add(new Step(x, y, z));
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            shelter.add(new Step(x, 3, z));
        var cabin = new ArrayList<Step>();
        for (int y = 0; y <= 2; y++) {
            for (int x = -2; x <= 2; x++) {
                cabin.add(new Step(x, y, -2));
                if (x != 0 || y == 2) cabin.add(new Step(x, y, 2));
            }
            for (int z = -1; z <= 1; z++) {
                cabin.add(new Step(-2, y, z));
                cabin.add(new Step(2, y, z));
            }
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            cabin.add(new Step(x, 3, z));
        var expanded = new ArrayList<Step>();
        for (int z = -1; z <= 1; z++) {
            expanded.add(new Step(3, 0, z));
            expanded.add(new Step(3, 3, z));
        }
        for (int y = 1; y <= 2; y++) {
            expanded.add(new Step(3, y, -1));
            expanded.add(new Step(3, y, 1));
        }
        var quality = new ArrayList<Step>();
        for (int x = -2; x <= 2; x++) quality.add(new Step(x, 4, 0));
        for (int z = -2; z <= 2; z++) quality.add(new Step(0, 4, z));
        var mature = new ArrayList<Step>();
        for (int z = -2; z <= 2; z++) mature.add(new Step(-3, 0, z));
        for (int x = -2; x <= 2; x++) mature.add(new Step(x, 4, -2));
        return List.of(List.copyOf(shelter), List.copyOf(cabin), List.copyOf(expanded),
                List.copyOf(quality), List.copyOf(mature));
    }
}
