package dev.local.goblinsettlement.housing;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Durable progress for incremental house improvements. Beds remain the capacity authority. */
public final class HousingSavedData extends SavedData {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_HOMES = 48;

    /** Durable two-axis housing targets. Progress itself is derived from world blocks. */
    public record Home(BlockPos bed, int variant, int capacityTarget, int qualityTarget,
                       Optional<String> workerId) {
        static final Codec<Home> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("bed").forGetter(Home::bed),
                Codec.INT.fieldOf("blueprint").forGetter(Home::variant),
                Codec.INT.optionalFieldOf("capacity_target").forGetter(home -> Optional.of(home.capacityTarget())),
                Codec.INT.optionalFieldOf("quality_target").forGetter(home -> Optional.of(home.qualityTarget())),
                Codec.INT.optionalFieldOf("stage").forGetter(home -> Optional.<Integer>empty()),
                Codec.INT.optionalFieldOf("step").forGetter(home -> Optional.<Integer>empty()),
                Codec.STRING.optionalFieldOf("worker_id").forGetter(Home::workerId)
        ).apply(instance, Home::migrate));

        public Home(BlockPos bed, int variant, int capacityTarget, int qualityTarget) {
            this(bed, variant, capacityTarget, qualityTarget, Optional.empty());
        }

        public Home {
            bed = bed.immutable();
            workerId = java.util.Objects.requireNonNull(workerId, "workerId");
            if (variant < 0 || variant > 2
                    || capacityTarget < 0 || capacityTarget > HousingRules.MAX_CAPACITY_TARGET
                    || qualityTarget < 0 || qualityTarget > HousingRules.MAX_QUALITY_TARGET) {
                throw new IllegalArgumentException("Invalid housing targets");
            }
        }

        /**
         * Legacy saves carried a single stage and a build cursor. Stage 5 was reachable (the old
         * advance loop incremented unconditionally and the validator allowed it) and is identical
         * to stage 4 in geometry. The cursor is dropped: progress is derived from the world.
         */
        private static Home migrate(BlockPos bed, int variant, Optional<Integer> capacityTarget,
                                    Optional<Integer> qualityTarget, Optional<Integer> legacyStage,
                                    Optional<Integer> legacyStep, Optional<String> workerId) {
            if (legacyStage.isPresent()) {
                int stage = legacyStage.orElseThrow();
                if (stage < 0 || stage > 5) {
                    throw new IllegalArgumentException("Invalid legacy housing stage: " + stage);
                }
                return new Home(bed, variant, Math.min(stage, 2), Math.min(2, Math.max(0, stage - 2)), workerId);
            }
            return new Home(bed, variant, capacityTarget.orElse(0), qualityTarget.orElse(0), workerId);
        }

        public Home withWorker(Optional<String> value) {
            return new Home(bed, variant, capacityTarget, qualityTarget, value);
        }

        public Home withCapacityTarget(int value) {
            return new Home(bed, variant, value, qualityTarget, workerId);
        }

        public Home withQualityTarget(int value) {
            return new Home(bed, variant, capacityTarget, value, workerId);
        }
    }

    private static final Codec<HousingSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("schema_version", SCHEMA_VERSION).forGetter(data -> SCHEMA_VERSION),
            Codec.STRING.optionalFieldOf("settlement_id", "").forGetter(data -> data.settlementId),
            Home.CODEC.listOf().optionalFieldOf("homes", List.of()).forGetter(data -> data.homes)
    ).apply(instance, HousingSavedData::new));
    private static final SavedDataType<HousingSavedData> TYPE = new SavedDataType<>(
            "goblin_housing", HousingSavedData::new, CODEC, null);

    private String settlementId;
    private List<Home> homes;

    public HousingSavedData() {
        this(SCHEMA_VERSION, "", List.of());
    }

    private HousingSavedData(int schemaVersion, String settlementId, List<Home> homes) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported housing data version: " + schemaVersion);
        }
        this.settlementId = settlementId;
        this.homes = List.copyOf(homes);
    }

    public static HousingSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<Home> homes(String id) {
        return settlementId.equals(id) ? homes : List.of();
    }

    public void useSettlement(String id) {
        if (!settlementId.equals(id)) {
            settlementId = id;
            homes = List.of();
            setDirty();
        }
    }

    public boolean add(Home home) {
        if (homes.size() >= MAX_HOMES || homes.stream().anyMatch(entry -> entry.bed().equals(home.bed()))) {
            return false;
        }
        var next = new ArrayList<>(homes);
        next.add(home);
        homes = List.copyOf(next);
        setDirty();
        return true;
    }

    public void replace(Home home) {
        var next = new ArrayList<>(homes);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).bed().equals(home.bed())) {
                next.set(i, home);
                homes = List.copyOf(next);
                setDirty();
                return;
            }
        }
    }

    public void remove(BlockPos bed) {
        if (homes.stream().noneMatch(home -> home.bed().equals(bed))) return;
        homes = homes.stream().filter(home -> !home.bed().equals(bed)).toList();
        setDirty();
    }
}
