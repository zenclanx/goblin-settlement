package dev.local.goblinsettlement.planning.math;

import dev.local.goblinsettlement.planning.math.BuildingPlotRanker.Candidate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * F005 独立验证程序（规格 v1）。
 *
 * <p>失败直接抛 AssertionError，不使用需要 -ea 才生效的 assert 语句，不依赖 JUnit。
 * 入口：{@code dev.local.goblinsettlement.planning.math.BuildingPlotRankerCheck}
 */
public final class BuildingPlotRankerCheck {

    private static int caseCount = 0;

    public static void main(String[] args) {
        checkAcceptanceCases();
        checkWeightCoefficients();
        checkEligibilityBoundaries();
        checkTieBreak();
        checkExtremeValues();
        checkAgainstReferenceSelection();
        checkValidation();
        checkInputNotModified();
        System.out.println("[F005] 全部通过，共 " + caseCount + " 个断言");
    }

    // ---------------------------------------------------------------- 验收案例

    /** A 得分 59，B 得分 54，应选 B；再按案例要求逐级改坏。 */
    private static void checkAcceptanceCases() {
        Candidate a = candidate(0, 0, 50, 1, 10, 4, 3);
        Candidate b = candidate(1, 0, 64, 0, 12, 2, 2);

        require(scoreOf(a) == 59L, "A 的手算得分应为 59，实际 " + scoreOf(a));
        require(scoreOf(b) == 54L, "B 的手算得分应为 54，实际 " + scoreOf(b));

        require(b.equals(bestOf(candidates(a, b))), "应选 B，实际 " + bestOf(candidates(a, b)));
        require(b.equals(bestOf(candidates(b, a))), "应选 B（输入顺序无关）");

        // B 的 usableCells 改为 47 → 不合格，只能选 A。
        Candidate bUnsuitable = candidate(1, 0, 47, 0, 12, 2, 2);
        require(a.equals(bestOf(candidates(a, bUnsuitable))), "B 不合格后应选 A");

        // A 的 elevationSpread 也改为 3 → 全部不合格，返回空。
        Candidate aUnsuitable = candidate(0, 0, 50, 3, 10, 4, 3);
        require(BuildingPlotRanker.best(candidates(aUnsuitable, bUnsuitable)).isEmpty(),
                "全部不合格时应返回空");

        // 空列表同样返回空。
        require(BuildingPlotRanker.best(new ArrayList<>()).isEmpty(), "空列表应返回空");
    }

    // ---------------------------------------------------------------- 权重

