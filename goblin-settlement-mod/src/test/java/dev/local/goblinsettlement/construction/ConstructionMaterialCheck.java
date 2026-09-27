package dev.local.goblinsettlement.construction;

import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class ConstructionMaterialCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkEveryMaterialIsBuildable();
        checkStoredNamesAreStable();
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

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
