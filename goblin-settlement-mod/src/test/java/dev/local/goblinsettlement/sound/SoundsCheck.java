package dev.local.goblinsettlement.sound;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.local.goblinsettlement.GoblinSettlement;
import dev.local.goblinsettlement.defense.GolemTier;
import dev.local.goblinsettlement.social.GreetingRules;
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

/** Standalone checks for the delivered sound set: the manifest, its files, the lookups and the subtitles. */
public final class SoundsCheck {
    private static final String SOUNDS_JSON = "/assets/goblin_settlement/sounds.json";
    private static final String LANG = "/assets/goblin_settlement/lang/";
    private static final String SUBTITLE_PREFIX = "subtitles." + GoblinSettlement.MOD_ID + ".";

    public static void main(String[] args) {
        // GolemTier names vanilla Items, whose class init builds vanilla blocks and sounds, so the
        // built-in registries must be bootstrapped first. This is read-only, like ConstructionMaterialCheck.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        JsonObject manifest = readJson(SOUNDS_JSON);

        // 1. The manifest declares exactly twenty events.
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

        // 6. Every core is buffered in memory rather than streamed. The round's central claim -- no seam,
        //    zero packets -- rests on the four second core being decoded once: a streamed sound is pulled
        //    off disk in pieces while it plays, which is where a loop seam would come from. This is worth
        //    pinning because sounds.json is copied verbatim from a file this mod does not own, so if the
        //    art team re-delivered the cores as streamed, nothing else here would notice. The field has to
        //    be present and false, not merely absent: an explicit false is what the delivery says.
        List<String> streamed = new ArrayList<>();
        for (String key : declared) {
            if (!key.endsWith(".core")) {
                continue;
            }
            for (var element : manifest.getAsJsonObject(key).getAsJsonArray("sounds")) {
                JsonObject sound = element.getAsJsonObject();
                if (!sound.has("stream") || sound.get("stream").getAsBoolean()) {
                    streamed.add(key + " -> " + sound.get("name").getAsString());
                }
            }
        }
        check(streamed.isEmpty(), "every core is buffered rather than streamed, but: " + streamed);

        // The meet rule: nothing to say when nobody is close enough, or when everyone is still in
        // cooldown; otherwise the first in-reach pair in roster order speaks, and inside that pair the
        // lower id greets. The pair follows roster order, the roles follow id order -- the last two
        // assertions say so, so the javadoc cannot drift stronger than the rule it describes.
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
        // Three residents in one cluster, which is where "the lower id greets" and "the answer does not
        // depend on roster order" part company: the pair is whichever in-reach pair the walk reaches
        // first, so the roster decides the pair while the ids decide the roles inside it. Both readings
        // are pinned here, because a claim stronger than the rule is how the javadoc got wrong before.
        var clustered = GreetingRules.pick(List.of(
                        new GreetingRules.Resident("a", 0.0, 0.0, cooled),
                        new GreetingRules.Resident("b", 1.0, 0.0, cooled),
                        new GreetingRules.Resident("c", 2.0, 0.0, cooled)), 3.0, now).orElseThrow();
        check(clustered.greeterId().equals("a") && clustered.answererId().equals("b"),
                "three in one cluster: the first pair in roster order greets, got " + clustered);
        var reversedCluster = GreetingRules.pick(List.of(
                        new GreetingRules.Resident("c", 2.0, 0.0, cooled),
                        new GreetingRules.Resident("b", 1.0, 0.0, cooled),
                        new GreetingRules.Resident("a", 0.0, 0.0, cooled)), 3.0, now).orElseThrow();
        check(reversedCluster.greeterId().equals("b") && reversedCluster.answererId().equals("c"),
                "the pair follows roster order, not id order, got " + reversedCluster);

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
