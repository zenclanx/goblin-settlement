package dev.local.goblinsettlement.economy.food;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ResidentRecord;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.economy.PublicWarehouseInventory;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

/** Eats one item per living resident after one active Minecraft day. */
public final class MealCoordinator {
    private static final int STEP_TICKS = 20;

    private MealCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % STEP_TICKS != 0) {
            return;
        }
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty() || !level.shouldTickBlocksAt(settlement.get().anchor())
                || !residentsActive(level, data)) {
            return;
        }
        var supply = PublicWarehouseInventory.snapshot(level, data);
        if (!supply.complete()) {
            return;
        }
        int diners = data.adultCount() + data.childCount();
        if (diners == 0) {
            return;
        }
        MealClock clock = MealClock.get(level, settlement.get().id());
        clock.advance(STEP_TICKS);
        if (!clock.mealDue() || supply.food() < diners) {
            return;
        }
        int remaining = diners;
        for (var pos : data.warehouses()) {
            if (WorldModificationPermission.check(level, settlement.get().id(), pos)
                    != WorldModificationPermission.Decision.ALLOWED
                    || !(level.getBlockEntity(pos) instanceof Container container)) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
                var stack = container.getItem(slot);
                if (stack.get(DataComponents.FOOD) == null) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                container.setChanged();
            }
            if (remaining == 0) {
                break;
            }
        }
        if (remaining == 0) {
            clock.finishMeal();
        }
    }

    private static boolean residentsActive(ServerLevel level, SettlementSavedData data) {
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.DECEASED
                    || data.pendingNewbornIds().contains(record.id())) {
                continue;
            }
            try {
                var entity = level.getEntity(UUID.fromString(record.id()));
                if (!(entity instanceof GoblinCitizenEntity goblin) || !goblin.isAlive()
                        || !level.shouldTickBlocksAt(goblin.blockPosition())) {
                    return false;
                }
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return data.pendingNewbornIds().isEmpty();
    }
}
