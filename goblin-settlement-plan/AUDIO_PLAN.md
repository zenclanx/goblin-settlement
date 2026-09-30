# 音效接入 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让美术 2026-09-29 交付的 40 段音效在游戏里真的响起来：20 个事件全部注册并各有落点，傀儡核心无缝循环，居民相遇时互相打招呼。

**Architecture:** 先铺地基（资源、`ModSounds`、语言条目，以及**一条把"字幕键在语言里是否齐全"变成构建期错误的检查**），再挂点。一次性音效（受击/死亡/工作号子/傀儡移动）走**服务端** `level.playSound`，让范围内所有玩家同时听到同一件事；只有傀儡核心走**客户端** `SoundInstance` 循环，因为那要的是无缝、零网络、随实体消失而停。"招呼/应答"需要两方，所以补一条最小的"碰面"行为 —— GAME_DESIGN §11 本就把它列为日常活动之一。

**Tech Stack:** Minecraft 1.21.11 / Fabric Loader 0.19.2 / Fabric API 0.141.4+1.21.11 / Java 21 / Gradle 9.2.1（离线）。

**设计依据：** [AUDIO_DESIGN.md](AUDIO_DESIGN.md)（文中 §N 均指该文档）。

## Global Constraints

- 目标环境固定：Minecraft 1.21.11、Fabric Loader 0.19.2、Java 21 字节码。构建命令一律 `./gradlew <task> --offline --no-daemon`，在 `goblin-settlement-mod/` 下执行（25–70 秒，Bash 超时给 **300000 ms**）。
- **本轮只做音效。** 九张物品图标与七类设施标识是另外两轮（设计 §8）；**不要顺手注册物品或方块**。
- **一次性音效一律服务端播**，只有傀儡核心走客户端（设计 §3、§4）。
- **不新增方块、不新增物品、不升 schema 版本。**
- **原版铁傀儡一行不动**（美术明确"沿用原版"）；**儿童用成年声音按性别**（美术留待后续）；不做表情动作；不做台词。
- **复用一个既有判据**：傀儡"是不是五个自定义阶之一"用 `GolemTier.hasCustomArt()`（美术那轮已有），**不要另写一份 tier 清单** —— 两张清单会各自漂移。
- **发明值都要标注**：`GreetingCoordinator.RADIUS = 3.0`、`GreetingCoordinator.INTERVAL_TICKS = 100`、`GreetingRules.GREETING_COOLDOWN_TICKS = 600`、`GreetingCoordinator.RESPONSE_DELAY_TICKS = 12`、`GoblinCitizenEntity.WORK_CHANT_COOLDOWN_TICKS = 1200` 全是**没有依据的发明值**，源码注释照项目惯例写明"待实测重定"。（**名字以源码为准**：前两个在 `GreetingCoordinator` 上是 `private`，没有 `GREETING_` 前缀——本计划初稿曾写成 `GREETING_RADIUS`/`GREETING_INTERVAL_TICKS`，源码里不存在，已更正。）
- 检查项数由 **23 增至 24**（新增 `soundsCheck`）。构建结束时 24 项必须全部 `*Check passed`。
- 交付包 `Models/handoff/art_handoff_20260929.zip` **只读**，不要修改 `Models/` 下任何东西。
- `goblin-settlement-plan/` 下可能有并行 agent 的未提交改动：文档任务先跑 `git status`。`UpdateLog.md` **只许在末尾追加**。
- 提交到 `main`（本项目不开特性分支），每轮结束推送。
- **已对 1.21.11 逐个核实的 API**（照这个写，不要按旧版本记忆）：`SoundEvent.createVariableRangeEvent(Identifier)`；`Registries.SOUND_EVENT`；`LivingEntity.getHurtSound(DamageSource)` 与 `getDeathSound()` 均为 `protected`；`Entity.playStepSound(BlockPos, BlockState)` 为 `protected`；`AbstractTickableSoundInstance(SoundEvent, SoundSource, RandomSource)` 带 `isStopped()`/`stop()`，父类 `AbstractSoundInstance` 的 `protected` 字段为 `sound`/`source`/`identifier`/`volume`/`pitch`/`x`/`y`/`z`/`looping`/`delay`/`attenuation`/`relative`/`random`；`SoundInstance.Attenuation.LINEAR`；`SoundManager.play(SoundInstance)`/`stop(SoundInstance)`；`ClientTickEvents.END_CLIENT_TICK`；`ClientLevel.entitiesForRendering()`。

---

### Task 1: 资源、`ModSounds`、字幕，与字幕覆盖检查

**Files:**
- Create: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/sounds.json`（来自交付包，原样）
- Create: 40 个 ogg，落在 `.../assets/goblin_settlement/sounds/goblin/{male,female}/` 与 `.../sounds/golem/{wood,stone,gold,diamond,obsidian}/`
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/sound/ModSounds.java`
- Create: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/sound/SoundsCheck.java`
- Modify: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/lang/en_us.json`（追加 20 条字幕）
- Modify: `goblin-settlement-mod/src/main/resources/assets/goblin_settlement/lang/zh_cn.json`（同上）
- Modify: `goblin-settlement-mod/build.gradle`（注册 `soundsCheck` 并加入 `check`）
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`（调用 `ModSounds.initialize()`）

**Interfaces:**
- Consumes: `GolemTier.hasCustomArt()`（`defense/GolemTier.java`，美术那轮已有）
- Produces: `ModSounds.Voice`（枚举 `GREETING, RESPONSE, WORK, HURT, DEATH`）；`ModSounds.goblinVoice(boolean female, Voice voice) -> SoundEvent`；`ModSounds.golemMove(GolemTier tier) -> Optional<SoundEvent>`；`ModSounds.golemCore(GolemTier tier) -> Optional<SoundEvent>`；Gradle 任务 `soundsCheck`；`SoundsCheck`（Task 4 会往同一个 `main` 追加断言）

- [ ] **Step 1: 先写检查（RED）**

新建 `src/test/java/dev/local/goblinsettlement/sound/SoundsCheck.java`：

```java
package dev.local.goblinsettlement.sound;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.local.goblinsettlement.GoblinSettlement;
import dev.local.goblinsettlement.defense.GolemTier;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvent;

