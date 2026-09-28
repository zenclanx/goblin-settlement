package dev.local.goblinsettlement.planning.math;

/**
 * F003 独立验证程序（规格 v1）。
 *
 * <p>失败直接抛 AssertionError，不使用需要 -ea 才生效的 assert 语句，不依赖 JUnit。
 * 入口：{@code dev.local.goblinsettlement.planning.math.RoadStepCostCheck}
 */
public final class RoadStepCostCheck {

    private static int caseCount = 0;

    public static void main(String[] args) {
        checkAcceptanceTable();
        checkDeltaYSymmetry();
        checkValidationRejectsBadWeights();
        checkUnusedBaseCostIsStillValidated();
        checkMonotonicInPenalties();
        checkExtremeWeights();
        checkFullDeltaYRange();
        System.out.println("[F003] 全部通过，共 " + caseCount + " 个断言");
    }

    // ---------------------------------------------------------------- 检查项

    /** 任务卡「必须覆盖的验收案例」原表。 */
    private static void checkAcceptanceTable() {
        expect(0, 0, false, 2, 10, 5, 10L);
        expect(1, 3, false, 2, 10, 5, 18L);
        expect(-1, 3, false, 2, 10, 5, 18L);
        expect(0, 0, true, 2, 10, 5, 2L);
        expect(Integer.MIN_VALUE, 0, false, 2, 10, 1, 2147483658L);
        expectThrows(0, 0, false, 0, 10, 5, "newRoadBaseCost 未用但为 0 的用例");

        // 反向用例：existingRoad=true 时 newRoadBaseCost=0 仍必须被拒绝。
        expectThrows(0, 0, true, 2, 0, 5, "newRoadBaseCost 为 0（已有道路分支）");
    }

    /** abs(deltaY)：上升与下降代价相同。 */
    private static void checkDeltaYSymmetry() {
        for (int deltaY = 1; deltaY <= 64; deltaY++) {
            long up = RoadStepCost.calculate(deltaY, 7, false, 2, 10, 3);
            long down = RoadStepCost.calculate(-deltaY, 7, false, 2, 10, 3);
            require(up == down, "±" + deltaY + " 代价应相同：" + up + " vs " + down);
            require(up == 10L + 7L + (long) deltaY * 3L, "±" + deltaY + " 代价计算错误：" + up);
        }

        // 0 与 Integer.MIN_VALUE 的绝对值处理。
        require(RoadStepCost.calculate(0, 0, true, 1, 1, 0) == 1L, "高差 0 时只取基础代价");
        require(RoadStepCost.calculate(Integer.MIN_VALUE, 0, true, 1, 1, 0) == 1L,
                "高差为 int 最小值时罚分权重为 0，仍是基础代价");
    }

    /** 负罚分与非法基础代价一律 IllegalArgumentException。 */
    private static void checkValidationRejectsBadWeights() {
        expectThrows(0, -1, false, 2, 10, 5, "terrainPenalty 为负");
        expectThrows(0, Integer.MIN_VALUE, false, 2, 10, 5, "terrainPenalty 为 int 最小值");
        expectThrows(0, 0, false, 2, 10, -1, "verticalPenaltyPerBlock 为负");
        expectThrows(0, 0, false, 2, 10, Integer.MIN_VALUE, "verticalPenaltyPerBlock 为 int 最小值");

        expectThrows(0, 0, false, -1, 10, 5, "existingRoadBaseCost 为负");
        expectThrows(0, 0, false, 0, 10, 5, "existingRoadBaseCost 为 0");
        expectThrows(0, 0, false, Integer.MIN_VALUE, 10, 5, "existingRoadBaseCost 为 int 最小值");
        expectThrows(0, 0, false, 2, -1, 5, "newRoadBaseCost 为负");
        expectThrows(0, 0, false, 2, 0, 5, "newRoadBaseCost 为 0");
        expectThrows(0, 0, true, 0, 10, 5, "已有道路分支下 existingRoadBaseCost 为 0");
        expectThrows(0, 0, true, 2, -5, 5, "已有道路分支下 newRoadBaseCost 为负");

        // 合法的最小配置：基础代价 1，罚分全 0。
        require(RoadStepCost.calculate(0, 0, true, 1, 1, 0) == 1L, "最小合法配置");
        require(RoadStepCost.calculate(0, 0, false, 1, 1, 0) == 1L, "最小合法配置（新建）");
    }

    /** 校验发生在分支选择之前，未使用的一侧也要检查。 */
    private static void checkUnusedBaseCostIsStillValidated() {
        // 取用 existingRoadBaseCost 的那一侧正常，另一侧刻意非法。
        expectThrows(0, 0, true, 5, 0, 0, "用已有道路，但 newRoadBaseCost=0");
        expectThrows(0, 0, false, 0, 5, 0, "用新建道路，但 existingRoadBaseCost=0");
        expectThrows(0, 0, true, 5, -3, 0, "用已有道路，但 newRoadBaseCost<0");
        expectThrows(0, 0, false, -3, 5, 0, "用新建道路，但 existingRoadBaseCost<0");

        // 合法的另一侧不影响结果。
        require(RoadStepCost.calculate(0, 0, true, 5, 999, 0) == 5L, "未使用的一侧不得进入计算");
        require(RoadStepCost.calculate(0, 0, false, 999, 5, 0) == 5L, "未使用的一侧不得进入计算");
    }

