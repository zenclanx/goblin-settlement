package dev.local.goblinsettlement.social;

import com.mojang.brigadier.Command;
import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.defense.DefenseCoordinator;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

/** Records explicit aggression and a real-item compensation path. */
public final class RelationshipCoordinator {
    private RelationshipCoordinator() {
    }

    public static void initialize() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) ->
                onResidentDamaged(entity, source));
        ServerLivingEntityEvents.AFTER_DEATH.register(RelationshipCoordinator::onResidentDamaged);
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            if (world instanceof ServerLevel level) {
                var data = SettlementSavedData.get(level);
                data.settlement().ifPresent(settlement -> {
                    if (data.warehouses().contains(pos)) {
                        SettlementRelations.get(level, settlement.id())
                                .recordWarehouseDestruction(player.getUUID().toString());
                    }
                });
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("goblinsettlement")
                        .then(Commands.literal("relationship").executes(context -> {
                            if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
                                context.getSource().sendFailure(Component.literal("Only a player has a relationship"));
                                return 0;
                            }
                            var data = SettlementSavedData.get(context.getSource().getLevel());
                            if (data.settlement().isEmpty()) {
                                context.getSource().sendFailure(Component.literal("No settlement in this dimension"));
                                return 0;
                            }
                            var relation = SettlementRelations.get(context.getSource().getLevel(),
                                    data.settlement().orElseThrow().id());
                            String id = player.getUUID().toString();
                            context.getSource().sendSuccess(() -> Component.literal("Trust="
                                    + relation.trust(id) + ", offenses=" + relation.offenses(id)), false);
                            return Command.SINGLE_SUCCESS;
                        }))
                        .then(Commands.literal("compensate").executes(context -> {
                            if (!(context.getSource().getEntity() instanceof ServerPlayer player)) {
                                context.getSource().sendFailure(Component.literal("Only a player can compensate"));
                                return 0;
                            }
                            var data = SettlementSavedData.get(context.getSource().getLevel());
                            if (data.settlement().isEmpty()) {
                                context.getSource().sendFailure(Component.literal("No settlement in this dimension"));
                                return 0;
                            }
                            var relation = SettlementRelations.get(context.getSource().getLevel(),
                                    data.settlement().orElseThrow().id());
                            String id = player.getUUID().toString();
                            if (relation.trust(id) >= 0 || !player.getMainHandItem().is(Items.EMERALD)) {
                                context.getSource().sendFailure(Component.literal(
                                        "Hold one emerald while trust is below zero"));
                                return 0;
                            }
                            player.getMainHandItem().shrink(1);
                            relation.compensate(id);
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Compensation accepted; trust=" + relation.trust(id)), true);
                            return Command.SINGLE_SUCCESS;
                        }))));
    }
    private static void onResidentDamaged(LivingEntity entity, DamageSource source) {
        if (!(entity instanceof GoblinCitizenEntity goblin)
                || !(goblin.level() instanceof ServerLevel level)) {
            return;
        }
        var data = SettlementSavedData.get(level);
        data.settlement().ifPresent(settlement -> {
            if (data.resident(goblin.getUUID().toString()).isEmpty()) {
                return;
            }
            DefenseCoordinator.onResidentAttack(level, goblin, source.getEntity());
            if (source.getEntity() instanceof ServerPlayer player) {
                SettlementRelations.get(level, settlement.id())
                        .recordAssault(player.getUUID().toString());
            }
        });
    }}
