package dev.local.goblinsettlement.construction;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
                                        source.sendFailure(Component.literal("Container already registered or sixteen-container limit reached"));
                                        return 0;
                                    }
                                    source.sendSuccess(() -> Component.literal("Public container registered at "
                                            + pos.toShortString()), true);
                                    return Command.SINGLE_SUCCESS;
                                })))
                .then(Commands.literal("plan")
                        .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                        .then(Commands.argument("start", BlockPosArgument.blockPos())
                                .then(Commands.argument("material", StringArgumentType.word())
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
                                            var material = materialNamed(StringArgumentType.getString(context, "material"));
                                            if (material.isEmpty()) {
                                                String accepted = String.join(", ", Arrays.stream(BuildMaterial.values())
                                                        .map(entry -> entry.name().toLowerCase(Locale.ROOT)).toList());
                                                source.sendFailure(Component.literal(
                                                        "Unknown material; try one of " + accepted));
                                                return 0;
                                            }
                                            if (!data.planStructure(start, material.orElseThrow())) {
                                                source.sendFailure(Component.literal(
                                                        "Project overlaps active work or the eight-project limit is reached"));
                                                return 0;
                                            }
                                            source.sendSuccess(() -> Component.literal("Two-block "
                                                    + material.orElseThrow().name().toLowerCase(Locale.ROOT)
                                                    + " project recorded at " + start.toShortString()), true);
                                            return Command.SINGLE_SUCCESS;
                                        }))))
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
        var active = plans.stream().filter(plan -> !plan.isComplete()).toList();
        boolean stockIncomplete = !PublicWarehouseInventory.snapshot(level, data).complete();
        source.sendSuccess(() -> Component.literal("Projects=" + plans.size()
                + ", unfinished=" + active.size()), false);
        for (BuildMaterial material : BuildMaterial.values()) {
            int remaining = active.stream().filter(plan -> plan.material() == material)
                    .mapToInt(plan -> ConstructionPlan.LENGTH - plan.completed()).sum();
            if (remaining == 0) {
                continue;
            }
            int stock = PublicWarehouseInventory.countOf(level, data, material.item());
            int carried = 0;
            boolean workersIncomplete = false;
            for (var plan : active) {
                if (plan.material() != material || plan.workerId().isEmpty()) {
                    continue;
                }
                var loadedCarried = carriedBy(level, plan.workerId().orElseThrow());
                if (loadedCarried.isPresent()) {
                    carried += loadedCarried.orElseThrow();
                } else {
                    workersIncomplete = true;
                }
            }
            int shortage = Math.max(0, remaining - stock - carried);
            String stockText = stockIncomplete
                    ? ">=" + stock + " (some warehouses unavailable)" : String.valueOf(stock);
            String carriedText = workersIncomplete
                    ? ">=" + carried + " (some workers unloaded)" : String.valueOf(carried);
            String shortageText = stockIncomplete || workersIncomplete
                    ? "unknown (at most " + shortage + ")" : String.valueOf(shortage);
            String materialName = material.name().toLowerCase(Locale.ROOT);
            source.sendSuccess(() -> Component.literal(materialName + ": still needed=" + shortageText
                    + ", public chest stock=" + stockText + ", worker carrying=" + carriedText), false);
        }
        if (active.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No unfinished construction project"), false);
        }
        for (var plan : plans) {
            source.sendSuccess(() -> Component.literal("Project at " + plan.start().toShortString()
                    + " " + plan.completed() + "/" + ConstructionPlan.LENGTH
                    + " " + plan.material().name().toLowerCase(Locale.ROOT)
                    + ", dropped material=" + (plan.recoveryDrop().isPresent() ? "tracked" : "none")
                    + ", worker=" + plan.workerId().orElse("none")
                    + ", last worker=" + plan.lastWorkerId().orElse("none")), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    /** The material a command word names, or empty when it names nothing this mod can build with. */
    private static Optional<BuildMaterial> materialNamed(String word) {
        for (BuildMaterial material : BuildMaterial.values()) {
            if (material.name().equalsIgnoreCase(word)) {
                return Optional.of(material);
            }
        }
        return Optional.empty();
    }

    private static Optional<Integer> carriedBy(ServerLevel level, String workerId) {
        try {
            var entity = level.getEntity(UUID.fromString(workerId));
            var material = SettlementSavedData.get(level).materialFor(workerId);
            return entity instanceof GoblinCitizenEntity goblin && material.isPresent()
                    ? Optional.of(goblin.carried(material.orElseThrow().item())) : Optional.empty();
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
