package dev.local.goblinsettlement.client.model;

import dev.local.goblinsettlement.client.GoblinRenderState;
import dev.local.goblinsettlement.colony.Profession;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;

/**
 * Every goblin body the client can render, in one table.
 *
 * Three readers share it, which is the point: {@code GoblinSettlementClient} registers a model layer
 * per row, {@code GoblinRenderer} bakes and picks a row per resident, and {@code ArtModelCheck}
 * asserts each row's geometry crop and texture are actually committed. Adding a trade's outfit is one
 * row here and nothing else -- and because the check reads the same rows, a row whose art never made
 * it into the repository fails the build instead of showing up in game as a missing texture.
 *
 * <p>A row is keyed on {@code (child, female, profession)}. The child axis is the roster's, not the
 * art's: a child has no trade, so its two rows leave {@code profession} empty and the renderer never
 * consults an outfit for them.
 */
public final class GoblinBodies {
    /**
     * One renderable body. {@code profession} is empty for the undressed base bodies and for both
     * children; the two flags are what {@code GoblinRenderer} and {@code ArtModelCheck} key a row off.
     */
    public record Body(boolean child, boolean female, Optional<Profession> profession, String crop,
                       String texture,
                       Supplier<LayerDefinition> layer,
                       Function<ModelPart, EntityModel<GoblinRenderState>> model) {
    }

    public static final List<Body> BODIES = List.of(
            GoblinMaleModel.body(false, false, Optional.empty()),
            GoblinFemaleModel.body(false, true, Optional.empty()),
            GoblinChildBoyModel.body(true, false, Optional.empty()),
            GoblinChildGirlModel.body(true, true, Optional.empty()),
            GoblinFarmerMaleModel.body(false, false, Optional.of(Profession.FARMER)),
            GoblinFarmerFemaleModel.body(false, true, Optional.of(Profession.FARMER)),
            GoblinForesterMaleModel.body(false, false, Optional.of(Profession.FORESTER)),
            GoblinForesterFemaleModel.body(false, true, Optional.of(Profession.FORESTER)),
            GoblinMinerMaleModel.body(false, false, Optional.of(Profession.MINER)),
            GoblinMinerFemaleModel.body(false, true, Optional.of(Profession.MINER)),
            GoblinBuilderMaleModel.body(false, false, Optional.of(Profession.BUILDER)),
            GoblinBuilderFemaleModel.body(false, true, Optional.of(Profession.BUILDER)),
            GoblinHaulerMaleModel.body(false, false, Optional.of(Profession.HAULER)),
            GoblinHaulerFemaleModel.body(false, true, Optional.of(Profession.HAULER)),
            GoblinArtisanMaleModel.body(false, false, Optional.of(Profession.ARTISAN)),
            GoblinArtisanFemaleModel.body(false, true, Optional.of(Profession.ARTISAN)),
            GoblinSentryMaleModel.body(false, false, Optional.of(Profession.SENTRY)),
            GoblinSentryFemaleModel.body(false, true, Optional.of(Profession.SENTRY)));

    private GoblinBodies() {
    }
}
