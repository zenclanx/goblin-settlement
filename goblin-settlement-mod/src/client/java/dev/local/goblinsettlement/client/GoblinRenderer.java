package dev.local.goblinsettlement.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.client.model.GoblinBodies;
import dev.local.goblinsettlement.colony.Profession;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.Identifier;

public final class GoblinRenderer
        extends MobRenderer<GoblinCitizenEntity, GoblinRenderState, EntityModel<GoblinRenderState>> {
    /**
     * One sex's bodies: the undressed base plus one outfit per trade that has art. A trade with no art
     * falls back to the base, which is why every lookup takes a default rather than returning null --
     * six of the seven trades are still undressed.
     */
    private record Bodies(EntityModel<GoblinRenderState> base, Identifier baseTexture,
                          Map<Profession, EntityModel<GoblinRenderState>> outfits,
                          Map<Profession, Identifier> textures) {
        EntityModel<GoblinRenderState> body(Profession profession) {
            return outfits.getOrDefault(profession, base);
        }

        Identifier texture(Profession profession) {
            return textures.getOrDefault(profession, baseTexture);
        }
    }

    private final Bodies male;
    private final Bodies female;

    public GoblinRenderer(EntityRendererProvider.Context context) {
        this(context, bake(context, false), bake(context, true));
    }

    private GoblinRenderer(EntityRendererProvider.Context context, Bodies male, Bodies female) {
        // super(...) must be the first statement, so it cannot take the field below -- this bakes the
        // male base twice and throws one tree away on the first submit. Cheap, and the alternative
        // (a shared holder) buys nothing here.
        super(context, male.base(), 0.3F);
        this.male = male;
        this.female = female;
    }

    /** Bakes every body of one sex from the table. The base is the row whose profession is empty. */
    private static Bodies bake(EntityRendererProvider.Context context, boolean female) {
        EntityModel<GoblinRenderState> base = null;
        Identifier baseTexture = null;
        Map<Profession, EntityModel<GoblinRenderState>> outfits = new EnumMap<>(Profession.class);
        Map<Profession, Identifier> textures = new EnumMap<>(Profession.class);
        for (GoblinBodies.Body body : GoblinBodies.BODIES) {
            if (body.female() != female) {
                continue;
            }
            var model = body.model().apply(context.bakeLayer(GoblinSettlementClient.layer(body.crop())));
            var texture = Identifier.fromNamespaceAndPath("goblin_settlement",
                    "textures/entity/" + body.texture() + ".png");
            if (body.profession().isEmpty()) {
                base = model;
                baseTexture = texture;
            } else {
                outfits.put(body.profession().orElseThrow(), model);
                textures.put(body.profession().orElseThrow(), texture);
            }
        }
        if (base == null) {
            throw new IllegalStateException("no base body for female=" + female + " in GoblinBodies.BODIES");
        }
        return new Bodies(base, baseTexture, Map.copyOf(outfits), Map.copyOf(textures));
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
        this.model = (state.female ? female : male).body(state.profession);
        super.submit(state, pose, collector, camera);
    }

    @Override
    public Identifier getTextureLocation(GoblinRenderState state) {
        return (state.female ? female : male).texture(state.profession);
    }
}
