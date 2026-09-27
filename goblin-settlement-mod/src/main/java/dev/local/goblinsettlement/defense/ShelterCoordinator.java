package dev.local.goblinsettlement.defense;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ResidentWorkLookup;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.housing.HousingCoordinator;
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

    /**
     * The nearest home with both walls and a free slot, or empty when there is nowhere to go. A home
     * counts only once its capacity axis has walls up -- a pergola is not a refuge -- and it holds at
     * most the beds its built geometry actually provides.
     */
    public static Optional<BlockPos> nearestShelter(ServerLevel level, String settlementId,
                                                    BlockPos from, GoblinCitizenEntity self) {
        var homes = HousingSavedData.get(level).homes(settlementId);
        if (homes.isEmpty()) {
            return Optional.empty();
        }
        // Counted, not reserved: two residents choosing in the same tick can both see the same free
        // slot and overfill one home by one. A reservation would have to be persisted or locked.
        List<BlockPos> taken = new ArrayList<>();
        for (GoblinCitizenEntity other
                : ResidentWorkLookup.loaded(level, SettlementSavedData.get(level))) {
            if (other == self) {
                continue;
            }
            other.shelterTarget().ifPresent(taken::add);
        }
        List<BlockPos> beds = new ArrayList<>();
        List<ShelterRules.Shelter> shelters = new ArrayList<>();
        for (var home : homes) {
            int capacity = HousingCoordinator.shelterCapacity(level, home);
            if (capacity <= 0) {
                continue;
            }
            BlockPos bed = home.bed();
            int used = 0;
            for (BlockPos target : taken) {
                if (target.equals(bed)) {
                    used++;
                }
            }
            beds.add(bed);
            shelters.add(new ShelterRules.Shelter(bed.getX(), bed.getY(), bed.getZ(), used, capacity));
        }
        int index = ShelterRules.choose(shelters, new int[] {from.getX(), from.getY(), from.getZ()});
        return index < 0 ? Optional.empty() : Optional.of(beds.get(index));
    }
}
