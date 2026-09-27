package dev.local.goblinsettlement.economy;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

/** Finds a worker's dropped material after vanilla item entities merge into a nearby stack. */
public final class DroppedMaterialLookup {
    private DroppedMaterialLookup() {
    }

    /**
     * The stack the worker lost: first the entity it remembers, then any nearby stack of the same item.
     * The item is the one the project declared -- matching a fixed item here would recover the wrong
     * material the moment a project is made of anything else.
     */
    public static Optional<ItemEntity> find(ServerLevel level, String entityId, Item item, BlockPos lastPos) {
        try {
            if (level.getEntity(UUID.fromString(entityId)) instanceof ItemEntity exact
                    && exact.getItem().is(item)) {
                return Optional.of(exact);
            }
        } catch (IllegalArgumentException ignored) {
            // Older or damaged records can still be located by the recorded drop position.
        }
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(lastPos).inflate(2.5),
                        found -> found.getItem().is(item) && found.getItem().getCount() > 1)
                .stream().min(Comparator.comparingDouble(found -> found.blockPosition().distSqr(lastPos)));
    }
}
