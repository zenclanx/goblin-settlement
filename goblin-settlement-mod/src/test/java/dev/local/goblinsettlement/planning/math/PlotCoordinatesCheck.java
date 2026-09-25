package dev.local.goblinsettlement.planning.math;

public final class PlotCoordinatesCheck {
    public static void main(String[] args) {
        check(0, 0, 0, 0, 0, 0);
        check(7, 8, 0, 1, 7, 0);
        check(-1, -8, -1, -1, 7, 0);
        check(-9, 15, -2, 1, 7, 7);
        check(Integer.MIN_VALUE, Integer.MAX_VALUE, -268435456, 268435455, 0, 7);
        for (int x = -17; x <= 17; x++) {
            for (int z = -17; z <= 17; z++) {
                var plot = PlotCoordinates.toPlot(x, z);
                require(8L * plot.plotX() + plot.localX() == x, "x reconstruction");
                require(8L * plot.plotZ() + plot.localZ() == z, "z reconstruction");
                require(plot.localX() >= 0 && plot.localX() < 8, "local x range");
                require(plot.localZ() >= 0 && plot.localZ() < 8, "local z range");
            }
        }
        System.out.println("PlotCoordinatesCheck passed");
    }

    private static void check(int x, int z, int plotX, int plotZ, int localX, int localZ) {
        var actual = PlotCoordinates.toPlot(x, z);
        require(actual.equals(new PlotCoordinates.PlotPosition(plotX, plotZ, localX, localZ)),
                "plot conversion for " + x + "," + z);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
