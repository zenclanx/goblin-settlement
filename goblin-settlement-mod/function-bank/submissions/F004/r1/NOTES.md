# F004 提交记录 — 跨公共仓库的取料方案

- **任务编号 / 规格版本**：F004 / 规格 v1
- **提交修订**：r1
- **提交时间**：2026-09-26
- **状态**：待验收（本文不构成验收结论）

## 运行状态

**未运行，等待初版完成后的统一测试。**

按 F004 任务卡「交付」一节的当前约定，本次**没有执行编译，也没有运行检查程序**。
因此下面列出的断言覆盖范围全部是**代码里写了什么**，不是**跑出来的结果**；
本文件不含任何通过声明，也不声称该实现可编译或行为正确。

## 文件列表

| 文件 | 说明 |
| --- | --- |
| `src/WarehouseWithdrawalPlan.java` | 实现，包名 `dev.local.goblinsettlement.economy.math` |
| `checks/WarehouseWithdrawalPlanCheck.java` | 独立验证程序，同包名，`public static void main` |
| `NOTES.md` | 本文件 |

## 实现说明

```java
public record Slot(String warehouseId, int slotIndex, String material, long count) {}
public record Withdrawal(String warehouseId, int slotIndex, String material, long count) {}
public record Plan(boolean complete, List<Withdrawal> withdrawals, Map<String, Long> missing) {}
public static Plan calculate(Map<String, Long> required, List<Slot> slots)
```

三个 record 按任务卡要求实现为 `WarehouseWithdrawalPlan` 的公开嵌套类型（一个源文件只能有
一个 public 顶层类型）。检查程序用单类型 import 引入。

处理流程：

1. 先完整校验 `required`（含 null 键、空白键、null 值、负值），再完整校验 `slots`
   （null 列表、null 元素、`warehouseId` / `material` 为 null 或空白、`slotIndex < 0`、
   `count < 0`，以及 `(warehouseId, slotIndex)` 重复）。**校验在取料之前完成，且与
   `required` 是否为空无关**，`required` 为空时同样走完全部校验。
2. 把 `required` 复制进 `TreeMap`（材料名自然升序，同时是独立副本）。
3. 只把 `count > 0` 的槽按材料分组，组内按「仓库 ID 自然升序 → 槽号升序」排序。
   排序用显式比较器 `SLOT_ORDER`，不依赖输入顺序。
4. 逐材料取料：只匹配材料名完全相同的槽，每次取 `min(remaining, count)`，
   `remaining` 逐个相减。**不做「先汇总所有槽数量」的求和**，因此
   `Long.MAX_VALUE` 场景不会溢出。
5. 任一材料仍有剩余即 `complete = false` 并记入 `missing`；此时返回
   `withdrawals = List.of()`，**不给出部分方案**，避免调用方执行半截取料。
6. 返回值：`List.copyOf(...)` / `Collections.unmodifiableMap(...)`，均为新建且不可修改，
   不保留对输入的可变视图。

## 检查程序覆盖内容（代码中已写入，尚未运行）

1. 验收案例一：需求 `{oak:5}`、槽 `(B,0,oak,4) (A,2,oak,2) (A,0,oak,1)`，
   断言取料顺序为 `A/0 取 1、A/2 取 2、B/0 取 2`、`complete`、缺口为空。
2. 验收案例二：需求 `{oak:6,stone:3}`、槽只有 oak 5 与 stone 3，
   断言 `complete=false`、取料清单为空、缺口 `{oak:1}`。
3. `required={}` 时分别配合「负数量槽位」「null 元素」「重复仓库槽号」「null 列表」，断言各自抛异常；
   并断言空需求返回完整且无取料行。
4. `Long.MAX_VALUE`：单槽同量、两槽同量的精确取料、跨槽相加、差 1 判缺、完全无库存。
5. 输入 List/Map 在计算后改变，已返回结果不变；返回的 List 与 Map 拒绝 add/remove/put。
6. 顺序：材料升序（oak 先于 stone）、仓库 ID 升序优先于槽号升序、大小写按 String 自然顺序、
   逆序输入结果一致。
7. 其它边界：`count == 0` 的槽不参与取料、不超取、材料名区分大小写、
   不同仓库同槽号合法、同仓库同槽号（即使材料不同）判重复、
   `slotIndex` 与数量为 `Integer.MIN_VALUE` / `Long.MIN_VALUE` 时被拒、
   未被 `required` 提到的材料其非法槽位同样被拒、第二个及之后的槽位非法也能被发现。

## 已做与未做

**已做**

- 按规格 v1 写出实现与检查程序，只使用 Java 21 标准库
  （`ArrayList` / `Comparator` / `HashSet` / `List` / `Map` / `Set` / `TreeMap` / `Collections`）。
- 自检了实现内部逻辑与检查程序的用例设计，发现并修正了检查程序里一处自身写错的用例
  （原先把「需求 5、库存 5」误当成缺料场景，已改为需求 6、库存 5）。
- 确认未改动运行时源码、`README.md`、任务卡以及 F001–F003 的任何文件。

**未做**

- **未编译**（`javac`），**未运行**检查程序，**未做**变异对照。
  因此检查程序本身是否存在编译错误、断言是否真的成立，均未验证。
- 未做游戏集成验证，未接入运行时源码，未登记 `accepted/F004/r1/`。
- 未与真实箱子、物品归一化、预留归属对接。

## 限制

- 只是快照上的方案，**不是真实预留**。主程序在验收后仍须重新读取真实槽位、核对材料与数量、
  执行扣料，并在失败时返还。
- 不保证真实库存随后未变化；两个 `(warehouseId, slotIndex)` 相同的槽位会被判为重复而非合并，
  同一槽位被拆分记录时需由调用方先归并。
- 不判断箱子是否可访问、是否在距离范围、是否需要物品归一化；材料名是不透明名称。
- 不完整时故意不返回任何取料行，调用方无法据此获取「部分可满足清单」；
  如需部分方案，应使用 F002 的缺口计算另行处理。

## 代码来源

- 全部代码依据 F004 任务卡规格 v1 直接编写，未复制任何第三方或既有项目代码。
- 只使用 Java 21 标准库，无 Minecraft / Fabric / 第三方依赖，无 I/O、网络、时间、随机数或全局可变状态。
- 未参考本仓库 `PopulationRules.java`，也未复用 F001–F003 的实现代码。

## 交给主程序的下一步

1. 统一测试时先编译、再运行检查程序，并亲自确认结果；本文不代表通过。
2. 建议补做变异对照（例如去掉 `Math.min` 的钳制、把重复槽位检测改成按材料区分、
   把逐槽相减改成先汇总求和），确认检查程序不是空跑。
3. 验收通过后才复制到 `accepted/F004/r1/` 并补 `REVIEW.md`。
