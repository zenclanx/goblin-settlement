package dev.local.goblinsettlement.housing;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Loads the housing blueprint data file once, on server start, and resolves every block id against
 * the registries. When the file is missing or invalid no houses are built at all: the hardcoded
 * geometry this replaced is gone, so falling back would only hide the problem.
 */
public final class HousingBlueprints {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(HousingBlueprints.class);
    private static final String RESOURCE =
            "/data/goblin_settlement/housing_blueprints.json";

    public record ResolvedStep(int x, int y, int z, Block block, Item item) {
    }

    private static List<List<List<ResolvedStep>>> styles = List.of();
    private static int reserveBasic = HousingRules.RESERVE_FALLBACK_BASIC;
    private static int reserveExpanded = HousingRules.RESERVE_FALLBACK_EXPANDED;
    private static boolean available;

    private HousingBlueprints() {
    }

    public static boolean available() {
        return available;
    }

    public static int styleCount() {
        return styles.size();
    }

    /** An out-of-range style degrades to the first one, so removing a style cannot break a world. */
    public static List<List<ResolvedStep>> stages(int styleIndex) {
        if (styles.isEmpty()) {
            return List.of();
        }
        if (styleIndex < 0 || styleIndex >= styles.size()) {
            return styles.get(0);
        }
        return styles.get(styleIndex);
    }

    public static int reserveBasic() {
        return reserveBasic;
    }

    public static int reserveExpanded() {
        return reserveExpanded;
    }

    /** Capacity stages first, then quality stages, exactly as the two-axis design orders them. */
    public static List<ResolvedStep> steps(int styleIndex, int capacityTarget, int qualityTarget) {
        List<List<ResolvedStep>> style = stages(styleIndex);
        List<ResolvedStep> result = new ArrayList<>();
        for (int index = 0; index <= capacityTarget; index++) {
            result.addAll(style.get(index));
        }
        for (int index = 1; index <= qualityTarget; index++) {
            result.addAll(style.get(HousingRules.MAX_CAPACITY_TARGET + index));
        }
        return List.copyOf(result);
    }

    public static void load() {
        available = false;
        try {
            BlueprintSet data = decode();
            if (data == null) {
                return;
            }
            Map<String, Block> blocks = new HashMap<>();
            BuiltInRegistries.BLOCK.forEach(block ->
                    blocks.put(BuiltInRegistries.BLOCK.getKey(block).toString(), block));
            Set<String> knownBlocks = Set.copyOf(blocks.keySet());
            List<String> problems = data.validate(knownBlocks);
            if (!problems.isEmpty()) {
                LOGGER.error("Housing blueprints are invalid, housing construction is stopped: {}",
                        problems);
                return;
            }
            List<List<List<ResolvedStep>>> resolvedStyles = new ArrayList<>();
            for (int styleIndex = 0; styleIndex < data.styleCount(); styleIndex++) {
                List<List<ResolvedStep>> resolved = new ArrayList<>();
                for (List<HousingRules.Step> branch : data.stages(styleIndex)) {
                    List<ResolvedStep> converted = new ArrayList<>();
                    for (HousingRules.Step step : branch) {
                        Block block = blocks.get(step.block());
                        Item item = block.asItem();
                        if (item == Items.AIR) {
                            LOGGER.error("Housing blueprint block {} has no item form, housing"
                                    + " construction is stopped", step.block());
                            return;
                        }
                        converted.add(new ResolvedStep(step.x(), step.y(), step.z(), block, item));
                    }
                    resolved.add(List.copyOf(converted));
                }
                resolvedStyles.add(List.copyOf(resolved));
            }
            styles = List.copyOf(resolvedStyles);
            reserveBasic = data.reserveBasic();
            reserveExpanded = data.reserveExpanded();
            available = true;
        } catch (RuntimeException failure) {
            LOGGER.error("Housing blueprints failed to load, housing construction is stopped",
                    failure);
        }
    }

    private static BlueprintSet decode() {
        try (InputStream stream = HousingBlueprints.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                LOGGER.error("Housing blueprint resource {} is missing, housing construction is"
                        + " stopped", RESOURCE);
                return null;
            }
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return BlueprintSet.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                    .getOrThrow();
        } catch (IOException failure) {
            LOGGER.error("Housing blueprint resource could not be read, housing construction is"
                    + " stopped", failure);
            return null;
        }
    }
}
