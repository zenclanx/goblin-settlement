package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
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
        for (GoblinGolemEntity golem : level.getEntitiesOfClass(GoblinGolemEntity.class,
                new AABB(victim.blockPosition()).inflate(GoblinGolemEntity.DEFENSE_RADIUS),
                golem -> golem.isAlive() && !golem.settlementId().isBlank())) {
            golem.alertToResidentAttack(victim, attacker);
        }
        VanillaIronGolemBridge.alert(level, victim, attacker);
    }

    private static boolean accessibleWarehouse(ServerLevel level, SettlementSavedData settlement,
                                                String settlementId, BlockPos warehousePos) {
        return settlement.warehouses().contains(warehousePos)
                && WorldModificationPermission.check(level, settlementId, warehousePos)
                        == WorldModificationPermission.Decision.ALLOWED
                && level.getBlockEntity(warehousePos) instanceof Container;
    }
}
