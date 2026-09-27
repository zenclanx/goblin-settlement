package dev.local.goblinsettlement.housing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Pure blueprint data: geometry, upgrade chain and the rules that keep it honest. */
public record BlueprintSet(List<Stage> capacity, List<Stage> quality,
                           int reserveBasic, int reserveExpanded) {
    /** The blueprint box every coordinate must stay inside. */
    public static final int MIN_X = -3;
    public static final int MAX_X = 3;
    public static final int MIN_Y = 0;
    public static final int MAX_Y = 4;
    public static final int MIN_Z = -2;
    public static final int MAX_Z = 2;

    /** The only block the data may name until the worker path learns other materials. */
    public static final String TRANSITIONAL_BLOCK = "minecraft:oak_planks";

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

    public static final Codec<BlueprintSet> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            STAGE_CODEC.listOf().fieldOf("capacity").forGetter(BlueprintSet::capacity),
            STAGE_CODEC.listOf().fieldOf("quality").forGetter(BlueprintSet::quality),
            Codec.INT.fieldOf("reserve_basic").forGetter(BlueprintSet::reserveBasic),
            Codec.INT.fieldOf("reserve_expanded").forGetter(BlueprintSet::reserveExpanded)
    ).apply(instance, BlueprintSet::new));

    /**
     * The capacity stages first, then the quality stages, exactly as the two-axis design orders
     * them. Both lists keep sharing their step instances, so repeated calls are cheap.
     */
    public List<HousingRules.Step> steps(int capacityTarget, int qualityTarget) {
        if (capacityTarget < 0 || capacityTarget >= capacity.size()
                || qualityTarget < 0 || qualityTarget > quality.size()) {
            throw new IllegalArgumentException("Blueprint targets out of range");
        }
        List<HousingRules.Step> result = new ArrayList<>();
        for (int index = 0; index <= capacityTarget; index++) {
            result.addAll(capacity.get(index).steps());
        }
        for (int index = 1; index <= qualityTarget; index++) {
            result.addAll(quality.get(index - 1).steps());
        }
        return List.copyOf(result);
    }

    /** Every stage in the order the coordinator indexes them: capacity 0..n, then quality 0..m. */
    public List<List<HousingRules.Step>> stages() {
        List<List<HousingRules.Step>> result = new ArrayList<>();
        for (Stage stage : capacity) {
            result.add(List.copyOf(stage.steps()));
        }
        for (Stage stage : quality) {
            result.add(List.copyOf(stage.steps()));
        }
        return List.copyOf(result);
    }

    /** All problems found, empty when the data is sound. */
    public List<String> validate(Set<String> knownBlocks) {
        List<String> problems = new ArrayList<>();
        if (capacity.size() != HousingRules.MAX_CAPACITY_TARGET + 1) {
            problems.add("capacity needs " + (HousingRules.MAX_CAPACITY_TARGET + 1)
                    + " stages, found " + capacity.size());
        }
        if (quality.size() != HousingRules.MAX_QUALITY_TARGET) {
            problems.add("quality needs " + HousingRules.MAX_QUALITY_TARGET
                    + " stages, found " + quality.size());
        }
        if (reserveBasic <= 0) {
            problems.add("reserve_basic must be positive");
        }
        if (reserveExpanded <= 0) {
            problems.add("reserve_expanded must be positive");
        }
        validateChain("capacity", capacity, knownBlocks, problems);
        validateChain("quality", quality, knownBlocks, problems);
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
            } else if (!TRANSITIONAL_BLOCK.equals(step.block())) {
                problems.add(name + "/" + stage.id() + ": " + step.block()
                        + " is not yet supported -- until the worker path carries other materials,"
                        + " the data may only use " + TRANSITIONAL_BLOCK);
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
