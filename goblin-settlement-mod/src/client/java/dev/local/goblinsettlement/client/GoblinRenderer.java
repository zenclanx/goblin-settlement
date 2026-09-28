package dev.local.goblinsettlement.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.client.model.GoblinFemaleModel;
import dev.local.goblinsettlement.client.model.GoblinMaleModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.Identifier;

public final class GoblinRenderer
        extends MobRenderer<GoblinCitizenEntity, GoblinRenderState, EntityModel<GoblinRenderState>> {
    private static final Identifier TEXTURE_MALE = texture("goblin_male");
    private static final Identifier TEXTURE_FEMALE = texture("goblin_female");

    private final EntityModel<GoblinRenderState> male;
    private final EntityModel<GoblinRenderState> female;

    public GoblinRenderer(EntityRendererProvider.Context context) {
        // super(...) must be the first statement, so it cannot take the field below -- this bakes the
        // male mesh twice and throws one tree away on the first submit. Cheap, and the alternative
        // (a shared holder) buys nothing here.
        super(context, new GoblinMaleModel(context.bakeLayer(GoblinSettlementClient.GOBLIN_MALE_LAYER)), 0.3F);
        male = new GoblinMaleModel(context.bakeLayer(GoblinSettlementClient.GOBLIN_MALE_LAYER));
        female = new GoblinFemaleModel(context.bakeLayer(GoblinSettlementClient.GOBLIN_FEMALE_LAYER));
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
        state.female = entity.femaleForRender();
    }

    /**
     * The submit path reads the {@code model} field rather than calling {@code getModel()}, so choosing
     * a body means assigning that field before the superclass submits.
     */
    @Override
    public void submit(GoblinRenderState state, PoseStack pose, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        this.model = state.female ? female : male;
        super.submit(state, pose, collector, camera);
    }

    @Override
    public EntityModel<GoblinRenderState> getModel() {
        return model;
    }

    @Override
    public Identifier getTextureLocation(GoblinRenderState state) {
        return state.female ? TEXTURE_FEMALE : TEXTURE_MALE;
    }
}
