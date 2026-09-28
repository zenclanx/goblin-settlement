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
 */
public final class GoblinBodies {
    /** One renderable body. {@code profession} is empty for the two undressed base bodies. */
    public record Body(boolean female, Optional<Profession> profession, String crop, String texture,
                       Supplier<LayerDefinition> layer,
                       Function<ModelPart, EntityModel<GoblinRenderState>> model) {
    }

    public static final List<Body> BODIES = List.of(
            GoblinMaleModel.body(false, Optional.empty(), "goblin_male"),
            GoblinFemaleModel.body(true, Optional.empty(), "goblin_female"),
            GoblinFarmerMaleModel.body(false, Optional.of(Profession.FARMER), "goblin_farmer_male"),
            GoblinFarmerFemaleModel.body(true, Optional.of(Profession.FARMER), "goblin_farmer_female"),
            GoblinForesterMaleModel.body(false, Optional.of(Profession.FORESTER), "goblin_forester_male"),
            GoblinForesterFemaleModel.body(true, Optional.of(Profession.FORESTER), "goblin_forester_female"),
            GoblinMinerMaleModel.body(false, Optional.of(Profession.MINER), "goblin_miner_male"),
            GoblinMinerFemaleModel.body(true, Optional.of(Profession.MINER), "goblin_miner_female"),
            GoblinBuilderMaleModel.body(false, Optional.of(Profession.BUILDER), "goblin_builder_male"),
            GoblinBuilderFemaleModel.body(true, Optional.of(Profession.BUILDER), "goblin_builder_female"),
            GoblinHaulerMaleModel.body(false, Optional.of(Profession.HAULER), "goblin_hauler_male"),
            GoblinHaulerFemaleModel.body(true, Optional.of(Profession.HAULER), "goblin_hauler_female"),
            GoblinArtisanMaleModel.body(false, Optional.of(Profession.ARTISAN), "goblin_artisan_male"),
            GoblinArtisanFemaleModel.body(true, Optional.of(Profession.ARTISAN), "goblin_artisan_female"),
            GoblinSentryMaleModel.body(false, Optional.of(Profession.SENTRY), "goblin_sentry_male"));

    private GoblinBodies() {
    }
}
