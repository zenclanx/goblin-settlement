package dev.local.goblinsettlement.housing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure blueprint data: geometry, upgrade chain and the rules that keep it honest. */
public record BlueprintSet(List<Style> styles, int reserveBasic, int reserveExpanded) {
    /** The blueprint box every coordinate must stay inside. */
    public static final int MIN_X = -3;
    public static final int MAX_X = 3;
    public static final int MIN_Y = 0;
    public static final int MAX_Y = 4;
    public static final int MIN_Z = -2;
    public static final int MAX_Z = 2;

    /**
     * One complete house style: a full capacity ladder plus a full quality ladder. Variety is per
     * style rather than per level because the levels are additive -- a per-level shape set would
     * demand that every pair of levels join seamlessly, which no simple rule can guarantee.
     */
    public record Style(String id, List<Stage> capacity, List<Stage> quality) {
    }

    /** {@code requires} is empty for a chain head, otherwise the id of the previous stage. */
    public record Stage(String id, String requires, List<HousingRules.Step> steps) {
    }

    public static final Codec<HousingRules.Step> STEP_CODEC =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("x").forGetter(HousingRules.Step::x),
                    Codec.INT.fieldOf("y").forGetter(HousingRules.Step::y),
                    Codec.INT.fieldOf("z").forGetter(HousingRules.Step::z),
                    Codec.STRING.fieldOf("block").forGetter(HousingRules.Step::block)
            ).apply(instance, HousingRules.Step::new));

    public static final Codec<Stage> STAGE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Stage::id),
            Codec.STRING.optionalFieldOf("requires", "").forGetter(Stage::requires),
            STEP_CODEC.listOf().fieldOf("steps").forGetter(Stage::steps)
    ).apply(instance, Stage::new));

    public static final Codec<Style> STYLE_CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Style::id),
            STAGE_CODEC.listOf().fieldOf("capacity").forGetter(Style::capacity),
            STAGE_CODEC.listOf().fieldOf("quality").forGetter(Style::quality)
    ).apply(instance, Style::new));

    public static final Codec<BlueprintSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            STYLE_CODEC.listOf().fieldOf("styles").forGetter(BlueprintSet::styles),
            Codec.INT.fieldOf("reserve_basic").forGetter(BlueprintSet::reserveBasic),
            Codec.INT.fieldOf("reserve_expanded").forGetter(BlueprintSet::reserveExpanded)
    ).apply(instance, BlueprintSet::new));

    public int styleCount() {
        return styles.size();
    }

    /** An out-of-range index degrades to the first style rather than failing a world load. */
    public Style styleAt(int styleIndex) {
        if (styleIndex < 0 || styleIndex >= styles.size()) {
            return styles.get(0);
        }
        return styles.get(styleIndex);
    }

    /**
     * The capacity stages first, then the quality stages, exactly as the two-axis design orders
     * them. Both lists keep sharing their step instances, so repeated calls are cheap.
     */
    public List<HousingRules.Step> steps(int styleIndex, int capacityTarget, int qualityTarget) {
        Style style = styleAt(styleIndex);
        if (capacityTarget < 0 || capacityTarget >= style.capacity().size()
                || qualityTarget < 0 || qualityTarget > style.quality().size()) {
            throw new IllegalArgumentException("Blueprint targets out of range");
        }
        List<HousingRules.Step> result = new ArrayList<>();
        for (int index = 0; index <= capacityTarget; index++) {
            result.addAll(style.capacity().get(index).steps());
        }
        for (int index = 1; index <= qualityTarget; index++) {
            result.addAll(style.quality().get(index - 1).steps());
        }
        return List.copyOf(result);
    }

    /** Every stage of one style in the order the coordinator indexes them: capacity, then quality. */
    public List<List<HousingRules.Step>> stages(int styleIndex) {
        Style style = styleAt(styleIndex);
        List<List<HousingRules.Step>> result = new ArrayList<>();
        for (Stage stage : style.capacity()) {
            result.add(List.copyOf(stage.steps()));
        }
        for (Stage stage : style.quality()) {
            result.add(List.copyOf(stage.steps()));
        }
        return List.copyOf(result);
    }

    /** All problems found, empty when the data is sound. */
    public List<String> validate(Set<String> knownBlocks) {
        List<String> problems = new ArrayList<>();
        if (styles.isEmpty()) {
            problems.add("at least one style is required");
        }
        if (reserveBasic <= 0) {
            problems.add("reserve_basic must be positive");
        }
        if (reserveExpanded <= 0) {
            problems.add("reserve_expanded must be positive");
        }
        Set<String> ids = new HashSet<>();
        for (Style style : styles) {
            if (style.id() == null || style.id().isEmpty()) {
                problems.add("a style has an empty id");
            } else if (!ids.add(style.id())) {
                problems.add("duplicate style id " + style.id());
            }
            if (style.capacity().size() != HousingRules.MAX_CAPACITY_TARGET + 1) {
                problems.add(style.id() + " needs " + (HousingRules.MAX_CAPACITY_TARGET + 1)
                        + " capacity stages, found " + style.capacity().size());
            }
            if (style.quality().size() != HousingRules.MAX_QUALITY_TARGET) {
                problems.add(style.id() + " needs " + HousingRules.MAX_QUALITY_TARGET
                        + " quality stages, found " + style.quality().size());
            }
            validateChain(style.id() + "/capacity", style.capacity(), knownBlocks, problems);
            validateChain(style.id() + "/quality", style.quality(), knownBlocks, problems);
        }
        return List.copyOf(problems);
    }

    private static void validateChain(String name, List<Stage> chain, Set<String> knownBlocks,
                                      List<String> problems) {
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < chain.size(); index++) {
            Stage stage = chain.get(index);
            if (!seen.add(stage.id())) {
                problems.add(name + ": duplicate stage id " + stage.id());
            }
            if (index == 0) {
                if (!stage.requires().isEmpty()) {
                    problems.add(name + ": the first stage must not require anything");
                }
            } else if (stage.requires().isEmpty()) {
                problems.add(name + ": " + stage.id() + " is a second chain head");
            } else if (chain.subList(0, index).stream()
                    .noneMatch(earlier -> earlier.id().equals(stage.requires()))) {
                // Requiring strictly backwards is what makes the chain acyclic by construction.
                problems.add(name + ": " + stage.id() + " requires " + stage.requires()
                        + ", which is not an earlier stage of the same chain");
            }
            validateSteps(name, stage, knownBlocks, problems);
        }
        if (!entranceOpen(chain)) {
            problems.add(name + ": the chain seals the anchor with no legal entrance");
        }
    }

    private static void validateSteps(String name, Stage stage, Set<String> knownBlocks,
                                      List<String> problems) {
        Set<Long> coordinates = new HashSet<>();
        for (HousingRules.Step step : stage.steps()) {
            if (step.x() < MIN_X || step.x() > MAX_X || step.y() < MIN_Y || step.y() > MAX_Y
                    || step.z() < MIN_Z || step.z() > MAX_Z) {
                problems.add(name + "/" + stage.id() + ": step outside the blueprint box at "
                        + step.x() + "," + step.y() + "," + step.z());
            }
            if (!coordinates.add(key3(step.x(), step.y(), step.z()))) {
                problems.add(name + "/" + stage.id() + ": duplicated step at "
                        + step.x() + "," + step.y() + "," + step.z());
            }
            if (!knownBlocks.contains(step.block())) {
                problems.add(name + "/" + stage.id() + ": unknown block " + step.block());
            }
        }
    }

    /**
     * A chain is enterable when the anchor column is reachable from outside the footprint, walking
     * only through columns that are free at both y=0 and y=1. A two-block-high opening anywhere in
     * the walls therefore keeps the blueprint legal, while a sealed ring is rejected. The walk runs
     * on the cumulative shape: a house grows stage by stage, and only the total encloses anything.
     */
    static boolean entranceOpen(List<Stage> chain) {
        Set<Long> blocked = new HashSet<>();
        int minX = 0;
        int maxX = 0;
        int minZ = 0;
        int maxZ = 0;
        for (Stage stage : chain) {
            for (HousingRules.Step step : stage.steps()) {
                minX = Math.min(minX, step.x());
                maxX = Math.max(maxX, step.x());
                minZ = Math.min(minZ, step.z());
                maxZ = Math.max(maxZ, step.z());
                if (step.y() == 0 || step.y() == 1) {
                    blocked.add(key2(step.x(), step.z()));
                }
            }
        }
        minX--;
        maxX++;
        minZ--;
        maxZ++;
        Set<Long> reached = new HashSet<>();
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int x = minX; x <= maxX; x++) {
            seed(queue, reached, blocked, x, minZ);
            seed(queue, reached, blocked, x, maxZ);
        }
        for (int z = minZ; z <= maxZ; z++) {
            seed(queue, reached, blocked, minX, z);
            seed(queue, reached, blocked, maxX, z);
        }
        while (!queue.isEmpty()) {
            int[] cell = queue.poll();
            seed(queue, reached, blocked, cell[0] + 1, cell[1]);
            seed(queue, reached, blocked, cell[0] - 1, cell[1]);
            seed(queue, reached, blocked, cell[0], cell[1] + 1);
            seed(queue, reached, blocked, cell[0], cell[1] - 1);
        }
        return reached.contains(key2(0, 0));
    }

    private static void seed(ArrayDeque<int[]> queue, Set<Long> reached, Set<Long> blocked,
                             int x, int z) {
        if (x < MIN_X - 1 || x > MAX_X + 1 || z < MIN_Z - 1 || z > MAX_Z + 1) {
            return;
        }
        long cell = key2(x, z);
        if (blocked.contains(cell) || !reached.add(cell)) {
            return;
        }
        queue.add(new int[] {x, z});
    }

    private static long key2(int x, int z) {
        return ((long) (x + 64) << 32) ^ (z + 64);
    }

    private static long key3(int x, int y, int z) {
        return ((long) (x + 64) << 42) ^ ((long) (y + 64) << 21) ^ (z + 64);
    }
}
