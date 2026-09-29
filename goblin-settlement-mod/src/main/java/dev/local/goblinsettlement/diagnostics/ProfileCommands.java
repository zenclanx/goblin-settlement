package dev.local.goblinsettlement.diagnostics;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/** Moderator commands to turn the session-only coordinator profiler on and read its report. */
public final class ProfileCommands {
    private ProfileCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("goblinsettlement")
                .then(Commands.literal("profile")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                        .then(Commands.literal("on").executes(context -> {
                            SettlementProfiler.on();
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Profiler on; previous samples cleared"), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("off").executes(context -> {
                            SettlementProfiler.off();
                            context.getSource().sendSuccess(() -> Component.literal("Profiler off"), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("report").executes(context -> {
                            // The report string itself stays pure; the collection state is a command
                            // concern, so a reader can tell final numbers from a still-running window.
                            String state = SettlementProfiler.enabled()
                                    ? "Profiler: collecting, numbers still accumulating\n"
                                    : "Profiler: stopped, numbers are final\n";
                            context.getSource().sendSuccess(() -> Component.literal(
                                    state + SettlementProfiler.report()), false);
                            return Command.SINGLE_SUCCESS;
                        }))));
    }
}
