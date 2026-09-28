package dev.local.goblinsettlement.construction;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * What a build can be made of. One authority: the road and bridge line and the small construction line
 * both ask here, so neither can hold a second opinion about which block an oak plank is.
 */
public enum BuildMaterial {
    OAK_PLANKS(Blocks.OAK_PLANKS, Items.OAK_PLANKS),
    OAK_LOG(Blocks.OAK_LOG, Items.OAK_LOG),
    OAK_FENCE(Blocks.OAK_FENCE, Items.OAK_FENCE),
    TORCH(Blocks.TORCH, Items.TORCH),
    COBBLESTONE(Blocks.COBBLESTONE, Items.COBBLESTONE);

    private final Block block;
    private final Item item;

    BuildMaterial(Block block, Item item) {
        this.block = block;
        this.item = item;
    }

    /** The block a resident places for this material. */
    public Block block() {
        return block;
    }

    /** The item a resident withdraws from a warehouse for this material. */
    public Item item() {
        return item;
    }
}
