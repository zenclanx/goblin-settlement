package dev.local.goblinsettlement.colony;

import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Claims one nearby plot per active twenty-minute window when developed land is crowded. */
public final class ExpansionCoordinator {
    private static final long WINDOW_TICKS = 20L * 60 * 20;
    private static final int PLOT_SIZE = 8;
    private static final int MAX_DISTANCE = 96;

    private ExpansionCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % WINDOW_TICKS != 0) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return;
        }
        int adults = data.adultCount();
        if (PopulationRules.expansionBudget(adults, data.claimedPlots().size()) == 0
                || data.plans().stream().anyMatch(plan -> !plan.isComplete())) {
            return;
        }
        var supply = PublicWarehouseInventory.snapshot(level, data);
        var demand = SettlementDemand.assess(adults, data.childCount(), supply, false);
        if (demand.priority() != SettlementDemand.Priority.READY || supply.oakPlanks() < 2) {
            return;
        }
        int freeCells = freeBuildCells(level, data, settlement.get().id(), settlement.get().anchor().getY());
        if (freeCells < 0 || freeCells >= Math.max(16, adults * 2)) {
            return;
        }

        BlockPos anchor = settlement.get().anchor();
        Set<SettlementSavedData.Plot> candidates = new HashSet<>();
        for (var plot : data.claimedPlots()) {
            candidates.add(new SettlementSavedData.Plot(plot.x() + 1, plot.z()));
            candidates.add(new SettlementSavedData.Plot(plot.x() - 1, plot.z()));
            candidates.add(new SettlementSavedData.Plot(plot.x(), plot.z() + 1));
            candidates.add(new SettlementSavedData.Plot(plot.x(), plot.z() - 1));
        }
        candidates.stream()
                .filter(plot -> !data.claimedPlots().contains(plot))
                .filter(plot -> inRange(plot, anchor))
                .filter(plot -> suitable(level, data, plot, anchor.getY()))
                .min(Comparator.comparingLong((SettlementSavedData.Plot plot) -> distanceSquared(plot, anchor))
                        .thenComparingInt(SettlementSavedData.Plot::x)
                        .thenComparingInt(SettlementSavedData.Plot::z))
                .ifPresent(plot -> {
                    var withdrawals = consumeExpansionCost(level, data, settlement.get().id());
                    if (withdrawals != null && !data.claimPlot(plot)) {
                        restore(withdrawals);
                    }
                });
    }

    /** Consumes two real planks across active public warehouses, restoring on failure. */
    private static List<WarehouseBefore> consumeExpansionCost(ServerLevel level, SettlementSavedData data,
                                                             String settlementId) {
        int remaining = 2;
        var withdrawals = new ArrayList<WarehouseBefore>();
        for (BlockPos pos : data.warehouses()) {
            if (remaining == 0) {
                break;
            }
            if (WorldModificationPermission.check(level, settlementId, pos)
                    != WorldModificationPermission.Decision.ALLOWED
                    || !(level.getBlockEntity(pos) instanceof Container container)) {
                continue;
            }
            List<ItemStack> before = null;
            for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.is(Items.OAK_PLANKS)) {
                    continue;
                }
                if (before == null) {
                    before = new ArrayList<>(container.getContainerSize());
                    for (int savedSlot = 0; savedSlot < container.getContainerSize(); savedSlot++) {
                        before.add(container.getItem(savedSlot).copy());
                    }
                    withdrawals.add(new WarehouseBefore(container, before));
                }
                int take = Math.min(remaining, stack.getCount());
                ItemStack removed = container.removeItem(slot, take);
                if (!removed.is(Items.OAK_PLANKS) || removed.getCount() != take) {
                    restore(withdrawals);
                    return null;
                }
                remaining -= take;
            }
            if (before != null) {
                container.setChanged();
            }
        }
        if (remaining != 0) {
            restore(withdrawals);
            return null;
        }
        return withdrawals;
    }

    private static void restore(List<WarehouseBefore> withdrawals) {
        for (var withdrawal : withdrawals) {
            for (int slot = 0; slot < withdrawal.contents().size(); slot++) {
                withdrawal.container().setItem(slot, withdrawal.contents().get(slot).copy());
            }
            withdrawal.container().setChanged();
        }
    }

    private record WarehouseBefore(Container container, List<ItemStack> contents) {
    }

    /** Returns -1 when any claimed plot is inactive; unknown space must not trigger expansion. */
    private static int freeBuildCells(ServerLevel level, SettlementSavedData data, String id, int y) {
        int count = 0;
        for (var plot : data.claimedPlots()) {
            for (int x = plot.x() * PLOT_SIZE; x < plot.x() * PLOT_SIZE + PLOT_SIZE; x++) {
                for (int z = plot.z() * PLOT_SIZE; z < plot.z() * PLOT_SIZE + PLOT_SIZE; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.shouldTickBlocksAt(pos)) {
                        return -1;
                    }
                    if (WorldModificationPermission.check(level, id, pos)
                            == WorldModificationPermission.Decision.ALLOWED
                            && level.getBlockState(pos).isAir()
                            && level.getBlockState(pos.above()).isAir()
                            && !level.getBlockState(pos.below()).isAir()
                            && level.getBlockState(pos.below()).getFluidState().isEmpty()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    private static boolean inRange(SettlementSavedData.Plot plot, BlockPos anchor) {
        return distanceSquared(plot, anchor) <= (long) MAX_DISTANCE * MAX_DISTANCE;
    }

    private static long distanceSquared(SettlementSavedData.Plot plot, BlockPos anchor) {
        long dx = 8L * plot.x() + 4 - anchor.getX();
        long dz = 8L * plot.z() + 4 - anchor.getZ();
        return dx * dx + dz * dz;
    }

    /** The initial candidate filter accepts level land with room for paths and buildings. */
    private static boolean suitable(ServerLevel level, SettlementSavedData data,
                                    SettlementSavedData.Plot plot, int anchorY) {
        int usable = 0;
        int low = Integer.MAX_VALUE;
        int high = Integer.MIN_VALUE;
        for (int x = plot.x() * PLOT_SIZE; x < plot.x() * PLOT_SIZE + PLOT_SIZE; x++) {
            for (int z = plot.z() * PLOT_SIZE; z < plot.z() * PLOT_SIZE + PLOT_SIZE; z++) {
                BlockPos column = new BlockPos(x, anchorY, z);
                if (!level.shouldTickBlocksAt(column) || data.isProtected(column)) {
                    return false;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos feet = column.offset(0, dy, 0);
                    if (level.getBlockState(feet).isAir()
                            && level.getBlockState(feet.above()).isAir()
                            && !level.getBlockState(feet.below()).isAir()
                            && level.getBlockState(feet.below()).getFluidState().isEmpty()) {
                        low = Math.min(low, dy);
                        high = Math.max(high, dy);
                        usable++;
                        break;
                    }
                }
            }
        }
        return usable >= 48 && high - low <= 2;
    }
}
