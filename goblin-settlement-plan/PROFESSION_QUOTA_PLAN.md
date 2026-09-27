# 职业名额约束 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给职业分配加名额上限，并把文档唯一写明的那个数落进代码——哨卫约每 12 名成年人 1 名、最多 4 名。

**Architecture:** 名额是纯函数 `ProfessionRules.ceiling(profession, adults)`；`scarcest` 增加 `adults` 参数并在候选里跳过已达上限的职业，返回 `Optional`。`ProfessionCoordinator` 传 `data.adultCount()`。名额只影响**新分配**，不转岗。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [PROFESSION_QUOTA_DESIGN.md](PROFESSION_QUOTA_DESIGN.md)（文中 §N 均指该文档）；GAME_DESIGN.md 第 128 行。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **只填文档写明的名额**：哨卫 `min(4, adults / 12)`；其余职业 `Integer.MAX_VALUE`。**不要为其余职业发明上限**——那是没有依据的玩法数值。
- **不转岗**：名额只影响新分配。已在岗的居民即使超出当前名额也不动（PROFESSION_DESIGN §7.1 与 §12）。
- **纯规则不得依赖 Minecraft 类型**：`ProfessionRules` 只吃 `Profession`、`int`、`java.util`。
- **本轮必须一次改完**：`scarcest` 签名变化会同时影响 `ProfessionCoordinator` 与 `ProfessionRulesCheck`，拆成多个任务会让中间态编译不过。因此本轮**只有一个代码任务**。（第四十六轮的计划在这一点上出错，已记入 UpdateLog。）
- `professionRulesCheck` 任务已注册在 `build.gradle` 的 `check` 聚合里，本轮不改 `build.gradle`。
- 代码注释用英文（与现有源码一致）。
- 不做游戏内验证（按用户约定）。
- `goblin-settlement-plan/` 下**可能有并行 agent 的未提交改动**：**在文档任务开始时**先跑 `git status`，发现不是自己改的就**先单独提交它们并署名**，再追加自己的。`UpdateLog.md` **只许在末尾追加**。

---

### Task 1: 名额规则、检查与接线

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ProfessionRules.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ProfessionCoordinator.java`
- Test: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/ProfessionRulesCheck.java`

**Interfaces:**
- Consumes: `Profession`（枚举）、`SettlementSavedData.adultCount()`、`SettlementSavedData.assignedProfessions()`、`SettlementSavedData.unassignedAdultIds()`、`SettlementSavedData.assignProfession(String, Profession)`
- Produces:
  - `ProfessionRules.ceiling(Profession profession, int adults) -> int`
  - `ProfessionRules.scarcest(List<Profession> assignedAdults, int adults) -> Optional<Profession>`（**签名变化**：原先只吃列表、返回 `Profession`）

- [ ] **Step 1: 改检查，写出失败用例（RED）**

把 `ProfessionRulesCheck.java` 里现存的三条 `scarcest` 断言补上 `adults` 参数并改为 `Optional`，再加名额相关的新用例。

1a. 把

```java
        check(ProfessionRules.scarcest(List.of()) == Profession.FARMER,
                "an empty roster starts from the first profession");
        check(ProfessionRules.scarcest(List.of(Profession.FARMER, Profession.FARMER))
                == Profession.FORESTER, "the fewest-staffed profession wins");
        check(ProfessionRules.scarcest(List.of(Profession.FORESTER, Profession.MINER))
                == Profession.FARMER, "ties fall back to enum order");
```

换成

```java
        check(ProfessionRules.scarcest(List.of(), 8).orElseThrow() == Profession.FARMER,
                "an empty roster starts from the first profession");
        check(ProfessionRules.scarcest(List.of(Profession.FARMER, Profession.FARMER), 8).orElseThrow()
                == Profession.FORESTER, "the fewest-staffed profession wins");
        check(ProfessionRules.scarcest(List.of(Profession.FORESTER, Profession.MINER), 8).orElseThrow()
                == Profession.FARMER, "ties fall back to enum order");
        checkScarcestSkipsSentryAtItsCeiling();
        checkScarcestPicksSentryBelowItsCeiling();
```

（`adults = 8` 时 `ceiling(SENTRY, 8) == 0`，但这三条用例的名单里都没有哨卫，所以结果与改动前一致——它们验的是"其余职业行为不变"。）

1b. 在 `check` 方法之前加两个新方法：

