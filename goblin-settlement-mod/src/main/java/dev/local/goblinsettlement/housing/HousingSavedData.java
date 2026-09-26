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

    public record Home(BlockPos bed, int blueprint, int stage, int step, Optional<String> workerId) {
        public static final Codec<Home> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.fieldOf("bed").forGetter(Home::bed),
                Codec.INT.fieldOf("blueprint").forGetter(Home::blueprint),
                Codec.INT.fieldOf("stage").forGetter(Home::stage),
                Codec.INT.fieldOf("step").forGetter(Home::step),
                Codec.STRING.optionalFieldOf("worker_id").forGetter(Home::workerId)
        ).apply(instance, Home::new));

        public Home(BlockPos bed, int blueprint, int stage, int step) {
            this(bed, blueprint, stage, step, Optional.empty());
        }

        public Home {
            bed = bed.immutable();
            workerId = java.util.Objects.requireNonNull(workerId, "workerId");
            if (blueprint < 0 || blueprint > 2 || stage < 0 || stage > 5 || step < 0 || step > 256) {
                throw new IllegalArgumentException("Invalid housing progress");
            }
        }

        public Home withWorker(Optional<String> value) {
            return new Home(bed, blueprint, stage, step, value);
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
