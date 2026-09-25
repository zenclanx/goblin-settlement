package dev.local.goblinsettlement.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

/** Identity and family history live in the settlement, even while the entity is unloaded. */
public record ResidentRecord(String id, LifeStage stage, Optional<String> motherId, Optional<String> fatherId) {
    public enum LifeStage {
        CHILD, ADULT, DECEASED
    }

    private static final Codec<LifeStage> STAGE_CODEC = Codec.STRING.comapFlatMap(value -> {
        try {
            return DataResult.success(LifeStage.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(() -> "Unknown resident life stage: " + value);
        }
    }, LifeStage::name);

    public static final Codec<ResidentRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(ResidentRecord::id),
            STAGE_CODEC.fieldOf("stage").forGetter(ResidentRecord::stage),
            Codec.STRING.optionalFieldOf("mother_id").forGetter(ResidentRecord::motherId),
            Codec.STRING.optionalFieldOf("father_id").forGetter(ResidentRecord::fatherId)
    ).apply(instance, ResidentRecord::new));

    public ResidentRecord {
        if (id == null || id.isBlank() || stage == null || motherId == null || fatherId == null) {
            throw new IllegalArgumentException("Resident identity, stage and parent references are required");
        }
        if (motherId.filter(id::equals).isPresent() || fatherId.filter(id::equals).isPresent()) {
            throw new IllegalArgumentException("A resident cannot be their own parent");
        }
    }

    public static ResidentRecord adult(String id) {
        return new ResidentRecord(id, LifeStage.ADULT, Optional.empty(), Optional.empty());
    }

    public ResidentRecord deceased() {
        return new ResidentRecord(id, LifeStage.DECEASED, motherId, fatherId);
    }
}
