package dev.local.goblinsettlement.defense;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Atomic within one server-thread call; no shadow inventory or fabricated materials. */
final class GolemMaterials {
    private GolemMaterials() {
    }

    static Optional<Snapshot> consume(Container container, List<GolemMaterialCost> costs) {
        if (container == null || costs == null) {
            return Optional.empty();
        }
        Map<Item, Integer> required = new HashMap<>();
        for (GolemMaterialCost cost : costs) {
            required.merge(cost.item(), cost.count(), Math::addExact);
        }
        for (var entry : required.entrySet()) {
            int available = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(entry.getKey())) {
                    available = Math.addExact(available, stack.getCount());
                }
            }
            if (available < entry.getValue()) {
                return Optional.empty();
            }
        }
        var original = new ArrayList<ItemStack>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            original.add(container.getItem(slot).copy());
        }
        Snapshot snapshot = new Snapshot(container, List.copyOf(original));
        for (var entry : required.entrySet()) {
            int remaining = entry.getValue();
            for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.is(entry.getKey())) {
                    continue;
                }
                int take = Math.min(stack.getCount(), remaining);
                ItemStack removed = container.removeItem(slot, take);
                if (!removed.is(entry.getKey()) || removed.getCount() != take) {
                    snapshot.restore();
                    return Optional.empty();
                }
                remaining -= take;
            }
            if (remaining != 0) {
                snapshot.restore();
                return Optional.empty();
            }
        }
        container.setChanged();
        return Optional.of(snapshot);
    }

    record Snapshot(Container container, List<ItemStack> contents) {
        void restore() {
            for (int slot = 0; slot < contents.size(); slot++) {
                container.setItem(slot, contents.get(slot).copy());
            }
            container.setChanged();
        }
    }
}
