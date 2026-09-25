package dev.local.goblinsettlement.construction.transport;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
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
}
