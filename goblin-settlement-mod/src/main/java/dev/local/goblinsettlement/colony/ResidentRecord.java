package dev.local.goblinsettlement.colony;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;

/** Identity and family history live in the settlement, even while the entity is unloaded. */
public record ResidentRecord(String id, LifeStage stage, Optional<String> motherId, Optional<String> fatherId,
                             ReproductiveRole reproductiveRole, Profession profession,
                             long childActiveTicks, long restTicks) {
    public static final long GROWTH_TICKS = 60L * 60 * 20;
    public static final long POST_BIRTH_REST_TICKS = 30L * 60 * 20;

    public enum LifeStage {
        CHILD, ADULT, DECEASED
    }

    public enum ReproductiveRole {
        UNSPECIFIED, MOTHER, FATHER
    }

    private static final Codec<LifeStage> STAGE_CODEC = Codec.STRING.comapFlatMap(value -> {
        try {
            return DataResult.success(LifeStage.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(() -> "Unknown resident life stage: " + value);
        }
    }, LifeStage::name);

    private static final Codec<ReproductiveRole> ROLE_CODEC = Codec.STRING.comapFlatMap(value -> {
        try {
            return DataResult.success(ReproductiveRole.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(() -> "Unknown reproductive role: " + value);
        }
    }, ReproductiveRole::name);

    private static final Codec<Profession> PROFESSION_CODEC = Codec.STRING.comapFlatMap(value -> {
        try {
            return DataResult.success(Profession.valueOf(value));
        } catch (IllegalArgumentException exception) {
            return DataResult.error(() -> "Unknown profession: " + value);
        }
    }, Profession::name);

    public static final Codec<ResidentRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(ResidentRecord::id),
            STAGE_CODEC.fieldOf("stage").forGetter(ResidentRecord::stage),
            Codec.STRING.optionalFieldOf("mother_id").forGetter(ResidentRecord::motherId),
            Codec.STRING.optionalFieldOf("father_id").forGetter(ResidentRecord::fatherId),
            ROLE_CODEC.optionalFieldOf("reproductive_role", ReproductiveRole.UNSPECIFIED)
                    .forGetter(ResidentRecord::reproductiveRole),
            PROFESSION_CODEC.optionalFieldOf("profession", Profession.UNASSIGNED)
                    .forGetter(ResidentRecord::profession),
            Codec.LONG.optionalFieldOf("child_active_ticks", 0L).forGetter(ResidentRecord::childActiveTicks),
            Codec.LONG.optionalFieldOf("rest_ticks", 0L).forGetter(ResidentRecord::restTicks)
    ).apply(instance, ResidentRecord::new));

    public ResidentRecord {
        if (id == null || id.isBlank() || stage == null || motherId == null || fatherId == null
                || reproductiveRole == null || profession == null) {
            throw new IllegalArgumentException("Resident identity, stage and parent references are required");
        }
        if (motherId.filter(id::equals).isPresent() || fatherId.filter(id::equals).isPresent()) {
            throw new IllegalArgumentException("A resident cannot be their own parent");
        }
        if (motherId.isPresent() && motherId.equals(fatherId)) {
            throw new IllegalArgumentException("A resident needs distinct parents");
        }
        if (childActiveTicks < 0 || childActiveTicks > GROWTH_TICKS
                || restTicks < 0 || restTicks > POST_BIRTH_REST_TICKS) {
            throw new IllegalArgumentException("Invalid family clock");
        }
    }

    public static ResidentRecord adult(String id) {
        return new ResidentRecord(id, LifeStage.ADULT, Optional.empty(), Optional.empty(),
                ReproductiveRole.UNSPECIFIED, Profession.UNASSIGNED, 0, 0);
    }

    public static ResidentRecord child(String id, String motherId, String fatherId) {
        return new ResidentRecord(id, LifeStage.CHILD, Optional.of(motherId), Optional.of(fatherId),
                ReproductiveRole.UNSPECIFIED, Profession.UNASSIGNED, 0, 0);
    }

    /** Stable fallback for existing residents whose older saves had no role field. */
    public ReproductiveRole effectiveReproductiveRole() {
        if (reproductiveRole != ReproductiveRole.UNSPECIFIED) {
            return reproductiveRole;
        }
        return (Integer.bitCount(id.hashCode()) & 1) == 0
                ? ReproductiveRole.MOTHER : ReproductiveRole.FATHER;
    }

    public ResidentRecord withReproductiveRole(ReproductiveRole role) {
        if (role == null || role == ReproductiveRole.UNSPECIFIED || stage == LifeStage.DECEASED
                || reproductiveRole != ReproductiveRole.UNSPECIFIED) {
            throw new IllegalArgumentException("Only a living resident with no role can be assigned a role");
        }
        return new ResidentRecord(id, stage, motherId, fatherId, role, profession, childActiveTicks, restTicks);
    }

    public ResidentRecord withProfession(Profession value) {
        if (value == null || value == Profession.UNASSIGNED || stage != LifeStage.ADULT
                || profession != Profession.UNASSIGNED) {
            throw new IllegalArgumentException("Only a living adult with no trade can be given one");
        }
        return new ResidentRecord(id, stage, motherId, fatherId, reproductiveRole, value,
                childActiveTicks, restTicks);
    }

    public ResidentRecord advanceFamilyTime(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("Active ticks cannot be negative");
        }
        if (stage == LifeStage.CHILD) {
            long age = childActiveTicks + Math.min(ticks, GROWTH_TICKS - childActiveTicks);
            return new ResidentRecord(id, age == GROWTH_TICKS ? LifeStage.ADULT : LifeStage.CHILD,
                    motherId, fatherId, reproductiveRole, profession, age, 0);
        }
        if (stage == LifeStage.ADULT && restTicks > 0) {
            return new ResidentRecord(id, stage, motherId, fatherId, reproductiveRole, profession,
                    childActiveTicks, restTicks - Math.min(ticks, restTicks));
        }
        return this;
    }

    public ResidentRecord withPostBirthRest() {
        return new ResidentRecord(id, stage, motherId, fatherId, reproductiveRole, profession,
                childActiveTicks, stage == LifeStage.ADULT ? POST_BIRTH_REST_TICKS : restTicks);
    }

    public ResidentRecord deceased() {
        return new ResidentRecord(id, LifeStage.DECEASED, motherId, fatherId,
                reproductiveRole, profession, childActiveTicks, restTicks);
    }
}
