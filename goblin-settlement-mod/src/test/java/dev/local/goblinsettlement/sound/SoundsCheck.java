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
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.sounds.SoundEvent;

/** Standalone checks for the delivered sound set: registration, resources, subtitles and the meet rule. */
public final class SoundsCheck {
    private static final String SOUNDS_JSON = "/assets/goblin_settlement/sounds.json";
    private static final String LANG = "/assets/goblin_settlement/lang/";
    private static final String SUBTITLE_PREFIX = "subtitles." + GoblinSettlement.MOD_ID + ".";

    public static void main(String[] args) {
        // Registries must be bootstrapped before anything here touches BuiltInRegistries.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Bootstrap.bootStrap() freezes every built-in registry. In the running game Fabric's
        // registry-sync mixin defers that freeze until after mod initializers have registered; this
        // standalone check has no mixin, so it defers the one registry it needs and then loads ModSounds
        // to register -- the same order, mods before freeze.
        deferSoundEventFreeze();
        ModSounds.initialize();

        JsonObject manifest = readJson(SOUNDS_JSON);

        // 1. Every event the manifest declares is registered, under exactly the id the manifest names.
        Set<String> declared = new LinkedHashSet<>(manifest.keySet());
        check(declared.size() == 20, "the manifest declares twenty events, got " + declared.size());
        for (String key : declared) {
            Identifier id = Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, key);
            check(BuiltInRegistries.SOUND_EVENT.containsKey(id), "registered: " + id);
        }

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

    /**
     * Clears the frozen flag on the sound-event registry so ModSounds can register into it. Vanilla's
     * Bootstrap.bootStrap() freezes every built-in registry; under Fabric the registry-sync mixin holds
     * that freeze back for mod initializers, but a standalone check runs without any mixin, so the one
     * registry this check reads is reopened here. Test-only: it changes nothing the mod ships.
     */
    private static void deferSoundEventFreeze() {
        try {
            var frozen = MappedRegistry.class.getDeclaredField("frozen");
            frozen.setAccessible(true);
            frozen.set(BuiltInRegistries.SOUND_EVENT, false);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("cannot defer the built-in registry freeze", error);
        }
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
