package dev.local.goblinsettlement.economy;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;

/** Reads live containers; registered positions never cache item counts. */
public final class PublicWarehouseInventory {
    private PublicWarehouseInventory() {
    }

    public static Optional<BlockPos> firstWithOakPlank(ServerLevel level, SettlementSavedData data) {
        return data.warehouses().stream().filter(pos -> countAt(level, data, pos) > 0).findFirst();
    }

    public static Optional<BlockPos> firstAccessible(ServerLevel level, SettlementSavedData data) {
        return data.warehouses().stream().filter(pos -> isAccessible(level, data, pos)).findFirst();
    }

    public static int countOakPlanks(ServerLevel level, SettlementSavedData data) {
        return data.warehouses().stream().mapToInt(pos -> countAt(level, data, pos)).sum();
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

    private static int countAt(ServerLevel level, SettlementSavedData data, BlockPos pos) {
        if (!isAccessible(level, data, pos)) {
            return 0;
        }
        Container container = (Container) level.getBlockEntity(pos);
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(Items.OAK_PLANKS)) {
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
