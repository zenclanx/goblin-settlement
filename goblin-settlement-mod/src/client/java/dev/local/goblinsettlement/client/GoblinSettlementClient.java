package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.GoblinSettlement;
import dev.local.goblinsettlement.citizen.ModEntities;
import dev.local.goblinsettlement.client.model.GoblinBodies;
import dev.local.goblinsettlement.defense.GolemRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.resources.Identifier;

public final class GoblinSettlementClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        for (GoblinBodies.Body body : GoblinBodies.BODIES) {
            EntityModelLayerRegistry.registerModelLayer(layer(body.crop()), body.layer()::get);
        }
        EntityRenderers.register(ModEntities.GOBLIN, GoblinRenderer::new);
        GolemRenderer.initializeClient();
    }

    /** The layer a body is baked under. The crop id is the geometry's identity, so it names the layer. */
    public static ModelLayerLocation layer(String crop) {
        return new ModelLayerLocation(
                Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, crop), "main");
    }
}
