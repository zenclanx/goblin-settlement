package dev.local.goblinsettlement.planning.road;

import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.interaction.WorldModificationPermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Finds a candidate foot-level centerline from a registered settlement facility
 * to a target facility. This is a synchronous, bounded server-thread query. It
 * neither reserves materials nor changes blocks, claims, or chunk loading state.
 *
 * <p>The result is not a construction blueprint. A later stage must choose a road
 * width, check its complete footprint, obtain materials, and recheck permissions,
 * terrain, and chunk activity immediately before each world modification.
 */
public final class RoadPlanner {
    private static final int MAX_HORIZONTAL_DISTANCE = 64;
    private static final int MAX_EXPANDED_NODES = 2048;
    private static final int EXISTING_PATH_COST = 5;
    private static final int NEW_PATH_COST = 10;
    private static final int SLOPE_COST = 8;
    private static final int ROUGH_GROUND_COST = 4;
    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST
    };

    public enum Status {
        FOUND,
        NO_SETTLEMENT,
        WRONG_SETTLEMENT,
        TARGET_UNAVAILABLE,
        NO_SOURCE_ACCESS,
        NO_ROUTE,
        SEARCH_LIMIT_REACHED
    }

    public record Candidate(BlockPos sourceFacility, BlockPos targetFacility,
                            List<BlockPos> centerline, long score) {
        public Candidate {
            sourceFacility = sourceFacility.immutable();
            targetFacility = targetFacility.immutable();
            centerline = List.copyOf(centerline);
        }
    }

    public record Result(Status status, Optional<Candidate> candidate, int expandedNodes) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    private enum Cell {
        BLOCKED, NORMAL, ROUGH, EXISTING_PATH
    }

    private record QueueEntry(BlockPos foot, long cost) {
    }

    private RoadPlanner() {
    }

    /**
     * Uses the saved settlement anchor, public warehouses, and registered farm
     * cells as possible source facilities. Each facility is approached from one
     * of its four neighboring columns at up to one block of height difference.
     * The target is a facility block position, not a road tile. All returned
     * centerline positions are clear spaces where a walking entity can stand.
     */
    public static Result planFromRegisteredFacilities(
            ServerLevel level, String settlementId, BlockPos targetFacility) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(targetFacility, "targetFacility");

        SettlementSavedData data = SettlementSavedData.get(level);
        var settlement = data.settlement();
        if (settlement.isEmpty()) {
            return failure(Status.NO_SETTLEMENT, 0);
        }
        if (!settlement.get().id().equals(settlementId)) {
            return failure(Status.WRONG_SETTLEMENT, 0);
        }

        BlockPos target = targetFacility.immutable();
        if (!permitted(level, settlementId, target)) {
            return failure(Status.TARGET_UNAVAILABLE, 0);
        }

        Map<BlockPos, Cell> sampled = new HashMap<>();
        Set<BlockPos> goals = new HashSet<>();
        for (BlockPos access : accessPositions(target)) {
            if (sample(level, settlementId, access, sampled) != Cell.BLOCKED) {
                goals.add(access);
            }
        }
        if (goals.isEmpty()) {
            return failure(Status.TARGET_UNAVAILABLE, 0);
        }

        Set<BlockPos> facilities = new LinkedHashSet<>();
        facilities.add(settlement.get().anchor());
        facilities.addAll(data.warehouses());
        data.farmSites().forEach(site -> facilities.add(site.cropPos()));

        Map<BlockPos, BlockPos> sources = new LinkedHashMap<>();
        for (BlockPos facility : facilities) {
            if (facility.equals(target) || !permitted(level, settlementId, facility)) {
                continue;
            }
            for (BlockPos access : accessPositions(facility)) {
                if (withinRange(access, target)
                        && sample(level, settlementId, access, sampled) != Cell.BLOCKED) {
                    sources.putIfAbsent(access, facility.immutable());
                }
            }
        }
        if (sources.isEmpty()) {
            return failure(Status.NO_SOURCE_ACCESS, 0);
        }

        Comparator<QueueEntry> order = Comparator.comparingLong(QueueEntry::cost)
                .thenComparingInt(entry -> entry.foot().getX())
                .thenComparingInt(entry -> entry.foot().getY())
                .thenComparingInt(entry -> entry.foot().getZ());
        PriorityQueue<QueueEntry> open = new PriorityQueue<>(order);
        Map<BlockPos, Long> best = new HashMap<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Map<BlockPos, BlockPos> origin = new HashMap<>();

        for (var source : sources.entrySet()) {
            best.put(source.getKey(), 0L);
            origin.put(source.getKey(), source.getValue());
            open.add(new QueueEntry(source.getKey(), 0L));
        }

        int expanded = 0;
        while (!open.isEmpty() && expanded < MAX_EXPANDED_NODES) {
            QueueEntry current = open.remove();
            if (current.cost() != best.getOrDefault(current.foot(), Long.MAX_VALUE)) {
                continue;
            }
            expanded++;
            if (goals.contains(current.foot())) {
                List<BlockPos> route = new ArrayList<>();
                BlockPos cursor = current.foot();
                while (cursor != null) {
                    route.add(cursor.immutable());
                    cursor = previous.get(cursor);
                }
                java.util.Collections.reverse(route);
                Candidate candidate = new Candidate(
                        origin.get(current.foot()), target, route, current.cost());
                return new Result(Status.FOUND, Optional.of(candidate), expanded);
            }

            for (Direction direction : HORIZONTAL) {
                int nextX = current.foot().getX() + direction.getStepX();
                int nextZ = current.foot().getZ() + direction.getStepZ();
                for (int deltaY = -1; deltaY <= 1; deltaY++) {
                    BlockPos next = new BlockPos(nextX, current.foot().getY() + deltaY, nextZ);
                    if (!withinRange(next, target)) {
                        continue;
                    }
                    Cell cell = sample(level, settlementId, next, sampled);
                    if (cell == Cell.BLOCKED) {
                        continue;
                    }
                    long step = (cell == Cell.EXISTING_PATH ? EXISTING_PATH_COST : NEW_PATH_COST)
                            + (cell == Cell.ROUGH ? ROUGH_GROUND_COST : 0)
                            + (long) Math.abs(deltaY) * SLOPE_COST;
                    long cost = current.cost() + step;
                    if (cost >= best.getOrDefault(next, Long.MAX_VALUE)) {
                        continue;
                    }
                    best.put(next, cost);
                    previous.put(next, current.foot());
                    origin.put(next, origin.get(current.foot()));
                    open.add(new QueueEntry(next, cost));
                }
            }
        }
        return failure(open.isEmpty() ? Status.NO_ROUTE : Status.SEARCH_LIMIT_REACHED, expanded);
    }

    private static Result failure(Status status, int expanded) {
        return new Result(status, Optional.empty(), expanded);
    }

    private static boolean withinRange(BlockPos point, BlockPos target) {
        long dx = Math.abs((long) point.getX() - target.getX());
        long dz = Math.abs((long) point.getZ() - target.getZ());
        return dx + dz <= MAX_HORIZONTAL_DISTANCE;
    }

    private static List<BlockPos> accessPositions(BlockPos facility) {
        List<BlockPos> accesses = new ArrayList<>(12);
        for (Direction direction : HORIZONTAL) {
            for (int deltaY = -1; deltaY <= 1; deltaY++) {
                accesses.add(facility.relative(direction).offset(0, deltaY, 0).immutable());
            }
        }
        return accesses;
    }

    private static boolean permitted(ServerLevel level, String settlementId, BlockPos pos) {
        return WorldModificationPermission.check(level, settlementId, pos)
                == WorldModificationPermission.Decision.ALLOWED;
    }

    private static Cell sample(ServerLevel level, String settlementId, BlockPos foot,
                               Map<BlockPos, Cell> sampled) {
        Cell cached = sampled.get(foot);
        if (cached != null) {
            return cached;
        }
        Cell result = inspect(level, settlementId, foot);
        sampled.put(foot, result);
        return result;
    }

    private static Cell inspect(ServerLevel level, String settlementId, BlockPos foot) {
        BlockPos head = foot.above();
        BlockPos ground = foot.below();
        if (!permitted(level, settlementId, foot)
                || !permitted(level, settlementId, head)
                || !permitted(level, settlementId, ground)) {
            return Cell.BLOCKED;
        }

        BlockState feetState = level.getBlockState(foot);
        BlockState headState = level.getBlockState(head);
        BlockState groundState = level.getBlockState(ground);
        if (!feetState.isAir() || !headState.isAir()
                || !groundState.getFluidState().isEmpty()
                || (!groundState.is(Blocks.DIRT_PATH)
                    && !groundState.isFaceSturdy(level, ground, Direction.UP))
                || hazardousGround(groundState)) {
            return Cell.BLOCKED;
        }
        if (groundState.is(Blocks.DIRT_PATH)) {
            return Cell.EXISTING_PATH;
        }
        if (groundState.is(Blocks.SAND) || groundState.is(Blocks.RED_SAND)
                || groundState.is(Blocks.GRAVEL) || groundState.is(Blocks.SOUL_SAND)
                || groundState.is(Blocks.ICE) || groundState.is(Blocks.PACKED_ICE)
                || groundState.is(Blocks.BLUE_ICE)) {
            return Cell.ROUGH;
        }
        return Cell.NORMAL;
    }

    private static boolean hazardousGround(BlockState ground) {
        return ground.is(Blocks.FARMLAND) || ground.is(Blocks.MAGMA_BLOCK)
                || ground.is(Blocks.CACTUS) || ground.is(Blocks.CAMPFIRE)
                || ground.is(Blocks.SOUL_CAMPFIRE) || ground.is(Blocks.POWDER_SNOW);
    }
}