/**
 * Standalone checks for the delivered sound set: the manifest's ids, the audio files behind them, the
 * lookup helpers, and the subtitles.
 *
 * <p>Never registers anything. An earlier draft bootstrapped and asserted the events were in
 * BuiltInRegistries; that cannot work, because Bootstrap.bootStrap() freezes the registries and
 * loading a class that registers afterwards throws. Reading registries after bootstrap is fine
 * (ConstructionMaterialCheck does it) but registering is not, and this check does not need to:
 * the lookup helpers can only return objects the registrations produced, so assertion 3 already
 * fails if an id drifts away from the manifest. ModSounds is shaped to make that possible -- see
 * the note on its factory.
 */
public final class SoundsCheck {
    private static final String SOUNDS_JSON = "/assets/goblin_settlement/sounds.json";
    private static final String LANG = "/assets/goblin_settlement/lang/";
    private static final String SUBTITLE_PREFIX = "subtitles." + GoblinSettlement.MOD_ID + ".";

    public static void main(String[] args) {
        // Read-only bootstrap, and it is required: assertion 3 walks GolemTier, whose constants name
        // vanilla Items, which forces Blocks and then SoundEvents. This check never *registers*
        // anything -- see ModSounds, where creating an event and registering it are two separate steps,
        // so loading that class here touches no registry. ConstructionMaterialCheck bootstraps the same
        // way for the same reason.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        JsonObject manifest = readJson(SOUNDS_JSON);

        // 1. The manifest declares the twenty events the design says it does.
        Set<String> declared = new LinkedHashSet<>(manifest.keySet());
        check(declared.size() == 20, "the manifest declares twenty events, got " + declared.size());

        // 2. Every audio file the manifest references exists in the mod's own resources.
        List<String> missing = new ArrayList<>();
        for (String key : declared) {
            JsonObject entry = manifest.getAsJsonObject(key);
            for (var element : entry.getAsJsonArray("sounds")) {
                String name = element.getAsJsonObject().get("name").getAsString();
                String namespace = name.substring(0, name.indexOf(':'));
                String path = name.substring(name.indexOf(':') + 1);
                String resource = "/assets/" + namespace + "/sounds/" + path + ".ogg";
                if (SoundsCheck.class.getResource(resource) == null) {
                    missing.add(resource);
                }
            }
        }
        check(missing.isEmpty(), "every referenced sound file is in the jar, missing: " + missing);

        // 3. The lookup helpers reach exactly the declared events -- no more, no less.
        Set<SoundEvent> reachable = new LinkedHashSet<>();
        for (ModSounds.Voice voice : ModSounds.Voice.values()) {
            reachable.add(ModSounds.goblinVoice(false, voice));
            reachable.add(ModSounds.goblinVoice(true, voice));
        }
        for (GolemTier tier : GolemTier.values()) {
            ModSounds.golemMove(tier).ifPresent(reachable::add);
            ModSounds.golemCore(tier).ifPresent(reachable::add);
        }
        Set<String> reachableIds = new LinkedHashSet<>();
        reachable.forEach(event -> reachableIds.add(event.location().getPath()));
        check(reachableIds.equals(new LinkedHashSet<>(declared)),
                "the lookups reach exactly the declared events, got " + reachableIds);

        // 4. Iron is vanilla in both art and sound, so it must resolve to nothing rather than to
        //    whichever event happened to be nearest.
        check(ModSounds.golemMove(GolemTier.IRON).isEmpty() && ModSounds.golemCore(GolemTier.IRON).isEmpty(),
                "iron resolves to no custom sound");

        // 5. Subtitle coverage, both ways. This is the check the FOOD bug would have failed.
        Set<String> english = subtitleKeys("en_us.json");
        Set<String> chinese = subtitleKeys("zh_cn.json");
        check(english.equals(chinese), "both languages carry the same subtitle keys, "
                + "english only: " + difference(english, chinese) + ", chinese only: " + difference(chinese, english));
        Set<String> wanted = new LinkedHashSet<>();
        for (String key : declared) {
            wanted.add(manifest.getAsJsonObject(key).get("subtitle").getAsString());
        }
        check(english.equals(wanted), "the language files carry a subtitle for every declared event and no "
                + "orphans, missing: " + difference(wanted, english) + ", orphaned: " + difference(english, wanted));

        System.out.println("SoundsCheck passed");
    }

    private static Set<String> subtitleKeys(String languageFile) {
        Set<String> keys = new LinkedHashSet<>();
        readJson(LANG + languageFile).keySet().stream()
                .filter(key -> key.startsWith(SUBTITLE_PREFIX))
                .forEach(keys::add);
        return keys;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> only = new LinkedHashSet<>(left);
        only.removeAll(right);
        return only;
    }

