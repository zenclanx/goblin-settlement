package dev.local.goblinsettlement.housing;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;

/** The single authority on how many beds the settlement really has. */
public final class BedCensus {
    private static final WeakHashMap<ServerLevel, Snapshot> SNAPSHOTS = new WeakHashMap<>();

    private record Snapshot(long tick, List<BlockPos> heads) {
    }

    private BedCensus() {
    }

    public static List<BlockPos> heads(ServerLevel level, SettlementSavedData data) {
        Snapshot snapshot = SNAPSHOTS.get(level);
        if (snapshot == null || snapshot.tick() != level.getGameTime()) {
            snapshot = new Snapshot(level.getGameTime(), scan(level, data));
            SNAPSHOTS.put(level, snapshot);
        }
        return snapshot.heads();
    }

    public static int count(ServerLevel level, SettlementSavedData data) {
        return heads(level, data).size();
    }

    public static boolean shortage(ServerLevel level, SettlementSavedData data) {
        return HousingRules.needsCapacity(heads(level, data).size(), data.occupiedPopulationSlots());
    }

    private static List<BlockPos> scan(ServerLevel level, SettlementSavedData data) {
        // Walk every claimed plot's 8x8x(anchorY-2..+5) window, keeping bed heads that are
        // loaded, permitted and open above, exactly as FamilyCoordinator.validBed decides.
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return List.of();
        }
        String id = settlement.get().id();
        int anchorY = settlement.get().anchor().getY();
        List<BlockPos> heads = new ArrayList<>();
        for (var plot : data.claimedPlots()) {
            for (int x = plot.x() * 8; x < plot.x() * 8 + 8; x++) {
                for (int z = plot.z() * 8; z < plot.z() * 8 + 8; z++) {
                    for (int y = anchorY - 2; y <= anchorY + 5; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (!level.shouldTickBlocksAt(pos)) continue;
                        if (usableBedHead(level, id, pos)) {
                            heads.add(pos);
                        }
                    }
                }
            }
        }
        return List.copyOf(heads);
    }

    /**
     * The single authority on what counts as a usable bed. Callers must never restate the rule --
     * the census, the family check and provisioning all ask here, so their counts cannot drift apart.
     */
    public static boolean usableBedHead(ServerLevel level, String settlementId, BlockPos bed) {
        var state = level.getBlockState(bed);
        boolean headHalf = state.is(BlockTags.BEDS) && state.hasProperty(BedBlock.PART)
                && state.getValue(BedBlock.PART) == BedPart.HEAD;
        boolean headroomClear = level.getBlockState(bed.above()).isAir()
                && level.getBlockState(bed.above(2)).isAir();
        boolean columnPermitted = allowed(level, settlementId, bed)
                && allowed(level, settlementId, bed.above())
                && allowed(level, settlementId, bed.above(2));
        return HousingRules.usableBedHead(headHalf, headroomClear, columnPermitted);
    }

    private static boolean allowed(ServerLevel level, String settlementId, BlockPos pos) {
        return WorldModificationPermission.check(level, settlementId, pos)
                == WorldModificationPermission.Decision.ALLOWED;
    }
}
