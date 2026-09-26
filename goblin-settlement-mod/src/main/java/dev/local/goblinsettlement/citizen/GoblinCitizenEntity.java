package dev.local.goblinsettlement.citizen;

import com.mojang.serialization.Codec;
import dev.local.goblinsettlement.colony.Profession;
import dev.local.goblinsettlement.colony.ProfessionRules;
import dev.local.goblinsettlement.colony.ResidentRecord;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.colony.WorkKind;
import dev.local.goblinsettlement.construction.transport.TransportSavedData;
import dev.local.goblinsettlement.construction.transport.TransportCoordinator;
import dev.local.goblinsettlement.construction.transport.TransportPlan;
import dev.local.goblinsettlement.economy.DroppedMaterialLookup;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.economy.food.FoodCraftingCoordinator;
import dev.local.goblinsettlement.economy.smelting.FurnaceWorksite;
import dev.local.goblinsettlement.economy.smelting.SmeltingSavedData;
import dev.local.goblinsettlement.economy.tools.ToolCraftingCoordinator;
import dev.local.goblinsettlement.economy.tools.WoodToolKind;
import dev.local.goblinsettlement.forestry.ForestryCoordinator;
import dev.local.goblinsettlement.forestry.ForestrySavedData;
import dev.local.goblinsettlement.housing.HousingCoordinator;
import dev.local.goblinsettlement.housing.HousingSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import dev.local.goblinsettlement.mining.MiningWorksite;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** A resident with persisted work and actual carried materials or products. */
public final class GoblinCitizenEntity extends PathfinderMob {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(GoblinCitizenEntity.class);
    private static final int REGISTRATION_INTERVAL_TICKS = 10;

    private enum WorkStage { IDLE, FETCHING, DELIVERING, COMPLETE, RECOVERING, RETURNING, RECOVERED, ABORTED,
        FARM_FETCHING_SEED, FARM_PLANTING, FARM_HARVESTING, FARM_RETURNING, FARM_COMPLETE,
        TOOL_FETCHING, TOOL_CRAFTING, TOOL_RETURNING, TOOL_COMPLETE,
        FOOD_FETCHING, FOOD_CRAFTING, FOOD_RETURNING, FOOD_COMPLETE,
        FORESTRY_FETCHING, FORESTRY_FELLING, FORESTRY_LOG_RETURNING, FORESTRY_SAPLING_RECOVERING,
        FORESTRY_PROCESS_FETCHING, FORESTRY_PROCESSING, FORESTRY_PLANK_RETURNING, FORESTRY_COMPLETE,
        TRANSPORT_FETCHING, TRANSPORT_DELIVERING, TRANSPORT_RETURNING, TRANSPORT_COMPLETE,
        HOUSING_FETCHING, HOUSING_DELIVERING, HOUSING_RETURNING, HOUSING_COMPLETE,
        MINING_DIGGING, SMELT_FEEDING, SMELT_WAITING, SMELT_COLLECTING }

    /** The kind of work a stage performs, or null for stages that are not a job. */
    private static WorkKind workKind(WorkStage stage) {
        return switch (stage) {
            case IDLE -> null;
            case FETCHING, DELIVERING, RETURNING, RECOVERING, RECOVERED, ABORTED, COMPLETE ->
                    WorkKind.CONSTRUCTION;
            case FARM_FETCHING_SEED, FARM_PLANTING, FARM_HARVESTING, FARM_RETURNING, FARM_COMPLETE ->
                    WorkKind.FARMING;
            case TOOL_FETCHING, TOOL_CRAFTING, TOOL_RETURNING, TOOL_COMPLETE -> WorkKind.TOOL_CRAFTING;
            case FOOD_FETCHING, FOOD_CRAFTING, FOOD_RETURNING, FOOD_COMPLETE -> WorkKind.FOOD_CRAFTING;
            case FORESTRY_FETCHING, FORESTRY_FELLING, FORESTRY_LOG_RETURNING, FORESTRY_SAPLING_RECOVERING,
                 FORESTRY_PROCESS_FETCHING, FORESTRY_PROCESSING, FORESTRY_PLANK_RETURNING, FORESTRY_COMPLETE ->
                    WorkKind.FORESTRY;
            case TRANSPORT_FETCHING, TRANSPORT_DELIVERING, TRANSPORT_RETURNING, TRANSPORT_COMPLETE ->
                    WorkKind.TRANSPORT;
            case HOUSING_FETCHING, HOUSING_DELIVERING, HOUSING_RETURNING, HOUSING_COMPLETE ->
                    WorkKind.HOUSING;
            case MINING_DIGGING -> WorkKind.MINING;
            case SMELT_FEEDING, SMELT_WAITING, SMELT_COLLECTING -> WorkKind.SMELTING;
        };
    }

    private WorkStage workStage = WorkStage.IDLE;
    private String settlementId = "";
    private BlockPos supplyPos = BlockPos.ZERO;
    private BlockPos buildPos = BlockPos.ZERO;
    private ItemStack carried = ItemStack.EMPTY;
    private List<ItemStack> farmGoods = new ArrayList<>();
    private List<ItemStack> toolGoods = new ArrayList<>();
    private List<ItemStack> foodGoods = new ArrayList<>();
    private List<ItemStack> forestryGoods = new ArrayList<>();
    private BlockPos forestryRoot = BlockPos.ZERO;
    private WoodToolKind toolKind;
    private String pickupItemId = "";
    private String transportPlanId = "";
    private int transportStepIndex = -1;
    private BlockPos housingBed = BlockPos.ZERO;
    private FurnaceWorksite.Ore smeltOre;
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
    public void die(DamageSource source) {
        if (level() instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).markResidentDead(getUUID().toString());
        }
        super.die(source);
        if (level() instanceof ServerLevel serverLevel) {
            releasePersistedWork(serverLevel);
        }
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

    public boolean assignTransport(String id, String planId, int stepIndex,
                                   BlockPos supply, BlockPos site) {
        if (!isAvailableForConstruction()) return false;
        settlementId = id;
        transportPlanId = planId;
        transportStepIndex = stepIndex;
        supplyPos = supply.immutable();
        buildPos = site.immutable();
        workStage = WorkStage.TRANSPORT_FETCHING;
        waitReason = "";
        return true;
    }

    public boolean assignHousing(String id, BlockPos bed, BlockPos supply, BlockPos site) {
        if (!isAvailableForConstruction()) return false;
        settlementId = id;
        housingBed = bed.immutable();
        supplyPos = supply.immutable();
        buildPos = site.immutable();
        workStage = WorkStage.HOUSING_FETCHING;
        waitReason = "";
        return true;
    }

    /** True while this resident still legitimately holds the given transport step. */
    public boolean hasTransportWork(String planId, int stepIndex) {
        return transportPlanId.equals(planId) && transportStepIndex == stepIndex
                && (workStage == WorkStage.TRANSPORT_FETCHING
                    || workStage == WorkStage.TRANSPORT_DELIVERING
                    || workStage == WorkStage.TRANSPORT_RETURNING);
    }

    /** True while this resident still legitimately holds the given home. Beds identify a home uniquely. */
    public boolean hasHousingWork(BlockPos bed) {
        return housingBed.equals(bed)
                && (workStage == WorkStage.HOUSING_FETCHING
                    || workStage == WorkStage.HOUSING_DELIVERING
                    || workStage == WorkStage.HOUSING_RETURNING);
    }

    /**
     * Mining keeps no persisted reservation: the site is re-derived from the world every tick, so the
     * work clears itself back to IDLE instead of using the *_COMPLETE and acknowledge pattern.
     */
    public boolean assignMining(String id, BlockPos warehouse, BlockPos block) {
        if (!isAvailableForConstruction()) {
            return false;
        }
        settlementId = id;
        supplyPos = warehouse.immutable();
        buildPos = block.immutable();
        workStage = WorkStage.MINING_DIGGING;
        waitReason = "";
        return true;
    }

    public boolean hasMiningWork(String id) {
        return settlementId.equals(id) && workStage == WorkStage.MINING_DIGGING;
    }

    /** Smelting books a global one-batch reservation, so this work ends itself once the batch is collected. */
    public boolean assignSmelting(String id, BlockPos warehouse, BlockPos furnace, FurnaceWorksite.Ore ore) {
        if (ore == null || !isAvailableForConstruction()) {
            return false;
        }
        settlementId = id;
        supplyPos = warehouse.immutable();
        buildPos = furnace.immutable();
        smeltOre = ore;
        workStage = WorkStage.SMELT_FEEDING;
        waitReason = "";
        return true;
    }

