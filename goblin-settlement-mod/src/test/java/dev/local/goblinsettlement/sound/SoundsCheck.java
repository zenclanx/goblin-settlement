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