```java
    /**
     * GAME_DESIGN asks for roughly one sentry per twelve adults, at most four. Every other trade has
     * no written ceiling, so it stays uncapped rather than getting an invented number.
     */
    private static void checkSentryCeiling() {
        check(ProfessionRules.ceiling(Profession.SENTRY, 0) == 0, "no adults, no sentry");
        check(ProfessionRules.ceiling(Profession.SENTRY, 11) == 0, "eleven adults still allow none");
        check(ProfessionRules.ceiling(Profession.SENTRY, 12) == 1, "twelve adults allow the first");
        check(ProfessionRules.ceiling(Profession.SENTRY, 23) == 1, "twenty-three allow one");
        check(ProfessionRules.ceiling(Profession.SENTRY, 24) == 2, "twenty-four allow two");
        check(ProfessionRules.ceiling(Profession.SENTRY, 48) == 4, "forty-eight allow four");
        check(ProfessionRules.ceiling(Profession.SENTRY, 120) == 4, "the ceiling stops at four");
        check(ProfessionRules.ceiling(Profession.FARMER, 120) == Integer.MAX_VALUE,
                "a trade with no written ceiling stays uncapped");
    }

    /**
     * The same roster at a larger population: twenty-four adults allow two sentries, so the single
     * sentry is below its ceiling and wins on the fewest holders. Pairing this with the check above
     * pins the ceiling itself as what decides the outcome -- the roster does not change between them.
     */
    private static void checkScarcestPicksSentryBelowItsCeiling() {
        check(ProfessionRules.ceiling(Profession.SENTRY, 24) == 2,
                "twenty-four adults allow a second sentry");
        check(ProfessionRules.scarcest(SENTRY_LIGHT_ROSTER, 24).orElseThrow() == Profession.SENTRY,
                "a sentry below its ceiling wins on the fewest holders");
    }

    /**
     * One sentry and two of every other trade. The sentry holds the fewest, so it only loses when a
     * ceiling rules it out.
     */
    private static final List<Profession> SENTRY_LIGHT_ROSTER = List.of(Profession.SENTRY,
            Profession.FARMER, Profession.FARMER,
            Profession.FORESTER, Profession.FORESTER,
            Profession.MINER, Profession.MINER,
            Profession.BUILDER, Profession.BUILDER,
            Profession.HAULER, Profession.HAULER,
            Profession.ARTISAN, Profession.ARTISAN);
```

并把 `checkScarcestSkipsSentryAtItsCeiling` 里的 `var roster = List.of(...)` 换成使用同一常量：