    /**
     * 用「换位后谁赢」来锁定四个系数：构造两个总分相同的候选，只交换 plotX，
     * 胜者必须随 plotX 变化。若某个系数被改动，平分关系被打破，胜者就不再跟着 plotX 走。
     */
    private static void checkWeightCoefficients() {
        // warehouseDistance 系数 4：4*1 == 2*2
        Candidate w1 = candidate(0, 0, 48, 0, 1, 0, 0);   // 4
        Candidate w2 = candidate(1, 0, 48, 0, 0, 2, 0);   // 4
        require(scoreOf(w1) == scoreOf(w2), "warehouseDistance=1 应与 roadDistance=2 同分");
        require(w1.equals(bestOf(candidates(w1, w2))), "同分时应取 plotX 较小者（w1）");
        Candidate w1Swapped = candidate(1, 0, 48, 0, 1, 0, 0);   // 4
        Candidate w2Swapped = candidate(0, 0, 48, 0, 0, 2, 0);   // 4
        require(w2Swapped.equals(bestOf(candidates(w1Swapped, w2Swapped))),
                "同分时应取 plotX 较小者（w2Swapped）");

        // roadDistance 系数 2：2*1 == 1*2
        Candidate r1 = candidate(0, 0, 48, 0, 0, 1, 0);   // 2
        Candidate r2 = candidate(1, 0, 48, 0, 0, 0, 2);   // 2
        require(scoreOf(r1) == scoreOf(r2), "roadDistance=1 应与 materialCost=2 同分");
        require(r1.equals(bestOf(candidates(r1, r2))), "同分时应取 plotX 较小者（r1）");
        Candidate r1Swapped = candidate(1, 0, 48, 0, 0, 1, 0);   // 2
        Candidate r2Swapped = candidate(0, 0, 48, 0, 0, 0, 2);   // 2
        require(r2Swapped.equals(bestOf(candidates(r1Swapped, r2Swapped))),
                "同分时应取 plotX 较小者（r2Swapped）");

        // elevationSpread 系数 8：8*1 == 2*4
        Candidate e1 = candidate(0, 0, 48, 1, 0, 0, 0);   // 8
        Candidate e2 = candidate(1, 0, 48, 0, 0, 4, 0);   // 8
        require(scoreOf(e1) == scoreOf(e2), "elevationSpread=1 应与 roadDistance=4 同分");
        require(e1.equals(bestOf(candidates(e1, e2))), "同分时应取 plotX 较小者（e1）");
        Candidate e1Swapped = candidate(1, 0, 48, 1, 0, 0, 0);   // 8
        Candidate e2Swapped = candidate(0, 0, 48, 0, 0, 4, 0);   // 8
        require(e2Swapped.equals(bestOf(candidates(e1Swapped, e2Swapped))),
                "同分时应取 plotX 较小者（e2Swapped）");

        // materialCost 系数 1：差 1 就分胜负，且 plotX 更大者也应取胜。
        Candidate m1 = candidate(0, 0, 48, 0, 0, 0, 1);   // 1
        Candidate m2 = candidate(1, 0, 48, 0, 0, 0, 0);   // 0
        require(scoreOf(m1) - scoreOf(m2) == 1L, "materialCost 每点应恰好差 1 分");
        require(m2.equals(bestOf(candidates(m1, m2))), "分数更低者应取胜，即使 plotX 更大");

        // 高差越高越差：elevationSpread 每 +1 应加 8 分。
        Candidate lowSpread = candidate(0, 0, 64, 0, 0, 0, 0);
        Candidate highSpread = candidate(1, 0, 64, 2, 0, 0, 0);
        require(scoreOf(highSpread) - scoreOf(lowSpread) == 16L, "高差 2 应加 16 分");
        require(lowSpread.equals(bestOf(candidates(lowSpread, highSpread))), "高差小的应取胜");
    }

    // ---------------------------------------------------------------- 合格性

    /** 合格边界：48 格与高差 2 刚好合格，47 格与高差 3 不合格。 */
    private static void checkEligibilityBoundaries() {
        // 单候选：usableCells 从 0 到 64 逐个试，只有 >= 48 才合格。
        for (int usable = 0; usable <= BuildingPlotRanker.MAX_USABLE_CELLS; usable++) {
            Optional<Candidate> result = BuildingPlotRanker.best(
                    candidates(candidate(0, 0, usable, 0, 0, 0, 0)));
            require(result.isPresent() == (usable >= BuildingPlotRanker.MIN_USABLE_CELLS),
                    "usableCells=" + usable + " 的合格性判断错误：" + result.isPresent());
        }

        // 单候选：高差 0..3，只有 <= 2 合格。
        for (int spread = 0; spread <= 3; spread++) {
            Optional<Candidate> result = BuildingPlotRanker.best(
                    candidates(candidate(0, 0, 64, spread, 0, 0, 0)));
            require(result.isPresent() == (spread <= BuildingPlotRanker.MAX_ELEVATION_SPREAD),
                    "elevationSpread=" + spread + " 的合格性判断错误：" + result.isPresent());
        }

        // 常量值与规格一致。
        require(BuildingPlotRanker.MIN_USABLE_CELLS == 48, "MIN_USABLE_CELLS 应为 48");
        require(BuildingPlotRanker.MAX_USABLE_CELLS == 64, "MAX_USABLE_CELLS 应为 64");
        require(BuildingPlotRanker.MAX_ELEVATION_SPREAD == 2, "MAX_ELEVATION_SPREAD 应为 2");

        // 不合格候选即使分数极低也不能被选中。
        Candidate cheapButTooSmall = candidate(0, 0, 47, 0, 0, 0, 0);
        Candidate expensiveButUsable = candidate(9, 9, 48, 2, 999, 999, 999);
        require(expensiveButUsable.equals(bestOf(candidates(cheapButTooSmall, expensiveButUsable))),
                "不合格候选不得因分数低而被选中");

        // 全部不合格 → 空。
        require(BuildingPlotRanker.best(candidates(
                candidate(0, 0, 0, 0, 0, 0, 0),
                candidate(1, 0, 47, 0, 0, 0, 0),
                candidate(2, 0, 64, 3, 0, 0, 0))).isEmpty(),
                "全部不合格应返回空");
    }

