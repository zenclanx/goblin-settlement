package dev.local.goblinsettlement.mining;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Small, exposed mining sites. A resident task must call extraction while physically present. */
public final class MiningWorksite {
    private static final int SURVEY_RADIUS = 5;
    private static final int MAX_SURVEYED_BLOCKS = 6 * 11 * 11;
    private static final int MAX_STORED_COMMON = 32;
    private static final int MAX_STORED_RARE = 8;

    public enum Deposit {
        STONE(Items.WOODEN_PICKAXE, Items.COBBLESTONE, false),
        COAL(Items.WOODEN_PICKAXE, Items.COAL, false),
        COPPER(Items.STONE_PICKAXE, Items.RAW_COPPER, false),
        IRON(Items.STONE_PICKAXE, Items.RAW_IRON, false),
        GOLD(Items.IRON_PICKAXE, Items.RAW_GOLD, true),
        DIAMOND(Items.IRON_PICKAXE, Items.DIAMOND, true),
        OBSIDIAN(Items.DIAMOND_PICKAXE, Items.OBSIDIAN, true);

        private final Item minimumTool;
        private final Item drop;
        private final boolean rare;

        Deposit(Item minimumTool, Item drop, boolean rare) {
            this.minimumTool = minimumTool;
            this.drop = drop;
            this.rare = rare;
        }

        public Item drop() {
            return drop;
        }
    }

    public record Site(BlockPos block, BlockPos stand, Deposit deposit) {
    }

    private MiningWorksite() {
    }

    /** Bounded survey near one real public warehouse; never loads chunks or opens tunnels. */
    public static Optional<Site> survey(ServerLevel level, String settlementId, BlockPos warehouse) {
        if (level == null || settlementId == null || warehouse == null
                || !registeredWarehouse(level, settlementId, warehouse).isPresent()) {
            return Optional.empty();
        }
        int examined = 0;
        for (BlockPos cursor : BlockPos.betweenClosed(warehouse.offset(-SURVEY_RADIUS, -2, -SURVEY_RADIUS),
                warehouse.offset(SURVEY_RADIUS, 3, SURVEY_RADIUS))) {
            if (++examined > MAX_SURVEYED_BLOCKS) {
                break;
            }
            BlockPos pos = cursor.immutable();
            Optional<Site> site = assess(level, settlementId, pos);
            if (site.isPresent() && countStored(level, settlementId, site.get().deposit().drop())
                    < (site.get().deposit().rare ? MAX_STORED_RARE : MAX_STORED_COMMON)
                    && hasUsablePickaxe(level, settlementId, warehouse, site.get().deposit())) {
                return site;
            }
        }
        return Optional.empty();
    }

    /** Revalidate a site immediately before assigning work or touching a block. */
    public static Optional<Site> assess(ServerLevel level, String settlementId, BlockPos block) {
        if (level == null || settlementId == null || block == null || !allowed(level, settlementId, block)
                || level.getBlockEntity(block) != null) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(block);
        Deposit deposit = deposit(state);
        if (deposit == null || !safeNeighbors(level, settlementId, block)) {
            return Optional.empty();
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos stand = block.relative(direction);
            if (allowed(level, settlementId, stand) && allowed(level, settlementId, stand.above())
                    && allowed(level, settlementId, stand.below())
                    && level.getBlockState(stand).isAir() && level.getBlockState(stand.above()).isAir()
                    && level.getBlockState(stand.below()).isFaceSturdy(level, stand.below(), Direction.UP)) {
                return Optional.of(new Site(block.immutable(), stand.immutable(), deposit));
            }
        }
        return Optional.empty();
    }

    /** Mine one real block and place its vanilla drops into the nearby real public container. */
    public static boolean extract(ServerLevel level, GoblinCitizenEntity worker, String settlementId,
            Site expected, BlockPos warehouse) {
        if (level == null || worker == null || expected == null || warehouse == null
                || worker.level() != level || !worker.isAlive()
                || !level.getGameRules().get(GameRules.MOB_GRIEFING)
                || worker.distanceToSqr(expected.stand().getX() + 0.5, expected.stand().getY(),
                        expected.stand().getZ() + 0.5) > 4.0
                || expected.block().distSqr(warehouse) > 36.0) {
            return false;
        }
        Optional<Site> actual = assess(level, settlementId, expected.block());
        Optional<Container> storage = registeredWarehouse(level, settlementId, warehouse);
        if (actual.isEmpty() || actual.get().deposit() != expected.deposit() || storage.isEmpty()
                || countStored(level, settlementId, actual.get().deposit().drop())
                        >= (actual.get().deposit().rare ? MAX_STORED_RARE : MAX_STORED_COMMON)) {
            return false;
        }
        Container container = storage.get();
        int toolSlot = findUsablePickaxe(container, actual.get().deposit());
        if (toolSlot < 0) {
            return false;
        }
        ItemStack tool = container.getItem(toolSlot);
        List<ItemStack> drops = Block.getDrops(level.getBlockState(expected.block()), level,
                expected.block(), null, worker, tool);
        if (drops.isEmpty() || !canFit(container, drops)) {
            return false;
        }
        if (!level.setBlock(expected.block(), Blocks.AIR.defaultBlockState(), 3)) {
            return false;
        }
        for (ItemStack drop : drops) {
            insert(container, drop.copy());
        }
        tool.setDamageValue(tool.getDamageValue() + 1);
        if (tool.getDamageValue() >= tool.getMaxDamage()) {
            tool.shrink(1);
        }
        container.setChanged();
        return true;
    }

