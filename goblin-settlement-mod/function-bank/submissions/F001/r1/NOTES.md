# F001 提交记录 — 坐标转地块

- **任务编号 / 规格版本**：F001 / 规格 v1
- **提交修订**：r1
- **提交时间**：2026-09-24
- **状态**：待验收（本文不构成验收结论）

## 文件列表

| 文件 | 说明 |
| --- | --- |
| `src/PlotCoordinates.java` | 实现，包名 `dev.local.goblinsettlement.planning.math` |
| `checks/PlotCoordinatesCheck.java` | 独立验证程序，同包名，`public static void main` |
| `NOTES.md` | 本文件 |

## 接口实现说明

```java
public static final int PLOT_SIZE = 8;
public record PlotPosition(int plotX, int plotZ, int localX, int localZ) {}
public static PlotPosition toPlot(int blockX, int blockZ)
```

`PlotPosition` 实现为 `PlotCoordinates` 的**嵌套 public record**。原因：任务卡要求「文件为 PlotCoordinates.java，声明 `public final class PlotCoordinates`，包含以下 public 成员」，
而一个 Java 源文件只能有一个 public 顶层类型，因此该 record 作为成员嵌套在类中。调用方写作 `PlotCoordinates.PlotPosition`；验证程序用单类型 import 引入。

核心实现使用 `Math.floorDiv` / `Math.floorMod`，保证地块编号向负无穷取整、局部坐标恒在 0..7，不出现普通除法的截断到零问题。
输出构造为新的 record 实例，输入为 int 原始值，无副作用。

## 验证命令与真实结果

环境：Temurin JDK 21.0.12.1（`C:\Users\27700\AppData\Local\Programs\Java\jdk-21.0.12.1+1`），Windows。

在 `submissions/F001/r1/` 目录执行：

```text
javac --release 21 -encoding UTF-8 -d out src/PlotCoordinates.java checks/PlotCoordinatesCheck.java
java -cp out dev.local.goblinsettlement.planning.math.PlotCoordinatesCheck
```

**真实结果（已运行）**：

```text
[F001] 全部通过，共 7418 个断言
退出码 = 0
```

覆盖内容：

1. `PLOT_SIZE == 8`。
2. 任务卡验收表全部 5 行原值比对，含 `(Integer.MIN_VALUE, Integer.MAX_VALUE) → (-268435456, 268435455, 0, 7)`。
3. 取整边界：`k = -5..5` 时 `8k-1` 必须落在上一地块的局部 7，`8k` 必须落在本地块的局部 0。
4. 固定范围 `-17..17` 全组合（1225 组）：局部坐标范围、`blockX == 8L * plotX + localX` 的 long 重建式、与 `floorDiv` 的一致性。
5. int 全域极值：四个角点与 `MIN_VALUE / MIN_VALUE+1 / MAX_VALUE-1 / MAX_VALUE / -1 / 0 / 1` 的 long 重建。

## 反向对照（验证程序非空跑）

为确认验证程序确实能发现错误，在项目外的临时目录复制一份并故意改坏实现，重新编译运行：

| 变异 | 结果 |
| --- | --- |
| 用 `blockX / PLOT_SIZE`、`blockX % PLOT_SIZE` 替换 `floorDiv` / `floorMod` | **失败**：`输入 (-1,-8) 期望 PlotPosition[plotX=-1, plotZ=-1, localX=7, localZ=0] 实际 PlotPosition[plotX=0, plotZ=-1, localX=-1, localZ=0]` |

即负数取整错误会被验收表第 3 行直接拦下。该变异副本未留在本目录。

## 限制

- 只做二维算术。维度身份、地块所有权、活动区块与施工许可由调用方判断；相同 X/Z 在不同维度不是同一块土地。
- 不提供 Y 坐标，不提供世界边界裁剪；`plotX` / `plotZ` 在 int 全域有效，不代表是世界坐标的合法区块。
- 输出为不可变 record，但只保证值语义，不代表地块已被占用或可建造。
- 验证覆盖的是输入输出契约，不能证明游戏内行为正确。

## 代码来源

- 全部代码为本任务卡规格 v1 直接编写，未复制任何第三方或既有项目代码。
- 只使用 Java 21 标准库（`java.lang.Math`）。无 Minecraft / Fabric / 第三方依赖，无 I/O、时间、随机数或全局可变状态。
- 未参考本仓库 `PopulationRules.java`。

## 交给主程序的下一步

1. 检查实现与规格、接口、边界是否一致，并**亲自重跑**上面的命令。
2. 验收通过后才复制到 `accepted/F001/r1/` 并补 `REVIEW.md`；本目录的通过声明不代表验收通过。
3. 接入地块与领地系统时，在调用层把 Minecraft 坐标转成 int 后调用本函数，并在运行时源码中继续维护那一份实现。
