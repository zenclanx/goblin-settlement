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
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
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

    // Hand-written rather than StreamCodec.composite: three lists and two Optionals each need their
    // own layer of encoding, so naming every field in order is shorter than the composite machinery and
    // easier to check by eye -- the round-trip assertion catches a field written but never read, or read
    // in the wrong order.
    //
    // The buffer is FriendlyByteBuf, not RegistryFriendlyByteBuf: PayloadTypeRegistry.playS2C() wants a
    // StreamCodec<? super RegistryFriendlyByteBuf, T>, and RegistryFriendlyByteBuf extends
    // FriendlyByteBuf, so the narrower type satisfies it. It also lets the standalone check build a
    // plain buffer, which a RegistryFriendlyByteBuf constructor (needing a RegistryAccess) would not.
    public static final StreamCodec<FriendlyByteBuf, SettlementReport> CODEC =
            StreamCodec.of(SettlementReport::encode, SettlementReport::decode);

    /**
     * The payload carries an Optional, because "this dimension has no settlement" is a real answer the
     * panel has to be able to show, not an error. One boolean in front of the same encoding.
     */
    public static final StreamCodec<FriendlyByteBuf, Optional<SettlementReport>> OPTIONAL_CODEC =
            StreamCodec.of(
                    (buf, value) -> {
                        buf.writeBoolean(value.isPresent());
                        value.ifPresent(present -> encode(buf, present));
                    },
                    buf -> buf.readBoolean() ? Optional.of(decode(buf)) : Optional.empty());

    private static void encode(FriendlyByteBuf buf, SettlementReport report) {
        buf.writeUtf(report.header().id());
        buf.writeVarInt(report.header().anchor().getX());
        buf.writeVarInt(report.header().anchor().getY());
        buf.writeVarInt(report.header().anchor().getZ());
        buf.writeVarInt(report.header().adults());
        buf.writeVarInt(report.header().children());
        buf.writeVarInt(report.header().occupiedSlots());
        buf.writeVarInt(report.header().maxResidents());
        buf.writeVarInt(report.header().plots());
        buf.writeVarInt(report.header().maxPlots());
        buf.writeVarInt(report.header().playerAreas());
        var stock = report.stock();
        buf.writeVarInt(stock.food());
        buf.writeVarInt(stock.foodTarget());
        buf.writeVarInt(stock.foodMinutes().orElse(-1));
        buf.writeVarInt(stock.seeds());
        buf.writeVarInt(stock.seedTarget());
        buf.writeVarInt(stock.hoes());
        buf.writeVarInt(stock.axes());
        buf.writeVarInt(stock.pickaxes());
        buf.writeVarInt(stock.containers());
        buf.writeBoolean(stock.complete());
        buf.writeUtf(stock.priority());
        var housing = report.housing();
        buf.writeVarInt(housing.beds());
        buf.writeVarInt(housing.occupied());
        buf.writeVarInt(housing.spare());
        buf.writeVarInt(housing.homeless());
        buf.writeVarInt(housing.notLoaded());
        buf.writeBoolean(housing.blueprintUnavailable());
        buf.writeVarInt(housing.homes().size());
        for (HomeRow home : housing.homes()) {
            buf.writeVarInt(home.bed().getX());
            buf.writeVarInt(home.bed().getY());
            buf.writeVarInt(home.bed().getZ());
            buf.writeVarInt(home.occupancy());
            buf.writeVarInt(home.capacity());
            buf.writeBoolean(home.reason().isPresent());
            home.reason().ifPresent(buf::writeUtf);
        }
        var traffic = report.traffic();
        buf.writeBoolean(traffic.hasSettlement());
        buf.writeVarInt(traffic.roads().size());
        for (var road : traffic.roads()) {
            buf.writeUtf(road.id());
            buf.writeVarInt(road.traffic());
            buf.writeVarInt(road.lanes());
            buf.writeBoolean(road.wideningReady());
        }
        buf.writeVarInt(traffic.links().loaded());
        buf.writeVarInt(traffic.links().skipped());
        buf.writeVarInt(traffic.links().brokenLinks().size());
        for (var link : traffic.links().brokenLinks()) {
            buf.writeUtf(link.id());
            buf.writeBoolean(link.bridge());
            buf.writeBoolean(link.blocked());
        }
        buf.writeBoolean(report.nextTarget().isPresent());
        report.nextTarget().ifPresent(pos -> {
            buf.writeVarInt(pos.getX());
            buf.writeVarInt(pos.getY());
            buf.writeVarInt(pos.getZ());
        });
        buf.writeVarInt(report.trades().size());
        for (var entry : report.trades().entrySet()) {
            buf.writeUtf(entry.getKey().name());
            buf.writeVarInt(entry.getValue());
        }
    }

    private static SettlementReport decode(FriendlyByteBuf buf) {
        var header = new Header(buf.readUtf(), new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt());
        int food = buf.readVarInt();
        int foodTarget = buf.readVarInt();
        int foodMinutes = buf.readVarInt();
        var stock = new Stock(food, foodTarget,
                foodMinutes < 0 ? OptionalInt.empty() : OptionalInt.of(foodMinutes),
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readBoolean(), buf.readUtf());
        int beds = buf.readVarInt();
        int occupied = buf.readVarInt();
        int spare = buf.readVarInt();
        int homeless = buf.readVarInt();
        int notLoaded = buf.readVarInt();
        boolean unavailable = buf.readBoolean();
        int homeCount = buf.readVarInt();
        var homes = new ArrayList<HomeRow>(homeCount);
        for (int index = 0; index < homeCount; index++) {
            var bed = new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
            int occupancy = buf.readVarInt();
            int capacity = buf.readVarInt();
            var reason = buf.readBoolean() ? Optional.of(buf.readUtf()) : Optional.<String>empty();
            homes.add(new HomeRow(bed, occupancy, capacity, reason));
        }
        boolean hasSettlement = buf.readBoolean();
        int roadCount = buf.readVarInt();
        var roads = new ArrayList<TransportFacts.RoadRow>(roadCount);
        for (int index = 0; index < roadCount; index++) {
            roads.add(new TransportFacts.RoadRow(
                    buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        }
        int loaded = buf.readVarInt();
        int skipped = buf.readVarInt();
        int brokenCount = buf.readVarInt();
        var broken = new ArrayList<TransportFacts.BrokenLink>(brokenCount);
        for (int index = 0; index < brokenCount; index++) {
            broken.add(new TransportFacts.BrokenLink(buf.readUtf(), buf.readBoolean(), buf.readBoolean()));
        }
        var traffic = new TransportFacts(hasSettlement, List.copyOf(roads),
                new TransportFacts.Links(loaded, skipped, List.copyOf(broken)));
        var nextTarget = buf.readBoolean()
                ? Optional.of(new BlockPos(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()))
                : Optional.<BlockPos>empty();
        int tradeCount = buf.readVarInt();
        var trades = new LinkedHashMap<Profession, Integer>();
        for (int index = 0; index < tradeCount; index++) {
            trades.put(Profession.valueOf(buf.readUtf()), buf.readVarInt());
        }
        return new SettlementReport(header, stock,
                new Housing(beds, occupied, spare, homeless, List.copyOf(homes), notLoaded, unavailable),
                traffic, nextTarget, Map.copyOf(trades));
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
