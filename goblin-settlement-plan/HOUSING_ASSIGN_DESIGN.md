# 住户分配 设计

更新日期：2026-09-28。依据：GAME_DESIGN.md 第 4 节「出生需要合适的成年伴侣、**床位**、食物储备和健康状态」与第 11 节公告牌要显示「住房空位」；CURRENT_STATUS「接续须知」里用户已批准的四点设计。

范围已由用户定下，本轮**不重做 brainstorming**，只把四点展开成可实现的规则。用户在实现前另行定死了两处本设计覆盖不到的取舍，见 §4.2。

## 0. 范围

| | 内容 |
| --- | --- |
| **本轮做** | ①**归属**：`ResidentRecord` 新增 `home`，指向那栋房的床头坐标；②**分配规则**（纯函数，可独立检查）：把无归属居民放进还有空位的房子、把归属指向已消失或已超容房子的居民解除；③**生育门落到户**：从"全聚落有床位"改为"母亲所在房子确实还有空位"；④**显示**：`status` 逐栋给出住几人/容量，并给出无房人数 |
| **明确不做** | 伴侣同住绑定；孩子随父母的房；睡觉/寻床行为；房子本身的拆除与改建；住户数预留；按距离就近入房 |
| 不新增 | 不新增需求档、不新增方块、不新增存档 schema 版本、不在 `Home` 里存住户名单 |

## 1. 现状（已核对）

- **居民身上没有任何住处字段**：`ResidentRecord`（`colony/ResidentRecord.java:9-11`）只有 `id / stage / motherId / fatherId / reproductiveRole / profession / childActiveTicks / restTicks`。
- **房子身上也没有住户名单**：`HousingSavedData.Home`（`housing/HousingSavedData.java:19-20`）只有 `bed / variant / style / capacityTarget / qualityTarget / workerId`。**`workerId` 是施工工人，不是住户。**
- **今天的"床位归属"纯粹靠距离**：`HousingCoordinator.usedBedsNear`（`:322-325`）以该房锚点床为中心、`HousingRules.BIND_RADIUS = 8` 半径（Chebyshev x/z ≤ 8、`|dy| ≤ 4`，`housing/HousingRules.java:12`）数床。**没有"谁住哪栋"，只有"哪张床离哪栋近"。**
- **生育门是聚落级的**：`FamilyCoordinator.tick`（`colony/family/FamilyCoordinator.java:58-69`）对每个待出生项要求 `housing.count() >= occupied` **且** `!housing.beds().isEmpty()`。`housing(...)`（`:175-190`）扫地块收集床、逐张过 `BedCensus.usableBedHead`，**只数到 `enough = occupied + 1` 张就停**。所以这个判据的实际含义是"至少还有一张空床"，与"哪一户"无关。
- **新生儿出生在任意一张可用床的上方**：`placePendingNewborns`（`:192-221`）遍历 `housing.beds()`、复检 `usableBedHead`、取 `bed.above()` 居中生成，要求无碰撞、包围盒内无存活实体。**出生点与"母亲住在哪"无关。**
- **房子能住几人的唯一算式已存在**：`HousingRules.builtCapacity(capacityTarget, stage1Built, stage2Built) = 1 + (容量轴 1 级已建成) + (容量轴 2 级已建成)`（`housing/HousingRules.java:89-94`），取值 1..3。它与 HOUSING_DESIGN §6.2「容量按**已建成**的几何算，不按目标算」是同一个定义。
- **已有"按名册扫出占用"的先例**：`ShelterCoordinator.nearestShelter`（`defense/ShelterCoordinator.java:24-59`）用 `ResidentWorkLookup.loaded(...)` 扫一遍名册、数每栋房已有多少人瞄着它。本轮的住户数与它同源，**但等级更高**——它数的是实体瞬态字段，住在名册里，所以不必要求实体已加载。
- **名册本身可枚举且保序**：`SettlementSavedData.residents()` 返回 `List.copyOf`，保插入序；`resident(String id)` 单查；`ResidentWorkLookup.loaded` 已确立"跳过 `DECEASED`"的口径。
- **可选的、会改值的居民字段有先例**：`profession` 就是以 `optionalFieldOf` 加进去的、**不升 schema**（`SettlementSavedData.SCHEMA_VERSION` 仍是 1）；写入走 `ResidentRecord.withProfession` + `SettlementSavedData.assignProfession`（`colony/SettlementSavedData.java:307-324`）。
- **初始营地一栋 home 都没有**（HOUSING_DESIGN §6.3 已核对）：营地 8 张床由 `CampGenerationCoordinator` 直接放置、彼此只隔 2 格，注册检查（同层 7×7 窗口）因此**一栋都不通过**。开局所有人**无房**，这不是异常而是常规起点。

