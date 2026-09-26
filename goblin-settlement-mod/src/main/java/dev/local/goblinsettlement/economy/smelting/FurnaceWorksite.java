package dev.local.goblinsettlement.economy.smelting;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Feeds a real furnace, waits for vanilla smelting, then moves its output into public storage. */
public final class FurnaceWorksite {
    public enum Ore {
        IRON(Items.RAW_IRON, Items.IRON_INGOT, 16),
        COPPER(Items.RAW_COPPER, Items.COPPER_INGOT, 16),
        GOLD(Items.RAW_GOLD, Items.GOLD_INGOT, 8);

        private final Item raw;
        private final Item ingot;
        private final int stockLimit;

        Ore(Item raw, Item ingot, int stockLimit) {
            this.raw = raw;
            this.ingot = ingot;
            this.stockLimit = stockLimit;
        }

        public Item raw() {
            return raw;
        }

        public Item ingot() {
            return ingot;
        }

        public int stockLimit() {
            return stockLimit;
        }
    }

    private FurnaceWorksite() {
    }

    /** The first ore whose real raw stock and coal are both present and whose output is still under its cap. */
    public static Optional<Ore> firstFeedable(ServerLevel level, String settlementId, BlockPos warehousePos) {
        Optional<Container> warehouse = warehouse(level, settlementId, warehousePos);
        if (warehouse.isEmpty()) {
            return Optional.empty();
        }
        for (Ore ore : Ore.values()) {
            if (countAll(level, settlementId, ore.ingot()) >= ore.stockLimit()) {
                continue;
            }
            if (slotWith(warehouse.get(), ore.raw()) >= 0 && slotWith(warehouse.get(), Items.COAL) >= 0) {
                return Optional.of(ore);
            }
        }
        return Optional.empty();
    }

    /** A resident task calls this only after reaching both the registered chest and furnace. */
    public static boolean feed(ServerLevel level, GoblinCitizenEntity worker, String settlementId,
            BlockPos warehousePos, BlockPos furnacePos, Ore ore) {
        if (level == null || worker == null || settlementId == null || warehousePos == null
                || furnacePos == null || ore == null || worker.level() != level || !worker.isAlive()
                || warehousePos.distSqr(furnacePos) > 16.0
                || worker.distanceToSqr(furnacePos.getX() + 0.5, furnacePos.getY() + 0.5,
                        furnacePos.getZ() + 0.5) > 9.0
                || SmeltingSavedData.get(level).batch().isPresent()
                || !allowed(level, settlementId, warehousePos)
                || !allowed(level, settlementId, furnacePos)
                || !level.getBlockState(furnacePos).is(Blocks.FURNACE)) {
            return false;
        }
        Optional<Container> warehouse = warehouse(level, settlementId, warehousePos);
        if (warehouse.isEmpty() || !(level.getBlockEntity(furnacePos) instanceof Container furnace)
                || !furnace.getItem(0).isEmpty() || !furnace.getItem(1).isEmpty()
                || !furnace.getItem(2).isEmpty()
                || countAll(level, settlementId, ore.ingot) >= ore.stockLimit) {
            return false;
        }
        int rawSlot = slotWith(warehouse.get(), ore.raw);
        int coalSlot = slotWith(warehouse.get(), Items.COAL);
        if (rawSlot < 0 || coalSlot < 0 || !furnace.canPlaceItem(0, new ItemStack(ore.raw))
                || !furnace.canPlaceItem(1, new ItemStack(Items.COAL))) {
            return false;
        }
        // Raw ore and fuel must be separate item types; both remain real items throughout.
        ItemStack raw = warehouse.get().removeItem(rawSlot, 1);
        ItemStack coal = warehouse.get().removeItem(coalSlot, 1);
        if (!raw.is(ore.raw) || raw.getCount() != 1 || !coal.is(Items.COAL) || coal.getCount() != 1) {
            restore(warehouse.get(), raw, coal);
            return false;
        }
        SmeltingSavedData.Batch batch = new SmeltingSavedData.Batch(settlementId,
                furnacePos.immutable(), warehousePos.immutable(), ore);
        if (!SmeltingSavedData.get(level).begin(batch)) {
            restore(warehouse.get(), raw, coal);
            return false;
        }
        furnace.setItem(0, raw);
        furnace.setItem(1, coal);
        furnace.setChanged();
        warehouse.get().setChanged();
        return true;
    }

