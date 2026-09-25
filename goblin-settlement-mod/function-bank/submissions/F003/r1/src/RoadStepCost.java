package dev.local.goblinsettlement.planning.math;

/**
 * 规格 v1：相邻两点的道路单步代价。
 *
 * <pre>
 *   base = existingRoad ? existingRoadBaseCost : newRoadBaseCost
 *   result = base + terrainPenalty + abs(deltaY) * verticalPenaltyPerBlock
 * </pre>
 *
 * <p>调用方已确认两点水平相邻且这一步可纳入道路规划；本函数只给出正整数代价用于路线比较。
 * 结果恒为正，不以 0、负数或无穷大表示不可通行，也不返回 null。
 *
 * <p>本类不做任何 I/O，不读世界/文件/网络/时间，无随机数与可变全局状态，不修改输入。
 */
public final class RoadStepCost {

    private RoadStepCost() {
        throw new AssertionError("no instances");
    }

    /**
     * 计算一步的代价。
     *
     * @param deltaY                  终点高度减起点高度，单位方块，支持整个 int 范围
     * @param terrainPenalty          调用方已算好的该步额外地形代价，非负
     * @param existingRoad            这一步是否使用已有可用道路
     * @param existingRoadBaseCost    已有道路的基础代价，必须大于 0
     * @param newRoadBaseCost         新建道路的基础代价，必须大于 0
     * @param verticalPenaltyPerBlock 每格绝对高差的附加代价，非负
     * @return 恒为正的代价
     * @throws IllegalArgumentException 任一基础代价 <= 0，或任一罚分 < 0
     */
    public static long calculate(
            int deltaY,
            int terrainPenalty,
            boolean existingRoad,
            int existingRoadBaseCost,
            int newRoadBaseCost,
            int verticalPenaltyPerBlock) {

        // 全部参数先校验，包括本次分支没有用到的基础代价。
        if (existingRoadBaseCost <= 0) {
            throw new IllegalArgumentException("existingRoadBaseCost 必须大于 0，实际 " + existingRoadBaseCost);
        }
        if (newRoadBaseCost <= 0) {
            throw new IllegalArgumentException("newRoadBaseCost 必须大于 0，实际 " + newRoadBaseCost);
        }
        if (terrainPenalty < 0) {
            throw new IllegalArgumentException("terrainPenalty 必须非负，实际 " + terrainPenalty);
        }
        if (verticalPenaltyPerBlock < 0) {
            throw new IllegalArgumentException("verticalPenaltyPerBlock 必须非负，实际 " + verticalPenaltyPerBlock);
        }

        long base = existingRoad ? (long) existingRoadBaseCost : (long) newRoadBaseCost;

        // 先转 long 再取绝对值：Math.abs(Integer.MIN_VALUE) 在 int 上会溢出为负。
        long rise = Math.abs((long) deltaY);

        // 整个 int 输入域下 abs(deltaY) <= 2^31、权重 <= 2^31-1，乘积 < 2^62，加法不溢出。
        return base + (long) terrainPenalty + rise * (long) verticalPenaltyPerBlock;
    }
}
