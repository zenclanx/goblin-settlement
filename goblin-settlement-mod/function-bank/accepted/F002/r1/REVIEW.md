# F002 验收记录

| | |
| --- | --- |
| **任务编号与规格版本** | F002 材料缺口，规格 v1（`tasks/F002-material-shortages.md`） |
| **提交修订号** | r1（本目录即该修订的验收快照） |
| **验收时间** | 2026-09-29 00:56 +08:00 |
| **验收结论** | **通过** |
| **接入状态** | **尚未接入运行时** |

## 验证命令与结果

在**提交目录之外**的临时目录编译运行（不修改 `function-bank/` 下任何文件），JDK 21（`21.0.12.1+1`，Temurin；`JAVA_HOME` 指向它，注意 PATH 上的 `java` 是 17）：

```bash
"$JAVA_HOME/bin/javac" --release 21 -encoding UTF-8 -d out \
    submissions/F002/r1/src/MaterialShortages.java \
    submissions/F002/r1/checks/MaterialShortagesCheck.java        # exit 0

"$JAVA_HOME/bin/java" -cp out dev.local.goblinsettlement.economy.math.MaterialShortagesCheck
# 输出：[F002] 全部通过，共 46 个断言                              # exit 0
```

**主程序另写的独立对抗套件：70 条全部通过。** 该套件放在**另一个包**里调用，以此证明公开接口可被外部调用；测试范围包括：空/零/恰好相等（缺口恰为 0 时**不产生条目**）的平局、三张 Map 各自的负数与 `Long.MIN_VALUE`、键缺失与多余键、字符串自然序（混合大小写、前缀、emoji、以及**不间断空格 ` ` 不算空白因而必须被接受**）、`Long.MAX_VALUE` 量级不溢出、以及返回 Map 的不可修改性（put/remove/clear/putAll/compute/merge/iterator.remove 全抛）。

**非空转证明（变异测试）**：在临时副本上把实现改坏四处，独立套件**每一处都报红**——去掉 `available` 的钳制（`expected {stone=4} got {stone=11}`）、把 `gap > 0` 改成 `>= 0`（零缺口泄漏）、去掉空白键校验、去掉 `unmodifiableMap`。证明该套件本身有效，不是一路绿。

## 主程序检查的契约条款

- 签名、参数顺序、返回类型与 `public final class` + 私有构造器：与规格逐字一致（`javap` 核对）。
- 数量全程 `long`，无 `int` 截断、无浮点。
- 公式 `available = max(0, stock - reserved)`、`gap = max(0, demand - available)`、`gap > 0` 才入结果：与规格一致。
- 键缺失按 0 处理；返回为 `unmodifiableMap(TreeMap)`（**按键自然升序**）。
- **校验先于计算、且对三张 Map 逐一完整执行**——`required` 为空、或某条目根本不被需求引用时**也照样校验**（这是最容易漏的一条，已单独验）。
- `reserved > stock` 合法（钳到 0），不抛异常。
- 规格里的六个可算例逐条复现，含顺序与异常例。
- 未发明规格之外的公开行为（只多两个 `private static` 助手）。

## 纯度

仅 `java.util`；无 Minecraft/Fabric/第三方、无 I/O/网络/时间/随机、**无任何静态字段**（无可变全局状态）。
**不修改调用方输入**，用三种独立方式核对：传入 `unmodifiableMap` 包装（未抛即说明未调用写方法）、传入会拒绝并计数一切写操作的 `SpyMap`（**实测改动次数 0**）、调用前后用 `equals` 比对输入不变。返回结果是新 `TreeMap` 经 `unmodifiableMap` 包装，调用方之后改动输入不影响已返回结果。

## 已知限制

- 如 NOTES 自述：若把"本任务自己的预留"也算进 `stock` 传入，结果会偏小——调用方须只传**已经确认可访问**的槽位。
- 本函数只对**快照**计算，**不构成真实预留**；真实读箱、核对、扣料、失败返还由主程序负责。
- 提交方按项目当时的约定**未运行**检查；上面的编译运行是**主程序**做的。

## 接入后的运行时代码位置

（尚未接入。接入时填写运行时源码路径。）
