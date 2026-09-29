package dev.local.goblinsettlement.economy;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Shared stack-into-container placement honouring canPlaceItem, component matching and stack caps. */
public final class ContainerStorage {
    private ContainerStorage() {
    }

    /**
     * Places as much of {@code stack} as fits, shrinking it by the amount moved. Callers mark the
     * container changed; a stack left non-empty did not fit anywhere.
     */
    public static void insert(Container container, ItemStack stack) {
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
}
