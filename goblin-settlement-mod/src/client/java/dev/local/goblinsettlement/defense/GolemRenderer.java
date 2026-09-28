package dev.local.goblinsettlement.defense;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.local.goblinsettlement.GoblinSettlement;
import dev.local.goblinsettlement.client.GoblinRenderState;
import dev.local.goblinsettlement.client.GoblinSettlementClient;
import dev.local.goblinsettlement.client.model.GolemBodies;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

/**
 * Draws each custom golem with the art of its residing tier, its core drawn full-bright on top.
 *
 * <p>The tier comes from the entity's synced data, so the client draws what the server owns. Every tier
 * this entity can render has a row in {@link GolemBodies} -- iron is the vanilla entity and is migrated
 * off before it is ever drawn -- so there is no fallback tier here: a tier with no art is a build error
 * and fails loudly rather than quietly wearing another tier's skin.
 */
public final class GolemRenderer
        extends MobRenderer<GoblinGolemEntity, GoblinRenderState, EntityModel<GoblinRenderState>> {
    /** One tier's art: the mesh, the skin it samples, and the mask its core glows through. */
    private record Outfit(EntityModel<GoblinRenderState> model, Identifier texture, Identifier mask) {
    }

    private final Map<GolemTier, Outfit> outfits;

    public GolemRenderer(EntityRendererProvider.Context context) {
        this(context, bake(context));
    }

    private GolemRenderer(EntityRendererProvider.Context context, Map<GolemTier, Outfit> outfits) {
        // super(...) must be the first statement, so it cannot read the field below. It takes wood's
        // mesh, the lowest tier; the submit path replaces it with the residing tier's before anything
        // is drawn, and `outfits` is the very map that was just checked to contain wood.
        super(context, outfit(outfits, GolemTier.WOOD).model(), 0.55F);
        this.outfits = outfits;
        addLayer(new CoreGlowLayer(this));
    }

    /**
     * Bakes every row of the table. A row is keyed on its tier, so a table that names one tier twice
     * would silently drop a mesh; the size check and {@code ArtModelCheck} both refuse that, this from
     * the render side.
     */
    private static Map<GolemTier, Outfit> bake(EntityRendererProvider.Context context) {
        Map<GolemTier, Outfit> outfits = new EnumMap<>(GolemTier.class);
        for (GolemBodies.Body body : GolemBodies.BODIES) {
            var model = body.model().apply(context.bakeLayer(GoblinSettlementClient.layer(body.crop())));
            outfits.put(body.tier(),
                    new Outfit(model, texture(body.texture()), texture(body.emissive())));
        }
        if (outfits.size() != GolemBodies.BODIES.size()) {
            throw new IllegalStateException(
                    "GolemBodies.BODIES names a tier twice: " + outfits.keySet());
        }
        return Map.copyOf(outfits);
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID,
                "textures/entity/" + name + ".png");
    }

    /** The art a tier wears. A tier with no art is a build error, not a fallback. */
    private static Outfit outfit(Map<GolemTier, Outfit> outfits, GolemTier tier) {
        Outfit outfit = outfits.get(tier);
        if (outfit == null) {
            throw new IllegalStateException("no golem art for the " + tier + " tier;"
                    + " GolemBodies.BODIES covers " + outfits.keySet());
        }
        return outfit;
    }

    private Outfit outfit(GolemTier tier) {
        return outfit(outfits, tier);
    }

    @Override
    public GoblinRenderState createRenderState() {
        return new GoblinRenderState();
    }

    @Override
    public void extractRenderState(GoblinGolemEntity entity, GoblinRenderState state, float partialTick) {
        super.extractRenderState(entity, state, partialTick);
        state.tier = entity.tierForRender();
    }

    /**
     * The submit path reads the {@code model} field rather than calling {@code getModel()}, so choosing
     * a tier means assigning that field before the superclass submits -- and it is the same field the
     * glow layer below redraws, so the core cannot land on another tier's mesh.
     */
    @Override
    public void submit(GoblinRenderState state, PoseStack pose, SubmitNodeCollector collector,
                       CameraRenderState camera) {
        this.model = outfit(state.tier).model();
        super.submit(state, pose, collector, camera);
    }

    @Override
    public Identifier getTextureLocation(GoblinRenderState state) {
        return outfit(state.tier).texture();
    }

    /**
     * Call once from the existing client initializer. Registers a model layer per row, exactly like the
     * goblin loop beside it, so a row added to {@code GolemBodies} is baked without touching this.
     */
    public static void initializeClient() {
        for (GolemBodies.Body body : GolemBodies.BODIES) {
            EntityModelLayerRegistry.registerModelLayer(
                    GoblinSettlementClient.layer(body.crop()), body.layer()::get);
        }
        EntityRenderers.register(GolemEntities.GOLEM, GolemRenderer::new);
    }

    /**
     * The glowing core. The art ships a second, transparent texture holding just the core and the eyes;
     * this redraws the tier's own mesh with that mask, so only those pixels light up and they light up
     * at full brightness.
     *
     * <p>Written by hand rather than reusing {@code LivingEntityEmissiveLayer}, which is exactly this
     * but holds one fixed model -- and here the mesh is chosen per tier. The render type is
     * {@code RenderTypes.eyes}, the full-bright one vanilla's own emissive layers use (the copper
     * golem's eye glow among them), and the call shape below matches theirs: {@code order(1)} so the
     * glow lands after the skin it sits on, the light and overlay the entity is drawn under, a full
     * alpha tint, no atlas sprite, and the entity's own outline colour.
     */
    private static final class CoreGlowLayer
            extends RenderLayer<GoblinRenderState, EntityModel<GoblinRenderState>> {
        private final GolemRenderer renderer;

        CoreGlowLayer(GolemRenderer renderer) {
            super(renderer);
            this.renderer = renderer;
        }

        @Override
        public void submit(PoseStack pose, SubmitNodeCollector collector, int packedLight,
                           GoblinRenderState state, float yRot, float xRot) {
            if (state.isInvisible) {
                return;
            }
            Outfit outfit = renderer.outfit(state.tier);
            collector.order(1).submitModel(outfit.model(), state, pose,
                    RenderTypes.eyes(outfit.mask()), packedLight,
                    LivingEntityRenderer.getOverlayCoords(state, 0.0F), ARGB.white(1.0F),
                    null, state.outlineColor, null);
        }
    }
}
