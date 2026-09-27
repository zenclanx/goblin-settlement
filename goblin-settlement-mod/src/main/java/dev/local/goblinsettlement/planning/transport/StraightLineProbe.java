package dev.local.goblinsettlement.planning.transport;

import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;

/** Bounded, read-only straight-line terrain probe between anchor and target facility. */
public final class StraightLineProbe {
    public static final int MAX_PROBE_COLUMNS = 80;
    private static final int SURFACE_SCAN_UP = 6;
    private static final int SURFACE_SCAN_DOWN = 10;

    public record Sample(List<TrafficDecision.ColumnKind> columns, int targetIndex) {
        public Sample {
            columns = List.copyOf(columns);
        }
    }

    private StraightLineProbe() {
    }

    public static Sample sample(ServerLevel level, String settlementId, BlockPos anchor, BlockPos target) {
        int dx = target.getX() - anchor.getX();
        int dz = target.getZ() - anchor.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps <= 0 || steps > MAX_PROBE_COLUMNS) {
            return new Sample(List.of(), -1);
        }
        List<TrafficDecision.ColumnKind> columns = new ArrayList<>(steps + 1);
        for (int index = 0; index <= steps; index++) {
            BlockPos column = columnAt(anchor, target, index);
            columns.add(classify(level, settlementId, column, anchor.getY()));
        }
        return new Sample(columns, steps);
    }

    /** Shared DDA column mapping; the bridge proposal reuses it to find the near bank. */
    public static BlockPos columnAt(BlockPos anchor, BlockPos target, int index) {
        int dx = target.getX() - anchor.getX();
        int dz = target.getZ() - anchor.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            return anchor.immutable();
        }
        int x = anchor.getX() + (int) Math.round((double) index * dx / steps);
        int z = anchor.getZ() + (int) Math.round((double) index * dz / steps);
        return new BlockPos(x, anchor.getY(), z).immutable();
    }

    /** Re-finds the walkable foot cell of a column classified LAND. */
    public static BlockPos surfaceFoot(ServerLevel level, String settlementId, BlockPos column, int referenceY) {
        for (int y = referenceY + SURFACE_SCAN_UP; y >= referenceY - SURFACE_SCAN_DOWN; y--) {
            BlockPos probe = new BlockPos(column.getX(), y, column.getZ());
            var state = level.getBlockState(probe);
            if (state.isAir() || state.getFluidState().is(FluidTags.WATER)) {
                continue;
            }
            if (state.isFaceSturdy(level, probe, Direction.UP) || state.is(Blocks.DIRT_PATH)) {
                return probe.above().immutable();
            }
            continue; // skip past non-sturdy clutter (grass, leaves) and keep scanning down
        }
        return column.immutable();
    }

    private static TrafficDecision.ColumnKind classify(ServerLevel level, String settlementId,
                                                       BlockPos column, int referenceY) {
        if (WorldModificationPermission.check(level, settlementId, column)
                != WorldModificationPermission.Decision.ALLOWED) {
            return TrafficDecision.ColumnKind.BLOCKED; // inactive, unclaimed, or protected columns
        }
        for (int y = referenceY + SURFACE_SCAN_UP; y >= referenceY - SURFACE_SCAN_DOWN; y--) {
            BlockPos probe = new BlockPos(column.getX(), y, column.getZ());
            var state = level.getBlockState(probe);
            if (state.isAir()) {
                continue;
            }
            if (state.getFluidState().is(FluidTags.WATER)) {
                return TrafficDecision.ColumnKind.WATER;
            }
            return state.isFaceSturdy(level, probe, Direction.UP) || state.is(Blocks.DIRT_PATH)
                    ? TrafficDecision.ColumnKind.LAND : TrafficDecision.ColumnKind.BLOCKED;
        }
        return TrafficDecision.ColumnKind.BLOCKED;
    }
}
