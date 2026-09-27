package dev.local.goblinsettlement.planning.transport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/**
 * Whether a walker can get from one declared walking cell to another. The cells are the road's or the
 * bridge's own, so this answers "is what we built passable end to end" and says nothing about the
 * terrain around it, nor about how the vanilla pathfinder would score the trip.
 */
public final class TransportConnectivity {
    private static final int[][] HORIZONTAL = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private TransportConnectivity() {
    }

    /**
     * Walks from from to any of goals, stepping only between cells of walkable. Steps are four-way --
     * never diagonal -- and may rise or drop one block, which is what a walker does over a step, a
     * slope or a deck sitting above its bank. The visited set bounds the work to one visit per cell.
     */
    public static boolean connects(Set<BlockPos> walkable, BlockPos from, Set<BlockPos> goals) {
        if (walkable.isEmpty() || goals.isEmpty() || !walkable.contains(from)) {
            return false;
        }
        var visited = new HashSet<BlockPos>();
        var open = new ArrayDeque<BlockPos>();
        visited.add(from);
        open.add(from);
        while (!open.isEmpty()) {
            BlockPos current = open.poll();
            if (goals.contains(current)) {
                return true;
            }
            for (BlockPos next : neighbours(current)) {
                if (walkable.contains(next) && visited.add(next)) {
                    open.add(next);
                }
            }
        }
        return false;
    }

    private static List<BlockPos> neighbours(BlockPos cell) {
        var neighbours = new ArrayList<BlockPos>(HORIZONTAL.length * 3);
        for (int[] step : HORIZONTAL) {
            for (int drop = -1; drop <= 1; drop++) {
                neighbours.add(new BlockPos(
                        cell.getX() + step[0], cell.getY() + drop, cell.getZ() + step[1]));
            }
        }
        return neighbours;
    }
}
