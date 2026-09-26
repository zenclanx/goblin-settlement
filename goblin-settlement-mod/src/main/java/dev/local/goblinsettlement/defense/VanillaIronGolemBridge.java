package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/** Ownership and bounded alarms for actual vanilla iron golem entities. */
public final class VanillaIronGolemBridge {
    private static final int ALERT_TICKS = 20 * 15;
    private record Alert(UUID targetId, long untilTick) {
    }

    private static final Map<UUID, Alert> ALERTS = new HashMap<>();

    private VanillaIronGolemBridge() {
    }

    public static void initialize() {
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (entity instanceof IronGolem iron) {
                ALERTS.remove(iron.getUUID());
                if (iron.isAlive()) {
                    return;
                }
                DefenseSavedData roster = DefenseSavedData.get(level);
                roster.record(iron.getUUID().toString())
                        .filter(record -> record.tier() == GolemTier.IRON)
                        .ifPresent(record -> roster.markDead(record.id(), record.settlementId()));
            }
        });
    }

    public static void alert(ServerLevel level, GoblinCitizenEntity victim, Entity cause) {
        if (!(cause instanceof LivingEntity attacker) || victim == null || victim.level() != level
                || attacker.level() != level || !attacker.isAlive() || !permittedAttacker(attacker)) {
            return;
        }
        SettlementSavedData settlement = SettlementSavedData.get(level);
        if (settlement.resident(victim.getUUID().toString()).isEmpty()) {
            return;
        }
        DefenseSavedData roster = DefenseSavedData.get(level);
        for (IronGolem iron : level.getEntitiesOfClass(IronGolem.class,
                new AABB(victim.blockPosition()).inflate(GoblinGolemEntity.DEFENSE_RADIUS),
                IronGolem::isAlive)) {
            var record = roster.record(iron.getUUID().toString());
            if (record.isEmpty() || record.get().tier() != GolemTier.IRON
                    || settlement.settlement()
                            .map(value -> !value.id().equals(record.get().settlementId())).orElse(true)) {
                continue;
            }
            BlockPos home = effectiveHome(roster, record.get(), iron);
            if (iron.distanceToSqr(victim) > radiusSquared()
                    || iron.distanceToSqr(attacker) > radiusSquared()
                    || attacker.distanceToSqr(centerX(home), home.getY() + 0.5,
                            centerZ(home)) > radiusSquared()) {
                continue;
            }
            iron.setTarget(attacker);
            ALERTS.put(iron.getUUID(), new Alert(attacker.getUUID(), level.getGameTime() + ALERT_TICKS));
        }
    }

    /** Runs at a low frequency; vanilla combat against monsters remains vanilla behavior. */
    public static void tick(ServerLevel level, BlockPos anchor, String settlementId) {
        if (level.getGameTime() % 20 != 0) {
            return;
        }
        DefenseSavedData roster = DefenseSavedData.get(level);
        for (IronGolem iron : level.getEntitiesOfClass(IronGolem.class,
                new AABB(anchor).inflate(128.0), IronGolem::isAlive)) {
            var record = roster.record(iron.getUUID().toString());
            if (record.isEmpty() || record.get().tier() != GolemTier.IRON
                    || !record.get().settlementId().equals(settlementId)) {
                continue;
            }
            BlockPos home = effectiveHome(roster, record.get(), iron);
            LivingEntity target = iron.getTarget();
            Alert alert = ALERTS.get(iron.getUUID());
            if (alert != null && alert.untilTick() <= level.getGameTime()) {
                ALERTS.remove(iron.getUUID());
            }
            boolean activeAlert = target != null && alert != null && alert.untilTick() > level.getGameTime()
                    && alert.targetId().equals(target.getUUID());
            if (target != null && (target instanceof ServerPlayer && !activeAlert
                    || target.distanceToSqr(centerX(home), home.getY() + 0.5,
                            centerZ(home)) > radiusSquared()
                    || alert != null && (!activeAlert || !target.isAlive()
                    || iron.distanceToSqr(target) > radiusSquared()
                    || target.distanceToSqr(centerX(home), home.getY() + 0.5,
                            centerZ(home)) > radiusSquared()))) {
                iron.setTarget(null);
                iron.getNavigation().stop();
                ALERTS.remove(iron.getUUID());
            }
            if (iron.getTarget() == null && iron.distanceToSqr(centerX(home),
                    home.getY(), centerZ(home)) > 16.0) {
                iron.getNavigation().moveTo(centerX(home), home.getY(),
                        centerZ(home), 1.0);
            }
        }
    }

    /** Older saves stored no home; adopt the golem's real position once and persist it. */
    private static BlockPos effectiveHome(DefenseSavedData roster, DefenseSavedData.GolemRecord record,
                                          IronGolem iron) {
        if (!record.home().equals(BlockPos.ZERO)) {
            return record.home();
        }
        BlockPos actual = iron.blockPosition();
        roster.backfillHome(record.id(), actual);
        return actual;
    }

    private static boolean permittedAttacker(LivingEntity attacker) {
        return attacker instanceof Monster || attacker instanceof ServerPlayer player
                && !player.isCreative() && !player.isSpectator();
    }

    private static double radiusSquared() {
        return GoblinGolemEntity.DEFENSE_RADIUS * GoblinGolemEntity.DEFENSE_RADIUS;
    }

    private static double centerX(BlockPos pos) {
        return pos.getX() + 0.5;
    }

    private static double centerZ(BlockPos pos) {
        return pos.getZ() + 0.5;
    }
}
