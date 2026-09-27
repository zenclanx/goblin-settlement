package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.housing.HousingSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Finds the refuge a resident should run to. */
public final class ShelterCoordinator {
    private ShelterCoordinator() {
    }

    /** The nearest registered home's anchor bed, or empty when the settlement has no home at all. */
    public static Optional<BlockPos> nearestShelter(ServerLevel level, String settlementId,
                                                    BlockPos from) {
        var homes = HousingSavedData.get(level).homes(settlementId);
        if (homes.isEmpty()) {
            return Optional.empty();
        }
        List<int[]> shelters = new ArrayList<>();
        for (var home : homes) {
            BlockPos bed = home.bed();
            shelters.add(new int[] {bed.getX(), bed.getY(), bed.getZ()});
        }
        int index = ShelterRules.nearest(shelters, new int[] {from.getX(), from.getY(), from.getZ()});
        if (index < 0) {
            return Optional.empty();
        }
        int[] chosen = shelters.get(index);
        return Optional.of(new BlockPos(chosen[0], chosen[1], chosen[2]));
    }
}
