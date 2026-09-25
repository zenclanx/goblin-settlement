package dev.local.goblinsettlement.defense;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Persistent per-dimension golem roster. Unloading never releases a golem slot. */
public final class DefenseSavedData extends SavedData {
    public record GolemRecord(String id, String settlementId, GolemTier tier) {
        private static final Codec<GolemTier> TIER_CODEC = Codec.STRING.comapFlatMap(value -> {
            try {
                return DataResult.success(GolemTier.valueOf(value));
            } catch (IllegalArgumentException exception) {
                return DataResult.error(() -> "Unknown golem tier: " + value);
            }
        }, GolemTier::name);

        public static final Codec<GolemRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(GolemRecord::id),
                Codec.STRING.fieldOf("settlement_id").forGetter(GolemRecord::settlementId),
                TIER_CODEC.fieldOf("tier").forGetter(GolemRecord::tier)
        ).apply(instance, GolemRecord::new));

        public GolemRecord {
            if (id == null || id.isBlank() || settlementId == null || settlementId.isBlank() || tier == null) {
                throw new IllegalArgumentException("Invalid golem roster record");
            }
        }
    }

    private static final int SCHEMA_VERSION = 1;
    private static final Codec<DefenseSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("schema_version").forGetter(data -> data.schemaVersion),
            GolemRecord.CODEC.listOf().optionalFieldOf("golems", List.of()).forGetter(data -> data.golems)
    ).apply(instance, DefenseSavedData::new));
    private static final SavedDataType<DefenseSavedData> TYPE = new SavedDataType<>(
            "goblin_defense", DefenseSavedData::new, CODEC, null);

    private final int schemaVersion;
    private List<GolemRecord> golems;

    public DefenseSavedData() {
        this(SCHEMA_VERSION, List.of());
    }

    private DefenseSavedData(int schemaVersion, List<GolemRecord> golems) {
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported defense data version: " + schemaVersion);
        }
        this.schemaVersion = schemaVersion;
        this.golems = List.copyOf(golems);
    }

    public static DefenseSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<GolemRecord> golems() {
        return golems;
    }

    public int count(String settlementId) {
        return (int) golems.stream().filter(record -> record.settlementId().equals(settlementId)).count();
    }

    public int obsidianCount(String settlementId) {
        return (int) golems.stream().filter(record -> record.settlementId().equals(settlementId)
                && record.tier() == GolemTier.OBSIDIAN).count();
    }

    public boolean contains(String golemId) {
        return golems.stream().anyMatch(record -> record.id().equals(golemId));
    }

    /** Also used to import a loaded golem missing from an older or interrupted roster. */
    public boolean register(GoblinGolemEntity golem) {
        if (golem == null || golem.settlementId().isBlank() || contains(golem.getUUID().toString())) {
            return false;
        }
        var updated = new ArrayList<>(golems);
        updated.add(new GolemRecord(golem.getUUID().toString(), golem.settlementId(), golem.tier()));
        golems = List.copyOf(updated);
        setDirty();
        return true;
    }

    public boolean updateTier(GoblinGolemEntity golem) {
        if (golem == null) {
            return false;
        }
        for (int index = 0; index < golems.size(); index++) {
            GolemRecord current = golems.get(index);
            if (current.id().equals(golem.getUUID().toString())
                    && current.settlementId().equals(golem.settlementId())
                    && current.tier() != golem.tier()) {
                var updated = new ArrayList<>(golems);
                updated.set(index, new GolemRecord(current.id(), current.settlementId(), golem.tier()));
                golems = List.copyOf(updated);
                setDirty();
                return true;
            }
        }
        return false;
    }

    /** Death is the only ordinary transition that releases the occupied slot. */
    public boolean markDead(String golemId, String settlementId) {
        int before = golems.size();
        golems = golems.stream().filter(record -> !record.id().equals(golemId)
                || !record.settlementId().equals(settlementId)).toList();
        if (golems.size() == before) {
            return false;
        }
        setDirty();
        return true;
    }
}
