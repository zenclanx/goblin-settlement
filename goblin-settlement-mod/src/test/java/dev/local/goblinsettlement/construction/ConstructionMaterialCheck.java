package dev.local.goblinsettlement.construction;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

public final class ConstructionMaterialCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkEveryMaterialIsBuildable();
        checkStoredNamesAreStable();
        checkPlanRoundTrip();
        checkOldPlanDefaultsToOak();
        checkEachMaterialMapsToItsOwnBlockAndItem();
        System.out.println("ConstructionMaterialCheck passed");
    }

    /** A material with no block or no item could never be fetched or placed. */
    private static void checkEveryMaterialIsBuildable() {
        for (BuildMaterial material : BuildMaterial.values()) {
            require(material.block() != null, material + " names a block");
            require(material.item() != null, material + " names an item");
        }
    }

    /**
     * These names are what saves already hold. Renaming a constant would silently change what every
     * finished road, bridge and pending project is made of, so they are pinned here.
     */
    private static void checkStoredNamesAreStable() {
        List<String> names = Arrays.stream(BuildMaterial.values()).map(Enum::name).toList();
        require(names.equals(List.of("OAK_PLANKS", "OAK_LOG", "OAK_FENCE", "TORCH")),
                "the stored material names are the ones already in saves");
    }

    private static void checkPlanRoundTrip() {
        var plan = new ConstructionPlan(new BlockPos(10, 64, 10), 1, Optional.of("worker"),
                Optional.empty(), Optional.of("last"), BuildMaterial.OAK_LOG);
        var json = ConstructionPlan.CODEC.encodeStart(JsonOps.INSTANCE, plan).getOrThrow();
        var reloaded = ConstructionPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        require(reloaded.material() == BuildMaterial.OAK_LOG, "a plan keeps the material it was made of");
        require(reloaded.equals(plan), "and survives the round trip whole");
    }

    private static void checkOldPlanDefaultsToOak() {
        var plan = new ConstructionPlan(new BlockPos(0, 70, 0), 0, Optional.empty(),
                Optional.empty(), Optional.empty(), BuildMaterial.OAK_PLANKS);
        var json = ConstructionPlan.CODEC.encodeStart(JsonOps.INSTANCE, plan).getOrThrow()
                .getAsJsonObject().deepCopy();
        json.remove("material");
        require(ConstructionPlan.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow().material()
                        == BuildMaterial.OAK_PLANKS,
                "a project written before this round still builds oak planks");
    }

    /** Each material must keep naming its own block and item, not merely some block and some item. */
    private static void checkEachMaterialMapsToItsOwnBlockAndItem() {
        require(BuildMaterial.OAK_PLANKS.block() == Blocks.OAK_PLANKS, "oak planks build oak planks");
        require(BuildMaterial.OAK_LOG.block() == Blocks.OAK_LOG, "oak logs build oak logs");
        require(BuildMaterial.OAK_FENCE.block() == Blocks.OAK_FENCE, "oak fences build oak fences");
        require(BuildMaterial.TORCH.block() == Blocks.TORCH, "torches build torches");
        require(BuildMaterial.OAK_PLANKS.item() == Items.OAK_PLANKS, "oak planks are fetched as oak planks");
        require(BuildMaterial.OAK_LOG.item() == Items.OAK_LOG, "oak logs are fetched as oak logs");
        require(BuildMaterial.OAK_FENCE.item() == Items.OAK_FENCE, "oak fences are fetched as oak fences");
        require(BuildMaterial.TORCH.item() == Items.TORCH, "torches are fetched as torches");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
