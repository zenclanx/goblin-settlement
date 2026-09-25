package dev.local.goblinsettlement.citizen;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.DroppedMaterialLookup;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** A resident with a persisted single-item construction or recovery task. */
public final class GoblinCitizenEntity extends PathfinderMob {
    private enum WorkStage { IDLE, FETCHING, DELIVERING, COMPLETE, RECOVERING, RETURNING, RECOVERED, ABORTED }

    private WorkStage workStage = WorkStage.IDLE;
    private String settlementId = "";
    private BlockPos supplyPos = BlockPos.ZERO;
    private BlockPos buildPos = BlockPos.ZERO;
    private ItemStack carried = ItemStack.EMPTY;
    private String pickupItemId = "";
    private String waitReason = "";

    public GoblinCitizenEntity(EntityType<? extends GoblinCitizenEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 16.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(1, new RandomLookAroundGoal(this));
    }

    public boolean assignConstruction(String id, BlockPos supply, BlockPos site) {
        if (!isAvailableForConstruction()) {
            return false;
        }
        settlementId = id;
        supplyPos = supply.immutable();
        buildPos = site.immutable();
        workStage = WorkStage.FETCHING;
        waitReason = "";
        return true;
    }

    public boolean assignRecovery(String id, BlockPos warehouse, String itemId, BlockPos itemPos) {
        if (!isAvailableForConstruction()) {
            return false;
        }
        settlementId = id;
        supplyPos = warehouse.immutable();
        buildPos = itemPos.immutable();
        pickupItemId = itemId;
        workStage = WorkStage.RECOVERING;
        waitReason = "";
        return true;
    }

    public String workSummary() {
        return workStage + (waitReason.isEmpty() ? "" : " (" + waitReason + ")")
                + ", carrying=" + carried.getCount();
    }

    public boolean isAvailableForConstruction() {
        if (level() instanceof ServerLevel serverLevel
                && SettlementSavedData.get(serverLevel).isCancelledWorker(getUUID().toString())) {
            return false;
        }
        return isAlive() && !isRemoved()
                && (workStage == WorkStage.IDLE || workStage == WorkStage.COMPLETE) && carried.isEmpty();
    }

    public boolean completedConstruction(String id, BlockPos site) {
        return workStage == WorkStage.COMPLETE && settlementId.equals(id) && buildPos.equals(site);
    }

    public boolean recoveryEnded(String id) {
        return settlementId.equals(id) && (workStage == WorkStage.RECOVERED || workStage == WorkStage.ABORTED);
    }

    public boolean recoverySucceeded() {
        return workStage == WorkStage.RECOVERED;
    }

    public void acknowledgeRecovery() {
        if (workStage == WorkStage.RECOVERED || workStage == WorkStage.ABORTED) {
            workStage = WorkStage.IDLE;
            pickupItemId = "";
            waitReason = "";
        }
    }

    public int carriedOakPlanks() {
        return carried.is(Items.OAK_PLANKS) ? carried.getCount() : 0;
    }

