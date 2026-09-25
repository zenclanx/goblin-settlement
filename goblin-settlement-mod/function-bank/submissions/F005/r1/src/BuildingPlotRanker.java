package dev.local.goblinsettlement.planning.math;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 规格 v1：候选建筑地块排序。
 *
 * <p>只在主程序已完成权限初筛的地块快照上排序，不做世界读取，也不认领土地。
 * 合格条件：{@code usableCells >= 48} 且 {@code elevationSpread <= 2}。
 * 评分（long）：{@code 4 * warehouseDistance + 2 * roadDistance + 8 * elevationSpread + materialCost}，
 * 分数升序；同分先取 plotX 较小，再取 plotZ 较小。结果与输入顺序无关。
 *
 * <p>本类不做任何 I/O，无 Minecraft / Fabric / 第三方依赖 / 时间 / 随机数 / 可变全局状态，不修改输入。
 */
public final class BuildingPlotRanker {

    /** usableCells 上界：一个 8×8 地块共 64 格。 */
    public static final int MAX_USABLE_CELLS = 64;

    /** 参与排名所需的最小可用格数。 */
    public static final int MIN_USABLE_CELLS = 48;

    /** 参与排名允许的最大高差。 */
    public static final int MAX_ELEVATION_SPREAD = 2;

    /** 已初筛的候选地块及其指标。 */
    public record Candidate(int plotX, int plotZ, int usableCells, int elevationSpread,
                            int warehouseDistance, int roadDistance, int materialCost) {}

    /** 重复地块判据。 */
    private record PlotKey(int plotX, int plotZ) {}

    private BuildingPlotRanker() {
        throw new AssertionError("no instances");
    }

    /**
     * 选出评分最低的合格候选。
     *
     * @param candidates 已通过权限初筛的候选，非 null
     * @return 最优候选；列表为空或全部不合格时返回 {@link Optional#empty()}
     * @throws IllegalArgumentException 参数或元素为 null；指标越界（usableCells 不在 0..64，
     *                                  其余指标为负）；同一 (plotX, plotZ) 重复
     */
    public static Optional<Candidate> best(List<Candidate> candidates) {
        requireCandidates(candidates);

        Candidate best = null;
        long bestScore = 0L;

        for (Candidate candidate : candidates) {
            if (candidate.usableCells() < MIN_USABLE_CELLS
                    || candidate.elevationSpread() > MAX_ELEVATION_SPREAD) {
                continue;
            }

            long score = score(candidate);
            if (best == null
                    || score < bestScore
                    || (score == bestScore && ranksBefore(candidate, best))) {
                best = candidate;
                bestScore = score;
            }
        }

        return Optional.ofNullable(best);
    }

    /** 评分公式，全程 long。最大量级 15 * Integer.MAX_VALUE，远小于 Long.MAX_VALUE。 */
    private static long score(Candidate candidate) {
        return 4L * candidate.warehouseDistance()
                + 2L * candidate.roadDistance()
                + 8L * candidate.elevationSpread()
                + candidate.materialCost();
    }

    /** 同分时的稳定排序：先 plotX 小，再 plotZ 小。 */
    private static boolean ranksBefore(Candidate left, Candidate right) {
        if (left.plotX() != right.plotX()) {
            return left.plotX() < right.plotX();
        }
        return left.plotZ() < right.plotZ();
    }

    /** 完整校验全部候选，即使某个候选随后会被合格性筛选掉。 */
    private static void requireCandidates(List<Candidate> candidates) {
        if (candidates == null) {
            throw new IllegalArgumentException("candidates 不能为 null");
        }
        Set<PlotKey> seen = new HashSet<>();
        for (int index = 0; index < candidates.size(); index++) {
            Candidate candidate = candidates.get(index);
            if (candidate == null) {
                throw new IllegalArgumentException("candidates 第 " + index + " 个元素为 null");
            }
            if (candidate.usableCells() < 0 || candidate.usableCells() > MAX_USABLE_CELLS) {
                throw new IllegalArgumentException(
                        "candidates 第 " + index + " 个元素的 usableCells 越界：" + candidate.usableCells());
            }
            requireNonNegative(candidate.elevationSpread(), "elevationSpread", index);
            requireNonNegative(candidate.warehouseDistance(), "warehouseDistance", index);
            requireNonNegative(candidate.roadDistance(), "roadDistance", index);
            requireNonNegative(candidate.materialCost(), "materialCost", index);

            if (!seen.add(new PlotKey(candidate.plotX(), candidate.plotZ()))) {
                throw new IllegalArgumentException(
                        "地块坐标重复：(" + candidate.plotX() + "," + candidate.plotZ() + ")");
            }
        }
    }

    private static void requireNonNegative(int value, String field, int index) {
        if (value < 0) {
            throw new IllegalArgumentException(
                    "candidates 第 " + index + " 个元素的 " + field + " 为负：" + value);
        }
    }
}
