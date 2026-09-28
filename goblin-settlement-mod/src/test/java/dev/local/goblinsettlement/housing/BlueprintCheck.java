package dev.local.goblinsettlement.housing;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class BlueprintCheck {
    private static final String RESOURCE =
            "/data/goblin_settlement/housing_blueprints.json";
    private static final Set<String> KNOWN =
            Set.of("minecraft:oak_planks", "minecraft:stone");

    public static void main(String[] args) throws Exception {
        BlueprintSet data = loadRealFile();
        checkRealFile(data);
        checkEveryStyleIsBuildable(data);
        checkSteps(data);
        checkFirstUnbuilt(data);
        checkCodecRoundTrip();
        checkChainRules();
        checkEntrance();
        checkBounds();
        checkBlocksAreNotRestricted();
        checkCellsUsedByABlueprint(data);
        checkBedHeadroomStaysFree(data);
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
        require(data.styles().size() >= 3,
                "the shipped file carries the cottage, the lean-to and the side porch");
        require(data.styles().get(0).id().equals("cottage"),
                "style 0 is the cottage, so old saves keep the geometry they were built with");
        require(data.styles().stream().map(BlueprintSet.Style::id).distinct().count()
                        == data.styles().size(),
                "style ids are unique");
    }

    private static void checkEveryStyleIsBuildable(BlueprintSet data) {
        for (int index = 0; index < data.styles().size(); index++) {
            var style = data.styles().get(index);
            require(style.capacity().size() == HousingRules.MAX_CAPACITY_TARGET + 1,
                    style.id() + " has a full capacity ladder");
            require(style.quality().size() == HousingRules.MAX_QUALITY_TARGET,
                    style.id() + " has a full quality ladder");
            require(!data.steps(index, 0, 0).isEmpty(), style.id() + " builds something at level 0");
            // The levels are additive and the cottage deliberately re-covers its roof at each
            // level, so a cell may repeat -- but only with the same block. Two stages claiming one
            // cell with different blocks would leave a step that can never be placed, because the
            // first one to run fills the cell the second one needs empty.
            var blocks = new HashMap<Long, String>();
            for (var step : data.steps(index, 2, 2)) {
                require(step.x() >= -3 && step.x() <= 3 && step.z() >= -2 && step.z() <= 2
                                && step.y() >= 0 && step.y() <= 4,
                        style.id() + " stays inside the blueprint box");
                long key = ((long) (step.x() + 64) << 42) ^ ((long) (step.y() + 64) << 21)
                        ^ (step.z() + 64);
                String previous = blocks.putIfAbsent(key, step.block());
                require(previous == null || previous.equals(step.block()),
                        style.id() + " claims " + step.x() + "," + step.y() + "," + step.z()
                                + " with both " + previous + " and " + step.block());
            }
        }
    }

    private static void checkSteps(BlueprintSet data) {
        require(data.steps(0, 0, 0).size() == 21, "shelter only");
        require(data.steps(0, 1, 0).size() == 92, "shelter plus cabin");
        require(data.steps(0, 2, 0).size() == 102, "all three capacity stages");
        require(data.steps(0, 2, 1).size() == 111, "capacity plus the first quality stage");
        require(data.steps(0, 2, 2).size() == 121, "everything");
        require(data.steps(0, 1, 1).equals(data.steps(0, 1, 1)),
                "the same targets always compose the same list");
        for (var step : data.steps(0, 2, 2)) {
            require(step.x() >= -3 && step.x() <= 3 && step.z() >= -2 && step.z() <= 2
                    && step.y() >= 0 && step.y() <= 4, "every step stays inside the blueprint box");
            require(KNOWN.contains(step.block()), "every step names a known block");
        }
        boolean threw = false;
        try {
            data.steps(0, 3, 0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        require(threw, "targets beyond the ladder are rejected");
    }

    private static void checkFirstUnbuilt(BlueprintSet data) {
        List<HousingRules.Step> shelter = data.steps(0, 0, 0);
        require(HousingRules.firstUnbuilt(shelter, Set.of()).orElseThrow() == 0,
                "an untouched home starts at the first step");
        Set<HousingRules.Step> half = new HashSet<>(shelter.subList(0, 5));
        require(HousingRules.firstUnbuilt(shelter, half).orElseThrow() == 5,
                "built leading steps are skipped");
        require(HousingRules.firstUnbuilt(shelter, new HashSet<>(shelter)).isEmpty(),
                "a fully built home has nothing left");
        Set<HousingRules.Step> builtEarly = new HashSet<>(data.steps(0, 1, 1));
        int next = HousingRules.firstUnbuilt(data.steps(0, 2, 1), builtEarly).orElseThrow();
        require(next == 92, "raising the capacity target after quality work finds the inserted steps");
        require(data.steps(0, 2, 1).get(next).equals(data.steps(0, 2, 0).get(21 + 71)),
                "the step found is the first expanded-stage step");
    }

    private static void checkCodecRoundTrip() {
        var json = JsonParser.parseString("""
                {"reserve_basic": 8, "reserve_expanded": 24,
                 "styles": [
                   {"id": "a", "capacity": [{"id": "s0", "steps": [{"x": 1, "y": 0, "z": -1, "block": "minecraft:oak_planks"}]}],
                              "quality": [{"id": "q0", "steps": []}]},
                   {"id": "b", "capacity": [{"id": "s0", "requires": "s0", "steps": []}],
                              "quality": [{"id": "q0", "steps": []}]}]}
                """);
        BlueprintSet parsed = BlueprintSet.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        require(parsed.styles().size() == 2, "every style decodes");
        require(parsed.styles().get(0).id().equals("a"), "style order is preserved");
        require(parsed.styles().get(0).capacity().get(0).requires().isEmpty(),
                "an absent requires decodes to empty");
        require(parsed.styles().get(1).capacity().get(0).requires().equals("s0"),
                "a present requires survives");
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

    private static void checkBlocksAreNotRestricted() {
        // Materials are general now: any known block with an item form is allowed.
        var stone = new HousingRules.Step(0, 3, 0, "minecraft:stone");
        var capacity = List.of(new BlueprintSet.Stage("a", "", List.of(stone)),
                cap("b", "a"), cap("c", "b"));
        var accepted = valid(capacity);
        require(accepted.isEmpty(), "a known block other than oak planks is accepted: " + accepted);

        var unknown = new HousingRules.Step(0, 3, 0, "minecraft:not_a_block");
        var otherCapacity = List.of(new BlueprintSet.Stage("a", "", List.of(unknown)),
                cap("b", "a"), cap("c", "b"));
        require(valid(otherCapacity).size() == 1, "an id outside the known set is still rejected");
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

    /** Wraps a hand-made capacity chain into an otherwise valid single-style set. */
    private static BlueprintSet setOf(List<BlueprintSet.Stage> capacity) {
        return setOf(capacity, 8, 24);
    }

    private static BlueprintSet setOf(List<BlueprintSet.Stage> capacity, int basic, int expanded) {
        return new BlueprintSet(
                List.of(new BlueprintSet.Style("probe", capacity, qualities())), basic, expanded);
    }

    /** Puts a hand-made capacity chain into an otherwise valid set and returns its problems. */
    private static List<String> valid(List<BlueprintSet.Stage> capacity) {
        return setOf(capacity).validate(KNOWN);
    }

    /** Problem count for a set whose first capacity stage carries exactly the given steps. */
    private static int problemsWithStage(HousingRules.Step... steps) {
        var capacity = List.of(new BlueprintSet.Stage("a", "", List.of(steps)),
                cap("b", "a"), cap("c", "b"));
        return setOf(capacity).validate(KNOWN).size();
    }

    private static int reserveProblem(int basic, int expanded) {
        var capacity = List.of(cap("a", ""), cap("b", "a"), cap("c", "b"));
        return setOf(capacity, basic, expanded).validate(KNOWN).size();
    }

    /** The rule the bed placement asks: does any stage of this style put a block on this cell? */
    private static void checkCellsUsedByABlueprint(BlueprintSet data) {
        for (int style = 0; style < data.styleCount(); style++) {
            // Every chain of every style, so a regression that ignored the quality chain -- or any
            // other single stage -- is caught, not only a change to the first capacity stage.
            var stages = data.stages(style);
            for (int chain = 0; chain < stages.size(); chain++) {
                for (HousingRules.Step step : stages.get(chain)) {
                    require(data.reserved(style, step.x(), step.y(), step.z()),
                            "a cell a chain builds on is reserved (style " + style
                                    + ", chain " + chain + ")");
                }
            }
            require(!data.reserved(style, 0, 99, 0),
                    "a cell far above the box is not reserved (style " + style + ")");
            require(!data.reserved(style, 12, 0, 12),
                    "a cell far outside the box is not reserved (style " + style + ")");
        }
        // A cell that is reserved in style 0: the out-of-range style must still say "reserved", which
        // pins that it degrades to the first style rather than merely not throwing.
        HousingRules.Step probe = data.stages(0).get(0).get(0);
        require(data.reserved(999, probe.x(), probe.y(), probe.z()),
                "an out-of-range style degrades to the first style rather than throwing");
    }

    /**
     * The bed stands on the anchor, so the anchor's own headroom must stay clear: a block there would
     * make that first bed unusable, and a stage may never build it. Hand-checked when the styles were
     * written; pinned here so changing the geometry cannot quietly break the bed.
     */
    private static void checkBedHeadroomStaysFree(BlueprintSet data) {
        for (int style = 0; style < data.styleCount(); style++) {
            require(!data.reserved(style, 0, 1, 0), "the cell above the bed head stays clear (style " + style + ")");
            require(!data.reserved(style, 0, 2, 0), "and the one above it too (style " + style + ")");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
