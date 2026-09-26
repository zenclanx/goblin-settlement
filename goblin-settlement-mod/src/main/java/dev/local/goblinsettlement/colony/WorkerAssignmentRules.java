package dev.local.goblinsettlement.colony;

import java.util.Optional;

/** Decides whether a persisted worker assignment still belongs to its recorded resident. Pure. */
public final class WorkerAssignmentRules {
    private WorkerAssignmentRules() {
    }

    public enum Decision {
        RELEASE, KEEP
    }

    /**
     * @param stage        the roster's life stage for the recorded worker, empty when the roster lost the record
     * @param entityLoaded whether the worker entity is currently loaded
     * @param stillWorking whether that loaded entity still holds this exact assignment
     */
    public static Decision decide(Optional<ResidentRecord.LifeStage> stage,
                                  boolean entityLoaded, boolean stillWorking) {
        if (stage.isEmpty() || stage.get() != ResidentRecord.LifeStage.ADULT) {
            return Decision.RELEASE;
        }
        if (!entityLoaded) {
            return Decision.KEEP;
        }
        return stillWorking ? Decision.KEEP : Decision.RELEASE;
    }
}
