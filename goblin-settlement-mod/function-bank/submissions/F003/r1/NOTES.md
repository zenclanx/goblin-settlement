# F003 提交记录 — 相邻两点的道路代价

- **任务编号 / 规格版本**：F003 / 规格 v1
- **提交修订**：r1
- **提交时间**：2026-09-24
- **状态**：待验收（本文不构成验收结论）

## 文件列表

| 文件 | 说明 |
| --- | --- |
| `src/RoadStepCost.java` | 实现，包名 `dev.local.goblinsettlement.planning.math` |
| `checks/RoadStepCostCheck.java` | 独立验证程序，同包名，`public static void main` |
| `NOTES.md` | 本文件 |

## 接口实现说明

```java
public static long calculate(
    int deltaY,
    int terrainPenalty,
    boolean existingRoad,
    int existingRoadBaseCost,
    int newRoadBaseCost,
    int verticalPenaltyPerBlock)
```

```text
base   = existingRoad ? existingRoadBaseCost : newRoadBaseCost
result = base + terrainPenalty + abs(deltaY) * verticalPenaltyPerBlock
```

- 四个校验**先于分支选择**执行，因此未使用的那侧基础代价同样被检查。
- 高差先转 long 再取绝对值：`Math.abs((long) deltaY)`。若在 int 上取绝对值，`Integer.MIN_VALUE` 会溢出仍为负，导致结果变负。
- 全部中间量按 long 计算。int 全域下 `abs(deltaY) <= 2^31`、权重 `<= 2^31-1`，乘积 `< 2^62`，加两项 `Integer.MAX_VALUE` 后最大约 `4.61e18`，仍小于 `Long.MAX_VALUE`，不会溢出。

## 验证命令与真实结果

环境：Temurin JDK 21.0.12.1，Windows。

在 `submissions/F003/r1/` 目录执行：

```text
javac --release 21 -encoding UTF-8 -d out src/RoadStepCost.java checks/RoadStepCostCheck.java
java -cp out dev.local.goblinsettlement.planning.math.RoadStepCostCheck
```

**真实结果（已运行）**：

```text
[F003] 全部通过，共 588 个断言
退出码 = 0
```

覆盖内容：

1. 任务卡验收表 6 行原值比对，含 `(Integer.MIN_VALUE, 0, false, 2, 10, 1) → 2147483658` 与 `existingRoadBaseCost = 0` 抛异常。
2. 对称性：`deltaY = ±1..±64` 时代价相同，且等于手算值；高差 0 与 `Integer.MIN_VALUE` 在权重 0 时都只取基础代价。
3. 拒绝输入：`terrainPenalty` 为负及 `Integer.MIN_VALUE`；`verticalPenaltyPerBlock` 同样两例；`existingRoadBaseCost` / `newRoadBaseCost` 分别为负、为 0、为 `Integer.MIN_VALUE`，且在 `existingRoad` 的两种取值下都测试（共 11 例）。
4. 未使用的基础代价仍被校验：`existingRoad=true` 且 `newRoadBaseCost=0`、`existingRoad=false` 且 `existingRoadBaseCost=0`、以及两者为负的组合，全部抛异常；同时确认合法时未使用的一侧不会进入计算。
5. 单调性：`terrainPenalty = 0..32` 与 `verticalPenaltyPerBlock = 0..32` 递增时结果不下降；已有道路与新建道路的差恒等于两个基础代价之差。
6. 极值权重：全部正权重取 `Integer.MAX_VALUE`、`deltaY = Integer.MIN_VALUE` 时结果为 `4611686020574871550`，仍为正且精确；最小的合法结果 1 也为正。
7. 全公式重建：15 个 `deltaY` 采样点（含 int 全域两端）× 3 个地形罚分 × 4 个垂直权重 × 2 种道路状态，共 360 组与用 long 手算的期望值逐一比对。

## 反向对照（验证程序非空跑）

在项目外的临时目录复制一份并故意改坏实现，重新编译运行：

| 变异 | 结果 |
| --- | --- |
| 把 `Math.abs((long) deltaY)` 改成 `Math.abs(deltaY)`（在 int 上取绝对值） | **失败**：`期望 2147483658 实际 -2147483638（deltaY=-2147483648, terrain=0, existingRoad=false）` |

即 `Integer.MIN_VALUE` 的溢出问题确实被断言覆盖，验证程序能发现结果变负这种规格明令禁止的行为。该变异副本未留在本目录。

## 限制

- **不判断可通行性**。坡度是否允许、两点是否真的相邻、是否为玩家保护区、桥梁是否完工、区块是否活动，全部由主程序判断后才调用本函数。本函数不判断能否建桥、不创建路线、不修改方块。
- 除 `deltaY` 外的整数都是抽象评分权重，没有物理单位。任务卡例子里的数字不是已确定的游戏平衡数值，本次交付不声称任何一组权重已经调平衡。
- 结果恒为正、无溢出，但**只是比较用的标量**，不同权重组合之间的代价不可跨配置比较。
- A* 等搜索若使用已有道路优惠，启发式必须由主程序按最小合法步代价另行设计，不能假定每步至少花 `newRoadBaseCost`。

## 代码来源

- 全部代码为本任务卡规格 v1 直接编写，未复制任何第三方或既有项目代码。
- 只使用 Java 21 标准库（`java.lang.Math`）。无 Minecraft / Fabric / 第三方依赖，无 I/O、时间、随机数或全局可变状态。
- 未参考本仓库 `PopulationRules.java`。

## 交给主程序的下一步

1. 检查实现与规格、接口、边界是否一致，并**亲自重跑**上面的命令。
2. 验收通过后才复制到 `accepted/F003/r1/` 并补 `REVIEW.md`。
3. 接入道路规划器时，在调用层读取地形并筛出合法候选步，再传入本函数；权重取值属于游戏平衡，需要单独确认。
