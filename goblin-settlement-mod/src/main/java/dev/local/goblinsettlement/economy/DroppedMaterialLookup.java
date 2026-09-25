package dev.local.goblinsettlement.economy;

import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** Finds a worker's plank after vanilla item entities merge into a nearby stack. */
public final class DroppedMaterialLookup {
    private DroppedMaterialLookup() {
    }

    public static Optional<ItemEntity> find(ServerLevel level, String itemId, BlockPos lastPos) {
        try {
            if (level.getEntity(UUID.fromString(itemId)) instanceof ItemEntity exact
                    && exact.getItem().is(Items.OAK_PLANKS)) {
                return Optional.of(exact);
            }
        } catch (IllegalArgumentException ignored) {
            // Older or damaged records can still be located by the recorded drop position.
        }
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(lastPos).inflate(2.5),
                        item -> item.getItem().is(Items.OAK_PLANKS) && item.getItem().getCount() > 1)
                .stream().min(Comparator.comparingDouble(item -> item.blockPosition().distSqr(lastPos)));
    }
}
