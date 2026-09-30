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
            // isActive is the load-bearing third clause, and it is there for the volume slider. With the
            // Neutral or Master slider at zero, SoundEngine.play answers NOT_STARTED *without registering
            // the instance*, so the table would keep an instance the engine never took and unmuting would
            // never bring the hum back. "The engine does not have it" therefore has to read as "not
            // humming", or the golem stays silent until it unloads or changes tier. One-shot sounds do not
            // need this: each is a fresh instance, so each heals itself.
            if (existing != null && existing.suits(tier.orElseThrow())
                    && client.getSoundManager().isActive(existing)) {
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