## 2. 数据模型：归属只存在居民侧

`ResidentRecord` 新增一个可选的 `home`：

```java
public record ResidentRecord(String id, LifeStage stage, Optional<String> motherId, Optional<String> fatherId,
                             ReproductiveRole reproductiveRole, Profession profession,
                             long childActiveTicks, long restTicks, Optional<BlockPos> home) { }
```

- codec 加 `BlockPos.CODEC.optionalFieldOf("home")`，**无默认值 → 旧档为空 = 无房**，与 `mother_id` / `father_id` 的写法同构。**不升 schema 版本。**
- 语义：**指向那栋房的床头坐标**（即 `Home.bed()`），它是**房子的标识**而不是某一张具体的床——同一栋房的所有住户指向同一个坐标。

### 2.1 为什么住户数不进 `Home`

**一处真相**：住户数 = 名册里 `home` 等于该床头坐标的居民个数，**扫名册推导**，不在 `Home` 里另存一份名单。两份数据放在一次写操作里尚且会漂（读档、死亡、超容挤出都要同时改两处），而推导出来的数**永远与名册一致**。这与第五十三轮避难占用数的做法同源。

代价是每次要看住户数都得扫一遍名册。名册上限 `PopulationRules.MAX_RESIDENTS = 64`，扫描是纯内存遍历，可接受。

### 2.2 `home` 不是一次性赋值（与 `profession` 的关键差别）

`withReproductiveRole` / `withProfession` 都是**一次性**的：只在当前为空时成立，之后抛 `IllegalArgumentException`（`ResidentRecord.java:96-111`）。`home` **不能照抄这个形状**——归属会反复变化：无房 → 入房 → 被超容挤出 → 再入房。

所以 `withHome(Optional<BlockPos>)` 是**无条件**的取值变换，`SettlementSavedData.assignHome(String residentId, Optional<BlockPos> home)` 也只做"找到这个 id、替换、标脏"，不带"必须为空"的前置条件。

### 2.3 实施陷阱：所有构造点都要串上 `home`

`ResidentRecord` 是 record，字段加一个就**多一个构造参数**。`ResidentRecord.java` 内部有 **8 处 `new ResidentRecord(...)`**（`:78` `adult`、`:83` `child` 两个工厂，以及 `withReproductiveRole :101` / `withProfession :109` / `advanceFamilyTime :119` 与 `:123` 两个分支 / `withPostBirthRest :130` / `deceased :135`）。

文件**之外**没有裸构造点——`SettlementSavedData.java:276`（登记成年）与 `:490`（造新生儿）都走 `adult` / `child` 工厂，所以只要两个工厂给出 `home = Optional.empty()`，它们不必改。别处的居民构造一律经这些方法。

其中 `advanceFamilyTime` **每 tick 都被调用**（`childActiveTicks` 累加）。**任何一处漏串 `home`，那个居民的归属就会在下一次调用时被静默清空**，而且只在运行时、只在特定分支上暴露。这不是风格问题，是本轮最容易漏的真缺陷，实现与审查时逐处核对。

## 3. 分配规则（纯函数）

新增纯层 `housing/HousingAssignment`，**不吃 Minecraft 类型**（照 `HousingRules` / `ShelterRules` 的写法只吃 int 与 record），可独立检查。

### 3.1 输入与输出

```java
public record BedKey(int x, int y, int z) { }
public record HomeSlot(BedKey bed, int capacity) { }                 // 已判定（其区块可 tick）的房子
public record ResidentSlot(String id, Optional<BedKey> home) { }     // 非 DECEASED 的居民
public record Change(String residentId, Optional<BedKey> home) { }   // 空 = 解除归属
```

`plan(List<HomeSlot> homes, Set<BedKey> unjudged, List<ResidentSlot> residents) -> List<Change>`

`unjudged` 是"登记在册、但其床头坐标所在区块不可 tick、因此算不出容量"的那批房子（见 §3.4）。**它不参与分配，但指向它的居民也不算无房。**

