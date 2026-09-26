package dev.local.goblinsettlement.client;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.Identifier;

public final class GoblinRenderer extends MobRenderer<GoblinCitizenEntity, GoblinRenderState, GoblinModel> {
    private static final Identifier DEFAULT_TEXTURE = texture("goblin");
    private static final Identifier TEXTURE_FARMER = texture("goblin_farmer");
    private static final Identifier TEXTURE_FORESTER = texture("goblin_forester");
    private static final Identifier TEXTURE_MINER = texture("goblin_miner");
    private static final Identifier TEXTURE_BUILDER = texture("goblin_builder");
    private static final Identifier TEXTURE_HAULER = texture("goblin_hauler");
    private static final Identifier TEXTURE_ARTISAN = texture("goblin_artisan");
    private static final Identifier TEXTURE_SENTRY = texture("goblin_sentry");

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
            case FARMER -> TEXTURE_FARMER;
            case FORESTER -> TEXTURE_FORESTER;
            case MINER -> TEXTURE_MINER;
            case BUILDER -> TEXTURE_BUILDER;
            case HAULER -> TEXTURE_HAULER;
            case ARTISAN -> TEXTURE_ARTISAN;
            case SENTRY -> TEXTURE_SENTRY;
            case UNASSIGNED -> DEFAULT_TEXTURE;
        };
    }
}
