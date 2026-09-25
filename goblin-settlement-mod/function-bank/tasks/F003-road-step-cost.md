# F003：相邻两点的道路代价（规格 v1）

本文件是完整任务，可直接发给没有上下文的开发模型。只实现一个纯 Java 21 算术函数，不需要地图、游戏或完整寻路算法。

## 输入与接口

调用方已经确认两个点水平相邻且这一步可以纳入道路规划，现在需要一个正整数代价来比较路线。

文件 RoadStepCost.java，包名 `dev.local.goblinsettlement.planning.math`，声明 `public final class RoadStepCost`，构造器私有：

```java
public static long calculate(
    int deltaY,
    int terrainPenalty,
    boolean existingRoad,
    int existingRoadBaseCost,
    int newRoadBaseCost,
    int verticalPenaltyPerBlock)
```

- deltaY：终点高度减起点高度，单位为方块，支持 int 全范围。
- terrainPenalty：调用方已算好的该步额外地形代价，非负。
- existingRoad：这一步是否使用已有可用道路。
- existingRoadBaseCost 和 newRoadBaseCost：两种情况下的基础代价，均必须大于 0。
- verticalPenaltyPerBlock：每一格绝对高差附加的代价，非负。
- 除 deltaY 外的整数都是抽象评分权重，没有物理单位。全部权重由参数传入，例子里的数字不是已确定的游戏平衡数值。

## 精确规则

```text
base = existingRoad ? existingRoadBaseCost : newRoadBaseCost
result = base + terrainPenalty + abs(deltaY) * verticalPenaltyPerBlock
```

- 必须先将 deltaY 转成 long 再取绝对值，乘法和加法也按 long 进行；整个 int 输入域的本公式能放入 long。
- 所有参数先校验，包括本次分支没有用到的基础代价。任一基础代价 <=0 或任一罚分 <0 都抛 IllegalArgumentException。
- 结果始终为正；不以 0、负数或无穷大代表不可通行，不返回 null。
- 无输入输出副作用、无可变全局状态、无文件/网络/时间/随机数，不引用 Minecraft/Fabric 或第三方库。

## 必须覆盖的验收案例

| deltaY | terrainPenalty | existingRoad | existingRoadBaseCost | newRoadBaseCost | verticalPenaltyPerBlock | 结果 |
| --- | --- | --- | --- | --- | --- | --- |
| 0 | 0 | false | 2 | 10 | 5 | 10 |
| 1 | 3 | false | 2 | 10 | 5 | 18 |
| -1 | 3 | false | 2 | 10 | 5 | 18 |
| 0 | 0 | true | 2 | 10 | 5 | 2 |
| Integer.MIN_VALUE | 0 | false | 2 | 10 | 1 | 2147483658 |
| 0 | 0 | false | 0 | 10 | 5 | IllegalArgumentException |

另验证负罚分被拒绝、未使用的基础代价也被校验、增大非负罚分不降低结果，以及所有正权重为 Integer.MAX_VALUE 时结果仍为正且准确。

## 交付

有项目访问权时只新增到 `goblin-settlement-mod/function-bank/submissions/F003/r1/`，不改其他文件：

- `src/RoadStepCost.java`。
- `checks/RoadStepCostCheck.java`：同包名，public main；失败抛 AssertionError，不使用需 `-ea` 的 assert，不依赖 JUnit。
- `NOTES.md`：规格 v1、文件列表、验证命令与真实结果、限制和代码来源；没运行就写“未运行”。

没有文件访问权时按文件名给出完整内容即可。在 r1 目录用 JDK 21 或以上运行：

```text
javac --release 21 -encoding UTF-8 -d out src/RoadStepCost.java checks/RoadStepCostCheck.java
java -cp out dev.local.goblinsettlement.planning.math.RoadStepCostCheck
```

## 主程序未来负责的适配

主程序读取地形，决定相邻关系、坡度是否允许、玩家保护区、桥梁是否完工和区块是否活动，然后把合法候选步传入本函数。函数不判断能否建桥、不创建路线、不修改方块。A* 等搜索如果使用已有道路优惠，启发式也必须由主程序按最小合法步代价设计，不能假定每一步至少花 newRoadBaseCost。
