package dev.local.goblinsettlement.defense;

import net.minecraft.world.item.Item;

/** A positive quantity of a real item required by a golem operation. */
public record GolemMaterialCost(Item item, int count) {
    public GolemMaterialCost {
        if (item == null || count <= 0) {
            throw new IllegalArgumentException("A golem material needs an item and positive count");
        }
    }
}
