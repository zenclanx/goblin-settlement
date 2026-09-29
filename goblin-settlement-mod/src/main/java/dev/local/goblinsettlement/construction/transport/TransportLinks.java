package dev.local.goblinsettlement.construction.transport;

import dev.local.goblinsettlement.planning.transport.RoadUpgradeRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Reads the traffic and link facts out of the world, and renders them. Read-only, never loads a chunk. */
public final class TransportLinks {
    private static final int STATUS_ROAD_LIMIT = 8;
    private static final int STATUS_LINK_LIMIT = 8;

    private TransportLinks() {
    }

    /** One read of everything both status lines need. */
    public static TransportFacts inspect(ServerLevel level, TransportSavedData traffic,
                                         Optional<BlockPos> settlementAnchor) {
        var rows = new ArrayList<TransportFacts.RoadRow>();
        for (TransportPlan road : traffic.roads()) {
            rows.add(new TransportFacts.RoadRow(road.id(), traffic.roadTraffic(road.id()),
                    traffic.roadWidth(road.id()), qualifies(traffic, road)));
        }
        rows.sort((left, right) -> {
            int byCount = Integer.compare(right.traffic(), left.traffic());
            return byCount != 0 ? byCount : left.id().compareTo(right.id());
        });
        return new TransportFacts(settlementAnchor.isPresent(), rows,
                links(level, traffic, settlementAnchor));
    }

    private static TransportFacts.Links links(ServerLevel level, TransportSavedData traffic,
                                              Optional<BlockPos> settlementAnchor) {
        if (settlementAnchor.isEmpty()) {
            return new TransportFacts.Links(0, 0, List.of());
        }
        BlockPos anchor = settlementAnchor.get();
        var broken = new ArrayList<TransportFacts.BrokenLink>();
        int loaded = 0;
        int skipped = 0;
        for (TransportPlan road : traffic.roads()) {
            if (!loaded(level, traffic.chainOf(road.id()))) {
                skipped++;
                continue;
            }
            loaded++;
            if (!TransportCoordinator.roadConnects(level, traffic, road, anchor)
                    || !chainIntact(level, traffic, road)) {
                broken.add(new TransportFacts.BrokenLink(road.id(), false,
                        blockedWord(level, traffic, road)));
            }
        }
        for (TransportPlan plan : traffic.plans()) {
            if (!plan.isBridge() || plan.completedSteps() != plan.steps().size()) {
                continue;
            }
            if (!loaded(level, List.of(plan))) {
                skipped++;
                continue;
            }
            loaded++;
            if (!TransportCoordinator.bridgeConnects(level, plan, anchor)) {
                broken.add(new TransportFacts.BrokenLink(plan.id(), true,
                        blockedWord(level, traffic, plan)));
            }
        }
        broken.sort((left, right) -> left.id().compareTo(right.id()));
        return new TransportFacts.Links(loaded, skipped, broken);
    }

    /** Whether every declared walking cell of these plans sits in a ticking chunk. */
    private static boolean loaded(ServerLevel level, List<TransportPlan> plans) {
        for (TransportPlan plan : plans) {
            for (TransportPlan.Step step : plan.steps()) {
                if (step.phase() == TransportPlan.Phase.BARRIERS) {
                    continue;
                }
                if (!level.shouldTickBlocksAt(step.site().above())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 缺口 = a declared cell is missing and the rebuild path will fix it; 受阻 = something foreign is in the way. */
    private static String blockedWord(ServerLevel level, TransportSavedData traffic, TransportPlan plan) {
        return chainIntact(level, traffic, plan) ? "受阻" : "缺口";
    }

    /** Every member of the road must still be structurally whole; a chain is one road. */
    private static boolean chainIntact(ServerLevel level, TransportSavedData traffic, TransportPlan plan) {
        for (TransportPlan member : traffic.chainOf(plan.id())) {
            if (!TransportCoordinator.structurallyComplete(level, member)) {
                return false;
            }
        }
        return true;
    }

    private static boolean qualifies(TransportSavedData traffic, TransportPlan road) {
        return RoadUpgradeRules.shouldUpgrade(traffic.roadWidth(road.id()),
                traffic.roadTraffic(road.id()));
    }

    private static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    /**
     * Every finished road's measured traffic, busiest first, with a star on the ones that have earned a
     * widening and the road's current width after its samples.
     */
    public static String trafficLine(TransportFacts facts) {
        var roads = facts.roads();
        if (roads.isEmpty()) {
            return "Road traffic: no completed roads";
        }
        long ready = roads.stream().filter(TransportFacts.RoadRow::wideningReady).count();
        var builder = new StringBuilder("Road traffic: ").append(roads.size())
                .append(" road(s), ").append(ready).append(" ready to widen (*); ");
        int shown = Math.min(STATUS_ROAD_LIMIT, roads.size());
        for (int index = 0; index < shown; index++) {
            TransportFacts.RoadRow road = roads.get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(shortId(road.id()))
                    .append('=').append(road.traffic())
                    .append(" L").append(road.lanes());
            if (road.wideningReady()) {
                builder.append('*');
            }
        }
        if (roads.size() > shown) {
            builder.append(", +").append(roads.size() - shown).append(" more");
        }
        return builder.toString();
    }

    /**
     * Every finished link's connectivity verdict: a road must reach the facility its chain was built
     * for, a bridge must be crossable.
     */
    public static String connectivityLine(TransportFacts facts) {
        if (!facts.hasSettlement()) {
            return "Links: no settlement in this dimension";
        }
        var links = facts.links();
        String unloaded = links.skipped() == 0 ? "" : ", " + links.skipped() + " not loaded";
        if (links.loaded() == 0 && links.skipped() == 0) {
            return "Links: no finished links yet";
        }
        if (links.brokenLinks().isEmpty()) {
            return "Links: all " + links.loaded() + " loaded verified" + unloaded;
        }
        var builder = new StringBuilder("Links: ").append(links.brokenLinks().size())
                .append(" of ").append(links.loaded()).append(" not connected: ");
        int shown = Math.min(STATUS_LINK_LIMIT, links.brokenLinks().size());
        for (int index = 0; index < shown; index++) {
            TransportFacts.BrokenLink link = links.brokenLinks().get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(shortId(link.id())).append(' ')
                    .append(link.bridge() ? "bridge" : "road")
                    .append('(').append(link.blocked()).append(')');
        }
        if (links.brokenLinks().size() > shown) {
            builder.append(", +").append(links.brokenLinks().size() - shown).append(" more");
        }
        return builder.toString() + unloaded;
    }
}