    private static JsonObject readJson(String resource) {
        try (InputStream stream = SoundsCheck.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new AssertionError("missing resource: " + resource);
            }
            return JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException error) {
            throw new AssertionError("cannot read " + resource, error);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

**已核实**：`SoundEvent` 在本版本是一个 record，取 Identifier 的访问器就是 `location()`（`javap` 确认存在）。照抄即可。

- [ ] **Step 2: 注册 Gradle 任务**

在 `build.gradle` 里、`tasks.register('noticeboardCheck', JavaExec) { ... }` 那块**之后**加：

```groovy
tasks.register('soundsCheck', JavaExec) {
    group = 'verification'
    description = 'Checks the delivered sound set: registration, resources, subtitles and the meet rule.'
    dependsOn tasks.named('testClasses')
    classpath = sourceSets.test.runtimeClasspath
    mainClass = 'dev.local.goblinsettlement.sound.SoundsCheck'
}
```

并在 `tasks.named('check') { ... }` 块里、`dependsOn tasks.named('noticeboardCheck')` 的**下一行**加 `dependsOn tasks.named('soundsCheck')`。

- [ ] **Step 3: 跑一次，确认它失败**

Run: `./gradlew soundsCheck --offline --no-daemon`
Expected: **BUILD FAILED** —— 编译错"找不到符号 `ModSounds`"。这就是 RED。

- [ ] **Step 4: 复制资源**

在仓库根目录执行（**从交付包读，不写回交付包**）：

```bash
python - <<'PYEOF'
import zipfile, os
z = zipfile.ZipFile('Models/handoff/art_handoff_20260929.zip')
dest = 'goblin-settlement-mod/src/main/resources/assets/goblin_settlement'
copied = 0
for name in z.namelist():
    if not name.startswith('assets/goblin_settlement/sounds/'):
        continue
    if name.endswith('/'):
        continue
    target = os.path.join(dest, name[len('assets/goblin_settlement/'):])
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, 'wb') as handle:
        handle.write(z.read(name))
    copied += 1
# the manifest itself
with open(os.path.join(dest, 'sounds.json'), 'wb') as handle:
    handle.write(z.read('assets/goblin_settlement/sounds.json'))
print('copied', copied, 'sound files plus sounds.json')
PYEOF
```

Expected: `copied 40 sound files plus sounds.json`。

- [ ] **Step 5: 写 `ModSounds`**

新建 `src/main/java/dev/local/goblinsettlement/sound/ModSounds.java`：

```java
package dev.local.goblinsettlement.sound;

import dev.local.goblinsettlement.GoblinSettlement;
import dev.local.goblinsettlement.defense.GolemTier;
import java.util.Optional;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * The twenty sound events the art team delivered. This class is the one place that says which sounds
 * exist and which one a given situation wants; SoundsCheck proves it reaches exactly the events
 * assets/goblin_settlement/sounds.json declares, and no others.
 */
public final class ModSounds {
    /** The five things an adult goblin says. Children use the same set; the art team kept theirs back. */
    public enum Voice { GREETING, RESPONSE, WORK, HURT, DEATH }

    private static final SoundEvent GOBLIN_MALE_GREETING = delivered("entity.goblin.male.greeting");
    private static final SoundEvent GOBLIN_MALE_RESPONSE = delivered("entity.goblin.male.response");
    private static final SoundEvent GOBLIN_MALE_WORK = delivered("entity.goblin.male.work");
    private static final SoundEvent GOBLIN_MALE_HURT = delivered("entity.goblin.male.hurt");
    private static final SoundEvent GOBLIN_MALE_DEATH = delivered("entity.goblin.male.death");
    private static final SoundEvent GOBLIN_FEMALE_GREETING = delivered("entity.goblin.female.greeting");
    private static final SoundEvent GOBLIN_FEMALE_RESPONSE = delivered("entity.goblin.female.response");
    private static final SoundEvent GOBLIN_FEMALE_WORK = delivered("entity.goblin.female.work");
    private static final SoundEvent GOBLIN_FEMALE_HURT = delivered("entity.goblin.female.hurt");
    private static final SoundEvent GOBLIN_FEMALE_DEATH = delivered("entity.goblin.female.death");
    private static final SoundEvent GOLEM_WOOD_MOVE = delivered("entity.golem.wood.move");
    private static final SoundEvent GOLEM_WOOD_CORE = delivered("entity.golem.wood.core");
    private static final SoundEvent GOLEM_STONE_MOVE = delivered("entity.golem.stone.move");
    private static final SoundEvent GOLEM_STONE_CORE = delivered("entity.golem.stone.core");
    private static final SoundEvent GOLEM_GOLD_MOVE = delivered("entity.golem.gold.move");
    private static final SoundEvent GOLEM_GOLD_CORE = delivered("entity.golem.gold.core");
    private static final SoundEvent GOLEM_DIAMOND_MOVE = delivered("entity.golem.diamond.move");
    private static final SoundEvent GOLEM_DIAMOND_CORE = delivered("entity.golem.diamond.core");
    private static final SoundEvent GOLEM_OBSIDIAN_MOVE = delivered("entity.golem.obsidian.move");
    private static final SoundEvent GOLEM_OBSIDIAN_CORE = delivered("entity.golem.obsidian.core");

    private ModSounds() {
    }

    /** Which of the five things this goblin just did, in the voice its sex calls for. */
    public static SoundEvent goblinVoice(boolean female, Voice voice) {
        return switch (voice) {
            case GREETING -> female ? GOBLIN_FEMALE_GREETING : GOBLIN_MALE_GREETING;
            case RESPONSE -> female ? GOBLIN_FEMALE_RESPONSE : GOBLIN_MALE_RESPONSE;
            case WORK -> female ? GOBLIN_FEMALE_WORK : GOBLIN_MALE_WORK;
            case HURT -> female ? GOBLIN_FEMALE_HURT : GOBLIN_MALE_HURT;
            case DEATH -> female ? GOBLIN_FEMALE_DEATH : GOBLIN_MALE_DEATH;
        };
    }

    /**
     * Empty for the tiers the art team did not make sounds for -- iron keeps the vanilla golem's.
     * Reuses hasCustomArt() rather than keeping a second list of "the custom tiers": the five tiers
     * with custom art are exactly the five with custom sounds, and two lists would drift apart.
     */
    public static Optional<SoundEvent> golemMove(GolemTier tier) {
        if (!tier.hasCustomArt()) {
            return Optional.empty();
        }
        return Optional.of(switch (tier) {
            case WOOD -> GOLEM_WOOD_MOVE;
            case STONE -> GOLEM_STONE_MOVE;
            case GOLD -> GOLEM_GOLD_MOVE;
            case DIAMOND -> GOLEM_DIAMOND_MOVE;
            case OBSIDIAN -> GOLEM_OBSIDIAN_MOVE;
            default -> throw new IllegalStateException("no custom sound for " + tier);
        });
    }

    /** The tier's looping core, empty for iron for the same reason as {@link #golemMove}. */
    public static Optional<SoundEvent> golemCore(GolemTier tier) {
        if (!tier.hasCustomArt()) {
            return Optional.empty();
        }
        return Optional.of(switch (tier) {
            case WOOD -> GOLEM_WOOD_CORE;
            case STONE -> GOLEM_STONE_CORE;
            case GOLD -> GOLEM_GOLD_CORE;
            case DIAMOND -> GOLEM_DIAMOND_CORE;
            case OBSIDIAN -> GOLEM_OBSIDIAN_CORE;
            default -> throw new IllegalStateException("no custom sound for " + tier);
        });
    }

    /**
     * Creates one event and remembers it for {@link #initialize()} to register. The two steps are
     * separate on purpose: creating an event touches no registry, so a standalone check can load this
     * class and read the ids, while registering can only happen at mod init, before Fabric freezes the
     * registries. Doing both in the initialiser would make this class unloadable from a check.
     */
    private static SoundEvent delivered(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, path);
        SoundEvent event = SoundEvent.createVariableRangeEvent(id);
        DELIVERED.add(event);
        return event;
    }

    /** Registers everything {@link #delivered} collected. Called once, from mod init. */
    public static void initialize() {
        for (SoundEvent event : DELIVERED) {
            Registry.register(BuiltInRegistries.SOUND_EVENT, event.location(), event);
        }
    }
}
```

**并在常量区最前面加一行** `private static final java.util.List<SoundEvent> DELIVERED = new java.util.ArrayList<>();`（`delivered` 要往它里面收）。

**这一段与初稿不同，是 Task 1 实现时更正过的**：初稿把注册写在静态初始化器里（`register(...)` 直接 `Registry.register`）。那样一来**独立检查根本加载不了这个类** —— 类初始化就会注册，而 bootstrap 之前注册不可用、bootstrap 之后注册表已冻结，两边都是异常。拆成"造对象"与"注册"两步之后，类的初始化不碰注册表，`SoundsCheck` 才取得到那 20 个 id。

**注意 switch 里的 `default`**：`GolemTier` 有六个值而这里只列五个，Java 的穷尽 switch 因此需要一个 `default`；写 `throw` 而不是兜底返回某个音，是为了让"漏了一阶"响亮地失败而不是静默发错声。

- [ ] **Step 6: 加语言条目**

两个语言文件**各自在末尾追加** 20 条（保留现有条目）。`zh_cn.json` 用交付包 `merge/zh_cn_subtitles.json` 的原文：

```json
  "subtitles.goblin_settlement.entity.goblin.male.greeting": "男性哥布林招呼",
  "subtitles.goblin_settlement.entity.goblin.female.greeting": "女性哥布林招呼",
  "subtitles.goblin_settlement.entity.goblin.male.response": "男性哥布林应答",
  "subtitles.goblin_settlement.entity.goblin.female.response": "女性哥布林应答",
  "subtitles.goblin_settlement.entity.goblin.male.work": "男性哥布林工作号子",
  "subtitles.goblin_settlement.entity.goblin.female.work": "女性哥布林工作号子",
  "subtitles.goblin_settlement.entity.goblin.male.hurt": "男性哥布林受击",
  "subtitles.goblin_settlement.entity.goblin.female.hurt": "女性哥布林受击",
  "subtitles.goblin_settlement.entity.goblin.male.death": "男性哥布林死亡",
  "subtitles.goblin_settlement.entity.goblin.female.death": "女性哥布林死亡",
  "subtitles.goblin_settlement.entity.golem.wood.move": "木傀儡移动",
  "subtitles.goblin_settlement.entity.golem.wood.core": "木傀儡核心运转",
  "subtitles.goblin_settlement.entity.golem.stone.move": "石傀儡移动",
  "subtitles.goblin_settlement.entity.golem.stone.core": "石傀儡核心运转",
  "subtitles.goblin_settlement.entity.golem.gold.move": "金傀儡移动",
  "subtitles.goblin_settlement.entity.golem.gold.core": "金傀儡核心运转",
  "subtitles.goblin_settlement.entity.golem.diamond.move": "钻石傀儡移动",
  "subtitles.goblin_settlement.entity.golem.diamond.core": "钻石傀儡核心运转",
  "subtitles.goblin_settlement.entity.golem.obsidian.move": "黑曜石傀儡移动",
  "subtitles.goblin_settlement.entity.golem.obsidian.core": "黑曜石傀儡核心运转"
```

`en_us.json` 用同样 20 个键，英文由本轮写（交付包只给了中文）：

```json
  "subtitles.goblin_settlement.entity.goblin.male.greeting": "Male goblin greets",
  "subtitles.goblin_settlement.entity.goblin.female.greeting": "Female goblin greets",
  "subtitles.goblin_settlement.entity.goblin.male.response": "Male goblin answers",
  "subtitles.goblin_settlement.entity.goblin.female.response": "Female goblin answers",
  "subtitles.goblin_settlement.entity.goblin.male.work": "Male goblin chants",
  "subtitles.goblin_settlement.entity.goblin.female.work": "Female goblin chants",
  "subtitles.goblin_settlement.entity.goblin.male.hurt": "Male goblin hurts",
  "subtitles.goblin_settlement.entity.goblin.female.hurt": "Female goblin hurts",
  "subtitles.goblin_settlement.entity.goblin.male.death": "Male goblin dies",
  "subtitles.goblin_settlement.entity.goblin.female.death": "Female goblin dies",
  "subtitles.goblin_settlement.entity.golem.wood.move": "Wood golem moves",
  "subtitles.goblin_settlement.entity.golem.wood.core": "Wood golem core hums",
  "subtitles.goblin_settlement.entity.golem.stone.move": "Stone golem moves",
  "subtitles.goblin_settlement.entity.golem.stone.core": "Stone golem core hums",
  "subtitles.goblin_settlement.entity.golem.gold.move": "Gold golem moves",
  "subtitles.goblin_settlement.entity.golem.gold.core": "Gold golem core hums",
  "subtitles.goblin_settlement.entity.golem.diamond.move": "Diamond golem moves",
  "subtitles.goblin_settlement.entity.golem.diamond.core": "Diamond golem core hums",
  "subtitles.goblin_settlement.entity.golem.obsidian.move": "Obsidian golem moves",
  "subtitles.goblin_settlement.entity.golem.obsidian.core": "Obsidian golem core hums"
```

- [ ] **Step 7: 接进主类**

`GoblinSettlement.onInitialize` 里、`ModBlocks.initialize();` 之后加 `ModSounds.initialize();`，并补 import。

- [ ] **Step 8: 跑检查**

Run: `./gradlew soundsCheck --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，`SoundsCheck passed`。

- [ ] **Step 9: 全量构建**

Run: `./gradlew build --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，**24** 项 `*Check passed`。

- [ ] **Step 10: 提交**

```bash
git add goblin-settlement-mod/src/main/resources/assets/goblin_settlement/sounds.json \
        goblin-settlement-mod/src/main/resources/assets/goblin_settlement/sounds/ \
        goblin-settlement-mod/src/main/resources/assets/goblin_settlement/lang/ \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/sound/ \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/sound/ \
        goblin-settlement-mod/build.gradle
git commit -m "Register the delivered sound set, and make a missing subtitle fail the build"
```

---

### Task 2: 一次性音效的挂点

**Files:**
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/GoblinGolemEntity.java`

**Interfaces:**
- Consumes: `ModSounds.goblinVoice(boolean, Voice)`、`ModSounds.golemMove(GolemTier)`（Task 1）；`femaleForRender()`（`GoblinCitizenEntity:613`）；`GolemTier.hasCustomArt()`；`DataComponents`…

- [ ] **Step 1: 哥布林的受击与死亡**

在 `GoblinCitizenEntity` 里加两个覆写（放在 `die` 附近即可，位置不影响行为）：

```java
    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return ModSounds.goblinVoice(femaleForRender(), ModSounds.Voice.HURT);
    }

