package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.construction.BuildMaterial;
import dev.local.goblinsettlement.planning.bridge.BridgePlanner;
import java.util.Arrays;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class BridgeMaterialsCheck {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        checkWoodBridgeMaterials();
        checkStoneBridgeMaterials();
        checkKindNamesAreStable();
        checkTheTwoListsDifferOnlyWhereTheyShould();
        checkKindsAreGuarded();
        checkOnlyOnePlaceAnswersWhatABridgeIs();
        checkSpanLadderMatchesTheDesign();
        System.out.println("BridgeMaterialsCheck passed");
    }

    private static void checkWoodBridgeMaterials() {
        var kind = TransportPlan.Kind.WOOD_BRIDGE;
        require(BridgeMaterials.deck(kind) == BuildMaterial.OAK_PLANKS, "a wooden deck is planks");
        require(BridgeMaterials.support(kind) == BuildMaterial.OAK_LOG, "wooden supports are logs");
        require(BridgeMaterials.railing(kind) == BuildMaterial.OAK_FENCE, "wooden railings are fences");
        require(BridgeMaterials.lighting(kind) == BuildMaterial.TORCH, "lighting is torches");
        require(BridgeMaterials.barrier(kind) == BuildMaterial.OAK_FENCE,
                "the temporary construction barriers are fences");
    }

    private static void checkStoneBridgeMaterials() {
        var kind = TransportPlan.Kind.STONE_BRIDGE;
        require(BridgeMaterials.deck(kind) == BuildMaterial.COBBLESTONE, "a stone deck is cobblestone");
        require(BridgeMaterials.support(kind) == BuildMaterial.COBBLESTONE, "stone supports are cobblestone");
        require(BridgeMaterials.railing(kind) == BuildMaterial.OAK_FENCE,
                "a stone bridge still uses wooden railings: the economy produces no stone railing");
        require(BridgeMaterials.lighting(kind) == BuildMaterial.TORCH, "lighting is still torches");
        require(BridgeMaterials.barrier(kind) == BuildMaterial.OAK_FENCE,
                "a stone bridge still raises fence barriers: three places hardcode OAK_FENCE");
    }

    /**
     * These names are what saves already hold. Renaming a kind would silently change what every
     * finished bridge and pending project is, and a new kind would answer half the questions below
     * with the wooden default until its row is added, so both are pinned here.
     */
    private static void checkKindNamesAreStable() {
        List<String> names = Arrays.stream(TransportPlan.Kind.values()).map(Enum::name).toList();
        require(names.equals(List.of("ROAD", "WOOD_BRIDGE", "STONE_BRIDGE")),
                "the stored kind names are the ones already in saves");
    }

    /** The two kinds differ in exactly one thing: what the deck and the supports are made of. */
    private static void checkTheTwoListsDifferOnlyWhereTheyShould() {
        var wood = BridgeMaterials.required(TransportPlan.Kind.WOOD_BRIDGE);
        var stone = BridgeMaterials.required(TransportPlan.Kind.STONE_BRIDGE);
        require(wood.containsKey(BuildMaterial.OAK_PLANKS) && wood.containsKey(BuildMaterial.OAK_LOG),
                "the wooden kind needs planks and logs");
        require(!stone.containsKey(BuildMaterial.OAK_PLANKS) && !stone.containsKey(BuildMaterial.OAK_LOG),
                "the stone kind uses no wood for its deck or its supports");
        require(stone.containsKey(BuildMaterial.COBBLESTONE), "the stone kind needs cobblestone");
        for (BuildMaterial shared : List.of(BuildMaterial.OAK_FENCE, BuildMaterial.TORCH)) {
            require(wood.containsKey(shared) && stone.containsKey(shared),
                    shared + " is needed by both kinds");
        }
        for (int floor : wood.values()) {
            require(floor > 0, "every material has a positive start floor");
        }
        for (int floor : stone.values()) {
            require(floor > 0, "every material has a positive start floor");
        }
    }

    private static void checkKindsAreGuarded() {
        require(TransportPlan.Kind.WOOD_BRIDGE.isBridge(), "a wooden bridge is a bridge");
        require(TransportPlan.Kind.STONE_BRIDGE.isBridge(), "a stone bridge is a bridge");
        require(!TransportPlan.Kind.ROAD.isBridge(), "a road is not a bridge");
        require(List.of(TransportPlan.Kind.values()).contains(TransportPlan.Kind.STONE_BRIDGE),
                "the stone kind is persisted under this name");
    }

    private static void checkOnlyOnePlaceAnswersWhatABridgeIs() {
        require(BridgeMaterials.minSpan(TransportPlan.Kind.WOOD_BRIDGE) == BridgePlanner.MIN_WOOD_SPAN,
                "the wooden kind starts at the wooden rung");
        require(BridgeMaterials.maxSpan(TransportPlan.Kind.WOOD_BRIDGE) == BridgePlanner.MAX_WOOD_SPAN,
                "and ends at it");
        require(BridgeMaterials.minSpan(TransportPlan.Kind.STONE_BRIDGE) == BridgePlanner.MIN_STONE_SPAN,
                "the stone kind starts at the stone rung");
        require(BridgeMaterials.maxSpan(TransportPlan.Kind.STONE_BRIDGE) == BridgePlanner.MAX_STONE_SPAN,
                "and ends at it");
    }

    private static void checkSpanLadderMatchesTheDesign() {
        require(BridgePlanner.MIN_WOOD_SPAN == 4 && BridgePlanner.MAX_WOOD_SPAN == 12,
                "the wooden span is the documented 4 to 12");
        require(BridgePlanner.MIN_STONE_SPAN == 13 && BridgePlanner.MAX_STONE_SPAN == 24,
                "the stone span picks up at 13 and reaches the documented 24");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
