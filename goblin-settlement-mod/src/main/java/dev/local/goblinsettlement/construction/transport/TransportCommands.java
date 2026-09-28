package dev.local.goblinsettlement.construction.transport;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.planning.transport.RoadUpgradeRules;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;

/** Explicit development entry points. Register alongside the existing commands. */
public final class TransportCommands {
    private TransportCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("goblinsettlement")
                .then(Commands.literal("traffic")
                        .then(Commands.literal("status")
                                .executes(context -> showStatus(context.getSource())))
                        .then(Commands.literal("road")
                                .requires(source -> source.permissions().hasPermission(
                                        Permissions.COMMANDS_MODERATOR))
                                .then(Commands.argument("target_facility", BlockPosArgument.blockPos())
                                        .executes(context -> {
                                            CommandSourceStack source = context.getSource();
                                            String id = settlementId(source.getLevel());
                                            if (id == null) {
                                                source.sendFailure(Component.literal("No settlement in this dimension"));
                                                return 0;
                                            }
                                            var result = TransportCoordinator.startRoad(source.getLevel(), id,
                                                    BlockPosArgument.getBlockPos(context, "target_facility"));
                                            return report(source, result);
                                        })))
                        .then(Commands.literal("bridge")
                                .requires(source -> source.permissions().hasPermission(
                                        Permissions.COMMANDS_MODERATOR))
                                .then(Commands.argument("near_bank_foot", BlockPosArgument.blockPos())
                                        .then(Commands.argument("direction", StringArgumentType.word())
                                                .executes(context -> {
                                                    CommandSourceStack source = context.getSource();
                                                    String id = settlementId(source.getLevel());
                                                    if (id == null) {
                                                        source.sendFailure(Component.literal(
                                                                "No settlement in this dimension"));
                                                        return 0;
                                                    }
                                                    Direction direction = horizontalDirection(
                                                            StringArgumentType.getString(context, "direction"));
                                                    if (direction == null) {
                                                        source.sendFailure(Component.literal(
                                                                "Use north, east, south, or west"));
                                                        return 0;
                                                    }
                                                    var result = TransportCoordinator.startWoodBridge(
                                                            source.getLevel(), id,
                                                            BlockPosArgument.getBlockPos(context, "near_bank_foot"),
                                                            direction);
                                                    return report(source, result);
                                                }))))));
    }

    private static String settlementId(ServerLevel level) {
        return SettlementSavedData.get(level).settlement()
                .map(SettlementSavedData.Settlement::id).orElse(null);
    }

    private static Direction horizontalDirection(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "east" -> Direction.EAST;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            default -> null;
        };
    }

    private static int report(CommandSourceStack source, TransportCoordinator.StartResult result) {
        if (!result.accepted()) {
            source.sendFailure(Component.literal("Traffic plan rejected: " + result.reason()));
            return 0;
        }
        TransportPlan plan = result.plan().orElseThrow();
        source.sendSuccess(() -> Component.literal("Traffic plan " + plan.id()
                + " queued, steps=" + plan.steps().size()), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int showStatus(CommandSourceStack source) {
        var plans = TransportSavedData.get(source.getLevel()).plans();
        source.sendSuccess(() -> Component.literal("Traffic plans=" + plans.size()), false);
        var traffic = TransportSavedData.get(source.getLevel());
        var settlement = SettlementSavedData.get(source.getLevel());
        source.sendSuccess(() -> Component.literal("Served facilities=" + traffic.servedFacilities().size()
                + ", " + TrafficProposalCoordinator.nearestUnservedFacility(
                        settlement, traffic, source.getLevel().getGameTime())
                        .map(pos -> "next target=" + pos.toShortString())
                        .orElse("no pending target")), false);
        for (int index = Math.max(0, plans.size() - 8); index < plans.size(); index++) {
            TransportPlan plan = plans.get(index);
            source.sendSuccess(() -> Component.literal(plan.kind() + " " + plan.id()
                    + " " + plan.completedSteps() + "/" + plan.steps().size()
                    + ", open=" + plan.open()
                    + (plan.completedSteps() < plan.steps().size()
                        ? ", next=" + plan.steps().get(plan.completedSteps()).material()
                        : "")), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static final int STATUS_ROAD_LIMIT = 8;

    /**
     * Every finished road's measured traffic, busiest first, with a star on the ones that have earned a
     * widening and the road's current width after its samples. A widened road is one entry, not one per
     * plan: the chain's samples belong to the road, not to the plan that laid a lane.
     */
    public static String trafficLine(TransportSavedData traffic) {
        var roads = traffic.roads();
        if (roads.isEmpty()) {
            return "Road traffic: no completed roads";
        }
        var sorted = new java.util.ArrayList<>(roads);
        sorted.sort((left, right) -> {
            int byCount = Integer.compare(traffic.roadTraffic(right.id()),
                    traffic.roadTraffic(left.id()));
            return byCount != 0 ? byCount : left.id().compareTo(right.id());
        });
        long ready = sorted.stream().filter(road -> qualifies(traffic, road)).count();
        var builder = new StringBuilder("Road traffic: ").append(sorted.size())
                .append(" road(s), ").append(ready).append(" ready to widen (*); ");
        int shown = Math.min(STATUS_ROAD_LIMIT, sorted.size());
        for (int index = 0; index < shown; index++) {
            TransportPlan road = sorted.get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(shortId(road.id()))
                    .append('=').append(traffic.roadTraffic(road.id()))
                    .append(" L").append(traffic.roadWidth(road.id()));
            if (qualifies(traffic, road)) {
                builder.append('*');
            }
        }
        if (sorted.size() > shown) {
            builder.append(", +").append(sorted.size() - shown).append(" more");
        }
        return builder.toString();
    }

    private static final int STATUS_LINK_LIMIT = 8;

    /**
     * Every finished link's connectivity verdict: a road must reach the facility its chain was built
     * for, a bridge must be crossable. Read-only -- it never clears a fence or touches a plan -- and
     * computed on demand, so no inspection cadence and no stored verdict are needed.
     */
    public static String connectivityLine(ServerLevel level) {
        var settlement = SettlementSavedData.get(level);
        if (settlement.settlement().isEmpty()) {
            return "Links: no settlement in this dimension";
        }
        BlockPos anchor = settlement.settlement().orElseThrow().anchor();
        var traffic = TransportSavedData.get(level);
        var broken = new java.util.ArrayList<TransportPlan>();
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
                broken.add(road);
            }
        }
        for (TransportPlan plan : traffic.plans()) {
            if (!plan.isBridge()
                    || plan.completedSteps() != plan.steps().size()) {
                continue;
            }
            if (!loaded(level, List.of(plan))) {
                skipped++;
                continue;
            }
            loaded++;
            if (!TransportCoordinator.bridgeConnects(level, plan, anchor)) {
                broken.add(plan);
            }
        }
        String unloaded = skipped == 0 ? "" : ", " + skipped + " not loaded";
        if (loaded == 0 && skipped == 0) {
            return "Links: no finished links yet";
        }
        if (broken.isEmpty()) {
            return "Links: all " + loaded + " loaded verified" + unloaded;
        }
        broken.sort((left, right) -> left.id().compareTo(right.id()));
        var builder = new StringBuilder("Links: ").append(broken.size()).append(" of ").append(loaded)
                .append(" not connected: ");
        int shown = Math.min(STATUS_LINK_LIMIT, broken.size());
        for (int index = 0; index < shown; index++) {
            TransportPlan plan = broken.get(index);
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(shortId(plan.id())).append(' ')
                    .append(plan.kind() == TransportPlan.Kind.ROAD ? "road" : "bridge")
                    .append('(').append(blockedWord(level, traffic, plan)).append(')');
        }
        if (broken.size() > shown) {
            builder.append(", +").append(broken.size() - shown).append(" more");
        }
        return builder.toString() + unloaded;
    }

    /**
     * Whether every declared walking cell of these plans sits in a ticking chunk. A status command must
     * never force a chunk to load, so a link in an inactive area is counted and left unjudged rather
     * than silently judged from unloaded blocks.
     */
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
}
