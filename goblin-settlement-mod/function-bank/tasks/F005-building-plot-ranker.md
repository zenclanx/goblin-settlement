# F005：候选建筑地块排序（规格 v1）

本文件可单独交给其他模型。主程序先读取真实世界、排除未活动区块和玩家保护区；本任务只对已通过权限初筛的地块快照进行纯 Java 21 排序。

## 目标与接口

文件 `BuildingPlotRanker.java`，包名 `dev.local.goblinsettlement.planning.math`，`public final class BuildingPlotRanker`、私有构造器。包含：

```java
public record Candidate(int plotX, int plotZ, int usableCells,
                        int elevationSpread, int warehouseDistance,
                        int roadDistance, int materialCost) {}
public static java.util.Optional<Candidate> best(java.util.List<Candidate> candidates)
```

`plotX/plotZ` 是 8×8 地块编号，可以为任意 int；`usableCells` 为可用格数，范围 `0..64`；其余指标是非负 int。距离是主程序测得的方块距离，材料成本是只用于比较的非负整数评分，不代表已经扣料。

## 规则

1. 参数、任一元素不得为 null；所有指标必须在上述范围。即使某候选稍后被筛掉，也要验证它。无效输入抛 `IllegalArgumentException`。
2. 同一 `(plotX, plotZ)` 不得重复，重复时抛 `IllegalArgumentException`。
3. 只有 `usableCells >= 48` 且 `elevationSpread <= 2` 的候选可参与排名。无候选或全部不合格时返回 `Optional.empty()`。
4. 合格候选评分为 `4L * warehouseDistance + 2L * roadDistance + 8L * elevationSpread + materialCost`，按分数升序选取；同分先选 `plotX` 较小，再选 `plotZ` 较小。必须用 long，不能发生 int 溢出，不能依赖输入顺序。
5. 不修改输入、不做 I/O，无 Minecraft、Fabric、第三方库、世界读取、时间、随机数或可变全局状态。函数不判断领地额度、玩家保护区、路径能否通行、地形是否安全，也不认领土地。

## 必须覆盖的案例

- A `(0,0,50,1,10,4,3)` 得分 59；B `(1,0,64,0,12,2,2)` 得分 54，应选 B。
- 把 B 改为 `usableCells=47` 后应选 A；把 A 也改为 `elevationSpread=3` 后返回空。
- 两个合格候选得分相同，按 `plotX`、`plotZ` 排序，与输入顺序无关。
- 距离与材料成本都为 `Integer.MAX_VALUE` 时精确计算、稳定排序，不溢出。
- `usableCells=65`、负指标、null 元素和重复坐标分别抛异常；输入 List 不变。

## 交付

只在 `goblin-settlement-mod/function-bank/submissions/F005/r1/` 提交 `src/BuildingPlotRanker.java`、`checks/BuildingPlotRankerCheck.java`、`NOTES.md`。检查文件用同包名和 public main，失败抛 AssertionError，不用依赖 `-ea` 的 assert。NOTES 记录来源、已做与未做；**按本项目当前约定先不要运行检查或构建，明确写“未运行，等待初版完成后的统一测试”**。不改运行时源码或其他文件。

主程序将来在创建住房/工坊方案前提供已测量且已初筛的候选，真正选址前仍需重新检查区块、保护区、材料、工人和施工空间。