    // ---------------------------------------------------------------- 同分

    /** 同分先比 plotX，再比 plotZ，且与输入顺序无关。 */
    private static void checkTieBreak() {
        // 分数相同、plotX 不同。
        Candidate leftX = candidate(3, 0, 48, 0, 0, 0, 10);
        Candidate rightX = candidate(7, 0, 48, 0, 0, 0, 10);
        require(scoreOf(leftX) == scoreOf(rightX), "两候选应同分");
        require(leftX.equals(bestOf(candidates(leftX, rightX))), "同分应取 plotX 较小者");
        require(leftX.equals(bestOf(candidates(rightX, leftX))), "同分应取 plotX 较小者（逆序输入）");

        // 分数相同、plotX 也相同 → 比 plotZ。
        Candidate lowZ = candidate(5, 0, 48, 0, 0, 0, 10);
        Candidate highZ = candidate(5, 1, 48, 0, 0, 0, 10);
        require(scoreOf(lowZ) == scoreOf(highZ), "两候选应同分");
        require(lowZ.equals(bestOf(candidates(lowZ, highZ))), "同分同 plotX 应取 plotZ 较小者");
        require(lowZ.equals(bestOf(candidates(highZ, lowZ))), "同分同 plotX 应取 plotZ 较小者（逆序输入）");

        // 负数地块编号也要按数值而非绝对值比较。
        Candidate negative = candidate(-3, -8, 48, 0, 0, 0, 10);
        Candidate positive = candidate(2, -8, 48, 0, 0, 0, 10);
        require(negative.equals(bestOf(candidates(negative, positive))), "负数 plotX 更小，应被选中");
        require(negative.equals(bestOf(candidates(positive, negative))), "负数 plotX 更小，应被选中（逆序）");

        // 三个以上同分候选，取 plotX/plotZ 字典序最小者。
        Candidate p1 = candidate(-1, 5, 48, 0, 0, 0, 4);
        Candidate p2 = candidate(-1, 4, 48, 0, 0, 0, 4);
        Candidate p3 = candidate(-2, 99, 48, 0, 0, 0, 4);
        require(p3.equals(bestOf(candidates(p1, p2, p3))), "字典序最小者应为 (-2,99)");
    }

    // ---------------------------------------------------------------- 极值

    /** Integer.MAX_VALUE 量级不溢出，且能识破 int 溢出导致的排序翻转。 */
    private static void checkExtremeValues() {
        int max = Integer.MAX_VALUE;

        // long 下 4 * 1073741824 = 4294967296；用 int 计算会回绕为 0，从而错误地选中小分候选。
        Candidate huge = candidate(0, 0, 64, 0, 1073741824, 0, 0);
        Candidate tiny = candidate(1, 0, 64, 0, 0, 0, 1000);
        require(scoreOf(huge) == 4294967296L, "huge 得分应为 4294967296");
        require(tiny.equals(bestOf(candidates(huge, tiny))),
                "高成本候选应落选；若被选中说明发生了 int 溢出");

        // 全部指标取 Integer.MAX_VALUE，精确到个位。
        Candidate allMax = candidate(0, 0, 64, 0, max, max, max);
        require(scoreOf(allMax) == 15032385529L, "全 MAX 得分应为 15032385529，实际 " + scoreOf(allMax));

        Candidate allMaxPlusSpread = candidate(1, 0, 64, 1, max, max, max);
        require(scoreOf(allMaxPlusSpread) == 15032385537L,
                "高差 1 的全 MAX 得分应为 15032385537，实际 " + scoreOf(allMaxPlusSpread));
        require(allMax.equals(bestOf(candidates(allMax, allMaxPlusSpread))),
                "高差更大者应落选");

        // 两个全 MAX 同分候选：仍然按 plotX 稳定排序，且结果为正数比较的结果。
        Candidate allMaxLeft = candidate(0, 0, 64, 0, max, max, max);
        Candidate allMaxRight = candidate(1, 0, 64, 0, max, max, max);
        require(allMaxLeft.equals(bestOf(candidates(allMaxLeft, allMaxRight))),
                "全 MAX 同分时应取 plotX 较小者");
        require(allMaxLeft.equals(bestOf(candidates(allMaxRight, allMaxLeft))),
                "全 MAX 同分时应取 plotX 较小者（逆序）");

        // 单候选的极端情况也必须仍被选中（分数极大且为正）。
        require(allMax.equals(bestOf(candidates(allMax))), "单候选全 MAX 应被选中");
    }

