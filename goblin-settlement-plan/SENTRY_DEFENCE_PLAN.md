# 哨卫有限自卫 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 哨卫被攻击时短距还击——只打打它的那个目标，只打近的，只打一会儿；"谁算威胁"的判断与应用层共用同一份。

**Architecture:** 居民加攻击力与 `MeleeAttackGoal`（照傀儡的优先级顺序），但只有哨卫会被赋目标：`DefenseCoordinator.onResidentAttack` 把攻击者交给受害者自己判定（`retaliateAgainst` 里判职业）。目标的生命周期在居民的 `customServerAiStep` 里维护，并在打架期间**跳过工作推进**以让出导航。`isPermittedAttacker` 从傀儡搬到 `DefenseCoordinator`，让警戒守卫与自卫判定共用一份。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [SENTRY_DEFENCE_DESIGN.md](SENTRY_DEFENCE_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。
- 构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行。
- **只打打它的那个目标**：不索敌、不换目标。看见怪物就冲上去是**报警**的职责（第四十九/五十轮已做），不是自卫。
- **绝不追远**：牵引绳 8 格（傀儡的 `DEFENSE_RADIUS` 是 24）。超出即脱战。
- **发明值必须标注为发明**（8 格、2.0）——文档没有依据，不要让后人以为有。
- **既有傀儡警戒逻辑一行不动**：`alertToResidentAttack` / `alertToSighting` / `onResidentAttack` 的告警部分只允许增加调用，不允许改动行为。
- **`isPermittedAttacker` 只能有一份**：从傀儡搬到协调器，两边共用。搬完必须 diff 核对傀儡侧的判定逐字等价。
- **本轮不新增独立检查**（连续第三轮）。日志与状态必须写明，不得记成已验证。
- 不做游戏内验证（按用户约定）。
- `goblin-settlement-plan/` 下**可能有并行 agent 的未提交改动**：**在文档任务开始时**先跑 `git status`，发现不是自己改的就**先单独提交它们并署名**，再追加自己的。`UpdateLog.md` **只许在末尾追加**。

---

### Task 1: 共享威胁判定、还击入口与实体机制

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/DefenseCoordinator.java`（搬入 `isPermittedAttacker`；`onResidentAttack` 增加还击调用）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/GoblinGolemEntity.java`（移除私有判定，两处改为调用协调器那份）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`（属性、goal、字段、两个方法、主循环守卫）

**Interfaces:**
- Consumes: `GoblinCitizenEntity.profession()`、既有的 `onResidentAttack` 事件路径、`Mob.setTarget`
- Produces:
  - `DefenseCoordinator.isPermittedAttacker(LivingEntity entity) -> boolean`（**public**，取代 `GoblinGolemEntity` 里那份）
  - `GoblinCitizenEntity.retaliateAgainst(LivingEntity attacker) -> boolean`
  - `GoblinCitizenEntity.maintainRetaliation(ServerLevel level)`（private）

- [ ] **Step 1: 把"谁算威胁"搬到协调器**

1a. 在 `DefenseCoordinator` 里加（放在 `SIGHTING_RADIUS` 附近）：

```java
    /**
     * Who counts as an attacker: monsters, and players who are neither creative nor spectators. This is
     * the one definition -- the golems' alert guards and a resident's decision to hit back both use it.
     *
     * Not the same question as the sighting filter in reportSighting, which admits monsters only:
     * GAME_DESIGN says a player who merely walks past is not an enemy, while a player who does attack
     * is handled by onResidentAttack.
     */
    public static boolean isPermittedAttacker(LivingEntity entity) {
        return entity instanceof Monster || entity instanceof ServerPlayer player
                && !player.isCreative() && !player.isSpectator();
    }
```

1b. 删掉 `GoblinGolemEntity` 里的

```java
    static boolean isPermittedAttacker(LivingEntity entity) {
        return entity instanceof Monster || entity instanceof ServerPlayer player
                && !player.isCreative() && !player.isSpectator();
    }
```

并把该类里两处 `isPermittedAttacker(...)` 调用改为 `DefenseCoordinator.isPermittedAttacker(...)`（同包，不需要 import）。

1c. `ServerPlayer` 若 `DefenseCoordinator` 未 import，加 `import net.minecraft.server.level.ServerPlayer;`。`Monster` 与 `LivingEntity` 第五十轮已加。`GoblinGolemEntity` 里因此可能不再需要 `Monster` / `ServerPlayer` 的 import——**用编译结果确认后再删，不要预先删**。

1d. **核对**：Run `git diff -- goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/GoblinGolemEntity.java`

Expected: 只有"删掉那个方法 + 两处调用改名"的改动，判定表达式逐字未变。

- [ ] **Step 2: 受击时把攻击者交给受害者**

在 `DefenseCoordinator.onResidentAttack` 的空值守卫之后、傀儡循环之前加：

```java
        // Whether to hit back is the resident's own call: it knows its trade. The golems below are
        // summoned regardless.
        if (attacker instanceof LivingEntity living) {
            victim.retaliateAgainst(living);
        }
```

**傀儡循环与 `VanillaIronGolemBridge.alert` 一行不动。**

- [ ] **Step 3: 居民加属性与 goal**

3a. `createAttributes` 改成：

```java
    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 16.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.ATTACK_DAMAGE, RETALIATION_ATTACK_DAMAGE);
    }
