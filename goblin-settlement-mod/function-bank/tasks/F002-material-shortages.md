# F002：按库存快照计算材料缺口（规格 v1）

本文件是完整任务，可以单独发给没有聊天上下文的开发模型。请实现一个纯 Java 21 函数，不需要游戏环境。

## 目标与接口

一个任务需要若干种材料，给出它尚未交付的需求、当前可领取的真实库存、其他任务已经预留的数量，计算还缺多少。

文件 MaterialShortages.java，包名 `dev.local.goblinsettlement.economy.math`，声明 `public final class MaterialShortages`，构造器私有：

```java
public static java.util.Map<String, Long> calculate(
    java.util.Map<String, Long> required,
    java.util.Map<String, Long> accessibleStock,
    java.util.Map<String, Long> reservedByOtherTasks)
```

## 输入和结果约定

- key 是不透明的材料名称，区分大小写，不替调用方合并、修剪或重命名；数量单位为“个”，是 0..Long.MAX_VALUE 的整数。
- required 仅包含本任务尚未交付的量。accessibleStock 仅含现在可领取的库存；其他任务预留不含本任务自己的预留。三者由调用方提供一致且调用期间不会改变的快照。
- 缺失键按 0 处理。针对 required 中每种材料：`可用 = max(0, 库存 - 其他任务预留)`；`缺口 = max(0, 需求 - 可用)`。
- 输出只保留正缺口，键的迭代顺序为 String 自然升序。返回新建且不可修改的 Map，不保留对输入的可变视图。
- 输入的三张 Map、任一键或任一值为 null，键 isBlank() 为 true，或值小于 0，统一抛 IllegalArgumentException。即使是 required 没用到的库存条目，也要校验；required 为空也不跳过其他输入的校验。
- 预留大于库存是合法状态：可用量为 0，例如物资被玩家取走。不要返回负缺口、不要将它当作参数异常。
- 支持 Long.MAX_VALUE，不把 Long 转成 int，不使用可能回绕的累加或浮点运算。不能修改输入集合。
- 无 Minecraft/Fabric、第三方库、文件、网络、时间、随机数或全局可变状态。

## 必须覆盖的验收案例

| required | accessibleStock | reservedByOtherTasks | 结果 |
| --- | --- | --- | --- |
| {wood:10} | {wood:8} | {wood:3} | {wood:5} |
| {stone:4} | {stone:2} | {stone:9} | {stone:4} |
| {wood:2,stone:0} | {wood:5} | {} | {} |
| {wood:5,stone:4} | {wood:1} | {} | {stone:4,wood:4}，此顺序 |
| {wood:Long.MAX_VALUE} | {wood:Long.MAX_VALUE} | {wood:1} | {wood:1} |
| {} | {unused:-1} | {} | IllegalArgumentException |

另覆盖：null map、null key/value、空白键；缺少库存的材料；输入保持不变；结果拒绝 put/remove；计算后更改原输入不会改变结果。

## 交付

能访问项目时只新增到 `goblin-settlement-mod/function-bank/submissions/F002/r1/`，不改其他文件：

- `src/MaterialShortages.java`。
- `checks/MaterialShortagesCheck.java`：同包名，public main；失败抛 AssertionError，不使用需 `-ea` 的 assert，不依赖 JUnit。
- `NOTES.md`：规格 v1、文件列表、验证命令与真实结果、限制和代码来源；未能运行必须写“未运行”。

没有文件权限时按以上文件名给出完整内容即可。使用 JDK 21 或以上，在 r1 目录验证：

```text
javac --release 21 -encoding UTF-8 -d out src/MaterialShortages.java checks/MaterialShortagesCheck.java
java -cp out dev.local.goblinsettlement.economy.math.MaterialShortagesCheck
```

## 主程序未来负责的适配

箱子扫描、物品归一化、任务预留的归属、实际取料及再次核验由主程序负责。本函数只回答快照上的算术缺口，不转移物品，不申请预留，不承诺过一会儿仍然能取到材料。
