package dev.local.goblinsettlement.housing;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;

/** Adds at most one real bed per review, using one white bed from a live public chest. */
public final class BedProvisioningCoordinator {
    private static final int INTERVAL_TICKS = 20;
    private static final int PLOTS_PER_REVIEW = 4;
    private static final WeakHashMap<ServerLevel, Scan> SCANS = new WeakHashMap<>();
    private static final Direction[] DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private BedProvisioningCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL_TICKS != 0) return;
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            SCANS.remove(level);
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || data.claimedPlots().isEmpty()) {
            SCANS.remove(level);
            return;
        }
        String id = settlement.get().id();
        int y = settlement.get().anchor().getY();
        Scan scan = SCANS.get(level);
        if (scan == null || !scan.id.equals(id) || scan.y != y
                || !scan.plots.equals(data.claimedPlots())) {
            scan = new Scan(id, y, List.copyOf(data.claimedPlots()));
            SCANS.put(level, scan);
        }
        int reviewed = 0;
        while (reviewed < PLOTS_PER_REVIEW && scan.next < scan.plots.size()) {
            var plot = scan.plots.get(scan.next);
            if (scan.phase == Phase.FIND) {
                Site site = findSite(level, data, scan, plot);
                if (site != null) {
                    scan.site = site;
                    scan.phase = Phase.VERIFY;
                    scan.next = 0;
                    scan.count = 0;
                    scan.beds.clear();
                    return;
                }
            } else if (!countBeds(level, scan, plot)) {
                SCANS.remove(level);
                return;
            }
            scan.next++;
            reviewed++;
        }
        if (scan.next < scan.plots.size()) return;
        if (scan.phase == Phase.COUNT) {
            if (scan.count >= data.occupiedPopulationSlots() + 1) {
                SCANS.remove(level);
            } else {
                scan.phase = Phase.FIND;
                scan.next = 0;
            }
        } else if (scan.phase == Phase.FIND) {
            SCANS.remove(level);
        } else {
            // Only a complete second count may authorize a real withdrawal.
            if (scan.count < data.occupiedPopulationSlots() + 1
                    && PublicWarehouseInventory.snapshot(level, data).complete()
                    && siteSuitable(level, data, id, scan.site.foot(), scan.site.head(), scan.beds)) {
                placeFromWarehouse(level, data, id, scan.site.foot(), scan.site.head(), scan.site.facing());
            }
            SCANS.remove(level);
        }
    }

    private static boolean countBeds(ServerLevel level, Scan scan, SettlementSavedData.Plot plot) {
        for (int x = plot.x() * 8; x < plot.x() * 8 + 8; x++) {
            for (int z = plot.z() * 8; z < plot.z() * 8 + 8; z++) {
                for (int y = scan.y - 2; y <= scan.y + 5; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.shouldTickBlocksAt(pos)) return false;
                    BlockState state = level.getBlockState(pos);
                    if (!state.is(BlockTags.BEDS)) continue;
                    scan.beds.add(pos);
                    if (state.hasProperty(BedBlock.PART)
                            && state.getValue(BedBlock.PART) == BedPart.HEAD
                            && permitted(level, scan.id, pos)
                            && permitted(level, scan.id, pos.above())
                            && permitted(level, scan.id, pos.above(2))
                            && level.getBlockState(pos.above()).isAir()
                            && level.getBlockState(pos.above(2)).isAir()) scan.count++;
                }
            }
        }
        return true;
    }

    private static Site findSite(ServerLevel level, SettlementSavedData data, Scan scan,
                                 SettlementSavedData.Plot plot) {
        for (int x = plot.x() * 8; x < plot.x() * 8 + 8; x++) {
            for (int z = plot.z() * 8; z < plot.z() * 8 + 8; z++) {
                for (int dy : new int[] {0, -1, 1, -2, 2, 3, 4, 5}) {
                    BlockPos foot = new BlockPos(x, scan.y + dy, z);
                    for (Direction facing : DIRECTIONS) {
                        BlockPos head = foot.relative(facing);
                        if (siteSuitable(level, data, scan.id, foot, head, scan.beds)) {
                            return new Site(foot, head, facing);
                        }
                    }
                }
            }
        }
        return null;
    }

    private enum Phase { COUNT, FIND, VERIFY }

    private record Site(BlockPos foot, BlockPos head, Direction facing) { }

    private static final class Scan {
        private final String id;
        private final int y;
        private final List<SettlementSavedData.Plot> plots;
        private final List<BlockPos> beds = new ArrayList<>();
        private Phase phase = Phase.COUNT;
        private int next;
        private int count;
        private Site site;

        private Scan(String id, int y, List<SettlementSavedData.Plot> plots) {
            this.id = id;
            this.y = y;
            this.plots = plots;
        }
    }
    private static boolean siteSuitable(ServerLevel level, SettlementSavedData data, String id,
                                        BlockPos foot, BlockPos head, List<BlockPos> beds) {
        for (BlockPos pos : List.of(foot, head)) {
            // Reject occupied and weak ground before the more expensive permission checks.
            if (!level.shouldTickBlocksAt(pos)
                    || !level.getBlockState(pos).isAir()
                    || !level.getBlockState(pos.above()).isAir()
                    || !level.getBlockState(pos.above(2)).isAir()
                    || !level.getFluidState(pos).isEmpty()
                    || !level.getFluidState(pos.below()).isEmpty()
                    || !level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                    || nearFacilities(data, pos) || nearBed(beds, pos)
                    || !permitted(level, id, pos) || !permitted(level, id, pos.below())
                    || !permitted(level, id, pos.above()) || !permitted(level, id, pos.above(2))) {
                return false;
            }
        }
        return true;
    }
    private static boolean nearFacilities(SettlementSavedData data, BlockPos pos) {
        for (BlockPos warehouse : data.warehouses()) {
            if (near(pos, warehouse, 3)) return true;
        }
        for (var farm : data.farmSites()) {
            if (near(pos, farm.cropPos(), 3)) return true;
        }
        for (var plan : data.plans()) {
            for (int step = 0; step < 2; step++) {
                if (near(pos, plan.start().east(step), 3)) return true;
            }
        }
        return false;
    }

    private static boolean nearBed(List<BlockPos> beds, BlockPos pos) {
        for (BlockPos bed : beds) {
            if (near(pos, bed, 4)) return true;
        }
        return false;
    }

    private static boolean near(BlockPos first, BlockPos second, int radius) {
        return Math.abs(first.getX() - second.getX()) <= radius
                && Math.abs(first.getZ() - second.getZ()) <= radius
                && Math.abs(first.getY() - second.getY()) <= 4;
    }

    private static boolean permitted(ServerLevel level, String id, BlockPos pos) {
        return WorldModificationPermission.check(level, id, pos)
                == WorldModificationPermission.Decision.ALLOWED;
    }

    private static boolean placeFromWarehouse(ServerLevel level, SettlementSavedData data, String id,
                                              BlockPos foot, BlockPos head, Direction facing) {
        for (BlockPos warehouse : data.warehouses()) {
            if (!permitted(level, id, warehouse)
                    || !(level.getBlockEntity(warehouse) instanceof Container container)) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.getItem(slot).is(Items.WHITE_BED)) {
                    continue;
                }
                if (!level.getGameRules().get(GameRules.MOB_GRIEFING)
                        || !permitted(level, id, foot) || !permitted(level, id, head)
                        || !level.getBlockState(foot).isAir() || !level.getBlockState(head).isAir()) {
                    return false;
                }
                var original = container.getItem(slot).copy();
                container.removeItem(slot, 1);
                container.setChanged();
                BlockState footState = Blocks.WHITE_BED.defaultBlockState()
                        .setValue(BedBlock.FACING, facing).setValue(BedBlock.PART, BedPart.FOOT);
                BlockState headState = footState.setValue(BedBlock.PART, BedPart.HEAD);
                boolean placed = level.setBlock(foot, footState, 3)
                        && level.setBlock(head, headState, 3)
                        && level.getBlockState(foot).equals(footState)
                        && level.getBlockState(head).equals(headState);
                if (placed) {
                    return true;
                }
                // Only remove our own halves, then put the exact withdrawn stack back.
                if (level.getBlockState(head).equals(headState)) {
                    level.setBlock(head, Blocks.AIR.defaultBlockState(), 2);
                }
                if (level.getBlockState(foot).equals(footState)) {
                    level.setBlock(foot, Blocks.AIR.defaultBlockState(), 2);
                }
                container.setItem(slot, original);
                container.setChanged();
                return false;
            }
        }
        return false;
    }
}
