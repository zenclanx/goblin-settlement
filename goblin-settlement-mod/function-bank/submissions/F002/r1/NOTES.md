# F002 提交记录 — 按库存快照计算材料缺口

- **任务编号 / 规格版本**：F002 / 规格 v1
- **提交修订**：r1
- **提交时间**：2026-09-24
- **状态**：待验收（本文不构成验收结论）

## 文件列表

| 文件 | 说明 |
| --- | --- |
| `src/MaterialShortages.java` | 实现，包名 `dev.local.goblinsettlement.economy.math` |
| `checks/MaterialShortagesCheck.java` | 独立验证程序，同包名，`public static void main` |
| `NOTES.md` | 本文件 |

## 接口实现说明

```java
public static java.util.Map<String, Long> calculate(
    java.util.Map<String, Long> required,
    java.util.Map<String, Long> accessibleStock,
    java.util.Map<String, Long> reservedByOtherTasks)
```

- 对 `required` 中每种材料：`可用 = max(0, 库存 - 其他预留)`，`缺口 = max(0, 需求 - 可用)`，只写入正缺口。
- 结果用 `TreeMap` 累积，保证 String 自然升序，再用 `Collections.unmodifiableMap` 包裹后返回。`TreeMap` 本身就是独立副本，因此计算后改动输入不会影响结果。
- 校验在计算之前对三张 Map **逐一完整执行**，包括 `required` 未用到的条目；`required` 为空也不跳过。
- 缺失键按 0 处理；`stock - reserved` 与 `demand - available` 两个减法在 `0..Long.MAX_VALUE` 区间内不会溢出，全程 long，无 int 转换与浮点。

## 验证命令与真实结果

环境：Temurin JDK 21.0.12.1，Windows。

在 `submissions/F002/r1/` 目录执行：

```text
javac --release 21 -encoding UTF-8 -d out src/MaterialShortages.java checks/MaterialShortagesCheck.java
java -cp out dev.local.goblinsettlement.economy.math.MaterialShortagesCheck
```

**真实结果（已运行）**：

```text
[F002] 全部通过，共 46 个断言
退出码 = 0
```

覆盖内容：

1. 任务卡验收表 6 行原值比对，含 `{wood:10} / {wood:8} / {wood:3} → {wood:5}`、`{stone:4} / {stone:2} / {stone:9} → {stone:4}`、`{wood:Long.MAX_VALUE}` 三例，以及 `{}` 配合 `{unused:-1}` 抛异常。
2. 键顺序：`{"b","A","a","Z","_","0"}` 的结果迭代顺序为 `["0","A","Z","_","a","b"]`；验收表第 4 行额外断言顺序为 `["stone","wood"]`。大小写不同的 `Wood` / `wood` 不合并。
3. 缺失库存条目：需求有、库存与预留都没有的材料按库存 0 计算；库存未提及但预留有记录时可用量为 0。
4. 拒绝输入：三张 Map 分别为 null；三处的 null 键；三处的 null 值；`" "` / `""` / `"\t"` / `"\u3000"` 空白键；`" \n "` 库存空白键；required / stock / reserved 三处负值以及 `Long.MIN_VALUE`。
5. 返回值拒绝 `put` / `remove` / `clear` / `putAll`，空结果同样拒绝。
6. 输入不被修改（三张 Map 计算前后 equals 比对）。
7. 快照语义：返回结果后再补货并削减需求、增加新需求，结果仍是原快照。
8. Long 边界：`Long.MAX_VALUE` 的需求 / 库存 / 预留组合，以及巨大可用量不产生虚假缺口。

## 反向对照（验证程序非空跑）

在项目外的临时目录复制一份并故意改坏实现，重新编译运行：

| 变异 | 结果 |
| --- | --- |
| 把 `Math.max(0L, stock - reserved)` 改成 `stock - reserved`（去掉可用量下界钳制） | **失败**：`期望 {stone=4} 实际 {stone=11}` |

即「预留大于库存时可用量为 0」这条规则确实被断言覆盖。该变异副本未留在本目录。

## 限制

- 只是快照上的算术结果：不转移物品、不申请预留、不承诺稍后仍能取到材料。
- 不判断箱子是否可访问、距离是否在范围内、物品是否需要归一化（如不同木头种类是否合并）；key 是不透明名称，本函数不合并、不修剪、不重命名。
- 「其他任务预留」的口径由调用方定义，本函数只做减法。若调用方传入了包含本任务自己预留的数据，结果会偏小。
- 空 Map 输入合法，返回空结果；调用方需自行区分「无缺口」与「需求本身为空」。

## 代码来源

- 全部代码为本任务卡规格 v1 直接编写，未复制任何第三方或既有项目代码。
- 只使用 Java 21 标准库（`java.util.Collections` / `TreeMap` / `Map`）。无 Minecraft / Fabric / 第三方依赖，无 I/O、时间、随机数或全局可变状态。
- 未参考本仓库 `PopulationRules.java`。

## 交给主程序的下一步

1. 检查实现与规格、接口、边界是否一致，并**亲自重跑**上面的命令。
2. 验收通过后才复制到 `accepted/F002/r1/` 并补 `REVIEW.md`。
3. 接入真实箱子库存查询与任务需求时，由集成层负责箱子扫描、物品归一化、预留归属、实际取料及取料前的再次核验。
