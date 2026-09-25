# F001：整数坐标转换为 8×8 地块位置（规格 v1）

本文件是完整任务，可以单独发给没有聊天上下文的开发模型。请实现下面的纯 Java 21 计算并交付文件，不需要了解 Minecraft 或访问完整项目。

## 目标与接口

把二维整数方块坐标划分到固定边长 8 的方形地块，返回地块编号和地块内坐标。坐标 (0,0) 是地块 (0,0) 的起点；边界沿整数 8 的倍数对齐。

文件为 PlotCoordinates.java，包名 `dev.local.goblinsettlement.planning.math`，声明 `public final class PlotCoordinates`，构造器私有，包含以下 public 成员：

```java
public record PlotPosition(int plotX, int plotZ, int localX, int localZ) {}
public static PlotPosition toPlot(int blockX, int blockZ)
```

## 规则

- 两个输入均为方块坐标，支持整个 int 范围。输出没有浮点数，也没有 Y 坐标。
- 地块编号向负无穷取整；局部坐标始终在 0..7，包含两端。
- 应满足 `blockX == 8L * plotX + localX`，Z 同理。
- 负数不能使用 Java 普通除法截断到零的结果；可使用标准库 floorDiv / floorMod。
- 不读世界、文件、网络或时间；无随机数、无可变全局状态、无 Minecraft/Fabric 或第三方依赖。

## 必须覆盖的验收案例

| 输入 (blockX, blockZ) | 输出 (plotX, plotZ, localX, localZ) |
| --- | --- |
| (0,0) | (0,0,0,0) |
| (7,8) | (0,1,7,0) |
| (-1,-8) | (-1,-1,7,0) |
| (-9,15) | (-2,1,7,7) |
| (Integer.MIN_VALUE,Integer.MAX_VALUE) | (-268435456,268435455,0,7) |

在检查程序中另用固定的小范围 -17..17 遍历组合，检验重建坐标关系及局部坐标范围。验证时用 long 计算重建式。

## 交付

如果能访问项目，只新增到 `goblin-settlement-mod/function-bank/submissions/F001/r1/`，不改其他文件：

- `src/PlotCoordinates.java`：实现。
- `checks/PlotCoordinatesCheck.java`：同包名，public main 入口，失败直接抛 AssertionError；不用 JUnit，不依赖 `-ea` 才运行的 assert 语句。
- `NOTES.md`：规格 v1、文件列表、验证命令和真实结果、限制、所引用代码来源；没有运行环境则明确写“未运行”，不要伪造成功。

若无本地文件权限，按这三个文件名输出可保存的完整内容即可。使用 Java 21 标准库，不引入构建系统。

在 r1 目录使用 JDK 21 或更高版本运行：

```text
javac --release 21 -encoding UTF-8 -d out src/PlotCoordinates.java checks/PlotCoordinatesCheck.java
java -cp out dev.local.goblinsettlement.planning.math.PlotCoordinatesCheck
```

## 主程序未来负责的适配

维度身份、地块所有权、活动区块与施工许可均由调用方处理。相同 X/Z 在不同维度不是同一块土地；这个函数只提供二维算术结果。