    public void applyProjectCancellation(ServerLevel level) {
        var data = SettlementSavedData.get(level);
        if (!data.isCancelledWorker(getUUID().toString())) {
            return;
        }
        if (!carried.isEmpty()) {
            ItemEntity dropped = spawnAtLocation(level, carried.copy());
            if (dropped == null) {
                getNavigation().stop();
                waitReason = "cancelled, material still carried";
                return;
            }
            carried = ItemStack.EMPTY;
        }
        getNavigation().stop();
        workStage = WorkStage.IDLE;
        pickupItemId = "";
        waitReason = "";
        data.acknowledgeCancelledWorker(getUUID().toString());
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);
        if (tickCount % 10 != 0) {
            return;
        }
        if (SettlementSavedData.get(level).isCancelledWorker(getUUID().toString())) {
            applyProjectCancellation(level);
            return;
        }
        if (workStage == WorkStage.IDLE || workStage == WorkStage.COMPLETE
                || workStage == WorkStage.RECOVERED || workStage == WorkStage.ABORTED) {
            return;
        }
        if (workStage == WorkStage.RECOVERING) {
            recoverDroppedItem(level);
            return;
        }
        if (workStage == WorkStage.RETURNING) {
            returnToWarehouse(level);
            return;
        }
        BlockPos destination = workStage == WorkStage.FETCHING ? supplyPos : buildPos;
        if (!level.shouldTickBlocksAt(destination)) {
            waitReason = "chunk inactive";
            getNavigation().stop();
            return;
        }
        if (WorldModificationPermission.check(level, settlementId, destination)
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "land permission changed";
            getNavigation().stop();
            return;
        }
        if (distanceToSqr(destination.getX() + 0.5, destination.getY() + 0.5,
                destination.getZ() + 0.5) > 8.0) {
            waitReason = "walking";
            getNavigation().moveTo(destination.getX() + 0.5, destination.getY(),
                    destination.getZ() + 0.5, 1.0);
            return;
        }
        getNavigation().stop();
        if (workStage == WorkStage.FETCHING) {
            fetchMaterial(level);
        } else {
            placeMaterial(level);
        }
    }

    private void recoverDroppedItem(ServerLevel level) {
        if (!carried.isEmpty()) {
            workStage = WorkStage.RETURNING;
            return;
        }
        if (WorldModificationPermission.check(level, settlementId, supplyPos)
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "warehouse inactive or protected";
            getNavigation().stop();
            return;
        }
        if (!level.shouldTickBlocksAt(buildPos)) {
            waitReason = "dropped item chunk inactive";
            getNavigation().stop();
            return;
        }
        var found = DroppedMaterialLookup.find(level, pickupItemId, buildPos);
        if (found.isEmpty()) {
            workStage = WorkStage.ABORTED;
            waitReason = "dropped item missing";
            return;
        }
        ItemEntity item = found.get();
        BlockPos itemPos = item.blockPosition();
        if (WorldModificationPermission.check(level, settlementId, itemPos)
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "dropped item area inactive or protected";
            getNavigation().stop();
            return;
        }
        SettlementSavedData.get(level).retargetRecoveryDrop(pickupItemId, item.getUUID().toString(), itemPos);
        pickupItemId = item.getUUID().toString();
        buildPos = itemPos.immutable();
        if (!item.getItem().is(Items.OAK_PLANKS)) {
            workStage = WorkStage.ABORTED;
            waitReason = "dropped item changed";
            return;
        }
        if (distanceToSqr(item) > 4.0) {
            waitReason = "walking to dropped item";
            getNavigation().moveTo(item, 1.0);
            return;
        }
        getNavigation().stop();
        ItemStack stack = item.getItem();
        ItemStack recovered = stack.split(1);
        if (recovered.isEmpty()) {
            return;
        }
        if (stack.isEmpty()) {
            item.discard();
        } else {
            item.setItem(stack);
        }
        carried = recovered;
        workStage = WorkStage.RETURNING;
        waitReason = "";
    }

    private void returnToWarehouse(ServerLevel level) {
        if (carried.isEmpty()) {
            workStage = WorkStage.ABORTED;
            waitReason = "carried item missing";
            return;
        }
        if (WorldModificationPermission.check(level, settlementId, supplyPos)
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "warehouse inactive or protected";
            getNavigation().stop();
            return;
        }
        if (distanceToSqr(supplyPos.getX() + 0.5, supplyPos.getY() + 0.5,
                supplyPos.getZ() + 0.5) > 8.0) {
            waitReason = "returning material";
            getNavigation().moveTo(supplyPos.getX() + 0.5, supplyPos.getY(), supplyPos.getZ() + 0.5, 1.0);
            return;
        }
        getNavigation().stop();
        if (!(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "warehouse container missing";
            return;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.canPlaceItem(slot, carried)) {
                continue;
            }
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                container.setItem(slot, carried);
            } else if (existing.is(Items.OAK_PLANKS)
                    && existing.getCount() < Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing))) {
                existing.grow(1);
                container.setChanged();
            } else {
                continue;
            }
            carried = ItemStack.EMPTY;
            workStage = WorkStage.RECOVERED;
            waitReason = "";
            return;
        }
        waitReason = "warehouse full";
    }

    private void fetchMaterial(ServerLevel level) {
        if (!carried.isEmpty()) {
            workStage = WorkStage.DELIVERING;
            return;
        }
        if (!(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "supply container missing";
            return;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(Items.OAK_PLANKS)) {
                ItemStack removed = container.removeItem(slot, 1);
                if (!removed.isEmpty()) {
                    carried = removed;
                    container.setChanged();
                    workStage = WorkStage.DELIVERING;
                    waitReason = "";
                }
                return;
            }
        }
        waitReason = "oak planks missing";
    }

    private void placeMaterial(ServerLevel level) {
        if (!carried.is(Items.OAK_PLANKS)) {
            workStage = WorkStage.FETCHING;
            waitReason = "material lost";
            return;
        }
        if (!level.getBlockState(buildPos).isAir()) {
            waitReason = "site occupied";
            return;
        }
        BlockPos below = buildPos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
            waitReason = "no solid foundation";
            return;
        }
        if (WorldModificationPermission.check(level, settlementId, buildPos)
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "land permission changed";
            return;
        }
        if (level.setBlock(buildPos, Blocks.OAK_PLANKS.defaultBlockState(), 3)) {
            carried = ItemStack.EMPTY;
            workStage = WorkStage.COMPLETE;
            waitReason = "";
            SettlementSavedData.get(level).finishStep(getUUID().toString(), buildPos);
        } else {
            waitReason = "placement failed";
        }
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.putString("GoblinSettlementWorkStage", workStage.name());
        output.putString("GoblinSettlementId", settlementId);
        output.store("GoblinSupplyPos", BlockPos.CODEC, supplyPos);
        output.store("GoblinBuildPos", BlockPos.CODEC, buildPos);
        output.putString("GoblinPickupItemId", pickupItemId);
        if (!carried.isEmpty()) {
            output.store("GoblinCarried", ItemStack.CODEC, carried);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        try {
            workStage = WorkStage.valueOf(input.getStringOr("GoblinSettlementWorkStage", "IDLE"));
        } catch (IllegalArgumentException exception) {
            workStage = WorkStage.IDLE;
        }
        settlementId = input.getStringOr("GoblinSettlementId", "");
        supplyPos = input.read("GoblinSupplyPos", BlockPos.CODEC).orElse(BlockPos.ZERO);
        buildPos = input.read("GoblinBuildPos", BlockPos.CODEC).orElse(BlockPos.ZERO);
        pickupItemId = input.getStringOr("GoblinPickupItemId", "");
        carried = input.read("GoblinCarried", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        if (!carried.isEmpty() && workStage == WorkStage.FETCHING) {
            workStage = WorkStage.DELIVERING;
        }
        if (!carried.isEmpty() && workStage == WorkStage.RECOVERING) {
            workStage = WorkStage.RETURNING;
        }
        if (carried.isEmpty() && workStage == WorkStage.RETURNING) {
            workStage = WorkStage.ABORTED;
        }
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource damageSource, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, damageSource, recentlyHit);
        var data = SettlementSavedData.get(level);
        if (!carried.isEmpty()) {
            ItemEntity dropped = spawnAtLocation(level, carried);
            if (dropped != null) {
                data.recordRecoverableDrop(getUUID().toString(), dropped.getUUID().toString(),
                        dropped.blockPosition());
            }
            carried = ItemStack.EMPTY;
        }
        data.releaseWorker(getUUID().toString());
        data.acknowledgeCancelledWorker(getUUID().toString());
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }
}
