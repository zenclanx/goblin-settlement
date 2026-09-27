package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;

/**
 * Bounded server-side defense loop. It never force-loads chunks and reads real public storage
 * on every attempt; unloaded golems remain in DefenseSavedData's occupied count.
 */
public final class DefenseCoordinator {
    private static final int WORK_INTERVAL_TICKS = 100;
    private static final double SETTLEMENT_SCAN_RADIUS = 128.0;

    private DefenseCoordinator() {
    }

    public static void tick(ServerLevel level) {
        SettlementSavedData settlement = SettlementSavedData.get(level);
        var founded = settlement.settlement();
        if (founded.isEmpty() || !level.shouldTickBlocksAt(founded.get().anchor())) {
            return;
        }
        String settlementId = founded.get().id();
        VanillaIronGolemBridge.tick(level, founded.get().anchor(), settlementId);
        if (level.getGameTime() % WORK_INTERVAL_TICKS != 0) {
            return;
        }
        DefenseSavedData roster = DefenseSavedData.get(level);
        List<GoblinGolemEntity> loaded = level.getEntitiesOfClass(GoblinGolemEntity.class,
                new AABB(founded.get().anchor()).inflate(SETTLEMENT_SCAN_RADIUS),
                golem -> golem.isAlive() && golem.settlementId().equals(settlementId));
        for (GoblinGolemEntity golem : loaded) {
            if (!roster.contains(golem.getUUID().toString())) {
                roster.register(golem);
            } else {
                roster.updateTier(golem);
            }
            if (golem.tier() == GolemTier.IRON) {
                GolemWorkshop.migrateLegacyIron(level, golem, roster);
            }
        }

        List<IronGolem> loadedIron = level.getEntitiesOfClass(IronGolem.class,
                new AABB(founded.get().anchor()).inflate(SETTLEMENT_SCAN_RADIUS),
                golem -> golem.isAlive() && roster.isSettlementIronGolem(golem, settlementId));
        for (IronGolem iron : loadedIron) {
            if (iron.getTarget() != null) {
                continue;
            }
            for (BlockPos warehousePos : settlement.warehouses()) {
                if (!accessibleWarehouse(level, settlement, settlementId, warehousePos)) {
                    continue;
                }
                if (GolemWorkshop.tryRepairIron(level, iron, warehousePos)
                        || GolemWorkshop.tryUpgradeIron(level, iron, warehousePos)) {
                    break;
                }
            }
        }

        for (GoblinGolemEntity golem : loaded) {
            if (!golem.isAlive() || golem.tier() == GolemTier.IRON || golem.getTarget() != null) {
                continue;
            }
            for (BlockPos warehousePos : settlement.warehouses()) {
                if (!accessibleWarehouse(level, settlement, settlementId, warehousePos)) {
                    continue;
                }
                if (GolemWorkshop.tryRepair(level, golem, warehousePos)
                        || GolemWorkshop.tryUpgrade(level, golem, warehousePos,
                                roster.obsidianCount(settlementId))) {
                    break;
                }
            }
        }

        if (roster.count(settlementId) >= GolemPopulationRules.maximumGolems(settlement.adultCount())) {
            return;
        }
        for (BlockPos warehousePos : settlement.warehouses()) {
            if (!accessibleWarehouse(level, settlement, settlementId, warehousePos)) {
                continue;
            }
            for (int radius = 1; radius <= 3; radius++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                            continue;
                        }
                        for (int dy = -1; dy <= 1; dy++) {
                            BlockPos site = warehousePos.offset(dx, dy, dz);
                            if (GolemWorkshop.tryCreate(level, settlementId, site, warehousePos,
                                    roster.count(settlementId), roster.obsidianCount(settlementId)).isPresent()) {
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Call only from an event confirming this attacker actually attacked this resident. */
    public static void onResidentAttack(ServerLevel level, GoblinCitizenEntity victim, Entity attacker) {
        if (level == null || victim == null || attacker == null || victim.level() != level
                || attacker.level() != level) {
            return;
        }
        // Whether to hit back is the resident's own call: it knows its trade. The golems below are
        // summoned regardless.
        if (attacker instanceof LivingEntity living) {
            victim.retaliateAgainst(living);
        }
        for (GoblinGolemEntity golem : level.getEntitiesOfClass(GoblinGolemEntity.class,
                new AABB(victim.blockPosition()).inflate(GoblinGolemEntity.DEFENSE_RADIUS),
                golem -> golem.isAlive() && !golem.settlementId().isBlank())) {
            golem.alertToResidentAttack(victim, attacker);
        }
        VanillaIronGolemBridge.alert(level, victim, attacker);
    }

    /** How far a sentry can pick out a hostile. Deliberately half a golem's own defence radius. */
    private static final double SIGHTING_RADIUS = 12.0;

    /**
     * A patrolling sentry reports what it can see. Only monsters count: GAME_DESIGN is explicit that a
     * player who merely carries a weapon or walks past is not an enemy, and an attack on a resident
     * already reaches the golems through onResidentAttack. Returns true when at least one golem took
     * the alert, so the caller can show that in its status line.
     */
    public static boolean reportSighting(ServerLevel level, GoblinCitizenEntity sentry) {
        if (level == null || sentry == null || sentry.level() != level) {
            return false;
        }
        LivingEntity threat = level.getEntitiesOfClass(LivingEntity.class,
                        new AABB(sentry.blockPosition()).inflate(SIGHTING_RADIUS),
                        entity -> entity instanceof Monster && entity.isAlive())
                .stream()
                .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(sentry)))
                .orElse(null);
        if (threat == null) {
            return false;
        }
        boolean alerted = false;
        for (GoblinGolemEntity golem : level.getEntitiesOfClass(GoblinGolemEntity.class,
                new AABB(sentry.blockPosition()).inflate(GoblinGolemEntity.DEFENSE_RADIUS),
                golem -> golem.isAlive() && !golem.settlementId().isBlank())) {
            alerted |= golem.alertToSighting(threat);
        }
        // Vanilla iron golems are left out on purpose: their bridge takes a victim, and a sighting has
        // none. They still defend themselves when something actually hits them.
        return alerted;
    }

    /**
     * Who counts as an attacker: monsters, and players who are neither creative nor spectators. This is
     * the one definition -- the golems' alert guards and a resident's decision to hit back both use it.
     *
     * Not the same question as the sighting filter in reportSighting, which admits monsters only:
     * GAME_DESIGN says a player who merely walks past is not an enemy, while a player who does attack
     * is handled by onResidentAttack.
     */
    public static boolean isPermittedAttacker(LivingEntity entity) {
        return entity instanceof Monster || entity instanceof ServerPlayer player
                && !player.isCreative() && !player.isSpectator();
    }

    private static boolean accessibleWarehouse(ServerLevel level, SettlementSavedData settlement,
                                                String settlementId, BlockPos warehousePos) {
        return settlement.warehouses().contains(warehousePos)
                && WorldModificationPermission.check(level, settlementId, warehousePos)
                        == WorldModificationPermission.Decision.ALLOWED
                && level.getBlockEntity(warehousePos) instanceof Container;
    }
}
