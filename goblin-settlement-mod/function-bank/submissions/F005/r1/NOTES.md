# F005 提交记录 — 候选建筑地块排序

- **任务编号 / 规格版本**：F005 / 规格 v1
- **提交修订**：r1
- **提交时间**：2026-09-26
- **状态**：待验收（本文不构成验收结论）

## 运行状态

**未运行，等待初版完成后的统一测试。**

按 F005 任务卡「交付」一节的当前约定，本次**没有执行编译，也没有运行检查程序**。
下面列出的覆盖范围是**代码里写了什么**，不是**跑出来的结果**；
本文件不含任何通过声明，也不声称该实现可编译或行为正确。

## 文件列表

| 文件 | 说明 |
| --- | --- |
| `src/BuildingPlotRanker.java` | 实现，包名 `dev.local.goblinsettlement.planning.math` |
| `checks/BuildingPlotRankerCheck.java` | 独立验证程序，同包名，`public static void main` |
| `NOTES.md` | 本文件 |

## 实现说明

```java
public static final int MAX_USABLE_CELLS = 64;
public static final int MIN_USABLE_CELLS = 48;
public static final int MAX_ELEVATION_SPREAD = 2;
public record Candidate(int plotX, int plotZ, int usableCells, int elevationSpread,
                        int warehouseDistance, int roadDistance, int materialCost) {}
public static Optional<Candidate> best(List<Candidate> candidates)
```

`Candidate` 按任务卡要求实现为 `BuildingPlotRanker` 的公开嵌套 record。

处理流程：

1. `requireCandidates` 先对整个列表做完整校验，**不区分该候选稍后是否会被筛选掉**：
   `candidates` 为 null、任一元素为 null、`usableCells` 不在 `0..64`、
   `elevationSpread` / `warehouseDistance` / `roadDistance` / `materialCost` 为负，
   均抛 `IllegalArgumentException`；同一 `(plotX, plotZ)` 重复同样抛异常。
2. 单次线性扫描，跳过不合格者（`usableCells < 48` 或 `elevationSpread > 2`）。
3. 评分 `4L * warehouseDistance + 2L * roadDistance + 8L * elevationSpread + materialCost`，
   全程 long；最大量级为 `15 * Integer.MAX_VALUE ≈ 1.5e10`，不会溢出。
4. 维护「当前最优」：分数更低即替换；分数相同时按 `plotX` 小、再 `plotZ` 小替换。
   由于 `(plotX, plotZ)` 已保证唯一，比较关系是全序，**结果与输入顺序无关**。
5. 无合格候选（含空列表）返回 `Optional.empty()`，不返回 null、不使用哨兵值。
6. 不修改输入列表，不做 I/O。

## 检查程序覆盖内容（代码中已写入，尚未运行）

1. 验收案例：A `(0,0,50,1,10,4,3)` 手算得分 59、B `(1,0,64,0,12,2,2)` 手算得分 54，
   断言选中 B，并额外断言逆序输入同样选中 B。
2. 把 B 改为 `usableCells=47` 断言选中 A；把 A 也改为 `elevationSpread=3` 断言返回空。
3. 权重锁定：构造「同分互换 plotX」的候选对来钉住四个系数——
   `warehouseDistance 1 == roadDistance 2`、`roadDistance 1 == materialCost 2`、
   `elevationSpread 1 == roadDistance 4`、`materialCost` 每点恰好 1 分、
   高差每 +1 恰好 8 分。若系数被改动，同分关系被打破，胜者不再随 plotX 变化。
4. 合格性边界：`usableCells` 从 0 到 64 逐个验证合格与否，`elevationSpread` 0 到 3 逐个验证；
   并断言常量值 `48 / 64 / 2`；另断言「分数极低但不合格」的候选不会被选中。
5. 同分排序：plotX 不同、plotX 相同比 plotZ、负数地块编号按数值比较、
   三个同分候选取字典序最小者，且正反输入顺序结果一致。
6. 极值：`warehouseDistance = 1073741824`（`4 * 2^30 = 4294967296`）与低分候选对比，
   若用 int 计算会回绕为 0 并错误选中高成本候选，以此识破 int 溢出；
   全指标 `Integer.MAX_VALUE` 时断言精确得分 15032385529、
   高差 1 时为 15032385537，并验证同分时的稳定排序。
7. 交叉核对：用检查程序内的独立参考实现对 720 个确定性候选（`usableCells` 5 档 ×
   `elevationSpread` 4 档 × 三个指标各 3–4 档，坐标按计数器生成以保证唯一）做全量比对，
   并断言所选候选得分等于全部合格候选的最小得分，再轮转 137 位与整体逆序后重跑，
   验证与输入顺序无关。
8. 校验：null 列表、null 元素、`usableCells` 为 -1 / 65 / `Integer.MIN_VALUE` /
   `Integer.MAX_VALUE`、四项指标分别为负、不合格候选上的非法指标、
   重复坐标（含第三个元素重复）；并断言「相同 plotX 不同 plotZ」不算重复。
9. 输入列表在调用后逐元素比对未被修改。

## 已做与未做

**已做**

- 按规格 v1 写出实现与检查程序，只使用 Java 21 标准库（`ArrayList` / `Arrays` /
  `Collections` / `HashSet` / `List` / `Optional` / `Set`）。
- 在检查程序内写了独立的参考实现与手算得分常量，用于交叉核对而不是复述实现。
- 确认未改动运行时源码、`README.md`、任务卡以及 F001–F004 的任何文件。

**未做**

- **未编译**（`javac`），**未运行**检查程序，**未做**变异对照。
  因此检查程序本身是否存在编译错误、断言是否真的成立，均未验证。
- 未做游戏集成验证，未接入运行时源码，未登记 `accepted/F005/r1/`。
- 未与真实世界读数、区块活动状态、玩家保护区对接。

## 限制

- 只排序主程序已初筛的候选；函数**不判断**领地额度、玩家保护区、路径能否通行、
  地形是否安全，也**不认领土地**。真正选址前主程序仍须重新检查区块、保护区、材料、
  工人与施工空间。
- `materialCost` 只是用于比较的非负评分，**不代表已经扣料**。
- 返回的是输入列表中的同一个 `Candidate` 实例（record 不可变，但调用方拿到的是原对象引用）；
  本函数不复制候选，也不缓存结果。
- 三个权重 `4 / 2 / 8` 与阈值 `48 / 2` 是规格给定的比较规则，不是已调平衡的游戏数值。

## 代码来源

- 全部代码依据 F005 任务卡规格 v1 直接编写，未复制任何第三方或既有项目代码。
- 只使用 Java 21 标准库，无 Minecraft / Fabric / 第三方依赖，无世界读取、I/O、时间、随机数或全局可变状态。
- 未参考本仓库 `PopulationRules.java`，也未复用 F001–F004 的实现代码。

## 交给主程序的下一步

1. 统一测试时先编译、再运行检查程序，并亲自确认结果；本文不代表通过。
2. 建议补做变异对照（例如把 `4L` 改成 `4`（int 乘法）、把同分比较改成只比 plotX、
   把 `>= 48` 改成 `> 48`），确认检查程序不是空跑。
3. 验收通过后才复制到 `accepted/F005/r1/` 并补 `REVIEW.md`。
