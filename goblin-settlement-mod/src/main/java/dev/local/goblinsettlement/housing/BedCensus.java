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
                        if (validBed(level, id, pos)) {
                            heads.add(pos);
                        }
                    }
                }
            }
        }
        return List.copyOf(heads);
    }

    private static boolean validBed(ServerLevel level, String settlementId, BlockPos bed) {
        if (WorldModificationPermission.check(level, settlementId, bed)
                    != WorldModificationPermission.Decision.ALLOWED
                || WorldModificationPermission.check(level, settlementId, bed.above())
                    != WorldModificationPermission.Decision.ALLOWED
                || WorldModificationPermission.check(level, settlementId, bed.above(2))
                    != WorldModificationPermission.Decision.ALLOWED) {
            return false;
        }
        var state = level.getBlockState(bed);
        return state.is(BlockTags.BEDS) && state.hasProperty(BedBlock.PART)
                && state.getValue(BedBlock.PART) == BedPart.HEAD
                && level.getBlockState(bed.above()).isAir()
                && level.getBlockState(bed.above(2)).isAir();
    }
}
