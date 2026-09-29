package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.GoblinSettlement;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** The mod's blocks and their items. The first ones; nothing here existed before the noticeboard. */
public final class ModBlocks {
    private static final ResourceKey<Block> NOTICEBOARD_KEY = ResourceKey.create(Registries.BLOCK,
            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "noticeboard"));

    public static final NoticeboardBlock NOTICEBOARD = Registry.register(BuiltInRegistries.BLOCK,
            NOTICEBOARD_KEY,
            new NoticeboardBlock(BlockBehaviour.Properties.of()
                    .noOcclusion()
                    .strength(1.0F)
                    .sound(SoundType.WOOD)
                    .setId(NOTICEBOARD_KEY)));

    public static final BlockItem NOTICEBOARD_ITEM = Registry.register(BuiltInRegistries.ITEM,
            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "noticeboard"),
            new BlockItem(NOTICEBOARD, new Item.Properties()
                    .useBlockDescriptionPrefix()
                    .setId(ResourceKey.create(Registries.ITEM,
                            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "noticeboard")))));

    private ModBlocks() {
    }

    /**
     * The vanilla tab's key is private in this version, so it is rebuilt here exactly the way
     * CreativeModeTabs builds it (verified by decompiling its own createKey).
     */
    private static final ResourceKey<CreativeModeTab> FUNCTIONAL_BLOCKS =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    Identifier.withDefaultNamespace("functional_blocks"));

    public static void initialize() {
        ItemGroupEvents.modifyEntriesEvent(FUNCTIONAL_BLOCKS)
                .register(entries -> entries.accept(NOTICEBOARD_ITEM));
    }
}
