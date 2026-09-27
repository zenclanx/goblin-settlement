package dev.local.goblinsettlement.housing;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BlueprintCheck {
    private static final String RESOURCE =
            "/data/goblin_settlement/housing_blueprints.json";
    private static final Set<String> KNOWN = Set.of("minecraft:oak_planks");

    public static void main(String[] args) throws Exception {
        BlueprintSet data = loadRealFile();
        checkRealFile(data);
        checkSteps(data);
        checkFirstUnbuilt(data);
        checkCodecRoundTrip();
        checkChainRules();
        checkEntrance();
        checkBounds();
        checkTransitionalBlockRule();
        System.out.println("BlueprintCheck passed");
    }

    /** The shipped data file must decode and validate; this is the "validated data file" point. */
    private static BlueprintSet loadRealFile() throws Exception {
        try (InputStream stream = BlueprintCheck.class.getResourceAsStream(RESOURCE)) {
            require(stream != null, "the blueprint resource is on the classpath");
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return BlueprintSet.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                    .getOrThrow();
        }
    }

    private static void checkRealFile(BlueprintSet data) {
        var problems = data.validate(KNOWN);
        require(problems.isEmpty(), "the shipped blueprint file validates: " + problems);
    }

    private static void checkSteps(BlueprintSet data) {
        require(data.steps(0, 0).size() == 21, "shelter only");
        require(data.steps(1, 0).size() == 92, "shelter plus cabin");
        require(data.steps(2, 0).size() == 102, "all three capacity stages");
        require(data.steps(2, 1).size() == 111, "capacity plus the first quality stage");
        require(data.steps(2, 2).size() == 121, "everything");
        require(data.steps(1, 1).equals(data.steps(1, 1)),
                "the same targets always compose the same list");
        for (var step : data.steps(2, 2)) {
            require(step.x() >= -3 && step.x() <= 3 && step.z() >= -2 && step.z() <= 2
                    && step.y() >= 0 && step.y() <= 4, "every step stays inside the blueprint box");
            require(step.block().equals("minecraft:oak_planks"), "the transitional block rule holds");
        }
        boolean threw = false;
        try {
            data.steps(3, 0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        require(threw, "targets beyond the ladder are rejected");
    }

    private static void checkFirstUnbuilt(BlueprintSet data) {
        List<HousingRules.Step> shelter = data.steps(0, 0);
        require(HousingRules.firstUnbuilt(shelter, Set.of()).orElseThrow() == 0,
                "an untouched home starts at the first step");
        Set<HousingRules.Step> half = new HashSet<>(shelter.subList(0, 5));
        require(HousingRules.firstUnbuilt(shelter, half).orElseThrow() == 5,
                "built leading steps are skipped");
        require(HousingRules.firstUnbuilt(shelter, new HashSet<>(shelter)).isEmpty(),
                "a fully built home has nothing left");
        Set<HousingRules.Step> builtEarly = new HashSet<>(data.steps(1, 1));
        int next = HousingRules.firstUnbuilt(data.steps(2, 1), builtEarly).orElseThrow();
        require(next == 92, "raising the capacity target after quality work finds the inserted steps");
        require(data.steps(2, 1).get(next).equals(data.steps(2, 0).get(21 + 71)),
                "the step found is the first expanded-stage step");
    }

    private static void checkCodecRoundTrip() {
        var json = JsonParser.parseString("""
                {"reserve_basic": 8, "reserve_expanded": 24,
                 "capacity": [{"id": "a", "steps": [{"x": 1, "y": 0, "z": -1, "block": "minecraft:oak_planks"}]}],
                 "quality": [{"id": "b", "requires": "a", "steps": []}]}
                """);
        BlueprintSet parsed = BlueprintSet.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        require(parsed.capacity().size() == 1 && parsed.quality().size() == 1,
                "both chains decode");
        require(parsed.capacity().get(0).requires().isEmpty(), "an absent requires decodes to empty");
        require(parsed.quality().get(0).requires().equals("a"), "a present requires survives");
        var reencoded = BlueprintSet.CODEC
                .encodeStart(JsonOps.INSTANCE, parsed).getOrThrow().getAsJsonObject();
        BlueprintSet again = BlueprintSet.CODEC.parse(JsonOps.INSTANCE, reencoded).getOrThrow();
        require(again.equals(parsed), "a round trip is lossless");
    }

    private static void checkChainRules() {
        require(valid(List.of(cap("a", ""), cap("b", "a"), cap("c", "b"))).isEmpty(),
                "a linear chain is legal");
        require(valid(List.of(cap("a", "a"), cap("b", "a"), cap("c", "b"))).size() == 1,
                "a chain head that requires something is rejected");
        require(valid(List.of(cap("a", ""), cap("b", "c"), cap("c", "b"))).size() == 1,
                "a backwards reference is rejected (this is what keeps the chain acyclic)");
        require(valid(List.of(cap("a", ""), cap("b", "missing"), cap("c", "b"))).size() == 1,
                "a dangling reference is rejected");
        require(valid(List.of(cap("a", ""), cap("b", "a"), cap("b", "b"))).size() == 1,
                "a duplicate id is rejected");
        require(valid(List.of(cap("a", ""), cap("b", ""), cap("c", "b"))).size() == 1,
                "a second chain head is rejected");
    }

    private static void checkEntrance() {
        // A walled ring with no gap seals the anchor: every orthogonal neighbour is blocked.
        require(!BlueprintSet.entranceOpen(List.of(stageOf(
                step(1, 0, 0), step(1, 1, 0), step(-1, 0, 0), step(-1, 1, 0),
                step(0, 0, 1), step(0, 1, 1), step(0, 0, -1), step(0, 1, -1)))),
                "a sealed ring has no entrance");

        // The same ring with one column left open is enterable.
        require(BlueprintSet.entranceOpen(List.of(stageOf(
                step(1, 0, 0), step(1, 1, 0), step(-1, 0, 0), step(-1, 1, 0),
                step(0, 0, 1), step(0, 1, 1)))),
                "a two-block-high doorway makes the ring enterable");

        // A column blocked only at y=1 still seals it: a doorway has to be two blocks high.
        require(!BlueprintSet.entranceOpen(List.of(stageOf(
                step(1, 0, 0), step(1, 1, 0), step(-1, 0, 0), step(-1, 1, 0),
                step(0, 0, -1), step(0, 1, -1), step(0, 1, 1)))),
                "a column blocked only at y=1 still seals the ring");

        // Nothing at ground level at all is trivially enterable (the shelter stage).
        require(BlueprintSet.entranceOpen(List.of(stageOf(step(1, 3, 1)))),
                "a wall-less stage is enterable");
    }

    private static void checkBounds() {
        require(problemsWithStage(step(4, 0, 0)) == 1, "x beyond the box is rejected");
        require(problemsWithStage(step(0, 5, 0)) == 1, "y beyond the box is rejected");
        require(problemsWithStage(step(0, -1, 0)) == 1, "y below the box is rejected");
        require(problemsWithStage(step(0, 0, 3)) == 1, "z beyond the box is rejected");
        require(problemsWithStage(step(1, 0, 1), step(1, 0, 1)) == 1,
                "a duplicated coordinate inside one stage is rejected");
        require(reserveProblem(0, 24) == 1, "a non-positive reserve_basic is rejected");
        require(reserveProblem(8, 0) == 1, "a non-positive reserve_expanded is rejected");
        require(reserveProblem(8, 24) == 0, "positive reserves pass");
    }

    private static void checkTransitionalBlockRule() {
        var stone = new HousingRules.Step(0, 3, 0, "minecraft:stone");
        var capacity = List.of(new BlueprintSet.Stage("a", "", List.of(stone)),
                cap("b", "a"), cap("c", "b"));
        var quality = List.of(cap("q0", ""), cap("q1", "q0"));
        var problems = new BlueprintSet(capacity, quality, 8, 24).validate(KNOWN);
        require(problems.size() == 1,
                "a known block that is not oak planks is rejected by the transitional rule: "
                        + problems);

        var unknown = new HousingRules.Step(0, 3, 0, "minecraft:not_a_block");
        var otherCapacity = List.of(new BlueprintSet.Stage("a", "", List.of(unknown)),
                cap("b", "a"), cap("c", "b"));
        require(new BlueprintSet(otherCapacity, quality, 8, 24).validate(KNOWN).size() == 1,
                "an id outside the known set is rejected");
    }

    private static BlueprintSet.Stage cap(String id, String requires) {
        return new BlueprintSet.Stage(id, requires, List.of(step(0, 3, 0)));
    }

    private static BlueprintSet.Stage stageOf(HousingRules.Step... steps) {
        return new BlueprintSet.Stage("probe", "", List.of(steps));
    }

    private static HousingRules.Step step(int x, int y, int z) {
        return new HousingRules.Step(x, y, z, "minecraft:oak_planks");
    }

    private static List<BlueprintSet.Stage> qualities() {
        return List.of(cap("q0", ""), cap("q1", "q0"));
    }

    /** Puts a hand-made capacity chain into an otherwise valid set and returns its problems. */
    private static List<String> valid(List<BlueprintSet.Stage> capacity) {
        return new BlueprintSet(capacity, qualities(), 8, 24).validate(KNOWN);
    }

    /** Problem count for a set whose first capacity stage carries exactly the given steps. */
    private static int problemsWithStage(HousingRules.Step... steps) {
        var capacity = List.of(new BlueprintSet.Stage("a", "", List.of(steps)),
                cap("b", "a"), cap("c", "b"));
        return new BlueprintSet(capacity, qualities(), 8, 24).validate(KNOWN).size();
    }

    private static int reserveProblem(int basic, int expanded) {
        var capacity = List.of(cap("a", ""), cap("b", "a"), cap("c", "b"));
        return new BlueprintSet(capacity, qualities(), basic, expanded).validate(KNOWN).size();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