    private static Deposit deposit(BlockState state) {
        if (state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)) return Deposit.STONE;
        if (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)) return Deposit.COAL;
        if (state.is(Blocks.COPPER_ORE) || state.is(Blocks.DEEPSLATE_COPPER_ORE)) return Deposit.COPPER;
        if (state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)) return Deposit.IRON;
        if (state.is(Blocks.GOLD_ORE) || state.is(Blocks.DEEPSLATE_GOLD_ORE)) return Deposit.GOLD;
        if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) return Deposit.DIAMOND;
        if (state.is(Blocks.OBSIDIAN)) return Deposit.OBSIDIAN;
        return null;
    }

    private static boolean safeNeighbors(ServerLevel level, String id, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = pos.relative(direction);
            if (!allowed(level, id, neighbor)) return false;
            BlockState state = level.getBlockState(neighbor);
            if (!state.getFluidState().isEmpty() || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND)) {
                return false;
            }
        }
        return true;
    }

    /** True when the registered warehouse holds a pickaxe that can still mine this deposit. */
    public static boolean hasUsablePickaxe(ServerLevel level, String settlementId, BlockPos warehouse,
                                           Deposit deposit) {
        Optional<Container> storage = registeredWarehouse(level, settlementId, warehouse);
        return storage.isPresent() && findUsablePickaxe(storage.get(), deposit) >= 0;
    }

    private static int findUsablePickaxe(Container container, Deposit deposit) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty() || stack.getMaxDamage() == 0 || stack.getDamageValue() + 1 >= stack.getMaxDamage()
                    && stack.is(Items.DIAMOND_PICKAXE)) {
                continue; // Retain the last usable diamond pickaxe for obsidian access.
            }
            if (pickaxeRank(stack) >= pickaxeRank(new ItemStack(deposit.minimumTool))) {
                return slot;
            }
        }
        return -1;
    }

    private static int pickaxeRank(ItemStack stack) {
        if (stack.is(Items.DIAMOND_PICKAXE) || stack.is(Items.NETHERITE_PICKAXE)) return 4;
        if (stack.is(Items.IRON_PICKAXE)) return 3;
        if (stack.is(Items.STONE_PICKAXE)) return 2;
        if (stack.is(Items.WOODEN_PICKAXE) || stack.is(Items.GOLDEN_PICKAXE)) return 1;
        return 0;
    }

    private static boolean canFit(Container container, List<ItemStack> incoming) {
        List<ItemStack> simulated = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) simulated.add(container.getItem(slot).copy());
        for (ItemStack stack : incoming) {
            ItemStack remainder = stack.copy();
            for (int slot = 0; slot < simulated.size() && !remainder.isEmpty(); slot++) {
                if (!container.canPlaceItem(slot, remainder)) continue;
                ItemStack held = simulated.get(slot);
                if (!held.isEmpty() && !ItemStack.isSameItemSameComponents(held, remainder)) continue;
                int capacity = Math.min(remainder.getMaxStackSize(), container.getMaxStackSize(remainder));
                int move = Math.min(remainder.getCount(), capacity - held.getCount());
                if (move <= 0) continue;
                if (held.isEmpty()) simulated.set(slot, remainder.copyWithCount(move));
                else held.grow(move);
                remainder.shrink(move);
            }
            if (!remainder.isEmpty()) return false;
        }
        return true;
    }

    private static void insert(Container container, ItemStack stack) {
        for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
            if (!container.canPlaceItem(slot, stack)) continue;
            ItemStack held = container.getItem(slot);
            if (!held.isEmpty() && !ItemStack.isSameItemSameComponents(held, stack)) continue;
            int capacity = Math.min(stack.getMaxStackSize(), container.getMaxStackSize(stack));
            int move = Math.min(stack.getCount(), capacity - held.getCount());
            if (move <= 0) continue;
            if (held.isEmpty()) container.setItem(slot, stack.copyWithCount(move));
            else held.grow(move);
            stack.shrink(move);
        }
    }

    private static int countStored(ServerLevel level, String id, Item item) {
        int count = 0;
        for (BlockPos pos : SettlementSavedData.get(level).warehouses()) {
            Optional<Container> warehouse = registeredWarehouse(level, id, pos);
            if (warehouse.isEmpty()) continue;
            for (int slot = 0; slot < warehouse.get().getContainerSize(); slot++) {
                ItemStack stack = warehouse.get().getItem(slot);
                if (stack.is(item)) count += stack.getCount();
            }
        }
        return count;
    }

    private static Optional<Container> registeredWarehouse(ServerLevel level, String id, BlockPos pos) {
        if (!SettlementSavedData.get(level).warehouses().contains(pos) || !allowed(level, id, pos)
                || !(level.getBlockEntity(pos) instanceof Container container)) return Optional.empty();
        return Optional.of(container);
    }

    private static boolean allowed(ServerLevel level, String id, BlockPos pos) {
        return WorldModificationPermission.check(level, id, pos)
                == WorldModificationPermission.Decision.ALLOWED;
    }
}
