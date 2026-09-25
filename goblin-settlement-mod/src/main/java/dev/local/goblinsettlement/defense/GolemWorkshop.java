package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

/** Server-only creation, one-tier upgrade and repair against accessible public containers. */
public final class GolemWorkshop {
    private static final double WORKSHOP_DISTANCE_SQUARED = 16.0;

    private GolemWorkshop() {
    }

    /**
     * Counts must include unloaded golems and come from the settlement's authoritative registry.
     * The caller must also ensure the selected site is safe for a 1.1x2.7-block entity.
     */
    public static Optional<GoblinGolemEntity> tryCreate(ServerLevel level, String settlementId,
            BlockPos spawnPos, BlockPos warehousePos, int existingGolems, int obsidianGolems) {
        if (level == null || settlementId == null || spawnPos == null || warehousePos == null) {
            return Optional.empty();
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        DefenseSavedData roster = DefenseSavedData.get(level);
        int countedGolems = Math.max(existingGolems, roster.count(settlementId));
        int countedObsidian = Math.max(obsidianGolems, roster.obsidianCount(settlementId));
        if (!ownsSettlement(data, settlementId)
                || !GolemPopulationRules.mayCreate(data.adultCount(), countedGolems, countedObsidian)
                || WorldModificationPermission.check(level, settlementId, spawnPos)
                        != WorldModificationPermission.Decision.ALLOWED
                || !level.getBlockState(spawnPos).isAir()
                || !level.getBlockState(spawnPos.above()).isAir()
                || !level.getBlockState(spawnPos.above(2)).isAir()
                || !level.getBlockState(spawnPos.below())
                        .isFaceSturdy(level, spawnPos.below(), Direction.UP)) {
            return Optional.empty();
        }
        Optional<Container> warehouse = warehouse(level, data, settlementId, warehousePos);
        if (warehouse.isEmpty()) {
            return Optional.empty();
        }
        GoblinGolemEntity golem = new GoblinGolemEntity(GolemEntities.GOLEM, level);
        golem.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        if (!golem.bindToSettlement(settlementId, spawnPos)
                || !level.noCollision(golem)
                || !level.getEntitiesOfClass(LivingEntity.class, golem.getBoundingBox(),
                        LivingEntity::isAlive).isEmpty()) {
            return Optional.empty();
        }
        Optional<GolemMaterials.Snapshot> paid = GolemMaterials.consume(
                warehouse.get(), GolemTier.WOOD.entryCost());
        if (paid.isEmpty()) {
            return Optional.empty();
        }
        if (!level.addFreshEntity(golem)) {
            paid.get().restore();
            return Optional.empty();
        }
        if (!roster.register(golem)) {
            golem.discard();
            paid.get().restore();
            return Optional.empty();
        }
        return Optional.of(golem);
    }

    public static boolean tryUpgrade(ServerLevel level, GoblinGolemEntity golem,
                                     BlockPos warehousePos, int obsidianGolems) {
        if (!canUseWorkshop(level, golem, warehousePos)) {
            return false;
        }
        DefenseSavedData roster = DefenseSavedData.get(level);
        if (!roster.contains(golem.getUUID().toString())) {
            roster.register(golem);
        }
        Optional<GolemTier> next = golem.tier().next();
        int countedObsidian = Math.max(obsidianGolems, roster.obsidianCount(golem.settlementId()));
        if (next.isEmpty() || !GolemPopulationRules.mayUpgradeTo(next.get(), countedObsidian)) {
            return false;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        if (next.get() == GolemTier.OBSIDIAN && !hasAccessibleDiamondPickaxe(level, data)) {
            return false;
        }
        Optional<Container> warehouse = warehouse(level, data, golem.settlementId(), warehousePos);
        if (warehouse.isEmpty() || GolemMaterials.consume(warehouse.get(), next.get().entryCost()).isEmpty()) {
            return false;
        }
        golem.applyPaidUpgrade(next.get());
        roster.updateTier(golem);
        return true;
    }

    public static boolean tryRepair(ServerLevel level, GoblinGolemEntity golem,
                                    BlockPos warehousePos) {
        if (!canUseWorkshop(level, golem, warehousePos)
                || golem.getHealth() >= golem.getMaxHealth()) {
            return false;
        }
        Optional<Container> warehouse = warehouse(level, SettlementSavedData.get(level),
                golem.settlementId(), warehousePos);
        if (warehouse.isEmpty() || GolemMaterials.consume(warehouse.get(),
                List.of(new GolemMaterialCost(golem.tier().repairItem(), 1))).isEmpty()) {
            return false;
        }
        DefenseSavedData roster = DefenseSavedData.get(level);
        if (!roster.contains(golem.getUUID().toString())) {
            roster.register(golem);
        }
        golem.applyPaidRepair();
        return true;
    }

    private static boolean canUseWorkshop(ServerLevel level, GoblinGolemEntity golem,
                                          BlockPos warehousePos) {
        if (level == null || golem == null || warehousePos == null || !golem.isAlive()
                || golem.level() != level || golem.settlementId().isBlank()
                || !ownsSettlement(SettlementSavedData.get(level), golem.settlementId())
                || WorldModificationPermission.check(level, golem.settlementId(), golem.blockPosition())
                        != WorldModificationPermission.Decision.ALLOWED) {
            return false;
        }
        return golem.distanceToSqr(warehousePos.getX() + 0.5, warehousePos.getY() + 0.5,
                warehousePos.getZ() + 0.5) <= WORKSHOP_DISTANCE_SQUARED;
    }

    private static Optional<Container> warehouse(ServerLevel level, SettlementSavedData data,
            String settlementId, BlockPos pos) {
        if (!data.warehouses().contains(pos)
                || WorldModificationPermission.check(level, settlementId, pos)
                        != WorldModificationPermission.Decision.ALLOWED
                || !(level.getBlockEntity(pos) instanceof Container container)) {
            return Optional.empty();
        }
        return Optional.of(container);
    }

    private static boolean hasAccessibleDiamondPickaxe(ServerLevel level, SettlementSavedData data) {
        for (BlockPos pos : data.warehouses()) {
            Optional<Container> accessible = warehouse(level, data, data.settlement().orElseThrow().id(), pos);
            if (accessible.isEmpty()) {
                continue;
            }
            Container container = accessible.get();
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).is(Items.DIAMOND_PICKAXE)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean ownsSettlement(SettlementSavedData data, String id) {
        return data.settlement().map(settlement -> settlement.id().equals(id)).orElse(false);
    }
}
