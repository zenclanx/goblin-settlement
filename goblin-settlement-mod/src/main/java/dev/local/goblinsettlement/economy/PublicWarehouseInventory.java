package dev.local.goblinsettlement.economy;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Reads live containers; registered positions never cache item counts. */
public final class PublicWarehouseInventory {
    private PublicWarehouseInventory() {
    }

    /** The first accessible warehouse holding at least one of the given item. */
    public static Optional<BlockPos> firstHolding(ServerLevel level, SettlementSavedData data,
                                                  Item item) {
        return data.warehouses().stream()
                .filter(pos -> countAt(level, data, pos, item) > 0).findFirst();
    }

    public static Optional<BlockPos> firstWithWheatSeeds(ServerLevel level, SettlementSavedData data) {
        return data.warehouses().stream().filter(pos -> {
            if (!isAccessible(level, data, pos)) return false;
            Container container = (Container) level.getBlockEntity(pos);
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).is(Items.WHEAT_SEEDS)) return true;
            }
            return false;
        }).findFirst();
    }

    public static Optional<BlockPos> firstAccessible(ServerLevel level, SettlementSavedData data) {
        return data.warehouses().stream().filter(pos -> isAccessible(level, data, pos)).findFirst();
    }

    /** Counts one item kind across accessible, permitted public warehouses. */
    public static int countOf(ServerLevel level, SettlementSavedData data, Item item) {
        int total = 0;
        for (BlockPos pos : data.warehouses()) {
            total = Math.addExact(total, countAt(level, data, pos, item));
        }
        return total;
    }

    /** Reads only active, permitted containers and marks missing chunks as incomplete. */
    public static WarehouseSupply snapshot(ServerLevel level, SettlementSavedData data) {
        int food = 0;
        int seeds = 0;
        int hoes = 0;
        int axes = 0;
        int pickaxes = 0;
        int planks = 0;
        int containers = 0;
        boolean complete = true;
        for (BlockPos pos : data.warehouses()) {
            if (!level.shouldTickBlocksAt(pos)) {
                complete = false;
                continue;
            }
            if (!isAccessible(level, data, pos)) {
                complete = false;
                continue;
            }
            containers++;
            Container container = (Container) level.getBlockEntity(pos);
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                var stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                int count = stack.getCount();
                if (stack.get(DataComponents.FOOD) != null) {
                    food = Math.addExact(food, count);
                }
                if (stack.is(Items.WHEAT_SEEDS)) {
                    seeds = Math.addExact(seeds, count);
                }
                if (stack.is(Items.WOODEN_HOE) || stack.is(Items.STONE_HOE)) {
                    hoes = Math.addExact(hoes, count);
                }
                if (stack.is(Items.WOODEN_AXE) || stack.is(Items.STONE_AXE)) {
                    axes = Math.addExact(axes, count);
                }
                if (stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.STONE_PICKAXE)) {
                    pickaxes = Math.addExact(pickaxes, count);
                }
                if (stack.is(Items.OAK_PLANKS)) {
                    planks = Math.addExact(planks, count);
                }
            }
        }
        return new WarehouseSupply(food, seeds, hoes, axes, pickaxes, planks, containers, complete);
    }

    /**
     * Places as much of {@code stack} as fits into accessible registered warehouses, shrinking it by the
     * amount moved. Returns true only once the stack is empty, so callers can tell a full or partial fit.
     */
    public static boolean storeInAccessible(ServerLevel level, SettlementSavedData data, ItemStack stack) {
        for (BlockPos pos : data.warehouses()) {
            if (stack.isEmpty()) break;
            if (!isAccessible(level, data, pos)) continue;
            Container container = (Container) level.getBlockEntity(pos);
            int before = stack.getCount();
            ContainerStorage.insert(container, stack);
            if (stack.getCount() != before) container.setChanged();
        }
        return stack.isEmpty();
    }

    private static int countAt(ServerLevel level, SettlementSavedData data, BlockPos pos, Item item) {
        if (!isAccessible(level, data, pos)) {
            return 0;
        }
        Container container = (Container) level.getBlockEntity(pos);
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                count += container.getItem(slot).getCount();
            }
        }
        return count;
    }

    private static boolean isAccessible(ServerLevel level, SettlementSavedData data, BlockPos pos) {
        var settlement = data.settlement();
        if (settlement.isEmpty() || WorldModificationPermission.check(level, settlement.get().id(), pos)
                != WorldModificationPermission.Decision.ALLOWED) {
            return false;
        }
        return level.getBlockEntity(pos) instanceof Container;
    }
}
