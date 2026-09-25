package dev.local.goblinsettlement.construction;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** A small persisted blueprint. Inventory stays in physical containers and residents. */
public record ConstructionPlan(BlockPos start, int completed, Optional<String> workerId,
                               Optional<RecoveryDrop> recoveryDrop, Optional<String> lastWorkerId) {
    public static final int LENGTH = 2;
    public record RecoveryDrop(String itemId, BlockPos pos) {
        public static final Codec<RecoveryDrop> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("item_id").forGetter(RecoveryDrop::itemId),
                BlockPos.CODEC.fieldOf("pos").forGetter(RecoveryDrop::pos)
        ).apply(instance, RecoveryDrop::new));
    }

    public static final Codec<ConstructionPlan> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("start").forGetter(ConstructionPlan::start),
            Codec.INT.fieldOf("completed").forGetter(ConstructionPlan::completed),
            Codec.STRING.optionalFieldOf("worker_id").forGetter(ConstructionPlan::workerId),
            RecoveryDrop.CODEC.optionalFieldOf("recovery_drop").forGetter(ConstructionPlan::recoveryDrop),
            Codec.STRING.optionalFieldOf("last_worker_id").forGetter(ConstructionPlan::lastWorkerId)
    ).apply(instance, ConstructionPlan::new));

    public ConstructionPlan {
        if (completed < 0 || completed > LENGTH
                || (completed == LENGTH && (workerId.isPresent() || recoveryDrop.isPresent()))) {
            throw new IllegalArgumentException("Invalid construction plan progress");
        }
        start = start.immutable();
    }

    public boolean isComplete() {
        return completed == LENGTH;
    }

    public BlockPos site() {
        if (isComplete()) {
            throw new IllegalStateException("Completed construction plan has no next site");
        }
        return start.east(completed);
    }

    public ConstructionPlan withWorker(String worker) {
        if (isComplete() || workerId.isPresent()) {
            throw new IllegalStateException("Construction step already assigned or complete");
        }
        return new ConstructionPlan(start, completed, Optional.of(worker), recoveryDrop, lastWorkerId);
    }

    public ConstructionPlan finishStep(String worker) {
        if (!workerId.equals(Optional.of(worker))) {
            throw new IllegalArgumentException("Wrong worker for construction step");
        }
        if (recoveryDrop.isPresent()) {
            throw new IllegalStateException("Recover material before construction advances");
        }
        return new ConstructionPlan(start, completed + 1, Optional.empty(), Optional.empty(), Optional.of(worker));
    }

    public ConstructionPlan withDroppedItem(String itemId, BlockPos pos) {
        if (workerId.isEmpty()) {
            throw new IllegalStateException("No worker owns this material");
        }
        return new ConstructionPlan(start, completed, Optional.empty(),
                Optional.of(new RecoveryDrop(itemId, pos.immutable())), lastWorkerId);
    }

    public ConstructionPlan retargetDrop(String itemId, BlockPos pos) {
        if (recoveryDrop.isEmpty()) {
            throw new IllegalStateException("No dropped material to retarget");
        }
        return new ConstructionPlan(start, completed, workerId,
                Optional.of(new RecoveryDrop(itemId, pos.immutable())), lastWorkerId);
    }

    public ConstructionPlan clearRecovery() {
        return new ConstructionPlan(start, completed, Optional.empty(), Optional.empty(), lastWorkerId);
    }
}
