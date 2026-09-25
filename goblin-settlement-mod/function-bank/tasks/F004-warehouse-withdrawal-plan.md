# F004：跨公共仓库的取料方案（规格 v1）

本文件可单独交给其他模型。只做快照上的纯 Java 21 计算；真实箱子读取、扣料、回滚、区块和权限由主程序负责。

## 目标与接口

文件 `WarehouseWithdrawalPlan.java`，包名 `dev.local.goblinsettlement.economy.math`，`public final class WarehouseWithdrawalPlan`、私有构造器。包含以下公开嵌套 record 和方法：

```java
public record Slot(String warehouseId, int slotIndex, String material, long count) {}
public record Withdrawal(String warehouseId, int slotIndex, String material, long count) {}
public record Plan(boolean complete, java.util.List<Withdrawal> withdrawals,
                   java.util.Map<String, Long> missing) {}
public static Plan calculate(java.util.Map<String, Long> required,
                             java.util.List<Slot> slots)
```

`warehouseId` 与 `material` 为不透明、区分大小写的非空白字符串。`slotIndex >= 0`，所有数量在 `0..Long.MAX_VALUE`。`required` 只写本次尚需数量；`slots` 只写当前已经由主程序确认可访问的公共仓库槽位和实物数量。不得把计算结果当作真实预留。

## 规则

1. 完整检查所有输入，即使 `required` 为空：参数、元素、键、值、record 字段不得为 null；字符串不得 `isBlank()`；负数非法。违反时抛 `IllegalArgumentException`。
2. 同一个 `(warehouseId, slotIndex)` 最多出现一次，重复时抛 `IllegalArgumentException`，即使材料名称不同。不同仓库的相同槽号合法。
3. 按材料名称自然升序处理需求；每种材料先按仓库 ID 自然升序，再按槽号升序取料。只使用材料名称完全相同、数量大于零的槽。每次取 `min(尚需, 槽中数量)`，不超取。
4. 对所有材料均足量时，`complete=true`，`withdrawals` 按上述处理顺序排列，`missing` 为空；零需求不生成取料行。
5. 只要有任意材料不足，`complete=false`，`withdrawals` 必须为空（禁止调用方执行部分方案），`missing` 为每种正缺口组成的按键自然升序、不可修改 Map。
6. 返回的 List、Map 是新的不可修改快照；不修改输入，也不保留可变视图。运算须支持 `Long.MAX_VALUE`，不得通过先汇总所有槽数量造成溢出。
7. 无 Minecraft、Fabric、第三方库、I/O、网络、时间、随机数或可变全局状态。

## 必须覆盖的案例

- 需求 `{oak:5}`，槽 `(B,0,oak,4),(A,2,oak,2),(A,0,oak,1)`：依次从 A/0 取 1、A/2 取 2、B/0 取 2，完整。
- 需求 `{oak:6,stone:3}`，只有 oak 5 和 stone 3：不完整、取料清单为空、缺口 `{oak:1}`。
- `required={}` 且 `slots` 包含负数、null 元素或重复仓库槽号：分别抛异常。
- 需求 `Long.MAX_VALUE`，单槽库存同为 `Long.MAX_VALUE`：完整且精确；两槽都为 `Long.MAX_VALUE` 时不得溢出。
- 输入 List/Map 在计算后改变，已返回结果不变；返回 List 和 Map 拒绝修改。

## 交付

只在 `goblin-settlement-mod/function-bank/submissions/F004/r1/` 提交 `src/WarehouseWithdrawalPlan.java`、`checks/WarehouseWithdrawalPlanCheck.java`、`NOTES.md`。检查文件用同包名和 public main，失败抛 AssertionError，不用依赖 `-ea` 的 assert。NOTES 记录来源、已做与未做；**按本项目当前约定先不要运行检查或构建，明确写“未运行，等待初版完成后的统一测试”**。不改运行时源码或其他文件。

主程序验收后仍需重新读取真实槽位、核对材料与数量、执行扣料并在失败时返还；本函数只给稳定方案，不保证真实库存随后未变化。