### 3.2 规则

按下面的顺序跑完，一次算出**完整的目标状态**，再与现状求差、只输出变化项：

1. **超容解除**：对每栋已判定的房子（按床位坐标序），取归属指向它的居民（按 id 序），**保留前 `capacity` 个，其余解除**。
2. **消失解除**：归属既不指向某栋已判定房子、也不在 `unjudged` 里的居民，解除归属。（房子的登记被移除，或床位坐标已不在册。）
3. **分配**：剩下的无房居民（按 id 序），逐个进入**第一栋还有空位的房子**（按床位坐标序）。

三条规定合起来就是用户批准的"把无归属的居民分配进还有空位的房子、把归属指向已消失或已超容房子的居民解除绑定"。

**但输出的是一条"净变化"，不是三条规则的动作序列**：一个归属已消失的居民，若聚落里还有空位，输出就是**一条"改派"**（新归属），而不是先解除再分配两条。规则的三步是推导过程，`Change` 列表表达的是**最终状态与现状之差**——这既是幂等（§3.5）的前提，也让"哪几条先落地"在预算下有意义。

### 3.3 确定性与顺序

- **房子按床位坐标 x → z → y 排序**，**居民按 `id` 字符串序排序**，两者都与输入列表的原始顺序无关（同一组输入无论以什么顺序传入，结果逐字相同）。
- **输出按（相关床位 x → z → y，再居民 id）排序**：赋值项取**目标**床位，解除项取**原**床位。
- **为什么"保留前 `capacity` 个（按 id）"而不是"保留先住进来的"**：先住进来的顺序没有被持久化，若为此另存一个序号就是`Home` 里那份名单的翻版（§2.1 拒绝的东西）。按 id 保留的规则**完全由当前状态决定**，与分配历史无关，因此可重复、可断言。
- **为什么分配按坐标序而不按距离**：纯层不许碰世界，拿不到居民与房子的距离。坐标序是确定且可检查的。代价见 §9。

### 3.4 区块未加载的房子（不可判定的容量）

容量要读方块几何（§1 的 `builtCapacity` 需要一个"某一级建完了没有"的布尔量），而**不可 tick 的区块读出来是空气**——照算会把一栋三人的房子读成容量 1，把两个好好的住户当成超容挤出去。

所以 MC 层在装配输入时先过 `level.shouldTickBlocksAt(bed)`（与第五十六轮 `Links:` 行、第五十九轮 `status` 房行同一个闸）：

- 该坐标可 tick → 进 `homes`（带真实容量）。
- 该坐标不可 tick → 进 `unjudged`：**该房不作为分配目标，指向它的居民原样不动**（既不解除也不改派）。
- 两者都不在 → 归入第 2 条的"消失"，解除。

口径与既有约定一致：**绝不把未判定的东西当成已判定**。

### 3.5 幂等

把 `plan` 的输出应用回去，再跑一次 `plan`，**必然返回空列表**。这条会被写成断言（§8），它同时钉住"分配不会来回搬家"。

## 4. 硬约束其一：生育门落到户

`FamilyCoordinator.tick` 里 `conditions(...)` 那处（`colony/family/FamilyCoordinator.java:64-65`）由

```java
conditions(level, motherId, fatherId, housing.count() >= occupied, foodForBirth)
```

改为"先问母亲所在的那栋房"。**判据本身仍走 `conditions(...)` 的入参**（它已经是"传进来的布尔量"，与 `SettlementDemand` 的 `trafficPending` 同构），只是这个布尔量的算法换掉。

### 4.1 新判据

```
母亲有归属，且那栋房子已判定，且 住户数 < 容量            → 可以生
以上任何一条不成立（无归属 / 房子已消失 / 房子不可 tick） → 回落旧判据 housing.count() >= occupied
```

**回落是为了不死锁**：初始营地一栋 home 都没有（§1），若"无房就不能生"严格执行，开局 8 人将**完全不繁衍**，而 GAME_DESIGN §11 的营地阶段本就写着 8～12 人。回落之后，无房家庭的行为与今天逐字一致，**一旦居民有了归属，户级硬约束立刻生效**——这正是本项要交付的东西。

`!housing.beds().isEmpty()` 这条**保留不动**：新生儿是在某张真实可用床的上方生成的（`placePendingNewborns`），没有可用床就没有生成点。

### 4.2 用户已定死的两处取舍（本设计覆盖不到，实现前另行确认）

