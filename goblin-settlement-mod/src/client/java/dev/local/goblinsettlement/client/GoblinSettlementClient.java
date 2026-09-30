package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.GoblinSettlement;
import dev.local.goblinsettlement.citizen.ModEntities;
import dev.local.goblinsettlement.client.model.GoblinBodies;
import dev.local.goblinsettlement.client.noticeboard.NoticeboardScreen;
import dev.local.goblinsettlement.client.sound.GolemCoreSounds;
import dev.local.goblinsettlement.defense.GolemRenderer;
import dev.local.goblinsettlement.noticeboard.NoticeboardPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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
        // The cores are one looping client-side sound per loaded custom golem: the tick hook is what
        // starts them, moves them with their golem and lets go of the ones whose golem is gone.
        ClientTickEvents.END_CLIENT_TICK.register(client -> GolemCoreSounds.tick());
        // The snapshot is pushed, never requested: opening the screen is all the client does, and it
        // has to happen on the client's own thread.
        ClientPlayNetworking.registerGlobalReceiver(NoticeboardPayload.TYPE,
                (payload, context) -> context.client().execute(
                        () -> context.client().setScreen(new NoticeboardScreen(payload.report()))));
    }

    /** The layer a body is baked under. The crop id is the geometry's identity, so it names the layer. */
    public static ModelLayerLocation layer(String crop) {
        return new ModelLayerLocation(
                Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, crop), "main");
    }
}
