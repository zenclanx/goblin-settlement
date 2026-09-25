package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.GoblinSettlement;
import dev.local.goblinsettlement.citizen.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.resources.Identifier;

public final class GoblinSettlementClient implements ClientModInitializer {
    public static final ModelLayerLocation GOBLIN_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "goblin"), "main");

    @Override
    public void onInitializeClient() {
        EntityModelLayerRegistry.registerModelLayer(GOBLIN_LAYER, GoblinModel::createLayer);
        EntityRenderers.register(ModEntities.GOBLIN, GoblinRenderer::new);
    }
}
