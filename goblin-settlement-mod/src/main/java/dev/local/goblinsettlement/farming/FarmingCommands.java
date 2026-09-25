package dev.local.goblinsettlement.farming;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.Blocks;

/** Registers wheat cells on claimed, active farmland. */
public final class FarmingCommands {
    private FarmingCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("goblinsettlement")
                .then(Commands.literal("farm")
                        .then(Commands.literal("status").executes(context -> {
                            var sites = SettlementSavedData.get(context.getSource().getLevel()).farmSites();
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Registered wheat cells=" + sites.size()), false);
                            for (var site : sites) {
                                context.getSource().sendSuccess(() -> Component.literal(
                                        "Wheat cell at " + site.cropPos().toShortString()
                                                + ", worker=" + site.workerId().orElse("none")), false);
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("add")
                                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                                .then(Commands.argument("crop", BlockPosArgument.blockPos())
                                        .executes(context -> {
                                            var source = context.getSource();
                                            ServerLevel level = source.getLevel();
                                            var crop = BlockPosArgument.getLoadedBlockPos(context, "crop");
                                            var data = SettlementSavedData.get(level);
                                            var settlement = data.settlement();
                                            if (settlement.isEmpty()
                                                    || WorldModificationPermission.check(level, settlement.get().id(), crop)
                                                    != WorldModificationPermission.Decision.ALLOWED
                                                    || WorldModificationPermission.check(level, settlement.get().id(), crop.below())
                                                    != WorldModificationPermission.Decision.ALLOWED
                                                    || !level.getBlockState(crop.below()).is(Blocks.FARMLAND)
                                                    || !(level.getBlockState(crop).isAir()
                                                    || level.getBlockState(crop).is(Blocks.WHEAT))) {
                                                source.sendFailure(Component.literal(
                                                        "Wheat cell needs active claimed farmland with air or wheat above it"));
                                                return 0;
                                            }
                                            if (!data.registerFarmSite(crop)) {
                                                source.sendFailure(Component.literal(
                                                        "Wheat cell already registered, overlaps construction, or farm limit reached"));
                                                return 0;
                                            }
                                            source.sendSuccess(() -> Component.literal(
                                                    "Wheat cell registered at " + crop.toShortString()), true);
                                            return Command.SINGLE_SUCCESS;
                                        })))));
    }
}
