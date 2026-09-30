package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.colony.Profession;
import dev.local.goblinsettlement.construction.transport.TransportFacts;
import dev.local.goblinsettlement.construction.transport.TransportLinks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Turns a report into titled sections of translatable rows. Pure: no world, no language, no text. */
public final class NoticeboardText {
    private static final String ROOT = "noticeboard.goblin_settlement.";

    /**
     * How many per-item rows any section shows, as design §4 requires ("上限同为 8"): the same cap the
     * status command puts on the housing report and on the two traffic lines.
     */
    private static final int ROW_LIMIT = 8;

    /**
     * A row has two columns; an empty key means "nothing here" and the screen draws nothing for it. A
     * housing note ("and 3 more homes") is one sentence with no label beside it, so its label column is
     * blank and the sentence, which carries the number, sits in the value column.
     */
    private static final String BLANK = "";

    public record Row(String labelKey, String valueKey, List<String> args) {
    }

    public record Section(String titleKey, List<Row> rows) {
    }

    private NoticeboardText() {
    }

    public static List<Section> sections(Optional<SettlementReport> report) {
        if (report.isEmpty()) {
            return List.of(new Section(ROOT + "title",
                    List.of(new Row(ROOT + "no_settlement", BLANK, List.of()))));
        }
        SettlementReport value = report.get();
        var header = value.header();
        var stock = value.stock();
        var housing = value.housing();
        var sections = new ArrayList<Section>();

        sections.add(new Section(ROOT + "section.population", List.of(
                raw(ROOT + "row.adults", String.valueOf(header.adults())),
                raw(ROOT + "row.children", String.valueOf(header.children())),
                pair(ROOT + "row.slots", String.valueOf(header.occupiedSlots()),
                        String.valueOf(header.maxResidents())),
                pair(ROOT + "row.plots", String.valueOf(header.plots()),
                        String.valueOf(header.maxPlots())),
                raw(ROOT + "row.player_areas", String.valueOf(header.playerAreas())))));

        var foodRows = new ArrayList<Row>();
        foodRows.add(pair(ROOT + "row.food", String.valueOf(stock.food()),
                String.valueOf(stock.foodTarget())));
        // Nobody eats: the forecast has no answer at all, so the value is the design's dash, not a
        // zero. A key with a placeholder must never be sent a missing argument -- that prints the raw
        // pattern on the panel -- so this branch swaps the value key as well.
        foodRows.add(stock.foodMinutes().isPresent()
                ? new Row(ROOT + "row.food_lasts", ROOT + "value.minutes",
                        List.of(String.valueOf(stock.foodMinutes().getAsInt())))
                : new Row(ROOT + "row.food_lasts", ROOT + "value.dash", List.of()));
        foodRows.add(pair(ROOT + "row.seeds", String.valueOf(stock.seeds()),
                String.valueOf(stock.seedTarget())));
        sections.add(new Section(ROOT + "section.food", List.copyOf(foodRows)));

        var homeRows = new ArrayList<Row>();
        homeRows.add(raw(ROOT + "row.beds", String.valueOf(housing.beds())));
        homeRows.add(raw(ROOT + "row.occupied", String.valueOf(housing.occupied())));
        homeRows.add(raw(ROOT + "row.spare", String.valueOf(housing.spare())));
        homeRows.add(raw(ROOT + "row.homeless", String.valueOf(housing.homeless())));
        // Design §9: when the blueprint data is unavailable the panel says what status says, word for
        // word, and invents no second phrasing. The English entry in en_us.json is that sentence.
        if (housing.blueprintUnavailable()) {
            homeRows.add(new Row(BLANK, ROOT + "row.blueprint_unavailable", List.of()));
        }
        for (int index = 0; index < Math.min(ROW_LIMIT, housing.homes().size()); index++) {
            SettlementReport.HomeRow home = housing.homes().get(index);
            var args = new ArrayList<String>();
            args.add(home.bed().toShortString());
            args.add(String.valueOf(home.occupancy()));
            args.add(String.valueOf(home.capacity()));
            if (home.reason().isPresent()) {
                // The reason is a fourth, optional argument, so it needs its own key: the brackets around
                // it are punctuation only this branch has. value.home itself would print a stray "%s".
                args.add(home.reason().orElseThrow());
                homeRows.add(new Row(ROOT + "row.home", ROOT + "value.home_blocked", List.copyOf(args)));
            } else {
                homeRows.add(new Row(ROOT + "row.home", ROOT + "value.home", List.copyOf(args)));
            }
        }
        if (housing.homes().size() > ROW_LIMIT) {
            homeRows.add(note(ROOT + "row.more_homes",
                    String.valueOf(housing.homes().size() - ROW_LIMIT)));
        }
        if (housing.notLoaded() > 0) {
            homeRows.add(note(ROOT + "row.not_loaded", String.valueOf(housing.notLoaded())));
        }
        sections.add(new Section(ROOT + "section.housing", List.copyOf(homeRows)));

        // Built from the facts, never from the command's finished lines. Two rows used to carry
        // `TransportLinks.trafficLine(...)` / `connectivityLine(...)` as a single raw argument, which put
        // the command's English sentence on a Chinese panel and its literal 受阻/缺口 on an English one;
        // design §4.1 wants keys plus raw arguments, so every figure here is a key and every id, count and
        // boolean is an argument.
        var trafficRows = new ArrayList<Row>();
        trafficRows.add(value.nextTarget()
                .map(pos -> raw(ROOT + "row.next_target", pos.toShortString()))
                .orElseGet(() -> new Row(ROOT + "row.next_target", ROOT + "value.none", List.of())));
        var traffic = value.traffic();
        var roads = traffic.roads();
        if (roads.isEmpty()) {
            trafficRows.add(new Row(ROOT + "row.road_traffic", ROOT + "value.road_traffic_none", List.of()));
        } else {
            long ready = roads.stream().filter(TransportFacts.RoadRow::wideningReady).count();
            trafficRows.add(new Row(ROOT + "row.road_traffic", ROOT + "value.road_traffic",
                    List.of(String.valueOf(roads.size()), String.valueOf(ready))));
            for (int index = 0; index < Math.min(ROW_LIMIT, roads.size()); index++) {
                TransportFacts.RoadRow road = roads.get(index);
                // The star is punctuation only the ready branch has, so that branch needs its own row key,
                // the same reason value.home and value.home_blocked are two keys.
                trafficRows.add(new Row(ROOT + "row.road",
                        road.wideningReady() ? ROOT + "value.road_ready" : ROOT + "value.road",
                        List.of(TransportLinks.shortId(road.id()), String.valueOf(road.traffic()),
                                String.valueOf(road.lanes()))));
            }
            if (roads.size() > ROW_LIMIT) {
                trafficRows.add(note(ROOT + "row.more_roads", String.valueOf(roads.size() - ROW_LIMIT)));
            }
        }
        var links = traffic.links();
        if (!traffic.hasSettlement()) {
            trafficRows.add(new Row(ROOT + "row.links", ROOT + "value.links_no_settlement", List.of()));
        } else if (links.loaded() == 0 && links.skipped() == 0) {
            trafficRows.add(new Row(ROOT + "row.links", ROOT + "value.links_none", List.of()));
        } else if (links.brokenLinks().isEmpty()) {
            trafficRows.add(new Row(ROOT + "row.links", ROOT + "value.links_verified",
                    List.of(String.valueOf(links.loaded()))));
        } else {
            trafficRows.add(new Row(ROOT + "row.links", ROOT + "value.links_broken",
                    List.of(String.valueOf(links.brokenLinks().size()),
                            String.valueOf(links.loaded()))));
            for (int index = 0; index < Math.min(ROW_LIMIT, links.brokenLinks().size()); index++) {
                TransportFacts.BrokenLink link = links.brokenLinks().get(index);
                trafficRows.add(new Row(ROOT + "row.link", linkValueKey(link),
                        List.of(TransportLinks.shortId(link.id()))));
            }
            if (links.brokenLinks().size() > ROW_LIMIT) {
                trafficRows.add(note(ROOT + "row.more_links",
                        String.valueOf(links.brokenLinks().size() - ROW_LIMIT)));
            }
        }
        if (links.skipped() > 0) {
            trafficRows.add(note(ROOT + "row.links_not_loaded", String.valueOf(links.skipped())));
        }
        sections.add(new Section(ROOT + "section.traffic", List.copyOf(trafficRows)));

        sections.add(new Section(ROOT + "section.materials", List.of(
                new Row(ROOT + "row.tools", ROOT + "value.tools", List.of(
                        String.valueOf(stock.hoes()), String.valueOf(stock.axes()),
                        String.valueOf(stock.pickaxes()))),
                raw(ROOT + "row.containers", String.valueOf(stock.containers())),
                // "complete"/"incomplete" are two whole values, not arguments to value.raw: sending a
                // key as an argument would print the key itself on the panel.
                new Row(ROOT + "row.stock", stock.complete()
                        ? ROOT + "value.complete" : ROOT + "value.incomplete", List.of()),
                // The priority is an enum, so the panel names a value key rather than shipping the raw
                // constant. Sending Priority.name() as an argument left "FOOD" sitting untranslated in
                // the middle of an otherwise Chinese panel -- seen in game, not in the build.
                new Row(ROOT + "row.priority",
                        ROOT + "value.priority." + stock.priority().name().toLowerCase(java.util.Locale.ROOT),
                        List.of()))));

        var tradeRows = new ArrayList<Row>();
        for (Profession profession : Profession.values()) {
            int held = value.trades().getOrDefault(profession, 0);
            if (held == 0) {
                continue;
            }
            tradeRows.add(raw(ROOT + "row.trade." + profession.name().toLowerCase(Locale.ROOT),
                    String.valueOf(held)));
        }
        sections.add(new Section(ROOT + "section.trades", List.copyOf(tradeRows)));
        return List.copyOf(sections);
    }

    private static Row raw(String labelKey, String value) {
        return new Row(labelKey, ROOT + "value.raw", List.of(value));
    }

    /**
     * Four whole phrases rather than "road"/"bridge" and a reason argument, because the row carries plain
     * strings: the two words the command writes around this fact (受阻/缺口) cannot be handed to the client
     * as an argument and translated there, so each combination is a key of its own.
     */
    private static String linkValueKey(TransportFacts.BrokenLink link) {
        if (link.bridge()) {
            return link.blocked() ? ROOT + "value.link_bridge_blocked" : ROOT + "value.link_bridge_gap";
        }
        return link.blocked() ? ROOT + "value.link_road_blocked" : ROOT + "value.link_road_gap";
    }

    private static Row pair(String labelKey, String first, String second) {
        return new Row(labelKey, ROOT + "value.pair", List.of(first, second));
    }

    /** A whole-line note with no label: the sentence, which carries its number, goes in the value column. */
    private static Row note(String sentenceKey, String count) {
        return new Row(BLANK, sentenceKey, List.of(count));
    }
}
