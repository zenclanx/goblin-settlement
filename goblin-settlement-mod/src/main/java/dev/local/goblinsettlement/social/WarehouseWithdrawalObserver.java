package dev.local.goblinsettlement.social;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/** Conservative server-side observation of a direct public-container-to-backpack transfer. */
public final class WarehouseWithdrawalObserver {
    private static final double WITNESS_RADIUS = 12.0;
    private static final double WORKER_EXCLUSION_RADIUS = 3.0;
    private static final Map<UUID, Observation> observations = new HashMap<>();

    private WarehouseWithdrawalObserver() {
    }

    /** Call once from the mod initializer; no client hook is required. */
    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Map<UUID, Observation> next = new HashMap<>();
            for (ServerLevel level : server.getAllLevels()) {
                SettlementSavedData data = SettlementSavedData.get(level);
                var settlement = data.settlement();
                if (settlement.isEmpty()) {
                    continue;
                }
                for (ServerPlayer player : level.players()) {
                    Observation current = observe(level, data, settlement.get().id(), player);
                    if (current == null) {
                        continue;
                    }
                    Observation previous = observations.get(player.getUUID());
                    if (previous != null && previous.menu == current.menu
                            && previous.container == current.container
                            && previous.position.equals(current.position)
                            && previous.settlementId.equals(current.settlementId)) {
                        if (previous.penalized) {
                            current = current.withPenalty();
                        } else if (previous.witnessed && current.witnessed
                                && oneDirectWithdrawal(previous, current)) {
                            SettlementRelations.get(level, current.settlementId)
                                    .recordWitnessedTheft(player.getUUID().toString());
                            current = current.withPenalty();
                        }
                    }
                    next.put(player.getUUID(), current);
                }
            }
            observations.clear();
            observations.putAll(next);
        });
    }

    private static Observation observe(ServerLevel level, SettlementSavedData data,
                                       String settlementId, ServerPlayer player) {
        if (player.isCreative() || player.isSpectator()
                || !(player.containerMenu instanceof ChestMenu menu)
                || !menu.stillValid(player) || !menu.getCarried().isEmpty()) {
            return null;
        }
        Container container = menu.getContainer();
        BlockPos position = null;
        for (BlockPos candidate : data.warehouses()) {
            if (level.shouldTickBlocksAt(candidate)
                    && WorldModificationPermission.check(level, settlementId, candidate)
                            == WorldModificationPermission.Decision.ALLOWED
                    && level.getBlockEntity(candidate) == container) {
                position = candidate;
                break;
            }
        }
        if (position == null) {
            return null; // Includes double chests: their menu uses a CompoundContainer.
        }
        for (ServerPlayer other : level.players()) {
            if (other != player && other.containerMenu instanceof ChestMenu otherMenu
                    && otherMenu.getContainer() == container) {
                return null;
            }
        }
        ItemStack[] stock = copy(container);
        ItemStack[] backpack = new ItemStack[player.getInventory().getNonEquipmentItems().size()];
        for (int slot = 0; slot < backpack.length; slot++) {
            backpack[slot] = player.getInventory().getNonEquipmentItems().get(slot).copy();
        }
        return new Observation(menu, container, position, settlementId, stock, backpack,
                hasUnambiguousWitness(level, data, position, player), false);
    }

    private static ItemStack[] copy(Container container) {
        ItemStack[] items = new ItemStack[container.getContainerSize()];
        for (int slot = 0; slot < items.length; slot++) {
            items[slot] = container.getItem(slot).copy();
        }
        return items;
    }

    private static boolean hasUnambiguousWitness(ServerLevel level, SettlementSavedData data,
                                                 BlockPos warehouse, ServerPlayer player) {
        boolean witnessed = false;
        for (GoblinCitizenEntity resident : level.getEntitiesOfClass(GoblinCitizenEntity.class,
                new AABB(warehouse).inflate(WITNESS_RADIUS), GoblinCitizenEntity::isAlive)) {
            if (data.resident(resident.getUUID().toString()).isEmpty()) {
                continue;
            }
            if (resident.distanceToSqr(warehouse.getCenter()) <= WORKER_EXCLUSION_RADIUS
                    * WORKER_EXCLUSION_RADIUS) {
                return false; // A worker at the chest could have changed its contents.
            }
            if (resident.distanceToSqr(player) <= WITNESS_RADIUS * WITNESS_RADIUS
                    && resident.hasLineOfSight(player)) {
                witnessed = true;
            }
        }
        return witnessed;
    }

    private static boolean oneDirectWithdrawal(Observation before, Observation after) {
        if (before.stock.length != after.stock.length
                || before.backpack.length != after.backpack.length) {
            return false;
        }
        ItemStack taken = ItemStack.EMPTY;
        int amount = 0;
        for (int slot = 0; slot < before.stock.length; slot++) {
            ItemStack old = before.stock[slot];
            ItemStack now = after.stock[slot];
            if (ItemStack.matches(old, now)) {
                continue;
            }
            if (!taken.isEmpty() || old.isEmpty()
                    || (!now.isEmpty() && (!ItemStack.isSameItemSameComponents(old, now)
                    || now.getCount() >= old.getCount()))) {
                return false;
            }
            taken = old;
            amount = old.getCount() - now.getCount();
        }
        if (taken.isEmpty()) {
            return false;
        }
        boolean received = false;
        for (int slot = 0; slot < before.backpack.length; slot++) {
            ItemStack old = before.backpack[slot];
            ItemStack now = after.backpack[slot];
            if (ItemStack.matches(old, now)) {
                continue;
            }
            if (received || now.isEmpty() || !ItemStack.isSameItemSameComponents(taken, now)
                    || (!old.isEmpty() && !ItemStack.isSameItemSameComponents(old, now))
                    || now.getCount() - old.getCount() != amount) {
                return false;
            }
            received = true;
        }
        return received;
    }

    private record Observation(ChestMenu menu, Container container, BlockPos position,
                               String settlementId, ItemStack[] stock, ItemStack[] backpack,
                               boolean witnessed, boolean penalized) {
        Observation withPenalty() {
            return new Observation(menu, container, position, settlementId, stock, backpack,
                    witnessed, true);
        }
    }
}