```

3b. `registerGoals` 改成（**照傀儡的顺序**：melee 1 / lookAtPlayer 2 / randomLook 3）：

```java
    @Override
    protected void registerGoals() {
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.0, true));
        goalSelector.addGoal(2, new LookAtPlayerGoal(this, Player.class, 6.0F));
        goalSelector.addGoal(3, new RandomLookAroundGoal(this));
    }
```

（原来 lookAtPlayer 是 0、randomLook 是 1；此改动只是让近战排在它们前面，与傀儡一致。`MeleeAttackGoal` 在没有目标时惰性，所以对不还击的居民没有行为影响。）

3c. 需要 `import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;`；`Attributes` 与 `Player` 该文件已在用。

- [ ] **Step 4: 居民加常量、字段与两个方法**

4a. 在 `private int patrolIndex;` 之后加：

```java
    private int retaliationTicks;
```

4b. 在类里（`PATROL_ARRIVE_DISTANCE_SQ` 附近）加常量：

```java
    /** How far a sentry will step to hit back. Far shorter than a golem's DEFENSE_RADIUS of 24. */
    private static final double RETALIATION_RANGE = 8.0;
    /** The same fifteen seconds a golem holds an alert. */
    private static final int RETALIATION_TICKS = 20 * 15;
    /** This design's own number -- half the weakest golem's attack damage, with nothing behind it. */
    private static final double RETALIATION_ATTACK_DAMAGE = 2.0;
```

4c. 在 `hasPatrolWork` 之后加：

```java
    /**
     * Called when this resident is attacked. Only a sentry hits back: everyone else relies on the
     * golems, which is what GAME_DESIGN means by sentries keeping watch and golems doing the fighting.
     * Returns whether a target was taken.
     */
    public boolean retaliateAgainst(LivingEntity attacker) {
        if (profession() != Profession.SENTRY || attacker == null || !attacker.isAlive()
                || attacker.level() != level()
                || !DefenseCoordinator.isPermittedAttacker(attacker)
                || distanceToSqr(attacker) > RETALIATION_RANGE * RETALIATION_RANGE) {
            return false;
        }
        setTarget(attacker);
        retaliationTicks = RETALIATION_TICKS;
        return true;
    }

    /** Drops the target once the fight is over, the attacker is out of reach, or the clock runs out. */
    private void maintainRetaliation(ServerLevel level) {
        LivingEntity target = getTarget();
        if (target == null) {
            retaliationTicks = 0;
            return;
        }
        boolean expired = retaliationTicks <= 0 || !target.isAlive() || target.level() != level
                || distanceToSqr(target) > RETALIATION_RANGE * RETALIATION_RANGE;
        if (expired) {
            setTarget(null);
            getNavigation().stop();
            retaliationTicks = 0;
            return;
        }
        retaliationTicks--;
    }
```

（`getTarget()` / `setTarget` 来自 `Mob`，`PathfinderMob` 继承得到，不需要新 import。）

- [ ] **Step 5: 主循环里维护目标并让出导航**

在 `customServerAiStep` 里，把

```java
        if (settlementData.isCancelledWorker(getUUID().toString())) {
            applyProjectCancellation(level);
            return;
        }
        if (workStage == WorkStage.IDLE || workStage == WorkStage.COMPLETE
                || workStage == WorkStage.RECOVERED || workStage == WorkStage.ABORTED) {
            return;
        }
```

换成

```java
        if (settlementData.isCancelledWorker(getUUID().toString())) {
            applyProjectCancellation(level);
            return;
        }
        maintainRetaliation(level);
        if (getTarget() != null) {
            // The melee goal owns the navigation while a fight lasts; work must not wrestle it for the
            // wheel. Registration and cancellation above still run.
            waitReason = "fighting back";
            return;
        }
        if (workStage == WorkStage.IDLE || workStage == WorkStage.COMPLETE
                || workStage == WorkStage.RECOVERED || workStage == WorkStage.ABORTED) {
            return;
        }
