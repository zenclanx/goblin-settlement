package dev.local.goblinsettlement;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.colony.PopulationRules;
import dev.local.goblinsettlement.colony.SettlementDemand;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.construction.ConstructionCommands;
import dev.local.goblinsettlement.construction.ConstructionCoordinator;
import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.farming.FarmingCommands;
import dev.local.goblinsettlement.farming.FarmingCoordinator;
import dev.local.goblinsettlement.citizen.ModEntities;
import dev.local.goblinsettlement.interaction.ProtectedRectangle;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.Container;
import net.minecraft.world.phys.AABB;
import java.util.Comparator;

public final class GoblinSettlement implements ModInitializer {
    public static final String MOD_ID = "goblin_settlement";

    @Override
    public void onInitialize() {
        ModEntities.initialize();
        ServerTickEvents.END_WORLD_TICK.register(ConstructionCoordinator::tick);
        ServerTickEvents.END_WORLD_TICK.register(FarmingCoordinator::tick);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ConstructionCommands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                FarmingCommands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("goblinsettlement")
                        .then(Commands.literal("status").executes(context -> {
                            ServerLevel level = context.getSource().getLevel();
                            var data = SettlementSavedData.get(level);
                            var settlement = data.settlement();
                            context.getSource().sendSuccess(() -> Component.literal(settlement
                                    .map(value -> "Settlement " + value.id() + " at " + value.anchor().toShortString()
                                            + ", adults=" + data.adultCount()
                                            + ", children=" + data.childCount()
                                            + ", population slots=" + data.occupiedPopulationSlots()
                                            + "/" + PopulationRules.MAX_RESIDENTS
                                            + ", plots=" + data.claimedPlots().size()
                                            + "/" + PopulationRules.maximumPlots(data.adultCount())
                                            + ", player areas=" + data.playerAreas().size())
                                    .orElse("No settlement in this dimension")), false);
                            if (settlement.isPresent()) {
                                var supply = PublicWarehouseInventory.snapshot(level, data);
                                var demand = SettlementDemand.assess(data.adultCount(), data.childCount(),
                                        supply, data.plans().stream().anyMatch(plan -> !plan.isComplete()));
                                context.getSource().sendSuccess(() -> Component.literal("Known public stock: food="
                                        + supply.food() + "/" + demand.foodTarget()
                                        + ", wheat seeds=" + supply.wheatSeeds() + "/" + demand.seedTarget()
                                        + ", hoes/axes/pickaxes=" + supply.hoes() + "/" + supply.axes()
                                        + "/" + supply.pickaxes()
                                        + ", containers=" + supply.accessibleContainers()
                                        + ", stock " + (supply.complete() ? "complete" : "incomplete")
                                        + ", next priority=" + demand.priority()), false);
                            }
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("found")
                                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                                .executes(context -> {
                                    ServerLevel level = context.getSource().getLevel();
                                    var data = SettlementSavedData.get(level);
                                    var result = data.found(BlockPos.containing(context.getSource().getPosition()));
                                    switch (result) {
                                        case ALREADY_EXISTS -> {
                                            context.getSource().sendFailure(Component.literal("A settlement already exists in this dimension"));
                                            return 0;
                                        }
                                        case PLAYER_AREA_CONFLICT -> {
                                            context.getSource().sendFailure(Component.literal("The starting plot overlaps a protected player area"));
                                            return 0;
                                        }
                                        case FOUNDED -> {
                                            context.getSource().sendSuccess(() -> Component.literal("Settlement founded with one starting plot"), true);
                                            return Command.SINGLE_SUCCESS;
                                        }
                                    }
                                    return 0;
                                }))
                        .then(Commands.literal("check").executes(context -> {
                            var source = context.getSource();
                            var settlement = SettlementSavedData.get(source.getLevel()).settlement();
                            if (settlement.isEmpty()) {
                                source.sendFailure(Component.literal("No settlement in this dimension"));
                                return 0;
                            }
                            var decision = WorldModificationPermission.check(source.getLevel(),
                                    settlement.get().id(), BlockPos.containing(source.getPosition()));
                            source.sendSuccess(() -> Component.literal("Block edit at your feet: " + decision), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("assign")
                                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                                .then(Commands.argument("supply", BlockPosArgument.blockPos())
                                        .then(Commands.argument("site", BlockPosArgument.blockPos())
                                                .executes(context -> {
                                                    var source = context.getSource();
                                                    ServerLevel level = source.getLevel();
                                                    BlockPos supply = BlockPosArgument.getLoadedBlockPos(context, "supply");
                                                    BlockPos site = BlockPosArgument.getLoadedBlockPos(context, "site");
                                                    var settlement = SettlementSavedData.get(level).settlement();
                                                    if (settlement.isEmpty()) {
                                                        source.sendFailure(Component.literal("No settlement in this dimension"));
                                                        return 0;
                                                    }
                                                    String id = settlement.get().id();
                                                    if (WorldModificationPermission.check(level, id, supply)
                                                            != WorldModificationPermission.Decision.ALLOWED
                                                            || WorldModificationPermission.check(level, id, site)
                                                            != WorldModificationPermission.Decision.ALLOWED) {
                                                        source.sendFailure(Component.literal("Supply and site must be active claimed land outside player areas"));
                                                        return 0;
                                                    }
                                                    if (!(level.getBlockEntity(supply) instanceof Container)) {
                                                        source.sendFailure(Component.literal("Supply must be a real container"));
                                                        return 0;
                                                    }
                                                    if (!level.getBlockState(site).isAir()) {
                                                        source.sendFailure(Component.literal("Build site must be empty"));
                                                        return 0;
                                                    }
                                                    var resident = level.getEntitiesOfClass(GoblinCitizenEntity.class,
                                                                    new AABB(supply).inflate(16.0))
                                                            .stream().min(Comparator.comparingDouble(
                                                                    goblin -> goblin.blockPosition().distSqr(supply)));
                                                    if (resident.isEmpty() || !resident.get().assignConstruction(id, supply, site)) {
                                                        source.sendFailure(Component.literal("No idle goblin within 16 blocks of the supply"));
                                                        return 0;
                                                    }
                                                    source.sendSuccess(() -> Component.literal("Assigned oak plank construction to goblin "
                                                            + resident.get().getUUID() + ": " + resident.get().workSummary()), true);
                                                    return Command.SINGLE_SUCCESS;
                                                }))))
                        .then(Commands.literal("work").executes(context -> {
                            var source = context.getSource();
                            var resident = source.getLevel().getEntitiesOfClass(GoblinCitizenEntity.class,
                                            new AABB(source.getPosition(), source.getPosition()).inflate(16.0))
                                    .stream().min(Comparator.comparingDouble(
                                            goblin -> goblin.distanceToSqr(source.getPosition())));
                            if (resident.isEmpty()) {
                                source.sendFailure(Component.literal("No goblin within 16 blocks"));
                                return 0;
                            }
                            source.sendSuccess(() -> Component.literal("Goblin " + resident.get().getUUID()
                                    + ": " + resident.get().workSummary()), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("protect")
                                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_MODERATOR))
                                .then(Commands.argument("x1", IntegerArgumentType.integer())
                                        .then(Commands.argument("z1", IntegerArgumentType.integer())
                                                .then(Commands.argument("x2", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z2", IntegerArgumentType.integer())
                                                                .executes(context -> {
                                                                    var source = context.getSource();
                                                                    var rectangle = ProtectedRectangle.fromCorners(
                                                                            IntegerArgumentType.getInteger(context, "x1"),
                                                                            IntegerArgumentType.getInteger(context, "z1"),
                                                                            IntegerArgumentType.getInteger(context, "x2"),
                                                                            IntegerArgumentType.getInteger(context, "z2"));
                                                                    var data = SettlementSavedData.get(source.getLevel());
                                                                    String owner = source.getEntity() instanceof ServerPlayer player
                                                                            ? player.getUUID().toString() : "server";
                                                                    if (!data.protect(owner, rectangle)) {
                                                                        source.sendFailure(Component.literal("This protected area is already registered"));
                                                                        return 0;
                                                                    }
                                                                    source.sendSuccess(() -> Component.literal("Protected area registered"), true);
                                                                    return Command.SINGLE_SUCCESS;
                                                                }))))))));
    }
}
