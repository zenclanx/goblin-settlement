package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.colony.Profession;
import dev.local.goblinsettlement.construction.transport.TransportFacts;
import dev.local.goblinsettlement.economy.food.FoodForecast;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** Standalone checks for the noticeboard: food forecast, snapshot text, and the panel sections. */
public final class NoticeboardCheck {
    private static final String ROOT = "noticeboard.goblin_settlement.";

    public static void main(String[] args) {
        // Food: one item per resident per active Minecraft day (MealClock.MEAL_INTERVAL_TICKS = 24000
        // = twenty minutes), so whole meals times twenty.
        check(FoodForecast.minutes(0, 0).isEmpty(), "nobody eats means no answer, not zero");
        check(FoodForecast.minutes(40, 4).orElseThrow() == 200, "ten meals at twenty minutes each");
        check(FoodForecast.minutes(39, 4).orElseThrow() == 180, "a partial meal does not count");
        check(FoodForecast.minutes(3, 4).orElseThrow() == 0, "less than one meal is zero minutes");
        check(FoodForecast.minutes(0, 4).orElseThrow() == 0, "no food is zero minutes");
        check(FoodForecast.minutes(4, 4).orElseThrow() == 20, "exactly one meal");
        check(FoodForecast.minutes(Long.MAX_VALUE, 1).orElseThrow() == Integer.MAX_VALUE,
                "an absurd stock clamps instead of overflowing");
        check(negativeDinersRejected(), "negative diners are rejected");

        // The status lines, pinned. A refactor that changes any character of any line fails here.
        check(SettlementText.lines(Optional.empty()).equals(List.of("No settlement in this dimension")),
                "no settlement is one line");
        var sample = new SettlementReport(
                new SettlementReport.Header("s1", new BlockPos(8, 70, -30), 14, 5, 19, 64, 3, 20, 1),
                new SettlementReport.Stock(32, 48, OptionalInt.of(40), 12, 25, 2, 2, 2, 4,
                        true, "READY"),
                new SettlementReport.Housing(18, 19, -1, 1,
                        List.of(new SettlementReport.HomeRow(new BlockPos(10, 64, 10), 2, 3,
                                        Optional.of("oak_planks missing"))),
                        2, false),
                new TransportFacts(true,
                        List.of(new TransportFacts.RoadRow("1a2b3c4d5e", 512, 3, true)),
                        new TransportFacts.Links(3, 1, List.of())),
                Optional.of(new BlockPos(12, 64, -30)),
                Map.of(Profession.FARMER, 3, Profession.UNASSIGNED, 2));
        var lines = SettlementText.lines(Optional.of(sample));
        check(lines.get(0).equals("Settlement s1 at 8, 70, -30, adults=14, children=5, population slots=19/64"
                + ", plots=3/20, player areas=1"), "header line, got: " + lines.get(0));
        check(lines.get(1).equals("Known public stock: food=32/48, wheat seeds=12/25, hoes/axes/pickaxes=2/2/2"
                + ", containers=4, stock complete, next priority=READY"), "stock line, got: " + lines.get(1));
        check(lines.get(2).equals("Housing: beds=18, occupied slots=19, spare=-1, homeless=1"),
                "housing line, got: " + lines.get(2));
        check(lines.get(3).equals("Homes: 10, 64, 10 2/3 [oak_planks missing], 2 not loaded"),
                "homes line, got: " + lines.get(3));
        check(lines.get(4).equals("Next traffic target: 12, 64, -30"),
                "next target line, got: " + lines.get(4));
        check(lines.get(5).equals("Road traffic: 1 road(s), 1 ready to widen (*); 1a2b3c4d=512 L3*"),
                "traffic line, got: " + lines.get(5));
        check(lines.get(6).equals("Links: all 3 loaded verified, 1 not loaded"),
                "connectivity line, got: " + lines.get(6));
        check(lines.get(7).equals("Trades: farmer=3, unassigned=2"), "trades line, got: " + lines.get(7));

        // The empty-target branch, which the sample cannot reach: the prefix lives inside the map, so a
        // report with no pending target prints the fallback alone, with no "Next traffic target:" head.
        var noTarget = new SettlementReport(sample.header(), sample.stock(), sample.housing(),
                sample.traffic(), Optional.empty(), sample.trades());
        var noTargetLine = SettlementText.lines(Optional.of(noTarget)).get(4);
        check(noTargetLine.equals("No pending traffic target"),
                "no pending traffic target line, got: " + noTargetLine);

        // The branches the sample above cannot reach, one report apiece. Each is pinned by a whole-line
        // equals: a silent change to any of them -- the class of defect that already sat in exactly this
        // gap once this round, where the assertions pinned one branch and the bug lived in the other --
        // fails here instead of shipping.

        // An empty settlement: founded, but no residents yet, no homes, no roads, no trades. Reachable
        // today (a fresh `found` has no adults until the camp spawns them), and the one report that
        // reaches "incomplete", the blueprint sentence, "no completed roads", "no finished links yet"
        // and "no adults" at once.
        var empty = new SettlementReport(
                new SettlementReport.Header("s2", new BlockPos(0, 64, 0), 0, 0, 0, 64, 0, 0, 0),
                new SettlementReport.Stock(0, 0, OptionalInt.empty(), 0, 0, 0, 0, 0, 0, false, "READY"),
                new SettlementReport.Housing(0, 0, 0, 0, List.of(), 0, true),
                new TransportFacts(true, List.of(), new TransportFacts.Links(0, 0, List.of())),
                Optional.empty(), Map.of());
        var emptyLines = SettlementText.lines(Optional.of(empty));
        check(emptyLines.get(1).equals("Known public stock: food=0/0, wheat seeds=0/0, hoes/axes/pickaxes=0/0/0"
                + ", containers=0, stock incomplete, next priority=READY"),
                "incomplete stock line, got: " + emptyLines.get(1));
        check(emptyLines.get(3).equals("Homes: nothing can start, the blueprint data is unavailable"),
                "blueprint unavailable line, got: " + emptyLines.get(3));
        check(emptyLines.get(5).equals("Road traffic: no completed roads"),
                "no completed roads line, got: " + emptyLines.get(5));
        check(emptyLines.get(6).equals("Links: no finished links yet"),
                "no finished links line, got: " + emptyLines.get(6));
        check(emptyLines.get(7).equals("Trades: no adults"), "no adults line, got: " + emptyLines.get(7));

        // Blueprints are available again but nothing has been judged: "Homes: none" is a third branch,
        // distinct from the blueprint sentence above, and it carries its own not-loaded count.
        var noHomes = new SettlementReport(empty.header(), empty.stock(),
                new SettlementReport.Housing(0, 0, 0, 0, List.of(), 2, false),
                empty.traffic(), Optional.empty(), Map.of());
        var noHomesLine = SettlementText.lines(Optional.of(noHomes)).get(3);
        check(noHomesLine.equals("Homes: none, 2 not loaded"), "none homes line, got: " + noHomesLine);

        // No settlement: the connectivity line says so instead of counting nothing.
        var unsettled = new SettlementReport(empty.header(), empty.stock(), noHomes.housing(),
                new TransportFacts(false, List.of(), new TransportFacts.Links(0, 0, List.of())),
                Optional.empty(), Map.of());
        var unsettledLine = SettlementText.lines(Optional.of(unsettled)).get(6);
        check(unsettledLine.equals("Links: no settlement in this dimension"),
                "no settlement links line, got: " + unsettledLine);

        // Both list lines at nine entries, so the "+N more" tails are reached, and a non-empty broken-link
        // list, so the not-connected branch and both words -- 受阻 and 缺口 -- are pinned. Alternating
        // road/bridge and blocked/gap covers all four combinations the connectivity line can print.
        var manyHomes = new ArrayList<SettlementReport.HomeRow>();
        for (int index = 0; index < 9; index++) {
            manyHomes.add(new SettlementReport.HomeRow(new BlockPos(index, 64, 0), 1, 3, Optional.empty()));
        }
        var manyRoads = new ArrayList<TransportFacts.RoadRow>();
        for (int index = 0; index < 9; index++) {
            manyRoads.add(new TransportFacts.RoadRow("road" + index, index, 2, false));
        }
        var manyLinks = new ArrayList<TransportFacts.BrokenLink>();
        for (int index = 0; index < 9; index++) {
            manyLinks.add(new TransportFacts.BrokenLink("link" + index, index % 2 == 1, index % 2 == 0));
        }
        var many = new SettlementReport(empty.header(), empty.stock(),
                new SettlementReport.Housing(0, 0, 0, 0, manyHomes, 0, false),
                new TransportFacts(true, manyRoads, new TransportFacts.Links(9, 0, manyLinks)),
                Optional.empty(), Map.of());
        var manyLines = SettlementText.lines(Optional.of(many));
        check(manyLines.get(3).equals("Homes: 0, 64, 0 1/3, 1, 64, 0 1/3, 2, 64, 0 1/3, 3, 64, 0 1/3"
                + ", 4, 64, 0 1/3, 5, 64, 0 1/3, 6, 64, 0 1/3, 7, 64, 0 1/3, +1 more"),
                "the housing report caps at eight, got: " + manyLines.get(3));
        check(manyLines.get(5).equals("Road traffic: 9 road(s), 0 ready to widen (*); road0=0 L2, road1=1 L2"
                + ", road2=2 L2, road3=3 L2, road4=4 L2, road5=5 L2, road6=6 L2, road7=7 L2, +1 more"),
                "the traffic line caps at eight, got: " + manyLines.get(5));
        check(manyLines.get(6).equals("Links: 9 of 9 not connected: link0 road(受阻), link1 bridge(缺口)"
                + ", link2 road(受阻), link3 bridge(缺口), link4 road(受阻), link5 bridge(缺口)"
                + ", link6 road(受阻), link7 bridge(缺口), +1 more"),
                "the connectivity line caps at eight and names both reasons, got: " + manyLines.get(6));

        // The payload codec: write the same samples the text assertions use into a real buffer, read them
        // back, compare. A field written but never read, or read in the wrong order, shows up here. The
        // second sample carries the branches the first cannot reach -- a non-empty broken-link list (so
        // the reason's own encoding is exercised) and an absent next target.
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        SettlementReport.CODEC.encode(buffer, sample);
        check(SettlementReport.CODEC.decode(buffer).equals(sample),
                "a report survives the wire round trip");
        SettlementReport.CODEC.encode(buffer, many);
        check(SettlementReport.CODEC.decode(buffer).equals(many),
                "a report with broken links survives the wire round trip");

        // The panel sections: the presenter turns the same sample into titled rows of translation keys,
        // and the screen only lays them out. The counts below are pinned so a section silently losing or
        // gaining a row fails here.
        var sections = NoticeboardText.sections(Optional.of(sample));
        check(sections.size() == 6, "six sections, got " + sections.size());
        check(sections.get(0).titleKey().equals(ROOT + "section.population"), "population comes first");
        check(sections.get(0).rows().size() == 5, "adults, children, slots, plots, player areas");
        check(sections.get(1).titleKey().equals(ROOT + "section.food"), "food second");
        check(sections.get(1).rows().get(1).valueKey().equals(ROOT + "value.minutes")
                && sections.get(1).rows().get(1).args().equals(List.of("40")),
                "the food forecast is the second row of food");
        // The sample has two homes that could not be judged, so the housing section carries that row as
        // well: beds, occupied, spare, homeless, one home row and the not-loaded note. Blueprints are
        // available here, so the blueprint sentence is not one of them.
        check(sections.get(2).rows().size() == 6, "beds, occupied, spare, homeless, one home row, not loaded");
        check(sections.get(2).rows().get(4).args().contains("oak_planks missing"), "the stuck reason is carried");
        check(sections.get(3).titleKey().equals(ROOT + "section.traffic")
                && sections.get(3).rows().size() == 5, "next target, road summary, one road, verdict, not loaded");
        check(sections.get(5).titleKey().equals(ROOT + "section.trades"), "trades come last");

        // The traffic section is built from the facts, so every figure is a key and every id, count and
        // boolean an argument -- the command's finished sentence never reaches the client, which is what
        // keeps its English words out of a Chinese panel and its literal 受阻/缺口 out of an English one.
        var sampleTraffic = sections.get(3).rows();
        check(sampleTraffic.get(1).valueKey().equals(ROOT + "value.road_traffic")
                && sampleTraffic.get(1).args().equals(List.of("1", "1")), "the road summary carries the counts");
        check(sampleTraffic.get(2).valueKey().equals(ROOT + "value.road_ready")
                && sampleTraffic.get(2).args().equals(List.of("1a2b3c4d", "512", "3")),
                "the road row carries the short id, traffic and lanes");
        check(sampleTraffic.get(3).valueKey().equals(ROOT + "value.links_verified")
                && sampleTraffic.get(3).args().equals(List.of("3")), "the verified verdict carries the count");
        check(sampleTraffic.get(4).valueKey().equals(ROOT + "row.links_not_loaded")
                && sampleTraffic.get(4).args().equals(List.of("1")), "the skipped links are a note of their own");

        // The panel's own empty report: the blueprint sentence is a whole-line note in the housing
        // section, and the two traffic rows fall back to their own keys rather than to blank counts.
        var emptySections = NoticeboardText.sections(Optional.of(empty));
        check(emptySections.get(2).rows().size() == 5, "four counters and the blueprint sentence");
        var blueprintRow = emptySections.get(2).rows().get(4);
        check(blueprintRow.labelKey().isEmpty() && blueprintRow.valueKey().equals(ROOT + "row.blueprint_unavailable")
                && blueprintRow.args().isEmpty(), "the blueprint sentence is a whole-line note");
        check(emptySections.get(3).rows().get(1).valueKey().equals(ROOT + "value.road_traffic_none")
                && emptySections.get(3).rows().get(2).valueKey().equals(ROOT + "value.links_none"),
                "no roads and no links each get their own key");

        // The panel at nine roads and nine broken links: eight rows apiece plus the overflow note, and all
        // four reason keys in use. Row 12 is link0 (road, blocked) and row 13 is link1 (bridge, gap).
        var manyTraffic = NoticeboardText.sections(Optional.of(many)).get(3).rows();
        check(manyTraffic.size() == 21, "traffic rows at nine apiece, got " + manyTraffic.size());
        check(manyTraffic.get(1).valueKey().equals(ROOT + "value.road_traffic")
                && manyTraffic.get(1).args().equals(List.of("9", "0")), "the road summary counts nine");
        check(manyTraffic.get(2).valueKey().equals(ROOT + "value.road")
                && manyTraffic.get(2).args().equals(List.of("road0", "0", "2")),
                "a road that is not ready has no star key");
        check(manyTraffic.get(10).valueKey().equals(ROOT + "row.more_roads")
                && manyTraffic.get(10).args().equals(List.of("1")), "and one more road");
        check(manyTraffic.get(11).valueKey().equals(ROOT + "value.links_broken")
                && manyTraffic.get(11).args().equals(List.of("9", "9")), "the not-connected verdict counts");
        check(manyTraffic.get(12).valueKey().equals(ROOT + "value.link_road_blocked")
                && manyTraffic.get(12).args().equals(List.of("link0")),
                "a blocked road is a key, not the word 受阻");
        check(manyTraffic.get(13).valueKey().equals(ROOT + "value.link_bridge_gap")
                && manyTraffic.get(13).args().equals(List.of("link1")),
                "a gapped bridge is a key, not the word 缺口");
        check(manyTraffic.get(20).valueKey().equals(ROOT + "row.more_links")
                && manyTraffic.get(20).args().equals(List.of("1")), "and one more link");

        System.out.println("NoticeboardCheck passed");
    }

    private static boolean negativeDinersRejected() {
        try {
            FoodForecast.minutes(10, -1);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
