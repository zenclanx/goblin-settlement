package dev.local.goblinsettlement.planning.transport;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

public final class TransportConnectivityCheck {
    public static void main(String[] args) {
        checkStraightLine();
        checkOneBlockRiseAndDrop();
        checkTwoBlockRiseIsNotAWalk();
        checkDiagonalIsNotAWalk();
        checkWallBreaksTheLine();
        checkDetourRestoresIt();
        checkFromMustBeWalkable();
        checkEmptyInputs();
        checkUnreachableGoal();
        checkManyCellsTerminate();
        System.out.println("TransportConnectivityCheck passed");
    }

    private static void checkStraightLine() {
        var cells = set(point(0, 64, 0), point(1, 64, 0), point(2, 64, 0));
        require(TransportConnectivity.connects(cells, point(0, 64, 0), set(point(2, 64, 0))),
                "a straight run connects its two ends");
    }

    private static void checkOneBlockRiseAndDrop() {
        var rise = set(point(0, 64, 0), point(1, 65, 0), point(2, 66, 0));
        require(TransportConnectivity.connects(rise, point(0, 64, 0), set(point(2, 66, 0))),
                "a walker climbs one block per step");
        var drop = set(point(0, 66, 0), point(1, 65, 0), point(2, 64, 0));
        require(TransportConnectivity.connects(drop, point(0, 66, 0), set(point(2, 64, 0))),
                "and drops one block per step");
    }

    private static void checkTwoBlockRiseIsNotAWalk() {
        var cells = set(point(0, 64, 0), point(1, 66, 0));
        require(!TransportConnectivity.connects(cells, point(0, 64, 0), set(point(1, 66, 0))),
                "two blocks of height in one step is a jump, not a walk");
    }

    private static void checkDiagonalIsNotAWalk() {
        var cells = set(point(0, 64, 0), point(1, 64, 1));
        require(!TransportConnectivity.connects(cells, point(0, 64, 0), set(point(1, 64, 1))),
                "a diagonal gap is not a crossing");
    }

    private static void checkWallBreaksTheLine() {
        var cells = set(point(0, 64, 0), point(1, 64, 0), point(3, 64, 0), point(4, 64, 0));
        require(!TransportConnectivity.connects(cells, point(0, 64, 0), set(point(4, 64, 0))),
                "a missing cell in the middle breaks the run");
    }

    private static void checkDetourRestoresIt() {
        var cells = set(point(0, 64, 0), point(1, 64, 0), point(3, 64, 0), point(4, 64, 0),
                point(1, 64, 1), point(2, 64, 1), point(3, 64, 1));
        require(TransportConnectivity.connects(cells, point(0, 64, 0), set(point(4, 64, 0))),
                "a way around the missing cell reconnects the run");
    }

    private static void checkFromMustBeWalkable() {
        var cells = set(point(0, 64, 0), point(1, 64, 0));
        require(!TransportConnectivity.connects(cells, point(9, 64, 9), set(point(1, 64, 0))),
                "a walker who cannot stand on the start cell never sets off");
    }

    private static void checkEmptyInputs() {
        require(!TransportConnectivity.connects(Set.of(), point(0, 64, 0), set(point(1, 64, 0))),
                "no cells means no crossing");
        require(!TransportConnectivity.connects(set(point(0, 64, 0)), point(0, 64, 0), Set.of()),
                "no goal means nothing to reach");
    }

    private static void checkUnreachableGoal() {
        var cells = set(point(0, 64, 0), point(1, 64, 0));
        require(!TransportConnectivity.connects(cells, point(0, 64, 0), set(point(5, 64, 5))),
                "a goal outside the walkable cells is unreachable");
        require(TransportConnectivity.connects(cells, point(0, 64, 0), set(point(0, 64, 0))),
                "standing on the goal counts as reaching it");
    }

    /** One call over a road-sized set must finish; the visited set is what bounds it. */
    private static void checkManyCellsTerminate() {
        var cells = new HashSet<BlockPos>();
        for (int x = 0; x < 40; x++) {
            for (int z = 0; z < 5; z++) {
                cells.add(point(x, 64, z));
            }
        }
        require(TransportConnectivity.connects(cells, point(0, 64, 0), set(point(39, 64, 4))),
                "a two-hundred-cell road is walked once and answers");
        require(!TransportConnectivity.connects(cells, point(0, 64, 0), set(point(0, 70, 0))),
                "and a goal far above it is still unreachable");
    }

    private static Set<BlockPos> set(BlockPos... cells) {
        return new HashSet<>(List.of(cells));
    }

    private static BlockPos point(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
