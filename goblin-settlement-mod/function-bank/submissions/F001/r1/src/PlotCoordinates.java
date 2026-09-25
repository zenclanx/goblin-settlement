package dev.local.goblinsettlement.planning.math;

/**
 * 规格 v1：把二维整数方块坐标划分到固定边长 8 的方形地块。
 *
 * <p>坐标 (0,0) 是地块 (0,0) 的起点，边界沿整数 8 的倍数对齐。地块编号向负无穷取整，
 * 局部坐标始终位于 0..7。恒等式：{@code blockX == 8L * plotX + localX}。
 *
 * <p>本类不做任何 I/O，不读世界/文件/网络/时间，无随机数与可变全局状态，不修改输入。
 */
public final class PlotCoordinates {

    /** 地块边长，单位：方块。 */
    public static final int PLOT_SIZE = 8;

    private PlotCoordinates() {
        throw new AssertionError("no instances");
    }

    /** 地块编号与其内部局部坐标（局部坐标范围 0..PLOT_SIZE-1）。 */
    public record PlotPosition(int plotX, int plotZ, int localX, int localZ) {}

    /**
     * 将方块坐标映射为地块位置。
     *
     * @param blockX 方块 X 坐标，支持整个 int 范围
     * @param blockZ 方块 Z 坐标，支持整个 int 范围
     * @return 对应地块编号与地块内局部坐标，永不为 null
     */
    public static PlotPosition toPlot(int blockX, int blockZ) {
        // floorDiv / floorMod 保证向负无穷取整，普通除法截断到零会在负数上出错。
        int plotX = Math.floorDiv(blockX, PLOT_SIZE);
        int plotZ = Math.floorDiv(blockZ, PLOT_SIZE);
        int localX = Math.floorMod(blockX, PLOT_SIZE);
        int localZ = Math.floorMod(blockZ, PLOT_SIZE);
        return new PlotPosition(plotX, plotZ, localX, localZ);
    }
}
