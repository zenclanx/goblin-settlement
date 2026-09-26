package dev.local.goblinsettlement.colony;

import net.minecraft.server.level.ServerLevel;

/**
 * Gives every unassigned adult the trade the settlement currently has fewest of. One pass covers the
 * initial settlers, newly grown adults and residents loaded from saves that predate professions.
 */
public final class ProfessionCoordinator {
    private static final int INTERVAL_TICKS = 200;

    private ProfessionCoordinator() {
    }

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % INTERVAL_TICKS != 0) {
            return;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        if (data.settlement().isEmpty()) {
            return;
        }
        for (String residentId : data.unassignedAdultIds()) {
            data.assignProfession(residentId, ProfessionRules.scarcest(data.assignedProfessions()));
        }
    }
}
