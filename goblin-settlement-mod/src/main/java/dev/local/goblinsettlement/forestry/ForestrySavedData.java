package dev.local.goblinsettlement.forestry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Only explicitly marked trees can be used; marks survive entity and chunk unloads. */
public final class ForestrySavedData extends SavedData {
    public static final int MAX_MARKED_TREES = 16;

    public record MarkedTree(String settlementId, BlockPos root) {
        public static final Codec<MarkedTree> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("settlement_id").forGetter(MarkedTree::settlementId),
                BlockPos.CODEC.fieldOf("root").forGetter(MarkedTree::root)
        ).apply(instance, MarkedTree::new));

        public MarkedTree {
            root = root.immutable();
        }
    }

    private static final Codec<ForestrySavedData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            MarkedTree.CODEC.listOf().optionalFieldOf("marked_trees", List.of())
                    .forGetter(data -> data.markedTrees)
    ).apply(instance, ForestrySavedData::new));

    private static final SavedDataType<ForestrySavedData> TYPE = new SavedDataType<>(
            "goblin_settlement_forestry", ForestrySavedData::new, CODEC, null);

    private List<MarkedTree> markedTrees;

    public ForestrySavedData() {
        this(List.of());
    }

    private ForestrySavedData(List<MarkedTree> markedTrees) {
        this.markedTrees = List.copyOf(markedTrees);
    }

    public static ForestrySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public List<MarkedTree> markedTrees() {
        return markedTrees;
    }

    public boolean register(String settlementId, BlockPos root) {
        if (settlementId == null || settlementId.isBlank() || root == null
                || markedTrees.size() >= MAX_MARKED_TREES
                || markedTrees.stream().anyMatch(tree -> tree.root().equals(root))) {
            return false;
        }
        var updated = new ArrayList<>(markedTrees);
        updated.add(new MarkedTree(settlementId, root));
        markedTrees = List.copyOf(updated);
        setDirty();
        return true;
    }

    public boolean remove(String settlementId, BlockPos root) {
        var updated = new ArrayList<>(markedTrees);
        if (!updated.removeIf(tree -> tree.settlementId().equals(settlementId) && tree.root().equals(root))) {
            return false;
        }
        markedTrees = List.copyOf(updated);
        setDirty();
        return true;
    }
}