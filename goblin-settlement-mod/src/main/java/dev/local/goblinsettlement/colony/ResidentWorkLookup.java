package dev.local.goblinsettlement.colony;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;

/** Looks up at most the roster's residents instead of scanning a wide entity volume. */
public final class ResidentWorkLookup {
    private ResidentWorkLookup() {
    }

    public static boolean anyLoaded(ServerLevel level, SettlementSavedData data,
                                    Predicate<GoblinCitizenEntity> hasWork) {
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.DECEASED) {
                continue;
            }
            try {
                var found = level.getEntity(UUID.fromString(record.id()));
                if (found instanceof GoblinCitizenEntity goblin && goblin.isAlive()
                        && hasWork.test(goblin)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                // A malformed or legacy roster ID cannot identify a loaded worker.
            }
        }
        return false;
    }
}
