package dev.local.goblinsettlement;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.colony.ProfessionCoordinator;
import dev.local.goblinsettlement.colony.ExpansionCoordinator;
import dev.local.goblinsettlement.colony.family.FamilyCoordinator;
import dev.local.goblinsettlement.camp.CampGenerationCoordinator;
import dev.local.goblinsettlement.economy.food.FoodCraftingCoordinator;
import dev.local.goblinsettlement.economy.food.MealCoordinator;
import dev.local.goblinsettlement.economy.tools.ToolCraftingCoordinator;
import dev.local.goblinsettlement.economy.smelting.SmeltingCoordinator;
import dev.local.goblinsettlement.economy.WarehouseRecoveryCommands;
import dev.local.goblinsettlement.defense.DefenseCoordinator;
import dev.local.goblinsettlement.defense.GolemEntities;
import dev.local.goblinsettlement.defense.PatrolCoordinator;
import dev.local.goblinsettlement.construction.ConstructionCommands;
import dev.local.goblinsettlement.construction.ConstructionCoordinator;
import dev.local.goblinsettlement.diagnostics.ProfileCommands;
import dev.local.goblinsettlement.diagnostics.SettlementProfiler;
import dev.local.goblinsettlement.construction.transport.TrafficProposalCoordinator;
import dev.local.goblinsettlement.construction.transport.TrafficSampler;
import dev.local.goblinsettlement.construction.transport.TransportCommands;
import dev.local.goblinsettlement.construction.transport.TransportCoordinator;
import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.farming.FarmingCommands;
import dev.local.goblinsettlement.farming.FarmingCoordinator;
import dev.local.goblinsettlement.farming.FarmDiscoveryCoordinator;
import dev.local.goblinsettlement.forestry.ForestryCoordinator;
import dev.local.goblinsettlement.forestry.ForestryCommands;
import dev.local.goblinsettlement.citizen.ModEntities;
import dev.local.goblinsettlement.interaction.ProtectedRectangle;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import dev.local.goblinsettlement.mining.MiningCoordinator;
import dev.local.goblinsettlement.housing.BedProvisioningCoordinator;
import dev.local.goblinsettlement.housing.HousingAssignmentCoordinator;
import dev.local.goblinsettlement.housing.HousingBlueprints;
import dev.local.goblinsettlement.housing.HousingCoordinator;
import dev.local.goblinsettlement.noticeboard.SettlementReport;
import dev.local.goblinsettlement.noticeboard.SettlementText;
import dev.local.goblinsettlement.social.RelationshipCoordinator;
import dev.local.goblinsettlement.social.GiftTradeCommands;
import dev.local.goblinsettlement.social.WarehouseWithdrawalObserver;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
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
        GolemEntities.initialize();
        RelationshipCoordinator.initialize();
        WarehouseWithdrawalObserver.initialize();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> HousingBlueprints.load());
        ServerTickEvents.END_WORLD_TICK.register(GoblinSettlement::tickSettlement);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ConstructionCommands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                FarmingCommands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ForestryCommands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                TransportCommands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                WarehouseRecoveryCommands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                GiftTradeCommands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ProfileCommands.register(dispatcher));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("goblinsettlement")
                        .then(Commands.literal("status").executes(context -> {
                            ServerLevel level = context.getSource().getLevel();
                            var report = SettlementReport.snapshot(level);
                            for (String line : SettlementText.lines(report)) {
                                context.getSource().sendSuccess(() -> Component.literal(line), false);
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
                                                            + resident.get().getUUID() + ": " + resident.get().workSummary()
                                                            + " (administrator bypass)"), true);
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
    /** Entire settlement pauses while its anchor chunk is inactive. */
    private static void tickSettlement(ServerLevel level) {
        // Runs before the settlement gate below: an alert must be able to lift even while the anchor's
        // chunk is unloaded, or a resident would stay indoors forever.
        SettlementProfiler.run("defense-alert", () -> DefenseCoordinator.tickAlert(level));
        SettlementProfiler.run("camp-generation", () -> CampGenerationCoordinator.tick(level));
        SettlementProfiler.run("profession", () -> ProfessionCoordinator.tick(level));
        var founded = SettlementSavedData.get(level).settlement();
        if (founded.isEmpty() || !level.shouldTickBlocksAt(founded.get().anchor())) {
            return;
        }
        SettlementProfiler.run("meal", () -> MealCoordinator.tick(level));
        SettlementProfiler.run("food-crafting", () -> FoodCraftingCoordinator.tick(level));
        SettlementProfiler.run("farm-discovery", () -> FarmDiscoveryCoordinator.tick(level));
        SettlementProfiler.run("farming", () -> FarmingCoordinator.tick(level));
        SettlementProfiler.run("tool-crafting", () -> ToolCraftingCoordinator.tick(level));
        SettlementProfiler.run("construction", () -> ConstructionCoordinator.tick(level));
        // propose first: saved plans reach the worker tick below
        SettlementProfiler.run("traffic-proposal", () -> TrafficProposalCoordinator.tick(level));
        SettlementProfiler.run("transport", () -> TransportCoordinator.tick(level));
        SettlementProfiler.run("traffic-sampler", () -> TrafficSampler.tick(level));
        SettlementProfiler.run("forestry", () -> ForestryCoordinator.tick(level));
        SettlementProfiler.run("mining", () -> MiningCoordinator.tick(level));
        SettlementProfiler.run("smelting", () -> SmeltingCoordinator.tick(level));
        SettlementProfiler.run("housing", () -> HousingCoordinator.tick(level));
        SettlementProfiler.run("bed-provisioning", () -> BedProvisioningCoordinator.tick(level));
        SettlementProfiler.run("housing-assignment", () -> HousingAssignmentCoordinator.tick(level));
        SettlementProfiler.run("patrol", () -> PatrolCoordinator.tick(level));
        SettlementProfiler.run("defense", () -> DefenseCoordinator.tick(level));
        SettlementProfiler.run("family", () -> FamilyCoordinator.tick(level));
        SettlementProfiler.run("expansion", () -> ExpansionCoordinator.tick(level));
    }
}
