package dev.local.goblinsettlement.planning.math;

/** Converts block coordinates to provisional 8x8 planning plots. */
public final class PlotCoordinates {
    public static final int PLOT_SIZE = 8;

    public record PlotPosition(int plotX, int plotZ, int localX, int localZ) {
    }

    private PlotCoordinates() {
    }

    public static PlotPosition toPlot(int blockX, int blockZ) {
        return new PlotPosition(
                Math.floorDiv(blockX, PLOT_SIZE), Math.floorDiv(blockZ, PLOT_SIZE),
                Math.floorMod(blockX, PLOT_SIZE), Math.floorMod(blockZ, PLOT_SIZE));
    }
}
