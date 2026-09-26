package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.GoblinSettlement;
import dev.local.goblinsettlement.client.GoblinModel;
import dev.local.goblinsettlement.client.GoblinRenderState;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;

/** Shared golem silhouette; the six tiers currently use one original texture. */
public final class GolemRenderer extends MobRenderer<GoblinGolemEntity, GoblinRenderState, GoblinModel> {
    private static final ModelLayerLocation GOLEM_LAYER = new ModelLayerLocation(
            Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, "goblin_golem"), "main");
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            "goblin_settlement", "textures/entity/goblin_golem.png");

    public GolemRenderer(EntityRendererProvider.Context context) {
        super(context, new GoblinModel(context.bakeLayer(GOLEM_LAYER)), 0.55F);
    }

    @Override
    public GoblinRenderState createRenderState() {
        return new GoblinRenderState();
    }

    @Override
    public Identifier getTextureLocation(GoblinRenderState state) {
        return TEXTURE;
    }

    /** Call once from the existing client initializer. */
    public static void initializeClient() {
        EntityModelLayerRegistry.registerModelLayer(GOLEM_LAYER, GoblinModel::createLayer);
        EntityRenderers.register(GolemEntities.GOLEM, GolemRenderer::new);
    }
}