```

**位置是有意的**：放在"取消检查之后、工作推进之前"——打架期间聚落登记、职业同步与被取消工人的处理照常，只有干活让位。`maintainRetaliation` 每 tick 都跑（不受工作节流影响），否则计时不会按时递减。

- [ ] **Step 6: 编译并跑全部检查**

Run: `./gradlew build --offline --no-daemon`

Expected: `BUILD SUCCESSFUL`，**13 项**检查全部 `*Check passed`，无编译警告。

若报 `ServerPlayer` / `Monster` 在 `GoblinGolemEntity` 里未使用，那是残留 import，删掉。

- [ ] **Step 7: 核对接线**

Run: `grep -n "isPermittedAttacker\|retaliateAgainst\|maintainRetaliation\|RETALIATION_\|MeleeAttackGoal" src/main/java/dev/local/goblinsettlement/defense/DefenseCoordinator.java src/main/java/dev/local/goblinsettlement/defense/GoblinGolemEntity.java src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`

Expected: 判定只在协调器里定义一次；傀儡两处调用它；居民里有 `retaliateAgainst`/`maintainRetaliation`/三个常量/`MeleeAttackGoal`。

Run: `grep -rn "isPermittedAttacker" src/main/java/ | wc -l`

Expected: 4（1 处定义 + 傀儡 2 处调用 + 居民 1 处调用）。

- [ ] **Step 8: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/DefenseCoordinator.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/GoblinGolemEntity.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java
git commit -m "Let a sentry hit back, briefly and close"
```

---

### Task 2: 完整构建收尾与项目文档

**Files:**
- Modify: `goblin-settlement-plan/PROFESSION_DESIGN.md`（§12 勾掉自卫那条）
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只在末尾追加**）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`

**Interfaces:**
- Consumes: Task 1 的改动与构建结果
- Produces: 本轮的可追溯记录

- [ ] **Step 1: 先查并行改动**

Run: `git -C .. status --short`

若 `goblin-settlement-plan/` 下有**不是你改的**改动：**先单独提交它们**并署名，**然后再**做本任务的文档改动。

- [ ] **Step 2: 更新 PROFESSION_DESIGN §12**

把「哨卫的**有限自卫**」那条（若在上轮已写成"仍未做"，照实际改写）标为已完成并指向 `SENTRY_DEFENCE_DESIGN.md`。写明进度：GAME_DESIGN 第 30 行四项职责（巡逻、报警、引导避难、有限自卫）**至此完成三项**，**只剩引导避难**。

- [ ] **Step 3: 追加 UpdateLog**

在末尾追加（时间换成实际操作时刻；**逐条写真实时间，不要留占位**）：

```markdown
## [<开始> – <结束>] 第五十一轮：哨卫有限自卫

