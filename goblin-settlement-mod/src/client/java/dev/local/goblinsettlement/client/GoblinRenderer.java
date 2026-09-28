package dev.local.goblinsettlement.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.local.goblinsettlement.GoblinSettlement;
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
    /** One trade's body: the mesh and the texture it samples, paired so the two cannot drift apart. */
    private record Outfit(EntityModel<GoblinRenderState> model, Identifier texture) {
    }

    /**
     * One sex's adult bodies: the undressed base plus one outfit per trade that has art. A trade with no
     * art falls back to the base, which is why every lookup takes a default rather than returning null.
     */
    private record Bodies(Outfit base, Map<Profession, Outfit> outfits) {
        EntityModel<GoblinRenderState> body(Profession profession) {
            return outfit(profession).model();
        }

        Identifier texture(Profession profession) {
            return outfit(profession).texture();
        }

        private Outfit outfit(Profession profession) {
            return outfits.getOrDefault(profession, base);
        }
    }

    private final Bodies male;
    private final Bodies female;
    /**
     * One child body per sex, held as a bare {@link Outfit} rather than a {@link Bodies}. That is the
     * whole guard: a child path has no outfit map and takes no profession, so a child can never resolve
     * to a trade's outfit, and only the two branches above can reach an outfit at all.
     */
    private final Outfit childMale;
    private final Outfit childFemale;

    public GoblinRenderer(EntityRendererProvider.Context context) {
        this(context, bake(context, false, false), bake(context, false, true),
                bake(context, true, false).base(), bake(context, true, true).base());
    }

    private GoblinRenderer(EntityRendererProvider.Context context, Bodies male, Bodies female,
                           Outfit childMale, Outfit childFemale) {
        // super(...) must be the first statement, so it cannot take the field below -- this bakes the
        // male base twice and throws one tree away on the first submit. Cheap, and the alternative
        // (a shared holder) buys nothing here.
        super(context, male.base().model(), 0.3F);
        this.male = male;
        this.female = female;
        this.childMale = childMale;
        this.childFemale = childFemale;
    }

    /**
     * Bakes every body of one age and sex from the table. The base is the row whose profession is empty;
     * a child table must have exactly that one row, so a child row that names a trade fails the build
     * here (ArtModelCheck spells the same rule out, this is the second lock on the same door).
     */
    private static Bodies bake(EntityRendererProvider.Context context, boolean child, boolean female) {
        Outfit base = null;
        Map<Profession, Outfit> outfits = new EnumMap<>(Profession.class);
        for (GoblinBodies.Body body : GoblinBodies.BODIES) {
            if (body.child() != child || body.female() != female) {
                continue;
            }
            var model = body.model().apply(context.bakeLayer(GoblinSettlementClient.layer(body.crop())));
            var texture = Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID,
                    "textures/entity/" + body.texture() + ".png");
            var outfit = new Outfit(model, texture);
            if (body.profession().isEmpty()) {
                base = outfit;
            } else {
                outfits.put(body.profession().orElseThrow(), outfit);
            }
        }
        if (base == null) {
            throw new IllegalStateException(
                    "no " + (child ? "child" : "base") + " body for female=" + female
                            + " in GoblinBodies.BODIES");
        }
        if (child && !outfits.isEmpty()) {
            throw new IllegalStateException("a child body carries an outfit, which the roster never gives: "
                    + outfits.keySet());
        }
        return new Bodies(base, Map.copyOf(outfits));
    }

    /** The single body a child wears. It takes no profession, so there is no outfit a child could get. */
    private Outfit childBody(boolean female) {
        return female ? childFemale : childMale;
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
        state.child = entity.childForRender();
    }

    /**
     * The submit path reads the {@code model} field rather than calling {@code getModel()}, so choosing
     * a body means assigning that field before the superclass submits.
     */
    @Override
    public void submit(GoblinRenderState state, PoseStack pose, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        this.model = state.child ? childBody(state.female).model()
                : (state.female ? female : male).body(state.profession);
        super.submit(state, pose, collector, camera);
    }

    @Override
    public Identifier getTextureLocation(GoblinRenderState state) {
        return state.child ? childBody(state.female).texture()
                : (state.female ? female : male).texture(state.profession);
    }
}
