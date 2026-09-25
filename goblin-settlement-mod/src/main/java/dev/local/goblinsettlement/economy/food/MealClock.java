package dev.local.goblinsettlement.economy.food;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Active simulation time until the next communal meal. */
public final class MealClock extends SavedData {
    public static final long MEAL_INTERVAL_TICKS = 24000L;
    private static final Codec<MealClock> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.optionalFieldOf("settlement_id", "").forGetter(clock -> clock.settlementId),
            Codec.LONG.optionalFieldOf("active_ticks", 0L).forGetter(clock -> clock.activeTicks)
    ).apply(instance, MealClock::new));
    private static final SavedDataType<MealClock> TYPE = new SavedDataType<>(
            "goblin_settlement_meals", MealClock::new, CODEC, null);

    private String settlementId;
    private long activeTicks;

    public MealClock() {
        this("", 0L);
    }

    private MealClock(String settlementId, long activeTicks) {
        if (settlementId == null || activeTicks < 0 || activeTicks > MEAL_INTERVAL_TICKS) {
            throw new IllegalArgumentException("Invalid meal clock");
        }
        this.settlementId = settlementId;
        this.activeTicks = activeTicks;
    }

    public static MealClock get(ServerLevel level, String settlementId) {
        MealClock clock = level.getDataStorage().computeIfAbsent(TYPE);
        if (!clock.settlementId.equals(settlementId)) {
            clock.settlementId = settlementId;
            clock.activeTicks = 0;
            clock.setDirty();
        }
        return clock;
    }

    public void advance(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("Meal ticks cannot be negative");
        }
        long next = Math.min(MEAL_INTERVAL_TICKS, activeTicks + ticks);
        if (next != activeTicks) {
            activeTicks = next;
            setDirty();
        }
    }

    public boolean mealDue() {
        return activeTicks == MEAL_INTERVAL_TICKS;
    }

    public void finishMeal() {
        if (!mealDue()) {
            throw new IllegalStateException("No meal due");
        }
        activeTicks = 0;
        setDirty();
    }
}
