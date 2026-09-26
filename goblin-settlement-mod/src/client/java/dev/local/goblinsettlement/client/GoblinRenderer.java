package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;

public final class GoblinRenderer extends MobRenderer<GoblinCitizenEntity, GoblinRenderState, GoblinModel> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
            "goblin_settlement", "textures/entity/goblin.png");

    public GoblinRenderer(EntityRendererProvider.Context context) {
        super(context, new GoblinModel(context.bakeLayer(GoblinSettlementClient.GOBLIN_LAYER)), 0.3F);
    }

    @Override
    public GoblinRenderState createRenderState() {
        return new GoblinRenderState();
    }

    @Override
    public Identifier getTextureLocation(GoblinRenderState state) {
        return TEXTURE;
    }
}
