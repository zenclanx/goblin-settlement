package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.Profession;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;

public final class GoblinRenderer extends MobRenderer<GoblinCitizenEntity, GoblinRenderState, GoblinModel> {
    private static final Identifier DEFAULT_TEXTURE = texture("goblin");

    public GoblinRenderer(EntityRendererProvider.Context context) {
        super(context, new GoblinModel(context.bakeLayer(GoblinSettlementClient.GOBLIN_LAYER)), 0.3F);
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath("goblin_settlement", "textures/entity/" + name + ".png");
    }

    @Override
    public GoblinRenderState createRenderState() {
        return new GoblinRenderState();
    }

    @Override
    public void extractRenderState(GoblinCitizenEntity entity, GoblinRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.profession = entity.professionForRender();
    }

    @Override
    public Identifier getTextureLocation(GoblinRenderState state) {
        return switch (state.profession) {
            case FARMER -> texture("goblin_farmer");
            case FORESTER -> texture("goblin_forester");
            case MINER -> texture("goblin_miner");
            case BUILDER -> texture("goblin_builder");
            case HAULER -> texture("goblin_hauler");
            case ARTISAN -> texture("goblin_artisan");
            case SENTRY -> texture("goblin_sentry");
            case UNASSIGNED -> DEFAULT_TEXTURE;
        };
    }
}