1. **无房者回落旧判据**（而非"无房就不生"）。理由见 §4.1。
2. **看母亲所在的那栋房**（而非父母双方、也非任一方）。母亲是生育方，且新生儿是在床头生成的；伴侣同住既已不做，双方各自有房，只取一栋最确定。

### 4.3 新生儿不继承母亲的房子

`commitBirth` 造出的记录 `home` 为**空**，随后由 §3 的分配规则放进"第一栋还有空位的房子"。因此 **新生儿不保证与母亲同房**——这正是"孩子随父母的房"被明确排除的直接后果，不是缺陷。出生门保证了"母亲的房子当时有空位"，但那个空位可能在同一拍被另一个无房居民先占（§9 的未预留竞态），此时新生儿会住进别的房子或暂时无房。

## 5. 硬约束其二：新居民只进有空位的房子

**不新增机制**：新生儿与任何其他无房居民走的是同一条 §3 规则——有房子有空位就进，没有就**留作无房**。住不下不会被拒绝登记、不会阻塞出生流程，只会体现在"无房人数"上；而"缺房 → `HOUSING` 需求档 → 扩建"这条链路（HOUSING_DESIGN §5、§6a）**已经在追这个数**，本轮一行不改。

既有的放床逃生口（HOUSING_DESIGN §6.3：没有任何房子有容量空位时恢复自由放床）**原样保留**。

## 6. 协调器与节拍

新增 `housing/HousingAssignmentCoordinator`，接入 `GoblinSettlement.tickSettlement`，**排在 `BedProvisioningCoordinator.tick` 之后、`FamilyCoordinator.tick` 之前**——床先落地，归属才有的可指；归属先算好，生育门才读到新鲜的。

- `INTERVAL_TICKS = 40`、`BUDGET = 8`（每拍最多应用 8 条变化）。**两个都是发明值**，源码注释里标明没有依据、需按实测重定（与 `TRAFFIC_PER_LANE` / `SAMPLE_INTERVAL_TICKS` 同样的处理）。
- 每拍：装配输入（扫名册 + 逐房过 `shouldTickBlocksAt` + 读容量）→ `HousingAssignment.plan(...)` → 按 `BUDGET` 截断 → 逐条 `assignHome`。
- **预算的意义**：把一次可能的几十条改写的写入摊到几拍上，且让"哪几条先落地"有确定答案（输出已排好序）。名册上限 64、房子上限 48，整批一次写完也付得起，但边界清楚比省事重要。

## 7. 显示

`/goblinsettlement status`：

1. **`Housing:` 汇总行**末尾追加 `, homeless=K`（无房人数），前面保持现状：
   `Housing: beds=8, occupied slots=8, spare=0, homeless=2`
2. **把既有的 `Housing work:` 行改成逐栋的 `Homes:` 行**，每栋给出**住几人/容量**，卡住的栋在原位带上原因：

   `Homes: (12,64,3) 1/2, (20,64,7) 3/3 [no way to place next step: cell occupied], +2 more, 1 not loaded`

   排序（床位 x → z → y）、上限 `HOUSING_REPORT_LIMIT = 8`、`+N more`、`n not loaded`、以及"原因只报第一个"全部**沿用现成的 `housingReportLine`**（`GoblinSettlement.java:303-349`），不重写、不新抄一遍门禁——它本来就是问 `HousingCoordinator.blockedReason`。一栋 home 都没有时该行输出 `Homes: none`。

**"容量"用的是容量，不是实际床位数**：`HousingRules.builtCapacity`（与 §4 的约束是同一个数）。按 HOUSING_DESIGN §6.2，容量由**已建成的几何**决定，实际床位数由 `BedProvisioningCoordinator` 事后补上、可能因放床受阻而滞后。**若希望这一列跟实际床位数走，那是另一个口径**（要逐栋数 BIND_RADIUS 内的床），需明确后另行处理。

**实体公告牌方块仍不在本轮**（GAME_DESIGN §11 要求的那个方块），同 HOUSING_DESIGN §7 的既有结论。

## 8. 验证

