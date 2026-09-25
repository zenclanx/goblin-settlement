package dev.local.goblinsettlement.housing;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;

/** Upgrades existing household beds with a small plank canopy, one real plank at a time. */
public final class HousingCoordinator {
    private static final int INTERVAL_TICKS = 40;
    private static final WeakHashMap<ServerLevel, BlockPos> ACTIVE_BED = new WeakHashMap<>();
    private static final int MIN_SPARE_PLANKS = 16;

    private HousingCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL_TICKS != 0
                || !level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || data.plans().stream().anyMatch(plan -> !plan.isComplete())
                || data.claimedPlots().isEmpty()) {
            return;
        }
        var stock = PublicWarehouseInventory.snapshot(level, data);
        if (!stock.complete() || stock.oakPlanks() <= MIN_SPARE_PLANKS) {
            return;
        }
        String id = settlement.get().id();
        BlockPos active = ACTIVE_BED.get(level);
        if (active != null && improveBed(level, data, id, active)) {
            return;
        }
        ACTIVE_BED.remove(level);

        // Scan only one 8x8 plot per interval; keep an unfinished bed in memory until done.
        int index = (int) ((level.getGameTime() / INTERVAL_TICKS) % data.claimedPlots().size());
        var plot = data.claimedPlots().get(index);
        int anchorY = settlement.get().anchor().getY();
        for (int x = plot.x() * 8; x < plot.x() * 8 + 8; x++) {
            for (int z = plot.z() * 8; z < plot.z() * 8 + 8; z++) {
                for (int y = anchorY - 2; y <= anchorY + 5; y++) {
                    BlockPos bed = new BlockPos(x, y, z);
                    if (isBedHead(level, id, bed) && improveBed(level, data, id, bed)) {
                        ACTIVE_BED.put(level, bed);
                        return;
                    }
                }
            }
        }
    }

    private static boolean improveBed(ServerLevel level, SettlementSavedData data,
                                      String settlementId, BlockPos bed) {
        if (!isBedHead(level, settlementId, bed)) {
            return false;
        }
        List<BlockPos> canopy = canopy(bed);
        if (!sitePermitted(level, settlementId, canopy)) {
            return false;
        }
        for (BlockPos next : canopy) {
            if (!level.getBlockState(next).isAir()) {
                continue;
            }
            var warehouse = PublicWarehouseInventory.firstWithOakPlank(level, data);
            if (warehouse.isEmpty()) {
                return true;
            }
            placeOnePlank(level, warehouse.get(), next);
            return true;
        }
        return false;
    }

    private static boolean isBedHead(ServerLevel level, String settlementId, BlockPos bed) {
        if (WorldModificationPermission.check(level, settlementId, bed)
                != WorldModificationPermission.Decision.ALLOWED) {
            return false;
        }
        var state = level.getBlockState(bed);
        return state.is(BlockTags.BEDS) && state.hasProperty(BedBlock.PART)
                && state.getValue(BedBlock.PART) == BedPart.HEAD;
    }

    private static boolean sitePermitted(ServerLevel level, String settlementId, List<BlockPos> canopy) {
        for (BlockPos pos : canopy) {
            if (WorldModificationPermission.check(level, settlementId, pos)
                    != WorldModificationPermission.Decision.ALLOWED) {
                return false;
            }
            var state = level.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.OAK_PLANKS)) {
                return false;
            }
        }
        return true;
    }

    private static List<BlockPos> canopy(BlockPos bed) {
        var positions = new ArrayList<BlockPos>(37);
        for (int y = 0; y < 3; y++) {
            for (int dx : new int[] {-2, 2}) {
                for (int dz : new int[] {-2, 2}) {
                    positions.add(bed.offset(dx, y, dz));
                }
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                positions.add(bed.offset(dx, 3, dz));
            }
        }
        return positions;
    }

    /** Deduct first; a failed placement refunds the same slot. No progress counter can dupe materials. */
    private static boolean placeOnePlank(ServerLevel level, BlockPos warehouse, BlockPos site) {
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)
                || !(level.getBlockEntity(warehouse) instanceof Container container)
                || !level.getBlockState(site).isAir()) {
            return false;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            var stack = container.getItem(slot);
            if (!stack.is(Items.OAK_PLANKS)) {
                continue;
            }
            stack.shrink(1);
            container.setChanged();
            if (level.setBlock(site, Blocks.OAK_PLANKS.defaultBlockState(), 3)) {
                return true;
            }
            stack.grow(1);
            container.setChanged();
            return false;
        }
        return false;
    }
}
