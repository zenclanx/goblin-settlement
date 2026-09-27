# 职业名额约束设计

更新日期：2026-09-27。依据：GAME_DESIGN.md 第 128 行「哨卫按约每 12 名成年人 1 名、最多 4 名，哨卫属于人口」；PROFESSION_DESIGN.md §12「职业名额约束（哨卫每 12 成人 1 名等）」；§7.1「只补 `UNASSIGNED`，不转岗」。

## 1. 目标与范围

**目标**：给职业分配加**名额上限**，并把文档里唯一写明的那个数（哨卫）落进代码。

**现状的偏差**：`ProfessionCoordinator` 把每个未定职成人派给 `ProfessionRules.scarcest`——当前人数最少的职业。7 个职业因此被**强制摊平**，第 7 个成人就会当上哨卫。而 GAME_DESIGN 要求哨卫约每 12 名成年人 1 名，**12 人以下不该有哨卫**。

**本轮做**：名额表机制 + 哨卫上限 + 分配接线 + 检查覆盖。

**本轮不做**：
- 给文档未写明的职业发明上限（见 §2 的理由）。
- **强制转岗**：名额只影响**新分配**，不会把已在岗的居民踢出。这是 PROFESSION_DESIGN §7.1 的既有决定，且「已定职居民的强制转岗」本就在 §12 的范围外。
- 哨卫的工作内容（那是 §12 的另一条）。

## 2. 名额表

单一落点，纯函数：

```java
/**
 * How many adults may hold this trade. Only the sentry has a documented ceiling: GAME_DESIGN asks
 * for roughly one per twelve adults, at most four. Every other trade keeps its old behaviour --
 * "fewest holders wins, with no ceiling" -- because no ceiling for them is written down anywhere,
 * and inventing one here would be a gameplay number with no basis.
 */
public static int ceiling(Profession profession, int adults) {
    return switch (profession) {
        case SENTRY -> Math.min(4, adults / 12);
        default -> Integer.MAX_VALUE;
    };
}
```

`UNASSIGNED` 也走 `default`（无上限）。它不在 `scarcest` 的候选里（既有逻辑就跳过它），所以这条分支不会影响分配。

## 3. `scarcest` 改签名

```java
/** The fewest-held trade still below its ceiling; empty when every trade is full. */
public static Optional<Profession> scarcest(List<Profession> assignedAdults, int adults)
```

实现：沿用既有的"人数最少、并列按枚举序"，但在候选里**跳过已达上限的职业**。

**为什么返回 `Optional` 而不是硬塞一个职业**：名额表的存在意味着"所有职业都满"是一个**制度上可能**的状态——只是今天表里只有哨卫有上限，所以实际不会发生。让签名如实表达这个状态、由调用方决定怎么办，比让函数在无路可走时悄悄越过上限要诚实。这是刻意的取舍，不是为不可能的情形加防御。

**为什么 `adults` 要传进来**：上限是人口的函数（`adults / 12`）。传总成年人数而不是"已分配人数"，因为后者在 `ProfessionCoordinator` 的循环里会逐次变化，而名额应当以聚落规模为准。

## 4. 分配接线

`ProfessionCoordinator` 只改一行：

```java
        int adults = data.adultCount();
        for (String residentId : data.unassignedAdultIds()) {
            ProfessionRules.scarcest(data.assignedProfessions(), adults)
                    .ifPresent(profession -> data.assignProfession(residentId, profession));
        }
```

`Optional` 为空时**什么都不做**：该成人保持未定职（通才，速度居中），下次 200 tick 再试。今天是不可达状态。

## 5. 可见的行为变化

**初始 8 人聚落不再出现哨卫。** 改前：7 个职业摊平，第 7 个成人当哨卫。改后：`ceiling(SENTRY, 8) = 0`，哨卫要等到 12 名成人才能出现 1 名。这**符合 GAME_DESIGN**，但确实是玩家看得见的变化，日志里要写明。

其余职业不受影响：它们无上限，仍是最缺优先。

另一个方向的后果：人口降到 12 以下时，**已有的哨卫不会转岗**（§1 的范围外决定）。所以哨卫数可能长期高于当前名额——这是"不强制转岗"的必然结果，不是缺陷，但要写进状态文档。

## 6. 验证

**本轮有新增覆盖**（与上一轮不同）。

`ProfessionRulesCheck` 增加：

- `ceiling(SENTRY, 0)`、`(SENTRY, 11)` == 0；`(SENTRY, 12)` == 1；`(SENTRY, 23)` == 1；`(SENTRY, 24)` == 2；`(SENTRY, 48)` == 4；`(SENTRY, 120)` == 4（封顶）；
- `ceiling(FARMER, 120)` == `Integer.MAX_VALUE`（未写明的职业无上限）；
- `scarcest` 在哨卫已达上限时**跳过**它：12 名成人、名单里已有 1 个哨卫且其余职业各 1 人时，结果不是哨卫；
- `scarcest` 在哨卫未达上限且人数最少时**选中**它：12 名成人、名单里只有哨卫以外的职业时，结果是哨卫；
- 既有的三条 `scarcest` 断言（空名单、最缺者胜、并列按枚举序）**保留**，只补上新的 `adults` 参数。

既有的 `professionRulesCheck` 任务已注册在 `build.gradle` 的 `check` 聚合里，本轮不改 `build.gradle`。

完整离线构建 + 12 项独立检查。**不做游戏内验证**（按用户约定）。

## 7. 风险

- **`scarcest` 是既有函数，签名变化会影响所有调用方与检查**：目前调用方只有 `ProfessionCoordinator` 与 `ProfessionRulesCheck`（已核对）。改动必须一次到位，否则编译不过——这也是本轮不把它拆成多个任务的原因。
- **名额只影响新分配**：见 §5 末。人口波动时哨卫数可能偏高，且不会有任何自动纠正；要纠正只能靠"强制转岗"，那是范围外。
- **`adults / 12` 是整数除法**：11 人 → 0，12 人 → 1，23 人 → 1，24 人 → 2。与 GAME_DESIGN 的"约每 12 名成年人 1 名"一致，但意味着**第 12 名成人出现的那一刻**才会放开第一个哨卫名额，而不是在 6 人时给半个。这是"约"的取整方式选择，已写进检查用例钉死。
- **哨卫目前没有专属工作**（PROFESSION_DESIGN §2.3：按通才对待）。所以本轮的效果是"少一个名义上的哨卫"，而不是"少一个干活的人"——不干活的人在任何职业下都干活。