    @Override
    protected SoundEvent getDeathSound() {
        return ModSounds.goblinVoice(femaleForRender(), ModSounds.Voice.DEATH);
    }
```

补 import：`net.minecraft.sounds.SoundEvent`、`net.minecraft.world.damagesource.DamageSource`。

**用 `femaleForRender()` 而不是读 `DATA_FEMALE`**：那是本实体已有的"客户端该画成哪个性别"的唯一判据（渲染器也用同一个）。这里虽然跑在服务端，但取的是同一个语义值，且不需要再抄一遍 `EntityDataAccessor` 的读取方式。

- [ ] **Step 2: 哥布林的工作号子**

在 `customServerAiStep` 里、**职业节流闸门之后**、派发链之前插入一行。原文（`:767-770`）：

```java
        WorkKind kind = workKind(workStage);
        if (kind != null && tickCount % ProfessionRules.workIntervalTicks(kind, profession()) != 0) {
            return;
        }
```

改成：

```java
        WorkKind kind = workKind(workStage);
        if (kind != null && tickCount % ProfessionRules.workIntervalTicks(kind, profession()) != 0) {
            return;
        }
        // After the gate, on purpose: this is the tick the resident actually works, so the chant
        // inherits the profession's own 10/20/30 throttle instead of inventing a second cadence, and
        // ticks the gate threw away stay silent.
        maybeChant(level);
```

并在类里加：

```java
    /**
     * How long a resident stays quiet after a work chant. An invented value: the art brief asked for a
     * work chant and gave no frequency, so this is a guess to be re-tuned once it can be heard in game.
     */
    private static final int WORK_CHANT_COOLDOWN_TICKS = 1200;

