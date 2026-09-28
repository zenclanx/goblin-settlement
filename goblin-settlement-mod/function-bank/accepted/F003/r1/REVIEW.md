# F003 验收记录

| | |
| --- | --- |
| **任务编号与规格版本** | F003 道路单步代价，规格 v1（`tasks/F003-road-step-cost.md`） |
| **提交修订号** | r1（本目录即该修订的验收快照） |
| **验收时间** | 2026-09-29 00:56 +08:00 |
| **验收结论** | **通过** |
| **接入状态** | **尚未接入运行时** |

## 验证命令与结果

在**提交目录之外**的临时目录编译运行（不修改 `function-bank/` 下任何文件），JDK 21（`21.0.12.1+1`）：

```bash
"$JAVA_HOME/bin/javac" --release 21 -encoding UTF-8 -d out \
    submissions/F003/r1/src/RoadStepCost.java \
    submissions/F003/r1/checks/RoadStepCostCheck.java            # exit 0

"$JAVA_HOME/bin/java" -cp out dev.local.goblinsettlement.planning.math.RoadStepCostCheck
# 输出：[F003] 全部通过，共 588 个断言                           # exit 0
```

**主程序另写的独立对抗套件：753 条全部通过。** 范围包括：`deltaY` 的四个端点（`Integer.MIN_VALUE` **先转 long 再取绝对值**，得 2147483648 而非 int 回绕的负数）、`MIN_VALUE × MAX_VALUE` 与 `MAX_VALUE × MAX_VALUE` 权重下的精确值（最坏 4611686020574871550，仍 < `Long.MAX_VALUE`，不溢出）、**11 条必抛矩阵**（四个基准参数各自非法、以及全部同时非法）、**未被采用的那一支的基准代价也照样校验**、`±d` 对称性、在 terrain / vertical / |deltaY| 三个维度上的严格单调性、零惩罚的合法性与最小返回值 1、以及重复调用的确定性。

**非空转证明（变异测试）**：在临时副本上把 `Math.abs((long) deltaY)` 改回 `Math.abs(deltaY)`，独立套件**立即报红**（`expected 2147483658 but got -2147483638`）——与提交方 NOTES 里自报的那次反向对照**逐字相同**。证明该套件本身有效。

## 主程序检查的契约条款

- 签名、参数顺序、返回类型与 `public final class` + 私有构造器：与规格逐字一致（反射核对，`javap` 显示零字段）。
- 返回 `long`、语义是"抽象分数、无物理单位"；结果为**正**（基准 ≥ 1，其余项 ≥ 0）。
- **代价方向**：分数随 `terrainPenalty`（1:1）、`verticalPenaltyPerBlock`、`|deltaY|` **严格递增**；只改路面标志时分数变化恰为 `existing − new`。**没有更便宜的地形反而排名更差的情形。**
- 规格**没有**规定两种基准代价之间的先后，实现也没有强加——一致，不是缺陷。
- 异常：任一基准 ≤ 0 或任一惩罚 < 0 时抛 `IllegalArgumentException`；**四道校验在分支选择之前执行**，所以未被采用的那一支也会被校验。
- 规格里的六行可算例逐行复现（10、18、18、2、2147483658，以及抛异常那行）。
- 未发明规格之外的行为。规格里本来就没有"水平距离/对角"参数（卡片写明水平相邻由调用方确认），所以对角与寻址不在本函数范围内。

## 纯度

仅 `java.lang.Math`（无 import 行）；字节码常量池只引用 `java/lang/{Math,AssertionError,IllegalArgumentException,Object}`。
**六个参数全是基本类型**（反射确认 `isPrimitive()`），按值传递；方法体从不给参数赋值，唯一的局部量是 `base` 与 `rise`；**类没有任何字段**，因而无从与调用方输入产生别名——输入不可能被改动。

## 已知限制

- 规格不规定两种基准代价之间的大小关系，调用方若要"现有路面 vs 新建路面"的偏置，须自行设定基准值。
- 本函数只算**一步**的代价，不寻路、不看地形、不查权限。
- 提交方按项目当时的约定**未运行**检查；上面的编译运行是**主程序**做的。

## 接入后的运行时代码位置

（尚未接入。接入时填写运行时源码路径——按任务卡所写，用途是道路规划器的步代价。）