- [<时间>] 按 SENTRY_DEFENCE_DESIGN.md 与 SENTRY_DEFENCE_PLAN.md 执行，落实 PROFESSION_DESIGN §12 的「哨卫的有限自卫」。GAME_DESIGN 第 30 行四项职责至此完成三项，只剩引导避难。
- [<时间>] "有限"落在三条上：**只打打它的那个目标**（不索敌、不换目标——看见怪物就冲上去是报警的职责，不是自卫）、**只打近的**（牵引绳 8 格，傀儡的 DEFENSE_RADIUS 是 24）、**只打一会儿**（15 秒，与傀儡的 ALERT_TICKS 同值）。
- [<时间>] 居民 `extends PathfinderMob`，此前 goal 里只有看的两个、属性里没有 ATTACK_DAMAGE（傀儡两样都有）。本轮加 ATTACK_DAMAGE 与 MeleeAttackGoal，并照傀儡的优先级顺序重排（melee 1 / lookAtPlayer 2 / randomLook 3）。**这两样加在实体类上，所以每个居民都有**；但 MeleeAttackGoal 在没有目标时惰性，而只有哨卫会被赋目标（retaliateAgainst 里判职业）。
- [<时间>] 目标来源：`DefenseCoordinator.onResidentAttack` 已在居民受击时被调用，加一次调用把攻击者交给受害者自己决定。**要不要还击是居民自己的政策**（它知道自己的职业），协调器只负责"有人被打"这个事件。既有傀儡警戒逻辑一行未动。
- [<时间>] **把"谁算威胁"从傀儡搬到 `DefenseCoordinator` 并设为 public**，让警戒守卫与自卫判定共用一份。理由是本项目在床位判据上修过的那类分叉——同一判断有两个副本，改一处漏一处就静默不一致。已 diff 核对傀儡侧判定逐字等价。
- [<时间>] 脱战与让位：居民的 customServerAiStep 里维护目标生命周期（死亡/超距/超时→清目标），并**在打架期间跳过工作推进**——否则巡逻每 tick 的 moveTo 会和 MeleeAttackGoal 抢方向盘。跳过点选在"取消检查之后、工作推进之前"，打架期间登记与职业同步照常。
- [<时间>] **两个数值是发明，已在源码注释里标注**：牵引绳 8 格、攻击力 2.0（傀儡最低档 4.0 的一半）。15 秒沿用傀儡既有的 ALERT_TICKS，不是新造的。发明值明说没有文档依据，不假装有出处。
- [<时间>] 验证：完整构建 ./gradlew build --offline --no-daemon BUILD SUCCESSFUL，**13 项**独立检查全部 *Check passed。产物 build/libs/goblin-settlement-0.1.0.jar：<字节数>。
- [<时间>] **连续第三轮没有新增独立检查**（第四十七轮派工收口、第五十轮报警、本轮自卫）。实体侧这批改动合起来**没有任何自动化证据**，只有编译通过；既有 13 项检查覆盖的是纯规则，碰不到这些。**统一测试时应把实体行为排在最前。**
- [<时间>] 未完成：不构成玩法验收；引导避难未做（四项职责最后一项）；**给所有居民加了攻击值与近战 goal**，目前只有哨卫会被赋目标，但实体行为面确实扩大了；哨卫攻击力 2.0、生命 16，对僵尸单挑未必赢——这是刻意的（"有限"），它的价值在报警与拖延而非击杀；数值为发明；导航让位的约定是新的，改巡逻 tick 的人必须知道。
```

- [ ] **Step 4: 更新 CURRENT_STATUS**

- 「更新日期」改为本次时刻。
- 「职业系统剩余」：把「哨卫的有限自卫」记为已完成，写明四项职责完成三项、**只剩引导避难**。
- 「本轮接入的内容」新增一节「第五十一轮：哨卫有限自卫」。
- 「本轮验证进展」替换为本轮构建结果，并**显式写明本轮无新增检查、且这是连续第三轮**。
- 「阶段定位」阶段 4：把哨卫那句补上"自卫"。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the sentry self-defence round"
git push origin main
```

Expected: 推送成功。若被拒，先 `git pull --rebase origin main` 再推，**不要**强推。

---

## 自查记录

**1. 规格覆盖**

- 设计 §2.1 属性与 goal → Task 1 Step 3。
- 设计 §2.2 目标从哪来 → Task 1 Step 2。
- 设计 §2.3 判定只有一份 → Task 1 Step 1（含 diff 核对）。
- 设计 §3 数值与出处 → Task 1 Step 4b 的注释与 Task 2 Step 3 的记录。
- 设计 §4 脱战与让位 → Task 1 Step 4c/5。
- 设计 §5 无新检查 → Global Constraints + Task 2 Step 3/4。
- 设计 §6 风险 → Task 2 Step 3 的"未完成"条。

**2. 占位符扫描**

- 无 TBD/TODO/待填。属性、goal、两个方法与主循环守卫都给了可粘贴全文。
- Task 1 Step 1c 说"用编译结果确认后再删 import，不要预先删"——条件性动作，判据明确。
- Task 2 Step 3 的 `<开始>`/`<时间>`/`<字节数>` 是模板占位；正文已要求逐条写真实时间——**第四十八与第五十轮各漏填过一次**，执行时务必替换。

**3. 类型一致性**

- `retaliateAgainst(LivingEntity) -> boolean` 与 `DefenseCoordinator.onResidentAttack` 里的 `attacker instanceof LivingEntity living` 绑定一致；协调器侧不需要强转。
- `maintainRetaliation(ServerLevel) -> void` 在 `customServerAiStep(ServerLevel level)` 里调用，`level` 类型匹配。
- `getTarget()` / `setTarget(LivingEntity)` 来自 `Mob`，`PathfinderMob` 继承；`retaliationTicks` 是 `int`，与 `RETALIATION_TICKS`（`int`）一致。
- `Attributes.ATTACK_DAMAGE` 取 `double`，`RETALIATION_ATTACK_DAMAGE` 声明为 `double`。
- `isPermittedAttacker` 搬迁后签名不变（`LivingEntity -> boolean`），傀儡两处调用只需改限定名。
- `MeleeAttackGoal(this, 1.0, true)` 的第二个参数是速度修正（傀儡用同样的 1.0），第三个 `true` 表示"目标可见时才追"（傀儡同样）。
