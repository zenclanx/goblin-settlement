package dev.local.goblinsettlement.economy.food;

import java.util.OptionalInt;

/**
 * How long the known public food lasts, in active simulation minutes.
 *
 * <p>Derived, not invented: MealCoordinator eats one item per living resident every
 * MealClock.MEAL_INTERVAL_TICKS, so whole meals times that interval is the answer. The interval is
 * read from MealClock rather than restated here, because a second copy of 24000 would be a second
 * truth.
 */
public final class FoodForecast {
    /** Minecraft runs at this many ticks per minute, so the meal interval divides into minutes. */
    public static final int TICKS_PER_MINUTE = 1200;

    private FoodForecast() {
    }

    /**
     * Whole meals still in stock times the meal interval. Empty when nobody eats: the question has no
     * answer rather than the answer zero, and a panel that says "0 minutes" would be lying.
     */
    public static OptionalInt minutes(long food, int diners) {
        if (food < 0 || diners < 0) {
            throw new IllegalArgumentException("Food and diners cannot be negative");
        }
        if (diners == 0) {
            return OptionalInt.empty();
        }
        // Clamp on the meal count before multiplying: meals * 20 can overflow a long for an absurd
        // stock, and an overflowed negative would slip past the Math.min below.
        long minutesPerMeal = MealClock.MEAL_INTERVAL_TICKS / TICKS_PER_MINUTE;
        long meals = food / diners;
        if (meals > Integer.MAX_VALUE / minutesPerMeal) {
            return OptionalInt.of(Integer.MAX_VALUE);
        }
        return OptionalInt.of((int) Math.min(Integer.MAX_VALUE, meals * minutesPerMeal));
    }
}