    public boolean hasSmeltingWork(String id) {
        return settlementId.equals(id)
                && (workStage == WorkStage.SMELT_FEEDING || workStage == WorkStage.SMELT_WAITING
                    || workStage == WorkStage.SMELT_COLLECTING);
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

    public boolean assignFarmPlanting(String id, BlockPos warehouse, BlockPos crop) {
        return assignFarm(id, warehouse, crop, WorkStage.FARM_FETCHING_SEED);
    }

    public boolean assignFarmHarvest(String id, BlockPos warehouse, BlockPos crop) {
        return assignFarm(id, warehouse, crop, WorkStage.FARM_HARVESTING);
    }

    private boolean assignFarm(String id, BlockPos warehouse, BlockPos crop, WorkStage stage) {
        if (!isAvailableForConstruction()) {
            return false;
        }
        settlementId = id;
        supplyPos = warehouse.immutable();
        buildPos = crop.immutable();
        farmGoods.clear();
        workStage = stage;
        waitReason = "";
        return true;
    }

    public boolean farmWorkComplete(String id, BlockPos crop) {
        return workStage == WorkStage.FARM_COMPLETE && settlementId.equals(id) && buildPos.equals(crop);
    }

    public void acknowledgeFarmWork() {
        if (workStage == WorkStage.FARM_COMPLETE && carried.isEmpty() && farmGoods.isEmpty()) {
            workStage = WorkStage.IDLE;
            waitReason = "";
        }
    }

    public void cancelUnreservedFarmWork() {
        if (workStage == WorkStage.FARM_FETCHING_SEED || workStage == WorkStage.FARM_HARVESTING) {
            workStage = WorkStage.IDLE;
            waitReason = "";
        }
    }

    public boolean assignToolCrafting(String id, BlockPos warehouse, BlockPos table, WoodToolKind kind) {
        if (kind == null || !isAvailableForConstruction()) {
            return false;
        }
        settlementId = id;
        supplyPos = warehouse.immutable();
        buildPos = table.immutable();
        toolKind = kind;
        toolGoods.clear();
        workStage = WorkStage.TOOL_FETCHING;
        waitReason = "";
        return true;
    }

    public boolean hasToolWork(String id) {
        return settlementId.equals(id) && (workStage == WorkStage.TOOL_FETCHING
                || workStage == WorkStage.TOOL_CRAFTING || workStage == WorkStage.TOOL_RETURNING);
    }

    public boolean assignFoodCrafting(String id, BlockPos warehouse, BlockPos table) {
        if (!isAvailableForConstruction()) {
            return false;
        }
        settlementId = id;
        supplyPos = warehouse.immutable();
        buildPos = table.immutable();
        foodGoods.clear();
        workStage = WorkStage.FOOD_FETCHING;
        waitReason = "";
        return true;
    }

    public boolean hasFoodWork(String id) {
        return settlementId.equals(id) && (workStage == WorkStage.FOOD_FETCHING
                || workStage == WorkStage.FOOD_CRAFTING || workStage == WorkStage.FOOD_RETURNING);
    }

    public boolean assignTreeFelling(String id, BlockPos warehouse, BlockPos root, BlockPos target) {
        if (!isAvailableForConstruction() || root == null || target == null) {
            return false;
        }
        settlementId = id;
        supplyPos = warehouse.immutable();
        forestryRoot = root.immutable();
        buildPos = target.immutable();
        forestryGoods.clear();
        workStage = WorkStage.FORESTRY_FETCHING;
        waitReason = "";
        return true;
    }

    public boolean assignSaplingRecovery(String id, BlockPos warehouse, BlockPos root,
                                         String itemId, BlockPos itemPos) {
        if (!isAvailableForConstruction() || itemId == null || itemId.isBlank()) {
            return false;
        }
        settlementId = id;
        supplyPos = warehouse.immutable();
        forestryRoot = root.immutable();
        buildPos = itemPos.immutable();
        pickupItemId = itemId;
        forestryGoods.clear();
        workStage = WorkStage.FORESTRY_SAPLING_RECOVERING;
        waitReason = "";
        return true;
    }

    public boolean assignWoodProcessing(String id, BlockPos warehouse, BlockPos table) {
        if (!isAvailableForConstruction()) {
            return false;
        }
        settlementId = id;
        supplyPos = warehouse.immutable();
        buildPos = table.immutable();
        forestryRoot = BlockPos.ZERO;
        forestryGoods.clear();
        workStage = WorkStage.FORESTRY_PROCESS_FETCHING;
        waitReason = "";
        return true;
    }

    public boolean hasForestryWork(String id) {
        return settlementId.equals(id) && switch (workStage) {
            case FORESTRY_FETCHING, FORESTRY_FELLING, FORESTRY_LOG_RETURNING,
                    FORESTRY_SAPLING_RECOVERING,
                    FORESTRY_PROCESS_FETCHING, FORESTRY_PROCESSING, FORESTRY_PLANK_RETURNING -> true;
            default -> false;
        };
    }
    public String workSummary() {
        int goods = farmGoods.stream().mapToInt(ItemStack::getCount).sum()
                + toolGoods.stream().mapToInt(ItemStack::getCount).sum()
                + foodGoods.stream().mapToInt(ItemStack::getCount).sum()
                + forestryGoods.stream().mapToInt(ItemStack::getCount).sum();
        return workStage + (waitReason.isEmpty() ? "" : " (" + waitReason + ")")
                + ", carrying=" + (carried.getCount() + goods);
    }

    public boolean isAvailableForConstruction() {
        if (level() instanceof ServerLevel serverLevel) {
            var data = SettlementSavedData.get(serverLevel);
            String workerId = getUUID().toString();
            if (data.isCancelledWorker(workerId) || data.isFarmWorker(workerId)
                    || data.plans().stream().anyMatch(plan -> plan.workerId().orElse("").equals(workerId))
                    || TransportSavedData.get(serverLevel).plans().stream()
                            .anyMatch(plan -> plan.workerId().orElse("").equals(workerId))
                    || HousingSavedData.get(serverLevel).homes(data.settlement()
                            .map(value -> value.id()).orElse("" )).stream()
                            .anyMatch(home -> home.workerId().orElse("").equals(workerId))
                    || data.resident(workerId).map(record -> record.stage() != ResidentRecord.LifeStage.ADULT)
                            .orElse(false)) {
                return false;
            }
        }
        return isAlive() && !isRemoved()
                && (workStage == WorkStage.IDLE || workStage == WorkStage.COMPLETE
                    || workStage == WorkStage.TOOL_COMPLETE
                    || workStage == WorkStage.FOOD_COMPLETE
                    || workStage == WorkStage.TRANSPORT_COMPLETE
                    || workStage == WorkStage.HOUSING_COMPLETE
                    || workStage == WorkStage.FORESTRY_COMPLETE) && carried.isEmpty()
                && farmGoods.isEmpty() && toolGoods.isEmpty() && foodGoods.isEmpty()
                && forestryGoods.isEmpty();
    }

    /**
     * This resident's persisted trade. Read from the roster rather than cached on the entity so a
     * reassignment in the settlement is picked up without touching loaded entities.
     */
    public Profession profession() {
        if (level() instanceof ServerLevel serverLevel) {
            return SettlementSavedData.get(serverLevel).resident(getUUID().toString())
                    .map(ResidentRecord::profession)
                    .orElse(Profession.UNASSIGNED);
        }
        return Profession.UNASSIGNED;
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
        for (int index = foodGoods.size() - 1; index >= 0; index--) {
            if (spawnAtLocation(level, foodGoods.get(index).copy()) == null) {
                getNavigation().stop();
                waitReason = "cancelled, food materials still carried";
                return;
            }
            foodGoods.remove(index);
        }
        for (int index = toolGoods.size() - 1; index >= 0; index--) {
            if (spawnAtLocation(level, toolGoods.get(index).copy()) == null) {
                getNavigation().stop();
                waitReason = "cancelled, tool materials still carried";
                return;
            }
            toolGoods.remove(index);
        }
        for (int index = forestryGoods.size() - 1; index >= 0; index--) {
            if (spawnAtLocation(level, forestryGoods.get(index).copy()) == null) {
                getNavigation().stop();
                waitReason = "cancelled, forestry materials still carried";
                return;
            }
            forestryGoods.remove(index);
        }
        getNavigation().stop();
        workStage = WorkStage.IDLE;
        toolKind = null;
        pickupItemId = "";
        transportPlanId = "";
        transportStepIndex = -1;
        housingBed = BlockPos.ZERO;
        waitReason = "";
        releasePersistedWork(level);
    }

    /**
     * Releases every persisted reservation this resident holds. A cancelled or dead worker must not keep
     * owning a plan or home, otherwise the coordinator never frees it and the resident stays out of the
     * labour pool forever.
     */
    private void releasePersistedWork(ServerLevel level) {
        var data = SettlementSavedData.get(level);
        String workerId = getUUID().toString();
        data.releaseFarmWorker(workerId);
        data.releaseWorker(workerId);
        var traffic = TransportSavedData.get(level);
        for (var plan : traffic.plans()) {
            if (plan.workerId().orElse("").equals(workerId)) {
                traffic.replace(plan.withWorker(Optional.empty()));
            }
        }
        data.settlement().ifPresent(settlement -> {
            var housing = HousingSavedData.get(level);
            for (var home : housing.homes(settlement.id())) {
                if (home.workerId().orElse("").equals(workerId)) {
                    housing.replace(home.withWorker(Optional.empty()));
                }
            }
        });
        data.acknowledgeCancelledWorker(workerId);
    }

    @Override
    protected void customServerAiStep(ServerLevel level) {
        stopAtClosedBridge(level);
        super.customServerAiStep(level);
        stopAtClosedBridge(level);
        if (tickCount % REGISTRATION_INTERVAL_TICKS != 0) {
            return;
        }
        var settlementData = SettlementSavedData.get(level);
        if (settlementData.settlement().isPresent() && settlementData.isClaimed(blockPosition())) {
            settlementData.registerAdult(getUUID().toString());
        }
        if (settlementData.isCancelledWorker(getUUID().toString())) {
            applyProjectCancellation(level);
            return;
        }
        if (workStage == WorkStage.IDLE || workStage == WorkStage.COMPLETE
                || workStage == WorkStage.RECOVERED || workStage == WorkStage.ABORTED) {
            return;
        }
        WorkKind kind = workKind(workStage);
        if (kind != null && tickCount % ProfessionRules.workIntervalTicks(kind, profession()) != 0) {
            return;
        }
        if (workStage == WorkStage.TRANSPORT_FETCHING
                || workStage == WorkStage.TRANSPORT_DELIVERING
                || workStage == WorkStage.TRANSPORT_RETURNING) {
            tickTransportWork(level);
            return;
        }
        if (workStage == WorkStage.TRANSPORT_COMPLETE) return;
        if (workStage == WorkStage.HOUSING_FETCHING
                || workStage == WorkStage.HOUSING_DELIVERING
                || workStage == WorkStage.HOUSING_RETURNING) {
            tickHousingWork(level);
            return;
        }
        if (workStage == WorkStage.HOUSING_COMPLETE) return;
        if (workStage == WorkStage.MINING_DIGGING) {
            tickMiningWork(level);
            return;
        }
        if (workStage == WorkStage.SMELT_FEEDING || workStage == WorkStage.SMELT_WAITING
                || workStage == WorkStage.SMELT_COLLECTING) {
            tickSmeltingWork(level);
            return;
        }
        if (hasForestryWork(settlementId)) {
            tickForestryWork(level);
            return;
        }
        if (workStage == WorkStage.FORESTRY_COMPLETE) {
            return;
        }
        if (workStage == WorkStage.FOOD_FETCHING || workStage == WorkStage.FOOD_CRAFTING
                || workStage == WorkStage.FOOD_RETURNING) {
            tickFoodWork(level);
            return;
        }
        if (workStage == WorkStage.TOOL_FETCHING || workStage == WorkStage.TOOL_CRAFTING
                || workStage == WorkStage.TOOL_RETURNING) {
            tickToolWork(level);
            return;
        }
        if (workStage == WorkStage.FARM_FETCHING_SEED || workStage == WorkStage.FARM_PLANTING
                || workStage == WorkStage.FARM_HARVESTING || workStage == WorkStage.FARM_RETURNING) {
            tickFarmWork(level);
            return;
        }
        if (workStage == WorkStage.FOOD_COMPLETE) {
            return;
        }
        if (workStage == WorkStage.TOOL_COMPLETE) {
            return;
        }
        if (workStage == WorkStage.FARM_COMPLETE) {
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

    private void tickTransportWork(ServerLevel level) {
        var assignedStep = TransportCoordinator.assignedStep(level, transportPlanId,
                getUUID().toString(), transportStepIndex);
        if (assignedStep.isEmpty()) {
            if (carried.isEmpty()) {
                workStage = WorkStage.TRANSPORT_COMPLETE;
                waitReason = "";
                return;
            }
            workStage = WorkStage.TRANSPORT_RETURNING;
        }
        if (assignedStep.isPresent()
                && !TransportCoordinator.assignmentReady(level, transportPlanId)) {
            waitReason = "transport footprint inactive or unsafe";
            getNavigation().stop();
            return;
        }
        if (workStage == WorkStage.TRANSPORT_RETURNING && carried.isEmpty()) {
            workStage = WorkStage.TRANSPORT_COMPLETE;
            waitReason = "";
            return;
        }
        BlockPos destination = supplyPos;
        if (workStage == WorkStage.TRANSPORT_DELIVERING && assignedStep.isPresent()) {
            TransportPlan.Rule rule = assignedStep.orElseThrow().rule();
            destination = rule == TransportPlan.Rule.ROAD_GROUND
                    || rule == TransportPlan.Rule.APPROACH_GROUND
                    ? buildPos.above() : buildPos;
        }
        if (WorldModificationPermission.check(level, settlementId, destination)
                != WorldModificationPermission.Decision.ALLOWED
                || !level.shouldTickBlocksAt(blockPosition())) {
            waitReason = "transport area inactive or protected";
            getNavigation().stop();
            return;
        }
        if (distanceToSqr(destination.getX() + 0.5, destination.getY() + 0.5,
                destination.getZ() + 0.5) > 8.0) {
            waitReason = "walking with transport material";
            getNavigation().moveTo(destination.getX() + 0.5, destination.getY(),
                    destination.getZ() + 0.5, 1.0);
            return;
        }
        getNavigation().stop();
        if (workStage == WorkStage.TRANSPORT_FETCHING) {
            if (!carried.isEmpty()) {
                workStage = WorkStage.TRANSPORT_DELIVERING;
                return;
            }
            if (!(level.getBlockEntity(supplyPos) instanceof Container container)) {
                waitReason = "transport warehouse missing";
                return;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.getItem(slot).is(TransportCoordinator.materialItem(
                        assignedStep.orElseThrow().material()))) continue;
                ItemStack withdrawn = container.removeItem(slot, 1);
                if (withdrawn.is(TransportCoordinator.materialItem(
                        assignedStep.orElseThrow().material())) && withdrawn.getCount() == 1) {
                    carried = withdrawn;
                    container.setChanged();
                    workStage = WorkStage.TRANSPORT_DELIVERING;
                    waitReason = "";
                }
                return;
            }
            waitReason = "transport material missing";
        } else if (workStage == WorkStage.TRANSPORT_DELIVERING) {
            if (carried.isEmpty()) {
                workStage = WorkStage.TRANSPORT_COMPLETE;
                return;
            }
            if (level.getBlockState(buildPos).is(Blocks.OAK_PLANKS)
                    || level.getBlockState(buildPos).is(Blocks.OAK_LOG)
                    || level.getBlockState(buildPos).is(Blocks.OAK_FENCE)
                    || level.getBlockState(buildPos).is(Blocks.TORCH)) {
                workStage = WorkStage.TRANSPORT_RETURNING;
                return;
            }
            if (TransportCoordinator.placeByResident(level, this, transportPlanId,
                    transportStepIndex, carried)) {
                carried = ItemStack.EMPTY;
                workStage = WorkStage.TRANSPORT_COMPLETE;
                waitReason = "";
            } else {
                waitReason = "transport site blocked or unsafe";
            }
        } else if (workStage == WorkStage.TRANSPORT_RETURNING) {
            if (returnCarriedToSupply(level)) {
                workStage = WorkStage.TRANSPORT_COMPLETE;
                waitReason = "";
            } else {
                waitReason = "return warehouse missing or full";
            }
        }
    }

    private void tickHousingWork(ServerLevel level) {
        boolean assigned = HousingCoordinator.assignedSite(level, housingBed,
                getUUID().toString(), buildPos);
        if (!assigned) {
            if (carried.isEmpty()) {
                workStage = WorkStage.HOUSING_COMPLETE;
                waitReason = "";
                return;
            }
            workStage = WorkStage.HOUSING_RETURNING;
        }
        if (workStage == WorkStage.HOUSING_RETURNING && carried.isEmpty()) {
            workStage = WorkStage.HOUSING_COMPLETE;
            waitReason = "";
            return;
        }
        BlockPos destination = supplyPos;
        if (workStage == WorkStage.HOUSING_DELIVERING) {
            destination = housingApproach(level);
            if (destination == null) {
                waitReason = "no safe housing work position";
                getNavigation().stop();
                return;
            }
        }
        if (WorldModificationPermission.check(level, settlementId, destination)
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "housing area inactive or protected";
            getNavigation().stop();
            return;
        }
        if (distanceToSqr(destination.getCenter()) > 2.0) {
            waitReason = "walking with housing material";
            getNavigation().moveTo(destination.getX() + 0.5, destination.getY(),
                    destination.getZ() + 0.5, 1.0);
            return;
        }
        getNavigation().stop();
        if (workStage == WorkStage.HOUSING_FETCHING) {
            if (!carried.isEmpty()) {
                workStage = WorkStage.HOUSING_DELIVERING;
                return;
            }
            if (!(level.getBlockEntity(supplyPos) instanceof Container container)) {
                waitReason = "housing warehouse missing";
                return;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.getItem(slot).is(Items.OAK_PLANKS)) continue;
                ItemStack withdrawn = container.removeItem(slot, 1);
                if (withdrawn.is(Items.OAK_PLANKS) && withdrawn.getCount() == 1) {
                    carried = withdrawn;
                    container.setChanged();
                    workStage = WorkStage.HOUSING_DELIVERING;
                    waitReason = "";
                }
                return;
            }
            waitReason = "housing planks missing";
        } else if (workStage == WorkStage.HOUSING_DELIVERING) {
            if (level.getBlockState(buildPos).is(Blocks.OAK_PLANKS)) {
                workStage = WorkStage.HOUSING_RETURNING;
                return;
            }
            if (HousingCoordinator.placeByResident(level, this, housingBed, buildPos, carried)) {
                carried = ItemStack.EMPTY;
                workStage = WorkStage.HOUSING_COMPLETE;
                waitReason = "";
            } else {
                waitReason = "housing site blocked or unsafe";
            }
        } else if (workStage == WorkStage.HOUSING_RETURNING) {
            if (returnCarriedToSupply(level)) {
                workStage = WorkStage.HOUSING_COMPLETE;
                waitReason = "";
            } else {
                waitReason = "housing return warehouse missing or full";
            }
        }
    }

    private BlockPos housingApproach(ServerLevel level) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                BlockPos foot = new BlockPos(buildPos.getX() + dx, housingBed.getY(),
                        buildPos.getZ() + dz);
                if (foot.distSqr(buildPos) > 16 || !level.shouldTickBlocksAt(foot)
                        || WorldModificationPermission.check(level, settlementId, foot)
                                != WorldModificationPermission.Decision.ALLOWED
                        || !level.getBlockState(foot).isAir()
                        || !level.getBlockState(foot.above()).isAir()
                        || !level.getBlockState(foot.below()).isFaceSturdy(level, foot.below(), Direction.UP)) {
                    continue;
                }
                double distance = blockPosition().distSqr(foot);
                if (distance < bestDistance) {
                    best = foot;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean returnCarriedToSupply(ServerLevel level) {
        if (!(level.getBlockEntity(supplyPos) instanceof Container container)) return false;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.canPlaceItem(slot, carried)) continue;
            ItemStack existing = container.getItem(slot);
            if (existing.isEmpty()) {
                int moved = Math.min(carried.getCount(),
                        Math.min(carried.getMaxStackSize(), container.getMaxStackSize(carried)));
                container.setItem(slot, carried.split(moved));
            } else if (ItemStack.isSameItemSameComponents(existing, carried)) {
                int room = Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing))
                        - existing.getCount();
                if (room <= 0) continue;
                int moved = Math.min(room, carried.getCount());
                existing.grow(moved);
                carried.shrink(moved);
                container.setChanged();
            } else continue;
            if (carried.isEmpty()) return true;
        }
        return false;
    }

    private void tickSmeltingWork(ServerLevel level) {
        SmeltingSavedData saved = SmeltingSavedData.get(level);
        // The saved batch is the authority; a reloaded worker recovers its ore from there rather than NBT.
        if (smeltOre == null) {
            smeltOre = saved.batch().map(SmeltingSavedData.Batch::ore).orElse(null);
            if (smeltOre == null) {
                workStage = WorkStage.IDLE;
                waitReason = "";
                return;
            }
        }
        if (WorldModificationPermission.check(level, settlementId, blockPosition())
                != WorldModificationPermission.Decision.ALLOWED
                || WorldModificationPermission.check(level, settlementId, buildPos)
                != WorldModificationPermission.Decision.ALLOWED
                || WorldModificationPermission.check(level, settlementId, supplyPos)
                != WorldModificationPermission.Decision.ALLOWED
                || !level.shouldTickBlocksAt(buildPos)) {
            waitReason = "smelt area inactive or protected";
            getNavigation().stop();
            return;
        }
        if (!level.getBlockState(buildPos).is(Blocks.FURNACE)) {
            workStage = WorkStage.IDLE; // The furnace is gone; the coordinator reconciles the batch.
            waitReason = "";
            getNavigation().stop();
            return;
        }
        if (workStage == WorkStage.SMELT_WAITING) {
            var batch = saved.batch();
            if (batch.isEmpty() || !batch.get().furnace().equals(buildPos)) {
                workStage = WorkStage.IDLE;
                waitReason = "";
                return;
            }
            FurnaceWorksite.forgetLostBatch(level);
            if (saved.batch().isEmpty()) {
                workStage = WorkStage.IDLE;
                waitReason = "";
                return;
            }
            if (level.getBlockEntity(buildPos) instanceof Container furnace
                    && furnace.getItem(2).is(smeltOre.ingot())) {
                workStage = WorkStage.SMELT_COLLECTING;
                return;
            }
            waitReason = "waiting for smelt";
            return;
        }
        if (distanceToSqr(buildPos.getX() + 0.5, buildPos.getY() + 0.5, buildPos.getZ() + 0.5) > 9.0) {
            waitReason = "walking to furnace";
            getNavigation().moveTo(buildPos.getX() + 0.5, buildPos.getY(), buildPos.getZ() + 0.5, 1.0);
            return;
        }
        getNavigation().stop();
        if (workStage == WorkStage.SMELT_FEEDING) {
            if (saved.batch().isPresent()) {
                workStage = WorkStage.SMELT_WAITING; // Another feed won the single global batch slot.
                return;
            }
            if (FurnaceWorksite.feed(level, this, settlementId, supplyPos, buildPos, smeltOre)) {
                workStage = WorkStage.SMELT_WAITING;
                waitReason = "";
            } else {
                waitReason = "smelt feed blocked";
            }
        } else if (workStage == WorkStage.SMELT_COLLECTING) {
            if (saved.batch().isEmpty()) {
                workStage = WorkStage.IDLE;
                waitReason = "";
                return;
            }
            if (FurnaceWorksite.collect(level, this)) {
                workStage = WorkStage.IDLE;
                waitReason = "";
            } else {
                workStage = WorkStage.SMELT_WAITING;
                waitReason = "output not ready";
            }
        }
    }

    private void tickMiningWork(ServerLevel level) {
        if (WorldModificationPermission.check(level, settlementId, blockPosition())
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "worker outside active claimed land";
            getNavigation().stop();
            return;
        }
        Optional<MiningWorksite.Site> site = MiningWorksite.assess(level, settlementId, buildPos);
        if (site.isEmpty()) {
            // The ore is gone, replaced, or its neighbours became unsafe: stop instead of swinging at air.
            workStage = WorkStage.IDLE;
            waitReason = "";
            getNavigation().stop();
            return;
        }
        BlockPos stand = site.orElseThrow().stand();
        if (WorldModificationPermission.check(level, settlementId, stand)
                != WorldModificationPermission.Decision.ALLOWED
                || !level.shouldTickBlocksAt(stand)) {
            waitReason = "mining site inactive or protected";
            getNavigation().stop();
            return;
        }
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            waitReason = "mobGriefing disabled";
            getNavigation().stop();
            return;
        }
        if (distanceToSqr(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5) > 4.0) {
            waitReason = "walking to ore";
            getNavigation().moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 1.0);
            return;
        }
        getNavigation().stop();
        if (MiningWorksite.extract(level, this, settlementId, site.orElseThrow(), supplyPos)) {
            workStage = WorkStage.IDLE;
            waitReason = "";
        } else {
            // Self-healing: a broken pickaxe, a full warehouse, or a lost site all resolve on their own.
            waitReason = "mining blocked";
        }
    }

    private void tickForestryWork(ServerLevel level) {
        if (WorldModificationPermission.check(level, settlementId, blockPosition())
                != WorldModificationPermission.Decision.ALLOWED
                || WorldModificationPermission.check(level, settlementId, supplyPos)
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "worker or warehouse inactive or protected";
            getNavigation().stop();
            return;
        }
        boolean atWork = workStage == WorkStage.FORESTRY_FELLING
                || workStage == WorkStage.FORESTRY_PROCESSING
                || workStage == WorkStage.FORESTRY_SAPLING_RECOVERING;
        BlockPos target = atWork ? buildPos : supplyPos;
        if (WorldModificationPermission.check(level, settlementId, target)
                != WorldModificationPermission.Decision.ALLOWED
                || (workStage == WorkStage.FORESTRY_FELLING
                    && WorldModificationPermission.check(level, settlementId, forestryRoot.below())
                    != WorldModificationPermission.Decision.ALLOWED)) {
            waitReason = "forestry target inactive or protected";
            getNavigation().stop();
            return;
        }
        if (distanceToSqr(target.getX() + 0.5, target.getY() + 0.5,
                target.getZ() + 0.5) > 8.0) {
            waitReason = "walking";
            getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            return;
        }
        getNavigation().stop();
        switch (workStage) {
            case FORESTRY_FETCHING -> fetchFellingTools(level);
            case FORESTRY_FELLING -> fellMarkedOak(level);
            case FORESTRY_LOG_RETURNING, FORESTRY_PLANK_RETURNING -> returnForestryGoods(level);
            case FORESTRY_SAPLING_RECOVERING -> recoverRealSapling(level);
            case FORESTRY_PROCESS_FETCHING -> fetchOakLog(level);
            case FORESTRY_PROCESSING -> processOakLog(level);
            default -> { }
        }
    }

    private void recoverRealSapling(ServerLevel level) {
        if (!registeredForestryTree(level)) {
            workStage = WorkStage.FORESTRY_COMPLETE;
            waitReason = "marked tree removed";
            return;
        }
        ItemEntity drop;
        try {
            var entity = level.getEntity(java.util.UUID.fromString(pickupItemId));
            if (!(entity instanceof ItemEntity item) || !item.isAlive()
                    || !item.getItem().is(Items.OAK_SAPLING)) {
                workStage = WorkStage.FORESTRY_COMPLETE;
                waitReason = "sapling drop missing";
                return;
            }
            drop = item;
        } catch (IllegalArgumentException exception) {
            workStage = WorkStage.FORESTRY_COMPLETE;
            waitReason = "sapling drop id invalid";
            return;
        }
        if (forestryRoot.distSqr(drop.blockPosition()) > 36
                || WorldModificationPermission.check(level, settlementId, drop.blockPosition())
                != WorldModificationPermission.Decision.ALLOWED) {
            workStage = WorkStage.FORESTRY_COMPLETE;
            waitReason = "sapling drop left permitted tree area";
            return;
        }
        if (distanceToSqr(drop) > 4.0) {
            buildPos = drop.blockPosition().immutable();
            getNavigation().moveTo(drop, 1.0);
            waitReason = "walking to sapling";
            return;
        }
        ItemStack recovered = drop.getItem().split(1);
        if (recovered.isEmpty()) {
            return;
        }
        if (drop.getItem().isEmpty()) {
            drop.discard();
        } else {
            drop.setItem(drop.getItem());
        }
        forestryGoods.add(recovered);
        pickupItemId = "";
        workStage = WorkStage.FORESTRY_LOG_RETURNING;
        waitReason = "";
    }

    private boolean registeredForestryTree(ServerLevel level) {
        return ForestrySavedData.get(level).markedTrees().stream().anyMatch(
                tree -> tree.settlementId().equals(settlementId) && tree.root().equals(forestryRoot));
    }

    private static boolean forestryAxe(ItemStack stack) {
        return stack.is(Items.WOODEN_AXE) || stack.is(Items.STONE_AXE);
    }

    private void fetchFellingTools(ServerLevel level) {
        if (!registeredForestryTree(level)
                || ForestryCoordinator.nextLog(level, settlementId, forestryRoot)
                        .filter(buildPos::equals).isEmpty()) {
            workStage = forestryGoods.isEmpty() ? WorkStage.FORESTRY_COMPLETE
                    : WorkStage.FORESTRY_LOG_RETURNING;
            waitReason = "marked trunk changed";
            return;
        }
        var data = SettlementSavedData.get(level);
        if (!data.warehouses().contains(supplyPos)
                || !(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "public warehouse missing";
            return;
        }
        boolean needsSapling = buildPos.equals(forestryRoot);
        if (forestryGoods.stream().anyMatch(GoblinCitizenEntity::forestryAxe)) {
            workStage = WorkStage.FORESTRY_FELLING;
            return;
        }
        if (needsSapling && !ForestryCoordinator.hasItem(container, Items.OAK_SAPLING)) {
            waitReason = "oak sapling missing";
            return;
        }
        ItemStack axe = ItemStack.EMPTY;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (forestryAxe(container.getItem(slot))) {
                axe = container.removeItem(slot, 1);
                break;
            }
        }
        if (axe.isEmpty() && !ForestryCoordinator.canRecoverWithoutAxe(level, data)) {
            waitReason = "axe missing";
            return;
        }
        if (!axe.isEmpty()) {
            forestryGoods.add(axe);
        }
        if (needsSapling) {
            ItemStack sapling = takeItems(container, Items.OAK_SAPLING, 1);
            if (!sapling.isEmpty()) {
                forestryGoods.add(sapling);
            }
        }
        container.setChanged();
        workStage = needsSapling && forestryGoods.stream().noneMatch(stack -> stack.is(Items.OAK_SAPLING))
                ? WorkStage.FORESTRY_LOG_RETURNING : WorkStage.FORESTRY_FELLING;
        waitReason = workStage == WorkStage.FORESTRY_FELLING ? "" : "sapling withdrawal incomplete";
    }

    private void fellMarkedOak(ServerLevel level) {
        if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            waitReason = "mobGriefing disabled";
            return;
        }
        if (!registeredForestryTree(level)
                || ForestryCoordinator.nextLog(level, settlementId, forestryRoot)
                        .filter(buildPos::equals).isEmpty()) {
            workStage = WorkStage.FORESTRY_LOG_RETURNING;
            waitReason = "marked trunk changed, returning tools";
            return;
        }
        ItemStack axe = forestryGoods.stream().filter(GoblinCitizenEntity::forestryAxe)
                .findFirst().orElse(ItemStack.EMPTY);
        boolean isRoot = buildPos.equals(forestryRoot);
        ItemStack sapling = forestryGoods.stream().filter(stack -> stack.is(Items.OAK_SAPLING))
                .findFirst().orElse(ItemStack.EMPTY);
        boolean bareHandRecovery = axe.isEmpty()
                && ForestryCoordinator.canRecoverWithoutAxe(level, SettlementSavedData.get(level));
        if ((axe.isEmpty() && !bareHandRecovery) || (isRoot && sapling.isEmpty())) {
            workStage = WorkStage.FORESTRY_LOG_RETURNING;
            waitReason = "axe or replacement sapling missing";
            return;
        }
        // Bare hands are deliberately slower and only recover a real marked log.
        if (bareHandRecovery && tickCount % 40 != 0) {
            waitReason = "slow hand felling";
            return;
        }
        // Replacing the root directly avoids a persistent bare-soil interval.
        if (!level.setBlock(buildPos, (isRoot ? Blocks.OAK_SAPLING : Blocks.AIR).defaultBlockState(), 3)) {
            waitReason = "tree block change failed";
            return;
        }
        if (isRoot) {
            sapling.shrink(1);
        }
        if (!axe.isEmpty()) {
            axe.setDamageValue(axe.getDamageValue() + 1);
            if (axe.getDamageValue() >= axe.getMaxDamage()) {
                axe.shrink(1);
            }
        }
        forestryGoods.removeIf(ItemStack::isEmpty);
        forestryGoods.add(new ItemStack(Items.OAK_LOG));
        workStage = WorkStage.FORESTRY_LOG_RETURNING;
        waitReason = "";
    }

    private void fetchOakLog(ServerLevel level) {
        if (!forestryGoods.isEmpty()) {
            workStage = WorkStage.FORESTRY_PROCESSING;
            return;
        }
        var data = SettlementSavedData.get(level);
        if (!data.warehouses().contains(supplyPos)
                || !(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "public warehouse missing";
            return;
        }
        if (WorldModificationPermission.check(level, settlementId, buildPos)
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "worktable inactive or protected";
            return;
        }
        if (!level.getBlockState(buildPos).is(Blocks.CRAFTING_TABLE)) {
            workStage = WorkStage.FORESTRY_COMPLETE;
            waitReason = "worktable missing";
            return;
        }
        ItemStack log = takeItems(container, Items.OAK_LOG, 1);
        if (log.isEmpty()) {
            workStage = WorkStage.FORESTRY_COMPLETE;
            waitReason = "oak log missing";
            return;
        }
        forestryGoods.add(log);
        container.setChanged();
        workStage = WorkStage.FORESTRY_PROCESSING;
        waitReason = "";
    }

    private void processOakLog(ServerLevel level) {
        if (!level.getBlockState(buildPos).is(Blocks.CRAFTING_TABLE)
                || forestryGoods.size() != 1
                || !forestryGoods.getFirst().is(Items.OAK_LOG)
                || forestryGoods.getFirst().getCount() != 1) {
            workStage = WorkStage.FORESTRY_PLANK_RETURNING;
            waitReason = "worktable or log changed, returning";
            return;
        }
        // One real oak log yields four real oak planks.
        forestryGoods.clear();
        forestryGoods.add(new ItemStack(Items.OAK_PLANKS, 4));
        workStage = WorkStage.FORESTRY_PLANK_RETURNING;
        waitReason = "";
    }

    private void returnForestryGoods(ServerLevel level) {
        if (forestryGoods.isEmpty()) {
            workStage = WorkStage.FORESTRY_COMPLETE;
            waitReason = "";
            return;
        }
        if (!SettlementSavedData.get(level).warehouses().contains(supplyPos)
                || !(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "public warehouse missing";
            return;
        }
        for (ItemStack goods : forestryGoods) {
            for (int slot = 0; slot < container.getContainerSize() && !goods.isEmpty(); slot++) {
                if (!container.canPlaceItem(slot, goods)) {
                    continue;
                }
                ItemStack existing = container.getItem(slot);
                int capacity = Math.min(goods.getMaxStackSize(), container.getMaxStackSize(goods));
                if (existing.isEmpty()) {
                    int moved = Math.min(capacity, goods.getCount());
                    ItemStack placed = goods.copy();
                    placed.setCount(moved);
                    container.setItem(slot, placed);
                    goods.shrink(moved);
                } else if (ItemStack.isSameItemSameComponents(existing, goods)) {
                    int moved = Math.min(Math.max(0, capacity - existing.getCount()), goods.getCount());
                    if (moved > 0) {
                        existing.grow(moved);
                        goods.shrink(moved);
                        container.setChanged();
                    }
                }
            }
        }
        forestryGoods.removeIf(ItemStack::isEmpty);
        if (forestryGoods.isEmpty()) {
            workStage = WorkStage.FORESTRY_COMPLETE;
            waitReason = "";
        } else {
            waitReason = "warehouse full";
        }
    }
    private void tickFoodWork(ServerLevel level) {
        if (WorldModificationPermission.check(level, settlementId, blockPosition())
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "worker outside active claimed land";
            getNavigation().stop();
            return;
        }
        BlockPos target = workStage == WorkStage.FOOD_CRAFTING ? buildPos : supplyPos;
        if (WorldModificationPermission.check(level, settlementId, target)
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "warehouse or worktable inactive or protected";
            getNavigation().stop();
            return;
        }
        if (distanceToSqr(target.getX() + 0.5, target.getY() + 0.5,
                target.getZ() + 0.5) > 8.0) {
            waitReason = "walking";
            getNavigation().moveTo(target.getX() + 0.5, target.getY(),
                    target.getZ() + 0.5, 1.0);
            return;
        }
        getNavigation().stop();
        switch (workStage) {
            case FOOD_FETCHING -> fetchFoodIngredients(level);
            case FOOD_CRAFTING -> craftBread(level);
            case FOOD_RETURNING -> returnFoodGoods(level);
            default -> { }
        }
    }

    private void fetchFoodIngredients(ServerLevel level) {
        if (!foodGoods.isEmpty()) {
            workStage = countFoodGoods(Items.WHEAT) == 3
                    ? WorkStage.FOOD_CRAFTING : WorkStage.FOOD_RETURNING;
            return;
        }
        var data = SettlementSavedData.get(level);
        if (!data.warehouses().contains(supplyPos)) {
            waitReason = "public warehouse missing";
            return;
        }
        var stock = PublicWarehouseInventory.snapshot(level, data);
        if (!stock.complete()) {
            waitReason = "warehouse stock incomplete";
            return;
        }
        if (!FoodCraftingCoordinator.needsBread(stock, data)) {
            workStage = WorkStage.FOOD_COMPLETE;
            waitReason = "food target reached";
            return;
        }
        if (!(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "public warehouse missing";
            return;
        }
        // Wheat seeds are a separate item and are never eligible for bread.
        if (countItems(container, Items.WHEAT) < 3) {
            waitReason = "wheat missing";
            return;
        }
        ItemStack wheat = takeItems(container, Items.WHEAT, 3);
        if (!wheat.isEmpty()) {
            foodGoods.add(wheat);
        }
        container.setChanged();
        if (countFoodGoods(Items.WHEAT) == 3) {
            workStage = WorkStage.FOOD_CRAFTING;
            waitReason = "";
        } else {
            workStage = WorkStage.FOOD_RETURNING;
            waitReason = "wheat withdrawal incomplete, returning";
        }
    }

    private void craftBread(ServerLevel level) {
        if (!level.getBlockState(buildPos).is(Blocks.CRAFTING_TABLE)
                || foodGoods.size() != 1 || !foodGoods.getFirst().is(Items.WHEAT)
                || foodGoods.getFirst().getCount() != 3) {
            workStage = WorkStage.FOOD_RETURNING;
            waitReason = "worktable or ingredients changed, returning";
            return;
        }
        var data = SettlementSavedData.get(level);
        var stock = PublicWarehouseInventory.snapshot(level, data);
        if (!stock.complete()) {
            waitReason = "warehouse stock incomplete";
            return;
        }
        if (!FoodCraftingCoordinator.needsBread(stock, data)) {
            workStage = WorkStage.FOOD_RETURNING;
            waitReason = "food target reached, returning wheat";
            return;
        }
        // The vanilla bread recipe consumes three wheat and produces one bread.
        foodGoods.clear();
        foodGoods.add(new ItemStack(Items.BREAD));
        workStage = WorkStage.FOOD_RETURNING;
        waitReason = "";
    }

    private void returnFoodGoods(ServerLevel level) {
        if (foodGoods.isEmpty()) {
            workStage = WorkStage.FOOD_COMPLETE;
            waitReason = "";
            return;
        }
        if (!SettlementSavedData.get(level).warehouses().contains(supplyPos)
                || !(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "public warehouse missing";
            return;
        }
        for (ItemStack goods : foodGoods) {
            for (int slot = 0; slot < container.getContainerSize() && !goods.isEmpty(); slot++) {
                if (!container.canPlaceItem(slot, goods)) {
                    continue;
                }
                ItemStack existing = container.getItem(slot);
                int capacity = Math.min(goods.getMaxStackSize(), container.getMaxStackSize(goods));
                if (existing.isEmpty()) {
                    int moved = Math.min(capacity, goods.getCount());
                    ItemStack placed = goods.copy();
                    placed.setCount(moved);
                    container.setItem(slot, placed);
                    goods.shrink(moved);
                } else if (ItemStack.isSameItemSameComponents(existing, goods)) {
                    int moved = Math.min(Math.max(0, capacity - existing.getCount()), goods.getCount());
                    if (moved > 0) {
                        existing.grow(moved);
                        goods.shrink(moved);
                        container.setChanged();
                    }
                }
            }
        }
        foodGoods.removeIf(ItemStack::isEmpty);
        if (foodGoods.isEmpty()) {
            workStage = WorkStage.FOOD_COMPLETE;
            waitReason = "";
        } else {
            waitReason = "warehouse full";
        }
    }

    private int countFoodGoods(Item item) {
        return foodGoods.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private void tickToolWork(ServerLevel level) {
        if (WorldModificationPermission.check(level, settlementId, blockPosition())
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "worker outside active claimed land";
            getNavigation().stop();
            return;
        }
        BlockPos target = workStage == WorkStage.TOOL_CRAFTING ? buildPos : supplyPos;
        if (WorldModificationPermission.check(level, settlementId, target)
                != WorldModificationPermission.Decision.ALLOWED) {
            waitReason = "warehouse or worktable inactive or protected";
            getNavigation().stop();
            return;
        }
        if (distanceToSqr(target.getX() + 0.5, target.getY() + 0.5,
                target.getZ() + 0.5) > 8.0) {
            waitReason = "walking";
            getNavigation().moveTo(target.getX() + 0.5, target.getY(),
                    target.getZ() + 0.5, 1.0);
            return;
        }
        getNavigation().stop();
        switch (workStage) {
            case TOOL_FETCHING -> fetchToolIngredients(level);
            case TOOL_CRAFTING -> craftWoodenTool(level);
            case TOOL_RETURNING -> returnToolGoods(level);
            default -> { }
        }
    }

    private void fetchToolIngredients(ServerLevel level) {
        if (!toolGoods.isEmpty()) {
            workStage = toolKind == null ? WorkStage.TOOL_RETURNING : WorkStage.TOOL_CRAFTING;
            return;
        }
        var data = SettlementSavedData.get(level);
        if (toolKind == null || !data.warehouses().contains(supplyPos)) {
            waitReason = "tool job or public warehouse missing";
            return;
        }
        var stock = PublicWarehouseInventory.snapshot(level, data);
        if (!stock.complete()) {
            waitReason = "warehouse stock incomplete";
            return;
        }
        if (ToolCraftingCoordinator.inStock(stock, toolKind)) {
            workStage = WorkStage.TOOL_COMPLETE;
            waitReason = "tool supplied elsewhere";
            return;
        }
        if (!(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "public warehouse missing";
            return;
        }
        if (!ToolCraftingCoordinator.hasIngredients(container, toolKind)) {
            waitReason = "oak planks missing";
            return;
        }
        int availableSticks = countItems(container, Items.STICK);
        int planksNeeded = toolKind.planks() + (availableSticks >= 2 ? 0 : 2);
        ItemStack planks = takeItems(container, Items.OAK_PLANKS, planksNeeded);
        if (!planks.isEmpty()) {
            toolGoods.add(planks);
        }
        if (availableSticks >= 2) {
            ItemStack sticks = takeItems(container, Items.STICK, 2);
            if (!sticks.isEmpty()) {
                toolGoods.add(sticks);
            }
        }
        container.setChanged();
        if (countGoods(Items.OAK_PLANKS) == planksNeeded
                && countGoods(Items.STICK) == (availableSticks >= 2 ? 2 : 0)) {
            workStage = WorkStage.TOOL_CRAFTING;
            waitReason = "";
        } else {
            workStage = WorkStage.TOOL_RETURNING;
            waitReason = "ingredient withdrawal incomplete, returning";
        }
    }

    private void craftWoodenTool(ServerLevel level) {
        if (toolKind == null || !level.getBlockState(buildPos).is(Blocks.CRAFTING_TABLE)) {
            workStage = WorkStage.TOOL_RETURNING;
            waitReason = "worktable missing, returning ingredients";
            return;
        }
        int planks = countGoods(Items.OAK_PLANKS);
        int sticks = countGoods(Items.STICK);
        boolean madeSticks = sticks == 0 && planks == toolKind.planks() + 2;
        if (!madeSticks && !(sticks == 2 && planks == toolKind.planks())) {
            workStage = WorkStage.TOOL_RETURNING;
            waitReason = "ingredients changed, returning";
            return;
        }
        var stock = PublicWarehouseInventory.snapshot(level, SettlementSavedData.get(level));
        if (!stock.complete()) {
            waitReason = "warehouse stock incomplete";
            return;
        }
        if (ToolCraftingCoordinator.inStock(stock, toolKind)) {
            workStage = WorkStage.TOOL_RETURNING;
            waitReason = "tool supplied elsewhere, returning ingredients";
            return;
        }
        // This mirrors the vanilla recipes: two planks make four sticks; the
        // tool uses two sticks and two or three planks. Keep the two spare sticks.
        toolGoods.clear();
        toolGoods.add(new ItemStack(toolKind.item()));
        if (madeSticks) {
            toolGoods.add(new ItemStack(Items.STICK, 2));
        }
        workStage = WorkStage.TOOL_RETURNING;
        waitReason = "";
    }

    private void returnToolGoods(ServerLevel level) {
        if (toolGoods.isEmpty()) {
            workStage = WorkStage.TOOL_COMPLETE;
            waitReason = "";
            return;
        }
        if (!SettlementSavedData.get(level).warehouses().contains(supplyPos)
                || !(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "public warehouse missing";
            return;
        }
        for (ItemStack goods : toolGoods) {
            for (int slot = 0; slot < container.getContainerSize() && !goods.isEmpty(); slot++) {
                if (!container.canPlaceItem(slot, goods)) {
                    continue;
                }
                ItemStack existing = container.getItem(slot);
                int capacity = Math.min(goods.getMaxStackSize(), container.getMaxStackSize(goods));
                if (existing.isEmpty()) {
                    int moved = Math.min(capacity, goods.getCount());
                    ItemStack placed = goods.copy();
                    placed.setCount(moved);
                    container.setItem(slot, placed);
                    goods.shrink(moved);
                } else if (ItemStack.isSameItemSameComponents(existing, goods)) {
                    int moved = Math.min(Math.max(0, capacity - existing.getCount()), goods.getCount());
                    if (moved > 0) {
                        existing.grow(moved);
                        goods.shrink(moved);
                        container.setChanged();
                    }
                }
            }
        }
        toolGoods.removeIf(ItemStack::isEmpty);
        if (toolGoods.isEmpty()) {
            workStage = WorkStage.TOOL_COMPLETE;
            waitReason = "";
        } else {
            waitReason = "warehouse full";
        }
    }

    private int countGoods(Item item) {
        return toolGoods.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getCount).sum();
    }

    private static int countItems(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                count += container.getItem(slot).getCount();
            }
        }
        return count;
    }

    private static ItemStack takeItems(Container container, Item item, int amount) {
        ItemStack taken = ItemStack.EMPTY;
        for (int slot = 0; slot < container.getContainerSize() && amount > 0; slot++) {
            if (!container.getItem(slot).is(item)) {
                continue;
            }
            ItemStack part = container.removeItem(slot, amount);
            if (part.isEmpty()) {
                continue;
            }
            if (taken.isEmpty()) {
                taken = part;
            } else {
                taken.grow(part.getCount());
            }
            amount -= part.getCount();
        }
        return taken;
    }

    private void tickFarmWork(ServerLevel level) {
        BlockPos target = workStage == WorkStage.FARM_FETCHING_SEED || workStage == WorkStage.FARM_RETURNING
                ? supplyPos : buildPos;
        if (WorldModificationPermission.check(level, settlementId, target)
                != WorldModificationPermission.Decision.ALLOWED
                || (workStage != WorkStage.FARM_RETURNING
                    && WorldModificationPermission.check(level, settlementId, buildPos.below())
                    != WorldModificationPermission.Decision.ALLOWED)) {
            waitReason = "farm or warehouse inactive or protected";
            getNavigation().stop();
            return;
        }
        if (distanceToSqr(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5) > 8.0) {
            waitReason = "walking";
            getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 1.0);
            return;
        }
        getNavigation().stop();
        if (workStage != WorkStage.FARM_RETURNING
                && !level.getGameRules().get(GameRules.MOB_GRIEFING)) {
            waitReason = "mobGriefing disabled";
            return;
        }
        switch (workStage) {
            case FARM_FETCHING_SEED -> fetchFarmSeed(level);
            case FARM_PLANTING -> plantFarmSeed(level);
            case FARM_HARVESTING -> harvestFarmCrop(level);
            case FARM_RETURNING -> returnFarmGoods(level);
            default -> { }
        }
    }

    private void fetchFarmSeed(ServerLevel level) {
        if (!carried.isEmpty()) {
            workStage = WorkStage.FARM_PLANTING;
            return;
        }
        if (!(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "warehouse missing";
            return;
        }
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(Items.WHEAT_SEEDS)) {
                ItemStack seed = container.removeItem(slot, 1);
                if (!seed.isEmpty()) {
                    carried = seed;
                    container.setChanged();
                    workStage = WorkStage.FARM_PLANTING;
                    waitReason = "";
                }
                return;
            }
        }
        waitReason = "wheat seeds missing";
    }

    private void plantFarmSeed(ServerLevel level) {
        if (!carried.is(Items.WHEAT_SEEDS)) {
            workStage = farmGoods.isEmpty() ? WorkStage.FARM_COMPLETE : WorkStage.FARM_RETURNING;
            waitReason = "seed missing";
            return;
        }
        if (!level.getBlockState(buildPos.below()).is(Blocks.FARMLAND)) {
            farmGoods.add(carried);
            carried = ItemStack.EMPTY;
            workStage = WorkStage.FARM_RETURNING;
            waitReason = "farmland missing, returning seed";
            return;
        }
        if (!level.getBlockState(buildPos).isAir()) {
            farmGoods.add(carried);
            carried = ItemStack.EMPTY;
            workStage = WorkStage.FARM_RETURNING;
            waitReason = "crop site occupied, returning seed";
            return;
        }
        if (level.setBlock(buildPos, Blocks.WHEAT.defaultBlockState(), 3)) {
            carried = ItemStack.EMPTY;
            workStage = farmGoods.isEmpty() ? WorkStage.FARM_COMPLETE : WorkStage.FARM_RETURNING;
            waitReason = "";
        } else {
            waitReason = "planting failed";
        }
    }

    private void harvestFarmCrop(ServerLevel level) {
        if (!level.getBlockState(buildPos.below()).is(Blocks.FARMLAND)) {
            workStage = WorkStage.FARM_COMPLETE;
            waitReason = "farmland missing";
            return;
        }
        var crop = level.getBlockState(buildPos);
        if (!crop.is(Blocks.WHEAT) || !((CropBlock) Blocks.WHEAT).isMaxAge(crop)) {
            workStage = WorkStage.FARM_COMPLETE;
            waitReason = "";
            return;
        }
        var drops = Block.getDrops(crop, level, buildPos, level.getBlockEntity(buildPos), this, ItemStack.EMPTY);
        if (level.setBlock(buildPos, Blocks.AIR.defaultBlockState(), 3)) {
            farmGoods.addAll(drops.stream().filter(stack -> !stack.isEmpty()).toList());
            for (ItemStack goods : farmGoods) {
                if (goods.is(Items.WHEAT_SEEDS)) {
                    carried = goods.split(1);
                    break;
                }
            }
            farmGoods.removeIf(ItemStack::isEmpty);
            workStage = carried.isEmpty()
                    ? (farmGoods.isEmpty() ? WorkStage.FARM_COMPLETE : WorkStage.FARM_RETURNING)
                    : WorkStage.FARM_PLANTING;
            waitReason = "";
        } else {
            waitReason = "harvest failed";
        }
    }

    private void returnFarmGoods(ServerLevel level) {
        if (farmGoods.isEmpty()) {
            workStage = WorkStage.FARM_COMPLETE;
            waitReason = "";
            return;
        }
        if (!(level.getBlockEntity(supplyPos) instanceof Container container)) {
            waitReason = "warehouse missing";
            return;
        }
        for (ItemStack goods : farmGoods) {
            for (int slot = 0; slot < container.getContainerSize() && !goods.isEmpty(); slot++) {
                if (!container.canPlaceItem(slot, goods)) {
                    continue;
                }
                ItemStack existing = container.getItem(slot);
                if (existing.isEmpty()) {
                    container.setItem(slot, goods.copy());
                    goods.setCount(0);
                } else if (ItemStack.isSameItemSameComponents(existing, goods)) {
                    int space = Math.min(existing.getMaxStackSize(), container.getMaxStackSize(existing))
                            - existing.getCount();
                    if (space > 0) {
                        int moved = Math.min(space, goods.getCount());
                        existing.grow(moved);
                        goods.shrink(moved);
                        container.setChanged();
                    }
                }
            }
        }
        farmGoods.removeIf(ItemStack::isEmpty);
        if (farmGoods.isEmpty()) {
            workStage = WorkStage.FARM_COMPLETE;
            waitReason = "";
        } else {
            waitReason = "warehouse full";
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

    /** Refuse a navigation path whose remaining nodes cross an unfinished bridge. */
    private void stopAtClosedBridge(ServerLevel level) {
        var path = getNavigation().getPath();
        if (path == null) return;
        var transport = TransportSavedData.get(level);
        for (int index = path.getNextNodeIndex(); index < path.getNodeCount(); index++) {
            BlockPos foot = path.getNodePos(index);
            boolean assignedBuilderOnSafeDeck = workStage == WorkStage.TRANSPORT_DELIVERING
                    && transport.plan(transportPlanId)
                            .filter(plan -> plan.workerId().orElse("").equals(getUUID().toString())
                                    && plan.closedFootprint().contains(foot))
                            .isPresent()
                    && level.getBlockState(foot.below()).isFaceSturdy(level, foot.below(), Direction.UP);
            if (transport.isBridgeClosedAt(foot) && !assignedBuilderOnSafeDeck) {
                getNavigation().stop();
                return;
            }
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
        output.putString("GoblinTransportPlanId", transportPlanId);
        output.store("GoblinTransportStepIndex", Codec.INT, transportStepIndex);
        output.store("GoblinHousingBed", BlockPos.CODEC, housingBed);
        if (!carried.isEmpty()) {
            output.store("GoblinCarried", ItemStack.CODEC, carried);
        }
        if (!farmGoods.isEmpty()) {
            output.store("GoblinFarmGoods", ItemStack.CODEC.listOf(), farmGoods);
        }
        if (!toolGoods.isEmpty()) {
            output.store("GoblinToolGoods", ItemStack.CODEC.listOf(), toolGoods);
        }
        if (!foodGoods.isEmpty()) {
            output.store("GoblinFoodGoods", ItemStack.CODEC.listOf(), foodGoods);
        }
        if (!forestryGoods.isEmpty()) {
            output.store("GoblinForestryGoods", ItemStack.CODEC.listOf(), forestryGoods);
        }
        output.store("GoblinForestryRoot", BlockPos.CODEC, forestryRoot);
        if (toolKind != null) {
            output.putString("GoblinToolKind", toolKind.name());
        }
        if (smeltOre != null) {
            output.putString("GoblinSmeltOre", smeltOre.name());
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
        transportPlanId = input.getStringOr("GoblinTransportPlanId", "");
        transportStepIndex = input.read("GoblinTransportStepIndex", Codec.INT).orElse(-1);
        housingBed = input.read("GoblinHousingBed", BlockPos.CODEC).orElse(BlockPos.ZERO);
        carried = input.read("GoblinCarried", ItemStack.CODEC).orElse(ItemStack.EMPTY);
        farmGoods = new ArrayList<>(input.read("GoblinFarmGoods", ItemStack.CODEC.listOf()).orElse(List.of()));
        toolGoods = new ArrayList<>(input.read("GoblinToolGoods", ItemStack.CODEC.listOf()).orElse(List.of()));
        foodGoods = new ArrayList<>(input.read("GoblinFoodGoods", ItemStack.CODEC.listOf()).orElse(List.of()));
        forestryGoods = new ArrayList<>(input.read("GoblinForestryGoods", ItemStack.CODEC.listOf()).orElse(List.of()));
        forestryRoot = input.read("GoblinForestryRoot", BlockPos.CODEC).orElse(BlockPos.ZERO);
        if (workStage == WorkStage.FORESTRY_FETCHING && !forestryGoods.isEmpty()) {
            workStage = forestryGoods.stream().anyMatch(GoblinCitizenEntity::forestryAxe)
                    ? WorkStage.FORESTRY_FELLING : WorkStage.FORESTRY_LOG_RETURNING;
        }
        if (workStage == WorkStage.FORESTRY_PROCESS_FETCHING && !forestryGoods.isEmpty()) {
            workStage = WorkStage.FORESTRY_PROCESSING;
        }
        if ((workStage == WorkStage.FORESTRY_LOG_RETURNING
                || workStage == WorkStage.FORESTRY_PLANK_RETURNING) && forestryGoods.isEmpty()) {
            workStage = WorkStage.FORESTRY_COMPLETE;
        }
        if (workStage == WorkStage.FOOD_FETCHING && !foodGoods.isEmpty()) {
            workStage = foodGoods.size() == 1 && foodGoods.getFirst().is(Items.WHEAT)
                    && foodGoods.getFirst().getCount() == 3
                    ? WorkStage.FOOD_CRAFTING : WorkStage.FOOD_RETURNING;
        }
        if (workStage == WorkStage.FOOD_CRAFTING && foodGoods.isEmpty()) {
            workStage = WorkStage.FOOD_RETURNING;
        }
        if (workStage == WorkStage.FOOD_RETURNING && foodGoods.isEmpty()) {
            workStage = WorkStage.FOOD_COMPLETE;
        }
        try {
            toolKind = WoodToolKind.valueOf(input.getStringOr("GoblinToolKind", ""));
        } catch (IllegalArgumentException exception) {
            toolKind = null;
        }
        try {
            smeltOre = FurnaceWorksite.Ore.valueOf(input.getStringOr("GoblinSmeltOre", ""));
        } catch (IllegalArgumentException exception) {
            smeltOre = null;
        }
        if (workStage == WorkStage.TOOL_FETCHING && !toolGoods.isEmpty()) {
            workStage = toolKind == null ? WorkStage.TOOL_RETURNING : WorkStage.TOOL_CRAFTING;
        }
        if (workStage == WorkStage.TOOL_CRAFTING && toolKind == null) {
            workStage = WorkStage.TOOL_RETURNING;
        }
        if (workStage == WorkStage.TOOL_RETURNING && toolGoods.isEmpty()) {
            workStage = WorkStage.TOOL_COMPLETE;
        }
        if (!carried.isEmpty() && workStage == WorkStage.FETCHING) {
            workStage = WorkStage.DELIVERING;
        }
        if (!carried.isEmpty() && workStage == WorkStage.TRANSPORT_FETCHING) {
            workStage = WorkStage.TRANSPORT_DELIVERING;
        }
        if (!carried.isEmpty() && workStage == WorkStage.HOUSING_FETCHING) {
            workStage = WorkStage.HOUSING_DELIVERING;
        }
        if (!carried.isEmpty() && workStage == WorkStage.RECOVERING) {
            workStage = WorkStage.RETURNING;
        }
        if (!carried.isEmpty() && workStage == WorkStage.FARM_FETCHING_SEED) {
            workStage = WorkStage.FARM_PLANTING;
        }
        if (farmGoods.isEmpty() && workStage == WorkStage.FARM_RETURNING) {
            workStage = WorkStage.FARM_COMPLETE;
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
            ItemEntity dropped = spawnAtLocation(level, carried.copy());
            if (dropped != null) {
                data.recordRecoverableDrop(getUUID().toString(), dropped.getUUID().toString(),
                        dropped.blockPosition());
                carried = ItemStack.EMPTY;
            } else {
                LOGGER.warn("Goblin {} could not drop carried {} on death at {}; material remains on dying entity",
                        getUUID(), carried, blockPosition());
            }
        }
        dropDeathGoods(level, farmGoods, "farm");
        dropDeathGoods(level, toolGoods, "tool");
        dropDeathGoods(level, foodGoods, "food");
        dropDeathGoods(level, forestryGoods, "forestry");
    }

    private void dropDeathGoods(ServerLevel level, List<ItemStack> goods, String source) {
        for (int index = goods.size() - 1; index >= 0; index--) {
            ItemStack stack = goods.get(index);
            if (stack.isEmpty()) {
                goods.remove(index);
                continue;
            }
            if (spawnAtLocation(level, stack.copy()) != null) {
                goods.remove(index);
            } else {
                LOGGER.warn("Goblin {} could not drop {} goods {} on death at {}; material remains on dying entity",
                        getUUID(), source, stack, blockPosition());
            }
        }
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }
}
