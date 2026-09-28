package dev.local.goblinsettlement.client.model;

import dev.local.goblinsettlement.client.GoblinRenderState;
import dev.local.goblinsettlement.defense.GolemTier;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;

/**
 * Every custom golem the client can render, in one table -- the golem twin of {@link GoblinBodies}, read
 * by the same three kinds of reader: {@code GoblinSettlementClient} registers a model layer per row,
 * {@code GolemRenderer} bakes a row per tier and picks it per golem, and {@code ArtModelCheck} asserts
 * each row's crop, texture and emissive mask are actually committed. Adding a tier's art is one row here
 * and nothing else.
 *
 * <p>A row is keyed on {@link GolemTier}, and every field a row carries comes from its class's own
 * constants -- a row names its class and cannot retype a crop, a skin, a glow mask or a tier name.
 *
 * <p>Iron has no row on purpose. Iron is the vanilla entity, owned by {@code VanillaIronGolemBridge},
 * and a GoblinGolemEntity at iron is a legacy save in the middle of being migrated off it, so the entity
 * never renders that tier; {@code GolemRenderer} says so again from its side.
 */
public final class GolemBodies {
    /**
     * One renderable golem. {@code emissive} is the transparent full-bright mask the glow layer draws
     * over {@code texture}; the two are paired here so a glow cannot drift onto another tier's skin.
     */
    public record Body(GolemTier tier, String crop, String texture, String emissive,
                       Supplier<LayerDefinition> layer,
                       Function<ModelPart, EntityModel<GoblinRenderState>> model) {
    }

    public static final List<Body> BODIES = List.of(
            WoodGolemModel.body(),
            StoneGolemModel.body(),
            GoldGolemModel.body(),
            DiamondGolemModel.body(),
            ObsidianGolemModel.body());

    private GolemBodies() {
    }
}
