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
            new Body(false, Optional.empty(), "goblin_male_a", "goblin_male",
                    GoblinMaleModel::createLayer, GoblinMaleModel::new),
            new Body(true, Optional.empty(), "goblin_female_a", "goblin_female",
                    GoblinFemaleModel::createLayer, GoblinFemaleModel::new),
            new Body(false, Optional.of(Profession.FARMER), "goblin_farmer_male_p01", "goblin_farmer_male",
                    GoblinFarmerMaleModel::createLayer, GoblinFarmerMaleModel::new),
            new Body(true, Optional.of(Profession.FARMER), "goblin_farmer_female_p01", "goblin_farmer_female",
                    GoblinFarmerFemaleModel::createLayer, GoblinFarmerFemaleModel::new));

    private GoblinBodies() {
    }
}