    /** The tick this resident last chanted; 0 means "not yet". Transient: not saved, gone with the entity. */
    private long lastChantTick = 0L;

    private void maybeChant(ServerLevel level) {
        long now = level.getGameTime();
        // Absolute ticks, NOT a countdown. This method is only reached on the ticks the profession's own
        // throttle let through, so a countdown would spend one unit per *throttled* tick -- "1200 ticks"
        // would stretch to ten minutes for a profession on the 10-tick cadence, not the sixty seconds the
        // constant is meant to say. Comparing absolute ticks means the number means what it says.
        if (now - lastChantTick < WORK_CHANT_COOLDOWN_TICKS) {
            return;
        }
        lastChantTick = now;
        level.playSound(null, getX(), getY(), getZ(),
                ModSounds.goblinVoice(femaleForRender(), ModSounds.Voice.WORK),
                SoundSource.NEUTRAL, 0.7F, 1.0F);
    }
```

补 import `net.minecraft.sounds.SoundSource`。

**`lastChantTick` 是瞬态字段**（与 `patrolIndex` 同类）：不入存档、随实体消失，这是有意的 —— 冷却不必跨重载记住，重载后最多早响一声。初值 0 的语义是"还没响过"，与 §5 的招呼冷却同一套（世界时间不足一个冷却时不会被选中）。

- [ ] **Step 3: 傀儡的移动音**

在 `GoblinGolemEntity` 里覆写迈步音：

```java
    /**
     * The tier's own footfall instead of the generic one. Iron has no custom sound, so it falls through
     * to the vanilla step sound rather than going silent.
     */
    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        Optional<SoundEvent> move = tierForRender().flatMap(ModSounds::golemMove);
        if (move.isEmpty()) {
            super.playStepSound(pos, state);
            return;
        }
        playSound(move.orElseThrow(), 0.6F, 1.0F);
    }
```

补 import：`net.minecraft.sounds.SoundEvent`、`net.minecraft.world.level.block.state.BlockState`、`java.util.Optional`（若尚未 import）。

**用 `tierForRender()` 而不是 `tier()`**：前者已经过滤掉"这只傀儡该不该画成自定义外观"，与声音要回答的"该不该用自定义声音"是同一个问题；而且 `playStepSound` 在两端都会被调用，`tierForRender()` 读的是同步值、两端都拿得到，`tier()` 只有服务端有。

- [ ] **Step 4: 构建**

Run: `./gradlew build --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，24 项 `*Check passed`（本任务不新增检查 —— 实体发声不可纯测）。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/citizen/GoblinCitizenEntity.java \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/defense/GoblinGolemEntity.java
git commit -m "Give the goblins a voice when they are hurt, die or work, and the golems their own tread"
```

---

### Task 3: 傀儡核心循环（客户端）

**Files:**
- Create: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/sound/GolemCoreSound.java`
- Create: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/sound/GolemCoreSounds.java`
- Modify: `goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/GoblinSettlementClient.java`

**Interfaces:**
- Consumes: `ModSounds.golemCore(GolemTier)`、`GolemGolemEntity.tierForRender()`、`ClientTickEvents.END_CLIENT_TICK`
- Produces: `GolemCoreSounds.tick()`（由客户端入口每 tick 调用）

- [ ] **Step 1: 写循环实例**

新建 `src/client/java/dev/local/goblinsettlement/client/sound/GolemCoreSound.java`：

```java
package dev.local.goblinsettlement.client.sound;

import dev.local.goblinsettlement.defense.GoblinGolemEntity;
import dev.local.goblinsettlement.defense.GolemTier;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * One golem's core, looping for as long as that golem is alive and loaded.
 *
 * <p>The engine stops it, not a timer of ours: the only question is whether the entity is still there,
 * which is what isStopped() answers. That is also why the core is not resent from the server every few
 * seconds -- a four second sample resent every four seconds would seam, would need a stop packet, and
 * would keep sending packets for as long as the golem lives.
 */
final class GolemCoreSound extends AbstractTickableSoundInstance {
    private final GoblinGolemEntity golem;
    private final GolemTier tier;

    GolemCoreSound(GoblinGolemEntity golem, GolemTier tier, SoundEvent sound) {
        super(sound, SoundSource.NEUTRAL, RandomSource.create());
        this.golem = golem;
        this.tier = tier;
        this.looping = true;
        this.relative = false;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
    }

    /** True when this instance is still the right sound for that golem -- a tier change needs a new one. */
    boolean suits(GolemTier other) {
        return this.tier == other;
    }

    @Override
    public void tick() {
        if (!golem.isAlive() || golem.isRemoved()) {
            stop();
            return;
        }
        this.x = golem.getX();
        this.y = golem.getY();
        this.z = golem.getZ();
    }
}
```

- [ ] **Step 2: 写管理器**

新建 `src/client/java/dev/local/goblinsettlement/client/sound/GolemCoreSounds.java`：

```java
package dev.local.goblinsettlement.client.sound;