    // ---------------------------------------------------------------- 交叉核对

    /** 用检查程序内的独立参考实现，对一批确定性候选做全量交叉核对。 */
    private static void checkAgainstReferenceSelection() {
        List<Candidate> pool = new ArrayList<>();
        int n = 0;
        for (int usable : new int[] {0, 47, 48, 49, 64}) {
            for (int spread : new int[] {0, 1, 2, 3}) {
                for (int warehouseDistance : new int[] {0, 1, 7, 1000}) {
                    for (int roadDistance : new int[] {0, 2, 5}) {
                        for (int materialCost : new int[] {0, 1, 9}) {
                            n++;
                            pool.add(candidate(n % 11 - 5, n / 11 - 5, usable, spread,
                                    warehouseDistance, roadDistance, materialCost));
                        }
                    }
                }
            }
        }
        require(pool.size() == n, "候选池大小应为 " + n);

        Optional<Candidate> actual = BuildingPlotRanker.best(pool);
        Optional<Candidate> expected = referenceBest(pool);
        require(actual.isPresent(), "候选池中应存在合格候选");
        require(expected.equals(actual),
                "与参考实现不一致：期望 " + expected + " 实际 " + actual);
        require(scoreOf(actual.get()) == minEligibleScore(pool),
                "所选候选的得分应为合格候选中的最小值，实际 " + scoreOf(actual.get()));

        // 轮转输入后结果不变，证明不依赖输入顺序。
        List<Candidate> rotated = new ArrayList<>(pool);
        Collections.rotate(rotated, 137);
        require(BuildingPlotRanker.best(rotated).equals(actual), "轮转输入后结果应不变");

        // 逆序输入后结果不变。
        List<Candidate> reversed = new ArrayList<>(pool);
        Collections.reverse(reversed);
        require(BuildingPlotRanker.best(reversed).equals(actual), "逆序输入后结果应不变");
    }

    // ---------------------------------------------------------------- 校验

    /** null、越界、重复坐标一律 IllegalArgumentException，且不合格候选也要被校验。 */
    private static void checkValidation() {
        expectThrows(null, "candidates 为 null");
        expectThrows(nullElementCandidates(), "candidates 含 null 元素");
        expectThrows(candidates(candidate(0, 0, 48, 0, 0, 0, 0), null), "第二个元素为 null");

        // usableCells 越界：-1 与 65。
        expectThrows(candidates(candidate(0, 0, -1, 0, 0, 0, 0)), "usableCells 为 -1");
        expectThrows(candidates(candidate(0, 0, 65, 0, 0, 0, 0)), "usableCells 为 65");
        expectThrows(candidates(candidate(0, 0, Integer.MIN_VALUE, 0, 0, 0, 0)),
                "usableCells 为 int 最小值");
        expectThrows(candidates(candidate(0, 0, Integer.MAX_VALUE, 0, 0, 0, 0)),
                "usableCells 为 int 最大值");

        // 其余指标为负。
        expectThrows(candidates(candidate(0, 0, 48, -1, 0, 0, 0)), "elevationSpread 为负");
        expectThrows(candidates(candidate(0, 0, 48, 0, -1, 0, 0)), "warehouseDistance 为负");
        expectThrows(candidates(candidate(0, 0, 48, 0, 0, -1, 0)), "roadDistance 为负");
        expectThrows(candidates(candidate(0, 0, 48, 0, 0, 0, -1)), "materialCost 为负");
        expectThrows(candidates(candidate(0, 0, 48, 0, Integer.MIN_VALUE, 0, 0)),
                "warehouseDistance 为 int 最小值");

        // 被筛选掉的不合格候选同样要校验：usableCells=10 会被筛掉，但负指标仍须抛异常。
        expectThrows(candidates(candidate(0, 0, 10, 0, 0, 0, -1)),
                "不合格候选的指标为负");
        expectThrows(candidates(candidate(9, 9, 60, 3, 0, -2, 0)),
                "不合格候选的 roadDistance 为负");

        // 重复坐标。
        expectThrows(candidates(
                        candidate(3, 4, 48, 0, 0, 0, 0),
                        candidate(3, 4, 64, 1, 9, 9, 9)),
                "重复坐标 (3,4)");
        expectThrows(candidates(
                        candidate(-7, 2, 48, 0, 0, 0, 0),
                        candidate(-7, 2, 48, 0, 0, 0, 1)),
                "重复坐标 (-7,2)");
        expectThrows(candidates(
                        candidate(0, 0, 48, 0, 0, 0, 0),
                        candidate(0, 1, 48, 0, 0, 0, 0),
                        candidate(0, 0, 48, 0, 0, 0, 0)),
                "第三个元素重复坐标 (0,0)");

        // 相同 plotX 但 plotZ 不同不算重复。
        require(BuildingPlotRanker.best(candidates(
                        candidate(0, 0, 48, 0, 0, 0, 0),
                        candidate(0, 1, 48, 0, 0, 0, 0))).isPresent(),
                "相同 plotX 不同 plotZ 不应判为重复");
    }

