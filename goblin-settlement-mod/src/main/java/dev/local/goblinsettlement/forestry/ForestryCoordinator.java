package dev.local.goblinsettlement.forestry;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Comparator;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/** Processes real oak logs first, then fells one segment of a marked tree if planks are low. */
public final class ForestryCoordinator {
    public static final int TARGET_OAK_PLANKS = 16;
    private static final int MAX_TRUNK_HEIGHT = 8;
    private static final int MAX_STORED_LOGS = TARGET_OAK_PLANKS / 4;

    private ForestryCoordinator() {
    }

    /** Register with END_WORLD_TICK after food, tools, farming, and construction. */
    public static void tick(ServerLevel level) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || data.adultCount() == 0) {
            return;
        }
        String id = settlement.get().id();
        var stock = PublicWarehouseInventory.snapshot(level, data);
        if (!stock.complete() || stock.accessibleContainers() == 0) {
            return;
        }
        if (!level.getEntitiesOfClass(GoblinCitizenEntity.class,
                new AABB(settlement.get().anchor()).inflate(256.0),
                goblin -> goblin.hasForestryWork(id)).isEmpty()) {
            return;
        }
        // Recover real sapling drops from marked trees, even when planks are plentiful.
        Optional<BlockPos> recoveryWarehouse = firstWarehouseWithSpace(level, data, id, Items.OAK_SAPLING);
        if (recoveryWarehouse.isPresent()) {
            for (var tree : ForestrySavedData.get(level).markedTrees()) {
                if (!tree.settlementId().equals(id)
                        || WorldModificationPermission.check(level, id, tree.root())
                        != WorldModificationPermission.Decision.ALLOWED) {
                    continue;
                }
                for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class,
                        new AABB(tree.root()).inflate(5.0),
                        item -> item.isAlive() && item.getItem().is(Items.OAK_SAPLING)
                                && WorldModificationPermission.check(level, id, item.blockPosition())
                                == WorldModificationPermission.Decision.ALLOWED)) {
                    var worker = nearestWorker(level, data, id, drop.blockPosition());
                    if (worker.isPresent() && worker.get().assignSaplingRecovery(
                            id, recoveryWarehouse.get(), tree.root(), drop.getUUID().toString(),
                            drop.blockPosition())) {
                        return;
                    }
                }
            }
        }
        if (stock.oakPlanks() >= TARGET_OAK_PLANKS) {
            return;
        }
        for (BlockPos warehouse : data.warehouses()) {
            if (WorldModificationPermission.check(level, id, warehouse)
                    != WorldModificationPermission.Decision.ALLOWED
                    || !(level.getBlockEntity(warehouse) instanceof Container container)
                    || !hasItem(container, Items.OAK_LOG)) {
                continue;
            }
            Optional<BlockPos> table = nearbyTable(level, id, warehouse);
            if (table.isEmpty()) {
                continue;
            }
            var worker = nearestWorker(level, data, id, warehouse);
            if (worker.isPresent() && worker.get().assignWoodProcessing(id, warehouse, table.get())) {
                return;
            }
        }
        // Do not stall felling because an existing log has no usable worktable.
        // A real-stock cap prevents the inaccessible processing step from filling boxes.
        if (countStoredLogs(level, data, id) >= MAX_STORED_LOGS) {
            return;
        }
        boolean bareHandRecovery = canRecoverWithoutAxe(level, data);
        for (var tree : ForestrySavedData.get(level).markedTrees()) {
            if (!tree.settlementId().equals(id)) {
                continue;
            }
            Optional<BlockPos> target = nextLog(level, id, tree.root());
            if (target.isEmpty()) {
                continue;
            }
            for (BlockPos warehouse : data.warehouses()) {
                if (WorldModificationPermission.check(level, id, warehouse)
                        != WorldModificationPermission.Decision.ALLOWED
                        || !(level.getBlockEntity(warehouse) instanceof Container container)
                        || !hasRoomFor(container, Items.OAK_LOG)
                        || (!bareHandRecovery && !hasItem(container, Items.WOODEN_AXE)
                            && !hasItem(container, Items.STONE_AXE))) {
                    continue;
                }
                // Never remove the final trunk block without a real replacement sapling.
                if (target.get().equals(tree.root()) && !hasItem(container, Items.OAK_SAPLING)) {
                    continue;
                }
                var worker = nearestWorker(level, data, id, tree.root());
                if (worker.isPresent() && worker.get().assignTreeFelling(
                        id, warehouse, tree.root(), target.get())) {
                    return;
                }
            }
        }
    }

    public static boolean canRecoverWithoutAxe(ServerLevel level, SettlementSavedData data) {
        var stock = PublicWarehouseInventory.snapshot(level, data);
        return stock.complete() && stock.accessibleContainers() > 0
                && stock.oakPlanks() == 0 && stock.axes() == 0;
    }

    private static int countStoredLogs(ServerLevel level, SettlementSavedData data, String id) {
        int count = 0;
        for (BlockPos warehouse : data.warehouses()) {
            if (WorldModificationPermission.check(level, id, warehouse)
                    != WorldModificationPermission.Decision.ALLOWED
                    || !(level.getBlockEntity(warehouse) instanceof Container container)) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).is(Items.OAK_LOG)) {
                    count += container.getItem(slot).getCount();
                }
            }
        }
        return count;
    }

    private static boolean hasRoomFor(Container container, net.minecraft.world.item.Item item) {
        var incoming = new net.minecraft.world.item.ItemStack(item);
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.canPlaceItem(slot, incoming)) {
                continue;
            }
            var stored = container.getItem(slot);
            if (stored.isEmpty() || (stored.is(item) && stored.getCount() <
                    Math.min(stored.getMaxStackSize(), container.getMaxStackSize(stored)))) {
                return true;
            }
        }
        return false;
    }

    private static Optional<BlockPos> firstWarehouseWithSpace(ServerLevel level, SettlementSavedData data,
                                                               String id, net.minecraft.world.item.Item item) {
        return data.warehouses().stream().filter(pos ->
                WorldModificationPermission.check(level, id, pos)
                        == WorldModificationPermission.Decision.ALLOWED
                        && level.getBlockEntity(pos) instanceof Container container
                        && hasRoomFor(container, item)).findFirst();
    }

    public static Optional<BlockPos> nextLog(ServerLevel level, String id, BlockPos root) {
        if (WorldModificationPermission.check(level, id, root)
                != WorldModificationPermission.Decision.ALLOWED
                || WorldModificationPermission.check(level, id, root.below())
                != WorldModificationPermission.Decision.ALLOWED
                || !level.getBlockState(root).is(Blocks.OAK_LOG)
                || !isSoil(level, root.below())) {
            return Optional.empty();
        }
        BlockPos top = root;
        for (int offset = 1; offset < MAX_TRUNK_HEIGHT; offset++) {
            BlockPos next = root.above(offset);
            if (WorldModificationPermission.check(level, id, next)
                    != WorldModificationPermission.Decision.ALLOWED) {
                return Optional.empty();
            }
            if (!level.getBlockState(next).is(Blocks.OAK_LOG)) {
                break;
            }
            if (level.getBlockEntity(next) != null) {
                return Optional.empty();
            }
            top = next;
        }
        if (WorldModificationPermission.check(level, id, root.above(MAX_TRUNK_HEIGHT))
                != WorldModificationPermission.Decision.ALLOWED
                || level.getBlockState(root.above(MAX_TRUNK_HEIGHT)).is(Blocks.OAK_LOG)
                || level.getBlockEntity(root) != null) {
            return Optional.empty();
        }
        return Optional.of(top);
    }

    /** A mark needs a short vertical trunk and a natural-looking oak canopy. */
    public static boolean mayMarkTree(ServerLevel level, String id, BlockPos root) {
        Optional<BlockPos> top = nextLog(level, id, root);
        if (top.isEmpty() || top.get().equals(root)) {
            return false;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos leaf = top.get().offset(dx, dy, dz);
                    if (WorldModificationPermission.check(level, id, leaf)
                            == WorldModificationPermission.Decision.ALLOWED
                            && level.getBlockState(leaf).is(Blocks.OAK_LEAVES)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static boolean hasItem(Container container, net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSoil(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.PODZOL) || state.is(Blocks.COARSE_DIRT);
    }

    private static Optional<BlockPos> nearbyTable(ServerLevel level, String id, BlockPos warehouse) {
        for (BlockPos candidate : BlockPos.betweenClosed(
                warehouse.offset(-3, -1, -3), warehouse.offset(3, 1, 3))) {
            if (WorldModificationPermission.check(level, id, candidate)
                    == WorldModificationPermission.Decision.ALLOWED
                    && level.getBlockState(candidate).is(Blocks.CRAFTING_TABLE)) {
                return Optional.of(candidate.immutable());
            }
        }
        return Optional.empty();
    }

    private static Optional<GoblinCitizenEntity> nearestWorker(
            ServerLevel level, SettlementSavedData data, String id, BlockPos target) {
        return level.getEntitiesOfClass(GoblinCitizenEntity.class,
                        new AABB(target).inflate(16.0),
                        goblin -> goblin.isAvailableForConstruction()
                                && WorldModificationPermission.check(level, id, goblin.blockPosition())
                                == WorldModificationPermission.Decision.ALLOWED)
                .stream().min(Comparator.comparingDouble(goblin -> goblin.blockPosition().distSqr(target)));
    }
}