import dev.local.goblinsettlement.defense.GoblinGolemEntity;
import dev.local.goblinsettlement.sound.ModSounds;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Keeps one looping core sound per loaded custom golem, and lets go of the ones whose golem is gone.
 * Client-only: the server never learns these exist.
 */
public final class GolemCoreSounds {
    private static final Map<Integer, GolemCoreSound> PLAYING = new HashMap<>();

    private GolemCoreSounds() {
    }

    public static void tick() {
        var client = Minecraft.getInstance();
        var level = client.level;
        if (level == null) {
            PLAYING.clear();
            return;
        }
        Set<Integer> present = new HashSet<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof GoblinGolemEntity golem)) {
                continue;
            }
            var tier = golem.tierForRender();
            if (tier.isEmpty()) {
                continue;
            }
            int id = golem.getId();
            present.add(id);
            GolemCoreSound existing = PLAYING.get(id);
            if (existing != null && existing.suits(tier.orElseThrow())) {
                continue;
            }
            // Either the first sighting of this golem, or it changed tier: a tier's core is its own
            // sound, so a promoted golem must stop humming the old one.
            if (existing != null) {
                client.getSoundManager().stop(existing);
            }
            var sound = ModSounds.golemCore(tier.orElseThrow()).orElseThrow();
            var instance = new GolemCoreSound(golem, tier.orElseThrow(), sound);
            client.getSoundManager().play(instance);
            PLAYING.put(id, instance);
        }
        PLAYING.entrySet().removeIf(entry -> !present.contains(entry.getKey()));
    }
}
```

- [ ] **Step 3: 接进客户端入口**

`GoblinSettlementClient.onInitializeClient` 里加：

```java
        ClientTickEvents.END_CLIENT_TICK.register(client -> GolemCoreSounds.tick());
```

补 import：`net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents`、`dev.local.goblinsettlement.client.sound.GolemCoreSounds`。

- [ ] **Step 4: 构建**

Run: `./gradlew build --offline --no-daemon`
Expected: **BUILD SUCCESSFUL**，24 项 `*Check passed`。

- [ ] **Step 5: 提交**

```bash
git add goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/sound/ \
        goblin-settlement-mod/src/client/java/dev/local/goblinsettlement/client/GoblinSettlementClient.java
git commit -m "Hum each golem's core on the client, so the loop has no seam and no packets"
```

---

### Task 4: 碰面行为

**Files:**
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/social/GreetingRules.java`
- Create: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/social/GreetingCoordinator.java`
- Modify: `goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java`（接线）
- Modify: `goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/sound/SoundsCheck.java`（追加断言）

**Interfaces:**
- Consumes: `ResidentWorkLookup.loaded(ServerLevel, SettlementSavedData)`（`colony/ResidentWorkLookup.java:16`）；`ModSounds.goblinVoice(boolean, Voice)`
- Produces: `GreetingRules.pick(List<GreetingRules.Resident>, double radius) -> Optional<GreetingRules.Pair>`；`GreetingRules.Resident(String id, double x, double z, long lastGreetTick)`；`GreetingRules.Pair(String greeterId, String answererId)`；`GreetingCoordinator.tick(ServerLevel)`

- [ ] **Step 1: 先写断言（RED）**

在 `SoundsCheck.main` 的 `System.out.println` **之前**加：

```java
        // The meet rule: nothing to say when nobody is close enough, or when everyone is still in
        // cooldown; otherwise the lower id greets and the other answers. Every tie is broken by id so
        // the same input always gives the same pair -- otherwise nothing here could be pinned.
        //
        // Every call passes "now" explicitly, so the cooldown is a function of the arguments rather
        // than of when the check happened to run.
        final long now = 10_000L;
        final long cooled = now - GreetingRules.GREETING_COOLDOWN_TICKS - 1;

        check(GreetingRules.pick(List.of(), 3.0, now).isEmpty(), "an empty crowd greets nobody");
        check(GreetingRules.pick(List.of(new GreetingRules.Resident("a", 0.0, 0.0, cooled)), 3.0, now)
                .isEmpty(), "one resident has nobody to greet");
        check(GreetingRules.pick(List.of(
                        new GreetingRules.Resident("a", 0.0, 0.0, cooled),
                        new GreetingRules.Resident("b", 100.0, 0.0, cooled)), 3.0, now).isEmpty(),
                "a resident out of reach is not greeted");
        var pair = GreetingRules.pick(List.of(
                        new GreetingRules.Resident("b", 1.0, 0.0, cooled),
                        new GreetingRules.Resident("a", 0.0, 0.0, cooled)), 3.0, now).orElseThrow();
        check(pair.greeterId().equals("a") && pair.answererId().equals("b"),
                "the lower id greets, whatever order the list came in, got " + pair);
        check(GreetingRules.pick(List.of(
                        new GreetingRules.Resident("a", 0.0, 0.0, now),
                        new GreetingRules.Resident("b", 1.0, 0.0, cooled)), 3.0, now).isEmpty(),
                "a resident who greeted just now is not picked again");
        // Exactly on the radius counts as in reach; a hair outside does not.
        check(GreetingRules.pick(List.of(
                        new GreetingRules.Resident("a", 0.0, 0.0, cooled),
                        new GreetingRules.Resident("b", 3.0, 0.0, cooled)), 3.0, now).isPresent(),
                "a resident exactly on the radius is in reach");
        check(GreetingRules.pick(List.of(
                        new GreetingRules.Resident("a", 0.0, 0.0, cooled),
                        new GreetingRules.Resident("b", 3.01, 0.0, cooled)), 3.0, now).isEmpty(),
                "a resident just past the radius is not");
        // lastGreetTick 0 means "has never greeted"; a settlement younger than the cooldown therefore
        // stays quiet, so a group born together does not all speak at once.
        check(GreetingRules.pick(List.of(
                        new GreetingRules.Resident("a", 0.0, 0.0, 0L),
                        new GreetingRules.Resident("b", 1.0, 0.0, 0L)), 3.0, 100L).isEmpty(),
                "a settlement younger than the cooldown stays quiet");
```

- [ ] **Step 2: 写纯规则**

新建 `src/main/java/dev/local/goblinsettlement/social/GreetingRules.java`：

```java
package dev.local.goblinsettlement.social;

import java.util.List;
import java.util.Optional;