    /** 输入列表不得被修改。 */
    private static void checkInputNotModified() {
        List<Candidate> pool = candidates(
                candidate(0, 0, 64, 0, 0, 0, 0),
                candidate(1, 0, 47, 0, 0, 0, 0),
                candidate(2, 0, 48, 2, 3, 3, 3));
        List<Candidate> backup = new ArrayList<>(pool);

        BuildingPlotRanker.best(pool);

        require(pool.size() == backup.size(), "输入列表大小被改变");
        for (int i = 0; i < backup.size(); i++) {
            require(backup.get(i).equals(pool.get(i)), "输入列表第 " + i + " 个元素被改变");
        }
    }

    // ---------------------------------------------------------------- 工具

    /** 检查程序内的独立参考实现，用于交叉核对。 */
    private static Optional<Candidate> referenceBest(List<Candidate> candidates) {
        Candidate best = null;
        long bestScore = 0L;
        for (Candidate candidate : candidates) {
            if (candidate.usableCells() < 48 || candidate.elevationSpread() > 2) {
                continue;
            }
            long score = scoreOf(candidate);
            if (best == null || score < bestScore) {
                best = candidate;
                bestScore = score;
                continue;
            }
            if (score > bestScore) {
                continue;
            }
            if (candidate.plotX() < best.plotX()
                    || (candidate.plotX() == best.plotX() && candidate.plotZ() < best.plotZ())) {
                best = candidate;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    private static long minEligibleScore(List<Candidate> candidates) {
        long min = Long.MAX_VALUE;
        for (Candidate candidate : candidates) {
            if (candidate.usableCells() < 48 || candidate.elevationSpread() > 2) {
                continue;
            }
            min = Math.min(min, scoreOf(candidate));
        }
        return min;
    }

    private static long scoreOf(Candidate candidate) {
        return 4L * candidate.warehouseDistance()
                + 2L * candidate.roadDistance()
                + 8L * candidate.elevationSpread()
                + candidate.materialCost();
    }

    private static Candidate candidate(int plotX, int plotZ, int usableCells, int elevationSpread,
                                       int warehouseDistance, int roadDistance, int materialCost) {
        return new Candidate(plotX, plotZ, usableCells, elevationSpread,
                warehouseDistance, roadDistance, materialCost);
    }

    private static List<Candidate> candidates(Candidate... items) {
        return new ArrayList<>(Arrays.asList(items));
    }

    private static List<Candidate> nullElementCandidates() {
        List<Candidate> list = new ArrayList<>();
        list.add(candidate(0, 0, 48, 0, 0, 0, 0));
        list.add(null);
        return list;
    }

    private static Optional<Candidate> bestOf(List<Candidate> candidates) {
        return BuildingPlotRanker.best(candidates);
    }

    private static void expectThrows(List<Candidate> candidates, String scenario) {
        try {
            BuildingPlotRanker.best(candidates);
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
