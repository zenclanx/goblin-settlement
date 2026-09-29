package dev.local.goblinsettlement.noticeboard;

import dev.local.goblinsettlement.colony.PopulationRules;
import dev.local.goblinsettlement.colony.Profession;
import dev.local.goblinsettlement.colony.SettlementDemand;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.construction.transport.TrafficProposalCoordinator;
import dev.local.goblinsettlement.construction.transport.TransportFacts;
import dev.local.goblinsettlement.construction.transport.TransportLinks;
import dev.local.goblinsettlement.construction.transport.TransportSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.economy.food.FoodForecast;
import dev.local.goblinsettlement.housing.BedCensus;
import dev.local.goblinsettlement.housing.HousingAssignmentCoordinator;
import dev.local.goblinsettlement.housing.HousingBlueprints;
import dev.local.goblinsettlement.housing.HousingCoordinator;
import dev.local.goblinsettlement.housing.HousingSavedData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * One settlement's state, as facts rather than finished text. Two presenters render it -- the status
 * command and the noticeboard panel -- so a new figure is added once and both show it.
 */
public record SettlementReport(Header header, Stock stock, Housing housing, TransportFacts traffic,
                               Optional<BlockPos> nextTarget, Map<Profession, Integer> trades) {

    /** Who lives here and how much room is claimed for them. */
    public record Header(String id, BlockPos anchor, int adults, int children, int occupiedSlots,
                         int maxResidents, int plots, int maxPlots, int playerAreas) {
    }

    /** The known public stock. foodMinutes is empty when nobody eats; the counts are a lower bound. */
    public record Stock(int food, int foodTarget, OptionalInt foodMinutes, int seeds,
                        int seedTarget, int hoes, int axes, int pickaxes, int containers,
                        boolean complete, String priority) {
    }

    /** Beds, occupancy, and one row per judged home with the reason it cannot advance. */
    public record Housing(int beds, int occupied, int spare, int homeless, List<HomeRow> homes,
                          int notLoaded, boolean blueprintUnavailable) {
    }

    public record HomeRow(BlockPos bed, int occupancy, int capacity, Optional<String> reason) {
    }

    /** One read of everything both presenters need. Empty means this dimension has no settlement. */
    public static Optional<SettlementReport> snapshot(ServerLevel level) {
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return Optional.empty();
        }
        var value = settlement.get();
        var supply = PublicWarehouseInventory.snapshot(level, data);
        var trafficData = TransportSavedData.get(level);
        var demand = SettlementDemand.assess(data.adultCount(), data.childCount(), supply,
                data.plans().stream().anyMatch(plan -> !plan.isComplete()),
                TrafficProposalCoordinator.hasPendingTarget(data, trafficData, level.getGameTime()),
                BedCensus.shortage(level, data));
        int diners = data.adultCount() + data.childCount();
        var header = new Header(value.id(), value.anchor(), data.adultCount(), data.childCount(),
                data.occupiedPopulationSlots(), PopulationRules.MAX_RESIDENTS,
                data.claimedPlots().size(), PopulationRules.maximumPlots(data.adultCount()),
                data.playerAreas().size());
        var stock = new Stock(supply.food(), (int) demand.foodTarget(),
                FoodForecast.minutes(supply.food(), diners),
                supply.wheatSeeds(), (int) demand.seedTarget(), supply.hoes(), supply.axes(),
                supply.pickaxes(), supply.accessibleContainers(), supply.complete(),
                demand.priority().name());
        var nextTarget = TrafficProposalCoordinator.nearestUnservedFacility(
                data, trafficData, level.getGameTime());
        var traffic = TransportLinks.inspect(level, trafficData, Optional.of(value.anchor()));
        var trades = new LinkedHashMap<Profession, Integer>();
        for (Profession profession : Profession.values()) {
            trades.put(profession, 0);
        }
        for (Profession profession : data.assignedProfessions()) {
            trades.merge(profession, 1, Integer::sum);
        }
        return Optional.of(new SettlementReport(header, stock, housing(level, data, value.id()),
                traffic, nextTarget, Map.copyOf(trades)));
    }

    private static Housing housing(ServerLevel level, SettlementSavedData data, String id) {
        int beds = BedCensus.count(level, data);
        int occupied = data.occupiedPopulationSlots();
        boolean unavailable = !HousingBlueprints.available();
        var rows = new ArrayList<HomeRow>();
        int notLoaded = 0;
        if (!unavailable) {
            var homes = new ArrayList<>(HousingSavedData.get(level).homes(id));
            homes.sort((left, right) -> {
                int byX = Integer.compare(left.bed().getX(), right.bed().getX());
                if (byX != 0) {
                    return byX;
                }
                int byZ = Integer.compare(left.bed().getZ(), right.bed().getZ());
                return byZ != 0 ? byZ : Integer.compare(left.bed().getY(), right.bed().getY());
            });
            for (var home : homes) {
                if (!HousingAssignmentCoordinator.canJudge(level, home.bed())) {
                    notLoaded++;
                    continue;
                }
                rows.add(new HomeRow(home.bed(),
                        HousingAssignmentCoordinator.occupancy(data, home.bed()),
                        HousingCoordinator.homeCapacity(level, home),
                        HousingCoordinator.blockedReason(level, data, home, id)));
            }
        }
        int homeless = HousingAssignmentCoordinator.homeless(level, id, data);
        return new Housing(beds, occupied, beds - occupied, homeless, List.copyOf(rows),
                notLoaded, unavailable);
    }
}
