package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.colony.Profession;
import dev.local.goblinsettlement.construction.transport.TransportLinks;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Renders a settlement report the way the status command prints it. Pure: no world, no level. */
public final class SettlementText {
    private static final int HOUSING_REPORT_LIMIT = 8;

    private SettlementText() {
    }

    public static List<String> lines(Optional<SettlementReport> report) {
        if (report.isEmpty()) {
            return List.of("No settlement in this dimension");
        }
        SettlementReport value = report.get();
        var lines = new ArrayList<String>();
        var header = value.header();
        lines.add("Settlement " + header.id() + " at " + header.anchor().toShortString()
                + ", adults=" + header.adults()
                + ", children=" + header.children()
                + ", population slots=" + header.occupiedSlots()
                + "/" + header.maxResidents()
                + ", plots=" + header.plots()
                + "/" + header.maxPlots()
                + ", player areas=" + header.playerAreas());
        var stock = value.stock();
        lines.add("Known public stock: food=" + stock.food() + "/" + stock.foodTarget()
                + ", wheat seeds=" + stock.seeds() + "/" + stock.seedTarget()
                + ", hoes/axes/pickaxes=" + stock.hoes() + "/" + stock.axes() + "/" + stock.pickaxes()
                + ", containers=" + stock.containers()
                + ", stock " + (stock.complete() ? "complete" : "incomplete")
                + ", next priority=" + stock.priority());
        var housing = value.housing();
        lines.add("Housing: beds=" + housing.beds()
                + ", occupied slots=" + housing.occupied()
                + ", spare=" + housing.spare()
                + ", homeless=" + housing.homeless());
        lines.add(homesLine(housing));
        lines.add(value.nextTarget().map(pos -> "Next traffic target: " + pos.toShortString())
                .orElse("No pending traffic target"));
        lines.add(TransportLinks.trafficLine(value.traffic()));
        lines.add(TransportLinks.connectivityLine(value.traffic()));
        lines.add("Trades: " + tradesLine(value.trades()));
        return lines;
    }

    private static String homesLine(SettlementReport.Housing housing) {
        if (housing.blueprintUnavailable()) {
            return "Homes: nothing can start, the blueprint data is unavailable";
        }
        String unloaded = housing.notLoaded() == 0 ? "" : ", " + housing.notLoaded() + " not loaded";
        if (housing.homes().isEmpty()) {
            return "Homes: none" + unloaded;
        }
        var entries = new ArrayList<String>();
        for (SettlementReport.HomeRow home : housing.homes()) {
            entries.add(home.bed().toShortString() + " " + home.occupancy() + "/" + home.capacity()
                    + (home.reason().isPresent() ? " [" + home.reason().orElseThrow() + "]" : ""));
        }
        var builder = new StringBuilder("Homes: ");
        int shown = Math.min(HOUSING_REPORT_LIMIT, entries.size());
        for (int index = 0; index < shown; index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(entries.get(index));
        }
        if (entries.size() > shown) {
            builder.append(", +").append(entries.size() - shown).append(" more");
        }
        return builder.toString() + unloaded;
    }

    private static String tradesLine(Map<Profession, Integer> trades) {
        var builder = new StringBuilder();
        for (Profession profession : Profession.values()) {
            if (profession == Profession.UNASSIGNED) {
                continue;
            }
            long held = trades.getOrDefault(profession, 0);
            if (held == 0) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(profession.name().toLowerCase(Locale.ROOT)).append('=').append(held);
        }
        long unassigned = trades.getOrDefault(Profession.UNASSIGNED, 0);
        if (unassigned > 0) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append("unassigned=").append(unassigned);
        }
        return builder.length() == 0 ? "no adults" : builder.toString();
    }
}
