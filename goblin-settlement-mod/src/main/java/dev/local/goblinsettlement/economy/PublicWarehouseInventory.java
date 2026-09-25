package dev.local.goblinsettlement.economy;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Optional;
import net.minecraft.core.BlockPos;
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
