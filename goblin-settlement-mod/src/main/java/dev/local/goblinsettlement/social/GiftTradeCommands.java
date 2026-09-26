package dev.local.goblinsettlement.social;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Player-facing transfers at a nearby registered public chest. Call register during command setup. */
public final class GiftTradeCommands {
    private static final double MAX_CHEST_DISTANCE_SQUARED = 36.0;

    private GiftTradeCommands() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("goblinsettlement")
                .then(Commands.literal("gift").executes(context -> gift(context.getSource())))
                .then(Commands.literal("trade")
                        .then(Commands.literal("planks")
                                .executes(context -> trade(context.getSource(), Items.OAK_PLANKS, 4, 16)))
                        .then(Commands.literal("bread")
                                .executes(context -> trade(context.getSource(), Items.BREAD, 2, 4)))));
    }

    private static int gift(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only a player can make a gift"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        var data = SettlementSavedData.get(level);
        if (data.settlement().isEmpty()) {
            source.sendFailure(Component.literal("No settlement in this dimension"));
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) {
            source.sendFailure(Component.literal("Hold the item to give in your main hand"));
            return 0;
        }
        var chest = nearbyChest(level, data, player);
        if (chest == null) {
            source.sendFailure(Component.literal("Stand near an active registered public chest"));
            return 0;
        }
        int slot = depositSlot(chest, held, -1);
        if (slot < 0) {
            source.sendFailure(Component.literal("The public chest has no room for that item"));
            return 0;
        }
        ItemStack one = held.copyWithCount(1);
        ItemStack stored = chest.getItem(slot);
        if (stored.isEmpty()) chest.setItem(slot, one);
        else stored.grow(1);
        chest.setChanged();
        held.shrink(1);
        String id = data.settlement().orElseThrow().id();
        var relations = SettlementRelations.get(level, id);
        if (one.is(Items.EMERALD) && relations.trust(player.getUUID().toString()) < 0) {
            relations.compensate(player.getUUID().toString());
        }
        source.sendSuccess(() -> Component.literal("One item given to the settlement"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int trade(CommandSourceStack source, Item product, int quantity, int reserve) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only a player can trade"));
            return 0;
        }
        ServerLevel level = source.getLevel();
        var data = SettlementSavedData.get(level);
        if (data.settlement().isEmpty()) {
            source.sendFailure(Component.literal("No settlement in this dimension"));
            return 0;
        }
        String id = data.settlement().orElseThrow().id();
        var relations = SettlementRelations.get(level, id);
        String playerId = player.getUUID().toString();
        int trust = relations.trust(playerId);
        if (trust <= -20 || (trust < 0 && relations.offenses(playerId) >= 2)) {
            source.sendFailure(Component.literal("Trading is suspended until trust is restored"));
            return 0;
        }
        if (!player.getMainHandItem().is(Items.EMERALD) || !player.getOffhandItem().isEmpty()) {
            source.sendFailure(Component.literal("Hold one emerald in your main hand and empty your offhand"));
            return 0;
        }
        var stock = PublicWarehouseInventory.snapshot(level, data);
        if (!stock.complete() || (product == Items.OAK_PLANKS && stock.oakPlanks() < reserve + quantity)) {
            source.sendFailure(Component.literal("Public stores are unavailable or needed for the settlement"));
            return 0;
        }
        Container chest = nearbyChest(level, data, player);
        if (chest == null) {
            source.sendFailure(Component.literal("Stand near an active registered public chest"));
            return 0;
        }
        int goodsSlot = -1;
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(product)) {
                total += stack.getCount();
                if (stack.getCount() >= quantity && goodsSlot < 0) goodsSlot = slot;
            }
        }
        if (goodsSlot < 0 || total < reserve + quantity) {
            source.sendFailure(Component.literal("That chest has no surplus for this trade"));
            return 0;
        }
        ItemStack emerald = player.getMainHandItem().copyWithCount(1);
        int paymentSlot = depositSlot(chest, emerald, goodsSlot);
        if (paymentSlot < 0 && chest.getItem(goodsSlot).getCount() == quantity
                && chest.canPlaceItem(goodsSlot, emerald)) paymentSlot = goodsSlot;
        if (paymentSlot < 0) {
            source.sendFailure(Component.literal("That chest has no room for payment"));
            return 0;
        }
        // All conditions are checked before the paired inventory writes on the server thread.
        ItemStack goods = chest.removeItem(goodsSlot, quantity);
        ItemStack storedPayment = chest.getItem(paymentSlot);
        if (storedPayment.isEmpty()) chest.setItem(paymentSlot, emerald);
        else storedPayment.grow(1);
        chest.setChanged();
        player.getMainHandItem().shrink(1);
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, goods);
        source.sendSuccess(() -> Component.literal("Trade complete: " + quantity + " "
                + Component.translatable(product.getDescriptionId()).getString()
                + " for one emerald"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static Container nearbyChest(ServerLevel level, SettlementSavedData data, ServerPlayer player) {
        String id = data.settlement().orElseThrow().id();
        for (BlockPos pos : data.warehouses()) {
            if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)
                    > MAX_CHEST_DISTANCE_SQUARED
                    || WorldModificationPermission.check(level, id, pos)
                    != WorldModificationPermission.Decision.ALLOWED) continue;
            if (level.getBlockEntity(pos) instanceof Container container) return container;
        }
        return null;
    }

    private static int depositSlot(Container chest, ItemStack item, int excluded) {
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (slot == excluded || !chest.canPlaceItem(slot, item)) continue;
            ItemStack stored = chest.getItem(slot);
            if (!stored.isEmpty() && ItemStack.isSameItemSameComponents(stored, item)
                    && stored.getCount() < Math.min(stored.getMaxStackSize(), chest.getMaxStackSize(stored))) return slot;
        }
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            if (slot != excluded && chest.getItem(slot).isEmpty() && chest.canPlaceItem(slot, item)) return slot;
        }
        return -1;
    }
}
