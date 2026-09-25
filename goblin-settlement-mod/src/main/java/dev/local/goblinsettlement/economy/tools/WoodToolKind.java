package dev.local.goblinsettlement.economy.tools;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** The three early tools made from oak planks and sticks at a crafting table. */
public enum WoodToolKind {
    HOE(Items.WOODEN_HOE, 2),
    AXE(Items.WOODEN_AXE, 3),
    PICKAXE(Items.WOODEN_PICKAXE, 3);

    private final Item item;
    private final int planks;

    WoodToolKind(Item item, int planks) {
        this.item = item;
        this.planks = planks;
    }

    public Item item() {
        return item;
    }

    public int planks() {
        return planks;
    }
}
