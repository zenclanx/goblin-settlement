package dev.local.goblinsettlement.economy.smelting;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** One persisted furnace batch per dimension; unfinished work survives a normal reload. */
public final class SmeltingSavedData extends SavedData {
    public record Batch(String settlementId, BlockPos furnace, BlockPos warehouse, FurnaceWorksite.Ore ore) {
        public static final Codec<Batch> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("settlement_id").forGetter(Batch::settlementId),
                BlockPos.CODEC.fieldOf("furnace").forGetter(Batch::furnace),
                BlockPos.CODEC.fieldOf("warehouse").forGetter(Batch::warehouse),
                Codec.STRING.xmap(FurnaceWorksite.Ore::valueOf, FurnaceWorksite.Ore::name)
                        .fieldOf("ore").forGetter(Batch::ore)
        ).apply(instance, Batch::new));
    }

    private static final Codec<SmeltingSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("schema_version").forGetter(data -> data.schemaVersion),
            Batch.CODEC.optionalFieldOf("batch").forGetter(data -> data.batch)
    ).apply(instance, SmeltingSavedData::new));
    private static final SavedDataType<SmeltingSavedData> TYPE = new SavedDataType<>(
            "goblin_smelting", SmeltingSavedData::new, CODEC, null);

    private final int schemaVersion;
    private Optional<Batch> batch;

    public SmeltingSavedData() {
        this(1, Optional.empty());
    }

    private SmeltingSavedData(int schemaVersion, Optional<Batch> batch) {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported smelting data version");
        this.schemaVersion = schemaVersion;
        this.batch = batch;
    }

    public static SmeltingSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<Batch> batch() {
        return batch;
    }

    public boolean begin(Batch value) {
        if (value == null || batch.isPresent()) return false;
        batch = Optional.of(value);
        setDirty();
        return true;
    }

    public void clear() {
        if (batch.isPresent()) {
            batch = Optional.empty();
            setDirty();
        }
    }
}
