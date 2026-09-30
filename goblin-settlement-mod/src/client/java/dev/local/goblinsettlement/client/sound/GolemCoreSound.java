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
        // Positioned here as well as in tick(): AbstractSoundInstance starts at the world origin, and the
        // engine reads the position when it starts the sound, so without this a golem near (0, 0, 0) hums
        // from the origin for its first tick. Vanilla's entity-bound loops do the same -- see
        // EntityBoundSoundInstance, which copies x/y/z out of its entity in the constructor.
        this.x = golem.getX();
        this.y = golem.getY();
        this.z = golem.getZ();
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
