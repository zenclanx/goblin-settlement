package dev.local.goblinsettlement.colony.family;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/** The child's UUID is reserved at conception, so a retry cannot produce a second child. */
public record PregnancyRecord(String childId, String motherId, String fatherId, long activeTicks) {
    public static final long GESTATION_TICKS = 10L * 60 * 20;

    public static final Codec<PregnancyRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("child_id").forGetter(PregnancyRecord::childId),
            Codec.STRING.fieldOf("mother_id").forGetter(PregnancyRecord::motherId),
            Codec.STRING.fieldOf("father_id").forGetter(PregnancyRecord::fatherId),
            Codec.LONG.fieldOf("active_ticks").forGetter(PregnancyRecord::activeTicks)
    ).apply(instance, PregnancyRecord::new));

    public PregnancyRecord {
        if (childId == null || childId.isBlank() || motherId == null || motherId.isBlank()
                || fatherId == null || fatherId.isBlank() || motherId.equals(fatherId)
                || childId.equals(motherId) || childId.equals(fatherId)
                || activeTicks < 0 || activeTicks > GESTATION_TICKS) {
            throw new IllegalArgumentException("Invalid pregnancy reservation");
        }
    }

    public boolean isReady() {
        return activeTicks == GESTATION_TICKS;
    }

    public PregnancyRecord advance(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("Active ticks cannot be negative");
        }
        return new PregnancyRecord(childId, motherId, fatherId,
                activeTicks + Math.min(ticks, GESTATION_TICKS - activeTicks));
    }
}
