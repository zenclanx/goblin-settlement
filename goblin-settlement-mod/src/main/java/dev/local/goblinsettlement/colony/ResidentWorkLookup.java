package dev.local.goblinsettlement.colony;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;

/** Looks up at most the roster's residents instead of scanning a wide entity volume. */
public final class ResidentWorkLookup {
    private ResidentWorkLookup() {
    }

    /** Every loaded, living resident, for callers that need to look at more than one. */
    public static List<GoblinCitizenEntity> loaded(ServerLevel level, SettlementSavedData data) {
        List<GoblinCitizenEntity> found = new ArrayList<>();
        for (var record : data.residents()) {
            if (record.stage() == ResidentRecord.LifeStage.DECEASED) {
                continue;
            }
            try {
                if (level.getEntity(UUID.fromString(record.id())) instanceof GoblinCitizenEntity goblin
                        && goblin.isAlive()) {
                    found.add(goblin);
                }
            } catch (IllegalArgumentException ignored) {
                // A malformed or legacy roster ID cannot identify a loaded worker.
            }
        }
        return found;
    }

    public static boolean anyLoaded(ServerLevel level, SettlementSavedData data,
                                    Predicate<GoblinCitizenEntity> hasWork) {
        return loaded(level, data).stream().anyMatch(hasWork);
    }
}
