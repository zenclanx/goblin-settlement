package dev.local.goblinsettlement.farming;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.construction.ConstructionPlan;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import dev.local.goblinsettlement.planning.math.PlotCoordinates;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Finds existing farmland on active claimed plots without creating or changing blocks. */
public final class FarmDiscoveryCoordinator {
    private static final int SCAN_INTERVAL_TICKS = 40;

    private FarmDiscoveryCoordinator() {
    }

    /** Register with END_WORLD_TICK; one claimed 8x8 plot is scanned per interval. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || data.claimedPlots().isEmpty()) {
            return;
        }
        retireInvalidIdleSites(level, data, settlement.get().id());
        if (data.farmSites().size() >= SettlementSavedData.MAX_FARM_SITES) {
            return;
        }
        int plotIndex = (int) ((level.getGameTime() / SCAN_INTERVAL_TICKS) % data.claimedPlots().size());
        var plot = data.claimedPlots().get(plotIndex);
        String settlementId = settlement.get().id();
        int startX = plot.x() * PlotCoordinates.PLOT_SIZE;
        int startZ = plot.z() * PlotCoordinates.PLOT_SIZE;
        int minY = Math.max(level.getMinY(), settlement.get().anchor().getY() - 8);
        // getMaxY() is inclusive; leave one block above farmland for the crop.
        int maxFarmlandY = Math.min(level.getMaxY() - 1, settlement.get().anchor().getY() + 8);
        if (minY > maxFarmlandY) {
            return;
        }
        for (int localX = 0; localX < PlotCoordinates.PLOT_SIZE; localX++) {
            for (int localZ = 0; localZ < PlotCoordinates.PLOT_SIZE; localZ++) {
                int x = startX + localX;
                int z = startZ + localZ;
                BlockPos column = new BlockPos(x, minY, z);
                if (WorldModificationPermission.check(level, settlementId, column)
                        != WorldModificationPermission.Decision.ALLOWED) {
                    continue;
                }
                for (int y = maxFarmlandY; y >= minY; y--) {
                    BlockPos farmland = new BlockPos(x, y, z);
                    if (!level.getBlockState(farmland).is(Blocks.FARMLAND)) {
                        continue;
                    }
                    BlockPos crop = farmland.above();
                    if (WorldModificationPermission.check(level, settlementId, farmland)
                            != WorldModificationPermission.Decision.ALLOWED
                            || WorldModificationPermission.check(level, settlementId, crop)
                            != WorldModificationPermission.Decision.ALLOWED
                            || !(level.getBlockState(crop).isAir()
                            || level.getBlockState(crop).is(Blocks.WHEAT))
                            || conflictsWithActiveConstruction(data, crop)) {
                        continue;
                    }
                    data.registerFarmSite(crop);
                    if (data.farmSites().size() >= SettlementSavedData.MAX_FARM_SITES) {
                        return;
                    }
                }
            }
        }
    }

    /** Inactive chunks and assigned work are left untouched until their state is known. */
    private static void retireInvalidIdleSites(ServerLevel level, SettlementSavedData data, String settlementId) {
        for (var site : data.farmSites()) {
            BlockPos crop = site.cropPos();
            if (site.workerId().isPresent() || !level.shouldTickBlocksAt(crop)) {
                continue;
            }
            boolean valid = WorldModificationPermission.check(level, settlementId, crop)
                    == WorldModificationPermission.Decision.ALLOWED
                    && WorldModificationPermission.check(level, settlementId, crop.below())
                    == WorldModificationPermission.Decision.ALLOWED
                    && level.getBlockState(crop.below()).is(Blocks.FARMLAND)
                    && (level.getBlockState(crop).isAir() || level.getBlockState(crop).is(Blocks.WHEAT));
            if (!valid) {
                data.removeIdleFarmSite(crop);
            }
        }
    }

    private static boolean conflictsWithActiveConstruction(SettlementSavedData data, BlockPos crop) {
        for (var plan : data.plans()) {
            if (plan.isComplete()) {
                continue;
            }
            for (int offset = 0; offset < ConstructionPlan.LENGTH; offset++) {
                BlockPos site = plan.start().east(offset);
                if (site.getX() == crop.getX() && site.getZ() == crop.getZ()) {
                    return true;
                }
            }
        }
        return false;
    }
}