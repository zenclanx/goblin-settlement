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
