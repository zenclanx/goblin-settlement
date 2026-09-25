package dev.local.goblinsettlement.forestry;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;

/** Administrator marks a specific oak tree before residents may fell it. */
public final class ForestryCommands {
    private ForestryCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("goblinsettlement")
                .then(Commands.literal("tree")
                        .then(Commands.literal("status").executes(context -> {
                            var source = context.getSource();
                            var settlement = SettlementSavedData.get(source.getLevel()).settlement();
                            if (settlement.isEmpty()) {
                                source.sendFailure(Component.literal("No settlement in this dimension"));
                                return 0;
                            }
                            var trees = ForestrySavedData.get(source.getLevel()).markedTrees().stream()
                                    .filter(tree -> tree.settlementId().equals(settlement.get().id())).toList();
                            source.sendSuccess(() -> Component.literal("Marked oak trees=" + trees.size()), false);
                            for (var tree : trees) {
                                source.sendSuccess(() -> Component.literal(
                                        "Oak root at " + tree.root().toShortString()), false);
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("add")
                                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                                .then(Commands.argument("root", BlockPosArgument.blockPos())
                                        .executes(context -> {
                                            var source = context.getSource();
                                            ServerLevel level = source.getLevel();
                                            var root = BlockPosArgument.getLoadedBlockPos(context, "root");
                                            var settlement = SettlementSavedData.get(level).settlement();
                                            if (settlement.isEmpty()
                                                    || !ForestryCoordinator.mayMarkTree(level,
                                                            settlement.get().id(), root)) {
                                                source.sendFailure(Component.literal(
                                                        "Mark only an active claimed oak trunk on soil with oak leaves"));
                                                return 0;
                                            }
                                            if (!ForestrySavedData.get(level).register(settlement.get().id(), root)) {
                                                source.sendFailure(Component.literal(
                                                        "Tree already marked or the sixteen-tree limit is reached"));
                                                return 0;
                                            }
                                            source.sendSuccess(() -> Component.literal(
                                                    "Marked oak root at " + root.toShortString()), true);
                                            return Command.SINGLE_SUCCESS;
                                        })))
                        .then(Commands.literal("remove")
                                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                                .then(Commands.argument("root", BlockPosArgument.blockPos())
                                        .executes(context -> {
                                            var source = context.getSource();
                                            var root = BlockPosArgument.getLoadedBlockPos(context, "root");
                                            var settlement = SettlementSavedData.get(source.getLevel()).settlement();
                                            if (settlement.isEmpty()
                                                    || !ForestrySavedData.get(source.getLevel())
                                                    .remove(settlement.get().id(), root)) {
                                                source.sendFailure(Component.literal("Tree mark not found"));
                                                return 0;
                                            }
                                            source.sendSuccess(() -> Component.literal(
                                                    "Removed oak tree mark at " + root.toShortString()), true);
                                            return Command.SINGLE_SUCCESS;
                                        })))));
    }
}