/**
 * Who greets whom when two residents meet. Pure: ints, doubles and ids only, so the choice can be
 * pinned by a check instead of described in a comment.
 */
public final class GreetingRules {
    /** An invented value: the art brief asked for a greeting and gave no cadence. Re-tune in game. */
    public static final int GREETING_COOLDOWN_TICKS = 600;

    /** One resident as the rule sees them: where they are, and when they last greeted anyone. */
    public record Resident(String id, double x, double z, long lastGreetTick) {
    }

    /** Who speaks first and who answers. */
    public record Pair(String greeterId, String answererId) {
    }

    private GreetingRules() {
    }

    /**
     * The first pair that may greet, or empty. The lower id greets, so the answer does not depend on
     * the order the roster happened to be walked in.
     */
    public static Optional<Pair> pick(List<Resident> residents, double radius, long nowTick) {
        Resident greeter = null;
        Resident answerer = null;
        for (Resident candidate : residents) {
            if (!ready(candidate, nowTick)) {
                continue;
            }
            for (Resident other : residents) {
                if (other == candidate || !ready(other, nowTick) || distance(candidate, other) > radius) {
                    continue;
                }
                if (candidate.id().compareTo(other.id()) < 0) {
                    greeter = candidate;
                    answerer = other;
                } else {
                    greeter = other;
                    answerer = candidate;
                }
                break;
            }
            if (greeter != null) {
                break;
            }
        }
        return greeter == null ? Optional.empty()
                : Optional.of(new Pair(greeter.id(), answerer.id()));
    }

    private static boolean ready(Resident resident, long nowTick) {
        return nowTick - resident.lastGreetTick() >= GREETING_COOLDOWN_TICKS;
    }

