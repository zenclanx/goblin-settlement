package dev.local.goblinsettlement.construction;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.Container;

/** Development setup for a public container and a two-block plank blueprint. */
public final class ConstructionCommands {
    private ConstructionCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("goblinsettlement")
                .then(Commands.literal("warehouse")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                        .then(Commands.argument("container", BlockPosArgument.blockPos())
                                .executes(context -> {
                                    var source = context.getSource();
                                    ServerLevel level = source.getLevel();
                                    BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "container");
                                    var data = SettlementSavedData.get(level);
                                    var settlement = data.settlement();
                                    if (settlement.isEmpty() || WorldModificationPermission.check(level,
                                            settlement.get().id(), pos) != WorldModificationPermission.Decision.ALLOWED
                                            || !(level.getBlockEntity(pos) instanceof Container)) {
                                        source.sendFailure(Component.literal("Public container must be on active claimed land"));
                                        return 0;
                                    }
                                    if (!data.registerWarehouse(pos)) {
                                        source.sendFailure(Component.literal("Container already registered"));
                                        return 0;
                                    }
                                    source.sendSuccess(() -> Component.literal("Public container registered at "
                                            + pos.toShortString()), true);
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("plan")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                        .then(Commands.argument("start", BlockPosArgument.blockPos())
                                .executes(context -> {
                                    var source = context.getSource();
                                    ServerLevel level = source.getLevel();
                                    BlockPos start = BlockPosArgument.getLoadedBlockPos(context, "start");
                                    var data = SettlementSavedData.get(level);
                                    var settlement = data.settlement();
                                    if (settlement.isEmpty()) {
                                        source.sendFailure(Component.literal("No settlement in this dimension"));
                                        return 0;
                                    }
                                    for (int index = 0; index < ConstructionPlan.LENGTH; index++) {
                                        BlockPos site = start.east(index);
                                        BlockPos below = site.below();
                                        if (WorldModificationPermission.check(level, settlement.get().id(), site)
                                                != WorldModificationPermission.Decision.ALLOWED
                                                || !level.getBlockState(site).isAir()
                                                || !level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                                            source.sendFailure(Component.literal("Both sites need active claimed land, air and solid foundations"));
                                            return 0;
                                        }
                                    }
                                    if (!data.planTwoPlanks(start)) {
                                        source.sendFailure(Component.literal(
                                                "Project overlaps active work or the eight-project limit is reached"));
                                        return 0;
                                    }
                                    source.sendSuccess(() -> Component.literal("Two-block project recorded at "
                                            + start.toShortString()), true);
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("cancel")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                        .executes(context -> {
                            var active = SettlementSavedData.get(context.getSource().getLevel()).plans().stream()
                                    .filter(plan -> !plan.isComplete()).toList();
                            if (active.size() != 1) {
                                context.getSource().sendFailure(Component.literal(
                                        "Specify a project start when there is not exactly one active project"));
                                return 0;
                            }
                            return cancelProject(context.getSource(), active.getFirst().start());
                        })
                        .then(Commands.argument("start", BlockPosArgument.blockPos())
                                .executes(context -> cancelProject(context.getSource(),
                                        BlockPosArgument.getBlockPos(context, "start")))))
                .then(Commands.literal("project")
                        .executes(context -> showProjects(context.getSource(),
                                SettlementSavedData.get(context.getSource().getLevel()).plans()))
                        .then(Commands.argument("start", BlockPosArgument.blockPos())
                                .executes(context -> {
                                    var plan = SettlementSavedData.get(context.getSource().getLevel())
                                            .plan(BlockPosArgument.getBlockPos(context, "start"));
                                    if (plan.isEmpty()) {
                                        context.getSource().sendFailure(Component.literal("No project at that start"));
                                        return 0;
                                    }
                                    return showProjects(context.getSource(), List.of(plan.get()));
                                }))));
    }

    private static int cancelProject(CommandSourceStack source, BlockPos start) {
        ServerLevel level = source.getLevel();
        var data = SettlementSavedData.get(level);
        var plan = data.plan(start);
        if (plan.isEmpty() || plan.get().isComplete() || !data.cancelPlan(start)) {
            source.sendFailure(Component.literal("No active construction project at that start"));
            return 0;
        }
        plan.get().workerId().ifPresent(workerId -> {
            try {
                if (level.getEntity(UUID.fromString(workerId)) instanceof GoblinCitizenEntity goblin) {
                    goblin.applyProjectCancellation(level);
                }
            } catch (IllegalArgumentException ignored) {
                // A malformed old worker reference cannot prevent operator cancellation.
            }
        });
        source.sendSuccess(() -> Component.literal("Project cancelled at " + start.toShortString()
                + "; built blocks and physical materials remain"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int showProjects(CommandSourceStack source, List<ConstructionPlan> plans) {
        if (plans.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No construction project"), false);
            return Command.SINGLE_SUCCESS;
        }
        ServerLevel level = source.getLevel();
        var data = SettlementSavedData.get(level);
        int stock = PublicWarehouseInventory.countOakPlanks(level, data);
        int remaining = plans.stream().filter(plan -> !plan.isComplete())
                .mapToInt(plan -> ConstructionPlan.LENGTH - plan.completed()).sum();
        int carried = plans.stream().mapToInt(plan -> plan.workerId()
                .map(id -> carriedBy(level, id)).orElse(0)).sum();
        int shortage = Math.max(0, remaining - stock - carried);
        source.sendSuccess(() -> Component.literal("Projects=" + plans.size()
                + ", public chest stock=" + stock + ", worker carrying=" + carried
                + ", total still needed=" + shortage), false);
        for (var plan : plans) {
            source.sendSuccess(() -> Component.literal("Project at " + plan.start().toShortString()
                    + " " + plan.completed() + "/" + ConstructionPlan.LENGTH
                    + ", dropped material=" + (plan.recoveryDrop().isPresent() ? "tracked" : "none")
                    + ", worker=" + plan.workerId().orElse("none")
                    + ", last worker=" + plan.lastWorkerId().orElse("none")), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int carriedBy(ServerLevel level, String workerId) {
        try {
            var entity = level.getEntity(UUID.fromString(workerId));
            return entity instanceof GoblinCitizenEntity goblin ? goblin.carriedOakPlanks() : 0;
        } catch (IllegalArgumentException ignored) {
            return 0;
        }
    }
}
