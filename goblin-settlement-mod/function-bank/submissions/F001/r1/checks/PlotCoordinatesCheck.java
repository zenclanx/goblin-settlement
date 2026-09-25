package dev.local.goblinsettlement.planning.math;

import dev.local.goblinsettlement.planning.math.PlotCoordinates.PlotPosition;

/**
 * F001 独立验证程序（规格 v1）。
 *
 * <p>失败直接抛 AssertionError，不使用需要 -ea 才生效的 assert 语句，不依赖 JUnit。
 * 入口：{@code dev.local.goblinsettlement.planning.math.PlotCoordinatesCheck}
 */
public final class PlotCoordinatesCheck {

    private static int caseCount = 0;

    public static void main(String[] args) {
        checkPlotSizeConstant();
        checkAcceptanceTable();
        checkNegativeBoundaries();
        checkExhaustiveSmallRange();
        checkFullIntExtremes();
        System.out.println("[F001] 全部通过，共 " + caseCount + " 个断言");
    }

    // ---------------------------------------------------------------- 检查项

    /** 地块边长必须与规格一致的 8。 */
    private static void checkPlotSizeConstant() {
        require(PlotCoordinates.PLOT_SIZE == 8,
                "PLOT_SIZE 应为 8，实际 " + PlotCoordinates.PLOT_SIZE);
    }

    /** 任务卡「必须覆盖的验收案例」原表。 */
    private static void checkAcceptanceTable() {
        expect(0, 0, 0, 0, 0, 0);
        expect(7, 8, 0, 1, 7, 0);
        expect(-1, -8, -1, -1, 7, 0);
        expect(-9, 15, -2, 1, 7, 7);
        expect(Integer.MIN_VALUE, Integer.MAX_VALUE, -268435456, 268435455, 0, 7);
    }

    /** 取整边界：8k-1 属于上一地块的最后一格，8k 属于下一地块的第一格。 */
    private static void checkNegativeBoundaries() {
        for (int k = -5; k <= 5; k++) {
            int boundary = 8 * k;
            PlotPosition before = PlotCoordinates.toPlot(boundary - 1, boundary - 1);
            require(before.plotX() == k - 1 && before.localX() == 7,
                    "边界前一点 (" + (boundary - 1) + ") 落在 X 地块 " + before.plotX()
                            + "/局部 " + before.localX());
            require(before.plotZ() == k - 1 && before.localZ() == 7,
                    "边界前一点 (" + (boundary - 1) + ") 落在 Z 地块 " + before.plotZ()
                            + "/局部 " + before.localZ());

            PlotPosition on = PlotCoordinates.toPlot(boundary, boundary);
            require(on.plotX() == k && on.localX() == 0,
                    "边界点 " + boundary + " 落在 X 地块 " + on.plotX() + "/局部 " + on.localX());
            require(on.plotZ() == k && on.localZ() == 0,
                    "边界点 " + boundary + " 落在 Z 地块 " + on.plotZ() + "/局部 " + on.localZ());
        }
    }

    /** 固定小范围 -17..17 遍历组合，检验重建关系与局部坐标范围（用 long 重建）。 */
    private static void checkExhaustiveSmallRange() {
        for (int blockX = -17; blockX <= 17; blockX++) {
            for (int blockZ = -17; blockZ <= 17; blockZ++) {
                PlotPosition p = PlotCoordinates.toPlot(blockX, blockZ);

                require(p.localX() >= 0 && p.localX() <= 7,
                        "局部 X 越界：" + p.localX() + " 于 (" + blockX + "," + blockZ + ")");
                require(p.localZ() >= 0 && p.localZ() <= 7,
                        "局部 Z 越界：" + p.localZ() + " 于 (" + blockX + "," + blockZ + ")");

                // 用 long 重建，避免 8 * plotX 在 int 上溢出。
                long rebuildX = 8L * p.plotX() + p.localX();
                long rebuildZ = 8L * p.plotZ() + p.localZ();
                require(rebuildX == blockX,
                        "X 重建失败：(" + blockX + "," + blockZ + ") -> " + rebuildX);
                require(rebuildZ == blockZ,
                        "Z 重建失败：(" + blockX + "," + blockZ + ") -> " + rebuildZ);

                // 相邻方块只在跨越 8 的倍数时更换地块号。
                require(p.plotX() == Math.floorDiv(blockX, 8),
                        "地块 X 与 floorDiv 不一致：" + blockX);
                require(p.plotZ() == Math.floorDiv(blockZ, 8),
                        "地块 Z 与 floorDiv 不一致：" + blockZ);
            }
        }
    }

    /** int 全域的四个极值点，检验不溢出的边界行为。 */
    private static void checkFullIntExtremes() {
        expect(Integer.MIN_VALUE, Integer.MIN_VALUE, -268435456, -268435456, 0, 0);
        expect(Integer.MAX_VALUE, Integer.MAX_VALUE, 268435455, 268435455, 7, 7);
        expect(Integer.MIN_VALUE, Integer.MAX_VALUE, -268435456, 268435455, 0, 7);
        expect(Integer.MAX_VALUE, Integer.MIN_VALUE, 268435455, -268435456, 7, 0);

        for (int blockX : new int[] {Integer.MIN_VALUE, Integer.MIN_VALUE + 1, Integer.MAX_VALUE - 1,
                Integer.MAX_VALUE, -1, 0, 1}) {
            PlotPosition p = PlotCoordinates.toPlot(blockX, blockX);
            long rebuild = 8L * p.plotX() + p.localX();
            require(rebuild == blockX, "极值重建失败：" + blockX + " -> " + rebuild);
            require(p.localX() >= 0 && p.localX() <= 7, "极值局部坐标越界：" + p.localX());
        }
    }

    // ---------------------------------------------------------------- 工具

    private static void expect(int blockX, int blockZ,
                               int plotX, int plotZ, int localX, int localZ) {
        PlotPosition actual = PlotCoordinates.toPlot(blockX, blockZ);
        PlotPosition wanted = new PlotPosition(plotX, plotZ, localX, localZ);
        require(wanted.equals(actual),
                "输入 (" + blockX + "," + blockZ + ") 期望 " + wanted + " 实际 " + actual);
    }

    private static void require(boolean condition, String message) {
        caseCount++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
