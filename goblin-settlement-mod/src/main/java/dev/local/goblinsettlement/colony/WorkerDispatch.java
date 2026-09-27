package dev.local.goblinsettlement.colony;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

/** The one place that decides which resident gets a piece of work. */
public final class WorkerDispatch {
    /** The search radius every caller already used. */
    public static final double SEARCH_RADIUS = 16.0;

    private WorkerDispatch() {
    }

    /**
     * A resident is eligible to be hired when they are free and allowed to change blocks where they
     * stand. Five of the eight callers check the second half and three do not, so it stays a
     * predicate the caller chooses -- making it a hidden default would change those three.
     */
    public static Predicate<GoblinCitizenEntity> permitted(ServerLevel level, String settlementId) {
        return goblin -> goblin.isAvailableForConstruction()
                && WorldModificationPermission.check(level, settlementId, goblin.blockPosition())
                        == WorldModificationPermission.Decision.ALLOWED;
    }

    /** The shared hiring rule: profession fit first, then distance to the work. */
    public static Optional<GoblinCitizenEntity> nearest(ServerLevel level, WorkKind kind, BlockPos anchor,
                                                        Predicate<GoblinCitizenEntity> eligible) {
        return level.getEntitiesOfClass(GoblinCitizenEntity.class,
                        new AABB(anchor).inflate(SEARCH_RADIUS), eligible)
                .stream().min(Comparator
                        .comparingInt((GoblinCitizenEntity goblin) ->
                                ProfessionRules.matchRank(kind, goblin.profession()))
                        .thenComparingDouble(goblin -> goblin.blockPosition().distSqr(anchor)));
    }
}
