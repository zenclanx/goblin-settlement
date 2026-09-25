package dev.local.goblinsettlement.social;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Per-player relationship with one settlement in this dimension. */
public final class SettlementRelations extends SavedData {
    public record Standing(String playerId, int trust, int offenses) {
        public static final Codec<Standing> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("player_id").forGetter(Standing::playerId),
                Codec.INT.fieldOf("trust").forGetter(Standing::trust),
                Codec.INT.fieldOf("offenses").forGetter(Standing::offenses)
        ).apply(instance, Standing::new));

        public Standing {
            if (playerId == null || playerId.isBlank() || trust < -100 || trust > 100 || offenses < 0) {
                throw new IllegalArgumentException("Invalid settlement standing");
            }
        }
    }

    private static final Codec<SettlementRelations> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("settlement_id", "").forGetter(data -> data.settlementId),
            Standing.CODEC.listOf().optionalFieldOf("standings", List.of()).forGetter(data -> data.standings)
    ).apply(instance, SettlementRelations::new));
    private static final SavedDataType<SettlementRelations> TYPE = new SavedDataType<>(
            "goblin_settlement_relations", SettlementRelations::new, CODEC, null);

    private String settlementId;
    private List<Standing> standings;

    public SettlementRelations() {
        this("", List.of());
    }

    private SettlementRelations(String settlementId, List<Standing> standings) {
        this.settlementId = settlementId;
        this.standings = List.copyOf(standings);
    }

    public static SettlementRelations get(ServerLevel level, String settlementId) {
        SettlementRelations data = level.getDataStorage().computeIfAbsent(TYPE);
        if (!data.settlementId.equals(settlementId)) {
            data.settlementId = settlementId;
            data.standings = List.of();
            data.setDirty();
        }
        return data;
    }

    public int trust(String playerId) {
        return standing(playerId).map(Standing::trust).orElse(0);
    }

    public int offenses(String playerId) {
        return standing(playerId).map(Standing::offenses).orElse(0);
    }

    public boolean isHostile(String playerId) {
        return trust(playerId) <= -60;
    }

    public void recordAssault(String playerId) {
        change(playerId, -30, true);
    }

    public void recordWarehouseDestruction(String playerId) {
        change(playerId, -40, true);
    }

    /** The first witnessed offense is a warning; later offenses cost trust. */
    public void recordWitnessedTheft(String playerId) {
        change(playerId, offenses(playerId) == 0 ? 0 : -10, true);
    }

    /** One real emerald of compensation restores up to 20 points of trust. */
    public boolean compensate(String playerId) {
        if (trust(playerId) >= 0) {
            return false;
        }
        change(playerId, 20, false);
        return true;
    }

    private Optional<Standing> standing(String playerId) {
        return standings.stream().filter(value -> value.playerId().equals(playerId)).findFirst();
    }

    private void change(String playerId, int delta, boolean offense) {
        Standing current = standing(playerId).orElse(new Standing(playerId, 0, 0));
        int trust = Math.max(-100, Math.min(100, current.trust() + delta));
        int offenses = current.offenses() + (offense ? 1 : 0);
        var next = new ArrayList<>(standings);
        next.removeIf(value -> value.playerId().equals(playerId));
        next.add(new Standing(playerId, trust, offenses));
        standings = List.copyOf(next);
        setDirty();
    }
}
