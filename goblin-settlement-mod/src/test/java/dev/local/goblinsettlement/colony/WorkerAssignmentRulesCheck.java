package dev.local.goblinsettlement.colony;

import java.util.Optional;

/** Standalone checks runnable with the JDK while Fabric dependencies are unavailable. */
public final class WorkerAssignmentRulesCheck {
    public static void main(String[] args) {
        check(WorkerAssignmentRules.decide(Optional.empty(), true, true)
                == WorkerAssignmentRules.Decision.RELEASE,
                "a lost roster record releases the assignment");
        check(WorkerAssignmentRules.decide(Optional.of(ResidentRecord.LifeStage.DECEASED), true, false)
                == WorkerAssignmentRules.Decision.RELEASE,
                "a dead worker releases the assignment");
        check(WorkerAssignmentRules.decide(Optional.of(ResidentRecord.LifeStage.CHILD), true, false)
                == WorkerAssignmentRules.Decision.RELEASE,
                "a resident who is no longer an adult releases the assignment");
        check(WorkerAssignmentRules.decide(Optional.of(ResidentRecord.LifeStage.ADULT), false, false)
                == WorkerAssignmentRules.Decision.KEEP,
                "an unloaded worker retains its assignment and its carried material");
        check(WorkerAssignmentRules.decide(Optional.of(ResidentRecord.LifeStage.ADULT), true, true)
                == WorkerAssignmentRules.Decision.KEEP,
                "a resident still holding the work keeps the assignment");
        check(WorkerAssignmentRules.decide(Optional.of(ResidentRecord.LifeStage.ADULT), true, false)
                == WorkerAssignmentRules.Decision.RELEASE,
                "a cancelled resident releases the assignment instead of deadlocking it");
        System.out.println("WorkerAssignmentRulesCheck passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