    private static double distance(Resident left, Resident right) {
        double dx = left.x() - right.x();
        double dz = left.z() - right.z();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
```

**注意**：`ready` 用 `nowTick - lastGreetTick >= COOLDOWN`。新居民（`lastGreetTick = 0`）在游戏时间还不足 `GREETING_COOLDOWN_TICKS` 时**不会**被选中——这是刻意的：刚出生的一群人不该同时开口。Step 1 最后那条断言钉的就是这个语义。

- [ ] **Step 3: 写协调器**

新建 `src/main/java/dev/local/goblinsettlement/social/GreetingCoordinator.java`：

```java
package dev.local.goblinsettlement.social;

import dev.local.goblinsettlement.citizen.GoblinCitizenEntity;
import dev.local.goblinsettlement.colony.ResidentWorkLookup;
import dev.local.goblinsettlement.colony.SettlementSavedData;
import dev.local.goblinsettlement.sound.ModSounds;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;

/**
 * Residents greet each other when they meet. GAME_DESIGN already lists "meeting in the public squares"
 * as part of a resident's day; this is the first thing to implement it, and it exists here because the
 * greeting and the answer are two halves of one exchange that had no other home.
 */
public final class GreetingCoordinator {
    /** How often to look for a meeting. An invented value; re-tune in game. */
    private static final int INTERVAL_TICKS = 100;
    /** How close counts as meeting. An invented value; re-tune in game. */
    private static final double RADIUS = 3.0;
    /** How long the answer waits, so the two do not talk over each other. An invented value. */
    private static final int RESPONSE_DELAY_TICKS = 12;

    /** When each resident last greeted anyone, and the answers still owed. Session-only, not saved. */
    private static final java.util.Map<String, Long> LAST_GREETING = new java.util.HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();

    private record Pending(String residentId, long dueTick, boolean female) {
    }

    private GreetingCoordinator() {
    }

    public static void tick(ServerLevel level) {
        long now = level.getGameTime();
        deliverAnswers(level, now);
        if (now % INTERVAL_TICKS != 0) {
            return;
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        List<GoblinCitizenEntity> residents = ResidentWorkLookup.loaded(level, data);
        var seen = new ArrayList<GreetingRules.Resident>(residents.size());
        var present = new java.util.HashSet<String>(residents.size());
        for (GoblinCitizenEntity resident : residents) {
            String id = resident.getUUID().toString();
            present.add(id);
            seen.add(new GreetingRules.Resident(id, resident.getX(), resident.getZ(),
                    LAST_GREETING.getOrDefault(id, 0L)));
        }
        // Drop residents who are gone, or the table would grow for the whole session: a dead resident's
        // id is never seen again and nothing else would ever remove it.
        LAST_GREETING.keySet().retainAll(present);
        var pair = GreetingRules.pick(seen, RADIUS, now);
        if (pair.isEmpty()) {
            return;
        }
        GoblinCitizenEntity greeter = find(residents, pair.orElseThrow().greeterId());
        GoblinCitizenEntity answerer = find(residents, pair.orElseThrow().answererId());
        if (greeter == null || answerer == null) {
            return;
        }
        LAST_GREETING.put(pair.orElseThrow().greeterId(), now);
        LAST_GREETING.put(pair.orElseThrow().answererId(), now);
        level.playSound(null, greeter.getX(), greeter.getY(), greeter.getZ(),
                ModSounds.goblinVoice(greeter.femaleForRender(), ModSounds.Voice.GREETING),
                SoundSource.NEUTRAL, 0.8F, 1.0F);
        PENDING.add(new Pending(pair.orElseThrow().answererId(), now + RESPONSE_DELAY_TICKS,
                answerer.femaleForRender()));
    }

    /** Answers owed from earlier. A resident who died, left, or walked away simply never answers. */
    private static void deliverAnswers(ServerLevel level, long now) {
        for (Iterator<Pending> iterator = PENDING.iterator(); iterator.hasNext(); ) {
            Pending pending = iterator.next();
            if (pending.dueTick() > now) {
                continue;
            }
            iterator.remove();
            GoblinCitizenEntity resident = level.getEntity(java.util.UUID.fromString(pending.residentId()))
                    instanceof GoblinCitizenEntity found ? found : null;
            if (resident == null || !resident.isAlive() || resident.isRemoved()) {
                continue;
            }
            level.playSound(null, resident.getX(), resident.getY(), resident.getZ(),
                    ModSounds.goblinVoice(pending.female(), ModSounds.Voice.RESPONSE),
                    SoundSource.NEUTRAL, 0.8F, 1.0F);
        }
    }

    private static GoblinCitizenEntity find(List<GoblinCitizenEntity> residents, String id) {
        for (GoblinCitizenEntity resident : residents) {
            if (resident.getUUID().toString().equals(id)) {
                return resident;
            }
        }
        return null;
    }
}
```

- [ ] **Step 4: 接线**

`GoblinSettlement.tickSettlement` 里、`SettlementProfiler.run("expansion", ...)` **之后**加：

```java
        SettlementProfiler.run("greeting", () -> GreetingCoordinator.tick(level));
```

**排在最后**：打招呼纯粹是氛围，没有任何协调职责，所以让它排在所有有职责的协调器之后。

- [ ] **Step 5: 跑检查与构建**

Run: `./gradlew soundsCheck --offline --no-daemon` 然后 `./gradlew build --offline --no-daemon`
Expected: 前者 `SoundsCheck passed`（新断言在内），后者 **BUILD SUCCESSFUL**、24 项 `*Check passed`。

- [ ] **Step 6: 证明断言会咬**

把 `GreetingRules.pick` 里 `candidate.id().compareTo(other.id()) < 0` 改成 `> 0`（谁招呼反了），跑 `./gradlew soundsCheck --offline --no-daemon`，**capture 到 `AssertionError`**，然后改回来再跑一次确认绿。把两次输出都写进报告。

- [ ] **Step 7: 提交**

```bash
git add goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/social/ \
        goblin-settlement-mod/src/main/java/dev/local/goblinsettlement/GoblinSettlement.java \
        goblin-settlement-mod/src/test/java/dev/local/goblinsettlement/sound/SoundsCheck.java
git commit -m "Let residents greet each other when they meet, which is the only home the calls had"
```

---

### Task 5: 文档收尾

**Files:**
- Modify: `goblin-settlement-plan/TEST_CHECKLIST.md`（追加第 11 组）
- Modify: `goblin-settlement-plan/CURRENT_STATUS.md`
- Modify: `goblin-settlement-plan/UpdateLog.md`（**只许追加**）

- [ ] **Step 1: 先跑 `git status`**

可能有并行 agent 的未提交改动。只提交自己的文件。

- [ ] **Step 2: 追加测试清单第 11 组**

内容：**只能进游戏听**的那些 —— 傀儡核心有没有接缝、被移除/死亡时是否真的停、招呼是不是太频繁、五阶移动音认不认得出、整体会不会太吵、男女声音听不听得出区别。**并把这轮的发明值列进第 9 组**：`GreetingCoordinator.RADIUS = 3.0`、`GreetingCoordinator.INTERVAL_TICKS = 100`、`GreetingRules.GREETING_COOLDOWN_TICKS = 600`、`GreetingCoordinator.RESPONSE_DELAY_TICKS = 12`、`GoblinCitizenEntity.WORK_CHANT_COOLDOWN_TICKS = 1200`。**另加一条冒烟步骤**：进游戏实际播一次某个音（例如 `/playsound goblin_settlement:entity.goblin.male.greeting`）——**24 项检查里没有任何一条覆盖 `ModSounds.initialize()` 这个调用本身**，删掉它所有检查仍然全绿。

- [ ] **Step 3: 更新 `CURRENT_STATUS.md`**

更新日期行；新增第七十七轮条目（音效已实现、待游戏验证），并把它从「下一步落点」里移出或改写。保持简短。

- [ ] **Step 4: 追加 `UpdateLog.md`**

按格式在**末尾**追加本轮段。如实写：改了什么、24 项检查结果、**未做游戏内验证**、以及实现期的任何偏离。

- [ ] **Step 5: 提交并推送**

```bash
git add goblin-settlement-plan/
git commit -m "Record the sound round: twenty events wired, and a subtitle gap now fails the build"
git push origin main
```

推不上去就留在本地并如实报告。

---

## Self-Review

**1. Spec coverage**

| 设计节 | 落在哪个任务 |
| --- | --- |
| §2 资源与注册（40 ogg、sounds.json、20 个 SoundEvent） | Task 1 |
| §3 挂点表（受击/死亡/工作号子/移动/核心/铁傀儡/儿童） | Task 2（前四项与铁傀儡回退）、Task 3（核心） |
| §4 傀儡核心客户端循环 | Task 3 |
| §5 碰面行为（含待办表边界） | Task 4 |
| §6 字幕与语言（中英各 20） | Task 1 Step 6 |
| §7 测试策略（四条可纯测 + 只能进游戏听） | Task 1（1–3 条与第 4 条的检查骨架）、Task 4 Step 1/6（第 4 条与变异证明）、Task 5 Step 2（只能进游戏听） |
| §8 明确未做 | 不建任务（是"不做"清单） |

**2. Placeholder scan**

无 `TBD`/`TODO`。Task 2 的 `maybeChant` 与 Task 4 的协调器都有完整代码。**初稿里有两处含糊，已在自查中消掉**：`SoundEvent` 取 Identifier 的方法名（原写"以编译器为准"）已核实为 `location()`（record 访问器）；`GreetingRules.pick` 的断言原混用两参与三参、并引用了一个**不存在的常量** `SUPPRESSED_UNTIL`，现已统一成三参 `(List, double, long)` 并显式传入 `now`。**两处都属于"含糊会变成 bug"那一类，不留。**

**3. Type consistency**

- `ModSounds.Voice` 五个值（`GREETING/RESPONSE/WORK/HURT/DEATH`）在 Task 1 定义，Task 2 与 Task 4 使用 —— 一致。
- `ModSounds.goblinVoice(boolean female, Voice)`：Task 2 传 `femaleForRender()`、Task 4 传 `golem...` 不，Task 4 传 `greeter.femaleForRender()` —— 一致。
- `ModSounds.golemMove/golemCore(GolemTier) -> Optional<SoundEvent>`：Task 1 定义，Task 2 用 `flatMap`、Task 3 用 `.orElseThrow()` —— 一致。
- `GreetingRules.Resident(String id, double x, double z, long lastGreetTick)` 与 `Pair(String greeterId, String answererId)`：Task 4 Step 1 的断言、Step 2 的定义、Step 3 的使用三处字段名一致。
- **`GreetingRules.pick` 的签名**：`(List<Resident>, double radius, long nowTick)`。Step 1 的九条断言全部三参、实现也是三参、Task 4 Step 3 的调用也是三参 —— 一致。断言里的 `now` 与 `cooled` 是局部量，随每条用例显式传入，所以冷却判据不依赖"检查跑在哪一刻"。
- `SoundsCheck` 由 Task 1 建、Task 4 追加 —— 与上一轮 `NoticeboardCheck` 同形。
- `GolemCoreSound.suits(GolemTier)`：Task 3 内定义与使用一致。