    /** Pick up only the saved batch's expected output; the worker must be at the furnace. */
    public static boolean collect(ServerLevel level, GoblinCitizenEntity worker) {
        if (level == null || worker == null || worker.level() != level || !worker.isAlive()) return false;
        var saved = SmeltingSavedData.get(level);
        if (saved.batch().isEmpty()) return false;
        var batch = saved.batch().get();
        if (!allowed(level, batch.settlementId(), batch.furnace())
                || !allowed(level, batch.settlementId(), batch.warehouse())
                || !level.getBlockState(batch.furnace()).is(Blocks.FURNACE)
                || worker.distanceToSqr(batch.furnace().getX() + 0.5, batch.furnace().getY() + 0.5,
                        batch.furnace().getZ() + 0.5) > 9.0
                || !(level.getBlockEntity(batch.furnace()) instanceof Container furnace)) {
            return false;
        }
        Optional<Container> warehouse = warehouse(level, batch.settlementId(), batch.warehouse());
        ItemStack output = furnace.getItem(2);
        if (warehouse.isEmpty() || !output.is(batch.ore().ingot) || output.getCount() != 1
                || !canFit(warehouse.get(), output)) {
            return false;
        }
        ItemStack taken = furnace.removeItem(2, 1);
        if (!taken.is(batch.ore().ingot) || taken.getCount() != 1) return false;
        insert(warehouse.get(), taken);
        furnace.setChanged();
        warehouse.get().setChanged();
        saved.clear();
        return true;
    }

    /** Reconcile only a provably empty or missing furnace, without fabricating refunds. */
    public static boolean forgetLostBatch(ServerLevel level) {
        var saved = SmeltingSavedData.get(level);
        if (saved.batch().isEmpty()) return false;
        var batch = saved.batch().get();
        if (!level.shouldTickBlocksAt(batch.furnace())) return false;
        if (level.getBlockEntity(batch.furnace()) instanceof Container furnace
                && (!furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty())) return false;
        saved.clear();
        return true;
    }

    private static void restore(Container warehouse, ItemStack raw, ItemStack coal) {
        if (!raw.isEmpty()) insert(warehouse, raw);
        if (!coal.isEmpty()) insert(warehouse, coal);
        warehouse.setChanged();
    }

    private static int slotWith(Container container, Item item) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) return slot;
        }
        return -1;
    }

    private static boolean canFit(Container container, ItemStack incoming) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.canPlaceItem(slot, incoming)) continue;
            ItemStack held = container.getItem(slot);
            if (held.isEmpty() || ItemStack.isSameItemSameComponents(held, incoming)
                    && held.getCount() < Math.min(held.getMaxStackSize(), container.getMaxStackSize(held))) {
                return true;
            }
        }
        return false;
    }

    private static void insert(Container container, ItemStack incoming) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.canPlaceItem(slot, incoming)) continue;
            ItemStack held = container.getItem(slot);
            if (held.isEmpty()) container.setItem(slot, incoming.copy());
            else if (ItemStack.isSameItemSameComponents(held, incoming)
                    && held.getCount() < Math.min(held.getMaxStackSize(), container.getMaxStackSize(held))) {
                held.grow(incoming.getCount());
            } else continue;
            incoming.setCount(0);
            return;
        }
    }

    private static int countAll(ServerLevel level, String id, Item item) {
        int total = 0;
        for (BlockPos pos : SettlementSavedData.get(level).warehouses()) {
            Optional<Container> container = warehouse(level, id, pos);
            if (container.isEmpty()) continue;
            for (int slot = 0; slot < container.get().getContainerSize(); slot++) {
                ItemStack stack = container.get().getItem(slot);
                if (stack.is(item)) total += stack.getCount();
            }
        }
        return total;
    }

    private static Optional<Container> warehouse(ServerLevel level, String id, BlockPos pos) {
        if (!SettlementSavedData.get(level).warehouses().contains(pos) || !allowed(level, id, pos)
                || !(level.getBlockEntity(pos) instanceof Container container)) return Optional.empty();
        return Optional.of(container);
    }

    private static boolean allowed(ServerLevel level, String id, BlockPos pos) {
        return WorldModificationPermission.check(level, id, pos)
                == WorldModificationPermission.Decision.ALLOWED;
    }
}