```java
    private static void checkScarcestSkipsSentryAtItsCeiling() {
        List<Profession> roster = SENTRY_LIGHT_ROSTER;
        check(ProfessionRules.ceiling(Profession.SENTRY, roster.size()) == 1,
                "this roster is at the sentry ceiling");
        check(ProfessionRules.scarcest(roster, roster.size()).orElseThrow() == Profession.FARMER,
                "a sentry at its ceiling is skipped in favour of the next fewest");
    }
```
```

1c. 在 `main` 里加 `checkSentryCeiling();`（放在 `checkScarcestSkipsSentryAtItsCeiling` 之前）。

- [ ] **Step 2: 运行检查，确认按预期失败**

Run: `./gradlew professionRulesCheck --offline --no-daemon`

Expected: `> Task :compileTestJava FAILED`，报错形如 `找不到符号: 方法 ceiling(Profession,int)` / `方法 scarcest(List<Profession>,int)`。失败原因必须是"新 API 不存在"。

- [ ] **Step 3: 实现纯规则（GREEN 的一半）**

在 `ProfessionRules.java` 中加 `ceiling`，并把 `scarcest` 整个替换为：

```java
    /**
     * How many adults may hold this trade. Only the sentry has a documented ceiling: GAME_DESIGN asks
     * for roughly one per twelve adults, at most four. Every other trade keeps its old behaviour --
     * fewest holders wins, with no ceiling -- because no ceiling for them is written down anywhere,
     * and inventing one here would be a gameplay number with no basis.
     */
    public static int ceiling(Profession profession, int adults) {
        return switch (profession) {
            case SENTRY -> Math.min(4, adults / 12);
            default -> Integer.MAX_VALUE;
        };
    }

    /**
     * The fewest-held trade still below its ceiling; empty when every trade is full. Ties fall back to
     * enum order so results repeat. {@code adults} is the settlement's adult count, not the roster
     * size -- a ceiling is a function of how big the settlement is.
     */
    public static Optional<Profession> scarcest(List<Profession> assignedAdults, int adults) {
        Profession best = null;
        int fewest = Integer.MAX_VALUE;
        for (Profession candidate : Profession.values()) {
            if (candidate == Profession.UNASSIGNED) {
                continue;
            }
            int count = 0;
            for (Profession assigned : assignedAdults) {
                if (assigned == candidate) {
                    count++;
                }
            }
            if (count >= ceiling(candidate, adults)) {
                continue;
            }
            if (count < fewest) {
                fewest = count;
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }
```

加 `import java.util.Optional;`。（删掉原来的 `Profession best = Profession.FARMER;` 初值——改后由 `best == null` 表达"一个都不可用"。）

- [ ] **Step 4: 接线协调器**

把 `ProfessionCoordinator.tick` 里的循环

```java
        for (String residentId : data.unassignedAdultIds()) {
            data.assignProfession(residentId, ProfessionRules.scarcest(data.assignedProfessions()));
        }
```

换成

```java
        int adults = data.adultCount();
        for (String residentId : data.unassignedAdultIds()) {
            ProfessionRules.scarcest(data.assignedProfessions(), adults)
                    .ifPresent(profession -> data.assignProfession(residentId, profession));
        }
```

- [ ] **Step 5: 运行检查，确认转绿**

Run: `./gradlew professionRulesCheck --offline --no-daemon`

Expected: `ProfessionRulesCheck passed`。

- [ ] **Step 6: 跑完整构建与全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，12 项检查全部 `*Check passed`，无编译警告。

- [ ] **Step 7: 核对没有别的调用方漏改**

Run: `grep -rn "ProfessionRules.scarcest\|ProfessionRules.ceiling" src/`

Expected: 只有 `ProfessionRules` 自身的定义、`ProfessionCoordinator` 一处调用、`ProfessionRulesCheck` 若干断言。若出现别的调用方，说明步骤 4 之前漏了。

- [ ] **Step 8: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ProfessionRules.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/colony/ProfessionCoordinator.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/colony/ProfessionRulesCheck.java
git commit -m "Cap the sentry trade at one per twelve adults"
```

---

### Task 2: 完整构建收尾与项目文档

**Files:**
- Modify: `goblin-settlement-plan/PROFESSION_DESIGN.md`（§12 勾掉名额那条）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1 的改动与构建结果
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：**先单独提交它们**并署名，**然后再**做本任务的文档改动。

- [ ] **Step 2: 标记 PROFESSION_DESIGN §12**

把「职业名额约束（哨卫每 12 成人 1 名等）」那条标记为已完成并指向 `PROFESSION_QUOTA_DESIGN.md`。注意原文有"等"字——实现只填了文档写明的哨卫一项，**其余职业的上限仍是未定**，要把这一点写清楚，别让"等"被读成"已经给全部职业定了名额"。

- [ ] **Step 3: 追加 UpdateLog**

在末尾追加（时间换成实际操作时刻）：

```markdown
## [<开始> – <结束>] 第四十八轮：职业名额约束（哨卫上限）

- [<时间>] 按 PROFESSION_QUOTA_DESIGN.md 与 PROFESSION_QUOTA_PLAN.md 执行，落实 PROFESSION_DESIGN §12 的「职业名额约束」。
- [<时间>] 修正的偏差：`scarcest` 把 7 个职业强制摊平，第 7 个成人就会当上哨卫；而 GAME_DESIGN 第 128 行要求哨卫约每 12 名成年人 1 名、最多 4 名，即 12 人以下不该有哨卫。
- [<时间>] 新增 `ProfessionRules.ceiling(profession, adults)`：哨卫 `min(4, adults/12)`，其余职业无上限。`scarcest` 增加 `adults` 参数、跳过已达上限的职业、改为返回 `Optional<Profession>`（"所有职业都满"是名额表制度上可能的状态，只是今天表里只有哨卫有上限；让签名如实表达比悄悄越过上限诚实）。`ProfessionCoordinator` 传 `data.adultCount()`，为空时留作未定职、下次再试。
- [<时间>] **只填文档写明的数**：没有给其余职业发明上限——那会是没有依据的玩法数值。§12 原文的"等"字仍代表"其余上限未定"，不是"已给全部职业定额"。
- [<时间>] **可见的行为变化**：初始 8 人聚落改前会出现 1 名哨卫，改后 `ceiling(SENTRY, 8) == 0`，要等 12 名成人才放开第一个名额。其余职业不受影响。
- [<时间>] **不转岗**（既有决定，§7.1）：名额只影响新分配。所以人口降到线下时已有哨卫不会退出，哨卫数可能长期高于当前名额——这是"不强制转岗"的必然结果。
- [<时间>] 验证：TDD 先看 `:compileTestJava FAILED`（找不到 `ceiling(Profession,int)` 与 `scarcest(List,int)`），实现后转绿。完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，12 项独立检查全部 *Check passed（含 `ProfessionRulesCheck` 新增的 8 条 `ceiling` 断言与 2 条 `scarcest` 名额断言，另有既有 3 条 `scarcest` 断言更新参数后保持通过）。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>。
- [<时间>] 未完成：不构成玩法验收；**哨卫目前没有专属工作**（§2.3 按通才对待），所以本轮效果是"少一个名义上的哨卫"而非"少一个干活的人"；其余职业的上限未定；强制转岗、哨卫的巡逻与警报、儿童体型、真实手持工具仍未做。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「职业系统剩余」：把「职业名额约束」记为已完成（并写明只做了哨卫一项、其余上限未定），保留其余项。
- 「本轮接入的内容」新增一节「第四十八轮：职业名额约束」。
- 「本轮验证进展」替换为本轮构建结果。
- 「阶段定位」阶段 4：补上"哨卫名额已约束、其专属工作仍缺"。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the profession quota round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2 名额表 → Task 1 Step 3 的 `ceiling`。
- 设计 §3 `scarcest` 改签名与 `Optional` 理由 → Task 1 Step 3（含 javadoc 原文）。
- 设计 §4 接线 → Task 1 Step 4。
- 设计 §5 可见行为变化 → Task 2 Step 3 的日志条目。
- 设计 §6 验证（8 条 ceiling + 2 条名额 scarcest + 既有 3 条保留）→ Task 1 Step 1。
- 设计 §7 风险（签名连带、不转岗、整数除法取整、"哨卫本就没工作"）→ 分别落在 Global Constraints、Task 2 Step 3/4 的记录、Step 1b 的取整断言行、以及日志的"未完成"条。

**2. 占位符扫描**

- 无 TBD/TODO/待填。纯规则与检查的代码都给了可粘贴全文。
- Task 2 Step 3 的 jar 字节数是测量值占位（`<字节数>`），实现时填。
- Global Constraints 明说本轮**只有一个代码任务**并给了理由（第四十六轮在这一点上出错）——这是刻意的，不是漏拆。

**3. 类型一致性**

- `ceiling(Profession, int) -> int` 与 `scarcest(List<Profession>, int) -> Optional<Profession>` 在 Task 1 Step 1 的检查、Step 3 的实现、Step 4 的调用三处一致。
- `Optional.ofNullable(best)`：`best` 初值为 `null`，只在"存在低于上限的候选"时被赋值；因此"全部职业都满"时返回 `Optional.empty()`，与 `ifPresent` 的调用方式匹配。
- `Profession.UNASSIGNED` 在 `scarcest` 里被跳过（既有逻辑），因此 `ceiling` 的 `default` 分支覆盖它不会影响分配结果。
- 两条名额 `scarcest` 用例**共用同一份名单常量 `SENTRY_LIGHT_ROSTER`**（1 个哨卫、其余职业各 2 人），只把 `adults` 从 `roster.size()`（13 → 上限 1，哨卫被跳过）换成 24（上限 2，哨卫胜出）。这样"结果只由上限决定"是构造出来的，而不是靠两份不同的名单凑出来的——计划初稿在这里写错过一次（另一份名单里其余职业计数为 0，会选到林工而非哨卫，根本测不到该规则），已修正。
- `SENTRY_LIGHT_ROSTER` 声明为 `private static final List<Profession>`，写在两个方法之后；Java 允许静态字段在方法之后声明，但**若实现者觉得可读性差，把它移到类顶部也完全可以**——计划不强制位置。
- `import java.util.Optional;` 需要加进 `ProfessionRules`；`ProfessionCoordinator` 不需要（用的是 `ifPresent` 与 `var`/方法引用，且 `Profession` 已在同包）。
