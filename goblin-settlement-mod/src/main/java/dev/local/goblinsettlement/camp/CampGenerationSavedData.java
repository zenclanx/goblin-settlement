package dev.local.goblinsettlement.camp;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** A durable one-shot claim for the natural camp in this dimension. */
public final class CampGenerationSavedData extends SavedData {
    private static final Codec<CampGenerationSavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.optionalFieldOf("site").forGetter(data -> data.site),
            Codec.BOOL.optionalFieldOf("layout_complete", false)
                    .forGetter(data -> data.layoutComplete),
            Codec.BOOL.optionalFieldOf("starter_grant_started", false)
                    .forGetter(data -> data.starterGrantStarted),
            Codec.BOOL.optionalFieldOf("population_started", false)
                    .forGetter(data -> data.populationStarted)
    ).apply(instance, CampGenerationSavedData::new));
    private static final SavedDataType<CampGenerationSavedData> TYPE = new SavedDataType<>(
            "goblin_natural_camp", CampGenerationSavedData::new, CODEC, null);

    private Optional<BlockPos> site;
    private boolean layoutComplete;
    private boolean starterGrantStarted;
    private boolean populationStarted;

    public CampGenerationSavedData() {
        this(Optional.empty(), false, false, false);
    }

    private CampGenerationSavedData(Optional<BlockPos> site, boolean layoutComplete, boolean starterGrantStarted,
                                    boolean populationStarted) {
        this.site = site;
        this.layoutComplete = layoutComplete;
        this.starterGrantStarted = starterGrantStarted;
        this.populationStarted = populationStarted;
    }

    public static CampGenerationSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<BlockPos> site() {
        return site;
    }

    public boolean starterGrantStarted() {
        return starterGrantStarted;
    }

    public boolean layoutComplete() {
        return layoutComplete;
    }

    public boolean populationStarted() {
        return populationStarted;
    }

    public void claim(BlockPos pos) {
        if (site.isPresent()) throw new IllegalStateException("Natural camp already claimed");
        site = Optional.of(pos.immutable());
        setDirty();
    }

    public void abandonUnbuiltSite() {
        if (layoutComplete || starterGrantStarted || populationStarted) return;
        site = Optional.empty();
        setDirty();
    }

    /** Set before changing a real chest, so retries never refill a looted chest. */
    public void markStarterGrantStarted() {
        if (!starterGrantStarted) {
            starterGrantStarted = true;
            setDirty();
        }
    }

    public void markLayoutComplete() {
        if (!layoutComplete) {
            layoutComplete = true;
            setDirty();
        }
    }

    public void markPopulationStarted() {
        if (!populationStarted) {
            populationStarted = true;
            setDirty();
        }
    }
}
