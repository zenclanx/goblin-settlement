package dev.local.goblinsettlement.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;

/** One claimed wheat cell and its durable worker reservation. */
public record FarmSite(BlockPos cropPos, Optional<String> workerId) {
    public static final Codec<FarmSite> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.fieldOf("crop_pos").forGetter(FarmSite::cropPos),
            Codec.STRING.optionalFieldOf("worker_id").forGetter(FarmSite::workerId)
    ).apply(instance, FarmSite::new));

    public FarmSite {
        cropPos = cropPos.immutable();
    }

    public FarmSite withWorker(String id) {
        if (workerId.isPresent()) {
            throw new IllegalStateException("Farm cell already has a worker");
        }
        return new FarmSite(cropPos, Optional.of(id));
    }

    public FarmSite withoutWorker() {
        return new FarmSite(cropPos, Optional.empty());
    }
}
