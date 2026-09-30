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

    private static final SoundEvent GOBLIN_MALE_GREETING = register("entity.goblin.male.greeting");
    private static final SoundEvent GOBLIN_MALE_RESPONSE = register("entity.goblin.male.response");
    private static final SoundEvent GOBLIN_MALE_WORK = register("entity.goblin.male.work");
    private static final SoundEvent GOBLIN_MALE_HURT = register("entity.goblin.male.hurt");
    private static final SoundEvent GOBLIN_MALE_DEATH = register("entity.goblin.male.death");
    private static final SoundEvent GOBLIN_FEMALE_GREETING = register("entity.goblin.female.greeting");
    private static final SoundEvent GOBLIN_FEMALE_RESPONSE = register("entity.goblin.female.response");
    private static final SoundEvent GOBLIN_FEMALE_WORK = register("entity.goblin.female.work");
    private static final SoundEvent GOBLIN_FEMALE_HURT = register("entity.goblin.female.hurt");
    private static final SoundEvent GOBLIN_FEMALE_DEATH = register("entity.goblin.female.death");
    private static final SoundEvent GOLEM_WOOD_MOVE = register("entity.golem.wood.move");
    private static final SoundEvent GOLEM_WOOD_CORE = register("entity.golem.wood.core");
    private static final SoundEvent GOLEM_STONE_MOVE = register("entity.golem.stone.move");
    private static final SoundEvent GOLEM_STONE_CORE = register("entity.golem.stone.core");
    private static final SoundEvent GOLEM_GOLD_MOVE = register("entity.golem.gold.move");
    private static final SoundEvent GOLEM_GOLD_CORE = register("entity.golem.gold.core");
    private static final SoundEvent GOLEM_DIAMOND_MOVE = register("entity.golem.diamond.move");
    private static final SoundEvent GOLEM_DIAMOND_CORE = register("entity.golem.diamond.core");
    private static final SoundEvent GOLEM_OBSIDIAN_MOVE = register("entity.golem.obsidian.move");
    private static final SoundEvent GOLEM_OBSIDIAN_CORE = register("entity.golem.obsidian.core");

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

    private static SoundEvent register(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(GoblinSettlement.MOD_ID, path);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    /** Touches the class so the static registrations above run during mod init. */
    public static void initialize() {
    }
}
