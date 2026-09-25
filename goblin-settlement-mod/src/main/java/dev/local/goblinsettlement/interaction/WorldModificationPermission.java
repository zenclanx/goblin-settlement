package dev.local.goblinsettlement.interaction;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Shared gate for planning and execution; both must recheck before changing blocks. */
public final class WorldModificationPermission {
    public enum Decision {
        NO_SETTLEMENT,
        WRONG_SETTLEMENT,
        PROTECTED_PLAYER_AREA,
        CHUNK_INACTIVE,
        UNCLAIMED_LAND,
        ALLOWED
    }

    private WorldModificationPermission() {
    }

    public static Decision check(ServerLevel level, String settlementId, BlockPos pos) {
        var data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return Decision.NO_SETTLEMENT;
        }
        if (!settlement.get().id().equals(settlementId)) {
            return Decision.WRONG_SETTLEMENT;
        }
        if (data.isProtected(pos)) {
            return Decision.PROTECTED_PLAYER_AREA;
        }
        if (!level.shouldTickBlocksAt(pos)) {
            return Decision.CHUNK_INACTIVE;
        }
        return data.isClaimed(pos) ? Decision.ALLOWED : Decision.UNCLAIMED_LAND;
    }
}
