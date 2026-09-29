package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.colony.Profession;
import dev.local.goblinsettlement.construction.transport.TransportFacts;
import dev.local.goblinsettlement.economy.food.FoodForecast;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** Standalone checks for the noticeboard: food forecast, snapshot text, and the panel sections. */
public final class NoticeboardCheck {
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

        // The payload codec: write the same sample the text assertions use into a real buffer, read it
        // back, compare. A field written but never read, or read in the wrong order, shows up here.
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        SettlementReport.CODEC.encode(buffer, sample);
        check(SettlementReport.CODEC.decode(buffer).equals(sample),
                "a report survives the wire round trip");

        // The panel sections: the presenter turns the same sample into titled rows of translation keys,
        // and the screen only lays them out. The counts below are pinned so a section silently losing or
        // gaining a row fails here.
        var sections = NoticeboardText.sections(Optional.of(sample));
        check(sections.size() == 6, "six sections, got " + sections.size());
        check(sections.get(0).titleKey().equals("noticeboard.goblin_settlement.section.population"),
                "population comes first");
        check(sections.get(0).rows().size() == 5, "adults, children, slots, plots, player areas");
        check(sections.get(1).titleKey().equals("noticeboard.goblin_settlement.section.food"), "food second");
        check(sections.get(1).rows().get(1).valueKey().equals("noticeboard.goblin_settlement.value.minutes")
                && sections.get(1).rows().get(1).args().equals(List.of("40")),
                "the food forecast is the second row of food");
        // The sample has two homes that could not be judged, so the housing section carries that row as
        // well: beds, occupied, spare, homeless, one home row and the not-loaded note.
        check(sections.get(2).rows().size() == 6, "beds, occupied, spare, homeless, one home row, not loaded");
        check(sections.get(2).rows().get(4).args().contains("oak_planks missing"), "the stuck reason is carried");
        check(sections.get(3).titleKey().equals("noticeboard.goblin_settlement.section.traffic")
                && sections.get(3).rows().size() == 3, "next target, traffic, links");
        check(sections.get(5).titleKey().equals("noticeboard.goblin_settlement.section.trades"),
                "trades come last");

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
