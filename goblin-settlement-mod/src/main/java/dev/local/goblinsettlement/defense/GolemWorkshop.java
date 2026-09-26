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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.IronGolem;
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
        if (warehouse.isEmpty()) {
            return false;
        }
        if (next.get() == GolemTier.IRON) {
            return upgradeToVanillaIron(level, golem, warehouse.get(), roster);
        }
        if (GolemMaterials.consume(warehouse.get(), next.get().entryCost()).isEmpty()) {
            return false;
        }
        golem.applyPaidUpgrade(next.get());
        roster.updateTier(golem);
        return true;
    }

    /** The third tier is the actual vanilla entity type, not the custom renderer with iron numbers. */
    private static boolean upgradeToVanillaIron(ServerLevel level, GoblinGolemEntity previous,
            Container warehouse, DefenseSavedData roster) {
        Optional<GolemMaterials.Snapshot> paid = GolemMaterials.consume(warehouse, GolemTier.IRON.entryCost());
        return paid.isPresent() && convertToVanillaIron(level, previous, roster, paid);
    }

    /** Existing iron-tier custom entities were already paid for in older saves. */
    static boolean migrateLegacyIron(ServerLevel level, GoblinGolemEntity previous,
            DefenseSavedData roster) {
        if (previous.tier() != GolemTier.IRON || previous.settlementId().isBlank()
                || previous.getTarget() != null) {
            return false;
        }
        return convertToVanillaIron(level, previous, roster, Optional.empty());
    }

    private static boolean convertToVanillaIron(ServerLevel level, GoblinGolemEntity previous,
            DefenseSavedData roster, Optional<GolemMaterials.Snapshot> paid) {
        IronGolem iron = new IronGolem(EntityType.IRON_GOLEM, level);
        iron.setPos(previous.getX(), previous.getY(), previous.getZ());
        iron.setCustomName(previous.getCustomName());
        iron.setCustomNameVisible(previous.isCustomNameVisible());
        iron.setHealth(Math.max(1.0F, iron.getMaxHealth() * previous.getHealth() / previous.getMaxHealth()));
        if (!level.getEntitiesOfClass(LivingEntity.class, iron.getBoundingBox(),
                        entity -> entity.isAlive() && entity != previous).isEmpty()) {
            paid.ifPresent(GolemMaterials.Snapshot::restore);
            return false;
        }
        if (!level.addFreshEntity(iron)) {
            paid.ifPresent(GolemMaterials.Snapshot::restore);
            return false;
        }
        if (!roster.replace(previous.getUUID().toString(), iron.getUUID().toString(),
                previous.settlementId(), GolemTier.IRON, previous.home())) {
            iron.discard();
            paid.ifPresent(GolemMaterials.Snapshot::restore);
            return false;
        }
        previous.discard();
        return true;
    }

    public static boolean tryUpgradeIron(ServerLevel level, IronGolem iron, BlockPos warehousePos) {
        var record = level == null || iron == null ? Optional.<DefenseSavedData.GolemRecord>empty()
                : DefenseSavedData.get(level).record(iron.getUUID().toString());
        if (record.isEmpty() || record.get().tier() != GolemTier.IRON
                || !canUseWorkshop(level, iron, record.get(), warehousePos)) {
            return false;
        }
        DefenseSavedData roster = DefenseSavedData.get(level);
        if (!GolemPopulationRules.mayUpgradeTo(GolemTier.GOLD, roster.obsidianCount(record.get().settlementId()))) {
            return false;
        }
        Optional<Container> warehouse = warehouse(level, SettlementSavedData.get(level),
                record.get().settlementId(), warehousePos);
        if (warehouse.isEmpty()) {
            return false;
        }
        // An older roster may have no home; adopt the golem's real position before copying it forward.
        BlockPos home = record.get().home().equals(BlockPos.ZERO)
                ? iron.blockPosition() : record.get().home();
        GoblinGolemEntity upgraded = new GoblinGolemEntity(GolemEntities.GOLEM, level);
        upgraded.setPos(iron.getX(), iron.getY(), iron.getZ());
        if (!upgraded.bindToSettlement(record.get().settlementId(), home)) {
            return false;
        }
        // A fresh golem starts at WOOD and applyPaidUpgrade steps exactly one tier at a time, so the GOLD
        // tier is constructed by three one-step calls. Only GOLD's entry cost is charged, once, below.
        upgraded.applyPaidUpgrade(GolemTier.STONE);
        upgraded.applyPaidUpgrade(GolemTier.IRON);
        upgraded.applyPaidUpgrade(GolemTier.GOLD);
        upgraded.setHealth(Math.max(1.0F, upgraded.getMaxHealth() * iron.getHealth() / iron.getMaxHealth()));
        upgraded.setCustomName(iron.getCustomName());
        upgraded.setCustomNameVisible(iron.isCustomNameVisible());
        if (!level.getEntitiesOfClass(LivingEntity.class, upgraded.getBoundingBox(),
                        entity -> entity.isAlive() && entity != iron).isEmpty()) {
            return false;
        }
        Optional<GolemMaterials.Snapshot> paid = GolemMaterials.consume(warehouse.get(), GolemTier.GOLD.entryCost());
        if (paid.isEmpty()) {
            return false;
        }
        if (!level.addFreshEntity(upgraded)) {
            paid.get().restore();
            return false;
        }
        if (!roster.replace(iron.getUUID().toString(), upgraded.getUUID().toString(),
                record.get().settlementId(), GolemTier.GOLD, home)) {
            upgraded.discard();
            paid.get().restore();
            return false;
        }
        iron.discard();
        return true;
    }

    public static boolean tryRepairIron(ServerLevel level, IronGolem iron, BlockPos warehousePos) {
        var record = level == null || iron == null ? Optional.<DefenseSavedData.GolemRecord>empty()
                : DefenseSavedData.get(level).record(iron.getUUID().toString());
        if (record.isEmpty() || record.get().tier() != GolemTier.IRON
                || !canUseWorkshop(level, iron, record.get(), warehousePos)
                || iron.getHealth() >= iron.getMaxHealth()) {
            return false;
        }
        Optional<Container> warehouse = warehouse(level, SettlementSavedData.get(level),
                record.get().settlementId(), warehousePos);
        if (warehouse.isEmpty() || GolemMaterials.consume(warehouse.get(),
                List.of(new GolemMaterialCost(GolemTier.IRON.repairItem(), 1))).isEmpty()) {
            return false;
        }
        iron.heal(Math.max(4.0F, iron.getMaxHealth() / 4.0F));
        return true;
    }

    private static boolean canUseWorkshop(ServerLevel level, IronGolem iron,
            DefenseSavedData.GolemRecord record, BlockPos warehousePos) {
        return level != null && iron != null && warehousePos != null && iron.isAlive()
                && iron.level() == level
                && SettlementSavedData.get(level).settlement()
                        .map(value -> value.id().equals(record.settlementId())).orElse(false)
                && WorldModificationPermission.check(level, record.settlementId(), iron.blockPosition())
                        == WorldModificationPermission.Decision.ALLOWED
                && iron.distanceToSqr(warehousePos.getX() + 0.5, warehousePos.getY() + 0.5,
                        warehousePos.getZ() + 0.5) <= WORKSHOP_DISTANCE_SQUARED;
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
