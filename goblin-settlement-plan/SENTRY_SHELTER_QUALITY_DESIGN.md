# 避难所质量（有墙与容量）设计

更新日期：2026-09-27。依据：GAME_DESIGN.md 第 132 行（「非战斗居民与儿童避难」）；SENTRY_SHELTER_DESIGN.md §3 与 §7（上一轮把"已登记房屋"当避难所，并自己标出两个缺口：可能躲进无墙的棚、没有容量判定）。

## 1. 目标与范围

**目标**：补掉上一轮自己标出的两个缺陷——避难所必须**真有墙**，且**有容量上限**。

**本轮做**：把避难所候选从"已登记房屋"收窄为"**有墙的房屋**"，并按**已建成的床位数**限制每栋房能容纳多少避难者；选择规则改成"最近且有**空位**者胜"。

**本轮不做**：
- 逐户护送（用户已否）。
- 占用**预留**：见 §6 的竞态。
- 儿童专属行为。

## 2. 候选收窄：什么叫"有墙"

房屋的容量轴 0 级是**只有四角柱与顶的棚**，1 级才是四壁 + 门洞。躲进棚等于没躲。

因此候选 = `capacityTarget >= 1` **且** 容量轴 1 级的几何**已建成**。

`HousingCoordinator` 上加一个**语义明确的公开方法**，把"有墙"与"容量"一次答完：

```java
/** Beds this home actually provides, or 0 when even its walls are not up yet. */
public static int shelterCapacity(ServerLevel level, HousingSavedData.Home home) {
    if (!stageFullyBuilt(level, home, 1)) {
        return 0;
    }
    return HousingRules.builtCapacity(home.capacityTarget(), true, stageFullyBuilt(level, home, 2));
}
```

**为什么用这一个方法而不是放开门内方法或加两个包装**：
- `stageFullyBuilt` 是包内可见的实现细节；直接放开会让 `defense` 依赖"第几级"这种几何概念。调用方想问的是**"这栋房能躲几个人"**，0 就是"不能当避难所"——一个问题、一个答案。
- 复用 `HousingRules.builtCapacity`，**不发明新数**。

## 3. 容量 = 已建成的床位数（1–3）

同一个数既决定"能住几人"、也决定"能躲几人"，不另造标准。

**必须先说清一个后果**：早期聚落房子少，床位数远低于需要避难的人数。要到 `HousingSavedData.MAX_HOMES`（48）× 3 = 144 才够 `PopulationRules.MAX_RESIDENTS`（64）的上限，所以**早期一定会有居民找不到位置**。

找不到的**照常干活**（沿用上一轮的兜底，只是从"一栋房都没有"扩展到"没有一栋还有空位"）。这是有意的：没有空屋就无处可躲，**乱挤在门口并不比干活安全**。

## 4. 选择规则（纯层，取代上一轮的 `nearest`）

```java
public record Shelter(int x, int y, int z, int used, int capacity) { }

/** The nearest shelter with a free slot, -1 when none has room. */
public static int choose(List<Shelter> shelters, int[] from)
```

最近且 `used < capacity` 者胜；并列按 x 再按 z；**全满或无候选返回 -1**。

上一轮的 `nearest` 由它取代——**不是并存**：容量判定属于"选哪个避难所"这条规则本身，拆成两层会让"最近"与"有没有空位"各自演化。它的四条用例改写成新签名后保留。

## 5. 占用数从哪来

`ShelterCoordinator` 在选之前数一遍：**当前已在避难的居民各自瞄向哪栋房**。

- 实体加一个只读访问器 `shelterTarget() -> Optional<BlockPos>`，**只在真的在避难时返回位置**（未避难时返回空，避免默认值 `BlockPos.ZERO` 被误算成"瞄向了原点那栋房"）。
- 需要一个"取全部已加载居民"的查询。`ResidentWorkLookup` 现有 `anyLoaded` 只回答布尔值，**本轮把它改为复用一个新的 `loaded(...)` 列表查询**，让两条路径共用同一段名册遍历——**同一份遍历不做两个副本**。
- **每次选择只算一次**（只发生在居民开始避难的那一刻），不是每 tick。

## 6. 验证

- `ShelterRulesCheck` 扩写：最近且有空位者胜；最近的满了就**让给次近的**；**全满返回 -1**；单候选；确定性（同输入同结果）；`capacity` 为 0 的候选永不入选。
- **纯层只覆盖选择规则**：候选如何筛出（有墙 + 容量）、占用如何数，都在 `defense`/`housing` 的 MC 侧，**不可纯测**。
- 完整离线构建 + 全部独立检查（仍 **14 项**，本次是改写而非新增）。

## 7. 风险

- **早期居民会找不到避难所**：见 §3。有意的取舍，但可见——统一测试时要观察"人多房少"时居民是否还照常干活而不是原地打转。
- **占用是扫描得到的、不是预留的**：两个居民在同一 tick 各自选房时，都会看到同一份占用快照，可能选到同一间房而**超员 1**。本轮不做预留（预留要落盘或做锁，代价远超收益）。这是**已知竞态**，不是疏漏。
- **每栋房要扫一遍 `stageFullyBuilt`**（约 20–70 格）：只在选择时发生，且候选数受 `MAX_HOMES` 限制；但聚落房子多时这里会变慢，届时需要缓存。
- **"能住几人 = 能躲几人"是借用而非定义**：床位是睡觉的容量，站人未必同一标准。本轮刻意借用（不发明新数），若试玩后觉得别扭，再单独设计避难容量。
- **`anyLoaded` 不再短路**：改为先取全部加载居民再判断。名册上限 64 且调用方都在审查节拍上，代价可接受；换来的是"名册遍历只有一份"。