- **新增第 20 项独立检查 `housingAssignmentCheck`**（`src/test/java/.../housing/HousingAssignmentCheck.java`，注册进 `build.gradle` 的 `check` 聚合，无 JUnit、`main` + `require`、成功打印 `HousingAssignmentCheck passed`）。先写失败断言再实现。覆盖：
  1. 无房者只进有空位的房子；房子按床位坐标序被填。
  2. 所有房子都满 → 无房者**原样留作无房**（不产出任何变化）。
  3. 归属指向已消失房子的居民 → 解除。
  4. 超容房子 → 按 id 序保留前 `capacity` 个、其余解除。
  5. **确定性**：打乱 `homes` / `residents` 的传入顺序，输出逐字相同。
  6. **输出顺序**：按（床位 x → z → y，再 id）断言逐项。
  7. **`unjudged`**：指向它的居民不动、且它不被选作分配目标。
  8. **输出只含真正的变化**：现状已经正确的居民不出现在输出里。
  9. **幂等**：把输出应用回去再跑一次 `plan` → 空列表。
- **`housingRulesCheck` 不新增断言**：`shelterCapacity` 将改为 `stage1Built ? homeCapacity : 0`。这是**逐字等价的委派**（原式在 `stage1Built` 为真时才计算 `builtCapacity(target, true, stage2)`，而那一支里 `true` 与 `stage1Built` 同值），不是行为变更，所以不写一条断言自己证明自己。该检查里既有的 `builtCapacity` 两条腿断言继续覆盖它。
- **`settlementSavedDataCheck` 补断言**：`ResidentRecord` codec 往返带 `home`；**旧档（无 `home` 键）读入后 `home` 为空**；`assignHome` 能改、能清、能再改（钉住 §2.2 的"非一次性"语义）；**`advanceFamilyTime` / `withProfession` / `withPostBirthRest` / `deceased` 之后 `home` 仍在**（钉住 §2.3 的陷阱）。
- 完整离线构建 `./gradlew build --offline --no-daemon` + **全部 20 项**独立检查。
- **不可纯测**（写进日志与状态文件）：分配协调器读世界的整条路径（`shouldTickBlocksAt` 闸、逐房读容量）、生育门在真实家庭上的效果、`status` 那两行、以及"无房人数"在真实聚落里的走势。**另有一处刻意不放进纯层**：`DECEASED` 的过滤落在 MC 层的名册映射里（纯规则按契约只收存活居民，它拿不到 `LifeStage`），因此由代码审查与 `ResidentWorkLookup` 的既有口径保证，不由独立检查覆盖。与本分支既有全部工作一样，**不做游戏内验证**。

## 9. 风险与已知边界

- **未预留**：住户数由名册**数出来**，不预留。同一拍里两个无房居民可能都看到同一个空位而超员 1，下一拍由 §3.2 第 1 条挤出。与第五十三轮避难占用的取舍同源，已记录在案。
- **不按距离就近**：分配按床位坐标序填房，可能把一个居民放进离它很远的房子。这是拿"确定性 + 可独立检查"换来的（纯层不许碰世界，拿不到距离）。若将来要就近，需要把居民位置作为输入加进纯规则，另做一轮。
- **超容挤出会"搬家"**：容量由**已建成几何**决定，玩家拆掉一面墙就会让一栋房的容量下降、住户被挤出并可能被分到别处。这不会造成来回抖动（容量只由几何决定，不随分配变化），但玩家看得见"有人在换房子"。**不为此加锁、不为此保留旧住户**。
- **`unjudged` 的房子会让分配停摆**：若某栋房的床位长期落在不可 tick 的区块，它既收不到新住户、也不会被解除；指向它的居民**保持现状**。这是有意的保守，不是遗漏。
- **`DECEASED` 仍在名册里**：本轮只让住户数与分配**跳过**它，**不清它的 `home` 字段**（清它需要在死亡路径上多改一处、且没有实际收益）。
- **`home` 指向的床本身被破坏**：本轮只处理"房子从登记里消失"，**不检查床头方块还在不在**。若玩家挖掉那栋房的锚点床，房子仍在册、容量仍按几何算，归属不变。这是既有 `HousingSavedData` 的口径，本轮不动。
- **`home` 会进存档**：新字段让每个居民的存档多一个可选坐标。仍然不升 schema 版本（`optionalFieldOf`），但与 `profession` 一样，**旧档能读、新档不能被旧版本读**（既有性质，非本轮引入）。
- **仍未做游戏内验证**：与本分支既有全部工作一样，只有编译与独立检查的证据。

## 10. 落地结果（实现后补记）

（待本轮实现后补写。）