    /** 增大非负罚分不得降低结果。 */
    private static void checkMonotonicInPenalties() {
        long previousTerrain = -1L;
        for (int terrainPenalty = 0; terrainPenalty <= 32; terrainPenalty++) {
            long value = RoadStepCost.calculate(13, terrainPenalty, false, 2, 10, 4);
            require(value >= previousTerrain,
                    "terrainPenalty=" + terrainPenalty + " 使结果下降：" + value + " < " + previousTerrain);
            previousTerrain = value;
        }

        long previousVertical = -1L;
        for (int verticalPenaltyPerBlock = 0; verticalPenaltyPerBlock <= 32; verticalPenaltyPerBlock++) {
            long value = RoadStepCost.calculate(13, 5, false, 2, 10, verticalPenaltyPerBlock);
            require(value >= previousVertical, "verticalPenaltyPerBlock=" + verticalPenaltyPerBlock
                    + " 使结果下降：" + value + " < " + previousVertical);
            previousVertical = value;
        }

        // 已有道路相对新建道路的优惠只体现在基础代价这一项。
        long existing = RoadStepCost.calculate(-7, 3, true, 2, 10, 6);
        long newRoad = RoadStepCost.calculate(-7, 3, false, 2, 10, 6);
        require(existing == newRoad - 8L, "两种基础代价之差应恒为 10-2=8：" + existing + " vs " + newRoad);
    }

    /** 所有正权重取 Integer.MAX_VALUE 时结果仍为正且精确。 */
    private static void checkExtremeWeights() {
        int max = Integer.MAX_VALUE;

        // 最大乘积累加：abs(Integer.MIN_VALUE) * Integer.MAX_VALUE + 两个 MAX_VALUE。
        // = 2147483648 * 2147483647 + 2147483647 + 2147483647 = 4611686020574871550
        long expected = 4611686020574871550L;
        long actual = RoadStepCost.calculate(Integer.MIN_VALUE, max, false, max, max, max);
        require(actual == expected,
                "最大权重结果应为 " + expected + " 实际 " + actual);
        require(actual > 0L, "最大权重结果必须为正：" + actual);

        // 两种基础代价都取最大值，且使用已有道路分支。
        long existing = RoadStepCost.calculate(Integer.MIN_VALUE, 0, true, max, 1, max);
        require(existing == 2147483647L + 2147483648L * 2147483647L,
                "已有道路分支的最大权重结果错误：" + existing);

        // 相加不溢出的上界：base + terrain + |deltaY| * weight 均取最大合法值。
        long upper = RoadStepCost.calculate(Integer.MIN_VALUE, max, false, 1, max, max);
        require(upper > 0L, "上界结果必须为正：" + upper);

        // 结果为 0 或负都是规格禁止的。
        long smallest = RoadStepCost.calculate(0, 0, true, 1, 1, 0);
        require(smallest > 0L, "最小结果必须为正：" + smallest);
    }

    /** 用 long 遍历重建公式，确认与实现完全一致（含 int 全域端点）。 */
    private static void checkFullDeltaYRange() {
        int[] samples = {
            0, 1, -1, 7, -7, 8, -8, 255, -255, 1000, -1000,
            Integer.MAX_VALUE, Integer.MAX_VALUE - 1, Integer.MIN_VALUE + 1, Integer.MIN_VALUE
        };
        int[] terrains = {0, 1, 37};
        int[] verticals = {0, 1, 3, 12};
        boolean[] roadFlags = {true, false};

        for (int deltaY : samples) {
            for (int terrain : terrains) {
                for (int vertical : verticals) {
                    for (boolean existingRoad : roadFlags) {
                        long base = existingRoad ? 2L : 10L;
                        long wanted = base + terrain + Math.abs((long) deltaY) * vertical;
                        long actual = RoadStepCost.calculate(deltaY, terrain, existingRoad, 2, 10, vertical);
                        require(actual == wanted, "deltaY=" + deltaY + " terrain=" + terrain
                                + " vertical=" + vertical + " existingRoad=" + existingRoad
                                + " 期望 " + wanted + " 实际 " + actual);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private static void expect(int deltaY, int terrainPenalty, boolean existingRoad,
                               int existingRoadBaseCost, int newRoadBaseCost,
                               int verticalPenaltyPerBlock, long wanted) {
        long actual = RoadStepCost.calculate(deltaY, terrainPenalty, existingRoad,
                existingRoadBaseCost, newRoadBaseCost, verticalPenaltyPerBlock);
        require(actual == wanted, "期望 " + wanted + " 实际 " + actual
                + "（deltaY=" + deltaY + ", terrain=" + terrainPenalty
                + ", existingRoad=" + existingRoad + "）");
    }

    private static void expectThrows(int deltaY, int terrainPenalty, boolean existingRoad,
                                     int existingRoadBaseCost, int newRoadBaseCost,
                                     int verticalPenaltyPerBlock, String scenario) {
        try {
            RoadStepCost.calculate(deltaY, terrainPenalty, existingRoad,
                    existingRoadBaseCost, newRoadBaseCost, verticalPenaltyPerBlock);
        } catch (IllegalArgumentException expected) {
            caseCount++;
            return;
        }
        throw new AssertionError("应抛 IllegalArgumentException：" + scenario);
    }

    private static void require(boolean condition, String message) {
        caseCount++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
