package dev.local.goblinsettlement.economy;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/** Lets an operator remove a destroyed or revoked public warehouse registration. */
public final class WarehouseRecoveryCommands {
    private WarehouseRecoveryCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("goblinsettlement")
                .then(Commands.literal("warehouse")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("container", BlockPosArgument.blockPos())
                                        .executes(context -> {
                                            var pos = BlockPosArgument.getBlockPos(context, "container");
                                            var data = SettlementSavedData.get(context.getSource().getLevel());
                                            if (!data.unregisterWarehouse(pos)) {
                                                context.getSource().sendFailure(Component.literal(
                                                        "No registered public container at that position"));
                                                return 0;
                                            }
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    "Public container registration removed at "
                                                            + pos.toShortString()), true);
                                            return Command.SINGLE_SUCCESS;
                                        })))));
    }
}
