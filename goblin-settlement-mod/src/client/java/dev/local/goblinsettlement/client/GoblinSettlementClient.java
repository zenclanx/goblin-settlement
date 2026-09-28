package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.GoblinSettlement;
import dev.local.goblinsettlement.citizen.ModEntities;
import dev.local.goblinsettlement.client.model.GoblinFemaleModel;
import dev.local.goblinsettlement.client.model.GoblinMaleModel;
import dev.local.goblinsettlement.defense.GolemRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.resources.Identifier;

public final class GoblinSettlementClient implements ClientModInitializer {
    public static final ModelLayerLocation GOBLIN_MALE_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "goblin_male"), "main");
    public static final ModelLayerLocation GOBLIN_FEMALE_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "goblin_female"), "main");

    @Override
    public void onInitializeClient() {
        EntityModelLayerRegistry.registerModelLayer(GOBLIN_MALE_LAYER, GoblinMaleModel::createLayer);
        EntityModelLayerRegistry.registerModelLayer(GOBLIN_FEMALE_LAYER, GoblinFemaleModel::createLayer);
        EntityRenderers.register(ModEntities.GOBLIN, GoblinRenderer::new);
        GolemRenderer.initializeClient();
    }
}
